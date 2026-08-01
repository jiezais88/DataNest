import type {SyncHistoryStatus, SyncJobHistory, SyncJobLog, SyncTriggerType} from '../../../types/sync';
import {formatDateTime, formatDuration, formatThroughput} from '../../../utils/format';
import DsButton from '../../../components/DsButton';
import DsModal from '../../../components/DsModal';
import DsStatusBadge from '../../../components/DsStatusBadge';
import {executionStatusVariant} from '../../../utils/status';

export const STATUS_OPTIONS: { value: SyncHistoryStatus | ''; label: string }[] = [
    {value: '', label: '全部状态'},
    {value: 'RUNNING', label: '执行中'},
    {value: 'SUCCESS', label: '成功'},
    {value: 'FAILED', label: '失败'},
];

/** 状态徽标的中文标签，配色统一走 executionStatusVariant + DsStatusBadge */
export function statusLabel(value: SyncHistoryStatus | string) {
    if (value === 'SUCCESS') return '成功';
    if (value === 'RUNNING') return '执行中';
    return '失败';
}

export function triggerBadge(triggerType: SyncTriggerType | string) {
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

export function formatSourceTable(item: SyncJobHistory) {
    const db = item.sourceDatabase || '';
    const schema = item.sourceSchema && item.sourceSchema !== db ? item.sourceSchema : '';
    const parts = [db, schema, item.sourceTable].filter(Boolean);
    return parts.length ? parts.join('.') : '-';
}

export function formatTargetTable(item: SyncJobHistory) {
    const db = item.targetDatabase || 'doris';
    return item.targetTable ? `doris.${db}.${item.targetTable}` : '-';
}

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

                    <span className="text-ds-text-muted">状态</span>
                    <span className="text-ds-text-primary">
                            <DsStatusBadge label={statusLabel(item.status)}
                                           variant={executionStatusVariant(item.status)}/>
                        </span>

                    <span className="text-ds-text-muted">耗时</span>
                    <span
                        className="text-ds-text-primary">{formatDuration(item.durationMs ?? (item.durationSeconds != null ? item.durationSeconds * 1000 : undefined))}</span>

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

export function HistoryLogModal({open, title, logs, loading, onClose}: HistoryLogModalProps) {
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
    );
}
