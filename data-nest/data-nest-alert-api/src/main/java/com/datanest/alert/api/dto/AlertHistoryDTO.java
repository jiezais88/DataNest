package com.datanest.alert.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 告警历史记录。
 */
@Data
public class AlertHistoryDTO {

    /** 历史记录 ID */
    private Long id;

    /** 告警规则 ID */
    private Long alertRuleId;

    /** 规则名称 */
    private String ruleName;

    /** 告警对象类型 */
    private String objectType;

    /** 告警对象 ID */
    private Long objectId;

    /** 关联质量批次 ID */
    private Long qualityBatchId;

    /** 告警类型 */
    private String alertType;

    /** 接收人 */
    private String recipients;

    /** 发送状态 */
    private String sendStatus;

    /** 发送时间 */
    private LocalDateTime sentAt;

    /** 告警摘要 */
    private String summary;
}
