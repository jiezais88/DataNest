package com.datanest.job.handler;

import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.Result;
import com.datanest.system.api.SystemAuditApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

/**
 * 审计日志定时清理任务（Sprint 11 F1 补全）。
 * 清理超过保留天数的 sys_audit_log 记录，经 Feign 远程调用 system 的 internal 端点执行。
 * 之前 system 的 InternalAuditController.cleanup 已就绪但无人定时调用，审计日志只增不清理。
 */
@Component
@RefreshScope
public class AuditLogCleanupHandler implements PlatformJobHandler {

    private static final Logger logger = LoggerFactory.getLogger(AuditLogCleanupHandler.class);

    private final SystemAuditApi systemAuditApi;
    private final int retainDays;

    public AuditLogCleanupHandler(SystemAuditApi systemAuditApi,
                                  @Value("${datanest.job.audit-log-cleanup.retain-days:90}") int retainDays) {
        this.systemAuditApi = systemAuditApi;
        this.retainDays = Math.max(1, retainDays);
    }

    @Override
    public String getName() {
        return "auditLogCleanupHandler";
    }

    @Override
    public void execute(String param) {
        logger.info("Starting audit log cleanup, retainDays={}", retainDays);
        Integer rows = RemoteCalls.execute("system.audit.cleanup", () -> {
            Result<Integer> result = systemAuditApi.cleanup(retainDays);
            return result != null && result.data() != null ? result.data() : 0;
        }, -1);
        if (rows < 0) {
            throw new IllegalStateException("审计日志清理失败，详见日志");
        }
        logger.info("Audit log cleanup completed: rows={}", rows);
    }
}
