package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "元数据管理左侧树节点（搜索后一次性返回完整树路径）")
@Data
public class MetadataTreeNodeDTO {

    @Schema(description = "节点唯一 ID")
    private String id;

    @Schema(description = "节点类型（datasource/database/schema/table）")
    private String type;

    @Schema(description = "节点名称")
    private String name;

    @Schema(description = "数据源连接是否存在")
    private Boolean exists;

    @Schema(description = "数据来源类型（BUILTIN_DORIS/EXTERNAL）")
    private String sourceType;

    @Schema(description = "数据源 ID", example = "1234567890123456789")
    private Long datasourceId;

    @Schema(description = "数据源类型（如 MYSQL/DORIS）")
    private String datasourceType;

    @Schema(description = "库名")
    private String databaseName;

    @Schema(description = "Schema 名")
    private String schemaName;

    @Schema(description = "数量（表节点为字段数，database/schema 节点为子表数量）")
    private Integer count;

    @Schema(description = "子节点列表")
    private List<MetadataTreeNodeDTO> children;
}
