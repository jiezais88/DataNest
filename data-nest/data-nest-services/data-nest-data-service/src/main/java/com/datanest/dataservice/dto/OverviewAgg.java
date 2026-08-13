package com.datanest.dataservice.dto;

import lombok.Data;

/**
 * 全局调用统计概览聚合投影（Mapper @Select，Sprint 10 F3）。
 */
@Data
public class OverviewAgg {

    private Long totalCalls;

    private Long successCalls;

    private Long rateLimited;

    private Double p95;
}
