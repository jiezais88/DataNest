import {useState, useEffect, useCallback} from 'react';
import {getUsers, createUser, updateUser, toggleUserStatus, resetPassword} from '../../../api/auth';
import type {UserVO, CreateUserParams, UpdateUserParams} from '../../../api/auth';
import UserModal from '../../../components/UserModal';
import ConfirmDialog from '../../../components/ConfirmDialog';
import {
    HiOutlinePlus,
    HiOutlineMagnifyingGlass,
    HiOutlinePencilSquare,
    HiOutlineNoSymbol,
    HiOutlineCheck,
    HiOutlineKey
} from 'react-icons/hi2';

const ROLE_OPTIONS = [
    {value: '', label: '全部角色'},
    {value: 'SUPER_ADMIN', label: '超级管理员'},
    {value: 'DATA_ENGINEER', label: '数据工程师'},
    {value: 'DATA_ANALYST', label: '数据分析师'},
    {value: 'GOVERNANCE_ADMIN', label: '治理管理员'},
];

const STATUS_OPTIONS = [
    {value: '', label: '全部状态'},
    {value: 'enabled', label: '已启用'},
    {value: 'disabled', label: '已禁用'},
];

export default function UsersPage() {
    const [users, setUsers] = useState<UserVO[]>([]);
    const [total, setTotal] = useState(0);
    const [page, setPage] = useState(1);
    const [keyword, setKeyword] = useState('');
    const [roleCode, setRoleCode] = useState('');
    const [status, setStatus] = useState('');
    const [modalOpen, setModalOpen] = useState(false);
    const [editUser, setEditUser] = useState<UserVO | null>(null);
    const [confirmOpen, setConfirmOpen] = useState(false);
    const [confirmTarget, setConfirmTarget] = useState<UserVO | null>(null);
    const [resetPwdTarget, setResetPwdTarget] = useState<UserVO | null>(null);
    const [resetPwdOpen, setResetPwdOpen] = useState(false);

    const pageSize = 20;

    const loadUsers = useCallback(async () => {
        const result = await getUsers({
            page,
            pageSize,
            keyword: keyword || undefined,
            roleCode: roleCode || undefined,
            status: status || undefined
        });
        if (result.code === 200) {
            setUsers(result.data.records);
            setTotal(result.data.total);
        }
    }, [page, keyword, roleCode, status]);

    useEffect(() => {
        loadUsers();
    }, [loadUsers]);

    const handleCreate = async (data: CreateUserParams | UpdateUserParams) => {
        const result = await createUser(data as CreateUserParams);
        if (result.code === 200) {
            setModalOpen(false);
            loadUsers();
        }
        return result;
    };

    const handleUpdate = async (data: CreateUserParams | UpdateUserParams) => {
        if (!editUser) return;
        const result = await updateUser(editUser.id, data as UpdateUserParams);
        if (result.code === 200) {
            setModalOpen(false);
            setEditUser(null);
            loadUsers();
        }
        return result;
    };

    const handleToggle = async () => {
        if (!confirmTarget) return;
        const result = await toggleUserStatus(confirmTarget.id);
        if (result.code === 200) {
            setConfirmOpen(false);
            setConfirmTarget(null);
            loadUsers();
        }
    };

    const handleResetPwd = async () => {
        if (!resetPwdTarget) return;
        const newPwd = prompt('请输入新密码（至少6位）：');
        if (!newPwd || newPwd.length < 6) return;
        const result = await resetPassword(resetPwdTarget.id, newPwd);
        if (result.code === 200) {
            setResetPwdOpen(false);
            setResetPwdTarget(null);
            loadUsers();
        }
    };

    const getRoleName = (code: string) => ROLE_OPTIONS.find((r) => r.value === code)?.label || code;

    const totalPages = Math.ceil(total / pageSize);

    return (
        <div>
            <div className="flex items-center justify-between mb-ds-5">
                <h1 className="text-ds-display text-ds-text-primary">用户管理</h1>
                <button onClick={() => {
                    setEditUser(null);
                    setModalOpen(true);
                }}
                        className="flex items-center gap-ds-1 px-ds-3 py-ds-2 bg-ds-accent hover:bg-ds-accent-hover text-white text-ds-small font-semibold rounded-ds-sm transition-colors ds-fast">
                    <HiOutlinePlus size={16}/>
                    创建用户
                </button>
            </div>

            {/* Toolbar */}
            <div className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle p-ds-3 mb-ds-4">
                <div className="flex items-center gap-ds-3 flex-wrap">
                    <div className="relative flex-1 min-w-[200px] max-w-[320px]">
                        <HiOutlineMagnifyingGlass size={16}
                                                  className="absolute left-3 top-1/2 -translate-y-1/2 text-ds-text-muted"/>
                        <input value={keyword} onChange={(e) => {
                            setKeyword(e.target.value);
                            setPage(1);
                        }}
                               className="w-full pl-9 pr-ds-3 py-ds-2 border border-ds-border-subtle rounded-ds-sm text-ds-body focus:outline-none focus:border-ds-accent focus:ring-1 focus:ring-ds-accent transition-colors ds-fast"
                               placeholder="搜索用户名或邮箱..."/>
                    </div>
                    <select value={roleCode} onChange={(e) => {
                        setRoleCode(e.target.value);
                        setPage(1);
                    }}
                            className="px-ds-3 py-ds-2 border border-ds-border-subtle rounded-ds-sm text-ds-small bg-white focus:outline-none focus:border-ds-accent">
                        {ROLE_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
                    </select>
                    <select value={status} onChange={(e) => {
                        setStatus(e.target.value);
                        setPage(1);
                    }}
                            className="px-ds-3 py-ds-2 border border-ds-border-subtle rounded-ds-sm text-ds-small bg-white focus:outline-none focus:border-ds-accent">
                        {STATUS_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
                    </select>
                    <span className="text-ds-small text-ds-text-muted ml-auto">
            共 {total} 个用户
          </span>
                </div>
            </div>

            {/* Table */}
            <div className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden">
                <table className="w-full">
                    <thead>
                    <tr className="border-b border-ds-border-subtle bg-ds-bg-hover/50">
                        <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-muted uppercase tracking-wider">用户名</th>
                        <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-muted uppercase tracking-wider">角色</th>
                        <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-muted uppercase tracking-wider">邮箱</th>
                        <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-muted uppercase tracking-wider">状态</th>
                        <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-muted uppercase tracking-wider">创建时间</th>
                        <th className="text-right px-ds-4 py-ds-3 text-ds-caption text-ds-text-muted uppercase tracking-wider">操作</th>
                    </tr>
                    </thead>
                    <tbody>
                    {users.map((user) => (
                        <tr key={user.id}
                            className="border-b border-ds-border-subtle last:border-0 hover:bg-ds-bg-hover/30 transition-colors">
                            <td className="px-ds-4 py-ds-3">
                                <span className="text-ds-body text-ds-text-primary font-medium">{user.username}</span>
                                <span className="text-ds-nano text-ds-text-muted ml-ds-1">#{user.id}</span>
                            </td>
                            <td className="px-ds-4 py-ds-3">
                                <div className="flex flex-wrap gap-1">
                                    {user.roles.map((role) => (
                                        <span key={role}
                                              className="px-ds-1 py-0.5 bg-ds-accent-light text-ds-accent rounded text-ds-nano font-semibold">
                        {getRoleName(role)}
                      </span>
                                    ))}
                                </div>
                            </td>
                            <td className="px-ds-4 py-ds-3 text-ds-body text-ds-text-secondary">
                                {user.email || '-'}
                            </td>
                            <td className="px-ds-4 py-ds-3">
                                <div className="flex items-center gap-ds-1">
                                    <span
                                        className={`w-1.5 h-1.5 rounded-full ${user.enabled ? 'bg-ds-success' : 'bg-ds-danger'}`}/>
                                    <span
                                        className={`text-ds-small font-medium ${user.enabled ? 'text-ds-success' : 'text-ds-danger'}`}>
                      {user.enabled ? '正常' : '已禁用'}
                    </span>
                                </div>
                            </td>
                            <td className="px-ds-4 py-ds-3 text-ds-small text-ds-text-muted">
                                {new Date(user.createdAt).toLocaleDateString('zh-CN')}
                            </td>
                            <td className="px-ds-4 py-ds-3">
                                <div className="flex items-center justify-end gap-1">
                                    <button onClick={() => {
                                        setEditUser(user);
                                        setModalOpen(true);
                                    }}
                                            className="p-1.5 text-ds-text-muted hover:text-ds-accent hover:bg-ds-accent-light rounded transition-colors"
                                            title="编辑">
                                        <HiOutlinePencilSquare size={16}/>
                                    </button>
                                    <button onClick={() => {
                                        setResetPwdTarget(user);
                                        setResetPwdOpen(true);
                                    }}
                                            className="p-1.5 text-ds-text-muted hover:text-ds-accent hover:bg-ds-accent-light rounded transition-colors"
                                            title="重置密码">
                                        <HiOutlineKey size={16}/>
                                    </button>
                                    <button onClick={() => {
                                        setConfirmTarget(user);
                                        setConfirmOpen(true);
                                    }}
                                            className={`p-1.5 rounded transition-colors ${user.enabled
                                                ? 'text-ds-text-muted hover:text-ds-danger hover:bg-ds-danger-light'
                                                : 'text-ds-text-muted hover:text-ds-success hover:bg-ds-success-light'}`}
                                            title={user.enabled ? '禁用' : '启用'}>
                                        {user.enabled ? <HiOutlineNoSymbol size={16}/> : <HiOutlineCheck size={16}/>}
                                    </button>
                                </div>
                            </td>
                        </tr>
                    ))}
                    {users.length === 0 && (
                        <tr>
                            <td colSpan={6} className="px-ds-4 py-ds-10 text-center text-ds-text-muted text-ds-body">
                                暂无用户数据
                            </td>
                        </tr>
                    )}
                    </tbody>
                </table>

                {/* Pagination */}
                {totalPages > 1 && (
                    <div className="flex items-center justify-between px-ds-4 py-ds-3 border-t border-ds-border-subtle">
            <span className="text-ds-small text-ds-text-muted">
              第 {page} / {totalPages} 页
            </span>
                        <div className="flex gap-ds-1">
                            <button disabled={page <= 1} onClick={() => setPage(page - 1)}
                                    className="px-ds-2 py-ds-1 text-ds-small text-ds-text-secondary hover:bg-ds-bg-hover rounded transition-colors disabled:opacity-40">
                                上一页
                            </button>
                            <button disabled={page >= totalPages} onClick={() => setPage(page + 1)}
                                    className="px-ds-2 py-ds-1 text-ds-small text-ds-text-secondary hover:bg-ds-bg-hover rounded transition-colors disabled:opacity-40">
                                下一页
                            </button>
                        </div>
                    </div>
                )}
            </div>

            {/* Modals */}
            <UserModal
                open={modalOpen}
                editUser={editUser}
                onClose={() => {
                    setModalOpen(false);
                    setEditUser(null);
                }}
                onSubmit={editUser ? handleUpdate : handleCreate}
            />

            <ConfirmDialog
                open={confirmOpen}
                title={confirmTarget?.enabled ? '禁用用户' : '启用用户'}
                message={`确定要${confirmTarget?.enabled ? '禁用' : '启用'}用户 "${confirmTarget?.username}" 吗？`}
                confirmLabel={confirmTarget?.enabled ? '确认禁用' : '确认启用'}
                danger={!!confirmTarget?.enabled}
                onConfirm={handleToggle}
                onCancel={() => {
                    setConfirmOpen(false);
                    setConfirmTarget(null);
                }}
            />

            <ConfirmDialog
                open={resetPwdOpen}
                title="重置密码"
                message={`确定要重置用户 "${resetPwdTarget?.username}" 的密码吗？`}
                confirmLabel="确认重置"
                danger
                onConfirm={handleResetPwd}
                onCancel={() => {
                    setResetPwdOpen(false);
                    setResetPwdTarget(null);
                }}
            />
        </div>
    );
}
