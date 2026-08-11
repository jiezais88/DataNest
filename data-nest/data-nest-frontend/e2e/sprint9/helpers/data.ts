/**
 * Sprint 9 F1/F2/F3 + 遗留清零 E2E 测试常量。
 * 复用 sprint8 的真实源数据源（middleware-test-mysql testdb / middleware-test-postgres postgres）
 * 与 sprint7 播种用户（s7_engineer / s7_analyst / s7_govadmin，均有邮箱）。
 *
 * 拆库口径：
 * - cdc_* 在 datanest_realtime；alert_* 在 datanest_alert；sys_user 在 datanest_system
 * - 源库造数走 test-mysql / test-postgres 容器
 */

export const ADMIN = {username: 'admin', password: 'admin123'};

/** 测试用户（复用 sprint7 播种；engineer 为主操作者，analyst 验证权限隔离） */
export const TEST_USERS = {
    govAdmin: {username: 's7_govadmin', password: 'Test123456', roles: ['GOVERNANCE_ADMIN'], email: 's7.govadmin@test.io'},
    engineer: {username: 's7_engineer', password: 'Test123456', roles: ['DATA_ENGINEER'], email: 's7.engineer@test.io'},
    analyst: {username: 's7_analyst', password: 'Test123456', roles: ['DATA_ANALYST'], email: 's7.analyst@test.io'},
};

/** 测试数据前缀 */
export const S9_PREFIX = 'e2e_s9';

/** 存量真实源数据源（sprint8 已验证可连） */
export const MYSQL_DS_ID = '2083088527209295874';
export const MYSQL_DS_LABEL = 'mysql（middleware-test-mysql）';
export const PG_DS_ID = '2083837829055127553';
export const MYSQL_DB = 'testdb';
export const PG_DB = 'postgres';

/** 湖仓目标库（Iceberg namespace，自动创建） */
export const TARGET_DB = 'e2e_s9_dwd';

/** 测试源表（MySQL 主链路；F1 指标落库用） */
export const T_MAIN = 'e2e_s9_cdc_users';
/** FAILURE 告警专用源表（运行中 DROP 制造作业失败） */
export const T_FAIL = 'e2e_s9_cdc_fail';
/** EXTERNAL_STOP/404 自愈专用源表 */
export const T_EXT = 'e2e_s9_cdc_ext';
/** PG 源表（遗留项④：REPLICA IDENTITY FULL 提示验证） */
export const T_PG = 'e2e_s9_pg_users';
/** 未开启 REPLICA IDENTITY FULL 的 PG 表（警示验证） */
export const T_PG_NO_FULL = 'e2e_s9_pg_no_full';

/** 管道名（e2e_s9_ 前缀自清理） */
export const P_MONITOR = 'e2e_s9_monitor';       // F1 监控主链路
export const P_CKPT = 'e2e_s9_checkpoint';       // F2 检查点/savepoint
export const P_LAG = 'e2e_s9_lag';               // F3 延迟告警
export const P_FAIL = 'e2e_s9_fail';             // F3 失败告警
export const P_EXT = 'e2e_s9_ext_stop';          // AC-6/外部停止
export const P_GUARD = 'e2e_s9_rule_unbind';     // 告警规则解绑

/** 快速 Checkpoint（10s，加速状态推进） */
export const FAST_CKPT = JSON.stringify({checkpointIntervalSeconds: 10});

/** 告警规则名 */
export const RULE_LAG = 'e2e_s9_cdc_lag_rule';
export const RULE_FAIL = 'e2e_s9_cdc_fail_rule';
export const RULE_EXT = 'e2e_s9_cdc_ext_rule';
