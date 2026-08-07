package com.datanest.governance.api.dto;

import lombok.Data;

/**
 * 元数据移除请求（DROP TABLE 场景：先删列再删表）。
 * <p>
 * 对齐原 MetadataRegistrationService.removeIfExists 的语义：
 * 内置 Doris 表（datasource_id=-1）在元数据中存在才删除，不存在则静默跳过。
 */
@Data
public class MetadataRemoveRequest {

    /** 目标库名 */
    private String databaseName;

    /** 目标表名 */
    private String tableName;
}
