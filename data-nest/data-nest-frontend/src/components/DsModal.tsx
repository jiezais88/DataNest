import {type ReactNode, useRef} from 'react';
import {HiOutlineXMark} from 'react-icons/hi2';
import DsIconButton from './DsIconButton';
import {useModalA11y} from '../hooks/useModalA11y';

/**
 * 全局统一弹窗基座。历史背景：项目里曾有 antd Modal / ConfirmDialog / 各页面手写
 * fixed inset-0 弹窗三套实现，z-index 还不一致；现在所有弹窗收敛到这里。
 *
 * 两种布局：
 * - 简洁（默认）：p-ds-6 容器 + 标题 + 内容 + 右下按钮区（确认/取消类弹窗）
 * - 结构化（bordered）：border-b 标题栏 + 可滚动内容区 + border-t 底栏（详情/预览类弹窗）
 */
export interface DsModalProps {
    open: boolean;
    onClose: () => void;
    title?: ReactNode;
    /** 容器宽度类，默认 w-[420px] */
    width?: string;
    /** 结构化布局：border-b 标题栏 + border-t 底栏 */
    bordered?: boolean;
    /** 点遮罩关闭，默认 true */
    maskClosable?: boolean;
    /** 右上角关闭按钮，默认 true */
    closable?: boolean;
    /** 底部按钮区（简洁布局右对齐；结构化布局在 border-t 底栏内右对齐） */
    footer?: ReactNode;
    /** 内容区最大高度（结构化布局生效），默认 max-h-[70vh] */
    bodyMaxHeight?: string;
    children: ReactNode;
}

export default function DsModal({
                                    open,
                                    onClose,
                                    title,
                                    width = 'w-[420px]',
                                    bordered = false,
                                    maskClosable = true,
                                    closable = true,
                                    footer,
                                    bodyMaxHeight = 'max-h-[70vh]',
                                    children,
                                }: DsModalProps) {
    const panelRef = useRef<HTMLDivElement>(null);
    useModalA11y(open, onClose, panelRef);

    if (!open) return null;

    return (
        <div className="fixed inset-0 z-ds-dialog flex items-center justify-center">
            <div
                className="absolute inset-0 bg-black/20 backdrop-blur-sm"
                onClick={maskClosable ? onClose : undefined}
            />
            <div
                ref={panelRef}
                role="dialog"
                aria-modal="true"
                aria-label={typeof title === 'string' ? title : undefined}
                tabIndex={-1}
                className={`relative bg-ds-bg-surface rounded-ds-md shadow-ds-xl ${width} animate-in zoom-in-95 outline-none ${
                    bordered ? 'flex flex-col' : 'p-ds-6'
                }`}
            >
                {bordered ? (
                    <>
                        <div
                            className="flex items-center justify-between px-ds-6 py-ds-4 border-b border-ds-border-subtle flex-shrink-0">
                            <h3 className="text-ds-subhead text-ds-text-primary">{title}</h3>
                            {closable && (
                                <DsIconButton tone="default" onClick={onClose} aria-label="关闭">
                                    <HiOutlineXMark size={20}/>
                                </DsIconButton>
                            )}
                        </div>
                        <div className={`overflow-auto p-ds-6 ${bodyMaxHeight}`}>
                            {children}
                        </div>
                        {footer && (
                            <div
                                className="flex items-center justify-end gap-ds-2 px-ds-6 py-ds-4 border-t border-ds-border-subtle flex-shrink-0">
                                {footer}
                            </div>
                        )}
                    </>
                ) : (
                    <>
                        {closable && (
                            <div className="absolute top-ds-4 right-ds-4">
                                <DsIconButton tone="default" onClick={onClose} aria-label="关闭">
                                    <HiOutlineXMark size={20}/>
                                </DsIconButton>
                            </div>
                        )}
                        {title && (
                            <h3 className="text-ds-subhead text-ds-text-primary mb-ds-2 pr-8">{title}</h3>
                        )}
                        {children}
                        {footer && (
                            <div className="flex justify-end gap-ds-2 mt-ds-5">
                                {footer}
                            </div>
                        )}
                    </>
                )}
            </div>
        </div>
    );
}
