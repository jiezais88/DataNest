import {type ButtonHTMLAttributes, forwardRef, useEffect, useRef, useState, type ReactNode} from 'react';
import DsSpinner from './DsSpinner';

/**
 * 全局统一按钮。历史背景：主/次按钮的 className 曾在 40+ 处复制粘贴并出现微变异
 * （disabled:opacity-50 vs 60、gap-ds-1 vs gap-1.5、hover 行为两种），
 * 现在所有变体收敛到这里，页面只传 variant。
 */
type Variant = 'primary' | 'secondary' | 'danger' | 'ghost';

const VARIANT_CLASS: Record<Variant, string> = {
    primary: 'bg-ds-accent hover:bg-ds-accent-hover text-white',
    secondary: 'bg-white border border-ds-border-subtle hover:border-ds-border-strong text-ds-text-secondary',
    danger: 'bg-ds-danger hover:bg-ds-danger-hover text-white',
    ghost: 'text-ds-text-secondary hover:bg-ds-bg-hover',
};

const BASE_CLASS =
    'inline-grid grid-cols-1 grid-rows-1 place-items-center gap-0 whitespace-nowrap px-ds-4 py-ds-2 text-ds-small font-semibold rounded-ds-sm transition-[color,background-color,border-color,box-shadow,opacity] duration-ds-fast disabled:opacity-60 disabled:cursor-not-allowed';

/** spinner 延迟显示阈值：请求在该时长内返回则不出现转圈，避免快查询"闪一下"（对齐主流组件库行为） */
const SPINNER_DELAY_MS = 200;

export interface DsButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
    variant?: Variant;
    /** 加载中：延迟约 200ms 显示 spinner（快请求不闪转圈），按钮宽度不变、相邻按钮不抖动 */
    loading?: boolean;
    children: ReactNode;
}

// antd Tooltip 需要通过 ref 拿到触发元素 DOM 来定位浮层，必须 forwardRef
const DsButton = forwardRef<HTMLButtonElement, DsButtonProps>(function DsButton({
                                                                                    variant = 'primary',
                                                                                    className = '',
                                                                                    type = 'button',
                                                                                    loading = false,
                                                                                    children,
                                                                                    ...rest
                                                                                }, ref) {
    const [spinnerOn, setSpinnerOn] = useState(false);
    const timer = useRef<number | undefined>(undefined);

    useEffect(() => {
        if (loading) {
            timer.current = window.setTimeout(() => setSpinnerOn(true), SPINNER_DELAY_MS);
        } else {
            if (timer.current != null) window.clearTimeout(timer.current);
            setSpinnerOn(false);
        }
        return () => {
            if (timer.current != null) window.clearTimeout(timer.current);
        };
    }, [loading]);

    const showSpinner = loading && spinnerOn;

    // Grid 同格叠放：children 与 spinner 永远占同一格（grid-area 1/1），
    // 切换仅改 visibility，DOM 恒定、零 reflow → 按钮宽度恒等于 children 自然宽度，
    // 不因 spinner 占位加宽，也不会有图标+文字换行。
    return (
        <button ref={ref} type="button" aria-busy={loading || undefined}
                className={`${BASE_CLASS} ${VARIANT_CLASS[variant]} ${className}`.trim()} {...rest}>
            <span className={`[grid-area:1/1] inline-flex items-center gap-ds-1 whitespace-nowrap ${showSpinner ? 'invisible' : 'visible'}`}>
                {children}
            </span>
            <span className={`[grid-area:1/1] inline-flex items-center justify-center ${showSpinner ? 'visible' : 'invisible'}`}>
                <DsSpinner size={14}/>
            </span>
        </button>
    );
});

export default DsButton;
