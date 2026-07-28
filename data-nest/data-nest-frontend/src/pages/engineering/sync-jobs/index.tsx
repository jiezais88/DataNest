import {useCallback, useEffect, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {message} from 'antd';
import {useAuthStore} from '../../../store/useAuthStore';
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
import {formatDateTime, formatRelativeTime} from '../../../utils/time';
import Pagination from '../../../components/Pagination';
import ConfirmDialog from '../../../components/ConfirmDialog';
import EmptyState from '../../../components/EmptyState';
import SearchInput from '../../../components/SearchInput';
import SyncJobDrawer from './SyncJobDrawer';
import {
    HiChevronRight,
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
            <span
                className="inline-flex items-center px-ds-2 py-ds-1 rounded-ds-full text-ds-small font-medium bg-blue-50 text-blue-700">
                增量同步{incrementalField ? ` (${incrementalField})` : ''}
            </span>
        );
    }
    return (
        <span
            className="inline-flex items-center px-ds-2 py-ds-1 rounded-ds-full text-ds-small font-medium bg-emerald-50 text-emerald-700">
            全量同步
        </span>
    );
}

function triggerBadge(triggerType: string) {
    if (triggerType === 'MANUAL') {
        return (
            <span
                className="inline-flex items-center px-ds-2 py-ds-1 rounded-ds-full text-ds-small font-medium bg-blue-50 text-blue-700">
                手动
            </span>
        );
    }
    return (
        <span
            className="inline-flex items-center px-ds-2 py-ds-1 rounded-ds-full text-ds-small font-medium bg-slate-100 text-blue-600">
            Cron 定时
        </span>
    );
}

function scheduleStatusBadge(item: SyncJob) {
    if (item.triggerType === 'MANUAL') {
        return <span className="text-ds-small text-ds-text-muted">—</span>;
    }
    if (item.scheduleEnabled) {
        return (
            <span
                className="inline-flex items-center gap-ds-1 px-ds-2 py-ds-1 rounded-ds-full text-ds-small font-medium bg-emerald-50 text-emerald-700">
                <span className="w-1.5 h-1.5 rounded-full bg-emerald-500"/>
                已启用
            </span>
        );
    }
    return (
        <span
            className="inline-flex items-center gap-ds-1 px-ds-2 py-ds-1 rounded-ds-full text-ds-small font-medium bg-gray-100 text-gray-600">
            <span className="w-1.5 h-1.5 rounded-full bg-gray-400"/>
            已停用
        </span>
    );
}

function executionStatusClass(value: SyncExecutionStatus) {
    if (value === 'PENDING') {
        return {dot: 'bg-ds-text-muted', bg: 'bg-ds-bg-hover', text: 'text-ds-text-muted', label: '未执行'};
    }
    if (value === 'RUNNING') {
        return {dot: 'bg-ds-accent', bg: 'bg-blue-50', text: 'text-blue-600', label: '运行中'};
    }
    if (value === 'SUCCESS') {
        return {dot: 'bg-ds-success', bg: 'bg-ds-success-light', text: 'text-ds-success', label: '成功'};
    }
    if (value === 'FAILED') {
        return {dot: 'bg-ds-danger', bg: 'bg-ds-danger-light', text: 'text-ds-danger', label: '失败'};
    }
    return {dot: 'bg-ds-text-muted', bg: 'bg-ds-bg-hover', text: 'text-ds-text-muted', label: '未知'};
}

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
    if (!item.scheduleEnabled) return '已停用';
    if (!item.nextExecutionTime) return '—';
    return formatDateTime(item.nextExecutionTime);
}

export default function SyncJobsPage() {
    const navigate = useNavigate();
    const {userInfo} = useAuthStore();
    const roles = userInfo?.roles || [];
    const canWrite = roles.includes('SUPER_ADMIN') || roles.includes('DATA_ENGINEER');

    const [items, setItems] = useState<SyncJob[]>([]);
    const [total, setTotal] = useState(0);
    const [page, setPage] = useState(1);
    const [pageSize, setPageSize] = useState(10);
    const [keyword, setKeyword] = useState('');
    const [triggerType, setTriggerType] = useState<SyncTriggerType | ''>('');
    const [executionStatus, setExecutionStatus] = useState<SyncExecutionStatus | ''>('');
    const [draftKeyword, setDraftKeyword] = useState('');
    const [draftTriggerType, setDraftTriggerType] = useState<SyncTriggerType | ''>('');
    const [draftExecutionStatus, setDraftExecutionStatus] = useState<SyncExecutionStatus | ''>('');
    const [loading, setLoading] = useState(false);

    const [drawerOpen, setDrawerOpen] = useState(false);
    const [editItem, setEditItem] = useState<SyncJob | null>(null);
    const [dataSources, setDataSources] = useState<DataSource[]>([]);
    const [executingId, setExecutingId] = useState<string | null>(null);
    const [schedulingId, setSchedulingId] = useState<string | null>(null);

    const [deleteTarget, setDeleteTarget] = useState<SyncJob | null>(null);
    const [deleteOpen, setDeleteOpen] = useState(false);
    const [deleteLoading, setDeleteLoading] = useState(false);
    const [searchTrigger, setSearchTrigger] = useState(0);

    const loadData = useCallback(async () => {
        setLoading(true);
        try {
            const params: SyncJobQueryParams = {
                page,
                pageSize,
                keyword: keyword || undefined,
                triggerType: triggerType || undefined,
                executionStatus: executionStatus || undefined,
            };
            const result = await querySyncJobs(params);
            if (result.code === 200) {
                setItems(result.data.records);
                setTotal(result.data.total);
            }
        } finally {
            setLoading(false);
        }
    }, [page, pageSize, keyword, triggerType, executionStatus, searchTrigger]);

    useEffect(() => {
        loadData();
    }, [loadData]);

    useEffect(() => {
        const hasRunning = items.some((i) => i.executionStatus === 'RUNNING') || executingId != null;
        if (!hasRunning) return;
        const timer = setInterval(() => loadData(), 3000);
        const stop = setTimeout(() => clearInterval(timer), 30000);
        return () => {
            clearInterval(timer);
            clearTimeout(stop);
        };
    }, [items, executingId, loadData]);

    const loadDataSources = async () => {
        try {
            const result = await getDataSources({page: 1, pageSize: 1000});
            if (result.code === 200) {
                setDataSources(result.data.records.filter((ds) => ds.status === 'NORMAL'));
            }
        } catch {
            // ignored
        }
    };

    const openCreate = async () => {
        await loadDataSources();
        setEditItem(null);
        setDrawerOpen(true);
    };

    const openEdit = async (item: SyncJob) => {
        await loadDataSources();
        setEditItem(item);
        setDrawerOpen(true);
    };

    const handleSubmit = async (payload: SyncJobCreateRequest) => {
        const result = editItem
            ? await updateSyncJob(editItem.id, payload)
            : await createSyncJob(payload);
        if (result.code === 200) {
            message.success(editItem ? '同步任务更新成功' : '同步任务创建成功');
            loadData();
        }
        return result;
    };

    const handleExecute = async (item: SyncJob) => {
        setExecutingId(item.id);
        try {
            const result = await executeSyncJob(item.id);
            if (result.code === 200) {
                message.success(`同步任务 "${item.name}" 已触发执行`);
                loadData();
            }
        } finally {
            setExecutingId(null);
        }
    };

    const handleToggleSchedule = async (item: SyncJob) => {
        setSchedulingId(item.id);
        try {
            const result = item.scheduleEnabled
                ? await stopSyncJobSchedule(item.id)
                : await startSyncJobSchedule(item.id);
            if (result.code === 200) {
                message.success(`同步任务 "${item.name}" 已${item.scheduleEnabled ? '停用调度' : '启用调度'}`);
                loadData();
            }
        } finally {
            setSchedulingId(null);
        }
    };

    const handleDelete = async () => {
        if (!deleteTarget) return;
        setDeleteLoading(true);
        try {
            const result = await deleteSyncJob(deleteTarget.id);
            if (result.code === 200) {
                message.success('同步任务已删除');
                setDeleteOpen(false);
                setDeleteTarget(null);
                loadData();
            }
        } finally {
            setDeleteLoading(false);
        }
    };

    const handleSearch = () => {
        setKeyword(draftKeyword);
        setTriggerType(draftTriggerType);
        setExecutionStatus(draftExecutionStatus);
        setPage(1);
        setSearchTrigger((v) => v + 1);
    };

    const handleReset = () => {
        setDraftKeyword('');
        setDraftTriggerType('');
        setDraftExecutionStatus('');
        setKeyword('');
        setTriggerType('');
        setExecutionStatus('');
        setPage(1);
    };

    const handlePageChange = (nextPage: number, nextPageSize: number) => {
        setPage(nextPage);
        setPageSize(nextPageSize);
    };

    return (
        <div className="h-full flex flex-col overflow-hidden">
            <div className="flex items-center justify-between mb-ds-5 flex-shrink-0">
                <div>
                    <h1 className="text-ds-display text-ds-text-primary">批量数据同步</h1>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1">管理数据源到 Doris 的批量同步任务，支持手动触发与
                        Cron 定时调度</p>
                </div>
                {canWrite && (
                    <button
                        data-testid="sync-job-create"
                        onClick={openCreate}
                        className="flex items-center gap-ds-1 px-ds-3 py-ds-2 bg-ds-accent hover:bg-ds-accent-hover text-white text-ds-small font-semibold rounded-ds-sm transition-colors ds-fast"
                    >
                        <HiOutlinePlus size={16}/>
                        创建任务
                    </button>
                )}
            </div>

            <div
                className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle p-ds-3 mb-ds-4 flex-shrink-0">
                <div className="flex items-center gap-ds-3 flex-wrap">
                    <SearchInput
                        value={draftKeyword}
                        onChange={(e) => setDraftKeyword(e.target.value)}
                        onEnter={handleSearch}
                        placeholder="搜索任务名称..."
                    />

                    <div className="relative">
                        <select
                            value={draftTriggerType}
                            onChange={(e) => setDraftTriggerType(e.target.value as SyncTriggerType | '')}
                            aria-label="按触发方式筛选"
                            className="appearance-none min-w-[140px] pl-ds-3 pr-9 py-ds-2 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-small text-ds-text-primary focus:outline-none focus-visible:border-ds-accent cursor-pointer"
                        >
                            {TRIGGER_OPTIONS.map((o) => (
                                <option key={o.value} value={o.value}>{o.label}</option>
                            ))}
                        </select>
                        <HiChevronRight
                            size={14}
                            className="absolute right-3 top-1/2 -translate-y-1/2 rotate-90 text-ds-text-muted pointer-events-none"
                        />
                    </div>

                    <div className="relative">
                        <select
                            value={draftExecutionStatus}
                            onChange={(e) => setDraftExecutionStatus(e.target.value as SyncExecutionStatus | '')}
                            aria-label="按执行状态筛选"
                            className="appearance-none min-w-[140px] pl-ds-3 pr-9 py-ds-2 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-small text-ds-text-primary focus:outline-none focus-visible:border-ds-accent cursor-pointer"
                        >
                            {STATUS_OPTIONS.map((o) => (
                                <option key={o.value} value={o.value}>{o.label}</option>
                            ))}
                        </select>
                        <HiChevronRight
                            size={14}
                            className="absolute right-3 top-1/2 -translate-y-1/2 rotate-90 text-ds-text-muted pointer-events-none"
                        />
                    </div>

                    <div className="flex items-center gap-ds-2 ml-auto">
                        <button
                            onClick={handleSearch}
                            disabled={loading}
                            className="px-ds-4 py-ds-2 bg-ds-accent hover:bg-ds-accent-hover disabled:opacity-60 disabled:cursor-not-allowed text-white text-ds-small font-semibold rounded-ds-sm transition-colors ds-fast"
                        >
                            {loading ? '查询中...' : '查询'}
                        </button>
                        <button
                            onClick={handleReset}
                            disabled={loading}
                            className="px-ds-4 py-ds-2 bg-white border border-ds-border-subtle hover:border-ds-border-strong disabled:opacity-60 disabled:cursor-not-allowed text-ds-text-secondary text-ds-small font-semibold rounded-ds-sm transition-colors ds-fast"
                        >
                            重置
                        </button>
                    </div>
                </div>
            </div>

            <div className="flex-1 min-h-0 overflow-auto">
                <div
                    className="relative bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden">
                    <table className="w-full">
                        <thead className="sticky top-0 z-10">
                        <tr className="border-b border-ds-border-subtle bg-ds-bg-hover/80">
                            <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-muted uppercase tracking-wider">任务名称</th>
                            <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-muted uppercase tracking-wider">触发方式</th>
                            <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-muted uppercase tracking-wider">调度状态</th>
                            <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-muted uppercase tracking-wider">下次执行时间</th>
                            <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-muted uppercase tracking-wider">源
                                → 目标
                            </th>
                            <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-muted uppercase tracking-wider">同步模式</th>
                            <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-muted uppercase tracking-wider">状态</th>
                            <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-muted uppercase tracking-wider">最近执行</th>
                            <th className="text-right px-ds-4 py-ds-3 text-ds-caption text-ds-text-muted uppercase tracking-wider">操作</th>
                        </tr>
                        </thead>
                        <tbody>
                        {items.map((item) => {
                            const statusStyle = executionStatusClass(item.executionStatus);
                            return (
                                <tr
                                    key={item.id}
                                    data-testid={`sync-job-row-${item.name}`}
                                    data-job-id={item.id}
                                    className="border-b border-ds-border-subtle last:border-0 hover:bg-ds-bg-hover/50 transition-colors"
                                >
                                    <td className="px-ds-4 py-ds-3">
                                        <span
                                            className="text-ds-body text-ds-text-primary font-medium">{item.name}</span>
                                    </td>
                                    <td className="px-ds-4 py-ds-3">{triggerBadge(item.triggerType)}</td>
                                    <td className="px-ds-4 py-ds-3">{scheduleStatusBadge(item)}</td>
                                    <td className="px-ds-4 py-ds-3 text-ds-small text-ds-text-secondary">
                                        {formatNextExecutionTime(item)}
                                    </td>
                                    <td className="px-ds-4 py-ds-3 text-ds-small text-ds-text-secondary font-mono">
                                        {formatSourceToTarget(item, dataSources)}
                                    </td>
                                    <td className="px-ds-4 py-ds-3">{syncModeBadge(item.syncMode, item.incrementalField)}</td>
                                    <td className="px-ds-4 py-ds-3">
                                        <span
                                            className={`inline-flex items-center gap-ds-1 px-ds-2 py-ds-1 rounded-ds-full text-ds-small font-medium ${statusStyle.bg} ${statusStyle.text}`}>
                                            <span className={`w-1.5 h-1.5 rounded-full ${statusStyle.dot}`}/>
                                            {statusStyle.label}
                                        </span>
                                    </td>
                                    <td className="px-ds-4 py-ds-3 text-ds-small text-ds-text-secondary"
                                        title={item.lastExecuteTime ? formatDateTime(item.lastExecuteTime) : ''}>
                                        {formatRelativeTime(item.lastExecuteTime)}
                                    </td>
                                    <td className="px-ds-4 py-ds-3">
                                        <div className="flex items-center justify-end gap-1">
                                            <button
                                                data-testid={`sync-job-history-${item.name}`}
                                                onClick={() => navigate(`/engineering/sync-jobs/${item.id}/history`)}
                                                className="p-1.5 text-ds-text-muted hover:text-ds-accent hover:bg-ds-accent-light rounded transition-colors"
                                                title="历史记录"
                                                aria-label="历史记录"
                                            >
                                                <HiOutlineClock size={16}/>
                                            </button>
                                            {canWrite && (
                                                <>
                                                    {item.triggerType === 'CRON' && (
                                                        <button
                                                            data-testid={`sync-job-schedule-${item.name}`}
                                                            onClick={() => handleToggleSchedule(item)}
                                                            disabled={schedulingId === item.id}
                                                            className={`p-1.5 rounded transition-colors disabled:opacity-60 ${
                                                                item.scheduleEnabled
                                                                    ? 'text-emerald-600 hover:text-emerald-700 hover:bg-emerald-50'
                                                                    : 'text-ds-text-muted hover:text-ds-accent hover:bg-ds-accent-light'
                                                            }`}
                                                            title={item.scheduleEnabled ? '停用调度' : '启用调度'}
                                                            aria-label={item.scheduleEnabled ? '停用调度' : '启用调度'}
                                                        >
                                                            <HiOutlineCalendar size={16}/>
                                                        </button>
                                                    )}
                                                    <button
                                                        data-testid={`sync-job-execute-${item.name}`}
                                                        onClick={() => handleExecute(item)}
                                                        disabled={executingId === item.id}
                                                        className="p-1.5 text-ds-text-muted hover:text-ds-success hover:bg-ds-success-light rounded transition-colors disabled:opacity-60"
                                                        title="立即执行"
                                                        aria-label="立即执行"
                                                    >
                                                        <HiOutlinePlay size={16}/>
                                                    </button>
                                                    <button
                                                        data-testid={`sync-job-edit-${item.name}`}
                                                        onClick={() => openEdit(item)}
                                                        className="p-1.5 text-ds-text-muted hover:text-ds-accent hover:bg-ds-accent-light rounded transition-colors"
                                                        title="编辑"
                                                        aria-label="编辑"
                                                    >
                                                        <HiOutlinePencilSquare size={16}/>
                                                    </button>
                                                    <button
                                                        data-testid={`sync-job-delete-${item.name}`}
                                                        onClick={() => {
                                                            setDeleteTarget(item);
                                                            setDeleteOpen(true);
                                                        }}
                                                        className="p-1.5 text-ds-text-muted hover:text-ds-danger hover:bg-ds-danger-light rounded transition-colors"
                                                        title="删除"
                                                        aria-label="删除"
                                                    >
                                                        <HiOutlineTrash size={16}/>
                                                    </button>
                                                </>
                                            )}
                                        </div>
                                    </td>
                                </tr>
                            );
                        })}
                        </tbody>
                    </table>

                    {items.length === 0 && !loading && (
                        <EmptyState
                            title="暂无同步任务"
                            description="还没有批量数据同步任务，创建第一个任务开始同步到 Doris。"
                            action={
                                canWrite ? (
                                    <button
                                        onClick={openCreate}
                                        className="flex items-center gap-ds-1 px-ds-4 py-ds-2 bg-ds-accent hover:bg-ds-accent-hover text-white text-ds-small font-semibold rounded-ds-sm transition-colors"
                                    >
                                        <HiOutlinePlus size={16}/>
                                        创建任务
                                    </button>
                                ) : null
                            }
                        />
                    )}

                    <Pagination page={page} pageSize={pageSize} total={total} onChange={handlePageChange}/>

                    {loading && (
                        <div
                            className="absolute inset-0 z-20 bg-ds-bg-surface/70 backdrop-blur-[1px] flex flex-col items-center justify-center gap-ds-2">
                            <svg className="animate-spin h-6 w-6 text-ds-accent" xmlns="http://www.w3.org/2000/svg"
                                 fill="none" viewBox="0 0 24 24">
                                <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor"
                                        strokeWidth="4"/>
                                <path className="opacity-75" fill="currentColor"
                                      d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"/>
                            </svg>
                            <span className="text-ds-small text-ds-text-secondary">加载中...</span>
                        </div>
                    )}
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
