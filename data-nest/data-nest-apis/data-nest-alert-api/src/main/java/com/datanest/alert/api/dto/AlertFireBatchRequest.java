package com.datanest.alert.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 批量告警触发请求。
 */
@Data
public class AlertFireBatchRequest {

    /** 告警对象类型 */
    private String objectType;

    /** 告警对象 ID */
    private Long objectId;

    /** 告警类型 */
    private String alertType;

    /** 告警条目列表 */
    private List<AlertItem> items;

    /** 关联批次 ID */
    private Long batchId;
}
