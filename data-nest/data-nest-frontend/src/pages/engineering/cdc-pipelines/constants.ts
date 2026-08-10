// Sprint 8 F2：CDC 管道模式枚举中文标签（列表详情抽屉 / 配置向导共用，就近放在页面目录）。
import type {CdcStartupMode, CdcSyncMode, CdcWriteMode} from '../../../types/cdc';

/** 同步模式中文标签 */
export const SYNC_MODE_LABEL: Record<CdcSyncMode, string> = {
    FULL_AND_INCREMENT: '全量 + 增量',
    INCREMENTAL_ONLY: '仅增量',
};
/** 启动位点中文标签 */
export const STARTUP_MODE_LABEL: Record<CdcStartupMode, string> = {
    INITIAL: '全量快照 + 增量',
    LATEST_OFFSET: '从最新位点',
    EARLIEST_OFFSET: '从最早位点',
};
/** 写入模式中文标签 */
export const WRITE_MODE_LABEL: Record<CdcWriteMode, string> = {
    UPSERT: 'Upsert（按主键）',
    APPEND: 'Append（仅追加）',
};
