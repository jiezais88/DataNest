import {HiOutlineXMark} from 'react-icons/hi2';

interface DrawerProps {
    open: boolean;
    title: string;
    width?: string;
    onClose: () => void;
    children: React.ReactNode;
    footer?: React.ReactNode;
}

export default function Drawer({open, title, width = 'max-w-[560px]', onClose, children, footer}: DrawerProps) {
    if (!open) return null;

    return (
        <div className="fixed inset-0 z-50 flex justify-end">
            <div className="absolute inset-0 bg-black/30" onClick={onClose}/>
            <div className={`relative w-full ${width} h-full bg-ds-bg-surface shadow-ds-lg flex flex-col`}>
                <div
                    className="flex items-center justify-between px-ds-6 py-ds-4 border-b border-ds-border-subtle flex-shrink-0">
                    <h2 className="text-ds-title text-ds-text-primary font-bold">{title}</h2>
                    <button
                        onClick={onClose}
                        className="p-1.5 text-ds-text-muted hover:text-ds-text-primary hover:bg-ds-bg-hover rounded transition-colors"
                        aria-label="关闭"
                    >
                        <HiOutlineXMark size={20}/>
                    </button>
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
