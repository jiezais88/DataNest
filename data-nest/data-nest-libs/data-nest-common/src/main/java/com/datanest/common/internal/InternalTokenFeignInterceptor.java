package com.datanest.common.internal;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Feign 内部调用令牌拦截器。
 * <p>
 * 为所有 Feign 请求附带 {@code X-Internal-Token} 头（值取配置 {@code datanest.internal.token}），
 * 与服务端的 {@link InternalTokenFilter} 配对完成服务间内部调用鉴权；令牌为空则不加头。
 * <p>
 * 仅当 classpath 存在 Feign 时装配，避免无 Feign 依赖的服务（如 gateway）误装配。
 */
@Component
@ConditionalOnClass(name = "feign.RequestInterceptor")
public class InternalTokenFeignInterceptor implements RequestInterceptor {

    /** 内部调用令牌，空则不给 Feign 请求加头 */
    @Value("${datanest.internal.token:}")
    private String internalToken;

    @Override
    public void apply(RequestTemplate template) {
        if (StringUtils.hasText(internalToken)) {
            template.header(InternalTokenFilter.INTERNAL_TOKEN_HEADER, internalToken);
        }
    }
}
