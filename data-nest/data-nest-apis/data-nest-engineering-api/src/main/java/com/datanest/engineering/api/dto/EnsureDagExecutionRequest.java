package com.datanest.engineering.api.dto;

import lombok.Data;

/**
 * 按 PowerJob 工作流实例补齐 DAG 执行记录的请求（P3）。
 * worker 节点 handler 在处理 cron 触发的实例时调用：若该 wfInstanceId 尚无 dag_execution
 * 则创建（triggerType=SCHEDULED）并预创建全量 WAITING node_execution，返回 dagExecutionId。
 */
@Data
public class EnsureDagExecutionRequest {

    private Long dagId;

    /** PowerJob 工作流实例 ID */
    private Long wfInstanceId;
}
