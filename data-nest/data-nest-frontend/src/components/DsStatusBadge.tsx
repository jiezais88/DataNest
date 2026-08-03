/**
 * 全局统一状态徽标（capsule badge）。历史背景：dags/project.tsx 和
 * dag-executions/index.tsx 里有两份逐字符复制的 StatusBadge，另有 6 份
 * statusClass 函数各自返回 {bg, text, dot, label} 手写同款徽章，且混用
 * emerald/blue 裸色。现在全部收敛到这里，配色只走 ds token。
 *
 * 状态到 variant 的映射统一用 src/utils/status.ts 的 executionStatusVariant。
 */
export type DsStatusVariant = 'success' | 'danger' | 'warning' | 'running' | 'pending' | 'disabled' | 'accent';

const STYLES: Record<DsStatusVariant, string> = {
    success: 'bg-ds-success-light text-ds-success',
    danger: 'bg-ds-danger-light text-ds-danger',
    warning: 'bg-ds-warning-light text-ds-warning',
    running: 'bg-ds-accent-light text-ds-accent',
    pending: 'bg-ds-bg-hover text-ds-text-muted',
    disabled: 'bg-ds-bg-hover text-ds-text-muted',
    accent: 'bg-ds-accent-light text-ds-accent',
};

/** 无圆点的变体（纯标签用途） */
const NO_DOT: readonly DsStatusVariant[] = ['disabled', 'accent'];

interface DsStatusBadgeProps {
    label: string;
    variant: DsStatusVariant;
    /** 圆点呼吸闪烁，默认仅 running 变体开启 */
    pulse?: boolean;
}

export default function DsStatusBadge({label, variant, pulse}: DsStatusBadgeProps) {
    const showDot = !NO_DOT.includes(variant);
    const dotPulse = pulse ?? variant === 'running';
    return (
        <span
            className={`inline-flex items-center gap-1 px-2.5 py-1 rounded-full text-ds-badge whitespace-nowrap ${STYLES[variant]}`}>
            {showDot && (
                <span className={`w-1.5 h-1.5 rounded-full bg-current ${dotPulse ? 'animate-pulse' : ''}`}/>
            )}
            {label}
        </span>
    );
}
