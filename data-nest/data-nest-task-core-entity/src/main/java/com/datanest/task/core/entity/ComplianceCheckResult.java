package com.datanest.task.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 标准合规检查结果（不合规项明细）。
 * 从 governance 模块下沉至共享底座，供治理编排与 job/worker 执行侧共用。
 * Sprint6 扩展：增加 ignored/ignoredAt/ignoredBy 三字段，支持按具体不合规项忽略。
 */
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

    /**
     * 违规类型：NAMING（命名规范）/ TYPE（字段类型）。
     */
    private String violationType;

    private String actualValue;

    private String expectedValue;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<ApplicableStandard> applicableStandards;

    private Integer isCompliant;

    private LocalDateTime checkedAt;

    /** 是否已忽略：1=已忽略，0=未忽略（默认）。 */
    private Integer ignored;

    /** 忽略时间。 */
    private LocalDateTime ignoredAt;

    /** 忽略操作人（sys_user.id）。 */
    private Long ignoredBy;

    @Data
    public static class ApplicableStandard {
        private String standardName;
        private String ruleType;
        private String ruleValue;
        private List<String> allowedTypes;
    }
}
