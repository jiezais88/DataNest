package com.datanest.engineering.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 创建执行队列请求（Sprint 11 F3）
 */
@Schema(description = "创建执行队列请求")
@Data
public class ExecutionQueueCreateRequest {

    @Schema(description = "队列名（唯一，字母/数字/下划线，2~32 位）")
    @NotBlank(message = "队列名不能为空")
    private String queueName;

    @Schema(description = "最大并发数（1~100）")
    @NotNull(message = "最大并发数不能为空")
    @Min(value = 1, message = "最大并发数至少为 1")
    @Max(value = 100, message = "最大并发数不能超过 100")
    private Integer maxConcurrency;

    @Schema(description = "队列描述")
    private String description;
}
