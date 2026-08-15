import {useCallback, useEffect, useMemo, useState} from 'react';
import {Input, Table, Tooltip} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import {createRole, deleteRole, getPermissions, getRoles, updateRole} from '@/api/role';
import type {PermissionVO, RoleVO} from '@/types/role';
import {formatDateTime} from '@/utils/format';
import {notify} from '@/utils/notify';
import DsButton from '@/components/DsButton';
import DsModal from '@/components/DsModal';
import DsIconButton from '@/components/DsIconButton';
import ConfirmDialog from '@/components/ConfirmDialog';
import DsTableEmpty from '@/components/DsTableEmpty';
import DsStatusBadge from '@/components/DsStatusBadge';
import PermissionTree from '@/components/PermissionTree';
import {HiOutlineEye, HiOutlinePencilSquare, HiOutlinePlus, HiOutlineTrash} from 'react-icons/hi2';

/** 角色管理（Sprint 11 F2）：预置角色只读，自定义角色创建/编辑/删除 + 功能权限点勾选 */
export default function RolesPage() {
    const [roles, setRoles] = useState<RoleVO[]>([]);
    const [loading, setLoading] = useState(false);
    const [permissions, setPermissions] = useState<PermissionVO[]>([]);

    const [modalOpen, setModalOpen] = useState(false);
    const [modalMode, setModalMode] = useState<'create' | 'edit'>('create');
    const [editRole, setEditRole] = useState<RoleVO | null>(null);
    const [submitting, setSubmitting] = useState(false);

    const [name, setName] = useState('');
    const [code, setCode] = useState('');
    const [description, setDescription] = useState('');
    const [checkedKeys, setCheckedKeys] = useState<React.Key[]>([]);

    const [deleteTarget, setDeleteTarget] = useState<RoleVO | null>(null);
    const [deleteLoading, setDeleteLoading] = useState(false);

    const [detailRole, setDetailRole] = useState<RoleVO | null>(null);

    const loadRoles = useCallback(async () => {
        setLoading(true);
        try {
            setRoles(await getRoles());
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        loadRoles();
        getPermissions().then(setPermissions).catch(() => undefined);
    }, [loadRoles]);

    const openCreate = () => {
        setModalMode('create');
        setEditRole(null);
        setName('');
        setCode('');
        setDescription('');
        setCheckedKeys([]);
        setModalOpen(true);
    };

    const openEdit = (role: RoleVO) => {
        setModalMode('edit');
        setEditRole(role);
        setName(role.name);
        setCode(role.code);
        setDescription(role.description || '');
        setCheckedKeys(role.permissions);
        setModalOpen(true);
    };

    const openDetail = (role: RoleVO) => setDetailRole(role);

    const handleSubmit = async () => {
        if (!name.trim() || name.trim().length < 2 || name.trim().length > 20) {
            notify.error('角色名称需 2~20 个字符');
            return;
        }
        if (modalMode === 'create' && !/^[A-Za-z][A-Za-z0-9_]{1,29}$/.test(code.trim())) {
            notify.error('角色编码需以字母开头，仅含字母/数字/下划线（2~30 位）');
            return;
        }
        if (checkedKeys.length === 0) {
            notify.error('请至少勾选一项功能权限');
            return;
        }
        const perms = checkedKeys.filter((k) => typeof k === 'string' && !k.startsWith('module:')) as string[];
        setSubmitting(true);
        try {
            if (modalMode === 'create') {
                await createRole({name: name.trim(), code: code.trim(), description: description.trim(), permissions: perms});
                notify.success('角色创建成功');
            } else if (editRole) {
                await updateRole(editRole.id, {description: description.trim(), permissions: perms});
                notify.success('角色更新成功');
            }
            setModalOpen(false);
            loadRoles();
        } finally {
            setSubmitting(false);
        }
    };

    const handleDelete = async () => {
        if (!deleteTarget) return;
        setDeleteLoading(true);
        try {
            await deleteRole(deleteTarget.id);
            notify.success('角色已删除');
            setDeleteTarget(null);
            loadRoles();
        } finally {
            setDeleteLoading(false);
        }
    };

    const columns = useMemo<ColumnsType<RoleVO>>(() => [
        {
            title: '角色名称',
            dataIndex: 'name',
            width: 180,
            render: (v: string) => <span className="text-ds-small text-ds-text-primary font-medium">{v}</span>,
        },
        {
            title: '角色编码',
            dataIndex: 'code',
            width: 200,
            render: (v: string) => <span className="text-ds-small text-ds-text-secondary font-mono">{v}</span>,
        },
        {
            title: '类型',
            dataIndex: 'builtin',
            width: 110,
            render: (builtin: boolean) => builtin
                ? <DsStatusBadge variant="pending" label="预置"/>
                : <DsStatusBadge variant="success" label="自定义"/>,
        },
        {
            title: '权限点数',
            dataIndex: 'permissions',
            width: 100,
            render: (v: string[]) => <span className="text-ds-small text-ds-text-secondary">{v.length}</span>,
        },
        {
            title: '描述',
            dataIndex: 'description',
            ellipsis: true,
            render: (v?: string) => <span className="text-ds-small text-ds-text-secondary">{v || '-'}</span>,
        },
        {
            title: '创建时间',
            dataIndex: 'createdAt',
            width: 170,
            render: (v: string) => (
                <span className="text-ds-small text-ds-text-secondary whitespace-nowrap">{formatDateTime(v)}</span>
            ),
        },
        {
            title: '操作',
            align: 'center',
            fixed: 'right' as const,
            width: 160,
            render: (_, role) => (
                <div className="flex items-center justify-center gap-1 whitespace-nowrap">
                    <Tooltip title="详情">
                        <DsIconButton
                            tone="default"
                            onClick={() => openDetail(role)}
                            aria-label="详情"
                        >
                            <HiOutlineEye size={14}/>
                        </DsIconButton>
                    </Tooltip>
                    <Tooltip title={role.builtin ? '预置角色不可编辑' : '编辑'}>
                        <DsIconButton
                            tone="accent"
                            disabled={role.builtin}
                            onClick={() => openEdit(role)}
                            aria-label="编辑"
                        >
                            <HiOutlinePencilSquare size={14}/>
                        </DsIconButton>
                    </Tooltip>
                    <Tooltip title={role.builtin ? '预置角色不可删除' : '删除'}>
                        <DsIconButton
                            tone="danger"
                            disabled={role.builtin}
                            onClick={() => setDeleteTarget(role)}
                            aria-label="删除"
                        >
                            <HiOutlineTrash size={14}/>
                        </DsIconButton>
                    </Tooltip>
                </div>
            ),
        },
        // eslint-disable-next-line react-hooks/exhaustive-deps
    ], []);

    return (
        <div className="flex flex-col">
            <div className="flex items-center justify-between mb-ds-5 flex-shrink-0">
                <div>
                    <h1 className="text-ds-display text-ds-text-primary">角色管理</h1>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1">
                        预置角色只读；自定义角色可创建/编辑/删除，并配置按钮级功能权限
                    </p>
                </div>
                <DsButton onClick={openCreate}>
                    <HiOutlinePlus size={16}/>
                    创建角色
                </DsButton>
            </div>

            <div
                className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden flex flex-col mb-ds-8">
                <div className="overflow-x-auto">
                    <Table<RoleVO>
                        dataSource={roles}
                        rowKey="id"
                        loading={loading}
                        pagination={false}
                        scroll={{x: 1000}}
                        columns={columns}
                        className="prototype-table prototype-table-flush"
                        locale={{emptyText: <DsTableEmpty description="暂无角色数据"/>}}
                    />
                </div>
            </div>

            {/* 创建/编辑弹窗 */}
            <DsModal
                open={modalOpen}
                onClose={() => {
                    if (submitting) return;
                    setModalOpen(false);
                    setEditRole(null);
                }}
                title={modalMode === 'create' ? '创建角色' : '编辑角色'}
                width="w-[640px]"
                footer={
                    <>
                        <DsButton variant="ghost" onClick={() => setModalOpen(false)} disabled={submitting}>取消</DsButton>
                        <DsButton onClick={handleSubmit} loading={submitting}>保存</DsButton>
                    </>
                }
            >
                <div className="space-y-ds-4">
                    <div>
                        <label className="block text-ds-small font-medium text-ds-text-primary mb-ds-1.5">
                            角色名称 <span className="text-ds-danger">*</span>
                        </label>
                        <Input
                            value={name}
                            onChange={(e) => setName(e.target.value)}
                            placeholder="2~20 字符，创建后不可修改"
                            maxLength={20}
                            disabled={modalMode === 'edit'}
                        />
                    </div>
                    <div>
                        <label className="block text-ds-small font-medium text-ds-text-primary mb-ds-1.5">
                            角色编码 <span className="text-ds-danger">*</span>
                        </label>
                        <Input
                            value={code}
                            onChange={(e) => setCode(e.target.value.toUpperCase())}
                            placeholder="英文可读，如 READONLY_AUDITOR"
                            maxLength={30}
                            disabled={modalMode === 'edit'}
                        />
                    </div>
                    <div>
                        <label className="block text-ds-small font-medium text-ds-text-primary mb-ds-1.5">
                            描述
                        </label>
                        <Input.TextArea
                            value={description}
                            onChange={(e) => setDescription(e.target.value)}
                            placeholder="不超过 100 字"
                            maxLength={100}
                            rows={2}
                        />
                    </div>
                    <div>
                        <label className="block text-ds-small font-medium text-ds-text-primary mb-ds-1">
                            功能权限 <span className="text-ds-danger">*</span>
                        </label>
                        <PermissionTree permissions={permissions} checkedKeys={checkedKeys} onChange={setCheckedKeys}/>
                    </div>
                </div>
            </DsModal>

            {/* 角色详情 */}
            <DsModal
                open={!!detailRole}
                onClose={() => setDetailRole(null)}
                title="角色详情"
                width="w-[640px]"
                footer={
                    <>
                        <DsButton variant="ghost" onClick={() => setDetailRole(null)}>关闭</DsButton>
                        {detailRole && !detailRole.builtin && (
                            <DsButton
                                onClick={() => {
                                    const r = detailRole;
                                    setDetailRole(null);
                                    openEdit(r);
                                }}
                            >
                                编辑
                            </DsButton>
                        )}
                    </>
                }
            >
                {detailRole && (
                    <div className="space-y-ds-4">
                        <div className="grid grid-cols-2 gap-ds-4">
                            <div>
                                <label className="block text-ds-small font-medium text-ds-text-primary mb-ds-1.5">
                                    角色名称
                                </label>
                                <Input value={detailRole.name} readOnly/>
                            </div>
                            <div>
                                <label className="block text-ds-small font-medium text-ds-text-primary mb-ds-1.5">
                                    角色编码
                                </label>
                                <Input value={detailRole.code} readOnly/>
                            </div>
                            <div>
                                <label className="block text-ds-small font-medium text-ds-text-primary mb-ds-1.5">
                                    类型
                                </label>
                                <div className="pt-ds-1">
                                    {detailRole.builtin
                                        ? <DsStatusBadge variant="pending" label="预置"/>
                                        : <DsStatusBadge variant="success" label="自定义"/>}
                                </div>
                            </div>
                            <div>
                                <label className="block text-ds-small font-medium text-ds-text-primary mb-ds-1.5">
                                    创建时间
                                </label>
                                <Input value={formatDateTime(detailRole.createdAt)} readOnly/>
                            </div>
                        </div>
                        <div>
                            <label className="block text-ds-small font-medium text-ds-text-primary mb-ds-1.5">
                                描述
                            </label>
                            <Input.TextArea value={detailRole.description || '-'} readOnly rows={2}/>
                        </div>
                        <div>
                            <label className="block text-ds-small font-medium text-ds-text-primary mb-ds-1">
                                功能权限（共 {detailRole.permissions.length} 项）
                            </label>
                            <PermissionTree
                                permissions={permissions}
                                checkedKeys={detailRole.permissions}
                                onChange={() => undefined}
                                disabled
                            />
                        </div>
                    </div>
                )}
            </DsModal>

            {/* 删除确认 */}
            <ConfirmDialog
                open={!!deleteTarget}
                title="删除角色"
                message={`确定要删除自定义角色 "${deleteTarget?.name}" 吗？删除后不可恢复。`}
                confirmLabel="确认删除"
                danger
                loading={deleteLoading}
                onConfirm={handleDelete}
                onCancel={() => {
                    if (deleteLoading) return;
                    setDeleteTarget(null);
                }}
            />
        </div>
    );
}
