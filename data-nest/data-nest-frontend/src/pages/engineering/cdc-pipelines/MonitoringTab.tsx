// Sprint 9 F1：CDC 管道详情抽屉「运行监控」页签。
// 展示 1h/6h/24h/7d 切换的延迟/吞吐趋势折线图（超阈值区段标红、无数据断点不插值）+ 当前延迟/吞吐/累计变更/作业重启 KPI 卡。
// 数据：GET /{id}/metrics/current + GET /{id}/metrics/trend?range=（分钟降采样，后端分桶聚合）。
import {useCallback, useEffect, useRef, useState} from 'react';
import {Spin} from 'antd';
import {getCdcMetricCurrent, getCdcMetricTrend} from '@/api/cdc';
import LineChart from '@/components/charts/LineChart';
import type {CdcMetricCurrent, CdcTrend, CdcTrendPoint} from '@/types/cdc';
import {formatTimeHm} from '@/utils/format';
import {KpiCard} from './shared';

const RANGES: { value: string; label: string }[] = [
    {value: '1h', label: '1h'},
    {value: '6h', label: '6h'},
    {value: '7d', label: '7d'},
];

const LAG_THRESHOLD = 30; // 延迟告警阈值（秒），沿用全局阈值默认 30s

const ACCENT = 'rgb(var(--color-accent))';
const WARNING = 'rgb(217 119 6)';
const MUTED = 'rgb(148 163 184)';


export default function MonitoringTab({pipelineId}: { pipelineId: string }) {
    const [range, setRange] = useState('1h');
    const [current, setCurrent] = useState<CdcMetricCurrent | null>(null);
    const [trend, setTrend] = useState<CdcTrend | null>(null);
    const [loading, setLoading] = useState(false);
    const [updatedAt, setUpdatedAt] = useState<string>('');
    const lastFetch = useRef<{ range: string }>({range: ''});
    const loadSeq = useRef(0);

    const loadCurrent = useCallback(() => {
        getCdcMetricCurrent(pipelineId)
            .then(c => setCurrent(c ?? null))
            .catch(() => {/* 拦截器已提示 */});
    }, [pipelineId]);

    const loadTrend = useCallback((r: string) => {
        const seq = ++loadSeq.current;
        setLoading(true);
        getCdcMetricTrend(pipelineId, r)
            .then(t => {
                if (seq !== loadSeq.current) return; // 过期响应丢弃，避免快速切换 range 乱序覆盖
                setTrend(t ?? null);
                setUpdatedAt(new Date().toLocaleTimeString('zh-CN', {hour12: false}));
                lastFetch.current.range = r;
            })
            .catch(() => {
                // 保留旧图（失败由拦截器提示），不闪空态
            })
            .finally(() => {
                if (seq === loadSeq.current) setLoading(false);
            });
    }, [pipelineId]);

    // 打开/首次：拉 current + trend
    useEffect(() => {
        loadCurrent();
        loadTrend(range);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [pipelineId]);

    // 切换 range：仅重拉 trend
    const handleRangeChange = (r: string) => {
        if (r === lastFetch.current.range) return;
        setRange(r);
        loadTrend(r);
    };

    const points = trend?.points ?? [];

    // 延迟趋势：均值 + 峰值两条系列
    const lagAvg = points.map((p: CdcTrendPoint) => (p.lagAvgSeconds == null ? null : p.lagAvgSeconds));
    const lagMax = points.map((p: CdcTrendPoint) => (p.lagMaxSeconds == null ? null : p.lagMaxSeconds));
    const throughput = points.map((p: CdcTrendPoint) => (p.recordsPerSecondAvg == null ? null : p.recordsPerSecondAvg));

    const xLabel = (i: number) => {
        const p = points[i];
        if (!p) return '';
        return formatTimeHm(p.minuteAt);
    };

    const live = current?.live !== false;
    const lagText = current?.currentLagSeconds != null && current.currentLagSeconds >= 0
        ? current.currentLagSeconds < 60
            ? `${current.currentLagSeconds} 秒`
            : `${Math.floor(current.currentLagSeconds / 60)} 分`
        : '—';

    return (
        <div className="flex flex-col h-full min-h-0">
            {/* 时间范围切换（固定区） */}
            <div className="flex items-center gap-ds-3 mb-ds-3 flex-shrink-0">
                <div className="flex items-center bg-ds-bg-root rounded-ds-sm p-0.5">
                    {RANGES.map(r => (
                        <button
                            key={r.value}
                            type="button"
                            onClick={() => handleRangeChange(r.value)}
                            className={`px-ds-3 py-ds-1 text-ds-small rounded-ds-sm transition-colors ${
                                range === r.value
                                    ? 'bg-ds-bg-surface text-ds-accent font-semibold shadow-ds-xs'
                                    : 'text-ds-text-muted hover:text-ds-text-secondary'
                            }`}
                        >
                            {r.label}
                        </button>
                    ))}
                </div>
                <span className="ml-auto text-ds-nano text-ds-text-muted">
                    更新于 {updatedAt} · 分钟降采样
                </span>
            </div>

            {/* KPI 卡（固定区） */}
            <div className="grid grid-cols-4 gap-ds-3 mb-ds-3 flex-shrink-0">
                <KpiCard label="当前延迟"
                         value={lagText}
                         unit="秒"
                         sub="阈值 30 秒"
                         danger={(current?.currentLagSeconds ?? 0) > LAG_THRESHOLD}
                         status={live ? undefined : {label: '已停止', variant: 'pending'}}/>
                <KpiCard label="当前吞吐"
                         value={current?.throughputRowsPerSecond != null && current.throughputRowsPerSecond >= 0
                             ? current.throughputRowsPerSecond.toFixed(1) : '—'}
                         unit="行/秒"
                         sub="Sink 端实时值"
                         status={live ? undefined : {label: '已停止', variant: 'pending'}}/>
                <KpiCard label="累计变更"
                         value={current?.totalChanges != null ? Number(current.totalChanges).toLocaleString() : '—'}
                         sub="含全量 + 增量"
                         status={live ? undefined : {label: '已停止', variant: 'pending'}}/>
                <KpiCard label="作业重启"
                         value={current?.numRestarts != null ? String(current.numRestarts) : '—'}
                         unit="次"
                         sub="本次运行以来"
                         status={live ? undefined : {label: '已停止', variant: 'pending'}}/>
            </div>

            {/* 图表区（弹性拉伸） */}
            <div className="flex flex-col flex-1 min-h-0 gap-ds-3">
                {/* 延迟趋势 */}
                <div className="bg-ds-bg-surface border border-ds-border-subtle rounded-ds-md p-ds-3 flex-1 min-h-0 flex flex-col">
                    <div className="flex items-center gap-ds-2 mb-ds-2 flex-shrink-0">
                        <span className="text-ds-small font-semibold text-ds-text-primary">延迟趋势（秒）</span>
                        <span className="text-ds-nano text-ds-text-muted">延迟均值 / 峰值，超阈值区段标红</span>
                        <div className="ml-auto flex items-center gap-ds-3">
                            <span className="flex items-center gap-1 text-ds-nano text-ds-text-muted">
                                <span className="w-2 h-2 rounded-full" style={{background: ACCENT}}/>延迟均值
                            </span>
                            <span className="flex items-center gap-1 text-ds-nano text-ds-text-muted">
                                <span className="w-2 h-2 rounded-full" style={{background: WARNING}}/>延迟峰值
                            </span>
                            <span className="flex items-center gap-1 text-ds-nano text-ds-text-muted">
                                <span className="w-4 h-0 border-t border-dashed" style={{borderColor: MUTED}}/>阈值 30s
                            </span>
                        </div>
                    </div>
                    {loading ? (
                        <div className="flex-1 flex items-center justify-center"><Spin size="small"/></div>
                    ) : (
                        <LineChart
                            data={points}
                            xLabel={xLabel}
                            emptyText="范围内暂无指标历史"
                            series={[
                                {
                                    key: 'lagAvg',
                                    label: '延迟均值',
                                    color: ACCENT,
                                    value: (_, i) => lagAvg[i],
                                },
                                {
                                    key: 'lagMax',
                                    label: '延迟峰值',
                                    color: WARNING,
                                    threshold: LAG_THRESHOLD,
                                    value: (_, i) => lagMax[i],
                                },
                            ]}
                        />
                    )}
                </div>

                {/* 吞吐量趋势 */}
                <div className="bg-ds-bg-surface border border-ds-border-subtle rounded-ds-md p-ds-3 flex-1 min-h-0 flex flex-col">
                    <div className="flex items-center gap-ds-2 mb-ds-2 flex-shrink-0">
                        <span className="text-ds-small font-semibold text-ds-text-primary">吞吐量趋势（行/秒）</span>
                        <span className="text-ds-nano text-ds-text-muted">Sink vertex numRecordsOutPerSecond 跨子任务求和</span>
                    </div>
                    {loading ? (
                        <div className="flex-1 flex items-center justify-center"><Spin size="small"/></div>
                    ) : (
                        <LineChart
                            data={points}
                            xLabel={xLabel}
                            emptyText="范围内暂无指标历史"
                            series={[
                                {
                                    key: 'throughput',
                                    label: '吞吐',
                                    color: ACCENT,
                                    area: true,
                                    value: (_, i) => throughput[i],
                                },
                            ]}
                        />
                    )}
                </div>
            </div>

            {/* 口径说明（固定区） */}
            <div className="mt-ds-3 flex-shrink-0 bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm px-ds-3 py-ds-2 text-ds-nano text-ds-text-muted leading-relaxed">
                指标为 5 秒轮询的分钟降采样（均值/峰值），保留 30 天；无数据时段断点不插值；重启次数取 Flink job-level numRestarts。
            </div>
        </div>
    );
}
