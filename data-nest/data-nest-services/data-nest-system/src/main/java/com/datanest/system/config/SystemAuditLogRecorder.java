package com.datanest.system.config;

import com.datanest.common.audit.AuditLogEvent;
import com.datanest.common.audit.AuditLogRecorder;
import com.datanest.system.service.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * system 服务本地的审计写入器（Sprint 11 F1）。
 * <p>
 * 直接写本库 audit_log 表（不走 Feign）；写入失败 fail-open，只打 warn 不阻断业务。
 * 供 common 的 AuditLogAspect 切面异步调用。
 */
@Component
public class SystemAuditLogRecorder implements AuditLogRecorder {

    private static final Logger log = LoggerFactory.getLogger(SystemAuditLogRecorder.class);

    private final AuditLogService auditLogService;

    public SystemAuditLogRecorder(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @Override
    public void record(AuditLogEvent event) {
        try {
            auditLogService.record(event);
        } catch (Exception e) {
            log.warn("审计日志写入失败（fail-open）：opType={}, resourceType={}", event.opType(), event.resourceType(), e);
        }
    }
}
