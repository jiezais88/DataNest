import {useRef} from 'react';
import {HiOutlineXMark} from 'react-icons/hi2';
import DsIconButton from './DsIconButton';
import {useModalA11y} from '../hooks/useModalA11y';

interface DrawerProps {
    open: boolean;
    title: React.ReactNode;
    width?: string;
    onClose: () => void;
    children: React.ReactNode;
    footer?: React.ReactNode;
    /** 标题栏右侧、关闭按钮左侧的额外操作区（如删除按钮） */
    extra?: React.ReactNode;
}

export default function Drawer({open, title, width = 'max-w-[560px]', onClose, children, footer, extra}: DrawerProps) {
    const panelRef = useRef<HTMLDivElement>(null);
    useModalA11y(open, onClose, panelRef);

    if (!open) return null;

    return (
        <div className="fixed inset-0 z-ds-dialog flex justify-end">
            <div className="absolute inset-0 bg-black/30" onClick={onClose}/>
            <div
                ref={panelRef}
                role="dialog"
                aria-modal="true"
                aria-label={typeof title === 'string' ? title : undefined}
                tabIndex={-1}
                className={`relative w-full ${width} h-full bg-ds-bg-surface shadow-ds-lg flex flex-col outline-none`}
            >
                <div
                    className="flex items-center justify-between px-ds-6 py-ds-4 border-b border-ds-border-subtle flex-shrink-0">
                    <h2 className="text-ds-title text-ds-text-primary font-bold">{title}</h2>
                    <div className="flex items-center gap-ds-2">
                        {extra}
                        <DsIconButton
                            tone="default"
                            onClick={onClose}
                            aria-label="关闭"
                        >
                            <HiOutlineXMark size={20}/>
                        </DsIconButton>
                    </div>
                </div>

                <div className="flex-1 overflow-auto p-ds-6">
                    {children}
                </div>

                {footer && (
                    <div
                        className="flex items-center justify-end gap-ds-3 px-ds-6 py-ds-4 border-t border-ds-border-subtle flex-shrink-0">
                        {footer}
                    </div>
                )}
            </div>
        </div>
    );
}
