package com.datanest.alert.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 告警规则 DTO（创建/更新/列表/详情，含接收用户）。
 */
@Schema(description = "告警规则（创建/更新/列表/详情，含接收用户）")
@Data
public class AlertRuleDTO {

    @Schema(description = "规则 ID", example = "1234567890123456789")
    private Long id;

    @Schema(description = "规则名称（用户自定义，必填；同一 object_type 下唯一）")
    private String name;

    @Schema(description = "告警对象类型（DAG/SYNC_JOB/COLLECT_TASK/QUALITY）")
    private String objectType;

    @Schema(description = "告警对象 ID 列表（多选）")
    private List<Long> objectIds;

    @Schema(description = "对象名称冗余，便于列表展示；多对象时以「、」拼接")
    private String objectName;

    @Schema(description = "触发条件列表（FAILURE/TIMEOUT/SUCCESS）")
    private List<String> triggerConditions;

    @Schema(description = "超时阈值（分钟）")
    private Integer timeoutMinutes;

    @Schema(description = "是否启用")
    private Boolean enabled;

    @Schema(description = "接收用户 ID 列表")
    private List<Long> userIds;

    @Schema(description = "创建人用户名")
    private String createdByName;

    @Schema(description = "更新人用户名")
    private String updatedByName;

    @Schema(description = "创建时间（ISO 8601）")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间（ISO 8601）")
    private LocalDateTime updatedAt;
}
