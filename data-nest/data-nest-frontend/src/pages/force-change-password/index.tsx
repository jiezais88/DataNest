import {useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {changePassword} from '@/api/auth';
import {useAuthStore} from '@/store/useAuthStore';
import ErrorCard from '@/components/ErrorCard';
import DsButton from '@/components/DsButton';
import LogoMark from '@/components/LogoMark';
import {getErrorMessage} from '@/utils/error';

/**
 * Sprint 14 密码过期强制改密页：登录成功后 mustChangePwd=true 时跳转至此。
 * 改密成功 → 刷新本地用户信息（清除 mustChangePwd）→ 进入系统。
 */
export default function ForceChangePasswordPage() {
    const navigate = useNavigate();
    const {userInfo, logout} = useAuthStore();
    const [oldPassword, setOldPassword] = useState('');
    const [newPassword, setNewPassword] = useState('');
    const [confirmPassword, setConfirmPassword] = useState('');
    const [showPwd, setShowPwd] = useState(false);
    const [error, setError] = useState('');
    const [loading, setLoading] = useState(false);

    const canSubmit = oldPassword && newPassword && confirmPassword && newPassword.length >= 6;

    const handleSubmit = async () => {
        if (!canSubmit || loading) return;
        if (newPassword !== confirmPassword) {
            setError('两次输入的新密码不一致');
            return;
        }
        setLoading(true);
        setError('');
        try {
            await changePassword(oldPassword, newPassword, confirmPassword);
            // 改密成功：本地 userInfo 标记清除（后端已重新计算过期时间）
            if (userInfo) {
                useAuthStore.setState({userInfo: {...userInfo, mustChangePwd: false}});
            }
            navigate('/', {replace: true});
        } catch (e) {
            setError(getErrorMessage(e, '密码修改失败'));
        } finally {
            setLoading(false);
        }
    };

    return (
        <div className="relative min-h-screen bg-ds-bg-root flex items-center justify-center p-ds-4 overflow-hidden">
            <div
                aria-hidden="true"
                className="absolute inset-0 pointer-events-none"
                style={{
                    backgroundImage: 'radial-gradient(circle, rgb(79 70 229 / 0.14) 1px, transparent 1.4px)',
                    backgroundSize: '26px 26px',
                    maskImage: 'radial-gradient(ellipse at center, black 40%, transparent 75%)',
                }}
            />
            <div className="relative bg-ds-bg-surface rounded-ds-md shadow-ds-lg p-ds-8 w-full max-w-[420px]">
                <div className="flex flex-col items-center mb-ds-6">
                    <LogoMark size={40} className="mb-ds-3"/>
                    <h1 className="text-ds-display text-ds-text-primary">修改密码</h1>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1 text-center">
                        您的密码已到期，为保障账号安全请先修改密码
                    </p>
                </div>

                {error && (
                    <div className="mb-ds-4">
                        <ErrorCard message={error} onClose={() => setError('')}/>
                    </div>
                )}

                <div className="space-y-ds-4">
                    <div>
                        <label className="block text-ds-small text-ds-text-secondary mb-1">当前密码</label>
                        <input type={showPwd ? 'text' : 'password'} value={oldPassword}
                               onChange={(e) => setOldPassword(e.target.value)}
                               onKeyDown={(e) => e.key === 'Enter' && handleSubmit()}
                               className="w-full px-ds-3 py-ds-2 border border-ds-border-subtle rounded-ds-sm text-ds-body focus:outline-none focus:border-ds-accent focus:ring-1 focus:ring-ds-accent transition-colors duration-ds-fast"
                               placeholder="请输入当前密码" autoComplete="current-password"/>
                    </div>
                    <div>
                        <label className="block text-ds-small text-ds-text-secondary mb-1">新密码</label>
                        <input type={showPwd ? 'text' : 'password'} value={newPassword}
                               onChange={(e) => setNewPassword(e.target.value)}
                               onKeyDown={(e) => e.key === 'Enter' && handleSubmit()}
                               className="w-full px-ds-3 py-ds-2 border border-ds-border-subtle rounded-ds-sm text-ds-body focus:outline-none focus:border-ds-accent focus:ring-1 focus:ring-ds-accent transition-colors duration-ds-fast"
                               placeholder="至少 6 位，需包含大小写字母和数字"
                               autoComplete="new-password"/>
                    </div>
                    <div>
                        <label className="block text-ds-small text-ds-text-secondary mb-1">确认新密码</label>
                        <input type={showPwd ? 'text' : 'password'} value={confirmPassword}
                               onChange={(e) => setConfirmPassword(e.target.value)}
                               onKeyDown={(e) => e.key === 'Enter' && handleSubmit()}
                               className="w-full px-ds-3 py-ds-2 border border-ds-border-subtle rounded-ds-sm text-ds-body focus:outline-none focus:border-ds-accent focus:ring-1 focus:ring-ds-accent transition-colors duration-ds-fast"
                               placeholder="请再次输入新密码" autoComplete="new-password"/>
                    </div>

                    <label className="flex items-center gap-ds-1 cursor-pointer select-none">
                        <input type="checkbox" checked={showPwd}
                               onChange={(e) => setShowPwd(e.target.checked)}
                               className="w-4 h-4 rounded border-ds-border-subtle text-ds-accent focus:ring-ds-accent"/>
                        <span className="text-ds-small text-ds-text-secondary">显示密码</span>
                    </label>

                    <DsButton type="submit" onClick={handleSubmit} disabled={!canSubmit || loading}
                              loading={loading} className="w-full">
                        确认修改
                    </DsButton>

                    <div className="text-center">
                        <button
                            onClick={() => {
                                logout();
                                navigate('/login', {replace: true});
                            }}
                            className="text-ds-small text-ds-text-muted hover:text-ds-text-secondary transition-colors duration-ds-fast"
                        >
                            退出登录
                        </button>
                    </div>
                </div>
            </div>
        </div>
    );
}
