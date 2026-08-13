// Sprint 8 F2：CDC 管道页面共用小组件——状态徽章、延迟展示（列表页 / 详情抽屉共用，就近放在页面目录）。
import DsStatusBadge, {type DsStatusVariant} from '@/components/DsStatusBadge';
import type {CdcPipelineStatus} from '@/types/cdc';

/** 管道状态徽章 */
export function CdcStatusBadge({status}: { status: CdcPipelineStatus }) {
    if (status === 'RUNNING') return <DsStatusBadge variant="running" label="运行中"/>;
    if (status === 'ERROR') return <DsStatusBadge variant="danger" label="异常"/>;
    return <DsStatusBadge variant="pending" label="已停止"/>;
}

/** 延迟格式化：≤30s 正常色，>30s 标红（PRD §6.6.2，考虑 Iceberg commit + Doris 刷新延迟放宽） */
export function LagValue({seconds}: { seconds?: number }) {
    if (seconds == null || seconds < 0) return <span className="text-ds-small text-ds-text-muted">—</span>;
    const text = seconds < 60
        ? `${seconds} 秒`
        : seconds < 3600
            ? `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒`
            : `${Math.floor(seconds / 3600)} 小时 ${Math.floor((seconds % 3600) / 60)} 分`;
    return (
        <span className={`text-ds-small ${seconds > 30 ? 'text-ds-danger font-semibold' : 'text-ds-success'}`}>
            {text}
        </span>
    );
}

/** KPI 卡（运行监控 / 实时订阅页签共用）：数字最大、单位小字、状态独立徽标 */
export function KpiCard({label, value, unit, sub, danger, status}: {
    label: string;
    value: string;
    unit?: string;
    sub?: string;
    danger?: boolean;
    /** 状态徽标（如「已停止」），可选 */
    status?: {label: string; variant: DsStatusVariant};
}) {
    return (
        <div className="bg-ds-bg-surface border border-ds-border-subtle rounded-ds-md p-ds-3 flex-1 min-w-0">
            <div className="flex items-center gap-ds-2 mb-ds-1 min-w-0">
                <span className="text-ds-nano text-ds-text-muted truncate">{label}</span>
                {status && <DsStatusBadge label={status.label} variant={status.variant}/>}
            </div>
            <div className="flex items-baseline gap-1 min-w-0">
                <span className={`text-ds-heading font-bold leading-none tabular-nums ${danger ? 'text-ds-danger' : 'text-ds-text-primary'}`}>
                    {value}
                </span>
                {unit && <span className="text-ds-small text-ds-text-muted font-normal">{unit}</span>}
            </div>
            {sub && <div className="text-ds-nano text-ds-text-muted mt-ds-1 truncate">{sub}</div>}
        </div>
    );
}
