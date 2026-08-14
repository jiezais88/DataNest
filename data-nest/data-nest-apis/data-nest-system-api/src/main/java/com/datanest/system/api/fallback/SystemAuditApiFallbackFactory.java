package com.datanest.system.api.fallback;

import com.datanest.common.audit.AuditLogEvent;
import com.datanest.common.model.Result;
import com.datanest.system.api.SystemAuditApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * SystemAuditApi 熔断降级工厂。
 * <p>
 * 审计写入整体 fail-open：system 不可用时降级为静默成功（返回 200 空结果），
 * 丢弃本次审计，绝不阻断业务主链路（技术文档 D-3）。
 */
@Component
public class SystemAuditApiFallbackFactory implements FallbackFactory<SystemAuditApi> {

    private static final Logger logger = LoggerFactory.getLogger(SystemAuditApiFallbackFactory.class);

    @Override
    public SystemAuditApi create(Throwable cause) {
        logger.warn("SystemAuditApi 审计写入降级（fail-open）: {}", cause == null ? "unknown" : cause.getMessage());
        return new SystemAuditApi() {
            @Override
            public Result<Void> record(AuditLogEvent event) {
                return Result.ok(null);
            }
        };
    }
}
