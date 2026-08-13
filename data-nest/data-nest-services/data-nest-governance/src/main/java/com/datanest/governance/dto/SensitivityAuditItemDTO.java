package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 分级变更审计项（Sprint 10 F5）。
 */
@Data
@Schema(description = "分级变更审计项")
public class SensitivityAuditItemDTO {

    @Schema(description = "审计记录 ID")
    private Long id;

    @Schema(description = "元数据表 ID")
    private Long tableId;

    @Schema(description = "表名")
    private String tableName;

    @Schema(description = "原敏感度（首次打标为 null）")
    private String oldLevel;

    @Schema(description = "新敏感度")
    private String newLevel;

    @Schema(description = "审计动作：CHANGE_LEVEL 改级 / API_EXEMPT 开白")
    private String action;

    @Schema(description = "动作补充说明（开白/取消开白）")
    private String remark;

    @Schema(description = "操作人用户 ID")
    private Long operatorId;

    @Schema(description = "操作人用户名（用户已注销为 null）")
    private String operatorName;

    @Schema(description = "操作时间")
    private LocalDateTime createdAt;
}
