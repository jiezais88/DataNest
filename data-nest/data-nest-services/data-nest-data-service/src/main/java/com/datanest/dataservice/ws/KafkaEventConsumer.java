package com.datanest.dataservice.ws;

import com.datanest.common.json.JsonUtils;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.Set;

/**
 * Kafka 事件消费者（F4，D-D6）：消费每管道专属 topic {@code cdc-events-{pipelineId}}，
 * 从 topic 解析 pipelineId → 归一化 Debezium JSON 为 PRD §6.6 订阅事件格式 → fan-out 到订阅连接。
 * <p>
 * 无订阅者的管道事件直接丢弃（PRD NG9 不落库，不空转资源）。
 * 有订阅者时同步埋点（连接监控）：管道今日事件/延迟 P95、订阅方 Key 接收事件/最近时间、fan-out 失败数。
 */
@Component
public class KafkaEventConsumer {

    private static final Logger logger = LoggerFactory.getLogger(KafkaEventConsumer.class);
    private static final String TOPIC_PREFIX = "cdc-events-";

    private final WebSocketSubscriptionRegistry registry;
    private final SubscriptionMetrics metrics;

    public KafkaEventConsumer(WebSocketSubscriptionRegistry registry, SubscriptionMetrics metrics) {
        this.registry = registry;
        this.metrics = metrics;
    }

    @KafkaListener(topicPattern = "cdc-events-.*")
    public void onEvent(ConsumerRecord<String, String> record) {
        Long pipelineId = parsePipelineId(record.topic());
        if (pipelineId == null) {
            return;
        }
        Set<WebSocketSession> sessions = registry.getSessions(pipelineId);
        if (sessions.isEmpty()) {
            return; // 无订阅者，事件丢弃（NG9）
        }
        NormalizedEvent event = normalize(pipelineId, record.value(), record.timestamp());
        if (event == null) {
            return;
        }
        TextMessage textMessage = new TextMessage(event.json());
        for (WebSocketSession session : sessions) {
            try {
                if (session.isOpen()) {
                    session.sendMessage(textMessage);
                    Long keyId = (Long) session.getAttributes().get(WsHandshakeInterceptor.ATTR_KEY_ID);
                    metrics.recordEvent(pipelineId, event.latencyMs(), keyId);
                }
            } catch (Exception e) {
                metrics.recordFailure(pipelineId);
                logger.debug("fan-out 发送失败（连接可能已断）: pipelineId={}, session={}", pipelineId, session.getId());
            }
        }
    }

    /** 解析 topic 后缀 pipelineId；非法返回 null */
    private Long parsePipelineId(String topic) {
        if (topic == null || !topic.startsWith(TOPIC_PREFIX)) {
            return null;
        }
        try {
            return Long.parseLong(topic.substring(TOPIC_PREFIX.length()));
        } catch (NumberFormatException e) {
            logger.warn("非法事件 topic（无法解析 pipelineId）: {}", topic);
            return null;
        }
    }

    /** 归一化结果：事件 JSON + 端到端延迟（毫秒，ts_ms 缺失时为 0 不采样有效值） */
    private record NormalizedEvent(String json, long latencyMs) {
    }

    /**
     * Debezium JSON → PRD §6.6 订阅事件格式 {@code {pipelineId, table, opType, data, ts}}。
     * <p>
     * op 映射：c→INSERT / u→UPDATE / d→DELETE；r(read snapshot)、t(truncate) 忽略。
     * data 取 after（DELETE 时 after 为 null，退 before）。ts 取 ts_ms → ISO-8601 UTC；ts_ms 缺失（Flink CDC 3.6 debezium-json 不输出）退 Kafka 消息时间戳。
     */
    private NormalizedEvent normalize(Long pipelineId, String value, long kafkaTsMs) {
        try {
            tools.jackson.databind.node.ObjectNode debezium = JsonUtils.parseObject(value);
            if (debezium == null) {
                return null;
            }
            String opType = mapOpType(JsonUtils.getString(debezium, "op"));
            if (opType == null) {
                return null;
            }
            tools.jackson.databind.node.ObjectNode source = JsonUtils.getObject(debezium, "source");
            String table = source == null ? null : JsonUtils.getString(source, "table");
            tools.jackson.databind.node.ObjectNode data = JsonUtils.getObject(debezium, "after");
            if (data == null) {
                data = JsonUtils.getObject(debezium, "before");
            }
            Long tsMs = JsonUtils.getLong(debezium, "ts_ms");
            // Flink CDC 3.6 debezium-json 不输出 ts_ms（序列化枚举固定为 before/after/op/source{db,table}），
            // 退用 Kafka 消息时间戳（Flink 写入 Kafka 时刻，接近事件发生时间）。
            long effectiveTsMs = tsMs != null ? tsMs : kafkaTsMs;
            String ts = Instant.ofEpochMilli(effectiveTsMs).toString();
            long latencyMs = Math.max(System.currentTimeMillis() - effectiveTsMs, 0);

            tools.jackson.databind.node.ObjectNode event = JsonUtils.createObjectNode();
            JsonUtils.put(event, "pipelineId", pipelineId);
            JsonUtils.put(event, "table", table);
            JsonUtils.put(event, "opType", opType);
            JsonUtils.put(event, "data", data);
            JsonUtils.put(event, "ts", ts);
            return new NormalizedEvent(JsonUtils.toJSONString(event), latencyMs);
        } catch (Exception e) {
            logger.warn("解析 Kafka 事件消息失败（丢弃）: pipelineId={}, error={}", pipelineId, e.getMessage());
            return null;
        }
    }

    private String mapOpType(String op) {
        if (op == null) {
            return null;
        }
        return switch (op) {
            case "c" -> "INSERT";
            case "u" -> "UPDATE";
            case "d" -> "DELETE";
            default -> null;
        };
    }
}
