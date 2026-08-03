import {seedAll} from './helpers/seed';

/** Playwright globalSetup：测试开始前播种全部 Sprint 5 测试数据（幂等） */
export default async function globalSetup(): Promise<void> {
    if (process.env.SKIP_SETUP === '1') {
        console.log('[sprint5-setup] SKIP_SETUP=1，跳过播种');
        return;
    }
    console.log('[sprint5-setup] 开始播种测试数据...');
    await seedAll();
    console.log('[sprint5-setup] 播种完成');
}
