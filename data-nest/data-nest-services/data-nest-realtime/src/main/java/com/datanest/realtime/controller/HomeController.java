package com.datanest.realtime.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.datanest.common.model.Result;
import com.datanest.realtime.dto.CdcPipelineStatsDTO;
import com.datanest.realtime.service.CdcPipelineService;
import com.datanest.realtime.service.FlinkJobService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 首页仪表盘（Sprint 11 F5）——实时域聚合。
 * <p>
 * CDC 管道统计（复用列表页 stats）+ Flink 集群探活（复用 FlinkJobService 集群 overview）。
 */
@Tag(name = "首页仪表盘", description = "Sprint 11 F5 平台首页各域 KPI 聚合")
@RestController
@RequestMapping("/home")
public class HomeController {

    private static final Logger logger = LoggerFactory.getLogger(HomeController.class);

    private final CdcPipelineService cdcPipelineService;
    private final FlinkJobService flinkJobService;

    public HomeController(CdcPipelineService cdcPipelineService, FlinkJobService flinkJobService) {
        this.cdcPipelineService = cdcPipelineService;
        this.flinkJobService = flinkJobService;
    }

    @Operation(summary = "首页实时域 KPI 聚合", description = "CDC 管道 stats + Flink 集群探活")
    @SaCheckLogin
    @GetMapping("/kpis")
    public Result<Map<String, Object>> kpis() {
        Map<String, Object> result = new LinkedHashMap<>();

        // ---- CDC 管道 ----
        CdcPipelineStatsDTO stats = cdcPipelineService.stats();
        result.put("cdcRunning", stats.getRunning());
        result.put("cdcError", stats.getError());
        result.put("cdcStopped", stats.getStopped());
        result.put("cdcSyncedTables", stats.getSyncedTables());

        // ---- Flink 集群探活 ----
        Map<String, Object> flink = new LinkedHashMap<>();
        try {
            Map<String, Object> overview = flinkJobService.getClusterOverview();
            flink.put("status", "UP");
            flink.put("taskmanagers", overview.get("taskmanagers"));
            flink.put("runningJobs", overview.get("runningJobs"));
        } catch (Exception e) {
            logger.warn("首页 Flink 集群探活失败: {}", e.getMessage());
            flink.put("status", "DOWN");
            flink.put("taskmanagers", null);
            flink.put("runningJobs", null);
        }
        result.put("flink", flink);
        return Result.ok(result);
    }
}
