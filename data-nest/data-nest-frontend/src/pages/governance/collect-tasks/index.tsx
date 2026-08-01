import type {HTMLAttributes} from 'react';
import {useMemo, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {Table, Tooltip} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import {nextRunTime} from '../../../utils/cron';
import {notify} from '../../../utils/notify';
import {useHasRole} from '../../../hooks/useHasRole';
import {GOVERNANCE_WRITE_ROLES} from '../../../constants/roles';
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
import {formatDateTime, formatRelativeTime} from '../../../utils/format';
import usePagedList from '../../../hooks/usePagedList';
import Pagination from '../../../components/Pagination';
import DsButton from '../../../components/DsButton';
import DsIconButton from '../../../components/DsIconButton';
import DsStatusBadge from '../../../components/DsStatusBadge';
import DsTableEmpty from '../../../components/DsTableEmpty';
import {executionStatusVariant} from '../../../utils/status';
import ConfirmDialog from '../../../components/ConfirmDialog';
import SearchInput from '../../../components/SearchInput';
import DsFilterSelect from '../../../components/DsFilterSelect';
import DsToolbar from '../../../components/DsToolbar';
import TaskDrawer from './TaskDrawer';
import {
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

function computeNextExecutionTime(item: CollectTask): string {
    if (item.triggerType !== 'CRON' || !item.cronExpression || item.scheduleEnabled !== 1) return '-';
    const next = nextRunTime(item.cronExpression);
    return next ? formatDateTime(next.toISOString()) : '-';
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
    return <DsStatusBadge label="全量采集" variant="success"/>;
}

function formatScope(items?: string[]) {
    if (!items || items.length === 0) return '全部';
    const first = items.slice(0, 2).join('、');
    return items.length > 2 ? `${first} +${items.length - 2}` : first;
}

interface TaskListQuery {
    keyword: string;
    status: TaskStatus | '';
}

const INITIAL_QUERY: TaskListQuery = {keyword: '', status: ''};

export default function CollectTasksPage() {
    const navigate = useNavigate();
    const canWrite = useHasRole(...GOVERNANCE_WRITE_ROLES);

    const {list, total, page, pageSize, loading, setPage, setPageSize, applyQuery, reload} =
        usePagedList<TaskListQuery, CollectTask>({
            fetcher: async (q) => {
                const params: CollectTaskQueryParams = {
                    page: q.page,
                    pageSize: q.pageSize,
                    keyword: q.keyword || undefined,
                    status: q.status || undefined,
                };
                const result = await queryCollectTasks(params);
                return {list: result.data.records, total: result.data.total};
            },
            initialQuery: INITIAL_QUERY,
            defaultPageSize: 10,
        });

    const [draftKeyword, setDraftKeyword] = useState('');
    const [draftStatus, setDraftStatus] = useState<TaskStatus | ''>('');

    const [drawerOpen, setDrawerOpen] = useState(false);
    const [editItem, setEditItem] = useState<CollectTask | null>(null);
    const [dataSources, setDataSources] = useState<DataSource[]>([]);
    const [executingId, setExecutingId] = useState<string | null>(null);
    const [schedulingId, setSchedulingId] = useState<string | null>(null);

    const [deleteTarget, setDeleteTarget] = useState<CollectTask | null>(null);
    const [deleteOpen, setDeleteOpen] = useState(false);
    const [deleteLoading, setDeleteLoading] = useState(false);

    const loadDataSources = async () => {
        try {
            const result = await getDataSources({page: 1, pageSize: 1000});
            setDataSources(result.data.records.filter((ds) => ds.status === 'NORMAL'));
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
        notify.success(editItem ? '采集任务更新成功' : '采集任务创建成功');
        reload();
        return result;
    };

    const handleExecute = async (item: CollectTask) => {
        setExecutingId(item.id);
        try {
            await executeCollectTask(item.id);
            notify.success(`采集任务 "${item.name}" 已触发执行`);
            reload();
        } finally {
            setExecutingId(null);
        }
    };

    const handleToggleSchedule = async (item: CollectTask) => {
        setSchedulingId(item.id);
        try {
            const isEnabled = item.scheduleEnabled === 1;
            if (isEnabled) {
                await stopCollectTaskSchedule(item.id);
            } else {
                await startCollectTaskSchedule(item.id);
            }
            notify.success(`采集任务 "${item.name}" 已${isEnabled ? '停止调度' : '开启调度'}`);
            reload();
        } finally {
            setSchedulingId(null);
        }
    };

    const handleDelete = async () => {
        if (!deleteTarget) return;
        setDeleteLoading(true);
        try {
            await deleteCollectTask(deleteTarget.id);
            notify.success('采集任务已删除');
            setDeleteOpen(false);
            setDeleteTarget(null);
            reload();
        } finally {
            setDeleteLoading(false);
        }
    };

    const handleSearch = () => {
        applyQuery({keyword: draftKeyword, status: draftStatus});
    };

    const handleReset = () => {
        setDraftKeyword('');
        setDraftStatus('');
        applyQuery(INITIAL_QUERY);
    };

    const handlePageChange = (nextPage: number, nextPageSize: number) => {
        if (nextPageSize !== pageSize) {
            setPageSize(nextPageSize);
        } else {
            setPage(nextPage);
        }
    };

    function statusLabel(value?: string): string {
        switch (value) {
            case 'SUCCESS':
                return '成功';
            case 'RUNNING':
                return '运行中';
            case 'FAILED':
                return '失败';
            case 'NEVER_EXECUTED':
                return '未执行';
            default:
                return '未知';
        }
    }

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
            return <DsStatusBadge label="已启用" variant="success"/>;
        }
        return <DsStatusBadge label="已停用" variant="disabled"/>;
    }

    const columns = useMemo<ColumnsType<CollectTask>>(() => [
        {
            title: '任务名称',
            dataIndex: 'name',
            ellipsis: true,
            render: (v: string) => (
                <span title={v} className="text-ds-body text-ds-text-primary font-medium">{v}</span>
            ),
        },
        {
            title: '数据源',
            dataIndex: 'datasourceName',
            ellipsis: true,
            render: (v: string | undefined, item) => (
                <span title={item.datasourceName || '-'}
                      className="text-ds-small text-ds-text-secondary">{v || '-'}</span>
            ),
        },
        {
            title: '采集范围',
            dataIndex: 'scope',
            ellipsis: true,
            render: (_, item) => (
                <span title={formatScope(item.scope)}
                      className="text-ds-body text-ds-text-secondary">{formatScope(item.scope)}</span>
            ),
        },
        {
            title: '触发方式',
            dataIndex: 'triggerType',
            render: (_, item) => triggerBadge(item.triggerType),
        },
        {
            title: 'Cron 表达式',
            dataIndex: 'cronExpression',
            className: 'text-ds-small text-ds-text-secondary font-mono',
            render: (_, item) => (item.triggerType === 'CRON' && item.cronExpression ? item.cronExpression : '—'),
        },
        {
            title: '调度状态',
            render: (_, item) => scheduleStatusBadge(item),
        },
        {
            title: '采集模式',
            dataIndex: 'collectMode',
            render: (_, item) => collectModeBadge(item.collectMode),
        },
        {
            title: '下次执行时间',
            className: 'text-ds-small text-ds-text-secondary',
            render: (_, item) => computeNextExecutionTime(item),
        },
        {
            title: '状态',
            dataIndex: 'status',
            render: (_, item) => (
                <DsStatusBadge label={statusLabel(item.status)} variant={executionStatusVariant(item.status)}/>
            ),
        },
        {
            title: '最近执行',
            dataIndex: 'lastExecuteTime',
            className: 'text-ds-small text-ds-text-secondary',
            render: (_, item) => (
                <span
                    title={item.lastExecuteTime ? new Date(item.lastExecuteTime).toLocaleString('zh-CN') : ''}>
                    {formatRelativeTime(item.lastExecuteTime)}
                </span>
            ),
        },
        {
            title: '操作',
            align: 'center',
            render: (_, item) => (
                <div className="flex items-center justify-center w-full gap-1">
                    <Tooltip title="历史记录">
                        <DsIconButton
                            tone="accent"
                            data-testid={`collect-task-history-${item.name}`}
                            onClick={() => navigate(`/governance/collect-task-history?taskId=${item.id}&taskName=${encodeURIComponent(item.name || '')}`)}
                            aria-label="历史记录"
                        >
                            <HiOutlineClock size={16}/>
                        </DsIconButton>
                    </Tooltip>
                    {canWrite && (
                        <>
                            {item.triggerType === 'CRON' && (
                                <Tooltip title={item.scheduleEnabled === 1 ? '关闭调度' : '开启调度'}>
                                    <DsIconButton
                                        tone="success"
                                        active={item.scheduleEnabled === 1}
                                        data-testid={`collect-task-schedule-${item.name}`}
                                        onClick={() => handleToggleSchedule(item)}
                                        disabled={schedulingId === item.id}
                                        className="disabled:opacity-60"
                                        aria-label={item.scheduleEnabled === 1 ? '关闭调度' : '开启调度'}
                                    >
                                        <HiOutlineCalendar size={16}/>
                                    </DsIconButton>
                                </Tooltip>
                            )}
                            <Tooltip title="立即执行">
                                <DsIconButton
                                    tone="success"
                                    data-testid={`collect-task-execute-${item.name}`}
                                    onClick={() => handleExecute(item)}
                                    disabled={executingId === item.id}
                                    className="disabled:opacity-60"
                                    aria-label="立即执行"
                                >
                                    <HiOutlinePlay size={16}/>
                                </DsIconButton>
                            </Tooltip>
                            <Tooltip title="编辑">
                                <DsIconButton
                                    tone="accent"
                                    data-testid={`collect-task-edit-${item.name}`}
                                    onClick={() => openEdit(item)}
                                    aria-label="编辑"
                                >
                                    <HiOutlinePencilSquare size={16}/>
                                </DsIconButton>
                            </Tooltip>
                            <Tooltip title="删除">
                                <DsIconButton
                                    tone="danger"
                                    data-testid={`collect-task-delete-${item.name}`}
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
    ], [canWrite, executingId, schedulingId, navigate]);

    return (
        <div className="h-full flex flex-col overflow-hidden">
            <div className="flex items-center justify-between mb-ds-5 flex-shrink-0">
                <div>
                    <h1 className="text-ds-display text-ds-text-primary">元数据采集任务</h1>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1">管理数据源元数据采集任务，支持手动与 Cron
                        定时触发</p>
                </div>
                {canWrite && (
                    <DsButton
                        data-testid="collect-task-create"
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
                    extra={(
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
                    )}
                >
                    <SearchInput
                        value={draftKeyword}
                        onChange={(e) => setDraftKeyword(e.target.value)}
                        onEnter={handleSearch}
                        placeholder="搜索任务名称..."
                    />

                    <DsFilterSelect
                        value={draftStatus}
                        onChange={(v) => setDraftStatus(v as TaskStatus | '')}
                        options={STATUS_OPTIONS}
                        aria-label="按状态筛选"
                    />
                </DsToolbar>
            </div>

            <div className="flex-1 min-h-0 flex flex-col">
                <div
                    className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden min-h-0 flex flex-col mb-ds-8">
                    <div className="flex-1 overflow-auto">
                        <Table<CollectTask>
                            dataSource={list}
                            rowKey="id"
                            loading={loading}
                            pagination={false}
                            columns={columns}
                            className="prototype-table prototype-table-flush"
                            onRow={(item) =>
                                ({
                                    'data-testid': `collect-task-row-${item.name}`,
                                    'data-task-id': item.id,
                                }) as unknown as HTMLAttributes<HTMLElement>
                            }
                            locale={{
                                emptyText: (
                                    <DsTableEmpty
                                        description={
                                            <div>
                                                <div>暂无采集任务</div>
                                                <div className="text-ds-small text-ds-text-muted mt-ds-1">
                                                    还没有元数据采集任务，创建第一个任务开始自动采集数据源表结构。
                                                </div>
                                            </div>
                                        }
                                        action={canWrite && (
                                            <DsButton
                                                onClick={openCreate}
                                            >
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
