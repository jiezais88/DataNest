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
