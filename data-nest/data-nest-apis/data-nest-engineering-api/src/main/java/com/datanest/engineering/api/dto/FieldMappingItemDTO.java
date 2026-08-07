package com.datanest.engineering.api.dto;

import lombok.Data;

/**
 * 字段映射项（与 task-core FieldMappingItem 结构一致）。
 */
@Data
public class FieldMappingItemDTO {

    private String sourceColumn;

    private String targetColumn;

    private String targetType;
}
