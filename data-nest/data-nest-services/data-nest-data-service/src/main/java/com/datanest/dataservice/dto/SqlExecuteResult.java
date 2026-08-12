package com.datanest.dataservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * SQL 终端执行结果（Sprint 10 F1，对齐 DorisSqlExecutor.QueryResult 语义 + 超时/耗时）。
 */
@Data
@Schema(description = "SQL 终端执行结果")
public class SqlExecuteResult {

    @Schema(description = "列头")
    private List<String> columns;

    @Schema(description = "行数据（LinkedHashMap 保序）")
    private List<Map<String, Object>> rows;

    @Schema(description = "是否因超过 1000 行上限截断")
    private boolean truncated;

    @Schema(description = "执行耗时毫秒")
    private long durationMs;

    @Schema(description = "返回行数")
    private int rowCount;
}
