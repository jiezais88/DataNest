import {useCallback, useEffect, useState} from 'react';
import {useNavigate, useParams} from 'react-router-dom';
import {useAuthStore} from '../../../../store/useAuthStore';
import {getCollectHistoryLogs, getCollectTask, queryCollectHistory} from '../../../../api/collect';
import type {
    CollectExecutionLog,
    CollectHistoryQueryParams,
    CollectTaskExecution,
    ExecutionStatus,
} from '../../../../types/collect';
import Pagination from '../../../../components/Pagination';
import EmptyState from '../../../../components/EmptyState';
import CodeLog from '../../../../components/CodeLog';
import {HiChevronLeft, HiChevronRight, HiOutlineClipboardDocument, HiOutlineDocumentText,} from 'react-icons/hi2';

const STATUS_OPTIONS: { value: ExecutionStatus | ''; label: string }[] = [
    {value: '', label: '全部状态'},
    {value: 'RUNNING', label: '执行中'},
    {value: 'SUCCESS', label: '成功'},
    {value: 'PARTIAL', label: '部分成功'},
    {value: 'FAILED', label: '失败'},
];

function formatDateTime(value?: string) {
    if (!value) return '-';
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return '-';
    const pad = (n: number) => String(n).padStart(2, '0');
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

function formatDuration(ms?: number) {
    if (ms === undefined || ms === null) return '-';
    if (ms < 1000) return `${ms}ms`;
    return `${(ms / 1000).toFixed(1)}s`;
}

export default function CollectHistoryPage() {
    const {taskId} = useParams<{ taskId: string }>();
    const navigate = useNavigate();
    const {userInfo} = useAuthStore();
    const roles = userInfo?.roles || [];
    const canReadLogs = roles.includes('SUPER_ADMIN') || roles.includes('GOVERNANCE_ADMIN');

    const [taskName, setTaskName] = useState('');
    const [items, setItems] = useState<CollectTaskExecution[]>([]);
    const [total, setTotal] = useState(0);
    const [page, setPage] = useState(1);
    const [pageSize, setPageSize] = useState(10);
    const [status, setStatus] = useState<ExecutionStatus | ''>('');
    const [draftStatus, setDraftStatus] = useState<ExecutionStatus | ''>('');
    const [loading, setLoading] = useState(false);

    const [selectedHistory, setSelectedHistory] = useState<CollectTaskExecution | null>(null);
    const [logOpen, setLogOpen] = useState(false);
    const [logs, setLogs] = useState<CollectExecutionLog[]>([]);
    const [logsLoading, setLogsLoading] = useState(false);

    const loadTask = useCallback(async () => {
        if (!taskId) return;
        try {
            const result = await getCollectTask(taskId);
            if (result.code === 200) {
                setTaskName(result.data.name);
            }
        } catch {
            // ignored
        }
    }, [taskId]);

    const loadData = useCallback(async () => {
        if (!taskId) return;
        setLoading(true);
        try {
            const params: CollectHistoryQueryParams = {
                taskId,
                page,
                pageSize,
                status: status || undefined,
            };
            const result = await queryCollectHistory(params);
            if (result.code === 200) {
                setItems(result.data.records);
                setTotal(result.data.total);
            }
        } finally {
            setLoading(false);
        }
    }, [taskId, page, pageSize, status]);

    useEffect(() => {
        loadTask();
    }, [loadTask]);

    useEffect(() => {
        loadData();
    }, [loadData]);

    const handleSearch = () => {
        setStatus(draftStatus);
        setPage(1);
    };

    const handleReset = () => {
        setDraftStatus('');
        setStatus('');
        setPage(1);
    };

    const handlePageChange = (nextPage: number, nextPageSize: number) => {
        setPage(nextPage);
        setPageSize(nextPageSize);
    };

    const handleOpenLogs = async (item: CollectTaskExecution) => {
        setSelectedHistory(item);
        setLogOpen(true);
        setLogsLoading(true);
        try {
            if (!taskId) return;
            const result = await getCollectHistoryLogs(taskId, item.id);
            if (result.code === 200) {
                setLogs(result.data);
            }
        } finally {
            setLogsLoading(false);
        }
    };

    const statusClass = (value: ExecutionStatus) => {
        if (value === 'SUCCESS') return {
            dot: 'bg-ds-success',
            bg: 'bg-ds-success-light',
            text: 'text-ds-success',
            label: '成功'
        };
        if (value === 'RUNNING') return {
            dot: 'bg-ds-accent',
            bg: 'bg-ds-accent-light',
            text: 'text-ds-accent',
            label: '执行中'
        };
        if (value === 'PARTIAL') return {
            dot: 'bg-ds-warning',
            bg: 'bg-ds-warning-light',
            text: 'text-ds-warning',
            label: '部分成功'
        };
        return {dot: 'bg-ds-danger', bg: 'bg-ds-danger-light', text: 'text-ds-danger', label: '失败'};
    };

    const triggerLabel = (value: string) => (value === 'CRON' ? 'Cron 定时' : '手动触发');

    return (
        <div className="h-full flex flex-col overflow-hidden">
            <div className="flex items-center gap-ds-2 mb-ds-2 flex-shrink-0">
                <button
                    onClick={() => navigate('/governance/collect-tasks')}
                    className="flex items-center gap-ds-1 text-ds-small text-ds-text-muted hover:text-ds-accent transition-colors"
                >
                    <HiChevronLeft size={16}/>
                    元数据采集任务
                </button>
                <HiChevronRight size={14} className="text-ds-text-muted"/>
                <span className="text-ds-small text-ds-text-primary font-medium">{taskName || taskId}</span>
                <HiChevronRight size={14} className="text-ds-text-muted"/>
                <span className="text-ds-small text-ds-text-secondary">历史记录</span>
            </div>

            <div className="flex items-center justify-between mb-ds-5 flex-shrink-0">
                <div>
                    <h1 className="text-ds-display text-ds-text-primary">历史记录</h1>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1">查看任务每次执行的详情、统计与日志</p>
                </div>
            </div>

            <div
                className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle p-ds-3 mb-ds-4 flex-shrink-0">
                <div className="flex items-center gap-ds-3 flex-wrap">
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
                            <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-muted uppercase tracking-wider">执行时间</th>
                            <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-muted uppercase tracking-wider">执行方式</th>
                            <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-muted uppercase tracking-wider">状态</th>
                            <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-muted uppercase tracking-wider">耗时</th>
                            <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-muted uppercase tracking-wider">库/表/字段</th>
                            <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-muted uppercase tracking-wider">变更统计</th>
                            <th className="text-right px-ds-4 py-ds-3 text-ds-caption text-ds-text-muted uppercase tracking-wider">操作</th>
                        </tr>
                        </thead>
                        <tbody>
                        {items.map((item) => {
                            const statusStyle = statusClass(item.status);
                            return (
                                <tr key={item.id}
                                    className="border-b border-ds-border-subtle last:border-0 hover:bg-ds-bg-hover/50 transition-colors">
                                    <td className="px-ds-4 py-ds-3 text-ds-small text-ds-text-secondary">
                                        {formatDateTime(item.startedAt)}
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
                                    <td className="px-ds-4 py-ds-3 text-ds-body text-ds-text-secondary">
                                        {formatDuration(item.durationMs)}
                                    </td>
                                    <td className="px-ds-4 py-ds-3 text-ds-small text-ds-text-secondary">
                                        {item.dbCount} / {item.tableCount} / {item.columnCount}
                                    </td>
                                    <td className="px-ds-4 py-ds-3 text-ds-small text-ds-text-secondary">
                                        +{item.addedTableCount} / ~{item.updatedTableCount} / -{item.deletedTableCount}
                                    </td>
                                    <td className="px-ds-4 py-ds-3">
                                        <div className="flex items-center justify-end gap-1">
                                            <button
                                                onClick={() => setSelectedHistory(item)}
                                                className="p-1.5 text-ds-text-muted hover:text-ds-accent hover:bg-ds-accent-light rounded transition-colors"
                                                title="详情"
                                                aria-label="详情"
                                            >
                                                <HiOutlineClipboardDocument size={16}/>
                                            </button>
                                            {canReadLogs && (
                                                <button
                                                    onClick={() => handleOpenLogs(item)}
                                                    className="p-1.5 text-ds-text-muted hover:text-ds-accent hover:bg-ds-accent-light rounded transition-colors"
                                                    title="查看日志"
                                                    aria-label="查看日志"
                                                >
                                                    <HiOutlineDocumentText size={16}/>
                                                </button>
                                            )}
                                        </div>
                                    </td>
                                </tr>
                            );
                        })}
                        </tbody>
                    </table>

                    {items.length === 0 && !loading && (
                        <EmptyState title="暂无历史记录"
                                    description="该任务还没有执行记录，手动触发或等待 Cron 调度后自动产生。"/>
                    )}

                    <Pagination page={page} pageSize={pageSize} total={total} onChange={handlePageChange}/>
                </div>
            </div>

            {selectedHistory && !logOpen && (
                <div className="fixed inset-0 z-ds-dialog flex items-center justify-center p-ds-6">
                    <div className="absolute inset-0 bg-black/30" onClick={() => setSelectedHistory(null)}/>
                    <div className="relative bg-ds-bg-surface rounded-ds-md shadow-ds-xl p-ds-6 w-[480px]">
                        <h3 className="text-ds-subhead text-ds-text-primary font-semibold mb-ds-4">执行详情</h3>
                        <div className="space-y-ds-3 text-ds-body text-ds-text-secondary">
                            <div className="flex justify-between">
                                <span>执行时间</span>
                                <span
                                    className="text-ds-text-primary">{formatDateTime(selectedHistory.startedAt)}</span>
                            </div>
                            <div className="flex justify-between">
                                <span>结束时间</span>
                                <span className="text-ds-text-primary">{formatDateTime(selectedHistory.endedAt)}</span>
                            </div>
                            <div className="flex justify-between">
                                <span>执行方式</span>
                                <span
                                    className="text-ds-text-primary">{triggerLabel(selectedHistory.triggerType)}</span>
                            </div>
                            <div className="flex justify-between">
                                <span>状态</span>
                                <span
                                    className="text-ds-text-primary">{STATUS_OPTIONS.find((o) => o.value === selectedHistory.status)?.label}</span>
                            </div>
                            <div className="flex justify-between">
                                <span>耗时</span>
                                <span
                                    className="text-ds-text-primary">{formatDuration(selectedHistory.durationMs)}</span>
                            </div>
                            <div className="flex justify-between">
                                <span>库 / 表 / 字段</span>
                                <span
                                    className="text-ds-text-primary">{selectedHistory.dbCount} / {selectedHistory.tableCount} / {selectedHistory.columnCount}</span>
                            </div>
                            <div className="flex justify-between">
                                <span>新增 / 更新 / 删除表</span>
                                <span
                                    className="text-ds-text-primary">{selectedHistory.addedTableCount} / {selectedHistory.updatedTableCount} / {selectedHistory.deletedTableCount}</span>
                            </div>
                            <div className="flex justify-between">
                                <span>新增 / 更新 / 删除字段</span>
                                <span
                                    className="text-ds-text-primary">{selectedHistory.addedColumnCount} / {selectedHistory.updatedColumnCount} / {selectedHistory.deletedColumnCount}</span>
                            </div>
                            {selectedHistory.errorMessage && (
                                <div className="p-ds-3 bg-ds-danger-light text-ds-danger rounded-ds-sm text-ds-small">
                                    {selectedHistory.errorMessage}
                                </div>
                            )}
                        </div>
                    </div>
                </div>
            )}

            <CodeLog
                open={logOpen}
                title={`执行日志 - ${formatDateTime(selectedHistory?.startedAt)}`}
                lines={logsLoading ? [{level: 'INFO', message: '加载中...'}] : logs.map((l) => ({
                    level: l.level,
                    message: l.message
                }))}
                onClose={() => {
                    setLogOpen(false);
                    setSelectedHistory(null);
                }}
            />
        </div>
    );
}
