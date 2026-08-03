import {cleanupAll} from './helpers/seed';

/** Playwright globalTeardown：测试结束后清理全部 Sprint 5 测试数据（幂等） */
export default async function globalTeardown(): Promise<void> {
    if (process.env.SKIP_SETUP === '1') {
        console.log('[sprint5-teardown] SKIP_SETUP=1，跳过清理');
        return;
    }
    console.log('[sprint5-teardown] 开始清理测试数据...');
    await cleanupAll();
    console.log('[sprint5-teardown] 清理完成');
}
