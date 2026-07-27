import {useCallback, useEffect, useState} from 'react';
import {useNavigate, useParams} from 'react-router-dom';
import {useAuthStore} from '../../../../store/useAuthStore';
import {getCollectHistory, getCollectHistoryLogs, getCollectTask, queryCollectHistory} from '../../../../api/collect';
import type {
    CollectChangeDetailDTO,
    CollectExecutionLog,
    CollectHistoryQueryParams,
    CollectTaskExecution,
    ExecutionStatus,
} from '../../../../types/collect';
import Pagination from '../../../../components/Pagination';
import EmptyState from '../../../../components/EmptyState';

import {
    HiChevronDown,
    HiChevronLeft,
    HiChevronRight,
    HiOutlineClipboardDocument,
    HiOutlineDocumentText,
    HiOutlineXMark,
} from 'react-icons/hi2';

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

type AddedTableGroup = {
    tableDetail: CollectChangeDetailDTO;
    columns: CollectChangeDetailDTO[];
};

function groupChangeDetails(details?: CollectChangeDetailDTO[]) {
    if (!details || details.length === 0) {
        return {addedTables: {} as Record<string, AddedTableGroup>, deletedTables: [], modifiedTables: {}};
    }

    const addedTablesMap: Record<string, AddedTableGroup> = {};
    const deletedTables: CollectChangeDetailDTO[] = [];
    const modifiedTablesMap: Record<string, CollectChangeDetailDTO[]> = {};

    for (const d of details) {
        const key = `${d.databaseName}.${d.schemaName || ''}.${d.tableName}`;
        if (d.changeType === 'ADDED_TABLE') {
            if (!addedTablesMap[key]) {
                addedTablesMap[key] = {tableDetail: d, columns: []};
            } else if (!d.columnName) {
                addedTablesMap[key].tableDetail = d;
            }
            if (d.columnName) {
                addedTablesMap[key].columns.push(d);
            }
        } else if (d.changeType === 'DELETED_TABLE') {
            deletedTables.push(d);
        } else if (d.changeType === 'MODIFIED_TABLE') {
            if (!modifiedTablesMap[key]) modifiedTablesMap[key] = [];
            modifiedTablesMap[key].push(d);
        }
    }

    return {addedTables: addedTablesMap, deletedTables, modifiedTables: modifiedTablesMap};
}

function hasChanges(item: CollectTaskExecution): boolean {
    return (
        (item.addedTableCount || 0) +
        (item.updatedTableCount || 0) +
        (item.deletedTableCount || 0) +
        (item.addedColumnCount || 0) +
        (item.updatedColumnCount || 0) +
        (item.deletedColumnCount || 0)
    ) > 0;
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
    const [searchTrigger, setSearchTrigger] = useState(0);

    const [selectedHistory, setSelectedHistory] = useState<CollectTaskExecution | null>(null);
    const [logOpen, setLogOpen] = useState(false);
    const [logs, setLogs] = useState<CollectExecutionLog[]>([]);
    const [logsLoading, setLogsLoading] = useState(false);
    const [expandedModifiedTables, setExpandedModifiedTables] = useState<Record<string, boolean>>({});
    const [expandedAddedTables, setExpandedAddedTables] = useState<Record<string, boolean>>({});
    const [rawLogExpanded, setRawLogExpanded] = useState(false);

    const loadTask = useCallback(async () => {
        if (!taskId) return;
        const result = await getCollectTask(taskId);
        if (result.code === 200) {
            setTaskName(result.data.name);
        }
    }, [taskId]);

    const loadData = useCallback(async () => {
        if (!taskId) return;
        setLoading(true);
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
        setLoading(false);
    }, [taskId, page, pageSize, status, searchTrigger]);

    useEffect(() => {
        loadTask();
    }, [loadTask]);
    useEffect(() => {
        loadData();
    }, [loadData]);

    const handleSearch = () => {
        setStatus(draftStatus);
        setPage(1);
        setSearchTrigger((v) => v + 1);
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
        setExpandedModifiedTables({});
        setExpandedAddedTables({});
        if (!taskId) return;
        const [historyResult, logsResult] = await Promise.all([
            getCollectHistory(taskId, item.id),
            getCollectHistoryLogs(taskId, item.id),
        ]);
        if (historyResult.code === 200) {
            setSelectedHistory(historyResult.data);
            setRawLogExpanded(historyResult.data.status === 'FAILED');
        }
        if (logsResult.code === 200) {
            setLogs(logsResult.data);
        }
        setLogsLoading(false);
    };

    const handleOpenDetail = async (item: CollectTaskExecution) => {
        setSelectedHistory(item);
        if (!taskId) return;
        const result = await getCollectHistory(taskId, item.id);
        if (result.code === 200) {
            setSelectedHistory(result.data);
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
            dot: 'bg-blue-500 animate-pulse',
            bg: 'bg-blue-50',
            text: 'text-blue-600',
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

    const triggerBadge = (triggerType: string) => {
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
    };

    const changeGroups = selectedHistory ? groupChangeDetails(selectedHistory.changeDetails) : {
        addedTables: {} as Record<string, AddedTableGroup>,
        deletedTables: [],
        modifiedTables: {}
    };

    return (
        <div className="h-full flex flex-col overflow-hidden">
            <div className="flex items-center gap-ds-2 mb-ds-2 flex-shrink-0">
                <button onClick={() => navigate('/governance/collect-tasks')}
                        className="flex items-center gap-ds-1 text-ds-small text-ds-text-muted hover:text-ds-accent transition-colors">
                    <HiChevronLeft size={16}/> 元数据采集任务
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
                        <select value={draftStatus}
                                onChange={(e) => setDraftStatus(e.target.value as ExecutionStatus | '')}
                                aria-label="按状态筛选"
                                className="appearance-none min-w-[140px] pl-ds-3 pr-9 py-ds-2 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-small text-ds-text-primary focus:outline-none focus-visible:border-ds-accent cursor-pointer">
                            {STATUS_OPTIONS.map((o) => (<option key={o.value} value={o.value}>{o.label}</option>))}
                        </select>
                        <HiChevronRight size={14}
                                        className="absolute right-3 top-1/2 -translate-y-1/2 rotate-90 text-ds-text-muted pointer-events-none"/>
                    </div>
                    <div className="flex items-center gap-ds-2 ml-auto">
                        <button onClick={handleSearch} disabled={loading}
                                className="px-ds-4 py-ds-2 bg-ds-accent hover:bg-ds-accent-hover disabled:opacity-60 disabled:cursor-not-allowed text-white text-ds-small font-semibold rounded-ds-sm transition-colors ds-fast">
                            {loading ? '查询中...' : '查询'}
                        </button>
                        <button onClick={handleReset} disabled={loading}
                                className="px-ds-4 py-ds-2 bg-white border border-ds-border-subtle hover:border-ds-border-strong disabled:opacity-60 disabled:cursor-not-allowed text-ds-text-secondary text-ds-small font-semibold rounded-ds-sm transition-colors ds-fast">
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
                            <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-muted uppercase tracking-wider">扫描的库/表/字段</th>
                            <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-muted uppercase tracking-wider">是否有变化</th>
                            <th className="text-right px-ds-4 py-ds-3 text-ds-caption text-ds-text-muted uppercase tracking-wider">操作</th>
                        </tr>
                        </thead>
                        <tbody>
                        {items.map((item) => {
                            const ss = statusClass(item.status);
                            return (
                                <tr key={item.id}
                                    className="border-b border-ds-border-subtle last:border-0 hover:bg-ds-bg-hover/50 transition-colors">
                                    <td className="px-ds-4 py-ds-3 text-ds-small text-ds-text-secondary">{formatDateTime(item.startedAt)}</td>
                                    <td className="px-ds-4 py-ds-3">{triggerBadge(item.triggerType)}</td>
                                    <td className="px-ds-4 py-ds-3">
                                        <span
                                            className={`inline-flex items-center gap-ds-1 px-ds-2 py-ds-1 rounded-ds-full text-ds-small font-medium ${ss.bg} ${ss.text}`}>
                                            <span className={`w-1.5 h-1.5 rounded-full ${ss.dot}`}/>{ss.label}
                                        </span>
                                    </td>
                                    <td className="px-ds-4 py-ds-3 text-ds-body text-ds-text-secondary">{formatDuration(item.durationMs)}</td>
                                    <td className="px-ds-4 py-ds-3 text-ds-small text-ds-text-secondary">{item.dbCount} / {item.tableCount} / {item.columnCount}</td>
                                    <td className="px-ds-4 py-ds-3">
                                        <span
                                            className={`inline-flex items-center px-ds-2 py-ds-1 rounded-ds-full text-ds-small font-medium ${hasChanges(item) ? 'bg-emerald-50 text-emerald-700' : 'bg-slate-100 text-slate-600'}`}>
                                            {hasChanges(item) ? '有变化' : '无变化'}
                                        </span>
                                    </td>
                                    <td className="px-ds-4 py-ds-3">
                                        <div className="flex items-center justify-end gap-1">
                                            <button onClick={() => handleOpenDetail(item)}
                                                    className="p-1.5 text-ds-text-muted hover:text-ds-accent hover:bg-ds-accent-light rounded transition-colors"
                                                    title="详情" aria-label="详情">
                                                <HiOutlineClipboardDocument size={16}/>
                                            </button>
                                            {canReadLogs && (
                                                <button onClick={() => handleOpenLogs(item)}
                                                        className="p-1.5 text-ds-text-muted hover:text-ds-accent hover:bg-ds-accent-light rounded transition-colors"
                                                        title="查看日志" aria-label="查看日志">
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

            {/* 执行详情弹窗 */}
            {selectedHistory && !logOpen && (
                <div className="fixed inset-0 z-ds-dialog flex items-center justify-center p-ds-6">
                    <div className="absolute inset-0 bg-black/30" onClick={() => setSelectedHistory(null)}/>
                    <div
                        className="relative bg-ds-bg-surface rounded-ds-md shadow-ds-xl p-ds-6 w-[560px] max-h-[80vh] overflow-auto">
                        <h3 className="text-ds-subhead text-ds-text-primary font-semibold mb-ds-4">执行详情</h3>
                        <div className="space-y-ds-3 text-ds-body">
                            <div className="detail-section">
                                <div className="text-ds-caption text-ds-text-muted font-semibold mb-ds-2">基本信息</div>
                                <div className="grid grid-cols-2 gap-ds-2 text-ds-small">
                                    <div><span className="text-ds-text-muted">执行时间</span><span
                                        className="ml-ds-2 text-ds-text-primary">{formatDateTime(selectedHistory.startedAt)}</span>
                                    </div>
                                    <div><span className="text-ds-text-muted">结束时间</span><span
                                        className="ml-ds-2 text-ds-text-primary">{formatDateTime(selectedHistory.endedAt)}</span>
                                    </div>
                                    <div><span className="text-ds-text-muted">执行方式</span><span
                                        className="ml-ds-2 text-ds-text-primary">{selectedHistory.triggerType === 'MANUAL' ? '手动触发' : '定时触发'}</span>
                                    </div>
                                    <div><span className="text-ds-text-muted">状态</span><span
                                        className="ml-ds-2 text-ds-text-primary">{STATUS_OPTIONS.find((o) => o.value === selectedHistory.status)?.label}</span>
                                    </div>
                                    <div><span className="text-ds-text-muted">耗时</span><span
                                        className="ml-ds-2 text-ds-text-primary">{formatDuration(selectedHistory.durationMs)}</span>
                                    </div>
                                </div>
                            </div>

                            <div className="detail-section">
                                <div className="text-ds-caption text-ds-text-muted font-semibold mb-ds-2">库/Schema
                                    执行情况
                                </div>
                                <table className="w-full text-ds-small border border-ds-border-subtle rounded-ds-sm">
                                    <thead>
                                    <tr className="bg-ds-bg-hover">
                                        <th className="text-left px-ds-2 py-ds-1 text-ds-text-muted font-medium">库/Schema</th>
                                        <th className="text-left px-ds-2 py-ds-1 text-ds-text-muted font-medium">表数</th>
                                        <th className="text-left px-ds-2 py-ds-1 text-ds-text-muted font-medium">字段数</th>
                                        <th className="text-left px-ds-2 py-ds-1 text-ds-text-muted font-medium">状态</th>
                                    </tr>
                                    </thead>
                                    <tbody>
                                    <tr className="border-t border-ds-border-subtle">
                                        <td className="px-ds-2 py-ds-1 text-ds-text-primary">{selectedHistory.taskName || '-'}</td>
                                        <td className="px-ds-2 py-ds-1 text-ds-text-secondary">{selectedHistory.tableCount}</td>
                                        <td className="px-ds-2 py-ds-1 text-ds-text-secondary">{selectedHistory.columnCount}</td>
                                        <td className="px-ds-2 py-ds-1">
                                            <span
                                                className={`inline-flex items-center gap-ds-1 px-ds-1.5 py-0.5 rounded-ds-full text-ds-nano font-medium ${statusClass(selectedHistory.status).bg} ${statusClass(selectedHistory.status).text}`}>
                                                {statusClass(selectedHistory.status).label}
                                            </span>
                                        </td>
                                    </tr>
                                    </tbody>
                                </table>
                            </div>

                            <div className="detail-section">
                                <div className="text-ds-caption text-ds-text-muted font-semibold mb-ds-2">变更统计</div>
                                <div className="flex gap-ds-4 text-ds-small">
                                    <div><span className="text-ds-text-muted">新增表：</span><span
                                        className="text-ds-text-primary">{selectedHistory.addedTableCount}</span></div>
                                    <div><span className="text-ds-text-muted">删除表：</span><span
                                        className="text-ds-text-primary">{selectedHistory.deletedTableCount}</span>
                                    </div>
                                    <div><span className="text-ds-text-muted">修改表：</span><span
                                        className="text-ds-text-primary">{selectedHistory.updatedTableCount}</span>
                                    </div>
                                </div>
                                <div className="flex gap-ds-4 text-ds-small mt-ds-1">
                                    <div><span className="text-ds-text-muted">新增字段：</span><span
                                        className="text-ds-text-primary">{selectedHistory.addedColumnCount}</span></div>
                                    <div><span className="text-ds-text-muted">删除字段：</span><span
                                        className="text-ds-text-primary">{selectedHistory.deletedColumnCount}</span>
                                    </div>
                                    <div><span className="text-ds-text-muted">修改字段：</span><span
                                        className="text-ds-text-primary">{selectedHistory.updatedColumnCount}</span>
                                    </div>
                                </div>
                            </div>

                            {selectedHistory.errorMessage && (
                                <div
                                    className="p-ds-3 bg-ds-danger-light text-ds-danger rounded-ds-sm text-ds-small">{selectedHistory.errorMessage}</div>
                            )}
                        </div>
                    </div>
                </div>
            )}

            {/* 执行日志弹窗 */}
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
                            <h3 className="text-ds-subhead text-ds-text-primary font-semibold">执行日志
                                - {formatDateTime(selectedHistory.startedAt)}</h3>
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

                        <div className="flex-1 overflow-auto p-ds-5 space-y-ds-5">
                            {/* 新增表 */}
                            <div>
                                <div
                                    className="text-ds-caption text-ds-text-muted font-semibold mb-ds-2">新增表（{Object.keys(changeGroups.addedTables).length}）
                                </div>
                                {Object.keys(changeGroups.addedTables).length === 0 ? (
                                    <div className="text-ds-small text-ds-text-muted">无</div>
                                ) : (
                                    <div className="space-y-ds-2">
                                        {Object.entries(changeGroups.addedTables).map(([key, group]) => {
                                            const expanded = !!expandedAddedTables[key];
                                            const hasColumns = group.columns.length > 0;
                                            return (
                                                <div key={key}
                                                     className="border border-ds-border-subtle rounded-ds-sm overflow-hidden">
                                                    <button
                                                        onClick={() => hasColumns && setExpandedAddedTables((prev) => ({
                                                            ...prev,
                                                            [key]: !expanded
                                                        }))}
                                                        className={`w-full flex items-center justify-between px-ds-3 py-ds-2 bg-emerald-50/60 text-left ${hasColumns ? 'hover:bg-emerald-50 cursor-pointer' : 'cursor-default'}`}
                                                    >
                                                        <span
                                                            className="text-ds-small text-emerald-800 font-medium">{key}</span>
                                                        {hasColumns && (
                                                            <HiChevronDown size={16}
                                                                           className={`text-emerald-700 transition-transform ${expanded ? 'rotate-180' : ''}`}/>
                                                        )}
                                                    </button>
                                                    {expanded && hasColumns && (
                                                        <div className="px-ds-3 py-ds-2">
                                                            <table className="w-full text-ds-small">
                                                                <thead>
                                                                <tr className="border-b border-ds-border-subtle text-ds-text-muted">
                                                                    <th className="text-left py-ds-1 font-medium">字段名</th>
                                                                    <th className="text-left py-ds-1 font-medium">数据类型</th>
                                                                    <th className="text-left py-ds-1 font-medium">是否可空</th>
                                                                    <th className="text-left py-ds-1 font-medium">备注</th>
                                                                </tr>
                                                                </thead>
                                                                <tbody>
                                                                {group.columns.map((d) => {
                                                                    const [dataType, nullable, ...commentParts] = (d.newValue || '').split('|');
                                                                    const comment = commentParts.join('|');
                                                                    return (
                                                                        <tr key={d.id}
                                                                            className="border-b border-ds-border-subtle last:border-0">
                                                                            <td className="py-ds-1 text-ds-text-primary">{d.columnName}</td>
                                                                            <td className="py-ds-1 text-ds-text-secondary">{dataType || '-'}</td>
                                                                            <td className="py-ds-1 text-ds-text-secondary">{nullable === 'true' ? '是' : nullable === 'false' ? '否' : '-'}</td>
                                                                            <td className="py-ds-1 text-ds-text-secondary">{comment || '-'}</td>
                                                                        </tr>
                                                                    );
                                                                })}
                                                                </tbody>
                                                            </table>
                                                        </div>
                                                    )}
                                                </div>
                                            );
                                        })}
                                    </div>
                                )}
                            </div>

                            {/* 删除表 */}
                            <div>
                                <div
                                    className="text-ds-caption text-ds-text-muted font-semibold mb-ds-2">删除表（{changeGroups.deletedTables.length}）
                                </div>
                                {changeGroups.deletedTables.length === 0 ? (
                                    <div className="text-ds-small text-ds-text-muted">无</div>
                                ) : (
                                    <div className="flex flex-wrap gap-ds-2">
                                        {changeGroups.deletedTables.map((d) => (
                                            <span key={d.id}
                                                  className="inline-flex items-center px-ds-2 py-ds-1 rounded-ds-sm text-ds-small bg-red-50 text-red-700">
                                                {d.schemaName ? `${d.databaseName}.${d.schemaName}.${d.tableName}` : `${d.databaseName}.${d.tableName}`}
                                            </span>
                                        ))}
                                    </div>
                                )}
                            </div>

                            {/* 变化表 */}
                            <div>
                                <div
                                    className="text-ds-caption text-ds-text-muted font-semibold mb-ds-2">变化表（{Object.keys(changeGroups.modifiedTables).length}）
                                </div>
                                {Object.keys(changeGroups.modifiedTables).length === 0 ? (
                                    <div className="text-ds-small text-ds-text-muted">无</div>
                                ) : (
                                    <div className="space-y-ds-2">
                                        {Object.entries(changeGroups.modifiedTables).map(([key, details]) => {
                                            const expanded = !!expandedModifiedTables[key];
                                            return (
                                                <div key={key}
                                                     className="border border-ds-border-subtle rounded-ds-sm overflow-hidden">
                                                    <button
                                                        onClick={() => setExpandedModifiedTables((prev) => ({
                                                            ...prev,
                                                            [key]: !expanded
                                                        }))}
                                                        className="w-full flex items-center justify-between px-ds-3 py-ds-2 bg-ds-bg-hover/50 hover:bg-ds-bg-hover text-left"
                                                    >
                                                        <span
                                                            className="text-ds-small text-ds-text-primary font-medium">{key}</span>
                                                        <HiChevronDown size={16}
                                                                       className={`text-ds-text-muted transition-transform ${expanded ? 'rotate-180' : ''}`}/>
                                                    </button>
                                                    {expanded && (
                                                        <div className="px-ds-3 py-ds-2">
                                                            <table className="w-full text-ds-small">
                                                                <thead>
                                                                <tr className="border-b border-ds-border-subtle text-ds-text-muted">
                                                                    <th className="text-left py-ds-1 font-medium">字段名</th>
                                                                    <th className="text-left py-ds-1 font-medium">变更类型</th>
                                                                    <th className="text-left py-ds-1 font-medium">旧值</th>
                                                                    <th className="text-left py-ds-1 font-medium">新值</th>
                                                                </tr>
                                                                </thead>
                                                                <tbody>
                                                                {details.map((d) => {
                                                                    const isTableComment = d.changeType === 'MODIFIED_TABLE' && !d.columnName;
                                                                    return (
                                                                        <tr key={d.id}
                                                                            className="border-b border-ds-border-subtle last:border-0">
                                                                            <td className="py-ds-1 text-ds-text-primary">{d.columnName || '表注释'}</td>
                                                                            <td className="py-ds-1 text-ds-text-secondary">{isTableComment ? '表注释变更' : '字段变更'}</td>
                                                                            <td className="py-ds-1 text-ds-text-secondary">{d.oldValue || '-'}</td>
                                                                            <td className="py-ds-1 text-ds-text-secondary">{d.newValue || '-'}</td>
                                                                        </tr>
                                                                    );
                                                                })}
                                                                </tbody>
                                                            </table>
                                                        </div>
                                                    )}
                                                </div>
                                            );
                                        })}
                                    </div>
                                )}
                            </div>

                            {/* 原始日志 */}
                            <div className="border border-ds-border-subtle rounded-ds-sm overflow-hidden">
                                <button
                                    onClick={() => setRawLogExpanded((v) => !v)}
                                    className="w-full flex items-center justify-between px-ds-3 py-ds-2 bg-ds-bg-hover/50 hover:bg-ds-bg-hover text-left"
                                >
                                    <span className="text-ds-caption text-ds-text-muted font-semibold">原始日志</span>
                                    <HiChevronDown size={16}
                                                   className={`text-ds-text-muted transition-transform ${rawLogExpanded ? 'rotate-180' : ''}`}/>
                                </button>
                                {rawLogExpanded && (
                                    <div
                                        className="max-h-[320px] overflow-auto p-ds-3 bg-ds-bg-base font-mono text-ds-small">
                                        {logsLoading ? (
                                            <div className="text-ds-text-secondary">加载中...</div>
                                        ) : logs.length === 0 ? (
                                            <div className="text-ds-text-muted">暂无日志</div>
                                        ) : (
                                            <div className="space-y-0.5">
                                                {logs.map((log, idx) => (
                                                    <div key={idx}
                                                         className={`break-all ${log.level === 'ERROR' ? 'text-ds-danger' : log.level === 'WARN' ? 'text-ds-warning' : 'text-ds-text-secondary'}`}>
                                                        <span
                                                            className="font-semibold">[{log.level}]</span> {log.message}
                                                    </div>
                                                ))}
                                            </div>
                                        )}
                                    </div>
                                )}
                            </div>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
}
