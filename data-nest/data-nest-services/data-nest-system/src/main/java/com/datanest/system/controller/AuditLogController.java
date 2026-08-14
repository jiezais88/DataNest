package com.datanest.system.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.system.entity.AuditLog;
import com.datanest.system.service.AuditLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审计日志查询（Sprint 11 F1，仅超级管理员）。
 * <p>
 * 只提供查询（列表 + 详情），无增删改接口——审计记录只增不改不删（PRD B5 / AL-10）。
 */
@Tag(name = "审计日志", description = "审计日志查询（仅超管）")
@RestController
@RequestMapping("/audit-logs")
public class AuditLogController {

    private final AuditLogService auditLogService;

    public AuditLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @Operation(summary = "审计日志分页查询（仅超管）", description = "操作人/操作类型/资源类型/时间范围/关键词组合筛选，默认按时间倒序")
    @SaCheckRole("SUPER_ADMIN")
    @GetMapping
    public Result<PageResult<AuditLog>> list(
            @Parameter(description = "页码，从 1 开始") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页条数") @RequestParam(defaultValue = "20") int pageSize,
            @Parameter(description = "操作人用户名（模糊）") @RequestParam(required = false) String operatorName,
            @Parameter(description = "操作类型") @RequestParam(required = false) String opType,
            @Parameter(description = "资源类型") @RequestParam(required = false) String resourceType,
            @Parameter(description = "开始时间") @RequestParam(required = false) String startTime,
            @Parameter(description = "结束时间") @RequestParam(required = false) String endTime,
            @Parameter(description = "关键词（匹配资源名称/内容摘要）") @RequestParam(required = false) String keyword) {
        return Result.ok(auditLogService.pageQuery(page, pageSize, operatorName, opType,
                resourceType, startTime, endTime, keyword));
    }

    @Operation(summary = "审计日志详情（仅超管）")
    @SaCheckRole("SUPER_ADMIN")
    @GetMapping("/{id}")
    public Result<AuditLog> detail(@Parameter(description = "审计记录 ID") @PathVariable Long id) {
        return Result.ok(auditLogService.detail(id));
    }
}
