package com.datanest.task.core.dto;

import lombok.Data;

import java.util.List;

/**
 * 多表同步中单张源表的详细映射配置。
 */
@Data
public class SourceTableDetail {

    private String sourceTable;

    private String targetTable;

    private List<FieldMappingItem> fieldMapping;
}
