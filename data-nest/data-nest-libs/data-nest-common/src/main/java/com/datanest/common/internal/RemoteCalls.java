package com.datanest.common.internal;

import io.micrometer.core.instrument.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * 远程调用统一降级入口。
 * <p>
 * 异常（含 {@code BusinessException}，熔断 fallback 之外残留的序列化/框架异常）时记 warn 日志、
 * Micrometer 计数（{@code remote_call_failed_total}，tag {@code target}=description）并返回降级值，
 * 替代各业务类手写的 try-catch 样板，统一降级语义。
 * <p>
 * fail-closed 场景（删除前引用校验等必须让异常传播的调用）不要使用本包装。
 */
public final class RemoteCalls {

    private static final Logger logger = LoggerFactory.getLogger(RemoteCalls.class);
    private static final String METRIC_NAME = "remote_call_failed_total";

    private RemoteCalls() {
    }

    /**
     * 有返回值的降级调用：异常时 warn 日志 + 计数并返回 fallback。
     *
     * @param description 调用短描述（指标 tag，如 "alert.fire"、"system.usernames"）
     */
    public static <T> T execute(String description, Supplier<T> supplier, T fallback) {
        try {
            return supplier.get();
        } catch (Exception e) {
            onFailure(description, e);
            return fallback;
        }
    }

    /** 无返回值的容错调用：异常时 warn 日志 + 计数 */
    public static void execute(String description, Runnable runnable) {
        try {
            runnable.run();
        } catch (Exception e) {
            onFailure(description, e);
        }
    }

    private static void onFailure(String description, Exception e) {
        logger.warn("远程调用失败，按降级处理: target={}, error={}", description, e.getMessage());
        Metrics.globalRegistry.counter(METRIC_NAME, "target", description).increment();
    }
}
