import request from './request';
import type {Result} from '@/types/common';
import type {LineageColumnLink, LineageGraphDTO, LineageRecord} from '@/types/lineage';

export function getLineageByTargetTable(tableName: string) {
    return request.get<Result<LineageRecord[]>>(`/governance/lineage/target/${encodeURIComponent(tableName)}`);
}

export function getLineageByDagId(dagId: number) {
    return request.get<Result<LineageRecord[]>>(`/governance/lineage/dag/${dagId}`);
}

// =================== Sprint 5：血缘图谱 / 字段级血缘 / 影响溯源 ===================
// 对齐后端 LineageController：/governance/lineage/graph|columns|impact|source
// 表名统一为「库名.表名」全名，URL 需 encodeURIComponent

export const getLineageGraph = (tableName: string, depth = 1) =>
    request.get<Result<LineageGraphDTO>>('/governance/lineage/graph', {
        params: {tableName, depth},
    }).then(r => r.data);

export const getLineageColumns = (tableName: string, columnName: string) =>
    request.get<Result<LineageColumnLink[]>>('/governance/lineage/columns', {
        params: {tableName, columnName},
    }).then(r => r.data);

export const getLineageImpact = (tableName: string, depth = 1) =>
    request.get<Result<LineageGraphDTO>>('/governance/lineage/impact', {
        params: {tableName, depth},
    }).then(r => r.data);

export const getLineageSource = (tableName: string, depth = 1) =>
    request.get<Result<LineageGraphDTO>>('/governance/lineage/source', {
        params: {tableName, depth},
    }).then(r => r.data);
