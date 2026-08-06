package com.datanest.task.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 质量任务 - 质量规则 多对多关联（Sprint 7 规则独立化）。
 * <p>
 * 质量规则可独立创建（不强制绑定任务），任务通过本关联表引用规则。
 * 原 {@code quality_rule.job_id} 仅作历史兼容，不再作为规则归属的强依据。
 */
@Data
@TableName("quality_job_rule")
public class QualityJobRule {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 质量任务 ID */
    private Long jobId;

    /** 质量规则 ID */
    private Long ruleId;

    private LocalDateTime createdAt;
}
