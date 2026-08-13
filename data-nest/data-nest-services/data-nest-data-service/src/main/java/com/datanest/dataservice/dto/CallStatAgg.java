package com.datanest.dataservice.dto;

import lombok.Data;

/**
 * 按 apiId/keyId 分组的调用统计聚合投影（Mapper @Select，Sprint 10 F3）。
 * refId = 分组键（apiId 或 keyId）。
 */
@Data
public class CallStatAgg {

    private Long refId;

    private Long totalCalls;

    private Long failedCalls;

    private Long rateLimited;

    private Double p95;
}
