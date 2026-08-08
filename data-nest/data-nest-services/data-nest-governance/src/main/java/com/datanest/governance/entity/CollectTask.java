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
@TableName(value = "collect_task", autoResultMap = true)
public class CollectTask {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String name;

    private Long datasourceId;

    private String datasourceName;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> scope;

    private String collectMode;

    private String triggerType;

    private String cronExpression;

    private String status;

    private LocalDateTime lastExecuteTime;

    private Long lastHistoryId;

    private String description;

    /** PowerJob jobId（调度统一走此字段，旧 xxl_job_id 列已随 V1.2.0 删除） */
    private Long schedulerJobId;

    private Integer scheduleEnabled;

    private LocalDateTime nextExecutionTime;

    private Long createdBy;

    private Long updatedBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
