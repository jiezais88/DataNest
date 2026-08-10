import request from './request';
import type {Result} from '@/types/common';

export interface PreviewResult {
    columns: string[];
    columnTypes?: Record<string, string>;
    rows: Array<Record<string, unknown>>;
    rowCount: number;
}

export function previewDataSource(datasourceId: string, database: string | undefined, schema: string | undefined, table: string) {
    const params = new URLSearchParams();
    if (database) params.append('database', database);
    if (schema) params.append('schema', schema);
    params.append('table', table);
    return request.get<Result<PreviewResult>>(`/engineering/datasources/${datasourceId}/preview?${params.toString()}`);
}

export function previewMetadataTable(tableId: string) {
    return request.get<Result<PreviewResult>>(`/governance/metadata/tables/${tableId}/preview`);
}
