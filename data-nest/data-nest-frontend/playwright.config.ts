import {defineConfig} from '@playwright/test';

/**
 * Sprint 5 API + E2E 测试配置
 * - API 测试：直接请求 http://localhost:8080/api（gateway）
 * - E2E 测试：访问 http://localhost:3000（前端生产构建容器，nginx 代理 /api → gateway）
 * - 串行执行（workers=1），避免共享环境数据相互干扰
 */
export default defineConfig({
    testDir: './e2e/sprint5',
    timeout: 240_000,
    expect: {timeout: 15_000},
    fullyParallel: false,
    workers: 1,
    retries: 0,
    reporter: [['list']],
    globalSetup: './e2e/sprint5/global-setup.ts',
    globalTeardown: './e2e/sprint5/global-teardown.ts',
    use: {
        baseURL: 'http://localhost:3000',
        trace: 'retain-on-failure',
        screenshot: 'only-on-failure',
        video: 'retain-on-failure',
    },
    projects: [
        {name: 'chromium', use: {browserName: 'chromium'}},
    ],
});
