package com.datanest.realtime.api.dto;

import lombok.Data;

/**
 * CDC 管道引用信息（删除数据源前置校验用，只带 id/name）。
 */
@Data
public class CdcPipelineReferenceDTO {

    private Long id;

    private String name;
}
