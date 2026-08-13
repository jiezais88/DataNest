package com.datanest.dataservice.ws;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 订阅事件内存埋点（F4 连接监控）：按管道统计今日事件数 / 推送失败数 / 端到端延迟 P95，
 * 并按订阅方 Key 统计接收事件数与最近事件时间。
 * <p>
 * 内存态（进程重启丢失），仅作实时订阅的瞬时运维观察，不落库、不审计（对齐 PRD NG9 不落库）。
 * 「今日」语义：跨天自动重置管道与各 Key 计数。延迟采样为固定窗口环形数组，仅保留最近 N 个样本。
 */
@Component
public class SubscriptionMetrics {

    /** 延迟 P95 采样窗口容量（仅保留最近 N 个样本） */
    private static final int LATENCY_WINDOW = 1024;

    private final Map<Long, PipelineMetrics> pipelines = new ConcurrentHashMap<>();

    /** 记录一次成功送达：管道事件数/延迟采样 + Key 接收事件数/最近时间 */
    public void recordEvent(Long pipelineId, long latencyMs, Long keyId) {
        pipelines.computeIfAbsent(pipelineId, k -> new PipelineMetrics()).recordEvent(latencyMs, keyId);
    }

    /** 记录一次 fan-out 发送失败（连接已断） */
    public void recordFailure(Long pipelineId) {
        pipelines.computeIfAbsent(pipelineId, k -> new PipelineMetrics()).recordFailure();
    }

    /** 管道统计快照（无数据返回零值空快照） */
    public PipelineSnapshot snapshot(Long pipelineId) {
        PipelineMetrics m = pipelines.get(pipelineId);
        return m == null ? PipelineSnapshot.empty() : m.snapshot();
    }

    /** 管道维度统计快照 */
    public static final class PipelineSnapshot {
        private final long todayEvents;
        private final long failedSends;
        private final long p95Ms;
        private final long lastEventAt;
        private final Map<Long, KeyMetrics> keys;

        PipelineSnapshot(long todayEvents, long failedSends, long p95Ms, long lastEventAt,
                         Map<Long, KeyMetrics> keys) {
            this.todayEvents = todayEvents;
            this.failedSends = failedSends;
            this.p95Ms = p95Ms;
            this.lastEventAt = lastEventAt;
            this.keys = keys;
        }

        static PipelineSnapshot empty() {
            return new PipelineSnapshot(0, 0, 0, 0, Map.of());
        }

        public long getTodayEvents() {
            return todayEvents;
        }

        public long getFailedSends() {
            return failedSends;
        }

        public long getP95Ms() {
            return p95Ms;
        }

        public long getLastEventAt() {
            return lastEventAt;
        }

        public Map<Long, KeyMetrics> getKeys() {
            return keys;
        }
    }

    /** 订阅方 Key 维度统计（接收事件数 + 最近事件时间） */
    public static final class KeyMetrics {
        private final AtomicLong receivedEvents = new AtomicLong();
        private final AtomicLong lastEventAt = new AtomicLong();

        public long getReceivedEvents() {
            return receivedEvents.get();
        }

        public long getLastEventAt() {
            return lastEventAt.get();
        }
    }

    /** 单管道指标（并发安全：Atomic + 环形采样） */
    private static final class PipelineMetrics {
        private final AtomicLong todayEvents = new AtomicLong();
        private final AtomicLong failedSends = new AtomicLong();
        private final AtomicLong lastEventAt = new AtomicLong();
        private final AtomicReference<LocalDate> day = new AtomicReference<>(LocalDate.now());
        private final long[] latencyWindow = new long[LATENCY_WINDOW];
        private final AtomicInteger latencyIdx = new AtomicInteger();
        private final AtomicInteger latencyCount = new AtomicInteger();
        private final Map<Long, KeyMetrics> keys = new ConcurrentHashMap<>();

        void recordEvent(long latencyMs, Long keyId) {
            rolloverDay();
            todayEvents.incrementAndGet();
            lastEventAt.set(System.currentTimeMillis());
            int idx = latencyIdx.getAndUpdate(i -> (i + 1) % LATENCY_WINDOW);
            latencyWindow[idx] = Math.max(latencyMs, 0);
            latencyCount.updateAndGet(c -> Math.min(c + 1, LATENCY_WINDOW));
            if (keyId != null) {
                KeyMetrics km = keys.computeIfAbsent(keyId, k -> new KeyMetrics());
                km.receivedEvents.incrementAndGet();
                km.lastEventAt.set(System.currentTimeMillis());
            }
        }

        void recordFailure() {
            rolloverDay();
            failedSends.incrementAndGet();
        }

        /** 跨天重置今日计数（管道 + 各 Key） */
        private void rolloverDay() {
            LocalDate today = LocalDate.now();
            if (!day.get().equals(today)) {
                day.set(today);
                todayEvents.set(0);
                failedSends.set(0);
                keys.values().forEach(k -> k.receivedEvents.set(0));
            }
        }

        PipelineSnapshot snapshot() {
            int count = Math.min(latencyCount.get(), LATENCY_WINDOW);
            long p95 = 0;
            if (count > 0) {
                long[] sorted = Arrays.copyOf(latencyWindow, count);
                Arrays.sort(sorted);
                p95 = sorted[(int) Math.ceil(count * 0.95) - 1];
            }
            return new PipelineSnapshot(todayEvents.get(), failedSends.get(), p95,
                    lastEventAt.get(), keys);
        }
    }
}
