package com.datanest.engineering.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 同步执行历史终态回写请求。
 */
@Data
public class SyncHistoryFinishRequest {

    private String status;

    private String errorMessage;

    private Long sourceRows;

    private Long targetRows;

    /** 多表明细 JSON 字符串；可空 */
    private String tableResults;

    private LocalDateTime endTime;

    private Long durationMs;
}
