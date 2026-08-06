package com.datanest.governance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class CollectTaskUpdateRequest {

    @NotBlank(message = "任务名称不能为空")
    private String name;

    @NotNull(message = "数据源不能为空")
    private Long datasourceId;

    @NotEmpty(message = "采集范围不能为空")
    private List<String> scope;

    @NotBlank(message = "采集模式不能为空")
    private String collectMode;

    @NotBlank(message = "触发方式不能为空")
    private String triggerType;

    private String cronExpression;

    private String description;

    @NotBlank(message = "任务状态不能为空")
    private String status;
}
