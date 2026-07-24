export type DataSourceType = 'MYSQL' | 'POSTGRESQL' | 'DORIS';
export type DataSourceStatus = 'NORMAL' | 'ERROR' | 'OFFLINE' | 'UNKNOWN';

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
