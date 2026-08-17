package com.datanest.alert.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.datanest.alert.dto.AlertHistoryStatsDTO;
import com.datanest.alert.service.AlertRuleService;
import com.datanest.common.model.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 首页仪表盘（Sprint 11 F5）——告警域聚合。
 * <p>
 * 「告警中」KPI 口径（已与产品确认）：近 24h 告警类历史聚合（FAILURE/TIMEOUT/LAG_EXCEEDED/EXTERNAL_STOP），
 * 返回总数 + 严重度分布 + 类型摘要（前端渲染「2 严重 · Doris 延迟·CPU 超阈」）。允许 5 分钟级延迟。
 */
@Tag(name = "首页仪表盘", description = "Sprint 11 F5 平台首页各域 KPI 聚合")
@RestController
@RequestMapping("/home")
public class HomeController {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final AlertRuleService alertRuleService;

    public HomeController(AlertRuleService alertRuleService) {
        this.alertRuleService = alertRuleService;
    }

    @Operation(summary = "首页告警域 KPI 聚合", description = "近 24h 告警总数 + 各类型分布 + 摘要")
    @SaCheckLogin
    @GetMapping("/kpis")
    public Result<Map<String, Object>> kpis() {
        LocalDateTime from = LocalDateTime.now().minusHours(24);
        AlertHistoryStatsDTO stats = alertRuleService.listHistoryStats(null, null,
                from.format(ISO), LocalDateTime.now().format(ISO));

        long total = n(stats.getFailure()) + n(stats.getTimeout()) + n(stats.getLagExceeded()) + n(stats.getExternalStop());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("failure", n(stats.getFailure()));
        result.put("timeout", n(stats.getTimeout()));
        result.put("lagExceeded", n(stats.getLagExceeded()));
        result.put("externalStop", n(stats.getExternalStop()));
        result.put("success", n(stats.getSuccess()));
        result.put("sendFailed", n(stats.getSendFailed()));
        // 摘要：给前端「告警中」卡展示（如「2 严重」）
        result.put("summary", buildSummary(stats));
        return Result.ok(result);
    }

    private long n(Long v) {
        return v == null ? 0L : v;
    }

    /** 摘要文案：优先「严重度」+「主要类型」；无告警返回「无活跃告警」 */
    private String buildSummary(AlertHistoryStatsDTO stats) {
        long total = n(stats.getFailure()) + n(stats.getTimeout()) + n(stats.getLagExceeded()) + n(stats.getExternalStop());
        if (total == 0) {
            return "无活跃告警";
        }
        // 按类型占比挑主要摘要（简化：非 failure 的都算「异常类」，failure 最多则标「任务失败」）
        long abnormal = total - n(stats.getFailure());
        if (n(stats.getFailure()) >= abnormal) {
            return n(stats.getFailure()) + " 起任务失败";
        }
        if (n(stats.getTimeout()) > 0) {
            return n(stats.getTimeout()) + " 起执行超时";
        }
        if (n(stats.getLagExceeded()) > 0) {
            return n(stats.getLagExceeded()) + " 起延迟超限";
        }
        if (n(stats.getExternalStop()) > 0) {
            return n(stats.getExternalStop()) + " 起外部停止";
        }
        return total + " 起告警";
    }
}
