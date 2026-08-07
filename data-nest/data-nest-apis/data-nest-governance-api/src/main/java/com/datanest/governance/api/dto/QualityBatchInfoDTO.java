package com.datanest.governance.api.dto;

import lombok.Data;

/**
 * 质量检查批次信息（超时回调 checkAndFireTimeout 用）。
 */
@Data
public class QualityBatchInfoDTO {

    /** 批次 ID */
    private Long id;

    /** 批次状态：RUNNING / SUCCESS / PARTIAL_FAILED / FAILED */
    private String status;

    /** 合并告警是否已发送：1 已发送，0/ null 未发送 */
    private Integer alertSent;
}
