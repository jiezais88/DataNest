import {useCallback, useEffect, useMemo, useState} from 'react';
import {useNavigate, useSearchParams} from 'react-router-dom';
import {Empty, Table, Tooltip} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import {getCollectHistoryLogs, queryAllCollectHistory} from '../../../../api/collect';
import type {
    CollectExecutionLog,
    CollectHistoryQueryParams,
    CollectTaskExecution,
    ExecutionStatus,
} from '../../../../types/collect';
import usePagedList from '../../../../hooks/usePagedList';
import Pagination from '../../../../components/Pagination';
import SearchInput from '../../../../components/SearchInput';
import DsButton from '../../../../components/DsButton';
import DsIconButton from '../../../../components/DsIconButton';
import DsModal from '../../../../components/DsModal';
import DsStatusBadge from '../../../../components/DsStatusBadge';
import DsFilterSelect from '../../../../components/DsFilterSelect';
import DsToolbar from '../../../../components/DsToolbar';
import {formatDateTime, formatDuration, getDefaultTimeRange} from '../../../../utils/format';
import {executionStatusVariant} from '../../../../utils/status';
import {HiChevronRight, HiOutlineDocumentText, HiOutlineEye,} from 'react-icons/hi2';

const STATUS_OPTIONS: { value: ExecutionStatus | ''; label: string }[] = [
    {value: '', label: '全部状态'},
    {value: 'RUNNING', label: '执行中'},
    {value: 'SUCCESS', label: '成功'},
    {value: 'PARTIAL', label: '部分成功'},
    {value: 'FAILED', label: '失败'},
];

const STATUS_LABELS: Record<ExecutionStatus, string> = {
    SUCCESS: '成功',
    RUNNING: '执行中',
    PARTIAL: '部分成功',
    FAILED: '失败',
};

// 「已应用」查询条件：接口分页参数由 usePagedList 注入，页面只管业务条件
type HistoryQuery = Omit<CollectHistoryQueryParams, 'page' | 'pageSize'>;

// PARTIAL（部分成功）不在统一映射里，保留原 statusClass 的 warning 语义
function statusVariant(value: ExecutionStatus) {
    return value === 'PARTIAL' ? 'warning' : executionStatusVariant(value);
}

function triggerBadge(triggerType: string) {
    if (triggerType === 'MANUAL') {
        return (
            <span
                className="inline-flex items-center px-ds-2 py-ds-1 rounded-ds-full text-ds-small font-medium bg-blue-50 text-blue-700">
                手动触发
            </span>
        );
    }
    return (
        <span
            className="inline-flex items-center px-ds-2 py-ds-1 rounded-ds-full text-ds-small font-medium bg-slate-100 text-blue-600">
            定时触发
        </span>
    );
}

export default function CollectHistoryGlobalPage() {
    const navigate = useNavigate();
    const [searchParams, setSearchParams] = useSearchParams();

    const defaultRange = getDefaultTimeRange();
    // 草稿查询条件（输入中、未点查询的值），点「查询」时通过 applyQuery 应用
    const [draftStatus, setDraftStatus] = useState<ExecutionStatus | ''>('');
    const [draftKeyword, setDraftKeyword] = useState('');
    const [draftStartTimeFrom, setDraftStartTimeFrom] = useState(defaultRange.from);
    const [draftStartTimeTo, setDraftStartTimeTo] = useState(defaultRange.to);

    const fetcher = useCallback(async (params: HistoryQuery & { page: number; pageSize: number }) => {
        const result = await queryAllCollectHistory(params);
        return {list: result.data.records, total: result.data.total};
    }, []);

    const {list, total, page, pageSize, loading, query, setPage, setPageSize, applyQuery} =
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

    const [selectedHistory, setSelectedHistory] = useState<CollectTaskExecution | null>(null);
    const [logs, setLogs] = useState<CollectExecutionLog[]>([]);
    const [logsLoading, setLogsLoading] = useState(false);
    const [logOpen, setLogOpen] = useState(false);
    const [detailOpen, setDetailOpen] = useState(false);

    const handleSearch = () => {
        clearTaskIdUrl();
        applyQuery({
            taskId: undefined,
            status: draftStatus || undefined,
            keyword: draftKeyword || undefined,
            startTimeFrom: draftStartTimeFrom,
            startTimeTo: draftStartTimeTo,
        });
    };

    const handleReset = () => {
        const range = getDefaultTimeRange();
        clearTaskIdUrl();
        setDraftStatus('');
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

    const handleOpenLogs = async (item: CollectTaskExecution) => {
        setSelectedHistory(item);
        setLogOpen(true);
        setLogsLoading(true);
        const result = await getCollectHistoryLogs(item.taskId, item.id);
        setLogs(result.data || []);
        setLogsLoading(false);
    };

    const columns = useMemo<ColumnsType<CollectTaskExecution>>(() => [
        {
            title: '任务名称',
            dataIndex: 'taskName',
            ellipsis: {showTitle: true},
            render: (v?: string) => (
                <span className="text-ds-body text-ds-text-primary font-medium">{v || '-'}</span>
            ),
        },
        {
            title: '触发方式',
            dataIndex: 'triggerType',
            render: (v: string) => triggerBadge(v),
        },
        {
            title: '状态',
            dataIndex: 'status',
            render: (v: ExecutionStatus) => (
                <DsStatusBadge label={STATUS_LABELS[v]} variant={statusVariant(v)}/>
            ),
        },
        {
            title: '开始时间',
            dataIndex: 'startedAt',
            render: (v?: string) => (
                <span className="text-ds-small text-ds-text-secondary">{formatDateTime(v)}</span>
            ),
        },
        {
            title: '结束时间',
            dataIndex: 'endedAt',
            render: (v?: string) => (
                <span className="text-ds-small text-ds-text-secondary">{formatDateTime(v)}</span>
            ),
        },
        {
            title: '耗时',
            dataIndex: 'durationMs',
            render: (v?: number) => (
                <span className="text-ds-body text-ds-text-secondary">{formatDuration(v)}</span>
            ),
        },
        {
            title: '库/表/字段',
            render: (_, item) => (
                <span className="text-ds-small text-ds-text-secondary">
                    {item.dbCount ?? 0}/{item.tableCount ?? 0}/{item.columnCount ?? 0}
                </span>
            ),
        },
        {
            title: '错误信息',
            dataIndex: 'errorMessage',
            ellipsis: {showTitle: true},
            render: (v?: string) => (
                <span className="text-ds-small text-ds-danger">{v || '—'}</span>
            ),
        },
        {
            title: '操作',
            align: 'center',
            width: 100,
            render: (_, item) => (
                <div className="flex items-center justify-center w-full gap-1">
                    <Tooltip title="详情">
                        <DsIconButton
                            tone="accent"
                            onClick={() => handleOpenDetail(item)}
                            aria-label="详情"
                        >
                            <HiOutlineEye size={16}/>
                        </DsIconButton>
                    </Tooltip>
                    <Tooltip title="查看日志">
                        <DsIconButton
                            tone="accent"
                            onClick={() => handleOpenLogs(item)}
                            aria-label="查看日志"
                        >
                            <HiOutlineDocumentText size={16}/>
                        </DsIconButton>
                    </Tooltip>
                </div>
            ),
        },
    ], []);

    return (
        <div className="h-full flex flex-col overflow-hidden">
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
                        value={draftStatus}
                        onChange={(v) => setDraftStatus(v as ExecutionStatus | '')}
                        options={STATUS_OPTIONS}
                        aria-label="按状态筛选"
                    />
                    <div className="flex items-center gap-ds-2">
                        <input
                            type="datetime-local"
                            value={draftStartTimeFrom}
                            onChange={(e) => setDraftStartTimeFrom(e.target.value)}
                            className="px-ds-3 py-ds-2 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-small text-ds-text-primary focus:outline-none focus-visible:border-ds-accent"
                            aria-label="开始时间起"
                        />
                        <span className="text-ds-small text-ds-text-muted">至</span>
                        <input
                            type="datetime-local"
                            value={draftStartTimeTo}
                            onChange={(e) => setDraftStartTimeTo(e.target.value)}
                            className="px-ds-3 py-ds-2 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-small text-ds-text-primary focus:outline-none focus-visible:border-ds-accent"
                            aria-label="开始时间止"
                        />
                    </div>
                </DsToolbar>
            </div>

            <div className="flex-1 min-h-0 flex flex-col">
                <div
                    className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden min-h-0 flex flex-col mb-ds-8">
                    <div className="flex-1 overflow-auto">
                        <Table<CollectTaskExecution>
                            dataSource={list}
                            rowKey="id"
                            loading={loading}
                            pagination={false}
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
                                        variant={statusVariant(selectedHistory.status)}
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
                <DsModal
                    open={logOpen}
                    onClose={() => {
                        setLogOpen(false);
                        setSelectedHistory(null);
                    }}
                    title={`执行日志 - ${formatDateTime(selectedHistory.startedAt)}`}
                    width="w-[720px]"
                    bordered
                >
                    {logsLoading ? (
                        <div className="text-ds-small text-ds-text-secondary">加载中...</div>
                    ) : logs.length === 0 ? (
                        <div className="text-ds-small text-ds-text-muted">暂无日志</div>
                    ) : (
                        <div className="space-y-1 font-mono text-ds-small">
                            {logs.map((log, idx) => (
                                <div
                                    key={idx}
                                    className={`break-all ${
                                        log.level === 'ERROR'
                                            ? 'text-ds-danger'
                                            : log.level === 'WARN'
                                                ? 'text-ds-warning'
                                                : 'text-ds-text-secondary'
                                    }`}
                                >
                                    <span
                                        className="font-semibold">[{log.level}]</span> {formatDateTime(log.createdAt)} {log.message}
                                </div>
                            ))}
                        </div>
                    )}
                </DsModal>
            )}
        </div>
    );
}
