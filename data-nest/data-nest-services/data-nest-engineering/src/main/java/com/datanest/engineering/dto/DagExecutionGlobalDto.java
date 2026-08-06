package com.datanest.engineering.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 全局 DAG 执行历史 DTO（Sprint 3 PRD §6.7.3）
 * - 用于 /api/engineering/dag-executions 列表
 * - 携带统计数字 + 节点执行明细，供展开行展示微缩 DAG
 */
@Data
public class DagExecutionGlobalDto {

    private Long id;

    private Long dagId;

    private String dagName;

    private String triggerType;          // MANUAL / CRON

    private String status;               // RUNNING / SUCCESS / FAILED / TERMINATED

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    /** endTime - startTime 毫秒；为 null 表示未结束 */
    private Long durationMs;

    private Integer nodeCount;           // 该次执行下 node_execution 总数

    private Integer successCount;        // 节点状态 = SUCCESS

    private Integer failedCount;         // 节点状态 = FAILED

    private Integer skippedCount;        // 节点状态 = SKIPPED

    private List<NodeExecutionDTO> nodeExecutions;   // 展开行：节点执行明细
}
