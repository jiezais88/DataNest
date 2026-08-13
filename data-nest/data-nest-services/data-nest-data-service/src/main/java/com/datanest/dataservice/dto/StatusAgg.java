package com.datanest.dataservice.dto;

import lombok.Data;

/**
 * 状态码分组聚合投影（Mapper @Select，Sprint 10 F3 错误码分布）。
 */
@Data
public class StatusAgg {

    private Integer statusCode;

    private Long cnt;
}
