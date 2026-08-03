import type {SyncTriggerType} from '../types/sync';

/**
 * 触发方式徽标（手动触发 / DAG 编排 / 定时触发）。Phase 7-K2：
 * 收敛 history-common-utils 与 collect/sync 历史页里 3~4 份手抄漂移色副本
 * （bg-blue-50/700、bg-violet-50/700、bg-slate-100/text-blue-600 多档不一致），
 * 配色统一走 ds token 语义色。
 */
const STYLES: Record<SyncTriggerType, string> = {
    MANUAL: 'bg-ds-accent-light text-ds-accent',
    DAG: 'bg-ds-type-condition-light text-ds-type-condition',
    CRON: 'bg-ds-bg-hover text-ds-text-secondary',
};

const LABELS: Record<SyncTriggerType, string> = {
    MANUAL: '手动触发',
    DAG: 'DAG 编排',
    CRON: '定时触发',
};

export default function TriggerBadge({type}: { type: SyncTriggerType | string }) {
    const normalized = (type as SyncTriggerType) || 'CRON';
    return (
        <span
            className={`inline-flex items-center px-2.5 py-1 rounded-full text-ds-badge whitespace-nowrap ${STYLES[normalized] ?? STYLES.CRON}`}>
            {LABELS[normalized] ?? LABELS.CRON}
        </span>
    );
}
