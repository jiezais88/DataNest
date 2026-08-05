// 表级质量评分（Sprint 6 NG8）
// 独立列表页：按数据源/健康度/表名筛选，展示各表的评分、健康度、通过/警告/严重规则数。
// 支持「查看详情」（评分概览 + 规则最近结果）、「评分算法说明」（静态弹窗）、「扣分配置」（全局配置读写）。
// 数据来源：POST /governance/quality/scores/page + GET /quality/scores/table/{tableId}/rules
//          + GET/PUT /quality/scores/config
import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {useSearchParams} from 'react-router-dom';
import {Table, Tooltip} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import {HiOutlineAdjustmentsHorizontal, HiOutlineCalculator, HiOutlineEye} from 'react-icons/hi2';
import {formatDateTime} from '../../../utils/format';
import {COL} from '../../../constants/table';
import {notify} from '../../../utils/notify';
import {useHasRole} from '../../../hooks/useHasRole';
import {GOVERNANCE_WRITE_ROLES} from '../../../constants/roles';
import {getDataSources} from '../../../api/datasource';
import {
    executeTableQualityRules,
    getQualityScoreConfig,
    getQualityScoreByTable,
    getTableQualityRuleResults,
    queryQualityScores,
    updateQualityScoreConfig,
} from '../../../api/quality';
import DsButton from '../../../components/DsButton';
import DsIconButton from '../../../components/DsIconButton';
import DsFilterSelect from '../../../components/DsFilterSelect';
import DsModal from '../../../components/DsModal';
import DsStatusBadge from '../../../components/DsStatusBadge';
import DsToolbar from '../../../components/DsToolbar';
import SearchInput from '../../../components/SearchInput';
import type {DsStatusVariant} from '../../../components/DsStatusBadge';
import DsTableEmpty from '../../../components/DsTableEmpty';
import Pagination from '../../../components/Pagination';
import QualityScoreBadge from '../../../components/QualityScoreBadge';
import type {DataSource} from '../../../types/datasource';
import {
    QUALITY_CHECK_LEVEL_LABEL,
    QUALITY_HEALTH_LABEL,
    QUALITY_HEALTH_OPTIONS,
    QUALITY_TYPE_LABEL,
} from '../../../types/quality';
import type {
    QualityCheckLevel,
    QualityHealthLevel,
    QualityScore,
    QualityScoreConfig,
    QualityTableRuleResult,
} from '../../../types/quality';

/** 健康度 -> 徽章变体（列表「健康度」列；未配置显示暂无） */
const HEALTH_VARIANT: Record<QualityHealthLevel, DsStatusVariant> = {
    EXCELLENT: 'success',
    GOOD: 'success',
    WARNING: 'warning',
    BAD: 'danger',
};

/** 规则分级判定 -> 徽章变体（详情页规则结果列表，对齐分级语义） */
const LEVEL_VARIANT: Record<QualityCheckLevel, DsStatusVariant> = {
    PASS: 'success',
    WARNING: 'warning',
    SEVERE: 'danger',
    UNAVAILABLE: 'pending',
};

export default function QualityScoresPage() {
    const [searchParams, setSearchParams] = useSearchParams();
    const canWrite = useHasRole(...GOVERNANCE_WRITE_ROLES);

    // ============ 分页 + 筛选 ============
    const [items, setItems] = useState<QualityScore[]>([]);
    const [total, setTotal] = useState(0);
    const [page, setPage] = useState(1);
    const [pageSize, setPageSize] = useState(10);
    const [keyword, setKeyword] = useState('');
    const [keywordInput, setKeywordInput] = useState('');
    const [datasourceId, setDatasourceId] = useState('');
    const [healthLevel, setHealthLevel] = useState<QualityHealthLevel | ''>('');
    const [loading, setLoading] = useState(false);

    // 数据源下拉
    const [datasources, setDatasources] = useState<DataSource[]>([]);
    const [datasourceOptions, setDatasourceOptions] = useState<{value: string; label: string}[]>([
        {value: '', label: '全部数据源'},
    ]);

    // ============ 详情弹窗 ============
    const [detailOpen, setDetailOpen] = useState(false);
    const [detailLoading, setDetailLoading] = useState(false);
    const [detail, setDetail] = useState<QualityScore | null>(null);
    const [detailRules, setDetailRules] = useState<QualityTableRuleResult[]>([]);
    const [executing, setExecuting] = useState(false);

    // ============ 扣分配置弹窗 ============
    const [configOpen, setConfigOpen] = useState(false);
    const [configSaving, setConfigSaving] = useState(false);
    const [config, setConfig] = useState<QualityScoreConfig>({warningDeduct: 10, severeDeduct: 30, badThreshold: 60});

    // ============ 评分算法说明弹窗 ============
    const [algoOpen, setAlgoOpen] = useState(false);

    const loadDataSources = useCallback(async () => {
        try {
            const res = await getDataSources({page: 1, pageSize: 500});
            const list = res.data.records ?? [];
            setDatasources(list);
            setDatasourceOptions([
                {value: '', label: '全部数据源'},
                ...list.map((d) => ({value: d.id, label: d.name})),
            ]);
        } catch {
            // 数据源下拉失败不影响评分列表加载，保持仅「全部数据源」
            setDatasourceOptions([{value: '', label: '全部数据源'}]);
        }
    }, []);

    useEffect(() => {
        loadDataSources();
    }, [loadDataSources]);

    const loadScores = useCallback(async () => {
        setLoading(true);
        try {
            const res = await queryQualityScores({
                page,
                pageSize,
                keyword: keyword || undefined,
                datasourceId: datasourceId || undefined,
                healthLevel: healthLevel || undefined,
            });
            setItems(res.data.records);
            setTotal(res.data.total);
        } finally {
            setLoading(false);
        }
    }, [page, pageSize, keyword, datasourceId, healthLevel]);

    useEffect(() => {
        loadScores();
    }, [loadScores]);

    // URL 状态同步（对齐质量检查历史）：进页初始化筛选与分页，深层跳转返回后筛选不丢
    const urlInitRef = useRef(false);
    useEffect(() => {
        if (urlInitRef.current) return;
        urlInitRef.current = true;
        const p = searchParams;
        const hl = p.get('healthLevel');
        const ds = p.get('datasourceId');
        setHealthLevel(QUALITY_HEALTH_OPTIONS.some(o => o.value === hl) ? (hl as QualityHealthLevel) : '');
        setDatasourceId(ds && datasources.some(d => d.id === ds) ? ds : '');
        const kw = p.get('keyword');
        if (kw) {
            setKeyword(kw);
            setKeywordInput(kw);
        }
        setPage(Number(p.get('page')) || 1);
        const ps = Number(p.get('pageSize')) || 10;
        if (ps !== 10) setPageSize(ps);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [datasources]);

    useEffect(() => {
        const next = new URLSearchParams();
        if (healthLevel) next.set('healthLevel', healthLevel);
        if (datasourceId) next.set('datasourceId', datasourceId);
        if (keyword) next.set('keyword', keyword);
        if (page > 1) next.set('page', String(page));
        if (pageSize !== 10) next.set('pageSize', String(pageSize));
        if (next.toString() === searchParams.toString()) return;
        setSearchParams(next, {replace: true});
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [healthLevel, datasourceId, keyword, page, pageSize]);

    const resetFilters = () => {
        setHealthLevel('');
        setDatasourceId('');
        setKeyword('');
        setKeywordInput('');
        setPage(1);
    };

    const handleSearch = () => {
        setKeyword(keywordInput.trim());
        setPage(1);
    };

    // ============ 详情 ============
    const openDetail = useCallback(async (item: QualityScore) => {
        setDetailOpen(true);
        setDetailLoading(true);
        setDetail(item);
        setDetailRules([]);
        if (!item.tableId) {
            setDetailLoading(false);
            return;
        }
        try {
            const [scoreRes, ruleRes] = await Promise.all([
                getQualityScoreByTable(item.tableId),
                getTableQualityRuleResults(item.tableId),
            ]);
            setDetail(scoreRes.data ?? item);
            setDetailRules(ruleRes.data ?? []);
        } finally {
            setDetailLoading(false);
        }
    }, []);

    const handleExecute = async () => {
        if (!detail?.tableId) return;
        setExecuting(true);
        try {
            await executeTableQualityRules(detail.tableId);
            notify.success('已触发执行，该表全部启用规则已提交到执行节点，稍后刷新查看结果');
            setExecuting(false);
            setDetailOpen(false);
        } catch (e: any) {
            notify.error(e?.message || '执行失败');
            setExecuting(false);
        }
    };

    // ============ 扣分配置 ============
    const openConfig = async () => {
        setConfigOpen(true);
        try {
            const res = await getQualityScoreConfig();
            setConfig({...res.data});
        } catch {
            // 读取失败保留默认值
        }
    };

    const handleConfigSave = async () => {
        if (!config.warningDeduct || !config.severeDeduct || !config.badThreshold) {
            notify.warning('请完整填写警告扣分/严重扣分/低分区阈值');
            return;
        }
        if (config.warningDeduct <= 0 || config.severeDeduct <= 0 || config.badThreshold <= 0) {
            notify.warning('扣分值与低分区阈值必须为正整数');
            return;
        }
        setConfigSaving(true);
        try {
            await updateQualityScoreConfig(config);
            notify.success('扣分配置已保存');
            setConfigSaving(false);
            setConfigOpen(false);
        } catch (e: any) {
            notify.error(e?.message || '保存失败');
            setConfigSaving(false);
        }
    };

    // ============ 列 ============
    const columns = useMemo<ColumnsType<QualityScore>>(() => [
        {
            title: '数据源',
            dataIndex: 'datasourceName',
            width: COL.NAME,
            ellipsis: true,
            render: (v?: string) => (
                <span title={v || '—'} className="text-ds-small text-ds-text-secondary whitespace-nowrap">{v || '—'}</span>
            ),
        },
        {
            title: '表名',
            dataIndex: 'tableName',
            width: COL.NAME,
            ellipsis: true,
            render: (v?: string, r?: QualityScore) => (
                <button
                    onClick={() => r && openDetail(r)}
                    title={v || ''}
                    className="text-ds-small text-ds-accent hover:underline text-left font-medium"
                >
                    {v || '—'}
                </button>
            ),
        },
        {
            title: '评分',
            dataIndex: 'score',
            width: COL.COUNT_NORMAL,
            render: (v?: number | string, r?: QualityScore) => (
                <QualityScoreBadge table score={v} healthLevel={r?.healthLevel}/>
            ),
        },
        {
            title: '健康度',
            dataIndex: 'healthLevel',
            width: COL.STATUS,
            render: (v?: QualityHealthLevel) => (
                v ? (
                    <DsStatusBadge variant={HEALTH_VARIANT[v]} label={QUALITY_HEALTH_LABEL[v]}/>
                ) : (
                    <span className="inline-flex items-center rounded-full px-ds-2 py-0.5 text-ds-small font-medium bg-[#f1f5f9] text-[#94a3b8]">
                        暂无质量
                    </span>
                )
            ),
        },
        {
            title: '通过',
            dataIndex: 'passRules',
            width: COL.COUNT,
            align: 'right',
            render: (v?: number) => <span className="text-ds-small text-ds-text-primary">{v ?? 0}</span>,
        },
        {
            title: '警告',
            dataIndex: 'warningRules',
            width: COL.COUNT,
            align: 'right',
            render: (v?: number) => <span className="text-ds-small text-ds-warning">{v ?? 0}</span>,
        },
        {
            title: '严重',
            dataIndex: 'severeRules',
            width: COL.COUNT,
            align: 'right',
            render: (v?: number) => <span className="text-ds-small text-ds-danger">{v ?? 0}</span>,
        },
        {
            title: '最近检查',
            dataIndex: 'lastCheckedAt',
            width: COL.DATETIME_COMPACT,
            render: (v?: string) => (
                <span className="text-ds-small text-ds-text-secondary whitespace-nowrap">{formatDateTime(v) || '—'}</span>
            ),
        },
        {
            title: '操作',
            key: 'action',
            width: COL.OPERATION_2,
            render: (_, r) => (
                <div className="flex items-center gap-ds-1">
                    <Tooltip title="查看详情">
                        <DsIconButton tone="accent" onClick={() => openDetail(r)} aria-label="查看详情">
                            <HiOutlineEye size={14}/>
                        </DsIconButton>
                    </Tooltip>
                </div>
            ),
        },
    ], [openDetail]);

    // ============ 渲染 ============
    return (
        <div className="flex flex-col">
            <div className="flex items-center justify-between mb-ds-5 flex-shrink-0">
                <div>
                    <h1 className="text-ds-display text-ds-text-primary">表级质量评分</h1>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1">综合各表启用质量规则的最近检查结果加权评分，联动血缘图谱展示</p>
                </div>
                <div className="flex items-center gap-ds-2 flex-shrink-0">
                    <DsButton onClick={() => setAlgoOpen(true)}>
                        <HiOutlineCalculator size={16}/>
                        评分算法说明
                    </DsButton>
                    {canWrite && (
                        <DsButton onClick={openConfig}>
                            <HiOutlineAdjustmentsHorizontal size={16}/>
                            扣分配置
                        </DsButton>
                    )}
                </div>
            </div>

            <div className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden flex flex-col">
                <div className="p-ds-3 border-b border-ds-border-subtle flex-shrink-0">
                    <DsToolbar
                        extra={(
                            <>
                                <DsButton onClick={handleSearch} disabled={loading}>
                                    {loading ? '查询中...' : '查询'}
                                </DsButton>
                                <DsButton variant="secondary" onClick={resetFilters}>重置</DsButton>
                            </>
                        )}
                    >
                        <SearchInput
                            value={keywordInput}
                            onChange={(e) => setKeywordInput(e.target.value)}
                            onEnter={handleSearch}
                            placeholder="搜索表名（库名.表名）"
                            aria-label="搜索表名"
                        />
                        <DsFilterSelect
                            value={datasourceId}
                            onChange={setDatasourceId}
                            aria-label="按数据源筛选"
                            options={datasourceOptions}
                        />
                        <DsFilterSelect
                            value={healthLevel}
                            onChange={(v) => setHealthLevel(v as QualityHealthLevel | '')}
                            aria-label="按健康度筛选"
                            options={QUALITY_HEALTH_OPTIONS}
                        />
                    </DsToolbar>
                </div>
                <div className="overflow-x-auto">
                    <Table
                        rowKey={(r) => r.tableId ?? r.id ?? ''}
                        columns={columns}
                        dataSource={items}
                        loading={loading}
                        pagination={false}
                        className="prototype-table prototype-table-flush"
                        locale={{emptyText: <DsTableEmpty description="暂无评分数据"/>}}
                    />
                </div>
                {total > 0 && (
                    <Pagination
                        page={page}
                        pageSize={pageSize}
                        total={total}
                        onChange={(p, s) => {
                            setPage(p);
                            setPageSize(s);
                        }}
                    />
                )}
            </div>

            {/* 评分算法说明弹窗 */}
            <DsModal
                open={algoOpen}
                onClose={() => setAlgoOpen(false)}
                title="评分算法说明"
                width="w-[560px]"
                bordered
                footer={<DsButton variant="primary" onClick={() => setAlgoOpen(false)}>知道了</DsButton>}
            >
                <div className="space-y-ds-3 text-ds-small text-ds-text-secondary">
                    <p>评分以规则检查结果与权重为基础，0~100 分，算法如下：</p>
                    <div className="bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm p-ds-3 font-mono text-ds-small space-y-ds-1">
                        <div>基础分 = 100 × (通过规则权重和 ÷ 有效规则权重和)</div>
                        <div>警告扣分 = 警告规则权重和 × 每权重扣 {config.warningDeduct} 分</div>
                        <div>严重扣分 = 严重规则权重和 × 每权重扣 {config.severeDeduct} 分</div>
                        <div>最终分 = max(0, 基础分 − 警告扣分 − 严重扣分)</div>
                        <div>存在严重规则时强制压至低分区</div>
                    </div>
                    <p>健康度区间（分）：优秀 ≥ 85 · 良好 ≥ 75 · 一般 ≥ {config.badThreshold} · 差 &lt; {config.badThreshold}</p>
                    <p>「不可用」（数据源不可用 / SQL 失败）不参与评分；未配置启用规则的表不评分。</p>
                </div>
            </DsModal>

            {/* 扣分配置弹窗 */}
            <DsModal
                open={configOpen}
                onClose={() => setConfigOpen(false)}
                title="扣分配置（质量评分全局配置）"
                width="w-[440px]"
                bordered
                footer={
                    <>
                        <DsButton variant="secondary" onClick={() => setConfigOpen(false)}>取消</DsButton>
                        <DsButton variant="primary" disabled={configSaving} onClick={handleConfigSave}>
                            {configSaving ? '保存中...' : '保存'}
                        </DsButton>
                    </>
                }
            >
                <div className="space-y-ds-4 text-ds-small">
                    <label className="block">
                        <span className="text-ds-text-primary font-medium block mb-ds-1">警告规则每权重扣分</span>
                        <input
                            type="number"
                            min={1}
                            max={100}
                            value={config.warningDeduct ?? ''}
                            onChange={(e) => setConfig({...config, warningDeduct: Number(e.target.value)})}
                            className="w-full px-ds-3 py-[9px] bg-white border border-ds-border-subtle rounded-ds-sm text-sm focus:outline-none focus:border-ds-accent"
                        />
                    </label>
                    <label className="block">
                        <span className="text-ds-text-primary font-medium block mb-ds-1">严重规则每权重扣分</span>
                        <input
                            type="number"
                            min={1}
                            max={100}
                            value={config.severeDeduct ?? ''}
                            onChange={(e) => setConfig({...config, severeDeduct: Number(e.target.value)})}
                            className="w-full px-ds-3 py-[9px] bg-white border border-ds-border-subtle rounded-ds-sm text-sm focus:outline-none focus:border-ds-accent"
                        />
                    </label>
                    <label className="block">
                        <span className="text-ds-text-primary font-medium block mb-ds-1">低分区阈值（评分低于此值 → 健康度「差」）</span>
                        <input
                            type="number"
                            min={1}
                            max={100}
                            value={config.badThreshold ?? ''}
                            onChange={(e) => setConfig({...config, badThreshold: Number(e.target.value)})}
                            className="w-full px-ds-3 py-[9px] bg-white border border-ds-border-subtle rounded-ds-sm text-sm focus:outline-none focus:border-ds-accent"
                        />
                    </label>
                </div>
            </DsModal>

            {/* 查看详情弹窗 */}
            <DsModal
                open={detailOpen}
                onClose={() => setDetailOpen(false)}
                title={detail?.tableName ? `质量详情 · ${detail.tableName}` : '质量详情'}
                width="w-[760px]"
                bordered
                footer={
                    <>
                        <DsButton variant="secondary" onClick={() => setDetailOpen(false)}>关闭</DsButton>
                        {canWrite && (
                            <DsButton variant="primary" disabled={executing} onClick={handleExecute}>
                                {executing ? '执行中...' : '立即执行全部规则'}
                            </DsButton>
                        )}
                    </>
                }
            >
                {detailLoading ? (
                    <div className="py-ds-10 text-center text-ds-small text-ds-text-muted">加载中…</div>
                ) : (
                    <div className="space-y-ds-5">
                        {/* 评分概览 */}
                        <div className="flex items-center gap-ds-6 flex-wrap">
                            <QualityScoreBadge score={detail?.score} healthLevel={detail?.healthLevel}/>
                            <div className="flex items-center gap-ds-5 text-ds-small text-ds-text-secondary">
                                <div>
                                    <div className="text-ds-text-muted">数据源</div>
                                    <div className="text-ds-text-primary font-medium">{detail?.datasourceName || '—'}</div>
                                </div>
                                <div>
                                    <div className="text-ds-text-muted">最近检查</div>
                                    <div className="text-ds-text-primary font-medium">{formatDateTime(detail?.lastCheckedAt) || '—'}</div>
                                </div>
                                <div>
                                    <div className="text-ds-text-muted">通过 / 警告 / 严重</div>
                                    <div className="text-ds-text-primary font-medium">
                                        <span className="text-ds-success">{detail?.passRules ?? 0}</span>
                                        {' / '}
                                        <span className="text-ds-warning">{detail?.warningRules ?? 0}</span>
                                        {' / '}
                                        <span className="text-ds-danger">{detail?.severeRules ?? 0}</span>
                                    </div>
                                </div>
                            </div>
                        </div>

                        {/* 规则结果列表 */}
                        <div>
                            <h4 className="text-ds-subhead font-semibold text-ds-text-primary mb-ds-2">规则最近结果</h4>
                            {detailRules.length === 0 ? (
                                <div className="text-ds-small text-ds-text-muted py-ds-4 bg-ds-bg-hover rounded-ds-sm text-center">
                                    该表暂无启用规则，或尚未执行检查
                                </div>
                            ) : (
                                <div className="overflow-x-auto">
                                    <Table
                                        rowKey={(r) => r?.ruleId ?? ''}
                                        columns={[
                                            {
                                                title: '规则名称',
                                                dataIndex: 'ruleName',
                                                ellipsis: true,
                                                width: COL.NAME_COMPACT,
                                                render: (v?: string, r?: QualityTableRuleResult) => (
                                                    <div>
                                                        <div className="text-ds-small text-ds-text-primary font-medium">{v || '—'}</div>
                                                        {r?.jobName && (
                                                            <div className="text-ds-tiny text-ds-text-muted truncate">{r.jobName}</div>
                                                        )}
                                                    </div>
                                                ),
                                            },
                                            {
                                                title: '类型',
                                                dataIndex: 'ruleType',
                                                width: COL.STATUS,
                                                render: (v?: string) => (
                                                    <span className="text-ds-small text-ds-text-secondary whitespace-nowrap">
                                                        {v ? (QUALITY_TYPE_LABEL[v as keyof typeof QUALITY_TYPE_LABEL] || v) : '—'}
                                                    </span>
                                                ),
                                            },
                                            {
                                                title: '检查字段',
                                                dataIndex: 'columnName',
                                                width: COL.NAME_COMPACT,
                                                ellipsis: true,
                                                render: (v?: string) => (
                                                    <span className="text-ds-small text-ds-text-secondary" title={v}>{v || '—'}</span>
                                                ),
                                            },
                                            {
                                                title: '权重',
                                                dataIndex: 'weight',
                                                width: COL.COUNT,
                                                align: 'right',
                                                render: (v?: number) => <span className="text-ds-small">{v ?? '—'}</span>,
                                            },
                                            {
                                                title: '最近结果',
                                                dataIndex: 'resultValue',
                                                width: COL.COUNT_NORMAL,
                                                align: 'right',
                                                render: (v?: number | string) => (
                                                    <span className="text-ds-small text-ds-text-primary">{v ?? '—'}</span>
                                                ),
                                            },
                                            {
                                                title: '判定',
                                                dataIndex: 'resultLevel',
                                                width: COL.STATUS,
                                                render: (v?: QualityCheckLevel, r?: QualityTableRuleResult) => (
                                                    v ? (
                                                        <DsStatusBadge variant={LEVEL_VARIANT[v]} label={QUALITY_CHECK_LEVEL_LABEL[v]}/>
                                                    ) : (
                                                        <span className="text-ds-small text-ds-text-muted">
                                                            {r?.success === 0 ? '失败' : '未检查'}
                                                        </span>
                                                    )
                                                ),
                                            },
                                            {
                                                title: '最近检查',
                                                dataIndex: 'lastCheckedAt',
                                                width: COL.DATETIME_COMPACT,
                                                render: (v?: string) => (
                                                    <span className="text-ds-small text-ds-text-secondary whitespace-nowrap">
                                                        {formatDateTime(v) || '—'}
                                                    </span>
                                                ),
                                            },
                                        ]}
                                        dataSource={detailRules}
                                        pagination={false}
                                        scroll={{x: 700}}
                                        className="prototype-table prototype-table-flush"
                                        locale={{emptyText: <DsTableEmpty description="暂无规则结果"/>}}
                                    />
                                </div>
                            )}
                        </div>
                    </div>
                )}
            </DsModal>
        </div>
    );
}
