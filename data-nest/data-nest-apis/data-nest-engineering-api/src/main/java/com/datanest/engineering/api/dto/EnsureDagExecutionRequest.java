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

    /**
     * Sprint 7 NG5：嵌套工作流（同步子 DAG）场景下，子工作流继承父工作流 initParams 中的
     * 父 DAG 执行 ID，worker 识别归属不匹配后透传，用于主→子参数下发；cron 触发为 null。
     */
    private Long parentDagExecutionId;
}
