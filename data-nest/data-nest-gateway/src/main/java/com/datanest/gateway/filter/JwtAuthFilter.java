package com.datanest.gateway.filter;

import cn.dev33.satoken.stp.StpUtil;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 全局过滤器：从 Redis 校验 Token，透传 X-User-Id 给下游。
 * 仅做透传，鉴权由 SaReactorFilter 负责。
 */
@Component
public class JwtAuthFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String token = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (token == null || token.isEmpty()) {
            return chain.filter(exchange);
        }

        if (token.startsWith("Bearer ")) {
            token = token.substring(7);
        }

        Object loginId = StpUtil.getLoginIdByToken(token);
        if (loginId == null) {
            return chain.filter(exchange);
        }

        // 透传 X-User-Id 给下游微服务
        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .header("X-User-Id", String.valueOf(loginId))
                .build();

        return chain.filter(exchange.mutate().request(mutated).build());
    }

    @Override
    public int getOrder() {
        return -200;
    }
}
