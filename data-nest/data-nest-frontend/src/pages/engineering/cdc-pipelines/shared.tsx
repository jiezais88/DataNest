// Sprint 8 F2：CDC 管道页面共用小组件——状态徽章、延迟展示（列表页 / 详情抽屉共用，就近放在页面目录）。
import DsStatusBadge from '@/components/DsStatusBadge';
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
