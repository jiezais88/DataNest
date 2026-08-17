package com.datanest.engineering.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * Sprint 11 F5 首页 KPI 聚合（工程域：DAG + 同步任务）。
 * <p>
 * 前端 5 区块之一「KPI 卡 + 近7日趋势 + 待处理异常·失败任务侧」由本 DTO 承载；
 * 各字段允许 5 分钟级延迟（PRD HP-1），由各服务聚合端点独立提供。
 */
@Schema(description = "首页 KPI 聚合（工程域）")
@Data
public class HomeKpiDTO {

    // ---------- KPI 1: 今日任务运行 + 状态分布 ----------
    @Schema(description = "今日任务运行总数（DAG + 同步）")
    private Long todayTotal;
    @Schema(description = "昨日任务运行总数（环比基数）")
    private Long yesterdayTotal;
    @Schema(description = "今日较昨日增减（+/-）")
    private Long todayDelta;
    @Schema(description = "今日成功数（v4.1 状态分布条）")
    private Long todaySuccess;
    @Schema(description = "今日失败数（v4.1 状态分布条）")
    private Long todayFailed;
    @Schema(description = "当前排队等待数（DAG 队列 WAITING，v4.1 状态分布条）")
    private Long waiting;

    // ---------- KPI 2: 任务成功率 ----------
    @Schema(description = "近 7 天任务成功率（0~100）")
    private Double successRate7d;
    @Schema(description = "近 7 天成功率环比（与再前 7 天相比，百分点）")
    private Double successRateDelta;

    // ---------- KPI 3: 运行中 ----------
    @Schema(description = "当前运行中任务总数（DAG + 同步 + 采集 + CDC，前端合并）")
    private Long running;

    // ---------- KPI 4: 失败待处理 ----------
    @Schema(description = "近 7 天失败且未恢复的任务数")
    private Long pendingFailed;
    @Schema(description = "近 1 小时新增失败数")
    private Long failedLast1h;

    // ---------- 近 14 日趋势 ----------
    @Schema(description = "近 14 日按天执行趋势（含异常日标记）")
    private List<TrendPoint> trend;

    // ---------- 待处理异常·失败任务侧 ----------
    @Schema(description = "失败任务异常列表（最多 3 条，按时间倒序）")
    private List<FailedItem> failedItems;

    @Schema(description = "近 7 日趋势点（按天）")
    @lombok.Data
    public static class TrendPoint {
        @Schema(description = "日期 MM-dd")
        private String day;
        @Schema(description = "当日执行总数")
        private Long total;
        @Schema(description = "当日成功数")
        private Long success;
        @Schema(description = "当日失败数")
        private Long failed;
        @Schema(description = "当日失败率 > 10% 视为异常日")
        private boolean abnormal;
    }

    @Schema(description = "失败任务条目（首页待处理异常列表用）")
    @lombok.Data
    public static class FailedItem {
        @Schema(description = "类型：dag / sync")
        private String type;
        @Schema(description = "任务名")
        private String name;
        @Schema(description = "执行 ID")
        private String executionId;
        @Schema(description = "关联对象 ID（dag 时为 dagId，sync 时为 syncJobId；v4.1 行内重跑用）")
        private String refId;
        @Schema(description = "失败时间（ISO 8601）")
        private String failedAt;
        @Schema(description = "失败原因摘要")
        private String reason;
    }
}
