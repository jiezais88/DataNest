import {expect, type Locator, type Page, test} from '@playwright/test';
import {API_BASE, Api} from '../../sprint6/helpers/api';
import {gotoAs} from '../../sprint6/helpers/e2e';
import {psqlGov, scalarGov} from '../../sprint7/helpers/db';
import {seedAll} from '../../sprint7/helpers/seed';
import {ADMIN, DS_ID, T1_ID, T2_ID, T3_ID, T4_ID, TEST_USERS} from '../../sprint7/helpers/data';

/**
 * Sprint 8 F3 质量报告 E2E 测试（DG-07 完整版业务主链路全覆盖，API 辅助诊断）。
 *
 * 覆盖：筛选联动选项（数据源/库/任务 + 双向联动 + 空态提示）、KPI 汇总（批次/明细/平均评分/通过率/待处理问题）、
 * 四档分布趋势、评分趋势（聚合模式 + 单表模式 + 4221）、表评分分布环图、数据源质量对比、
 * 问题清单（TOP6 + 全部抽屉分页 + 跳转表详情 + 阈值回填）、CSV 导出（BOM/公式注入防护/权限 1005）、
 * 存量评分历史补算（幂等）、草稿→查询应用模型、自定义时间校验、菜单与导出按钮权限。
 *
 * 测试数据：复用 sprint7 seedAll 的 e2e_s7 表（T1=95/T2=85/T3=70/T4=20）与 s7 批次（2 PASS + 1 WARNING）；
 * 本 spec 自播种：e2e_s8_报告任务/阈值规则/12 条问题明细（7 WARNING + 5 SEVERE）/评分历史快照（T1×3 + T2×1），
 * 固定 ID 段 900008xxxxxxxxxxxxxxx，beforeAll/afterAll 双向清理。
 */

let admin: Api;
let gov: Api;
let engineer: Api;
let analyst: Api;

// ==================== 固定测试数据 ====================

const JOB_ID = '9000080000000000121';
const JOB_NAME = 'e2e_s8_报告任务';
const RULE_J_ID = '9000080000000000111';
const RULE_J_NAME = 'e2e_s8_报告阈值规则';
const BATCH2_ID = '9000080000000000061';
/** CSV 公式注入验证规则名（首字符 = 必须被 CsvExportHelper.safe 前置单引号） */
const CSV_INJECT_NAME = '=HYPERLINK("http://evil","click")';

/** 本地日期 YYYY-MM-DD（offsetDays 相对今天；与 PG 容器同为 CST） */
function dayStr(offsetDays: number): string {
    const d = new Date(Date.now() + offsetDays * 86400000);
    const p = (n: number) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

/** ISO 本地时间（YYYY-MM-DDTHH:mm:ss，对齐后端 parseIso） */
function isoLocal(d: Date): string {
    const p = (n: number) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

/** 近 N 天范围请求体（endTime +10min 缓冲：PG 容器时钟比宿主快约 0.6s，新插入行的 created_at 可能略超宿主 now） */
const range30 = () => ({
    startTime: isoLocal(new Date(Date.now() - 30 * 86400000)),
    endTime: isoLocal(new Date(Date.now() + 600_000)),
});
/** DS_ID 过滤 + 近 30 天（本 spec 大部分断言的口径） */
const dsRange = () => ({datasourceId: DS_ID, ...range30()});

// ==================== 播种 / 清理 ====================

function cleanF3(): void {
    psqlGov(`DELETE FROM quality_score_history WHERE table_id IN (${T1_ID}, ${T2_ID}, ${T3_ID}, ${T4_ID})`);
    psqlGov(`DELETE FROM quality_check_detail WHERE batch_id = ${BATCH2_ID} OR rule_name LIKE 'e2e_s8%' OR rule_name LIKE '=HYPERLINK%'`);
    psqlGov(`DELETE FROM quality_check_batch WHERE id = ${BATCH2_ID}`);
    psqlGov(`DELETE FROM quality_rule WHERE id = ${RULE_J_ID} OR name LIKE 'e2e_s8%'`);
    psqlGov(`DELETE FROM quality_job WHERE id = ${JOB_ID} OR name LIKE 'e2e_s8%'`);
}

function seedF3(): void {
    cleanF3();
    // 质量任务 + 阈值规则（覆盖 T1；warning 0.1 / severe 0.5，供问题清单阈值回填断言）
    psqlGov(`INSERT INTO quality_job (id, name, datasource_id, enabled, created_at)
             VALUES (${JOB_ID}, '${JOB_NAME}', ${DS_ID}, 1, now())`);
    psqlGov(`INSERT INTO quality_rule
             (id, job_id, name, type, table_id, column_name, check_field, sql_expression, result_metric,
              warning_threshold, severe_threshold, weight, enabled, created_at, updated_at)
             VALUES (${RULE_J_ID}, ${JOB_ID}, '${RULE_J_NAME}', 'COMPLETENESS', ${T1_ID}, 'amount', 1,
                     'SELECT 0 AS null_rate', 'null_rate', 0.1, 0.5, 1, 1, now(), now())`);
    // 报告批次（挂任务 J1）
    psqlGov(`INSERT INTO quality_check_batch
             (id, job_id, job_name, trigger_type, status, started_at, ended_at, duration_ms, created_at, alert_sent)
             VALUES (${BATCH2_ID}, ${JOB_ID}, 'e2e_s8_报告批次', 'MANUAL', 'SUCCESS', now(), now(), 100, now(), 0)`);
    // 12 条问题明细：i=1..7 WARNING / i=8..12 SEVERE，T1/T2 交替；i=6 用公式注入规则名
    for (let i = 1; i <= 12; i++) {
        const level = i <= 7 ? 'WARNING' : 'SEVERE';
        const tableId = i % 2 === 1 ? T1_ID : T2_ID;
        const ruleName = i === 6 ? CSV_INJECT_NAME : `e2e_s8_问题规则_${i}`;
        psqlGov(`INSERT INTO quality_check_detail
                 (id, batch_id, rule_id, rule_name, rule_type, table_id, result_metric, result_value,
                  success, executed_sql, created_at, result_level)
                 VALUES (9000080000000000300 + ${i}, ${BATCH2_ID}, ${RULE_J_ID}, '${ruleName}', 'COMPLETENESS',
                         ${tableId}, 'null_rate', 0.5, 1, 'SELECT 0 AS null_rate', now(), '${level}')`);
    }
    // 评分历史快照：T1 三天（80→90→95），T2 今天（85）；供评分趋势聚合/单表模式
    psqlGov(`INSERT INTO quality_score_history
             (id, table_id, table_name, datasource_id, score, health_level, pass_rules, warning_rules, severe_rules, checked_at, created_at)
             VALUES
             (9000080000000000201, ${T1_ID}, 'testdb.e2e_s7_trade_orders', ${DS_ID}, 80.00, 'GOOD', 2, 1, 0, now() - interval '2 days', now()),
             (9000080000000000202, ${T1_ID}, 'testdb.e2e_s7_trade_orders', ${DS_ID}, 90.00, 'EXCELLENT', 3, 0, 0, now() - interval '1 days', now()),
             (9000080000000000203, ${T1_ID}, 'testdb.e2e_s7_trade_orders', ${DS_ID}, 95.00, 'EXCELLENT', 3, 0, 0, now(), now()),
             (9000080000000000204, ${T2_ID}, 'testdb.e2e_s7_trade_refunds', ${DS_ID}, 85.00, 'GOOD', 2, 0, 0, now(), now())`);
}

// ==================== UI 辅助 ====================

const notice = (page: Page, text: string | RegExp) =>
    page.locator('.ant-message-notice').filter({hasText: text}).first();

/** KPI 卡取值（label → 同卡数值文本；调用方传入 KPI 网格作用域，避免与图表副标题撞文案） */
function kpiValue(scope: Locator, label: string) {
    return scope.getByText(label, {exact: true}).locator('xpath=..').locator('div').nth(1);
}

test.describe.configure({mode: 'serial'});

test.beforeAll(async () => {
    admin = await Api.create();
    await admin.login(ADMIN.username, ADMIN.password);
    await seedAll();
    seedF3();
    gov = await Api.create();
    await gov.login(TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password);
    engineer = await Api.create();
    await engineer.login(TEST_USERS.engineer.username, TEST_USERS.engineer.password);
    analyst = await Api.create();
    await analyst.login(TEST_USERS.analyst.username, TEST_USERS.analyst.password);
});

test.afterAll(async () => {
    cleanF3();
    await admin?.dispose();
    await gov?.dispose();
    await engineer?.dispose();
    await analyst?.dispose();
});

// ==================== A. 筛选联动选项 ====================

test.describe('A. 筛选联动选项', () => {
    test('options：数据源（含内置 Doris）/ 库随数据源联动 / 任务随数据源联动', async () => {
        const all = await analyst.post<any>('/governance/quality/report/options', null);
        const dsNames = all.datasources.map((d: any) => d.name);
        expect(dsNames).toContain('e2e_s7_mysql_ds');
        expect(dsNames).toContain('Doris 数仓');

        // 选 e2e_s7 数据源：库只剩 testdb（带所属数据源），任务含 e2e_s8_报告任务
        const filtered = await analyst.post<any>(`/governance/quality/report/options?datasourceId=${DS_ID}`, null);
        expect(filtered.databases.map((d: any) => d.name)).toEqual(['testdb']);
        expect(filtered.databases[0].datasourceId).toBe(DS_ID);
        expect(filtered.jobs.map((j: any) => j.name)).toContain(JOB_NAME);

        // 内置 Doris（-1）：无任务 → 空列表（前端空态提示的数据基础）；库为 datanest/ods
        const doris = await analyst.post<any>('/governance/quality/report/options?datasourceId=-1', null);
        expect(doris.jobs).toEqual([]);
        expect(doris.databases.map((d: any) => d.name)).toContain('datanest');
    });
});

// ==================== B. KPI 汇总 ====================

test.describe('B. KPI 汇总', () => {
    test('按数据源过滤：批次/明细/通过率/待处理问题/平均评分精确命中', async () => {
        const s = await analyst.post<any>('/governance/quality/report/summary', dsRange());
        // s7 批次（2 PASS + 1 WARNING）+ 本 spec 批次（7 WARNING + 5 SEVERE）
        expect(s.batchCount).toBe('2');
        expect(s.detailCount).toBe('15');
        expect(Number(s.passRate)).toBeCloseTo(13.33, 2);
        expect(s.severeCount).toBe('5');
        expect(s.warningCount).toBe('8');
        // 平均评分 = T1..T4（95/85/70/20）均值
        expect(Number(s.avgScore)).toBeCloseTo(67.5, 2);
    });

    test('按质量任务过滤：明细收窄到任务批次，平均评分收窄到任务规则覆盖表', async () => {
        const s = await analyst.post<any>('/governance/quality/report/summary',
            {...dsRange(), jobId: JOB_ID});
        expect(s.batchCount).toBe('1');
        expect(s.detailCount).toBe('12');
        expect(s.severeCount).toBe('5');
        expect(s.warningCount).toBe('7');
        // J1 规则只覆盖 T1 → 平均评分 = T1 的 95
        expect(Number(s.avgScore)).toBe(95);
    });

    test('筛选无命中表：全零且平均评分缺省；非法时间范围 4221', async () => {
        const empty = await analyst.post<any>('/governance/quality/report/summary',
            {datasourceId: '999999999999', ...range30()});
        expect(empty.batchCount).toBe('0');
        expect(empty.detailCount).toBe('0');
        expect(empty.avgScore ?? null).toBeNull();

        const invalid = await analyst.raw('POST', '/governance/quality/report/summary', {
            startTime: isoLocal(new Date()), endTime: isoLocal(new Date(Date.now() - 86400000)),
        });
        expect(invalid.code).toBe(4221);
    });
});

// ==================== C. 趋势与分布 ====================

test.describe('C. 趋势与分布', () => {
    test('四档分布趋势：按天聚合命中当天计数', async () => {
        const trend = await analyst.post<any[]>('/governance/quality/report/level-trend', dsRange());
        const today = trend.find(p => p.day === dayStr(0));
        expect(today).toBeTruthy();
        expect(today.passCount).toBe('2');
        expect(today.warningCount).toBe('8');
        expect(today.severeCount).toBe('5');
        expect(today.unavailableCount).toBe('0');
        // 时间窗收窄到昨天：无数据
        const yesterday = await analyst.post<any[]>('/governance/quality/report/level-trend', {
            datasourceId: DS_ID,
            startTime: isoLocal(new Date(Date.now() - 2 * 86400000)),
            endTime: isoLocal(new Date(Date.now() - 86400000)),
        });
        expect(yesterday).toEqual([]);
    });

    test('评分趋势聚合模式：按天平均评分 + 表数', async () => {
        const trend = await analyst.post<any[]>('/governance/quality/report/score-trend', {
            datasourceId: DS_ID,
            startTime: isoLocal(new Date(Date.now() - 7 * 86400000)),
            endTime: isoLocal(new Date(Date.now() + 600_000)), // +10min 缓冲容器时钟偏移
        });
        expect(trend.length).toBe(3);
        const byDay = Object.fromEntries(trend.map(p => [p.day, p]));
        expect(Number(byDay[dayStr(-2)].avgScore)).toBe(80);
        expect(byDay[dayStr(-2)].tableCount).toBe('1');
        expect(Number(byDay[dayStr(-1)].avgScore)).toBe(90);
        // 今天：T1 95 + T2 85 → 均值 90，2 张表
        expect(Number(byDay[dayStr(0)].avgScore)).toBe(90);
        expect(byDay[dayStr(0)].tableCount).toBe('2');
    });

    test('评分趋势单表模式：T1 历史序列；表不存在 4221', async () => {
        const trend = await analyst.post<any[]>('/governance/quality/report/score-trend',
            {...range30(), tableId: T1_ID});
        expect(trend.length).toBe(3);
        expect(trend.map(p => Number(p.score))).toEqual([80, 90, 95]);
        expect(trend.map(p => p.healthLevel)).toEqual(['GOOD', 'EXCELLENT', 'EXCELLENT']);
        expect(trend[0].checkedAt).toBeTruthy();

        const missing = await analyst.raw('POST', '/governance/quality/report/score-trend',
            {...range30(), tableId: '999999999999'});
        expect(missing.code).toBe(4221);
    });

    test('表评分分布：四档计数 + 无评分表数（ONLINE 口径）', async () => {
        const d = await analyst.post<any>('/governance/quality/report/score-distribution', dsRange());
        expect(d.excellentCount).toBe('1');
        expect(d.goodCount).toBe('1');
        expect(d.warningCount).toBe('1');
        expect(d.badCount).toBe('1');
        expect(d.totalTables).toBe('4');
        expect(d.noScoreCount).toBe('0');
    });

    test('数据源质量对比：均分降序 + 数据源名回填', async () => {
        const rows = await analyst.post<any[]>('/governance/quality/report/datasource-comparison', dsRange());
        expect(rows.length).toBe(1);
        expect(rows[0].datasourceId).toBe(DS_ID);
        expect(rows[0].datasourceName).toBe('e2e_s7_mysql_ds');
        expect(Number(rows[0].avgScore)).toBeCloseTo(67.5, 1);
        expect(rows[0].tableCount).toBe('4');
    });
});

// ==================== D. 问题清单 ====================

test.describe('D. 问题清单', () => {
    test('分页 / 字段完整 / 阈值按规则回填 / 任务过滤', async () => {
        const page1 = await analyst.post<any>('/governance/quality/report/issues',
            {...dsRange(), page: 1, pageSize: 10});
        // 12 条本 spec 明细 + s7 批次 1 条 WARNING
        expect(page1.total).toBe('13');
        expect(page1.records.length).toBe(10);
        // id 倒序：首条 = 最后插入的 i=12（SEVERE，T2）
        const first = page1.records[0];
        expect(first.ruleName).toBe('e2e_s8_问题规则_12');
        expect(first.tableName).toBe('testdb.e2e_s7_trade_refunds');
        expect(first.resultLevel).toBe('SEVERE');
        expect(first.ruleType).toBe('COMPLETENESS');
        expect(Number(first.resultValue)).toBe(0.5);
        expect(Number(first.threshold)).toBe(0.5); // SEVERE 取严重阈值
        expect(first.checkedAt).toBeTruthy();
        // WARNING 行阈值取警告阈值 0.1
        const warn = page1.records.find((r: any) => r.ruleName === 'e2e_s8_问题规则_7');
        expect(Number(warn.threshold)).toBe(0.1);

        const page2 = await analyst.post<any>('/governance/quality/report/issues',
            {...dsRange(), page: 2, pageSize: 10});
        expect(page2.records.length).toBe(3);
        // s7 批次的 WARNING 明细也在清单内
        expect(page2.records.map((r: any) => r.ruleName)).toContain('e2e_s7_金额范围');

        // 任务过滤：只剩本 spec 批次的 12 条
        const byJob = await analyst.post<any>('/governance/quality/report/issues',
            {...dsRange(), jobId: JOB_ID, page: 1, pageSize: 20});
        expect(byJob.total).toBe('12');
    });
});

// ==================== E. CSV 导出与权限 ====================

test.describe('E. CSV 导出与权限', () => {
    test('治理员导出：BOM + 汇总段 + 问题清单全量 + 公式注入防护', async () => {
        const res = await gov.ctx.fetch(`${API_BASE}/governance/quality/report/export`, {
            method: 'POST',
            headers: {Authorization: gov.token!, 'Content-Type': 'application/json'},
            data: JSON.stringify(dsRange()),
        });
        expect(res.status()).toBe(200);
        expect(res.headers()['content-type']).toContain('text/csv');
        const body = await res.text();
        expect(body.charCodeAt(0)).toBe(0xFEFF);
        expect(body).toContain('检查批次数,规则明细数,平均评分,通过率(%)');
        expect(body).toContain('问题清单（严重/警告）');
        expect(body).toContain('表,规则,类型,结果指标,结果值,阈值,级别,检查时间');
        expect(body).toContain('testdb.e2e_s7_trade_orders');
        // 13 条问题全量导出（无截断标记）
        expect(body).toContain('e2e_s8_问题规则_12');
        expect(body).toContain('e2e_s7_金额范围');
        expect(body).not.toContain('已截断');
        // 枚举值中文化（2026-08-11 用户要求）：类型/级别不出现英文枚举
        expect(body).toContain('完整性');
        expect(body).toContain('严重');
        expect(body).toContain('警告');
        expect(body).not.toContain('COMPLETENESS');
        expect(body).not.toContain('SEVERE');
        expect(body).not.toContain('WARNING');
        // 公式注入防护：= 开头被前置单引号
        expect(body).toContain("'=HYPERLINK");
        // 时间格式约定（2026-08-11 用户确认）：yyyy-MM-dd HH:mm:ss，禁止 ISO 带 T
        expect(body).toMatch(/\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}/);
        expect(body).not.toMatch(/\d{4}-\d{2}-\d{2}T\d{2}/);
    });

    test('分析师/工程师导出与补算被 1005 拦截；读接口四角色可用', async () => {
        for (const api of [analyst, engineer]) {
            expect((await api.raw('POST', '/governance/quality/report/export', dsRange())).code).toBe(1005);
            expect((await api.raw('POST', '/governance/quality/report/backfill-score-history')).code).toBe(1005);
        }
        // 读接口四角色（抽查分析师/工程师）
        for (const api of [analyst, engineer]) {
            await api.post('/governance/quality/report/summary', dsRange());
            await api.post('/governance/quality/report/issues', {...dsRange(), page: 1, pageSize: 5});
        }
        // 非法范围导出 → 4221（流写出前参数错误仍是 JSON）
        const invalid = await gov.raw('POST', '/governance/quality/report/export', {
            startTime: isoLocal(new Date()), endTime: isoLocal(new Date(Date.now() - 86400000)),
        });
        expect(invalid.code).toBe(4221);
    });
});

// ==================== F. 页面主链路（UI） ====================

test.describe('F. 页面主链路', () => {
    test('菜单进入 → 一屏 Dashboard 结构 → 筛选查询 → KPI/清单精确联动', async ({page}) => {
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/governance/quality-report');
        await expect(page.getByRole('heading', {name: '质量报告'})).toBeVisible();
        // KPI × 5（限网格作用域，「平均评分」与对比卡副标题撞文案）+ 图表卡
        const kpiGrid = page.locator('.grid.grid-cols-5');
        for (const label of ['检查批次', '规则明细', '平均评分', '通过率', '待处理问题']) {
            await expect(kpiGrid.getByText(label, {exact: true})).toBeVisible();
        }
        for (const title of ['四档分布趋势', '平均评分趋势', '表评分分布', '数据源质量对比', '问题清单']) {
            await expect(page.getByText(title, {exact: true})).toBeVisible();
        }

        // 草稿筛选 → 查询应用：选 e2e_s7 数据源
        await page.getByLabel('按数据源筛选').selectOption({label: 'e2e_s7_mysql_ds'});
        await page.getByRole('button', {name: '查询', exact: true}).click();
        await expect(kpiValue(kpiGrid, '检查批次')).toHaveText(/^2批次$/);
        await expect(kpiValue(kpiGrid, '规则明细')).toHaveText(/^15条$/);
        await expect(kpiValue(kpiGrid, '平均评分')).toHaveText('67.5');
        await expect(kpiValue(kpiGrid, '通过率')).toHaveText(/^13\.33%$/);
        await expect(kpiValue(kpiGrid, '待处理问题')).toHaveText('13');
        await expect(page.getByText('严重 5 / 警告 8', {exact: true})).toBeVisible();

        // 问题清单 TOP6：首行 = 最新明细；查看全部 13 条
        await expect(page.getByText('e2e_s8_问题规则_12').first()).toBeVisible();
        await expect(page.getByText('范围内暂无待处理问题')).toHaveCount(0);
    });

    test('筛选双向联动：库选项带数据源后缀 + 选库精确带出数据源 + 无任务空态提示', async ({page}) => {
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/governance/quality-report');
        // 正向：选数据源 → 库/任务收窄（库选项为 库名（数据源名）复合展示）
        await page.getByLabel('按数据源筛选').selectOption({label: 'e2e_s7_mysql_ds'});
        await expect(page.getByLabel('按库筛选').locator('option')).toHaveText(['全部库', 'testdb（e2e_s7_mysql_ds）']);
        await expect(page.getByLabel('按质量任务筛选').locator('option')).toHaveText(['全部质量任务', JOB_NAME]);
        // 反向：选库 datanest（Doris 数仓）→ 数据源精确带出内置 Doris（复合键无歧义）
        await page.getByLabel('按数据源筛选').selectOption({label: '全部数据源'});
        await page.getByLabel('按库筛选').selectOption({label: 'datanest（Doris 数仓）'});
        await expect(page.getByLabel('按数据源筛选')).toHaveValue('-1');
        // 内置 Doris 无质量任务 → 空态提示选项
        await expect(page.getByLabel('按质量任务筛选').locator('option').first())
            .toHaveText('该数据源下暂无任务');
    });

    test('自定义时间未选起止 → 查询拦截提示', async ({page}) => {
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/governance/quality-report');
        await page.getByLabel('时间范围').selectOption('custom');
        await expect(page.getByPlaceholder('开始时间')).toBeVisible();
        await page.getByRole('button', {name: '查询', exact: true}).click();
        await expect(notice(page, '请选择自定义起止时间')).toBeVisible();
    });

    test('问题清单抽屉：分页 + 行点击跳转表详情质量页签', async ({page}) => {
        // 一屏 Dashboard 在 720p 视口下结构区被裁（行不可见），拉高视口再验行点击
        await page.setViewportSize({width: 1440, height: 1000});
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/governance/quality-report');
        await page.getByLabel('按数据源筛选').selectOption({label: 'e2e_s7_mysql_ds'});
        await page.getByRole('button', {name: '查询', exact: true}).click();
        await page.getByRole('button', {name: /查看全部 13 条/}).click();
        const drawer = page.getByRole('dialog');
        await expect(drawer.getByText('问题清单（13 条）')).toBeVisible();
        // 第 1 页 10 条（id 倒序，i=12..3）
        await expect(drawer.getByText('e2e_s8_问题规则_12')).toBeVisible();
        await expect(drawer.getByText('e2e_s8_问题规则_3')).toBeVisible();
        await expect(drawer.getByText('e2e_s8_问题规则_2')).toHaveCount(0);
        // 第 2 页 3 条（i=2/1 + s7 金额范围）
        await drawer.getByLabel('第 2 页').click();
        await expect(drawer.getByText('e2e_s8_问题规则_2')).toBeVisible();
        await expect(drawer.getByText('e2e_s8_问题规则_1')).toBeVisible();
        await expect(drawer.getByText('e2e_s7_金额范围')).toBeVisible();
        await drawer.getByLabel('关闭').click();

        // TOP6 首行点击 → 资产详情质量页签（行本身是 button，直接点行避免文本节点被卡片拦截）
        await page.getByRole('button', {name: /e2e_s8_问题规则_12/}).click();
        await page.waitForURL(u => u.pathname === `/asset-catalog/${T2_ID}` && u.search.includes('tab=quality'));
    });

    test('治理员 UI 导出（下载 + 提示）；分析师无导出按钮但有菜单', async ({page}) => {
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/governance/quality-report');
        const downloadPromise = page.waitForEvent('download');
        await page.getByRole('button', {name: '导出', exact: true}).click();
        const download = await downloadPromise;
        expect(download.suggestedFilename()).toMatch(/DataNest-质量报告-.*\.csv/);
        await expect(notice(page, '质量报告已导出')).toBeVisible();

        await gotoAs(page, TEST_USERS.analyst.username, TEST_USERS.analyst.password, '/governance/quality-report');
        await expect(page.getByRole('heading', {name: '质量报告'})).toBeVisible();
        await expect(page.getByRole('button', {name: '导出', exact: true})).toHaveCount(0);
        await expect(page.getByRole('button', {name: '质量报告'})).toBeVisible();
    });
});

// ==================== G. 存量评分历史补算 ====================

test.describe('G. 存量评分历史补算', () => {
    test('补算 T3/T4 首快照 + 幂等（二次 0 条）', async () => {
        // 前置：T3/T4 有当前评分、无历史快照（beforeAll 已清）
        expect(scalarGov(`SELECT COUNT(*) FROM quality_score_history WHERE table_id IN (${T3_ID}, ${T4_ID})`)).toBe('0');
        const added = await gov.post<number>('/governance/quality/report/backfill-score-history');
        expect(Number(added)).toBeGreaterThanOrEqual(2);
        // T3/T4 各补 1 条，评分与健康度与当前一致
        const t3 = scalarGov(`SELECT score || '|' || health_level FROM quality_score_history WHERE table_id = ${T3_ID}`);
        expect(t3).toBe('70.00|WARNING');
        const t4 = scalarGov(`SELECT score || '|' || health_level FROM quality_score_history WHERE table_id = ${T4_ID}`);
        expect(t4).toBe('20.00|BAD');
        // 幂等
        const again = await gov.post<number>('/governance/quality/report/backfill-score-history');
        expect(Number(again)).toBe(0);
    });
});
