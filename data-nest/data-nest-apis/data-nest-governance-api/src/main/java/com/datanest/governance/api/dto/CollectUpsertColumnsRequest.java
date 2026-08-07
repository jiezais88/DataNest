package com.datanest.governance.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 元数据字段 diff upsert 请求（对齐 CollectExecutor.upsertColumns）。
 * <p>
 * databaseName/schemaName/tableName/tableIsNew 用于变更明细记录：
 * 新增表的字段随表记 ADDED_TABLE，已存在表的新增字段记 ADDED_COLUMN。
 */
@Data
public class CollectUpsertColumnsRequest {

    private Long tableId;

    /** 本次采集历史 ID（回写 last_collect_history_id 与变更明细） */
    private Long collectHistoryId;

    private String databaseName;

    private String schemaName;

    private String tableName;

    /** 所属表是否本次新增（决定变更明细类型 ADDED_TABLE / ADDED_COLUMN） */
    private Boolean tableIsNew;

    private List<ColumnItem> columns;

    @Data
    public static class ColumnItem {

        private String columnName;

        private String dataType;

        private String columnComment;

        private Integer ordinalPosition;

        private Boolean nullable;

        private String columnDefault;
    }
}
