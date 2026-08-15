import {useEffect, useRef, useState} from 'react';
import {Input, Table} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import {
    createExecutionQueue,
    deleteExecutionQueue,
    pageQueueDags,
    queryExecutionQueues,
    updateExecutionQueue,
} from '@/pages/engineering/dags/api';
import type {ExecutionQueue} from '@/pages/engineering/dags/api';
import type {Dag} from '@/pages/engineering/dags/types';
import {notify} from '@/utils/notify';
import {formatDateTime} from '@/utils/format';
import {executionStatusVariant} from '@/utils/status';
import DsButton from '@/components/DsButton';
import DsIconButton from '@/components/DsIconButton';
import DsModal from '@/components/DsModal';
import DsFilterSelect from '@/components/DsFilterSelect';
import Drawer from '@/components/Drawer';
import Pagination from '@/components/Pagination';
import ConfirmDialog from '@/components/ConfirmDialog';
import DsTableEmpty from '@/components/DsTableEmpty';
import DsStatusBadge from '@/components/DsStatusBadge';
import {COL} from '@/constants/table';
import usePagedList from '@/hooks/usePagedList';
import {HiOutlinePencilSquare, HiOutlinePlus, HiOutlineTrash} from 'react-icons/hi2';

/** 队列详情抽屉：DAG 状态筛选 */
const DETAIL_STATUS_OPTIONS = [
    {value: '', label: '全部状态'},
    {value: 'ENABLED', label: '启用'},
    {value: 'DISABLED', label: '停用'},
];

/** 队列详情抽屉：优先级筛选 */
const DETAIL_PRIORITY_OPTIONS = [
    {value: '', label: '全部优先级'},
    {value: '3', label: '高'},
    {value: '2', label: '中'},
    {value: '1', label: '低'},
];

/** 队列详情抽屉：触发方式筛选 */
const DETAIL_TRIGGER_OPTIONS = [
    {value: '', label: '全部触发方式'},
    {value: 'MANUAL', label: '手动触发'},
    {value: 'CRON', label: '定时触发'},
];

const POLL_INTERVAL_MS = 5000;

interface QueueListQuery {
    keyword: string;
}

const INITIAL_QUERY: QueueListQuery = {keyword: ''};

export default function ExecutionQueuesPage() {
    const {
        list, total, page, pageSize, loading,
        setPage, setPageSize, applyQuery, reload,
    } = usePagedList<QueueListQuery, ExecutionQueue>({
        fetcher: async (q) => {
            const result = await queryExecutionQueues({
                keyword: q.keyword || undefined,
                page: q.page,
                pageSize: q.pageSize,
            });
            return {list: result.data.records, total: result.data.total};
        },
        initialQuery: INITIAL_QUERY,
        defaultPageSize: 10,
    });

    // 5s 轮询（PRD B6 队列运行/等待数实时可见）
    const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);
    useEffect(() => {
        pollRef.current = setInterval(() => reload(), POLL_INTERVAL_MS);
        return () => {
            if (pollRef.current) clearInterval(pollRef.current);
        };
    }, [reload]);

    const [draftKeyword, setDraftKeyword] = useState('');
    const handleSearch = () => applyQuery({keyword: draftKeyword.trim()});
    const handleReset = () => {
        setDraftKeyword('');
        applyQuery(INITIAL_QUERY);
    };

    // 弹窗状态
    const [modalOpen, setModalOpen] = useState(false);
    const [modalMode, setModalMode] = useState<'create' | 'edit'>('create');
    const [editQueue, setEditQueue] = useState<ExecutionQueue | null>(null);
    const [formName, setFormName] = useState('');
    const [formConcurrency, setFormConcurrency] = useState(10);
    const [formDesc, setFormDesc] = useState('');
    const [submitting, setSubmitting] = useState(false);

    // 队列详情抽屉（绑定 DAG 列表，分页 + 多条件筛选）
    const [drawerOpen, setDrawerOpen] = useState(false);
    const [detailQueue, setDetailQueue] = useState<ExecutionQueue | null>(null);
    const [draftDetailKeyword, setDraftDetailKeyword] = useState('');
    const [draftDetailStatus, setDraftDetailStatus] = useState('');
    const [draftDetailPriority, setDraftDetailPriority] = useState('');
    const [draftDetailTrigger, setDraftDetailTrigger] = useState('');

    const {
        list: detailDags,
        total: detailTotal,
        page: detailPage,
        pageSize: detailPageSize,
        loading: detailLoading,
        setPage: setDetailPage,
        setPageSize: setDetailPageSize,
        applyQuery: applyDetailQuery,
    } = usePagedList<{queueName: string; keyword: string; status: string; priority: string; triggerType: string}, Dag>({
        fetcher: async (q) => {
            if (!q.queueName) return {list: [], total: 0};
            const result = await pageQueueDags({
                queueName: q.queueName,
                keyword: q.keyword || undefined,
                status: q.status || undefined,
                priority: q.priority ? Number(q.priority) : undefined,
                triggerType: q.triggerType || undefined,
                page: q.page,
                pageSize: q.pageSize,
            });
            return {list: result.data.records, total: result.data.total};
        },
        initialQuery: {queueName: '', keyword: '', status: '', priority: '', triggerType: ''},
        defaultPageSize: 10,
    });

    const openDetail = (q: ExecutionQueue) => {
        setDetailQueue(q);
        setDraftDetailKeyword('');
        setDraftDetailStatus('');
        setDraftDetailPriority('');
        setDraftDetailTrigger('');
        setDrawerOpen(true);
        applyDetailQuery({queueName: q.queueName, keyword: '', status: '', priority: '', triggerType: ''});
    };

    const handleDetailSearch = () => {
        if (!detailQueue) return;
        applyDetailQuery({
            queueName: detailQueue.queueName,
            keyword: draftDetailKeyword.trim(),
            status: draftDetailStatus,
            priority: draftDetailPriority,
            triggerType: draftDetailTrigger,
        });
    };

    const handleDetailReset = () => {
        setDraftDetailKeyword('');
        setDraftDetailStatus('');
        setDraftDetailPriority('');
        setDraftDetailTrigger('');
        if (detailQueue) applyDetailQuery({queueName: detailQueue.queueName, keyword: '', status: '', priority: '', triggerType: ''});
    };

    // 删除确认
    const [confirmOpen, setConfirmOpen] = useState(false);
    const [confirmTarget, setConfirmTarget] = useState<ExecutionQueue | null>(null);

    const openCreate = () => {
        setModalMode('create');
        setEditQueue(null);
        setFormName('');
        setFormConcurrency(10);
        setFormDesc('');
        setModalOpen(true);
    };

    const openEdit = (q: ExecutionQueue) => {
        setModalMode('edit');
        setEditQueue(q);
        setFormName(q.queueName);
        setFormConcurrency(q.maxConcurrency);
        setFormDesc(q.description ?? '');
        setModalOpen(true);
    };

    const submit = async () => {
        const name = formName.trim();
        if (modalMode === 'create' && !/^[A-Za-z0-9_]{2,32}$/.test(name)) {
            notify.error('队列名仅限字母/数字/下划线，2~32 位');
            return;
        }
        if (!formConcurrency || formConcurrency < 1 || formConcurrency > 100) {
            notify.error('最大并发数需在 1~100 之间');
            return;
        }
        setSubmitting(true);
        try {
            if (modalMode === 'create') {
                await createExecutionQueue({queueName: name, maxConcurrency: formConcurrency, description: formDesc.trim()});
                notify.success('队列创建成功');
            } else if (editQueue) {
                // 系统内置队列名称不可改，只提交并发/描述
                await updateExecutionQueue(editQueue.id, {
                    queueName: editQueue.isSystem ? editQueue.queueName : name,
                    maxConcurrency: formConcurrency,
                    description: formDesc.trim(),
                });
                notify.success('队列已更新');
            }
            setModalOpen(false);
            reload();
        } finally {
            setSubmitting(false);
        }
    };

    const confirmDelete = (q: ExecutionQueue) => {
        setConfirmTarget(q);
        setConfirmOpen(true);
    };

    const doDelete = async () => {
        if (!confirmTarget) return;
        try {
            await deleteExecutionQueue(confirmTarget.id);
            notify.success('队列已删除');
            setConfirmOpen(false);
            reload();
        } finally {
            setConfirmTarget(null);
        }
    };

    const detailColumns: ColumnsType<Dag> = [
        {
            title: 'DAG',
            key: 'name',
            ellipsis: true,
            render: (_, d) => (
                <span className="text-ds-small text-ds-text-primary truncate">
                    {d.projectName ? `${d.projectName} - ${d.name}` : d.name}
                </span>
            ),
        },
        {
            title: '状态',
            dataIndex: 'status',
            width: 90,
            render: (s?: string) => (
                <DsStatusBadge
                    label={s === 'ENABLED' ? '启用' : '停用'}
                    variant={s === 'ENABLED' ? 'success' : 'disabled'}
                />
            ),
        },
        {
            title: '优先级',
            dataIndex: 'priority',
            width: 80,
            render: (p?: number) => p != null ? (
                <span className={`text-ds-small tabular-nums ${priorityCellClass(p)}`}>
                    {p}
                </span>
            ) : <span className="text-ds-small text-ds-text-muted">-</span>,
        },
        {
            title: '触发方式',
            dataIndex: 'triggerType',
            width: 100,
            render: (t?: string) => (
                <span className="text-ds-small text-ds-text-secondary">
                    {t === 'CRON' ? '定时' : '手动'}
                </span>
            ),
        },
        {
            title: '最近执行',
            key: 'latestExecution',
            width: 170,
            render: (_, d) => {
                const last = d.latestExecution;
                if (!last) return <span className="text-ds-small text-ds-text-muted">未执行</span>;
                return (
                    <div className="flex flex-col gap-ds-0.5">
                        <DsStatusBadge
                            label={EXEC_STATUS_LABEL[last.status] || last.status}
                            variant={executionStatusVariant(last.status)}
                        />
                        {last.startTime && (
                            <span className="text-ds-caption text-ds-text-muted whitespace-nowrap">
                                {formatDateTime(last.startTime)}
                            </span>
                        )}
                    </div>
                );
            },
        },
        {
            title: '7天执行',
            dataIndex: 'executionCount7d',
            width: 90,
            render: (v?: number) => (
                <span className="text-ds-small text-ds-text-secondary tabular-nums">
                    {v ?? 0}
                </span>
            ),
        },
    ];

    const columns: ColumnsType<ExecutionQueue> = [
        {
            title: '队列名',
            dataIndex: 'queueName',
            width: 180,
            render: (v: string, r) => (
                <div className="flex items-center gap-ds-2">
                    <span className="text-ds-small text-ds-text-primary font-mono font-medium">{v}</span>
                    {r.isSystem && <DsStatusBadge label="内置" variant="accent"/>}
                </div>
            ),
        },
        {
            title: '最大并发',
            dataIndex: 'maxConcurrency',
            width: 100,
            render: (v: number) => <span className="text-ds-small tabular-nums">{v}</span>,
        },
        {
            title: '当前运行',
            dataIndex: 'runningCount',
            width: 100,
            render: (v?: number) => (
                <span className="text-ds-small text-ds-accent font-medium tabular-nums">{v ?? 0}</span>
            ),
        },
        {
            title: '等待任务',
            dataIndex: 'waitingCount',
            width: 100,
            render: (v?: number) => (
                <span className="text-ds-small text-ds-warning font-medium tabular-nums">{v ?? 0}</span>
            ),
        },
        {
            title: '绑定 DAG',
            dataIndex: 'dagCount',
            width: 110,
            render: (v, r) => (
                <button
                    type="button"
                    disabled={!v}
                    onClick={() => openDetail(r)}
                    className="text-ds-small font-medium text-ds-accent hover:text-ds-accent-hover disabled:text-ds-text-muted disabled:cursor-default tabular-nums"
                    aria-label={`查看队列 ${r.queueName} 绑定的 DAG`}
                >
                    {v ?? 0}
                </button>
            ),
        },
        {
            title: '描述',
            dataIndex: 'description',
            ellipsis: true,
            render: (v?: string) => <span className="text-ds-small text-ds-text-muted">{v || '-'}</span>,
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
            width: 100,
            fixed: 'right',
            render: (_, r) => (
                <div className="flex items-center gap-ds-1">
                    <DsIconButton onClick={() => openEdit(r)} aria-label="编辑队列">
                        <HiOutlinePencilSquare size={14}/>
                    </DsIconButton>
                    {!r.isSystem && (
                        <DsIconButton tone="danger" onClick={() => confirmDelete(r)} aria-label="删除队列">
                            <HiOutlineTrash size={14}/>
                        </DsIconButton>
                    )}
                </div>
            ),
        },
    ];

    return (
        <div className="flex flex-col">
            {/* 顶部：标题 + 描述 + 新建 */}
            <div className="flex items-start justify-between mb-ds-5 flex-shrink-0">
                <div>
                    <h1 className="text-ds-display text-ds-text-primary">执行队列</h1>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1">
                        控制 DAG 并发执行的资源队列；队列满时任务按优先级排队等待
                    </p>
                </div>
                <DsButton onClick={openCreate} className="mt-ds-1">
                    <HiOutlinePlus size={16}/>
                    新建队列
                </DsButton>
            </div>

            {/* 工具栏：搜索（与 sync-jobs 一致） */}
            <div className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle p-ds-3 mb-ds-4 flex-shrink-0">
                <div className="flex items-center gap-ds-2">
                    <Input
                        value={draftKeyword}
                        onChange={(e) => setDraftKeyword(e.target.value)}
                        onPressEnter={handleSearch}
                        placeholder="搜索队列名/描述..."
                        allowClear
                        className="max-w-[280px]"
                    />
                    <DsButton onClick={handleSearch} disabled={loading} loading={loading}>查询</DsButton>
                    <DsButton variant="secondary" onClick={handleReset} disabled={loading}>重置</DsButton>
                </div>
            </div>

            {/* 列表 + 分页（结构与 sync-jobs 一致：卡片内容自适应高度，不顶满） */}
            <div className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden flex flex-col">
                <div className="overflow-x-auto">
                    <Table<ExecutionQueue>
                        rowKey="id"
                        columns={columns}
                        dataSource={list}
                        loading={loading}
                        pagination={false}
                        scroll={{x: 1380}}
                        size="middle"
                        className="prototype-table prototype-table-flush"
                        locale={{emptyText: <DsTableEmpty description="暂无执行队列"/>}}
                    />
                </div>
                <Pagination
                    page={page}
                    pageSize={pageSize}
                    total={total}
                    onChange={(nextPage, nextPageSize) => {
                        if (nextPageSize !== pageSize) {
                            setPageSize(nextPageSize);
                        } else {
                            setPage(nextPage);
                        }
                    }}
                />
            </div>

            {/* 创建/编辑弹窗 */}
            <DsModal
                open={modalOpen}
                onClose={() => setModalOpen(false)}
                title={modalMode === 'create' ? '新建执行队列' : '编辑执行队列'}
                width="w-[460px]"
                footer={
                    <>
                        <DsButton variant="ghost" onClick={() => setModalOpen(false)} disabled={submitting}>取消</DsButton>
                        <DsButton onClick={submit} loading={submitting}>保存</DsButton>
                    </>
                }
            >
                <div className="space-y-ds-4">
                    <div>
                        <label className="block text-ds-small font-medium text-ds-text-primary mb-ds-1">
                            队列名 <span className="text-ds-danger">*</span>
                        </label>
                        <Input
                            value={formName}
                            disabled={modalMode === 'edit' && editQueue?.isSystem}
                            onChange={(e) => setFormName(e.target.value)}
                            placeholder="字母/数字/下划线，2~32 位"
                            maxLength={32}
                        />
                        {modalMode === 'edit' && editQueue?.isSystem && (
                            <div className="text-ds-nano text-ds-text-muted mt-ds-1">系统内置队列名称不可修改</div>
                        )}
                    </div>
                    <div>
                        <label className="block text-ds-small font-medium text-ds-text-primary mb-ds-1">
                            最大并发数 <span className="text-ds-danger">*</span>
                        </label>
                        <Input
                            type="number"
                            value={formConcurrency}
                            min={1}
                            max={100}
                            onChange={(e) => setFormConcurrency(Number(e.target.value))}
                            placeholder="1~100"
                        />
                        <div className="text-ds-nano text-ds-text-muted mt-ds-1">
                            该队列同时 RUNNING 的执行实例上限，满后 DAG 进入等待队列
                        </div>
                    </div>
                    <div>
                        <label className="block text-ds-small font-medium text-ds-text-primary mb-ds-1">描述</label>
                        <Input.TextArea
                            value={formDesc}
                            onChange={(e) => setFormDesc(e.target.value)}
                            placeholder="队列用途说明"
                            maxLength={256}
                            rows={2}
                        />
                    </div>
                </div>
            </DsModal>

            {/* 删除确认 */}
            <ConfirmDialog
                open={confirmOpen}
                title="删除执行队列"
                message={
                    confirmTarget
                        ? `确定删除队列「${confirmTarget.queueName}」吗？有 DAG 绑定或运行中任务的队列不可删除。`
                        : ''
                }
                danger
                confirmLabel="删除"
                onCancel={() => setConfirmOpen(false)}
                onConfirm={doDelete}
            />

            {/* 队列详情抽屉：绑定 DAG 列表 */}
            <Drawer
                open={drawerOpen}
                onClose={() => setDrawerOpen(false)}
                title={detailQueue ? `队列「${detailQueue.queueName}」绑定的 DAG` : '队列详情'}
                width="max-w-[840px]"
            >
                <div className="flex flex-col h-full">
                    {/* 头部摘要条：单行 4 指标 + 运行率进度条（一眼看出队列压力） */}
                    {detailQueue && (
                        <div className="rounded-ds-md border border-ds-border-subtle p-ds-4 mb-ds-4">
                            <div className="flex items-center justify-between mb-ds-3">
                                <span className="text-ds-caption text-ds-text-muted">
                                    {detailQueue.queueName}
                                </span>
                                <span className="text-ds-caption text-ds-text-muted">
                                    运行率 {ratePct(detailQueue)}%
                                </span>
                            </div>
                            <div className="h-ds-1.5 bg-ds-bg-subtle rounded-full overflow-hidden mb-ds-3">
                                <div
                                    className="h-full bg-ds-accent rounded-full transition-all duration-500"
                                    style={{width: `${Math.min(ratePct(detailQueue), 100)}%`}}
                                />
                            </div>
                            <div className="grid grid-cols-4 gap-ds-2">
                                <div>
                                    <div className="text-ds-caption text-ds-text-muted">最大并发</div>
                                    <div className="text-ds-title font-medium text-ds-text-primary tabular-nums mt-ds-0.5">
                                        {detailQueue.maxConcurrency}
                                    </div>
                                </div>
                                <div>
                                    <div className="text-ds-caption text-ds-text-muted">当前运行</div>
                                    <div className="text-ds-title font-medium text-ds-accent tabular-nums mt-ds-0.5">
                                        {detailQueue.runningCount ?? 0}
                                    </div>
                                </div>
                                <div>
                                    <div className="text-ds-caption text-ds-text-muted">等待任务</div>
                                    <div className="text-ds-title font-medium text-ds-warning tabular-nums mt-ds-0.5">
                                        {detailQueue.waitingCount ?? 0}
                                    </div>
                                </div>
                                <div>
                                    <div className="text-ds-caption text-ds-text-muted">绑定 DAG</div>
                                    <div className="text-ds-title font-medium text-ds-text-primary tabular-nums mt-ds-0.5">
                                        {detailTotal}
                                    </div>
                                </div>
                            </div>
                        </div>
                    )}

                    {/* 筛选栏：搜索 + 状态/优先级/触发方式 */}
                    <div className="flex items-center gap-ds-2 mb-ds-3">
                        <Input
                            value={draftDetailKeyword}
                            onChange={(e) => setDraftDetailKeyword(e.target.value)}
                            onPressEnter={handleDetailSearch}
                            placeholder="搜索 DAG 名/项目名..."
                            allowClear
                            className="max-w-[240px]"
                        />
                        <DsFilterSelect
                            value={draftDetailStatus}
                            onChange={setDraftDetailStatus}
                            options={DETAIL_STATUS_OPTIONS}
                            aria-label="按状态筛选"
                        />
                        <DsFilterSelect
                            value={draftDetailPriority}
                            onChange={setDraftDetailPriority}
                            options={DETAIL_PRIORITY_OPTIONS}
                            aria-label="按优先级筛选"
                        />
                        <DsFilterSelect
                            value={draftDetailTrigger}
                            onChange={setDraftDetailTrigger}
                            options={DETAIL_TRIGGER_OPTIONS}
                            aria-label="按触发方式筛选"
                        />
                        <DsButton onClick={handleDetailSearch} disabled={detailLoading} loading={detailLoading}>查询</DsButton>
                        <DsButton variant="secondary" onClick={handleDetailReset} disabled={detailLoading}>重置</DsButton>
                    </div>

                    <div className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden flex flex-col">
                        <div className="overflow-x-auto">
                            <Table<Dag>
                                rowKey="id"
                                columns={detailColumns}
                                dataSource={detailDags}
                                loading={detailLoading}
                                pagination={false}
                                size="middle"
                                className="prototype-table prototype-table-flush"
                                locale={{emptyText: <DsTableEmpty description="该队列暂未绑定 DAG"/>}}
                            />
                        </div>
                        {detailTotal > 0 && (
                            <Pagination
                                page={detailPage}
                                pageSize={detailPageSize}
                                total={detailTotal}
                                onChange={(nextPage, nextPageSize) => {
                                    if (nextPageSize !== detailPageSize) {
                                        setDetailPageSize(nextPageSize);
                                    } else {
                                        setDetailPage(nextPage);
                                    }
                                }}
                            />
                        )}
                    </div>
                </div>
            </Drawer>
        </div>
    );
}

// ===== 抽屉辅助（DAG 列表展示） =====

/** 执行实例状态文案（与 dag-executions 页面一致） */
const EXEC_STATUS_LABEL: Record<string, string> = {
    RUNNING: '运行中',
    WAITING: '排队中',
    SUCCESS: '成功',
    FAILED: '失败',
    TERMINATED: '已终止',
    SKIPPED: '已跳过',
};

/** 优先级文字颜色（1=低灰 / 2=中 / 3=高橙） */
function priorityCellClass(p: number): string {
    if (p === 1) return 'text-ds-text-muted';
    if (p === 3) return 'text-ds-warning font-medium';
    return 'text-ds-text-secondary';
}

/** 队列运行率（当前运行/最大并发，%）。返回整数百分比，避免除以零。 */
function ratePct(q: ExecutionQueue): number {
    const cap = q.maxConcurrency ?? 1;
    const running = q.runningCount ?? 0;
    return Math.round((running / cap) * 100);
}