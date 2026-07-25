import {HiOutlineCheckCircle, HiOutlineXCircle} from 'react-icons/hi2';

interface TestResultModalProps {
    open: boolean;
    success: boolean;
    message: string;
    onClose: () => void;
}

export default function TestResultModal({open, success, message: msg, onClose}: TestResultModalProps) {
    if (!open) return null;

    return (
        <div className="fixed inset-0 z-ds-dialog flex items-center justify-center">
            <div className="absolute inset-0 bg-black/20 backdrop-blur-sm" onClick={onClose}/>
            <div
                className="relative bg-ds-bg-surface rounded-ds-md shadow-ds-xl p-ds-6 w-[400px] animate-in zoom-in-95 text-center">
                {success ? (
                    <HiOutlineCheckCircle size={48} className="mx-auto text-ds-success mb-ds-3"/>
                ) : (
                    <HiOutlineXCircle size={48} className="mx-auto text-ds-danger mb-ds-3"/>
                )}
                <h3 className="text-ds-subhead text-ds-text-primary mb-ds-2">
                    {success ? '连接成功' : '连接失败'}
                </h3>
                <p className="text-ds-body text-ds-text-secondary mb-ds-5 whitespace-pre-wrap">{msg}</p>
                <button onClick={onClose}
                        className={`px-ds-6 py-ds-2 text-ds-small text-white rounded-ds-sm font-semibold transition-colors ds-fast ${
                            success ? 'bg-ds-accent hover:bg-ds-accent-hover' : 'bg-ds-danger hover:bg-ds-danger-hover'
                        }`}>
                    知道了
                </button>
            </div>
        </div>
    );
}
