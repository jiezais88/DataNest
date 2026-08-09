package com.datanest.engineering.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "DAG 执行实例 DTO")
@Data
public class DagExecutionDTO {
    @Schema(description = "执行实例 ID", example = "1234567890123456789")
    private Long id;
    @Schema(description = "DAG ID", example = "1234567890123456789")
    private Long dagId;
    @Schema(description = "DAG 名称")
    private String dagName;
    @Schema(description = "触发方式（MANUAL/SCHEDULED）")
    private String triggerType;
    @Schema(description = "执行状态（RUNNING/SUCCESS/FAILED/TERMINATED）")
    private String status;
    @Schema(description = "开始时间（ISO 8601）")
    private LocalDateTime startTime;
    @Schema(description = "结束时间（ISO 8601）")
    private LocalDateTime endTime;
    @Schema(description = "耗时（毫秒）")
    private Long durationMs;
    @Schema(description = "边快照 JSON（执行时刻的连线固化）")
    private String edgeSnapshot;
    @Schema(description = "错误信息")
    private String errorMessage;
    @Schema(description = "节点执行明细列表")
    private List<NodeExecutionDTO> nodeExecutions;
}
