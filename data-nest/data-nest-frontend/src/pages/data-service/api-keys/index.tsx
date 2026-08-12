// API Key 管理页（Sprint 10 F2）
// 业务系统凭 API Key（X-API-Key 头）调用对外 API；Key 仅创建时展示一次明文，后端只存哈希。
// 列表含「近 7 天调用」聚合：0 调用 = 僵尸 Key（灰显），建议停用防泄露。
// 数据来源：GET /data-service/api-keys/page
import {useMemo, useState} from 'react';
import {Table, Tooltip} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import {
    HiOutlineExclamationTriangle,
    HiOutlinePencil,
    HiOutlinePlay,
    HiOutlinePlus,
    HiOutlineStop,
    HiOutlineTrash,
} from 'react-icons/hi2';
import usePagedList from '@/hooks/usePagedList';
import {useHasRole} from '@/hooks/useHasRole';
import {DATA_SERVICE_WRITE_ROLES} from '@/constants/roles';
import {COL} from '@/constants/table';
import {formatDateTime, formatNumber} from '@/utils/format';
import {notify} from '@/utils/notify';
import {getErrorMessage} from '@/utils/error';
import {deleteApiKey, disableApiKey, enableApiKey, pageApiKeys} from '@/api/data-service';
import DsButton from '@/components/DsButton';
import DsIconButton from '@/components/DsIconButton';
import DsFilterSelect from '@/components/DsFilterSelect';
import DsTableEmpty from '@/components/DsTableEmpty';
import DsToolbar from '@/components/DsToolbar';
import SearchInput from '@/components/SearchInput';
import Pagination from '@/components/Pagination';
import ConfirmDialog from '@/components/ConfirmDialog';
import {ApiKeyStatusBadge} from '../badges';
import KeyFormModal from './KeyFormModal';
import type {ApiKeyPageItem} from '@/types/data-service';

const STATUS_OPTIONS = [
    {value: '', label: '全部状态'},
    {value: 'ENABLED', label: '启用'},
    {value: 'DISABLED', label: '禁用'},
];

interface KeyListQuery {
    keyword: string;
    status: string;
}

const INITIAL_QUERY: KeyListQuery = {keyword: '', status: ''};

export default function ApiKeysPage() {
    const canWrite = useHasRole(...DATA_SERVICE_WRITE_ROLES);

    // ============ 列表（分页 + 筛选） ============
    const [draft, setDraft] = useState<KeyListQuery>(INITIAL_QUERY);
    const {list, total, page, pageSize, loading, setPage, setPageSize, applyQuery, reload} =
        usePagedList<KeyListQuery, ApiKeyPageItem>({
            fetcher: async (q) => {
                const res = await pageApiKeys({
                    page: q.page,
                    pageSize: q.pageSize,
                    keyword: q.keyword || undefined,
                    status: q.status || undefined,
                });
                return {list: res.data.records, total: res.data.total};
            },
            initialQuery: INITIAL_QUERY,
            defaultPageSize: 10,
        });

    // ============ 新建/编辑弹窗 ============
    const [modalOpen, setModalOpen] = useState(false);
    const [editingKey, setEditingKey] = useState<ApiKeyPageItem | null>(null);

    // ============ 行操作（启停/删除） ============
    const [actionLoadingId, setActionLoadingId] = useState<string | null>(null);
    const [deleteTarget, setDeleteTarget] = useState<ApiKeyPageItem | null>(null);
    const [deleting, setDeleting] = useState(false);

    const handleToggleStatus = async (item: ApiKeyPageItem) => {
        setActionLoadingId(item.id);
        try {
            if (item.status === 'ENABLED') {
                await disableApiKey(item.id);
                notify.success(`Key「${item.name}」已禁用，业务系统将立即无法凭它调用`);
            } else {
                await enableApiKey(item.id);
                notify.success(`Key「${item.name}」已启用`);
            }
            reload();
        } catch {
            // 拦截器已提示
        } finally {
            setActionLoadingId(null);
        }
    };

    const handleDelete = async () => {
        if (!deleteTarget) return;
        setDeleting(true);
        try {
            await deleteApiKey(deleteTarget.id);
            notify.success(`Key「${deleteTarget.name}」已删除`);
            setDeleteTarget(null);
            reload();
        } catch (err) {
            notify.error(getErrorMessage(err));
        } finally {
            setDeleting(false);
        }
    };

    // ============ 列 ============
    const columns = useMemo<ColumnsType<ApiKeyPageItem>>(() => [
        {
            title: 'Key 名称',
            dataIndex: 'name',
            width: COL.NAME,
            ellipsis: true,
            render: (v: string) => (
                <span title={v} className="text-ds-small text-ds-text-primary font-medium">{v}</span>
            ),
        },
        {
            title: '状态',
            dataIndex: 'status',
            width: COL.STATUS,
            render: (v: ApiKeyPageItem['status']) => <ApiKeyStatusBadge status={v}/>,
        },
        {
            title: '绑定 API 数',
            dataIndex: 'boundApiCount',
            width: COL.COUNT_NORMAL,
            align: 'right',
            render: (v?: string) => (
                <span className="text-ds-small text-ds-text-secondary font-mono tabular-nums">{v ?? '0'}</span>
            ),
        },
        {
            title: '限流 QPS',
            dataIndex: 'qpsLimit',
            width: COL.COUNT_NORMAL,
            align: 'right',
            render: (v?: number) => (
                <span className="text-ds-small text-ds-text-secondary font-mono tabular-nums">{v ?? '—'}</span>
            ),
        },
        {
            title: '近 7 天调用',
            dataIndex: 'calls7d',
            width: COL.COUNT_NORMAL,
            align: 'right',
            render: (v?: string) => {
                const zero = !v || Number(v) === 0;
                return zero ? (
                    <Tooltip title="僵尸 Key：近 7 天 0 调用，建议停用防泄露">
                        <span className="text-ds-small text-ds-text-muted font-mono tabular-nums">0</span>
                    </Tooltip>
                ) : (
                    <span
                        className="text-ds-small text-ds-success font-mono tabular-nums font-medium">{formatNumber(v)}</span>
                );
            },
        },
        {
            title: '创建人',
            dataIndex: 'createdByName',
            width: COL.USERNAME,
            ellipsis: true,
            render: (v?: string) => (
                <span className="text-ds-small text-ds-text-secondary">{v || '—'}</span>
            ),
        },
        {
            title: '创建时间',
            dataIndex: 'createdAt',
            width: COL.DATETIME_COMPACT,
            render: (v?: string) => (
                <span className="text-ds-small text-ds-text-secondary whitespace-nowrap">{formatDateTime(v)}</span>
            ),
        },
        {
            title: '修改人',
            dataIndex: 'updatedByName',
            width: COL.USERNAME,
            ellipsis: true,
            render: (v?: string) => (
                <span className="text-ds-small text-ds-text-secondary">{v || '—'}</span>
            ),
        },
        {
            title: '修改时间',
            dataIndex: 'updatedAt',
            width: COL.DATETIME_COMPACT,
            render: (v?: string) => (
                <span className="text-ds-small text-ds-text-secondary whitespace-nowrap">{formatDateTime(v)}</span>
            ),
        },
        ...(canWrite ? [{
            title: '操作',
            align: 'center' as const,
            fixed: 'right' as const,
            width: COL.OPERATION_3,
            render: (_: unknown, item: ApiKeyPageItem) => (
                <div className="flex items-center justify-center gap-1 whitespace-nowrap">
                    <Tooltip title="编辑">
                        <DsIconButton tone="accent" onClick={() => {
                            setEditingKey(item);
                            setModalOpen(true);
                        }} aria-label="编辑">
                            <HiOutlinePencil size={14}/>
                        </DsIconButton>
                    </Tooltip>
                    {item.status === 'ENABLED' ? (
                        <Tooltip title="禁用（泄露 1 步处置）">
                            <DsIconButton tone="danger" onClick={() => handleToggleStatus(item)}
                                          disabled={actionLoadingId === item.id} aria-label="禁用">
                                <HiOutlineStop size={14}/>
                            </DsIconButton>
                        </Tooltip>
                    ) : (
                        <Tooltip title="启用">
                            <DsIconButton tone="success" onClick={() => handleToggleStatus(item)}
                                          disabled={actionLoadingId === item.id} aria-label="启用">
                                <HiOutlinePlay size={14}/>
                            </DsIconButton>
                        </Tooltip>
                    )}
                    <Tooltip title="删除">
                        <DsIconButton tone="danger" onClick={() => setDeleteTarget(item)} aria-label="删除">
                            <HiOutlineTrash size={14}/>
                        </DsIconButton>
                    </Tooltip>
                </div>
            ),
        }] : []),
        // eslint-disable-next-line react-hooks/exhaustive-deps
    ], [canWrite, actionLoadingId]);

    return (
        <div className="flex flex-col">
            <div className="flex items-center justify-between mb-ds-5 flex-shrink-0">
                <div>
                    <h1 className="text-ds-display text-ds-text-primary">API Key 管理</h1>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1">
                        Key 是业务系统调用数据 API 的凭证：在这里签发、设置限流、随时禁用。完整 Key 仅在创建时展示一次，请妥善保管。
                    </p>
                </div>
                <div className="flex items-center gap-ds-2">
                    {canWrite && (
                        <DsButton onClick={() => {
                            setEditingKey(null);
                            setModalOpen(true);
                        }}>
                            <HiOutlinePlus size={14}/>
                            新建 Key
                        </DsButton>
                    )}
                </div>
            </div>

            <div className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden flex flex-col">
                <div className="p-ds-3 border-b border-ds-border-subtle flex-shrink-0">
                    <DsToolbar
                        extra={(
                            <>
                                <DsButton onClick={() => applyQuery({...draft})} disabled={loading} loading={loading}>
                                    查询
                                </DsButton>
                                <DsButton variant="secondary" onClick={() => {
                                    setDraft(INITIAL_QUERY);
                                    applyQuery(INITIAL_QUERY);
                                }}>
                                    重置
                                </DsButton>
                            </>
                        )}
                    >
                        <SearchInput
                            value={draft.keyword}
                            onChange={(e) => setDraft({...draft, keyword: e.target.value})}
                            onEnter={() => applyQuery({...draft})}
                            placeholder="搜索 Key 名称"
                        />
                        <DsFilterSelect
                            value={draft.status}
                            onChange={(v) => setDraft({...draft, status: v})}
                            aria-label="按状态筛选"
                            options={STATUS_OPTIONS}
                        />
                    </DsToolbar>
                </div>

                <div className="overflow-x-auto">
                    <Table<ApiKeyPageItem>
                        dataSource={list}
                        rowKey="id"
                        loading={loading}
                        pagination={false}
                        scroll={{x: 1400}}
                        columns={columns}
                        className="prototype-table prototype-table-flush"
                        locale={{
                            emptyText: (
                                <DsTableEmpty
                                    description="暂无 API Key，点击右上角「新建 Key」为业务系统签发调用凭证。"
                                    action={canWrite ? (
                                        <DsButton onClick={() => {
                                            setEditingKey(null);
                                            setModalOpen(true);
                                        }}>
                                            <HiOutlinePlus size={14}/>
                                            新建 Key
                                        </DsButton>
                                    ) : undefined}
                                />
                            ),
                        }}
                    />
                </div>

                <Pagination
                    page={page}
                    pageSize={pageSize}
                    total={total}
                    onChange={(p, s) => {
                        setPage(p);
                        setPageSize(s);
                    }}
                />
            </div>

            {/* 底部提示条（对齐原型 hint-box） */}
            <div className="mt-ds-3 flex items-start gap-ds-2 rounded-ds-md border border-ds-border-subtle bg-ds-bg-surface px-ds-4 py-ds-3">
                <HiOutlineExclamationTriangle size={16} className="text-ds-warning flex-shrink-0 mt-0.5"/>
                <p className="text-ds-small text-ds-text-secondary">
                    <span className="font-semibold">限流说明：</span>
                    Key 的 QPS 上限对它绑定的所有 API 生效，超限的调用会被拒绝并提示业务方稍后重试。
                    一个 Key 可绑定多个 API，也可绑定 CDC 管道用于实时订阅数据变更。
                    <span className="text-ds-warning font-medium">近 7 天无调用的 Key 建议停用</span>
                    ，避免长期闲置带来泄露风险。
                </p>
            </div>

            <KeyFormModal
                open={modalOpen}
                editing={editingKey}
                onClose={() => {
                    setModalOpen(false);
                    setEditingKey(null);
                }}
                onSaved={reload}
            />

            <ConfirmDialog
                open={!!deleteTarget}
                title="删除 API Key"
                message={(
                    <>
                        确认删除 Key「{deleteTarget?.name}」？
                        删除后该 Key 立即失效，业务系统将无法再凭它调用任何 API 或订阅管道；历史调用统计保留。
                    </>
                )}
                confirmLabel="删除"
                danger
                loading={deleting}
                onConfirm={handleDelete}
                onCancel={() => setDeleteTarget(null)}
            />
        </div>
    );
}
