import {HiOutlineCheckCircle, HiOutlineXCircle} from 'react-icons/hi2';
import DsButton from './DsButton';
import DsModal from './DsModal';

interface TestResultModalProps {
    open: boolean;
    success: boolean;
    message: string;
    onClose: () => void;
}

export default function TestResultModal({open, success, message: msg, onClose}: TestResultModalProps) {
    return (
        <DsModal
            open={open}
            onClose={onClose}
            closable={false}
            title={success ? '连接成功' : '连接失败'}
            footer={
                <DsButton onClick={onClose} variant={success ? 'primary' : 'danger'}>
                    知道了
                </DsButton>
            }
        >
            {success ? (
                <HiOutlineCheckCircle size={48} className="text-ds-success mb-ds-3"/>
            ) : (
                <HiOutlineXCircle size={48} className="text-ds-danger mb-ds-3"/>
            )}
            <p className="text-ds-body text-ds-text-secondary whitespace-pre-wrap">{msg}</p>
        </DsModal>
    );
}
