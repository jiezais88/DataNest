package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Schema(description = "采集任务更新请求")
@Data
public class CollectTaskUpdateRequest {

    @Schema(description = "任务名称")
    @NotBlank(message = "任务名称不能为空")
    private String name;

    @Schema(description = "数据源 ID", example = "1234567890123456789")
    @NotNull(message = "数据源不能为空")
    private Long datasourceId;

    @Schema(description = "采集范围（库名列表）")
    @NotEmpty(message = "采集范围不能为空")
    private List<String> scope;

    @Schema(description = "采集模式（FULL/FULL_INCREMENT）")
    @NotBlank(message = "采集模式不能为空")
    private String collectMode;

    @Schema(description = "触发方式（MANUAL/CRON）")
    @NotBlank(message = "触发方式不能为空")
    private String triggerType;

    @Schema(description = "Cron 表达式（triggerType=CRON 时必填）")
    private String cronExpression;

    @Schema(description = "任务描述")
    private String description;

    @Schema(description = "任务状态（NEVER_EXECUTED/RUNNING/SUCCESS/FAILED/TERMINATED）")
    @NotBlank(message = "任务状态不能为空")
    private String status;
}
