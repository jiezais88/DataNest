package com.datanest.alert.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 告警对象下拉选项（新增规则时选择 DAG / 同步任务 / 采集任务）。
 * DAG 类型使用 children 表达「项目 → DAG」树形结构。
 */
@Schema(description = "告警对象下拉选项")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AlertObjectOptionDTO {

    @Schema(description = "对象 ID", example = "1234567890123456789")
    private Long id;

    @Schema(description = "对象名称")
    private String name;

    @Schema(description = "树形子节点（仅 DAG 类型使用，表达「项目 → DAG」结构）")
    private List<AlertObjectOptionDTO> children;

    public AlertObjectOptionDTO(Long id, String name) {
        this.id = id;
        this.name = name;
    }
}
