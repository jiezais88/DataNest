package com.datanest.common.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.cloud.openfeign.FeignClientFactory;
import org.springframework.cloud.openfeign.support.FeignHttpMessageConverters;
import org.springframework.stereotype.Component;

/**
 * Feign 消息转换器启动预热。
 * <p>
 * 背景：spring-cloud-openfeign 已知并发缺陷（上游 issue #1307）——
 * {@link FeignHttpMessageConverters#getConverters} 在**类未初始化**时被并发调用会返回
 * 未初始化完成的转换器列表（空列表/含 null 元素），报
 * {@code 'messageConverters' must not be empty} / {@code must not contain null elements} /
 * {@code no suitable HttpMessageConverter found}。初始化完成后不再出现。
 * 实测症状：worker 重启后首个 DAG 执行，多个节点回调并发触发 Feign 首调时偶发一次。
 * 该异常若命中 fail-fast 路径（如执行登记）会误杀一次执行。
 * <p>
 * 修复（官方 workaround 的 5.x 版）：注意 5.x 中 FeignHttpMessageConverters 是
 * **每个 client 子上下文各一个**（FeignClientsConfiguration 内声明），主容器没有该 bean，
 * 因此必须遍历所有 client 子上下文逐个初始化；且仅取 Decoder bean 不会触发初始化，
 * 必须显式调用 getConverters()。
 */
@Component
@ConditionalOnClass(FeignClientFactory.class)
public class FeignContextWarmup implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(FeignContextWarmup.class);

    private final FeignClientFactory feignClientFactory;

    public FeignContextWarmup(FeignClientFactory feignClientFactory) {
        this.feignClientFactory = feignClientFactory;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (String name : feignClientFactory.getContextNames()) {
            try {
                // 触发 per-client 子上下文创建，并显式初始化其 FeignHttpMessageConverters
                // （上游 issue #1307 workaround：getConverters() 单线程完成后并发调用即安全）
                FeignHttpMessageConverters converters =
                        feignClientFactory.getInstance(name, FeignHttpMessageConverters.class);
                if (converters != null) {
                    int size = converters.getConverters().size();
                    logger.info("Feign 消息转换器预热完成: {}（{} 个转换器）", name, size);
                }
            } catch (Exception e) {
                // 预热失败不影响启动（保留原竞态风险，但主流程可用）
                logger.warn("Feign 消息转换器预热失败: {}, error={}", name, e.getMessage());
            }
        }
    }
}
