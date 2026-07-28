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
    createdAt?: string;
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
