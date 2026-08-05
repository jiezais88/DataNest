package com.datanest.task.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 命名规范。定义了表/字段命名的规则（PREFIX/SUFFIX/REGEX）。
 * 从 governance 模块下沉至共享底座，供治理编排与 job/worker 执行侧共用。
 */
@Data
@TableName("naming_standard")
public class NamingStandard {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String name;

    private String appliesTo;

    private String ruleType;

    private String ruleValue;

    private Long targetStandardId;

    private Integer priority;

    private Integer enabled;

    private String description;

    private Long createdBy;

    private Long updatedBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
