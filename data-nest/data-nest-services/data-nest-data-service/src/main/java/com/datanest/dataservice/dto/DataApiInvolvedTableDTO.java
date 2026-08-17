package com.datanest.dataservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 数据 API 涉及表（CUSTOM_SQL 形态详情返回；与前端 {@code InvolvedTable} 契约一致）。
 */
@Data
@Schema(description = "数据 API 涉及表")
public class DataApiInvolvedTableDTO {

    @Schema(description = "数据源 ID（内置 Doris 为 -1）")
    private Long datasourceId;

    @Schema(description = "库名（MySQL/Doris）")
    private String database;

    @Schema(description = "Schema 名（PG 系）")
    private String schema;

    @Schema(description = "表名")
    private String table;
}
