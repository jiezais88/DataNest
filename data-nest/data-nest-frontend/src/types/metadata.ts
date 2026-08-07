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
