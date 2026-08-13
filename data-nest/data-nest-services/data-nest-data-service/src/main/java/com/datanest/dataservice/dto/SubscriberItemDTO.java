package com.datanest.dataservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订阅方 Key 项（F4 连接监控）：订阅授权 + 连接状态 + 接收统计 + 审计字段。
 */
@Data
@Schema(description = "订阅方 Key 项")
public class SubscriberItemDTO {

    @Schema(description = "API Key ID")
    private Long keyId;

    @Schema(description = "Key 名称")
    private String keyName;

    @Schema(description = "是否在线（有 ≥1 条订阅连接）")
    private boolean online;

    @Schema(description = "今日接收事件数（跨天重置）")
    private long receivedEvents;

    @Schema(description = "最近事件时间（从未接收为 null）")
    private LocalDateTime lastEventAt;

    @Schema(description = "创建人用户名")
    private String createdByName;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "修改人用户名")
    private String updatedByName;

    @Schema(description = "修改时间")
    private LocalDateTime updatedAt;
}
