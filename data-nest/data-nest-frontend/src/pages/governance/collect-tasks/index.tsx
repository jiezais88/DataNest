import {useCallback, useEffect, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {useAuthStore} from '../../../store/useAuthStore';
import {getDataSources} from '../../../api/datasource';
import {
    createCollectTask,
    deleteCollectTask,
    executeCollectTask,
    queryCollectTasks,
    updateCollectTask,
} from '../../../api/collect';
import type {CollectTask, CollectTaskCreateRequest, CollectTaskQueryParams, TaskStatus} from '../../../types/collect';
import type {DataSource} from '../../../types/datasource';
import Pagination from '../../../components/Pagination';
import ConfirmDialog from '../../../components/ConfirmDialog';
import EmptyState from '../../../components/EmptyState';
import TaskDrawer from './TaskDrawer';
import {
    HiChevronRight,
    HiOutlineClock,
    HiOutlineMagnifyingGlass,
    HiOutlinePencilSquare,
    HiOutlinePlay,
    HiOutlinePlus,
    HiOutlineTrash,
} from 'react-icons/hi2';

const STATUS_OPTIONS: { value: TaskStatus | ''; label: string }[] = [
    {value: '', label: '全部状态'},
    {value: 'NORMAL', label: '正常'},
    {value: 'PAUSED', label: '已暂停'},
    {value: 'ERROR', label: '异常'},
];

function formatDateTime(value?: string) {
    if (!value) return '-';
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return '-';
    const pad = (n: number) => String(n).padStart(2, '0');
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
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

    const [deleteTarget, setDeleteTarget] = useState<CollectTask | null>(null);
    const [deleteOpen, setDeleteOpen] = useState(false);
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
            loadData();
        }
        return result;
    };

    const handleExecute = async (item: CollectTask) => {
        setExecutingId(item.id);
        try {
            const result = await executeCollectTask(item.id);
            if (result.code === 200) {
                loadData();
            }
        } finally {
            setExecutingId(null);
        }
    };

    const handleDelete = async () => {
        if (!deleteTarget) return;
        try {
            const result = await deleteCollectTask(deleteTarget.id);
            if (result.code === 200) {
                setDeleteOpen(false);
                setDeleteTarget(null);
                loadData();
            }
        } finally {
            // ignored
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
        if (value === 'NORMAL') return {
            dot: 'bg-ds-success',
            bg: 'bg-ds-success-light',
            text: 'text-ds-success',
            label: '正常'
        };
        if (value === 'PAUSED') return {
            dot: 'bg-ds-warning',
            bg: 'bg-ds-warning-light',
            text: 'text-ds-warning',
            label: '已暂停'
        };
        if (value === 'ERROR') return {
            dot: 'bg-ds-danger',
            bg: 'bg-ds-danger-light',
            text: 'text-ds-danger',
            label: '异常'
        };
        return {dot: 'bg-ds-text-muted', bg: 'bg-ds-bg-hover', text: 'text-ds-text-muted', label: '未知'};
    };

    const triggerLabel = (value: string) => (value === 'CRON' ? 'Cron 定时' : '手动触发');

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
                    <div className="relative flex-1 min-w-[220px] max-w-[360px]">
                        <HiOutlineMagnifyingGlass
                            size={16}
                            className="absolute left-3 top-1/2 -translate-y-1/2 text-ds-text-muted"
                        />
                        <input
                            value={draftKeyword}
                            onChange={(e) => setDraftKeyword(e.target.value)}
                            onKeyDown={(e) => {
                                if (e.key === 'Enter') handleSearch();
                            }}
                            aria-label="搜索任务名称"
                            className="w-full pl-9 pr-ds-3 py-ds-2 bg-ds-bg-hover border border-transparent rounded-ds-sm text-ds-body text-ds-text-primary placeholder:text-ds-text-muted focus:outline-none focus-visible:border-ds-accent focus-visible:bg-ds-bg-surface focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors ds-fast"
                            placeholder="搜索任务名称..."
                        />
                    </div>

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
                            <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-muted uppercase tracking-wider">任务名称</th>
                            <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-muted uppercase tracking-wider">采集范围</th>
                            <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-muted uppercase tracking-wider">触发方式</th>
                            <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-muted uppercase tracking-wider">状态</th>
                            <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-muted uppercase tracking-wider">最近执行</th>
                            <th className="text-right px-ds-4 py-ds-3 text-ds-caption text-ds-text-muted uppercase tracking-wider">操作</th>
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
                                    <td className="px-ds-4 py-ds-3 text-ds-body text-ds-text-secondary">
                                        {triggerLabel(item.triggerType)}
                                    </td>
                                    <td className="px-ds-4 py-ds-3">
                                        <span
                                            className={`inline-flex items-center gap-ds-1 px-ds-2 py-ds-1 rounded-ds-full text-ds-small font-medium ${statusStyle.bg} ${statusStyle.text}`}>
                                            <span className={`w-1.5 h-1.5 rounded-full ${statusStyle.dot}`}/>
                                            {statusStyle.label}
                                        </span>
                                    </td>
                                    <td className="px-ds-4 py-ds-3 text-ds-small text-ds-text-secondary">
                                        {formatDateTime(item.lastExecuteTime)}
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
                title="删除采集任务"
                message={`确定要删除采集任务 "${deleteTarget?.name}" 吗？删除后将不再调度执行，历史记录将一并删除，已采集元数据仍保留。`}
                confirmLabel="确认删除"
                danger
                onConfirm={handleDelete}
                onCancel={() => {
                    setDeleteOpen(false);
                    setDeleteTarget(null);
                }}
            />
        </div>
    );
}
