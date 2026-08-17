package com.datanest.governance.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.datanest.common.model.Result;
import com.datanest.governance.dto.CollectHistoryStatsDTO;
import com.datanest.governance.dto.QualityIssueItemDTO;
import com.datanest.governance.dto.QualityReportRequest;
import com.datanest.governance.service.CollectHistoryService;
import com.datanest.governance.service.QualityReportService;
import com.datanest.task.core.service.DorisSqlExecutor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 首页仪表盘（Sprint 11 F5）——治理域聚合。
 * <p>
 * 提供：collect 今日/近7日执行 stats（复用全局历史统计）、质量异常清单（复用质量报告 issues 前 3 条）、
 * Doris 延迟探活（复用 task-core DorisSqlExecutor，连接耗时作为延迟，允许 5 分钟级延迟）。
 */
@Tag(name = "首页仪表盘", description = "Sprint 11 F5 平台首页各域 KPI 聚合")
@RestController
@RequestMapping("/home")
public class HomeController {

    private static final Logger logger = LoggerFactory.getLogger(HomeController.class);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final CollectHistoryService collectHistoryService;
    private final QualityReportService qualityReportService;
    private final DorisSqlExecutor dorisSqlExecutor;

    public HomeController(CollectHistoryService collectHistoryService,
                          QualityReportService qualityReportService,
                          DorisSqlExecutor dorisSqlExecutor) {
        this.collectHistoryService = collectHistoryService;
        this.qualityReportService = qualityReportService;
        this.dorisSqlExecutor = dorisSqlExecutor;
    }

    @Operation(summary = "首页治理域 KPI 聚合", description = "collect 统计 + 质量异常（WARNING/SEVERE 前3条）+ Doris 延迟探活")
    @SaCheckLogin
    @GetMapping("/kpis")
    public Result<Map<String, Object>> kpis() {
        Map<String, Object> result = new LinkedHashMap<>();

        // ---- collect 今日 + 近7日 ----
        LocalDateTime todayStart = LocalDateTime.now().toLocalDate().atStartOfDay();
        String todayFrom = todayStart.format(ISO);
        String now = LocalDateTime.now().format(ISO);
        CollectHistoryStatsDTO todayStats = collectHistoryService.listStats(todayFrom, now);
        CollectHistoryStatsDTO weekStats = collectHistoryService.listStats(
                todayStart.minusDays(7).format(ISO), now);

        Map<String, Object> collect = new LinkedHashMap<>();
        collect.put("today", n(todayStats.getSuccess()) + n(todayStats.getFailed()));
        collect.put("weekSuccess", n(weekStats.getSuccess()));
        collect.put("weekFailed", n(weekStats.getFailed()));
        result.put("collect", collect);

        // ---- 质量异常（WARNING/SEVERE，取前 50 条供前端按「规则+表」去重；避免 3 条截断导致少计） ----
        QualityReportRequest req = new QualityReportRequest();
        req.setPage(1);
        req.setPageSize(50);
        try {
            List<QualityIssueItemDTO> issues = qualityReportService.issues(req).records();
            result.put("qualityIssues", issues == null ? List.of() : issues);
        } catch (Exception e) {
            logger.warn("首页质量异常聚合失败（降级为空列表）: {}", e.getMessage());
            result.put("qualityIssues", List.of());
        }

        // ---- Doris 延迟探活（连接耗时 ms；失败 = null，前端显示「不可用」） ----
        result.put("doris", probeDoris());
        return Result.ok(result);
    }

    /** Doris JDBC 连接探活：连接耗时作为延迟（ms）；失败返回 status=DOWN */
    private Map<String, Object> probeDoris() {
        Map<String, Object> m = new LinkedHashMap<>();
        long start = System.currentTimeMillis();
        try (java.sql.Connection conn = dorisSqlExecutor.openConnection()) {
            long cost = System.currentTimeMillis() - start;
            m.put("status", "UP");
            m.put("latencyMs", cost);
        } catch (Exception e) {
            logger.warn("Doris 探活失败: {}", e.getMessage());
            m.put("status", "DOWN");
            m.put("latencyMs", null);
        }
        return m;
    }

    private long n(Long v) {
        return v == null ? 0L : v;
    }
}
