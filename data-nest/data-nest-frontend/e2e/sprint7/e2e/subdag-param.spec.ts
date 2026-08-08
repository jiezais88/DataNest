import {expect, type Page, test} from '@playwright/test';
import {Api} from '../../sprint6/helpers/api';
import {gotoAs} from '../../sprint6/helpers/e2e';
import {waitFor} from '../../sprint6/helpers/poll';
import {scalar} from '../../sprint5/helpers/db';
import {waitDagDsSynced} from '../../sprint5/helpers/dag';
import {seedAll} from '../helpers/seed';
import {ADMIN, TEST_USERS} from '../helpers/data';

/**
 * Sprint 7 F3 子 DAG 参数下发 E2E（NG5）。
 *
 * 范围：① 参数映射编辑器 UI 全链路——打开子 DAG 配置、主参数候选（主 DAG 声明参数 + 系统变量）、
 * 前端校验（必填/子参数唯一）、保存持久化（dag_node.config 断言）、重开回显、删除映射还原、
 * 7106 后端校验（API 辅助）；② **执行链路（2026-08-08 用户确认纳入）**——真实触发父 DAG，
 * 断言子 DAG 执行 resolved_params 收到透传参数（同步/异步双链路 + 主参数无值跳过 + 无映射回归）。
 *
 * 夹具：beforeAll 经 API 创建（项目 + UI 夹具父子 DAG + 执行夹具：子 DAG（sub_date 无默认值/
 * sub_env 默认 sub_default_env）+ 异步父/同步父（biz_date→sub_date、main_env→sub_env 映射）+
 * 无值父（main_novalue→sub_env 映射、主参数无默认值）），afterAll 删除。
 */

let admin: Api;
let projectId: string;
let subDagId: string;
let parentDagId: string;
/** 执行链路夹具 */
let execSubDagId: string;
let parentAsyncExecId: string;
let parentSyncExecId: string;
let parentNovalueId: string;

const SUB_DAG_NAME = 'e2e_s7_f3_sub';
const PARENT_DAG_NAME = 'e2e_s7_f3_parent';
const EXEC_SUB_DAG_NAME = 'e2e_s7_f3_exec_sub';
const PARENT_ASYNC_EXEC_NAME = 'e2e_s7_f3_parent_async';
const PARENT_SYNC_EXEC_NAME = 'e2e_s7_f3_parent_sync';
const PARENT_NOVALUE_NAME = 'e2e_s7_f3_parent_novalue';

const TERMINAL_STATUSES = ['SUCCESS', 'FAILED', 'TERMINATED'];

interface ExecutionDto {
    id: string;
    dagId: string;
    status: string;
}

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

// ---------- 执行链路辅助 ----------

/** 指定 DAG 最新一条执行记录（/dag-executions 分页接口） */
async function latestExecution(dagId: string): Promise<ExecutionDto | null> {
    const page = await admin.get(`/engineering/dag-executions?dagId=${dagId}&page=1&pageSize=1`);
    const records = (page?.records ?? []) as ExecutionDto[];
    return records.length > 0 ? records[0] : null;
}

/** 触发 DAG 并轮询至终态，返回执行 DTO */
async function runDagAndWait(dagId: string): Promise<ExecutionDto> {
    const dto = await admin.post(`/engineering/dev/dags/${dagId}/trigger`);
    const executionId = String(dto.id);
    expect(executionId, 'trigger 应返回 executionId').toBeTruthy();
    return waitFor(
        async () => {
            const list = await admin.get(`/engineering/dev/dags/${dagId}/executions`) as ExecutionDto[];
            const found = list.find((e) => String(e.id) === executionId);
            if (!found) throw new Error(`执行记录不存在: executionId=${executionId} dagId=${dagId}`);
            return found;
        },
        (e) => TERMINAL_STATUSES.includes(e.status),
        {timeoutMs: 180_000, intervalMs: 3000, label: `DAG ${dagId} 执行 ${executionId} 进入终态`},
    );
}

/** 等待子 DAG 产生「快照之后」的新执行并进入终态 */
async function waitNewSubExecution(dagId: string, beforeId: string | null): Promise<ExecutionDto> {
    return waitFor(
        () => latestExecution(dagId),
        (e) => e != null && String(e.id) !== (beforeId ?? '') && TERMINAL_STATUSES.includes(e.status),
        {timeoutMs: 180_000, intervalMs: 3000, label: `子 DAG ${dagId} 新执行进入终态`},
    );
}

/** 读执行的 resolved_params（DB 辅助断言，dag_execution 在 datanest_engineering） */
function resolvedParams(executionId: string): Record<string, string | null> {
    const raw = scalar(`SELECT resolved_params FROM dag_execution WHERE id=${executionId}`);
    return raw ? JSON.parse(raw) : {};
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

    // ---------- 执行链路夹具 ----------
    // 子 DAG：sub_date 无默认值、sub_env 默认 sub_default_env（验证「未覆盖时用子 DAG 自身默认」）
    const execSub = await admin.post('/engineering/dev/dags', {
        projectId, name: EXEC_SUB_DAG_NAME, triggerType: 'MANUAL', scheduleEnabled: false,
        maxParallelism: 1, status: 'ENABLED',
        nodes: [{nodeId: 's1', nodeName: '子SQL', nodeType: 'SQL', positionX: 0, positionY: 0,
            config: '{"type":"SQL","sqlContent":"select 1"}'}],
        edges: [],
    });
    execSubDagId = String(execSub.id);
    await admin.post(`/engineering/dev/dags/${execSubDagId}/parameters`,
        {paramName: 'sub_date', paramType: 'STRING', required: false});
    await admin.post(`/engineering/dev/dags/${execSubDagId}/parameters`,
        {paramName: 'sub_env', paramType: 'STRING', defaultValue: 'sub_default_env', required: false});

    // 异步父：SQL → SUB_DAG(异步，biz_date→sub_date、main_env→sub_env)
    const parentAsync = await admin.post('/engineering/dev/dags', {
        projectId, name: PARENT_ASYNC_EXEC_NAME, triggerType: 'MANUAL', scheduleEnabled: false,
        maxParallelism: 1, status: 'ENABLED',
        nodes: [
            {nodeId: 'a1', nodeName: '前置SQL', nodeType: 'SQL', positionX: 0, positionY: 0,
                config: '{"type":"SQL","sqlContent":"select 1"}'},
            {nodeId: 'a2', nodeName: '异步子DAG', nodeType: 'SUB_DAG', positionX: 240, positionY: 0,
                config: `{"type":"SUB_DAG","subDagId":"${execSubDagId}","subDagName":"${EXEC_SUB_DAG_NAME}","syncExecution":false,"paramMappings":[{"mainParam":"biz_date","subParam":"sub_date"},{"mainParam":"main_env","subParam":"sub_env"}]}`},
        ],
        edges: [{edgeId: 'e1', sourceNodeId: 'a1', targetNodeId: 'a2'}],
    });
    parentAsyncExecId = String(parentAsync.id);
    await admin.post(`/engineering/dev/dags/${parentAsyncExecId}/parameters`,
        {paramName: 'main_env', paramType: 'STRING', defaultValue: 'prod_async', required: false});

    // 同步父：SQL → SUB_DAG(同步，同映射)
    const parentSync = await admin.post('/engineering/dev/dags', {
        projectId, name: PARENT_SYNC_EXEC_NAME, triggerType: 'MANUAL', scheduleEnabled: false,
        maxParallelism: 1, status: 'ENABLED',
        nodes: [
            {nodeId: 's1', nodeName: '前置SQL', nodeType: 'SQL', positionX: 0, positionY: 0,
                config: '{"type":"SQL","sqlContent":"select 1"}'},
            {nodeId: 's2', nodeName: '同步子DAG', nodeType: 'SUB_DAG', positionX: 240, positionY: 0,
                config: `{"type":"SUB_DAG","subDagId":"${execSubDagId}","subDagName":"${EXEC_SUB_DAG_NAME}","syncExecution":true,"paramMappings":[{"mainParam":"biz_date","subParam":"sub_date"},{"mainParam":"main_env","subParam":"sub_env"}]}`},
        ],
        edges: [{edgeId: 'e1', sourceNodeId: 's1', targetNodeId: 's2'}],
    });
    parentSyncExecId = String(parentSync.id);
    await admin.post(`/engineering/dev/dags/${parentSyncExecId}/parameters`,
        {paramName: 'main_env', paramType: 'STRING', defaultValue: 'prod_sync', required: false});

    // 无值父：main_novalue（声明但无默认值）→ sub_env，验证「主参数无值 warn 跳过不阻断」
    const parentNovalue = await admin.post('/engineering/dev/dags', {
        projectId, name: PARENT_NOVALUE_NAME, triggerType: 'MANUAL', scheduleEnabled: false,
        maxParallelism: 1, status: 'ENABLED',
        nodes: [
            {nodeId: 'n1', nodeName: '前置SQL', nodeType: 'SQL', positionX: 0, positionY: 0,
                config: '{"type":"SQL","sqlContent":"select 1"}'},
            {nodeId: 'n2', nodeName: '异步子DAG', nodeType: 'SUB_DAG', positionX: 240, positionY: 0,
                config: `{"type":"SUB_DAG","subDagId":"${execSubDagId}","subDagName":"${EXEC_SUB_DAG_NAME}","syncExecution":false,"paramMappings":[{"mainParam":"main_novalue","subParam":"sub_env"}]}`},
        ],
        edges: [{edgeId: 'e1', sourceNodeId: 'n1', targetNodeId: 'n2'}],
    });
    parentNovalueId = String(parentNovalue.id);
    await admin.post(`/engineering/dev/dags/${parentNovalueId}/parameters`,
        {paramName: 'main_novalue', paramType: 'STRING', required: false});

    // 全部 DAG 等待调度注册（PowerJob workflow 同步）后再触发
    for (const dagId of [subDagId, parentDagId, execSubDagId, parentAsyncExecId, parentSyncExecId, parentNovalueId]) {
        await waitDagDsSynced(admin, dagId);
    }
});

test.afterAll(async () => {
    try {
        for (const id of [parentDagId, subDagId, parentAsyncExecId, parentSyncExecId, parentNovalueId, execSubDagId]) {
            if (id) await admin.del(`/engineering/dev/dags/${id}`);
        }
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

// ==================== 执行链路（真实触发，同步/异步双链路 + 边界） ====================

test.describe('执行链路：参数透传到子 DAG 执行上下文', () => {
    test('异步链路：映射参数（biz_date→sub_date、main_env→sub_env）落入子执行 resolved_params', async () => {
        const before = await latestExecution(execSubDagId);

        const parentExec = await runDagAndWait(parentAsyncExecId);
        expect(parentExec.status, '父 DAG（异步子）应执行成功').toBe('SUCCESS');

        const subExec = await waitNewSubExecution(execSubDagId, before ? String(before.id) : null);
        expect(subExec.status, '子 DAG 新执行应成功').toBe('SUCCESS');

        const params = resolvedParams(String(subExec.id));
        // main_env（默认值 prod_async）→ sub_env
        expect(params.sub_env).toBe('prod_async');
        // biz_date（系统变量，父执行解析值）→ sub_date，与父执行 resolved_params.biz_date 一致（杜绝时区歧义）
        const parentParams = resolvedParams(String(parentExec.id));
        expect(parentParams.biz_date).toMatch(/^\d{4}-\d{2}-\d{2}$/);
        expect(params.sub_date).toBe(parentParams.biz_date);
        // 异步链路语义：子执行 trigger_type=MANUAL
        expect(scalar(`SELECT trigger_type FROM dag_execution WHERE id=${subExec.id}`)).toBe('MANUAL');
    });

    test('同步链路：映射参数落入子执行 resolved_params', async () => {
        const before = await latestExecution(execSubDagId);

        const parentExec = await runDagAndWait(parentSyncExecId);
        expect(parentExec.status, '父 DAG（同步子）应执行成功').toBe('SUCCESS');

        const subExec = await waitNewSubExecution(execSubDagId, before ? String(before.id) : null);
        expect(subExec.status, '子 DAG 新执行应成功').toBe('SUCCESS');

        const params = resolvedParams(String(subExec.id));
        expect(params.sub_env).toBe('prod_sync');
        const parentParams = resolvedParams(String(parentExec.id));
        expect(params.sub_date).toBe(parentParams.biz_date);
        // 同步链路语义：子执行 trigger_type=SCHEDULED（既有语义，见 handoff F3 变更明细）
        expect(scalar(`SELECT trigger_type FROM dag_execution WHERE id=${subExec.id}`)).toBe('SCHEDULED');
    });

    test('边界：主参数无值 warn 跳过不阻断，子参数用子 DAG 自身默认值', async () => {
        const before = await latestExecution(execSubDagId);

        // main_novalue 声明但无默认值、触发未传值 → 映射被跳过，父/子仍成功
        const parentExec = await runDagAndWait(parentNovalueId);
        expect(parentExec.status, '主参数无值不应阻断父 DAG').toBe('SUCCESS');

        const subExec = await waitNewSubExecution(execSubDagId, before ? String(before.id) : null);
        expect(subExec.status, '子 DAG 新执行应成功').toBe('SUCCESS');

        const params = resolvedParams(String(subExec.id));
        // sub_env 未被覆盖 → 子 DAG 自身默认值
        expect(params.sub_env).toBe('sub_default_env');
    });

    test('回归：无 paramMappings 时子 DAG 按原语义执行（只带系统变量/默认值）', async () => {
        // UI 夹具父 DAG 的映射已在编辑器用例中删除还原，此处直接复用做无映射回归
        const cfg = await readP2Config();
        expect(cfg, '前置：UI 用例结束后父节点应无 paramMappings').not.toContain('paramMappings');
        const before = await latestExecution(subDagId);

        const parentExec = await runDagAndWait(parentDagId);
        expect(parentExec.status).toBe('SUCCESS');

        const subExec = await waitNewSubExecution(subDagId, before ? String(before.id) : null);
        expect(subExec.status).toBe('SUCCESS');

        const params = resolvedParams(String(subExec.id));
        // 无任何映射值透传（UI 夹具子 DAG 的 sub_date/sub_env 均无默认值 → 为空或不出现）
        expect(params.sub_date ?? null).toBeNull();
        expect(params.sub_env ?? null).toBeNull();
    });
});
