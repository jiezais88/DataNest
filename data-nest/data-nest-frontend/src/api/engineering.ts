import request from './request';
import type {Result} from './datasource';

export function getDataSourceSchemas(datasourceId: string) {
    return request.get<Result<string[]>>(`/engineering/datasources/${datasourceId}/schemas`);
}
