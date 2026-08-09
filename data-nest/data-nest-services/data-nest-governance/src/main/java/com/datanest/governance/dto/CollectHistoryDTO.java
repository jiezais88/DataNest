package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "采集历史")
@Data
public class CollectHistoryDTO {

    @Schema(description = "历史 ID", example = "1234567890123456789")
    private Long id;

    @Schema(description = "采集任务 ID", example = "1234567890123456789")
    private Long taskId;

    @Schema(description = "采集任务名称")
    private String taskName;

    @Schema(description = "数据源 ID", example = "1234567890123456789")
    private Long datasourceId;

    @Schema(description = "触发方式（MANUAL/CRON）")
    private String triggerType;

    @Schema(description = "执行状态（RUNNING/SUCCESS/FAILED/TERMINATED）")
    private String status;

    @Schema(description = "开始时间（ISO 8601）")
    private LocalDateTime startedAt;

    @Schema(description = "结束时间（ISO 8601）")
    private LocalDateTime endedAt;

    @Schema(description = "执行耗时（毫秒）")
    private Long durationMs;

    @Schema(description = "采集库数")
    private Integer dbCount;

    @Schema(description = "采集表数")
    private Integer tableCount;

    @Schema(description = "采集字段数")
    private Integer columnCount;

    @Schema(description = "新增表数")
    private Integer addedTableCount;

    @Schema(description = "更新表数")
    private Integer updatedTableCount;

    @Schema(description = "删除表数")
    private Integer deletedTableCount;

    @Schema(description = "新增字段数")
    private Integer addedColumnCount;

    @Schema(description = "更新字段数")
    private Integer updatedColumnCount;

    @Schema(description = "删除字段数")
    private Integer deletedColumnCount;

    @Schema(description = "失败原因")
    private String errorMessage;

    @Schema(description = "创建时间（ISO 8601）")
    private LocalDateTime createdAt;

    @Schema(description = "变更明细列表（仅详情接口返回）")
    private List<CollectChangeDetailDTO> changeDetails;
}
