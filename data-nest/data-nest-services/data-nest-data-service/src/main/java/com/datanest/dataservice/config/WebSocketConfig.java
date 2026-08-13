package com.datanest.dataservice.config;

import com.datanest.dataservice.ws.WsEventsHandler;
import com.datanest.dataservice.ws.WsHandshakeInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 配置（F4）：注册 {@code /ws/events} 端点 + 握手 Key 校验拦截器。
 * <p>
 * 网关对 ws:// 握手请求按 `/api/data-service/ws/**` 路由放行（SaTokenConfig 已放行），
 * 服务内 servletPath 为 `/ws/events`。Key 认证在握手拦截器（401 拒连），不依赖登录态。
 * {@code @EnableKafka} 显式启用 {@code @KafkaListener}（Spring Boot 4 自动配置未覆盖该注解驱动）。
 */
@Configuration
@EnableWebSocket
@EnableKafka
public class WebSocketConfig implements WebSocketConfigurer {

    private final WsEventsHandler eventsHandler;
    private final WsHandshakeInterceptor handshakeInterceptor;

    public WebSocketConfig(WsEventsHandler eventsHandler, WsHandshakeInterceptor handshakeInterceptor) {
        this.eventsHandler = eventsHandler;
        this.handshakeInterceptor = handshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(eventsHandler, "/ws/events")
                .addInterceptors(handshakeInterceptor)
                .setAllowedOriginPatterns("*");
    }
}
