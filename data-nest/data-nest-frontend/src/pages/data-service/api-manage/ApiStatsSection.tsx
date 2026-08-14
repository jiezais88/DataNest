// Sprint 10 F3：单 API 调用统计区块（PRD §6.5.2，API 详情页）。
// KPI 4 卡 + 调用量/错误率趋势 + 今日小时分布 + 调用方 Key 排行 + 错误码分布 + 最近调用明细。
// 数据来源：GET /data-service/apis/{id}/stats?range=24h|7d|30d。
import {useEffect, useState} from 'react';
import {HiOutlineChartBar, HiOutlineShieldCheck} from 'react-icons/hi2';
import {getApiStats} from '@/api/data-service';
import LineChart from '@/components/charts/LineChart';
import {formatNumber} from '@/utils/format';
import {
    Bars,
    ChartCard,
    KpiCard,
    LegendDot,
    RankItem,
    RANGE_LABEL,
    RangeSeg,
    SplitBar,
    StatsLoading,
    bucketLabel,
    pct,
} from '../api-stats/charts';
import type {ApiStats, StatsRange, StatsTrendPoint} from '@/types/data-service';

export default function ApiStatsSection({apiId}: { apiId: string }) {
    const [range, setRange] = useState<StatsRange>('7d');
    const [stats, setStats] = useState<ApiStats | null>(null);
    const [loading, setLoading] = useState(true);

    useEffect(() => {
        let cancelled = false;
        setLoading(true);
        getApiStats(apiId, range)
            .then(r => { if (!cancelled) setStats(r.data); })
            .catch(() => {
                // 拦截器已提示
            })
            .finally(() => { if (!cancelled) setLoading(false); });
        return () => { cancelled = true; };
    }, [apiId, range]);

    const rangeLabel = RANGE_LABEL[range];
    const hasCalls = stats != null && Number(stats.totalCalls) > 0;
    const errorRate = stats && hasCalls ? 1 - (stats.successRate ?? 0) : null;
    const level = stats == null ? null
        : !hasCalls ? 'NONE'
            : (errorRate! >= 0.05 || stats.p95Ms >= 1000) ? 'SEVERE'
                : (errorRate! >= 0.01 || stats.p95Ms >= 500) ? 'WARNING'
                    : 'PASS';
    const levelText = level === 'PASS' ? '健康' : level === 'WARNING' ? '警告' : level === 'SEVERE' ? '严重' : '暂无调用';
    const levelColor = level === 'PASS' ? 'text-ds-success' : level === 'WARNING' ? 'text-ds-warning' : level === 'SEVERE' ? 'text-ds-danger' : 'text-ds-text-muted';
    const levelScore = level === 'PASS' ? 100 : level === 'WARNING' ? 60 : level === 'SEVERE' ? 20 : null;

    const trend = stats?.trend ?? [];
    const maxTopKey = Math.max(1, ...(stats?.topKeys ?? []).map(k => Number(k.calls ?? 0)));
    const bd = stats?.statusBreakdown;

    return (
        <section className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle p-ds-5">
            <div className="flex items-center justify-between mb-ds-4 flex-wrap gap-ds-3">
                <h3 className="text-ds-small font-semibold text-ds-text-primary flex items-center gap-ds-2">
                    <HiOutlineChartBar size={16} className="text-ds-accent"/>
                    调用统计
                </h3>
                <RangeSeg range={range} onChange={setRange}/>
            </div>

            {/* 健康评级条 */}
            {stats && (
                <div className="flex items-center gap-ds-3 mb-ds-4 px-ds-4 py-ds-3 rounded-ds-md border border-ds-border-subtle">
                    <HiOutlineShieldCheck size={20} className={levelColor}/>
                    <div className="flex-1 min-w-0">
                        <span className={`text-ds-small font-semibold ${levelColor}`}>{levelText}</span>
                        <span className="text-ds-tiny text-ds-text-muted ml-ds-2">
                            成功率 {pct(stats.successRate)} · P95 {Math.round(stats.p95Ms)}ms · 错误率 {pct(errorRate)}
                        </span>
                    </div>
                    {levelScore != null && <span className={`text-ds-heading font-bold ${levelColor}`}>{levelScore}</span>}
                </div>
            )}

            {/* KPI 4 卡 */}
            <div className="grid grid-cols-4 gap-ds-4 mb-ds-4">
                <KpiCard
                    label="总调用量"
                    value={loading ? '…' : formatNumber(stats?.totalCalls)}
                    sub={rangeLabel}
                />
                <KpiCard
                    label="成功率"
                    value={loading ? '…' : hasCalls ? pct(stats?.successRate) : '—'}
                    sub="目标 ≥ 99.0%"
                    valueClass={stats && hasCalls ? (stats.successRate < 0.99 ? 'text-ds-warning' : 'text-ds-success') : undefined}
                />
                <KpiCard
                    label="平均 / P95 耗时"
                    value={loading ? '…' : stats ? String(Math.round(stats.avgMs)) : '—'}
                    unit="ms"
                    sub={`P95：${stats ? Math.round(stats.p95Ms) : '—'}ms`}
                />
                <KpiCard
                    label="今日调用"
                    value={loading ? '…' : formatNumber(stats?.todayCalls)}
                    sub="自今日 0 点"
                />
            </div>

            {/* 调用量趋势 + 错误率趋势 */}
            <div className="grid grid-cols-2 gap-ds-4 mb-ds-4">
                <ChartCard
                    title="调用量趋势"
                    sub={rangeLabel}
                    action={(
                        <>
                            <LegendDot color="rgb(var(--color-accent))" label="调用量"/>
                            <LegendDot color="rgb(var(--color-danger))" label="失败数"/>
                        </>
                    )}
                >
                    {loading ? (
                        <StatsLoading minHeight={160}/>
                    ) : (
                        <LineChart
                            data={trend}
                            xLabel={(i) => bucketLabel(trend[i]?.bucket ?? '', range)}
                            emptyText="范围内暂无调用记录"
                            minHeight={130}
                            series={[
                                {
                                    key: 'total', label: '调用量', color: 'rgb(var(--color-accent))',
                                    value: (t) => Number((t as StatsTrendPoint).total ?? 0),
                                },
                                {
                                    key: 'failed', label: '失败数', color: 'rgb(var(--color-danger))',
                                    value: (t) => Number((t as StatsTrendPoint).failed ?? 0),
                                },
                            ]}
                        />
                    )}
                </ChartCard>

                <ChartCard title="错误率趋势" sub={rangeLabel}>
                    {loading ? (
                        <StatsLoading minHeight={160}/>
                    ) : (
                        <LineChart
                            data={trend}
                            xLabel={(i) => bucketLabel(trend[i]?.bucket ?? '', range)}
                            emptyText="范围内暂无调用记录"
                            minHeight={130}
                            series={[
                                {
                                    key: 'errorRate', label: '错误率', color: 'rgb(var(--color-danger))',
                                    value: (t) => {
                                        const p = t as StatsTrendPoint;
                                        const total = Number(p.total ?? 0);
                                        return total > 0 ? (Number(p.failed ?? 0) / total) * 100 : null;
                                    },
                                },
                            ]}
                        />
                    )}
                </ChartCard>
            </div>

            {/* 今日小时分布 + 调用方 Key 排行 */}
            <div className="grid grid-cols-2 gap-ds-4 mb-ds-4">
                <ChartCard title="今日小时调用分布" sub="按小时 · 自今日 0 点">
                    {loading ? (
                        <StatsLoading minHeight={160}/>
                    ) : (
                        <Bars
                            data={stats?.hourly ?? []}
                            range="24h"
                            color="rgb(var(--color-accent))"
                            emptyText="今日暂无调用"
                            minHeight={120}
                            xLabel={(i) => (stats?.hourly?.[i]?.bucket ?? '').slice(11, 13)}
                        />
                    )}
                </ChartCard>

                <ChartCard title="调用方 Key 排行" sub={`按 ${rangeLabel}调用量`}>
                    {loading ? (
                        <StatsLoading minHeight={160}/>
                    ) : (stats?.topKeys ?? []).length === 0 ? (
                        <div className="h-full min-h-[160px] flex items-center justify-center text-ds-small text-ds-text-muted">范围内暂无调用方</div>
                    ) : (
                        <div className="flex flex-col">
                            {(stats?.topKeys ?? []).map((k, i) => (
                                <RankItem
                                    key={k.keyId}
                                    rank={i + 1}
                                    title={k.name}
                                    value={k.calls}
                                    maxValue={maxTopKey}
                                />
                            ))}
                        </div>
                    )}
                </ChartCard>
            </div>

            {/* 错误码分布 + 最近调用明细 */}
            <div className="grid grid-cols-2 gap-ds-4">
                <ChartCard title="错误码分布" sub={rangeLabel}>
                    {loading ? (
                        <StatsLoading minHeight={160}/>
                    ) : bd ? (
                        <div className="flex flex-col flex-1 min-h-[160px] justify-center">
                            {(() => {
                                const total = Number(bd.success ?? 0) + Number(bd.clientError ?? 0) + Number(bd.serverError ?? 0);
                                const rate = total > 0 ? Number(bd.success ?? 0) / total : null;
                                return (
                                    <div className="flex items-end gap-2 mb-ds-2">
                                        <span className={`text-ds-heading font-bold leading-none ${rate == null ? 'text-ds-text-muted' : rate >= 0.99 ? 'text-ds-success' : rate >= 0.95 ? 'text-ds-warning' : 'text-ds-danger'}`}>
                                            {rate == null ? '—' : pct(rate)}
                                        </span>
                                        <span className="text-ds-tiny text-ds-text-muted mb-0.5">2xx 成功率</span>
                                    </div>
                                );
                            })()}
                            <SplitBar
                                segments={[
                                    {color: 'rgb(var(--color-success))', ratio: Number(bd.success ?? 0), label: `2xx 成功 ${formatNumber(bd.success)} 次`},
                                    {color: 'rgb(var(--color-warning))', ratio: Number(bd.clientError ?? 0), label: `4xx 客户端 ${formatNumber(bd.clientError)} 次`},
                                    {color: 'rgb(var(--color-danger))', ratio: Number(bd.serverError ?? 0), label: `5xx 服务端 ${formatNumber(bd.serverError)} 次`},
                                ]}
                            />
                            <div className="flex items-center gap-ds-4 mt-ds-2 text-ds-tiny text-ds-text-muted">
                                <LegendDot color="rgb(var(--color-success))" label="2xx 成功"/>
                                <LegendDot color="rgb(var(--color-warning))" label="4xx 客户端"/>
                                <LegendDot color="rgb(var(--color-danger))" label="5xx 服务端"/>
                            </div>
                        </div>
                    ) : null}
                </ChartCard>

                <ChartCard title="最近调用" sub="最新 5 条 · 异常高亮">
                    {loading ? (
                        <StatsLoading minHeight={160}/>
                    ) : (stats?.recentLogs ?? []).length === 0 ? (
                        <div className="h-full min-h-[160px] flex items-center justify-center text-ds-small text-ds-text-muted">暂无调用记录</div>
                    ) : (
                        <div className="flex flex-col">
                            <div className="flex px-ds-1 py-ds-1 text-ds-tiny text-ds-text-muted border-b border-ds-border-subtle">
                                <span className="w-20 flex-shrink-0">时间</span>
                                <span className="flex-1 min-w-0">调用方</span>
                                <span className="w-16 flex-shrink-0 text-right">状态</span>
                                <span className="w-16 flex-shrink-0 text-right">耗时</span>
                            </div>
                            {(stats?.recentLogs ?? []).map((log, i) => {
                                const statusClass = log.statusCode >= 500 ? 'text-ds-danger' : log.statusCode >= 400 ? 'text-ds-warning' : 'text-ds-success';
                                return (
                                    <div key={i} className="flex px-ds-1 py-1 text-ds-small border-b border-ds-border-subtle last:border-b-0">
                                        <span className="w-20 flex-shrink-0 font-mono text-ds-text-muted">{log.createdAt.slice(11, 19)}</span>
                                        <span className="flex-1 min-w-0 truncate text-ds-text-secondary" title={log.keyName ?? '—'}>{log.keyName ?? '—'}</span>
                                        <span className={`w-16 flex-shrink-0 text-right font-mono font-semibold ${statusClass}`}>{log.statusCode}</span>
                                        <span className="w-16 flex-shrink-0 text-right font-mono text-ds-text-secondary">
                                            {log.durationMs == null ? '—' : `${log.durationMs}ms`}
                                        </span>
                                    </div>
                                );
                            })}
                        </div>
                    )}
                </ChartCard>
            </div>
        </section>
    );
}
