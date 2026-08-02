export interface LineageRecord {
    id: number;
    sourceTable?: string;
    targetTable: string;
    dagId?: number;
    dagName?: string;
    nodeId?: string;
    nodeName?: string;
    executionId?: number;
    lineageType: 'SQL' | 'SYNC' | 'PYTHON';
    createdAt: string;
}
