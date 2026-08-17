package com.datanest.dataservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 自定义 SQL 参数定义（CUSTOM_SQL 形态，params_json.sqlParams 元素，Sprint 13）。
 * <p>
 * type 枚举：LONG / DECIMAL / DATE / DATETIME / STRING / BOOLEAN，对齐 CancelableSqlExecutor 启发式推断结果，可手动修正。
 */
@Data
@Schema(description = "自定义 SQL 参数定义")
public class CustomSqlParamDef {

    @Schema(description = "参数名（对应 SQL 内 :param 命名占位符）")
    @NotBlank(message = "参数名不能为空")
    private String name;

    @Schema(description = "参数类型：LONG / DECIMAL / DATE / DATETIME / STRING / BOOLEAN")
    @NotBlank(message = "参数类型不能为空")
    private String type;

    @Schema(description = "是否必填（默认 true；选填参数缺省时不带该条件）")
    private Boolean required;

    @Schema(description = "默认值（选填参数缺省时使用）")
    private String defaultValue;
}
