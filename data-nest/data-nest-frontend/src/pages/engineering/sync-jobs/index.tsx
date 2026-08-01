import type {HTMLAttributes} from 'react';
import {useCallback, useEffect, useMemo, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {Table, Tooltip} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import {getDataSources} from '../../../api/datasource';
import {
    createSyncJob,
    deleteSyncJob,
    executeSyncJob,
    querySyncJobs,
    startSyncJobSchedule,
    stopSyncJobSchedule,
    updateSyncJob,
} from '../../../api/sync';
import type {DataSource} from '../../../types/datasource';
import type {
    SyncExecutionStatus,
    SyncJob,
    SyncJobCreateRequest,
    SyncJobQueryParams,
    SyncTriggerType,
} from '../../../types/sync';
import {formatDateTime, formatRelativeTime} from '../../../utils/format';
import {executionStatusVariant} from '../../../utils/status';
import {notify} from '../../../utils/notify';
import {usePollingWhile} from '../../../hooks/usePollingWhile';
import {useHasRole} from '../../../hooks/useHasRole';
import {ENGINEERING_WRITE_ROLES} from '../../../constants/roles';
import usePagedList from '../../../hooks/usePagedList';
import Pagination from '../../../components/Pagination';
import ConfirmDialog from '../../../components/ConfirmDialog';
import DsButton from '../../../components/DsButton';
import DsIconButton from '../../../components/DsIconButton';
import DsStatusBadge from '../../../components/DsStatusBadge';
import SearchInput from '../../../components/SearchInput';
import DsFilterSelect from '../../../components/DsFilterSelect';
import DsToolbar from '../../../components/DsToolbar';
import DsTableEmpty from '../../../components/DsTableEmpty';
import SyncJobDrawer from './SyncJobDrawer';
import {
    HiOutlineCalendar,
    HiOutlineClock,
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
];

const TRIGGER_OPTIONS: { value: SyncTriggerType | ''; label: string }[] = [
    {value: '', label: '全部触发方式'},
    {value: 'MANUAL', label: '手动'},
    {value: 'CRON', label: 'Cron 定时'},
];

function syncModeBadge(syncMode?: string, incrementalField?: string) {
    if (syncMode === 'INCREMENTAL') {
        return (
            <DsStatusBadge
                variant="accent"
                label={`增量同步${incrementalField ? ` (${incrementalField})` : ''}`}
            />
        );
    }
    return <DsStatusBadge variant="success" label="全量同步"/>;
}

function triggerBadge(triggerType: string) {
    if (triggerType === 'MANUAL') {
        return <DsStatusBadge variant="accent" label="手动"/>;
    }
    return <DsStatusBadge variant="disabled" label="Cron 定时"/>;
}

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
};

// 「已应用」的查询条件（页面里的草稿条件由下方 draft* state 持有）
interface SyncJobListQuery {
    keyword: string;
    triggerType: SyncTriggerType | '';
    executionStatus: SyncExecutionStatus | '';
}

const INITIAL_QUERY: SyncJobListQuery = {keyword: '', triggerType: '', executionStatus: ''};

function formatSourceToTarget(item: SyncJob, dataSources: DataSource[]) {
    const ds = dataSources.find((d) => d.id === item.sourceDatasourceId);
    const dsName = ds?.name || item.sourceDatasourceId || '-';
    const db = item.sourceDatabase || item.sourceSchema || '';
    const schema = item.sourceSchema && item.sourceSchema !== item.sourceDatabase ? item.sourceSchema : '';
    const dbPath = schema ? `${db}/${schema}` : db;
    const sourceTable = item.sourceTables?.[0] || '';
    const sourcePath = dbPath ? `${dsName}.${dbPath}.${sourceTable}` : `${dsName}.${sourceTable}`;
    const targetDb = item.targetDatabase || 'doris';
    const targetTable = item.targetTable || sourceTable || '';
    return `${sourcePath} → doris.${targetDb}.${targetTable}`;
}

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
    const [draftTriggerType, setDraftTriggerType] = useState<SyncTriggerType | ''>('');
    const [draftExecutionStatus, setDraftExecutionStatus] = useState<SyncExecutionStatus | ''>('');

    const {
        list, total, page, pageSize, loading,
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

    const [drawerOpen, setDrawerOpen] = useState(false);
    const [editItem, setEditItem] = useState<SyncJob | null>(null);
    const [dataSources, setDataSources] = useState<DataSource[]>([]);
    const [executingId, setExecutingId] = useState<string | null>(null);
    const [schedulingId, setSchedulingId] = useState<string | null>(null);

    const [deleteTarget, setDeleteTarget] = useState<SyncJob | null>(null);
    const [deleteOpen, setDeleteOpen] = useState(false);
    const [deleteLoading, setDeleteLoading] = useState(false);

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

    const hasRunning = list.some((i) => i.executionStatus === 'RUNNING') || executingId != null;
    usePollingWhile(hasRunning, reload);

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
        setDrawerOpen(true);
    };

    const openEdit = useCallback(async (item: SyncJob) => {
        await loadDataSources();
        setEditItem(item);
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
        } finally {
            setDeleteLoading(false);
        }
    };

    const handleSearch = () => {
        applyQuery({
            keyword: draftKeyword,
            triggerType: draftTriggerType,
            executionStatus: draftExecutionStatus,
        });
    };

    const handleReset = () => {
        setDraftKeyword('');
        setDraftTriggerType('');
        setDraftExecutionStatus('');
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
            title: '任务名称',
            dataIndex: 'name',
            ellipsis: true,
            render: (v: string) => (
                <span className="text-ds-body text-ds-text-primary font-medium" title={v}>{v}</span>
            ),
        },
        {
            title: '触发方式',
            dataIndex: 'triggerType',
            render: (v: string) => triggerBadge(v),
        },
        {
            title: 'Cron 表达式',
            render: (_, item) => (
                <span className="text-ds-small text-ds-text-secondary font-mono">
                    {item.triggerType === 'CRON' && item.cronExpression ? item.cronExpression : '—'}
                </span>
            ),
        },
        {
            title: '调度状态',
            render: (_, item) => scheduleStatusBadge(item),
        },
        {
            title: '下次执行时间',
            render: (_, item) => (
                <span className="text-ds-small text-ds-text-secondary">
                    {formatNextExecutionTime(item)}
                </span>
            ),
        },
        {
            title: '源 → 目标',
            ellipsis: true,
            render: (_, item) => (
                <span className="text-ds-small text-ds-text-secondary font-mono"
                      title={formatSourceToTarget(item, dataSources)}>
                    {formatSourceToTarget(item, dataSources)}
                </span>
            ),
        },
        {
            title: '同步模式',
            render: (_, item) => syncModeBadge(item.syncMode, item.incrementalField),
        },
        {
            title: '状态',
            render: (_, item) => (
                <DsStatusBadge
                    variant={executionStatusVariant(item.executionStatus)}
                    label={EXECUTION_STATUS_LABELS[item.executionStatus] ?? '未知'}
                />
            ),
        },
        {
            title: '最近执行',
            render: (_, item) => (
                <span className="text-ds-small text-ds-text-secondary"
                      title={item.lastExecuteTime ? formatDateTime(item.lastExecuteTime) : ''}>
                    {formatRelativeTime(item.lastExecuteTime)}
                </span>
            ),
        },
        {
            title: '操作',
            align: 'center',
            render: (_, item) => (
                <div className="flex items-center justify-center w-full gap-1 whitespace-nowrap">
                    <Tooltip title="历史记录">
                        <DsIconButton
                            tone="accent"
                            data-testid={`sync-job-history-${item.name}`}
                            onClick={() => navigate(`/engineering/sync-job-history?syncJobId=${item.id}&jobName=${encodeURIComponent(item.name || '')}`)}
                            aria-label="历史记录"
                        >
                            <HiOutlineClock size={16}/>
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
                                        <HiOutlineCalendar size={16}/>
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
                                    <HiOutlinePlay size={16}/>
                                </DsIconButton>
                            </Tooltip>
                            <Tooltip title="编辑">
                                <DsIconButton
                                    tone="accent"
                                    data-testid={`sync-job-edit-${item.name}`}
                                    onClick={() => openEdit(item)}
                                    aria-label="编辑"
                                >
                                    <HiOutlinePencilSquare size={16}/>
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
                                    <HiOutlineTrash size={16}/>
                                </DsIconButton>
                            </Tooltip>
                        </>
                    )}
                </div>
            ),
        },
    ], [canWrite, dataSources, executingId, schedulingId, navigate, handleExecute, handleToggleSchedule, openEdit]);

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
                            >
                                {loading ? '查询中...' : '查询'}
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
                        value={draftTriggerType}
                        onChange={(v) => setDraftTriggerType(v as SyncTriggerType | '')}
                        options={TRIGGER_OPTIONS}
                        aria-label="按触发方式筛选"
                    />

                    <DsFilterSelect
                        value={draftExecutionStatus}
                        onChange={(v) => setDraftExecutionStatus(v as SyncExecutionStatus | '')}
                        options={STATUS_OPTIONS}
                        aria-label="按执行状态筛选"
                    />
                </DsToolbar>
            </div>

            <div className="flex flex-col">
                <div
                    className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden flex flex-col mb-ds-8">
                    <div className="overflow-x-auto">
                        <Table<SyncJob>
                            dataSource={list}
                            rowKey="id"
                            loading={loading}
                            pagination={false}
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
                sourceDataSources={dataSources}
                onClose={() => {
                    setDrawerOpen(false);
                    setEditItem(null);
                }}
                onSubmit={handleSubmit}
                onExecute={handleExecute}
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
        </div>
    );
}
