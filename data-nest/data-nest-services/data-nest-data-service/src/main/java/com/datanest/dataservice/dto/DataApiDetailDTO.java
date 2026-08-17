package com.datanest.dataservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 数据 API 详情（Sprint 10 F2）：定义 + 自动文档 + 绑定 Key + 近 7 天调用。
 */
@Data
@Schema(description = "数据 API 详情")
public class DataApiDetailDTO {

    @Schema(description = "API ID")
    private Long id;

    @Schema(description = "API 名称")
    private String name;

    @Schema(description = "对外路径")
    private String path;

    @Schema(description = "请求方法")
    private String method;

    @Schema(description = "数据源 ID（内置 Doris 为 -1）")
    private Long datasourceId;

    @Schema(description = "数据源显示名")
    private String datasourceName;

    @Schema(description = "库名")
    private String databaseName;

    @Schema(description = "Schema 名")
    private String schemaName;

    @Schema(description = "表名")
    private String tableName;

    @Schema(description = "源表敏感度：PUBLIC / INTERNAL / CONFIDENTIAL；governance 不可达时降级 null")
    private String sensitivityLevel;

    @Schema(description = "关联元数据表 ID")
    private Long metadataTableId;

    @Schema(description = "API 定义（filters + fields；CUSTOM_SQL 形态含 queryType + sqlParams）")
    private DataApiDefinition definition;

    @Schema(description = "查询定义形态：TABLE_SELECT 选表 / CUSTOM_SQL 自定义 SQL")
    private String queryType;

    @Schema(description = "自定义 SQL 文本（CUSTOM_SQL 形态）")
    private String sqlText;

    @Schema(description = "自定义 SQL 参数定义（CUSTOM_SQL 形态）")
    private List<CustomSqlParamDef> sqlParams;

    @Schema(description = "SQL 涉及表清单 JSON（[{datasourceId,database,schema,table}]）")
    private String involvedTables;

    @Schema(description = "排序")
    private String orderBy;

    @Schema(description = "是否分页：1 启用 / 0 关闭")
    private Integer paginated;

    @Schema(description = "pageSize 上限")
    private Integer pageSizeMax;

    @Schema(description = "状态：CREATED / PUBLISHED / DISABLED")
    private String status;

    @Schema(description = "自动文档（参数说明 + curl 示例）")
    private DataApiDocDTO doc;

    @Schema(description = "绑定 Key 列表")
    private List<ApiKeyBriefDTO> boundKeys;

    @Schema(description = "近 7 天调用量")
    private Long calls7d;

    @Schema(description = "创建人 ID")
    private Long createdBy;

    @Schema(description = "创建人用户名")
    private String createdByName;

    @Schema(description = "更新人用户名")
    private String updatedByName;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
