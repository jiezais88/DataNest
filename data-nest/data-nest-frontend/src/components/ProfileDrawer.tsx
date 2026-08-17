import {useCallback, useEffect, useState} from 'react';
import {Spin} from 'antd';
import {getProfile, updateProfile, type UserProfile} from '@/api/auth';
import {useAuthStore} from '@/store/useAuthStore';
import {ROLE_OPTIONS} from '@/constants/roles';
import {formatDateTime} from '@/utils/format';
import {notify} from '@/utils/notify';
import {getErrorMessage} from '@/utils/error';
import ChangePasswordModal from './ChangePasswordModal';
import DsButton from './DsButton';
import Drawer from './Drawer';

const EMAIL_RE = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;
const PHONE_RE = /^\d{6,20}$/;

/** 一行内 label 左 + value 右，compact 紧凑布局 */
function InfoRow({label, value, empty}: {label: string; value: string; empty?: boolean}) {
    return (
        <div className="flex items-baseline gap-ds-3 py-ds-2.5 border-b border-ds-border-subtle last:border-b-0">
            <span className="text-ds-small text-ds-text-muted w-16 flex-shrink-0">{label}</span>
            <span className={`text-ds-small ${empty ? 'text-ds-text-muted' : 'text-ds-text-primary'}`}>
                {value}
            </span>
        </div>
    );
}

interface Props {
    open: boolean;
    onClose: () => void;
}

export default function ProfileDrawer({open, onClose}: Props) {
    const {userInfo} = useAuthStore();
    const [profile, setProfile] = useState<UserProfile | null>(null);
    const [loading, setLoading] = useState(true);
    const [failed, setFailed] = useState(false);
    const [pwdModalOpen, setPwdModalOpen] = useState(false);

    // 资料编辑态
    const [editing, setEditing] = useState(false);
    const [emailDraft, setEmailDraft] = useState('');
    const [phoneDraft, setPhoneDraft] = useState('');
    const [saving, setSaving] = useState(false);
    const [saveError, setSaveError] = useState('');

    const load = useCallback(async () => {
        setLoading(true);
        setFailed(false);
        try {
            setProfile(await getProfile());
        } catch {
            setFailed(true);
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        if (open) {
            setEditing(false);
            load();
        }
    }, [open, load]);

    const startEdit = () => {
        setEmailDraft(profile?.email ?? '');
        setPhoneDraft(profile?.phone ?? '');
        setSaveError('');
        setEditing(true);
    };

    const cancelEdit = () => {
        setEditing(false);
        setSaveError('');
    };

    const handleSave = async () => {
        setSaveError('');
        const email = emailDraft.trim();
        const phone = phoneDraft.trim();
        if (email && !EMAIL_RE.test(email)) {
            setSaveError('邮箱格式不正确');
            return;
        }
        if (phone && !PHONE_RE.test(phone)) {
            setSaveError('手机号格式不正确（6~20 位数字）');
            return;
        }
        setSaving(true);
        try {
            await updateProfile({email, phone});
            setProfile((prev) => (prev ? {...prev, email: email || undefined, phone: phone || undefined} : prev));
            setEditing(false);
            notify.success('资料已更新');
        } catch (e) {
            setSaveError(getErrorMessage(e));
        } finally {
            setSaving(false);
        }
    };

    const username = profile?.username ?? userInfo?.username ?? '';
    const userId = profile?.userId ?? userInfo?.userId ?? '';
    const initials = username.slice(0, 1).toUpperCase() || 'U';
    const roles = profile?.roles ?? userInfo?.roles ?? [];
    const primaryRoleLabel = roles.length > 0
        ? (ROLE_OPTIONS.find((r) => r.value === roles[0])?.label ?? roles[0])
        : '未分配角色';

    const inputClass = 'w-full px-ds-3 py-ds-2 border border-ds-border-subtle rounded-ds-sm text-ds-small focus:outline-none focus:border-ds-accent focus:ring-1 focus:ring-ds-accent transition-colors duration-ds-fast';

    return (
        <Drawer open={open} onClose={onClose} title="个人中心" width="max-w-[440px]">
            {loading ? (
                <div className="h-full flex items-center justify-center">
                    <Spin/>
                </div>
            ) : failed || !profile ? (
                <div className="h-full flex flex-col items-center justify-center gap-ds-3">
                    <p className="text-ds-small text-ds-text-muted">加载失败</p>
                    <DsButton variant="ghost" onClick={load}>点击重试</DsButton>
                </div>
            ) : (
                <div className="flex flex-col gap-ds-4 h-full">
                    {/* 身份头部 */}
                    <div className="relative bg-gradient-to-br from-ds-accent to-ds-accent-hover rounded-ds-md p-ds-5 overflow-hidden flex-shrink-0">
                        <div
                            className="absolute inset-0 bg-white/5 rounded-full blur-3xl -top-12 -right-12 w-48 h-48 pointer-events-none"/>
                        <div className="relative flex items-center gap-ds-4">
                            <div
                                className="w-14 h-14 rounded-full bg-white/15 backdrop-blur-sm border-2 border-white/30 flex items-center justify-center flex-shrink-0">
                                <span className="text-white text-xl font-bold">{initials}</span>
                            </div>
                            <div className="min-w-0">
                                <div className="flex items-center gap-ds-2 flex-wrap">
                                    <span className="text-ds-body font-bold text-white truncate">{username}</span>
                                    <span
                                        className="px-ds-2 py-0.5 bg-white/20 text-white text-ds-nano font-semibold rounded-full backdrop-blur-sm">
                                        {primaryRoleLabel}
                                    </span>
                                </div>
                                <p className="text-ds-nano text-white/75 mt-1">用户 ID {userId}</p>
                            </div>
                        </div>
                    </div>

                    {/* 基本信息 */}
                    <section className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle px-ds-4 py-ds-2 flex-shrink-0">
                        {editing ? (
                            <>
                                <div className="flex items-center gap-ds-3 py-ds-2.5 border-b border-ds-border-subtle">
                                    <span className="text-ds-small text-ds-text-muted w-16 flex-shrink-0">邮箱</span>
                                    <input type="text" value={emailDraft}
                                           onChange={(e) => setEmailDraft(e.target.value)} placeholder="name@example.com"
                                           className={inputClass}/>
                                </div>
                                <div className="flex items-center gap-ds-3 py-ds-2.5 border-b border-ds-border-subtle">
                                    <span className="text-ds-small text-ds-text-muted w-16 flex-shrink-0">手机号</span>
                                    <input type="text" value={phoneDraft}
                                           onChange={(e) => setPhoneDraft(e.target.value)} placeholder="6~20 位数字"
                                           className={inputClass}/>
                                </div>
                                <div className="py-ds-2.5 border-b border-ds-border-subtle">
                                    <InfoRow label="创建时间" value={formatDateTime(profile.createdAt)}/>
                                </div>
                                {saveError && (
                                    <div
                                        className="bg-ds-danger-light text-ds-danger text-ds-small px-ds-3 py-ds-2 rounded-ds-sm mt-ds-2">
                                        {saveError}
                                    </div>
                                )}
                                <div className="flex items-center justify-end gap-ds-2 py-ds-3">
                                    <DsButton variant="ghost" onClick={cancelEdit} disabled={saving}>取消</DsButton>
                                    <DsButton variant="primary" onClick={handleSave} loading={saving}>保存</DsButton>
                                </div>
                            </>
                        ) : (
                            <>
                                <div className="flex items-center justify-between">
                                    <InfoRow label="邮箱" value={profile.email || '未设置'} empty={!profile.email}/>
                                    <DsButton variant="ghost" onClick={startEdit}>编辑</DsButton>
                                </div>
                                <InfoRow label="手机号" value={profile.phone || '未设置'} empty={!profile.phone}/>
                                <InfoRow label="创建时间" value={formatDateTime(profile.createdAt)}/>
                                {roles.length > 1 && (
                                    <div className="py-ds-2.5">
                                        <p className="text-ds-small text-ds-text-muted mb-ds-2">全部角色</p>
                                        <div className="flex flex-wrap gap-ds-2">
                                            {roles.map((code) => (
                                                <span key={code}
                                                      className="px-ds-2 py-0.5 bg-ds-accent-light text-ds-accent text-ds-nano font-semibold rounded">
                                                    {ROLE_OPTIONS.find((r) => r.value === code)?.label ?? code}
                                                </span>
                                            ))}
                                        </div>
                                    </div>
                                )}
                            </>
                        )}
                    </section>

                    {/* 账号安全：贴底 */}
                    <section className="mt-auto flex-shrink-0">
                        <h2 className="text-ds-nano font-bold text-ds-text-muted uppercase tracking-[0.8px] mb-ds-3">
                            账号安全
                        </h2>
                        <div
                            className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle px-ds-4 py-ds-3 flex items-center justify-between gap-ds-3">
                            <div className="min-w-0">
                                <p className="text-ds-small text-ds-text-primary font-medium">登录密码</p>
                                <p className="text-ds-nano text-ds-text-muted mt-0.5">定期更新密码可提高账号安全性</p>
                            </div>
                            <DsButton variant="ghost" onClick={() => setPwdModalOpen(true)}>修改密码</DsButton>
                        </div>
                    </section>
                </div>
            )}

            <ChangePasswordModal open={pwdModalOpen} onClose={() => setPwdModalOpen(false)}/>
        </Drawer>
    );
}
