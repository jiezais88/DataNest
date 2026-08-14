import type {ReactNode} from 'react';
import {cn} from '@/utils/cn';

/**
 * 表单通用 select（Sprint 11 下拉统一收编产物，2026-08-14）。
 *
 * 与 DsFilterSelect（筛选工具栏变体）共用同一套样式语言；本组件面向「表单 / 内联」场景：
 * - 保留原生 option 渲染（支持自定义 option，含 data-testid / 动态生成）
 * - 默认 w-full 表单尺寸，className 可覆盖为紧凑/自定义尺寸（tailwind-merge 安全合并，见 utils/cn）
 * - 保留浏览器原生下拉箭头（表单场景箭头即指示器，无需自绘 chevron；筛选工具栏才用 chevron）
 *
 * 选型规则（docs/agent/shared-code-governance.md）：
 * 列表筛选 → DsFilterSelect；表单简单枚举 → 本组件；搜索/多选/异步 → antd Select；禁止裸 select。
 */
export interface DsSelectProps {
    value: string;
    onChange: (value: string) => void;
    disabled?: boolean;
    /** 覆盖默认尺寸（如紧凑场景传 min-w-[80px] px-ds-2 py-ds-1 text-ds-small） */
    className?: string;
    'aria-label'?: string;
    'data-testid'?: string;
    children: ReactNode;
}

const BASE_CLASS =
    'bg-white border border-ds-border-subtle rounded-ds-sm text-ds-text-primary ' +
    'focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors ' +
    'disabled:opacity-60 disabled:cursor-not-allowed cursor-pointer ' +
    'px-ds-3 py-ds-2 text-ds-body w-full';

export default function DsSelect({value, onChange, disabled, className, children, ...rest}: DsSelectProps) {
    return (
        <select
            value={value}
            onChange={(e) => onChange(e.target.value)}
            disabled={disabled}
            className={cn(BASE_CLASS, className)}
            {...rest}
        >
            {children}
        </select>
    );
}
