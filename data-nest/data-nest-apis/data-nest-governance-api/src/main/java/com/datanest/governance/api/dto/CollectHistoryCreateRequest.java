package com.datanest.governance.api.dto;

import lombok.Data;

/**
 * 初始化采集历史请求（对齐 CollectExecutor.initHistory：RUNNING + 统计列清零）。
 */
@Data
public class CollectHistoryCreateRequest {

    private Long taskId;

    private String taskName;

    private Long datasourceId;

    /** 触发方式（TaskTriggerType code） */
    private String triggerType;
}
