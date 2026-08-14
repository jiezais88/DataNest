export interface MetadataTable {
    id: string;
    datasourceId: string;
    databaseName: string;
    schemaName?: string;
    tableName: string;
    tableComment?: string;
    manualComment?: string;
    columnCount?: number;
    lastCollectTime?: string;
    sourceTaskName?: string;
    datasourceName?: string;
    datasourceType?: string;
    lastCollectHistoryId?: string;
    /** Sprint 7 F1 资产目录：分类（数据域/主题，冗余存名称）与负责人 */
    dataDomain?: string;
    dataTopic?: string;
    ownerUserId?: string;
    ownerName?: string;
    /** Sprint 4 来源字段：SYNC / SQL / PYTHON 任务自动注册的表会带回来源 DAG/节点 */
    sourceType?: string;
    taskSourceType?: string;
    sourceDagId?: string | number;
    sourceDagName?: string;
    sourceNodeId?: string;
    sourceNodeName?: string;
    /** Sprint 10 F5：数据敏感度（PUBLIC 公开 / INTERNAL 内部 / CONFIDENTIAL 机密；未打标默认 PUBLIC） */
    sensitivityLevel?: string;
    /** Sprint 10 F5：内部表生成对外 API 的超管特批开放标记（1 = 已特批） */
    apiExempted?: number;
    createdByName?: string;
    createdAt?: string;
    updatedByName?: string;
    updatedAt?: string;
}

export interface MetadataColumn {
    id: string;
    tableId: string;
    columnName: string;
    columnComment?: string;
    dataType?: string;
    ordinalPosition?: number;
    nullable?: boolean;
    columnDefault?: string;
    manualComment?: string;
    remark?: string;
    lastCollectHistoryId?: string;
    createdAt?: string;
    updatedAt?: string;
}

export interface MetadataCommentRequest {
    manualComment: string;
}

export interface MetadataDatasource {
    id: string;
    name?: string;
    type?: string;
    sourceType?: string;
    exists: boolean;
}

export interface MetadataTreeNode {
    id: string;
    type: 'datasource' | 'database' | 'schema' | 'table';
    name: string;
    exists?: boolean;
    sourceType?: string;
    datasourceId?: string;
    children?: MetadataTreeNode[];
    count?: number;
    databaseName?: string;
    schemaName?: string;
    datasourceType?: string;
}

/** 数据分级分类表列表项（Sprint 10 F5，调 governance /metadata/sensitivity/tables） */
export interface SensitivityTableItem {
    tableId: string;
    tableName: string;
    databaseName: string;
    schemaName?: string;
    datasourceId: string;
    datasourceName?: string;
    sensitivityLevel: string;
    apiExempted?: number;
    sourceStatus: string;
    /** 任务来源类型（COLLECT/SYNC/SQL/PYTHON；手工录入为 null） */
    taskSourceType?: string;
    ownerName?: string;
    createdByName?: string;
    createdAt?: string;
    updatedByName?: string;
    updatedAt?: string;
}

/** 数据分级变更审计项（Sprint 10 F5） */
export interface SensitivityAuditItem {
    id: string;
    tableId: string;
    tableName: string;
    oldLevel?: string;
    newLevel: string;
    action: string;
    remark?: string;
    operatorName?: string;
    createdAt?: string;
}
