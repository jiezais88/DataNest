package com.datanest.engineering.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * DAG 执行终态回写请求。
 * <p>
 * 服务端在落库后触发 DAG 完成副作用（进程内直接调 app-alert 的 dagFinished）。
 */
@Data
public class DagExecutionFinalizeRequest {

    private String status;

    private LocalDateTime endTime;

    private Long durationMs;

    /** 失败原因（可空；P3 PowerJob 同步器收尾 FAILED 时携带） */
    private String errorMessage;
}
