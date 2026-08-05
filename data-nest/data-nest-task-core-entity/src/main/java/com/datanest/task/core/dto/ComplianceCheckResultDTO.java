package com.datanest.task.core.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 标准合规检查结果 DTO。从 governance 模块下沉至共享底座。
 * Sprint6 扩展：增加 ignored/ignoredAt/ignoredBy 三字段。
 */
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

    /** 是否已忽略：1=已忽略，0=未忽略。 */
    private Integer ignored;

    /** 忽略时间。 */
    private LocalDateTime ignoredAt;

    /** 忽略操作人。 */
    private Long ignoredBy;

    @Data
    public static class ApplicableStandardDTO {
        private String standardName;
        private String ruleType;
        private String ruleValue;
        private List<String> allowedTypes;
    }
}
