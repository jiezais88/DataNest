import {useCallback, useEffect, useState} from 'react';
import {message} from 'antd';
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
    DataSourceStatus,
    DataSourceType,
    DataSourceUpdateRequest,
} from '../../../types/datasource';
import Pagination from '../../../components/Pagination';
import ConfirmDialog from '../../../components/ConfirmDialog';
import DataSourceDrawer from './DataSourceDrawer';
import TypeBadge from '../../../components/TypeBadge';
import TestResultModal from '../../../components/TestResultModal';
import {formatRelativeTime} from '../../../utils/time';
import {useAuthStore} from '../../../store/useAuthStore';
import {
    HiChevronRight,
    HiOutlineBolt,
    HiOutlineMagnifyingGlass,
    HiOutlinePencilSquare,
    HiOutlinePlus,
    HiOutlineTrash,
} from 'react-icons/hi2';

const TYPE_OPTIONS: { value: DataSourceType | ''; label: string }[] = [
    {value: '', label: '全部类型'},
    {value: 'MYSQL', label: 'MySQL'},
    {value: 'POSTGRESQL', label: 'PostgreSQL'},
    {value: 'DORIS', label: 'Doris'},
];

const STATUS_OPTIONS: { value: DataSourceStatus | ''; label: string }[] = [
    {value: '', label: '全部状态'},
    {value: 'NORMAL', label: '正常'},
    {value: 'ERROR', label: '异常'},
    {value: 'OFFLINE', label: '已下线'},
    {value: 'UNKNOWN', label: '未检测'},
];

function formatDateTime(value?: string) {
    if (!value) return '-';
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return '-';
    const pad = (n: number) => String(n).padStart(2, '0');
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

export default function DataSourcesPage() {
    const {userInfo} = useAuthStore();
    const roles = userInfo?.roles || [];
    const canWrite = roles.includes('SUPER_ADMIN') || roles.includes('DATA_ENGINEER');

    const [items, setItems] = useState<DataSource[]>([]);
    const [total, setTotal] = useState(0);
    const [page, setPage] = useState(1);
    const [pageSize, setPageSize] = useState(10);
    const [keyword, setKeyword] = useState('');
    const [type, setType] = useState<DataSourceType | ''>('');
    const [status, setStatus] = useState<DataSourceStatus | ''>('');

    const [draftKeyword, setDraftKeyword] = useState('');
    const [draftType, setDraftType] = useState<DataSourceType | ''>('');
    const [draftStatus, setDraftStatus] = useState<DataSourceStatus | ''>('');

    const [loading, setLoading] = useState(false);
    const [drawerOpen, setDrawerOpen] = useState(false);
    const [editItem, setEditItem] = useState<DataSource | null>(null);
    const [deleteTarget, setDeleteTarget] = useState<DataSource | null>(null);
    const [deleteOpen, setDeleteOpen] = useState(false);
    const [testingId, setTestingId] = useState<string | null>(null);
    const [searchTrigger, setSearchTrigger] = useState(0);
    const [deleteLoading, setDeleteLoading] = useState(false);
    const [testModalOpen, setTestModalOpen] = useState(false);
    const [testModalSuccess, setTestModalSuccess] = useState(false);
    const [testModalMessage, setTestModalMessage] = useState('');

    const loadData = useCallback(async () => {
        setLoading(true);
        try {
            const result = await getDataSources({
                page,
                pageSize,
                keyword: keyword || undefined,
                type: type || undefined,
                status: status || undefined,
            });
            if (result.code === 200) {
                setItems(result.data.records);
                setTotal(result.data.total);
            }
        } finally {
            setLoading(false);
        }
    }, [page, pageSize, keyword, type, status, searchTrigger]);

    useEffect(() => {
        loadData();
    }, [loadData]);

    const handleSearch = () => {
        setKeyword(draftKeyword);
        setType(draftType);
        setStatus(draftStatus);
        setPage(1);
        setSearchTrigger((v) => v + 1);
    };

    const handleReset = () => {
        setDraftKeyword('');
        setDraftType('');
        setDraftStatus('');
        setKeyword('');
        setType('');
        setStatus('');
        setPage(1);
        setPageSize(10);
    };

    const handlePageChange = (nextPage: number, nextPageSize: number) => {
        setPage(nextPage);
        setPageSize(nextPageSize);
    };

    const handleCreate = async (data: DataSourceCreateRequest | DataSourceUpdateRequest) => {
        const result = await createDataSource(data as DataSourceCreateRequest);
        if (result.code === 200) {
            message.success('数据源创建成功');
            setDrawerOpen(false);
            setEditItem(null);
            loadData();
        }
        return result;
    };

    const handleUpdate = async (data: DataSourceCreateRequest | DataSourceUpdateRequest) => {
        if (!editItem) return;
        const result = await updateDataSource(editItem.id, data as DataSourceUpdateRequest);
        if (result.code === 200) {
            message.success('数据源更新成功');
            setDrawerOpen(false);
            setEditItem(null);
            loadData();
        }
        return result;
    };

    const handleDelete = async () => {
        if (!deleteTarget) return;
        setDeleteLoading(true);
        const result = await deleteDataSource(deleteTarget.id);
        if (result.code === 200) {
            message.success('数据源已删除');
            setDeleteOpen(false);
            setDeleteTarget(null);
            loadData();
        }
        setDeleteLoading(false);
    };

    const handleTest = async (item: DataSource) => {
        setTestingId(item.id);
        const result = await testSavedDataSource(item.id);
        setTestingId(null);
        if (result.code === 200) {
            setTestModalSuccess(result.data.success);
            setTestModalMessage(result.data.message || (result.data.success ? '连接正常' : '连接失败'));
            setTestModalOpen(true);
            loadData();
        }
    };

    const getTypeLabel = (value: DataSourceType) => TYPE_OPTIONS.find((o) => o.value === value)?.label || value;

    const statusClass = (value: DataSourceStatus) => {
        if (value === 'NORMAL') return {
            dot: 'bg-ds-success',
            bg: 'bg-ds-success-light',
            text: 'text-ds-success',
            label: '正常'
        };
        if (value === 'ERROR') return {
            dot: 'bg-ds-danger',
            bg: 'bg-ds-danger-light',
            text: 'text-ds-danger',
            label: '异常'
        };
        if (value === 'OFFLINE') return {
            dot: 'bg-ds-text-muted',
            bg: 'bg-ds-bg-hover',
            text: 'text-ds-text-muted',
            label: '已下线'
        };
        return {dot: 'bg-ds-text-muted', bg: 'bg-ds-bg-hover', text: 'text-ds-text-muted', label: '未检测'};
    };

    return (
        <div className="h-full flex flex-col overflow-hidden">
            <div className="flex items-center justify-between mb-ds-5 flex-shrink-0">
                <div>
                    <h1 className="text-ds-display text-ds-text-primary">数据源</h1>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1">管理 MySQL、PostgreSQL、Doris 等数据源连接</p>
                </div>
                {canWrite && (
                    <button
                        data-testid="datasource-create-btn"
                        onClick={() => {
                            setEditItem(null);
                            setDrawerOpen(true);
                        }}
                        className="flex items-center gap-ds-1 px-ds-3 py-ds-2 bg-ds-accent hover:bg-ds-accent-hover text-white text-ds-small font-semibold rounded-ds-sm transition-colors ds-fast"
                    >
                        <HiOutlinePlus size={16}/>
                        新增数据源
                    </button>
                )}
            </div>

            <div
                className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle p-ds-3 mb-ds-4 flex-shrink-0">
                <div className="flex items-center gap-ds-3 flex-wrap">
                    <div className="relative flex-1 min-w-[220px] max-w-[360px]">
                        <HiOutlineMagnifyingGlass
                            size={16}
                            className="absolute left-3 top-1/2 -translate-y-1/2 text-ds-text-muted"
                        />
                        <input
                            data-testid="datasource-search-input"
                            value={draftKeyword}
                            onChange={(e) => setDraftKeyword(e.target.value)}
                            onKeyDown={(e) => {
                                if (e.key === 'Enter') handleSearch();
                            }}
                            aria-label="搜索数据源名称或主机"
                            className="w-full pl-9 pr-ds-3 py-ds-2 bg-ds-bg-hover border border-transparent rounded-ds-sm text-ds-body text-ds-text-primary placeholder:text-ds-text-muted focus:outline-none focus-visible:border-ds-accent focus-visible:bg-ds-bg-surface focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors ds-fast"
                            placeholder="🔍 搜索数据源名称或主机..."
                        />
                    </div>

                    <div className="relative">
                        <select
                            value={draftType}
                            onChange={(e) => setDraftType(e.target.value as DataSourceType | '')}
                            aria-label="按类型筛选"
                            className="appearance-none min-w-[140px] pl-ds-3 pr-9 py-ds-2 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-small text-ds-text-primary focus:outline-none focus-visible:border-ds-accent cursor-pointer"
                        >
                            {TYPE_OPTIONS.map((o) => (
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
                            onChange={(e) => setDraftStatus(e.target.value as DataSourceStatus | '')}
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

            <div className="flex-1 min-h-0 overflow-auto">
                <div
                    className="relative bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden">
                    <table className="w-full">
                        <thead className="sticky top-0 z-10">
                        <tr className="border-b border-ds-border-subtle bg-ds-bg-hover/80">
                            <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-muted uppercase tracking-wider">数据源名称</th>
                            <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-muted uppercase tracking-wider">类型</th>
                            <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-muted uppercase tracking-wider">主机地址</th>
                            <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-muted uppercase tracking-wider">状态</th>
                            <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-muted uppercase tracking-wider">最近连接时间</th>
                            <th className="text-right px-ds-4 py-ds-3 text-ds-caption text-ds-text-muted uppercase tracking-wider">操作</th>
                        </tr>
                        </thead>
                        <tbody>
                        {items.map((item) => {
                            const statusStyle = statusClass(item.status);
                            return (
                                <tr
                                    key={item.id}
                                    data-testid={`datasource-row-${item.name}`}
                                    className="border-b border-ds-border-subtle last:border-0 hover:bg-ds-bg-hover/50 transition-colors"
                                >
                                    <td className="px-ds-4 py-ds-3">
                                        <span
                                            className="text-ds-body text-ds-text-primary font-medium">{item.name}</span>
                                    </td>
                                    <td className="px-ds-4 py-ds-3">
                                        <TypeBadge type={item.type}/>
                                    </td>
                                    <td className="px-ds-4 py-ds-3 text-ds-body text-ds-text-secondary">
                                        {item.host}:{item.port}/{item.databaseName}
                                    </td>
                                    <td className="px-ds-4 py-ds-3">
                                        <span
                                            title={item.errorMessage || ''}
                                            className={`inline-flex items-center gap-ds-1 px-ds-2 py-ds-1 rounded-ds-full text-ds-small font-medium ${statusStyle.bg} ${statusStyle.text}`}
                                        >
                                            <span className={`w-1.5 h-1.5 rounded-full ${statusStyle.dot}`}/>
                                            {statusStyle.label}
                                        </span>
                                    </td>
                                    <td className="px-ds-4 py-ds-3 text-ds-small text-ds-text-secondary"
                                        title={formatDateTime(item.lastTestTime)}>
                                        {formatRelativeTime(item.lastTestTime)}
                                    </td>
                                    <td className="px-ds-4 py-ds-3">
                                        <div className="flex items-center justify-end gap-1">
                                            {canWrite && (
                                                <>
                                                    <button
                                                        data-testid={`datasource-edit-btn-${item.name}`}
                                                        onClick={() => {
                                                            setEditItem(item);
                                                            setDrawerOpen(true);
                                                        }}
                                                        className="p-1.5 text-ds-text-muted hover:text-ds-accent hover:bg-ds-accent-light rounded transition-colors"
                                                        title="编辑"
                                                        aria-label="编辑"
                                                    >
                                                        <HiOutlinePencilSquare size={16}/>
                                                    </button>
                                                    <button
                                                        data-testid={`datasource-test-btn-${item.name}`}
                                                        onClick={() => handleTest(item)}
                                                        disabled={testingId === item.id}
                                                        className="p-1.5 text-ds-text-muted hover:text-ds-accent hover:bg-ds-accent-light rounded transition-colors disabled:opacity-60"
                                                        title="测试连接"
                                                        aria-label="测试连接"
                                                    >
                                                        <HiOutlineBolt size={16}/>
                                                    </button>
                                                    <button
                                                        data-testid={`datasource-delete-btn-${item.name}`}
                                                        onClick={() => {
                                                            setDeleteTarget(item);
                                                            setDeleteOpen(true);
                                                        }}
                                                        className="p-1.5 text-ds-text-muted hover:text-ds-danger hover:bg-ds-danger-light rounded transition-colors"
                                                        title="删除"
                                                        aria-label="删除"
                                                    >
                                                        <HiOutlineTrash size={16}/>
                                                    </button>
                                                </>
                                            )}
                                        </div>
                                    </td>
                                </tr>
                            );
                        })}
                        {items.length === 0 && !loading && (
                            <tr>
                                <td colSpan={6}
                                    className="px-ds-4 py-ds-16 text-center text-ds-text-muted text-ds-body">
                                    暂无数据源
                                </td>
                            </tr>
                        )}
                        </tbody>
                    </table>

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

            <TestResultModal
                open={testModalOpen}
                success={testModalSuccess}
                message={testModalMessage}
                onClose={() => setTestModalOpen(false)}
            />
        </div>
    );
}
