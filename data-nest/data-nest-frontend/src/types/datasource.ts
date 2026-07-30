import type {DataSourceStatus, DataSourceType} from '../constants/datasource';

export interface DataSource {
    id: string;
    name: string;
    type: DataSourceType;
    host: string;
    port: number;
    databaseName: string;
    schemaName?: string;
    username: string;
    passwordMasked?: string;
    description?: string;
    status: DataSourceStatus;
    lastTestTime?: string;
    errorMessage?: string;
    createdAt?: string;
    updatedAt?: string;
}

export interface DataSourceCreateRequest {
    name: string;
    type: DataSourceType;
    host: string;
    port: number;
    databaseName: string;
    schemaName?: string;
    username: string;
    password: string;
    description?: string;
    autoCollectOnSave?: boolean;
}

export interface DataSourceUpdateRequest {
    type: DataSourceType;
    host: string;
    port: number;
    databaseName: string;
    schemaName?: string;
    username: string;
    password?: string;
    passwordChanged: boolean;
    description?: string;
    autoCollectOnSave?: boolean;
}

export interface TestConnectionRequest {
    type: DataSourceType;
    host: string;
    port: number;
    databaseName: string;
    schemaName?: string;
    username: string;
    password: string;
}

export interface TestConnectionResult {
    success: boolean;
    message: string;
}

export interface DataSourceQueryParams {
    keyword?: string;
    type?: DataSourceType | '';
    status?: DataSourceStatus | '';
    page: number;
    pageSize: number;
}

export interface DataSourceReference {
    taskId: string;
    taskName: string;
    status?: string;
    type: 'COLLECT' | 'SYNC';
    sourceDatabase?: string;
    targetDatabase?: string;
    targetTable?: string;
}
