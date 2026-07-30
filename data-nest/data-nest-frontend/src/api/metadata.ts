import request from './request';
import type {
    MetadataColumn,
    MetadataCommentRequest,
    MetadataDatasource,
    MetadataTable,
    MetadataTreeNode
} from '../types/metadata';
import type {Result} from './datasource';

interface MetadataRemarkRequest {
    remark: string;
}

export function listMetadataDatasourceIds() {
    return request.get<Result<MetadataDatasource[]>>('/governance/metadata/datasources');
}

export function searchMetadataTree(keyword: string) {
    return request.get<Result<MetadataTreeNode[]>>(`/governance/metadata/search-tree?keyword=${encodeURIComponent(keyword)}`);
}

export function listMetadataDatabases(datasourceId: string) {
    return request.get<Result<string[]>>(`/governance/metadata/datasources/${datasourceId}/databases`);
}

export function listMetadataSchemas(datasourceId: string, databaseName: string) {
    return request.get<Result<string[]>>(`/governance/metadata/datasources/${datasourceId}/databases/${databaseName}/schemas`);
}

export function listMetadataTables(datasourceId: string, databaseName: string, schemaName: string) {
    return request.get<Result<MetadataTable[]>>(`/governance/metadata/datasources/${datasourceId}/databases/${databaseName}/schemas/${schemaName}/tables`);
}

export function listMetadataTablesWithoutSchema(datasourceId: string, databaseName: string) {
    return request.get<Result<MetadataTable[]>>(`/governance/metadata/datasources/${datasourceId}/databases/${databaseName}/tables`);
}

export function listBuiltinDorisDatabases() {
    return request.get<Result<string[]>>('/governance/metadata/builtin-doris/databases');
}

export function listBuiltinDorisTables(databaseName: string) {
    return request.get<Result<string[]>>(`/governance/metadata/builtin-doris/databases/${databaseName}/tables`);
}

export function getMetadataTable(tableId: string) {
    return request.get<Result<MetadataTable>>(`/governance/metadata/tables/${tableId}`);
}

export function listMetadataColumns(tableId: string) {
    return request.get<Result<MetadataColumn[]>>(`/governance/metadata/tables/${tableId}/columns`);
}

export function updateTableComment(tableId: string, manualComment: string) {
    return request.put<Result<null>>(`/governance/metadata/tables/${tableId}/comment`, {manualComment} as MetadataCommentRequest);
}

export function updateColumnComment(columnId: string, manualComment: string) {
    return request.put<Result<null>>(`/governance/metadata/columns/${columnId}/comment`, {manualComment} as MetadataCommentRequest);
}

export function updateColumnRemark(columnId: string, remark: string) {
    return request.put<Result<null>>(`/governance/metadata/columns/${columnId}/remark`, {remark} as MetadataRemarkRequest);
}
