import {useMemo, useState} from 'react';
import {Table, Tooltip} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import type {CreateUserParams, UpdateUserParams, UserVO} from '../../../api/auth';
import {createUser, getUsers, resetPassword, toggleUserStatus, updateUser} from '../../../api/auth';
import {formatDateTime} from '../../../utils/format';
import {notify} from '../../../utils/notify';
import usePagedList from '../../../hooks/usePagedList';
import UserModal from '../../../components/UserModal';
import DsButton from '../../../components/DsButton';
import DsIconButton from '../../../components/DsIconButton';
import DsModal from '../../../components/DsModal';
import ConfirmDialog from '../../../components/ConfirmDialog';
import Pagination from '../../../components/Pagination';
import SearchInput from '../../../components/SearchInput';
import DsFilterSelect from '../../../components/DsFilterSelect';
import {ROLE_OPTIONS as ROLE_OPTION_ITEMS} from '../../../constants/roles';
import DsToolbar from '../../../components/DsToolbar';
import DsTableEmpty from '../../../components/DsTableEmpty';
import DsSpinner from '../../../components/DsSpinner';
import {HiOutlineCheck, HiOutlineKey, HiOutlineNoSymbol, HiOutlinePencilSquare, HiOutlinePlus,} from 'react-icons/hi2';

const ROLE_OPTIONS = [
    {value: '', label: '全部角色'},
    ...ROLE_OPTION_ITEMS,
];

const STATUS_OPTIONS = [
    {value: '', label: '全部状态'},
    {value: 'enabled', label: '已启用'},
    {value: 'disabled', label: '已禁用'},
];

function getRoleName(code: string) {
    return ROLE_OPTIONS.find((r) => r.value === code)?.label || code;
}

export default function UsersPage() {
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
    const [toggleLoading, setToggleLoading] = useState(false);
    const [resetPwdLoading, setResetPwdLoading] = useState(false);
    const [userSubmitting, setUserSubmitting] = useState(false);

    const {
        list: users,
        total,
        page,
        pageSize,
        loading,
        setPage,
        setPageSize,
        applyQuery,
        reload,
    } = usePagedList<{ keyword: string; roleCode: string; status: string }, UserVO>({
        fetcher: async (query) => {
            const result = await getUsers({
                page: query.page,
                pageSize: query.pageSize,
                keyword: query.keyword || undefined,
                roleCode: query.roleCode || undefined,
                status: query.status || undefined,
            });
            return {list: result.data.records, total: result.data.total};
        },
        initialQuery: {keyword: '', roleCode: '', status: ''},
        defaultPageSize: 20,
    });

    const handleSearch = () => {
        applyQuery({keyword: draftKeyword, roleCode: draftRoleCode, status: draftStatus});
    };

    const handleReset = () => {
        setDraftKeyword('');
        setDraftRoleCode('');
        setDraftStatus('');
        applyQuery({keyword: '', roleCode: '', status: ''});
        setPageSize(20);
    };

    const handlePageChange = (nextPage: number, nextPageSize: number) => {
        if (nextPageSize !== pageSize) {
            setPageSize(nextPageSize);
        } else {
            setPage(nextPage);
        }
    };

    const handleCreate = async (data: CreateUserParams | UpdateUserParams) => {
        setUserSubmitting(true);
        const result = await createUser(data as CreateUserParams);
        notify.success('用户创建成功');
        setModalOpen(false);
        reload();
        setUserSubmitting(false);
        return result;
    };

    const handleUpdate = async (data: CreateUserParams | UpdateUserParams) => {
        if (!editUser) return;
        setUserSubmitting(true);
        const result = await updateUser(editUser.id, data as UpdateUserParams);
        notify.success('用户更新成功');
        setModalOpen(false);
        setEditUser(null);
        reload();
        setUserSubmitting(false);
        return result;
    };

    const handleToggle = async () => {
        if (!confirmTarget) return;
        setToggleLoading(true);
        await toggleUserStatus(confirmTarget.id);
        notify.success(confirmTarget.enabled ? '用户已禁用' : '用户已启用');
        setConfirmOpen(false);
        setConfirmTarget(null);
        reload();
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
        await resetPassword(resetPwdTarget.id, resetPwdValue);
        notify.success('密码已重置');
        setResetPwdInputOpen(false);
        setResetPwdTarget(null);
        setResetPwdValue('');
        reload();
        setResetPwdLoading(false);
    };

    const columns = useMemo<ColumnsType<UserVO>>(() => [
        {
            title: '用户名',
            dataIndex: 'username',
            ellipsis: true,
            render: (v: string) => (
                <span className="text-ds-body text-ds-text-primary font-medium">{v}</span>
            ),
        },
        {
            title: '角色',
            dataIndex: 'roles',
            render: (roles: string[]) => (
                <div className="flex flex-wrap gap-1">
                    {roles.map((role) => (
                        <span
                            key={role}
                            className="px-ds-1.5 py-0.5 bg-ds-accent-light text-ds-accent rounded text-ds-nano font-semibold"
                        >
                            {getRoleName(role)}
                        </span>
                    ))}
                </div>
            ),
        },
        {
            title: '邮箱',
            dataIndex: 'email',
            ellipsis: true,
            render: (v?: string) => (
                <span className="text-ds-body text-ds-text-secondary">{v || '-'}</span>
            ),
        },
        {
            title: '状态',
            dataIndex: 'enabled',
            render: (enabled: boolean) => (
                <span
                    className={`inline-flex items-center gap-ds-1 px-ds-2 py-ds-1 rounded-ds-full text-ds-small font-medium ${
                        enabled
                            ? 'bg-ds-success-light text-ds-success'
                            : 'bg-ds-danger-light text-ds-danger'
                    }`}
                >
                    <span
                        className={`w-1.5 h-1.5 rounded-full ${enabled ? 'bg-ds-success' : 'bg-ds-danger'}`}/>
                    {enabled ? '正常' : '已禁用'}
                </span>
            ),
        },
        {
            title: '创建时间',
            dataIndex: 'createdAt',
            render: (v: string) => (
                <span className="text-ds-small text-ds-text-secondary">{formatDateTime(v)}</span>
            ),
        },
        {
            title: '操作',
            align: 'center',
            width: 140,
            render: (_, user) => (
                <div className="flex items-center justify-center gap-1 whitespace-nowrap">
                    <Tooltip title="编辑">
                        <DsIconButton
                            tone="accent"
                            onClick={() => {
                                setEditUser(user);
                                setModalOpen(true);
                            }}
                            aria-label="编辑"
                        >
                            <HiOutlinePencilSquare size={16}/>
                        </DsIconButton>
                    </Tooltip>
                    <Tooltip title="重置密码">
                        <DsIconButton
                            tone="accent"
                            onClick={() => {
                                setResetPwdTarget(user);
                                setResetPwdOpen(true);
                            }}
                            aria-label="重置密码"
                        >
                            <HiOutlineKey size={16}/>
                        </DsIconButton>
                    </Tooltip>
                    <Tooltip title={user.enabled ? '禁用' : '启用'}>
                        <DsIconButton
                            tone={user.enabled ? 'danger' : 'success'}
                            onClick={() => {
                                setConfirmTarget(user);
                                setConfirmOpen(true);
                            }}
                            aria-label={user.enabled ? '禁用' : '启用'}
                        >
                            {user.enabled ? <HiOutlineNoSymbol size={16}/> :
                                <HiOutlineCheck size={16}/>}
                        </DsIconButton>
                    </Tooltip>
                </div>
            ),
        },
    ], []);

    return (
        <div className="h-full flex flex-col overflow-hidden">
            {/* Header */}
            <div className="flex items-center justify-between mb-ds-5 flex-shrink-0">
                <div>
                    <h1 className="text-ds-display text-ds-text-primary">用户管理</h1>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1">管理平台用户账号、角色分配与访问状态</p>
                </div>
                <DsButton
                    onClick={() => {
                        setEditUser(null);
                        setModalOpen(true);
                    }}
                >
                    <HiOutlinePlus size={16}/>
                    创建用户
                </DsButton>
            </div>

            {/* Toolbar */}
            <div
                className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle p-ds-3 mb-ds-4 flex-shrink-0">
                <DsToolbar
                    extra={
                        <>
                            <DsButton
                                onClick={handleSearch}
                                disabled={loading}
                            >
                                {loading ? '查询中...' : '查询'}
                            </DsButton>
                            <DsButton
                                variant="secondary"
                                onClick={handleReset}
                                disabled={loading}
                            >
                                重置
                            </DsButton>
                        </>
                    }
                >
                    <SearchInput
                        value={draftKeyword}
                        onChange={(e) => setDraftKeyword(e.target.value)}
                        onEnter={handleSearch}
                        placeholder="搜索用户名或邮箱..."
                    />

                    <DsFilterSelect
                        value={draftRoleCode}
                        onChange={(v) => setDraftRoleCode(v)}
                        options={ROLE_OPTIONS}
                        aria-label="按角色筛选"
                    />

                    <DsFilterSelect
                        value={draftStatus}
                        onChange={(v) => setDraftStatus(v)}
                        options={STATUS_OPTIONS}
                        aria-label="按状态筛选"
                    />
                </DsToolbar>
            </div>

            {/* 表格卡片 + 底部分页器：卡片高度封顶，表格区内部滚动，分页器始终可见 */}
            <div className="flex-1 min-h-0 flex flex-col">
                <div
                    className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden min-h-0 flex flex-col mb-ds-8">
                    <div className="flex-1 overflow-auto">
                        <Table<UserVO>
                            dataSource={users}
                            rowKey="id"
                            loading={loading}
                            pagination={false}
                            columns={columns}
                            className="prototype-table prototype-table-flush"
                            locale={{
                                emptyText: <DsTableEmpty description="暂无用户数据"/>,
                            }}
                        />
                    </div>

                    <Pagination
                        page={page}
                        pageSize={pageSize}
                        total={total}
                        onChange={handlePageChange}
                    />
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
            <DsModal
                open={resetPwdInputOpen}
                onClose={() => {
                    if (resetPwdLoading) return;
                    setResetPwdInputOpen(false);
                    setResetPwdTarget(null);
                }}
                title="输入新密码"
                closable={false}
                footer={
                    <>
                        <DsButton variant="ghost" onClick={() => {
                            setResetPwdInputOpen(false);
                            setResetPwdTarget(null);
                        }} disabled={resetPwdLoading}>
                            取消
                        </DsButton>
                        <DsButton onClick={handleResetPwdSubmit}
                                  disabled={resetPwdLoading || !resetPwdValue || resetPwdValue.length < 6}>
                            {resetPwdLoading && <DsSpinner/>}
                            {resetPwdLoading ? '处理中...' : '确认重置'}
                        </DsButton>
                    </>
                }
            >
                <p className="text-ds-body text-ds-text-secondary mb-ds-4">
                    为用户 <strong>"{resetPwdTarget?.username}"</strong> 设置新密码
                </p>
                <input
                    type="password"
                    value={resetPwdValue}
                    onChange={(e) => setResetPwdValue(e.target.value)}
                    placeholder="请输入新密码（至少6位）"
                    className="w-full px-ds-3 py-ds-2 border border-ds-border-subtle rounded-ds-sm text-ds-body focus:outline-none focus:border-ds-accent focus:ring-1 focus:ring-ds-accent transition-colors"
                    onKeyDown={(e) => {
                        if (e.key === 'Enter') handleResetPwdSubmit();
                    }}
                />
            </DsModal>
        </div>
    );
}
