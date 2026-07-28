package com.datanest.governance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

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
