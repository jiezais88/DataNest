package com.datanest.common.audit;

/**
 * 审计日志事件（切面采集后，经 AuditLogRecorder 落库 / Feign 传输到 system）。
 * <p>
 * 跨服务传输 DTO：字段保持简单标量，无嵌套对象；敏感信息（密码/Key 明文/查询结果）不得写入。
 */
public record AuditLogEvent(
        Long operatorId,
        String operatorName,
        String opType,
        String resourceType,
        String resourceId,
        String resourceName,
        String content,
        String result,
        String errorMessage,
        String clientIp
) {

    public static final String RESULT_SUCCESS = "SUCCESS";
    public static final String RESULT_FAILURE = "FAILURE";
}
