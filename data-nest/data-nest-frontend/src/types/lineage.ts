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

// =================== Sprint 5：血缘图谱 / 字段级血缘 ===================

/** 血缘图谱节点（表节点），对齐后端 LineageNodeDTO */
export interface LineageNodeDTO {
    /** 唯一 ID：库名.表名 全名 */
    id: string;
    /** 展示名：库名.表名 */
    name: string;
    /** 库名（表名无 schema 时为空） */
    database?: string;
    /** 血缘类型：SQL / SYNC / PYTHON */
    type?: string;
    /** 是否当前查询的表 */
    current?: boolean;
}

/** 血缘图谱边（source → target），对齐后端 LineageEdgeDTO */
export interface LineageEdgeDTO {
    source: string;
    target: string;
    /** SQL / SYNC / PYTHON */
    lineageType?: string;
}

/** 表级血缘图谱数据，对齐后端 LineageGraphDTO */
export interface LineageGraphDTO {
    nodes: LineageNodeDTO[];
    edges: LineageEdgeDTO[];
}

/** 字段级血缘链路（source 列 → target 列），对齐后端 LineageColumnLinkDTO */
export interface LineageColumnLink {
    sourceTable: string;
    sourceColumn: string;
    targetTable: string;
    targetColumn: string;
    /** SQL / SYNC / PYTHON */
    lineageType?: string;
    dagId?: number;
    dagName?: string;
    nodeId?: string;
    nodeName?: string;
    executionId?: number;
    createdAt?: string;
}
