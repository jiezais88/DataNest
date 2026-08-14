package com.datanest.common.audit;

/**
 * 审计日志写入器接口（Sprint 11 F1，技术文档 D-3）。
 * <p>
 * 各服务提供自己的实现：
 * <ul>
 *   <li>system 服务：直接写本库 {@code audit_log} 表；</li>
 *   <li>engineering / governance / data-service：经 {@code SystemAuditApi} Feign 调 system internal 写入端点。</li>
 * </ul>
 * 实现须保证 fail-open：写入失败不向上抛异常阻断业务。
 */
@FunctionalInterface
public interface AuditLogRecorder {

    /** 写入一条审计事件（异步线程中调用，实现内部吞异常打 warn） */
    void record(AuditLogEvent event);
}
