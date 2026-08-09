package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "采集历史分页查询请求")
@Data
public class CollectHistoryQueryRequest {

    @Schema(description = "采集任务 ID", example = "1234567890123456789")
    private Long taskId;

    @Schema(description = "执行状态（RUNNING/SUCCESS/FAILED/TERMINATED）")
    private String status;

    @Schema(description = "关键字（按采集任务名称模糊匹配）")
    private String keyword;

    @Schema(description = "开始时间起（ISO 8601）")
    @NotNull(message = "开始时间起不能为空")
    private LocalDateTime startTimeFrom;

    @Schema(description = "开始时间止（ISO 8601）")
    @NotNull(message = "开始时间止不能为空")
    private LocalDateTime startTimeTo;

    @Schema(description = "页码，从 1 开始")
    private Integer page = 1;

    @Schema(description = "每页条数")
    private Integer pageSize = 10;
}
