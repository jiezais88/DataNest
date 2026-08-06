package com.datanest.governance.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class NamingStandardDTO {

    private Long id;

    private String name;

    private String appliesTo;

    private String ruleType;

    private String ruleValue;

    private Long targetStandardId;

    private String targetStandardName;

    private Integer priority;

    private Integer enabled;

    private String description;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Long createdBy;

    private Long updatedBy;

    private String createdByName;

    private String updatedByName;
}
