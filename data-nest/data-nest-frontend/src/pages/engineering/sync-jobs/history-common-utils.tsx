import type {SyncHistoryStatus, SyncJobHistory, SyncTriggerType} from '../../../types/sync';

export const STATUS_OPTIONS: { value: SyncHistoryStatus | ''; label: string }[] = [
    {value: '', label: '全部状态'},
    {value: 'RUNNING', label: '执行中'},
    {value: 'SUCCESS', label: '成功'},
    {value: 'FAILED', label: '失败'},
    {value: 'TERMINATED', label: '已终止'},
];

/** 状态徽标的中文标签，配色统一走 executionStatusVariant + DsStatusBadge */
export function statusLabel(value: SyncHistoryStatus | string) {
    if (value === 'SUCCESS') return '成功';
    if (value === 'RUNNING') return '执行中';
    if (value === 'TERMINATED') return '已终止';
    return '失败';
}

export function triggerBadge(triggerType: SyncTriggerType | string) {
    if (triggerType === 'MANUAL') {
        return (
            <span
                className="inline-flex items-center px-2.5 py-1 rounded-full text-[11px] font-semibold whitespace-nowrap bg-blue-50 text-blue-700">
                手动触发
            </span>
        );
    }
    if (triggerType === 'DAG') {
        return (
            <span
                className="inline-flex items-center px-2.5 py-1 rounded-full text-[11px] font-semibold whitespace-nowrap bg-violet-50 text-violet-700">
                DAG 编排
            </span>
        );
    }
    return (
        <span
            className="inline-flex items-center px-2.5 py-1 rounded-full text-[11px] font-semibold whitespace-nowrap bg-slate-100 text-blue-600">
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
