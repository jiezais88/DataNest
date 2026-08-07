package com.datanest.governance.api.dto;

import lombok.Data;

/**
 * 血缘记录项。
 * <p>
 * 对齐原 SqlLineageExtractor 组装 LineageRecord 的字段；
 * source_column/target_column 均为空时表示表级血缘，否则为字段级血缘。
 */
@Data
public class LineageRecordItemDTO {

    /** 源表（Python 节点产出等场景可为空） */
    private String sourceTable;

    /** 源字段，字段级血缘时使用 */
    private String sourceColumn;

    /** 目标表 */
    private String targetTable;

    /** 目标字段，字段级血缘时使用 */
    private String targetColumn;

    /** DAG ID */
    private Long dagId;

    /** DAG 名称 */
    private String dagName;

    /** 节点 ID */
    private String nodeId;

    /** 节点名称 */
    private String nodeName;

    /** 执行实例 ID */
    private Long executionId;

    /** 血缘类型：SQL / SYNC / PYTHON */
    private String lineageType;
}
