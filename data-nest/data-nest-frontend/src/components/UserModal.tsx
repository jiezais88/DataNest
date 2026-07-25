import {useEffect, useState} from 'react';
import {HiOutlineXMark} from 'react-icons/hi2';
import type {CreateUserParams, UpdateUserParams, UserVO} from '../api/auth';

interface Props {
    open: boolean;
    editUser?: UserVO | null;
    submitting?: boolean;
    onClose: () => void;
    onSubmit: (data: CreateUserParams | UpdateUserParams) => void;
}

const ALL_ROLES = [
    {code: 'SUPER_ADMIN', name: '超级管理员'},
    {code: 'DATA_ENGINEER', name: '数据工程师'},
    {code: 'DATA_ANALYST', name: '数据分析师'},
    {code: 'GOVERNANCE_ADMIN', name: '治理管理员'},
];

export default function UserModal({open, editUser, submitting = false, onClose, onSubmit}: Props) {
    const [username, setUsername] = useState('');
    const [password, setPassword] = useState('');
    const [selectedRoles, setSelectedRoles] = useState<string[]>([]);
    const [email, setEmail] = useState('');
    const [phone, setPhone] = useState('');

    useEffect(() => {
        if (editUser) {
            setUsername(editUser.username);
            setPassword('');
            setSelectedRoles(editUser.roles || []);
            setEmail(editUser.email || '');
            setPhone(editUser.phone || '');
        } else {
            setUsername('');
            setPassword('');
            setSelectedRoles([]);
            setEmail('');
            setPhone('');
        }
    }, [editUser, open]);

    if (!open) return null;

    const isEdit = !!editUser;

    const handleSubmit = () => {
        if (!isEdit && (!username || !password)) return;
        onSubmit({
            username,
            password: password || undefined,
            roles: selectedRoles,
            email: email || undefined,
            phone: phone || undefined,
        });
    };

    const toggleRole = (code: string) => {
        setSelectedRoles((prev) =>
            prev.includes(code) ? prev.filter((r) => r !== code) : [...prev, code]
        );
    };

    const canSubmit = isEdit
        ? selectedRoles.length > 0
        : username && password && password.length >= 6 && selectedRoles.length > 0;

    return (
        <div className="fixed inset-0 z-ds-dialog flex items-center justify-center">
            <div className="absolute inset-0 bg-black/20 backdrop-blur-sm" onClick={onClose}/>
            <div
                className="relative bg-ds-bg-surface rounded-ds-md shadow-ds-xl p-ds-6 w-[520px] max-h-[90vh] overflow-y-auto animate-in zoom-in-95">
                <div className="flex items-center justify-between mb-ds-4">
                    <h2 className="text-ds-heading text-ds-text-primary">
                        {isEdit ? '编辑用户' : '创建用户'}
                    </h2>
                    <button onClick={onClose}
                            className="text-ds-text-muted hover:text-ds-text-primary transition-colors">
                        <HiOutlineXMark size={20}/>
                    </button>
                </div>

                <div className="space-y-ds-4">
                    <div>
                        <label className="block text-ds-small text-ds-text-secondary mb-1">用户名</label>
                        <input name="username" value={username} onChange={(e) => setUsername(e.target.value)}
                               disabled={isEdit}
                               className="w-full px-ds-3 py-ds-2 border border-ds-border-subtle rounded-ds-sm text-ds-body focus:outline-none focus:border-ds-accent focus:ring-1 focus:ring-ds-accent transition-colors ds-fast disabled:bg-ds-bg-hover disabled:text-ds-text-muted"
                               placeholder="请输入用户名"/>
                    </div>

                    <div>
                        <label className="block text-ds-small text-ds-text-secondary mb-1">
                            密码 {isEdit && <span className="text-ds-text-muted">（不修改则留空）</span>}
                        </label>
                        <input name="password" type="password" value={password}
                               onChange={(e) => setPassword(e.target.value)}
                               className="w-full px-ds-3 py-ds-2 border border-ds-border-subtle rounded-ds-sm text-ds-body focus:outline-none focus:border-ds-accent focus:ring-1 focus:ring-ds-accent transition-colors ds-fast"
                               placeholder={isEdit ? '不修改则留空' : '请输入密码（6-20位）'}/>
                    </div>

                    <div>
                        <label className="block text-ds-small text-ds-text-secondary mb-1">角色</label>
                        <div className="flex flex-wrap gap-ds-1">
                            {ALL_ROLES.map((role) => {
                                const selected = selectedRoles.includes(role.code);
                                return (
                                    <button key={role.code} onClick={() => toggleRole(role.code)}
                                            className={`px-ds-2 py-ds-1 rounded-ds-full text-ds-small transition-colors ds-fast border
                      ${selected
                                                ? 'bg-ds-accent-light border-ds-accent text-ds-accent font-semibold'
                                                : 'bg-white border-ds-border-subtle text-ds-text-secondary hover:border-ds-accent'
                                            }`}>
                                        {role.name}
                                    </button>
                                );
                            })}
                        </div>
                    </div>

                    <div>
                        <label className="block text-ds-small text-ds-text-secondary mb-1">邮箱</label>
                        <input name="email" value={email} onChange={(e) => setEmail(e.target.value)}
                               className="w-full px-ds-3 py-ds-2 border border-ds-border-subtle rounded-ds-sm text-ds-body focus:outline-none focus:border-ds-accent focus:ring-1 focus:ring-ds-accent transition-colors ds-fast"
                               placeholder="请输入邮箱"/>
                    </div>

                    <div>
                        <label className="block text-ds-small text-ds-text-secondary mb-1">手机号</label>
                        <input name="phone" value={phone} onChange={(e) => setPhone(e.target.value)}
                               className="w-full px-ds-3 py-ds-2 border border-ds-border-subtle rounded-ds-sm text-ds-body focus:outline-none focus:border-ds-accent focus:ring-1 focus:ring-ds-accent transition-colors ds-fast"
                               placeholder="请输入手机号"/>
                    </div>
                </div>

                <div className="flex justify-end gap-ds-2 mt-ds-5">
                    <button onClick={onClose} disabled={submitting}
                            className="px-ds-4 py-ds-2 text-ds-small text-ds-text-secondary hover:bg-ds-bg-hover rounded-ds-sm transition-colors ds-fast disabled:opacity-50 disabled:cursor-not-allowed">
                        取消
                    </button>
                    <button type="button" onClick={handleSubmit} disabled={!canSubmit || submitting}
                            className="px-ds-4 py-ds-2 text-ds-small text-white bg-ds-accent hover:bg-ds-accent-hover rounded-ds-sm font-semibold transition-colors ds-fast disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-1.5">
                        {submitting && (
                            <svg className="animate-spin h-3.5 w-3.5" xmlns="http://www.w3.org/2000/svg" fill="none"
                                 viewBox="0 0 24 24">
                                <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor"
                                        strokeWidth="4"/>
                                <path className="opacity-75" fill="currentColor"
                                      d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"/>
                            </svg>
                        )}
                        {submitting ? '处理中...' : (isEdit ? '保存修改' : '创建用户')}
                    </button>
                </div>
            </div>
        </div>
    );
}
