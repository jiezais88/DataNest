package com.datanest.alert.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DAG 告警配置 DTO
 */
@Schema(description = "DAG 告警配置（全局配置 / 按 DAG 覆盖）")
@Data
public class DagAlertConfigPayload {

    @Schema(description = "配置 ID", example = "1234567890123456789")
    private Long id;

    @Schema(description = "是否启用")
    private Boolean enabled;

    @Schema(description = "收件人邮箱列表（以 ;；,， 分隔）")
    private String recipients;

    @Schema(description = "触发条件列表（FAILURE/TIMEOUT/SUCCESS）")
    private List<String> triggerConditions;

    @Schema(description = "超时阈值（分钟）")
    private Integer timeoutMinutes;

    @Schema(description = "DAG ID（按 DAG 覆盖时填写；null 表示全局默认）", example = "1234567890123456789")
    private Long dagId;          // Sprint 4 review：按 DAG 覆盖；null 表示全局默认

    @Schema(description = "创建人 ID", example = "1234567890123456789")
    private Long createdBy;

    @Schema(description = "更新人 ID", example = "1234567890123456789")
    private Long updatedBy;

    @Schema(description = "创建时间（ISO 8601）")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间（ISO 8601）")
    private LocalDateTime updatedAt;
}
