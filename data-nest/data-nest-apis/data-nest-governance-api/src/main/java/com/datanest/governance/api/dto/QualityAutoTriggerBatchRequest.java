package com.datanest.governance.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 质量检查自动触发批量请求。
 */
@Data
public class QualityAutoTriggerBatchRequest {

    /** 对象类型 */
    private String objectType;

    /** 对象 ID 列表 */
    private List<Long> objectIds;
}
