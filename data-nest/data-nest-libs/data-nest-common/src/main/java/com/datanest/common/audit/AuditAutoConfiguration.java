package com.datanest.common.audit;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 审计日志切面自动配置（Sprint 11 F1）。
 * <p>
 * 提供异步落库线程池 + 切面 bean；仅当消费方 classpath 同时存在 AspectJ 与 Sa-Token（有 AOP + 登录态能力）时装配，
 * 无 AOP 依赖的服务（gateway/worker/job）自然跳过。切面经 {@link ObjectProvider} 引用 Recorder，
 * 服务未提供 {@link AuditLogRecorder} Bean 时静默跳过（fail-open）。
 * <p>
 * 线程池采用有界队列 + CallerRunsPolicy：队列满时降级为调用线程同步写入，避免 system 故障时无限堆积内存。
 */
@AutoConfiguration
@ConditionalOnClass(name = {"org.aspectj.lang.annotation.Aspect", "cn.dev33.satoken.stp.StpUtil"})
public class AuditAutoConfiguration {

    @Bean(name = "auditLogExecutor")
    @ConditionalOnMissingBean(name = "auditLogExecutor")
    public ExecutorService auditLogExecutor() {
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "audit-log-" + seq.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        };
        return new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1024), factory, new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditLogAspect auditLogAspect(ObjectProvider<AuditLogRecorder> recorderProvider,
                                         @org.springframework.beans.factory.annotation.Qualifier("auditLogExecutor") ExecutorService executor) {
        return new AuditLogAspect(recorderProvider, executor);
    }
}
