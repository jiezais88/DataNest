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
    createdAt?: string;
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
    createdAt?: string;
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
    violationType?: 'NAMING' | 'FIELD_TYPE';
    tableId?: string;
    tableName?: string;
    columnId?: string;
    columnName?: string;
    actualValue?: string;
    expectedValue?: string;
    applicableStandards?: ApplicableStandard[];
    isCompliant: number;
    checkedAt?: string;
}

export interface ComplianceCheckParams {
    datasourceIds?: string[];
    databaseName?: string;
    schemaName?: string;
    tableId?: string;
    checkNaming?: boolean;
    checkFieldType?: boolean;
    startTime?: string;
    endTime?: string;
}
