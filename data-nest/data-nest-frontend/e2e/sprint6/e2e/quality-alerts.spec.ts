import {expect, type Page, test} from '@playwright/test';
import {Api} from '../helpers/api';
import {
    ADMIN,
    ALERT_PREFIX,
    ALERT_JOB_ID,
    ALERT_JOB_SEVERE_ONLY_ID,
    ALERT_JOB_UNAVAILABLE_ID,
    ALERT_JOB_PASS_ID,
    ALERT_RULE_SEVERE_ID,
    ALERT_RULE_WARNING_ID,
    ALERT_RULE_SO_SEVERE_ID,
    ALERT_RULE_UNAVAILABLE_ID,
    ALERT_RULE_PASS_ID,
} from '../helpers/data';
import {psql, scalar, rows} from '../helpers/db';
import {waitFor} from '../helpers/poll';
import {gotoAs} from '../helpers/e2e';
import {seedExecTables, seedExecMetadata, seedQualityAlerts} from '../helpers/seed';
import {Mailhog, decodeMimeEncoded} from '../../sprint5/helpers/mailhog';

/**
 * Sprint 6 分级邮件告警 E2E 测试（业务视角走 UI，DB/MailHog 做确定性断言）。
 *
 * 功能语义（后端 task-core）：
 * - 质量规则按阈值分级判定 result_level：PASS / WARNING / SEVERE / UNAVAILABLE(SQL 失败)
 * - 批次收尾 fireBatchAlert 按任务 alert_level（SEVERE_ONLY 仅严重 / SEVERE_WARNING 严重+警告）
 *   过滤达标明细，合并为一封邮件 + 每条异常写一条 alert_history；批次 alert_sent=1 幂等。
 * - UNAVAILABLE / PASS 不触发告警（R2：环境抖动不误报）。
 * - 告警复用 alert_rule 体系，对象类型 QUALITY（对象=质量任务），triggerConditions=['FAILURE']。
 *
 * 数据设计（seed 由 seedQualityAlerts 播种，复用 MYSQL 执行数据源 + e2e_s6_orders(COUNT=4)）：
 * - 主链路任务 ALERT_JOB_ID（SEVERE_WARNING）：SEVERE 规则(value4≥3) + WARNING 规则(value4∈[3,5))
 * - SEVERE_ONLY 任务 ALERT_JOB_SEVERE_ONLY_ID：SEVERE 规则 + WARNING 规则（WARNING 应被排除）
 * - UNAVAILABLE 任务 ALERT_JOB_UNAVAILABLE_ID：查不存在表 → SQL 失败 → UNAVAILABLE（不告警）
 * - PASS 任务 ALERT_JOB_PASS_ID：阈值 value4<5 → PASS（不告警）
 *
 * 告警规则在「告警中心」创建（UI 走完整流程），接收用户为 govAdmin（已配邮箱）。
 * 执行是异步的（经 XXL-JOB 投递 app-worker），测试用 waitFor 轮询 quality_check_batch 至终态。
 */
test.describe.configure({mode: 'serial'});

const ALERT_JOB_NAME_MAIN = `${ALERT_PREFIX}_main`;
const ALERT_RULE_SEVERE_NAME = `${ALERT_PREFIX}_severe_rule`;
const ALERT_RULE_WARNING_NAME = `${ALERT_PREFIX}_warning_rule`;

let admin: Api;
let mailhog: Mailhog;

/** 定位当前行：按「任务名称/批次名称」列精确匹配表格行 */
function rowBy(page: Page, name: string) {
    return page.locator('.ant-table-row').filter({
        has: page.locator('.ant-table-cell:first-child').getByText(name, {exact: true}),
    });
}

/** 轮询批次表：等待指定任务（job_id）的 MANUAL 批次进入终态，返回批次行 */
async function waitBatch(
    jobId: string,
    opts: { timeoutMs?: number } = {},
): Promise<{id: string; status: string}> {
    const {timeoutMs = 120_000} = opts;
    return waitFor(
        async () => {
            const id = scalar(`SELECT id FROM quality_check_batch
                                WHERE job_id=${jobId} AND trigger_type='MANUAL' ORDER BY id DESC LIMIT 1`);
            if (!id) return null;
            return {
                id,
                status: scalar(`SELECT status FROM quality_check_batch WHERE id=${id}`) ?? 'RUNNING',
            };
        },
        (b) => b != null && b.status !== 'RUNNING',
        {timeoutMs, label: `batch(job=${jobId}) 进入终态`},
    ) as unknown as {id: string; status: string};
}

/** 查询指定任务的 QUALITY 告警历史明细（rule_name + send_status） */
function alertHistories(jobId: string): Array<{ruleName: string; sendStatus: string}> {
    return rows(`SELECT rule_name, send_status FROM alert_history
                 WHERE object_type='QUALITY' AND object_id=${jobId} AND alert_type='FAILURE'
                 ORDER BY id`).map(([ruleName, sendStatus]) => ({ruleName, sendStatus}));
}

/** 查询指定任务的最新批次 alert_sent 标记 */
function alertSent(jobId: string): string {
    return scalar(`SELECT alert_sent FROM quality_check_batch
                   WHERE job_id=${jobId} ORDER BY id DESC LIMIT 1`) ?? '0';
}

/** 解码 quoted-printable（正文传输编码），移除软换行（=\r\n）后还原字节 */
function decodeBody(msg: {Content?: {Body?: string}}): string {
    const raw = msg.Content?.Body ?? '';
    const softRemoved = raw.replace(/=\r?\n/g, '');
    const bytes: number[] = [];
    let i = 0;
    while (i < softRemoved.length) {
        if (softRemoved[i] === '=' && i + 2 < softRemoved.length
            && /^[0-9A-Fa-f]{2}$/.test(softRemoved.slice(i + 1, i + 3))) {
            bytes.push(parseInt(softRemoved.slice(i + 1, i + 3), 16));
            i += 3;
        } else if (softRemoved[i] === '=') {
            // 转义等号本身
            bytes.push(0x3d);
            i += 1;
        } else {
            const c = softRemoved.charCodeAt(i);
            if (c < 128) {
                bytes.push(c);
                i += 1;
            } else {
                Buffer.from(softRemoved[i], 'utf8').forEach((b) => bytes.push(b));
                i += 1;
            }
        }
    }
    return Buffer.from(bytes).toString('utf8');
}

/** 轮询：等待某 job 出现目标主题关键词的邮件，返回匹配的邮件 */
async function waitMail(subjectKeyword: string, opts: {timeoutMs?: number} = {}): Promise<unknown> {
    const {timeoutMs = 30_000} = opts;
    return waitFor(
        async () => {
            const msgs = await mailhog.find(subjectKeyword);
            return msgs.length ? msgs[0] : null;
        },
        (m) => m != null,
        {timeoutMs, label: `邮件主题含「${subjectKeyword}」`},
    ) as unknown as {Content: {Body: string}};
}

test.describe('Sprint 6 分级邮件告警', () => {
    test.beforeAll(async () => {
        admin = await Api.create();
        await admin.login(ADMIN.username, ADMIN.password);
        mailhog = new Mailhog();
        await mailhog.init();

        // 确保执行数据源 / 表 / 分级任务规则就绪（幂等，兼容独立运行）
        seedExecTables();
        seedExecMetadata();
        seedQualityAlerts();

        // 清空历史邮件与告警历史，避免串扰
        await mailhog.deleteAll();
        psql(`DELETE FROM alert_history WHERE object_type='QUALITY' AND object_id IN (
            ${ALERT_JOB_ID}, ${ALERT_JOB_SEVERE_ONLY_ID}, ${ALERT_JOB_UNAVAILABLE_ID}, ${ALERT_JOB_PASS_ID})`);
        psql(`DELETE FROM quality_check_detail WHERE batch_id IN (
            SELECT id FROM quality_check_batch WHERE job_id IN (
                ${ALERT_JOB_ID}, ${ALERT_JOB_SEVERE_ONLY_ID}, ${ALERT_JOB_UNAVAILABLE_ID}, ${ALERT_JOB_PASS_ID}))`);
        psql(`DELETE FROM quality_check_batch WHERE job_id IN (
            ${ALERT_JOB_ID}, ${ALERT_JOB_SEVERE_ONLY_ID}, ${ALERT_JOB_UNAVAILABLE_ID}, ${ALERT_JOB_PASS_ID})`);
        // 清理告警规则（QUALITY 对象类型，按前缀）
        psql(`DELETE FROM alert_rule WHERE name LIKE '${ALERT_PREFIX}%'`);
    });

    test.afterAll(async () => {
        psql(`DELETE FROM alert_history WHERE object_type='QUALITY' AND object_id IN (
            ${ALERT_JOB_ID}, ${ALERT_JOB_SEVERE_ONLY_ID}, ${ALERT_JOB_UNAVAILABLE_ID}, ${ALERT_JOB_PASS_ID})`);
        psql(`DELETE FROM quality_check_detail WHERE batch_id IN (
            SELECT id FROM quality_check_batch WHERE job_id IN (
                ${ALERT_JOB_ID}, ${ALERT_JOB_SEVERE_ONLY_ID}, ${ALERT_JOB_UNAVAILABLE_ID}, ${ALERT_JOB_PASS_ID}))`);
        psql(`DELETE FROM quality_check_batch WHERE job_id IN (
            ${ALERT_JOB_ID}, ${ALERT_JOB_SEVERE_ONLY_ID}, ${ALERT_JOB_UNAVAILABLE_ID}, ${ALERT_JOB_PASS_ID})`);
        psql(`DELETE FROM alert_rule WHERE name LIKE '${ALERT_PREFIX}%'`);
        await mailhog.dispose();
        await admin.dispose();
    });

    test('UI 创建 QUALITY 告警规则（选质量任务 + 失败 + 接收用户 govAdmin）', async ({page}) => {
        await gotoAs(page, ADMIN.username, ADMIN.password, '/system/alert-center');
        await expect(page.getByRole('heading', {name: '告警中心'})).toBeVisible();

        await page.getByRole('button', {name: /新增告警规则/}).click();
        const modal = page.getByRole('dialog', {name: '新增告警规则'});
        await modal.waitFor({state: 'visible', timeout: 10000});

        // 规则名称
        await modal.getByPlaceholder('如：财务夜间同步失败告警').fill(`${ALERT_PREFIX}_rule`);

        // 对象类型 = 质量任务（modal 内第一个 .ant-select）
        const objTypeSelect = modal.locator('.ant-select').nth(0);
        await objTypeSelect.click();
        await page.locator('.ant-select-dropdown:visible').getByText('质量任务', {exact: true}).click();

        // 对象 = 主链路 + SEVERE_ONLY 两个质量任务（multiple + 本地过滤；让同一告警规则覆盖两条链路）
        const objSelect = modal.locator('.ant-select').nth(1);
        await objSelect.click();
        await objSelect.locator('input').fill(ALERT_JOB_NAME_MAIN);
        let option = page.locator('.ant-select-dropdown:visible')
            .locator('.ant-select-item-option').filter({hasText: ALERT_JOB_NAME_MAIN}).first();
        await option.waitFor({state: 'visible', timeout: 8000});
        await option.click();
        // 选完一项后搜索框清空，继续输入第二个关键词（multiple Select dropdown 保持打开）
        await objSelect.locator('input').fill(`${ALERT_PREFIX}_severe_only`);
        option = page.locator('.ant-select-dropdown:visible')
            .locator('.ant-select-item-option').filter({hasText: `${ALERT_PREFIX}_severe_only`}).first();
        await option.waitFor({state: 'visible', timeout: 8000});
        await option.click();

        // 接收用户 = govAdmin（远程搜索，filterOption=false，需输入触发 onSearch）
        const userSelect = modal.locator('.ant-select').nth(2);
        await userSelect.click();
        await userSelect.locator('input').fill('govadmin');
        const userOpt = page.locator('.ant-select-dropdown:visible')
            .locator('.ant-select-item-option').filter({hasText: 'govadmin'}).first();
        await userOpt.waitFor({state: 'visible', timeout: 8000});
        await userOpt.click();
        // multiple Select 选中后 dropdown 保持打开；点击弹窗标题关闭 antd dropdown（不能用 Escape，
        // 否则会同时关闭 DsModal）
        await modal.getByText('新增告警规则', {exact: true}).click();

        // 保存
        await modal.getByRole('button', {name: '保存'}).click();
        await expect(page.getByText('告警规则已保存')).toBeVisible({timeout: 8000});

        // 断言规则已创建（API/DB 侧），且列表出现质量任务对象
        const ruleId = scalar(`SELECT id FROM alert_rule WHERE name='${ALERT_PREFIX}_rule'`);
        expect(ruleId).toBeTruthy();
        await expect(rowBy(page, ALERT_PREFIX + '_rule').getByText('质量任务', {exact: true})).toBeVisible();
    });

    test('主链路：SEVERE_WARNING 任务执行 → SEVERE+WARNING 分级告警（DB + 邮件）', async ({page}) => {
        // 业务视角：在数据质量页点「执行」按钮触发任务
        await gotoAs(page, ADMIN.username, ADMIN.password, '/governance/data-quality');
        const row = rowBy(page, ALERT_JOB_NAME_MAIN);
        await expect(row).toBeVisible({timeout: 15000});
        await row.getByLabel('执行').click();

        // 轮询批次进入终态
        const batch = await waitBatch(ALERT_JOB_ID);

        // 明细分级：SEVERE 规则 → SEVERE；WARNING 规则 → WARNING
        const severeLevel = scalar(`SELECT result_level FROM quality_check_detail
                                    WHERE batch_id=${batch.id} AND rule_id=${ALERT_RULE_SEVERE_ID}`);
        const warningLevel = scalar(`SELECT result_level FROM quality_check_detail
                                     WHERE batch_id=${batch.id} AND rule_id=${ALERT_RULE_WARNING_ID}`);
        expect(severeLevel).toBe('SEVERE');
        expect(warningLevel).toBe('WARNING');

        // 批次 alert_sent 已置 1
        expect(alertSent(ALERT_JOB_ID)).toBe('1');

        // alert_history：SEVERE + WARNING 两条达标明细 → 写 2 条历史（rule_name 统一为告警规则名）
        const histories = alertHistories(ALERT_JOB_ID);
        expect(histories.length).toBe(2);
        expect(histories.every(h => h.sendStatus === 'SUCCESS')).toBe(true);

        // 邮件：合并一封，主题含「质量任务『name』执行失败（2 项）」，正文含 [严重]/[警告]
        const mail = await waitMail(ALERT_JOB_NAME_MAIN);
        expect(mail.Content.Body).toBeTruthy();
        const body = decodeBody(mail);
        expect(body).toContain('[严重]');
        expect(body).toContain('[警告]');
        expect(body).toContain('共 2 项');
    });

    test('幂等：再次执行主链路任务不重复发告警（alert_history 条数不增）', async () => {
        // 紧接主链路执行，60s 防重窗口内再次触发；countRecent + alert_sent 双保险，不新增 alert_history
        const before = alertHistories(ALERT_JOB_ID).length;
        expect(before).toBeGreaterThan(0);
        await admin.post(`/governance/quality/jobs/${ALERT_JOB_ID}/execute`);
        await waitBatch(ALERT_JOB_ID);
        const after = alertHistories(ALERT_JOB_ID).length;
        expect(after).toBe(before);
    });

    test('UI 质量检查历史详情：SEVERE/WARNING 分级徽章', async ({page}) => {
        await gotoAs(page, ADMIN.username, ADMIN.password, '/governance/quality-checks');
        await page.getByLabel('按触发方式筛选').selectOption('MANUAL');
        await page.getByRole('button', {name: /查询/}).click();
        // 主链路任务可能已有多个 MANUAL 批次（主链路 + 幂等），取最新一行
        const row = rowBy(page, ALERT_JOB_NAME_MAIN).first();
        await expect(row).toBeVisible({timeout: 15000});
        await row.getByLabel('查看明细').click();
        const drawer = page.getByRole('dialog', {name: ALERT_JOB_NAME_MAIN});
        await drawer.waitFor({state: 'visible', timeout: 10000});

        // SEVERE 规则 → 「严重」徽章；WARNING 规则 → 「警告」徽章
        await expect(drawer.getByText(ALERT_RULE_SEVERE_NAME, {exact: true})).toBeVisible();
        await expect(drawer.getByText(ALERT_RULE_WARNING_NAME, {exact: true})).toBeVisible();
        await expect(drawer.getByText('严重', {exact: true}).first()).toBeVisible();
        await expect(drawer.getByText('警告', {exact: true}).first()).toBeVisible();

        // 结果值 = 4（前端格式化为整数）
        await expect(drawer.getByText('4', {exact: true}).first()).toBeVisible();

        await drawer.getByRole('button', {name: '关闭'}).last().click();
    });

    test('UI 告警中心历史页：QUALITY 记录展示', async ({page}) => {
        await gotoAs(page, ADMIN.username, ADMIN.password, '/system/alert-center');
        await page.getByRole('button', {name: '告警历史'}).click();
        await expect(page.getByLabel('按对象类型筛选')).toBeVisible();
        await page.getByLabel('按对象类型筛选').selectOption('QUALITY');
        await page.getByRole('button', {name: /查询/}).click();
        // 主链路任务产生 2 条 QUALITY 告警历史，对象类型徽章=质量任务，发送成功
        const historyRow = page.locator('.ant-table-row').filter({hasText: ALERT_JOB_NAME_MAIN});
        await expect(historyRow).toHaveCount(2, {timeout: 15000});
        // 断言表格行内的徽章（避免命中筛选下拉里的 option 文本）
        await expect(historyRow.first().getByText('质量任务', {exact: true})).toBeVisible();
        await expect(historyRow.first().getByText('发送成功', {exact: true})).toBeVisible();
        await expect(historyRow.first().getByText('失败', {exact: true})).toBeVisible();
    });

    test('负向：UNAVAILABLE（SQL 失败）不告警', async () => {
        await admin.post(`/governance/quality/jobs/${ALERT_JOB_UNAVAILABLE_ID}/execute`);
        await waitBatch(ALERT_JOB_UNAVAILABLE_ID);
        const level = scalar(`SELECT result_level FROM quality_check_detail
                              WHERE rule_id=${ALERT_RULE_UNAVAILABLE_ID}`);
        expect(level).toBe('UNAVAILABLE');
        const histories = alertHistories(ALERT_JOB_UNAVAILABLE_ID);
        expect(histories.length).toBe(0);
    });

    test('负向：PASS（低于阈值）不告警', async () => {
        await admin.post(`/governance/quality/jobs/${ALERT_JOB_PASS_ID}/execute`);
        await waitBatch(ALERT_JOB_PASS_ID);
        const level = scalar(`SELECT result_level FROM quality_check_detail
                              WHERE rule_id=${ALERT_RULE_PASS_ID}`);
        expect(level).toBe('PASS');
        const histories = alertHistories(ALERT_JOB_PASS_ID);
        expect(histories.length).toBe(0);
    });

    test('负向：SEVERE_ONLY 任务排除 WARNING，只收 SEVERE', async () => {
        await admin.post(`/governance/quality/jobs/${ALERT_JOB_SEVERE_ONLY_ID}/execute`);
        const batch = await waitBatch(ALERT_JOB_SEVERE_ONLY_ID);
        const severeLevel = scalar(`SELECT result_level FROM quality_check_detail
                                    WHERE batch_id=${batch.id} AND rule_id=${ALERT_RULE_SO_SEVERE_ID}`);
        expect(severeLevel).toBe('SEVERE');

        // alert_history 仅 1 条（只有 SEVERE 达标明细告警，WARNING 被 SEVERE_ONLY 排除）
        const histories = alertHistories(ALERT_JOB_SEVERE_ONLY_ID);
        expect(histories.length).toBe(1);
        // 邮件仅 1 项 [严重]，无 [警告]
        const mail = await waitMail(`${ALERT_PREFIX}_severe_only`);
        const body = decodeBody(mail);
        expect(body).toContain('共 1 项');
        expect(body).toContain('[严重]');
        expect(body).not.toContain('[警告]');
    });

});
