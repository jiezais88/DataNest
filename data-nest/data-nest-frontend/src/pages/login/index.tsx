import {useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {login} from '../../api/auth';
import {useAuthStore} from '../../store/useAuthStore';
import ErrorCard from '../../components/ErrorCard';

export default function LoginPage() {
    const navigate = useNavigate();
    const {setAuth} = useAuthStore();
    const [username, setUsername] = useState('');
    const [password, setPassword] = useState('');
    const [showPwd, setShowPwd] = useState(false);
    const [rememberMe, setRememberMe] = useState(false);
    const [error, setError] = useState('');
    const [loading, setLoading] = useState(false);

    const canSubmit = username.trim() && password.trim();

    const handleLogin = async () => {
        if (!canSubmit) return;
        setLoading(true);
        setError('');
        const result = await login({username, password, rememberMe});
        if (result.code === 200) {
            setAuth(result.data.token, result.data.userInfo);
            navigate('/');
        } else {
            setError(errorMessages[result.code] || result.message || '登录失败');
        }
        setLoading(false);
    };

    const errorMessages: Record<string, string> = {
        USER_NOT_FOUND: '用户不存在',
        PASSWORD_ERROR: '密码错误',
        ACCOUNT_DISABLED: '账号已禁用',
    };

    return (
        <div className="min-h-screen bg-ds-bg-root flex items-center justify-center p-ds-4">
            <div className="bg-ds-bg-surface rounded-ds-md shadow-ds-lg p-ds-8 w-full max-w-[420px]">
                {/* Logo */}
                <div className="flex flex-col items-center mb-ds-6">
                    <div className="w-10 h-10 bg-ds-accent rounded-ds-sm flex items-center justify-center mb-ds-3">
                        <span className="text-white font-extrabold text-sm">DN</span>
                    </div>
                    <h1 className="text-ds-display text-ds-text-primary">DataNest</h1>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1">企业级数据中台</p>
                </div>

                {/* Error */}
                {error && (
                    <div className="mb-ds-4">
                        <ErrorCard message={error} onClose={() => setError('')}/>
                    </div>
                )}

                {/* Form */}
                <div className="space-y-ds-4">
                    <div>
                        <label className="block text-ds-small text-ds-text-secondary mb-1">用户名</label>
                        <input name="username" value={username} onChange={(e) => setUsername(e.target.value)}
                               onKeyDown={(e) => e.key === 'Enter' && handleLogin()}
                               className="w-full px-ds-3 py-ds-2 border border-ds-border-subtle rounded-ds-sm text-ds-body focus:outline-none focus:border-ds-accent focus:ring-1 focus:ring-ds-accent transition-colors ds-fast"
                               placeholder="请输入用户名" autoComplete="username"/>
                    </div>

                    <div>
                        <label className="block text-ds-small text-ds-text-secondary mb-1">密码</label>
                        <div className="relative">
                            <input name="password" type={showPwd ? 'text' : 'password'} value={password}
                                   onChange={(e) => setPassword(e.target.value)}
                                   onKeyDown={(e) => e.key === 'Enter' && handleLogin()}
                                   className="w-full px-ds-3 py-ds-2 pr-10 border border-ds-border-subtle rounded-ds-sm text-ds-body focus:outline-none focus:border-ds-accent focus:ring-1 focus:ring-ds-accent transition-colors ds-fast"
                                   placeholder="请输入密码" autoComplete="current-password"/>
                            <button onClick={() => setShowPwd(!showPwd)}
                                    className="absolute right-3 top-1/2 -translate-y-1/2 text-ds-text-muted hover:text-ds-text-secondary">
                                {showPwd ? (
                                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                                         strokeWidth="1.5">
                                        <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/>
                                        <circle cx="12" cy="12" r="3"/>
                                    </svg>
                                ) : (
                                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                                         strokeWidth="1.5">
                                        <path
                                            d="M17.94 17.94A10.07 10.07 0 0112 20c-7 0-11-8-11-8a18.45 18.45 0 015.06-5.94M9.9 4.24A9.12 9.12 0 0112 4c7 0 11 8 11 8a18.5 18.5 0 01-2.16 3.19m-6.72-1.07a3 3 0 11-4.24-4.24"/>
                                        <line x1="1" y1="1" x2="23" y2="23"/>
                                    </svg>
                                )}
                            </button>
                        </div>
                    </div>

                    <div className="flex items-center justify-between">
                        <label className="flex items-center gap-ds-1 cursor-pointer">
                            <input type="checkbox" checked={rememberMe}
                                   onChange={(e) => setRememberMe(e.target.checked)}
                                   className="w-4 h-4 rounded border-ds-border-subtle text-ds-accent focus:ring-ds-accent"/>
                            <span className="text-ds-small text-ds-text-secondary">记住登录状态</span>
                        </label>
                    </div>

                    <button type="submit" onClick={handleLogin} disabled={!canSubmit || loading}
                            className="w-full py-ds-2 rounded-ds-sm text-ds-small font-semibold text-white transition-colors ds-fast
              bg-ds-accent hover:bg-ds-accent-hover disabled:opacity-50 disabled:cursor-not-allowed">
                        {loading ? '登录中...' : '登 录'}
                    </button>
                </div>

                <p className="text-center text-ds-small text-ds-text-muted mt-ds-5">
                    没有账号？联系管理员创建
                </p>
            </div>
        </div>
    );
}
