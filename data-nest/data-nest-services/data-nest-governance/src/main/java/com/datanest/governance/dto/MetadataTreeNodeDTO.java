package com.datanest.governance.dto;

import lombok.Data;

import java.util.List;

/**
 * 元数据管理左侧树节点，用于搜索后一次性返回完整树路径。
 */
@Data
public class MetadataTreeNodeDTO {

    private String id;

    /**
     * datasource / database / schema / table
     */
    private String type;

    private String name;

    private Boolean exists;

    private String sourceType;

    private Long datasourceId;

    private String datasourceType;

    private String databaseName;

    private String schemaName;

    /**
     * 表节点为字段数，database/schema 节点为子表数量。
     */
    private Integer count;

    private List<MetadataTreeNodeDTO> children;
}
