package com.datanest.task.core.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 字段级血缘链路（source 列 → target 列）
 * Sprint 5：lineage_record 的 source_column / target_column 有值时对应一条字段级血缘。
 */
@Data
public class LineageColumnLinkDTO {

    private String sourceTable;

    private String sourceColumn;

    private String targetTable;

    private String targetColumn;

    /** SQL / SYNC / PYTHON */
    private String lineageType;

    private Long dagId;

    private String dagName;

    private String nodeId;

    private String nodeName;

    private Long executionId;

    private LocalDateTime createdAt;
}
