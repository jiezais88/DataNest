package com.datanest.common.dto;

import lombok.Data;

@Data
public class DataSourceReferenceDTO {

    private Long taskId;

    private String taskName;

    private String status;

    private String type;

    /** 仅在 type=SYNC 时使用：源数据库/Schema */
    private String sourceDatabase;

    private String sourceSchema;

    /** 仅在 type=SYNC 时使用：目标 Doris 库/表 */
    private String targetDatabase;

    private String targetTable;

    /** 同步模式 / 触发方式等摘要 */
    private String syncMode;

    private String triggerType;
}
