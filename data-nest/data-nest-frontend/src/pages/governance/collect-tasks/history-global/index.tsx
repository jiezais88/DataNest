import {useCallback, useEffect, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {getCollectHistoryLogs, queryAllCollectHistory} from '../../../../api/collect';
import type {
    CollectExecutionLog,
    CollectHistoryQueryParams,
    CollectTaskExecution,
    ExecutionStatus,
} from '../../../../types/collect';
import Pagination from '../../../../components/Pagination';
import EmptyState from '../../../../components/EmptyState';
import SearchInput from '../../../../components/SearchInput';
import {formatDateTime} from '../../../../utils/time';
import {HiChevronRight, HiOutlineDocumentText, HiOutlineEye, HiOutlineXMark,} from 'react-icons/hi2';

const STATUS_OPTIONS: { value: ExecutionStatus | ''; label: string }[] = [
    {value: '', label: '全部状态'},
    {value: 'RUNNING', label: '执行中'},
    {value: 'SUCCESS', label: '成功'},
    {value: 'PARTIAL', label: '部分成功'},
    {value: 'FAILED', label: '失败'},
];

function formatDuration(ms?: number) {
    if (ms === undefined || ms === null) return '-';
    if (ms < 1000) return `${ms}ms`;
    return `${(ms / 1000).toFixed(1)}s`;
}

function formatDateTimeLocalInput(date: Date): string {
    const pad = (n: number) => String(n).padStart(2, '0');
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

function getDefaultTimeRange(): { from: string; to: string } {
    const now = new Date();
    const to = new Date(now.getFullYear(), now.getMonth(), now.getDate(), 23, 59, 59);
    const from = new Date(to.getTime() - 7 * 24 * 60 * 60 * 1000);
    from.setHours(0, 0, 0, 0);
    return {from: formatDateTimeLocalInput(from), to: formatDateTimeLocalInput(to)};
}

function statusClass(value: ExecutionStatus) {
    if (value === 'SUCCESS') {
        return {dot: 'bg-ds-success', bg: 'bg-ds-success-light', text: 'text-ds-success', label: '成功'};
    }
    if (value === 'RUNNING') {
        return {dot: 'bg-blue-500 animate-pulse', bg: 'bg-blue-50', text: 'text-blue-600', label: '执行中'};
    }
    if (value === 'PARTIAL') {
        return {dot: 'bg-ds-warning', bg: 'bg-ds-warning-light', text: 'text-ds-warning', label: '部分成功'};
    }
    return {dot: 'bg-ds-danger', bg: 'bg-ds-danger-light', text: 'text-ds-danger', label: '失败'};
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

    const [items, setItems] = useState<CollectTaskExecution[]>([]);
    const [total, setTotal] = useState(0);
    const [page, setPage] = useState(1);
    const [pageSize, setPageSize] = useState(10);
    const [status, setStatus] = useState<ExecutionStatus | ''>('');
    const [draftStatus, setDraftStatus] = useState<ExecutionStatus | ''>('');
    const [keyword, setKeyword] = useState('');
    const [draftKeyword, setDraftKeyword] = useState('');
    const defaultRange = getDefaultTimeRange();
    const [startTimeFrom, setStartTimeFrom] = useState(defaultRange.from);
    const [startTimeTo, setStartTimeTo] = useState(defaultRange.to);
    const [draftStartTimeFrom, setDraftStartTimeFrom] = useState(defaultRange.from);
    const [draftStartTimeTo, setDraftStartTimeTo] = useState(defaultRange.to);
    const [loading, setLoading] = useState(false);
    const [searchTrigger, setSearchTrigger] = useState(0);

    const [selectedHistory, setSelectedHistory] = useState<CollectTaskExecution | null>(null);
    const [logs, setLogs] = useState<CollectExecutionLog[]>([]);
    const [logsLoading, setLogsLoading] = useState(false);
    const [logOpen, setLogOpen] = useState(false);
    const [detailOpen, setDetailOpen] = useState(false);

    const loadData = useCallback(async () => {
        setLoading(true);
        try {
            const params: CollectHistoryQueryParams = {
                page,
                pageSize,
                status: status || undefined,
                keyword: keyword || undefined,
                startTimeFrom,
                startTimeTo,
            };
            const result = await queryAllCollectHistory(params);
            if (result.code === 200) {
                setItems(result.data.records);
                setTotal(result.data.total);
            }
        } finally {
            setLoading(false);
        }
    }, [page, pageSize, status, keyword, startTimeFrom, startTimeTo, searchTrigger]);

    useEffect(() => {
        loadData();
    }, [loadData]);

    const handleSearch = () => {
        setStatus(draftStatus);
        setKeyword(draftKeyword);
        setStartTimeFrom(draftStartTimeFrom);
        setStartTimeTo(draftStartTimeTo);
        setPage(1);
        setSearchTrigger((v) => v + 1);
    };

    const handleReset = () => {
        const range = getDefaultTimeRange();
        setDraftStatus('');
        setStatus('');
        setDraftKeyword('');
        setKeyword('');
        setDraftStartTimeFrom(range.from);
        setDraftStartTimeTo(range.to);
        setStartTimeFrom(range.from);
        setStartTimeTo(range.to);
        setPage(1);
        setSearchTrigger((v) => v + 1);
    };

    const handlePageChange = (nextPage: number, nextPageSize: number) => {
        setPage(nextPage);
        setPageSize(nextPageSize);
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
        if (result.code === 200) {
            setLogs(result.data || []);
        }
        setLogsLoading(false);
    };

    return (
        <div className="h-full flex flex-col overflow-hidden">
            <div className="flex items-center justify-between mb-ds-5 flex-shrink-0">
                <div>
                    <h1 className="text-ds-display text-ds-text-primary">采集执行历史</h1>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1">查看所有元数据采集任务的执行记录、统计与日志</p>
                </div>
                <button
                    onClick={() => navigate('/governance/collect-tasks')}
                    className="flex items-center gap-ds-1 px-ds-3 py-ds-2 bg-white border border-ds-border-subtle hover:border-ds-border-strong text-ds-text-secondary text-ds-small font-semibold rounded-ds-sm transition-colors ds-fast"
                >
                    <HiChevronRight size={16} className="rotate-180"/>
                    返回任务列表
                </button>
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
                            onChange={(e) => setDraftStatus(e.target.value as ExecutionStatus | '')}
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
                <div className="ds-table-card">
                    <div className="ds-table-scroll">
                        <table className="ds-table">
                            <thead>
                            <tr>
                                <th>任务名称</th>
                                <th>触发方式</th>
                                <th>状态</th>
                                <th>开始时间</th>
                                <th>结束时间</th>
                                <th>耗时</th>
                                <th>库/表/字段</th>
                                <th>错误信息</th>
                                <th className="text-center">操作</th>
                            </tr>
                            </thead>
                            <tbody>
                            {items.map((item) => {
                                const ss = statusClass(item.status);
                                return (
                                    <tr key={item.id}>
                                        <td className="ds-table-cell-truncate" title={item.taskName || '-'}>
                                            <span
                                                className="text-ds-body text-ds-text-primary font-medium">{item.taskName || '-'}</span>
                                        </td>
                                        <td>{triggerBadge(item.triggerType)}</td>
                                        <td>
                                            <span
                                                className={`inline-flex items-center gap-ds-1 px-ds-2 py-ds-1 rounded-ds-full text-ds-small font-medium ${ss.bg} ${ss.text}`}>
                                                <span className={`w-1.5 h-1.5 rounded-full ${ss.dot}`}/>
                                                {ss.label}
                                            </span>
                                        </td>
                                        <td className="text-ds-small text-ds-text-secondary">{formatDateTime(item.startedAt)}</td>
                                        <td className="text-ds-small text-ds-text-secondary">{formatDateTime(item.endedAt)}</td>
                                        <td className="text-ds-body text-ds-text-secondary">{formatDuration(item.durationMs)}</td>
                                        <td className="text-ds-small text-ds-text-secondary">
                                            {item.dbCount ?? 0}/{item.tableCount ?? 0}/{item.columnCount ?? 0}
                                        </td>
                                        <td className="ds-table-cell-wide text-ds-small text-ds-danger"
                                            title={item.errorMessage || ''}>
                                            {item.errorMessage || '—'}
                                        </td>
                                        <td className="ds-table-cell-no-truncate">
                                            <div className="flex items-center justify-center w-full gap-1">
                                                <button
                                                    onClick={() => handleOpenDetail(item)}
                                                    className="p-1.5 text-ds-text-muted hover:text-ds-accent hover:bg-ds-accent-light rounded transition-colors"
                                                    title="详情"
                                                    aria-label="详情"
                                                >
                                                    <HiOutlineEye size={16}/>
                                                </button>
                                                <button
                                                    onClick={() => handleOpenLogs(item)}
                                                    className="p-1.5 text-ds-text-muted hover:text-ds-accent hover:bg-ds-accent-light rounded transition-colors"
                                                    title="查看日志"
                                                    aria-label="查看日志"
                                                >
                                                    <HiOutlineDocumentText size={16}/>
                                                </button>
                                            </div>
                                        </td>
                                    </tr>
                                );
                            })}
                            </tbody>
                        </table>
                    </div>

                    {items.length === 0 && !loading && (
                        <EmptyState
                            title="暂无执行历史"
                            description="还没有采集任务执行记录，手动触发或等待 Cron 调度后自动产生。"
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

            {detailOpen && selectedHistory && (
                <div className="fixed inset-0 z-ds-dialog flex items-center justify-center p-ds-6">
                    <div className="absolute inset-0 bg-black/30" onClick={() => {
                        setDetailOpen(false);
                        setSelectedHistory(null);
                    }}/>
                    <div
                        className="relative bg-ds-bg-surface rounded-ds-md shadow-ds-xl flex flex-col w-[520px] max-h-[85vh]">
                        <div
                            className="flex items-center justify-between px-ds-5 py-ds-4 border-b border-ds-border-subtle">
                            <h3 className="text-ds-subhead text-ds-text-primary font-semibold">执行详情</h3>
                            <button
                                onClick={() => {
                                    setDetailOpen(false);
                                    setSelectedHistory(null);
                                }}
                                className="p-1 text-ds-text-muted hover:text-ds-text-primary hover:bg-ds-bg-hover rounded transition-colors"
                                aria-label="关闭"
                            >
                                <HiOutlineXMark size={20}/>
                            </button>
                        </div>
                        <div className="p-ds-5 space-y-ds-3 overflow-auto">
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
                                    <span
                                        className={`inline-flex items-center gap-ds-1 px-ds-2 py-ds-1 rounded-ds-full text-ds-small font-medium ${statusClass(selectedHistory.status).bg} ${statusClass(selectedHistory.status).text}`}>
                                        <span
                                            className={`w-1.5 h-1.5 rounded-full ${statusClass(selectedHistory.status).dot}`}/>
                                        {statusClass(selectedHistory.status).label}
                                    </span>
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
                        <div className="px-ds-5 py-ds-4 border-t border-ds-border-subtle flex justify-end gap-ds-3">
                            <button
                                onClick={() => {
                                    setDetailOpen(false);
                                    setSelectedHistory(null);
                                }}
                                className="px-ds-4 py-ds-2 bg-white border border-ds-border-subtle hover:border-ds-border-strong text-ds-text-secondary text-ds-small font-semibold rounded-ds-sm transition-colors"
                            >
                                关闭
                            </button>
                            <button
                                onClick={() => {
                                    setDetailOpen(false);
                                    handleOpenLogs(selectedHistory);
                                }}
                                className="px-ds-4 py-ds-2 bg-ds-accent hover:bg-ds-accent-hover text-white text-ds-small font-semibold rounded-ds-sm transition-colors"
                            >
                                查看日志
                            </button>
                        </div>
                    </div>
                </div>
            )}

            {logOpen && selectedHistory && (
                <div className="fixed inset-0 z-ds-dialog flex items-center justify-center p-ds-6">
                    <div className="absolute inset-0 bg-black/30" onClick={() => {
                        setLogOpen(false);
                        setSelectedHistory(null);
                    }}/>
                    <div
                        className="relative bg-ds-bg-surface rounded-ds-md shadow-ds-xl flex flex-col w-[720px] max-h-[85vh]">
                        <div
                            className="flex items-center justify-between px-ds-5 py-ds-4 border-b border-ds-border-subtle">
                            <h3 className="text-ds-subhead text-ds-text-primary font-semibold">
                                执行日志 - {formatDateTime(selectedHistory.startedAt)}
                            </h3>
                            <button
                                onClick={() => {
                                    setLogOpen(false);
                                    setSelectedHistory(null);
                                }}
                                className="p-1 text-ds-text-muted hover:text-ds-text-primary hover:bg-ds-bg-hover rounded transition-colors"
                                aria-label="关闭"
                            >
                                <HiOutlineXMark size={20}/>
                            </button>
                        </div>
                        <div className="flex-1 overflow-auto p-ds-5">
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
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
}
