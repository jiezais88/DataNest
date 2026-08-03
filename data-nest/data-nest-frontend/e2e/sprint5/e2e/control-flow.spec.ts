import {expect, type Page, test} from '@playwright/test';
import {ADMIN} from '../helpers/data';
import {gotoAs} from '../helpers/e2e';
import {getProjectId} from '../helpers/dag';

let projectId: string;

/** 模拟从节点面板拖放一个节点到画布（ReactFlow HTML5 drag & drop） */
async function dropNode(page: Page, type: string, sourceTitle: string, x: number, y: number): Promise<void> {
    const dt = await page.evaluateHandle((t) => {
        const d = new DataTransfer();
        d.setData('application/reactflow', t);
        return d;
    }, type);
    const source = page.locator(`[title="${sourceTitle}"]`);
    await source.dispatchEvent('dragstart', {dataTransfer: dt});
    await page.locator('[data-testid="dag-canvas"]').dispatchEvent('drop', {dataTransfer: dt, clientX: x, clientY: y});
}

async function openEditor(page: Page): Promise<void> {
    await gotoAs(page, ADMIN.username, ADMIN.password, `/engineering/dags/new?projectId=${projectId}`);
    await expect(page.locator('[data-testid="dag-canvas"]')).toBeVisible({timeout: 15000});
    await expect(page.getByText('条件分支', {exact: true})).toBeVisible();
}

test.describe.configure({mode: 'serial'});

test.describe('DAG 控制流编辑器 E2E', () => {
    test.beforeAll(async () => {
        projectId = getProjectId('e2e_s5_project')!;
    });

    test('AC-15 节点面板含「条件分支」与「子 DAG」', async ({page}) => {
        await openEditor(page);
        await expect(page.getByText('条件分支', {exact: true})).toBeVisible();
        await expect(page.getByText('子 DAG', {exact: true})).toBeVisible();
    });

    test('AC-15 拖入条件分支节点：画布出现节点，默认 2 个分支', async ({page}) => {
        await openEditor(page);
        await dropNode(page, 'CONDITION', '按表达式选择下游分支', 500, 300);
        const condNode = page.locator('.react-flow__node').filter({hasText: '条件分支'});
        await expect(condNode).toHaveCount(1, {timeout: 10000});
        // 默认 2 分支提示
        await expect(condNode.getByText(/分支：2 个/)).toBeVisible();
    });

    test('AC-15 双击条件分支节点打开配置弹窗', async ({page}) => {
        await openEditor(page);
        await dropNode(page, 'CONDITION', '按表达式选择下游分支', 500, 300);
        const condNode = page.locator('.react-flow__node').filter({hasText: '条件分支'});
        await condNode.dblclick();
        const dialog = page.getByRole('dialog');
        await expect(dialog.getByText('条件分支配置')).toBeVisible({timeout: 10000});
        // 默认分支锁定 true
        await expect(dialog.locator('input[value="true"]')).toBeVisible();
        // 关闭
        await dialog.getByRole('button', {name: '取消'}).click();
    });

    test('AC-15 保存校验：条件分支配置不完整被前端拦截', async ({page}) => {
        await openEditor(page);
        await page.getByPlaceholder('DAG 名称').fill(`e2e_s5_e2e_cond_incomplete_${Date.now()}`);
        await dropNode(page, 'CONDITION', '按表达式选择下游分支', 500, 300);
        // 默认第二个分支为空 → 保存应被拦截
        await page.getByRole('button', {name: '保存'}).click();
        await expect(page.getByText(/存在未完整配置的分支/)).toBeVisible({timeout: 10000});
    });

    test('AC-17 拖入子 DAG 节点：画布出现节点', async ({page}) => {
        await openEditor(page);
        await page.getByPlaceholder('DAG 名称').fill(`e2e_s5_e2e_subdag_${Date.now()}`);
        await dropNode(page, 'SUB_DAG', '引用其他 DAG 作为节点', 500, 300);
        const subNode = page.locator('.react-flow__node').filter({hasText: '子 DAG'});
        await expect(subNode).toHaveCount(1, {timeout: 10000});
        // 双击打开子 DAG 配置弹窗
        await subNode.dblclick();
        const dialog = page.getByRole('dialog');
        await expect(dialog.getByText('子 DAG 配置')).toBeVisible({timeout: 10000});
        await dialog.getByRole('button', {name: '取消'}).click();
    });

    test('AC-17 子 DAG 节点未选择子 DAG 时保存被拦截', async ({page}) => {
        await openEditor(page);
        await page.getByPlaceholder('DAG 名称').fill(`e2e_s5_e2e_subdag_nosel_${Date.now()}`);
        await dropNode(page, 'SUB_DAG', '引用其他 DAG 作为节点', 500, 300);
        await page.getByRole('button', {name: '保存'}).click();
        await expect(page.getByText(/未选择子 DAG/)).toBeVisible({timeout: 10000});
    });
});
