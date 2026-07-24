import {useState} from 'react';
import {changePassword} from '../api/auth';

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

    if (!open) return null;

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
        const result = await changePassword(oldPwd, newPwd);
        if (result.code === 200) {
            setSuccess(true);
            setTimeout(() => {
                onClose();
                setSuccess(false);
            }, 1500);
        }
    };

    return (
        <div className="fixed inset-0 z-ds-dialog flex items-center justify-center">
            <div className="absolute inset-0 bg-black/20 backdrop-blur-sm" onClick={onClose}/>
            <div
                className="relative bg-ds-bg-surface rounded-ds-md shadow-ds-xl p-ds-6 w-[420px] animate-in zoom-in-95">
                <h2 className="text-ds-heading text-ds-text-primary mb-ds-4">修改密码</h2>

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
                               className="w-full px-ds-3 py-ds-2 border border-ds-border-subtle rounded-ds-sm text-ds-body focus:outline-none focus:border-ds-accent focus:ring-1 focus:ring-ds-accent transition-colors ds-fast"/>
                    </div>
                    <div>
                        <label className="block text-ds-small text-ds-text-secondary mb-1">新密码</label>
                        <input type="password" value={newPwd} onChange={(e) => setNewPwd(e.target.value)}
                               className="w-full px-ds-3 py-ds-2 border border-ds-border-subtle rounded-ds-sm text-ds-body focus:outline-none focus:border-ds-accent focus:ring-1 focus:ring-ds-accent transition-colors ds-fast"/>
                    </div>
                    <div>
                        <label className="block text-ds-small text-ds-text-secondary mb-1">确认新密码</label>
                        <input type="password" value={confirmPwd} onChange={(e) => setConfirmPwd(e.target.value)}
                               className="w-full px-ds-3 py-ds-2 border border-ds-border-subtle rounded-ds-sm text-ds-body focus:outline-none focus:border-ds-accent focus:ring-1 focus:ring-ds-accent transition-colors ds-fast"/>
                    </div>
                </div>

                <div className="flex justify-end gap-ds-2 mt-ds-5">
                    <button onClick={onClose}
                            className="px-ds-4 py-ds-2 text-ds-small text-ds-text-secondary hover:bg-ds-bg-hover rounded-ds-sm transition-colors ds-fast">
                        取消
                    </button>
                    <button onClick={handleSubmit}
                            className="px-ds-4 py-ds-2 text-ds-small text-white bg-ds-accent hover:bg-ds-accent-hover rounded-ds-sm font-semibold transition-colors ds-fast disabled:opacity-50"
                            disabled={!oldPwd || !newPwd || !confirmPwd}>
                        确认修改
                    </button>
                </div>
            </div>
        </div>
    );
}
