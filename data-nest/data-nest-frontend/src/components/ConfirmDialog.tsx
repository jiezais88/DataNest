interface Props {
    open: boolean;
    title: string;
    message: string;
    confirmLabel?: string;
    danger?: boolean;
    onConfirm: () => void;
    onCancel: () => void;
}

export default function ConfirmDialog({
                                          open,
                                          title,
                                          message,
                                          confirmLabel = '确认',
                                          danger,
                                          onConfirm,
                                          onCancel
                                      }: Props) {
    if (!open) return null;

    return (
        <div className="fixed inset-0 z-ds-dialog flex items-center justify-center">
            <div className="absolute inset-0 bg-black/20 backdrop-blur-sm" onClick={onCancel}/>
            <div
                className="relative bg-ds-bg-surface rounded-ds-md shadow-ds-xl p-ds-6 w-[420px] animate-in zoom-in-95">
                <h3 className="text-ds-subhead text-ds-text-primary mb-ds-2">{title}</h3>
                <p className="text-ds-body text-ds-text-secondary mb-ds-5">{message}</p>
                <div className="flex justify-end gap-ds-2">
                    <button onClick={onCancel}
                            className="px-ds-4 py-ds-2 text-ds-small text-ds-text-secondary hover:bg-ds-bg-hover rounded-ds-sm transition-colors ds-fast">
                        取消
                    </button>
                    <button onClick={onConfirm}
                            className={`px-ds-4 py-ds-2 text-ds-small text-white rounded-ds-sm font-semibold transition-colors ds-fast
              ${danger ? 'bg-ds-danger hover:bg-ds-danger-hover' : 'bg-ds-accent hover:bg-ds-accent-hover'}`}>
                        {confirmLabel}
                    </button>
                </div>
            </div>
        </div>
    );
}
