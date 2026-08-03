// DAG 节点/边/请求类型
export type NodeType = 'SQL' | 'SYNC' | 'PYTHON';

export interface DagNodeConfig {
    type: NodeType;
    // SQL
    sqlContent?: string;
    // SYNC
    syncJobId?: number;
    syncJobName?: string;
    // PYTHON
    pythonScript?: string;
    timeoutMinutes?: number;
    memoryLimitMb?: number;
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

// =================== Sprint 4：DAG 参数 / 版本 / 告警 / Python / 实时日志 ===================

/** DAG 自定义参数（对齐后端 DagParameterPayload） */
export interface DagParameter {
    id?: string | number;
    dagId?: string | number;
    paramName: string;
    /** STRING / NUMBER / DATE / BOOLEAN */
    paramType: string;
    defaultValue?: string;
    required: boolean;
    description?: string;
    createdAt?: string;
    updatedAt?: string;
}

/** Python 节点「运行测试」结果（对齐 task-core PythonExecuteResult） */
export interface PythonExecuteResult {
    success: boolean;
    timeout?: boolean;
    stdout?: string;
    stderr?: string;
    exitCode?: number;
    output?: unknown;
    outputTables?: string[];
    durationMs?: number;
}

/** DAG 版本快照（对齐后端 DagVersionPayload；保存人只有 createdBy 数字 id） */
export interface DagVersion {
    id?: string | number;
    dagId?: string | number;
    versionNo: number;
    /** 快照 JSON 字符串：{"nodes":[...],"edges":[...],"params":[...]} */
    snapshot?: string;
    changeSummary?: string;
    createdBy?: string | number;
    createdByName?: string;
    createdAt?: string;
}

/** 版本对比结果：diff 项为字符串（nodeId / "a->b" / paramName） */
export interface DagVersionDiff {
    addedNodes?: string[];
    removedNodes?: string[];
    modifiedNodes?: string[];
    addedEdges?: string[];
    removedEdges?: string[];
    addedParams?: string[];
    removedParams?: string[];
    modifiedParams?: string[];
}

/** 按 DAG 告警配置（对齐后端 DagAlertConfigPayload；dagId 为 null 表示返回的是全局默认配置） */
export interface DagAlertConfig {
    id?: string | number;
    /** null 表示当前继承全局默认配置 */
    dagId?: string | number | null;
    enabled: boolean;
    /** 多个邮箱用分号分隔 */
    recipients?: string;
    /** ["FAILURE","TIMEOUT","SUCCESS"] 子集 */
    triggerConditions?: string[];
    timeoutMinutes?: number;
    createdAt?: string;
    updatedAt?: string;
}

/** 节点实时日志行（对齐后端 NodeExecutionLogDTO） */
export interface NodeExecutionLog {
    id?: string | number;
    executionId?: string | number;
    nodeId?: string;
    level?: string;
    message?: string;
    lineNum?: number;
    createdAt?: string;
}
