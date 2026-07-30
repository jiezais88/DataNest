package com.datanest.task.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.datanest.task.core.dto.FieldMappingItem;
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

    private Integer scheduleEnabled;

    /** Sprint 3 Phase 8：多表结构化配置 JSONB（字符串直接存储） */
    private String sourceTablesDetail;

    /** Sprint 3 Phase 8：读取速率限制（MB/s，0=不限制） */
    private Integer readRateLimitMbps;

    /** Sprint 3 Phase 8：写入速率限制（行/秒，0=不限制） */
    private Integer writeRateLimitRowsPerSecond;

    /** Sprint 3 Phase 8：限流总开关 0/1 */
    private Integer rateLimitEnabled;

    private Integer xxlJobId;

    private String description;

    private LocalDateTime lastExecuteTime;

    private Long lastHistoryId;

    private Long createdBy;

    private Long updatedBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
