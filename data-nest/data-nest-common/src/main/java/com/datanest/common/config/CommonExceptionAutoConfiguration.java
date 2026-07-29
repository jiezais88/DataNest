package com.datanest.common.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 公共全局异常处理器自动配置。
 * <p>
 * 将 {@link GlobalExceptionHandler} 注入到所有消费 data-nest-common 的微服务中，
 * 确保 {@link com.datanest.common.exception.BusinessException} 等业务异常被统一包装为 Result。
 */
@AutoConfiguration
public class CommonExceptionAutoConfiguration {

    @Bean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }
}
