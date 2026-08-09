package com.datanest.task.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "多表同步中单张源表的详细映射配置")
@Data
public class SourceTableDetail {

    @Schema(description = "源表名")
    private String sourceTable;

    @Schema(description = "目标表名")
    private String targetTable;

    @Schema(description = "字段映射列表")
    private List<FieldMappingItem> fieldMapping;
}
