package com.datanest.system.api;

import com.datanest.common.audit.AuditLogEvent;
import com.datanest.common.model.Result;
import com.datanest.system.api.fallback.SystemAuditApiFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 系统服务审计日志内部 Feign 契约（Sprint 11 F1）。
 * <p>
 * 仅供服务间内部调用，对应 data-nest-system 的 /system/internal/audit 端点；
 * 由 common 的 InternalTokenFilter 做内部令牌鉴权。
 */
@FeignClient(name = "data-nest-system", path = "/system/internal/audit", contextId = "systemAuditApi",
        fallbackFactory = SystemAuditApiFallbackFactory.class)
public interface SystemAuditApi {

    /** 写入一条审计日志（fail-open：system 不可用时丢弃，不阻断业务） */
    @PostMapping
    Result<Void> record(@RequestBody AuditLogEvent event);

    /** 清理保留天数之前的审计记录（job 定时调用），返回删除条数 */
    @PostMapping("/cleanup")
    Result<Integer> cleanup(@RequestParam("retainDays") int retainDays);
}
