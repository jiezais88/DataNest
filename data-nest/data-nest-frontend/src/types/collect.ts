export type CollectMode = 'FULL' | 'FULL_INCREMENT';
export type TaskTriggerType = 'MANUAL' | 'CRON';
export type TaskStatus = 'NEVER_EXECUTED' | 'RUNNING' | 'SUCCESS' | 'FAILED';
export type ExecutionStatus = 'RUNNING' | 'SUCCESS' | 'FAILED' | 'PARTIAL';
export type LogLevel = 'INFO' | 'WARN' | 'ERROR';

export interface CollectTask {
    id: string;
    name: string;
    datasourceId: string;
    datasourceName?: string;
    scope?: string[];
    collectMode: CollectMode;
    triggerType: TaskTriggerType;
    cronExpression?: string;
    status: TaskStatus;
    lastHistoryId?: string;
    lastExecuteTime?: string;
    scheduleEnabled?: number;
    description?: string;
    createdAt?: string;
    updatedAt?: string;
}

export interface CollectTaskCreateRequest {
    name: string;
    datasourceId: string;
    scope?: string[];
    collectMode: CollectMode;
    triggerType: TaskTriggerType;
    cronExpression?: string;
    description?: string;
}

export type CollectTaskUpdateRequest = CollectTaskCreateRequest;

export interface CollectTaskQueryParams {
    keyword?: string;
    status?: TaskStatus | '';
    page: number;
    pageSize: number;
}

export interface CollectTaskExecution {
    id: string;
    taskId: string;
    taskName: string;
    triggerType: TaskTriggerType;
    status: ExecutionStatus;
    startedAt?: string;
    endedAt?: string;
    durationMs?: number;
    dbCount: number;
    tableCount: number;
    columnCount: number;
    addedTableCount: number;
    updatedTableCount: number;
    deletedTableCount: number;
    addedColumnCount: number;
    updatedColumnCount: number;
    deletedColumnCount: number;
    errorMessage?: string;
    createdAt?: string;
    changeDetails?: CollectChangeDetailDTO[];
}

export interface CollectHistoryQueryParams {
    taskId?: string;
    status?: ExecutionStatus | '';
    page: number;
    pageSize: number;
}

export interface CollectExecutionLog {
    id: string;
    historyId: string;
    taskId?: string;
    level: LogLevel;
    message: string;
    createdAt?: string;
}

export interface CollectChangeDetailDTO {
    id: string;
    historyId: string;
    changeType: 'ADDED_TABLE' | 'DELETED_TABLE' | 'MODIFIED_TABLE';
    databaseName: string;
    schemaName?: string;
    tableName: string;
    columnName?: string;
    oldValue?: string;
    newValue?: string;
    createdAt?: string;
}

export interface DataSourceReferenceDTO {
    taskId: string;
    taskName: string;
    status: string;
}
