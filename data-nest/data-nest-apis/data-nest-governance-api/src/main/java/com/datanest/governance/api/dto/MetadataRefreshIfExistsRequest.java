package com.datanest.governance.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 元数据刷新请求（表已存在才刷新）。
 * <p>
 * 对齐原 MetadataRegistrationService.refreshIfExists 的语义：
 * 内置 Doris 表（datasource_id=-1）在元数据中存在才刷新列结构 + column_count，不存在则静默跳过。
 * 微服务化 4.2 契约补漏：列结构由调用方从 Doris information_schema 提取后随本请求上报
 * （4.1 仅上报 columnCount，ALTER 后的列结构变化会丢失）。
 */
@Data
public class MetadataRefreshIfExistsRequest {

    /** 目标库名 */
    private String databaseName;

    /** 目标表名 */
    private String tableName;

    /** 字段数量（由调用方从 Doris 提取后上报；传了 columns 时以 columns.size() 为准） */
    private Integer columnCount;

    /** 字段列表（可空；非空时服务端顺带 refreshColumns，对齐原 refreshIfExists 语义） */
    private List<MetadataRegisterColumnDTO> columns;

    /** 操作人 ID（可选；非空时回写 updated_by） */
    private Long operatorId;
}
