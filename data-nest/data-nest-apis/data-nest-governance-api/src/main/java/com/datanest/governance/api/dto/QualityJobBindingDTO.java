package com.datanest.governance.api.dto;

import lombok.Data;

/**
 * 质量任务自动触发绑定信息。
 * <p>
 * 表示某个 DAG 节点（objectId）上绑定的启用质量任务，供自动触发对账判定补发范围。
 */
@Data
public class QualityJobBindingDTO {

    /** 质量任务 ID */
    private Long jobId;

    /** 质量任务名称 */
    private String jobName;

    /** 绑定的对象 ID（DAG 节点为 dag_node.id） */
    private Long objectId;
}
