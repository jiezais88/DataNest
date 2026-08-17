import request from './request';
import type {Result} from '@/types/common';

// =================== Sprint 11 F5 首页仪表盘 ===================

/**
 * 后端 Long 按全局约定序列化为字符串（conventions-backend §5），故计数类字段用 `number | string`，
 * 前端参与算术处必须 `Number(...)` 归一，避免 `"2" + "3" = "23"` 串拼接。
 */
export type CountField = number | string;

export interface HomeTrendPoint {
    day: string;
    total: CountField;
    success: CountField;
    failed: CountField;
    abnormal: boolean;
}

export interface HomeFailedItem {
    type: 'dag' | 'sync';
    name: string;
    executionId: string;
    /** 关联对象 ID（dag → dagId，sync → syncJobId），行内重跑用 */
    refId?: string;
    failedAt?: string;
    reason?: string;
}

export interface HomeEngineeringKpi {
    todayTotal: CountField;
    yesterdayTotal: CountField;
    todayDelta: CountField;
    /** 今日状态分布条（v4.1）：成功 / 失败 / 排队等待 */
    todaySuccess?: CountField;
    todayFailed?: CountField;
    waiting?: CountField;
    successRate7d?: number;
    successRateDelta?: number;
    running: CountField;
    pendingFailed: CountField;
    failedLast1h: CountField;
    trend: HomeTrendPoint[];
    failedItems: HomeFailedItem[];
}

export interface HomeAlertKpi {
    total: CountField;
    failure: CountField;
    timeout: CountField;
    lagExceeded: CountField;
    externalStop: CountField;
    success: CountField;
    sendFailed: CountField;
    summary: string;
}

export interface HomeGovernanceKpi {
    collect: {today: CountField; weekSuccess: CountField; weekFailed: CountField};
    qualityIssues: Array<{
        detailId: string;
        ruleName: string;
        ruleType: string;
        tableName: string;
        resultLevel: 'WARNING' | 'SEVERE' | string;
        resultValue?: number;
        checkedAt: string;
    }>;
    doris: {status: 'UP' | 'DOWN'; latencyMs?: number};
}

export interface HomeRealtimeKpi {
    cdcRunning: CountField;
    cdcError: CountField;
    cdcStopped: CountField;
    cdcSyncedTables: CountField;
    flink: {status: 'UP' | 'DOWN'; taskmanagers?: number; runningJobs?: number};
}

/** 工程域 KPI（DAG + 同步）：今日/成功率/运行中/失败待处理 + 趋势 + 失败异常 */
export const fetchEngineeringKpi = () =>
    request.get<Result<HomeEngineeringKpi>>('/engineering/home/kpis').then(r => r.data);

/** 告警域 KPI：近 24h 告警聚合 */
export const fetchAlertKpi = () =>
    request.get<Result<HomeAlertKpi>>('/alert/home/kpis').then(r => r.data);

/** 治理域 KPI：collect 统计 + 质量异常 + Doris 探活 */
export const fetchGovernanceKpi = () =>
    request.get<Result<HomeGovernanceKpi>>('/governance/home/kpis').then(r => r.data);

/** 实时域 KPI：CDC 统计 + Flink 探活 */
export const fetchRealtimeKpi = () =>
    request.get<Result<HomeRealtimeKpi>>('/realtime/home/kpis').then(r => r.data);
