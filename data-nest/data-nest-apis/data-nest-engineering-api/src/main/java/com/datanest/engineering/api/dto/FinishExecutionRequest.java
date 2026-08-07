package com.datanest.engineering.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 同步任务执行完成回写请求（last_execute_time + last_history_id）。
 */
@Data
public class FinishExecutionRequest {

    private Long historyId;

    private LocalDateTime lastExecuteTime;
}
