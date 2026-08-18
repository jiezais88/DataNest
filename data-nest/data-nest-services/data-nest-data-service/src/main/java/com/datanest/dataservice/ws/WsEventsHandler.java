package com.datanest.dataservice.ws;

import com.datanest.common.exception.BusinessException;
import com.datanest.common.json.JsonUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;

/**
 * WebSocket 实时订阅处理器（F4，PRD §6.6）：连接后收 subscribe/unsubscribe 消息绑定管道，
 * 变更事件由 {@link KafkaEventConsumer} 经 {@link WebSocketSubscriptionRegistry} fan-out 推送。
 * <p>
 * 协议：客户端 → {@code {"op":"subscribe","pipelineId":12}}；服务端回 {@code {"op":"subscribed",...}} /
 * {@code {"op":"error","code":9005,"message":"..."}}。心跳 60s ping/pong 由 Spring WebSocket 原生支持
 * （客户端可发 ping，服务端自动 pong），空闲断开由网关/客户端侧控制。
 */
@Component
public class WsEventsHandler extends TextWebSocketHandler {

    private final WebSocketSubscriptionRegistry registry;
    private final WsSubscriptionService subscriptionService;

    public WsEventsHandler(WebSocketSubscriptionRegistry registry,
                           WsSubscriptionService subscriptionService) {
        this.registry = registry;
        this.subscriptionService = subscriptionService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        // 握手拦截器已校验 Key；防御性校验（理论上不会走到）
        if (session.getAttributes().get(WsHandshakeInterceptor.ATTR_KEY_ID) == null) {
            session.close(CloseStatus.POLICY_VIOLATION);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String op;
        Long pipelineId;
        try {
            tools.jackson.databind.node.ObjectNode msg = JsonUtils.parseObject(message.getPayload());
            op = JsonUtils.getString(msg, "op");
            pipelineId = JsonUtils.getLong(msg, "pipelineId");
        } catch (Exception e) {
            sendError(session, 9008, "消息格式非法，应为 {\"op\":\"subscribe\",\"pipelineId\":N}");
            return;
        }
        switch (op == null ? "" : op) {
            case "subscribe" -> handleSubscribe(session, pipelineId);
            case "unsubscribe" -> handleUnsubscribe(session, pipelineId);
            default -> sendError(session, 9008, "不支持的操作: " + op);
        }
    }

    private void handleSubscribe(WebSocketSession session, Long pipelineId) {
        if (pipelineId == null) {
            sendError(session, 9008, "缺少 pipelineId");
            return;
        }
        Long keyId = (Long) session.getAttributes().get(WsHandshakeInterceptor.ATTR_KEY_ID);
        try {
            subscriptionService.checkSubscribe(keyId, pipelineId);
            registry.subscribe(pipelineId, session);
            send(session, JsonUtils.toJSONString(Map.of("op", "subscribed", "pipelineId", pipelineId)));
        } catch (BusinessException e) {
            sendError(session, e.getErrorCode().getCode(), e.getMessage());
        }
    }

    private void handleUnsubscribe(WebSocketSession session, Long pipelineId) {
        if (pipelineId != null) {
            registry.unsubscribe(pipelineId, session);
        }
        send(session, JsonUtils.toJSONString(Map.of("op", "unsubscribed", "pipelineId", pipelineId)));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        registry.removeSession(session);
    }

    private void sendError(WebSocketSession session, int code, String message) {
        send(session, JsonUtils.toJSONString(Map.of("op", "error", "code", code, "message", message)));
    }

    private void send(WebSocketSession session, String json) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(json));
            }
        } catch (IOException e) {
            // 发送失败（连接已断）忽略；fan-out 时由 registry.removeSession 清理
        }
    }
}
