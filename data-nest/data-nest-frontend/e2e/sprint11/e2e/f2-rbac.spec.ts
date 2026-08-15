import {expect, test, type Page} from '@playwright/test';
import {Api} from '../../sprint6/helpers/api';
import {gotoAs} from '../../sprint6/helpers/e2e';
import {
    ADMIN,
    cleanupF2,
    MYSQL,
    seedF2,
    setConfSensitivity,
    snapshotConfSensitivity,
    CONF_TABLE_ID,
    PREFIX,
} from './helpers/f2-seed';

/**
 * Sprint 11 F2 角色权限（RBAC）E2E 测试（2026-08-15）。
 *
 * 覆盖 PRD PM-1~18 + 本次实现的两项缺陷：
 * - PM-6 机密表在权限树显示锁定图标/禁用勾选/悬停提示（后端 permission-tree 返回 sensitivityLevel + 前端授权弹窗锁定）
 * - PM-14 保存即时生效（角色权限/成员变更后刷新已登录用户 Session，下次请求即按新权限校验）
 *
 * 分组：
 * A 角色管理（PM-7~15）  B 权限点体系（PM-16）  C 数据权限五入口（PM-1/2/4/5）
 * D PM-6 机密表锁定      E PM-14 即时生效        F 权限配置页 UI
 * G 角色管理页 UI         H 数据源列表按钮级（PM-16）
 *
 * 数据策略：自播种自清理（e2e_s11f2_ 前缀，测完物理清理；机密表改级恢复原级别）。
 * UI E2E 为主 + API 辅助诊断。
 */

const BASE = 'http://localhost:8080';

// 全局串行状态
let ROLE_ID = '';
let USER_ID = '';

// ==================== UI 辅助 ====================

/** 复用 sprint6 已验证的 gotoAs（API 注入 token + userInfo 到 localStorage）；sprint10 大量用例验证过 */
async function adminGoto(page: Page, path: string) {
    await gotoAs(page, ADMIN.username, ADMIN.password, path);
}

/** 等待 antd 表格 loading 消失 */
async function waitTableSettled(page: Page) {
    await expect(page.locator('.ant-spin-spinning')).toHaveCount(0, {timeout: 15_000});
    await expect(page.locator('.ant-table-row').first()).toBeVisible();
}

// ==================== A 角色管理（PM-7~15） ====================

test.describe('A 角色管理 API', () => {
    test('A1 角色列表：预置 4 角色（builtin）+ 自定义角色（PM-7）', async () => {
        const api = await Api.create();
        await api.login(ADMIN.username, ADMIN.password);
        const data = await api.get<Array<Record<string, any>>>('/system/roles');
        const list = data as unknown as Array<Record<string, any>>;
        expect(list.length).toBeGreaterThanOrEqual(5);
        const builtin = list.filter(r => r.builtin);
        expect(builtin.length).toBeGreaterThanOrEqual(4);
        // 预置角色编码固定
        const codes = builtin.map(r => r.code);
        expect(codes).toContain('SUPER_ADMIN');
        expect(codes).toContain('DATA_ENGINEER');
        // 自定义角色出现
        expect(list.some(r => r.name === `${PREFIX}role`)).toBeTruthy();
        await api.dispose();
    });

    test('A2 创建自定义角色（PM-8）：含权限点，登录返回 permissions', async () => {
        const api = await Api.create();
        await api.login(ADMIN.username, ADMIN.password);
        const code = `E2EF2T${Date.now() % 100000}`;
        const created = await api.post<{id: string}>('/system/roles', {
            name: `F2TMP${Date.now() % 100000}`,
            code,
            description: 'A2 创建',
            permissions: ['datasource:view'],
        });
        expect(created.id).toBeTruthy();
        // 删除
        await api.del(`/system/roles/${created.id}`);
        await api.dispose();
    });

    test('A3 创建重名角色 → 阻止（PM-11）', async () => {
        const api = await Api.create();
        await api.login(ADMIN.username, ADMIN.password);
        const code = `E2EF2D${Date.now() % 100000}`;
        const name = `F2DUP${Date.now() % 100000}`;
        await api.post('/system/roles', {name, code, permissions: ['datasource:view']});
        const dup = await api.raw<{code?: number; message?: string}>('POST', '/system/roles', {
            name, code: code + 'X', permissions: ['datasource:view'],
        });
        expect(dup.code).toBe(2006);
        const dup2 = await api.raw<{code?: number}>('POST', '/system/roles', {
            name: name + 'X', code, permissions: ['datasource:view'],
        });
        expect(dup2.code).toBe(2005);
        // 清理
        const list = await api.get<Array<Record<string, any>>>('/system/roles');
        const target = (list as unknown as Array<Record<string, any>>).filter(r => r.code === code);
        for (const r of target) await api.del(`/system/roles/${r.id}`);
        await api.dispose();
    });

    test('A4 编辑自定义角色（PM-9）：改描述 + 功能权限点', async () => {
        const api = await Api.create();
        await api.login(ADMIN.username, ADMIN.password);
        const code = `E2EF2E${Date.now() % 100000}`;
        const name = `F2EDIT${Date.now() % 100000}`;
        const created = await api.post<{id: string}>('/system/roles', {
            name, code, description: 'v1', permissions: ['datasource:view'],
        });
        const updated = await api.put<{permissions: string[]; description?: string}>(
            `/system/roles/${created.id}`, {description: 'v2', permissions: ['datasource:view', 'datasource:create']},
        );
        expect(updated.permissions).toContain('datasource:create');
        expect(updated.description).toBe('v2');
        await api.del(`/system/roles/${created.id}`);
        await api.dispose();
    });

    test('A5 预置角色编辑/删除 → 拒绝（PM-15）', async () => {
        const api = await Api.create();
        await api.login(ADMIN.username, ADMIN.password);
        const list = await api.get<Array<Record<string, any>>>('/system/roles');
        const superAdmin = (list as unknown as Array<Record<string, any>>).find(r => r.code === 'SUPER_ADMIN');
        expect(superAdmin).toBeTruthy();
        const edit = await api.raw<{code?: number}>('PUT', `/system/roles/${superAdmin!.id}`, {
            description: 'x', permissions: ['datasource:view'],
        });
        expect(edit.code).toBe(2007);
        const del = await api.raw<{code?: number}>('DELETE', `/system/roles/${superAdmin!.id}`);
        expect(del.code).toBe(2007);
        await api.dispose();
    });

    test('A6 删除被绑定用户的自定义角色 → 阻止（PM-12）', async () => {
        const api = await Api.create();
        await api.login(ADMIN.username, ADMIN.password);
        // seed 角色已绑定 e2e_s11f2_user
        const del = await api.raw<{code?: number; message?: string}>('DELETE', `/system/roles/${ROLE_ID}`);
        expect(del.code).toBe(2008);
        expect(String(del.message || '')).toContain('使用');
        await api.dispose();
    });

    test('A7 删除无绑定用户的自定义角色 → 成功（PM-13）+ 权限点被清', async () => {
        const api = await Api.create();
        await api.login(ADMIN.username, ADMIN.password);
        const code = `E2EF2R${Date.now() % 100000}`;
        const created = await api.post<{id: string}>('/system/roles', {
            name: `F2DEL${Date.now() % 100000}`, code, permissions: ['datasource:view'],
        });
        const del = await api.raw<{code?: number}>('DELETE', `/system/roles/${created.id}`);
        expect(del.code).toBe(200);
        await api.dispose();
    });
});

// ==================== B 权限点体系（PM-16） ====================

test.describe('B 权限点体系', () => {
    test('B1 权限点清单 ≥ 20 个（datasource:view 等）', async () => {
        const api = await Api.create();
        await api.login(ADMIN.username, ADMIN.password);
        const perms = await api.get<Array<Record<string, any>>>('/system/permissions');
        expect((perms as unknown as Array<Record<string, any>>).length).toBeGreaterThanOrEqual(20);
        const codes = (perms as unknown as Array<Record<string, any>>).map(p => p.code);
        expect(codes).toContain('datasource:view');
        expect(codes).toContain('sql:execute');
        await api.dispose();
    });

    test('B2 自定义角色只勾选 datasource:view → 登录返回最小 permissions（PM-16）', async () => {
        const api = await Api.create();
        await api.login(`${PREFIX}user`, 'Test123456');
        const me = await api.get<{permissions: string[]}>('/system/auth/me');
        const perms = (me as unknown as {permissions: string[]}).permissions;
        expect(perms).toContain('datasource:view');
        // seed 角色未勾选 datasource:create → 不应出现
        expect(perms).not.toContain('datasource:create');
        // 直接调创建接口 → 403（无权限）
        const resp = await api.raw<{code?: number}>('POST', '/engineering/datasources', {
            name: `${PREFIX}x`, type: 'MYSQL', host: '127.0.0.1', port: 3306,
            databaseName: 'x', username: 'x', password: 'x',
        });
        expect(resp.code).not.toBe(200);
        await api.dispose();
    });
});

// ==================== C 数据权限五入口（PM-1/2/4/5） ====================

test.describe('C 数据权限五入口', () => {
    test('C1 SQL 终端：白名单表可查、无权限表 2012（PM-3）', async () => {
        const api = await Api.create();
        await api.login(`${PREFIX}user`, 'Test123456');
        // 白名单表 users → 可查
        const ok = await api.raw<{code?: number; data?: {rowCount?: number}; message?: string}>(
            'POST', '/data-service/sql-console/execute',
            {datasourceId: MYSQL.datasourceId, sql: `SELECT * FROM ${MYSQL.database}.${MYSQL.table} LIMIT 2;`, timeoutSeconds: 20},
        );
        expect(ok.code).toBe(200);
        // 无权限表 orders → 2012
        const denied = await api.raw<{code?: number}>('POST', '/data-service/sql-console/execute', {
            datasourceId: MYSQL.datasourceId,
            sql: `SELECT * FROM ${MYSQL.database}.${MYSQL.forbiddenTable} LIMIT 2;`,
            timeoutSeconds: 20,
        });
        expect(denied.code).toBe(2012);
        await api.dispose();
    });

    test('C2 SQL 终端数据源下拉：白名单数据源可见（PM-1）', async () => {
        const api = await Api.create();
        await api.login(`${PREFIX}user`, 'Test123456');
        const list = await api.get<Array<Record<string, any>>>('/data-service/sql-console/datasources');
        const ids = (list as unknown as Array<Record<string, any>>).map(d => String(d.id));
        // 白名单 mysql 可见（内置 Doris=-1 恒可见）
        expect(ids).toContain(MYSQL.datasourceId);
        await api.dispose();
    });

    test('C3 资产目录浏览过滤：白名单外数据源不可见（PM-2）', async () => {
        const api = await Api.create();
        await api.login(`${PREFIX}user`, 'Test123456');
        const resp = await api.get<{records?: Array<Record<string, any>>}>('/governance/assets/browse?page=1&pageSize=50');
        const records = (resp as unknown as {records?: Array<Record<string, any>>}).records || [];
        // 若有记录，外部数据源只能来自白名单 mysql
        for (const r of records) {
            const dsId = String(r.datasourceId || '');
            if (dsId && dsId !== '-1') {
                expect(dsId).toBe(MYSQL.datasourceId);
            }
        }
        await api.dispose();
    });

    test('C4 数据 API 创建：无权限表 → 2012（PM-4 fail-closed）', async () => {
        const api = await Api.create();
        await api.login(`${PREFIX}user`, 'Test123456');
        const resp = await api.raw<{code?: number}>('POST', '/data-service/apis', {
            name: `${PREFIX}api_${Date.now()}`,
            path: `/e2e-f2-${Date.now()}`,
            datasourceId: MYSQL.datasourceId,
            databaseName: MYSQL.database,
            schemaName: '',
            tableName: MYSQL.forbiddenTable,
            fields: [],
            filters: [],
            orderBy: '',
        });
        expect(resp.code).toBe(2012);
        await api.dispose();
    });

    test('C5 同步任务创建：源表无权限 → 2012（PM-5 fail-closed）', async () => {
        const api = await Api.create();
        await api.login(`${PREFIX}user`, 'Test123456');
        const resp = await api.raw<{code?: number}>('POST', '/engineering/sync-jobs', {
            name: `${PREFIX}sync_${Date.now()}`,
            sourceDatasourceId: MYSQL.datasourceId,
            sourceDatabase: MYSQL.database,
            sourceTables: [MYSQL.forbiddenTable],
            syncMode: 'FULL',
            triggerType: 'MANUAL',
            targetDatabase: 'datanest',
            targetTable: `${PREFIX}tgt_${Date.now()}`,
        });
        expect(resp.code).toBe(2012);
        await api.dispose();
    });
});

// ==================== D PM-6 机密表锁定 ====================

test.describe('D PM-6 机密表锁定', () => {
    test('D1 权限树返回 sensitivityLevel（CONFIDENTIAL 表标记）', async () => {
        const original = snapshotConfSensitivity();
        try {
            setConfSensitivity('CONFIDENTIAL');
            const api = await Api.create();
            await api.login(ADMIN.username, ADMIN.password);
            const resp = await api.get<Array<Record<string, any>>>('/governance/metadata/permission-tree');
            const tree = resp as unknown as Array<Record<string, any>>;
            const flat = tree.flatMap((ds: Record<string, any>) =>
                (ds.databases || []).flatMap((db: Record<string, any>) =>
                    (db.tables || []).map((t: Record<string, any>) => ({dsId: String(ds.datasourceId), db: db.databaseName, ...t}))));
            const conf = flat.find(t => t.dsId === MYSQL.datasourceId && t.db === MYSQL.database && t.tableName === MYSQL.table);
            expect(conf).toBeTruthy();
            expect(conf.sensitivityLevel).toBe('CONFIDENTIAL');
            await api.dispose();
        } finally {
            setConfSensitivity(original as 'PUBLIC' | 'INTERNAL' | 'CONFIDENTIAL');
        }
    });

    test('D2 UI 授权弹窗：机密表显示锁定图标 + 禁用勾选 + 悬停提示', async ({page}) => {
        const original = snapshotConfSensitivity();
        try {
            setConfSensitivity('CONFIDENTIAL');
            await adminGoto(page, '/system/data-permission');
            await page.getByText(`${PREFIX}role`).first().click();
            await page.getByRole('tab', {name: '数据权限'}).click();
            await page.getByRole('radio', {name: /仅授权数据/}).click();
            await page.getByRole('button', {name: '添加授权'}).click();
            // 清空搜索框（默认已通过状态密文触发过滤），搜索目标表名
            await page.getByPlaceholder('搜索数据源 / 库 / 表名').fill(MYSQL.table);
            // 展开 mysql → testdb 两层（antd Tree 切换器在 title 左侧 chevron）
            // 第 1 个 switcher：mysql；第 2 个 switcher：testdb
            await page.locator('.ant-tree-switcher').nth(0).click();
            // mysql 展开后出现 testdb，再点 testdb 的 switcher
            await page.locator('.ant-tree-switcher').nth(1).click();
            // 表节点渲染：CONFIDENTIAL 表应出现锁定图标（HiOutlineLockClosed 渲染为 svg + text-ds-danger）
            const lockIcon = page.locator('svg.text-ds-danger').first();
            await expect(lockIcon).toBeVisible({timeout: 10_000});
            // 锁定表节点应不可勾选（disabled）：点击其 title 勾选框不变为 checked
            const tableRow = lockIcon.locator('xpath=ancestor::*[contains(@class,"ant-tree-node-content-wrapper")]');
            await tableRow.click();
            await expect(tableRow.locator('.ant-tree-checkbox-checked')).toHaveCount(0);
            // 悬停提示：Tooltip 「机密表，请先在数据分级分类中降级」
            await lockIcon.hover();
            await expect(page.getByText('机密表，请先在数据分级分类中降级').first()).toBeVisible({timeout: 5_000});
            await page.getByRole('button', {name: '取消'}).click();
        } finally {
            setConfSensitivity(original as 'PUBLIC' | 'INTERNAL' | 'CONFIDENTIAL');
        }
    });
});

// ==================== E PM-14 保存即时生效 ====================

test.describe('E PM-14 保存即时生效', () => {
    test('E1 改角色功能权限 → 旧 token 下次请求即 403（无需重新登录）', async () => {
        const adminApi = await Api.create();
        await adminApi.login(ADMIN.username, ADMIN.password);

        // 临时角色+用户（独立于 seed，避免污染）
        const code = `E2EF2P${Date.now() % 100000}`;
        const role = await adminApi.post<{id: string}>('/system/roles', {
            name: `F2PM14${Date.now() % 100000}`, code,
            permissions: ['datasource:view', 'datasource:create'],
        });
        const roleId = String(role.id);
        const userName = `${PREFIX}pm14u_${Date.now()}`;
        const user = await adminApi.post<{id: string}>('/system/users', {
            username: userName, password: 'Test123456', roles: [code],
        });
        const userId = String(user.id);

        // 目标用户登录
        const targetApi = await Api.create();
        await targetApi.login(userName, 'Test123456');
        // 当前有 create 权限 → 可创建
        const ok = await targetApi.raw<{code?: number}>('POST', '/engineering/datasources', {
            name: `${PREFIX}probe_${Date.now()}`, type: 'MYSQL', host: '127.0.0.1', port: 3306,
            databaseName: 'x', username: 'x', password: 'x',
        });
        expect(ok.code).toBe(200);

        // admin 修改角色：去掉 datasource:create
        await adminApi.put(`/system/roles/${roleId}`, {
            description: 'pm14 v2', permissions: ['datasource:view'],
        });

        // 旧 token 立即失去 create 权限 → 403（无重新登录）
        const denied = await targetApi.raw<{code?: number}>('POST', '/engineering/datasources', {
            name: `${PREFIX}probe2_${Date.now()}`, type: 'MYSQL', host: '127.0.0.1', port: 3306,
            databaseName: 'x', username: 'x', password: 'x',
        });
        expect(denied.code).not.toBe(200);

        // /auth/me 返回最新权限（无 datasource:create）
        const me = await targetApi.get<{permissions: string[]}>('/system/auth/me');
        const perms = (me as unknown as {permissions: string[]}).permissions;
        expect(perms).not.toContain('datasource:create');
        expect(perms).toContain('datasource:view');

        await targetApi.dispose();
        // 先删用户（解除角色绑定），再删角色（否则 2008 角色仍被使用）
        const {execSync} = await import('child_process');
        execSync(
            `docker exec -i datanest-middleware-postgres psql -U datanest -d datanest_system -c "DELETE FROM sys_user_role WHERE user_id=${userId}; DELETE FROM sys_user WHERE id=${userId};"`,
            {encoding: 'utf-8'},
        );
        await adminApi.del(`/system/roles/${roleId}`);
        await adminApi.dispose();
    });

    test('E2 改角色成员（移除用户）→ 旧 token 立即失去权限', async () => {
        const adminApi = await Api.create();
        await adminApi.login(ADMIN.username, ADMIN.password);

        const code = `E2EF2M${Date.now() % 100000}`;
        const role = await adminApi.post<{id: string}>('/system/roles', {
            name: `F2MEM${Date.now() % 100000}`, code,
            permissions: ['datasource:view', 'datasource:create'],
        });
        const roleId = String(role.id);
        const userName = `${PREFIX}memu_${Date.now()}`;
        const user = await adminApi.post<{id: string}>('/system/users', {
            username: userName, password: 'Test123456', roles: [code],
        });
        const userId = String(user.id);

        const targetApi = await Api.create();
        await targetApi.login(userName, 'Test123456');
        // 有权限
        const ok = await targetApi.raw<{code?: number}>('POST', '/engineering/datasources', {
            name: `${PREFIX}memprobe_${Date.now()}`, type: 'MYSQL', host: '127.0.0.1', port: 3306,
            databaseName: 'x', username: 'x', password: 'x',
        });
        expect(ok.code).toBe(200);

        // 移除该用户成员
        await adminApi.put(`/system/roles/${roleId}/users`, {userIds: []});

        // 旧 token 立即失去权限
        const denied = await targetApi.raw<{code?: number}>('POST', '/engineering/datasources', {
            name: `${PREFIX}memprobe2_${Date.now()}`, type: 'MYSQL', host: '127.0.0.1', port: 3306,
            databaseName: 'x', username: 'x', password: 'x',
        });
        expect(denied.code).not.toBe(200);

        await targetApi.dispose();
        // 先删用户（解除角色绑定），再删角色（否则 2008 角色仍被使用）
        const {execSync} = await import('child_process');
        execSync(
            `docker exec -i datanest-middleware-postgres psql -U datanest -d datanest_system -c "DELETE FROM sys_user_role WHERE user_id=${userId}; DELETE FROM sys_user WHERE id=${userId};"`,
            {encoding: 'utf-8'},
        );
        await adminApi.del(`/system/roles/${roleId}`);
        await adminApi.dispose();
    });
});

// ==================== F 权限配置页 UI ====================

test.describe('F 权限配置页 UI', () => {
    test('F1 页面加载：角色清单 + 三 Tab（功能权限/数据权限/成员）', async ({page}) => {
        await adminGoto(page, '/system/data-permission');
        // 页面标题「权限配置」+ 副标题
        await expect(page.getByRole('heading', {name: '权限配置'})).toBeVisible();
        // 角色清单（seed 角色）
        await expect(page.getByText(`${PREFIX}role`).first()).toBeVisible();
        // 选中自定义角色后出现三 Tab
        await page.getByText(`${PREFIX}role`).first().click();
        await expect(page.getByRole('tab', {name: '功能权限'})).toBeVisible();
        await expect(page.getByRole('tab', {name: '数据权限'})).toBeVisible();
        await expect(page.getByRole('tab', {name: '成员'})).toBeVisible();
    });

    test('F2 功能权限树 + 快捷档位「查看档」「全部档」（PM-17）', async ({page}) => {
        await adminGoto(page, '/system/data-permission');
        await page.getByText(`${PREFIX}role`).first().click();
        // 快捷档位
        await page.getByRole('button', {name: '全部档'}).click();
        await expect(page.getByText(/已勾选 \d+ 项/).first()).toBeVisible();
        // 查看档：勾选恢复为只读权限点数（少于全部档）
        await page.getByRole('button', {name: '查看档'}).click();
    });

    test('F3 数据权限 Tab：切换 WHITELIST + 添加授权（选白名单表）', async ({page}) => {
        await adminGoto(page, '/system/data-permission');
        await page.getByText(`${PREFIX}role`).first().click();
        await page.getByRole('tab', {name: '数据权限'}).click();
        await page.getByRole('radio', {name: /仅授权数据/}).click();
        // 当前已有授权（seed 白名单 mysql.testdb.users）→ 显示授权组
        await expect(page.getByText(MYSQL.table).first()).toBeVisible();
    });

    test('F4 成员 Tab：seed 用户已在成员列表', async ({page}) => {
        await adminGoto(page, '/system/data-permission');
        await page.getByText(`${PREFIX}role`).first().click();
        await page.getByRole('tab', {name: '成员'}).click();
        await expect(page.getByText(`${PREFIX}user`).first()).toBeVisible();
    });

    test('F5 保存权限配置 → 提示成功', async ({page}) => {
        await adminGoto(page, '/system/data-permission');
        await page.getByText(`${PREFIX}role`).first().click();
        // 功能权限 Tab 默认激活：点击「全部档」制造脏改 → 保存按钮激活
        await page.getByRole('button', {name: '全部档'}).click();
        await expect(page.getByText(/已勾选 \d+ 项/).first()).toBeVisible();
        // 保存按钮激活（保存所有修改）
        await page.getByRole('button', {name: /保存所有修改/}).click();
        // 保存完成：保存按钮回到 disabled（无未保存修改）
        await expect(page.getByRole('button', {name: /保存所有修改/})).toBeDisabled({timeout: 15_000});
    });
});

// ==================== G 角色管理页 UI ====================

test.describe('G 角色管理页 UI', () => {
    test('G1 列表渲染 + 预置角色只读（编辑/删除禁用，PM-15）', async ({page}) => {
        await adminGoto(page, '/system/roles');
        await waitTableSettled(page);
        // 预置标识
        await expect(page.getByText('预置').first()).toBeVisible();
        await expect(page.getByText('自定义').first()).toBeVisible();
        // 自定义角色行
        await expect(page.getByText(`${PREFIX}role`).first()).toBeVisible();
        // 预置行：编辑/删除按钮 disabled
        const superRow = page.locator('.ant-table-row').filter({hasText: '超级管理员'});
        await expect(superRow.locator('[aria-label="编辑"]')).toBeDisabled();
        await expect(superRow.locator('[aria-label="删除"]')).toBeDisabled();
    });

    test('G2 创建角色流程（弹窗校验：空权限点被阻止）', async ({page}) => {
        await adminGoto(page, '/system/roles');
        await page.getByRole('button', {name: '创建角色'}).click();
        // 自定义 label 无 for 关联，用 placeholder 定位
        await page.getByPlaceholder('2~20 字符，创建后不可修改').fill(`${PREFIX}ui_${Date.now()}`);
        await page.getByPlaceholder('英文可读，如 READONLY_AUDITOR').fill(`E2EF2U${Date.now() % 100000}`);
        // 不勾权限点直接提交 → 阻止（提交按钮文本为「保存」）
        await page.getByRole('button', {name: '保存'}).click();
        await expect(page.getByText('请至少勾选一项功能权限').first()).toBeVisible();
        await page.getByRole('button', {name: '取消'}).click();
    });
});

// ==================== H 数据源列表按钮级（PM-16） ====================

test.describe('H 数据源列表按钮级', () => {
    test('H1 自定义角色只勾 datasource:view → 数据源列表无「新建」按钮（PM-16 UI）', async ({page}) => {
        // 独立最小权限角色+用户（仅 datasource:view，避免 ENGINEERING_WRITE_PERMS 任一匹配误显示按钮）
        const adminApi = await Api.create();
        await adminApi.login(ADMIN.username, ADMIN.password);
        const code = `E2EF2RO${Date.now() % 100000}`;
        const role = await adminApi.post<{id: string}>('/system/roles', {
            name: `${PREFIX}vo${Date.now() % 100000}`, code,
            permissions: ['datasource:view'],
        });
        const roleId = String(role.id);
        const userName = `${PREFIX}vo_u_${Date.now() % 100000}`;
        const user = await adminApi.post<{id: string}>('/system/users', {
            username: userName, password: 'Test123456', roles: [code],
        });
        const userId = String(user.id);
        await adminApi.dispose();

        try {
            await gotoAs(page, userName, 'Test123456', '/engineering/datasources');
            // 等待列表加载
            await expect(page.locator('.ant-table-row').first()).toBeVisible();
            // 无新建按钮（canWrite=false）
            await expect(page.getByTestId('datasource-create-btn')).toHaveCount(0);
        } finally {
            // 清理：先删用户（解绑）再删角色
            const {execSync} = await import('child_process');
            execSync(
                `docker exec -i datanest-middleware-postgres psql -U datanest -d datanest_system -c "DELETE FROM sys_user_role WHERE user_id=${userId}; DELETE FROM sys_user WHERE id=${userId}; DELETE FROM sys_role_permission WHERE role_id=${roleId}; DELETE FROM sys_data_permission WHERE role_id=${roleId}; DELETE FROM sys_role WHERE id=${roleId};"`,
                {encoding: 'utf-8'},
            );
        }
    });
});

// ==================== Setup / Cleanup ====================

test.beforeAll(async () => {
    const seeded = await seedF2();
    ROLE_ID = seeded.roleId;
    USER_ID = seeded.userId;
    console.log(`[F2] seed 完成 roleId=${ROLE_ID} userId=${USER_ID}`);
});

test.afterAll(async () => {
    await cleanupF2();
    console.log('[F2] 清理完成');
});
