package com.datanest.engineering.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * DAG 自定义参数
 * 对应表 dag_parameter
 */
@Data
@TableName("dag_parameter")
public class DagParameter {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long dagId;

    private String paramName;

    private String paramType;        // STRING / NUMBER / DATE / BOOLEAN

    private String defaultValue;

    private Integer required;        // 1 必填，0 可选

    private String description;

    private Long createdBy;

    private Long updatedBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
