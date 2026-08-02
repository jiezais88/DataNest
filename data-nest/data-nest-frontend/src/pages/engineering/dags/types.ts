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
    createdByName?: string;
    updatedByName?: string;
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
    createdByName?: string;
    updatedByName?: string;
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
    /** 执行实例创建时的边快照 JSON（[{source,target},...]）；老实例无此数据，渲染边时回退当前定义 */
    edgeSnapshot?: string;
    /** 执行失败原因（如 DS 工作流未上线），仅 FAILED 时可能有值 */
    errorMessage?: string;
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
    syncJobId?: string | number;
    syncJobHistoryId?: string | number;
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
    /** 单条语句耗时（可选：后端未返回时不展示对应内容） */
    durationMs?: number;
    /** 结果集被截断（仅返回前 N 行）时为 true */
    truncated?: boolean;
    message?: string;
    error?: string;
}

export interface SqlPreviewResult {
    statements: SqlStatementResult[];
}
