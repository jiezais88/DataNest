/**
 * 审计日志枚举映射（Sprint 11 F1）。op_type / resource_type 的 code → 中文 label，
 * 与后端 com.datanest.common.audit.AuditOpType / AuditResourceType 对齐。
 * 筛选下拉与列表展示的唯一出处。
 */

export const AUDIT_OP_TYPES: { value: string; label: string }[] = [
    {value: 'CREATE', label: '创建'},
    {value: 'UPDATE', label: '修改'},
    {value: 'DELETE', label: '删除'},
    {value: 'EXECUTE', label: '执行'},
    {value: 'STOP', label: '停止'},
    {value: 'TEST', label: '测试连接'},
    {value: 'CHANGE_LEVEL', label: '改级'},
    {value: 'PUBLISH', label: '发布'},
    {value: 'OFFLINE', label: '下线'},
    {value: 'ENABLE', label: '启用'},
    {value: 'DISABLE', label: '禁用'},
    {value: 'RESET_PASSWORD', label: '重置密码'},
    {value: 'TRIGGER', label: '手动触发'},
    {value: 'SSO_LOGIN', label: '企业身份登录'},
    {value: 'LDAP_SYNC', label: 'LDAP 用户同步'},
    {value: 'UNBIND', label: '解绑'},
    {value: 'UNLOCK', label: '解锁'},
];

export const AUDIT_RESOURCE_TYPES: { value: string; label: string }[] = [
    {value: 'USER', label: '用户'},
    {value: 'ROLE', label: '角色'},
    {value: 'DATASOURCE', label: '数据源'},
    {value: 'SYNC_JOB', label: '批量同步任务'},
    {value: 'DAG', label: 'DAG 编排'},
    {value: 'SQL_QUERY', label: 'SQL 查询'},
    {value: 'DATA_API', label: '数据 API'},
    {value: 'API_KEY', label: 'API Key'},
    {value: 'SENSITIVITY', label: '数据分级分类'},
    {value: 'EXECUTION_QUEUE', label: '执行队列'},
    {value: 'SSO_CONFIG', label: '身份认证'},
];

export function getOpTypeLabel(code: string): string {
    return AUDIT_OP_TYPES.find((o) => o.value === code)?.label || code;
}

export function getResourceTypeLabel(code: string): string {
    return AUDIT_RESOURCE_TYPES.find((o) => o.value === code)?.label || code;
}
