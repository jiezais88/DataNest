package com.datanest.engineering.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "DAG 项目 DTO")
@Data
public class DagProjectDTO {
    @Schema(description = "项目 ID", example = "1234567890123456789")
    private Long id;
    @Schema(description = "项目名称")
    private String name;
    @Schema(description = "项目描述")
    private String description;
    @Schema(description = "创建时间（ISO 8601）")
    private LocalDateTime createdAt;
    @Schema(description = "更新时间（ISO 8601）")
    private LocalDateTime updatedAt;
    @Schema(description = "创建人 ID", example = "1234567890123456789")
    private Long createdBy;
    @Schema(description = "更新人 ID", example = "1234567890123456789")
    private Long updatedBy;
    @Schema(description = "创建人用户名")
    private String createdByName;
    @Schema(description = "更新人用户名")
    private String updatedByName;
    /** Sprint 3 性能优化：项目下的 DAG 数量，避免前端 N+1 */
    @Schema(description = "项目下的 DAG 数量")
    private Long dagCount;
}
