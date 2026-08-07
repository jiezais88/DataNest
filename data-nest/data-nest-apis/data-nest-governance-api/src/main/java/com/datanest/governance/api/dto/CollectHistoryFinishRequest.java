package com.datanest.governance.api.dto;

import lombok.Data;

/**
 * 采集历史收尾请求（对齐 CollectExecutor.finishHistory，统计列全部透出）。
 */
@Data
public class CollectHistoryFinishRequest {

    /** 终态（ExecutionStatus code：SUCCESS/FAILED/TERMINATED） */
    private String status;

    /** 错误信息（失败时） */
    private String errorMessage;

    /** 结束时间，ISO 格式 */
    private String endedAt;

    /** 耗时毫秒（为空时服务端按 startedAt~endedAt 计算） */
    private Long durationMs;

    private Integer dbCount;

    private Integer tableCount;

    private Integer columnCount;

    private Integer addedTableCount;

    private Integer updatedTableCount;

    private Integer deletedTableCount;

    private Integer addedColumnCount;

    private Integer updatedColumnCount;

    private Integer deletedColumnCount;
}
