package com.datanest.task.core.dto;

import lombok.Data;

/**
 * 表级血缘边（source → target），图谱查询用。
 */
@Data
public class LineageTableEdge {

    private String sourceTable;

    private String targetTable;

    /** SQL / SYNC / PYTHON */
    private String lineageType;
}
