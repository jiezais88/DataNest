package com.datanest.dataservice.ws;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanest.dataservice.entity.ApiKey;
import com.datanest.dataservice.mapper.ApiKeyMapper;
import com.datanest.dataservice.service.ApiKeyService;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;

/**
 * WebSocket 握手拦截器（F4，AC-10）：握手时校验 X-API-Key 头 → SHA-256 命中启用 Key。
 * <p>
 * 无 Key / 错 Key / 禁用 Key 返回 401 拒绝握手；通过则把 keyId 写入 session attributes 供订阅校验复用。
 * Key-管道绑定校验在 subscribe 消息时做（握手时尚未指定管道）。
 * <p>
 * 浏览器 WebSocket 不支持自定义 Header，支持 query 参数 {@code ?apiKey=K-xxx} 作为 fallback。
 */
@Component
public class WsHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_KEY_ID = "ws.keyId";
    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String API_KEY_QUERY_PARAM = "apiKey";

    private final ApiKeyMapper apiKeyMapper;

    public WsHandshakeInterceptor(ApiKeyMapper apiKeyMapper) {
        this.apiKeyMapper = apiKeyMapper;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String rawKey = resolveApiKey(request);
        if (rawKey == null || rawKey.isBlank()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        ApiKey key = apiKeyMapper.selectOne(new QueryWrapper<ApiKey>()
                .eq("key_hash", ApiKeyService.sha256Hex(rawKey.trim()))
                .eq("status", ApiKey.STATUS_ENABLED));
        if (key == null) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(ATTR_KEY_ID, key.getId());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 无后续处理
    }

    /** 优先读 X-API-Key header（wscat / 服务端调用），降级读 ?apiKey= query param（浏览器 WebSocket） */
    private String resolveApiKey(ServerHttpRequest request) {
        List<String> header = request.getHeaders().get(API_KEY_HEADER);
        if (header != null && !header.isEmpty() && header.get(0) != null && !header.get(0).isBlank()) {
            return header.get(0);
        }
        String query = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams()
                .getFirst(API_KEY_QUERY_PARAM);
        return query;
    }
}
