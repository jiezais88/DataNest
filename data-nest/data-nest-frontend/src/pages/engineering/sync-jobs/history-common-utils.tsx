import type {DataSource} from '../../../types/datasource';
import type {SyncHistoryStatus, SyncJob, SyncJobHistory, SyncMode, SyncTriggerType} from '../../../types/sync';

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

export function syncModeBadge(syncMode: SyncMode | string, incrementalField?: string) {
    if (syncMode === 'INCREMENTAL') {
        return (
            <span className="text-ds-small text-ds-text-secondary">
                增量同步{incrementalField ? ` (${incrementalField})` : ''}
            </span>
        );
    }
    return <span className="text-ds-small text-ds-text-secondary">全量同步</span>;
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

export function formatSourceToTarget(item: SyncJob, dataSources: DataSource[]) {
    const sourceDs = dataSources.find((ds) => String(ds.id) === String(item.sourceDatasourceId));
    const sourceDb = item.sourceDatabase || sourceDs?.databaseName || '';
    const sourceSchema = item.sourceSchema || sourceDs?.schemaName || '';
    const sourceTable = item.sourceTables?.[0] || '';
    const sourceParts = [sourceDb, sourceSchema, sourceTable].filter(Boolean);
    const source = sourceParts.length ? sourceParts.join('.') : '-';
    const target = item.targetTable ? `${item.targetDatabase || 'doris'}.${item.targetTable}` : '-';
    return `${source} → ${target}`;
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
