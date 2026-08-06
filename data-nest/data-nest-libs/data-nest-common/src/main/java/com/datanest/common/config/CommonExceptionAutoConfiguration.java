package com.datanest.common.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * 公共全局异常处理器自动配置。
 * <p>
 * 将 {@link GlobalExceptionHandler} 注入到所有消费 data-nest-common 的微服务中，
 * 确保 {@link com.datanest.common.exception.BusinessException} 等业务异常被统一包装为 Result。
 * <p>
 * {@link GlobalExceptionHandler} 是 Servlet(MVC) 专属的 {@code @RestControllerAdvice}，
 * 其异常处理器引用了 {@code org.springframework.web.servlet.*} 类型（如
 * {@code NoResourceFoundException}），在 WebFlux 网关下不存在，会导致启动失败。
 * 故仅在 Servlet 应用注册，网关(WebFlux)不注册。
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CommonExceptionAutoConfiguration {

    @Bean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }
}
