import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {useNavigate, useSearchParams} from 'react-router-dom';
import {Empty, Modal, Table, Tooltip} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import {queryAllCollectHistory, stopCollectHistory} from '../../../../api/collect';
import type {CollectHistoryQueryParams, CollectTaskExecution, ExecutionStatus,} from '../../../../types/collect';
import CollectLogModal from './CollectLogModal';
import usePagedList from '../../../../hooks/usePagedList';
import Pagination from '../../../../components/Pagination';
import SearchInput from '../../../../components/SearchInput';
import DsButton from '../../../../components/DsButton';
import DsIconButton from '../../../../components/DsIconButton';
import DsModal from '../../../../components/DsModal';
import DsStatusBadge from '../../../../components/DsStatusBadge';
import DsRangePicker from '../../../../components/DsRangePicker';
import TriggerBadge from '../../../../components/TriggerBadge';
import DsFilterSelect from '../../../../components/DsFilterSelect';
import DsToolbar from '../../../../components/DsToolbar';
import {formatDateTime, formatDuration, formatExecutionDuration, getDefaultTimeRange} from '../../../../utils/format';
import {executionStatusVariant} from '../../../../utils/status';
import {useHasRole} from '../../../../hooks/useHasRole';
import {GOVERNANCE_WRITE_ROLES} from '../../../../constants/roles';
import {COL} from '../../../../constants/table';
import {notify} from '../../../../utils/notify';
import {HiChevronRight, HiOutlineDocumentText, HiOutlineEye, HiOutlineStop,} from 'react-icons/hi2';

const STATUS_OPTIONS: { value: ExecutionStatus | ''; label: string }[] = [
    {value: '', label: '全部状态'},
    {value: 'RUNNING', label: '执行中'},
    {value: 'SUCCESS', label: '成功'},
    {value: 'FAILED', label: '失败'},
    {value: 'TERMINATED', label: '已终止'},
];

const STATUS_LABELS: Record<ExecutionStatus, string> = {
    SUCCESS: '成功',
    RUNNING: '执行中',
    FAILED: '失败',
    TERMINATED: '已终止',
};

// 「已应用」查询条件：接口分页参数由 usePagedList 注入，页面只管业务条件
type HistoryQuery = Omit<CollectHistoryQueryParams, 'page' | 'pageSize'>;

function triggerBadge(triggerType: string) {
    return <TriggerBadge type={triggerType}/>;
}

export default function CollectHistoryGlobalPage() {
    const navigate = useNavigate();
    const canWrite = useHasRole(...GOVERNANCE_WRITE_ROLES);
    const [searchParams, setSearchParams] = useSearchParams();

    const defaultRange = getDefaultTimeRange();
    // 草稿查询条件（输入中、未点查询的值）：输入框与时间范围；状态下拉即时生效
    const [draftKeyword, setDraftKeyword] = useState('');
    const [draftStartTimeFrom, setDraftStartTimeFrom] = useState(defaultRange.from);
    const [draftStartTimeTo, setDraftStartTimeTo] = useState(defaultRange.to);

    const fetcher = useCallback(async (params: HistoryQuery & { page: number; pageSize: number }) => {
        const result = await queryAllCollectHistory(params);
        return {list: result.data.records, total: result.data.total};
    }, []);

    const {list, total, page, pageSize, loading, query, setPage, setPageSize, applyQuery, reload} =
        usePagedList<HistoryQuery, CollectTaskExecution>({
            fetcher,
            initialQuery: {startTimeFrom: defaultRange.from, startTimeTo: defaultRange.to},
            defaultPageSize: 10,
        });
    const urlTaskId = searchParams.get('taskId');
    const urlTaskName = searchParams.get('taskName') || '';
    // 从任务列表「历史」跳入：URL ?taskId=xxx&taskName=yyy → 精确过滤该任务
    useEffect(() => {
        if (!urlTaskId || query.taskId === urlTaskId) return;
        applyQuery({...query, taskId: urlTaskId});
    }, [urlTaskId, query, applyQuery]);

    const clearTaskIdUrl = useCallback(() => {
        if (searchParams.has('taskId')) {
            const next = new URLSearchParams(searchParams);
            next.delete('taskId');
            next.delete('taskName');
            setSearchParams(next, {replace: true});
        }
    }, [searchParams, setSearchParams]);

    // 清除 taskId 精确过滤（chip ×）：清 URL 参数并从已应用条件里去掉 taskId
    const clearTaskIdFilter = () => {
        clearTaskIdUrl();
        applyQuery({...query, taskId: undefined});
    };

    // L2：进页时从 URL 初始化筛选（状态/关键字/时间范围/分页），深层跳转返回后筛选不丢
    const urlInitRef = useRef(false);
    useEffect(() => {
        if (urlInitRef.current) return;
        urlInitRef.current = true;
        const p = searchParams;
        const hasTaskId = p.has('taskId');
        const urlStatus = p.get('status');
        const urlKeyword = p.get('keyword');
        const urlFrom = p.get('startTimeFrom');
        const urlTo = p.get('startTimeTo');
        const pageNum = Number(p.get('page')) || 1;
        const pageSizeNum = Number(p.get('pageSize')) || 10;
        const status = STATUS_OPTIONS.some(o => o.value === urlStatus) ? urlStatus as ExecutionStatus | undefined : undefined;
        const next: HistoryQuery = {
            ...(hasTaskId ? {taskId: p.get('taskId')!} : {}),
            status,
            keyword: urlKeyword || undefined,
            startTimeFrom: urlFrom || defaultRange.from,
            startTimeTo: urlTo || defaultRange.to,
        };
        setDraftKeyword(next.keyword || '');
        setDraftStartTimeFrom(next.startTimeFrom || defaultRange.from);
        setDraftStartTimeTo(next.startTimeTo || defaultRange.to);
        if (pageSizeNum !== 10) setPageSize(pageSizeNum);
        applyQuery(next);
        if (pageNum > 1) setPage(pageNum);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    // L2：筛选/分页变化时同步到 URL（replace 不产生多余历史记录），刷新/分享也能恢复
    useEffect(() => {
        const next = new URLSearchParams();
        if (query.taskId) {
            next.set('taskId', query.taskId);
            next.set('taskName', urlTaskName);
        }
        if (query.keyword) next.set('keyword', query.keyword);
        if (query.status) next.set('status', query.status);
        if (query.startTimeFrom) next.set('startTimeFrom', query.startTimeFrom);
        if (query.startTimeTo) next.set('startTimeTo', query.startTimeTo);
        next.set('page', String(page));
        if (pageSize !== 10) next.set('pageSize', String(pageSize));
        if (next.toString() === searchParams.toString()) return;
        setSearchParams(next, {replace: true});
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [query, page, pageSize]);

    const [selectedHistory, setSelectedHistory] = useState<CollectTaskExecution | null>(null);
    const [logOpen, setLogOpen] = useState(false);
    const [detailOpen, setDetailOpen] = useState(false);

    const handleSearch = () => {
        // 时间范围必填（与其他执行历史页一致）
        if (!draftStartTimeFrom || !draftStartTimeTo) {
            notify.warning('请选择执行时间范围');
            return;
        }
        // 从任务列表「历史」跳入时，精确过滤应随查询按钮保留（不要清除 taskId/taskName）
        const hasTaskId = !!query.taskId;
        applyQuery({
            ...(hasTaskId ? {taskId: query.taskId} : {}),
            status: query.status,
            keyword: draftKeyword || undefined,
            startTimeFrom: draftStartTimeFrom,
            startTimeTo: draftStartTimeTo,
        });
    };

    const handleReset = () => {
        const range = getDefaultTimeRange();
        clearTaskIdUrl();
        setDraftKeyword('');
        setDraftStartTimeFrom(range.from);
        setDraftStartTimeTo(range.to);
        applyQuery({
            taskId: undefined,
            status: undefined,
            keyword: undefined,
            startTimeFrom: range.from,
            startTimeTo: range.to,
        });
    };

    const handlePageChange = (nextPage: number, nextPageSize: number) => {
        if (nextPageSize !== pageSize) {
            setPageSize(nextPageSize);
        } else {
            setPage(nextPage);
        }
    };

    const handleOpenDetail = (item: CollectTaskExecution) => {
        setSelectedHistory(item);
        setDetailOpen(true);
    };

    const handleOpenLogs = (item: CollectTaskExecution) => {
        setSelectedHistory(item);
        setLogOpen(true);
    };

    // 手动停止运行中的执行实例（停止后状态归一为 TERMINATED）
    const handleStop = useCallback((item: CollectTaskExecution) => {
        Modal.confirm({
            centered: true,
            wrapClassName: 'prototype-modal',
            title: '停止执行',
            content: `确定停止任务「${item.taskName || item.taskId}」的本次执行吗？停止后状态将标记为「已终止」。`,
            okText: '停止',
            cancelText: '取消',
            onOk: async () => {
                try {
                    await stopCollectHistory(item.id);
                    notify.success('已发送停止指令，3s 后刷新列表');
                    setTimeout(reload, 3000);
                } catch {
                    // 错误提示由 request 拦截器统一弹出
                }
            },
        });
    }, [reload]);

    const columns = useMemo<ColumnsType<CollectTaskExecution>>(() => [
        {
            title: '任务名称',
            dataIndex: 'taskName',
            width: COL.NAME_COMPACT,
            ellipsis: {showTitle: true},
            render: (v?: string) => (
                <span className="text-ds-small text-ds-text-primary font-medium">{v || '-'}</span>
            ),
        },
        {
            title: '实例 ID',
            dataIndex: 'id',
            width: COL.ID,
            ellipsis: true,
            render: (v?: string) => (
                v ? (
                    <Tooltip title={v}>
                        <span
                            className="text-ds-small text-ds-text-secondary font-mono tabular-nums">{v}</span>
                    </Tooltip>
                ) : (
                    <span className="text-ds-small text-ds-text-muted">-</span>
                )
            ),
        },
        {
            title: '触发方式',
            dataIndex: 'triggerType',
            width: 90,
            render: (v: string) => triggerBadge(v),
        },
        {
            title: '状态',
            dataIndex: 'status',
            width: 90,
            render: (v: ExecutionStatus) => (
                <DsStatusBadge label={STATUS_LABELS[v]} variant={executionStatusVariant(v)}/>
            ),
        },
        {
            title: '开始时间',
            dataIndex: 'startedAt',
            width: COL.DATETIME_COMPACT,
            render: (v?: string) => (
                <span className="text-ds-small text-ds-text-secondary whitespace-nowrap">{formatDateTime(v)}</span>
            ),
        },
        {
            title: '结束时间',
            dataIndex: 'endedAt',
            width: COL.DATETIME_COMPACT,
            render: (v?: string) => (
                <span className="text-ds-small text-ds-text-secondary whitespace-nowrap">{formatDateTime(v)}</span>
            ),
        },
        {
            title: '耗时',
            dataIndex: 'durationMs',
            width: 90,
            ellipsis: true,
            // 运行中（endedAt 为空）：用当前时间静态计算一次，不做定时刷新
            // 超宽截断 + title 悬浮提示
            render: (v: number | undefined, item) => {
                const text = formatExecutionDuration(v, item.startedAt, item.endedAt);
                return <span title={text}
                             className="text-ds-small text-ds-text-secondary font-mono tabular-nums">{text}</span>;
            },
        },
        {
            title: '扫描库表字段',
            width: COL.COUNT,
            align: 'right',
            render: (_, item) => (
                <span className="text-ds-small text-ds-text-secondary whitespace-nowrap font-mono tabular-nums">
                    {item.dbCount ?? 0}/{item.tableCount ?? 0}/{item.columnCount ?? 0}
                </span>
            ),
        },
        {
            title: '是否变化',
            width: COL.STATUS,
            align: 'center',
            render: (_, item) => {
                const hasChange =
                    (item.addedTableCount ?? 0) + (item.updatedTableCount ?? 0) + (item.deletedTableCount ?? 0) +
                    (item.addedColumnCount ?? 0) + (item.updatedColumnCount ?? 0) + (item.deletedColumnCount ?? 0) > 0;
                return (
                    <DsStatusBadge
                        label={hasChange ? '有变化' : '无变化'}
                        variant={hasChange ? 'success' : 'disabled'}
                    />
                );
            },
        },
        {
            title: '错误信息',
            dataIndex: 'errorMessage',
            width: COL.ERROR_MESSAGE,
            ellipsis: {showTitle: true},
            render: (v?: string) => (
                <span className="text-ds-small text-ds-danger">{v || '—'}</span>
            ),
        },
        {
            title: '操作',
            align: 'center',
            width: COL.OPERATION_3,
            fixed: 'right' as const,
            render: (_, item) => (
                <div className="flex items-center justify-center gap-1 whitespace-nowrap">
                    {item.status === 'RUNNING' && (
                        <Tooltip title={canWrite ? '停止执行' : '只读模式：您没有编辑权限'}>
                            <DsIconButton
                                tone="danger"
                                disabled={!canWrite}
                                onClick={() => handleStop(item)}
                                aria-label="停止执行"
                            >
                                <HiOutlineStop size={14}/>
                            </DsIconButton>
                        </Tooltip>
                    )}
                    <Tooltip title="详情">
                        <DsIconButton
                            tone="accent"
                            onClick={() => handleOpenDetail(item)}
                            aria-label="详情"
                        >
                            <HiOutlineEye size={14}/>
                        </DsIconButton>
                    </Tooltip>
                    <Tooltip title="查看日志">
                        <DsIconButton
                            tone="accent"
                            onClick={() => handleOpenLogs(item)}
                            aria-label="查看日志"
                        >
                            <HiOutlineDocumentText size={14}/>
                        </DsIconButton>
                    </Tooltip>
                </div>
            ),
        },
    ], [canWrite, handleStop]);

    return (
        <div className="flex flex-col">
            <div className="flex items-center justify-between mb-ds-5 flex-shrink-0">
                <div>
                    <h1 className="text-ds-display text-ds-text-primary">采集执行历史</h1>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1">查看所有元数据采集任务的执行记录、统计与日志</p>
                </div>
                <DsButton
                    variant="secondary"
                    onClick={() => navigate('/governance/collect-tasks')}
                >
                    <HiChevronRight size={16} className="rotate-180"/>
                    返回任务列表
                </DsButton>
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
                    {query.taskId ? (
                        // 从任务列表「历史」跳入：按 taskId 精确过滤，名称框换成可清除的 chip
                        <span
                            className="inline-flex items-center gap-ds-2 px-ds-3 py-ds-2 bg-ds-accent-light text-ds-accent rounded-ds-sm text-ds-small font-semibold">
                            任务：{urlTaskName || query.taskId}
                            <button
                                onClick={clearTaskIdFilter}
                                className="hover:text-ds-accent-hover font-bold"
                                aria-label="清除任务过滤"
                                title="清除过滤，显示全部任务"
                            >
                                ×
                            </button>
                        </span>
                    ) : (
                        <SearchInput
                            value={draftKeyword}
                            onChange={(e) => setDraftKeyword(e.target.value)}
                            onEnter={handleSearch}
                            placeholder="搜索任务名称..."
                        />
                    )}

                    <DsFilterSelect
                        value={query.status ?? ''}
                        onChange={(v) => applyQuery({...query, status: v as ExecutionStatus | undefined})}
                        options={STATUS_OPTIONS}
                        aria-label="按状态筛选"
                    />
                    <DsRangePicker
                        from={draftStartTimeFrom}
                        to={draftStartTimeTo}
                        allowClear={false}
                        onChange={(from, to) => {
                            setDraftStartTimeFrom(from);
                            setDraftStartTimeTo(to);
                            applyQuery({...query, startTimeFrom: from, startTimeTo: to});
                        }}
                    />
                </DsToolbar>
            </div>

            <div className="flex flex-col">
                <div
                    className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden flex flex-col">
                    <div className="overflow-x-auto">
                        <Table<CollectTaskExecution>
                            dataSource={list}
                            rowKey="id"
                            loading={loading}
                            pagination={false}
                            scroll={{x: 1390}}
                            columns={columns}
                            className="prototype-table prototype-table-flush"
                            locale={{
                                emptyText: (
                                    <Empty
                                        image={Empty.PRESENTED_IMAGE_SIMPLE}
                                        description={
                                            <span>
                                                <span className="block">暂无执行历史</span>
                                                <span
                                                    className="block">还没有采集任务执行记录，手动触发或等待 Cron 调度后自动产生。</span>
                                            </span>
                                        }
                                    />
                                ),
                            }}
                        />
                    </div>

                    <Pagination page={page} pageSize={pageSize} total={total} onChange={handlePageChange}/>
                </div>
            </div>

            {detailOpen && selectedHistory && (
                <DsModal
                    open={detailOpen}
                    onClose={() => {
                        setDetailOpen(false);
                        setSelectedHistory(null);
                    }}
                    title="执行详情"
                    width="w-[520px]"
                    bordered
                    footer={
                        <>
                            <DsButton
                                variant="secondary"
                                onClick={() => {
                                    setDetailOpen(false);
                                    setSelectedHistory(null);
                                }}
                            >
                                关闭
                            </DsButton>
                            <DsButton
                                onClick={() => {
                                    setDetailOpen(false);
                                    handleOpenLogs(selectedHistory);
                                }}
                            >
                                查看日志
                            </DsButton>
                        </>
                    }
                >
                    <div className="space-y-ds-3">
                        <div className="grid grid-cols-[100px_1fr] gap-y-ds-2 text-ds-small">
                            {selectedHistory.id != null && (
                                <>
                                    <span className="text-ds-text-muted">实例 ID</span>
                                    <span
                                        className="text-ds-text-primary font-mono tabular-nums break-all">{selectedHistory.id}</span>
                                </>
                            )}
                                <span className="text-ds-text-muted">任务名称</span>
                                <span
                                    className="text-ds-text-primary font-medium">{selectedHistory.taskName || '-'}</span>

                                <span className="text-ds-text-muted">执行时间</span>
                                <span
                                    className="text-ds-text-primary font-medium">{formatDateTime(selectedHistory.startedAt)}</span>

                                <span className="text-ds-text-muted">执行方式</span>
                                <span
                                    className="text-ds-text-primary">{triggerBadge(selectedHistory.triggerType)}</span>

                                <span className="text-ds-text-muted">状态</span>
                                <span className="text-ds-text-primary">
                                    <DsStatusBadge
                                        label={STATUS_LABELS[selectedHistory.status]}
                                        variant={executionStatusVariant(selectedHistory.status)}
                                    />
                                </span>

                                <span className="text-ds-text-muted">耗时</span>
                                <span
                                    className="text-ds-text-primary">{formatDuration(selectedHistory.durationMs)}</span>

                                <span className="text-ds-text-muted">库数量</span>
                                <span className="text-ds-text-primary">{selectedHistory.dbCount ?? 0}</span>

                                <span className="text-ds-text-muted">表数量</span>
                                <span className="text-ds-text-primary">{selectedHistory.tableCount ?? 0}</span>

                                <span className="text-ds-text-muted">字段数量</span>
                                <span className="text-ds-text-primary">{selectedHistory.columnCount ?? 0}</span>

                                <span className="text-ds-text-muted">新增/变更/删除表</span>
                                <span
                                    className="text-ds-text-primary">{selectedHistory.addedTableCount ?? 0}/{selectedHistory.updatedTableCount ?? 0}/{selectedHistory.deletedTableCount ?? 0}</span>

                                <span className="text-ds-text-muted">新增/变更/删除字段</span>
                                <span
                                    className="text-ds-text-primary">{selectedHistory.addedColumnCount ?? 0}/{selectedHistory.updatedColumnCount ?? 0}/{selectedHistory.deletedColumnCount ?? 0}</span>
                        </div>
                        {selectedHistory.errorMessage && (
                            <div className="bg-ds-danger-light rounded-ds-sm p-ds-3 text-ds-small text-ds-danger">
                                <p className="font-semibold mb-ds-1">错误信息</p>
                                <p>{selectedHistory.errorMessage}</p>
                            </div>
                        )}
                    </div>
                </DsModal>
            )}

            {logOpen && selectedHistory && (
                <CollectLogModal
                    open={logOpen}
                    history={selectedHistory}
                    onClose={() => {
                        setLogOpen(false);
                        setSelectedHistory(null);
                    }}
                />
            )}
        </div>
    );
}
