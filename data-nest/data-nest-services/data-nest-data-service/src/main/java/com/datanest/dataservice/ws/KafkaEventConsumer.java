package com.datanest.dataservice.ws;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
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
 */
@Component
public class KafkaEventConsumer {

    private static final Logger logger = LoggerFactory.getLogger(KafkaEventConsumer.class);
    private static final String TOPIC_PREFIX = "cdc-events-";

    private final WebSocketSubscriptionRegistry registry;

    public KafkaEventConsumer(WebSocketSubscriptionRegistry registry) {
        this.registry = registry;
    }

    @KafkaListener(topicPattern = "cdc-events-.*")
    public void onEvent(ConsumerRecord<String, String> record) {
        String topic = record.topic();
        if (topic == null || !topic.startsWith(TOPIC_PREFIX)) {
        }
        Long pipelineId;
        try {
            pipelineId = Long.parseLong(topic.substring(TOPIC_PREFIX.length()));
        } catch (NumberFormatException e) {
            logger.warn("非法事件 topic（无法解析 pipelineId）: {}", topic);
            return;
        }
        Set<WebSocketSession> sessions = registry.getSessions(pipelineId);
        if (sessions.isEmpty()) {
            return; // 无订阅者，事件丢弃（NG9）
        }
        String event = normalize(pipelineId, record.value());
        if (event == null) {
            return;
        }
        TextMessage textMessage = new TextMessage(event);
        for (WebSocketSession session : sessions) {
            try {
                if (session.isOpen()) {
                    session.sendMessage(textMessage);
                }
            } catch (Exception e) {
                logger.debug("fan-out 发送失败（连接可能已断）: pipelineId={}, session={}", pipelineId, session.getId());
            }
        }
    }

    /**
     * Debezium JSON → PRD §6.6 订阅事件格式 {@code {pipelineId, table, opType, data, ts}}。
     * <p>
     * op 映射：c→INSERT / u→UPDATE / d→DELETE；r(read snapshot)、t(truncate) 忽略。
     * data 取 after（DELETE 时 after 为 null，退 before）。ts 取 ts_ms → ISO-8601 UTC。
     */
    private String normalize(Long pipelineId, String value) {
        try {
            JSONObject debezium = JSON.parseObject(value);
            if (debezium == null) {
                return null;
            }
            String opType = mapOpType(debezium.getString("op"));
            if (opType == null) {
                return null;
            }
            JSONObject source = debezium.getJSONObject("source");
            String table = source == null ? null : source.getString("table");
            JSONObject data = debezium.getJSONObject("after");
            if (data == null) {
                data = debezium.getJSONObject("before");
            }
            Long tsMs = debezium.getLong("ts_ms");
            String ts = tsMs == null ? null : Instant.ofEpochMilli(tsMs).toString();

            JSONObject event = new JSONObject();
            event.put("pipelineId", pipelineId);
            event.put("table", table);
            event.put("opType", opType);
            event.put("data", data);
            event.put("ts", ts);
            return event.toJSONString();
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
