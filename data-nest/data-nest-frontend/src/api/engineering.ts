import request from './request';
import type {Result} from '@/types/common';

export function getDataSourceSchemas(datasourceId: string) {
    return request.get<Result<string[]>>(`/engineering/datasources/${datasourceId}/schemas`);
}

export function getDataSourceDatabases(datasourceId: string) {
    return request.get<Result<string[]>>(`/engineering/datasources/${datasourceId}/databases`);
}

export function getDataSourceTables(datasourceId: string, database: string | undefined, schema: string | undefined) {
    const params = new URLSearchParams();
    if (database) params.append('database', database);
    if (schema) params.append('schema', schema);
    return request.get<Result<string[]>>(`/engineering/datasources/${datasourceId}/tables?${params.toString()}`);
}
