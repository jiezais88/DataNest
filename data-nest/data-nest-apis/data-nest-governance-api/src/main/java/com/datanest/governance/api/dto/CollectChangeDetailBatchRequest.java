package com.datanest.governance.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 批量写入采集变更明细请求（对照 collect_change_detail 列，
 * 对齐 CollectExecutor.writeChangeDetail；createdAt 由服务端填当前时间）。
 */
@Data
public class CollectChangeDetailBatchRequest {

    private List<Item> items;

    @Data
    public static class Item {

        /** 变更类型（ADDED_TABLE/MODIFIED_TABLE/DELETED_TABLE/ADDED_COLUMN/MODIFIED_COLUMN_XXX/DELETED_COLUMN） */
        private String changeType;

        private String databaseName;

        private String schemaName;

        private String tableName;

        private String columnName;

        private String oldValue;

        private String newValue;
    }
}
