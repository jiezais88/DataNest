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

// ==================== 分级邮件告警（Sprint 6） ====================

/** 分级告警测试数据前缀（质量任务 / 质量规则 / 告警规则） */
export const ALERT_PREFIX = 'e2e_s6_alert';

/**
 * 分级告警测试固定 ID 段（9000040000000000000+，独立于质量/执行/自动触发三段）。
 * 对应 seed.ts 中 seedQualityAlerts 播种的质量任务与质量规则。
 */
export const ALERT_JOB_ID = '9000040000000000001';
export const ALERT_JOB_SEVERE_ONLY_ID = '9000040000000000002';
export const ALERT_JOB_UNAVAILABLE_ID = '9000040000000000003';
export const ALERT_JOB_PASS_ID = '9000040000000000004';
/** 主链路任务（SEVERE_WARNING）下的规则 */
export const ALERT_RULE_SEVERE_ID = '9000040000000000101';
export const ALERT_RULE_WARNING_ID = '9000040000000000102';
/** SEVERE_ONLY 任务下的规则（含严重 + 警告，验证排除警告） */
export const ALERT_RULE_SO_SEVERE_ID = '9000040000000000201';
export const ALERT_RULE_SO_WARNING_ID = '9000040000000000202';
/** UNAVAILABLE 任务下的规则（查不存在表 → SQL 失败） */
export const ALERT_RULE_UNAVAILABLE_ID = '9000040000000000301';
/** PASS 任务下的规则（无阈值配置 → 通过不告警） */
export const ALERT_RULE_PASS_ID = '9000040000000000401';

// ==================== 表级质量评分（Sprint 6 NG8） ====================

/** 表级质量评分测试数据前缀（物理表名 / 规则名） */
export const SCORE_PREFIX = 'e2e_s6_score';

/**
 * 表级质量评分测试固定 ID 段（9000050000000000000+，独立于质量/执行/自动/告警四段）。
 * 对应 seed.ts 中 seedQualityScores 播种的 4 张评分物理表 + 7 条质量规则。
 * 复用 MYSQL 执行数据源（EXEC_DS_MYSQL_ID = 9000020000000000001）与 middleware-test-mysql。
 */
/** 评分物理表 metadata_table 固定 ID（4 档：全通过 / 警告 / 严重 / 不可用） */
export const SCORE_TABLE_PASS_ID = '9000050000000000011';
export const SCORE_TABLE_WARN_ID = '9000050000000000012';
export const SCORE_TABLE_SEVERE_ID = '9000050000000000013';
export const SCORE_TABLE_UNAVAIL_ID = '9000050000000000014';
/** 评分物理表名（不同表名满足 metadata_table 唯一约束，行数控制 COUNT 值决定分级） */
export const SCORE_TABLE_PASS = 'e2e_s6_score_pass';
export const SCORE_TABLE_WARN = 'e2e_s6_score_warn';
export const SCORE_TABLE_SEVERE = 'e2e_s6_score_severe';
export const SCORE_TABLE_UNAVAIL = 'e2e_s6_score_unavail';

/** 评分规则固定 ID（7 条：P 表 2 全通过；W 表 1 警告+1 通过；B 表 1 严重+1 通过；U 表 1 不可用） */
export const SCORE_RULE_PASS_1 = '9000050000000000101';
export const SCORE_RULE_PASS_2 = '9000050000000000102';
export const SCORE_RULE_WARN_1 = '9000050000000000103';
export const SCORE_RULE_WARN_PASS = '9000050000000000104';
export const SCORE_RULE_SEVERE_1 = '9000050000000000105';
export const SCORE_RULE_SEVERE_PASS = '9000050000000000106';
export const SCORE_RULE_UNAVAIL = '9000050000000000107';

