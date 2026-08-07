package com.datanest.governance.api.dto;

import lombok.Data;

/**
 * 采集历史轻量信息（手动停止轮询用，对齐 CollectExecutor.isTerminated 只读 status）。
 */
@Data
public class CollectHistoryInfoDTO {

    private Long id;

    /** 执行状态（ExecutionStatus code） */
    private String status;
}
