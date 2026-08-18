import {useEffect, useState} from 'react';
import DsModal from '@/components/DsModal';
import DsButton from '@/components/DsButton';
import ErrorCard from '@/components/ErrorCard';
import {getErrorMessage} from '@/utils/error';
import {ldapLogin} from '@/api/auth';
import {useAuthStore} from '@/store/useAuthStore';
import {useNavigate} from 'react-router-dom';

interface Props {
    open: boolean;
    onClose: () => void;
}

/** Sprint 14 SSO：AD 域账号登录弹窗（LDAP bind 认证，与本地登录同会话语义） */
export default function LdapLoginModal({open, onClose}: Props) {
    const navigate = useNavigate();
    const {setAuth} = useAuthStore();
    const [username, setUsername] = useState('');
    const [password, setPassword] = useState('');
    const [error, setError] = useState('');
    const [loading, setLoading] = useState(false);

    useEffect(() => {
        if (open) {
            setUsername('');
            setPassword('');
            setError('');
        }
    }, [open]);

    const canSubmit = username.trim() && password.trim();

    const handleLogin = async () => {
        if (!canSubmit || loading) return;
        setLoading(true);
        setError('');
        try {
            const result = await ldapLogin(username.trim(), password);
            setAuth(result.data.token, result.data.userInfo);
            onClose();
            navigate(result.data.userInfo.mustChangePwd ? '/force-change-password' : '/');
        } catch (e) {
            setError(getErrorMessage(e, '域账号登录失败'));
        } finally {
            setLoading(false);
        }
    };

    return (
        <DsModal
            open={open}
            onClose={() => {
                if (loading) return;
                onClose();
            }}
            title="企业域账号登录"
            width="w-[400px]"
            closable={!loading}
            footer={
                <>
                    <DsButton variant="ghost" onClick={onClose} disabled={loading}>
                        取消
                    </DsButton>
                    <DsButton onClick={handleLogin} disabled={!canSubmit || loading} loading={loading}>
                        登录
                    </DsButton>
                </>
            }
        >
            <p className="text-ds-small text-ds-text-muted mb-ds-4">
                使用企业域账号（LDAP/AD）登录，账号由企业身份目录统一管理
            </p>
            {error && (
                <div className="mb-ds-4">
                    <ErrorCard message={error} onClose={() => setError('')}/>
                </div>
            )}
            <div className="space-y-ds-4">
                <div>
                    <label className="block text-ds-small text-ds-text-secondary mb-1">域账号</label>
                    <input name="ldap-username" value={username} onChange={(e) => setUsername(e.target.value)}
                           onKeyDown={(e) => e.key === 'Enter' && handleLogin()}
                           className="w-full px-ds-3 py-ds-2 border border-ds-border-subtle rounded-ds-sm text-ds-body focus:outline-none focus:border-ds-accent focus:ring-1 focus:ring-ds-accent transition-colors duration-ds-fast"
                           placeholder="请输入域账号" autoComplete="username"/>
                </div>
                <div>
                    <label className="block text-ds-small text-ds-text-secondary mb-1">密码</label>
                    <input name="ldap-password" type="password" value={password}
                           onChange={(e) => setPassword(e.target.value)}
                           onKeyDown={(e) => e.key === 'Enter' && handleLogin()}
                           className="w-full px-ds-3 py-ds-2 border border-ds-border-subtle rounded-ds-sm text-ds-body focus:outline-none focus:border-ds-accent focus:ring-1 focus:ring-ds-accent transition-colors duration-ds-fast"
                           placeholder="请输入密码" autoComplete="current-password"/>
                </div>
            </div>
        </DsModal>
    );
}
