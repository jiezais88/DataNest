import {expect, type Page, test} from '@playwright/test';
import {gotoAs} from '../../sprint6/helpers/e2e';
import {psqlGov} from '../helpers/db';
import {seedAll} from '../helpers/seed';
import {TEST_USERS} from '../helpers/data';

/**
 * Sprint 7 F4 Python 质量规则 E2E（DG-10，业务流程覆盖，DB 辅助断言）。
 *
 * 覆盖：模板库 PYTHON 模板（脚本编辑区/保存/落库 python_template/编辑回显/删除）；
 * 质量规则 PYTHON 类型（脚本编辑区/可选检查字段/「测试脚本」真实沙箱试跑/必填校验/
 * 保存落库 python_script/编辑回显/删除）；CUSTOM_SQL 强化（执行预览多指标列 + 点列回填结果指标）。
 *
 * 说明：test-script 需要真实数据源连接，使用环境存量 mysql 数据源的 testdb.orders
 * （e2e_s7_mysql_ds 种子数据源密码为假，连接必失败——后端 F4 自测同样改用真实数据源）。
 * 本 spec 产生的模板/规则以 e2e_s7_f4 前缀命名，afterAll 经 DB 兜底清理。
 */

const PY_TPL_NAME = 'e2e_s7_f4_py_tpl';
const PY_RULE_NAME = 'e2e_s7_f4_py_rule';
const SQL_RULE_NAME = 'e2e_s7_f4_sql_rule';

const PY_SCRIPT = `def check(df):
    if df.empty:
        return {'null_rate': 0.0}
    return {'null_rate': float(df['total_amount'].isnull().mean())}`;

/** 规则/模板行 */
function row(page: Page, name: string) {
    return page.locator('.ant-table-row').filter({hasText: name});
}

/** 当前打开的抽屉（role=dialog 含 h2 标题） */
function drawer(page: Page) {
    return page.locator('div[role="dialog"]').filter({has: page.locator('h2')});
}

/** 规则列表按名称搜索（全量 setup 下规则超 10 条/页，新规则可能不在首屏，搜索确定命中） */
async function searchRule(page: Page, name: string) {
    await page.getByPlaceholder('搜索规则名称...').fill(name);
    await page.getByRole('button', {name: /查询/}).click();
    await expect(row(page, name)).toBeVisible({timeout: 10000});
}

/** 规则表单选目标表（mysql / testdb / orders 级联） */
async function selectOrdersTable(d: ReturnType<typeof drawer>) {
    await d.locator('select').nth(1).selectOption({label: 'mysql'});
    await d.locator('select').nth(2).selectOption('testdb');
    await expect(d.locator('select').nth(3).locator('option', {hasText: 'orders'})).toHaveCount(1, {timeout: 10000});
    await d.locator('select').nth(3).selectOption({label: 'orders'});
}

test.describe.configure({mode: 'serial'});

test.beforeAll(async () => {
    await seedAll();
});

test.afterAll(() => {
    psqlGov(`DELETE FROM quality_rule WHERE name LIKE 'e2e_s7_f4%'`);
    psqlGov(`DELETE FROM quality_rule_template WHERE name LIKE 'e2e_s7_f4%'`);
});

test.describe('PYTHON 模板', () => {
    test('新增 PYTHON 模板：脚本编辑区 + 保存落库 + 编辑回显 + 删除', async ({page}) => {
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password,
            '/governance/quality-templates');
        await expect(page.getByRole('heading', {name: '规则模板库'})).toBeVisible();
        await page.getByRole('button', {name: '新增自定义模板'}).first().click();
        const d = drawer(page);
        await expect(d.getByRole('heading', {name: '新增自定义模板'})).toBeVisible();

        await d.locator('input').first().fill(PY_TPL_NAME);
        // 类型切 Python → 脚本编辑区出现、SQL 模板区隐藏
        await d.getByRole('button', {name: 'Python', exact: true}).click();
        const pyArea = d.locator('textarea[placeholder*="def check"]');
        await expect(pyArea).toBeVisible();
        await pyArea.fill(PY_SCRIPT);
        await d.locator('input[placeholder*="null_rate"]').fill('null_rate');
        await d.getByRole('button', {name: '保存'}).click();
        await expect(row(page, PY_TPL_NAME)).toBeVisible();

        // DB 断言：python_template 落库
        const tpl = psqlGov(`SELECT python_template FROM quality_rule_template WHERE name = '${PY_TPL_NAME}'`);
        expect(tpl).toContain('def check');

        // 编辑回显：脚本保留
        await row(page, PY_TPL_NAME).getByLabel('编辑').click();
        const d2 = drawer(page);
        await expect(d2.getByRole('heading', {name: '编辑模板'})).toBeVisible();
        await expect(d2.locator('textarea[placeholder*="def check"]')).toHaveValue(/def check\(df\)/);
        await d2.getByLabel('关闭').click();

        // 删除
        await row(page, PY_TPL_NAME).getByLabel('删除').click();
        await page.getByRole('dialog').getByRole('button', {name: '删除'}).click();
        await expect(row(page, PY_TPL_NAME)).toHaveCount(0);
    });
});

test.describe('PYTHON 规则', () => {
    test('新增 PYTHON 规则：脚本区 + 测试脚本真实试跑 + 保存落库', async ({page}) => {
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password,
            '/governance/quality-rules');
        await page.getByRole('button', {name: '新增规则'}).first().click();
        const d = drawer(page);
        await expect(d.getByRole('heading', {name: '新增质量规则'})).toBeVisible();

        await d.locator('input').first().fill(PY_RULE_NAME);
        await d.locator('select').nth(0).selectOption('PYTHON');
        // 模板选择对 PYTHON 隐藏
        await expect(d.getByText('规则模板', {exact: false})).toHaveCount(0);
        await selectOrdersTable(d);
        // 检查字段为可选（无必填星号语义，占位提示可选）
        await expect(d.getByText(/（可选，脚本内可通过 read_table 拉取该表数据）/)).toBeVisible();

        await d.locator('textarea[placeholder*="def check"]').fill(PY_SCRIPT);
        await d.locator('input[placeholder="如：null_rate"]').fill('null_rate');

        // 测试脚本：真实沙箱试跑（governance 本地），返回 dict
        await d.getByRole('button', {name: '测试脚本'}).click();
        await expect(d.getByText(/执行成功/)).toBeVisible({timeout: 60000});
        await expect(d.locator('pre')).toContainText('null_rate');

        await d.getByRole('button', {name: '保存'}).click();
        await searchRule(page, PY_RULE_NAME);

        // DB 断言：python_script 落库、类型 PYTHON
        const script = psqlGov(`SELECT python_script FROM quality_rule WHERE name = '${PY_RULE_NAME}'`);
        expect(script).toContain('def check');
        const type = psqlGov(`SELECT type FROM quality_rule WHERE name = '${PY_RULE_NAME}'`);
        expect(type).toBe('PYTHON');
    });

    test('PYTHON 必填校验：缺脚本 / 缺结果指标被前端拦截', async ({page}) => {
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password,
            '/governance/quality-rules');
        await page.getByRole('button', {name: '新增规则'}).first().click();
        const d = drawer(page);
        await d.locator('input').first().fill('e2e_s7_f4_invalid');
        await d.locator('select').nth(0).selectOption('PYTHON');
        await selectOrdersTable(d);

        await d.getByRole('button', {name: '保存'}).click();
        await expect(d.getByText('请输入 Python 校验脚本')).toBeVisible();

        await d.locator('textarea[placeholder*="def check"]').fill(PY_SCRIPT);
        await d.getByRole('button', {name: '保存'}).click();
        await expect(d.getByText(/请输入结果指标名/)).toBeVisible();
        await d.getByLabel('关闭').click();
    });

    test('PYTHON 规则编辑回显脚本保留 + 删除', async ({page}) => {
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password,
            '/governance/quality-rules');
        await searchRule(page, PY_RULE_NAME);
        await row(page, PY_RULE_NAME).getByLabel('编辑').click();
        const d = drawer(page);
        await expect(d.getByRole('heading', {name: '编辑质量规则'})).toBeVisible();
        await expect(d.locator('textarea[placeholder*="def check"]')).toHaveValue(/def check\(df\)/);
        await expect(d.locator('input[placeholder="如：null_rate"]')).toHaveValue('null_rate');
        await d.getByLabel('关闭').click();

        await row(page, PY_RULE_NAME).getByLabel('删除').click();
        await page.getByRole('dialog').getByRole('button', {name: '删除'}).click();
        await expect(row(page, PY_RULE_NAME)).toHaveCount(0);
    });
});

test.describe('CUSTOM_SQL 执行预览', () => {
    test('执行预览返回多指标列 + 点列名回填结果指标', async ({page}) => {
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password,
            '/governance/quality-rules');
        await page.getByRole('button', {name: '新增规则'}).first().click();
        const d = drawer(page);
        await d.locator('input').first().fill(SQL_RULE_NAME);
        await d.locator('select').nth(0).selectOption('CUSTOM_SQL');
        await selectOrdersTable(d);
        await d.locator('textarea').first().fill(
            'SELECT COUNT(*) AS total, SUM(CASE WHEN total_amount IS NULL THEN 1 ELSE 0 END) AS nulls FROM {table}');

        await d.getByRole('button', {name: '执行预览'}).click();
        await expect(d.getByText(/Query returned/)).toBeVisible({timeout: 30000});
        // 两个指标列 chips
        await expect(d.getByRole('button', {name: 'total', exact: true})).toBeVisible();
        await expect(d.getByRole('button', {name: 'nulls', exact: true})).toBeVisible();
        // 点 nulls 回填结果指标
        await d.getByRole('button', {name: 'nulls', exact: true}).click();
        await expect(d.locator('input[placeholder="如：null_rate"]')).toHaveValue('nulls');
        await d.getByLabel('关闭').click();
    });
});
