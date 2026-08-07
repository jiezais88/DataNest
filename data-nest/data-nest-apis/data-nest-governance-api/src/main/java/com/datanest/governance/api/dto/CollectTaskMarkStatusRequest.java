package com.datanest.governance.api.dto;

import lombok.Data;

/**
 * 采集任务状态回写请求。
 * <p>
 * status 必传；lastHistoryId / lastExecuteTime 可空，为空则不回写对应列
 * （运行中置 RUNNING 时只传 status；收尾时三者全传，对齐 CollectExecutor.updateTaskStatus）。
 */
@Data
public class CollectTaskMarkStatusRequest {

    /** 任务状态（ExecutionStatus code） */
    private String status;

    /** 最近一次采集历史 ID（可空） */
    private Long lastHistoryId;

    /** 最近执行时间，ISO 格式（可空） */
    private String lastExecuteTime;
}
