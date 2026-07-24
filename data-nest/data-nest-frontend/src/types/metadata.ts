export interface MetadataTable {
    id: string;
    datasourceId: string;
    databaseName: string;
    schemaName?: string;
    tableName: string;
    tableComment?: string;
    manualComment?: string;
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
    isNullable?: boolean;
    columnDefault?: string;
    manualComment?: string;
    lastCollectHistoryId?: string;
    createdAt?: string;
    updatedAt?: string;
}

export interface MetadataCommentRequest {
    manualComment: string;
}

export interface MetadataTreeNode {
    id: string;
    type: 'datasource' | 'database' | 'schema' | 'table';
    name: string;
    children?: MetadataTreeNode[];
    count?: number;
    databaseName?: string;
    schemaName?: string;
}
