package com.datanest.dataservice.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Key 级 QPS 限流（Sprint 10 F3，Redis ZSET 滑动窗口）。
 * <p>
 * 粒度 = Key 级（api_key.qps_limit，该 Key 下所有 API 共享一个窗口，对齐 PRD 6.4 + AC-7）。
 * 每次请求在 {@code datanest:ratelimit:{keyId}} 写入当前时间戳成员，窗口内成员数 ≥ QPS 上限即拒绝；
 * 先清理窗口外过期成员（ZREMRANGEBYSCORE）再计数（ZCARD）。
 * <p>
 * 注：removeRange + zCard + add 非原子，极端并发下可能少量超发；验收口径（顺序请求第 N 次 429）下准确。
 */
@Service
@RefreshScope
public class RateLimitService {

    private static final String KEY_PREFIX = "datanest:ratelimit:";

    private final StringRedisTemplate redisTemplate;

    @Value("${datanest.dataservice.ratelimit.window-seconds:60}")
    private int windowSeconds;

    public RateLimitService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 尝试获取一次调用许可。
     *
     * @param keyId    API Key ID
     * @param qpsLimit Key 级 QPS 上限（≤0 视为不限流）
     * @return true 放行；false 超限（调用方返回 429 + Retry-After）
     */
    public boolean tryAcquire(Long keyId, int qpsLimit) {
        if (keyId == null || qpsLimit <= 0) {
            return true;
        }
        long now = System.currentTimeMillis();
        long windowMillis = windowSeconds * 1000L;
        String key = KEY_PREFIX + keyId;

        // 清理窗口外过期成员（score < now - windowMillis）
        redisTemplate.opsForZSet().removeRangeByScore(key, 0, now - windowMillis);
        Long count = redisTemplate.opsForZSet().zCard(key);
        if (count != null && count >= qpsLimit) {
            return false;
        }
        // 写入当前请求成员（UUID 保证同毫秒成员唯一）
        redisTemplate.opsForZSet().add(key, now + ":" + UUID.randomUUID(), now);
        return true;
    }

    /** 限流窗口秒数（超限时作为 Retry-After 秒数） */
    public int windowSeconds() {
        return windowSeconds;
    }
}
