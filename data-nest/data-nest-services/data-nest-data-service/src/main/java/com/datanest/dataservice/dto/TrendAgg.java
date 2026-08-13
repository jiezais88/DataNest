package com.datanest.dataservice.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 时间桶趋势聚合投影（Mapper @Select，Sprint 10 F3）。
 * bucket = date_trunc 时间桶；total = 调用量；failed = 失败数（或限流数，视查询而定）。
 */
@Data
public class TrendAgg {

    private LocalDateTime bucket;

    private Long total;

    private Long failed;
}
