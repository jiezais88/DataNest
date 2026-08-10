package com.datanest.realtime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "CDC 管道详情")
@Data
public class CdcPipelineDTO {

    @Schema(description = "管道 ID", example = "1234567890123456789")
    private Long id;

    @Schema(description = "管道名称")
    private String name;
    @Schema(description = "管道描述")
    private String description;

    @Schema(description = "源数据源 ID", example = "2083088527209295874")
    private Long sourceDatasourceId;

    @Schema(description = "源数据源名称（跨域回填，失败为 null）")
    private String sourceDatasourceName;

    @Schema(description = "源库名")
    private String sourceDatabase;

    @Schema(description = "目标库名")
    private String targetDatabase;

    @Schema(description = "同步模式：FULL_AND_INCREMENT / INCREMENTAL_ONLY")
    private String syncMode;

    @Schema(description = "启动位点：INITIAL / LATEST_OFFSET / EARLIEST_OFFSET")
    private String startupMode;

    @Schema(description = "写入模式：UPSERT / APPEND")
    private String writeMode;

    @Schema(description = "管道状态：STOPPED / RUNNING / ERROR")
    private String status;

    @Schema(description = "Flink 作业 ID（RUNNING 时有值）")
    private String flinkJobId;

    @Schema(description = "最近一次 savepoint 路径（启动优先恢复；编辑后清空）")
    private String savepointPath;

    @Schema(description = "当前同步延迟（秒）")
    private Integer currentLagSeconds;

    @Schema(description = "累计写入变更条数")
    private Long totalChanges;

    @Schema(description = "最近一次错误信息")
    private String lastError;

    @Schema(description = "扩展配置 JSON")
    private String configJson;

    @Schema(description = "表级映射列表")
    private List<CdcTableMappingDTO> tables;

    @Schema(description = "创建人 ID", example = "1234567890123456789")
    private Long createdBy;

    @Schema(description = "更新人 ID", example = "1234567890123456789")
    private Long updatedBy;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
