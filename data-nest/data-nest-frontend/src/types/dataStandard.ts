export type AppliesTo = 'TABLE' | 'COLUMN';
export type RuleType = 'PREFIX' | 'SUFFIX' | 'REGEX';

export interface NamingStandard {
    id: string;
    name: string;
    appliesTo: AppliesTo;
    ruleType: RuleType;
    ruleValue: string;
    targetStandardId: string;
    targetStandardName?: string;
    priority: number;
    enabled: number;
    description?: string;
    createdByName?: string;
    createdAt?: string;
    updatedByName?: string;
    updatedAt?: string;
}

export interface NamingStandardCreateRequest {
    name: string;
    appliesTo: AppliesTo;
    ruleType: RuleType;
    ruleValue: string;
    targetStandardId: string;
    priority?: number;
    enabled?: number;
    description?: string;
}

export type NamingStandardUpdateRequest = NamingStandardCreateRequest;

export interface NamingStandardQueryParams {
    keyword?: string;
    appliesTo?: AppliesTo | '';
    enabled?: number;
    page: number;
    pageSize: number;
}

export interface FieldTypeStandard {
    id: string;
    name: string;
    category?: string;
    allowedTypes: string[];
    description?: string;
    createdByName?: string;
    createdAt?: string;
    updatedByName?: string;
    updatedAt?: string;
}

export interface FieldTypeStandardCreateRequest {
    name: string;
    category?: string;
    allowedTypes: string[];
    description?: string;
}

export type FieldTypeStandardUpdateRequest = FieldTypeStandardCreateRequest;

export interface FieldTypeStandardQueryParams {
    keyword?: string;
    category?: string;
    page: number;
    pageSize: number;
}

export interface ApplicableStandard {
    standardName?: string;
    ruleType?: string;
    ruleValue?: string;
    allowedTypes?: string[];
}

export interface ComplianceCheckResult {
    id: string;
    standardId: string;
    standardName?: string;
    objectType: 'TABLE' | 'COLUMN';
    objectPath?: string;
    objectName: string;
    /** 后端语义：NAMING / TYPE（字段类型用 TYPE，非 FIELD_TYPE） */
    violationType?: 'NAMING' | 'TYPE';
    tableId?: string;
    tableName?: string;
    columnId?: string;
    columnName?: string;
    actualValue?: string;
    expectedValue?: string;
    applicableStandards?: ApplicableStandard[];
    isCompliant: number;
    checkedAt?: string;
    /** Sprint6：忽略标记（0=未忽略，1=已忽略） */
    ignored?: number;
    ignoredAt?: string;
    ignoredBy?: string;
}

export interface ComplianceCheckParams {
    datasourceIds?: string[];
    /** 单数数据源 ID（后端兼容字段，优先 datasourceIds） */
    datasourceId?: string;
    databaseName?: string;
    schemaName?: string;
    tableId?: string;
    checkNaming?: boolean;
    checkFieldType?: boolean;
    startTime?: string;
    endTime?: string;
}

/** 合规检查分页查询参数（ComplianceCheckPageRequest） */
export interface ComplianceCheckPageParams extends ComplianceCheckParams {
    page: number;
    pageSize: number;
    /** 忽略状态筛选：null/缺省=仅未忽略(0)；1=仅已忽略；2=全部 */
    ignored?: number;
    /** 违规类型筛选：NAMING / TYPE，可空 */
    violationType?: 'NAMING' | 'TYPE';
}

/** 合规统计摘要（三格统计：不合规项 / 已忽略 / 合规率） */
export interface ComplianceCheckSummary {
    nonCompliant: number;
    ignored: number;
    totalObjects: number;
    complianceRate: number;
}
