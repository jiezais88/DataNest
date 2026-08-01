import {HiChevronRight} from 'react-icons/hi2';

/**
 * 工具栏筛选下拉。历史背景：11 个页面各手写一遍
 * appearance-none 原生 select + 绝对定位 chevron 的 hack，类名逐字符相同。收敛到这里。
 * 需要紧凑尺寸（如 Pagination 内部）时用 className 覆盖。
 */
export interface DsFilterSelectOption {
    value: string;
    label: string;
}

export interface DsFilterSelectProps {
    value: string;
    onChange: (value: string) => void;
    options: DsFilterSelectOption[];
    'aria-label': string;
    disabled?: boolean;
    /** 追加到 select 上的类（宽度/尺寸覆盖） */
    className?: string;
}

export default function DsFilterSelect({
                                           value,
                                           onChange,
                                           options,
                                           disabled,
                                           className = '',
                                           ...rest
                                       }: DsFilterSelectProps) {
    return (
        <div className="relative">
            <select
                value={value}
                onChange={(e) => onChange(e.target.value)}
                disabled={disabled}
                className={`appearance-none min-w-[140px] pl-ds-3 pr-9 py-ds-2 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-small text-ds-text-primary focus:outline-none focus-visible:border-ds-accent cursor-pointer disabled:opacity-60 disabled:cursor-not-allowed ${className}`.trim()}
                {...rest}
            >
                {options.map((o) => (
                    <option key={o.value} value={o.value}>{o.label}</option>
                ))}
            </select>
            <HiChevronRight
                size={14}
                className="absolute right-3 top-1/2 -translate-y-1/2 rotate-90 text-ds-text-muted pointer-events-none"
            />
        </div>
    );
}
