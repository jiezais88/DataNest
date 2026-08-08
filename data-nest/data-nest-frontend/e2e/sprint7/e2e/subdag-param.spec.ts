import {expect, type Page, test} from '@playwright/test';
import {Api} from '../../sprint6/helpers/api';
import {gotoAs} from '../../sprint6/helpers/e2e';
import {seedAll} from '../helpers/seed';
import {ADMIN, TEST_USERS} from '../helpers/data';

/**
 * Sprint 7 F3 子 DAG 参数下发 E2E（UI 级，NG5）。
 *
 * 范围（2026-08-08 用户确认）：参数映射编辑器 UI 全链路——打开子 DAG 配置、
 * 主参数候选（主 DAG 声明参数 + 系统变量）、前端校验（必填/子参数唯一）、
 * 保存持久化（dag_node.config 断言）、重开回显、删除映射还原、7106 后端校验（API 辅助）。
 * 执行链路（触发父 DAG → 子执行 resolved_params 透传）已由后端 curl 自测覆盖，E2E 不重复。
 *
 * 夹具：beforeAll 经 API 创建（项目 + 子 DAG（ENABLED，声明 sub_date/sub_env 参数）+
 * 父 DAG（SQL→SUB_DAG 两节点 + 声明 main_env 参数）），afterAll 删除。
 */

let admin: Api;
let projectId: string;
let subDagId: string;
let parentDagId: string;

const SUB_DAG_NAME = 'e2e_s7_f3_sub';
const PARENT_DAG_NAME = 'e2e_s7_f3_parent';

/** antd 通知断言 */
function notice(page: Page, text: string | RegExp) {
    return page.locator('.ant-message-notice').filter({hasText: text}).first();
}

/** 可见的 Select 下拉（antd 关闭的 dropdown 残留在 DOM 隐藏态，选项点击必须限定可见实例） */
function visibleDropdown(page: Page) {
    return page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)').last();
}

/** 编辑器「保存 DAG」（含「未连线节点」确认框兼容） */
async function saveDag(page: Page) {
    await page.getByRole('button', {name: '保存', exact: true}).first().click();
    await page.waitForTimeout(500);
    const confirmBtn = page.getByRole('button', {name: '继续保存'});
    if (await confirmBtn.count() > 0) await confirmBtn.click();
    await expect(notice(page, /DAG 已更新|DAG 已创建|保存成功/)).toBeVisible({timeout: 15000});
}

/** 双击画布中的 SUB_DAG 节点打开配置弹窗 */
async function openSubDagModal(page: Page) {
    await page.locator('.react-flow__node', {hasText: '（异步）'}).first().dblclick();
    const dialog = page.getByRole('dialog', {name: '子 DAG 配置'});
    await expect(dialog).toBeVisible({timeout: 10000});
    return dialog;
}

/** 读父 DAG p2 节点 config（API 辅助断言） */
async function readP2Config(): Promise<string> {
    const dag = await admin.get(`/engineering/dev/dags/${parentDagId}`);
    const p2 = (dag.nodes || []).find((n: { nodeId: string }) => n.nodeId === 'p2');
    return p2?.config ?? '';
}

test.describe.configure({mode: 'serial'});

test.beforeAll(async () => {
    await seedAll();
    admin = await Api.create();
    await admin.login(ADMIN.username, ADMIN.password);

    // 项目
    const project = await admin.post('/engineering/dev/dag-projects', {name: 'e2e_s7_f3_project'});
    projectId = String(project.id);

    // 子 DAG（ENABLED 才会出现在候选列表）+ 声明 sub_date/sub_env 参数
    const sub = await admin.post('/engineering/dev/dags', {
        projectId, name: SUB_DAG_NAME, triggerType: 'MANUAL', scheduleEnabled: false,
        maxParallelism: 1, status: 'ENABLED',
        nodes: [{nodeId: 's1', nodeName: '子SQL', nodeType: 'SQL', positionX: 0, positionY: 0,
            config: '{"type":"SQL","sqlContent":"select 1"}'}],
        edges: [],
    });
    subDagId = String(sub.id);
    await admin.post(`/engineering/dev/dags/${subDagId}/parameters`,
        {paramName: 'sub_date', paramType: 'STRING', required: false});
    await admin.post(`/engineering/dev/dags/${subDagId}/parameters`,
        {paramName: 'sub_env', paramType: 'STRING', required: false});

    // 父 DAG：SQL → SUB_DAG（异步）+ 声明 main_env 参数
    const parent = await admin.post('/engineering/dev/dags', {
        projectId, name: PARENT_DAG_NAME, triggerType: 'MANUAL', scheduleEnabled: false,
        maxParallelism: 1, status: 'ENABLED',
        nodes: [
            {nodeId: 'p1', nodeName: '前置SQL', nodeType: 'SQL', positionX: 0, positionY: 0,
                config: '{"type":"SQL","sqlContent":"select 1"}'},
            {nodeId: 'p2', nodeName: '异步子DAG', nodeType: 'SUB_DAG', positionX: 240, positionY: 0,
                config: `{"type":"SUB_DAG","subDagId":"${subDagId}","subDagName":"${SUB_DAG_NAME}","syncExecution":false}`},
        ],
        edges: [{edgeId: 'e1', sourceNodeId: 'p1', targetNodeId: 'p2'}],
    });
    parentDagId = String(parent.id);
    await admin.post(`/engineering/dev/dags/${parentDagId}/parameters`,
        {paramName: 'main_env', paramType: 'STRING', defaultValue: 'prod', required: false});
});

test.afterAll(async () => {
    try {
        if (parentDagId) await admin.del(`/engineering/dev/dags/${parentDagId}`);
        if (subDagId) await admin.del(`/engineering/dev/dags/${subDagId}`);
        if (projectId) await admin.del(`/engineering/dev/dag-projects/${projectId}`);
    } catch (e) {
        console.warn('F3 夹具清理失败:', e);
    }
    await admin?.dispose();
});

test.describe('子 DAG 参数下发编辑器（UI 级）', () => {
    test('参数下发区：添加映射 + 主参数候选含声明参数与系统变量', async ({page}) => {
        await gotoAs(page, TEST_USERS.engineer.username, TEST_USERS.engineer.password,
            `/engineering/dags/${parentDagId}/edit`);
        await expect(page.locator('.react-flow__node').first()).toBeVisible({timeout: 10000});
        const dialog = await openSubDagModal(page);

        // 参数下发区 + 添加映射
        await expect(dialog.getByText('参数下发（可选）')).toBeVisible();
        await dialog.getByRole('button', {name: '添加映射'}).click();

        // 主参数下拉候选：main_env（声明）+ biz_date/current_time/dag_id（系统变量）
        await dialog.locator('[aria-label="映射 1 主参数"]').click();
        await expect(visibleDropdown(page).locator('.ant-select-item', {hasText: 'main_env'})).toBeVisible();
        await expect(visibleDropdown(page).locator('.ant-select-item', {hasText: 'biz_date'})).toBeVisible();
        await expect(visibleDropdown(page).locator('.ant-select-item', {hasText: 'current_time'})).toBeVisible();
        await expect(visibleDropdown(page).locator('.ant-select-item', {hasText: 'dag_id'})).toBeVisible();
        // 选中 biz_date 关闭下拉（Escape 会连带关弹窗，勿用）
        await visibleDropdown(page).locator('.ant-select-item', {hasText: 'biz_date'}).first().click();
        await dialog.getByLabel('关闭').click();
    });

    test('前端校验：子参数必填 + 映射内唯一', async ({page}) => {
        await gotoAs(page, TEST_USERS.engineer.username, TEST_USERS.engineer.password,
            `/engineering/dags/${parentDagId}/edit`);
        await expect(page.locator('.react-flow__node').first()).toBeVisible({timeout: 10000});
        const dialog = await openSubDagModal(page);

        // 主参数选了、子参数空 → 拦截
        await dialog.getByRole('button', {name: '添加映射'}).click();
        await dialog.locator('[aria-label="映射 1 主参数"]').click();
        await visibleDropdown(page).locator('.ant-select-item', {hasText: 'biz_date'}).first().click();
        await dialog.getByRole('button', {name: '保存'}).click();
        await expect(dialog.getByText('存在未填写子 DAG 参数的映射行')).toBeVisible();

        // 两行同子参数 → 唯一性拦截
        await dialog.locator('input[aria-label="映射 1 子参数"]').fill('sub_date');
        await dialog.getByRole('button', {name: '添加映射'}).click();
        await dialog.locator('[aria-label="映射 2 主参数"]').click();
        await page.waitForTimeout(400);
        await visibleDropdown(page).locator('.ant-select-item', {hasText: 'main_env'}).first().click({force: true});
        await dialog.locator('input[aria-label="映射 2 子参数"]').fill('sub_date');
        await dialog.getByRole('button', {name: '保存'}).click();
        await expect(dialog.getByText('在映射中重复')).toBeVisible();
        await dialog.getByLabel('关闭').click();
    });

    test('配置映射 → 保存持久化 → 重开回显 → 删除还原', async ({page}) => {
        await gotoAs(page, TEST_USERS.engineer.username, TEST_USERS.engineer.password,
            `/engineering/dags/${parentDagId}/edit`);
        await expect(page.locator('.react-flow__node').first()).toBeVisible({timeout: 10000});
        const dialog = await openSubDagModal(page);

        // 映射 1：biz_date → sub_date
        await dialog.getByRole('button', {name: '添加映射'}).click();
        await dialog.locator('[aria-label="映射 1 主参数"]').click();
        await visibleDropdown(page).locator('.ant-select-item', {hasText: 'biz_date'}).first().click();
        await dialog.locator('input[aria-label="映射 1 子参数"]').fill('sub_date');

        // 映射 2：main_env → sub_env（子参数下拉应含子 DAG 声明参数 sub_env）
        await dialog.getByRole('button', {name: '添加映射'}).click();
        await dialog.locator('[aria-label="映射 2 主参数"]').click();
        await page.waitForTimeout(400);
        await visibleDropdown(page).locator('.ant-select-item', {hasText: 'main_env'}).first().click({force: true});
        await dialog.locator('input[aria-label="映射 2 子参数"]').click();
        await expect(visibleDropdown(page).locator('.ant-select-item-option', {hasText: 'sub_env'})
            .or(visibleDropdown(page).locator('[title="sub_env"]')).first()).toBeVisible();
        await dialog.locator('input[aria-label="映射 2 子参数"]').fill('sub_env');

        await dialog.getByRole('button', {name: '保存'}).click();
        await expect(notice(page, '子 DAG 节点已更新')).toBeVisible();
        await saveDag(page);

        // API 断言：config 含两条映射
        const cfg = await readP2Config();
        expect(cfg).toContain('paramMappings');
        expect(cfg).toContain('biz_date');
        expect(cfg).toContain('sub_date');
        expect(cfg).toContain('main_env');
        expect(cfg).toContain('sub_env');

        // 重开回显
        const dialog2 = await openSubDagModal(page);
        await expect(dialog2.getByText('biz_date', {exact: true})).toBeVisible();
        await expect(dialog2.locator('input[aria-label="映射 1 子参数"]')).toHaveValue('sub_date');
        await expect(dialog2.getByText('main_env', {exact: true})).toBeVisible();
        await expect(dialog2.locator('input[aria-label="映射 2 子参数"]')).toHaveValue('sub_env');

        // 删除映射 2 → 保存 → 只剩一条
        await dialog2.getByLabel('删除映射 2').click();
        await dialog2.getByRole('button', {name: '保存'}).click();
        await saveDag(page);
        const cfg2 = await readP2Config();
        expect(cfg2).toContain('sub_date');
        expect(cfg2).not.toContain('sub_env');

        // 删除映射 1 → 保存 → config 无 paramMappings（还原）
        const dialog3 = await openSubDagModal(page);
        await dialog3.getByLabel('删除映射 1').click();
        await dialog3.getByRole('button', {name: '保存'}).click();
        await saveDag(page);
        const cfg3 = await readP2Config();
        expect(cfg3).not.toContain('paramMappings');
    });

    test('API 辅助：非法映射被后端 7106 拦截', async () => {
        const dag = await admin.get(`/engineering/dev/dags/${parentDagId}`);
        const badNodes = (dag.nodes || []).map((n: { nodeId: string; config: string }) =>
            n.nodeId === 'p2'
                ? {
                    ...n,
                    config: JSON.stringify({
                        type: 'SUB_DAG', subDagId, subDagName: SUB_DAG_NAME, syncExecution: false,
                        paramMappings: [{mainParam: 'not_declared_param', subParam: 'sub_date'}],
                    }),
                }
                : n);
        const env = await admin.raw('PUT', `/engineering/dev/dags/${parentDagId}`, {...dag, nodes: badNodes});
        expect(env.code).toBe(7106);
    });
});
