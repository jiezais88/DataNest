package com.datanest.dataservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据 API 分页列表项（Sprint 10 F2）。
 */
@Data
@Schema(description = "数据 API 分页列表项")
public class DataApiPageItem {

    @Schema(description = "API ID")
    private Long id;

    @Schema(description = "API 名称")
    private String name;

    @Schema(description = "对外路径（/open-api/v1/{段}）")
    private String path;

    @Schema(description = "请求方法")
    private String method;

    @Schema(description = "数据源 ID（内置 Doris 为 -1）")
    private Long datasourceId;

    @Schema(description = "数据源显示名（内置 Doris = Doris 数仓；查不到降级 null）")
    private String datasourceName;

    @Schema(description = "库名")
    private String databaseName;

    @Schema(description = "Schema 名")
    private String schemaName;

    @Schema(description = "表名")
    private String tableName;

    @Schema(description = "查询定义形态：TABLE_SELECT 选表 / CUSTOM_SQL 自定义 SQL")
    private String queryType;

    @Schema(description = "源表敏感度：PUBLIC 公开 / INTERNAL 内部 / CONFIDENTIAL 机密；governance 不可达时降级 null")
    private String sensitivityLevel;

    @Schema(description = "状态：CREATED 未发布 / PUBLISHED 可调用 / DISABLED 下线")
    private String status;

    @Schema(description = "绑定 Key 数")
    private Long boundKeyCount;

    @Schema(description = "近 7 天调用量")
    private Long calls7d;

    @Schema(description = "创建人 ID")
    private Long createdBy;

    @Schema(description = "创建人用户名")
    private String createdByName;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "修改人用户名")
    private String updatedByName;

    @Schema(description = "修改时间")
    private LocalDateTime updatedAt;
}
