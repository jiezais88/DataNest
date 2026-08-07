package com.datanest.governance.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 质量任务自动触发绑定查询请求（按 DAG 节点 ID 批量）。
 */
@Data
public class AutoTriggerBindingRequest {

    /** DAG 节点 ID 列表（dag_node.id） */
    private List<Long> dagNodeIds;
}
