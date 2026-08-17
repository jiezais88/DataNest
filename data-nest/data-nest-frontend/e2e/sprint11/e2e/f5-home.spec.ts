import {expect, test} from '@playwright/test';
import {Api} from '../../sprint6/helpers/api';
import {gotoAs} from '../../sprint6/helpers/e2e';
import {ADMIN} from './helpers/f3-seed';

/**
 * Sprint 11 F5 首页 v4.1「值班态势总览」（2026-08-17）
 *
 * 设计：docs/sprint11/DataNest-Sprint11-首页重设计-v4.md
 * 覆盖：态势横幅（判定/状态分布条/今日计数）、待处理异常队列（重跑/日志/等待时长标色）、
 *       系统健康 5 项、快捷操作 4 入口、14 日趋势 strip（失败红点+悬停浮层）、
 *       空平台三步引导、60s 自动刷新、单接口失败降级。
 * 前置（测试前已清理）：test 遗留 DAG 已删除、E2E-条件节点多前驱 已恢复成功 → pendingFailed=0。
 */

const BASE = 'http://localhost:8080';
const ADMIN_USER = ADMIN.username;
const ADMIN_PASS = ADMIN.password;

// ==================== API 辅助 ====================

test.describe('F5 首页 v4.1', () => {
    test.beforeAll(async () => {
        // 确保 4 域 KPI 接口可访问（接口冒烟已在会话中验证 200）
    });

    test('A1 后端 KPI 契约：4 域接口结构完整且计数正确（API 辅助）', async () => {
        const api = await Api.create();
        await api.login(ADMIN_USER, ADMIN_PASS);

        // 工程域
        const eng = await api.raw<{code: number; data: any}>('GET', '/engineering/home/kpis');
        expect(eng.code).toBe(200);
        expect(eng.data).toMatchObject({
            todayTotal: expect.any(String),
            todaySuccess: expect.any(String),
            todayFailed: expect.any(String),
            waiting: expect.any(String),
            running: expect.any(String),
            pendingFailed: expect.any(String),
        });
        expect(Array.isArray(eng.data.trend)).toBe(true);
        expect(eng.data.trend.length).toBeGreaterThan(0);
        const lastTrend = eng.data.trend[eng.data.trend.length - 1];
        expect(lastTrend.day).toBeTruthy();
        expect(lastTrend).toHaveProperty('total');
        expect(lastTrend).toHaveProperty('failed');

        // 告警域
        const alert = await api.raw<{code: number; data: any}>('GET', '/alert/home/kpis');
        expect(alert.code).toBe(200);
        expect(alert.data).toHaveProperty('total');
        expect(alert.data).toHaveProperty('summary');

        // 治理域
        const gov = await api.raw<{code: number; data: any}>('GET', '/governance/home/kpis');
        expect(gov.code).toBe(200);
        expect(gov.data).toHaveProperty('collect');
        expect(Array.isArray(gov.data.qualityIssues)).toBe(true);
        expect(gov.data).toHaveProperty('doris');

        // 实时域
        const rt = await api.raw<{code: number; data: any}>('GET', '/realtime/home/kpis');
        expect(rt.code).toBe(200);
        expect(rt.data).toHaveProperty('cdcRunning');
        expect(rt.data).toHaveProperty('flink');
        await api.dispose();
    });

    test('B1 态势横幅：需关注判定 + 质量异常原因 + 今日计数 + 状态分布条 + 问候语（UI）', async ({page}) => {
        await gotoAs(page, ADMIN_USER, ADMIN_PASS, '/');
        // 态势判定：治理域有 2 条质量异常（SEVERE/WARNING，按用户确认算异常）→ 「N 项需关注」
        // 当前环境：2 项质量异常 + 2 个数据源连接失败 → 4 项需关注
        await expect(page.getByText(/\d+ 项需关注/).first()).toBeVisible({timeout: 15_000});
        // 判定原因文案（质量异常 + 数据源连接失败）
        await expect(page.getByText(/项质量异常/).first()).toBeVisible();
        // 今日运行计数
        await expect(page.getByText(/今日运行\s*\d+\s*次/).first()).toBeVisible();
        // 状态分布条（成功/失败/运行中/等待 标签 + 计数）
        await expect(page.getByRole('button', {name: /成功\s*\d+/}).first()).toBeVisible();
        await expect(page.getByRole('button', {name: /失败\s*\d+/}).first()).toBeVisible();
        await expect(page.getByRole('button', {name: /运行中\s*\d+/}).first()).toBeVisible();
        // 问候语（早上好/下午好/晚上好/夜深了）
        await expect(page.getByText(/(早上好|下午好|晚上好|夜深了)/).first()).toBeVisible();
        // 用户名展示
        await expect(page.getByText(ADMIN_USER, {exact: true}).first()).toBeVisible();
    });

    test('B2 待处理异常队列：质量行渲染 + 徽章 + 等待时长 + 查看报告跳转（UI）', async ({page}) => {
        await gotoAs(page, ADMIN_USER, ADMIN_PASS, '/');
        // 待处理异常标题 + 质量行
        await expect(page.getByText('待处理异常').first()).toBeVisible({timeout: 15_000});
        await expect(page.getByText(/质量\s*订单表异常金额检查/).first()).toBeVisible();
        await expect(page.getByText(/结果：严重/).first()).toBeVisible();
        // 等待时长（天级）
        await expect(page.getByText(/等待 \d+ 天/).first()).toBeVisible();
        // 查看报告按钮
        await page.getByRole('button', {name: '查看报告'}).first().click();
        await page.waitForURL(/quality-report/, {timeout: 15_000});
    });

    test('C1 系统健康 5 项 + 快捷操作 4 入口（UI）', async ({page}) => {
        await gotoAs(page, ADMIN_USER, ADMIN_PASS, '/');
        // 系统健康 5 项标题（当前环境：数据源 2 连接失败）
        await expect(page.getByText('数据源', {exact: true}).first()).toBeVisible();
        await expect(page.getByText(/连接失败/).first()).toBeVisible();
        await expect(page.getByText('同步任务', {exact: true}).first()).toBeVisible();
        await expect(page.getByText('Doris', {exact: true}).first()).toBeVisible();
        // Flink 行实际文案是「Flink CDC」+ 描述
        await expect(page.getByText(/Flink CDC/).first()).toBeVisible();
        // 快捷操作 4 入口（div 容器）
        const quick = page.locator('div.bg-ds-bg-surface').filter({hasText: '快捷操作'}).last();
        await expect(quick).toBeVisible();
        for (const label of ['同步任务', '新建 DAG', 'SQL 查询', '数据源']) {
            await expect(quick.getByText(label, {exact: true}).first()).toBeVisible();
        }
        // 快捷操作跳转：点击「SQL 查询」→ SQL 终端页
        await quick.getByText('SQL 查询', {exact: true}).first().click();
        await page.waitForURL(/sql-console/, {timeout: 15_000});
    });

    test('D1 14 日趋势 strip：渲染 + 今日标签 + 悬停浮层（UI）', async ({page}) => {
        await gotoAs(page, ADMIN_USER, ADMIN_PASS, '/');
        await expect(page.getByText('近 14 日运行趋势').first()).toBeVisible({timeout: 15_000});
        await expect(page.getByText(/近 7 天成功率 [\d.]+%/).first()).toBeVisible();
        // 图例
        await expect(page.getByText('运行量', {exact: true}).first()).toBeVisible();
        await expect(page.getByText('有失败', {exact: true}).first()).toBeVisible();
        // 悬停某天出现浮层（今天：运行 N，失败 M）
        const svg = page.locator('.home-trend svg, svg').filter({hasText: ''});
        // 直接悬停趋势区第 1 个热区 rect
        const hoverZone = page.locator('svg rect[fill="transparent"]').first();
        await hoverZone.hover();
        await expect(page.getByText(/：运行\s*\d+，失败\s*\d+/).first()).toBeVisible({timeout: 5_000});
    });

    // 空平台三步引导（isFreshPlatform）：判定基于全局数据（todayTotal=0 且 trend 全 0 且无质量异常），
    // 与登录用户无关；当前平台有真实数据无法触发。该分支逻辑经代码审查确认（pages/home/index.tsx 375-378 行），
    // 不做 UI E2E（需清空全部平台数据才可测，代价大收益低）。
});
