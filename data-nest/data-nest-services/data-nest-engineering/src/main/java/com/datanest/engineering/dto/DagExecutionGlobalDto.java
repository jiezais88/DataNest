package com.datanest.engineering.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 全局 DAG 执行历史 DTO（Sprint 3 PRD §6.7.3）
 * - 用于 /api/engineering/dag-executions 列表
 * - 携带统计数字 + 节点执行明细，供展开行展示微缩 DAG
 */
@Schema(description = "全局 DAG 执行历史 DTO（携带统计数字与节点执行明细）")
@Data
public class DagExecutionGlobalDto {

    @Schema(description = "执行实例 ID", example = "1234567890123456789")
    private Long id;

    @Schema(description = "DAG ID", example = "1234567890123456789")
    private Long dagId;

    @Schema(description = "DAG 名称")
    private String dagName;

    @Schema(description = "触发方式（MANUAL/SCHEDULED）")
    private String triggerType;          // MANUAL / SCHEDULED

    @Schema(description = "执行状态（RUNNING/SUCCESS/FAILED/TERMINATED）")
    private String status;               // RUNNING / SUCCESS / FAILED / TERMINATED

    @Schema(description = "开始时间（ISO 8601）")
    private LocalDateTime startTime;

    @Schema(description = "结束时间（ISO 8601）")
    private LocalDateTime endTime;

    /** endTime - startTime 毫秒；为 null 表示未结束 */
    @Schema(description = "耗时（毫秒）；为 null 表示未结束")
    private Long durationMs;

    @Schema(description = "该次执行下节点执行总数")
    private Integer nodeCount;           // 该次执行下 node_execution 总数

    @Schema(description = "成功节点数")
    private Integer successCount;        // 节点状态 = SUCCESS

    @Schema(description = "失败节点数")
    private Integer failedCount;         // 节点状态 = FAILED

    @Schema(description = "跳过节点数")
    private Integer skippedCount;        // 节点状态 = SKIPPED

    @Schema(description = "节点执行明细（展开行展示）")
    private List<NodeExecutionDTO> nodeExecutions;   // 展开行：节点执行明细
}
