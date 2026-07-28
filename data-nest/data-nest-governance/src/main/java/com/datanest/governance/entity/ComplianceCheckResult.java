package com.datanest.governance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("compliance_check_result")
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

    private Integer isCompliant;

    private LocalDateTime checkedAt;
}
