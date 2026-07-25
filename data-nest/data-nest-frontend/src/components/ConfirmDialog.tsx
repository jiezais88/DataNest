interface Props {
    open: boolean;
    title: string;
    message: React.ReactNode;
    confirmLabel?: string;
    danger?: boolean;
    loading?: boolean;
    onConfirm: () => void;
    onCancel: () => void;
}

export default function ConfirmDialog({
                                          open,
                                          title,
                                          message,
                                          confirmLabel = '确认',
                                          danger,
                                          loading = false,
                                          onConfirm,
                                          onCancel
                                      }: Props) {
    if (!open) return null;

    return (
        <div className="fixed inset-0 z-ds-dialog flex items-center justify-center">
            <div className="absolute inset-0 bg-black/20 backdrop-blur-sm" onClick={loading ? undefined : onCancel}/>
            <div
                className="relative bg-ds-bg-surface rounded-ds-md shadow-ds-xl p-ds-6 w-[420px] animate-in zoom-in-95">
                <h3 className="text-ds-subhead text-ds-text-primary mb-ds-2">{title}</h3>
                <div className="text-ds-body text-ds-text-secondary mb-ds-5">{message}</div>
                <div className="flex justify-end gap-ds-2">
                    <button onClick={onCancel} disabled={loading}
                            className="px-ds-4 py-ds-2 text-ds-small text-ds-text-secondary hover:bg-ds-bg-hover rounded-ds-sm transition-colors ds-fast disabled:opacity-50 disabled:cursor-not-allowed">
                        取消
                    </button>
                    <button onClick={onConfirm} disabled={loading}
                            className={`px-ds-4 py-ds-2 text-ds-small text-white rounded-ds-sm font-semibold transition-colors ds-fast disabled:opacity-60 disabled:cursor-not-allowed flex items-center gap-1.5
              ${danger ? 'bg-ds-danger hover:bg-ds-danger-hover' : 'bg-ds-accent hover:bg-ds-accent-hover'}`}>
                        {loading && (
                            <svg className="animate-spin h-3.5 w-3.5" xmlns="http://www.w3.org/2000/svg" fill="none"
                                 viewBox="0 0 24 24">
                                <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor"
                                        strokeWidth="4"/>
                                <path className="opacity-75" fill="currentColor"
                                      d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"/>
                            </svg>
                        )}
                        {loading ? '处理中...' : confirmLabel}
                    </button>
                </div>
            </div>
        </div>
    );
}
