package com.datanest.task.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "字段级血缘的列引用（表名 + 列名），字段级图谱 BFS 展开用")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ColumnRef {

    @Schema(description = "表名")
    private String table;

    @Schema(description = "列名")
    private String column;
}
