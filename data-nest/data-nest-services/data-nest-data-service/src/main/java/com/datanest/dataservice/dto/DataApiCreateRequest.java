package com.datanest.dataservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 创建数据 API 请求（Sprint 10 F2）。
 */
@Data
@Schema(description = "创建数据 API 请求")
public class DataApiCreateRequest {

    @Schema(description = "API 名称", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "API 名称不能为空")
    @Size(max = 100, message = "API 名称最长 100 字符")
    private String name;

    @Schema(description = "对外路径（可传自定义段 orders 或完整 /open-api/v1/orders，统一归一为完整形态）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "API 路径不能为空")
    private String path;

    @Schema(description = "数据源 ID（内置 Doris 为 -1）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "数据源不能为空")
    private Long datasourceId;

    @Schema(description = "库名", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "库名不能为空")
    @Size(max = 100)
    private String databaseName;

    @Schema(description = "Schema 名（MySQL/Doris 为空）")
    @Size(max = 100)
    private String schemaName;

    @Schema(description = "表名", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "表名不能为空")
    @Size(max = 100)
    private String tableName;

    @Schema(description = "关联元数据表 ID（从元数据/资产带入时传）")
    private Long metadataTableId;

    @Schema(description = "参数化筛选（EQ/RANGE，AND 组合）")
    @Valid
    private List<ApiParamDef> filters;

    @Schema(description = "返回字段白名单（空 = 全部字段）")
    private List<String> fields;

    @Schema(description = "排序（如 cnt DESC）")
    private String orderBy;

    @Schema(description = "是否分页：1 启用（默认）/ 0 关闭")
    private Integer paginated;

    @Schema(description = "pageSize 上限（默认 100）")
    private Integer pageSizeMax;
}
