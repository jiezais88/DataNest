package com.datanest.task.core.service;

import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * DAG 同步任务节点互斥锁。
 * Sprint 4 下沉到 task-core，供 engineering 与 worker 共用。
 * 决策 ADR-S3-006：同一 syncJobId 在任意时刻只能有一个执行实例
 * 用 Redis SETNX 实现（lock_key = "datanest:dag:sync-lock:{syncJobId}"）
 */
@Service
public class SyncNodeMutexService {

    private static final Logger logger = LoggerFactory.getLogger(SyncNodeMutexService.class);
    private static final String KEY_PREFIX = "datanest:dag:sync-lock:";
    /** 默认 6 小时过期（防止死锁） */
    private static final Duration DEFAULT_TTL = Duration.ofHours(6);

    private final StringRedisTemplate redisTemplate;

    public SyncNodeMutexService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 尝试获取锁；获取成功返回 token，失败抛 DAG_ALREADY_RUNNING。
     */
    public String tryLock(Long syncJobId) {
        String key = KEY_PREFIX + syncJobId;
        String token = java.util.UUID.randomUUID().toString();
        Boolean ok = redisTemplate.opsForValue().setIfAbsent(key, token, DEFAULT_TTL);
        if (Boolean.TRUE.equals(ok)) {
            logger.debug("获取同步任务互斥锁: syncJobId={}, token={}", syncJobId, token);
            return token;
        }

        logger.warn("同步任务互斥锁冲突: syncJobId={} 已有执行实例", syncJobId);
        throw new BusinessException(ErrorCode.DAG_ALREADY_RUNNING,
                "同步任务 " + syncJobId + " 已有执行实例在跑");
    }

    /**
     * 释放锁：仅当 token 匹配才删除（避免误删别人的锁）
     */
    public void unlock(Long syncJobId, String token) {
        if (syncJobId == null || token == null) return;
        String key = KEY_PREFIX + syncJobId;
        String current = redisTemplate.opsForValue().get(key);
        if (token.equals(current)) {
            redisTemplate.delete(key);
            logger.debug("释放同步任务互斥锁: syncJobId={}, token={}", syncJobId, token);
        } else {
            logger.warn("互斥锁 token 不匹配，跳过释放: syncJobId={}, expected={}, actual={}",
                    syncJobId, token, current);
        }
    }

    /**
     * 按 syncJobId 强释放（不走 token 校验）。
     * 用途：DagExecutionSyncService SPI 收尾时调用。
     * 安全性：syncJobId 本身具有唯一性，按 id 释放不会误删其他 syncJob 的锁。
     */
    public void unlockBySyncJobId(Long syncJobId) {
        if (syncJobId == null) return;
        String key = KEY_PREFIX + syncJobId;
        Boolean deleted = redisTemplate.delete(key);
        if (Boolean.TRUE.equals(deleted)) {
            logger.debug("SPI 强释放同步任务互斥锁: syncJobId={}", syncJobId);
        }
    }

    /**
     * 查询当前是否有锁（不获取）
     */
    public boolean isLocked(Long syncJobId) {
        String key = KEY_PREFIX + syncJobId;
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }
}
