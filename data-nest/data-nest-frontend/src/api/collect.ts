import request from './request';
import type {
    CollectExecutionLog,
    CollectHistoryQueryParams,
    CollectTask,
    CollectTaskCreateRequest,
    CollectTaskExecution,
    CollectTaskQueryParams,
    DataSourceReferenceDTO,
} from '@/types/collect';
import type {PageResult, Result} from '@/types/common';

export function createCollectTask(data: CollectTaskCreateRequest) {
    return request.post<Result<CollectTask>>('/governance/collect-tasks', data);
}

export function updateCollectTask(id: string, data: CollectTaskCreateRequest) {
    return request.put<Result<CollectTask>>(`/governance/collect-tasks/${id}`, data);
}

export function deleteCollectTask(id: string) {
    return request.delete<Result<null>>(`/governance/collect-tasks/${id}`);
}

export function getCollectTask(id: string) {
    return request.get<Result<CollectTask>>(`/governance/collect-tasks/${id}`);
}

export function queryCollectTasks(params: CollectTaskQueryParams) {
    return request.post<Result<PageResult<CollectTask>>>('/governance/collect-tasks/page', params);
}

export function executeCollectTask(id: string) {
    return request.post<Result<null>>(`/governance/collect-tasks/${id}/execute`);
}

export function startCollectTaskSchedule(id: string) {
    return request.post<Result<null>>(`/governance/collect-tasks/${id}/schedule/start`);
}

export function stopCollectTaskSchedule(id: string) {
    return request.post<Result<null>>(`/governance/collect-tasks/${id}/schedule/stop`);
}


export function getDataSourceReferences(datasourceId: string) {
    return request.get<Result<DataSourceReferenceDTO[]>>(`/governance/collect-tasks/datasources/${datasourceId}/references`);
}

export function queryCollectHistory(params: CollectHistoryQueryParams) {
    if (params.taskId) {
        return request.post<Result<PageResult<CollectTaskExecution>>>(`/governance/collect-tasks/${params.taskId}/history/page`, params);
    }
    return request.post<Result<PageResult<CollectTaskExecution>>>('/governance/collect-tasks/global-history/page', params);
}

export function queryAllCollectHistory(params: CollectHistoryQueryParams) {
    return request.post<Result<PageResult<CollectTaskExecution>>>('/governance/collect-tasks/global-history/page', params);
}

export function getCollectHistory(taskId: string, historyId: string) {
    return request.get<Result<CollectTaskExecution>>(`/governance/collect-tasks/${taskId}/history/${historyId}`);
}

export function getCollectHistoryLogs(taskId: string, historyId: string) {
    return request.get<Result<CollectExecutionLog[]>>(`/governance/collect-tasks/${taskId}/history/${historyId}/logs`);
}

// 手动停止运行中的采集执行实例（停止后状态归一为 TERMINATED）
export function stopCollectHistory(historyId: string) {
    return request.post<Result<null>>(`/governance/collect-tasks/history/${historyId}/stop`);
}
