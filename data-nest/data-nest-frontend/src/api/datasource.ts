import request from './request';
import type {
    DataSource,
    DataSourceCreateRequest,
    DataSourceQueryParams,
    DataSourceUpdateRequest,
    TestConnectionRequest,
    TestConnectionResult,
} from '../types/datasource';

export interface PageResult<T> {
    records: T[];
    total: number;
    page: number;
    pageSize: number;
}

export interface Result<T> {
    code: number;
    message?: string;
    data: T;
}

export function getDataSources(params: DataSourceQueryParams) {
    return request.get<Result<PageResult<DataSource>>>('/engineering/datasources', {params});
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
