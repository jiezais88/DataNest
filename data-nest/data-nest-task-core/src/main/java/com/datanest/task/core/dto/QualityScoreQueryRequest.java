package com.datanest.task.core.dto;

import lombok.Data;

/**
 * 表级质量评分列表查询请求（Sprint 6 NG8）。
 * <p>
 * 按表名关键字 / 数据源 / 健康度筛选，分页返回。
 */
@Data
public class QualityScoreQueryRequest {

    private Long page = 1L;

    private Long pageSize = 10L;

    /** 表名关键字（库名.表名 模糊匹配） */
    private String keyword;

    /** 数据源 ID 过滤 */
    private Long datasourceId;

    /** 健康度过滤：EXCELLENT/GOOD/WARNING/BAD */
    private String healthLevel;
}
