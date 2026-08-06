package com.datanest.common.internal;

import feign.Retryer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

/**
 * Feign 全局重试器：初始间隔 100ms、最大间隔 1s、最多 3 次尝试。
 * <p>
 * 只对 IOException / {@link feign.RetryableException}（含 ErrorDecoder 对 503 的转换）生效；
 * 业务错误（{@code BusinessException}）不重试。
 * <p>
 * 仅当 classpath 存在 Feign 时装配，与 {@link InternalTokenFeignInterceptor} 同样的装配方式。
 */
@Component
@ConditionalOnClass(name = "feign.Retryer")
public class InternalFeignRetryer extends Retryer.Default {

    public InternalFeignRetryer() {
        super(100, 1000, 3);
    }
}
