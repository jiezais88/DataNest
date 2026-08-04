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

// ==================== 质量任务 / 质量规则 ====================

/** 质量任务 / 规则相关测试数据前缀 */
export const QUALITY_PREFIX = 'e2e_s6_q';
/** 元数据测试数据源名称 */
export const QUALITY_DS_NAME = 'e2e_s6_quality_ds';
/** 元数据测试库名 */
export const QUALITY_DB = 'e2e_s6_qdb';
/** 元数据测试表名 */
export const QUALITY_TABLE = 'e2e_s6_orders';
/** 自动触发绑定用同步任务名 */
export const QUALITY_SYNC_JOB = 'e2e_s6_sync_job';

/** 质量任务类型枚举（供断言用） */
export const QUALITY_ALERT_LEVELS = ['SEVERE_WARNING', 'WARNING', 'DANGER'] as const;

// ==================== 质量检查执行层（Sprint 8） ====================

/** 执行层测试数据前缀 */
export const EXEC_PREFIX = 'e2e_s6_exec';

/** 执行层目标表名（MYSQL / PG 各建一张同名） */
export const EXEC_TABLE = 'e2e_s6_orders';

/** 失败批次指向的不存在表名 */
export const EXEC_BAD_TABLE = 'e2e_s6_no_such_table';

/** 执行数据源名称 */
export const EXEC_DS_MYSQL_NAME = 'e2e_s6_exec_ds';
export const EXEC_DS_PG_NAME = 'e2e_s6_exec_pg_ds';

/** 测试数据源连接信息（worker 容器内经容器名直连） */
export const EXEC_MYSQL = {
    host: 'middleware-test-mysql',
    port: 3306,
    db: 'testdb',
    schema: null as string | null,
    user: 'testuser',
    pass: 'testpass123',
};
export const EXEC_PG = {
    host: 'middleware-test-postgres',
    port: 5432,
    db: 'testdb',
    schema: 'public',
    user: 'testuser',
    pass: 'testpass123',
};

