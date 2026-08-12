package com.datanest.dataservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * SQL 终端执行请求（Sprint 10 F1）。
 */
@Data
@Schema(description = "SQL 终端执行请求")
public class SqlExecuteRequest {

    @Schema(description = "数据源 ID（内置 Doris 恒为 -1）", example = "-1")
    @NotNull(message = "数据源 ID 不能为空")
    private Long datasourceId;

    @Schema(description = "SQL（只允许 SELECT/WITH/SHOW/DESC/EXPLAIN 只读语句，多语句逐条校验）")
    @NotBlank(message = "SQL 不能为空")
    private String sql;

    @Schema(description = "查询超时秒数（默认取服务配置 60）", example = "60")
    private Integer timeoutSeconds;
}
