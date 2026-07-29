package com.datanest.governance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName(value = "compliance_check_result", autoResultMap = true)
public class ComplianceCheckResult {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long standardId;

    private String standardName;

    private String objectType;

    private Long datasourceId;

    private String databaseName;

    private String schemaName;

    private Long tableId;

    private Long columnId;

    private String objectName;

    private String objectPath;

    private String violationType;

    private String actualValue;

    private String expectedValue;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<ApplicableStandard> applicableStandards;

    private Integer isCompliant;

    private LocalDateTime checkedAt;

    @Data
    public static class ApplicableStandard {
        private String standardName;
        private String ruleType;
        private String ruleValue;
        private List<String> allowedTypes;
    }
}
