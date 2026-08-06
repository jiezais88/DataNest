package com.datanest.alert.api.dto;

import lombok.Data;

/**
 * 单条告警触发请求。
 */
@Data
public class AlertFireRequest {

    /** 告警对象类型 */
    private String objectType;

    /** 告警对象 ID */
    private Long objectId;

    /** 告警类型 */
    private String alertType;

    /** 告警详情 */
    private String detail;
}
