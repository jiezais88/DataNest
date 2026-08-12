import request from './request';
import type {
    SyncJob,
    SyncJobCreateRequest,
    SyncJobHistory,
    SyncJobHistoryQueryParams,
    SyncJobLog,
    SyncJobQueryParams,
} from '@/types/sync';
import type {PageResult, Result} from '@/types/common';

export function createSyncJob(data: SyncJobCreateRequest) {
    return request.post<Result<SyncJob>>('/engineering/sync-jobs', data);
}

export function updateSyncJob(id: string, data: SyncJobCreateRequest) {
    return request.put<Result<SyncJob>>(`/engineering/sync-jobs/${id}`, data);
}

export function deleteSyncJob(id: string) {
    return request.delete<Result<null>>(`/engineering/sync-jobs/${id}`);
}

export function getSyncJob(id: string) {
    return request.get<Result<SyncJob>>(`/engineering/sync-jobs/${id}`);
}

export function querySyncJobs(params: SyncJobQueryParams) {
    return request.post<Result<PageResult<SyncJob>>>('/engineering/sync-jobs/page', params);
}

export function executeSyncJob(id: string) {
    return request.post<Result<null>>(`/engineering/sync-jobs/${id}/execute`);
}

export function startSyncJobSchedule(id: string) {
    return request.post<Result<null>>(`/engineering/sync-jobs/${id}/schedule/start`);
}

export function stopSyncJobSchedule(id: string) {
    return request.post<Result<null>>(`/engineering/sync-jobs/${id}/schedule/stop`);
}

export function querySyncJobHistory(params: SyncJobHistoryQueryParams) {
    if (params.syncJobId) {
        return request.post<Result<PageResult<SyncJobHistory>>>(`/engineering/sync-jobs/${params.syncJobId}/history/page`, params);
    }
    return request.post<Result<PageResult<SyncJobHistory>>>('/engineering/sync-jobs/history/page', params);
}

export function queryAllSyncJobHistory(params: SyncJobHistoryQueryParams) {
    return request.post<Result<PageResult<SyncJobHistory>>>('/engineering/sync-jobs/history/page', params);
}

export interface SyncJobStats {
    running: number;
    success: number;
    failed: number;
    terminated: number;
    pending: number;
}

/** 同步任务状态统计（顶部统计卡） */
export function getSyncJobStats() {
    return request.get<Result<SyncJobStats>>('/engineering/sync-jobs/stats');
}

export interface SyncJobHistoryStats {
    running: number;
    success: number;
    failed: number;
    terminated: number;
}

/** 同步执行历史状态统计（顶部统计卡，按时间范围聚合） */
export function getSyncJobHistoryStats(params: {startTimeFrom?: string; startTimeTo?: string}) {
    return request.get<Result<SyncJobHistoryStats>>('/engineering/sync-jobs/history/stats', {params});
}

export function getSyncJobLogs(syncJobId: string, historyId: string, scope: string, page: number, pageSize: number) {
    return request.get<Result<PageResult<SyncJobLog>>>(`/engineering/sync-jobs/${syncJobId}/history/${historyId}/logs`, {
        params: {scope, page, pageSize},
    });
}

// 手动停止运行中的同步执行实例（停止后状态归一为 TERMINATED）
export function stopSyncHistory(historyId: string) {
    return request.post<Result<null>>(`/engineering/sync-jobs/history/${historyId}/stop`);
}
