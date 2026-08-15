/**
 * 按钮级权限点前端映射表（Sprint 11 F2，对齐后端 common PermissionCode + Flyway 种子）。
 *
 * 唯一权威来源：后端 GET /system/permissions 返回 code + name；本文件只维护
 * ① 权限点 code 常量（供 usePermission 判断 + 按钮显隐）② 模块分组与中文名（供角色勾选树分组展示）。
 * 菜单动态渲染（F6）与按钮显隐逐步迁移到权限点后，roles 判断（constants/roles.ts）逐步退役。
 */

// ============ 权限点 code 常量 ============
export const PERM = {
    // 数据源管理
    DATASOURCE_VIEW: 'datasource:view',
    DATASOURCE_CREATE: 'datasource:create',
    DATASOURCE_UPDATE: 'datasource:update',
    DATASOURCE_DELETE: 'datasource:delete',
    DATASOURCE_TEST: 'datasource:test',
    // 批量同步任务
    SYNC_VIEW: 'sync:view',
    SYNC_CREATE: 'sync:create',
    SYNC_UPDATE: 'sync:update',
    SYNC_DELETE: 'sync:delete',
    SYNC_EXECUTE: 'sync:execute',
    SYNC_HISTORY: 'sync:history',
    // CDC 管道
    CDC_VIEW: 'cdc:view',
    CDC_CREATE: 'cdc:create',
    CDC_UPDATE: 'cdc:update',
    CDC_DELETE: 'cdc:delete',
    CDC_EXECUTE: 'cdc:execute',
    CDC_MONITOR: 'cdc:monitor',
    // DAG 编排
    DAG_VIEW: 'dag:view',
    DAG_CREATE: 'dag:create',
    DAG_UPDATE: 'dag:update',
    DAG_DELETE: 'dag:delete',
    DAG_EXECUTE: 'dag:execute',
    DAG_HISTORY: 'dag:history',
    // 任务模板库
    TEMPLATE_VIEW: 'template:view',
    TEMPLATE_CREATE: 'template:create',
    TEMPLATE_UPDATE: 'template:update',
    TEMPLATE_DELETE: 'template:delete',
    // 元数据
    METADATA_VIEW: 'metadata:view',
    METADATA_COMMENT: 'metadata:comment',
    METADATA_LINEAGE: 'metadata:lineage',
    // 元数据采集任务
    COLLECT_VIEW: 'collect:view',
    COLLECT_CREATE: 'collect:create',
    COLLECT_UPDATE: 'collect:update',
    COLLECT_DELETE: 'collect:delete',
    COLLECT_EXECUTE: 'collect:execute',
    COLLECT_HISTORY: 'collect:history',
    // 数据标准
    STANDARD_VIEW: 'standard:view',
    STANDARD_CREATE: 'standard:create',
    STANDARD_UPDATE: 'standard:update',
    STANDARD_DELETE: 'standard:delete',
    // 标准合规
    COMPLIANCE_VIEW: 'compliance:view',
    COMPLIANCE_HANDLE: 'compliance:handle',
    // 质量规则
    QUALITY_RULE_VIEW: 'quality_rule:view',
    QUALITY_RULE_CREATE: 'quality_rule:create',
    QUALITY_RULE_UPDATE: 'quality_rule:update',
    QUALITY_RULE_DELETE: 'quality_rule:delete',
    // 质量任务
    QUALITY_JOB_VIEW: 'quality_job:view',
    QUALITY_JOB_CREATE: 'quality_job:create',
    QUALITY_JOB_UPDATE: 'quality_job:update',
    QUALITY_JOB_DELETE: 'quality_job:delete',
    QUALITY_JOB_EXECUTE: 'quality_job:execute',
    QUALITY_JOB_HISTORY: 'quality_job:history',
    // 质量结果
    QUALITY_RESULT_SCORE: 'quality_result:score',
    QUALITY_RESULT_REPORT: 'quality_result:report',
    // 资产目录
    ASSET_VIEW: 'asset:view',
    ASSET_COLLAB: 'asset:collab',
    ASSET_COMMENT: 'asset:comment',
    ASSET_MANAGE: 'asset:manage',
    // SQL 查询终端
    SQL_EXECUTE: 'sql:execute',
    SQL_EXPORT: 'sql:export',
    SQL_HISTORY: 'sql:history',
    // 数据 API
    API_VIEW: 'api:view',
    API_CREATE: 'api:create',
    API_UPDATE: 'api:update',
    API_PUBLISH: 'api:publish',
    API_DELETE: 'api:delete',
    API_STATS: 'api:stats',
    // API Key 管理
    API_KEY_VIEW: 'api_key:view',
    API_KEY_CREATE: 'api_key:create',
    API_KEY_TOGGLE: 'api_key:toggle',
    API_KEY_DELETE: 'api_key:delete',
    // 数据分级分类
    SENSITIVITY_VIEW: 'sensitivity:view',
    SENSITIVITY_CHANGE: 'sensitivity:change',
    SENSITIVITY_BATCH_CHANGE: 'sensitivity:batch_change',
    // 告警中心
    ALERT_VIEW: 'alert:view',
    ALERT_RULE_MANAGE: 'alert:rule_manage',
    // 系统管理类
    USER_VIEW: 'user:view',
    USER_CREATE: 'user:create',
    USER_UPDATE: 'user:update',
    USER_TOGGLE: 'user:toggle',
    USER_RESET_PWD: 'user:reset_pwd',
    ROLE_VIEW: 'role:view',
    ROLE_CREATE: 'role:create',
    ROLE_UPDATE: 'role:update',
    ROLE_DELETE: 'role:delete',
    DATA_PERMISSION_MANAGE: 'data_permission:manage',
    AUDIT_VIEW: 'audit:view',
    QUEUE_MANAGE: 'queue:manage',
} as const;

export type PermissionCode = (typeof PERM)[keyof typeof PERM];

// ============ 模块分组（供角色勾选树，按 code 冒号前缀分组） ============
export interface PermissionModule {
    /** code 前缀，如 datasource */
    prefix: string;
    /** 模块中文名，如 数据源管理 */
    label: string;
}

export const PERMISSION_MODULES: PermissionModule[] = [
    {prefix: 'datasource', label: '数据源管理'},
    {prefix: 'sync', label: '批量同步任务'},
    {prefix: 'cdc', label: 'CDC 管道'},
    {prefix: 'dag', label: 'DAG 编排'},
    {prefix: 'template', label: '任务模板库'},
    {prefix: 'metadata', label: '元数据'},
    {prefix: 'collect', label: '元数据采集任务'},
    {prefix: 'standard', label: '数据标准'},
    {prefix: 'compliance', label: '标准合规'},
    {prefix: 'quality_rule', label: '质量规则'},
    {prefix: 'quality_job', label: '质量任务'},
    {prefix: 'quality_result', label: '质量结果'},
    {prefix: 'asset', label: '资产目录'},
    {prefix: 'sql', label: 'SQL 查询终端'},
    {prefix: 'api_key', label: 'API Key 管理'},
    {prefix: 'api', label: '数据 API'},
    {prefix: 'sensitivity', label: '数据分级分类'},
    {prefix: 'alert', label: '告警中心'},
    {prefix: 'user', label: '用户管理'},
    {prefix: 'role', label: '角色管理'},
    {prefix: 'data_permission', label: '权限配置'},
    {prefix: 'audit', label: '审计日志'},
    {prefix: 'queue', label: '执行队列'},
];

/** 按 code 解析模块前缀（如 datasource:view → datasource） */
export const permissionPrefix = (code: string): string => {
    const idx = code.indexOf(':');
    return idx === -1 ? code : code.slice(0, idx);
};

/** 只读权限点后缀（「查看档」快捷勾选命中这些 code） */
const READ_ONLY_SUFFIXES = [':view', ':history', ':stats', ':monitor', ':score', ':report', ':lineage'];

/** 判断某权限点是否为只读（查看类）权限点 */
export const isReadOnlyPermission = (code: string): boolean =>
    READ_ONLY_SUFFIXES.some((s) => code.endsWith(s));

// ============ 权限点组合（对应 constants/roles.ts 的写权限角色组合，供按钮显隐迁移） ============

/** 工程模块写（数据源/同步/CDC/DAG/模板）——对齐 ENGINEERING_WRITE_ROLES = 超管/工程师 */
export const ENGINEERING_WRITE_PERMS: PermissionCode[] = [
    PERM.DATASOURCE_CREATE, PERM.DATASOURCE_UPDATE, PERM.DATASOURCE_DELETE, PERM.DATASOURCE_TEST,
    PERM.SYNC_CREATE, PERM.SYNC_UPDATE, PERM.SYNC_DELETE, PERM.SYNC_EXECUTE,
    PERM.CDC_CREATE, PERM.CDC_UPDATE, PERM.CDC_DELETE, PERM.CDC_EXECUTE,
    PERM.DAG_CREATE, PERM.DAG_UPDATE, PERM.DAG_DELETE, PERM.DAG_EXECUTE,
    PERM.TEMPLATE_CREATE, PERM.TEMPLATE_UPDATE, PERM.TEMPLATE_DELETE,
];

/** 治理模块写（采集/标准/质量/分级/元数据注释/合规处理）——对齐 GOVERNANCE_WRITE_ROLES = 超管/治理员 */
export const GOVERNANCE_WRITE_PERMS: PermissionCode[] = [
    PERM.COLLECT_CREATE, PERM.COLLECT_UPDATE, PERM.COLLECT_DELETE, PERM.COLLECT_EXECUTE,
    PERM.STANDARD_CREATE, PERM.STANDARD_UPDATE, PERM.STANDARD_DELETE,
    PERM.QUALITY_RULE_CREATE, PERM.QUALITY_RULE_UPDATE, PERM.QUALITY_RULE_DELETE,
    PERM.QUALITY_JOB_CREATE, PERM.QUALITY_JOB_UPDATE, PERM.QUALITY_JOB_DELETE, PERM.QUALITY_JOB_EXECUTE,
    PERM.SENSITIVITY_CHANGE, PERM.SENSITIVITY_BATCH_CHANGE,
    PERM.METADATA_COMMENT, PERM.COMPLIANCE_HANDLE, PERM.ASSET_MANAGE,
];

/** 数据服务写（API/API Key）——对齐 DATA_SERVICE_WRITE_ROLES = 超管/工程师 */
export const DATA_SERVICE_WRITE_PERMS: PermissionCode[] = [
    PERM.API_CREATE, PERM.API_UPDATE, PERM.API_PUBLISH, PERM.API_DELETE,
    PERM.API_KEY_CREATE, PERM.API_KEY_TOGGLE, PERM.API_KEY_DELETE,
];

/** 告警规则写——对齐 ALERT_WRITE_ROLES = 超管/工程师 */
export const ALERT_WRITE_PERMS: PermissionCode[] = [PERM.ALERT_RULE_MANAGE];

/** 告警中心查看——对齐 ALERT_VIEW_ROLES = 超管/工程师/治理员 */
export const ALERT_VIEW_PERMS: PermissionCode[] = [PERM.ALERT_VIEW];

/** 标准合规查看——对齐 COMPLIANCE_VIEW_ROLES = 超管/治理员/工程师 */
export const COMPLIANCE_VIEW_PERMS: PermissionCode[] = [PERM.COMPLIANCE_VIEW, PERM.COMPLIANCE_HANDLE];

/** 元数据查看（人人可用的只读场景，含元数据预览） */
export const METADATA_VIEW_PERMS: PermissionCode[] = [PERM.METADATA_VIEW];
