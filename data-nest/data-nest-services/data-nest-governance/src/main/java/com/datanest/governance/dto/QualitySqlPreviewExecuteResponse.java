package com.datanest.governance.dto;

import lombok.Data;

import java.util.List;

/**
 * CUSTOM_SQL 质量规则执行预览响应（Sprint 7 DG-10）。
 * 多指标预览：columns 展示 SQL 返回的全部指标列，rows 为截断样例行。
 */
@Data
public class QualitySqlPreviewExecuteResponse {

    /** 执行是否成功 */
    private boolean success;

    /** 返回列名（多指标列清单，供选择 resultMetric） */
    private List<String> columns;

    /** 样例行（按 columns 顺序，最多 50 行） */
    private List<List<Object>> rows;

    /** 行数是否被截断 */
    private boolean truncated;

    /** 结果描述（如返回行数） */
    private String message;

    /** 失败原因（success=false 时有值） */
    private String error;
}
