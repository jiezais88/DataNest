package com.datanest.engineering.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * DAG 节点执行日志行 DTO
 */
@Schema(description = "DAG 节点执行日志行 DTO")
@Data
public class NodeExecutionLogDTO {

    @Schema(description = "日志 ID", example = "1234567890123456789")
    private Long id;

    @Schema(description = "所属执行实例 ID", example = "1234567890123456789")
    private Long executionId;

    @Schema(description = "节点标识")
    private String nodeId;

    @Schema(description = "日志级别（如 INFO/ERROR）")
    private String level;

    @Schema(description = "日志内容")
    private String message;

    @Schema(description = "行号")
    private Integer lineNum;

    @Schema(description = "创建时间（ISO 8601）")
    private LocalDateTime createdAt;
}
