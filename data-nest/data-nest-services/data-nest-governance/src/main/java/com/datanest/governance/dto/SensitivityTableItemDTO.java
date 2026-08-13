package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 分级管理页表列表项（Sprint 10 F5）：敏感度筛选 + 批量打标的列表行。
 */
@Data
@Schema(description = "分级表列表项")
public class SensitivityTableItemDTO {

    @Schema(description = "元数据表 ID")
    private Long tableId;

    @Schema(description = "表名")
    private String tableName;

    @Schema(description = "数据库名")
    private String databaseName;

    @Schema(description = "Schema 名")
    private String schemaName;

    @Schema(description = "数据源 ID")
    private Long datasourceId;

    @Schema(description = "数据源名称")
    private String datasourceName;

    @Schema(description = "敏感度：PUBLIC / INTERNAL / CONFIDENTIAL")
    private String sensitivityLevel;

    @Schema(description = "内部表 API 开白标记（1 已开白）")
    private Integer apiExempted;

    @Schema(description = "元数据状态（ONLINE/OFFLINE）")
    private String sourceStatus;

    @Schema(description = "表负责人用户 ID")
    private Long ownerUserId;

    @Schema(description = "表负责人用户名")
    private String ownerName;

    @Schema(description = "最近修改人用户 ID")
    private Long updatedBy;

    @Schema(description = "最近修改人用户名")
    private String updatedByName;

    @Schema(description = "最近修改时间")
    private LocalDateTime updatedAt;
}
