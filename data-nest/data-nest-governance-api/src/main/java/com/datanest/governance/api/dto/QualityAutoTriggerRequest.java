package com.datanest.governance.api.dto;

import lombok.Data;

/**
 * 质量检查自动触发请求。
 */
@Data
public class QualityAutoTriggerRequest {

    /** 对象类型 */
    private String objectType;

    /** 对象 ID */
    private Long objectId;
}
