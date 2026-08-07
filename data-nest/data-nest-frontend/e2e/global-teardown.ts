import {cleanupAll as cleanupS5} from './sprint5/helpers/seed';
import {cleanupAll as cleanupS6} from './sprint6/helpers/seed';
import {cleanupAll as cleanupS7} from './sprint7/helpers/seed';

/**
 * Playwright 统一 globalTeardown：同时清理 Sprint 5 + Sprint 6 + Sprint 7 测试数据（均幂等）。
 */
export default async function globalTeardown(): Promise<void> {
    if (process.env.SKIP_SETUP === '1') {
        console.log('[teardown] SKIP_SETUP=1，跳过清理');
        return;
    }
    console.log('[teardown] 开始清理 Sprint5 + Sprint6 + Sprint7 测试数据...');
    await cleanupS5();
    await cleanupS6();
    await cleanupS7();
    console.log('[teardown] 清理完成');
}
