/**
 * Sprint 6 测试数据常量
 * 规则模板库（quality/templates）专属。
 * 所有测试数据带 e2e_s6 前缀，测试结束后由 seed.ts cleanupAll 清理。
 */

export const ADMIN = {username: 'admin', password: 'admin123'};

/** 测试用户（独立于 sprint5，避免跨 sprint 数据耦合） */
export const TEST_USERS = {
    govAdmin: {
        username: 's6_govadmin',
        password: 'Test123456',
        roles: ['GOVERNANCE_ADMIN'],
        email: 's6.govadmin@test.io'
    },
    engineer: {
        username: 's6_engineer',
        password: 'Test123456',
        roles: ['DATA_ENGINEER'],
        email: 's6.engineer@test.io'
    },
    analyst: {
        username: 's6_analyst',
        password: 'Test123456',
        roles: ['DATA_ANALYST'],
        email: 's6.analyst@test.io'
    },
};

/** 测试自定义模板名称前缀（用于 seed 播种与清理定位） */
export const TPL_PREFIX = 'e2e_s6';

/** 内置四类模板（与 V3.6.0 迁移脚本种子一致） */
export const BUILTIN_TEMPLATES = [
    {name: '完整性检查', type: 'COMPLETENESS', resultMetric: 'null_rate'},
    {name: '唯一性检查', type: 'UNIQUENESS', resultMetric: 'duplicate_count'},
    {name: '值域范围检查', type: 'RANGE', resultMetric: 'out_of_range_rate'},
    {name: '自定义 SQL', type: 'CUSTOM_SQL', resultMetric: 'custom_value'},
];

/** 内置模板的 result_metric（用于内置模板不可删除校验时的定位） */
export const BUILTIN_NAME = '完整性检查';
