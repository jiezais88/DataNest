package com.datanest.engineering.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 更新执行队列请求（Sprint 11 F3）
 * <p>
 * 名称系统队列不可改；并发/描述可改。自定义队列名称也可改（同步改绑定 DAG 的 queue_name）。
 */
@Schema(description = "更新执行队列请求")
@Data
public class ExecutionQueueUpdateRequest {

    @Schema(description = "队列名（唯一；系统内置队列不可改）")
    private String queueName;

    @Schema(description = "最大并发数（1~100）")
    @NotNull(message = "最大并发数不能为空")
    @Min(value = 1, message = "最大并发数至少为 1")
    @Max(value = 100, message = "最大并发数不能超过 100")
    private Integer maxConcurrency;

    @Schema(description = "队列描述")
    private String description;
}
