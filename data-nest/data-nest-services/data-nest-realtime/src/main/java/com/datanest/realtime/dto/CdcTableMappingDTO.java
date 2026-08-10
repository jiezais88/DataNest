package com.datanest.realtime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "CDC 表级映射")
@Data
public class CdcTableMappingDTO {

    @Schema(description = "源表名（不含库名）", example = "users")
    private String sourceTable;

    @Schema(description = "目标表名（可空，默认同源表名）", example = "users")
    private String targetTable;

    @Schema(description = "目标表主键列（逗号分隔，UPSERT 模式必填）", example = "id")
    private String primaryKey;
}
