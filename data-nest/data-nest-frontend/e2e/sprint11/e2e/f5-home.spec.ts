import {expect, test} from '@playwright/test';
import {Api} from '../../sprint6/helpers/api';
import {gotoAs} from '../../sprint6/helpers/e2e';
import {ADMIN} from './helpers/f3-seed';

/**
 * 首页 v5「运营仪表盘」（2026-08-17，Sprint 12）
 *
 * 设计：docs/sprint12/DataNest-首页重设计-v5.md（原型 DataNest-首页原型-v5.html）
 * 结构：R1 问候+状态徽标 / R2 统计卡×5（数据源/数据表/调度任务/数据API/待处理异常风险卡）/
 *       R3 运行态势（14 日面积图 + 卡头状态分段条）+ 系统健康 + 快捷操作 /
 *       R4 待处理异常 · 失败任务排行 · 最近运行 三栏
 * 环境：卸载重装后的 demo 环境（电商主题造数：待处理异常 1 条「订单质量核查（异常示例）」）。
 * 断言原则：结构断言 + 文案模式匹配为主，具体计数仅校验类型不校验值（环境数据会演进）。
 */

const ADMIN_USER = ADMIN.username;
const ADMIN_PASS = ADMIN.password;

test.describe('首页 v5 运营仪表盘', () => {

    test('A1 后端 KPI 契约：4 域接口 + v5 新增聚合字段（API）', async () => {
        const api = await Api.create();
        await api.login(ADMIN_USER, ADMIN_PASS);

        // 工程域：v4.1 字段 + v5 规模/排行/feed
        const eng = await api.raw<{code: number; data: any}>('GET', '/engineering/home/kpis');
        expect(eng.code).toBe(200);
        expect(eng.data).toMatchObject({
            todayTotal: expect.any(String),
            todaySuccess: expect.any(String),
            todayFailed: expect.any(String),
            running: expect.any(String),
            pendingFailed: expect.any(String),
            datasourceTotal: expect.any(String),
            taskTotal: expect.any(String),
        });
        expect(Array.isArray(eng.data.trend)).toBe(true);
        expect(eng.data.trend.length).toBe(14);
        expect(Array.isArray(eng.data.topFailures)).toBe(true);
        expect(Array.isArray(eng.data.recentRuns)).toBe(true);
        // v5 demo 环境：失败示例 DAG 应同时出现在待处理与排行中
        expect(eng.data.topFailures.length).toBeGreaterThan(0);
        expect(eng.data.recentRuns.length).toBeGreaterThan(0);

        // 治理域：v5 新增 assets（数据表规模）
        const gov = await api.raw<{code: number; data: any}>('GET', '/governance/home/kpis');
        expect(gov.code).toBe(200);
        expect(gov.data).toHaveProperty('collect');
        expect(gov.data).toHaveProperty('doris');
        expect(gov.data).toHaveProperty('assets');
        expect(gov.data.assets).toHaveProperty('tableTotal');
        expect(gov.data.assets).toHaveProperty('tableNew7d');

        // 告警域 / 实时域结构不变
        const alert = await api.raw<{code: number; data: any}>('GET', '/alert/home/kpis');
        expect(alert.code).toBe(200);
        expect(alert.data).toHaveProperty('total');
        const rt = await api.raw<{code: number; data: any}>('GET', '/realtime/home/kpis');
        expect(rt.code).toBe(200);
        expect(rt.data).toHaveProperty('flink');
        await api.dispose();
    });

    test('B1 R1+R2：问候/状态徽标/告警入口 + 统计卡×5（含风险卡高亮）（UI）', async ({page}) => {
        await gotoAs(page, ADMIN_USER, ADMIN_PASS, '/');
        // R1：问候语 + 用户名 + 状态徽标（正常/需关注/故障三态之一）
        await expect(page.getByText(/(早上好|下午好|晚上好|夜深了)/).first()).toBeVisible({timeout: 15_000});
        await expect(page.getByText(/(运行正常|\d+ 项需关注|\d+ 项故障|状态检查中)/).first()).toBeVisible();
        await expect(page.getByText(/24h 告警/).first()).toBeVisible();
        // R2 统计卡×5：标题齐全
        for (const label of ['数据源', '数据表', '调度任务', '数据 API', '待处理异常']) {
            await expect(page.getByText(label, {exact: true}).first()).toBeVisible();
        }
        // 风险卡副行（有异常=去处理；无异常=近 24 小时无异常）
        await expect(page.getByText(/(最早已等待|近 24 小时无异常)/).first()).toBeVisible();
        // 调度任务卡副行：今日已运行 N 次
        await expect(page.getByText(/今日已运行/).first()).toBeVisible();
    });

    test('B2 R4 三栏：待处理异常行 + 失败排行 + 最近运行 feed（UI）', async ({page}) => {
        await gotoAs(page, ADMIN_USER, ADMIN_PASS, '/');
        // 三栏卡头
        await expect(page.getByText('待处理异常').first()).toBeVisible({timeout: 15_000});
        await expect(page.getByText('失败任务排行').first()).toBeVisible();
        await expect(page.getByText('最近运行').first()).toBeVisible();
        // 异常行：demo 失败示例 DAG + 行内重跑 + 日志
        await expect(page.getByText('订单质量核查（异常示例）').first()).toBeVisible();
        await expect(page.getByText(/等待 (?:\d+ 分钟|\d+h|\d+ 天)/).first()).toBeVisible();
        await expect(page.getByRole('button', {name: '重跑'}).first()).toBeVisible();
        // 失败排行：第 1 名 + 失败次数
        await expect(page.getByText(/\d+ 次/).first()).toBeVisible();
        // 最近运行 feed：状态文案（成功/失败）
        await expect(page.getByText(/电商每日经营加工/).first()).toBeVisible();
    });

    test('C1 系统健康 5 项 + 快捷操作 4 入口 + SQL 查询跳转（UI）', async ({page}) => {
        await gotoAs(page, ADMIN_USER, ADMIN_PASS, '/');
        await expect(page.getByText('系统健康').first()).toBeVisible({timeout: 15_000});
        for (const label of ['数据源', '集成任务', 'Doris', '平台服务']) {
            await expect(page.getByText(label, {exact: true}).first()).toBeVisible();
        }
        await expect(page.getByText(/Flink CDC/).first()).toBeVisible();
        const quick = page.locator('div.bg-ds-bg-surface').filter({hasText: '快捷操作'}).last();
        await expect(quick).toBeVisible();
        for (const label of ['同步任务', '新建 DAG', 'SQL 查询', '数据源']) {
            await expect(quick.getByText(label, {exact: true}).first()).toBeVisible();
        }
        await quick.getByText('SQL 查询', {exact: true}).first().click();
        await page.waitForURL(/sql-console/, {timeout: 15_000});
    });

    test('D1 运行态势：趋势图 + 成功率 + 分段条 + 失败红点 + 悬停浮层（UI）', async ({page}) => {
        await gotoAs(page, ADMIN_USER, ADMIN_PASS, '/');
        await expect(page.getByText('运行态势').first()).toBeVisible({timeout: 15_000});
        await expect(page.getByText(/近 7 天成功率 [\d.]+%/).first()).toBeVisible();
        // 卡头分段条（成功/失败/运行中/等待 图例按钮）
        await expect(page.getByRole('button', {name: /成功\s*\d+/}).first()).toBeVisible();
        await expect(page.getByRole('button', {name: /失败\s*\d+/}).first()).toBeVisible();
        // 失败红点（HTML 圆点，v5 修复 SVG 拉伸变形后改为 span 渲染）
        await expect(page.locator('span.border-ds-danger.rounded-full').first()).toBeVisible();
        // 悬停浮层
        const hoverZone = page.locator('svg rect[fill="transparent"]').first();
        await hoverZone.hover();
        await expect(page.getByText(/：运行\s*\d+，失败\s*\d+/).first()).toBeVisible({timeout: 5_000});
    });

    // 空平台三步引导（isFreshPlatform）：判定基于全局数据，当前 demo 环境有真实数据无法触发，
    // 分支逻辑经代码审查确认（pages/home/index.tsx isFreshPlatform），不做 UI E2E。
});
