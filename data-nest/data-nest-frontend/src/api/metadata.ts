import request from './request';
import type {
    MetadataColumn,
    MetadataCommentRequest,
    MetadataDatasource,
    MetadataTable,
    MetadataTreeNode,
    PermissionTreeDatasource,
    SensitivityAuditItem,
    SensitivityTableItem,
} from '@/types/metadata';
import type {PageResult, Result} from '@/types/common';

interface MetadataRemarkRequest {
    remark: string;
}

export function listMetadataDatasourceIds() {
    return request.get<Result<MetadataDatasource[]>>('/governance/metadata/datasources');
}

/** 权限配置树（Sprint 11 F2）：一次性返回可配置的外部数据源→库→表三级结构 */
export function getPermissionTree() {
    return request.get<Result<PermissionTreeDatasource[]>>('/governance/metadata/permission-tree');
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

// ============ Sprint 10 F5：数据分级分类 ============

/** 分级表列表（敏感度/数据源筛选 + 库/模式/表关键词，仅 ONLINE 表） */
export function pageSensitivityTables(params: {
    page: number;
    pageSize: number;
    sensitivityLevel?: string;
    keyword?: string;
    datasourceId?: string;
}) {
    const search = new URLSearchParams();
    search.set('page', String(params.page));
    search.set('pageSize', String(params.pageSize));
    if (params.sensitivityLevel) search.set('sensitivityLevel', params.sensitivityLevel);
    if (params.keyword) search.set('keyword', params.keyword);
    if (params.datasourceId) search.set('datasourceId', params.datasourceId);
    return request.get<Result<PageResult<SensitivityTableItem>>>(`/governance/metadata/sensitivity/tables?${search.toString()}`);
}

/** 单表改级（机密降级必经 INTERNAL 两步，后端兜底） */
export function updateTableSensitivity(tableId: string, newLevel: string) {
    return request.put<Result<number>>(`/governance/metadata/tables/${tableId}/sensitivity`, {newLevel});
}

/** 批量改级（全有或全无） */
export function batchUpdateTableSensitivity(tableIds: string[], newLevel: string) {
    return request.post<Result<number>>('/governance/metadata/tables/sensitivity/batch', {tableIds, newLevel});
}

/** 内部表 API 特批开放（仅超管；apiExempted 0/1） */
export function updateTableApiExempt(tableId: string, apiExempted: number) {
    return request.put<Result<null>>(`/governance/metadata/tables/${tableId}/api-exempt`, {apiExempted});
}

/** 分级变更审计（改级 + 特批开放留痕） */
export function pageSensitivityAudit(page: number, pageSize: number) {
    return request.get<Result<PageResult<SensitivityAuditItem>>>(`/governance/metadata/sensitivity/audit?page=${page}&pageSize=${pageSize}`);
}
