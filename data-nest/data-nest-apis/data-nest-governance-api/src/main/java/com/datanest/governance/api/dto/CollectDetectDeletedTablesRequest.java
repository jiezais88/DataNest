package com.datanest.governance.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 删除表检测请求（对齐 CollectExecutor.detectDeletedTables）：
 * 同数据源+库+schema 下 ONLINE 但不在本次清单的表连同字段置 OFFLINE。
 * currentTableNames 为空时服务端直接跳过（对齐源 collectedTables.isEmpty() 时不做检测，
 * 避免清单未采完误判删除）。
 */
@Data
public class CollectDetectDeletedTablesRequest {

    private Long datasourceId;

    private String databaseName;

    private String schemaName;

    /** 本次采集历史 ID（回写 last_collect_history_id 与变更明细） */
    private Long collectHistoryId;

    /** 本次采集到的表名清单 */
    private List<String> currentTableNames;
}
