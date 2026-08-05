package com.datanest.task.core.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 告警对象下拉选项（新增规则时选择 DAG / 同步任务 / 采集任务）。
 * DAG 类型使用 children 表达「项目 → DAG」树形结构。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AlertObjectOptionDTO {

    private Long id;

    private String name;

    /** 树形子节点（仅 DAG 类型使用） */
    private List<AlertObjectOptionDTO> children;

    public AlertObjectOptionDTO(Long id, String name) {
        this.id = id;
        this.name = name;
    }
}
