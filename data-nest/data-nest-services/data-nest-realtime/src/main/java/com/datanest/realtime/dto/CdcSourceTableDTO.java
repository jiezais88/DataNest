package com.datanest.realtime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "CDC 源表信息（向导同步表勾选列表，2026-08-10 前端联调确认新增）")
@Data
public class CdcSourceTableDTO {

    @Schema(description = "源表名", example = "users")
    private String tableName;

    @Schema(description = "约估行数（information_schema.TABLES.TABLE_ROWS，InnoDB 为估算值）", example = "1024")
    private Long tableRows;

    @Schema(description = "主键列（逗号分隔，按 ORDINAL_POSITION 排序；无主键为 null）", example = "id")
    private String primaryKey;

    @Schema(description = "PG 源是否已开启 REPLICA IDENTITY FULL（update/delete 同步必需；MySQL 源为 null；未开 FULL 的表向导给警示）")
    private Boolean replicaIdentityFull;
}
