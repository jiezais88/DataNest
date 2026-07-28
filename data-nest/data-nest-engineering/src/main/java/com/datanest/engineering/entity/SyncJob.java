package com.datanest.engineering.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.datanest.engineering.dto.FieldMappingItem;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName(value = "sync_job", autoResultMap = true)
public class SyncJob {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String name;

    private Long sourceDatasourceId;

    private Long targetDatasourceId;

    private String sourceDatabase;

    private String sourceSchema;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> sourceTables;

    private String syncMode;

    private String incrementalField;

    private String triggerType;

    private String cronExpression;

    private Integer retryTimes;

    private Integer retryInterval;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<FieldMappingItem> fieldMapping;

    private String status;

    private String executionStatus;

    private String targetDatabase;

    private String targetTable;

    private LocalDateTime nextExecutionTime;

    private Integer retryCount;

    private LocalDateTime nextRetryAt;

    private Integer scheduleEnabled;

    private Integer xxlJobId;

    private String description;

    private Long createdBy;

    private Long updatedBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
