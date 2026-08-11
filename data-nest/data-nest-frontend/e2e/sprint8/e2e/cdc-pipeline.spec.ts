import {execSync} from 'child_process';
import {expect, type Page, test} from '@playwright/test';
import {Api} from '../../sprint6/helpers/api';
import {gotoAs} from '../../sprint6/helpers/e2e';
import {psqlEng} from '../../sprint7/helpers/db';
import {seedAll} from '../../sprint7/helpers/seed';
import {ADMIN, TEST_USERS} from '../../sprint7/helpers/data';

/**
 * Sprint 8 F2 实时 CDC 管道 E2E 测试（DI-04/RC-01 业务主链路全覆盖，API 辅助诊断）。
 *
 * 覆盖：
 * - 源预检（MySQL binlog/ROW/连通/库存在；PG wal_level/复制权限）+ 源库/源表/目标库元数据端点
 * - 向导 UI 创建（3 步：基本信息 → 配置管道（源卡/目标卡/配置带）→ 预检 + 摘要 → 仅保存）
 * - 列表页（4 统计卡/状态分段/关键词/运行时长/创建人修改人列/详情抽屉）
 * - 生命周期：启动（真实 Flink 作业）→ initial 快照落湖（Doris 可查）→ binlog 增量秒级可见
 *   → 日志抽屉 → 运行中保护（编辑/删除 8003）→ 停止（savepoint）→ 恢复续传不丢不重
 *   → 编辑（savepoint 清空）→ 删除（级联清理 + 湖仓数据保留）
 * - 仅增量 LATEST_OFFSET 模式（不跑全量快照）
 * - PostgreSQL 源全链路（WAL → 湖仓 → Doris，含 UPDATE/DELETE 语义观察）
 * - 权限隔离（分析师只读 / 写操作 1005）
 * - engineering 删除数据源 CDC 引用校验（8009 fail-closed）
 * - 参数校验（重名 8002 / UPSERT 缺主键 8000 / 仅增量+INITIAL 8000 / PG+EARLIEST 8000 / 并行度越界 8000）
 *
 * 环境约定：
 * - 真实源：middleware-test-mysql testdb / middleware-test-postgres postgres（专用 e2e_s8 前缀表，自播种自清理）
 * - 真实链路：Flink Session 集群（1 slot，全程串行保证同一时刻只有 1 个作业 RUNNING）
 * - 湖仓断言：Doris datalake_catalog（经 middleware-mysql 容器内 mysql client 查询）
 * - 管道数据：datanest_realtime 库，e2e_s8_ 前缀，beforeAll/afterAll 双向清理
 */

// ==================== 环境常量 ====================

/** 存量真实 MySQL 数据源（middleware-test-mysql:3306/testdb，testuser 已授复制权限） */
const MYSQL_DS_ID = '2083088527209295874';
const MYSQL_DS_LABEL = 'mysql（middleware-test-mysql）';
/** 存量真实 PG 数据源（middleware-test-postgres:5432/postgres） */
const PG_DS_ID = '2083837829055127553';
const MYSQL_DB = 'testdb';
const PG_DB = 'postgres';
/** 湖仓目标库（Iceberg namespace，自动创建） */
const TARGET_DB = 'e2e_s8_dwd';

/** 测试源表 */
const T_MAIN = 'e2e_s8_cdc_users'; // MySQL 主链路（快照 3 行）
const T_INCR = 'e2e_s8_cdc_incr'; // MySQL 仅增量（存量 3 行不应被同步）
const T_PG = 'e2e_s8_pg_users'; // PG 全链路（快照 2 行）

/** 管道名（全局唯一约束，e2e_s8_ 前缀自清理） */
const P_MAIN = 'e2e_s8_main_users';
const P_INCR = 'e2e_s8_incr_only';
const P_PG = 'e2e_s8_pg_users';
const P_GUARD = 'e2e_s8_ds_guard';

/** 临时数据源（引用校验用，SQL 复制真实 mysql 连接信息） */
const TMP_DS_ID = '9000080000000000099';
const TMP_DS_NAME = 'e2e_s8_tmp_ds';

const FAST_CKPT = JSON.stringify({checkpointIntervalSeconds: 10});

let admin: Api;
let engineer: Api;
let analyst: Api;
let govAdmin: Api;

/** 主链路管道 id（跨用例传递） */
let mainPipelineId = '';
/** 主链路源表 e2e_s8_cdc_users 的动态行数基线（快照 3 行起，随用例插入递增） */
let mainRows = 3;

// ==================== 外部存储辅助 ====================

function exec(sql: string, cmd: string): string {
    return execSync(cmd, {input: sql, encoding: 'utf-8', maxBuffer: 10 * 1024 * 1024}).trim();
}

/** test-mysql（源库造数） */
const mysqlT = (sql: string) =>
    exec(sql, 'docker exec -i datanest-middleware-test-mysql mysql -u root -proot123 testdb -N -B');

/** test-postgres（源库造数） */
const pgT = (sql: string) =>
    exec(sql, 'docker exec -i datanest-middleware-test-postgres psql -U postgres -d postgres -t -A');

/** realtime 业务库（管道/日志断言） */
const psqlRt = (sql: string) =>
    exec(sql, 'docker exec -i datanest-middleware-postgres psql -U datanest -d datanest_realtime -t -A');

/** Doris 查询（经 middleware-mysql 容器内 mysql client；表不存在等错误返回 null） */
function doris(sql: string): string | null {
    try {
        return exec(sql, 'docker exec -i datanest-middleware-mysql mysql -h192.168.119.135 -P9030 -uroot -ppassword -N -B');
    } catch {
        return null;
    }
}

/** 湖仓表行数（表不存在/查询失败返回 null） */
function lakeCount(table: string): number | null {
    const r = doris(`SELECT COUNT(*) FROM datalake_catalog.${TARGET_DB}.${table}`);
    return r === null || r === '' || isNaN(Number(r)) ? null : Number(r);
}

/** 触发 Doris REFRESH 后断言湖仓行数（轮询直到可见；snapshot/增量可见都走这条路） */
async function expectLakeCount(api: Api, pipelineId: string, table: string, expected: number, timeoutMs = 150_000): Promise<void> {
    await expect.poll(async () => {
        try {
            await api.get(`/realtime/cdc/pipelines/${pipelineId}/refresh-catalog`);
        } catch {
            // 刷新失败不阻断，下轮重试
        }
        return lakeCount(table);
    }, {timeout: timeoutMs, intervals: [5_000, 8_000, 10_000]}).toBe(expected);
}

// ==================== 播种 / 清理 ====================

/** 重建源库测试表（幂等，保证行数基线） */
function seedSourceTables(): void {
    mysqlT(`DROP TABLE IF EXISTS ${T_MAIN};
            CREATE TABLE ${T_MAIN} (id BIGINT NOT NULL AUTO_INCREMENT, username VARCHAR(64) NOT NULL,
                amount INT DEFAULT 0, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (id));
            INSERT INTO ${T_MAIN} (username, amount) VALUES ('seed_a', 100), ('seed_b', 200), ('seed_c', 300);
            DROP TABLE IF EXISTS ${T_INCR};
            CREATE TABLE ${T_INCR} (id BIGINT NOT NULL AUTO_INCREMENT, note VARCHAR(128), PRIMARY KEY (id));
            INSERT INTO ${T_INCR} (note) VALUES ('old_1'), ('old_2'), ('old_3');`);
    pgT(`DROP TABLE IF EXISTS ${T_PG};
         CREATE TABLE ${T_PG} (id BIGINT PRIMARY KEY, name VARCHAR(64), updated_at TIMESTAMP DEFAULT now());
         ALTER TABLE ${T_PG} REPLICA IDENTITY FULL;
         INSERT INTO ${T_PG} (id, name) VALUES (1, 'pg_seed_a'), (2, 'pg_seed_b');`);
}

/** 清理全部 e2e_s8 管道（先停 Flink 作业再删库记录，幂等） */
async function cleanPipelines(): Promise<void> {
    try {
        const running = psqlRt(`SELECT id FROM cdc_pipeline WHERE name LIKE 'e2e_s8_%' AND status = 'RUNNING'`);
        for (const id of running.split('\n').filter(Boolean)) {
            await admin.raw('POST', `/realtime/cdc/pipelines/${id}/stop`).catch(() => undefined);
        }
    } catch { /* 无残留 */ }
    psqlRt(`DELETE FROM cdc_pipeline_log WHERE pipeline_id IN (SELECT id FROM cdc_pipeline WHERE name LIKE 'e2e_s8_%');
            DELETE FROM cdc_pipeline_table WHERE pipeline_id IN (SELECT id FROM cdc_pipeline WHERE name LIKE 'e2e_s8_%');
            DELETE FROM cdc_pipeline WHERE name LIKE 'e2e_s8_%';`);
    psqlEng(`DELETE FROM datasource_connection WHERE id = ${TMP_DS_ID} OR name LIKE 'e2e_s8_%'`);
}

/** 清理 Doris 湖仓残留（best effort） */
function cleanLake(): void {
    for (const t of [T_MAIN, T_INCR, T_PG]) {
        doris(`DROP TABLE IF EXISTS datalake_catalog.${TARGET_DB}.${t}`);
    }
}

function dropSourceTables(): void {
    try {
        mysqlT(`DROP TABLE IF EXISTS ${T_MAIN}; DROP TABLE IF EXISTS ${T_INCR};`);
        pgT(`DROP TABLE IF EXISTS ${T_PG};`);
    } catch { /* best effort */ }
}

// ==================== UI 辅助 ====================

const notice = (page: Page, text: string | RegExp) =>
    page.locator('.ant-message-notice').filter({hasText: text}).first();

const pipelineRow = (page: Page, name: string) =>
    page.locator('.ant-table-row').filter({hasText: name});

/** 管道列表页按名称定位行（先关键词搜索缩小范围） */
async function gotoListAndFind(page: Page, name: string): Promise<void> {
    await page.getByLabel('搜索 CDC 管道').fill(name);
    await page.getByRole('button', {name: '查询', exact: true}).click();
    await expect(pipelineRow(page, name)).toBeVisible();
}

test.describe.configure({mode: 'serial'});

test.beforeAll(async () => {
    admin = await Api.create();
    await admin.login(ADMIN.username, ADMIN.password);
    // 复用 sprint7 播种（幂等，提供 s7_engineer/s7_analyst/s7_govadmin 用户）
    await seedAll();
    await cleanPipelines();
    cleanLake();
    seedSourceTables();
    engineer = await Api.create();
    await engineer.login(TEST_USERS.engineer.username, TEST_USERS.engineer.password);
    analyst = await Api.create();
    await analyst.login(TEST_USERS.analyst.username, TEST_USERS.analyst.password);
    govAdmin = await Api.create();
    await govAdmin.login(TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password);
});

test.afterAll(async () => {
    await cleanPipelines();
    cleanLake();
    dropSourceTables();
    await admin?.dispose();
    await engineer?.dispose();
    await analyst?.dispose();
    await govAdmin?.dispose();
});

// ==================== A. 源预检与元数据（API 辅助） ====================

test.describe('A. 源预检与元数据', () => {
    test('MySQL 预检 4 项全通过；不存在库检出失败', async () => {
        const ok = await engineer.post<any>('/realtime/cdc/pipelines/validate-source',
            {datasourceId: MYSQL_DS_ID, sourceDatabase: MYSQL_DB});
        expect(ok.success).toBe(true);
        const names = ok.checks.map((c: any) => c.name);
        expect(names).toEqual(['数据源连通性', 'binlog 开启', 'binlog 格式为 ROW', '源库存在']);
        for (const c of ok.checks) expect(c.passed).toBe(true);

        const bad = await engineer.post<any>('/realtime/cdc/pipelines/validate-source',
            {datasourceId: MYSQL_DS_ID, sourceDatabase: 'e2e_s8_no_such_db'});
        expect(bad.success).toBe(false);
        expect(bad.checks.find((c: any) => c.name === '源库存在').passed).toBe(false);
    });

    test('PostgreSQL 预检（wal_level=logical + 复制权限）', async () => {
        const ok = await engineer.post<any>('/realtime/cdc/pipelines/validate-source',
            {datasourceId: PG_DS_ID, sourceDatabase: PG_DB});
        expect(ok.success).toBe(true);
        const names = ok.checks.map((c: any) => c.name);
        expect(names).toContain('WAL 逻辑复制开启');
        expect(names).toContain('复制权限');
        for (const c of ok.checks) expect(c.passed).toBe(true);
    });

    test('源库/源表/目标库元数据端点', async () => {
        const dbs = await engineer.get<string[]>(`/realtime/cdc/pipelines/source-databases/${MYSQL_DS_ID}`);
        expect(dbs).toContain(MYSQL_DB);

        const tables = await engineer.get<any[]>(
            `/realtime/cdc/pipelines/source-tables/${MYSQL_DS_ID}?database=${MYSQL_DB}`);
        const main = tables.find(t => t.tableName === T_MAIN);
        expect(main).toBeTruthy();
        expect(main.primaryKey).toBe('id');
        expect(Number(main.tableRows)).toBe(3);

        const pgTables = await engineer.get<any[]>(
            `/realtime/cdc/pipelines/source-tables/${PG_DS_ID}?database=${PG_DB}`);
        expect(pgTables.map(t => t.tableName)).toContain(T_PG);

        const targets = await engineer.get<string[]>('/realtime/cdc/pipelines/target-databases');
        expect(Array.isArray(targets)).toBe(true);

        // 集群容量端点（向导并行度动态提示）
        const cluster = await engineer.get<any>('/realtime/cdc/pipelines/cluster-info');
        expect(cluster.slotsTotal).toBeGreaterThanOrEqual(1);
        expect(cluster.slotsAvailable).toBeGreaterThanOrEqual(0);
    });
});

// ==================== B. 权限隔离 ====================

test.describe('B. 权限隔离', () => {
    test('分析师/治理员读接口可用，写接口 1005', async () => {
        // 读：四角色可用
        for (const api of [analyst, govAdmin]) {
            await api.get('/realtime/cdc/pipelines/page?page=1&pageSize=10');
            await api.get('/realtime/cdc/pipelines/stats');
        }
        // 写：分析师/治理员被 1005 拦截
        const body = {
            name: 'e2e_s8_forbidden', sourceDatasourceId: MYSQL_DS_ID, sourceDatabase: MYSQL_DB,
            targetDatabase: TARGET_DB, syncMode: 'FULL_AND_INCREMENT', startupMode: 'INITIAL',
            writeMode: 'APPEND', tables: [{sourceTable: T_MAIN}],
        };
        for (const api of [analyst, govAdmin]) {
            expect((await api.raw('POST', '/realtime/cdc/pipelines', body)).code).toBe(1005);
            expect((await api.raw('POST', '/realtime/cdc/pipelines/validate-source',
                {datasourceId: MYSQL_DS_ID})).code).toBe(1005);
            expect((await api.raw('POST', '/realtime/cdc/pipelines/1/start')).code).toBe(1005);
            expect((await api.raw('DELETE', '/realtime/cdc/pipelines/1')).code).toBe(1005);
        }
    });

    test('分析师 UI：菜单可见、列表只读（无新建/写按钮）', async ({page}) => {
        await gotoAs(page, TEST_USERS.analyst.username, TEST_USERS.analyst.password, '/engineering/cdc-pipelines');
        await expect(page.getByRole('heading', {name: 'CDC 管道'})).toBeVisible();
        await expect(page.getByRole('button', {name: /新建管道/})).toHaveCount(0);
        // 只读操作（详情/日志图标按钮）存在与否取决于有无数据行；写按钮绝不出现
        await expect(page.locator('[aria-label^="启动 "]')).toHaveCount(0);
        await expect(page.locator('[aria-label^="停止 "]')).toHaveCount(0);
        await expect(page.locator('[aria-label^="编辑 "]')).toHaveCount(0);
        await expect(page.locator('[aria-label^="删除 "]')).toHaveCount(0);
        await expect(page.locator('[aria-label^="刷新 Catalog"]')).toHaveCount(0);
        // 侧边栏菜单可见
        await expect(page.getByRole('button', {name: 'CDC 管道'})).toBeVisible();
    });
});

// ==================== C. 向导创建 + 列表页（主链路 P1） ====================

test.describe('C. 向导创建与列表页', () => {
    test('向导 3 步创建管道（仅保存）：预检通过 + 配置摘要 + 主键自动回填', async ({page}) => {
        await gotoAs(page, TEST_USERS.engineer.username, TEST_USERS.engineer.password, '/engineering/cdc-pipelines');
        await page.getByRole('button', {name: /新建管道/}).click();
        await expect(page.getByRole('heading', {name: '新建 CDC 管道'})).toBeVisible();

        // ① 基本信息：空名拦截 → 填名称/描述
        await page.getByRole('button', {name: '下一步'}).click();
        await expect(notice(page, '请输入管道名称')).toBeVisible();
        await page.getByPlaceholder('例如：订单实时同步').fill(P_MAIN);
        await page.getByPlaceholder('同步订单库到 Iceberg 湖仓，供实时分析使用').fill('e2e_s8 主链路验证管道');
        await page.getByRole('button', {name: '下一步'}).click();

        // ② 配置管道：源卡
        await page.getByLabel('源数据源').click();
        await page.locator('.ant-select-dropdown:visible .ant-select-item-option')
            .filter({hasText: MYSQL_DS_LABEL}).click();
        await page.getByLabel('源数据库').click();
        await page.locator('.ant-select-dropdown:visible .ant-select-item-option')
            .filter({hasText: MYSQL_DB}).first().click();
        // 勾选同步表（主键随勾选自动回填）
        await page.locator('label').filter({hasText: T_MAIN}).click();
        // 目标卡：目标库自由输入 + 表映射主键已预填 id
        await page.getByLabel('目标库').fill(TARGET_DB);
        await expect(page.getByLabel(`主键列 ${T_MAIN}`)).toHaveValue('id');
        await expect(page.getByLabel(`目标表名 ${T_MAIN}`)).toHaveValue(T_MAIN);
        // 配置带：默认 全量+增量 / Upsert；并行度容量为动态检测的真实 slot 数
        await expect(page.getByText(/当前集群 \d+ 个 Task Slot（空闲 \d+），并行度超过将无法调度/)).toBeVisible();
        // 高级配置 Checkpoint 选「实时（10 秒）」档（加速增量验证）
        await page.getByLabel('Checkpoint 间隔').click();
        await page.locator('.ant-select-dropdown:visible .ant-select-item-option')
            .filter({hasText: '实时（10 秒'}).click();
        await page.getByRole('button', {name: '下一步'}).click();

        // ③ 确认启动：预检自动执行 → 全部通过；摘要含关键配置
        await expect(page.getByText('全部通过', {exact: true})).toBeVisible({timeout: 30_000});
        await expect(page.getByText('binlog 开启')).toBeVisible();
        const summary = page.locator('div').filter({hasText: '配置摘要'}).last();
        await expect(page.getByText(`${T_MAIN}（1 表）`)).toBeVisible();
        await expect(page.getByText(`datalake_catalog.${TARGET_DB}.${T_MAIN}`).first()).toBeVisible();
        await expect(page.getByText('实时（10 秒）', {exact: true})).toBeVisible();

        // 仅保存 → 回列表
        await page.getByRole('button', {name: '仅保存'}).click();
        await expect(notice(page, '管道已创建（未启动）')).toBeVisible();
        await page.waitForURL('**/engineering/cdc-pipelines');
        const row = pipelineRow(page, P_MAIN);
        await expect(row).toBeVisible();
        await expect(row.getByText('已停止')).toBeVisible();
        await expect(row.getByText(T_MAIN)).toBeVisible();
        await expect(row.getByText(TEST_USERS.engineer.username)).toBeVisible();

        mainPipelineId = psqlRt(`SELECT id FROM cdc_pipeline WHERE name = '${P_MAIN}'`);
        expect(mainPipelineId).not.toBe('');
    });

    test('列表页：统计卡 / 状态分段 / 关键词搜索 / 详情抽屉', async ({page}) => {
        await gotoAs(page, TEST_USERS.engineer.username, TEST_USERS.engineer.password, '/engineering/cdc-pipelines');
        // 4 统计卡（仅本用例的 1 条 STOPPED 管道 + 1 张同步表）；限网格容器内避免与状态分段按钮撞文案
        const statGrid = page.locator('.grid.grid-cols-4');
        await expect(statGrid.getByText('运行中', {exact: true})).toBeVisible();
        await expect(statGrid.getByText('已停止', {exact: true})).toBeVisible();
        await expect(statGrid.getByText('异常', {exact: true})).toBeVisible();
        await expect(statGrid.getByText('已同步表', {exact: true})).toBeVisible();
        const stats = await engineer.get<any>('/realtime/cdc/pipelines/stats');
        expect(stats).toMatchObject({running: '0', stopped: '1', error: '0', syncedTables: '1'});

        // 状态分段：运行中 → 空；已停止 → 1 行
        await page.getByRole('button', {name: '运行中', exact: true}).click();
        await expect(page.getByText('没有符合条件的管道')).toBeVisible();
        await page.getByRole('button', {name: '已停止', exact: true}).click();
        await expect(pipelineRow(page, P_MAIN)).toBeVisible();
        await page.getByRole('button', {name: '全部', exact: true}).click();

        // 关键词：命中 / 不命中 / 重置
        await page.getByLabel('搜索 CDC 管道').fill('e2e_s8_no_match');
        await page.getByRole('button', {name: '查询', exact: true}).click();
        await expect(page.getByText('没有符合条件的管道')).toBeVisible();
        await page.getByRole('button', {name: '重置'}).click();
        await expect(pipelineRow(page, P_MAIN)).toBeVisible();

        // 详情抽屉（全角色可用入口）
        await pipelineRow(page, P_MAIN).getByLabel(`详情 ${P_MAIN}`).click();
        const drawer = page.getByRole('dialog');
        await expect(drawer.getByText(`管道详情 · ${P_MAIN}`)).toBeVisible();
        await expect(drawer.getByText('e2e_s8 主链路验证管道')).toBeVisible();
        await expect(drawer.getByText(TEST_USERS.engineer.username).first()).toBeVisible();
        await expect(drawer.getByText('全量 + 增量')).toBeVisible();
        await expect(drawer.getByText('全量快照 + 增量')).toBeVisible();
        await expect(drawer.getByText(`${MYSQL_DB}.${T_MAIN} → ${TARGET_DB}.${T_MAIN}`)).toBeVisible();
        await expect(drawer.getByText('10 秒')).toBeVisible(); // 高级配置 Checkpoint 间隔
        await expect(drawer.getByText('默认（EVOLVE）')).toBeVisible(); // 表结构变更策略默认
        await drawer.getByLabel('关闭').click();
    });
});

// ==================== D. 运行生命周期（P1 主链路） ====================

test.describe('D. 运行生命周期', () => {
    test('UI 启动 → RUNNING + Flink 作业提交', async ({page}) => {
        await gotoAs(page, TEST_USERS.engineer.username, TEST_USERS.engineer.password, '/engineering/cdc-pipelines');
        await gotoListAndFind(page, P_MAIN);
        await pipelineRow(page, P_MAIN).getByLabel(`启动 ${P_MAIN}`).click();
        await expect(notice(page, `管道「${P_MAIN}」已启动`)).toBeVisible();
        // 状态徽章 + 运行时长列
        await expect(pipelineRow(page, P_MAIN).getByText('运行中')).toBeVisible({timeout: 30_000});
        // API 辅助：flinkJobId 回填 + startedAt 记录
        const detail = await engineer.get<any>(`/realtime/cdc/pipelines/${mainPipelineId}`);
        expect(detail.status).toBe('RUNNING');
        expect(detail.flinkJobId).toBeTruthy();
        expect(detail.startedAt).toBeTruthy();
        expect(detail.sourceDatasourceName).toBe('mysql');
    });

    test('initial 全量快照落湖 → Doris 外部表可查（AC-6）', async ({page}) => {
        // UI 点「刷新 Catalog」（操作列）
        await gotoAs(page, TEST_USERS.engineer.username, TEST_USERS.engineer.password, '/engineering/cdc-pipelines');
        await gotoListAndFind(page, P_MAIN);
        await pipelineRow(page, P_MAIN).getByLabel(`刷新 Catalog ${P_MAIN}`).click();
        await expect(notice(page, '已触发 Doris Catalog 刷新')).toBeVisible();
        // 轮询：快照 3 行落湖且 Doris 可见
        await expectLakeCount(engineer, mainPipelineId, T_MAIN, 3);
        const rows = doris(`SELECT username FROM datalake_catalog.${TARGET_DB}.${T_MAIN} ORDER BY id`);
        expect(rows).toContain('seed_a');
        expect(rows).toContain('seed_c');
    });

    test('binlog 增量秒级可见 + 累计变更/延迟回写（AC-7）', async () => {
        mysqlT(`INSERT INTO ${T_MAIN} (username, amount) VALUES ('incr_d', 400), ('incr_e', 500)`);
        mainRows += 2;
        await expectLakeCount(engineer, mainPipelineId, T_MAIN, mainRows);
        // Doris catalog 自动刷新（app-job 每 30s 条件刷新）：不手动调 refresh-catalog 也应自动可见
        mysqlT(`INSERT INTO ${T_MAIN} (username, amount) VALUES ('auto_refresh_g', 700)`);
        mainRows += 1;
        await expect.poll(() => lakeCount(T_MAIN), {timeout: 150_000, intervals: [10_000]})
            .toBe(mainRows);
        // 监控回写（5s 轮询）：累计变更 ≥ 基线；currentEmitEventTimeLag 只在源有事件流时存在，
        // 空闲窗口查不到会跳过回写 → 每轮补一行保持事件流，直到 lag 非空
        await expect.poll(async () => {
            const d = await engineer.get<any>(`/realtime/cdc/pipelines/${mainPipelineId}`);
            const lagOk = d.currentLagSeconds != null && Number(d.currentLagSeconds) >= 0;
            if (!lagOk) {
                mysqlT(`INSERT INTO ${T_MAIN} (username, amount) VALUES ('trickle_${Date.now() % 1000000}', 1)`);
                mainRows += 1;
            }
            return lagOk && Number(d.totalChanges ?? -1) >= mainRows;
        }, {timeout: 90_000, intervals: [6_000]}).toBe(true);
    });

    test('日志抽屉：条目展示 / 级别徽章 / 清屏 / 刷新', async ({page}) => {
        await gotoAs(page, TEST_USERS.engineer.username, TEST_USERS.engineer.password, '/engineering/cdc-pipelines');
        await gotoListAndFind(page, P_MAIN);
        await pipelineRow(page, P_MAIN).getByLabel(`日志 ${P_MAIN}`).click();
        const drawer = page.getByRole('dialog');
        await expect(drawer.getByText(`${P_MAIN} · 运行日志`)).toBeVisible();
        // 运行中自动刷新提示 + 启动日志（创建/启动/savepoint 等 INFO 条目）
        await expect(drawer.getByText('自动刷新（5s）')).toBeVisible();
        await expect(drawer.getByText('管道创建', {exact: true})).toBeVisible();
        await expect(drawer.getByText(/已启动|启动成功/).first()).toBeVisible();
        // 清屏 → 提示文案；刷新 → 恢复
        await drawer.getByRole('button', {name: /清屏/}).click();
        await expect(drawer.getByText(/已清屏/)).toBeVisible();
        await drawer.getByRole('button', {name: /刷新/}).click();
        await expect(drawer.getByText('管道创建', {exact: true})).toBeVisible();
        await drawer.getByLabel('关闭').click();
    });

    test('运行中保护：编辑/删除 UI 禁用 + API 8003', async ({page}) => {
        await gotoAs(page, TEST_USERS.engineer.username, TEST_USERS.engineer.password, '/engineering/cdc-pipelines');
        await gotoListAndFind(page, P_MAIN);
        await expect(pipelineRow(page, P_MAIN).getByLabel(`编辑 ${P_MAIN}`)).toBeDisabled();
        await expect(pipelineRow(page, P_MAIN).getByLabel(`删除 ${P_MAIN}`)).toBeDisabled();
        // API 强操作 → 8003（状态非法）
        const put = await engineer.raw('PUT', `/realtime/cdc/pipelines/${mainPipelineId}`, {
            name: P_MAIN, sourceDatasourceId: MYSQL_DS_ID, sourceDatabase: MYSQL_DB,
            targetDatabase: TARGET_DB, syncMode: 'FULL_AND_INCREMENT', startupMode: 'INITIAL',
            writeMode: 'UPSERT', tables: [{sourceTable: T_MAIN, primaryKey: 'id'}],
        });
        expect(put.code).toBe(8003);
        expect((await engineer.raw('DELETE', `/realtime/cdc/pipelines/${mainPipelineId}`)).code).toBe(8003);
        expect((await engineer.raw('POST', `/realtime/cdc/pipelines/${mainPipelineId}/start`)).code).toBe(8003);
    });

    test('UI 停止（savepoint）→ STOPPED + savepoint 回填', async ({page}) => {
        await gotoAs(page, TEST_USERS.engineer.username, TEST_USERS.engineer.password, '/engineering/cdc-pipelines');
        await gotoListAndFind(page, P_MAIN);
        await pipelineRow(page, P_MAIN).getByLabel(`停止 ${P_MAIN}`).click();
        const dialog = page.getByRole('dialog', {name: '停止管道'});
        await expect(dialog.getByText(/savepoint/)).toBeVisible();
        await dialog.getByRole('button', {name: '停止', exact: true}).click();
        await expect(notice(page, /已停止（savepoint 已保存）/)).toBeVisible();
        await expect(pipelineRow(page, P_MAIN).getByText('已停止')).toBeVisible({timeout: 60_000});
        const detail = await engineer.get<any>(`/realtime/cdc/pipelines/${mainPipelineId}`);
        expect(detail.status).toBe('STOPPED');
        expect(detail.savepointPath).toContain('s3a://');
    });

    test('savepoint 恢复续传：停止期变更不丢不重（NAC-2）', async () => {
        // 停止期间源库产生 1 行变更
        mysqlT(`INSERT INTO ${T_MAIN} (username, amount) VALUES ('during_stop_f', 600)`);
        mainRows += 1;
        // 从 savepoint 恢复启动
        await engineer.post(`/realtime/cdc/pipelines/${mainPipelineId}/start`);
        // 轮询至恢复后增量落湖：精确等于基线（不丢）且无重复（不重）
        await expectLakeCount(engineer, mainPipelineId, T_MAIN, mainRows);
        const dup = doris(`SELECT COUNT(*) - COUNT(DISTINCT id) FROM datalake_catalog.${TARGET_DB}.${T_MAIN}`);
        expect(dup).toBe('0');
        // 恢复后仍是 RUNNING
        const detail = await engineer.get<any>(`/realtime/cdc/pipelines/${mainPipelineId}`);
        expect(detail.status).toBe('RUNNING');
        // 为下个编辑用例准备 STOPPED 状态
        await engineer.post(`/realtime/cdc/pipelines/${mainPipelineId}/stop`);
        await expect.poll(async () =>
            (await engineer.get<any>(`/realtime/cdc/pipelines/${mainPipelineId}`)).status,
        {timeout: 60_000, intervals: [3_000]}).toBe('STOPPED');
    });

    test('UI 编辑：预填 + 保存后 savepoint 清空 + 审计字段', async ({page}) => {
        await gotoAs(page, TEST_USERS.engineer.username, TEST_USERS.engineer.password, '/engineering/cdc-pipelines');
        await gotoListAndFind(page, P_MAIN);
        await pipelineRow(page, P_MAIN).getByLabel(`编辑 ${P_MAIN}`).click();
        await expect(page.getByRole('heading', {name: '编辑 CDC 管道'})).toBeVisible();
        // 预填校验
        await expect(page.getByPlaceholder('例如：订单实时同步')).toHaveValue(P_MAIN);
        await page.getByPlaceholder('同步订单库到 Iceberg 湖仓，供实时分析使用')
            .fill('e2e_s8 主链路验证管道（已编辑）');
        await page.getByRole('button', {name: '下一步'}).click();
        // 高级配置回填：10 秒命中「实时」档；改走「自定义秒数」→ 15
        await expect(page.getByText('实时（10 秒，湖仓提交频繁）')).toBeVisible();
        await page.getByLabel('Checkpoint 间隔').click();
        await page.locator('.ant-select-dropdown:visible .ant-select-item-option')
            .filter({hasText: '自定义秒数'}).click();
        await page.getByLabel('自定义 Checkpoint 间隔').fill('15');
        await expect(page.getByLabel(`主键列 ${T_MAIN}`)).toHaveValue('id');
        await page.getByRole('button', {name: '下一步'}).click();
        await expect(page.getByText('全部通过', {exact: true})).toBeVisible({timeout: 30_000});
        await page.getByRole('button', {name: '保存', exact: true}).click();
        await expect(notice(page, /管道已保存（savepoint 已清空/)).toBeVisible();
        // API 辅助：描述更新 / savepoint 清空 / 修改时间写入（创建时按约定不写）
        const detail = await engineer.get<any>(`/realtime/cdc/pipelines/${mainPipelineId}`);
        expect(detail.description).toBe('e2e_s8 主链路验证管道（已编辑）');
        expect(detail.savepointPath ?? null).toBeNull();
        expect(detail.updatedAt).toBeTruthy();
        expect(detail.updatedByName).toBe(TEST_USERS.engineer.username);
        expect(JSON.parse(detail.configJson).checkpointIntervalSeconds).toBe(15);
    });

    test('UI 删除：级联清理 + 湖仓数据保留', async ({page}) => {
        const before = lakeCount(T_MAIN);
        expect(before).toBe(mainRows);
        await gotoAs(page, TEST_USERS.engineer.username, TEST_USERS.engineer.password, '/engineering/cdc-pipelines');
        await gotoListAndFind(page, P_MAIN);
        await pipelineRow(page, P_MAIN).getByLabel(`删除 ${P_MAIN}`).click();
        const dialog = page.getByRole('dialog', {name: '删除管道'});
        await expect(dialog.getByText(/级联删除表映射与运行日志/)).toBeVisible();
        await dialog.getByRole('button', {name: '删除', exact: true}).click();
        await expect(notice(page, `已删除管道「${P_MAIN}」`)).toBeVisible();
        await expect(pipelineRow(page, P_MAIN)).toHaveCount(0);
        // DB 级联：三表均无残留
        expect(psqlRt(`SELECT COUNT(*) FROM cdc_pipeline WHERE id = ${mainPipelineId}`)).toBe('0');
        expect(psqlRt(`SELECT COUNT(*) FROM cdc_pipeline_table WHERE pipeline_id = ${mainPipelineId}`)).toBe('0');
        expect(psqlRt(`SELECT COUNT(*) FROM cdc_pipeline_log WHERE pipeline_id = ${mainPipelineId}`)).toBe('0');
        // 湖仓数据保留不删（删除确认文案承诺）
        await engineer.get(`/realtime/cdc/pipelines/stats`); // 服务活性确认
        expect(lakeCount(T_MAIN)).toBe(mainRows);
    });
});

// ==================== E. 仅增量模式（LATEST_OFFSET） ====================

test.describe('E. 仅增量模式', () => {
    // 后端校验已于 2026-08-10 补齐（Sprint8 E2E 发现上报后修复）：仅增量 + INITIAL → 8000
    test('防御校验：仅增量 + INITIAL 被 8000 拦截（残留 INITIAL 会被当全量跑）', async () => {
        const invalid = await engineer.raw('POST', '/realtime/cdc/pipelines', {
            name: P_INCR, sourceDatasourceId: MYSQL_DS_ID, sourceDatabase: MYSQL_DB,
            targetDatabase: TARGET_DB, syncMode: 'INCREMENTAL_ONLY', startupMode: 'INITIAL',
            writeMode: 'UPSERT', tables: [{sourceTable: T_INCR, primaryKey: 'id'}],
        });
        // 防御性兜底：若异常地被创建出来，先清理避免污染后续用例
        if (invalid.code === 200) {
            await engineer.del(`/realtime/cdc/pipelines/${invalid.data.id}`);
        }
        expect(invalid.code).toBe(8000);
    });

    test('LATEST_OFFSET 不跑全量快照，只捕获启动后变更', async () => {
        const created = await engineer.post<any>('/realtime/cdc/pipelines', {
            name: P_INCR, sourceDatasourceId: MYSQL_DS_ID, sourceDatabase: MYSQL_DB,
            targetDatabase: TARGET_DB, syncMode: 'INCREMENTAL_ONLY', startupMode: 'LATEST_OFFSET',
            writeMode: 'UPSERT', tables: [{sourceTable: T_INCR, primaryKey: 'id'}], configJson: FAST_CKPT,
        });
        const id = created.id;
        await engineer.post(`/realtime/cdc/pipelines/${id}/start`);
        // 等作业真正进入 RUNNING（位点在 source 启动时确定，等 monitor 回写后位点已定）
        await expect.poll(async () =>
            (await engineer.get<any>(`/realtime/cdc/pipelines/${id}`)).status,
        {timeout: 90_000, intervals: [3_000]}).toBe('RUNNING');
        // 位点确定后再产生变更（表未创建/行数不足时持续补插，消除启动位点竞态，封顶 12 轮）
        mysqlT(`INSERT INTO ${T_INCR} (note) VALUES ('new_1'), ('new_2')`);
        let retries = 0;
        await expect.poll(async () => {
            let c: number | null;
            try {
                await engineer.get(`/realtime/cdc/pipelines/${id}/refresh-catalog`);
            } catch { /* 下轮重试 */ }
            c = lakeCount(T_INCR);
            if ((c === null || c < 2) && retries < 12) {
                retries += 1;
                mysqlT(`INSERT INTO ${T_INCR} (note) VALUES ('new_retry_${retries}')`);
            }
            return c;
        }, {timeout: 180_000, intervals: [8_000]}).toBeGreaterThanOrEqual(2);
        // 存量 3 行未被同步（未跑全量快照）
        const notes = doris(`SELECT note FROM datalake_catalog.${TARGET_DB}.${T_INCR}`) ?? '';
        expect(notes).not.toContain('old_1');
        expect(notes).not.toContain('old_2');
        expect(notes).not.toContain('old_3');

        await engineer.post(`/realtime/cdc/pipelines/${id}/stop`);
        await engineer.del(`/realtime/cdc/pipelines/${id}`);
    });
});

// ==================== F. PostgreSQL 源全链路 ====================

test.describe('F. PostgreSQL 源', () => {
    test('PG 管道：WAL 快照 + 增量 insert/update/delete 全链路', async () => {
        const created = await engineer.post<any>('/realtime/cdc/pipelines', {
            name: P_PG, sourceDatasourceId: PG_DS_ID, sourceDatabase: PG_DB,
            targetDatabase: TARGET_DB, syncMode: 'FULL_AND_INCREMENT', startupMode: 'INITIAL',
            writeMode: 'UPSERT', tables: [{sourceTable: T_PG, primaryKey: 'id'}], configJson: FAST_CKPT,
        });
        const id = created.id;
        await engineer.post(`/realtime/cdc/pipelines/${id}/start`);
        await expect.poll(async () =>
            (await engineer.get<any>(`/realtime/cdc/pipelines/${id}`)).status,
        {timeout: 90_000, intervals: [3_000]}).toBe('RUNNING');
        // initial 快照 2 行落湖
        await expectLakeCount(engineer, id, T_PG, 2);
        // 增量：insert 1 + update 1 + delete 1（REPLICA IDENTITY FULL 保障 before 镜像）
        pgT(`INSERT INTO ${T_PG} (id, name) VALUES (3, 'pg_incr_c');
             UPDATE ${T_PG} SET name = 'pg_seed_a_v2' WHERE id = 1;
             DELETE FROM ${T_PG} WHERE id = 2;`);
        // upsert 语义（主键合并）：最终 id ∈ {1,3}，id=1 名称已更新，id=2 已删
        await expectLakeCount(engineer, id, T_PG, 2);
        // 值级断言同样走 refresh 轮询（count 命中的快照可能先于 update 提交一拍）
        await expect.poll(async () => {
            try {
                await engineer.get(`/realtime/cdc/pipelines/${id}/refresh-catalog`);
            } catch { /* 下轮重试 */ }
            return doris(`SELECT name FROM datalake_catalog.${TARGET_DB}.${T_PG} WHERE id = 1`);
        }, {timeout: 90_000, intervals: [8_000]}).toBe('pg_seed_a_v2');
        // 主键无重复（非 append 双写）
        expect(doris(`SELECT COUNT(*) FROM datalake_catalog.${TARGET_DB}.${T_PG} WHERE id = 1`)).toBe('1');
        expect(doris(`SELECT COUNT(*) FROM datalake_catalog.${TARGET_DB}.${T_PG} WHERE id = 2`)).toBe('0');

        await engineer.post(`/realtime/cdc/pipelines/${id}/stop`);
        await engineer.del(`/realtime/cdc/pipelines/${id}`);
        // 删除级联清理 PG 复制槽（2026-08-11 E2E 发现槽泄漏打满 max_wal_senders 致新作业无法启动）
        await expect.poll(() =>
                pgT(`SELECT COUNT(*) FROM pg_replication_slots WHERE slot_name = 'datanest_cdc_${id}'`),
            {timeout: 15_000, intervals: [1_000]}).toBe('0');
    });

    test('向导 UI：PG 源仅增量时「从最早」位点禁用 + 后端 8000 兜底', async ({page}) => {
        // 后端兜底：PG + EARLIEST_OFFSET → 8000
        const invalid = await engineer.raw('POST', '/realtime/cdc/pipelines', {
            name: P_PG, sourceDatasourceId: PG_DS_ID, sourceDatabase: PG_DB,
            targetDatabase: TARGET_DB, syncMode: 'INCREMENTAL_ONLY', startupMode: 'EARLIEST_OFFSET',
            writeMode: 'APPEND', tables: [{sourceTable: T_PG}],
        });
        expect(invalid.code).toBe(8000);

        await gotoAs(page, TEST_USERS.engineer.username, TEST_USERS.engineer.password, '/engineering/cdc-pipelines/new');
        await page.getByPlaceholder('例如：订单实时同步').fill('e2e_s8_pg_probe');
        await page.getByRole('button', {name: '下一步'}).click();
        await page.getByLabel('源数据源').click();
        await page.locator('.ant-select-dropdown:visible .ant-select-item-option')
            .filter({hasText: 'middleware-test-postgres'}).first().click();
        // 切仅增量 → PG 源「从最早」禁用
        await page.getByText('仅增量', {exact: true}).click();
        const earliest = page.getByRole('button', {name: /从最早/});
        await expect(earliest).toBeDisabled();
        await expect(page.getByText('PostgreSQL connector 不支持该位点')).toBeVisible();
        // 不保存，直接离开（避免污染）
        await page.getByRole('button', {name: '取消'}).click();
    });
});

// ==================== G. 引用校验 / 参数校验 / 统计一致性 ====================

test.describe('G. 引用与参数校验', () => {
    test('删除数据源被 CDC 管道引用拦截（8009 fail-closed）', async () => {
        // 临时数据源（SQL 复制真实 mysql 连接信息，密码随密文复制）
        psqlEng(`INSERT INTO datasource_connection
                 (id, name, type, host, port, database_name, schema_name, username, encrypted_password, status, created_at, updated_at, auto_collect_on_save)
                 SELECT ${TMP_DS_ID}, '${TMP_DS_NAME}', type, host, port, database_name, schema_name, username, encrypted_password, 'NORMAL', now(), now(), 0
                 FROM datasource_connection WHERE id = ${MYSQL_DS_ID}`);
        // 建一个引用该数据源的管道（不启动）
        const created = await engineer.post<any>('/realtime/cdc/pipelines', {
            name: P_GUARD, sourceDatasourceId: TMP_DS_ID, sourceDatabase: MYSQL_DB,
            targetDatabase: TARGET_DB, syncMode: 'FULL_AND_INCREMENT', startupMode: 'INITIAL',
            writeMode: 'APPEND', tables: [{sourceTable: T_MAIN}],
        });
        // 删除数据源 → 8009（fail-closed，提示含管道名）
        const blocked = await engineer.raw('DELETE', `/engineering/datasources/${TMP_DS_ID}`);
        expect(blocked.code).toBe(8009);
        expect(blocked.message).toContain(P_GUARD);
        // 删管道后 → 可删
        await engineer.del(`/realtime/cdc/pipelines/${created.id}`);
        const ok = await engineer.raw('DELETE', `/engineering/datasources/${TMP_DS_ID}`);
        expect(ok.code).toBe(200);
        expect(psqlEng(`SELECT COUNT(*) FROM datasource_connection WHERE id = ${TMP_DS_ID}`)).toBe('0');
    });

    test('参数校验：重名 8002 / UPSERT 缺主键 8000 / 并行度越界 8000 / 不存在 8001', async () => {
        const base = {
            name: P_GUARD, sourceDatasourceId: MYSQL_DS_ID, sourceDatabase: MYSQL_DB,
            targetDatabase: TARGET_DB, syncMode: 'FULL_AND_INCREMENT', startupMode: 'INITIAL',
            writeMode: 'UPSERT', tables: [{sourceTable: T_MAIN, primaryKey: 'id'}],
        };
        const created = await engineer.post<any>('/realtime/cdc/pipelines', base);
        // 重名 → 8002
        expect((await engineer.raw('POST', '/realtime/cdc/pipelines', base)).code).toBe(8002);
        // UPSERT 缺主键 → 8000
        expect((await engineer.raw('POST', '/realtime/cdc/pipelines', {
            ...base, name: 'e2e_s8_no_pk', tables: [{sourceTable: T_MAIN}],
        })).code).toBe(8000);
        // 并行度越界 → 8000
        expect((await engineer.raw('POST', '/realtime/cdc/pipelines', {
            ...base, name: 'e2e_s8_bad_par', configJson: JSON.stringify({parallelism: 9}),
        })).code).toBe(8000);
        // Checkpoint 间隔 < 3 → 8000
        expect((await engineer.raw('POST', '/realtime/cdc/pipelines', {
            ...base, name: 'e2e_s8_bad_ckpt', configJson: JSON.stringify({checkpointIntervalSeconds: 2}),
        })).code).toBe(8000);
        // 不存在管道 → 8001
        expect((await engineer.raw('GET', '/realtime/cdc/pipelines/999999999999')).code).toBe(8001);
        await engineer.del(`/realtime/cdc/pipelines/${created.id}`);
    });

    test('stats 与分页列表一致性', async () => {
        const stats = await engineer.get<any>('/realtime/cdc/pipelines/stats');
        const page1 = await engineer.get<any>('/realtime/cdc/pipelines/page?page=1&pageSize=50');
        const records = page1.records ?? [];
        const count = (s: string) => records.filter((r: any) => r.status === s).length;
        expect(Number(stats.running)).toBe(count('RUNNING'));
        expect(Number(stats.stopped)).toBe(count('STOPPED'));
        expect(Number(stats.error)).toBe(count('ERROR'));
        // 已同步表 = cdc_pipeline_table 总行数
        expect(Number(stats.syncedTables)).toBe(Number(psqlRt('SELECT COUNT(*) FROM cdc_pipeline_table')));
    });
});
