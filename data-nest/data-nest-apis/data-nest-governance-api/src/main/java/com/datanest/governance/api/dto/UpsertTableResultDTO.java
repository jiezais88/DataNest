package com.datanest.governance.api.dto;

import lombok.Data;

/**
 * 元数据表 upsert 结果（微服务化 4.2 契约补漏）。
 * <p>
 * 变更明细已在服务端落库，计数是其副产品：worker 采集执行器用 isNew/changed
 * 累加采集历史 finish 的 added_table_count/updated_table_count 统计列。
 */
@Data
public class UpsertTableResultDTO {

    /** 元数据表 ID */
    private Long tableId;

    /** 是否本次新增（对应原 CollectExecutor.TableChange.added） */
    private Boolean isNew;

    /** 是否发生变更（表注释变化，对应原 CollectExecutor.TableChange.updated） */
    private Boolean changed;
}
