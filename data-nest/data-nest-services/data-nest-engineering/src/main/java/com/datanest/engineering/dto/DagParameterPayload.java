package com.datanest.engineering.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * DAG 参数 DTO
 */
@Schema(description = "DAG 参数 DTO")
@Data
public class DagParameterPayload {

    @Schema(description = "参数 ID", example = "1234567890123456789")
    private Long id;

    @Schema(description = "DAG ID", example = "1234567890123456789")
    private Long dagId;

    @Schema(description = "参数名")
    private String paramName;

    @Schema(description = "参数类型")
    private String paramType;

    @Schema(description = "默认值")
    private String defaultValue;

    @Schema(description = "是否必填")
    private Boolean required;

    @Schema(description = "参数描述")
    private String description;

    @Schema(description = "创建时间（ISO 8601）")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间（ISO 8601）")
    private LocalDateTime updatedAt;
}
