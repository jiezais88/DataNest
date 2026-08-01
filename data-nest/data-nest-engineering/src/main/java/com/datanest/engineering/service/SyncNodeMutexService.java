package com.datanest.engineering.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.task.core.entity.SyncJob;
import com.datanest.task.core.entity.SyncJobHistory;
import com.datanest.task.core.mapper.SyncJobHistoryMapper;
import com.datanest.task.core.mapper.SyncJobMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * DAG 同步任务节点互斥锁
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
    private final SyncJobMapper syncJobMapper;
    private final SyncJobHistoryMapper syncJobHistoryMapper;

    public SyncNodeMutexService(StringRedisTemplate redisTemplate,
                                SyncJobMapper syncJobMapper,
                                SyncJobHistoryMapper syncJobHistoryMapper) {
        this.redisTemplate = redisTemplate;
        this.syncJobMapper = syncJobMapper;
        this.syncJobHistoryMapper = syncJobHistoryMapper;
    }

    /**
     * 尝试获取锁；获取成功返回 token，失败抛 DAG_ALREADY_RUNNING。
     * 发现 Redis 中存在锁但底层 sync_job/sync_job_history 没有 RUNNING 记录时，
     * 判定为残留锁并强制清理后重试，避免上次执行异常导致 6h 内后续 DAG 执行被永远阻塞。
     */
    public String tryLock(Long syncJobId) {
        String key = KEY_PREFIX + syncJobId;
        String token = java.util.UUID.randomUUID().toString();
        Boolean ok = redisTemplate.opsForValue().setIfAbsent(key, token, DEFAULT_TTL);
        if (Boolean.TRUE.equals(ok)) {
            logger.debug("获取同步任务互斥锁: syncJobId={}, token={}", syncJobId, token);
            return token;
        }

        // 锁已存在：检查是否残留
        if (!hasActiveSyncJob(syncJobId)) {
            logger.warn("同步任务互斥锁疑似残留（无 RUNNING 记录），强制清理后重试: syncJobId={}", syncJobId);
            redisTemplate.delete(key);
            ok = redisTemplate.opsForValue().setIfAbsent(key, token, DEFAULT_TTL);
            if (Boolean.TRUE.equals(ok)) {
                logger.info("成功清理残留锁并获取锁: syncJobId={}, token={}", syncJobId, token);
                return token;
            }
        }

        logger.warn("同步任务互斥锁冲突: syncJobId={} 已有执行实例", syncJobId);
        throw new BusinessException(ErrorCode.DAG_ALREADY_RUNNING,
                "同步任务 " + syncJobId + " 已有执行实例在跑");
    }

    private boolean hasActiveSyncJob(Long syncJobId) {
        if (syncJobId == null) return false;
        SyncJob job = syncJobMapper.selectById(syncJobId);
        if (job != null && "RUNNING".equalsIgnoreCase(job.getExecutionStatus())) {
            return true;
        }
        Long runningHistories = syncJobHistoryMapper.selectCount(
                new QueryWrapper<SyncJobHistory>()
                        .eq("sync_job_id", syncJobId)
                        .eq("status", "RUNNING"));
        return runningHistories != null && runningHistories > 0;
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
     * 决策 Sprint3-Fix4：按 syncJobId 强释放（不走 token 校验）。
     * 用途：DagExecutionSyncService 收尾时通过 SPI 调；callback 不再 finally 释放（避免锁窗口太短测不到冲突）。
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
