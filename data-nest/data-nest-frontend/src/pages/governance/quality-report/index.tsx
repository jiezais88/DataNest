// Sprint 8 F3：质量报告 Dashboard（DG-07 完整版，2026-08-11 产品化重排）。
// 三区结构：KPI×5（现在怎么样）→ 趋势区（四档分布 + 平均评分，均聚合口径）→ 结构/行动区（评分分布 + 数据源对比 + 问题清单）。
// 筛选草稿 → 「查询」统一应用；数据源↔库双向联动、任务随数据源联动；查看全角色，导出治理员/超管（后端 1005 兜底）。
import {useCallback, useEffect, useState} from 'react';
import {useLocation, useNavigate} from 'react-router-dom';
import {Tooltip} from 'antd';
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
import Drawer from '@/components/Drawer';
import DsButton from '@/components/DsButton';
import DsFilterSelect from '@/components/DsFilterSelect';
import DsRangePicker from '@/components/DsRangePicker';
import DsStatusBadge from '@/components/DsStatusBadge';
import DsTableEmpty from '@/components/DsTableEmpty';
import Pagination from '@/components/Pagination';
import {GOVERNANCE_WRITE_PERMS} from '@/constants/permissions';
import usePagedList from '@/hooks/usePagedList';
import {useCan} from '@/hooks/useCan';
import {formatDateTime, formatDateTimeLocalInput} from '@/utils/format';
import {notify} from '@/utils/notify';
import {downloadExportBlob} from '@/utils/download';
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
    const location = useLocation();
    const canExport = useCan(...GOVERNANCE_WRITE_PERMS);

    // ============ 筛选（草稿 → 查询时应用） ============
    const [datasourceId, setDatasourceId] = useState('');
    const [databaseName, setDatabaseName] = useState('');
    /** 库下拉选中键（'' = 全部库；否则 `数据源ID::库名` 复合键，同名库多数据源归属时选择无歧义） */
    const [dbKey, setDbKey] = useState('');
    const [jobId, setJobId] = useState('');
    const [rangeKey, setRangeKey] = useState('30');
    const [customFrom, setCustomFrom] = useState('');
    const [customTo, setCustomTo] = useState('');

    const [dsOptions, setDsOptions] = useState<{ value: string; label: string }[]>([]);
    const [dbOptions, setDbOptions] = useState<{ value: string; label: string }[]>([]);
    const [jobOptions, setJobOptions] = useState<{ value: string; label: string }[]>([]);

    // 选项加载（库/任务随数据源联动）
    useEffect(() => {
        getQualityReportOptions(datasourceId || undefined)
            .then(res => {
                const datasources = res?.datasources ?? [];
                setDsOptions([{value: '', label: '全部数据源'},
                    ...datasources.map(d => ({value: String(d.id), label: d.name || `数据源 ${d.id}`}))]);
                const dsNameMap = new Map(datasources.map(d => [String(d.id), d.name || `数据源 ${d.id}`]));
                // 库选项 = `库名（数据源名）` + 复合键值（同名库可属多个数据源，option value 必须全局唯一，
                // 否则原生 select 重复 key 会 reconciliation 残留旧选项——E2E 2026-08-11 抓到）
                setDbOptions([{value: '', label: '全部库'},
                    ...(res?.databases ?? []).map(db => ({
                        value: `${db.datasourceId}::${db.name}`,
                        label: `${db.name}（${dsNameMap.get(String(db.datasourceId)) ?? db.datasourceId}）`,
                    }))]);
                const jobs = res?.jobs ?? [];
                // 选了数据源但无关联任务时给空态提示，避免下拉空白误以为不可选
                setJobOptions([{value: '', label: datasourceId && jobs.length === 0 ? '该数据源下暂无任务' : '全部质量任务'},
                    ...jobs.map(j => ({value: String(j.id), label: j.name || `任务 ${j.id}`}))]);
            })
            .catch(() => {
                // 拦截器已提示
            });
    }, [datasourceId]);

    /** 选手动改数据源：清空下游（库/任务） */
    const handleDatasourceChange = (v: string) => {
        setDatasourceId(v);
        setDbKey('');
        setDatabaseName('');
        setJobId('');
    };

    /** 选库：复合键同时精确设定 数据源+库（反向联动无歧义）；任务随数据源收窄故清空 */
    const handleDatabaseChange = (v: string) => {
        setDbKey(v);
        setJobId('');
        if (!v) {
            setDatabaseName('');
            return;
        }
        const sep = v.indexOf('::');
        const dsId = v.slice(0, sep);
        setDatabaseName(v.slice(sep + 2));
        if (dsId && dsId !== datasourceId) setDatasourceId(dsId);
    };

    // ============ 报告数据 ============
    const [summary, setSummary] = useState<QualityReportSummary | null>(null);
    const [levelTrend, setLevelTrend] = useState<QualityLevelTrendPoint[]>([]);
    const [distribution, setDistribution] = useState<QualityScoreDistribution | null>(null);
    const [comparison, setComparison] = useState<DatasourceScoreComparison[]>([]);
    const [scoreTrend, setScoreTrend] = useState<QualityScoreTrendPoint[]>([]);
    const [loading, setLoading] = useState(false);

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

    /** 查询：自定义范围校验 → 应用草稿（只刷新面板，不做任何历史计算） */
    const handleGenerate = () => {
        if (rangeKey === 'custom' && (!customFrom || !customTo)) {
            notify.warning('请选择自定义起止时间');
            return;
        }
        setAppliedRequest(buildRequest());
    };

    // KPI + 趋势区 + 结构区：仅随已应用筛选加载
    useEffect(() => {
        setLoading(true);
        Promise.all([
            getQualityReportSummary(appliedRequest).then(setSummary),
            getQualityLevelTrend(appliedRequest).then(r => setLevelTrend(r ?? [])),
            getQualityScoreDistribution(appliedRequest).then(r => setDistribution(r ?? null)),
            getDatasourceComparison(appliedRequest).then(r => setComparison(r ?? [])),
            getQualityScoreTrend(appliedRequest).then(r => setScoreTrend(r ?? [])),
        ]).catch(() => {
            // 拦截器已提示
        }).finally(() => setLoading(false));
    }, [appliedRequest]);

    // ============ 问题清单（TOP6 + 全部抽屉；行 flex-1 拉伸填满卡片，任何视口高度都不留白/不滚动） ============
    const [topIssues, setTopIssues] = useState<{ list: QualityIssueItem[]; total: number }>({list: [], total: 0});
    const [issuesOpen, setIssuesOpen] = useState(false);

    useEffect(() => {
        getQualityIssues({...appliedRequest, page: 1, pageSize: 8})
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
            if (await downloadExportBlob(blob, `DataNest-质量报告-${date}.xlsx`)) {
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
        <div className="h-full flex flex-col overflow-hidden">
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
                    <DsFilterSelect value={datasourceId} onChange={handleDatasourceChange}
                                    aria-label="按数据源筛选" options={dsOptions} className="w-[160px]"/>
                    <DsFilterSelect value={dbKey} onChange={handleDatabaseChange} aria-label="按库筛选"
                                    options={dbOptions} className="w-[220px]"/>
                    <DsFilterSelect value={jobId} onChange={setJobId} aria-label="按质量任务筛选"
                                    options={jobOptions} className="w-[180px]"/>
                    <DsFilterSelect value={rangeKey} onChange={setRangeKey} aria-label="时间范围"
                                    options={RANGE_OPTIONS} className="w-[140px]"/>
                    {rangeKey === 'custom' && (
                        <DsRangePicker from={customFrom} to={customTo}
                                       onChange={(f, t) => {
                                           setCustomFrom(f);
                                           setCustomTo(t);
                                       }}/>
                    )}
                    {canExport && (
                        <DsButton variant="secondary" onClick={handleExport} disabled={exporting} loading={exporting}>
                            <HiOutlineArrowDownTray size={14}/>
                            导出
                        </DsButton>
                    )}
                    <DsButton onClick={handleGenerate} disabled={loading} loading={loading}>
                        查询
                    </DsButton>
                </div>
            </div>

            {/* KPI × 5 */}
            <div className="grid grid-cols-5 gap-ds-4 mb-ds-4 flex-shrink-0">
                <KpiCard label="待处理问题"
                         value={summary ? String(Number(summary.severeCount ?? 0) + Number(summary.warningCount ?? 0)) : '—'}
                         sub={`严重 ${summary?.severeCount ?? 0} / 警告 ${summary?.warningCount ?? 0}`}
                         danger={Number(summary?.severeCount ?? 0) > 0}/>
                <KpiCard label="通过率" value={summary?.passRate != null ? String(summary.passRate) : '—'} unit="%"
                         sub="通过 / 有效明细"/>
                <KpiCard label="平均评分" value={summary?.avgScore != null ? String(summary.avgScore) : '—'}
                         sub="表最近评分均值"/>
                <KpiCard label="检查批次" value={summary?.batchCount ?? '—'} unit="批次" sub="范围内执行完成"/>
                <KpiCard label="规则明细" value={summary?.detailCount ?? '—'} unit="条" sub="全部规则检查明细"/>
            </div>

            {/* 趋势区：四档分布趋势 + 平均评分趋势（均为聚合口径） */}
            <div className="flex gap-ds-4 mb-ds-4 flex-shrink-0" style={{height: '240px'}}>
                <ChartCard title="四档分布趋势" sub="按天聚合的四档检查明细" className="flex-[1.4]">
                    <LevelTrendChart data={levelTrend}/>
                </ChartCard>
                <ChartCard title="平均评分趋势" sub="按天聚合范围内表评分" className="flex-1">
                    <ScoreTrendChart data={scoreTrend}/>
                </ChartCard>
            </div>

            {/* 结构/行动区：评分分布 + 数据源对比 + 问题清单 */}
            <div className="flex-1 min-h-0 flex gap-ds-4">
                <ChartCard title="表评分分布"
                           sub={distribution ? `${distribution.totalTables ?? 0} 张表` : undefined}
                           className="flex-1">
                    <ScoreDonut data={distribution ?? {}} avgScore={summary?.avgScore}/>
                </ChartCard>
                <ChartCard title="数据源质量对比" sub="平均评分" className="flex-1">
                    <ComparisonBars data={comparison}/>
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
                        <div className="h-full overflow-y-auto flex flex-col">
                            {topIssues.list.map(issue => (
                                <button key={issue.detailId} type="button"
                                        onClick={() => navigate(`/asset-catalog/${issue.tableId}?tab=quality`, {state: {from: location.pathname}})}
                                        className="w-full flex items-center gap-ds-3 py-[4px] border-b border-ds-border-subtle last:border-b-0 text-left hover:bg-ds-bg-hover transition-colors">
                                    <Tooltip title={issue.tableName}>
                                        <span className="font-mono text-ds-small text-ds-accent w-36 truncate">
                                            {issue.tableName}
                                        </span>
                                    </Tooltip>
                                    <Tooltip title={issue.ruleName}>
                                        <span className="flex-1 min-w-0 text-ds-small text-ds-text-secondary truncate">
                                            {issue.ruleName}
                                        </span>
                                    </Tooltip>
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
                            <Tooltip title={issue.tableName}>
                                <span className="font-mono text-ds-small text-ds-text-primary w-44 truncate">
                                    {issue.tableName}
                                </span>
                            </Tooltip>
                            <span className="flex-1 min-w-0">
                                <Tooltip title={issue.ruleName}>
                                    <span className="block text-ds-small text-ds-text-secondary truncate">{issue.ruleName}</span>
                                </Tooltip>
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
