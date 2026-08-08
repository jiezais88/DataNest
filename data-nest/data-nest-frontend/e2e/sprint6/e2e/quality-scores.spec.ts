import {expect, type Page, test} from '@playwright/test';
import {Api} from '../helpers/api';
import {ADMIN, TEST_USERS, SCORE_PREFIX} from '../helpers/data';
import {psql, scalar} from '../helpers/db';
import {waitFor} from '../helpers/poll';
import {gotoAs} from '../helpers/e2e';
import {ensureTestUsers, seedExecTables, seedExecMetadata, seedQualityScores} from '../helpers/seed';

/**
 * Sprint 6 NG8 表级质量评分 E2E 测试。
 *
 * 覆盖深度：DB + 前端 UI 全覆盖，API 辅助诊断。
 * 核心：评分在质量检查批次收尾时由 ScoreCalculator 自动重算，upsert 到 quality_score（每表一行）。
 *
 * 多档评分场景（seedQualityScores 播种，默认扣分配置 warningDeduct=10 / severeDeduct=30 / badThreshold=60）：
 * - P 全通过表 e2e_s6_score_pass（COUNT=2）：2 规则 PASS → 100.00 EXCELLENT（pass=2）
 * - W 警告表 e2e_s6_score_warn（COUNT=4）：1 警告(w1) + 1 通过(w4) → 100×4/5-10 = 70.00 WARNING（pass=1/warning=1）
 * - B 严重表 e2e_s6_score_severe（COUNT=4）：1 严重(w1) + 1 通过(w1) → 严重强制 BAD，min(50-30,59.99)=20.00 BAD（pass=1/severe=1）
 * - U 不可用表 e2e_s6_score_unavail：规则查不存在表 → UNAVAILABLE 不参与 → 无有效规则 → 不落评分行
 *
 * 执行方式：逐条 POST /governance/quality/rules/{ruleId}/execute（MANUAL 投递 worker，串行触发），
 * 异步执行。测试用 waitFor 轮询 quality_score 落行 / quality_check_detail 分级至终态断言。
 * 测试数据前缀 e2e_s6_score，seed 由 seedQualityScores 提供 4 张评分物理表 + 7 条规则。
 */

// ==================== 固定 ID（与 seed.ts 一致） ====================
const TABLE_PASS_ID = '9000050000000000011';
const TABLE_WARN_ID = '9000050000000000012';
const TABLE_SEVERE_ID = '9000050000000000013';
const TABLE_UNAVAIL_ID = '9000050000000000014';
const TABLE_PASS = 'e2e_s6_score_pass';
const TABLE_WARN = 'e2e_s6_score_warn';
const TABLE_SEVERE = 'e2e_s6_score_severe';
const TABLE_UNAVAIL = 'e2e_s6_score_unavail';
const RULE_UNAVAIL = '9000050000000000107';

/** 完整表名（无 schema 时列表/详情展示 库名.表名） */
const fullTable = (t: string) => `testdb.${t}`;

let admin: Api;

/**
 * 轮询评分：等待指定表 quality_score 落行且计数达标（所有启用规则已执行完，评分收敛），
 * 返回 {score, health}。用于 P/W/B 表（会落行）。
 */
async function waitScore(
    tableId: string,
    expect: {pass: number; warning: number; severe: number},
    opts: {timeoutMs?: number} = {},
): Promise<{score: string; health: string}> {
    const {timeoutMs = 120_000} = opts;
    return waitFor(
        async () => {
            const row = psql(`SELECT score, health_level, pass_rules, warning_rules, severe_rules
                              FROM quality_score WHERE table_id=${tableId} LIMIT 1`);
            if (!row) return null;
            const [score, health, pass, warning, severe] = row.split('|');
            if (Number(pass) === expect.pass && Number(warning) === expect.warning && Number(severe) === expect.severe) {
                return {score, health};
            }
            return null;
        },
        (v) => v != null,
        {timeoutMs, label: `评分表 ${tableId} 落行且计数(${expect.pass}/${expect.warning}/${expect.severe})达标`},
    ) as unknown as {score: string; health: string};
}

/** 轮询规则分级：等待某规则最近一条 detail 到达指定 result_level（用于 U 表不落行的负向场景） */
async function waitRuleLevel(ruleId: string, level: string, opts: {timeoutMs?: number} = {}): Promise<string> {
    const {timeoutMs = 120_000} = opts;
    return waitFor(
        async () => {
            const lv = scalar(`SELECT result_level FROM quality_check_detail
                               WHERE rule_id=${ruleId} ORDER BY id DESC LIMIT 1`);
            return lv;
        },
        (v) => v === level,
        {timeoutMs, label: `规则 ${ruleId} 分级到达 ${level}`},
    ) as unknown as string;
}

/** 定位评分列表页表格行：按「表名」完整文本匹配 */
function scoreRow(page: Page, table: string) {
    return page.locator('.ant-table-row').filter({hasText: fullTable(table)});
}

test.describe.configure({mode: 'serial'});

test.describe('Sprint 6 表级质量评分（多档 + UI + 负向 + 配置）', () => {
    test.beforeAll(async () => {
        admin = await Api.create();
        await admin.login(ADMIN.username, ADMIN.password);
        // 播种依赖：测试用户 + 执行数据源（含 e2e_s6_orders）+ 评分物理表/元数据/规则。
        // 本 spec 自带播种，支持 SKIP_SETUP=1 独立运行（不依赖 globalSetup 的 Sprint5 播种）。
        await ensureTestUsers();
        seedExecTables();
        seedExecMetadata();
        seedQualityScores();
        // 清理历史评分/明细/批次（先按明细反查删批次，再删明细，避免孤儿批次残留）
        psql(`DELETE FROM quality_score WHERE table_id IN (
            ${TABLE_PASS_ID}, ${TABLE_WARN_ID}, ${TABLE_SEVERE_ID}, ${TABLE_UNAVAIL_ID})`);
        psql(`DELETE FROM quality_check_batch WHERE id IN (
            SELECT DISTINCT batch_id FROM quality_check_detail WHERE rule_id IN (SELECT id FROM quality_rule WHERE name LIKE '${SCORE_PREFIX}%'))`);
        psql(`DELETE FROM quality_check_detail WHERE rule_id IN (SELECT id FROM quality_rule WHERE name LIKE '${SCORE_PREFIX}%')`);

        // 触发四张表全部启用规则执行（异步投递 worker）。
        // 注意：PowerJob 质量执行 handler（qualityCheckExecuteHandler）注册时 max_instance_num=1，
        // scores/table/{id}/execute 会并发投递同 handler 的多条规则实例，并发实例会被 PowerJob
        // 拒绝（too many instances(1>1)，实例直接 FAILED 不执行）。因此这里逐条顺序触发：
        // 每次触发后等该规则新批次进入终态再触发下一条，绕开并发限制。
        const SCORE_RULE_IDS = [
            '9000050000000000101', '9000050000000000102', // pass 表 r1/r2
            '9000050000000000103', '9000050000000000104', // warn 表 r1/r2
            '9000050000000000105', '9000050000000000106', // severe 表 r1/r2
            '9000050000000000107',                        // unavail 表 r1
        ];
        for (const ruleId of SCORE_RULE_IDS) {
            // 打点排除 9e18 固定 ID 号段（sprint7 种子批次 900007* / 本套件 AUTO_TRIGGER 种子批次 900003*，
            // 均比雪花 ID ~2e18 大），否则 MAX(id) 被顶住，b.id > since 永远匹配不到新批次
            const since = scalar(`SELECT COALESCE(MAX(id),0) FROM quality_check_batch WHERE id < 9000000000000000000`) ?? '0';
            await admin.post(`/governance/quality/rules/${ruleId}/execute`);
            // 按 rule_id 经明细关联定位本规则的批次（不用全局最新批次，避免其它来源的 RUNNING 批次遮蔽断言）
            const waitBatchDone = (timeoutMs: number) => waitFor(
                async () => scalar(`SELECT b.status FROM quality_check_batch b
                                    JOIN quality_check_detail d ON d.batch_id = b.id
                                    WHERE b.id > ${since} AND d.rule_id = ${ruleId}
                                    ORDER BY b.id DESC LIMIT 1`),
                (s) => s != null && s !== 'RUNNING',
                {timeoutMs, label: `规则 ${ruleId} 批次进入终态`},
            );
            try {
                await waitBatchDone(20_000);
            } catch {
                // 短窗内无新批次：投递很可能被单实例槽拒绝（实例直接 FAILED 不落批次），重投一次。
                // 若原实例只是排队较慢，重投会被拒绝，但原批次仍满足 id > since，等待可正常收敛。
                await admin.post(`/governance/quality/rules/${ruleId}/execute`);
                await waitBatchDone(90_000);
            }
        }

        // 等待落行表评分收敛；等待 U 表规则到 UNAVAILABLE
        await waitScore(TABLE_PASS_ID, {pass: 2, warning: 0, severe: 0});
        await waitScore(TABLE_WARN_ID, {pass: 1, warning: 1, severe: 0});
        await waitScore(TABLE_SEVERE_ID, {pass: 1, warning: 0, severe: 1});
        await waitRuleLevel(RULE_UNAVAIL, 'UNAVAILABLE');
    });

    test.afterAll(async () => {
        // 恢复扣分配置默认值（G 组用例可能改动过），保证幂等
        await admin.put('/governance/quality/scores/config', {
            warningDeduct: 10, severeDeduct: 30, badThreshold: 60,
        }).catch(() => {});
        await admin.dispose();
    });

    // ==================== A. DB 多档评分断言 ====================

    test('A1 全通过表 → 100.00 EXCELLENT（pass=2）', async () => {
        const row = psql(`SELECT score, health_level, pass_rules, warning_rules, severe_rules
                          FROM quality_score WHERE table_id=${TABLE_PASS_ID}`);
        expect(row).toBeTruthy();
        const [score, health, pass, warning, severe] = row.split('|');
        expect(score).toBe('100.00');
        expect(health).toBe('EXCELLENT');
        expect(pass).toBe('2');
        expect(warning).toBe('0');
        expect(severe).toBe('0');
    });

    test('A2 警告表 → 70.00 WARNING（1 通过 + 1 警告，权重影响）', async () => {
        const row = psql(`SELECT score, health_level, pass_rules, warning_rules, severe_rules
                          FROM quality_score WHERE table_id=${TABLE_WARN_ID}`);
        const [score, health, pass, warning, severe] = row.split('|');
        expect(score).toBe('70.00');
        expect(health).toBe('WARNING');
        expect(pass).toBe('1');
        expect(warning).toBe('1');
        expect(severe).toBe('0');
    });

    test('A3 严重表 → 20.00 BAD（SEVERE 强制 BAD）', async () => {
        const row = psql(`SELECT score, health_level, pass_rules, warning_rules, severe_rules
                          FROM quality_score WHERE table_id=${TABLE_SEVERE_ID}`);
        const [score, health, pass, warning, severe] = row.split('|');
        expect(score).toBe('20.00');
        expect(health).toBe('BAD');
        expect(pass).toBe('1');
        expect(warning).toBe('0');
        expect(severe).toBe('1');
    });

    test('A4 负向：UNAVAILABLE 不参与 + 无有效规则不落评分行', async () => {
        // U 表规则已到达 UNAVAILABLE（beforeAll 已等待），但评分不落行
        const lv = scalar(`SELECT result_level FROM quality_check_detail
                           WHERE rule_id=${RULE_UNAVAIL} ORDER BY id DESC LIMIT 1`);
        expect(lv).toBe('UNAVAILABLE');
        const scoreRow = scalar(`SELECT id FROM quality_score WHERE table_id=${TABLE_UNAVAIL_ID} LIMIT 1`);
        expect(scoreRow).toBeNull();
    });

    // ==================== B. UI 评分列表页 ====================

    test('B1 评分列表页：表格展示各表评分 + 健康度徽章', async ({page}) => {
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/governance/quality-scores');
        await expect(page.getByRole('heading', {name: '表级质量评分'})).toBeVisible();
        // 表名完整显示（库名.表名）
        const pRow = scoreRow(page, TABLE_PASS);
        await expect(pRow).toBeVisible({timeout: 15000});
        // 全通过表：评分 100 + 健康度「优秀」（评分徽章与健康度列均有「优秀」，first 避免歧义）
        await expect(pRow.getByText('优秀', {exact: true}).first()).toBeVisible();
        // 严重表：健康度「差」
        await expect(scoreRow(page, TABLE_SEVERE).getByText('差', {exact: true}).first()).toBeVisible();
        // 警告表：健康度「一般」
        await expect(scoreRow(page, TABLE_WARN).getByText('一般', {exact: true}).first()).toBeVisible();
    });

    test('B2 表名关键词筛选', async ({page}) => {
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/governance/quality-scores');
        // 先等初始（未筛选）列表加载完成：初始响应若晚于筛选响应返回会覆盖表格（竞态），导致漏筛误判
        await expect(scoreRow(page, TABLE_PASS)).toBeVisible({timeout: 15000});
        await page.getByLabel('搜索表名').fill('score_severe');
        await page.getByRole('button', {name: /查询/}).click();
        await expect(scoreRow(page, TABLE_SEVERE)).toBeVisible({timeout: 15000});
        await expect(scoreRow(page, TABLE_PASS)).toHaveCount(0);
    });

    test('B3 健康度筛选：按「差」只显示严重表', async ({page}) => {
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/governance/quality-scores');
        // 同 B2：先等初始列表加载完成再筛选，规避响应竞态
        await expect(scoreRow(page, TABLE_PASS)).toBeVisible({timeout: 15000});
        await page.getByLabel('按健康度筛选').selectOption('BAD');
        await page.getByRole('button', {name: /查询/}).click();
        await expect(scoreRow(page, TABLE_SEVERE)).toBeVisible({timeout: 15000});
        await expect(scoreRow(page, TABLE_PASS)).toHaveCount(0);
        await expect(scoreRow(page, TABLE_WARN)).toHaveCount(0);
    });

    // ==================== C. UI 详情弹窗 ====================

    test('C1 详情弹窗：评分概览 + 规则最近结果判定', async ({page}) => {
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/governance/quality-scores');
        // 用严重表做详情（分数 20 + 健康度差 + 严重判定），信息区分度最高
        await scoreRow(page, TABLE_SEVERE).getByRole('button', {name: fullTable(TABLE_SEVERE)}).click();
        const drawer = page.getByRole('dialog', {name: `质量详情 · ${fullTable(TABLE_SEVERE)}`});
        await drawer.waitFor({state: 'visible', timeout: 10000});
        // 评分概览：健康度「差」+ 通过/警告/严重 = 1 / 0 / 1
        await expect(drawer.getByText('差', {exact: true}).first()).toBeVisible();
        await expect(drawer.getByText(/1 \/ 0 \/ 1/)).toBeVisible();
        // 规则最近结果：严重规则 + 通过规则（判定徽章「严重」/「通过」）
        await expect(drawer.getByRole('heading', {name: '规则最近结果'})).toBeVisible();
        await expect(drawer.getByText(`${SCORE_PREFIX}_severe_r1`, {exact: true})).toBeVisible();
        await expect(drawer.getByText(`${SCORE_PREFIX}_severe_r2`, {exact: true})).toBeVisible();
        await expect(drawer.getByText('严重', {exact: true}).first()).toBeVisible();
        await expect(drawer.getByText('通过', {exact: true}).first()).toBeVisible();
    });

    // ==================== D. UI 元数据页「质量」tab ====================

    test('D1 元数据页「质量」tab：评分卡片 + 规则最近结果 + 立即执行按钮', async ({page}) => {
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password,
            `/governance/metadata?tableId=${TABLE_SEVERE_ID}`);
        // 自动选中表后点击「质量」tab
        await page.getByRole('tab', {name: /质量/}).click();
        // 评分概览卡片：健康度「差」+ 通过/警告/严重 + 启用规则数 + 立即执行按钮
        await expect(page.getByText('差', {exact: true}).first()).toBeVisible({timeout: 15000});
        await expect(page.getByText(/1 \/ 0 \/ 1/)).toBeVisible();
        await expect(page.getByText('启用规则数', {exact: true})).toBeVisible();
        await expect(page.locator('div').filter({hasText: '启用规则数'}).getByText('2', {exact: true})).toBeVisible(); // 2 条启用规则
        await expect(page.getByRole('button', {name: /立即执行全部规则/})).toBeVisible();
        // 规则最近结果列表
        await expect(page.getByRole('heading', {name: '规则最近结果'})).toBeVisible();
        await expect(page.getByText(`${SCORE_PREFIX}_severe_r1`, {exact: true})).toBeVisible();
        await expect(page.getByText('严重', {exact: true}).first()).toBeVisible();
    });

    // ==================== E. 权限 ====================

    test('E1 工程师可查看评分列表，但不能改扣分配置', async ({page}) => {
        await gotoAs(page, TEST_USERS.engineer.username, TEST_USERS.engineer.password, '/governance/quality-scores');
        await expect(page.getByRole('heading', {name: '表级质量评分'})).toBeVisible();
        // 工程师无写权限：不显示「扣分配置」按钮
        await expect(page.getByRole('button', {name: /扣分配置/})).toHaveCount(0);
        // API：PUT config 被拒（403）
        const engineer = await Api.create();
        await engineer.login(TEST_USERS.engineer.username, TEST_USERS.engineer.password);
        const env = await engineer.raw('PUT', '/governance/quality/scores/config', {
            warningDeduct: 10, severeDeduct: 30, badThreshold: 60,
        });
        expect(env.code).not.toBe(200);
        await engineer.dispose();
    });

    // ==================== F. 扣分配置（全局，放最后 + 末尾恢复默认） ====================

    test('F1 扣分配置：修改 warningDeduct 后重算警告表分数变化', async ({page}) => {
        // 记录当前警告表分数（默认配置应为 70.00）
        await waitScore(TABLE_WARN_ID, {pass: 1, warning: 1, severe: 0});
        // 打开扣分配置弹窗，读默认值
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/governance/quality-scores');
        await page.getByRole('button', {name: /扣分配置/}).click();
        const configModal = page.getByRole('dialog', {name: '扣分配置（质量评分全局配置）'});
        await configModal.waitFor({state: 'visible', timeout: 10000});
        // 三个输入框按 label 定位
        const warningInput = configModal.getByText('警告规则每权重扣分').locator('..').locator('input');
        const severeInput = configModal.getByText('严重规则每权重扣分').locator('..').locator('input');
        const thresholdInput = configModal.getByText(/低分区阈值/).locator('..').locator('input');
        await expect(warningInput).toHaveValue('10');
        await expect(severeInput).toHaveValue('30');
        await expect(thresholdInput).toHaveValue('60');
        // 改为 warningDeduct=20 → 警告表 100×4/5-20 = 60.00
        await warningInput.fill('20');
        await configModal.getByRole('button', {name: '保存'}).click();
        // 保存后弹窗关闭
        await expect(configModal).toHaveCount(0, {timeout: 10000});

        // 重新执行警告表，等待重算：70.00 → 60.00（badThreshold=60，60≥60 仍 WARNING）
        await admin.post(`/governance/quality/scores/table/${TABLE_WARN_ID}/execute`);
        const res = await waitScore(TABLE_WARN_ID, {pass: 1, warning: 1, severe: 0}, {timeoutMs: 120_000});
        expect(res.score).toBe('60.00');
        expect(res.health).toBe('WARNING');

        // 恢复默认配置并重算回 70.00（保证幂等）
        await admin.put('/governance/quality/scores/config', {
            warningDeduct: 10, severeDeduct: 30, badThreshold: 60,
        });
        await admin.post(`/governance/quality/scores/table/${TABLE_WARN_ID}/execute`);
        const restored = await waitScore(TABLE_WARN_ID, {pass: 1, warning: 1, severe: 0});
        expect(restored.score).toBe('70.00');
    });
});
