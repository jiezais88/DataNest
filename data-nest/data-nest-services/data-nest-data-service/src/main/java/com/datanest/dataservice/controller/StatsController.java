package com.datanest.dataservice.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.datanest.common.model.Result;
import com.datanest.dataservice.dto.StatsErrorCodeDTO;
import com.datanest.dataservice.dto.StatsHealthDistributionDTO;
import com.datanest.dataservice.dto.StatsOverviewDTO;
import com.datanest.dataservice.dto.StatsTopApiDTO;
import com.datanest.dataservice.dto.StatsTopKeyDTO;
import com.datanest.dataservice.dto.TrendAgg;
import com.datanest.dataservice.service.StatsQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 全局 API 调用统计（Sprint 10 F3，API 运行统计页）。
 * <p>
 * 四角色可查（对齐 PRD §8 权限矩阵「API 查看（统计）」）。
 */
@Tag(name = "API 运行统计", description = "全局调用量/成功率/健康分布/排行/错误码/限流趋势（Sprint 10 F3）")
@SaCheckLogin
@RestController
@RequestMapping("/stats")
public class StatsController {

    private final StatsQueryService statsQueryService;

    public StatsController(StatsQueryService statsQueryService) {
        this.statsQueryService = statsQueryService;
    }

    @Operation(summary = "全局 KPI", description = "总调用量/成功率/P95/限流命中（range=24h|7d|30d）")
    @GetMapping("/overview")
    public Result<StatsOverviewDTO> overview(@RequestParam(value = "range", defaultValue = "24h") String range) {
        return Result.ok(statsQueryService.overview(range));
    }

    @Operation(summary = "全局调用量趋势", description = "双线：调用量 + 失败数")
    @GetMapping("/trend")
    public Result<List<TrendAgg>> trend(@RequestParam(value = "range", defaultValue = "24h") String range) {
        return Result.ok(statsQueryService.trend(range));
    }

    @Operation(summary = "API 健康分布", description = "健康/警告/严重占比 + 平台综合健康分（对齐告警 PASS/WARNING/SEVERE）")
    @GetMapping("/health-distribution")
    public Result<StatsHealthDistributionDTO> healthDistribution(
            @RequestParam(value = "range", defaultValue = "24h") String range) {
        return Result.ok(statsQueryService.healthDistribution(range));
    }

    @Operation(summary = "Top API 调用排行", description = "按调用量排序，整行可点进单 API 详情")
    @GetMapping("/top-apis")
    public Result<List<StatsTopApiDTO>> topApis(@RequestParam(value = "range", defaultValue = "24h") String range,
                                                @RequestParam(value = "limit", defaultValue = "5") int limit) {
        return Result.ok(statsQueryService.topApis(range, clampLimit(limit)));
    }

    @Operation(summary = "错误码分布", description = "4xx/5xx TopN（429 限流突出）")
    @GetMapping("/error-codes")
    public Result<List<StatsErrorCodeDTO>> errorCodes(@RequestParam(value = "range", defaultValue = "24h") String range,
                                                      @RequestParam(value = "limit", defaultValue = "5") int limit) {
        return Result.ok(statsQueryService.errorCodes(range, clampLimit(limit)));
    }

    @Operation(summary = "调用方 Key 排行", description = "TopN 调用 + 近 7 天 0 调用僵尸 Key 灰显")
    @GetMapping("/top-keys")
    public Result<List<StatsTopKeyDTO>> topKeys(@RequestParam(value = "range", defaultValue = "24h") String range,
                                                @RequestParam(value = "limit", defaultValue = "5") int limit) {
        return Result.ok(statsQueryService.topKeys(range, clampLimit(limit)));
    }

    @Operation(summary = "限流命中趋势", description = "429 按时间桶柱状")
    @GetMapping("/rate-limit-trend")
    public Result<List<TrendAgg>> rateLimitTrend(@RequestParam(value = "range", defaultValue = "24h") String range) {
        return Result.ok(statsQueryService.rateLimitTrend(range));
    }

    private int clampLimit(int limit) {
        return Math.min(Math.max(limit, 1), 50);
    }
}
