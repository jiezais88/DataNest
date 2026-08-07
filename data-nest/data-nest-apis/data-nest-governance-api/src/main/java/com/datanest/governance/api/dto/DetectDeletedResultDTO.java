package com.datanest.governance.api.dto;

import lombok.Data;

/**
 * 删除表检测结果（微服务化 4.2 契约补漏）。
 * <p>
 * 变更明细已在服务端落库，计数是其副产品：worker 采集执行器用 deletedTableCount
 * 累加采集历史 finish 的 deleted_table_count 统计列（deletedColumnCount 仅透出，
 * 对齐原语义不计入 deleted_column_count）。
 */
@Data
public class DetectDeletedResultDTO {

    /** 置 OFFLINE 的表数 */
    private Integer deletedTableCount;

    /** 随表置 OFFLINE 的字段数（原实现不统计入采集历史，仅透出） */
    private Integer deletedColumnCount;
}
