package com.datanest.dataservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 管道订阅监控统计（F4 连接监控）：在线连接 / 今日事件 / 端到端延迟 / 推送失败 + 订阅方 Key 列表。
 */
@Data
@Schema(description = "管道订阅监控统计")
public class SubscriptionStatsDTO {

    @Schema(description = "在线订阅连接数")
    private int onlineConnections;

    @Schema(description = "今日变更事件数（有订阅者时推送，跨天重置）")
    private long todayEvents;

    @Schema(description = "端到端延迟 P95（毫秒，CDC 事件 ts → 推送时刻）")
    private long p95Ms;

    @Schema(description = "推送失败数（fan-out 发送失败，跨天重置）")
    private long failedSends;

    @Schema(description = "订阅方 Key 列表（含离线订阅授权）")
    private List<SubscriberItemDTO> subscribers;
}
