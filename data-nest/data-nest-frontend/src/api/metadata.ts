import request from './request';
import type {
    MetadataColumn,
    MetadataCommentRequest,
    MetadataDatasource,
    MetadataTable,
    MetadataTreeNode
} from '@/types/metadata';
import type {Result} from '@/types/common';

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

/** 按数据源立即触发一次元数据采集（SQL 终端「去采集」入口），返回 collectTaskId */
export function collectMetadataNow(datasourceId: string) {
    return request.post<Result<string>>(`/governance/metadata/datasources/${datasourceId}/collect-now`);
}

/** 查询采集任务状态（SQL 终端采集轮询用；status: NEVER_EXECUTED/RUNNING/SUCCESS/FAILED/TERMINATED） */
export function getCollectTask(taskId: string) {
    return request.get<Result<{id: string; status: string}>>(`/governance/collect-tasks/${taskId}`);
}
