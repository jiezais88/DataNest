package com.datanest.engineering.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * DAG 参数 DTO
 */
@Data
public class DagParameterPayload {

    private Long id;

    private Long dagId;

    private String paramName;

    private String paramType;

    private String defaultValue;

    private Boolean required;

    private String description;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
