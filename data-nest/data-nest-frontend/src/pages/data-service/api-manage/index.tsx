// API 管理列表页（Sprint 10 F2）
// 数据表一键生成 RESTful API（X-API-Key 认证 + 限流 + 调用统计）的管理入口。
// 数据来源：GET /data-service/apis/page + GET /data-service/apis/summary
import {useCallback, useEffect, useMemo, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {Table, Tooltip} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import {
    HiOutlineBolt,
    HiOutlineDocumentText,
    HiOutlineExclamationTriangle,
    HiOutlineEye,
    HiOutlinePencil,
    HiOutlinePlay,
    HiOutlinePlus,
    HiOutlineStop,
    HiOutlineTrash,
} from 'react-icons/hi2';
import usePagedList from '@/hooks/usePagedList';
import {useCan} from '@/hooks/useCan';
import {DATA_SERVICE_WRITE_PERMS} from '@/constants/permissions';
import {COL} from '@/constants/table';
import {formatDateTime, formatNumber} from '@/utils/format';
import {notify} from '@/utils/notify';
import {getErrorMessage} from '@/utils/error';
import {
    deleteDataApi,
    disableDataApi,
    getDataApiSummary,
    pageDataApis,
    publishDataApi,
} from '@/api/data-service';
import DsButton from '@/components/DsButton';
import DsIconButton from '@/components/DsIconButton';
import DsFilterSelect from '@/components/DsFilterSelect';
import DsTableEmpty from '@/components/DsTableEmpty';
import DsToolbar from '@/components/DsToolbar';
import SearchInput from '@/components/SearchInput';
import StatsCards from '@/components/StatsCards';
import Pagination from '@/components/Pagination';
import ConfirmDialog from '@/components/ConfirmDialog';
import {DataApiStatusBadge, SensitivityBadge} from '../badges';
import type {DataApiPageItem, DataApiStatus, DataApiSummary} from '@/types/data-service';

const STATUS_OPTIONS = [
    {value: '', label: '全部状态'},
    {value: 'PUBLISHED', label: '已发布'},
    {value: 'CREATED', label: '未发布'},
    {value: 'DISABLED', label: '已下线'},
];

interface ApiListQuery {
    keyword: string;
    status: DataApiStatus | '';
    scope: '' | 'mine';
}

const INITIAL_QUERY: ApiListQuery = {keyword: '', status: '', scope: ''};

export default function ApiManagePage() {
    const navigate = useNavigate();
    const canWrite = useCan(...DATA_SERVICE_WRITE_PERMS);

    // ============ 列表（分页 + 筛选） ============
    const [draft, setDraft] = useState<ApiListQuery>(INITIAL_QUERY);
    const {list, total, page, pageSize, loading, query, setPage, setPageSize, applyQuery, reload} =
        usePagedList<ApiListQuery, DataApiPageItem>({
            fetcher: async (q) => {
                const res = await pageDataApis({
                    page: q.page,
                    pageSize: q.pageSize,
                    scope: q.scope || undefined,
                    keyword: q.keyword || undefined,
                    status: q.status || undefined,
                });
                return {list: res.data.records, total: res.data.total};
            },
            initialQuery: INITIAL_QUERY,
            defaultPageSize: 10,
        });

    // ============ 顶部统计卡 ============
    const [summary, setSummary] = useState<DataApiSummary | null>(null);
    const [summaryLoading, setSummaryLoading] = useState(false);
    const loadSummary = useCallback(() => {
        setSummaryLoading(true);
        getDataApiSummary()
            .then((res) => setSummary(res.data))
            .catch(() => {
                // 拦截器已提示，保持旧数据
            })
            .finally(() => setSummaryLoading(false));
    }, []);
    useEffect(() => {
        loadSummary();
    }, [loadSummary]);

    // 统计卡点击下钻：状态筛选联动（再点取消）；近 7 天调用无筛选维度，不可点
    const drillStatus = (target: DataApiStatus) => {
        const next: ApiListQuery = {...query, status: query.status === target ? '' : target};
        setDraft(next);
        applyQuery(next);
    };

    // ============ 行操作（发布/下线/删除） ============
    const [actionLoadingId, setActionLoadingId] = useState<string | null>(null);
    const [deleteTarget, setDeleteTarget] = useState<DataApiPageItem | null>(null);
    const [deleting, setDeleting] = useState(false);

    const refreshAll = () => {
        reload();
        loadSummary();
    };

    const handlePublish = async (item: DataApiPageItem) => {
        setActionLoadingId(item.id);
        try {
            await publishDataApi(item.id);
            notify.success(`API「${item.name}」已发布`);
            refreshAll();
        } catch {
            // 拦截器已提示
        } finally {
            setActionLoadingId(null);
        }
    };

    const handleDisable = async (item: DataApiPageItem) => {
        setActionLoadingId(item.id);
        try {
            await disableDataApi(item.id);
            notify.success(`API「${item.name}」已下线，业务系统将无法再调用`);
            refreshAll();
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
            await deleteDataApi(deleteTarget.id);
            notify.success(`API「${deleteTarget.name}」已删除`);
            setDeleteTarget(null);
            refreshAll();
        } catch (err) {
            notify.error(getErrorMessage(err));
        } finally {
            setDeleting(false);
        }
    };

    // ============ 列 ============
    const columns = useMemo<ColumnsType<DataApiPageItem>>(() => [
        {
            title: 'API 名称',
            dataIndex: 'name',
            width: COL.NAME,
            ellipsis: true,
            render: (v: string, item) => (
                <button
                    type="button"
                    onClick={() => navigate(`/data-service/api-manage/${item.id}`)}
                    title={v}
                    className="text-ds-small text-ds-accent font-medium hover:underline bg-transparent border-0 p-0 cursor-pointer max-w-full truncate"
                >
                    {v}
                </button>
            ),
        },
        {
            title: '路径',
            dataIndex: 'path',
            width: 220,
            ellipsis: true,
            render: (v: string) => (
                <span title={v} className="text-ds-small text-ds-text-secondary font-mono">{v}</span>
            ),
        },
        {
            title: '数据表',
            key: 'table',
            width: 200,
            ellipsis: true,
            render: (_, item) => {
                const qualified = `${item.databaseName}${item.schemaName ? `.${item.schemaName}` : ''}.${item.tableName}`;
                return (
                    <span title={`${item.datasourceName || '数据源'} · ${qualified}`}
                          className="text-ds-small text-ds-text-secondary font-mono">{qualified}</span>
                );
            },
        },
        {
            title: '敏感度',
            dataIndex: 'sensitivityLevel',
            width: COL.STATUS,
            render: (v?: string) => <SensitivityBadge level={v}/>,
        },
        {
            title: '状态',
            dataIndex: 'status',
            width: COL.STATUS,
            render: (v: DataApiStatus) => <DataApiStatusBadge status={v}/>,
        },
        {
            title: '绑定 Key',
            dataIndex: 'boundKeyCount',
            width: COL.COUNT,
            align: 'right',
            render: (v?: string) => (
                <span className="text-ds-small text-ds-text-secondary font-mono tabular-nums">{v ?? '0'}</span>
            ),
        },
        {
            title: '近 7 天调用',
            dataIndex: 'calls7d',
            width: COL.COUNT_NORMAL,
            align: 'right',
            render: (v?: string) => (
                <span className="text-ds-small text-ds-text-secondary font-mono tabular-nums">{formatNumber(v)}</span>
            ),
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
        {
            title: '操作',
            align: 'center',
            fixed: 'right' as const,
            width: COL.OPERATION_4,
            render: (_, item) => (
                <div className="flex items-center justify-center gap-1 whitespace-nowrap group">
                    <Tooltip title="查看详情">
                        <DsIconButton tone="accent" onClick={() => navigate(`/data-service/api-manage/${item.id}`)}
                                      aria-label="查看详情">
                            <HiOutlineEye size={14}/>
                        </DsIconButton>
                    </Tooltip>
                    {canWrite && (
                        <>
                            <Tooltip title="编辑">
                                <DsIconButton tone="accent"
                                              onClick={() => navigate(`/data-service/api-manage/${item.id}/edit`)}
                                              aria-label="编辑">
                                    <HiOutlinePencil size={14}/>
                                </DsIconButton>
                            </Tooltip>
                            {item.status !== 'PUBLISHED' ? (
                                <Tooltip title="发布">
                                    <DsIconButton tone="success" onClick={() => handlePublish(item)}
                                                  disabled={actionLoadingId === item.id} aria-label="发布">
                                        <HiOutlinePlay size={14}/>
                                    </DsIconButton>
                                </Tooltip>
                            ) : (
                                <Tooltip title="下线">
                                    <DsIconButton tone="danger" onClick={() => handleDisable(item)}
                                                  disabled={actionLoadingId === item.id} aria-label="下线">
                                        <HiOutlineStop size={14}/>
                                    </DsIconButton>
                                </Tooltip>
                            )}
                            <div className="opacity-0 group-hover:opacity-100 transition-opacity">
                                <Tooltip title="删除">
                                    <DsIconButton tone="danger" onClick={() => setDeleteTarget(item)} aria-label="删除">
                                        <HiOutlineTrash size={14}/>
                                    </DsIconButton>
                                </Tooltip>
                            </div>
                        </>
                    )}
                </div>
            ),
        },
        // eslint-disable-next-line react-hooks/exhaustive-deps
    ], [canWrite, actionLoadingId, navigate]);

    return (
        <div className="flex flex-col">
            <div className="flex items-center justify-between mb-ds-5 flex-shrink-0">
                <div>
                    <h1 className="text-ds-display text-ds-text-primary">API 管理</h1>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1">
                        把数据表一键发布成可调用的数据 API，业务系统凭 Key 自助取数；支持限流与调用统计。
                    </p>
                </div>
                <div className="flex items-center gap-ds-2">
                    {canWrite && (
                        <DsButton onClick={() => navigate('/data-service/api-manage/new')}>
                            <HiOutlinePlus size={14}/>
                            新建 API
                        </DsButton>
                    )}
                </div>
            </div>

            <StatsCards
                loading={summaryLoading}
                items={[
                    {
                        label: '已发布 API', value: summary ? formatNumber(summary.publishedCount) : '—',
                        icon: <HiOutlineBolt size={20}/>,
                        iconClass: 'bg-ds-accent-light text-ds-accent', valueClass: 'text-ds-accent',
                        tip: '点击筛选列表', active: query.status === 'PUBLISHED',
                        onClick: () => drillStatus('PUBLISHED'),
                    },
                    {
                        label: '待发布 / 草稿', value: summary ? formatNumber(summary.createdCount) : '—',
                        icon: <HiOutlineDocumentText size={20}/>,
                        iconClass: 'bg-ds-bg-hover text-ds-text-muted',
                        tip: '点击筛选列表', active: query.status === 'CREATED',
                        onClick: () => drillStatus('CREATED'),
                    },
                    {
                        label: '近 7 天总调用', value: summary ? formatNumber(summary.totalCalls7d) : '—',
                        icon: <HiOutlinePlay size={20}/>,
                        iconClass: 'bg-ds-success-light text-ds-success', valueClass: 'text-ds-success',
                        tip: '全部已发布 API 的近 7 天调用总量',
                    },
                    {
                        label: '已下线', value: summary ? formatNumber(summary.disabledCount) : '—',
                        icon: <HiOutlineExclamationTriangle size={20}/>,
                        iconClass: summary?.disabledCount ? 'bg-ds-danger-light text-ds-danger' : 'bg-ds-bg-hover text-ds-text-muted',
                        valueClass: summary?.disabledCount ? 'text-ds-danger' : undefined,
                        tip: '点击筛选列表', active: query.status === 'DISABLED',
                        onClick: () => drillStatus('DISABLED'),
                    },
                ]}
            />

            <div className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden flex flex-col">
                <div className="p-ds-3 border-b border-ds-border-subtle flex-shrink-0">
                    <DsToolbar
                        extra={(
                            <>
                                <div className="flex items-center rounded-ds-sm border border-ds-border-subtle overflow-hidden">
                                    {([{value: '' as const, label: '全部'}, {value: 'mine' as const, label: '我的 API'}]).map((opt) => (
                                        <button
                                            key={opt.value}
                                            type="button"
                                            onClick={() => {
                                                const next = {...query, scope: opt.value};
                                                setDraft(next);
                                                applyQuery(next);
                                            }}
                                            className={`px-ds-3 py-ds-2 text-ds-small transition-colors ${
                                                query.scope === opt.value
                                                    ? 'bg-ds-accent text-white font-medium'
                                                    : 'bg-white text-ds-text-secondary hover:bg-ds-bg-hover'
                                            }`}
                                        >
                                            {opt.label}
                                        </button>
                                    ))}
                                </div>
                                <DsButton onClick={() => applyQuery({...query, keyword: draft.keyword, status: draft.status})}
                                          disabled={loading} loading={loading}>
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
                            onEnter={() => applyQuery({...query, keyword: draft.keyword, status: draft.status})}
                            placeholder="搜索 API 名称 / 路径"
                        />
                        <DsFilterSelect
                            value={draft.status}
                            onChange={(v) => setDraft({...draft, status: v as DataApiStatus | ''})}
                            aria-label="按状态筛选"
                            options={STATUS_OPTIONS}
                        />
                    </DsToolbar>
                </div>

                <div className="overflow-x-auto">
                    <Table<DataApiPageItem>
                        dataSource={list}
                        rowKey="id"
                        loading={loading}
                        pagination={false}
                        scroll={{x: 1700}}
                        columns={columns}
                        className="prototype-table prototype-table-flush"
                        locale={{
                            emptyText: (
                                <DsTableEmpty
                                    description="暂无数据 API。点击右上角「新建 API」，把数据表发布成业务系统可调用的接口。"
                                    action={canWrite ? (
                                        <DsButton onClick={() => navigate('/data-service/api-manage/new')}>
                                            <HiOutlinePlus size={14}/>
                                            新建 API
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

            <ConfirmDialog
                open={!!deleteTarget}
                title="删除 API"
                message={(
                    <>
                        确认删除 API「{deleteTarget?.name}」（{deleteTarget?.path}）？
                        删除后业务系统将无法再调用该 API，相关 Key 的绑定会自动解除；历史调用统计保留。
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
