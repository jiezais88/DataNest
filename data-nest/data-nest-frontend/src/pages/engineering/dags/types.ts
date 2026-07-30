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
    id?: number;
    projectId: number;
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
}

export interface DagProject {
    id?: number;
    name: string;
    description?: string;
    createdAt?: string;
    updatedAt?: string;
}

export interface DagExecution {
    id?: number;
    dagId?: number;
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
    id?: number;
    executionId?: number;
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
