package com.datanest.engineering.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/**
 * 从模板一键创建任务请求（Sprint 7 DD-09）。
 */
@Schema(description = "从模板一键创建任务请求")
@Data
public class TemplateCreateTaskRequest {

    @Schema(description = "任务名称")
    @NotBlank(message = "任务名称不能为空")
    @Size(max = 50, message = "任务名称最多 50 个字符")
    private String name;

    /** 占位符取值：key → 用户填写值（DATASOURCE 类型为数据源 ID 字符串） */
    @Schema(description = "占位符取值：key → 用户填写值（DATASOURCE 类型为数据源 ID 字符串）")
    private Map<String, String> values;
}
