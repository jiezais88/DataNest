import type {HTMLAttributes} from 'react';
import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {useNavigate, useSearchParams} from 'react-router-dom';
import {Table, Tooltip} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import {nextRunTime} from '@/utils/cron';
import {notify} from '@/utils/notify';
import {useHasRole} from '@/hooks/useHasRole';
import {ALERT_WRITE_ROLES, GOVERNANCE_WRITE_ROLES} from '@/constants/roles';
import {COL} from '@/constants/table';
import {NODE_STATUS_COLOR} from '@/constants/statusColors';
import StatusSpine from '@/components/StatusSpine';
import {getDataSources} from '@/api/datasource';
import {
    createCollectTask,
    deleteCollectTask,
    executeCollectTask,
    queryCollectTasks,
    startCollectTaskSchedule,
    stopCollectTaskSchedule,
    updateCollectTask,
} from '@/api/collect';
import type {CollectTask, CollectTaskCreateRequest, CollectTaskQueryParams, TaskStatus} from '@/types/collect';
import type {DataSource} from '@/types/datasource';
import {formatDateTime, formatRelativeTime} from '@/utils/format';
import usePagedList from '@/hooks/usePagedList';
import Pagination from '@/components/Pagination';
import DsButton from '@/components/DsButton';
import DsIconButton from '@/components/DsIconButton';
import DsStatusBadge from '@/components/DsStatusBadge';
import TriggerBadge from '@/components/TriggerBadge';
import DsTableEmpty from '@/components/DsTableEmpty';
import {executionStatusVariant} from '@/utils/status';
import ConfirmDialog from '@/components/ConfirmDialog';
import SearchInput from '@/components/SearchInput';
import DsFilterSelect from '@/components/DsFilterSelect';
import DsToolbar from '@/components/DsToolbar';
import TaskDrawer from './TaskDrawer';
import AlertRuleModal from '@/components/AlertRuleModal';
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
    // 采集任务快捷告警：查看 = 超管/工程师/治理员，编辑 = 超管/工程师（PRD §8）
    const canWriteAlert = useHasRole(...ALERT_WRITE_ROLES);

    const {list, total, page, pageSize, loading, query, setPage, setPageSize, applyQuery, reload} =
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
// L2：进页时从 URL 初始化筛选，进入子页/返回后筛选不丢
    const [searchParams, setSearchParams] = useSearchParams();
    const urlInitRef = useRef(false);
    useEffect(() => {
        if (urlInitRef.current) return;
        urlInitRef.current = true;
        const p = searchParams;
        const keyword = p.get('keyword') || '';
        const status = STATUS_OPTIONS.some(o => o.value === p.get('status'))
            ? p.get('status') as TaskStatus | ''
            : '';
        const pageNum = Number(p.get('page')) || 1;
        const pageSizeNum = Number(p.get('pageSize')) || 10;
        setDraftKeyword(keyword);
        if (pageSizeNum !== 10) setPageSize(pageSizeNum);
        applyQuery({keyword, status});
        if (pageNum > 1) setPage(pageNum);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    // L2：筛选/分页变化时同步到 URL
    useEffect(() => {
        const next = new URLSearchParams();
        if (query.keyword) next.set('keyword', query.keyword);
        if (query.status) next.set('status', query.status);
        next.set('page', String(page));
        if (pageSize !== 10) next.set('pageSize', String(pageSize));
        if (next.toString() === searchParams.toString()) return;
        setSearchParams(next, {replace: true});
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [query, page, pageSize]);

    const [draftKeyword, setDraftKeyword] = useState('');

    const [drawerOpen, setDrawerOpen] = useState(false);
    const [editItem, setEditItem] = useState<CollectTask | null>(null);
    const [drawerMode, setDrawerMode] = useState<'create' | 'edit' | 'view'>('create');
    const [dataSources, setDataSources] = useState<DataSource[]>([]);
    const [executingId, setExecutingId] = useState<string | null>(null);
    const [schedulingId, setSchedulingId] = useState<string | null>(null);

    const [deleteTarget, setDeleteTarget] = useState<CollectTask | null>(null);
    const [deleteOpen, setDeleteOpen] = useState(false);
    const [deleteLoading, setDeleteLoading] = useState(false);

    // Sprint 5：快捷告警配置
    const [alertTarget, setAlertTarget] = useState<CollectTask | null>(null);
    const [alertOpen, setAlertOpen] = useState(false);

    const loadDataSources = useCallback(async () => {
        try {
            const result = await getDataSources({page: 1, pageSize: 1000});
            setDataSources(result.data.records.filter((ds) => ds.status === 'NORMAL'));
        } catch {
            // ignored
        }
    }, []);

    const openCreate = () => {
        setEditItem(null);
        setDrawerMode('create');
        loadDataSources();
        setDrawerOpen(true);
    };

    const openEdit = useCallback((item: CollectTask) => {
        setEditItem(item);
        setDrawerMode('edit');
        loadDataSources();
        setDrawerOpen(true);
    }, [loadDataSources]);

    const openView = useCallback((item: CollectTask) => {
        setEditItem(item);
        setDrawerMode('view');
        loadDataSources();
        setDrawerOpen(true);
    }, [loadDataSources]);

    const handleSubmit = async (payload: CollectTaskCreateRequest) => {
        const result = editItem
            ? await updateCollectTask(editItem.id, payload)
            : await createCollectTask(payload);
        notify.success(editItem ? '采集任务更新成功' : '采集任务创建成功');
        reload();
        return result;
    };

    const handleExecute = useCallback(async (item: CollectTask) => {
        setExecutingId(item.id);
        try {
            await executeCollectTask(item.id);
            notify.success(`采集任务 "${item.name}" 已触发执行`);
            reload();
        } finally {
            setExecutingId(null);
        }
    }, [reload]);

    const handleToggleSchedule = useCallback(async (item: CollectTask) => {
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
    }, [reload]);

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
        applyQuery({...query, keyword: draftKeyword});
    };

    const handleReset = () => {
        setDraftKeyword('');
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
                return '执行中';
            case 'FAILED':
                return '失败';
            case 'TERMINATED':
                return '已终止';
            case 'NEVER_EXECUTED':
                return '未执行';
            default:
                return '未知';
        }
    }

    const triggerBadge = (triggerType: string) => <TriggerBadge type={triggerType}/>;

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
            title: '',
            width: 12,
            render: (_, item) => {
                const status = item.status;
                const color = status === 'SUCCESS'
                    ? NODE_STATUS_COLOR.SUCCESS
                    : status === 'FAILED'
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
                <span title={v} className="text-ds-small text-ds-text-primary font-medium">{v}</span>
            ),
        },
        {
            title: '数据源',
            dataIndex: 'datasourceName',
            width: COL.NAME,
            ellipsis: true,
            render: (v: string | undefined, item) => (
                <span title={item.datasourceName || '-'}
                      className="text-ds-small text-ds-text-secondary">{v || '-'}</span>
            ),
        },
        {
            title: '采集范围',
            dataIndex: 'scope',
            width: 150,
            ellipsis: true,
            render: (_, item) => (
                <span title={formatScope(item.scope)}
                      className="text-ds-small text-ds-text-secondary">{formatScope(item.scope)}</span>
            ),
        },
        {
            title: '触发方式',
            dataIndex: 'triggerType',
            width: COL.TRIGGER_TYPE,
            render: (_, item) => triggerBadge(item.triggerType),
        },
        {
            title: 'Cron 表达式',
            dataIndex: 'cronExpression',
            width: COL.CRON,
            className: 'text-ds-small text-ds-text-secondary font-mono whitespace-nowrap',
            render: (_, item) => (item.triggerType === 'CRON' && item.cronExpression ? item.cronExpression : '—'),
        },
        {
            title: '调度状态',
            width: COL.STATUS,
            render: (_, item) => scheduleStatusBadge(item),
        },
        {
            title: '下次执行时间',
            width: COL.DATETIME,
            className: 'text-ds-small text-ds-text-secondary whitespace-nowrap',
            render: (_, item) => computeNextExecutionTime(item),
        },
        {
            title: '最近执行状态',
            dataIndex: 'status',
            width: COL.STATUS,
            render: (_, item) => (
                <DsStatusBadge label={statusLabel(item.status)} variant={executionStatusVariant(item.status)}/>
            ),
        },
        {
            title: '最近执行',
            dataIndex: 'lastExecuteTime',
            width: COL.DATETIME,
            className: 'text-ds-small text-ds-text-secondary',
            render: (_, item) => (
                <span
                    className="whitespace-nowrap"
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
                <span title={v || '—'} className="text-ds-small text-ds-text-secondary">{v || '—'}</span>
            ),
        },
        {
            title: '创建时间',
            dataIndex: 'createdAt',
            width: COL.DATETIME,
            render: (v?: string) => (
                <span
                    className="text-ds-small text-ds-text-secondary whitespace-nowrap">{v ? formatDateTime(v) : '—'}</span>
            ),
        },
        {
            title: '修改人',
            dataIndex: 'updatedByName',
            width: COL.USERNAME,
            ellipsis: true,
            render: (v?: string) => (
                <span title={v || '—'} className="text-ds-small text-ds-text-secondary">{v || '—'}</span>
            ),
        },
        {
            title: '修改时间',
            dataIndex: 'updatedAt',
            width: COL.DATETIME,
            render: (v?: string) => (
                <span
                    className="text-ds-small text-ds-text-secondary whitespace-nowrap">{v ? formatDateTime(v) : '—'}</span>
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
                            data-testid={`collect-task-alert-${item.name}`}
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
                            data-testid={`collect-task-view-${item.name}`}
                            onClick={() => openView(item)}
                            aria-label="详情"
                        >
                            <HiOutlineEye size={14}/>
                        </DsIconButton>
                    </Tooltip>
                    <Tooltip title="历史记录">
                        <DsIconButton
                            tone="accent"
                            data-testid={`collect-task-history-${item.name}`}
                            onClick={() => navigate(`/governance/collect-task-history?taskId=${item.id}&taskName=${encodeURIComponent(item.name || '')}`)}
                            aria-label="历史记录"
                        >
                            <HiOutlineClock size={14}/>
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
                                        <HiOutlineCalendar size={14}/>
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
                                    <HiOutlinePlay size={14}/>
                                </DsIconButton>
                            </Tooltip>
                            <Tooltip title="编辑">
                                <DsIconButton
                                    tone="accent"
                                    data-testid={`collect-task-edit-${item.name}`}
                                    onClick={() => openEdit(item)}
                                    aria-label="编辑"
                                >
                                    <HiOutlinePencilSquare size={14}/>
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
                        value={query.status}
                        onChange={(v) => applyQuery({...query, status: v as TaskStatus | ''})}
                        options={STATUS_OPTIONS}
                        aria-label="按状态筛选"
                    />
                </DsToolbar>
            </div>

            <div className="flex flex-col">
                <div
                    className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden flex flex-col">
                    <div className="overflow-x-auto">
                        <Table<CollectTask>
                            dataSource={list}
                            rowKey="id"
                            loading={loading}
                            pagination={false}
                            scroll={{x: 2210}}
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
                mode={drawerMode}
                editItem={editItem}
                dataSources={dataSources}
                onClose={() => {
                    setDrawerOpen(false);
                    setEditItem(null);
                }}
                onSubmit={handleSubmit}
            />

            {/* Sprint 5：采集任务告警配置快捷入口（同一 alert_rule 数据源） */}
            <AlertRuleModal
                open={alertOpen}
                onClose={() => {
                    setAlertOpen(false);
                    setAlertTarget(null);
                }}
                mode="quick"
                quickObjectType="COLLECT_TASK"
                quickObjectId={alertTarget?.id}
                quickObjectName={alertTarget?.name}
                readOnly={!canWriteAlert}
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
