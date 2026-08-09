package com.datanest.task.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "质量检查批次分页查询请求")
@Data
public class QualityCheckQueryRequest {

    @Schema(description = "页码，从 1 开始")
    private Long page = 1L;

    @Schema(description = "每页条数")
    private Long pageSize = 10L;

    @Schema(description = "质量任务 ID 过滤", example = "1234567890123456789")
    private Long jobId;

    @Schema(description = "触发方式过滤（MANUAL/SCHEDULED/AUTO_TRIGGER）")
    private String triggerType;

    @Schema(description = "批次状态过滤（RUNNING/SUCCESS/PARTIAL_FAILED/FAILED）")
    private String status;

    @Schema(description = "开始时间下界（ISO 8601，如 2026-08-02T12:00:00，按 started_at 过滤）")
    private String startTimeFrom;

    @Schema(description = "开始时间上界（ISO 8601）")
    private String startTimeTo;
}
