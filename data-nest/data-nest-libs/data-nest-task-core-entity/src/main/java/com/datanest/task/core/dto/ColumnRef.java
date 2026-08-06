package com.datanest.task.core.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 字段级血缘的列引用（表名 + 列名），字段级图谱 BFS 展开用。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ColumnRef {

    private String table;

    private String column;
}
