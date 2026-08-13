package com.datanest.dataservice.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanest.dataservice.dto.SubscriberItemDTO;
import com.datanest.dataservice.dto.SubscriptionStatsDTO;
import com.datanest.dataservice.entity.ApiKey;
import com.datanest.dataservice.entity.ApiKeyPipeline;
import com.datanest.dataservice.mapper.ApiKeyMapper;
import com.datanest.dataservice.mapper.ApiKeyPipelineMapper;
import com.datanest.dataservice.ws.SubscriptionMetrics;
import com.datanest.dataservice.ws.WebSocketSubscriptionRegistry;
import com.datanest.dataservice.ws.WsHandshakeInterceptor;
import com.datanest.system.api.SystemUserApi;
import com.datanest.task.core.support.SystemUserResolver;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 管道订阅监控查询（F4 连接监控）：聚合在线连接（registry）+ 埋点（metrics）+ 订阅授权（api_key_pipeline）
 * + Key 审计字段（api_key）+ 用户名回填（system-api）。
 */
@Service
public class WsSubscriptionQueryService {

    private final WebSocketSubscriptionRegistry registry;
    private final SubscriptionMetrics metrics;
    private final ApiKeyPipelineMapper pipelineMapper;
    private final ApiKeyMapper apiKeyMapper;
    private final SystemUserApi systemUserApi;

    public WsSubscriptionQueryService(WebSocketSubscriptionRegistry registry,
                                      SubscriptionMetrics metrics,
                                      ApiKeyPipelineMapper pipelineMapper,
                                      ApiKeyMapper apiKeyMapper,
                                      SystemUserApi systemUserApi) {
        this.registry = registry;
        this.metrics = metrics;
        this.pipelineMapper = pipelineMapper;
        this.apiKeyMapper = apiKeyMapper;
        this.systemUserApi = systemUserApi;
    }

    public SubscriptionStatsDTO stats(Long pipelineId) {
        // 1. 在线连接 + 在线 keyId 集合（session attributes 由握手拦截器写入）
        Set<WebSocketSession> sessions = registry.getSessions(pipelineId);
        Set<Long> onlineKeyIds = new HashSet<>();
        for (WebSocketSession s : sessions) {
            Object keyId = s.getAttributes().get(WsHandshakeInterceptor.ATTR_KEY_ID);
            if (keyId instanceof Long l) {
                onlineKeyIds.add(l);
            }
        }

        // 2. 埋点快照
        SubscriptionMetrics.PipelineSnapshot snap = metrics.snapshot(pipelineId);

        // 3. 订阅授权：api_key_pipeline → api_key（批量，无 N+1）
        List<ApiKeyPipeline> bindings = pipelineMapper.selectList(
                new QueryWrapper<ApiKeyPipeline>().eq("pipeline_id", pipelineId));
        List<Long> keyIds = bindings.stream().map(ApiKeyPipeline::getKeyId).distinct().toList();
        Map<Long, ApiKey> keys = keyIds.isEmpty() ? Map.of()
                : apiKeyMapper.selectBatchIds(keyIds).stream()
                .collect(Collectors.toMap(ApiKey::getId, k -> k));

        // 4. 用户名回填（读路径降级空 Map 不阻断）
        List<Long> userIds = keys.values().stream()
                .flatMap(k -> Stream.of(k.getCreatedBy(), k.getUpdatedBy()))
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, String> usernames = SystemUserResolver.usernames(systemUserApi, userIds);

        // 5. 组装订阅方列表
        List<SubscriberItemDTO> subscribers = bindings.stream().map(b -> {
            ApiKey k = keys.get(b.getKeyId());
            SubscriberItemDTO item = new SubscriberItemDTO();
            item.setKeyId(b.getKeyId());
            item.setKeyName(k == null ? "—" : k.getName());
            item.setOnline(onlineKeyIds.contains(b.getKeyId()));
            SubscriptionMetrics.KeyMetrics km = snap.getKeys().get(b.getKeyId());
            if (km != null) {
                item.setReceivedEvents(km.getReceivedEvents());
                long last = km.getLastEventAt();
                item.setLastEventAt(last == 0 ? null
                        : LocalDateTime.ofInstant(Instant.ofEpochMilli(last), ZoneId.systemDefault()));
            }
            if (k != null) {
                item.setCreatedByName(usernames.get(k.getCreatedBy()));
                item.setCreatedAt(k.getCreatedAt());
                item.setUpdatedByName(usernames.get(k.getUpdatedBy()));
                item.setUpdatedAt(k.getUpdatedAt());
            }
            return item;
        }).toList();

        SubscriptionStatsDTO dto = new SubscriptionStatsDTO();
        dto.setOnlineConnections(sessions.size());
        dto.setTodayEvents(snap.getTodayEvents());
        dto.setP95Ms(snap.getP95Ms());
        dto.setFailedSends(snap.getFailedSends());
        dto.setSubscribers(subscribers);
        return dto;
    }
}
