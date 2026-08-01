import {type HTMLAttributes, useCallback, useMemo, useState} from 'react';
import {Table, Tooltip} from 'antd';
import {notify} from '../../../utils/notify';
import type {ColumnsType} from 'antd/es/table';
import {
    createDataSource,
    deleteDataSource,
    getDataSources,
    testSavedDataSource,
    updateDataSource,
} from '../../../api/datasource';
import type {
    DataSource,
    DataSourceCreateRequest,
    DataSourceReference,
    DataSourceUpdateRequest,
} from '../../../types/datasource';
import {DataSourceStatus, DataSourceStatusEnum, DataSourceType, TYPE_OPTIONS} from '../../../constants/datasource';
import Pagination from '../../../components/Pagination';
import ConfirmDialog from '../../../components/ConfirmDialog';
import DsModal from '../../../components/DsModal';
import DsButton from '../../../components/DsButton';
import DsIconButton from '../../../components/DsIconButton';
import DsStatusBadge, {type DsStatusVariant} from '../../../components/DsStatusBadge';
import DsTableEmpty from '../../../components/DsTableEmpty';
import DataSourceDrawer from './DataSourceDrawer';
import TypeBadge from '../../../components/TypeBadge';
import TestResultModal from '../../../components/TestResultModal';
import SearchInput from '../../../components/SearchInput';
import {formatDateTime, formatRelativeTime} from '../../../utils/format';
import type {ApiError} from '../../../utils/error';
import {previewDataSource, type PreviewResult} from '../../../api/preview';
import PreviewModal from '../../../components/PreviewModal';
import DatasourcePreviewSelector from './DatasourcePreviewSelector';
import usePagedList from '../../../hooks/usePagedList';
import {useHasRole} from '../../../hooks/useHasRole';
import {ENGINEERING_WRITE_ROLES, ROLE} from '../../../constants/roles';
import DsFilterSelect from '../../../components/DsFilterSelect';
import DsToolbar from '../../../components/DsToolbar';
import {HiOutlineBolt, HiOutlineEye, HiOutlinePencilSquare, HiOutlinePlus, HiOutlineTrash,} from 'react-icons/hi2';

const STATUS_OPTIONS: { value: DataSourceStatus | ''; label: string }[] = [
    {value: '', label: '全部状态'},
    {value: DataSourceStatusEnum.NORMAL, label: '正常'},
    {value: DataSourceStatusEnum.ERROR, label: '异常'},
    {value: DataSourceStatusEnum.OFFLINE, label: '已下线'},
    {value: DataSourceStatusEnum.UNKNOWN, label: '未检测'},
];

const STATUS_BADGE: Record<DataSourceStatus, { label: string; variant: DsStatusVariant }> = {
    [DataSourceStatusEnum.NORMAL]: {label: '正常', variant: 'success'},
    [DataSourceStatusEnum.ERROR]: {label: '异常', variant: 'danger'},
    [DataSourceStatusEnum.OFFLINE]: {label: '已下线', variant: 'disabled'},
    [DataSourceStatusEnum.UNKNOWN]: {label: '未检测', variant: 'pending'},
};

interface DataSourceQuery {
    keyword: string;
    type: DataSourceType | '';
    status: DataSourceStatus | '';
}

const INITIAL_QUERY: DataSourceQuery = {keyword: '', type: '', status: ''};

export default function DataSourcesPage() {
    const canWrite = useHasRole(...ENGINEERING_WRITE_ROLES);

    const {
        list, total, page, pageSize, loading,
        setPage, setPageSize, applyQuery, reload,
    } = usePagedList<DataSourceQuery, DataSource>({
        fetcher: async ({keyword, type, status, page, pageSize}) => {
            const result = await getDataSources({
                page,
                pageSize,
                keyword: keyword || undefined,
                type: type || undefined,
                status: status || undefined,
            });
            return {list: result.data.records, total: result.data.total};
        },
        initialQuery: INITIAL_QUERY,
        defaultPageSize: 10,
    });

    const [draftKeyword, setDraftKeyword] = useState('');
    const [draftType, setDraftType] = useState<DataSourceType | ''>('');
    const [draftStatus, setDraftStatus] = useState<DataSourceStatus | ''>('');

    const [drawerOpen, setDrawerOpen] = useState(false);
    const [editItem, setEditItem] = useState<DataSource | null>(null);
    const [deleteTarget, setDeleteTarget] = useState<DataSource | null>(null);
    const [deleteOpen, setDeleteOpen] = useState(false);
    const [deleteBlockedOpen, setDeleteBlockedOpen] = useState(false);
    const [deleteReferences, setDeleteReferences] = useState<DataSourceReference[]>([]);
    const [deleteLoading, setDeleteLoading] = useState(false);
    const [testingId, setTestingId] = useState<string | null>(null);
    const [testModalOpen, setTestModalOpen] = useState(false);
    const [testModalSuccess, setTestModalSuccess] = useState(false);
    const [testModalMessage, setTestModalMessage] = useState('');
    const [previewSelectorOpen, setPreviewSelectorOpen] = useState(false);
    const [previewTarget, setPreviewTarget] = useState<DataSource | null>(null);
    const [previewModalOpen, setPreviewModalOpen] = useState(false);
    const [previewLoading, setPreviewLoading] = useState(false);
    const [previewResult, setPreviewResult] = useState<PreviewResult | null>(null);
    const [previewTitle, setPreviewTitle] = useState('');

    const canPreview = useHasRole(ROLE.SUPER_ADMIN, ROLE.GOVERNANCE_ADMIN, ROLE.DATA_ENGINEER);

    const handleSearch = () => {
        applyQuery({keyword: draftKeyword, type: draftType, status: draftStatus});
    };

    const handleReset = () => {
        setDraftKeyword('');
        setDraftType('');
        setDraftStatus('');
        setPageSize(10);
        applyQuery(INITIAL_QUERY);
    };

    const handlePageChange = (nextPage: number, nextPageSize: number) => {
        if (nextPageSize !== pageSize) {
            setPageSize(nextPageSize);
        } else {
            setPage(nextPage);
        }
    };

    const handleCreate = async (data: DataSourceCreateRequest | DataSourceUpdateRequest) => {
        const result = await createDataSource(data as DataSourceCreateRequest);
        if (result.message) {
            notify.success(result.message);
        } else {
            notify.success('数据源创建成功');
        }
        setDrawerOpen(false);
        setEditItem(null);
        reload();
        return result;
    };

    const handleUpdate = async (data: DataSourceCreateRequest | DataSourceUpdateRequest) => {
        if (!editItem) return;
        const result = await updateDataSource(editItem.id, data as DataSourceUpdateRequest);
        if (result.message) {
            notify.success(result.message);
        } else {
            notify.success('数据源更新成功');
        }
        setDrawerOpen(false);
        setEditItem(null);
        reload();
        return result;
    };

    const handleDelete = async () => {
        if (!deleteTarget) return;
        setDeleteLoading(true);
        try {
            await deleteDataSource(deleteTarget.id);
            notify.success('数据源已删除');
            setDeleteOpen(false);
            setDeleteTarget(null);
            reload();
        } catch (err) {
            const errorData = (err as ApiError)?.response?.data;
            if (errorData?.code === 3005 && Array.isArray(errorData?.data)) {
                setDeleteReferences(errorData.data as DataSourceReference[]);
                setDeleteOpen(false);
                setDeleteBlockedOpen(true);
            }
        } finally {
            setDeleteLoading(false);
        }
    };

    const handleTest = useCallback(async (item: DataSource) => {
        setTestingId(item.id);
        const result = await testSavedDataSource(item.id);
        setTestingId(null);
        setTestModalSuccess(result.data.success);
        setTestModalMessage(result.data.message || (result.data.success ? '连接正常' : '连接失败'));
        setTestModalOpen(true);
        reload();
    }, [reload]);

    const handlePreview = async (database: string, schema: string | undefined, table: string) => {
        if (!previewTarget) return;
        setPreviewSelectorOpen(false);
        setPreviewModalOpen(true);
        setPreviewLoading(true);
        setPreviewResult(null);
        setPreviewTitle(`${previewTarget.name} / ${database}${schema && schema !== database ? ` / ${schema}` : ''} / ${table}`);
        try {
            const result = await previewDataSource(previewTarget.id, database, schema, table);
            if (result.data) {
                setPreviewResult({
                    columns: result.data.columns,
                    rows: result.data.rows,
                    rowCount: result.data.rowCount,
                });
            }
        } finally {
            setPreviewLoading(false);
        }
    };

    const columns = useMemo<ColumnsType<DataSource>>(() => [
        {
            title: '数据源名称',
            dataIndex: 'name',
            ellipsis: true,
            render: (v: string) => (
                <span className="text-ds-body text-ds-text-primary font-medium" title={v}>{v}</span>
            ),
        },
        {
            title: '类型',
            dataIndex: 'type',
            render: (v: DataSourceType) => <TypeBadge type={v}/>,
        },
        {
            title: '主机地址',
            ellipsis: true,
            render: (_, item) => (
                <span className="text-ds-body text-ds-text-secondary"
                      title={`${item.host}:${item.port}/${item.databaseName}`}>
                    {item.host}:{item.port}/{item.databaseName}
                </span>
            ),
        },
        {
            title: '描述',
            dataIndex: 'description',
            ellipsis: true,
            render: (v?: string) => (
                <span className="text-ds-small text-ds-text-secondary" title={v || ''}>{v || '-'}</span>
            ),
        },
        {
            title: '状态',
            dataIndex: 'status',
            render: (v: DataSourceStatus, item) => {
                const badge = STATUS_BADGE[v] ?? STATUS_BADGE[DataSourceStatusEnum.UNKNOWN];
                return (
                    <span title={item.errorMessage || ''}>
                        <DsStatusBadge label={badge.label} variant={badge.variant}/>
                    </span>
                );
            },
        },
        {
            title: '最近连接时间',
            dataIndex: 'lastTestTime',
            render: (v?: string) => (
                <span className="text-ds-small text-ds-text-secondary" title={formatDateTime(v)}>
                    {formatRelativeTime(v)}
                </span>
            ),
        },
        {
            title: '操作',
            align: 'center',
            render: (_, item) => (
                <div className="flex items-center justify-center w-full gap-1">
                    {canWrite && (
                        <>
                            <Tooltip title="编辑">
                                <DsIconButton
                                    tone="accent"
                                    data-testid={`datasource-edit-btn-${item.name}`}
                                    onClick={() => {
                                        setEditItem(item);
                                        setDrawerOpen(true);
                                    }}
                                    aria-label="编辑"
                                >
                                    <HiOutlinePencilSquare size={16}/>
                                </DsIconButton>
                            </Tooltip>
                            <Tooltip title="测试连接">
                                <DsIconButton
                                    tone="accent"
                                    data-testid={`datasource-test-btn-${item.name}`}
                                    onClick={() => handleTest(item)}
                                    disabled={testingId === item.id}
                                    aria-label="测试连接"
                                >
                                    <HiOutlineBolt size={16}/>
                                </DsIconButton>
                            </Tooltip>
                            <Tooltip title="删除">
                                <DsIconButton
                                    tone="danger"
                                    data-testid={`datasource-delete-btn-${item.name}`}
                                    onClick={() => {
                                        setDeleteTarget(item);
                                        setDeleteOpen(true);
                                    }}
                                    aria-label="删除"
                                >
                                    <HiOutlineTrash size={16}/>
                                </DsIconButton>
                            </Tooltip>
                        </>
                    )}
                    {canPreview && (
                        <Tooltip title="预览数据">
                            <DsIconButton
                                tone="accent"
                                data-testid={`datasource-preview-btn-${item.name}`}
                                onClick={() => {
                                    setPreviewTarget(item);
                                    setPreviewSelectorOpen(true);
                                }}
                                aria-label="预览数据"
                            >
                                <HiOutlineEye size={16}/>
                            </DsIconButton>
                        </Tooltip>
                    )}
                </div>
            ),
        },
    ], [canWrite, canPreview, testingId, handleTest]);

    return (
        <div className="flex flex-col">
            <div className="flex items-center justify-between mb-ds-5 flex-shrink-0">
                <div>
                    <h1 className="text-ds-display text-ds-text-primary">数据源管理</h1>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1">管理 MySQL、PostgreSQL、Doris、Oracle、SQL
                        Server 等数据源连接</p>
                </div>
                {canWrite && (
                    <DsButton
                        data-testid="datasource-create-btn"
                        onClick={() => {
                            setEditItem(null);
                            setDrawerOpen(true);
                        }}
                    >
                        <HiOutlinePlus size={16}/>
                        新增数据源
                    </DsButton>
                )}
            </div>

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
                        data-testid="datasource-search-input"
                        value={draftKeyword}
                        onChange={(e) => setDraftKeyword(e.target.value)}
                        onEnter={handleSearch}
                        placeholder="搜索数据源名称或主机..."
                    />

                    <DsFilterSelect
                        value={draftType}
                        onChange={(v) => setDraftType(v as DataSourceType | '')}
                        options={TYPE_OPTIONS}
                        aria-label="按类型筛选"
                    />

                    <DsFilterSelect
                        value={draftStatus}
                        onChange={(v) => setDraftStatus(v as DataSourceStatus | '')}
                        options={STATUS_OPTIONS}
                        aria-label="按状态筛选"
                    />
                </DsToolbar>
            </div>

            <div className="flex flex-col">
                <div
                    className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden flex flex-col mb-ds-8">
                    <div className="overflow-x-auto">
                        <Table<DataSource>
                            dataSource={list}
                            rowKey="id"
                            loading={loading}
                            pagination={false}
                            columns={columns}
                            className="prototype-table prototype-table-flush"
                            onRow={(item) => ({'data-testid': `datasource-row-${item.name}`}) as HTMLAttributes<HTMLElement>}
                            locale={{
                                emptyText: (
                                    <DsTableEmpty description="暂无数据源"/>
                                ),
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

            <DataSourceDrawer
                open={drawerOpen}
                editItem={editItem}
                onClose={() => {
                    setDrawerOpen(false);
                    setEditItem(null);
                }}
                onSubmit={editItem ? handleUpdate : handleCreate}
            />

            <ConfirmDialog
                open={deleteOpen}
                title="删除数据源"
                message={
                    <div>
                        <p>确定要删除数据源 <strong>"{deleteTarget?.name}"</strong> 吗？</p>
                        <p className="mt-2 text-ds-danger">将同步清理该数据源下已采集的元数据，删除后不可恢复。</p>
                    </div>
                }
                confirmLabel="确认删除"
                danger
                loading={deleteLoading}
                onConfirm={handleDelete}
                onCancel={() => {
                    if (deleteLoading) return;
                    setDeleteOpen(false);
                    setDeleteTarget(null);
                }}
            />

            <DsModal
                open={deleteBlockedOpen}
                onClose={() => setDeleteBlockedOpen(false)}
                title="无法删除数据源"
                closable={false}
                footer={
                    <DsButton
                        onClick={() => {
                            setDeleteBlockedOpen(false);
                            setDeleteTarget(null);
                            setDeleteReferences([]);
                        }}
                    >
                        我知道了
                    </DsButton>
                }
            >
                <p className="text-ds-body text-ds-text-secondary mb-ds-4">
                    数据源 <strong>"{deleteTarget?.name}"</strong> 已被以下任务引用，请先删除或修改这些任务后再删除数据源。
                </p>
                {deleteReferences.filter(r => r.type === 'COLLECT').length > 0 && (
                    <div className="mb-ds-4">
                        <h4 className="text-ds-small font-semibold text-ds-text-primary mb-ds-2">元数据采集任务：</h4>
                        <ul className="list-disc list-inside text-ds-small text-ds-text-secondary space-y-ds-1">
                            {deleteReferences.filter(r => r.type === 'COLLECT').map(r => (
                                <li key={r.taskId}>{r.taskName}</li>
                            ))}
                        </ul>
                    </div>
                )}
                {deleteReferences.filter(r => r.type === 'SYNC').length > 0 && (
                    <div className="mb-ds-4">
                        <h4 className="text-ds-small font-semibold text-ds-text-primary mb-ds-2">批量数据同步任务：</h4>
                        <ul className="list-disc list-inside text-ds-small text-ds-text-secondary space-y-ds-1">
                            {deleteReferences.filter(r => r.type === 'SYNC').map(r => (
                                <li key={r.taskId}>{r.taskName}</li>
                            ))}
                        </ul>
                    </div>
                )}
            </DsModal>

            <TestResultModal
                open={testModalOpen}
                success={testModalSuccess}
                message={testModalMessage}
                onClose={() => setTestModalOpen(false)}
            />

            <DatasourcePreviewSelector
                datasource={previewTarget}
                open={previewSelectorOpen}
                onClose={() => setPreviewSelectorOpen(false)}
                onPreview={handlePreview}
            />

            <PreviewModal
                open={previewModalOpen}
                loading={previewLoading}
                title={previewTitle}
                result={previewResult}
                onClose={() => setPreviewModalOpen(false)}
            />
        </div>
    );
}
