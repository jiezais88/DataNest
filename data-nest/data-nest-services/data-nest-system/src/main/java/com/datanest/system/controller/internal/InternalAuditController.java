package com.datanest.system.controller.internal;

import com.datanest.common.audit.AuditLogEvent;
import com.datanest.common.model.Result;
import com.datanest.system.service.AuditLogService;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审计日志内部接口（实现 system-api 的 SystemAuditApi 契约）。
 * <p>
 * 仅供服务间内部调用：engineering/governance/data-service 经 Feign 写入审计、
 * job 经 Feign 触发 90 天清理；由 common 的 InternalTokenFilter 做内部令牌鉴权。
 */
@Hidden // 内部 Feign 契约端点，不进接口文档
@RestController
@RequestMapping("/internal/audit")
public class InternalAuditController {

    private final AuditLogService auditLogService;

    public InternalAuditController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    /** 写入一条审计日志 */
    @PostMapping
    public Result<Void> record(@RequestBody AuditLogEvent event) {
        auditLogService.record(event);
        return Result.ok(null);
    }

    /** 清理保留天数之前的审计记录（job 定时调用） */
    @PostMapping("/cleanup")
    public Result<Integer> cleanup(@RequestParam(value = "retainDays", defaultValue = "90") int retainDays) {
        return Result.ok(auditLogService.cleanup(retainDays));
    }
}
