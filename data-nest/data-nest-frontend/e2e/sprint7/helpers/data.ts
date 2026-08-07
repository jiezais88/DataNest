/**
 * Sprint 7 F1 数据资产目录测试数据常量。
 * 所有测试数据带 e2e_s7 前缀，固定 ID 段 900007xxxxxxxxxxxxxxx，
 * 测试结束后由 seed.ts cleanupAll 清理。
 *
 * 拆库口径（2026-08-07 起）：asset_classification / metadata_* / quality_* / lineage_record
 * 在 datanest_governance；datasource_connection 在 datanest_engineering；sys_user 在 datanest_system。
 */

export const ADMIN = {username: 'admin', password: 'admin123'};

/** 测试用户（独立于 sprint5/6，避免跨 sprint 数据耦合） */
export const TEST_USERS = {
    govAdmin: {
        username: 's7_govadmin',
        password: 'Test123456',
        roles: ['GOVERNANCE_ADMIN'],
        email: 's7.govadmin@test.io'
    },
    engineer: {
        username: 's7_engineer',
        password: 'Test123456',
        roles: ['DATA_ENGINEER'],
        email: 's7.engineer@test.io'
    },
    analyst: {
        username: 's7_analyst',
        password: 'Test123456',
        roles: ['DATA_ANALYST'],
        email: 's7.analyst@test.io'
    },
};

/** 测试数据前缀 */
export const S7_PREFIX = 'e2e_s7';

// ==================== 数据源（datanest_engineering） ====================

/** 元数据测试数据源固定 ID（仅元数据引用，不做真实执行，密码无需可解密） */
export const DS_ID = '9000070000000000001';
export const DS_NAME = 'e2e_s7_mysql_ds';
export const DB_NAME = 'testdb';

// ==================== 元数据表（datanest_governance） ====================

/** T1 主链路表：交易域·订单，负责人 s7_analyst，评分 95 优秀，有血缘有质量规则 */
export const T1_ID = '9000070000000000011';
export const T1_NAME = 'e2e_s7_trade_orders';
export const T1_COMMENT = '电商交易订单主表';
/** T2：交易域·订单，评分 85 良好 */
export const T2_ID = '9000070000000000012';
export const T2_NAME = 'e2e_s7_trade_refunds';
export const T2_COMMENT = '电商交易退款流水表';
/** T3：用户域（仅域无主题），评分 70 一般 */
export const T3_ID = '9000070000000000013';
export const T3_NAME = 'e2e_s7_user_profile';
export const T3_COMMENT = '用户画像宽表';
/** T4：未分类，评分 20 差（健康度筛选/批量分配/移出分类主用表） */
export const T4_ID = '9000070000000000014';
export const T4_NAME = 'e2e_s7_product_sku';
export const T4_COMMENT = '商品维度表';
/** T5：内置 Doris 表（datasource_id=-1），未分类无评分，验证 Doris 数仓兜底回显 */
export const T5_ID = '9000070000000000015';
export const T5_NAME = 'e2e_s7_doris_mart';
export const T5_COMMENT = 'Doris 汇总表';

/** 完整表名（无 schema 时展示 库名.表名） */
export const fullTable = (t: string) => `${DB_NAME}.${t}`;

// ==================== 分类体系（datanest_governance.asset_classification） ====================

export const D1_ID = '9000070000000000021';
export const D1_NAME = 'e2e_s7_交易域';
export const D1T1_ID = '9000070000000000022';
export const D1T1_NAME = 'e2e_s7_订单';
export const D1T2_ID = '9000070000000000023';
export const D1T2_NAME = 'e2e_s7_退款';
export const D2_ID = '9000070000000000024';
export const D2_NAME = 'e2e_s7_用户域';

// ==================== 质量评分 / 规则 / 批次（datanest_governance） ====================

export const SCORE_T1_ID = '9000070000000000031';
export const SCORE_T2_ID = '9000070000000000032';
export const SCORE_T3_ID = '9000070000000000033';
export const SCORE_T4_ID = '9000070000000000034';

/** T1 质量规则（3 条启用：2 PASS + 1 WARNING） */
export const RULE_R1_ID = '9000070000000000041';
export const RULE_R1_NAME = 'e2e_s7_订单完整性';
export const RULE_R2_ID = '9000070000000000042';
export const RULE_R2_NAME = 'e2e_s7_订单唯一性';
export const RULE_R3_ID = '9000070000000000043';
export const RULE_R3_NAME = 'e2e_s7_金额范围';

export const BATCH_ID = '9000070000000000051';

// ==================== 血缘（datanest_governance.lineage_record） ====================

/** T3 → T1 → T2（T1 详情：直接上游 1 / 下游 1） */
export const LINEAGE_UP_ID = '9000070000000000061';
export const LINEAGE_DOWN_ID = '9000070000000000062';

// ==================== F1 开发自测残留（用户确认清理后重建，seed 时幂等清除） ====================

/** 残留分类 ID（交易域/订单/用户域） */
export const RESIDUE_CLASSIFICATION_IDS = ['2085194182698643457', '2085194182191132674', '2085194605933277186'];
/** 残留表分类/负责人（orders/order_items） */
export const RESIDUE_TABLE_IDS = ['2083088529268047873', '2083088529049944066'];
