// Sprint 8 F2：CDC 管道列表页（DI-04 管理 + 监控）。
// MySQL Binlog / PostgreSQL WAL → Flink CDC → Iceberg 湖仓（MinIO）→ Doris 外部表。
// 读四角色可见；写操作（新建/编辑/启停/刷 catalog/删除）仅超管+数据工程师。
// 有 RUNNING 管道时列表与统计卡每 5s 轮询（延迟/累计变更由后端监控回写）。
import {useCallback, useEffect, useMemo, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {Table, Tooltip} from 'antd';
import {
    HiOutlineArrowPath,
    HiOutlineBolt,
    HiOutlineDocumentText,
    HiOutlinePause,
    HiOutlinePencilSquare,
    HiOutlinePlay,
    HiOutlinePlus,
    HiOutlineTableCells,
    HiOutlineTrash,
} from 'react-icons/hi2';
import {
    deleteCdcPipeline,
    getCdcPipelineStats,
    pageCdcPipelines,
    refreshCdcCatalog,
    startCdcPipeline,
    stopCdcPipeline,
} from '../../../api/cdc';
import ConfirmDialog from '../../../components/ConfirmDialog';
import DsButton from '../../../components/DsButton';
import DsIconButton from '../../../components/DsIconButton';
import DsStatusBadge from '../../../components/DsStatusBadge';
import DsTableEmpty from '../../../components/DsTableEmpty';
import DsToolbar from '../../../components/DsToolbar';
import Pagination from '../../../components/Pagination';
import SearchInput from '../../../components/SearchInput';
import {ENGINEERING_WRITE_ROLES} from '../../../constants/roles';
import {COL} from '../../../constants/table';
import usePagedList from '../../../hooks/usePagedList';
import {useHasRole} from '../../../hooks/useHasRole';
import {usePollingWhile} from '../../../hooks/usePollingWhile';
import {formatDateTime} from '../../../utils/format';
import {notify} from '../../../utils/notify';
import type {CdcPipeline, CdcPipelineQuery, CdcPipelineStats, CdcPipelineStatus} from '../../../types/cdc';
import CdcLogDrawer from './CdcLogDrawer';

const INITIAL_QUERY: CdcPipelineQuery = {status: ''};

const STATUS_TABS: { value: CdcPipelineStatus | ''; label: string }[] = [
    {value: '', label: '全部'},
    {value: 'RUNNING', label: '运行中'},
    {value: 'STOPPED', label: '已停止'},
    {value: 'ERROR', label: '异常'},
];

function statusBadge(status: CdcPipelineStatus) {
    if (status === 'RUNNING') return <DsStatusBadge variant="running" label="运行中"/>;
    if (status === 'ERROR') return <DsStatusBadge variant="danger" label="异常"/>;
    return <DsStatusBadge variant="pending" label="已停止"/>;
}

/** 延迟格式化：≤30s 正常色，>30s 标红（PRD §6.6.2，考虑 Iceberg commit + Doris 刷新延迟放宽） */
function LagValue({seconds}: { seconds?: number }) {
    if (seconds == null || seconds < 0) return <span className="text-ds-small text-ds-text-muted">—</span>;
    const text = seconds < 60
        ? `${seconds} 秒`
        : seconds < 3600
            ? `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒`
            : `${Math.floor(seconds / 3600)} 小时 ${Math.floor((seconds % 3600) / 60)} 分`;
    return (
        <span className={`text-ds-small ${seconds > 30 ? 'text-ds-danger font-semibold' : 'text-ds-success'}`}>
            {text}
        </span>
    );
}

/** 顶部统计卡（对齐原型 cdc-mini-strip） */
function StatCard({icon, iconClass, label, value}: {
    icon: React.ReactNode;
    iconClass: string;
    label: string;
    value: string;
}) {
    return (
        <div className="bg-ds-bg-surface border border-ds-border-subtle rounded-ds-md p-ds-4 flex items-center gap-ds-3">
            <div className={`w-10 h-10 rounded-ds-md flex items-center justify-center flex-shrink-0 ${iconClass}`}>
                {icon}
            </div>
            <div className="min-w-0">
                <div className="text-ds-heading font-bold text-ds-text-primary leading-tight">{value}</div>
                <div className="text-ds-tiny text-ds-text-muted mt-ds-1">{label}</div>
            </div>
        </div>
    );
}

export default function CdcPipelinesPage() {
    const navigate = useNavigate();
    const canWrite = useHasRole(...ENGINEERING_WRITE_ROLES);

    const [keywordInput, setKeywordInput] = useState('');
    const [status, setStatus] = useState<CdcPipelineStatus | ''>('');

    const {list, total, page, pageSize, loading, setPage, setPageSize, applyQuery, reload} =
        usePagedList<CdcPipelineQuery, CdcPipeline>({
            fetcher: (q) => pageCdcPipelines(q).then(r => ({list: r?.records ?? [], total: Number(r?.total ?? 0)})),
            initialQuery: INITIAL_QUERY,
        });

    // ============ 顶部统计卡 ============
    const [stats, setStats] = useState<CdcPipelineStats | null>(null);
    const loadStats = useCallback(() => {
        getCdcPipelineStats().then(s => setStats(s ?? null)).catch(() => {
            // 拦截器已提示，保持旧数据
        });
    }, []);
    useEffect(() => {
        loadStats();
    }, [loadStats]);

    // 有运行中管道时轮询列表 + 统计（延迟/累计变更由后端监控轮询回写）；
    // 条件用全量统计的 running 计数而非当前页 list，RUNNING 管道在其它页时统计卡也能刷新
    const hasRunning = Number(stats?.running ?? 0) > 0;
    const pollTick = useCallback(() => {
        reload();
        loadStats();
    }, [reload, loadStats]);
    usePollingWhile(hasRunning, pollTick, {interval: 5000, timeout: 1800000});

    // ============ 操作 ============
    const [logTarget, setLogTarget] = useState<CdcPipeline | null>(null);
    const [stopTarget, setStopTarget] = useState<CdcPipeline | null>(null);
    const [stopLoading, setStopLoading] = useState(false);
    const [deleteTarget, setDeleteTarget] = useState<CdcPipeline | null>(null);
    const [deleteLoading, setDeleteLoading] = useState(false);
    const [actingId, setActingId] = useState<string | null>(null);

    const afterMutation = () => {
        reload();
        loadStats();
    };

    const handleStart = async (p: CdcPipeline) => {
        setActingId(p.id);
        try {
            await startCdcPipeline(p.id);
            notify.success(`管道「${p.name}」已启动`);
            afterMutation();
        } catch {
            // 8004/8005/8007/8008 由拦截器统一提示
        } finally {
            setActingId(null);
        }
    };

    const handleStop = async () => {
        if (!stopTarget) return;
        setStopLoading(true);
        try {
            await stopCdcPipeline(stopTarget.id);
            notify.success(`管道「${stopTarget.name}」已停止（savepoint 已保存）`);
            afterMutation();
        } catch {
            // 拦截器已提示
        } finally {
            setStopLoading(false);
            setStopTarget(null);
        }
    };

    const handleDelete = async () => {
        if (!deleteTarget) return;
        setDeleteLoading(true);
        try {
            await deleteCdcPipeline(deleteTarget.id);
            notify.success(`已删除管道「${deleteTarget.name}」`);
            afterMutation();
        } catch {
            // 拦截器已提示
        } finally {
            setDeleteLoading(false);
            setDeleteTarget(null);
        }
    };

    const handleRefreshCatalog = async (p: CdcPipeline) => {
        setActingId(p.id);
        try {
            await refreshCdcCatalog(p.id);
            notify.success('已触发 Doris Catalog 刷新');
        } catch {
            // 拦截器已提示
        } finally {
            setActingId(null);
        }
    };

    const handleSearch = () => {
        applyQuery({status, keyword: keywordInput.trim() || undefined});
    };

    const handleReset = () => {
        setKeywordInput('');
        setStatus('');
        applyQuery(INITIAL_QUERY);
    };

    /** 状态分段切换即时生效 */
    const handleStatusChange = (s: CdcPipelineStatus | '') => {
        setStatus(s);
        applyQuery({status: s, keyword: keywordInput.trim() || undefined});
    };

    // ============ 列 ============
    const columns = useMemo(() => {
        const base = [
            {
                title: '名称',
                dataIndex: 'name',
                width: COL.NAME_COMPACT,
                ellipsis: true,
                render: (v?: string, r?: CdcPipeline) => (
                    <span title={r?.description || v} className="text-ds-small text-ds-text-primary font-medium">
                        {v || '—'}
                    </span>
                ),
            },
            {
                title: '源',
                key: 'source',
                width: 240,
                ellipsis: true,
                render: (_: unknown, r: CdcPipeline) => {
                    const tables = r.tables ?? [];
                    const tableSummary = tables.length === 0
                        ? ''
                        : tables.length === 1
                            ? tables[0].sourceTable
                            : `${tables[0].sourceTable} 等 ${tables.length} 表`;
                    return (
                        <span className="text-ds-small text-ds-text-secondary"
                              title={tables.map(t => t.sourceTable).join('、')}>
                            {r.sourceDatasourceName ? `${r.sourceDatasourceName} · ` : ''}
                            <span className="font-mono">{r.sourceDatabase}{tableSummary ? ` · ${tableSummary}` : ''}</span>
                        </span>
                    );
                },
            },
            {
                title: '目标库',
                dataIndex: 'targetDatabase',
                width: 110,
                ellipsis: true,
                render: (v?: string) => <span className="font-mono text-ds-small text-ds-text-secondary">{v || '—'}</span>,
            },
            {
                title: '状态',
                dataIndex: 'status',
                width: COL.STATUS,
                render: (v: CdcPipelineStatus, r: CdcPipeline) => (
                    <Tooltip title={v === 'ERROR' ? r.lastError : undefined}>
                        <span>{statusBadge(v)}</span>
                    </Tooltip>
                ),
            },
            {
                title: '当前延迟',
                dataIndex: 'currentLagSeconds',
                width: 110,
                render: (v?: number) => <LagValue seconds={v}/>,
            },
            {
                title: '累计变更',
                dataIndex: 'totalChanges',
                width: COL.COUNT_NORMAL,
                render: (v?: string) => (
                    <span className="text-ds-small text-ds-text-secondary font-mono">
                        {v == null ? '—' : Number(v).toLocaleString()}
                    </span>
                ),
            },
            {
                title: '更新时间',
                dataIndex: 'updatedAt',
                width: COL.DATETIME_COMPACT,
                render: (v?: string, r?: CdcPipeline) => (
                    <span className="text-ds-small text-ds-text-secondary whitespace-nowrap">
                        {formatDateTime(v || r?.createdAt)}
                    </span>
                ),
            },
        ];
        const action = {
            title: '操作',
            key: 'action',
            width: COL.OPERATION_5,
            fixed: 'right' as const,
            render: (_: unknown, r: CdcPipeline) => (
                <div className="flex items-center gap-ds-1">
                    {canWrite && r.status === 'RUNNING' && (
                        <Tooltip title="停止（保存 savepoint）">
                            <DsIconButton tone="default" aria-label={`停止 ${r.name}`}
                                          disabled={actingId === r.id}
                                          onClick={() => setStopTarget(r)}>
                                <HiOutlinePause size={14}/>
                            </DsIconButton>
                        </Tooltip>
                    )}
                    {canWrite && r.status !== 'RUNNING' && (
                        <Tooltip title={r.savepointPath ? '启动（从 savepoint 恢复）' : '启动'}>
                            <DsIconButton tone="accent" aria-label={`启动 ${r.name}`}
                                          disabled={actingId === r.id}
                                          onClick={() => handleStart(r)}>
                                <HiOutlinePlay size={14}/>
                            </DsIconButton>
                        </Tooltip>
                    )}
                    {canWrite && (
                        <Tooltip title={r.status === 'STOPPED' ? '编辑' : '仅停止状态可编辑'}>
                            <span>
                                <DsIconButton tone="accent" aria-label={`编辑 ${r.name}`}
                                              disabled={r.status !== 'STOPPED'}
                                              onClick={() => navigate(`/engineering/cdc-pipelines/${r.id}/edit`)}>
                                    <HiOutlinePencilSquare size={14}/>
                                </DsIconButton>
                            </span>
                        </Tooltip>
                    )}
                    <Tooltip title="运行日志">
                        <DsIconButton tone="accent" aria-label={`日志 ${r.name}`}
                                      onClick={() => setLogTarget(r)}>
                            <HiOutlineDocumentText size={14}/>
                        </DsIconButton>
                    </Tooltip>
                    {canWrite && (
                        <Tooltip title="刷新 Doris Catalog（湖仓新表/新数据可见）">
                            <DsIconButton tone="accent" aria-label={`刷新 Catalog ${r.name}`}
                                          disabled={actingId === r.id}
                                          onClick={() => handleRefreshCatalog(r)}>
                                <HiOutlineArrowPath size={14}/>
                            </DsIconButton>
                        </Tooltip>
                    )}
                    {canWrite && (
                        <Tooltip title={r.status === 'RUNNING' ? '运行中请先停止' : '删除'}>
                            <span>
                                <DsIconButton tone="danger" aria-label={`删除 ${r.name}`}
                                              disabled={r.status === 'RUNNING'}
                                              onClick={() => setDeleteTarget(r)}>
                                    <HiOutlineTrash size={14}/>
                                </DsIconButton>
                            </span>
                        </Tooltip>
                    )}
                </div>
            ),
        };
        return [...base, action];
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [canWrite, actingId, navigate]);

    return (
        <div className="flex flex-col">
            <div className="flex items-center justify-between mb-ds-5">
                <div>
                    <h1 className="text-ds-display text-ds-text-primary flex items-center gap-ds-2">
                        <HiOutlineBolt className="text-ds-accent"/>
                        CDC 管道
                    </h1>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1">
                        MySQL Binlog / PostgreSQL WAL → Flink CDC → Iceberg 湖仓（MinIO）→ Doris 外部表，秒级捕获业务库变更。
                    </p>
                </div>
                {canWrite && (
                    <DsButton onClick={() => navigate('/engineering/cdc-pipelines/new')}>
                        <HiOutlinePlus size={14}/>
                        新建管道
                    </DsButton>
                )}
            </div>

            {/* 统计卡 */}
            <div className="grid grid-cols-4 gap-ds-4 mb-ds-4">
                <StatCard icon={<HiOutlineBolt size={20}/>} iconClass="bg-ds-accent-light text-ds-accent"
                          label="运行中" value={stats?.running ?? '—'}/>
                <StatCard icon={<HiOutlinePause size={20}/>} iconClass="bg-ds-bg-hover text-ds-text-muted"
                          label="已停止" value={stats?.stopped ?? '—'}/>
                <StatCard icon={<HiOutlineTrash size={20}/>} iconClass="bg-ds-danger-light text-ds-danger"
                          label="异常" value={stats?.error ?? '—'}/>
                <StatCard icon={<HiOutlineTableCells size={20}/>} iconClass="bg-ds-success-light text-ds-success"
                          label="已同步表" value={stats?.syncedTables ?? '—'}/>
            </div>

            <div
                className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden">
                <div className="p-ds-3 border-b border-ds-border-subtle">
                    <DsToolbar
                        extra={(
                            <>
                                <DsButton onClick={handleSearch} disabled={loading}>
                                    {loading ? '查询中...' : '查询'}
                                </DsButton>
                                <DsButton variant="secondary" onClick={handleReset}>重置</DsButton>
                            </>
                        )}
                    >
                        {/* 状态分段（切换即时生效） */}
                        <div className="flex items-center bg-ds-bg-root rounded-ds-sm p-0.5">
                            {STATUS_TABS.map(t => (
                                <button
                                    key={t.value}
                                    type="button"
                                    onClick={() => handleStatusChange(t.value)}
                                    className={`px-ds-3 py-ds-1 text-ds-small rounded-ds-sm transition-colors ${
                                        status === t.value
                                            ? 'bg-ds-bg-surface text-ds-accent font-semibold shadow-ds-xs'
                                            : 'text-ds-text-muted hover:text-ds-text-secondary'
                                    }`}
                                >
                                    {t.label}
                                </button>
                            ))}
                        </div>
                        <SearchInput
                            value={keywordInput}
                            onChange={(e) => setKeywordInput(e.target.value)}
                            onEnter={handleSearch}
                            placeholder="搜索管道名称…"
                            aria-label="搜索 CDC 管道"
                        />
                    </DsToolbar>
                </div>

                <Table
                    rowKey={(r) => r.id}
                    columns={columns}
                    dataSource={list}
                    loading={loading}
                    pagination={false}
                    scroll={{x: 1280}}
                    className="prototype-table prototype-table-flush"
                    locale={{
                        emptyText: (
                            <DsTableEmpty
                                description={status || keywordInput
                                    ? '没有符合条件的管道'
                                    : canWrite ? '暂无 CDC 管道，点击右上「新建管道」开始' : '暂无 CDC 管道'}
                            />
                        ),
                    }}
                />

                {total > 0 && (
                    <Pagination
                        page={page}
                        pageSize={pageSize}
                        total={total}
                        onChange={(p, s) => {
                            setPage(p);
                            if (s !== pageSize) setPageSize(s);
                        }}
                    />
                )}
            </div>

            {/* 运行日志抽屉（pipeline 优先取列表轮询后的最新行，状态徽章/自动刷新条件随之更新） */}
            <CdcLogDrawer pipeline={list.find(p => p.id === logTarget?.id) ?? logTarget}
                          onClose={() => setLogTarget(null)}/>

            {/* 停止确认 */}
            <ConfirmDialog
                open={!!stopTarget}
                title="停止管道"
                message={`确认停止管道「${stopTarget?.name}」？将保存 savepoint 后停止 Flink 作业，下次启动从 savepoint 恢复（不丢不重）。`}
                confirmLabel="停止"
                danger
                loading={stopLoading}
                onConfirm={handleStop}
                onCancel={() => setStopTarget(null)}
            />

            {/* 删除确认 */}
            <ConfirmDialog
                open={!!deleteTarget}
                title="删除管道"
                message={`确认删除管道「${deleteTarget?.name}」？将级联删除表映射与运行日志；湖仓已同步的数据保留不删。`}
                confirmLabel="删除"
                danger
                loading={deleteLoading}
                onConfirm={handleDelete}
                onCancel={() => setDeleteTarget(null)}
            />
        </div>
    );
}
