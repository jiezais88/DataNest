import {useMemo} from 'react';
import {Tabs} from 'antd';
import {Link} from 'react-router-dom';
import type {SyncJobHistory, SyncJobLog} from '../../../types/sync';
import {formatDateTime, formatExecutionDuration, formatThroughput} from '../../../utils/format';
import DsButton from '../../../components/DsButton';
import DsModal from '../../../components/DsModal';
import DsStatusBadge from '../../../components/DsStatusBadge';
import {executionStatusVariant} from '../../../utils/status';
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
                        className="text-ds-text-primary">{formatExecutionDuration(item.durationMs ?? (item.durationSeconds != null ? item.durationSeconds * 1000 : undefined), item.startTime, item.endTime)}</span>

                    <span className="text-ds-text-muted">源表</span>
                    <span className="text-ds-text-primary font-mono">{formatSourceTable(item)}</span>

                    <span className="text-ds-text-muted">目标表</span>
                    <span className="text-ds-text-primary font-mono">{formatTargetTable(item)}</span>

                    <span className="text-ds-text-muted">同步模式</span>
                    <span
                        className="text-ds-text-primary">{item.syncMode === 'INCREMENTAL' ? `增量同步${item.incrementalField ? ` (${item.incrementalField})` : ''}` : '全量同步'}</span>

                    <span className="text-ds-text-muted">同步行数</span>
                    <span className="text-ds-text-primary">{item.targetRows ?? '—'}</span>

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
    logs: SyncJobLog[];
    loading: boolean;
    onClose: () => void;
}

// 聚合日志里按 "===== Addax 执行: {表} =====" 头切分，把日志按表分组
const LOG_TABLE_HEADER_RE = /^===== Addax 执行: (.+?) =====$/;
// 平台（worker SyncJobExecutorService）自打印的概要行：归到「概览」，不混进某张表的 Tab
const PLATFORM_SUMMARY_RE = /^(开始 Addax 同步执行|同步成功|Addax 执行失败|同步任务最终失败|开始.*Addax)/;

function splitLogsByTable(logs: SyncJobLog[]): { table: string; logs: SyncJobLog[] }[] {
    const groups: { table: string; logs: SyncJobLog[] }[] = [];
    let current: { table: string; logs: SyncJobLog[] } | null = null;
    // 确保「概览」组存在且排在最前
    const ensureOverview = () => {
        if (!groups.length || groups[0].table !== '概览') {
            groups.unshift({table: '概览', logs: []});
        }
    };
    for (const log of logs) {
        const msg = (log.message || '').trim();
        const m = LOG_TABLE_HEADER_RE.exec(msg);
        if (m) {
            current = {table: m[1], logs: []};
            groups.push(current);
            continue;
        }
        if (PLATFORM_SUMMARY_RE.test(msg)) {
            // 平台概要行（开始/成功/失败收尾）归「概览」
            ensureOverview();
            groups[0].logs.push(log);
            continue;
        }
        if (current) {
            current.logs.push(log);
        } else {
            // 首个表头之前的行（如 "开始 Addax 同步执行"）归到「概览」
            ensureOverview();
            groups[0].logs.push(log);
        }
    }
    return groups;
}

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

export function HistoryLogModal({open, title, logs, loading, onClose}: HistoryLogModalProps) {
    const groups = useMemo(() => splitLogsByTable(logs), [logs]);
    const hasTableSplit = groups.length > 1;
    const items = hasTableSplit
        ? [
            {key: '__all', label: '全部', children: <LogRows logs={logs}/>},
            ...groups.map((g, i) => ({key: `g-${i}`, label: g.table, children: <LogRows logs={g.logs}/>})),
        ]
        : undefined;
    return (
        <DsModal
            open={open}
            onClose={onClose}
            title={`执行日志${title ? ` - ${title}` : ''}`}
            width="w-[720px]"
            bordered
        >
            {loading ? (
                <div className="text-ds-small text-ds-text-secondary">加载中...</div>
            ) : logs.length === 0 ? (
                <div className="text-ds-small text-ds-text-muted">暂无日志</div>
            ) : hasTableSplit ? (
                <Tabs size="small" items={items}/>
            ) : (
                <LogRows logs={logs}/>
            )}
        </DsModal>
    );
}
