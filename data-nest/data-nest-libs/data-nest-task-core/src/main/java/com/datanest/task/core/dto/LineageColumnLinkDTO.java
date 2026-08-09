package com.datanest.task.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 字段级血缘链路（source 列 → target 列）
 * Sprint 5：lineage_record 的 source_column / target_column 有值时对应一条字段级血缘。
 */
@Schema(description = "字段级血缘链路（source 列 → target 列）")
@Data
public class LineageColumnLinkDTO {

    @Schema(description = "源表名")
    private String sourceTable;

    @Schema(description = "源列名")
    private String sourceColumn;

    @Schema(description = "目标表名")
    private String targetTable;

    @Schema(description = "目标列名")
    private String targetColumn;

    @Schema(description = "血缘类型（SQL/SYNC/PYTHON）")
    private String lineageType;

    @Schema(description = "关联 DAG ID", example = "1234567890123456789")
    private Long dagId;

    @Schema(description = "DAG 名称")
    private String dagName;

    @Schema(description = "节点 ID")
    private String nodeId;

    @Schema(description = "节点名称")
    private String nodeName;

    @Schema(description = "执行 ID", example = "1234567890123456789")
    private Long executionId;

    @Schema(description = "创建时间（ISO 8601）")
    private LocalDateTime createdAt;
}
