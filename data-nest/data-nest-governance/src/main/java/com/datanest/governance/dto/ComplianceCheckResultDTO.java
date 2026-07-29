package com.datanest.governance.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class ComplianceCheckResultDTO {

    private Long id;

    private Long standardId;

    private String standardName;

    private String objectType;

    private String objectPath;

    private String violationType;

    private Long tableId;

    private String tableName;

    private Long columnId;

    private String columnName;

    private String objectName;

    private String actualValue;

    private String expectedValue;

    private List<ApplicableStandardDTO> applicableStandards;

    private Integer isCompliant;

    private LocalDateTime checkedAt;

    @Data
    public static class ApplicableStandardDTO {
        private String standardName;
        private String ruleType;
        private String ruleValue;
        private List<String> allowedTypes;
    }
}
