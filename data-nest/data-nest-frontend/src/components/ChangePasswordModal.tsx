import {useState} from 'react';
import {changePassword} from '@/api/auth';
import DsButton from './DsButton';
import DsModal from './DsModal';

interface Props {
    open: boolean;
    onClose: () => void;
}

export default function ChangePasswordModal({open, onClose}: Props) {
    const [oldPwd, setOldPwd] = useState('');
    const [newPwd, setNewPwd] = useState('');
    const [confirmPwd, setConfirmPwd] = useState('');
    const [error, setError] = useState('');
    const [success, setSuccess] = useState(false);

    const handleSubmit = async () => {
        setError('');
        if (!oldPwd || !newPwd) {
            setError('请填写所有字段');
            return;
        }
        if (newPwd !== confirmPwd) {
            setError('两次密码不一致');
            return;
        }
        if (newPwd.length < 6) {
            setError('新密码至少6位');
            return;
        }
        await changePassword(oldPwd, newPwd, confirmPwd);
        setSuccess(true);
        setTimeout(() => {
            onClose();
            setSuccess(false);
        }, 1500);
    };

    return (
        <DsModal
            open={open}
            onClose={onClose}
            title="修改密码"
            closable={false}
            footer={
                <>
                    <DsButton variant="ghost" onClick={onClose}>
                        取消
                    </DsButton>
                    <DsButton variant="primary" onClick={handleSubmit}
                              disabled={!oldPwd || !newPwd || !confirmPwd}>
                        确认修改
                    </DsButton>
                </>
            }
        >
            {success && (
                <div
                    className="bg-ds-success-light text-ds-success text-ds-small px-ds-3 py-ds-2 rounded-ds-sm mb-ds-3">
                    密码修改成功
                </div>
            )}
            {error && (
                <div
                    className="bg-ds-danger-light text-ds-danger text-ds-small px-ds-3 py-ds-2 rounded-ds-sm mb-ds-3">
                    {error}
                </div>
            )}

            <div className="space-y-ds-3">
                <div>
                    <label className="block text-ds-small text-ds-text-secondary mb-1">旧密码</label>
                    <input type="password" value={oldPwd} onChange={(e) => setOldPwd(e.target.value)}
                           className="w-full px-ds-3 py-ds-2 border border-ds-border-subtle rounded-ds-sm text-ds-body focus:outline-none focus:border-ds-accent focus:ring-1 focus:ring-ds-accent transition-colors duration-ds-fast"/>
                </div>
                <div>
                    <label className="block text-ds-small text-ds-text-secondary mb-1">新密码</label>
                    <input type="password" value={newPwd} onChange={(e) => setNewPwd(e.target.value)}
                           className="w-full px-ds-3 py-ds-2 border border-ds-border-subtle rounded-ds-sm text-ds-body focus:outline-none focus:border-ds-accent focus:ring-1 focus:ring-ds-accent transition-colors duration-ds-fast"/>
                </div>
                <div>
                    <label className="block text-ds-small text-ds-text-secondary mb-1">确认新密码</label>
                    <input type="password" value={confirmPwd} onChange={(e) => setConfirmPwd(e.target.value)}
                           className="w-full px-ds-3 py-ds-2 border border-ds-border-subtle rounded-ds-sm text-ds-body focus:outline-none focus:border-ds-accent focus:ring-1 focus:ring-ds-accent transition-colors duration-ds-fast"/>
                </div>
            </div>
        </DsModal>
    );
}
