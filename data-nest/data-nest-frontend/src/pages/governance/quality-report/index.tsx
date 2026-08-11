// Sprint 8 F3：质量报告 Dashboard（DG-07 完整版）。
// 一屏总览：KPI×5 + 四档分布趋势 + 表评分分布环图 + 数据源对比 + 表评分趋势 + 问题清单 TOP5。
// 筛选草稿 → 「生成报告」统一应用；查看全角色，导出治理员/超管（后端 1005 兜底）。
import {useCallback, useEffect, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {
    HiOutlineArrowDownTray,
    HiOutlineArrowRight,
    HiOutlineChartBar,
    HiOutlineExclamationTriangle,
} from 'react-icons/hi2';
import {
    exportQualityReport,
    getDatasourceComparison,
    getQualityIssues,
    getQualityLevelTrend,
    getQualityReportOptions,
    getQualityReportSummary,
    getQualityScoreDistribution,
    getQualityScoreTrend,
} from '@/api/quality-report';
import {queryQualityScores} from '@/api/quality';
import Drawer from '@/components/Drawer';
import DsButton from '@/components/DsButton';
import DsFilterSelect from '@/components/DsFilterSelect';
import DsRangePicker from '@/components/DsRangePicker';
import DsStatusBadge from '@/components/DsStatusBadge';
import DsTableEmpty from '@/components/DsTableEmpty';
import Pagination from '@/components/Pagination';
import {GOVERNANCE_WRITE_ROLES} from '@/constants/roles';
import usePagedList from '@/hooks/usePagedList';
import {useHasRole} from '@/hooks/useHasRole';
import {formatDateTime, formatDateTimeLocalInput} from '@/utils/format';
import {notify} from '@/utils/notify';
import {downloadCsvBlob} from '@/utils/download';
import type {
    DatasourceScoreComparison,
    QualityIssueItem,
    QualityLevelTrendPoint,
    QualityReportRequest,
    QualityReportSummary,
    QualityScoreDistribution,
    QualityScoreTrendPoint,
} from '@/types/quality-report';
import {QUALITY_TYPE_LABEL} from '@/types/quality';
import {ComparisonBars, LevelTrendChart, ScoreDonut, ScoreTrendChart} from './charts';
import {levelLabel} from './constants';

/** 时间范围快捷项 */
const RANGE_OPTIONS = [
    {value: '7', label: '最近 7 天'},
    {value: '30', label: '最近 30 天'},
    {value: '90', label: '最近 90 天'},
    {value: 'custom', label: '自定义'},
];

function rangeToIso(days: number): { startTime: string; endTime: string } {
    const end = new Date();
    const start = new Date(end.getTime() - days * 24 * 60 * 60 * 1000);
    return {startTime: formatDateTimeLocalInput(start), endTime: formatDateTimeLocalInput(end)};
}

/** KPI 卡（对齐原型 kpi-card） */
function KpiCard({label, value, unit, sub, danger}: {
    label: string;
    value: string;
    unit?: string;
    sub: string;
    danger?: boolean;
}) {
    return (
        <div className="bg-ds-bg-surface border border-ds-border-subtle rounded-ds-md p-ds-4">
            <div className="text-ds-tiny text-ds-text-muted">{label}</div>
            <div className={`text-ds-display font-bold mt-ds-1 ${danger ? 'text-ds-danger' : 'text-ds-text-primary'}`}>
                {value}
                {unit && <span className="text-ds-small font-normal text-ds-text-muted ml-1">{unit}</span>}
            </div>
            <div className="text-ds-tiny text-ds-text-muted mt-ds-1">{sub}</div>
        </div>
    );
}

/** 图表卡片容器 */
function ChartCard({title, sub, action, children, className = ''}: {
    title: string;
    sub?: string;
    action?: React.ReactNode;
    children: React.ReactNode;
    className?: string;
}) {
    return (
        <div className={`bg-ds-bg-surface border border-ds-border-subtle rounded-ds-md p-ds-4 flex flex-col min-h-0 ${className}`}>
            <div className="flex items-center gap-ds-2 mb-ds-3 flex-shrink-0">
                <span className="text-ds-small font-semibold text-ds-text-primary">{title}</span>
                {sub && <span className="text-ds-tiny text-ds-text-muted">{sub}</span>}
                {action && <div className="ml-auto">{action}</div>}
            </div>
            <div className="flex-1 min-h-0">{children}</div>
        </div>
    );
}

export default function QualityReportPage() {
    const navigate = useNavigate();
    const canExport = useHasRole(...GOVERNANCE_WRITE_ROLES);

    // ============ 筛选（草稿 → 生成报告时应用） ============
    const [datasourceId, setDatasourceId] = useState('');
    const [databaseName, setDatabaseName] = useState('');
    const [jobId, setJobId] = useState('');
    const [rangeKey, setRangeKey] = useState('30');
    const [customFrom, setCustomFrom] = useState('');
    const [customTo, setCustomTo] = useState('');

    const [dsOptions, setDsOptions] = useState<{ value: string; label: string }[]>([]);
    const [dbOptions, setDbOptions] = useState<{ value: string; label: string }[]>([]);
    const [jobOptions, setJobOptions] = useState<{ value: string; label: string }[]>([]);

    // 选项加载（库随数据源联动）
    useEffect(() => {
        getQualityReportOptions(datasourceId || undefined)
            .then(res => {
                setDsOptions([{value: '', label: '全部数据源'},
                    ...(res?.datasources ?? []).map(d => ({value: String(d.id), label: d.name || `数据源 ${d.id}`}))]);
                setDbOptions([{value: '', label: '全部库'},
                    ...(res?.databases ?? []).map(db => ({value: db, label: db}))]);
                setJobOptions([{value: '', label: '全部质量任务'},
                    ...(res?.jobs ?? []).map(j => ({value: String(j.id), label: j.name || `任务 ${j.id}`}))]);
            })
            .catch(() => {
                // 拦截器已提示
            });
    }, [datasourceId]);

    // ============ 报告数据 ============
    const [summary, setSummary] = useState<QualityReportSummary | null>(null);
    const [levelTrend, setLevelTrend] = useState<QualityLevelTrendPoint[]>([]);
    const [distribution, setDistribution] = useState<QualityScoreDistribution | null>(null);
    const [comparison, setComparison] = useState<DatasourceScoreComparison[]>([]);
    const [scoreTrend, setScoreTrend] = useState<QualityScoreTrendPoint[]>([]);
    const [loading, setLoading] = useState(false);
    /** 表评分趋势选中表（评分页拉候选） */
    const [trendTableId, setTrendTableId] = useState('');
    const [tableOptions, setTableOptions] = useState<{ value: string; label: string }[]>([]);

    const buildRequest = useCallback((): QualityReportRequest => {
        const range = rangeKey === 'custom'
            ? {startTime: customFrom || undefined, endTime: customTo || undefined}
            : rangeToIso(Number(rangeKey));
        return {
            datasourceId: datasourceId || undefined,
            databaseName: databaseName || undefined,
            jobId: jobId || undefined,
            ...range,
        };
    }, [datasourceId, databaseName, jobId, rangeKey, customFrom, customTo]);

    /** 已应用筛选（初始最近 30 天）：全部数据以它为准，草稿改动不触发刷新（PRD §6.7 生成报告语义） */
    const [appliedRequest, setAppliedRequest] = useState<QualityReportRequest>(() => rangeToIso(30));

    /** 生成报告：自定义范围校验 → 应用草稿 */
    const handleGenerate = () => {
        if (rangeKey === 'custom' && (!customFrom || !customTo)) {
            notify.warning('请选择自定义起止时间');
            return;
        }
        setAppliedRequest(buildRequest());
    };

    // KPI + 三图：仅随已应用筛选加载
    useEffect(() => {
        setLoading(true);
        Promise.all([
            getQualityReportSummary(appliedRequest).then(setSummary),
            getQualityLevelTrend(appliedRequest).then(r => setLevelTrend(r ?? [])),
            getQualityScoreDistribution(appliedRequest).then(r => setDistribution(r ?? null)),
            getDatasourceComparison(appliedRequest).then(r => setComparison(r ?? [])),
        ]).catch(() => {
            // 拦截器已提示
        }).finally(() => setLoading(false));
    }, [appliedRequest]);

    // 表评分趋势候选表（有评分的表，按当前评分）
    useEffect(() => {
        queryQualityScores({page: 1, pageSize: 100})
            .then(res => {
                const records = res.data?.records ?? [];
                setTableOptions(records.map(s => ({
                    value: String(s.tableId),
                    label: `${s.tableName}（${s.score ?? '—'}）`,
                })));
                if (records.length > 0) setTrendTableId(prev => prev || String(records[0].tableId));
            })
            .catch(() => setTableOptions([]));
    }, []);

    // 评分趋势随表 + 已应用筛选联动
    useEffect(() => {
        if (!trendTableId) return;
        getQualityScoreTrend({...appliedRequest, tableId: trendTableId})
            .then(r => setScoreTrend(r ?? []))
            .catch(() => setScoreTrend([]));
    }, [trendTableId, appliedRequest]);

    // ============ 问题清单（TOP5 + 全部抽屉） ============
    const [topIssues, setTopIssues] = useState<{ list: QualityIssueItem[]; total: number }>({list: [], total: 0});
    const [issuesOpen, setIssuesOpen] = useState(false);

    useEffect(() => {
        getQualityIssues({...appliedRequest, page: 1, pageSize: 5})
            .then(r => setTopIssues({list: r?.records ?? [], total: Number(r?.total ?? 0)}))
            .catch(() => setTopIssues({list: [], total: 0}));
    }, [appliedRequest]);

    // 全部问题抽屉（分页；未打开时门控不请求）
    const issuesPager = usePagedList<Record<string, never>, QualityIssueItem>({
        fetcher: ({page: p, pageSize: ps}) => {
            if (!issuesOpen) return Promise.resolve({list: [], total: 0});
            return getQualityIssues({...appliedRequest, page: p, pageSize: ps})
                .then(r => ({list: r?.records ?? [], total: Number(r?.total ?? 0)}));
        },
        initialQuery: {},
    });
    useEffect(() => {
        if (issuesOpen) issuesPager.reload();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [issuesOpen, appliedRequest]);

    // ============ 导出（与页面已应用口径一致） ============
    const [exporting, setExporting] = useState(false);
    const handleExport = async () => {
        setExporting(true);
        try {
            const blob = await exportQualityReport(appliedRequest);
            const date = formatDateTimeLocalInput(new Date()).slice(0, 10).replace(/-/g, '');
            if (await downloadCsvBlob(blob, `DataNest-质量报告-${date}.csv`)) {
                notify.success('质量报告已导出');
            }
        } catch {
            // 拦截器已提示
        } finally {
            setExporting(false);
        }
    };

    const issueTotal = topIssues.total;

    return (
        <div className="h-[calc(100vh-9rem)] flex flex-col overflow-hidden">
            {/* 顶栏：标题 + 筛选 + 操作 */}
            <div className="flex items-start justify-between mb-ds-4 flex-shrink-0 gap-ds-4 flex-wrap">
                <div>
                    <h1 className="text-ds-display text-ds-text-primary flex items-center gap-ds-2">
                        <HiOutlineChartBar className="text-ds-accent"/>
                        质量报告
                    </h1>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1">
                        多维质量成果一屏总览：趋势 · 分布 · 对比 · 问题清单
                    </p>
                </div>
                <div className="flex items-center gap-ds-2 flex-wrap">
                    <DsFilterSelect value={datasourceId} onChange={(v) => {
                        setDatasourceId(v);
                        setDatabaseName('');
                    }} aria-label="按数据源筛选" options={dsOptions}/>
                    <DsFilterSelect value={databaseName} onChange={setDatabaseName} aria-label="按库筛选"
                                    options={dbOptions}/>
                    <DsFilterSelect value={jobId} onChange={setJobId} aria-label="按质量任务筛选"
                                    options={jobOptions}/>
                    <DsFilterSelect value={rangeKey} onChange={setRangeKey} aria-label="时间范围"
                                    options={RANGE_OPTIONS}/>
                    {rangeKey === 'custom' && (
                        <DsRangePicker from={customFrom} to={customTo}
                                       onChange={(f, t) => {
                                           setCustomFrom(f);
                                           setCustomTo(t);
                                       }}/>
                    )}
                    {canExport && (
                        <DsButton variant="secondary" onClick={handleExport} disabled={exporting}>
                            <HiOutlineArrowDownTray size={14}/>
                            {exporting ? '导出中...' : '导出'}
                        </DsButton>
                    )}
                    <DsButton onClick={handleGenerate} disabled={loading}>
                        {loading ? '生成中...' : '生成报告'}
                    </DsButton>
                </div>
            </div>

            {/* KPI × 5 */}
            <div className="grid grid-cols-5 gap-ds-4 mb-ds-4 flex-shrink-0">
                <KpiCard label="检查批次" value={summary?.batchCount ?? '—'} unit="批次" sub="范围内执行完成"/>
                <KpiCard label="规则明细" value={summary?.detailCount ?? '—'} unit="条" sub="全部规则检查明细"/>
                <KpiCard label="平均评分" value={summary?.avgScore != null ? String(summary.avgScore) : '—'}
                         sub="表最近评分均值"/>
                <KpiCard label="通过率" value={summary?.passRate != null ? String(summary.passRate) : '—'} unit="%"
                         sub="通过 / 有效明细"/>
                <KpiCard label="待处理问题"
                         value={summary ? String(Number(summary.severeCount ?? 0) + Number(summary.warningCount ?? 0)) : '—'}
                         sub={`严重 ${summary?.severeCount ?? 0} / 警告 ${summary?.warningCount ?? 0}`}
                         danger={Number(summary?.severeCount ?? 0) > 0}/>
            </div>

            {/* 行 2：四档趋势 + 评分分布 + 数据源对比 */}
            <div className="flex gap-ds-4 mb-ds-4 flex-shrink-0" style={{height: '240px'}}>
                <ChartCard title="四档分布趋势" sub="按天聚合的四档检查明细" className="flex-[1.4]">
                    <LevelTrendChart data={levelTrend}/>
                </ChartCard>
                <ChartCard title="表评分分布"
                           sub={distribution ? `${distribution.totalTables ?? 0} 张表` : undefined}
                           className="flex-1">
                    <ScoreDonut data={distribution ?? {}} avgScore={summary?.avgScore}/>
                </ChartCard>
                <ChartCard title="数据源质量对比" sub="平均评分" className="flex-1">
                    <ComparisonBars data={comparison}/>
                </ChartCard>
            </div>

            {/* 行 3：表评分趋势 + 问题清单 */}
            <div className="flex-1 min-h-0 flex gap-ds-4">
                <ChartCard title="表评分趋势" sub="历史评分" className="flex-1"
                           action={(
                               <DsFilterSelect value={trendTableId} onChange={setTrendTableId}
                                               aria-label="选择表查看评分趋势" options={tableOptions}
                                               className="min-w-[180px]"/>
                           )}>
                    <ScoreTrendChart data={scoreTrend}/>
                </ChartCard>
                <ChartCard title="问题清单" sub="严重 / 警告明细" className="flex-[1.25]"
                           action={(
                               <button type="button"
                                       className="flex items-center gap-ds-1 text-ds-tiny text-ds-accent hover:underline"
                                       onClick={() => setIssuesOpen(true)}>
                                   查看全部 {issueTotal} 条
                                   <HiOutlineArrowRight size={12}/>
                               </button>
                           )}>
                    {topIssues.list.length === 0 ? (
                        <DsTableEmpty description="范围内暂无待处理问题"/>
                    ) : (
                        <div className="h-full overflow-y-auto">
                            {topIssues.list.map(issue => (
                                <button key={issue.detailId} type="button"
                                        onClick={() => navigate(`/asset-catalog/${issue.tableId}?tab=quality`)}
                                        className="w-full flex items-center gap-ds-3 py-ds-2 border-b border-ds-border-subtle last:border-b-0 text-left hover:bg-ds-bg-hover transition-colors">
                                    <span className="font-mono text-ds-small text-ds-accent w-36 truncate"
                                          title={issue.tableName}>
                                        {issue.tableName}
                                    </span>
                                    <span className="flex-1 min-w-0 text-ds-small text-ds-text-secondary truncate"
                                          title={issue.ruleName}>
                                        {issue.ruleName}
                                    </span>
                                    <DsStatusBadge
                                        variant={issue.resultLevel === 'SEVERE' ? 'danger' : 'warning'}
                                        label={levelLabel(issue.resultLevel)}/>
                                    <span className="text-ds-tiny text-ds-text-muted whitespace-nowrap">
                                        {formatDateTime(issue.checkedAt)}
                                    </span>
                                </button>
                            ))}
                        </div>
                    )}
                </ChartCard>
            </div>

            {/* 全部问题抽屉（分页） */}
            <Drawer open={issuesOpen} onClose={() => setIssuesOpen(false)}
                    title={(
                        <span className="flex items-center gap-ds-2">
                            <HiOutlineExclamationTriangle size={16} className="text-ds-danger"/>
                            问题清单（{issuesPager.total} 条）
                        </span>
                    )}
                    width="max-w-[860px]">
                <div className="flex flex-col">
                    {issuesPager.list.map(issue => (
                        <div key={issue.detailId}
                             className="flex items-center gap-ds-3 py-ds-2 border-b border-ds-border-subtle last:border-b-0">
                            <span className="font-mono text-ds-small text-ds-text-primary w-44 truncate"
                                  title={issue.tableName}>
                                {issue.tableName}
                            </span>
                            <span className="flex-1 min-w-0">
                                <span className="block text-ds-small text-ds-text-secondary truncate"
                                      title={issue.ruleName}>{issue.ruleName}</span>
                                <span className="text-ds-tiny text-ds-text-muted">
                                    {QUALITY_TYPE_LABEL[issue.ruleType as keyof typeof QUALITY_TYPE_LABEL] ?? issue.ruleType ?? '—'}
                                    {' · '}结果值 {issue.resultValue ?? '—'} / 阈值 {issue.threshold ?? '—'}
                                </span>
                            </span>
                            <DsStatusBadge variant={issue.resultLevel === 'SEVERE' ? 'danger' : 'warning'}
                                           label={levelLabel(issue.resultLevel)}/>
                            <span className="text-ds-tiny text-ds-text-muted whitespace-nowrap">
                                {formatDateTime(issue.checkedAt)}
                            </span>
                        </div>
                    ))}
                    {issuesPager.list.length === 0 && !issuesPager.loading && (
                        <DsTableEmpty description="范围内暂无待处理问题"/>
                    )}
                </div>
                {issuesPager.total > 0 && (
                    <Pagination
                        page={issuesPager.page}
                        pageSize={issuesPager.pageSize}
                        total={issuesPager.total}
                        onChange={(p, s) => {
                            issuesPager.setPage(p);
                            if (s !== issuesPager.pageSize) issuesPager.setPageSize(s);
                        }}
                    />
                )}
            </Drawer>
        </div>
    );
}
