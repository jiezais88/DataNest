package com.datanest.task.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 标准合规检查请求（检查范围 / 分页筛选）。
 * 从 governance 模块下沉至共享底座。
 */
@Schema(description = "标准合规检查请求（检查范围/筛选）")
@Data
public class ComplianceCheckRequest {

    @Schema(description = "数据源 ID", example = "1234567890123456789")
    private Long datasourceId;

    @Schema(description = "数据源 ID 列表")
    private List<Long> datasourceIds;

    @Schema(description = "数据库名")
    private String databaseName;

    @Schema(description = "Schema 名")
    private String schemaName;

    @Schema(description = "表 ID", example = "1234567890123456789")
    private Long tableId;

    @Schema(description = "是否检查命名规范")
    private Boolean checkNaming;

    @Schema(description = "是否检查字段类型")
    private Boolean checkFieldType;

    @Schema(description = "检查时间范围下界（ISO 8601）")
    private LocalDateTime startTime;

    @Schema(description = "检查时间范围上界（ISO 8601）")
    private LocalDateTime endTime;
}
