package com.datanest.engineering.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.datanest.common.model.Result;
import com.datanest.engineering.dto.HomeKpiDTO;
import com.datanest.engineering.service.HomeKpiService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 首页仪表盘（Sprint 11 F5）——工程域 KPI 聚合。
 * <p>
 * 按 PRD D-5「各区块独立请求各服务」，前端 KPI/趋势/异常区块调本端点；
 * CDC（realtime）、质量（governance）、告警（alert）、健康（system）由各自服务聚合端点提供。
 */
@Tag(name = "首页仪表盘", description = "Sprint 11 F5 平台首页各域 KPI 聚合")
@RestController
@RequestMapping("/home")
public class HomeController {

    private final HomeKpiService homeKpiService;

    public HomeController(HomeKpiService homeKpiService) {
        this.homeKpiService = homeKpiService;
    }

    @Operation(summary = "首页工程域 KPI 聚合", description = "今日运行/成功率/运行中/失败待处理 + 近7日趋势 + 失败异常列表（DAG+同步）")
    @SaCheckLogin
    @GetMapping("/kpis")
    public Result<HomeKpiDTO> kpis() {
        return Result.ok(homeKpiService.build());
    }
}
