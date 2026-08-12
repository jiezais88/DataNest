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

    @Schema(description = "执行耗时毫秒（int，避免 long 被序列化为字符串）")
    private int durationMs;

    @Schema(description = "返回行数")
    private int rowCount;

    @Schema(description = "本次 SQL 引用的表数量（JSqlParser 表集合，供前端结果 KPI 展示）")
    private int tableCount;

    @Schema(description = "命中机密级敏感表的数量（成功返回恒为 0，表示未触碰机密数据）")
    private int confidentialHits;
}
