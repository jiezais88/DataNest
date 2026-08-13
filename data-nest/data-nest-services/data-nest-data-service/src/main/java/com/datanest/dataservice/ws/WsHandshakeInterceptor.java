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

import java.util.List;
import java.util.Map;

/**
 * WebSocket 握手拦截器（F4，AC-10）：握手时校验 X-API-Key 头 → SHA-256 命中启用 Key。
 * <p>
 * 无 Key / 错 Key / 禁用 Key 返回 401 拒绝握手；通过则把 keyId 写入 session attributes 供订阅校验复用。
 * Key-管道绑定校验在 subscribe 消息时做（握手时尚未指定管道）。
 */
@Component
public class WsHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_KEY_ID = "ws.keyId";
    private static final String API_KEY_HEADER = "X-API-Key";

    private final ApiKeyMapper apiKeyMapper;

    public WsHandshakeInterceptor(ApiKeyMapper apiKeyMapper) {
        this.apiKeyMapper = apiKeyMapper;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        List<String> keyHeader = request.getHeaders().get(API_KEY_HEADER);
        if (keyHeader == null || keyHeader.isEmpty() || keyHeader.get(0) == null || keyHeader.get(0).isBlank()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        ApiKey key = apiKeyMapper.selectOne(new QueryWrapper<ApiKey>()
                .eq("key_hash", ApiKeyService.sha256Hex(keyHeader.get(0).trim()))
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
}
