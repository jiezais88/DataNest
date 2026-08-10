// Sprint 8 F2：CDC 管道列表页（DI-04 管理 + 监控）。
// MySQL Binlog / PostgreSQL WAL → Flink CDC → Iceberg 湖仓（MinIO）→ Doris 外部表。
// 读四角色可见；写操作（新建/编辑/启停/刷 catalog/删除）仅超管+数据工程师；详情按钮全角色可见。
// 不做定时轮询：统计卡与列表只在操作成功后或手动点「刷新」时重拉（运行时长由 startedAt 静态计算）。
import {useCallback, useEffect, useMemo, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {Table, Tooltip} from 'antd';
import {
    HiOutlineArrowPath,
    HiOutlineBolt,
    HiOutlineDocumentText,
    HiOutlineEye,
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
} from '@/api/cdc';
import ConfirmDialog from '@/components/ConfirmDialog';
import DsButton from '@/components/DsButton';
import DsIconButton from '@/components/DsIconButton';
import DsTableEmpty from '@/components/DsTableEmpty';
import DsToolbar from '@/components/DsToolbar';
import Pagination from '@/components/Pagination';
import SearchInput from '@/components/SearchInput';
import {ENGINEERING_WRITE_ROLES} from '@/constants/roles';
import {COL} from '@/constants/table';
import usePagedList from '@/hooks/usePagedList';
import {useHasRole} from '@/hooks/useHasRole';
import {formatDateTime, formatRunningDuration} from '@/utils/format';
import {notify} from '@/utils/notify';
import type {CdcPipeline, CdcPipelineQuery, CdcPipelineStats, CdcPipelineStatus} from '@/types/cdc';
import CdcLogDrawer from './CdcLogDrawer';
import CdcPipelineDetailDrawer from './CdcPipelineDetailDrawer';
import {CdcStatusBadge, LagValue} from './shared';

const INITIAL_QUERY: CdcPipelineQuery = {status: ''};

const STATUS_TABS: { value: CdcPipelineStatus | ''; label: string }[] = [
    {value: '', label: '全部'},
    {value: 'RUNNING', label: '运行中'},
    {value: 'STOPPED', label: '已停止'},
    {value: 'ERROR', label: '异常'},
];

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

    // ============ 操作 ============
    const [detailId, setDetailId] = useState<string | null>(null);
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
                width: 220,
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
                        <span><CdcStatusBadge status={v}/></span>
                    </Tooltip>
                ),
            },
            {
                title: '当前延迟',
                dataIndex: 'currentLagSeconds',
                width: COL.COUNT_NORMAL,
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
                // 运行时长：仅 RUNNING 有值，由 startedAt 到当前时间静态计算（无轮询，不跳动）
                title: '运行时长',
                dataIndex: 'startedAt',
                width: 110,
                render: (v: string | undefined, r: CdcPipeline) => (
                    r.status === 'RUNNING' && v ? (
                        <span className="text-ds-small text-ds-text-secondary whitespace-nowrap"
                              title={`启动时间：${formatDateTime(v)}`}>
                            {formatRunningDuration(v)}
                        </span>
                    ) : (
                        <span className="text-ds-small text-ds-text-muted">—</span>
                    )
                ),
            },
            {
                title: '创建人',
                dataIndex: 'createdByName',
                width: COL.USERNAME,
                ellipsis: true,
                render: (v?: string) => (
                    <span className="text-ds-small text-ds-text-secondary">{v || '—'}</span>
                ),
            },
            {
                title: '创建时间',
                dataIndex: 'createdAt',
                width: COL.DATETIME_COMPACT,
                render: (v?: string) => (
                    <span className="text-ds-small text-ds-text-secondary whitespace-nowrap">
                        {formatDateTime(v)}
                    </span>
                ),
            },
            {
                title: '修改人',
                dataIndex: 'updatedByName',
                width: COL.USERNAME,
                ellipsis: true,
                render: (v?: string) => (
                    <span className="text-ds-small text-ds-text-secondary">{v || '—'}</span>
                ),
            },
            {
                title: '修改时间',
                dataIndex: 'updatedAt',
                width: COL.DATETIME_COMPACT,
                render: (v?: string) => (
                    v ? (
                        <span className="text-ds-small text-ds-text-secondary whitespace-nowrap">
                            {formatDateTime(v)}
                        </span>
                    ) : (
                        <span className="text-ds-small text-ds-text-muted">—</span>
                    )
                ),
            },
        ];
        const action = {
            title: '操作',
            key: 'action',
            width: 240,
            fixed: 'right' as const,
            render: (_: unknown, r: CdcPipeline) => (
                <div className="flex items-center gap-ds-1">
                    <Tooltip title="详情">
                        <DsIconButton tone="accent" aria-label={`详情 ${r.name}`}
                                      onClick={() => setDetailId(r.id)}>
                            <HiOutlineEye size={14}/>
                        </DsIconButton>
                    </Tooltip>
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
                                <Tooltip title="刷新列表与统计">
                                    <span>
                                        <DsButton variant="secondary" onClick={afterMutation} disabled={loading}>
                                            <HiOutlineArrowPath size={14}/>
                                            刷新
                                        </DsButton>
                                    </span>
                                </Tooltip>
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
                    scroll={{x: 1720}}
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

            {/* 详情抽屉（打开时按 id 拉最新详情） */}
            <CdcPipelineDetailDrawer pipelineId={detailId} onClose={() => setDetailId(null)}/>

            {/* 运行日志抽屉（pipeline 优先取列表刷新后的最新行，状态徽章随之更新） */}
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
