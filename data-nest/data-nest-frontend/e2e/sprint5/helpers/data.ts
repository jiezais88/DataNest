/**
 * Sprint 5 测试数据常量
 * 所有测试数据带 e2e_s5 前缀，测试结束后由 seed.ts cleanupAll 清理
 */

export const ADMIN = {username: 'admin', password: 'admin123'};

export const TEST_USERS = {
    engineer: {username: 's5_engineer', password: 'Test123456', roles: ['DATA_ENGINEER'], email: 's5.engineer@test.io'},
    govAdmin: {
        username: 's5_govadmin',
        password: 'Test123456',
        roles: ['GOVERNANCE_ADMIN'],
        email: 's5.govadmin@test.io'
    },
    analyst: {username: 's5_analyst', password: 'Test123456', roles: ['DATA_ANALYST'], email: 's5.analyst@test.io'},
    noEmail: {username: 's5_noemail', password: 'Test123456', roles: ['DATA_ENGINEER'], email: ''},
};

/** 血缘测试数据：库名前缀 */
export const LIN_DB = 'e2e_s5_lin';

/** 血缘表（表级 + 字段级） */
export const LINEAGE = {
    /** ods_orders -> dwd_orders.amount */
    t1Source: `${LIN_DB}.ods_orders`,
    t1Target: `${LIN_DB}.dwd_orders`,
    /** ods_order_logs -> dwd_orders */
    t2Source: `${LIN_DB}.ods_order_logs`,
    /** dwd_orders -> dws_order_summary */
    t3Target: `${LIN_DB}.dws_order_summary`,
    /** 无血缘表 */
    orphan: `${LIN_DB}.no_lineage_table`,
};

/** Doris 上字段级血缘真实执行的目标表 */
export const DORIS_TARGET = 'datanest.e2e_s5_lin_target';
export const DORIS_TARGET_SHORT = 'e2e_s5_lin_target';

/** 测试 DAG 名称前缀 */
export const DAG_PREFIX = 'e2e_s5';

/** 同步任务/采集任务名称前缀 */
export const SYNC_PREFIX = 'e2e_s5';

/** 同步任务失败用（源表不存在，必然失败） */
export const SYNC_BAD_SOURCE_TABLE = 'e2e_s5_not_exist_source_table';

/** 采集任务失败用数据源/库 */
export const COLLECT_BAD_DATABASE = 'e2e_s5_not_exist_db';
