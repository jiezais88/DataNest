package com.datanest.governance.config;

import com.datanest.common.audit.AuditLogEvent;
import com.datanest.common.audit.AuditLogRecorder;
import com.datanest.system.api.SystemAuditApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * governance 服务的审计写入器（Sprint 11 F1）。
 * <p>
 * 经 SystemAuditApi Feign 调 system internal 审计写入端点；写入失败 fail-open（只打 warn 不阻断业务）。
 */
@Component
public class GovernanceAuditLogRecorder implements AuditLogRecorder {

    private static final Logger log = LoggerFactory.getLogger(GovernanceAuditLogRecorder.class);

    private final SystemAuditApi systemAuditApi;

    public GovernanceAuditLogRecorder(SystemAuditApi systemAuditApi) {
        this.systemAuditApi = systemAuditApi;
    }

    @Override
    public void record(AuditLogEvent event) {
        try {
            systemAuditApi.record(event);
        } catch (Exception e) {
            log.warn("审计日志写入失败（fail-open）：opType={}, resourceType={}", event.opType(), event.resourceType(), e);
        }
    }
}
