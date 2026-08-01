import DsButton from './DsButton';
import DsModal from './DsModal';
import DsSpinner from './DsSpinner';

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
    return (
        <DsModal
            open={open}
            onClose={onCancel}
            title={title}
            closable={false}
            maskClosable={!loading}
            footer={
                <>
                    <DsButton variant="ghost" onClick={onCancel} disabled={loading}>
                        取消
                    </DsButton>
                    <DsButton onClick={onConfirm} disabled={loading}
                              variant={danger ? 'danger' : 'primary'}>
                        {loading && <DsSpinner/>}
                        {loading ? '处理中...' : confirmLabel}
                    </DsButton>
                </>
            }
        >
            <div className="text-ds-body text-ds-text-secondary">{message}</div>
        </DsModal>
    );
}
