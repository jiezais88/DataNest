package com.datanest.engineering.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DagProjectDTO {
    private Long id;
    private String name;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long createdBy;
    private Long updatedBy;
    private String createdByName;
    private String updatedByName;
    /** Sprint 3 性能优化：项目下的 DAG 数量，避免前端 N+1 */
    private Long dagCount;
}
