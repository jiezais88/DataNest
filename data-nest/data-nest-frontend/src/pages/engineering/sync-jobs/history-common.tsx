import {useCallback, useEffect, useState} from 'react';
import {Tabs} from 'antd';
import {Link} from 'react-router-dom';
import type {SyncJobHistory, SyncJobLog} from '@/types/sync';
import {formatDateTime, formatExecutionDuration, formatThroughput} from '@/utils/format';
import DsButton from '@/components/DsButton';
import DsModal from '@/components/DsModal';
import DsStatusBadge from '@/components/DsStatusBadge';
import {executionStatusVariant} from '@/utils/status';
import {formatSourceTable, formatTargetTable, statusLabel, triggerBadge} from './history-common-utils';

interface HistoryDetailModalProps {
    open: boolean;
    item: SyncJobHistory | null;
    onClose: () => void;
    onViewLogs: (item: SyncJobHistory) => void;
}

export function HistoryDetailModal({open, item, onClose, onViewLogs}: HistoryDetailModalProps) {
    if (!item) return null;
    return (
        <DsModal
            open={open}
            onClose={onClose}
            title="执行详情"
            width="w-[520px]"
            bordered
            footer={
                <>
                    <DsButton
                        variant="secondary"
                        onClick={onClose}
                    >
                        关闭
                    </DsButton>
                    <DsButton
                        variant="primary"
                        onClick={() => onViewLogs(item)}
                    >
                        查看日志
                    </DsButton>
                </>
            }
        >
            <div className="space-y-ds-3">
                {item.id != null && (
                    <div className="grid grid-cols-[100px_1fr] gap-y-ds-2 text-ds-small">
                        <span className="text-ds-text-muted">实例 ID</span>
                        <span className="text-ds-text-primary font-mono tabular-nums break-all">{item.id}</span>
                    </div>
                )}
                {'taskName' in item && item.taskName !== undefined && (
                    <div className="grid grid-cols-[100px_1fr] gap-y-ds-2 text-ds-small">
                        <span className="text-ds-text-muted">任务名称</span>
                        <span className="text-ds-text-primary font-medium">{item.taskName || '-'}</span>
                    </div>
                )}
                <div className="grid grid-cols-[100px_1fr] gap-y-ds-2 text-ds-small">
                    <span className="text-ds-text-muted">执行时间</span>
                    <span className="text-ds-text-primary font-medium">{formatDateTime(item.startTime)}</span>

                    <span className="text-ds-text-muted">执行方式</span>
                    <span className="text-ds-text-primary">{triggerBadge(item.triggerType)}</span>

                    {item.dagId != null && item.dagExecutionId != null && (
                        <>
                            <span className="text-ds-text-muted">DAG 实例</span>
                            <Link
                                to={`/engineering/dags/${item.dagId}/executions/${item.dagExecutionId}`}
                                onClick={onClose}
                                className="text-ds-accent hover:underline"
                            >
                                {item.dagName || `DAG #${item.dagId}`}
                            </Link>
                        </>
                    )}

                    <span className="text-ds-text-muted">状态</span>
                    <span className="text-ds-text-primary">
                            <DsStatusBadge label={statusLabel(item.status)}
                                           variant={executionStatusVariant(item.status)}/>
                        </span>

                    <span className="text-ds-text-muted">耗时</span>
                    <span
                        className="text-ds-text-primary font-mono tabular-nums">{formatExecutionDuration(item.durationMs ?? (item.durationSeconds != null ? item.durationSeconds * 1000 : undefined), item.startTime, item.endTime)}</span>

                    <span className="text-ds-text-muted">源表</span>
                    <span className="text-ds-text-primary font-mono">{formatSourceTable(item)}</span>

                    <span className="text-ds-text-muted">目标表</span>
                    <span className="text-ds-text-primary font-mono">{formatTargetTable(item)}</span>

                    <span className="text-ds-text-muted">同步模式</span>
                    <span
                        className="text-ds-text-primary">{item.syncMode === 'INCREMENTAL' ? `增量同步${item.incrementalField ? ` (${item.incrementalField})` : ''}` : '全量同步'}</span>

                    <span className="text-ds-text-muted">同步行数</span>
                    <span className="text-ds-text-primary font-mono tabular-nums">{item.targetRows ?? '—'}</span>

                    <span className="text-ds-text-muted">吞吐量</span>
                    <span className="text-ds-text-primary">{formatThroughput(item.throughputRowsPerSecond)}</span>
                </div>
                {item.errorMessage && (
                    <div className="bg-ds-danger-light rounded-ds-sm p-ds-3 text-ds-small text-ds-danger">
                        <p className="font-semibold mb-ds-1">错误信息</p>
                        <p>{item.errorMessage}</p>
                    </div>
                )}

                {/* 多表同步：按表展示每张表的执行结果（单表时不展示，避免与上方汇总重复） */}
                {item.tableResults && item.tableResults.length > 1 && (
                    <div>
                        <h4 className="text-ds-small font-semibold text-ds-text-secondary mb-ds-2">
                            每表执行结果（{item.tableResults.length} 张）
                        </h4>
                        <div className="space-y-ds-2">
                            {item.tableResults.map((tr, idx) => (
                                <div
                                    key={idx}
                                    className="border border-ds-border-subtle rounded-ds-sm p-ds-3 text-ds-small"
                                >
                                    <div className="flex items-center justify-between gap-ds-2 mb-ds-1">
                                        <span className="font-mono text-ds-text-primary break-all">
                                            {tr.sourceTable || '?'} → {tr.targetTable || '?'}
                                        </span>
                                        <DsStatusBadge
                                            label={statusLabel(tr.status || '')}
                                            variant={executionStatusVariant(tr.status || '')}
                                        />
                                    </div>
                                    <div className="flex items-center gap-ds-4 text-ds-text-secondary mt-1">
                                        <span>读 {tr.readRows ?? 0}</span>
                                        <span>写 {tr.writeRows ?? 0}</span>
                                        <span>耗时 {formatExecutionDuration(tr.durationMs)}</span>
                                    </div>
                                    {tr.errorMessage && (
                                        <div className="text-ds-danger text-ds-caption break-all mt-1">
                                            {tr.errorMessage}
                                        </div>
                                    )}
                                </div>
                            ))}
                        </div>
                    </div>
                )}
            </div>
        </DsModal>
    );
}

interface HistoryLogModalProps {
    open: boolean;
    title?: string;
    onClose: () => void;
    /** 分页模式（同步历史日志）：提供 tabs + fetchLogs 时启用「概览 + 每表」Tab、各自滚动加载 */
    tabs?: string[];
    /** 拉取指定 scope 的一页日志；scope='overview' 为概览，否则为表名 */
    fetchLogs?: (scope: string, page: number, pageSize: number) => Promise<{ records: SyncJobLog[]; total: number }>;
    /** 平铺模式（DAG SYNC 节点日志等）：一次性展示全部日志 */
    logs?: SyncJobLog[];
    loading?: boolean;
}

const LOG_PAGE_SIZE = 200;

interface TabLogState {
    page: number;
    total: number;
    logs: SyncJobLog[];
    loaded: boolean;
    loading: boolean;
}

const EMPTY_TAB: TabLogState = {page: 0, total: 0, logs: [], loaded: false, loading: false};

function LogRows({logs}: { logs: SyncJobLog[] }) {
    return (
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
                    <span className="font-semibold">[{log.level}]</span> {formatDateTime(log.createdAt)} {log.message}
                </div>
            ))}
        </div>
    );
}

export function HistoryLogModal({open, title, tabs, fetchLogs, logs, loading, onClose}: HistoryLogModalProps) {
    const paginated = !!tabs && !!fetchLogs;

    const [activeKey, setActiveKey] = useState<string>('概览');
    const [tabStates, setTabStates] = useState<Record<string, TabLogState>>({});

    const updateTab = useCallback((tab: string, patch: Partial<TabLogState>) => {
        setTabStates(prev => ({...prev, [tab]: {...(prev[tab] || EMPTY_TAB), ...patch}}));
    }, []);

    const loadPage = useCallback((tab: string, append: boolean) => {
        if (!fetchLogs) return;
        const st = tabStates[tab] || EMPTY_TAB;
        if (st.loading) return;
        // 已加载完全部则不再请求
        if (st.total > 0 && st.logs.length >= st.total) return;
        const nextPage = append ? st.page + 1 : 1;
        updateTab(tab, {loading: true});
        const scope = tab === '概览' ? 'overview' : tab;
        fetchLogs(scope, nextPage, LOG_PAGE_SIZE)
            .then(res => {
                updateTab(tab, {
                    page: nextPage,
                    total: res.total,
                    logs: append ? [...st.logs, ...res.records] : res.records,
                    loaded: true,
                    loading: false,
                });
            })
            .catch(() => updateTab(tab, {loading: false}));
    }, [tabStates, fetchLogs, updateTab]);

    // 打开时重置并加载第一个 Tab（仅分页模式）
    useEffect(() => {
        if (!open || !paginated) return;
        setTabStates({});
        const first = (tabs && tabs.length ? tabs : ['概览'])[0];
        setActiveKey(first);
        loadPage(first, false);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [open]);

    const handleTabChange = (key: string) => {
        setActiveKey(key);
        const st = tabStates[key];
        if (!st || (!st.loaded && !st.loading)) loadPage(key, false);
    };

    const handleScroll = (e: React.UIEvent<HTMLDivElement>) => {
        const el = e.currentTarget;
        const st = tabStates[activeKey];
        if (!st || st.loading) return;
        if (el.scrollTop + el.clientHeight >= el.scrollHeight - 60) {
            loadPage(activeKey, true);
        }
    };

    // 平铺模式（DAG SYNC 节点日志等）：一次性展示全部日志
    if (!paginated) {
        return (
            <DsModal
                open={open}
                onClose={onClose}
                title={`执行日志${title ? ` - ${title}` : ''}`}
                width="w-[720px]"
                bordered
            >
                {loading ? (
                    <div className="text-ds-small text-ds-text-secondary py-4">加载中...</div>
                ) : !logs || logs.length === 0 ? (
                    <div className="text-ds-small text-ds-text-muted py-4">暂无日志</div>
                ) : (
                    <div className="max-h-[420px] overflow-y-auto">
                        <LogRows logs={logs}/>
                    </div>
                )}
            </DsModal>
        );
    }

    const items = (tabs && tabs.length ? tabs : ['概览']).map(tab => {
        const st = tabStates[tab] || EMPTY_TAB;
        return {
            key: tab,
            label: tab,
            children: (
                <div className="h-[420px] overflow-y-auto" onScroll={handleScroll}>
                    {st.loading && st.logs.length === 0 ? (
                        <div className="text-ds-small text-ds-text-secondary py-4">加载中...</div>
                    ) : st.logs.length === 0 ? (
                        <div className="text-ds-small text-ds-text-muted py-4">暂无日志</div>
                    ) : (
                        <>
                            <LogRows logs={st.logs}/>
                            <div className="text-ds-caption text-ds-text-muted py-2 text-center">
                                {st.loading
                                    ? '加载中...'
                                    : `已显示 ${st.logs.length} / 共 ${st.total} 行${st.logs.length < st.total ? '（滚动加载更多）' : ''}`}
                            </div>
                        </>
                    )}
                </div>
            ),
        };
    });

    return (
        <DsModal
            open={open}
            onClose={onClose}
            title={`执行日志${title ? ` - ${title}` : ''}`}
            width="w-[760px]"
            bordered
        >
            <Tabs size="small" activeKey={activeKey} onChange={handleTabChange} items={items}/>
        </DsModal>
    );
}
