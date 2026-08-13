// Sprint 10 F3：API 运行统计（全局观测域，PRD §6.5.1）。
// 平台全部 API 的运行态势：总量趋势、健康分级、Top 调用与限流命中；点击排行条目进单 API 详情。
// 数据来源：GET /data-service/stats/*（7 端点）+ /apis/summary（状态速览），range=24h|7d|30d。
import {useEffect, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {HiOutlineChartBar, HiOutlineChevronLeft, HiOutlineExclamationTriangle, HiOutlineShieldCheck} from 'react-icons/hi2';
import {
    getDataApiSummary,
    getStatsErrorCodes,
    getStatsHealthDistribution,
    getStatsOverview,
    getStatsRateLimitTrend,
    getStatsTopApis,
    getStatsTopKeys,
    getStatsTrend,
} from '@/api/data-service';
import LineChart from '@/components/charts/LineChart';
import DsButton from '@/components/DsButton';
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
    statusName,
} from './charts';
import type {
    DataApiSummary,
    StatsErrorCode,
    StatsHealthDistribution,
    StatsOverview,
    StatsRange,
    StatsTopApi,
    StatsTopKey,
    StatsTrendPoint,
} from '@/types/data-service';

export default function ApiStatsPage() {
    const navigate = useNavigate();
    const [range, setRange] = useState<StatsRange>('7d');

    const [overview, setOverview] = useState<StatsOverview | null>(null);
    const [trend, setTrend] = useState<StatsTrendPoint[]>([]);
    const [health, setHealth] = useState<StatsHealthDistribution | null>(null);
    const [topApis, setTopApis] = useState<StatsTopApi[]>([]);
    const [errorCodes, setErrorCodes] = useState<StatsErrorCode[]>([]);
    const [topKeys, setTopKeys] = useState<StatsTopKey[]>([]);
    const [rateLimitTrend, setRateLimitTrend] = useState<StatsTrendPoint[]>([]);
    const [summary, setSummary] = useState<DataApiSummary | null>(null);
    const [loading, setLoading] = useState(true);

    useEffect(() => {
        let cancelled = false;
        setLoading(true);
        Promise.all([
            getStatsOverview(range).then(r => { if (!cancelled) setOverview(r.data); }),
            getStatsTrend(range).then(r => { if (!cancelled) setTrend(r.data ?? []); }),
            getStatsHealthDistribution(range).then(r => { if (!cancelled) setHealth(r.data); }),
            getStatsTopApis(range).then(r => { if (!cancelled) setTopApis(r.data ?? []); }),
            getStatsErrorCodes(range).then(r => { if (!cancelled) setErrorCodes(r.data ?? []); }),
            getStatsTopKeys(range).then(r => { if (!cancelled) setTopKeys(r.data ?? []); }),
            getStatsRateLimitTrend(range).then(r => { if (!cancelled) setRateLimitTrend(r.data ?? []); }),
            getDataApiSummary().then(r => { if (!cancelled) setSummary(r.data); }),
        ]).catch(() => {
            // 拦截器已提示
        }).finally(() => { if (!cancelled) setLoading(false); });
        return () => { cancelled = true; };
    }, [range]);

    const rangeLabel = RANGE_LABEL[range];
    const maxTopApi = Math.max(1, ...topApis.map(a => Number(a.calls ?? 0)));
    const maxTopKey = Math.max(1, ...topKeys.map(k => Number(k.calls ?? 0)));

    // 错误码 4xx/5xx 占比（非 2xx 错误总量内）
    const errTotal = errorCodes.reduce((s, e) => s + Number(e.count ?? 0), 0);
    const err4xx = errorCodes.filter(e => e.statusCode < 500).reduce((s, e) => s + Number(e.count ?? 0), 0);
    const err5xx = errTotal - err4xx;
    const maxErr = Math.max(1, ...errorCodes.map(e => Number(e.count ?? 0)));
    const top429 = errorCodes.find(e => e.statusCode === 429);

    return (
        <div className="flex flex-col gap-ds-4">
            {/* 页头 */}
            <div className="flex items-start justify-between gap-ds-4 flex-wrap flex-shrink-0">
                <div>
                    <h1 className="text-ds-display text-ds-text-primary flex items-center gap-ds-2">
                        <HiOutlineChartBar className="text-ds-accent"/>
                        API 运行统计
                    </h1>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1">
                        平台全部 API 的运行态势：总量趋势、健康分级、Top 调用与限流命中；点击排行条目可进入单 API 详情。
                    </p>
                </div>
                <div className="flex items-center gap-ds-2 flex-wrap">
                    <RangeSeg range={range} onChange={setRange}/>
                    <DsButton variant="secondary" onClick={() => navigate('/data-service/api-manage')}>
                        <HiOutlineChevronLeft size={14}/>
                        返回 API 列表
                    </DsButton>
                </div>
            </div>

            {/* KPI 4 卡 */}
            <div className="grid grid-cols-4 gap-ds-4 flex-shrink-0">
                <KpiCard
                    label="总调用量"
                    value={loading ? '…' : formatNumber(overview?.totalCalls)}
                    sub={`${rangeLabel} · ${summary ? `${formatNumber(summary.publishedCount)} 个已发布 API` : '—'}`}
                />
                <KpiCard
                    label="平均成功率"
                    value={loading ? '…' : pct(overview?.successRate)}
                    sub="目标 ≥ 99.0%"
                    valueClass={overview && overview.successRate < 0.99 ? 'text-ds-warning' : 'text-ds-success'}
                />
                <KpiCard
                    label="P95 耗时"
                    value={loading ? '…' : overview ? String(Math.round(overview.p95Ms)) : '—'}
                    unit="ms"
                    sub="P95 目标 < 500ms"
                    valueClass={overview && overview.p95Ms >= 1000 ? 'text-ds-danger' : overview && overview.p95Ms >= 500 ? 'text-ds-warning' : 'text-ds-success'}
                />
                <KpiCard
                    label="限流命中"
                    value={loading ? '…' : formatNumber(overview?.rateLimitedCount)}
                    sub={`占调用 ${pct(overview?.rateLimitRatio)}`}
                    valueClass={overview && Number(overview.rateLimitedCount ?? 0) > 0 ? 'text-ds-warning' : 'text-ds-text-primary'}
                />
            </div>

            {/* 全局调用量趋势 + API 健康分布 */}
            <div className="grid grid-cols-3 gap-ds-4">
                <ChartCard
                    title="全局调用量趋势"
                    sub={rangeLabel}
                    className="col-span-2"
                    action={(
                        <>
                            <LegendDot color="rgb(var(--color-accent))" label="调用量"/>
                            <LegendDot color="rgb(var(--color-danger))" label="失败数"/>
                        </>
                    )}
                >
                    {loading ? (
                        <StatsLoading/>
                    ) : (
                        <LineChart
                            data={trend}
                            xLabel={(i) => bucketLabel(trend[i]?.bucket ?? '', range)}
                            emptyText="范围内暂无调用记录"
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

                <ChartCard
                    title="API 健康分布"
                    sub={`${rangeLabel} · 共 ${(health?.healthyCount ?? 0) + (health?.warningCount ?? 0) + (health?.severeCount ?? 0)} 个`}
                >
                    {loading ? (
                        <StatsLoading/>
                    ) : health ? (
                        <div className="flex flex-col h-full min-h-[180px]">
                            <div className="flex items-end gap-2">
                                <span className="text-ds-display font-bold leading-none text-ds-success">{health.overallScore}</span>
                                <span className="text-ds-tiny text-ds-text-muted mb-0.5">平台综合健康分</span>
                            </div>
                            <SplitBar
                                segments={[
                                    {color: 'rgb(var(--color-success))', ratio: health.healthyCount},
                                    {color: 'rgb(var(--color-warning))', ratio: health.warningCount},
                                    {color: 'rgb(var(--color-danger))', ratio: health.severeCount},
                                ]}
                            />
                            <div className="flex items-center gap-ds-4 mt-ds-2 text-ds-tiny text-ds-text-muted">
                                <LegendDot color="rgb(var(--color-success))" label={`健康 ${health.healthyCount}`}/>
                                <LegendDot color="rgb(var(--color-warning))" label={`警告 ${health.warningCount}`}/>
                                <LegendDot color="rgb(var(--color-danger))" label={`严重 ${health.severeCount}`}/>
                            </div>
                            <div className="flex flex-col gap-1 mt-ds-3">
                                {health.items.map((it) => {
                                    const color = it.level === 'PASS' ? 'rgb(var(--color-success))' : it.level === 'WARNING' ? 'rgb(var(--color-warning))' : 'rgb(var(--color-danger))';
                                    const clickable = !!it.path;
                                    return (
                                        <button
                                            key={it.apiId}
                                            type="button"
                                            disabled={!clickable}
                                            onClick={() => clickable && navigate(`/data-service/api-manage/${it.apiId}`)}
                                            className={`flex items-center gap-2 text-left text-ds-tiny ${clickable ? 'hover:text-ds-accent cursor-pointer' : 'cursor-default'}`}
                                            title={it.name}
                                        >
                                            <span className="w-2 h-2 rounded-full flex-shrink-0" style={{background: color}}/>
                                            <span className="flex-1 min-w-0 truncate text-ds-text-secondary">{it.name}</span>
                                            {clickable && <span className="text-ds-accent font-medium flex-shrink-0">详情</span>}
                                        </button>
                                    );
                                })}
                                {health.items.length === 0 && (
                                    <span className="text-ds-tiny text-ds-text-muted">范围内暂无 API 调用</span>
                                )}
                            </div>
                        </div>
                    ) : null}
                </ChartCard>
            </div>

            {/* Top 5 API 调用排行 + 错误码分布 */}
            <div className="grid grid-cols-3 gap-ds-4">
                <ChartCard title="Top 5 API 调用排行" sub={`按 ${rangeLabel}调用量`} className="col-span-2">
                    {loading ? (
                        <StatsLoading/>
                    ) : topApis.length === 0 ? (
                        <div className="h-full min-h-[180px] flex items-center justify-center text-ds-small text-ds-text-muted">范围内暂无 API 调用</div>
                    ) : (
                        <div className="flex flex-col">
                            {topApis.map((a, i) => (
                                <RankItem
                                    key={a.apiId}
                                    rank={i + 1}
                                    title={a.name}
                                    sub={a.path ?? undefined}
                                    value={a.calls}
                                    maxValue={maxTopApi}
                                    dimmed={!a.path}
                                    onClick={a.path ? () => navigate(`/data-service/api-manage/${a.apiId}`) : undefined}
                                />
                            ))}
                        </div>
                    )}
                </ChartCard>

                <ChartCard title="错误码分布" sub={errTotal > 0 ? `非 2xx 请求 · 共 ${formatNumber(errTotal)}` : undefined}>
                    {loading ? (
                        <StatsLoading/>
                    ) : errorCodes.length === 0 ? (
                        <div className="h-full min-h-[180px] flex items-center justify-center text-ds-small text-ds-text-muted">范围内无错误请求</div>
                    ) : (
                        <div className="flex flex-col h-full min-h-[180px]">
                            <div className="flex items-center gap-2">
                                <SplitBar
                                    segments={[
                                        {color: 'rgb(var(--color-warning))', ratio: err4xx},
                                        {color: 'rgb(var(--color-danger))', ratio: err5xx},
                                    ]}
                                />
                                <span className="text-ds-tiny text-ds-text-muted whitespace-nowrap">
                                    {errTotal > 0 ? `${Math.round((err4xx / errTotal) * 100)}% 客户端 · ${Math.round((err5xx / errTotal) * 100)}% 服务端` : ''}
                                </span>
                            </div>
                            <div className="flex flex-col mt-ds-3">
                                {errorCodes.map((e, i) => (
                                    <RankItem
                                        key={e.statusCode}
                                        rank={i + 1}
                                        title={`${e.statusCode} ${statusName(e.statusCode)}`}
                                        value={e.count}
                                        maxValue={maxErr}
                                        barColor={e.statusCode >= 500 ? 'rgb(var(--color-danger))' : 'rgb(var(--color-warning))'}
                                    />
                                ))}
                            </div>
                            {top429 && (
                                <div className="mt-auto pt-ds-3 flex items-start gap-1.5 text-ds-tiny text-ds-warning">
                                    <HiOutlineExclamationTriangle size={14} className="flex-shrink-0 mt-0.5"/>
                                    <span>
                                        <b>429 限流占错误总量 {pct(top429.ratio, 0)}</b>，命中集中在高频 API；建议调高对应 Key 级 QPS 或增加结果缓存。
                                    </span>
                                </div>
                            )}
                        </div>
                    )}
                </ChartCard>
            </div>

            {/* 调用方 Key 排行 + 限流趋势 + 状态速览 */}
            <div className="grid grid-cols-3 gap-ds-4">
                <ChartCard title="调用方 Key 排行" sub={`按 ${rangeLabel}调用量`}>
                    {loading ? (
                        <StatsLoading/>
                    ) : topKeys.length === 0 ? (
                        <div className="h-full min-h-[180px] flex items-center justify-center text-ds-small text-ds-text-muted">范围内暂无调用方</div>
                    ) : (
                        <div className="flex flex-col">
                            {topKeys.map((k, i) => (
                                <RankItem
                                    key={k.keyId}
                                    rank={i + 1}
                                    title={k.name}
                                    sub={k.zombie ? '近 7 天 0 调用' : undefined}
                                    value={k.calls}
                                    maxValue={maxTopKey}
                                    dimmed={k.zombie}
                                />
                            ))}
                        </div>
                    )}
                </ChartCard>

                <ChartCard title="限流命中趋势" sub={rangeLabel}>
                    {loading ? <StatsLoading/> : <Bars data={rateLimitTrend} range={range} emptyText="范围内无限流命中"/>}
                </ChartCard>

                <ChartCard title="API 状态速览" sub="全量 API">
                    <div className="flex flex-col h-full min-h-[180px]">
                        <div className="grid grid-cols-3 gap-ds-3">
                            <div className="flex flex-col items-center py-ds-3 bg-ds-success-light rounded-ds-md">
                                <span className="text-ds-heading font-bold text-ds-success">{summary ? formatNumber(summary.publishedCount) : '—'}</span>
                                <span className="text-ds-tiny text-ds-text-muted mt-1">已发布</span>
                            </div>
                            <div className="flex flex-col items-center py-ds-3 bg-ds-accent-light rounded-ds-md">
                                <span className="text-ds-heading font-bold text-ds-accent">{summary ? formatNumber(summary.createdCount) : '—'}</span>
                                <span className="text-ds-tiny text-ds-text-muted mt-1">待发布</span>
                            </div>
                            <div className="flex flex-col items-center py-ds-3 bg-ds-bg-hover rounded-ds-md">
                                <span className="text-ds-heading font-bold text-ds-text-secondary">{summary ? formatNumber(summary.disabledCount) : '—'}</span>
                                <span className="text-ds-tiny text-ds-text-muted mt-1">已下线</span>
                            </div>
                        </div>
                        <div className="mt-auto pt-ds-3 flex items-start gap-1.5 text-ds-tiny text-ds-text-muted">
                            <HiOutlineShieldCheck size={14} className="flex-shrink-0 mt-0.5 text-ds-success"/>
                            <span>
                                近 7 天 <b>0 调用</b> 的 Key 为僵尸 Key，建议停用以防泄露；可在
                                <button type="button" className="text-ds-accent hover:underline mx-0.5" onClick={() => navigate('/data-service/api-keys')}>API Key 管理</button>
                                页一键处置。
                            </span>
                        </div>
                    </div>
                </ChartCard>
            </div>
        </div>
    );
}
