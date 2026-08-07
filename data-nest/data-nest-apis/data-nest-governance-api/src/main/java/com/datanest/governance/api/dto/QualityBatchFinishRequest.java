package com.datanest.governance.api.dto;

import lombok.Data;

/**
 * 质量检查批次收尾请求。
 * <p>
 * 批次终态由 worker 按成功/失败计数判定（全成功=SUCCESS，部分失败=PARTIAL_FAILED，全失败=FAILED）后透传；
 * 服务端串联：终态回写 + last_trigger_at 更新 + 评分重算 + 合并告警。
 */
@Data
public class QualityBatchFinishRequest {

    /** 批次终态：SUCCESS / PARTIAL_FAILED / FAILED */
    private String status;

    /** 结束时间（ISO 格式，如 2026-08-07T12:00:00） */
    private String endedAt;

    /** 耗时（毫秒）；为空时服务端按 startedAt → endedAt 兜底计算 */
    private Long durationMs;
}
