import {type ButtonHTMLAttributes, forwardRef, type ReactNode} from 'react';

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
    'inline-flex items-center justify-center gap-ds-1 px-ds-4 py-ds-2 text-ds-small font-semibold rounded-ds-sm transition-colors duration-ds-fast disabled:opacity-60 disabled:cursor-not-allowed';

export interface DsButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
    variant?: Variant;
    children: ReactNode;
}

// antd Tooltip 需要通过 ref 拿到触发元素 DOM 来定位浮层，必须 forwardRef
const DsButton = forwardRef<HTMLButtonElement, DsButtonProps>(function DsButton({
                                                                                    variant = 'primary',
                                                                                    className = '',
                                                                                    type = 'button',
                                                                                    children,
                                                                                    ...rest
                                                                                }, ref) {
    return (
        <button ref={ref} type={type}
                className={`${BASE_CLASS} ${VARIANT_CLASS[variant]} ${className}`.trim()} {...rest}>
            {children}
        </button>
    );
});

export default DsButton;
