package com.datanest.engineering.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 节点执行实例信息（全字段，含乐观锁 version）。
 */
@Data
public class NodeExecutionInfo {

    private Long id;

    private Long executionId;

    /** 非持久化字段，仅 running-with-dag 查询时由服务端 join 带入 */
    private Long dagId;

    private String nodeId;

    private String nodeName;

    private String nodeType;

    private String status;

    /** PowerJob 任务实例 ID（旧 dsTaskInstanceId 已随 P4 切流清理删除） */
    private Long powerjobInstanceId;

    private Long syncJobId;

    private Long syncJobHistoryId;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Long durationMs;

    private String errorMessage;

    private String outputInfo;

    private Integer version;
}
