package com.datanest.dataservice.service;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * 数据源维度熔断（Sprint 10 F3，Resilience4j）。
 * <p>
 * 对外 API 查询按数据源（datasourceId，内置 Doris=-1 也按 -1 一个维度）建立独立熔断器，
 * 连续失败（查询超时/连接失败/SQL 执行错误）达到阈值即开闸（对外返回 503「数据源暂不可用」），
 * 半开探测 1 个请求通过后自动闭合。
 * <p>
 * 计数窗口 = failure-threshold，失败率阈值 50%（即窗口内 ≥ 半数为失败即开闸，贴合「连续失败」语义）。
 */
@Service
public class CircuitBreakerService {

    private final ConcurrentMap<Long, CircuitBreaker> breakers = new ConcurrentHashMap<>();

    @Value("${datanest.dataservice.circuitbreaker.failure-threshold:5}")
    private int failureThreshold;

    @Value("${datanest.dataservice.circuitbreaker.wait-seconds:30}")
    private int waitSeconds;

    private CircuitBreakerConfig config;

    @PostConstruct
    public void init() {
        int threshold = Math.max(failureThreshold, 1);
        this.config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(threshold)
                .minimumNumberOfCalls(threshold)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(Math.max(waitSeconds, 1)))
                .permittedNumberOfCallsInHalfOpenState(1)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();
    }

    /**
     * 尝试获取一次执行许可：CLOSED 恒放行；OPEN 拒绝；HALF_OPEN 允许 1 个探测请求。
     *
     * @return true 可执行；false 开闸（调用方返回 503）
     */
    public boolean tryAcquire(Long datasourceId) {
        return breaker(datasourceId).tryAcquirePermission();
    }

    /** 记录一次成功执行（耗时仅用于熔断器统计，不计入开闸阈值判定） */
    public void recordSuccess(Long datasourceId, long durationMs) {
        breaker(datasourceId).onSuccess(durationMs, TimeUnit.MILLISECONDS);
    }

    /** 记录一次失败执行（抛出的业务异常），供计数窗口统计 */
    public void recordFailure(Long datasourceId, long durationMs, Throwable cause) {
        breaker(datasourceId).onError(durationMs, TimeUnit.MILLISECONDS,
                cause == null ? new IllegalStateException("data source query failed") : cause);
    }

    private CircuitBreaker breaker(Long datasourceId) {
        return breakers.computeIfAbsent(datasourceId, id -> CircuitBreaker.of("ds-" + id, config));
    }
}
