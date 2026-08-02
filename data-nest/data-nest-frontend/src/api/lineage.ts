import request from './request';
import type {Result} from '../types/common';
import type {LineageRecord} from '../types/lineage';

export function getLineageByTargetTable(tableName: string) {
    return request.get<Result<LineageRecord[]>>(`/governance/lineage/target/${encodeURIComponent(tableName)}`);
}

export function getLineageByDagId(dagId: number) {
    return request.get<Result<LineageRecord[]>>(`/governance/lineage/dag/${dagId}`);
}
