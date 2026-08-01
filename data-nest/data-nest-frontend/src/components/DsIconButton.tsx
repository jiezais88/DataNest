import {type ButtonHTMLAttributes, forwardRef, type ReactNode} from 'react';

/**
 * 操作列/工具栏的图标按钮。hover 色调按语义传 tone：
 * accent=编辑/查看类，danger=删除/禁用类，success=启用类，default=中性。
 * 需要悬浮说明时用 antd Tooltip 包裹本组件。
 */
type Tone = 'default' | 'accent' | 'danger' | 'success';

const TONE_HOVER: Record<Tone, string> = {
    default: 'hover:text-ds-text-primary hover:bg-ds-bg-hover',
    accent: 'hover:text-ds-accent hover:bg-ds-accent-light',
    danger: 'hover:text-ds-danger hover:bg-ds-danger-light',
    success: 'hover:text-ds-success hover:bg-ds-success-light',
};

/** active 时的常驻文字色（hover 色保持一致，形成"已开启"双态效果） */
const TONE_ACTIVE_TEXT: Record<Tone, string> = {
    default: 'text-ds-text-primary',
    accent: 'text-ds-accent',
    danger: 'text-ds-danger',
    success: 'text-ds-success',
};

export interface DsIconButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
    tone?: Tone;
    /** 双态按钮的"开启"态：常驻 tone 色（如调度开关已启用） */
    active?: boolean;
    children: ReactNode;
}

// antd Tooltip 需要通过 ref 拿到触发元素 DOM 来定位浮层，必须 forwardRef
const DsIconButton = forwardRef<HTMLButtonElement, DsIconButtonProps>(function DsIconButton({
                                                                                                tone = 'default',
                                                                                                active = false,
                                                                                                className = '',
                                                                                                type = 'button',
                                                                                                children,
                                                                                                ...rest
                                                                                            }, ref) {
    return (
        <button
            ref={ref}
            type={type}
            className={`p-1.5 rounded transition-colors duration-ds-fast ${active ? TONE_ACTIVE_TEXT[tone] : 'text-ds-text-muted'} ${TONE_HOVER[tone]} ${className}`.trim()}
            {...rest}
        >
            {children}
        </button>
    );
});

export default DsIconButton;
