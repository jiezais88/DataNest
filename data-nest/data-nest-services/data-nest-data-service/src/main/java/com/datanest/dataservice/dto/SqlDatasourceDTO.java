package com.datanest.dataservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * SQL 终端数据源下拉项（Sprint 10 F1：内置 Doris + 状态 NORMAL 平台数据源）。
 */
@Data
@Schema(description = "SQL 终端数据源下拉项")
public class SqlDatasourceDTO {

    @Schema(description = "数据源 ID（内置 Doris 恒为 -1）")
    private Long id;

    @Schema(description = "数据源名称")
    private String name;

    @Schema(description = "数据源类型（DORIS/MYSQL/POSTGRESQL/ORACLE/SQLSERVER）")
    private String type;

    @Schema(description = "是否内置 Doris")
    private boolean builtin;

    @Schema(description = "数据库名（内置 Doris 为 datanest 目标库）")
    private String databaseName;
}
