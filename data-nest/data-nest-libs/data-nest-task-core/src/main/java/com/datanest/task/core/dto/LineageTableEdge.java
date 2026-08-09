package com.datanest.task.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "表级血缘边（source → target），图谱查询用")
@Data
public class LineageTableEdge {

    @Schema(description = "源表名")
    private String sourceTable;

    @Schema(description = "目标表名")
    private String targetTable;

    @Schema(description = "血缘类型（SQL/SYNC/PYTHON）")
    private String lineageType;
}
