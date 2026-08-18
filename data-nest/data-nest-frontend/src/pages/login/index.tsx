import {useEffect, useState} from 'react';
import {useNavigate, useSearchParams} from 'react-router-dom';
import {getMe, getSsoStatus, login, oidcAuthorizeUrl} from '@/api/auth';
import {useAuthStore} from '@/store/useAuthStore';
import ErrorCard from '@/components/ErrorCard';
import DsButton from '@/components/DsButton';
import LogoMark from '@/components/LogoMark';
import {getErrorMessage} from '@/utils/error';
import LdapLoginModal from './LdapLoginModal';
import {HiOutlineChevronDown, HiOutlineKey, HiOutlineShieldCheck} from 'react-icons/hi2';

export default function LoginPage() {
    const navigate = useNavigate();
    const [searchParams, setSearchParams] = useSearchParams();
    const {setAuth, logout} = useAuthStore();
    const [username, setUsername] = useState('');
    const [password, setPassword] = useState('');
    const [showPwd, setShowPwd] = useState(false);
    const [rememberMe, setRememberMe] = useState(false);
    const [error, setError] = useState('');
    const [loading, setLoading] = useState(false);

    // Sprint 14 SSO：登录页初始化状态
    const [sso, setSso] = useState<{ enabled: boolean; mode: string; oidcEnabled: boolean; ldapEnabled: boolean }>({
        enabled: false,
        mode: 'mixed',
        oidcEnabled: false,
        ldapEnabled: false,
    });
    const [ldapOpen, setLdapOpen] = useState(false);
    const [showLocalForm, setShowLocalForm] = useState(false);
    const [ssoLoaded, setSsoLoaded] = useState(false);

    // 被踢出登录（401 跳转携带 expired=1）时提示原因；读取后清掉参数避免刷新重复提示
    useEffect(() => {
        if (searchParams.get('expired') === '1') {
            setError('登录已过期，请重新登录');
            setSearchParams({}, {replace: true});
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    // Sprint 14：拉取 SSO 状态（公开接口，决定是否展示企业身份入口）
    useEffect(() => {
        getSsoStatus()
            .then((data) => {
                setSso(data);
                // sso-only 模式默认隐藏本地表单，仅保留「管理员本地登录」逃生通道
                setShowLocalForm(data.mode !== 'sso-only');
            })
            .catch(() => {
                // 状态拉取失败按未启用处理，本地登录完全可用
                setSso({enabled: false, mode: 'mixed', oidcEnabled: false, ldapEnabled: false});
                setShowLocalForm(true);
            })
            .finally(() => setSsoLoaded(true));
    }, []);

    // Sprint 14：OIDC 回调（302 到 /login#ssoToken=xxx，token 走 URL fragment 不落服务端日志）
    useEffect(() => {
        const hash = window.location.hash;
        if (!hash.startsWith('#ssoToken=')) return;
        const token = hash.slice('#ssoToken='.length);
        window.history.replaceState(null, '', window.location.pathname + window.location.search);
        (async () => {
            try {
                // 先落 token 再 getMe（拦截器从 localStorage 取 token），拿不到 userInfo 视为失败
                setAuth(token, {userId: '', username: '', roles: []});
                const res = await getMe();
                setAuth(token, res.data);
                navigate(res.data.mustChangePwd ? '/force-change-password' : '/');
            } catch {
                logout();
                setError('企业身份登录失败，请稍后重试');
            }
        })();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    const canSubmit = username.trim() && password.trim();
    const ssoOnlyMode = sso.enabled && sso.mode === 'sso-only';

    const handleLogin = async () => {
        if (!canSubmit) return;
        setLoading(true);
        setError('');
        try {
            const result = await login({username, password, rememberMe});
            setAuth(result.data.token, result.data.userInfo);
            navigate(result.data.userInfo.mustChangePwd ? '/force-change-password' : '/');
        } catch (e) {
            setError(getErrorMessage(e, '登录失败'));
        } finally {
            setLoading(false);
        }
    };

    const handleSsoLogin = () => {
        // 整页跳转 IdP（后端生成 state 防 CSRF；授权码流程结束回跳 /login#ssoToken=xxx）
        window.location.href = oidcAuthorizeUrl;
    };

    return (
        <div className="relative min-h-screen bg-ds-bg-root flex items-center justify-center p-ds-4 overflow-hidden">
            {/* 品牌背景母题：数据点阵（hexagon dot grid） */}
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
                {/* Logo */}
                <div className="flex flex-col items-center mb-ds-6">
                    <LogoMark size={40} className="mb-ds-3"/>
                    <h1 className="text-ds-display text-ds-text-primary">DataNest</h1>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1">企业级数据中台</p>
                </div>

                {/* Error */}
                {error && (
                    <div className="mb-ds-4">
                        <ErrorCard message={error} onClose={() => setError('')}/>
                    </div>
                )}

                {/* 企业身份入口（SSO 启用时展示） */}
                {ssoLoaded && sso.enabled && (
                    <div className="mb-ds-4 space-y-ds-2">
                        {sso.oidcEnabled && (
                            <DsButton onClick={handleSsoLogin} className="w-full" variant="secondary">
                                <HiOutlineShieldCheck size={16}/>
                                企业 SSO 登录
                            </DsButton>
                        )}
                        {sso.ldapEnabled && (
                            <DsButton onClick={() => setLdapOpen(true)} className="w-full" variant="ghost">
                                <HiOutlineKey size={16}/>
                                AD 域账号登录
                            </DsButton>
                        )}
                        {ssoOnlyMode ? (
                            <div>
                                <button
                                    onClick={() => setShowLocalForm((v) => !v)}
                                    className="w-full flex items-center justify-center gap-1 text-ds-small text-ds-text-muted hover:text-ds-text-secondary transition-colors duration-ds-fast"
                                >
                                    <HiOutlineChevronDown size={14} className={`transition-transform ${showLocalForm ? 'rotate-180' : ''}`}/>
                                    管理员本地登录
                                </button>
                            </div>
                        ) : (
                            <div className="flex items-center gap-ds-2 my-ds-3">
                                <div className="flex-1 h-px bg-ds-border-subtle"/>
                                <span className="text-ds-nano text-ds-text-muted">或使用本地账号</span>
                                <div className="flex-1 h-px bg-ds-border-subtle"/>
                            </div>
                        )}
                    </div>
                )}

                {/* Form（sso-only 模式隐藏，仅管理员可展开） */}
                {(ssoLoaded && (!ssoOnlyMode || showLocalForm)) ? (
                    <div className="space-y-ds-4">
                        <div>
                            <label className="block text-ds-small text-ds-text-secondary mb-1">用户名</label>
                            <input name="username" value={username} onChange={(e) => setUsername(e.target.value)}
                                   onKeyDown={(e) => e.key === 'Enter' && handleLogin()}
                                   className="w-full px-ds-3 py-ds-2 border border-ds-border-subtle rounded-ds-sm text-ds-body focus:outline-none focus:border-ds-accent focus:ring-1 focus:ring-ds-accent transition-colors duration-ds-fast"
                                   placeholder="请输入用户名" autoComplete="username"/>
                        </div>

                        <div>
                            <label className="block text-ds-small text-ds-text-secondary mb-1">密码</label>
                            <div className="relative">
                                <input name="password" type={showPwd ? 'text' : 'password'} value={password}
                                       onChange={(e) => setPassword(e.target.value)}
                                       onKeyDown={(e) => e.key === 'Enter' && handleLogin()}
                                       className="w-full px-ds-3 py-ds-2 pr-10 border border-ds-border-subtle rounded-ds-sm text-ds-body focus:outline-none focus:border-ds-accent focus:ring-1 focus:ring-ds-accent transition-colors duration-ds-fast"
                                       placeholder="请输入密码" autoComplete="current-password"/>
                                <button onClick={() => setShowPwd(!showPwd)}
                                        className="absolute right-3 top-1/2 -translate-y-1/2 text-ds-text-muted hover:text-ds-text-secondary">
                                    {showPwd ? (
                                        <svg width="18" height="18" viewBox="0 0 24 24" fill="none"
                                             stroke="currentColor" strokeWidth="1.5">
                                            <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/>
                                            <circle cx="12" cy="12" r="3"/>
                                        </svg>
                                    ) : (
                                        <svg width="18" height="18" viewBox="0 0 24 24" fill="none"
                                             stroke="currentColor" strokeWidth="1.5">
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

                        <DsButton type="submit" onClick={handleLogin} disabled={!canSubmit || loading}
                                  loading={loading}
                                  className="w-full">
                            登 录
                        </DsButton>
                    </div>
                ) : (
                    <p className="text-center text-ds-small text-ds-text-muted">
                        当前为仅企业身份登录模式，请使用上方企业身份入口登录
                    </p>
                )}

                <p className="text-center text-ds-small text-ds-text-muted mt-ds-5">
                    没有账号？联系管理员创建
                </p>
            </div>

            <LdapLoginModal open={ldapOpen} onClose={() => setLdapOpen(false)}/>
        </div>
    );
}
