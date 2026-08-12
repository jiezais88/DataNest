import request from './request';
import type {PageResult, Result} from '@/types/common';
import type {
    DataSource,
    DataSourceCreateRequest,
    DataSourceQueryParams,
    DataSourceUpdateRequest,
    TestConnectionRequest,
    TestConnectionResult,
} from '@/types/datasource';

export function getDataSources(params: DataSourceQueryParams) {
    return request.post<Result<PageResult<DataSource>>>('/engineering/datasources/page', params);
}

export interface DataSourceStats {
    normal: number;
    error: number;
    offline: number;
    unknown: number;
}

/** 数据源连接状态统计（顶部统计卡） */
export function getDataSourceStats() {
    return request.get<Result<DataSourceStats>>('/engineering/datasources/stats');
}

export function getDataSource(id: string) {
    return request.get<Result<DataSource>>(`/engineering/datasources/${id}`);
}

export function createDataSource(data: DataSourceCreateRequest) {
    return request.post<Result<DataSource>>('/engineering/datasources', data);
}

export function updateDataSource(id: string, data: DataSourceUpdateRequest) {
    return request.put<Result<DataSource>>(`/engineering/datasources/${id}`, data);
}

export function deleteDataSource(id: string) {
    return request.delete<Result<null>>(`/engineering/datasources/${id}`);
}

export function testConnection(data: TestConnectionRequest) {
    return request.post<Result<TestConnectionResult>>('/engineering/datasources/test', data);
}

export function testSavedDataSource(id: string) {
    return request.post<Result<TestConnectionResult>>(`/engineering/datasources/${id}/test`);
}
