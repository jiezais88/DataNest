export const DataSourceTypeEnum = {
    MYSQL: 'MYSQL',
    POSTGRESQL: 'POSTGRESQL',
    DORIS: 'DORIS',
    ORACLE: 'ORACLE',
    SQLSERVER: 'SQLSERVER',
} as const;

export type DataSourceType = typeof DataSourceTypeEnum[keyof typeof DataSourceTypeEnum];

export const DataSourceStatusEnum = {
    NORMAL: 'NORMAL',
    ERROR: 'ERROR',
    OFFLINE: 'OFFLINE',
    UNKNOWN: 'UNKNOWN',
} as const;

export type DataSourceStatus = typeof DataSourceStatusEnum[keyof typeof DataSourceStatusEnum];

export const SourceTypeEnum = {
    EXTERNAL: 'EXTERNAL',
    BUILTIN_DORIS: 'BUILTIN_DORIS',
} as const;

export type SourceType = typeof SourceTypeEnum[keyof typeof SourceTypeEnum];

export const DB_TYPES_WITHOUT_SCHEMA: Set<DataSourceType> = new Set([
    DataSourceTypeEnum.MYSQL,
    DataSourceTypeEnum.DORIS,
]);

export function isWithoutSchema(type?: string): boolean {
    if (!type) return false;
    return DB_TYPES_WITHOUT_SCHEMA.has(type.toUpperCase() as DataSourceType);
}

export const DEFAULT_PORTS: Record<DataSourceType, number> = {
    [DataSourceTypeEnum.MYSQL]: 3306,
    [DataSourceTypeEnum.POSTGRESQL]: 5432,
    [DataSourceTypeEnum.DORIS]: 9030,
    [DataSourceTypeEnum.ORACLE]: 1521,
    [DataSourceTypeEnum.SQLSERVER]: 1433,
};

export const TYPE_OPTIONS: { value: DataSourceType | ''; label: string }[] = [
    {value: '', label: '全部类型'},
    {value: DataSourceTypeEnum.MYSQL, label: 'MySQL'},
    {value: DataSourceTypeEnum.POSTGRESQL, label: 'PostgreSQL'},
    {value: DataSourceTypeEnum.DORIS, label: 'Doris'},
    {value: DataSourceTypeEnum.ORACLE, label: 'Oracle'},
    {value: DataSourceTypeEnum.SQLSERVER, label: 'SQL Server'},
];
