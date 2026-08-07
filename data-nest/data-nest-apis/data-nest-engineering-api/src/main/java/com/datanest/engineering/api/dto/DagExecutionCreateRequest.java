package com.datanest.engineering.api.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 创建 DAG 执行实例请求（ensureDagExecution：插执行 + 批量插节点，一个事务）。
 */
@Data
public class DagExecutionCreateRequest {

    private Long dagId;

    /** DS 流程实例 ID；可空（先占位后回写） */
    private Long dsProcessInstanceId;

    /** MANUAL / CRON；可空，默认 MANUAL（dag_execution.trigger_type NOT NULL） */
    private String triggerType;

    /** 默认 RUNNING */
    private String status;

    /** 可空，默认服务端当前时间 */
    private LocalDateTime startTime;

    private String resolvedParams;

    private String edgesSnapshot;

    /** 预创建节点（status 缺省 WAITING） */
    private List<NodeSeed> nodes;

    @Data
    public static class NodeSeed {

        private String nodeId;

        private String nodeName;

        private String nodeType;

        private String status;
    }
}
