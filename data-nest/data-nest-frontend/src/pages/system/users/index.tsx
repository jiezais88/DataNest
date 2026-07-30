import {useCallback, useEffect, useState} from 'react';
import {message} from 'antd';
import type {CreateUserParams, UpdateUserParams, UserVO} from '../../../api/auth';
import {createUser, getUsers, resetPassword, toggleUserStatus, updateUser} from '../../../api/auth';
import UserModal from '../../../components/UserModal';
import ConfirmDialog from '../../../components/ConfirmDialog';
import Pagination from '../../../components/Pagination';
import SearchInput from '../../../components/SearchInput';
import {
    HiChevronRight,
    HiOutlineCheck,
    HiOutlineKey,
    HiOutlineNoSymbol,
    HiOutlinePencilSquare,
    HiOutlinePlus,
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

function formatDateTime(value: string) {
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return '-';
    const pad = (n: number) => String(n).padStart(2, '0');
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

export default function UsersPage() {
    const [users, setUsers] = useState<UserVO[]>([]);
    const [total, setTotal] = useState(0);
    const [page, setPage] = useState(1);
    const [pageSize, setPageSize] = useState(20);
    const [keyword, setKeyword] = useState('');
    const [roleCode, setRoleCode] = useState('');
    const [status, setStatus] = useState('');

    const [draftKeyword, setDraftKeyword] = useState('');
    const [draftRoleCode, setDraftRoleCode] = useState('');
    const [draftStatus, setDraftStatus] = useState('');

    const [modalOpen, setModalOpen] = useState(false);
    const [editUser, setEditUser] = useState<UserVO | null>(null);
    const [confirmOpen, setConfirmOpen] = useState(false);
    const [confirmTarget, setConfirmTarget] = useState<UserVO | null>(null);
    const [resetPwdTarget, setResetPwdTarget] = useState<UserVO | null>(null);
    const [resetPwdOpen, setResetPwdOpen] = useState(false);
    const [resetPwdInputOpen, setResetPwdInputOpen] = useState(false);
    const [resetPwdValue, setResetPwdValue] = useState('');
    const [loading, setLoading] = useState(false);
    const [searchTrigger, setSearchTrigger] = useState(0);
    const [toggleLoading, setToggleLoading] = useState(false);
    const [resetPwdLoading, setResetPwdLoading] = useState(false);
    const [userSubmitting, setUserSubmitting] = useState(false);

    const loadUsers = useCallback(async () => {
        setLoading(true);
        try {
            const result = await getUsers({
                page,
                pageSize,
                keyword: keyword || undefined,
                roleCode: roleCode || undefined,
                status: status || undefined,
            });
            if (result.code === 200) {
                setUsers(result.data.records);
                setTotal(result.data.total);
            }
        } finally {
            setLoading(false);
        }
    }, [page, pageSize, keyword, roleCode, status, searchTrigger]);

    useEffect(() => {
        loadUsers();
    }, [loadUsers]);

    const handleSearch = () => {
        setKeyword(draftKeyword);
        setRoleCode(draftRoleCode);
        setStatus(draftStatus);
        setPage(1);
        setSearchTrigger((v) => v + 1);
    };

    const handleReset = () => {
        setDraftKeyword('');
        setDraftRoleCode('');
        setDraftStatus('');
        setKeyword('');
        setRoleCode('');
        setStatus('');
        setPage(1);
        setPageSize(20);
    };

    const handlePageChange = (nextPage: number, nextPageSize: number) => {
        setPage(nextPage);
        setPageSize(nextPageSize);
    };

    const handleCreate = async (data: CreateUserParams | UpdateUserParams) => {
        setUserSubmitting(true);
        const result = await createUser(data as CreateUserParams);
        if (result.code === 200) {
            message.success('用户创建成功');
            setModalOpen(false);
            loadUsers();
        }
        setUserSubmitting(false);
        return result;
    };

    const handleUpdate = async (data: CreateUserParams | UpdateUserParams) => {
        if (!editUser) return;
        setUserSubmitting(true);
        const result = await updateUser(editUser.id, data as UpdateUserParams);
        if (result.code === 200) {
            message.success('用户更新成功');
            setModalOpen(false);
            setEditUser(null);
            loadUsers();
        }
        setUserSubmitting(false);
        return result;
    };

    const handleToggle = async () => {
        if (!confirmTarget) return;
        setToggleLoading(true);
        const result = await toggleUserStatus(confirmTarget.id);
        if (result.code === 200) {
            message.success(confirmTarget.enabled ? '用户已禁用' : '用户已启用');
            setConfirmOpen(false);
            setConfirmTarget(null);
            loadUsers();
        }
        setToggleLoading(false);
    };

    const handleResetPwd = async () => {
        if (!resetPwdTarget) return;
        // 第一步：确认后显示密码输入框
        setResetPwdOpen(false);
        setResetPwdValue('');
        setResetPwdInputOpen(true);
    };

    const handleResetPwdSubmit = async () => {
        if (!resetPwdTarget || !resetPwdValue || resetPwdValue.length < 6) return;
        setResetPwdLoading(true);
        const result = await resetPassword(resetPwdTarget.id, resetPwdValue);
        if (result.code === 200) {
            message.success('密码已重置');
            setResetPwdInputOpen(false);
            setResetPwdTarget(null);
            setResetPwdValue('');
            loadUsers();
        }
        setResetPwdLoading(false);
    };

    const getRoleName = (code: string) => ROLE_OPTIONS.find((r) => r.value === code)?.label || code;

    return (
        <div className="h-full flex flex-col overflow-hidden">
            {/* Header */}
            <div className="flex items-center justify-between mb-ds-5 flex-shrink-0">
                <div>
                    <h1 className="text-ds-display text-ds-text-primary">用户管理</h1>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1">管理平台用户账号、角色分配与访问状态</p>
                </div>
                <button
                    onClick={() => {
                        setEditUser(null);
                        setModalOpen(true);
                    }}
                    className="flex items-center gap-ds-1 px-ds-3 py-ds-2 bg-ds-accent hover:bg-ds-accent-hover text-white text-ds-small font-semibold rounded-ds-sm transition-colors ds-fast"
                >
                    <HiOutlinePlus size={16}/>
                    创建用户
                </button>
            </div>

            {/* Toolbar */}
            <div
                className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle p-ds-3 mb-ds-4 flex-shrink-0">
                <div className="flex items-center gap-ds-3 flex-wrap">
                    <SearchInput
                        value={draftKeyword}
                        onChange={(e) => setDraftKeyword(e.target.value)}
                        onEnter={handleSearch}
                        placeholder="搜索用户名或邮箱..."
                    />

                    <div className="relative">
                        <select
                            value={draftRoleCode}
                            onChange={(e) => setDraftRoleCode(e.target.value)}
                            aria-label="按角色筛选"
                            className="appearance-none min-w-[140px] pl-ds-3 pr-9 py-ds-2 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-small text-ds-text-primary focus:outline-none focus-visible:border-ds-accent cursor-pointer"
                        >
                            {ROLE_OPTIONS.map((o) => (
                                <option key={o.value} value={o.value}>{o.label}</option>
                            ))}
                        </select>
                        <HiChevronRight
                            size={14}
                            className="absolute right-3 top-1/2 -translate-y-1/2 rotate-90 text-ds-text-muted pointer-events-none"
                        />
                    </div>

                    <div className="relative">
                        <select
                            value={draftStatus}
                            onChange={(e) => setDraftStatus(e.target.value)}
                            aria-label="按状态筛选"
                            className="appearance-none min-w-[140px] pl-ds-3 pr-9 py-ds-2 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-small text-ds-text-primary focus:outline-none focus-visible:border-ds-accent cursor-pointer"
                        >
                            {STATUS_OPTIONS.map((o) => (
                                <option key={o.value} value={o.value}>{o.label}</option>
                            ))}
                        </select>
                        <HiChevronRight
                            size={14}
                            className="absolute right-3 top-1/2 -translate-y-1/2 rotate-90 text-ds-text-muted pointer-events-none"
                        />
                    </div>

                    <div className="flex items-center gap-ds-2 ml-auto">
                        <button
                            onClick={handleSearch}
                            disabled={loading}
                            className="px-ds-4 py-ds-2 bg-ds-accent hover:bg-ds-accent-hover disabled:opacity-60 disabled:cursor-not-allowed text-white text-ds-small font-semibold rounded-ds-sm transition-colors ds-fast"
                        >
                            {loading ? '查询中...' : '查询'}
                        </button>
                        <button
                            onClick={handleReset}
                            disabled={loading}
                            className="px-ds-4 py-ds-2 bg-white border border-ds-border-subtle hover:border-ds-border-strong disabled:opacity-60 disabled:cursor-not-allowed text-ds-text-secondary text-ds-small font-semibold rounded-ds-sm transition-colors ds-fast"
                        >
                            重置
                        </button>
                    </div>
                </div>
            </div>

            {/* Table */}
            <div className="flex-1 min-h-0 overflow-auto">
                <div
                    className="ds-table-card">
                    <div className="ds-table-scroll">
                        <table className="ds-table">
                            <thead>
                            <tr>
                                <th className="text-left text-ds-caption text-ds-text-primary uppercase tracking-wider">用户名</th>
                                <th className="text-left text-ds-caption text-ds-text-primary uppercase tracking-wider">角色</th>
                                <th className="text-left text-ds-caption text-ds-text-primary uppercase tracking-wider">邮箱</th>
                                <th className="text-left text-ds-caption text-ds-text-primary uppercase tracking-wider">状态</th>
                                <th className="text-left text-ds-caption text-ds-text-primary uppercase tracking-wider">创建时间</th>
                                <th className="text-center text-ds-caption text-ds-text-primary uppercase tracking-wider">操作</th>
                            </tr>
                            </thead>
                            <tbody>
                            {users.map((user) => (
                                <tr key={user.id}>
                                    <td className="ds-table-cell-truncate" title={user.username}>
                                        <span
                                            className="text-ds-body text-ds-text-primary font-medium">{user.username}</span>
                                    </td>
                                    <td>
                                        <div className="flex flex-wrap gap-1">
                                            {user.roles.map((role) => (
                                                <span
                                                    key={role}
                                                    className="px-ds-1.5 py-0.5 bg-ds-accent-light text-ds-accent rounded text-ds-nano font-semibold"
                                                >
                                                    {getRoleName(role)}
                                                </span>
                                            ))}
                                        </div>
                                    </td>
                                    <td className="ds-table-cell-truncate" title={user.email || '-'}>
                                        <span className="text-ds-body text-ds-text-secondary">{user.email || '-'}</span>
                                    </td>
                                    <td>
                                        <span
                                            className={`inline-flex items-center gap-ds-1 px-ds-2 py-ds-1 rounded-ds-full text-ds-small font-medium ${
                                                user.enabled
                                                    ? 'bg-ds-success-light text-ds-success'
                                                    : 'bg-ds-danger-light text-ds-danger'
                                            }`}
                                        >
                                            <span
                                                className={`w-1.5 h-1.5 rounded-full ${user.enabled ? 'bg-ds-success' : 'bg-ds-danger'}`}/>
                                            {user.enabled ? '正常' : '已禁用'}
                                        </span>
                                    </td>
                                    <td className="text-ds-small text-ds-text-secondary">
                                        {formatDateTime(user.createdAt)}
                                    </td>
                                    <td className="ds-table-cell-no-truncate">
                                        <div className="flex items-center justify-center w-full gap-1">
                                            <button
                                                onClick={() => {
                                                    setEditUser(user);
                                                    setModalOpen(true);
                                                }}
                                                className="p-1.5 text-ds-text-muted hover:text-ds-accent hover:bg-ds-accent-light rounded transition-colors"
                                                title="编辑"
                                                aria-label="编辑"
                                            >
                                                <HiOutlinePencilSquare size={16}/>
                                            </button>
                                            <button
                                                onClick={() => {
                                                    setResetPwdTarget(user);
                                                    setResetPwdOpen(true);
                                                }}
                                                className="p-1.5 text-ds-text-muted hover:text-ds-accent hover:bg-ds-accent-light rounded transition-colors"
                                                title="重置密码"
                                                aria-label="重置密码"
                                            >
                                                <HiOutlineKey size={16}/>
                                            </button>
                                            <button
                                                onClick={() => {
                                                    setConfirmTarget(user);
                                                    setConfirmOpen(true);
                                                }}
                                                className={`p-1.5 rounded transition-colors ${
                                                    user.enabled
                                                        ? 'text-ds-text-muted hover:text-ds-danger hover:bg-ds-danger-light'
                                                        : 'text-ds-text-muted hover:text-ds-success hover:bg-ds-success-light'
                                                }`}
                                                title={user.enabled ? '禁用' : '启用'}
                                                aria-label={user.enabled ? '禁用' : '启用'}
                                            >
                                                {user.enabled ? <HiOutlineNoSymbol size={16}/> :
                                                    <HiOutlineCheck size={16}/>}
                                            </button>
                                        </div>
                                    </td>
                                </tr>
                            ))}
                            {users.length === 0 && !loading && (
                                <tr>
                                    <td colSpan={6}
                                        className="py-ds-16 text-center text-ds-text-muted text-ds-body">
                                        暂无用户数据
                                    </td>
                                </tr>
                            )}
                            </tbody>
                        </table>
                    </div>

                    <Pagination
                        page={page}
                        pageSize={pageSize}
                        total={total}
                        onChange={handlePageChange}
                    />

                    {loading && (
                        <div
                            className="absolute inset-0 z-20 bg-ds-bg-surface/70 backdrop-blur-[1px] flex flex-col items-center justify-center gap-ds-2">
                            <svg className="animate-spin h-6 w-6 text-ds-accent" xmlns="http://www.w3.org/2000/svg"
                                 fill="none" viewBox="0 0 24 24">
                                <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor"
                                        strokeWidth="4"/>
                                <path className="opacity-75" fill="currentColor"
                                      d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"/>
                            </svg>
                            <span className="text-ds-small text-ds-text-secondary">加载中...</span>
                        </div>
                    )}
                </div>
            </div>

            {/* Modals */}
            <UserModal
                open={modalOpen}
                editUser={editUser}
                submitting={userSubmitting}
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
                loading={toggleLoading}
                onConfirm={handleToggle}
                onCancel={() => {
                    if (toggleLoading) return;
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

            {/* 重置密码 - 输入新密码 */}
            {resetPwdInputOpen && (
                <div className="fixed inset-0 z-ds-dialog flex items-center justify-center">
                    <div className="absolute inset-0 bg-black/20 backdrop-blur-sm" onClick={() => {
                        if (resetPwdLoading) return;
                        setResetPwdInputOpen(false);
                        setResetPwdTarget(null);
                    }}/>
                    <div
                        className="relative bg-ds-bg-surface rounded-ds-md shadow-ds-xl p-ds-6 w-[420px] animate-in zoom-in-95">
                        <h3 className="text-ds-subhead text-ds-text-primary mb-ds-2">输入新密码</h3>
                        <p className="text-ds-body text-ds-text-secondary mb-ds-4">
                            为用户 <strong>"{resetPwdTarget?.username}"</strong> 设置新密码
                        </p>
                        <input
                            type="password"
                            value={resetPwdValue}
                            onChange={(e) => setResetPwdValue(e.target.value)}
                            placeholder="请输入新密码（至少6位）"
                            className="w-full px-ds-3 py-ds-2 border border-ds-border-subtle rounded-ds-sm text-ds-body focus:outline-none focus:border-ds-accent focus:ring-1 focus:ring-ds-accent transition-colors mb-ds-5"
                            onKeyDown={(e) => {
                                if (e.key === 'Enter') handleResetPwdSubmit();
                            }}
                        />
                        <div className="flex justify-end gap-ds-2">
                            <button onClick={() => {
                                setResetPwdInputOpen(false);
                                setResetPwdTarget(null);
                            }} disabled={resetPwdLoading}
                                    className="px-ds-4 py-ds-2 text-ds-small text-ds-text-secondary hover:bg-ds-bg-hover rounded-ds-sm transition-colors ds-fast disabled:opacity-50">
                                取消
                            </button>
                            <button onClick={handleResetPwdSubmit}
                                    disabled={resetPwdLoading || !resetPwdValue || resetPwdValue.length < 6}
                                    className="px-ds-4 py-ds-2 text-ds-small text-white rounded-ds-sm font-semibold transition-colors ds-fast bg-ds-accent hover:bg-ds-accent-hover disabled:opacity-60 disabled:cursor-not-allowed flex items-center gap-1.5">
                                {resetPwdLoading && (
                                    <svg className="animate-spin h-3.5 w-3.5" xmlns="http://www.w3.org/2000/svg"
                                         fill="none" viewBox="0 0 24 24">
                                        <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor"
                                                strokeWidth="4"/>
                                        <path className="opacity-75" fill="currentColor"
                                              d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"/>
                                    </svg>
                                )}
                                {resetPwdLoading ? '处理中...' : '确认重置'}
                            </button>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
}
