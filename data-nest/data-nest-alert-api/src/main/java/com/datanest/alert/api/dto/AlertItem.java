package com.datanest.alert.api.dto;

import lombok.Data;

/**
 * 批量告警条目。
 */
@Data
public class AlertItem {

    /** 告警等级 */
    private String level;

    /** 规则名称 */
    private String ruleName;

    /** 告警详情 */
    private String detail;
}
