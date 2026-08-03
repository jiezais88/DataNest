import type {DataSource} from '../../../types/datasource';
import type {SyncHistoryStatus, SyncJob, SyncJobHistory, SyncMode, SyncTriggerType} from '../../../types/sync';
import TriggerBadge from '../../../components/TriggerBadge';

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
    // 收敛实现：统一走 components/TriggerBadge（ds token 语义色，Phase 7-K2）
    return <TriggerBadge type={triggerType}/>;
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
    const tables = item.sourceTables?.length ? item.sourceTables : (item.sourceTable ? [item.sourceTable] : []);
    if (tables.length > 1) {
        return `${tables.length} 张表`;
    }
    const db = item.sourceDatabase || '';
    const schema = item.sourceSchema && item.sourceSchema !== db ? item.sourceSchema : '';
    const parts = [db, schema, tables[0]].filter(Boolean);
    return parts.length ? parts.join('.') : '-';
}

export function formatTargetTable(item: SyncJobHistory) {
    const tables = item.sourceTables?.length ? item.sourceTables : (item.sourceTable ? [item.sourceTable] : []);
    if (tables.length > 1) {
        return `${tables.length} 张目标表`;
    }
    const db = item.targetDatabase || 'doris';
    return item.targetTable ? `doris.${db}.${item.targetTable}` : '-';
}
