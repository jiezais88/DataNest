import {seedAll as seedS5} from './sprint5/helpers/seed';
import {seedAll as seedS6} from './sprint6/helpers/seed';
import {seedAll as seedS7} from './sprint7/helpers/seed';

/**
 * Playwright 统一 globalSetup：同时播种 Sprint 5 + Sprint 6 + Sprint 7 测试数据（均幂等）。
 * testDir 指向 e2e 根目录，各 sprint 的 spec 一起跑，共用一套 setup/teardown。
 * 拆库适配（2026-08-07 起）：Sprint 5/6 的 seed 经 helpers/db.ts 的表路由写入拆库后的
 * 新库（datanest_system / datanest_alert / datanest_engineering / datanest_governance），
 * 不再写已冻结的旧 datanest 库。
 */
export default async function globalSetup(): Promise<void> {
    if (process.env.SKIP_SETUP === '1') {
        console.log('[setup] SKIP_SETUP=1，跳过播种');
        return;
    }
    console.log('[setup] 开始播种 Sprint5 + Sprint6 + Sprint7 测试数据...');
    await seedS5();
    await seedS6();
    await seedS7();
    console.log('[setup] 播种完成');
}
