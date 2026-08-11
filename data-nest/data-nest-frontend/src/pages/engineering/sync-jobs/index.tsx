import type {HTMLAttributes} from 'react';
import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {useNavigate, useSearchParams} from 'react-router-dom';
import {Table, Tooltip} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import {getDataSources} from '@/api/datasource';
import {
    createSyncJob,
    deleteSyncJob,
    executeSyncJob,
    querySyncJobs,
    startSyncJobSchedule,
    stopSyncJobSchedule,
    updateSyncJob,
} from '@/api/sync';
import type {DataSource} from '@/types/datasource';
import type {
    SyncExecutionStatus,
    SyncJob,
    SyncJobCreateRequest,
    SyncJobQueryParams,
    SyncTriggerType,
} from '@/types/sync';
import {formatDateTime, formatRelativeTime} from '@/utils/format';
import {executionStatusVariant} from '@/utils/status';
import {notify} from '@/utils/notify';
import {useHasRole} from '@/hooks/useHasRole';
import {ENGINEERING_WRITE_ROLES} from '@/constants/roles';
import {COL} from '@/constants/table';
import {NODE_STATUS_COLOR} from '@/constants/statusColors';
import usePagedList from '@/hooks/usePagedList';
import Pagination from '@/components/Pagination';
import ConfirmDialog from '@/components/ConfirmDialog';
import ReferenceListModal from '@/components/ReferenceListModal';
import type {ApiError} from '@/utils/error';
import DsButton from '@/components/DsButton';
import DsIconButton from '@/components/DsIconButton';
import DsStatusBadge from '@/components/DsStatusBadge';
import StatusSpine from '@/components/StatusSpine';
import SearchInput from '@/components/SearchInput';
import DsFilterSelect from '@/components/DsFilterSelect';
import DsToolbar from '@/components/DsToolbar';
import DsTableEmpty from '@/components/DsTableEmpty';
import SyncJobDrawer from './SyncJobDrawer';
import AlertRuleModal from '@/components/AlertRuleModal';
import {triggerBadge} from './history-common-utils';
import {
    HiOutlineBell,
    HiOutlineCalendar,
    HiOutlineClock,
    HiOutlineEye,
    HiOutlinePencilSquare,
    HiOutlinePlay,
    HiOutlinePlus,
    HiOutlineTrash,
} from 'react-icons/hi2';

const STATUS_OPTIONS: { value: SyncExecutionStatus | ''; label: string }[] = [
    {value: '', label: '全部状态'},
    {value: 'PENDING', label: '未执行'},
    {value: 'RUNNING', label: '运行中'},
    {value: 'SUCCESS', label: '成功'},
    {value: 'FAILED', label: '失败'},
    {value: 'TERMINATED', label: '已终止'},
];

const TRIGGER_OPTIONS: { value: SyncTriggerType | ''; label: string }[] = [
    {value: '', label: '全部触发方式'},
    {value: 'MANUAL', label: '手动'},
    {value: 'CRON', label: 'Cron 定时'},
];

function scheduleStatusBadge(item: SyncJob) {
    if (item.triggerType === 'MANUAL') {
        return <span className="text-ds-small text-ds-text-muted">—</span>;
    }
    if (item.scheduleEnabled) {
        return <DsStatusBadge variant="success" label="已启用"/>;
    }
    return <DsStatusBadge variant="disabled" label="已停用"/>;
}

const EXECUTION_STATUS_LABELS: Record<string, string> = {
    PENDING: '未执行',
    RUNNING: '运行中',
    SUCCESS: '成功',
    FAILED: '失败',
    TERMINATED: '已终止',
};

// 「已应用」的查询条件（页面里的草稿条件由下方 draft* state 持有）
interface SyncJobListQuery {
    keyword: string;
    triggerType: SyncTriggerType | '';
    executionStatus: SyncExecutionStatus | '';
}

const INITIAL_QUERY: SyncJobListQuery = {keyword: '', triggerType: '', executionStatus: ''};

function formatNextExecutionTime(item: SyncJob) {
    if (item.triggerType === 'MANUAL') return '—';
    if (!item.scheduleEnabled) return '—';
    if (!item.nextExecutionTime) return '—';
    return formatDateTime(item.nextExecutionTime);
}

export default function SyncJobsPage() {
    const navigate = useNavigate();
    const canWrite = useHasRole(...ENGINEERING_WRITE_ROLES);

    const [draftKeyword, setDraftKeyword] = useState('');

    const {
        list, total, page, pageSize, loading, query,
        setPage, setPageSize, applyQuery, reload,
    } = usePagedList<SyncJobListQuery, SyncJob>({
        fetcher: async (query) => {
            const params: SyncJobQueryParams = {
                page: query.page,
                pageSize: query.pageSize,
                keyword: query.keyword || undefined,
                triggerType: query.triggerType || undefined,
                executionStatus: query.executionStatus || undefined,
            };
            const result = await querySyncJobs(params);
            return {list: result.data.records, total: result.data.total};
        },
        initialQuery: INITIAL_QUERY,
        defaultPageSize: 10,
    });
// L2：进页时从 URL 初始化筛选，进入子页/返回后筛选不丢
    const [searchParams, setSearchParams] = useSearchParams();
    const urlInitRef = useRef(false);
    useEffect(() => {
        if (urlInitRef.current) return;
        urlInitRef.current = true;
        const p = searchParams;
        const keyword = p.get('keyword') || '';
        const triggerType = TRIGGER_OPTIONS.some(o => o.value === p.get('triggerType'))
            ? p.get('triggerType') as SyncTriggerType | ''
            : '';
        const executionStatus = STATUS_OPTIONS.some(o => o.value === p.get('executionStatus'))
            ? p.get('executionStatus') as SyncExecutionStatus | ''
            : '';
        const pageNum = Number(p.get('page')) || 1;
        const pageSizeNum = Number(p.get('pageSize')) || 10;
        setDraftKeyword(keyword);
        if (pageSizeNum !== 10) setPageSize(pageSizeNum);
        applyQuery({keyword, triggerType, executionStatus});
        if (pageNum > 1) setPage(pageNum);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    // L2：筛选/分页变化时同步到 URL
    useEffect(() => {
        const next = new URLSearchParams();
        if (query.keyword) next.set('keyword', query.keyword);
        if (query.triggerType) next.set('triggerType', query.triggerType);
        if (query.executionStatus) next.set('executionStatus', query.executionStatus);
        next.set('page', String(page));
        if (pageSize !== 10) next.set('pageSize', String(pageSize));
        if (next.toString() === searchParams.toString()) return;
        setSearchParams(next, {replace: true});
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [query, page, pageSize]);

    const [drawerOpen, setDrawerOpen] = useState(false);
    const [drawerMode, setDrawerMode] = useState<'create' | 'edit' | 'view'>('create');
    const [editItem, setEditItem] = useState<SyncJob | null>(null);
    const [dataSources, setDataSources] = useState<DataSource[]>([]);
    const [executingId, setExecutingId] = useState<string | null>(null);
    const [schedulingId, setSchedulingId] = useState<string | null>(null);

    const [deleteTarget, setDeleteTarget] = useState<SyncJob | null>(null);
    const [deleteOpen, setDeleteOpen] = useState(false);
    const [deleteLoading, setDeleteLoading] = useState(false);
    const [deleteBlockedOpen, setDeleteBlockedOpen] = useState(false);
    const [deleteReferences, setDeleteReferences] = useState<string[]>([]);

    // Sprint 5：快捷告警配置
    const [alertTarget, setAlertTarget] = useState<SyncJob | null>(null);
    const [alertOpen, setAlertOpen] = useState(false);

    useEffect(() => {
        let mounted = true;
        getDataSources({page: 1, pageSize: 1000}).then((result) => {
            if (!mounted) return;
            setDataSources(result.data.records.filter((ds) => ds.status === 'NORMAL'));
        });
        return () => {
            mounted = false;
        };
    }, []);

    const loadDataSources = useCallback(async () => {
        try {
            const result = await getDataSources({page: 1, pageSize: 1000});
            setDataSources(result.data.records.filter((ds) => ds.status === 'NORMAL'));
        } catch {
            // ignored
        }
    }, []);

    const openCreate = async () => {
        await loadDataSources();
        setEditItem(null);
        setDrawerMode('create');
        setDrawerOpen(true);
    };

    const openEdit = useCallback(async (item: SyncJob) => {
        await loadDataSources();
        setEditItem(item);
        setDrawerMode('edit');
        setDrawerOpen(true);
    }, [loadDataSources]);

    const openView = useCallback(async (item: SyncJob) => {
        await loadDataSources();
        setEditItem(item);
        setDrawerMode('view');
        setDrawerOpen(true);
    }, [loadDataSources]);

    const handleSubmit = async (payload: SyncJobCreateRequest) => {
        const result = editItem
            ? await updateSyncJob(editItem.id, payload)
            : await createSyncJob(payload);
        notify.success(editItem ? '同步任务更新成功' : '同步任务创建成功');
        reload();
        return result;
    };

    const handleExecute = useCallback(async (item: SyncJob) => {
        setExecutingId(item.id);
        try {
            await executeSyncJob(item.id);
            notify.success(`同步任务 "${item.name}" 已触发执行`);
            reload();
        } finally {
            setExecutingId(null);
        }
    }, [reload]);

    const handleToggleSchedule = useCallback(async (item: SyncJob) => {
        setSchedulingId(item.id);
        try {
            if (item.scheduleEnabled) {
                await stopSyncJobSchedule(item.id);
            } else {
                await startSyncJobSchedule(item.id);
            }
            notify.success(`同步任务 "${item.name}" 已${item.scheduleEnabled ? '停用调度' : '启用调度'}`);
            reload();
        } finally {
            setSchedulingId(null);
        }
    }, [reload]);

    const handleDelete = async () => {
        if (!deleteTarget) return;
        setDeleteLoading(true);
        try {
            await deleteSyncJob(deleteTarget.id);
            notify.success('同步任务已删除');
            setDeleteOpen(false);
            setDeleteTarget(null);
            reload();
        } catch (e) {
            const errorData = (e as ApiError)?.response?.data;
            // 被 DAG 引用时（7009），后端 data 返回引用 DAG 名称列表，弹窗展示
            if (errorData?.code === 7009 && Array.isArray(errorData?.data)) {
                setDeleteReferences(errorData.data as string[]);
                setDeleteOpen(false);
                setDeleteBlockedOpen(true);
            }
        } finally {
            setDeleteLoading(false);
        }
    };

    const handleSearch = () => {
        applyQuery({...query, keyword: draftKeyword});
    };

    const handleReset = () => {
        setDraftKeyword('');
        applyQuery(INITIAL_QUERY);
    };

    const handlePageChange = (nextPage: number, nextPageSize: number) => {
        // Pagination 在改每页条数时回调 onChange(1, nextPageSize)，hook 的 setPageSize 自带回第 1 页
        if (nextPageSize !== pageSize) {
            setPageSize(nextPageSize);
        } else {
            setPage(nextPage);
        }
    };

    const columns = useMemo<ColumnsType<SyncJob>>(() => [
        {
            title: '',
            width: 12,
            render: (_, item) => {
                const status = item.executionStatus;
                const color = status === 'SUCCESS'
                    ? NODE_STATUS_COLOR.SUCCESS
                    : status === 'FAILED' || status === 'TERMINATED'
                        ? NODE_STATUS_COLOR.FAILED
                        : status === 'RUNNING'
                            ? NODE_STATUS_COLOR.RUNNING
                            : NODE_STATUS_COLOR.WAITING;
                return <StatusSpine color={color}/>;
            },
        },
        {
            title: '任务名称',
            dataIndex: 'name',
            width: COL.NAME,
            ellipsis: true,
            render: (v: string) => (
                <span className="text-ds-small text-ds-text-primary font-medium" title={v}>{v}</span>
            ),
        },
        {
            title: '触发方式',
            dataIndex: 'triggerType',
            width: COL.TRIGGER_TYPE,
            render: (v: string) => triggerBadge(v),
        },
        {
            title: 'Cron 表达式',
            width: COL.CRON,
            render: (_, item) => (
                <span className="text-ds-small text-ds-text-secondary font-mono tabular-nums whitespace-nowrap">
                    {item.triggerType === 'CRON' && item.cronExpression ? item.cronExpression : '—'}
                </span>
            ),
        },
        {
            title: '调度状态',
            width: COL.STATUS,
            render: (_, item) => scheduleStatusBadge(item),
        },
        {
            title: '下次执行时间',
            width: COL.DATETIME,
            render: (_, item) => (
                <span className="text-ds-small text-ds-text-secondary whitespace-nowrap">
                    {formatNextExecutionTime(item)}
                </span>
            ),
        },
        {
            title: '最近执行状态',
            width: COL.STATUS,
            render: (_, item) => (
                <DsStatusBadge
                    variant={executionStatusVariant(item.executionStatus)}
                    label={EXECUTION_STATUS_LABELS[item.executionStatus] ?? '未知'}
                />
            ),
        },
        {
            title: '最近执行',
            width: COL.DATETIME,
            render: (_, item) => (
                <span className="text-ds-small text-ds-text-secondary whitespace-nowrap"
                      title={item.lastExecuteTime ? formatDateTime(item.lastExecuteTime) : ''}>
                    {formatRelativeTime(item.lastExecuteTime)}
                </span>
            ),
        },
        {
            title: '创建人',
            dataIndex: 'createdByName',
            width: COL.USERNAME,
            ellipsis: true,
            render: (v?: string) => (
                <span className="text-ds-small text-ds-text-secondary">{v || '-'}</span>
            ),
        },
        {
            title: '创建时间',
            width: COL.DATETIME,
            render: (_, item) => (
                <span className="text-ds-small text-ds-text-secondary whitespace-nowrap">
                    {formatDateTime(item.createdAt)}
                </span>
            ),
        },
        {
            title: '修改人',
            dataIndex: 'updatedByName',
            width: COL.USERNAME,
            ellipsis: true,
            render: (v?: string) => (
                <span className="text-ds-small text-ds-text-secondary">{v || '-'}</span>
            ),
        },
        {
            title: '修改时间',
            width: COL.DATETIME,
            render: (_, item) => (
                <span className="text-ds-small text-ds-text-secondary whitespace-nowrap">
                    {formatDateTime(item.updatedAt)}
                </span>
            ),
        },
        {
            title: '操作',
            align: 'center',
            fixed: 'right' as const,
            width: 280,
            render: (_, item) => (
                <div className="flex items-center justify-center gap-1 whitespace-nowrap">
                    <Tooltip title="告警配置">
                        <DsIconButton
                            tone="accent"
                            data-testid={`sync-job-alert-${item.name}`}
                            onClick={() => {
                                setAlertTarget(item);
                                setAlertOpen(true);
                            }}
                            aria-label="告警配置"
                        >
                            <HiOutlineBell size={14}/>
                        </DsIconButton>
                    </Tooltip>
                    <Tooltip title="详情">
                        <DsIconButton
                            tone="accent"
                            data-testid={`sync-job-view-${item.name}`}
                            onClick={() => openView(item)}
                            aria-label="详情"
                        >
                            <HiOutlineEye size={14}/>
                        </DsIconButton>
                    </Tooltip>
                    <Tooltip title="历史记录">
                        <DsIconButton
                            tone="accent"
                            data-testid={`sync-job-history-${item.name}`}
                            onClick={() => navigate(`/engineering/sync-job-history?syncJobId=${item.id}&jobName=${encodeURIComponent(item.name || '')}`)}
                            aria-label="历史记录"
                        >
                            <HiOutlineClock size={14}/>
                        </DsIconButton>
                    </Tooltip>
                    {canWrite && (
                        <>
                            {item.triggerType === 'CRON' && (
                                <Tooltip title={item.scheduleEnabled ? '停用调度' : '启用调度'}>
                                    <DsIconButton
                                        tone="success"
                                        active={item.scheduleEnabled}
                                        data-testid={`sync-job-schedule-${item.name}`}
                                        onClick={() => handleToggleSchedule(item)}
                                        disabled={schedulingId === item.id}
                                        aria-label={item.scheduleEnabled ? '停用调度' : '启用调度'}
                                    >
                                        <HiOutlineCalendar size={14}/>
                                    </DsIconButton>
                                </Tooltip>
                            )}
                            <Tooltip title="立即执行">
                                <DsIconButton
                                    tone="success"
                                    data-testid={`sync-job-execute-${item.name}`}
                                    onClick={() => handleExecute(item)}
                                    disabled={executingId === item.id}
                                    aria-label="立即执行"
                                >
                                    <HiOutlinePlay size={14}/>
                                </DsIconButton>
                            </Tooltip>
                            <Tooltip title="编辑">
                                <DsIconButton
                                    tone="accent"
                                    data-testid={`sync-job-edit-${item.name}`}
                                    onClick={() => openEdit(item)}
                                    aria-label="编辑"
                                >
                                    <HiOutlinePencilSquare size={14}/>
                                </DsIconButton>
                            </Tooltip>
                            <Tooltip title="删除">
                                <DsIconButton
                                    tone="danger"
                                    data-testid={`sync-job-delete-${item.name}`}
                                    onClick={() => {
                                        setDeleteTarget(item);
                                        setDeleteOpen(true);
                                    }}
                                    aria-label="删除"
                                >
                                    <HiOutlineTrash size={14}/>
                                </DsIconButton>
                            </Tooltip>
                        </>
                    )}
                </div>
            ),
        },
    ], [canWrite, executingId, schedulingId, navigate, handleExecute, handleToggleSchedule, openEdit, openView]);

    return (
        <div className="flex flex-col">
            <div className="flex items-center justify-between mb-ds-5 flex-shrink-0">
                <div>
                    <h1 className="text-ds-display text-ds-text-primary">批量数据同步任务</h1>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1">管理数据源到 Doris 的批量同步任务，支持手动触发与
                        Cron 定时调度</p>
                </div>
                {canWrite && (
                    <DsButton
                        data-testid="sync-job-create"
                        onClick={openCreate}
                    >
                        <HiOutlinePlus size={16}/>
                        创建任务
                    </DsButton>
                )}
            </div>

            <div
                className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle p-ds-3 mb-ds-4 flex-shrink-0">
                <DsToolbar
                    extra={
                        <>
                            <DsButton
                                onClick={handleSearch}
                                disabled={loading}
                                loading={loading}
                            >
                                查询
                            </DsButton>
                            <DsButton
                                variant="secondary"
                                onClick={handleReset}
                                disabled={loading}
                            >
                                重置
                            </DsButton>
                        </>
                    }
                >
                    <SearchInput
                        value={draftKeyword}
                        onChange={(e) => setDraftKeyword(e.target.value)}
                        onEnter={handleSearch}
                        placeholder="搜索任务名称..."
                    />

                    <DsFilterSelect
                        value={query.triggerType}
                        onChange={(v) => applyQuery({...query, triggerType: v as SyncTriggerType | ''})}
                        options={TRIGGER_OPTIONS}
                        aria-label="按触发方式筛选"
                    />

                    <DsFilterSelect
                        value={query.executionStatus}
                        onChange={(v) => applyQuery({...query, executionStatus: v as SyncExecutionStatus | ''})}
                        options={STATUS_OPTIONS}
                        aria-label="按执行状态筛选"
                    />
                </DsToolbar>
            </div>

            <div className="flex flex-col">
                <div
                    className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden flex flex-col">
                    <div className="overflow-x-auto">
                        <Table<SyncJob>
                            dataSource={list}
                            rowKey="id"
                            loading={loading}
                            pagination={false}
                            scroll={{x: 1760}}
                            columns={columns}
                            className="prototype-table prototype-table-flush"
                            onRow={(item) => ({
                                'data-testid': `sync-job-row-${item.name}`,
                                'data-job-id': item.id,
                            } as HTMLAttributes<HTMLElement>)}
                            locale={{
                                emptyText: (
                                    <DsTableEmpty
                                        description={
                                            <>
                                                暂无同步任务
                                                <p className="text-ds-small text-ds-text-muted mb-ds-3">
                                                    还没有批量数据同步任务，创建第一个任务开始同步到 Doris。
                                                </p>
                                            </>
                                        }
                                        action={canWrite && (
                                            <DsButton onClick={openCreate}>
                                                <HiOutlinePlus size={16}/>
                                                创建任务
                                            </DsButton>
                                        )}
                                    />
                                ),
                            }}
                        />
                    </div>

                    <Pagination page={page} pageSize={pageSize} total={total} onChange={handlePageChange}/>
                </div>
            </div>

            <SyncJobDrawer
                open={drawerOpen}
                editItem={editItem}
                mode={drawerMode}
                sourceDataSources={dataSources}
                onClose={() => {
                    setDrawerOpen(false);
                    setEditItem(null);
                    setDrawerMode('create');
                }}
                onSubmit={handleSubmit}
                onExecute={handleExecute}
            />

            {/* Sprint 5：同步任务告警配置快捷入口（同一 alert_rule 数据源） */}
            <AlertRuleModal
                open={alertOpen}
                onClose={() => {
                    setAlertOpen(false);
                    setAlertTarget(null);
                }}
                mode="quick"
                quickObjectType="SYNC_JOB"
                quickObjectId={alertTarget?.id}
                quickObjectName={alertTarget?.name}
                readOnly={!canWrite}
            />

            <ConfirmDialog
                open={deleteOpen}
                title="删除确认"
                message={
                    <div>
                        <p className="text-ds-body text-ds-text-secondary">
                            确定删除任务 <strong>"{deleteTarget?.name}"</strong> 吗？
                        </p>
                        <div className="mt-2 text-ds-small text-ds-text-muted leading-relaxed">
                            <p>删除后：</p>
                            <ul className="mt-1 space-y-0.5 list-disc list-inside">
                                <li>该任务不再执行</li>
                                <li>该任务的所有历史记录和日志将被删除</li>
                                <li>已同步到 Doris 的数据不会被清理</li>
                            </ul>
                        </div>
                    </div>
                }
                confirmLabel="确认删除"
                danger
                loading={deleteLoading}
                onConfirm={handleDelete}
                onCancel={() => {
                    if (deleteLoading) return;
                    setDeleteOpen(false);
                    setDeleteTarget(null);
                }}
            />

            <ReferenceListModal
                open={deleteBlockedOpen}
                title="无法删除同步任务"
                message={`同步任务 "${deleteTarget?.name ?? ''}" 已被以下 DAG 引用，请先删除或修改这些 DAG 后再删除。`}
                references={deleteReferences}
                onClose={() => setDeleteBlockedOpen(false)}
            />
        </div>
    );
}
