package com.datanest.governance.api.dto;

import lombok.Data;

/**
 * 元数据字段 diff upsert 结果（微服务化 4.2 契约补漏）。
 * <p>
 * 变更明细已在服务端落库，计数是其副产品：worker 采集执行器用各计数
 * 累加采集历史 finish 的 added/updated/deleted_column_count 统计列。
 * 注意：原 CollectExecutor 把复活字段计入 added，本 DTO 拆出 resurrectedCount，
 * 由调用方按需合并（addedCount + resurrectedCount = 原 added 语义）。
 */
@Data
public class UpsertColumnsResultDTO {

    /** 新增字段数（不含复活） */
    private Integer addedCount;

    /** 属性变更字段数 */
    private Integer updatedCount;

    /** 本次清单中消失而置 OFFLINE 的字段数 */
    private Integer deletedCount;

    /** 已 OFFLINE 字段重新出现的复活数（原实现计入 added） */
    private Integer resurrectedCount;
}
