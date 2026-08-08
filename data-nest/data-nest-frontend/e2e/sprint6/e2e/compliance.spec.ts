import {expect, type Page, request as pwRequest, test} from '@playwright/test';
import {Api, API_BASE} from '../helpers/api';
import {
    ADMIN,
    TEST_USERS,
    COMPLIANCE_PREFIX,
    COMPLIANCE_DS_ID,
    COMPLIANCE_DS_NAME,
    COMPLIANCE_TABLE,
    COMPLIANCE_TABLE_ID,
    COMPLIANCE_NS_COL_NAME,
    POWERJOB_HANDLER_COMPLIANCE,
} from '../helpers/data';
import {psql, rows, scalar} from '../helpers/db';
import {waitFor} from '../helpers/poll';
import {gotoAs} from '../helpers/e2e';
import {ensureTestUsers, seedCompliance} from '../helpers/seed';
import {PowerJobClient} from '../helpers/powerjob';

/**
 * Sprint 6 标准合规检查 E2E 测试（业务视角）。
 *
 * 业务链路：治理员配置「命名规范（表前缀/字段前缀）+ 字段类型标准」→ 手动扫描合规数据源
 * → 元数据中不符合规范的表/字段落「不合规项」→ 分页/筛选/忽略/取消忽略/导出管理 → 统计三格变化
 * → 工程师可查看/忽略但不可扫描 → 分析师不可查看 → 定时 handler（PowerJob）全量扫描。
 *
 * 播种（seedCompliance，固定 ID 段 900006...）：
 * - 合规专属数据源 e2e_s6_compliance_ds + 1 张表 e2e_s6_compliance_orders（列 id/order_no/amount）
 * - 命名规范 TABLE: PREFIX dwd_；命名规范 COLUMN: PREFIX order_（关联字段类型标准）
 * - 字段类型标准 e2e_s6_compliance_type: allowedTypes=["INT"]
 *
 * 判定（扫描合规数据源，checkNaming+checkFieldType）共 4 条不合规：
 * - 表 e2e_s6_compliance_orders 不以 dwd_ 开头 → NAMING TABLE ×1
 * - 列 id / amount 不匹配 order_ → NAMING COLUMN ×2
 * - 列 order_no 匹配 order_，varchar 不在 [INT] → TYPE COLUMN ×1
 * 统计：totalObjects=1表+3列=4；初始 nonCompliant=4、ignored=0、rate=0.0%。
 */

const DS = 'testdb';
const fullObjectPath = (suffix = '') => `${DS}.${COMPLIANCE_TABLE}${suffix}`;

let admin: Api;

test.describe.configure({mode: 'serial'});

test.describe('Sprint 6 标准合规检查（判定 + UI + 忽略 + 权限 + 定时）', () => {
    test.beforeAll(async () => {
        admin = await Api.create();
        await admin.login(ADMIN.username, ADMIN.password);
        // 播种：测试用户 + 合规数据源 / 标准；仅清 e2e 相关合规结果（保护环境真实数据源的历史结果）
        await ensureTestUsers();
        seedCompliance();
        psql("DELETE FROM compliance_check_result WHERE object_path LIKE '%e2e_s6%'");
        // 触发手动扫描（合规数据源），生成 4 条不合规
        await admin.post('/governance/data-standards/compliance-check', {
            datasourceIds: [COMPLIANCE_DS_ID],
            checkNaming: true,
            checkFieldType: true,
        });
    });

    test.afterAll(async () => {
        await admin.dispose();
    });

    // ==================== A. DB 判定 ====================

    test('A1 扫描合规数据源 → 恰好 4 条不合规（1 表命名 + 2 列命名 + 1 字段类型）', async () => {
        const list = rows(`SELECT object_type, violation_type, object_name
                           FROM compliance_check_result WHERE table_id=${COMPLIANCE_TABLE_ID}
                           ORDER BY violation_type, object_name`);
        const mapped = list.map((r) => ({type: r[0], vt: r[1], name: r[2]}));
        expect(mapped).toHaveLength(4);
        // NAMING TABLE：表名
        expect(mapped).toContainEqual({type: 'TABLE', vt: 'NAMING', name: COMPLIANCE_TABLE});
        // NAMING COLUMN：id / amount
        expect(mapped).toContainEqual({type: 'COLUMN', vt: 'NAMING', name: 'id'});
        expect(mapped).toContainEqual({type: 'COLUMN', vt: 'NAMING', name: 'amount'});
        // TYPE COLUMN：order_no
        expect(mapped).toContainEqual({type: 'COLUMN', vt: 'TYPE', name: 'order_no'});
    });

    test('A2 NAMING 表不合规字段正确（未命中规范）', async () => {
        const row = psql(`SELECT object_path, standard_name, actual_value, expected_value, ignored
                          FROM compliance_check_result
                          WHERE table_id=${COMPLIANCE_TABLE_ID} AND object_type='TABLE' AND violation_type='NAMING'`);
        expect(row).toBeTruthy();
        const [path, std, actual, expected, ignored] = row.split('|');
        expect(path).toBe(fullObjectPath());
        expect(std).toBe('未命中命名规范');
        expect(actual).toBe('');
        expect(expected).toBe('');
        expect(ignored).toBe('0');
    });

    test('A3 TYPE 字段不合规字段正确（命中 order_ 规范但类型不符）', async () => {
        const row = psql(`SELECT object_path, standard_name, object_name, actual_value, expected_value
                          FROM compliance_check_result
                          WHERE table_id=${COMPLIANCE_TABLE_ID} AND violation_type='TYPE'`);
        expect(row).toBeTruthy();
        const [path, std, name, actual, expected] = row.split('|');
        expect(path).toBe(fullObjectPath('.order_no'));
        expect(std).toBe(COMPLIANCE_NS_COL_NAME);
        expect(name).toBe('order_no');
        expect(actual).toBe('varchar');
        expect(expected).toBe('INT');
    });

    test('A4 重扫同范围幂等：结果仍为 4 条（先删后建）', async () => {
        await admin.post('/governance/data-standards/compliance-check', {
            datasourceIds: [COMPLIANCE_DS_ID],
            checkNaming: true,
            checkFieldType: true,
        });
        const cnt = scalar(`SELECT count(*) FROM compliance_check_result WHERE table_id=${COMPLIANCE_TABLE_ID}`);
        expect(cnt).toBe('4');
    });

    // ==================== B. 统计 / 分页 API ====================

    test('B1 summary 统计：不合规 4 / 忽略 0 / 合规率 0.0%', async () => {
        const s = await admin.post('/governance/data-standards/compliance-check/summary', {
            datasourceIds: [COMPLIANCE_DS_ID],
            checkNaming: true,
            checkFieldType: true,
        });
        // 后端 Long 经序列化为字符串，统一转 Number 断言
        expect(Number(s.nonCompliant)).toBe(4);
        expect(Number(s.ignored)).toBe(0);
        expect(Number(s.totalObjects)).toBe(4);
        expect(Number(s.complianceRate)).toBe(0.0);
    });

    test('B2 分页默认仅未忽略返回 4 条；按违规类型筛选 TYPE 返回 1 条', async () => {
        const all = await admin.post('/governance/data-standards/compliance-check/page', {
            datasourceIds: [COMPLIANCE_DS_ID],
            page: 1,
            pageSize: 10,
            ignored: 0,
        });
        expect(Number(all.total)).toBe(4);

        const typeOnly = await admin.post('/governance/data-standards/compliance-check/page', {
            datasourceIds: [COMPLIANCE_DS_ID],
            page: 1,
            pageSize: 10,
            ignored: 0,
            violationType: 'TYPE',
        });
        expect(Number(typeOnly.total)).toBe(1);
        expect(typeOnly.records[0].violationType).toBe('TYPE');
        expect(typeOnly.records[0].objectName).toBe('order_no');
    });

    // ==================== C. UI 标准合规页 ====================

    test('C1 标准合规页：选合规数据源 → 三格统计 + 列表展示 4 条不合规', async ({page}) => {
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/governance/compliance');
        await expect(page.getByRole('heading', {name: '标准合规'})).toBeVisible();
        // 先筛选到合规数据源，隔离环境真实数据源（其它数据源另有结果）
        await page.getByLabel('按数据源筛选').selectOption(COMPLIANCE_DS_ID);
        await page.getByRole('button', {name: /查询/}).click();
        // 三格统计（合规数据源范围内：4 不合规 / 0 忽略 / 0.0%）
        await expect(page.getByText('不合规项', {exact: true})).toBeVisible();
        await expect(page.locator('div').filter({hasText: '不合规项'}).getByText('4', {exact: true})).toBeVisible();
        await expect(page.getByText('已忽略', {exact: true})).toBeVisible();
        await expect(page.locator('div').filter({hasText: '已忽略'}).getByText('0', {exact: true})).toBeVisible();
        await expect(page.getByText('合规率', {exact: true})).toBeVisible();
        await expect(page.getByText('0.0%', {exact: true})).toBeVisible();
        // 列表：对象路径 + 各违规类型徽章（限定在 .ant-table 内，避免命中筛选下拉 option 文本）
        const table = page.locator('.ant-table');
        await expect(page.getByText(fullObjectPath(), {exact: true})).toBeVisible({timeout: 15000});
        await expect(page.getByText(fullObjectPath('.order_no'), {exact: true})).toBeVisible();
        // NAMING 徽章 3 个（表 + id + amount），TYPE 徽章 1 个（order_no）
        await expect(table.getByText('命名规范', {exact: true})).toHaveCount(3);
        await expect(table.getByText('字段类型', {exact: true})).toHaveCount(1);
    });

    test('C2 数据源筛选 + 违规类型筛选', async ({page}) => {
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/governance/compliance');
        // 数据源筛选：选择合规数据源
        await page.getByLabel('按数据源筛选').selectOption(COMPLIANCE_DS_ID);
        await page.getByRole('button', {name: /查询/}).click();
        await expect(page.getByText(fullObjectPath(), {exact: true})).toBeVisible({timeout: 15000});
        // 违规类型筛选「字段类型」→ 只剩 order_no 的 TYPE 记录
        await page.getByLabel('按违规类型筛选').selectOption('TYPE');
        await page.getByRole('button', {name: /查询/}).click();
        await expect(page.getByText(fullObjectPath('.order_no'), {exact: true})).toBeVisible({timeout: 15000});
        await expect(page.getByText(fullObjectPath(), {exact: true})).toHaveCount(0);
    });

    test('C3 立即扫描：勾选合规数据源开始扫描 → 扫描完成', async ({page}) => {
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/governance/compliance');
        await page.getByRole('button', {name: /立即扫描/}).click();
        const modal = page.getByRole('dialog', {name: '标准合规扫描'});
        await modal.waitFor({state: 'visible', timeout: 10000});
        // 勾选合规数据源
        await modal.getByText(COMPLIANCE_DS_NAME, {exact: true}).click();
        await modal.getByRole('button', {name: /开始扫描/}).click();
        // 扫描完成后弹窗关闭 + 列表刷新（仍为 4 条）
        await expect(modal).toHaveCount(0, {timeout: 15000});
        await expect(page.getByText(fullObjectPath(), {exact: true})).toBeVisible({timeout: 15000});
    });

    // ==================== D. 忽略 / 取消忽略 ====================

    test('D1 API 忽略一条 → 统计 不合规 3 / 忽略 1 / 合规率 25.0%', async () => {
        const id = scalar(`SELECT id FROM compliance_check_result
                           WHERE table_id=${COMPLIANCE_TABLE_ID} AND violation_type='TYPE' LIMIT 1`);
        expect(id).toBeTruthy();
        await admin.post(`/governance/data-standards/compliance-check/ignore/${id}`);
        // 统计变化
        const s = await admin.post('/governance/data-standards/compliance-check/summary', {
            datasourceIds: [COMPLIANCE_DS_ID],
            checkNaming: true,
            checkFieldType: true,
        });
        expect(Number(s.nonCompliant)).toBe(3);
        expect(Number(s.ignored)).toBe(1);
        expect(Number(s.totalObjects)).toBe(4);
        expect(Number(s.complianceRate)).toBe(25.0);
        // 默认分页（仅未忽略）→ 3 条；忽略筛选 → 1 条
        const unignored = await admin.post('/governance/data-standards/compliance-check/page', {
            datasourceIds: [COMPLIANCE_DS_ID], page: 1, pageSize: 10, ignored: 0,
        });
        expect(Number(unignored.total)).toBe(3);
        const ignoredOnly = await admin.post('/governance/data-standards/compliance-check/page', {
            datasourceIds: [COMPLIANCE_DS_ID], page: 1, pageSize: 10, ignored: 1,
        });
        expect(Number(ignoredOnly.total)).toBe(1);
        expect(ignoredOnly.records[0].objectName).toBe('order_no');
        // 取消忽略恢复
        await admin.post(`/governance/data-standards/compliance-check/unignore/${id}`);
        const restored = await admin.post('/governance/data-standards/compliance-check/summary', {
            datasourceIds: [COMPLIANCE_DS_ID], checkNaming: true, checkFieldType: true,
        });
        expect(Number(restored.nonCompliant)).toBe(4);
        expect(Number(restored.ignored)).toBe(0);
    });

    test('D2 UI 忽略一条 → 忽略状态徽章「已忽略」+ 统计变化；再取消忽略', async ({page}) => {
        // 先忽略 order_no（TYPE）
        const id = scalar(`SELECT id FROM compliance_check_result
                           WHERE table_id=${COMPLIANCE_TABLE_ID} AND violation_type='TYPE' LIMIT 1`);
        await admin.post(`/governance/data-standards/compliance-check/ignore/${id}`);
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/governance/compliance');
        // 先筛选到合规数据源，再忽略状态筛选「仅已忽略」→ order_no 行显示「已忽略」
        await page.getByLabel('按数据源筛选').selectOption(COMPLIANCE_DS_ID);
        await page.getByLabel('按忽略状态筛选').selectOption('1');
        await page.getByRole('button', {name: /查询/}).click();
        const row = page.locator('.ant-table-row').filter({hasText: fullObjectPath('.order_no')});
        await expect(row).toBeVisible({timeout: 15000});
        await expect(row.getByText('已忽略', {exact: true})).toBeVisible();
        // 统计：已忽略=1（定位「已忽略」label 父容器内的统计值，避免命中分页按钮的「1」）
        await expect(
            page.getByText('已忽略', {exact: true}).locator('..').locator('.text-ds-display').getByText('1', {exact: true}),
        ).toBeVisible();
        // 取消忽略：点「取消忽略」操作按钮 → 确认
        await row.getByLabel('取消忽略').click();
        const confirm = page.getByRole('dialog');
        await confirm.getByRole('button', {name: /确认取消/}).click();
        await expect(confirm).toHaveCount(0, {timeout: 10000});
        // 已忽略列表此时应为空（该条恢复未忽略）
        await expect(page.getByText(fullObjectPath('.order_no'), {exact: true})).toHaveCount(0, {timeout: 15000});
        // 恢复干净状态
        await admin.post(`/governance/data-standards/compliance-check/unignore/${id}`).catch(() => {});
    });

    // ==================== E. 导出 ====================

    test('E1 导出问题清单 CSV 含表头与不合规行', async () => {
        // 导出接口返回 text/csv 二进制，Api.raw 只解析 JSON，这里用 playwright 原生 fetch 拿文本
        const ctx = await pwRequest.newContext();
        const login = await ctx.post(`${API_BASE}/system/auth/login`, {
            data: {username: ADMIN.username, password: ADMIN.password},
        });
        const token = (await login.json()).data.token;
        const res = await ctx.post(`${API_BASE}/governance/data-standards/compliance-check/export`, {
            headers: {Authorization: token, 'Content-Type': 'application/json'},
            data: JSON.stringify({
                datasourceIds: [COMPLIANCE_DS_ID], checkNaming: true, checkFieldType: true,
            }),
        });
        expect(res.ok()).toBeTruthy();
        const csv = await res.text();
        await ctx.dispose();
        // CSV 含 UTF-8 BOM + 表头 + 不合规行
        expect(csv).toContain('对象路径,对象类型,违规类型,实际值,期望值,适用规范,检查时间,是否忽略');
        expect(csv).toContain(fullObjectPath('.order_no'));
        expect(csv).toContain('varchar');
        expect(csv).toContain('INT');
        // 默认导出未忽略（本用例前所有条目均未忽略）
        expect(csv).toContain('否');
    });

    // ==================== F. 权限 ====================

    test('F1 工程师可查看合规结果/忽略，但不可扫描/创建标准', async ({page}) => {
        // UI：工程师可见「标准合规」菜单与结果
        await gotoAs(page, TEST_USERS.engineer.username, TEST_USERS.engineer.password, '/governance/compliance');
        await expect(page.getByRole('heading', {name: '标准合规'})).toBeVisible();
        // 工程师无写权限：不显示「立即扫描」按钮
        await expect(page.getByRole('button', {name: /立即扫描/})).toHaveCount(0);
        // API：page 可查
        const engineer = await Api.create();
        await engineer.login(TEST_USERS.engineer.username, TEST_USERS.engineer.password);
        const pageRes = await engineer.post('/governance/data-standards/compliance-check/page', {
            datasourceIds: [COMPLIANCE_DS_ID], page: 1, pageSize: 10, ignored: 0,
        });
        expect(Number(pageRes.total)).toBe(4);
        // API：扫描被拒（403）
        const runEnv = await engineer.raw('POST', '/governance/data-standards/compliance-check', {
            datasourceIds: [COMPLIANCE_DS_ID], checkNaming: true, checkFieldType: true,
        });
        expect(runEnv.code).not.toBe(200);
        // API：创建命名规范被拒（403）
        const createEnv = await engineer.raw('POST', '/governance/data-standards/naming-standards', {
            name: `${COMPLIANCE_PREFIX}_forbidden`, appliesTo: 'TABLE', ruleType: 'PREFIX', ruleValue: 'x_',
        });
        expect(createEnv.code).not.toBe(200);
        await engineer.dispose();
    });

    test('F2 分析师不可查看合规结果（页面无菜单 + API 403）', async ({page}) => {
        await gotoAs(page, TEST_USERS.analyst.username, TEST_USERS.analyst.password, '/governance/compliance');
        await expect(page.getByRole('heading', {name: '标准合规'})).toHaveCount(0, {timeout: 15000});
        const analyst = await Api.create();
        await analyst.login(TEST_USERS.analyst.username, TEST_USERS.analyst.password);
        const env = await analyst.raw('POST', '/governance/data-standards/compliance-check/page', {
            datasourceIds: [COMPLIANCE_DS_ID], page: 1, pageSize: 10, ignored: 0,
        });
        expect(env.code).not.toBe(200);
        await analyst.dispose();
    });

    // ==================== G. 定时 handler（PowerJob 全量扫描） ====================

    test('G1 定时 handler：PowerJob 触发标准合规定时扫描 → 合规专属表结果被重建', async () => {
        // 先删合规专属表的结果，制造「仅靠定时扫描才能恢复」的干净基线（不动真实数据源结果）
        psql(`DELETE FROM compliance_check_result WHERE table_id=${COMPLIANCE_TABLE_ID}`);
        expect(scalar(`SELECT count(*) FROM compliance_check_result WHERE table_id=${COMPLIANCE_TABLE_ID}`)).toBe('0');
        // 通过 PowerJob OpenAPI 触发 standardComplianceCheckHandler（等价于定时触发，全量扫描在线数据源）
        const pj = await PowerJobClient.create();
        const jobId = await pj.findJobIdByProcessor(POWERJOB_HANDLER_COMPLIANCE);
        await pj.runJob(jobId);
        await pj.dispose();
        // 轮询：合规专属表结果被定时扫描重建为 4 条（证明 handler 执行了 check 全量路径）
        await waitFor(
            async () => {
                const own = scalar(`SELECT count(*) FROM compliance_check_result WHERE table_id=${COMPLIANCE_TABLE_ID}`);
                return own === '4' ? own : null;
            },
            (v) => v != null,
            {timeoutMs: 60_000, label: '定时扫描后合规专属表重建 4 条结果'},
        );
        // 合规表结果明细仍为 1 表命名 + 2 列命名 + 1 字段类型
        const detailRows = rows(`SELECT object_type, violation_type, object_name
                                 FROM compliance_check_result WHERE table_id=${COMPLIANCE_TABLE_ID}`);
        const list = detailRows.map((r) => `${r[0]}:${r[1]}:${r[2]}`);
        expect(list).toHaveLength(4);
        expect(list).toContain(`TABLE:NAMING:${COMPLIANCE_TABLE}`);
        expect(list).toContain('COLUMN:TYPE:order_no');
    });
});
