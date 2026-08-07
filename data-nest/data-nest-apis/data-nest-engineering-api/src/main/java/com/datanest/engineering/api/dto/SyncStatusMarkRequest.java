package com.datanest.engineering.api.dto;

import lombok.Data;

/**
 * 同步任务 execution_status 条件更新请求。
 * <p>
 * expectedLastHistoryId 非空时保留 last_history_id 保护语义：
 * 仅当 sync_job.last_history_id 为空或等于该值时才翻转状态，
 * 避免覆盖同一任务新一轮执行的状态。
 */
@Data
public class SyncStatusMarkRequest {

    /** 目标 execution_status（RUNNING / SUCCESS / FAILED 等） */
    private String status;

    /** 可空；非空时启用 last_history_id 保护 */
    private Long expectedLastHistoryId;
}
