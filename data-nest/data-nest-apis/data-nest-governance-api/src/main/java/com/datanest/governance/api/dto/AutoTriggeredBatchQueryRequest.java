package com.datanest.governance.api.dto;

import lombok.Data;

import java.util.List;

/**
 * AUTO_TRIGGER 批次查重请求：查询指定质量任务在某时间点之后是否已有自动触发批次。
 */
@Data
public class AutoTriggeredBatchQueryRequest {

    /** 质量任务 ID 列表 */
    private List<Long> jobIds;

    /** 起始时间（ISO_LOCAL_DATE_TIME 字符串），服务端解析 */
    private String since;
}
