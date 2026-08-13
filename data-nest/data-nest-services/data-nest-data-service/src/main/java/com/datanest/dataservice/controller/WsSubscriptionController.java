package com.datanest.dataservice.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.datanest.common.model.Result;
import com.datanest.dataservice.dto.SubscriptionStatsDTO;
import com.datanest.dataservice.service.WsSubscriptionQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 实时订阅监控（Sprint 10 F4 连接监控）。
 * <p>
 * 四角色可查（对齐 PRD §8 权限矩阵「订阅文档查看」全角色）。
 */
@Tag(name = "实时订阅监控", description = "管道在线连接/今日事件/延迟/失败 + 订阅方 Key 列表（Sprint 10 F4）")
@SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN", "DATA_ENGINEER", "DATA_ANALYST"}, mode = SaMode.OR)
@RestController
@RequestMapping("/subscriptions")
public class WsSubscriptionController {

    private final WsSubscriptionQueryService queryService;

    public WsSubscriptionController(WsSubscriptionQueryService queryService) {
        this.queryService = queryService;
    }

    @Operation(summary = "管道订阅监控", description = "在线连接数/今日事件/延迟 P95/推送失败 + 订阅方 Key 列表")
    @GetMapping("/{pipelineId}/stats")
    public Result<SubscriptionStatsDTO> stats(@PathVariable("pipelineId") Long pipelineId) {
        return Result.ok(queryService.stats(pipelineId));
    }
}
