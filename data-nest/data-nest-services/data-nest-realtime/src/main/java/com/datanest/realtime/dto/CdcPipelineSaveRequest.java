package com.datanest.realtime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "CDC 管道新增/编辑请求")
@Data
public class CdcPipelineSaveRequest {

    @Schema(description = "管道名称（唯一）", example = "testdb 实时入湖")
    private String name;
    @Schema(description = "管道描述（可空）", example = "同步订单库到 Iceberg 湖仓，供实时分析使用")
    private String description;

    @Schema(description = "源数据源 ID", example = "2083088527209295874")
    private Long sourceDatasourceId;

    @Schema(description = "源库名（MySQL database）", example = "testdb")
    private String sourceDatabase;

    @Schema(description = "目标库名（Iceberg/Doris catalog 下的 database）", example = "cdc_test")
    private String targetDatabase;

    @Schema(description = "同步模式：FULL_AND_INCREMENT 全量+增量 / INCREMENTAL_ONLY 仅增量", example = "FULL_AND_INCREMENT")
    private String syncMode;

    @Schema(description = "启动位点：INITIAL 全量快照+增量 / LATEST_OFFSET 从最新位点 / EARLIEST_OFFSET 从最早位点（仅增量模式可选，全量+增量固定 INITIAL）", example = "INITIAL")
    private String startupMode;

    @Schema(description = "写入模式：UPSERT 主键覆盖 / APPEND 追加", example = "UPSERT")
    private String writeMode;

    @Schema(description = "表级映射列表（至少一条）")
    private List<CdcTableMappingDTO> tables;

    @Schema(description = "扩展配置 JSON（预留，可空）")
    private String configJson;
}
