import {seedAll as seedS5} from './sprint5/helpers/seed';
import {seedAll as seedS6} from './sprint6/helpers/seed';

/**
 * Playwright 统一 globalSetup：同时播种 Sprint 5 + Sprint 6 测试数据（均幂等）。
 * testDir 指向 e2e 根目录，两个 sprint 的 spec 一起跑，共用一套 setup/teardown。
 */
export default async function globalSetup(): Promise<void> {
    if (process.env.SKIP_SETUP === '1') {
        console.log('[setup] SKIP_SETUP=1，跳过播种');
        return;
    }
    console.log('[setup] 开始播种 Sprint5 + Sprint6 测试数据...');
    await seedS5();
    await seedS6();
    console.log('[setup] 播种完成');
}
