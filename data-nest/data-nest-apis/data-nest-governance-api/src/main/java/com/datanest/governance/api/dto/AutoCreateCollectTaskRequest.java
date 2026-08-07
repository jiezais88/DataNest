package com.datanest.governance.api.dto;

import lombok.Data;

/**
 * 数据源保存后自动创建采集任务请求（engineering 下沉到 governance 的逻辑入参）。
 * <p>
 * 携带连接属性是为了让 governance 本地推导采集范围（schema 层/库名），
 * 不再回读 engineering 的数据源表。
 */
@Data
public class AutoCreateCollectTaskRequest {

    private Long datasourceId;

    private String datasourceName;

    /** 数据源类型（mysql/postgresql/oracle/sqlserver 等，用于推导采集范围） */
    private String type;

    private String databaseName;

    private String schemaName;

    /** 部分类型（如 oracle）用用户名作为默认 schema */
    private String username;

    /** 操作人（engineering 侧当前登录用户，无登录态时为 0） */
    private Long createdBy;
}
