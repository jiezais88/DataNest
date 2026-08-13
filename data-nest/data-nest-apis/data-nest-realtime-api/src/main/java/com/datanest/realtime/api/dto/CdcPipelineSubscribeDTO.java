package com.datanest.realtime.api.dto;

import lombok.Data;

import java.util.List;

/**
 * CDC 管道订阅信息（F4 WebSocket 订阅校验用：管道状态 + 源数据源/库 + 源表清单，
 * 供数据服务反查表敏感度判定「机密管道不可订阅」）。
 */
@Data
public class CdcPipelineSubscribeDTO {

    /** 管道 ID */
    private Long id;

    /** 管道名称 */
    private String name;

    /** 管道状态：RUNNING / STOPPED / ERROR */
    private String status;

    /** 源数据源 ID（敏感度反查用） */
    private Long sourceDatasourceId;

    /** 源库名 */
    private String sourceDatabase;

    /** 源表名列表（敏感度反查用，不含库名） */
    private List<String> sourceTables;
}
