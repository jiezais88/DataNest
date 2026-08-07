package com.datanest.governance.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 数据源在治理域的引用情况（删除数据源前的引用检查）。
 * <p>
 * collectTasks / qualityRules 为阻断删除的引用；metadataTables 为该数据源下已采集的
 * 元数据表（随 cascade-delete 级联清理，不作为阻断引用）。
 */
@Data
public class DatasourceReferencesDTO {

    private List<ReferenceItemDTO> collectTasks;

    private List<ReferenceItemDTO> metadataTables;

    private List<ReferenceItemDTO> qualityRules;
}
