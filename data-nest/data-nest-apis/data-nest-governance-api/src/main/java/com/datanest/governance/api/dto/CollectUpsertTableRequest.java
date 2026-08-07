package com.datanest.governance.api.dto;

import lombok.Data;

/**
 * 元数据表 upsert 请求（对齐 CollectExecutor.upsertTable：
 * find-or-create + 更新 comment/source_status/last_collect_history_id）。
 */
@Data
public class CollectUpsertTableRequest {

    private Long datasourceId;

    private String databaseName;

    private String schemaName;

    private String tableName;

    private String tableComment;

    /** 本次采集历史 ID（回写 last_collect_history_id 与变更明细） */
    private Long collectHistoryId;
}
