import {useCallback, useEffect, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {message} from 'antd';
import parseExpression from 'cron-parser';
import {useAuthStore} from '../../../store/useAuthStore';
import {getDataSources} from '../../../api/datasource';
import {
    createCollectTask,
    deleteCollectTask,
    executeCollectTask,
    queryCollectTasks,
    startCollectTaskSchedule,
    stopCollectTaskSchedule,
    updateCollectTask,
} from '../../../api/collect';
import type {CollectTask, CollectTaskCreateRequest, CollectTaskQueryParams, TaskStatus} from '../../../types/collect';
import type {DataSource} from '../../../types/datasource';
import {formatRelativeTime} from '../../../utils/time';
import Pagination from '../../../components/Pagination';
import ConfirmDialog from '../../../components/ConfirmDialog';
import EmptyState from '../../../components/EmptyState';
import SearchInput from '../../../components/SearchInput';
import TaskDrawer from './TaskDrawer';
import {
    HiChevronRight,
    HiOutlineCalendar,
    HiOutlineClock,
    HiOutlinePencilSquare,
    HiOutlinePlay,
    HiOutlinePlus,
    HiOutlineTrash,
} from 'react-icons/hi2';

const STATUS_OPTIONS: { value: TaskStatus | ''; label: string }[] = [
    {value: '', label: '全部状态'},
    {value: 'NEVER_EXECUTED', label: '未执行'},
    {value: 'RUNNING', label: '运行中'},
    {value: 'SUCCESS', label: '成功'},
    {value: 'FAILED', label: '失败'},
];

function formatDateTime(value?: string) {
    if (!value) return '-';
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return '-';
    const pad = (n: number) => String(n).padStart(2, '0');
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

function computeNextExecutionTime(triggerType: string, cronExpression?: string): string {
    if (triggerType !== 'CRON' || !cronExpression) return '-';
    try {
        const interval = parseExpression.parse(cronExpression);
        return formatDateTime(interval.next().toDate().toISOString());
    } catch {
        return '-';
    }
}

function collectModeBadge(collectMode?: string) {
    if (collectMode === 'FULL_INCREMENT') {
        return (
            <span
                className="inline-flex items-center px-ds-2 py-ds-1 rounded-ds-full text-ds-small font-medium bg-purple-50 text-purple-700">
                {'全量+增量'}
            </span>
        );
    }
    return (
        <span
            className="inline-flex items-center px-ds-2 py-ds-1 rounded-ds-full text-ds-small font-medium bg-emerald-50 text-emerald-700">
            {'全量采集'}
        </span>
    );
}

function formatScope(items?: string[]) {
    if (!items || items.length === 0) return '全部';
    const first = items.slice(0, 2).join('、');
    return items.length > 2 ? `${first} +${items.length - 2}` : first;
}

export default function CollectTasksPage() {
    const navigate = useNavigate();
    const {userInfo} = useAuthStore();
    const roles = userInfo?.roles || [];
    const canWrite = roles.includes('SUPER_ADMIN') || roles.includes('GOVERNANCE_ADMIN');

    const [items, setItems] = useState<CollectTask[]>([]);
    const [total, setTotal] = useState(0);
    const [page, setPage] = useState(1);
    const [pageSize, setPageSize] = useState(10);
    const [keyword, setKeyword] = useState('');
    const [status, setStatus] = useState<TaskStatus | ''>('');
    const [draftKeyword, setDraftKeyword] = useState('');
    const [draftStatus, setDraftStatus] = useState<TaskStatus | ''>('');
    const [loading, setLoading] = useState(false);

    const [drawerOpen, setDrawerOpen] = useState(false);
    const [editItem, setEditItem] = useState<CollectTask | null>(null);
    const [dataSources, setDataSources] = useState<DataSource[]>([]);
    const [executingId, setExecutingId] = useState<string | null>(null);
    const [schedulingId, setSchedulingId] = useState<string | null>(null);

    const [deleteTarget, setDeleteTarget] = useState<CollectTask | null>(null);
    const [deleteOpen, setDeleteOpen] = useState(false);
    const [deleteLoading, setDeleteLoading] = useState(false);
    const [searchTrigger, setSearchTrigger] = useState(0);

    const loadData = useCallback(async () => {
        setLoading(true);
        try {
            const params: CollectTaskQueryParams = {
                page,
                pageSize,
                keyword: keyword || undefined,
                status: status || undefined,
            };
            const result = await queryCollectTasks(params);
            if (result.code === 200) {
                setItems(result.data.records);
                setTotal(result.data.total);
            }
        } finally {
            setLoading(false);
        }
    }, [page, pageSize, keyword, status, searchTrigger]);

    useEffect(() => {
        loadData();
    }, [loadData]);

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

    const openCreate = () => {
        setEditItem(null);
        loadDataSources();
        setDrawerOpen(true);
    };

    const openEdit = (item: CollectTask) => {
        setEditItem(item);
        loadDataSources();
        setDrawerOpen(true);
    };

    const handleSubmit = async (payload: CollectTaskCreateRequest) => {
        const result = editItem
            ? await updateCollectTask(editItem.id, payload)
            : await createCollectTask(payload);
        if (result.code === 200) {
            message.success(editItem ? '采集任务更新成功' : '采集任务创建成功');
            loadData();
        }
        return result;
    };

    const handleExecute = async (item: CollectTask) => {
        setExecutingId(item.id);
        try {
            const result = await executeCollectTask(item.id);
            if (result.code === 200) {
                message.success(`采集任务 "${item.name}" 已触发执行`);
                loadData();
            }
        } finally {
            setExecutingId(null);
        }
    };

    const handleToggleSchedule = async (item: CollectTask) => {
        setSchedulingId(item.id);
        try {
            const isEnabled = item.scheduleEnabled === 1;
            const result = isEnabled
                ? await stopCollectTaskSchedule(item.id)
                : await startCollectTaskSchedule(item.id);
            if (result.code === 200) {
                message.success(`采集任务 "${item.name}" 已${isEnabled ? '停止调度' : '开启调度'}`);
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
            const result = await deleteCollectTask(deleteTarget.id);
            if (result.code === 200) {
                message.success('采集任务已删除');
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
        setStatus(draftStatus);
        setPage(1);
        setSearchTrigger((v) => v + 1);
    };

    const handleReset = () => {
        setDraftKeyword('');
        setDraftStatus('');
        setKeyword('');
        setStatus('');
        setPage(1);
    };

    const handlePageChange = (nextPage: number, nextPageSize: number) => {
        setPage(nextPage);
        setPageSize(nextPageSize);
    };

    const statusClass = (value: TaskStatus) => {
        if (value === 'SUCCESS') return {
            dot: 'bg-ds-success',
            bg: 'bg-ds-success-light',
            text: 'text-ds-success',
            label: '成功'
        };
        if (value === 'RUNNING') return {
            dot: 'bg-blue-500 animate-pulse',
            bg: 'bg-blue-50',
            text: 'text-blue-600',
            label: '运行中'
        };
        if (value === 'FAILED') return {
            dot: 'bg-ds-danger',
            bg: 'bg-ds-danger-light',
            text: 'text-ds-danger',
            label: '失败'
        };
        if (value === 'NEVER_EXECUTED') return {
            dot: 'bg-gray-400',
            bg: 'bg-gray-100',
            text: 'text-gray-500',
            label: '未执行'
        };
        return {dot: 'bg-ds-text-muted', bg: 'bg-ds-bg-hover', text: 'text-ds-text-muted', label: '未知'};
    };

    const triggerBadge = (triggerType: string) => {
        if (triggerType === 'MANUAL') {
            return (
                <span
                    className="inline-flex items-center px-ds-2 py-ds-1 rounded-ds-full text-ds-small font-medium bg-blue-50 text-blue-700">
                    {'手动'}
                </span>
            );
        }
        return (
            <span
                className="inline-flex items-center px-ds-2 py-ds-1 rounded-ds-full text-ds-small font-medium bg-slate-100 text-blue-600">
                {'Cron 定时'}
            </span>
        );
    };

    function scheduleStatusBadge(item: CollectTask) {
        if (item.triggerType === 'MANUAL') {
            return <span className="text-ds-small text-ds-text-muted">{'—'}</span>;
        }
        if (item.scheduleEnabled === 1) {
            return (
                <span
                    className="inline-flex items-center gap-ds-1 px-ds-2 py-ds-1 rounded-ds-full text-ds-small font-medium bg-emerald-50 text-emerald-700">
                    <span className="w-1.5 h-1.5 rounded-full bg-emerald-500"/>
                    {'已启用'}
                </span>
            );
        }
        return (
            <span
                className="inline-flex items-center gap-ds-1 px-ds-2 py-ds-1 rounded-ds-full text-ds-small font-medium bg-gray-100 text-gray-600">
                <span className="w-1.5 h-1.5 rounded-full bg-gray-400"/>
                {'已停用'}
            </span>
        );
    }

    return (
        <div className="h-full flex flex-col overflow-hidden">
            <div className="flex items-center justify-between mb-ds-5 flex-shrink-0">
                <div>
                    <h1 className="text-ds-display text-ds-text-primary">元数据采集任务</h1>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1">管理数据源元数据采集任务，支持手动与 Cron
                        定时触发</p>
                </div>
                {canWrite && (
                    <button
                        data-testid="collect-task-create"
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
                            value={draftStatus}
                            onChange={(e) => setDraftStatus(e.target.value as TaskStatus | '')}
                            aria-label="按状态筛选"
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
                            <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-secondary uppercase tracking-wider">任务名称</th>
                            <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-secondary uppercase tracking-wider">采集范围</th>
                            <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-secondary uppercase tracking-wider">触发方式</th>
                            <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-secondary uppercase tracking-wider">Cron
                                表达式
                            </th>
                            <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-secondary uppercase tracking-wider">调度状态</th>
                            <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-secondary uppercase tracking-wider">采集模式</th>
                            <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-secondary uppercase tracking-wider">下次执行时间</th>
                            <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-secondary uppercase tracking-wider">状态</th>
                            <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-secondary uppercase tracking-wider">最近执行</th>
                            <th className="text-right px-ds-4 py-ds-3 text-ds-caption text-ds-text-secondary uppercase tracking-wider">操作</th>
                        </tr>
                        </thead>
                        <tbody>
                        {items.map((item) => {
                            const statusStyle = statusClass(item.status);
                            return (
                                <tr
                                    key={item.id}
                                    data-testid={`collect-task-row-${item.name}`}
                                    data-task-id={item.id}
                                    className="border-b border-ds-border-subtle last:border-0 hover:bg-ds-bg-hover/50 transition-colors"
                                >
                                    <td className="px-ds-4 py-ds-3">
                                        <span
                                            className="text-ds-body text-ds-text-primary font-medium">{item.name}</span>
                                    </td>
                                    <td className="px-ds-4 py-ds-3 text-ds-body text-ds-text-secondary">
                                        {formatScope(item.scope)}
                                    </td>
                                    <td className="px-ds-4 py-ds-3">
                                        {triggerBadge(item.triggerType)}
                                    </td>
                                    <td className="px-ds-4 py-ds-3 text-ds-small text-ds-text-secondary font-mono">
                                        {item.triggerType === 'CRON' && item.cronExpression ? item.cronExpression : '—'}
                                    </td>
                                    <td className="px-ds-4 py-ds-3">
                                        {scheduleStatusBadge(item)}
                                    </td>
                                    <td className="px-ds-4 py-ds-3">
                                        {collectModeBadge(item.collectMode)}
                                    </td>
                                    <td className="px-ds-4 py-ds-3 text-ds-small text-ds-text-secondary">
                                        {computeNextExecutionTime(item.triggerType, item.cronExpression)}
                                    </td>
                                    <td className="px-ds-4 py-ds-3">
                                        <span
                                            className={`inline-flex items-center gap-ds-1 px-ds-2 py-ds-1 rounded-ds-full text-ds-small font-medium ${statusStyle.bg} ${statusStyle.text}`}>
                                            <span className={`w-1.5 h-1.5 rounded-full ${statusStyle.dot}`}/>
                                            {statusStyle.label}
                                        </span>
                                    </td>
                                    <td className="px-ds-4 py-ds-3 text-ds-small text-ds-text-secondary"
                                        title={item.lastExecuteTime ? new Date(item.lastExecuteTime).toLocaleString('zh-CN') : ''}>
                                        {formatRelativeTime(item.lastExecuteTime)}
                                    </td>
                                    <td className="px-ds-4 py-ds-3">
                                        <div className="flex items-center justify-end gap-1">
                                            <button
                                                data-testid={`collect-task-history-${item.name}`}
                                                onClick={() => navigate(`/governance/collect-tasks/${item.id}/history`)}
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
                                                            data-testid={`collect-task-schedule-${item.name}`}
                                                            onClick={() => handleToggleSchedule(item)}
                                                            disabled={schedulingId === item.id}
                                                            className={`p-1.5 rounded transition-colors disabled:opacity-60 ${
                                                                item.scheduleEnabled === 1
                                                                    ? 'text-emerald-600 hover:text-emerald-700 hover:bg-emerald-50'
                                                                    : 'text-ds-text-muted hover:text-ds-accent hover:bg-ds-accent-light'
                                                            }`}
                                                            title={item.scheduleEnabled === 1 ? '关闭调度' : '开启调度'}
                                                            aria-label={item.scheduleEnabled === 1 ? '关闭调度' : '开启调度'}
                                                        >
                                                            <HiOutlineCalendar size={16}/>
                                                        </button>
                                                    )}
                                                    <button
                                                        data-testid={`collect-task-execute-${item.name}`}
                                                        onClick={() => handleExecute(item)}
                                                        disabled={executingId === item.id}
                                                        className="p-1.5 text-ds-text-muted hover:text-ds-success hover:bg-ds-success-light rounded transition-colors disabled:opacity-60"
                                                        title="立即执行"
                                                        aria-label="立即执行"
                                                    >
                                                        <HiOutlinePlay size={16}/>
                                                    </button>
                                                    <button
                                                        data-testid={`collect-task-edit-${item.name}`}
                                                        onClick={() => openEdit(item)}
                                                        className="p-1.5 text-ds-text-muted hover:text-ds-accent hover:bg-ds-accent-light rounded transition-colors"
                                                        title="编辑"
                                                        aria-label="编辑"
                                                    >
                                                        <HiOutlinePencilSquare size={16}/>
                                                    </button>
                                                    <button
                                                        data-testid={`collect-task-delete-${item.name}`}
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
                            title="暂无采集任务"
                            description="还没有元数据采集任务，创建第一个任务开始自动采集数据源表结构。"
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

            <TaskDrawer
                open={drawerOpen}
                editItem={editItem}
                dataSources={dataSources}
                onClose={() => {
                    setDrawerOpen(false);
                    setEditItem(null);
                }}
                onSubmit={handleSubmit}
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
                                <li>该任务的所有历史记录将被删除</li>
                                <li>已采集的元数据记录仍保留</li>
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
