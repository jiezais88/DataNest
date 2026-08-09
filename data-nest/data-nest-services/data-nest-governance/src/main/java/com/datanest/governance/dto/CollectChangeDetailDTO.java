package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "采集变更明细")
@Data
public class CollectChangeDetailDTO {

    @Schema(description = "明细 ID", example = "1234567890123456789")
    private Long id;

    @Schema(description = "采集历史 ID", example = "1234567890123456789")
    private Long historyId;

    @Schema(description = "变更类型")
    private String changeType;

    @Schema(description = "库名")
    private String databaseName;

    @Schema(description = "Schema 名")
    private String schemaName;

    @Schema(description = "表名")
    private String tableName;

    @Schema(description = "字段名")
    private String columnName;

    @Schema(description = "变更前内容")
    private String oldValue;

    @Schema(description = "变更后内容")
    private String newValue;

    @Schema(description = "创建时间（ISO 8601）")
    private LocalDateTime createdAt;
}
