import {expect, type Page, test} from '@playwright/test';
import {Api} from '../helpers/api';
import {ADMIN} from '../helpers/data';
import {psql, scalar} from '../helpers/db';
import {gotoAs} from '../helpers/e2e';
import {getProjectId, waitDagDsSynced} from '../helpers/dag';
import {createDag} from '../helpers/seed';

let admin: Api;
let engineerUserId: string;
let ruleDagId: string;
let ruleDagName: string;

/** antd Select 选值：index 为弹窗内第几个 .ant-select；通过键盘输入触发 showSearch 过滤 */
async function selectAntd(page: Page, modal: any, selectIndex: number, optionText: string): Promise<void> {
    await modal.locator('.ant-select').nth(selectIndex).click();
    await page.locator('.ant-select-dropdown:visible').first().waitFor({state: 'visible'});
    await page.keyboard.type(optionText, {delay: 40});
    const option = page.locator('.ant-select-dropdown:visible .ant-select-item-option')
        .filter({hasText: optionText}).first();
    await option.waitFor({state: 'visible', timeout: 10000});
    await option.click();
}

async function createRuleForDag(triggers: string[], enabled = true): Promise<any> {
    return admin.post('/system/alert-rules', {
        objectType: 'DAG', objectId: ruleDagId,
        triggerConditions: triggers, enabled, timeoutMinutes: 30,
        userIds: [engineerUserId],
    });
}

test.describe.configure({mode: 'serial'});

test.describe('告警中心 E2E', () => {
    test.beforeAll(async () => {
        admin = await Api.create();
        await admin.login(ADMIN.username, ADMIN.password);
        engineerUserId = scalar(`SELECT id FROM sys_user WHERE username='s5_engineer'`)!;
        // 建一个真实 DAG 作为规则对象（保证 objectName 有值，列表可筛选）
        const projectId = getProjectId('e2e_s5_project')!;
        const dag = await createDag(
            admin, projectId, `e2e_s5_e2e_rule_dag_${Date.now()}`,
            [{
                nodeId: 'n1',
                nodeName: '节点',
                nodeType: 'SQL',
                positionX: 0,
                positionY: 0,
                config: {type: 'SQL', sqlContent: 'SELECT 1'}
            }],
            [],
        );
        ruleDagId = String(dag.id);
        ruleDagName = dag.name;
        await waitDagDsSynced(admin, ruleDagId);
    });

    test.afterAll(async () => {
        await admin.del(`/engineering/dev/dags/${ruleDagId}`);
        await admin.dispose();
    });

    test('AC-8 告警中心菜单：超管可见', async ({page}) => {
        await gotoAs(page, ADMIN.username, ADMIN.password, '/');
        const menu = page.getByText('告警中心', {exact: true});
        await expect(menu).toBeVisible();
        await menu.click();
        await expect(page.getByRole('heading', {name: '告警中心'})).toBeVisible({timeout: 15000});
    });

    test('AC-20 权限：分析师无告警中心菜单', async ({page}) => {
        await gotoAs(page, 's5_analyst', 'Test123456', '/');
        await expect(page.getByText('告警中心', {exact: true})).toHaveCount(0);
    });

    test('AC-9 规则列表展示', async ({page}) => {
        const rule = await createRuleForDag(['FAILURE', 'SUCCESS']);
        await gotoAs(page, ADMIN.username, ADMIN.password, '/system/alert-center');
        await expect(page.getByRole('heading', {name: '告警中心'})).toBeVisible();
        const row = page.locator('.ant-table-row').filter({hasText: ruleDagName});
        await expect(row).toBeVisible({timeout: 15000});
        await expect(row).toContainText('DAG');
        await expect(row).toContainText('失败');
        await expect(row).toContainText('成功');
        await expect(row).toContainText('启用');
        await expect(row).toContainText('s5_engineer');
        await admin.del(`/system/alert-rules/${rule.id}`);
    });

    test('AC-10 新增告警规则弹窗：填写并保存', async ({page}) => {
        await gotoAs(page, ADMIN.username, ADMIN.password, '/system/alert-center');
        await page.getByRole('button', {name: /新增告警规则/}).click();
        const modal = page.getByRole('dialog');
        await expect(modal.getByText('新增告警规则').first()).toBeVisible();

        // 对象类型选 DAG（默认）→ 对象选 ruleDag
        await selectAntd(page, modal, 0, 'DAG');
        await selectAntd(page, modal, 1, ruleDagName);
        // 触发条件：失败 + 成功
        await modal.getByText('失败', {exact: true}).click();
        await modal.getByText('成功', {exact: true}).click();
        // 接收用户：s5_engineer
        await modal.locator('.ant-select').nth(2).click();
        const dropdown = page.locator('.ant-select-dropdown:visible');
        await dropdown.getByText('s5_engineer', {exact: false}).first().click();
        // 保存
        await modal.getByRole('button', {name: '保存'}).click();
        await expect(page.getByText('告警规则已保存')).toBeVisible({timeout: 10000});
        await expect(page.locator('.ant-table-row').filter({hasText: ruleDagName})).toBeVisible({timeout: 15000});
        // 清理：删除本测试创建的规则
        const ruleId = scalar(`SELECT id FROM alert_rule WHERE object_type='DAG' AND object_id=${ruleDagId}`);
        if (ruleId) await admin.del(`/system/alert-rules/${ruleId}`);
    });

    test('AC-11 编辑规则：修改触发条件', async ({page}) => {
        const rule = await createRuleForDag(['FAILURE']);
        await gotoAs(page, ADMIN.username, ADMIN.password, '/system/alert-center');
        const row = page.locator('.ant-table-row').filter({hasText: ruleDagName});
        await expect(row).toBeVisible({timeout: 15000});
        await row.getByLabel('编辑').click();
        const modal = page.getByRole('dialog');
        await expect(modal.getByText('编辑告警规则')).toBeVisible();
        await modal.getByText('成功', {exact: true}).click();
        await modal.getByRole('button', {name: '保存'}).click();
        await expect(page.getByText('告警规则已保存')).toBeVisible({timeout: 10000});
        await expect(row.getByText('成功')).toBeVisible();
        await admin.del(`/system/alert-rules/${rule.id}`);
    });

    test('AC-11 启用/停用规则', async ({page}) => {
        const rule = await createRuleForDag(['FAILURE']);
        await gotoAs(page, ADMIN.username, ADMIN.password, '/system/alert-center');
        const row = page.locator('.ant-table-row').filter({hasText: ruleDagName});
        await expect(row).toBeVisible({timeout: 15000});
        await expect(row.getByText('启用')).toBeVisible();
        await row.getByLabel('停用').click();
        await expect(row.getByText('停用')).toBeVisible({timeout: 10000});
        await admin.del(`/system/alert-rules/${rule.id}`);
    });

    test('AC-11 删除规则', async ({page}) => {
        const rule = await createRuleForDag(['FAILURE']);
        await gotoAs(page, ADMIN.username, ADMIN.password, '/system/alert-center');
        const row = page.locator('.ant-table-row').filter({hasText: ruleDagName});
        await expect(row).toBeVisible({timeout: 15000});
        await row.getByLabel('删除').click();
        await page.getByRole('dialog').getByRole('button', {name: /删除|确定/}).click();
        await expect(row).toHaveCount(0, {timeout: 10000});
    });

    test('AC-9 告警历史 Tab：列表与发送状态', async ({page}) => {
        // 清空历史（本环境 alert_history 均为测试数据），保证待验证记录唯一且在第一页
        psql(`DELETE FROM alert_history`);
        // 直接写一条历史记录
        psql(
            `INSERT INTO alert_history (id, alert_rule_id, object_type, object_id, alert_type, recipients, send_status, sent_at)
             VALUES (6400000000000000021, NULL, 'SYNC_JOB', 6200000000000000022, 'FAILURE', 's5.engineer@test.io', 'SUCCESS', NOW())`,
        );
        await gotoAs(page, ADMIN.username, ADMIN.password, '/system/alert-center');
        await page.getByRole('button', {name: '告警历史'}).click();
        // 定位含该接收邮箱的历史行
        const row = page.getByRole('row', {name: /s5\.engineer@test\.io/}).first();
        await expect(row).toBeVisible({timeout: 15000});
        // 发送状态徽章
        await expect(row.getByText('发送成功')).toBeVisible();
        // 告警类型失败
        await expect(row.getByText('失败')).toBeVisible();
        psql(`DELETE FROM alert_history WHERE id=6400000000000000021`);
    });

    test('AC-14 同步任务「告警配置」快捷入口', async ({page}) => {
        const syncName = scalar(`SELECT name FROM sync_job WHERE name LIKE 'e2e_s5_sync_fail%' LIMIT 1`)!;
        await gotoAs(page, ADMIN.username, ADMIN.password, '/engineering/sync-jobs');
        const btn = page.locator(`[data-testid="sync-job-alert-${syncName}"]`);
        await expect(btn).toBeVisible({timeout: 15000});
        await btn.click();
        const modal = page.getByRole('dialog');
        await expect(modal.getByText(/告警配置/).first()).toBeVisible();
        await expect(modal.getByText('同步任务', {exact: true}).first()).toBeVisible();
        await modal.getByRole('button', {name: '取消'}).click();
    });

    test('AC-14 采集任务「告警配置」快捷入口', async ({page}) => {
        const collectName = scalar(`SELECT name FROM collect_task WHERE name LIKE 'e2e_s5_collect_fail%' LIMIT 1`)!;
        await gotoAs(page, ADMIN.username, ADMIN.password, '/governance/collect-tasks');
        const btn = page.locator(`[data-testid="collect-task-alert-${collectName}"]`);
        await expect(btn).toBeVisible({timeout: 15000});
        await btn.click();
        const modal = page.getByRole('dialog');
        await expect(modal.getByText(/告警配置/).first()).toBeVisible();
        await modal.getByRole('button', {name: '取消'}).click();
    });

    test('AC-20 权限：治理员可查看但不可编辑', async ({page}) => {
        const rule = await createRuleForDag(['FAILURE']);
        await gotoAs(page, 's5_govadmin', 'Test123456', '/system/alert-center');
        const row = page.locator('.ant-table-row').filter({hasText: ruleDagName});
        await expect(row).toBeVisible({timeout: 15000});
        await expect(row.getByLabel('编辑')).toBeDisabled();
        await expect(row.getByLabel('删除')).toBeDisabled();
        await expect(page.getByRole('button', {name: /新增告警规则/})).toHaveCount(0);
        await admin.del(`/system/alert-rules/${rule.id}`);
    });
});
