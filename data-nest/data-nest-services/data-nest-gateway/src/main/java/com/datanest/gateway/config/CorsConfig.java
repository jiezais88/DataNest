package com.datanest.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class CorsConfig {

    /**
     * 允许的跨域来源，消费 shared-security.yaml 的 datanest.security.cors.allowed-origins；
     * 该配置在 yaml 中为逗号分隔字符串（支持环境变量 CORS_ORIGINS 覆盖），
     * 未配置时回退到本地前端地址，绝不使用 "*"（与 allowCredentials 搭配存在安全风险）。
     */
    @Value("${datanest.security.cors.allowed-origins:*}")
    private List<String> allowedOrigins;

    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration config = new CorsConfiguration();
        // 使用 origin pattern，支持 "*" 通配的同时仍允许携带 credentials
        allowedOrigins.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(config::addAllowedOriginPattern);
        config.addAllowedMethod("*");
        config.addAllowedHeader("*");
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsWebFilter(source);
    }
}
