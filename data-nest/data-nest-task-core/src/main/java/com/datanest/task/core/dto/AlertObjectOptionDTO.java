package com.datanest.task.core.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 告警对象下拉选项（新增规则时选择 DAG / 同步任务 / 采集任务）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AlertObjectOptionDTO {

    private Long id;

    private String name;
}
