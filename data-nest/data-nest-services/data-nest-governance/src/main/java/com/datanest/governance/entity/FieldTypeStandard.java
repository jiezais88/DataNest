package com.datanest.governance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 字段类型标准。定义了某类字段允许的数据类型集合。
 * 从 governance 模块下沉至共享底座，供治理编排与 job/worker 执行侧共用。
 */
@Data
@TableName(value = "field_type_standard", autoResultMap = true)
public class FieldTypeStandard {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String name;

    private String category;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> allowedTypes;

    private String description;

    private Long createdBy;

    private Long updatedBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
