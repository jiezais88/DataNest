// 质量检查历史（Sprint 8 执行层）
// 展示质量检查批次列表与规则明细，支持按触发方式/批次状态过滤。
// 数据来源：POST /governance/quality/checks/page + GET /governance/quality/checks/{id}
import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {useSearchParams} from 'react-router-dom';
import {Table, Tooltip} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import {formatDateTime, formatDuration} from '../../../utils/format';
import {COL} from '../../../constants/table';
import {
    getQualityCheckDetail,
    queryQualityChecks,
} from '../../../api/quality';
import Drawer from '../../../components/Drawer';
import DsButton from '../../../components/DsButton';
import DsIconButton from '../../../components/DsIconButton';
import DsStatusBadge from '../../../components/DsStatusBadge';
import type {DsStatusVariant} from '../../../components/DsStatusBadge';
import DsTableEmpty from '../../../components/DsTableEmpty';
import DsToolbar from '../../../components/DsToolbar';
import DsFilterSelect from '../../../components/DsFilterSelect';
import Pagination from '../../../components/Pagination';
import {
    QUALITY_CHECK_STATUS_LABEL,
    QUALITY_CHECK_TRIGGER_LABEL,
    QUALITY_TYPE_LABEL,
} from '../../../types/quality';
import type {
    QualityCheckBatch,
    QualityCheckDetail,
    QualityCheckStatus,
    QualityCheckTriggerType,
} from '../../../types/quality';
import {HiOutlineEye} from 'react-icons/hi2';

/** 批次状态 -> 徽章变体（单一出处） */
const STATUS_VARIANT: Record<QualityCheckStatus, DsStatusVariant> = {
    RUNNING: 'running',
    SUCCESS: 'success',
    PARTIAL_FAILED: 'warning',
    FAILED: 'danger',
};

const TRIGGER_OPTIONS = [
    {value: '', label: '全部触发方式'},
    {value: 'MANUAL', label: '手动触发'},
    {value: 'SCHEDULED', label: '定时触发'},
    {value: 'AUTO_TRIGGER', label: '自动触发'},
];

const STATUS_OPTIONS = [
    {value: '', label: '全部状态'},
    {value: 'RUNNING', label: '运行中'},
    {value: 'SUCCESS', label: '成功'},
    {value: 'PARTIAL_FAILED', label: '部分失败'},
    {value: 'FAILED', label: '失败'},
];

export default function QualityChecksPage() {
    const [searchParams, setSearchParams] = useSearchParams();

    // ============ 分页 + 筛选 ============
    const [items, setItems] = useState<QualityCheckBatch[]>([]);
    const [total, setTotal] = useState(0);
    const [page, setPage] = useState(1);
    const [pageSize, setPageSize] = useState(10);
    const [triggerType, setTriggerType] = useState<QualityCheckTriggerType | ''>('');
    const [status, setStatus] = useState<QualityCheckStatus | ''>('');
    const [loading, setLoading] = useState(false);

    // ============ 详情抽屉 ============
    const [detailOpen, setDetailOpen] = useState(false);
    const [detailLoading, setDetailLoading] = useState(false);
    const [detail, setDetail] = useState<QualityCheckBatch | null>(null);

    const loadChecks = useCallback(async () => {
        setLoading(true);
        try {
            const res = await queryQualityChecks({
                page,
                pageSize,
                triggerType: triggerType || undefined,
                status: status || undefined,
            });
            setItems(res.data.records);
            setTotal(res.data.total);
        } finally {
            setLoading(false);
        }
    }, [page, pageSize, triggerType, status]);

    useEffect(() => {
        loadChecks();
    }, [loadChecks]);

    // URL 状态同步（对齐 data-quality）：进页初始化筛选与分页，深层跳转返回后筛选不丢
    const urlInitRef = useRef(false);
    useEffect(() => {
        if (urlInitRef.current) return;
        urlInitRef.current = true;
        const p = searchParams;
        const tt = p.get('triggerType');
        const st = p.get('status');
        setTriggerType(TRIGGER_OPTIONS.some(o => o.value === tt) ? (tt as QualityCheckTriggerType) : '');
        setStatus(STATUS_OPTIONS.some(o => o.value === st) ? (st as QualityCheckStatus) : '');
        setPage(Number(p.get('page')) || 1);
        const ps = Number(p.get('pageSize')) || 10;
        if (ps !== 10) setPageSize(ps);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    useEffect(() => {
        const next = new URLSearchParams();
        if (triggerType) next.set('triggerType', triggerType);
        if (status) next.set('status', status);
        if (page > 1) next.set('page', String(page));
        if (pageSize !== 10) next.set('pageSize', String(pageSize));
        if (next.toString() === searchParams.toString()) return;
        setSearchParams(next, {replace: true});
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [triggerType, status, page, pageSize]);

    const resetFilters = () => {
        setTriggerType('');
        setStatus('');
        setPage(1);
    };

    // ============ 详情 ============
    const openDetail = useCallback(async (item: QualityCheckBatch) => {
        setDetailOpen(true);
        setDetailLoading(true);
        try {
            const res = await getQualityCheckDetail(item.id);
            setDetail(res.data);
        } finally {
            setDetailLoading(false);
        }
    }, []);

    // ============ 列 ============
    const columns = useMemo<ColumnsType<QualityCheckBatch>>(() => [
        {
            title: '任务名称',
            dataIndex: 'jobName',
            width: COL.NAME,
            ellipsis: true,
            render: (v?: string) => (
                <span title={v || '—'} className="text-ds-small text-ds-text-primary font-medium">{v || '—'}</span>
            ),
        },
        {
            title: '触发方式',
            dataIndex: 'triggerType',
            width: COL.TRIGGER_TYPE,
            render: (v?: QualityCheckTriggerType) => (
                <span className="text-ds-small text-ds-text-secondary whitespace-nowrap">
                    {v ? (QUALITY_CHECK_TRIGGER_LABEL[v] || v) : '—'}
                </span>
            ),
        },
        {
            title: '批次状态',
            dataIndex: 'status',
            width: 110,
            render: (v?: QualityCheckStatus) => (
                v ? <DsStatusBadge label={QUALITY_CHECK_STATUS_LABEL[v] || v} variant={STATUS_VARIANT[v] || 'pending'}/> :
                    <span className="text-ds-small text-ds-text-muted">—</span>
            ),
        },
        {
            title: '规则数',
            dataIndex: 'ruleCount',
            width: COL.COUNT,
            render: (v?: number) => (
                <span className="text-ds-small text-ds-text-secondary">{v ?? 0}</span>
            ),
        },
        {
            title: '成功/失败',
            key: 'counts',
            width: 110,
            render: (_, item) => (
                <span className="text-ds-small text-ds-text-secondary whitespace-nowrap">
                    {item.successCount ?? 0}
                    <span className="text-ds-success"> 成功</span>
                    <span className="text-ds-text-muted"> / </span>
                    {item.failedCount ?? 0}
                    <span className="text-ds-danger"> 失败</span>
                </span>
            ),
        },
        {
            title: '开始时间',
            dataIndex: 'startedAt',
            width: COL.DATETIME_COMPACT,
            render: (v?: string) => (
                <span className="text-ds-small text-ds-text-secondary whitespace-nowrap">{formatDateTime(v)}</span>
            ),
        },
        {
            title: '结束时间',
            dataIndex: 'endedAt',
            width: COL.DATETIME_COMPACT,
            render: (v?: string) => (
                <span className="text-ds-small text-ds-text-secondary whitespace-nowrap">{formatDateTime(v)}</span>
            ),
        },
        {
            title: '耗时',
            dataIndex: 'durationMs',
            width: 90,
            ellipsis: true,
            render: (v?: number) => {
                const text = formatDuration(v);
                return <span title={text} className="text-ds-small text-ds-text-secondary font-mono tabular-nums">{text}</span>;
            },
        },
        {
            title: '错误信息',
            dataIndex: 'errorMessage',
            width: COL.ERROR_MESSAGE_NORMAL,
            ellipsis: true,
            render: (v?: string) => (
                v ? <span title={v} className="text-ds-small text-ds-danger">{v}</span> :
                    <span className="text-ds-small text-ds-text-muted">—</span>
            ),
        },
        {
            title: '操作',
            align: 'center',
            fixed: 'right' as const,
            width: COL.OPERATION_2,
            render: (_, item) => (
                <div className="flex items-center justify-center gap-1 whitespace-nowrap">
                    <Tooltip title="查看明细">
                        <DsIconButton tone="accent" onClick={() => openDetail(item)} aria-label="查看明细">
                            <HiOutlineEye size={14}/>
                        </DsIconButton>
                    </Tooltip>
                </div>
            ),
        },
    ], [openDetail]);

    return (
        <div className="flex flex-col">
            <div className="flex items-center justify-between mb-ds-5 flex-shrink-0">
                <div>
                    <h1 className="text-ds-display text-ds-text-primary">质量检查历史</h1>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1">查看质量检查批次与规则执行明细</p>
                </div>
            </div>

            <div className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden flex flex-col">
                <div className="p-ds-3 border-b border-ds-border-subtle flex-shrink-0">
                    <DsToolbar
                        extra={(
                            <>
                                <DsButton onClick={() => { setPage(1); loadChecks(); }} disabled={loading}>
                                    {loading ? '查询中...' : '查询'}
                                </DsButton>
                                <DsButton variant="secondary" onClick={resetFilters}>重置</DsButton>
                            </>
                        )}
                    >
                        <DsFilterSelect
                            value={triggerType}
                            onChange={(v) => setTriggerType(v as QualityCheckTriggerType | '')}
                            aria-label="按触发方式筛选"
                            options={TRIGGER_OPTIONS}
                        />
                        <DsFilterSelect
                            value={status}
                            onChange={(v) => setStatus(v as QualityCheckStatus | '')}
                            aria-label="按状态筛选"
                            options={STATUS_OPTIONS}
                        />
                    </DsToolbar>
                </div>

                <div className="overflow-x-auto">
                    <Table<QualityCheckBatch>
                        dataSource={items}
                        rowKey="id"
                        loading={loading}
                        pagination={false}
                        scroll={{x: 1200}}
                        columns={columns}
                        className="prototype-table prototype-table-flush"
                        locale={{
                            emptyText: (
                                <DsTableEmpty description="暂无质量检查记录，去执行质量任务或规则后查看。"/>
                            ),
                        }}
                    />
                </div>

                <Pagination
                    page={page}
                    pageSize={pageSize}
                    total={total}
                    onChange={(p, s) => {
                        setPage(p);
                        setPageSize(s);
                    }}
                />
            </div>

            <Drawer
                open={detailOpen}
                title={detail?.jobName || '批次明细'}
                width="max-w-[680px]"
                onClose={() => {
                    setDetailOpen(false);
                    setDetail(null);
                }}
                footer={<DsButton variant="secondary" onClick={() => { setDetailOpen(false); setDetail(null); }}>关闭</DsButton>}
            >
                <QualityCheckDetailView loading={detailLoading} detail={detail}/>
            </Drawer>
        </div>
    );
}

/** 批次概览 + 规则明细 */
function QualityCheckDetailView({loading, detail}: { loading: boolean; detail: QualityCheckBatch | null }) {
    if (loading) {
        return <p className="text-ds-caption text-ds-text-muted text-center py-ds-8">加载中...</p>;
    }
    if (!detail) {
        return <p className="text-ds-caption text-ds-text-muted text-center py-ds-8">无数据</p>;
    }

    const overview = [
        {label: '任务名称', value: detail.jobName || '单规则执行'},
        {label: '触发方式', value: detail.triggerType ? (QUALITY_CHECK_TRIGGER_LABEL[detail.triggerType] || detail.triggerType) : '—'},
        {label: '批次状态', value: detail.status ? (QUALITY_CHECK_STATUS_LABEL[detail.status] || detail.status) : '—'},
        {label: '规则总数', value: detail.ruleCount != null ? String(detail.ruleCount) : '—'},
        {label: '成功', value: detail.successCount != null ? String(detail.successCount) : '—'},
        {label: '失败', value: detail.failedCount != null ? String(detail.failedCount) : '—'},
        {label: '开始时间', value: formatDateTime(detail.startedAt)},
        {label: '结束时间', value: formatDateTime(detail.endedAt)},
        {label: '耗时', value: formatDuration(detail.durationMs)},
    ];

    const details = detail.details || [];

    return (
        <div className="flex flex-col gap-ds-5">
            <section>
                <h3 className="text-ds-small font-semibold text-ds-text-primary mb-ds-2">批次概览</h3>
                <div className="grid grid-cols-3 gap-x-ds-4 gap-y-ds-3 bg-ds-bg-hover rounded-ds-sm p-ds-4">
                    {overview.map((o) => (
                        <div key={o.label}>
                            <p className="text-ds-caption text-ds-text-muted">{o.label}</p>
                            <p className="text-ds-small text-ds-text-primary mt-ds-1 break-all">{o.value}</p>
                        </div>
                    ))}
                </div>
            </section>

            {detail.errorMessage && (
                <section>
                    <h3 className="text-ds-small font-semibold text-ds-danger mb-ds-2">批次错误</h3>
                    <pre className="p-ds-3 bg-ds-danger-light border border-ds-border-subtle rounded-ds-sm text-ds-small text-ds-danger font-mono whitespace-pre-wrap break-all">
                        {detail.errorMessage}
                    </pre>
                </section>
            )}

            <section>
                <h3 className="text-ds-small font-semibold text-ds-text-primary mb-ds-2">
                    规则明细（{details.length}）
                </h3>
                {details.length === 0 ? (
                    <p className="text-ds-caption text-ds-text-muted text-center py-ds-4">无规则明细</p>
                ) : (
                    <div className="flex flex-col gap-ds-3">
                        {details.map((d) => (
                            <DetailCard key={d.id} d={d}/>
                        ))}
                    </div>
                )}
            </section>
        </div>
    );
}

function DetailCard({d}: { d: QualityCheckDetail }) {
    const fields = [
        {label: '规则类型', value: d.ruleType ? (QUALITY_TYPE_LABEL[d.ruleType] || d.ruleType) : '—'},
        {label: '目标表', value: d.tableName || '—'},
        {label: '结果指标', value: d.resultMetric || '—'},
        {label: '结果值', value: d.resultValue != null ? String(d.resultValue) : '—'},
    ];

    return (
        <div className="border border-ds-border-subtle rounded-ds-sm p-ds-4">
            <div className="flex items-center justify-between gap-ds-2 mb-ds-2">
                <span className="text-ds-small text-ds-text-primary font-medium break-all">{d.ruleName || '—'}</span>
                {d.success === 1 ? (
                    <DsStatusBadge label="成功" variant="success"/>
                ) : (
                    <DsStatusBadge label="失败" variant="danger"/>
                )}
            </div>
            <div className="grid grid-cols-4 gap-x-ds-4 gap-y-ds-2 mb-ds-3">
                {fields.map((f) => (
                    <div key={f.label}>
                        <p className="text-ds-caption text-ds-text-muted">{f.label}</p>
                        <p className="text-ds-small text-ds-text-primary mt-ds-0.5 font-mono break-all">{f.value}</p>
                    </div>
                ))}
            </div>
            {d.errorMessage && (
                <pre className="p-ds-2 bg-ds-danger-light border border-ds-border-subtle rounded-ds-sm text-ds-caption text-ds-danger font-mono whitespace-pre-wrap break-all mb-ds-2">
                    {d.errorMessage}
                </pre>
            )}
            {d.executedSql && (
                <details className="group">
                    <summary className="text-ds-caption text-ds-accent cursor-pointer select-none">执行 SQL</summary>
                    <pre className="mt-ds-2 p-ds-3 bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm text-ds-small text-ds-text-primary font-mono whitespace-pre-wrap break-all">
                        {d.executedSql}
                    </pre>
                </details>
            )}
        </div>
    );
}
