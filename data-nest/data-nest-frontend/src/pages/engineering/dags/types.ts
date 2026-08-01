// DAG 节点/边/请求类型
export type NodeType = 'SQL' | 'SYNC';

export interface DagNodeConfig {
    type: NodeType;
    // SQL
    sqlContent?: string;
    // SYNC
    syncJobId?: number;
    syncJobName?: string;
}

export interface DagNode {
    id?: number;
    nodeId: string;
    nodeName: string;
    nodeType: NodeType;
    positionX: number;
    positionY: number;
    config: string;   // JSON string
}

export interface DagEdge {
    id?: number;
    edgeId: string;
    sourceNodeId: string;
    targetNodeId: string;
}

export interface Dag {
    // Snowflake id 是 19 位数字，超 JS Number.MAX_SAFE_INTEGER，全程当 string 用
    id?: string | number;
    projectId: string | number;
    name: string;
    triggerType: 'MANUAL' | 'CRON';
    cronExpression?: string;
    scheduleEnabled: boolean;
    maxParallelism: number;
    status: 'ENABLED' | 'DISABLED';
    dsProjectCode?: number;
    dsProcessDefinitionCode?: number;
    releaseState?: string;
    createdAt?: string;
    updatedAt?: string;
    nodes?: DagNode[];
    edges?: DagEdge[];
    /** Sprint 3 性能优化：后端聚合的节点摘要，列表页直接用 */
    nodeSummary?: string;
    /** Sprint 3 性能优化：后端聚合的最近一次执行 */
    latestExecution?: {
        status: string;
        startTime?: string;
        endTime?: string;
    };
}

export interface DagProject {
    id?: string | number;
    name: string;
    description?: string;
    createdAt?: string;
    updatedAt?: string;
    dagCount?: number;
}

export interface DagExecution {
    id?: string | number;
    dagId?: string | number;
    dagName?: string;
    dsProcessInstanceId?: number;
    triggerType?: string;
    status: string;
    startTime?: string;
    endTime?: string;
    durationMs?: number;
    nodeExecutions?: NodeExecution[];
}

export interface NodeExecution {
    id?: string | number;
    executionId?: string | number;
    nodeId?: string;
    nodeName?: string;
    nodeType?: string;
    status: string;
    dsTaskInstanceId?: number;
    startTime?: string;
    endTime?: string;
    durationMs?: number;
    errorMessage?: string;
    outputInfo?: string;
}

/**
 * SQL preview result for the "Run Test" button in the SQL editor modal.
 * Sprint 3: each statement runs independently; failure of one does not block others.
 */

/** JDBC 返回的标量单元格值 */
export type SqlCellValue = string | number | boolean | null;

export interface SqlStatementResult {
    stmt: string;
    status: 'SUCCESS' | 'FAILED';
    type: 'QUERY' | 'DML' | 'DDL' | 'UNKNOWN';
    rowCount: number;
    columns?: string[];
    rows?: SqlCellValue[][];
    message?: string;
    error?: string;
}

export interface SqlPreviewResult {
    statements: SqlStatementResult[];
}
