import {useEffect, useMemo, useRef, useState} from 'react';
import {useSearchParams} from 'react-router-dom';
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
import {COL} from '../../../constants/table';
import DsToolbar from '../../../components/DsToolbar';
import DsTableEmpty from '../../../components/DsTableEmpty';
import DsSpinner from '../../../components/DsSpinner';
import DsStatusBadge from '../../../components/DsStatusBadge';
import {
    HiOutlineCheck,
    HiOutlineEye,
    HiOutlineKey,
    HiOutlineNoSymbol,
    HiOutlinePencilSquare,
    HiOutlinePlus,
} from 'react-icons/hi2';

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

    const [modalOpen, setModalOpen] = useState(false);
    const [modalMode, setModalMode] = useState<'create' | 'edit' | 'view'>('create');
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
        query,
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
        defaultPageSize: 10,
    });

    // L2：进页时从 URL 初始化筛选，进入子页/返回后筛选不丢
    const [searchParams, setSearchParams] = useSearchParams();
    const urlInitRef = useRef(false);
    useEffect(() => {
        if (urlInitRef.current) return;
        urlInitRef.current = true;
        const p = searchParams;
        const keyword = p.get('keyword') || '';
        const roleCode = ROLE_OPTIONS.some(o => o.value === p.get('roleCode')) ? p.get('roleCode') || '' : '';
        const status = STATUS_OPTIONS.some(o => o.value === p.get('status')) ? p.get('status') || '' : '';
        const pageNum = Number(p.get('page')) || 1;
        const pageSizeNum = Number(p.get('pageSize')) || 10;
        setDraftKeyword(keyword);
        if (pageSizeNum !== 10) setPageSize(pageSizeNum);
        applyQuery({keyword, roleCode, status});
        if (pageNum > 1) setPage(pageNum);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    // L2：筛选/分页变化时同步到 URL
    useEffect(() => {
        const next = new URLSearchParams();
        if (query.keyword) next.set('keyword', query.keyword);
        if (query.roleCode) next.set('roleCode', query.roleCode);
        if (query.status) next.set('status', query.status);
        next.set('page', String(page));
        if (pageSize !== 10) next.set('pageSize', String(pageSize));
        if (next.toString() === searchParams.toString()) return;
        setSearchParams(next, {replace: true});
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [query, page, pageSize]);

    const handleSearch = () => {
        applyQuery({...query, keyword: draftKeyword});
    };

    const handleReset = () => {
        setDraftKeyword('');
        applyQuery({keyword: '', roleCode: '', status: ''});
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
            width: COL.USERNAME,
            ellipsis: true,
            render: (v: string) => (
                <span className="text-ds-small text-ds-text-primary font-medium">{v}</span>
            ),
        },
        {
            title: '角色',
            dataIndex: 'roles',
            width: 150,
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
            width: 200,
            ellipsis: true,
            render: (v?: string) => (
                <span className="text-ds-small text-ds-text-secondary">{v || '-'}</span>
            ),
        },
        {
            title: '状态',
            dataIndex: 'enabled',
            width: COL.STATUS,
            render: (enabled: boolean) => (
                enabled
                    ? <DsStatusBadge variant="success" label="正常"/>
                    : <DsStatusBadge variant="danger" label="已禁用"/>
            ),
        },
        {
            title: '创建人',
            dataIndex: 'createdByName',
            width: COL.USERNAME,
            ellipsis: true,
            render: (v?: string) => (
                <span className="text-ds-small text-ds-text-secondary" title={v || ''}>{v || '-'}</span>
            ),
        },
        {
            title: '创建时间',
            dataIndex: 'createdAt',
            width: COL.DATETIME,
            render: (v: string) => (
                <span className="text-ds-small text-ds-text-secondary whitespace-nowrap">{formatDateTime(v)}</span>
            ),
        },
        {
            title: '修改人',
            dataIndex: 'updatedByName',
            width: COL.USERNAME,
            ellipsis: true,
            render: (v?: string) => (
                <span className="text-ds-small text-ds-text-secondary" title={v || ''}>{v || '-'}</span>
            ),
        },
        {
            title: '修改时间',
            dataIndex: 'updatedAt',
            width: COL.DATETIME,
            render: (v?: string) => (
                <span className="text-ds-small text-ds-text-secondary whitespace-nowrap">{formatDateTime(v)}</span>
            ),
        },
        {
            title: '操作',
            align: 'center',
            fixed: 'right' as const,
            width: COL.OPERATION_4,
            render: (_, user) => (
                <div className="flex items-center justify-center gap-1 whitespace-nowrap">
                    <Tooltip title="详情">
                        <DsIconButton
                            tone="default"
                            onClick={() => {
                                setEditUser(user);
                                setModalMode('view');
                                setModalOpen(true);
                            }}
                            aria-label="详情"
                        >
                            <HiOutlineEye size={14}/>
                        </DsIconButton>
                    </Tooltip>
                    <Tooltip title="编辑">
                        <DsIconButton
                            tone="accent"
                            onClick={() => {
                                setEditUser(user);
                                setModalMode('edit');
                                setModalOpen(true);
                            }}
                            aria-label="编辑"
                        >
                            <HiOutlinePencilSquare size={14}/>
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
                            <HiOutlineKey size={14}/>
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
                            {user.enabled ? <HiOutlineNoSymbol size={14}/> :
                                <HiOutlineCheck size={14}/>}
                        </DsIconButton>
                    </Tooltip>
                </div>
            ),
        },
    ], []);

    return (
        <div className="flex flex-col">
            {/* Header */}
            <div className="flex items-center justify-between mb-ds-5 flex-shrink-0">
                <div>
                    <h1 className="text-ds-display text-ds-text-primary">用户管理</h1>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1">管理平台用户账号、角色分配与访问状态</p>
                </div>
                <DsButton
                    onClick={() => {
                        setEditUser(null);
                        setModalMode('create');
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
                        value={query.roleCode}
                        onChange={(v) => applyQuery({...query, roleCode: v})}
                        options={ROLE_OPTIONS}
                        aria-label="按角色筛选"
                    />

                    <DsFilterSelect
                        value={query.status}
                        onChange={(v) => applyQuery({...query, status: v})}
                        options={STATUS_OPTIONS}
                        aria-label="按状态筛选"
                    />
                </DsToolbar>
            </div>

            {/* 表格卡片 + 底部分页器：卡片随内容高度，分页器紧贴表格；内容超高时整页滚动 */}
            <div className="flex flex-col">
                <div
                    className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden flex flex-col mb-ds-8">
                    <div className="overflow-x-auto">
                        <Table<UserVO>
                            dataSource={users}
                            rowKey="id"
                            loading={loading}
                            pagination={false}
                            scroll={{x: 1290}}
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
                mode={modalMode}
                submitting={userSubmitting}
                onClose={() => {
                    setModalOpen(false);
                    setEditUser(null);
                    setModalMode('create');
                }}
                onSubmit={modalMode === 'view' ? undefined : (editUser ? handleUpdate : handleCreate)}
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
