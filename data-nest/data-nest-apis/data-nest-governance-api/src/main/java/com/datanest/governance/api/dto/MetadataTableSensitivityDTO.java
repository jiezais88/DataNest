package com.datanest.governance.api.dto;

import lombok.Data;

/**
 * 元数据表敏感度信息（Sprint 10 F1/F2 数据服务闸门契约 DTO）。
 * <p>
 * 由 governance 的 metadata_table 行投影而来；数据服务据此判断 SQL 执行/API 创建是否命中机密拦截。
 */
@Data
public class MetadataTableSensitivityDTO {

    /** 数据源 ID（内置 Doris 恒为 -1） */
    private Long datasourceId;

    /** 数据库名 */
    private String databaseName;

    /** Schema 名（MySQL/Doris 为空） */
    private String schemaName;

    /** 表名 */
    private String tableName;

    /** 数据敏感度：PUBLIC 公开 / INTERNAL 内部 / CONFIDENTIAL 机密 */
    private String sensitivityLevel;

    /** 内部表生成对外 API 的超管强制开白标记（机密表恒为 0） */
    private Integer apiExempted;

    /** 元数据状态（ONLINE/OFFLINE） */
    private String sourceStatus;
}
