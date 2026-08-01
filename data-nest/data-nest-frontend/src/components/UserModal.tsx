import {useEffect, useState} from 'react';
import type {CreateUserParams, UpdateUserParams, UserVO} from '../api/auth';
import DsButton from './DsButton';
import DsModal from './DsModal';
import DsSpinner from './DsSpinner';
import {ROLE_OPTIONS} from '../constants/roles';

interface Props {
    open: boolean;
    editUser?: UserVO | null;
    submitting?: boolean;
    onClose: () => void;
    onSubmit: (data: CreateUserParams | UpdateUserParams) => void;
}

const ALL_ROLES = ROLE_OPTIONS.map((o) => ({code: o.value, name: o.label}));

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
        <DsModal
            open={open}
            onClose={onClose}
            title={isEdit ? '编辑用户' : '创建用户'}
            width="w-[520px]"
            footer={
                <>
                    <DsButton variant="ghost" onClick={onClose} disabled={submitting}>
                        取消
                    </DsButton>
                    <DsButton type="button" onClick={handleSubmit} disabled={!canSubmit || submitting}>
                        {submitting && <DsSpinner/>}
                        {submitting ? '处理中...' : (isEdit ? '保存修改' : '创建用户')}
                    </DsButton>
                </>
            }
        >
            <div className="space-y-ds-4">
                    <div>
                        <label className="block text-ds-small text-ds-text-secondary mb-1">用户名</label>
                        <input name="username" value={username} onChange={(e) => setUsername(e.target.value)}
                               disabled={isEdit}
                               className="w-full px-ds-3 py-ds-2 border border-ds-border-subtle rounded-ds-sm text-ds-body focus:outline-none focus:border-ds-accent focus:ring-1 focus:ring-ds-accent transition-colors duration-ds-fast disabled:bg-ds-bg-hover disabled:text-ds-text-muted"
                               placeholder="请输入用户名"/>
                    </div>

                    <div>
                        <label className="block text-ds-small text-ds-text-secondary mb-1">
                            密码 {isEdit && <span className="text-ds-text-muted">（不修改则留空）</span>}
                        </label>
                        <input name="password" type="password" value={password}
                               onChange={(e) => setPassword(e.target.value)}
                               className="w-full px-ds-3 py-ds-2 border border-ds-border-subtle rounded-ds-sm text-ds-body focus:outline-none focus:border-ds-accent focus:ring-1 focus:ring-ds-accent transition-colors duration-ds-fast"
                               placeholder={isEdit ? '不修改则留空' : '请输入密码（6-20位）'}/>
                    </div>

                    <div>
                        <label className="block text-ds-small text-ds-text-secondary mb-1">角色</label>
                        <div className="flex flex-wrap gap-ds-1">
                            {ALL_ROLES.map((role) => {
                                const selected = selectedRoles.includes(role.code);
                                return (
                                    <button key={role.code} onClick={() => toggleRole(role.code)}
                                            className={`px-ds-2 py-ds-1 rounded-ds-full text-ds-small transition-colors duration-ds-fast border
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
                               className="w-full px-ds-3 py-ds-2 border border-ds-border-subtle rounded-ds-sm text-ds-body focus:outline-none focus:border-ds-accent focus:ring-1 focus:ring-ds-accent transition-colors duration-ds-fast"
                               placeholder="请输入邮箱"/>
                    </div>

                    <div>
                        <label className="block text-ds-small text-ds-text-secondary mb-1">手机号</label>
                        <input name="phone" value={phone} onChange={(e) => setPhone(e.target.value)}
                               className="w-full px-ds-3 py-ds-2 border border-ds-border-subtle rounded-ds-sm text-ds-body focus:outline-none focus:border-ds-accent focus:ring-1 focus:ring-ds-accent transition-colors duration-ds-fast"
                               placeholder="请输入手机号"/>
                    </div>
            </div>
        </DsModal>
    );
}
