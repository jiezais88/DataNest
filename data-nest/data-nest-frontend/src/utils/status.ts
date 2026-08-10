import type {DsStatusVariant} from '@/components/DsStatusBadge';

/**
 * 后端执行状态字符串 → DsStatusBadge variant 的统一映射。
 * 历史背景：同样的归一逻辑曾在 dags/project.tsx（mapStatus）、
 * dag-executions/index.tsx（statusToBadgeVariant）和 6 份 statusClass 里各写一遍。
 */
export function executionStatusVariant(status?: string): DsStatusVariant {
    if (!status) return 'pending';
    switch (status.toUpperCase()) {
        case 'SUCCESS':
        case 'SUCCEEDED':
        case 'FINISHED':
            return 'success';
        case 'FAILED':
        case 'FAILURE':
        case 'ERROR':
            return 'danger';
        case 'RUNNING':
        case 'IN_PROGRESS':
        case 'EXECUTING':
            return 'running';
        case 'TERMINATED':
            return 'danger';
        case 'STOPPED':
        case 'STOP':
        case 'KILLED':
        case 'PARTIAL':
            return 'warning';
        case 'SKIPPED':
            return 'disabled';
        default:
            return 'pending';
    }
}
