package com.datanest.engineering.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * DAG 项目（DAG 命名空间，全局唯一）
 * 对应表 dag_project
 */
@Data
@TableName("dag_project")
public class DagProject {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String name;

    private String description;

    /** DS 项目 code（关联 DolphinScheduler t_ds_project.code）— Sprint 3 P0-3 */
    private Long dsProjectCode;

    private Long createdBy;

    private Long updatedBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
