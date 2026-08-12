package com.datanest.alert.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.alert.dto.AlertHistoryStatsDTO;
import com.datanest.alert.entity.AlertHistory;
import com.datanest.alert.service.AlertRuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全局告警中心 - 告警历史（Sprint 5）。
 * 权限（PRD §8）：查看告警历史 = 超管/工程师/治理员。
 */
@Tag(name = "告警历史", description = "全局告警历史分页查询")
@RestController
@RequestMapping("/alert-history")
public class AlertHistoryController {

    private final AlertRuleService alertRuleService;

    public AlertHistoryController(AlertRuleService alertRuleService) {
        this.alertRuleService = alertRuleService;
    }

    @Operation(summary = "告警历史分页列表")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @GetMapping
    public Result<PageResult<AlertHistory>> list(@Parameter(description = "页码，从 1 开始") @RequestParam(defaultValue = "1") int page,
                                                 @Parameter(description = "每页条数") @RequestParam(defaultValue = "10") int pageSize,
                                                 @Parameter(description = "告警对象类型（DAG/SYNC_JOB/COLLECT_TASK/QUALITY/CDC_PIPELINE）") @RequestParam(required = false) String objectType,
                                                 @Parameter(description = "告警对象 ID") @RequestParam(required = false) Long objectId,
                                                 @Parameter(description = "告警类型（FAILURE/TIMEOUT/SUCCESS/LAG_EXCEEDED/EXTERNAL_STOP）") @RequestParam(required = false) String alertType,
                                                 @Parameter(description = "邮件发送状态（SUCCESS/FAILED）") @RequestParam(required = false) String sendStatus,
                                                 @Parameter(description = "告警时间下界（ISO 8601，如 2026-08-05T00:00:00）") @RequestParam(required = false) String sentAtFrom,
                                                 @Parameter(description = "告警时间上界（ISO 8601，如 2026-08-05T23:59:59）") @RequestParam(required = false) String sentAtTo) {
        return Result.ok(alertRuleService.listHistory(page, pageSize, objectType, objectId, alertType, sendStatus, sentAtFrom, sentAtTo));
    }

    @Operation(summary = "告警历史统计（顶部统计卡，按时间范围 + 对象维度聚合）")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @GetMapping("/stats")
    public Result<AlertHistoryStatsDTO> stats(@Parameter(description = "告警对象类型（DAG/SYNC_JOB/COLLECT_TASK/QUALITY/CDC_PIPELINE）") @RequestParam(required = false) String objectType,
                                              @Parameter(description = "告警对象 ID") @RequestParam(required = false) Long objectId,
                                              @Parameter(description = "告警时间下界（ISO 8601，如 2026-08-05T00:00:00）") @RequestParam(required = false) String sentAtFrom,
                                              @Parameter(description = "告警时间上界（ISO 8601，如 2026-08-05T23:59:59）") @RequestParam(required = false) String sentAtTo) {
        return Result.ok(alertRuleService.listHistoryStats(objectType, objectId, sentAtFrom, sentAtTo));
    }
}
