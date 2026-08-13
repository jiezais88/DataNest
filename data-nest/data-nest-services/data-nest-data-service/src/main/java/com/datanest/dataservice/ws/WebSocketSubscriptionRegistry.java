package com.datanest.dataservice.ws;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 订阅注册表（F4）：pipelineId → 订阅连接集合，Kafka 事件 fan-out 用。
 * <p>
 * 双向索引：{@code subscriptions}（pipelineId → sessions，分发查）+ {@code sessionPipelines}
 * （sessionId → pipelineIds，断开时反向清理）。全部并发安全（ConcurrentHashMap + keySet）。
 */
@Component
public class WebSocketSubscriptionRegistry {

    /** pipelineId → 订阅该管道的 WebSocket 会话集合 */
    private final Map<Long, Set<WebSocketSession>> subscriptions = new ConcurrentHashMap<>();

    /** sessionId → 该会话订阅的 pipelineId 集合（断开反向清理用） */
    private final Map<String, Set<Long>> sessionPipelines = new ConcurrentHashMap<>();

    public void subscribe(Long pipelineId, WebSocketSession session) {
        subscriptions.computeIfAbsent(pipelineId, k -> ConcurrentHashMap.newKeySet()).add(session);
        sessionPipelines.computeIfAbsent(session.getId(), k -> ConcurrentHashMap.newKeySet()).add(pipelineId);
    }

    public void unsubscribe(Long pipelineId, WebSocketSession session) {
        Set<WebSocketSession> sessions = subscriptions.get(pipelineId);
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) {
                subscriptions.remove(pipelineId);
            }
        }
        Set<Long> pipelines = sessionPipelines.get(session.getId());
        if (pipelines != null) {
            pipelines.remove(pipelineId);
            if (pipelines.isEmpty()) {
                sessionPipelines.remove(session.getId());
            }
        }
    }

    /** 连接断开：清理该会话的全部订阅 */
    public void removeSession(WebSocketSession session) {
        Set<Long> pipelines = sessionPipelines.remove(session.getId());
        if (pipelines == null) {
            return;
        }
        for (Long pipelineId : pipelines) {
            Set<WebSocketSession> sessions = subscriptions.get(pipelineId);
            if (sessions != null) {
                sessions.remove(session);
                if (sessions.isEmpty()) {
                    subscriptions.remove(pipelineId);
                }
            }
        }
    }

    /** 该管道当前订阅连接（无订阅返回空集合） */
    public Set<WebSocketSession> getSessions(Long pipelineId) {
        return subscriptions.getOrDefault(pipelineId, Set.of());
    }
}
