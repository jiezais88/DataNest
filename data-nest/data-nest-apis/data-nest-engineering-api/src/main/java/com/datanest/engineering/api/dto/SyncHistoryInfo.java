package com.datanest.engineering.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 同步执行历史信息。
 * <p>
 * 单条查询端点（停止 watcher 轮询 + 执行收尾重读用）服务端只查必要列，
 * 仅 id/syncJobId/status/errorMessage/sourceRows/targetRows/startTime/endTime/triggerType/retryCount 有值。
 */
@Data
public class SyncHistoryInfo {

    private Long id;

    private Long syncJobId;

    private Long dagExecutionId;

    private String triggerType;

    private String status;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Long durationMs;

    private Long sourceRows;

    private Long targetRows;

    private String errorMessage;

    /** 多表同步 per-table 明细 JSON 数组字符串 */
    private String tableResults;

    private Long parentHistoryId;

    private Integer retryCount;

    private LocalDateTime nextRetryAt;

    private LocalDateTime createdAt;
}
