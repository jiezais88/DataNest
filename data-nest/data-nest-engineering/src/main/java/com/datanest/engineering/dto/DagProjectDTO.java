package com.datanest.engineering.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DagProjectDTO {
    private Long id;
    private String name;
    private String description;
    /** Sprint 3 P0-3：DS 项目 code */
    private Long dsProjectCode;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
