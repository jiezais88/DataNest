import {expect, test} from '@playwright/test';
import {Api} from '../helpers/api';
import {ADMIN} from '../helpers/data';
import {psql} from '../helpers/db';

/**
 * Sprint 6 血缘节点质量徽章（API 层，清单 §8）。
 * <p>
 * 血缘图谱节点展示表级质量评分徽章（分数+健康度 / 灰色「—」），数据由后端 LineageService
 * 按节点表名回填 quality_score（fillQualityScores）。本测试直接播种血缘记录 + 评分，
 * 调血缘接口断言节点回填 qualityScore/healthLevel：
 * - 有评分节点（dwd_orders=100 EXCELLENT）→ 回填 qualityScore=100 / healthLevel=EXCELLENT
 * - 无评分节点（ods_orders 等）→ 保持 null（前端显示灰色「—」）
 */

// 血缘库 / 表名（与 sprint5 血缘一致，用独立库名避免与 sprint5 seed 冲突）
const LIN_DB = 'e2e_s6_lin';
const T_SOURCE = `${LIN_DB}.ods_orders`;
const T_TARGET = `${LIN_DB}.dwd_orders`;
const T_SUMMARY = `${LIN_DB}.dws_order_summary`;

// 固定 ID（血缘记录 / 元数据 / 评分）
const LINEAGE_REC_1 = '6700000000000000101';
const LINEAGE_REC_2 = '6700000000000000102';
const LINEAGE_REC_3 = '6700000000000000103';
const METADATA_TABLE_ID = '6700000000000000001';
const SCORE_ID = '6700000000000000002';

let admin: Api;

function seedLineage(): void {
    // 血缘记录（3 条：ods_orders→dwd_orders, ods_order_logs→dwd_orders, dwd_orders→dws_order_summary）
    psql(`INSERT INTO lineage_record
          (id, source_table, target_table, source_column, target_column, dag_id, dag_name, node_id, node_name,
           execution_id, lineage_type, created_at)
          VALUES (${LINEAGE_REC_1}, '${T_SOURCE}', '${T_TARGET}', NULL, NULL, 1, 'e2e_s6_dag', 'n1', 'SQL节点', 1, 'SQL', now()),
                 (${LINEAGE_REC_2}, '${LIN_DB}.ods_order_logs', '${T_TARGET}', NULL, NULL, 1, 'e2e_s6_dag', 'n1', 'SQL节点', 1, 'SQL', now()),
                 (${LINEAGE_REC_3}, '${T_TARGET}', '${T_SUMMARY}', NULL, NULL, 1, 'e2e_s6_dag', 'n1', 'SQL节点', 1, 'SQL', now())
          ON CONFLICT (id) DO NOTHING;`);
    // 血缘元数据表（供图谱节点按表名匹配）
    psql(`INSERT INTO metadata_table (id, datasource_id, database_name, schema_name, table_name, table_comment,
                                       source_status, source_type, created_at, updated_at)
          VALUES (${METADATA_TABLE_ID}, 2083088527209295874, '${LIN_DB}', NULL, 'dwd_orders',
                  'e2e s6 血缘质量徽章测试表', 'ONLINE', 'EXTERNAL', now(), now())
          ON CONFLICT (id) DO NOTHING;`);
}

function cleanupLineage(): void {
    psql(`DELETE FROM quality_score WHERE table_name='${T_TARGET}'`);
    psql(`DELETE FROM lineage_record WHERE id IN (${LINEAGE_REC_1}, ${LINEAGE_REC_2}, ${LINEAGE_REC_3})`);
    psql(`DELETE FROM metadata_table WHERE id=${METADATA_TABLE_ID}`);
}

test.describe.configure({mode: 'serial'});

test.describe('Sprint 6 血缘节点质量徽章（API）', () => {
    test.beforeAll(async () => {
        admin = await Api.create();
        await admin.login(ADMIN.username, ADMIN.password);
        cleanupLineage();
        seedLineage();
    });

    test.afterAll(async () => {
        cleanupLineage();
        await admin.dispose();
    });

    test('有评分节点回填 qualityScore/healthLevel，无评分节点保持 null', async () => {
        // 播种评分：dwd_orders=100 EXCELLENT
        psql(`INSERT INTO quality_score (id, table_id, table_name, datasource_id, score, health_level,
                                          pass_rules, warning_rules, severe_rules, last_checked_at, updated_at)
              VALUES (${SCORE_ID}, ${METADATA_TABLE_ID}, '${T_TARGET}', 2083088527209295874,
                      100, 'EXCELLENT', 1, 0, 0, now(), now())
              ON CONFLICT (id) DO NOTHING;`);

        const graph = await admin.get<{nodes: any[]; edges: any[]}>(
            '/governance/lineage/graph?tableName=' + encodeURIComponent(T_TARGET) + '&depth=1',
        );
        const nodes = graph.nodes || [];
        const nodeById = new Map(nodes.map((n: any) => [n.id, n]));

        // 当前表 dwd_orders：有评分 → 回填 100 / EXCELLENT
        const targetNode = nodeById.get(T_TARGET);
        expect(targetNode).toBeTruthy();
        expect(targetNode.qualityScore).toBe(100);
        expect(targetNode.healthLevel).toBe('EXCELLENT');

        // 上游 ods_orders：无评分 → 未回填（JSON 可能省略 null 字段，故容忍 null/undefined）
        const sourceNode = nodeById.get(T_SOURCE);
        expect(sourceNode).toBeTruthy();
        expect(sourceNode.qualityScore == null).toBe(true);
        expect(sourceNode.healthLevel == null).toBe(true);
    });
});
