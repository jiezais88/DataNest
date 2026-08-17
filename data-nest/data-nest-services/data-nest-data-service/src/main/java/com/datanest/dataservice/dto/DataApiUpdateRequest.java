package com.datanest.dataservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 编辑数据 API 请求（Sprint 10 F2）：数据源/库/表绑定不可改（换表 = 新建 API）。
 */
@Data
@Schema(description = "编辑数据 API 请求")
public class DataApiUpdateRequest {

    @Schema(description = "API 名称", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "API 名称不能为空")
    @Size(max = 100, message = "API 名称最长 100 字符")
    private String name;

    @Schema(description = "对外路径（同创建归一规则）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "API 路径不能为空")
    private String path;

    @Schema(description = "查询定义形态：TABLE_SELECT 选表（默认）/ CUSTOM_SQL 自定义 SQL")
    private String queryType = "TABLE_SELECT";

    @Schema(description = "自定义 SQL 文本（CUSTOM_SQL 形态必填，只读 SELECT，:param 命名参数）")
    private String sqlText;

    @Schema(description = "自定义 SQL 参数定义（CUSTOM_SQL 形态，与 SQL :param 一一对应）")
    @Valid
    private List<CustomSqlParamDef> sqlParams;

    @Schema(description = "参数化筛选（EQ/RANGE，AND 组合）")
    @Valid
    private List<ApiParamDef> filters;

    @Schema(description = "返回字段白名单（空 = 全部字段）")
    private List<String> fields;

    @Schema(description = "排序（如 cnt DESC）")
    private String orderBy;

    @Schema(description = "是否分页：1 启用 / 0 关闭")
    private Integer paginated;

    @Schema(description = "pageSize 上限")
    private Integer pageSizeMax;
}
