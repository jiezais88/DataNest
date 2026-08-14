import {useState} from 'react';
import {Table, Tooltip} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import type {AuditLogItem} from '@/api/audit';
import {getAuditLogs} from '@/api/audit';
import {
    AUDIT_OP_TYPES,
    AUDIT_RESOURCE_TYPES,
    getOpTypeLabel,
    getResourceTypeLabel,
} from '@/constants/audit';
import {formatDateTime, getDefaultTimeRange} from '@/utils/format';
import usePagedList from '@/hooks/usePagedList';
import DsButton from '@/components/DsButton';
import DsFilterSelect from '@/components/DsFilterSelect';
import DsRangePicker from '@/components/DsRangePicker';
import Pagination from '@/components/Pagination';
import SearchInput from '@/components/SearchInput';
import DsToolbar from '@/components/DsToolbar';
import DsTableEmpty from '@/components/DsTableEmpty';
import DsStatusBadge from '@/components/DsStatusBadge';
import Drawer from '@/components/Drawer';
import {COL} from '@/constants/table';

interface AuditQuery {
    operatorName: string;
    opType: string;
    resourceType: string;
    startTime: string;
    endTime: string;
    keyword: string;
}

const EMPTY_QUERY: AuditQuery = {
    operatorName: '',
    opType: '',
    resourceType: '',
    startTime: '',
    endTime: '',
    keyword: '',
};

/** 初始/重置查询：时间范围必填，默认近 7 天（对齐执行历史页约定） */
function buildInitialQuery(): AuditQuery {
    const range = getDefaultTimeRange();
    return {...EMPTY_QUERY, startTime: range.from, endTime: range.to};
}

const OP_OPTIONS = [{value: '', label: '全部类型'}, ...AUDIT_OP_TYPES];
const RESOURCE_OPTIONS = [{value: '', label: '全部资源'}, ...AUDIT_RESOURCE_TYPES];

/** 资源列：类型:名称（名称缺失时回退资源 ID） */
function resourceText(record: AuditLogItem): string {
    const name = record.resourceName || record.resourceId || '-';
    return `${getResourceTypeLabel(record.resourceType)}: ${name}`;
}

export default function AuditLogsPage() {
    const [draft, setDraft] = useState<AuditQuery>(buildInitialQuery);
    const [detail, setDetail] = useState<AuditLogItem | null>(null);

    const {
        list,
        total,
        page,
        pageSize,
        loading,
        query,
        setPage,
        setPageSize,
        applyQuery,
    } = usePagedList<AuditQuery, AuditLogItem>({
        fetcher: async (q) => {
            const result = await getAuditLogs({
                page: q.page,
                pageSize: q.pageSize,
                operatorName: q.operatorName || undefined,
                opType: q.opType || undefined,
                resourceType: q.resourceType || undefined,
                startTime: q.startTime || undefined,
                endTime: q.endTime || undefined,
                keyword: q.keyword || undefined,
            });
            return {list: result.data.records, total: result.data.total};
        },
        initialQuery: buildInitialQuery(),
        defaultPageSize: 10,
    });

    const handleSearch = () => applyQuery({...draft});

    const handleReset = () => {
        setDraft(buildInitialQuery());
        applyQuery(buildInitialQuery());
    };

    const handlePageChange = (nextPage: number, nextPageSize: number) => {
        if (nextPageSize !== pageSize) setPageSize(nextPageSize);
        else setPage(nextPage);
    };

    const columns: ColumnsType<AuditLogItem> = [
        {
            title: '操作时间',
            dataIndex: 'createdAt',
            width: COL.DATETIME,
            render: (v: string) => (
                <span className="text-ds-small text-ds-text-secondary whitespace-nowrap">{formatDateTime(v)}</span>
            ),
        },
        {
            title: '操作人',
            dataIndex: 'operatorName',
            width: COL.USERNAME,
            render: (v: string | null) => (
                <span className="text-ds-small text-ds-text-secondary">{v || '-'}</span>
            ),
        },
        {
            title: '操作类型',
            dataIndex: 'opType',
            width: 100,
            render: (v: string) => (
                <span className="text-ds-small text-ds-text-primary font-medium">{getOpTypeLabel(v)}</span>
            ),
        },
        {
            title: '资源',
            key: 'resource',
            width: 200,
            ellipsis: true,
            render: (_: unknown, record) => (
                <Tooltip title={resourceText(record)}>
                    <span className="text-ds-small text-ds-text-secondary">{resourceText(record)}</span>
                </Tooltip>
            ),
        },
        {
            title: '内容摘要',
            dataIndex: 'content',
            ellipsis: true,
            render: (v: string | null) => (
                <span className="text-ds-small text-ds-text-secondary">{v || '-'}</span>
            ),
        },
        {
            title: '结果',
            dataIndex: 'result',
            width: 90,
            render: (v: string) =>
                v === 'SUCCESS'
                    ? <DsStatusBadge label="成功" variant="success"/>
                    : <DsStatusBadge label="失败" variant="danger"/>,
        },
        {
            title: 'IP',
            dataIndex: 'clientIp',
            width: 130,
            render: (v: string | null) => (
                <span className="text-ds-small text-ds-text-secondary">{v || '-'}</span>
            ),
        },
    ];

    return (
        <div className="flex flex-col">
            {/* Header */}
            <div className="flex items-center justify-between mb-ds-5 flex-shrink-0">
                <div>
                    <h1 className="text-ds-display text-ds-text-primary">审计日志</h1>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1">追踪平台关键操作留痕，支持按操作人、类型、时间范围与关键词检索</p>
                </div>
            </div>

            {/* 筛选工具栏 */}
            <div className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle p-ds-4 mb-ds-4">
                <DsToolbar
                    extra={
                        <>
                            <DsButton onClick={handleSearch} disabled={loading} loading={loading}>查询</DsButton>
                            <DsButton variant="secondary" onClick={handleReset} disabled={loading}>重置</DsButton>
                        </>
                    }
                >
                    <SearchInput
                        value={draft.operatorName}
                        onChange={(e) => setDraft({...draft, operatorName: e.target.value})}
                        onEnter={handleSearch}
                        placeholder="操作人..."
                    />
                    <DsFilterSelect
                        value={draft.opType}
                        onChange={(v) => setDraft({...draft, opType: v})}
                        options={OP_OPTIONS}
                        aria-label="按操作类型筛选"
                    />
                    <DsFilterSelect
                        value={draft.resourceType}
                        onChange={(v) => setDraft({...draft, resourceType: v})}
                        options={RESOURCE_OPTIONS}
                        aria-label="按资源类型筛选"
                    />
                    <DsRangePicker
                        from={draft.startTime}
                        to={draft.endTime}
                        onChange={(from, to) => setDraft({...draft, startTime: from, endTime: to})}
                        allowClear={false}
                    />
                    <SearchInput
                        value={draft.keyword}
                        onChange={(e) => setDraft({...draft, keyword: e.target.value})}
                        onEnter={handleSearch}
                        placeholder="关键词（资源/内容）..."
                    />
                </DsToolbar>
            </div>

            {/* 表格卡片 */}
            <div className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden flex flex-col mb-ds-8">
                <div className="overflow-x-auto">
                    <Table<AuditLogItem>
                        dataSource={list}
                        rowKey="id"
                        loading={loading}
                        pagination={false}
                        scroll={{x: 1200}}
                        columns={columns}
                        className="prototype-table prototype-table-flush"
                        onRow={(record) => ({
                            onClick: () => setDetail(record),
                            className: record.result === 'FAILURE'
                                ? 'cursor-pointer bg-ds-danger-light'
                                : 'cursor-pointer',
                        })}
                        locale={{emptyText: <DsTableEmpty description="暂无审计记录"/>}}
                    />
                </div>
                <Pagination page={page} pageSize={pageSize} total={total} onChange={handlePageChange}/>
            </div>

            {/* 详情抽屉 */}
            <Drawer open={!!detail} title="审计详情" onClose={() => setDetail(null)}>
                {detail && <AuditDetail record={detail}/>}
            </Drawer>
        </div>
    );
}

/** 详情抽屉内容：全字段 + SQL 查询类完整 SQL 文本 */
function AuditDetail({record}: { record: AuditLogItem }) {
    const isSql = record.resourceType === 'SQL_QUERY';
    const rows: { label: string; value: string | null | undefined }[] = [
        {label: '操作时间', value: formatDateTime(record.createdAt)},
        {label: '操作人', value: record.operatorName},
        {label: '操作类型', value: getOpTypeLabel(record.opType)},
        {label: '资源类型', value: getResourceTypeLabel(record.resourceType)},
        {label: '资源名称', value: record.resourceName || record.resourceId || '-'},
        {label: '客户端 IP', value: record.clientIp},
    ];

    return (
        <div className="flex flex-col gap-ds-4">
            <dl className="grid grid-cols-[96px_1fr] gap-y-ds-3 text-ds-small">
                {rows.map((r) => (
                    <FragmentRow key={r.label} label={r.label} value={r.value}/>
                ))}
            </dl>

            <div>
                <p className="text-ds-nano font-bold text-ds-text-muted uppercase tracking-wide mb-ds-1">执行结果</p>
                {record.result === 'SUCCESS'
                    ? <DsStatusBadge label="成功" variant="success"/>
                    : <DsStatusBadge label="失败" variant="danger"/>}
            </div>

            {record.errorMessage && (
                <div>
                    <p className="text-ds-nano font-bold text-ds-text-muted uppercase tracking-wide mb-ds-1">失败原因</p>
                    <p className="text-ds-small text-ds-danger break-all">{record.errorMessage}</p>
                </div>
            )}

            {record.content && (
                <div>
                    <p className="text-ds-nano font-bold text-ds-text-muted uppercase tracking-wide mb-ds-1">
                        {isSql ? '执行 SQL' : '操作内容'}
                    </p>
                    <pre className="text-ds-small bg-ds-bg-root border border-ds-border-subtle rounded-ds-sm p-ds-3 whitespace-pre-wrap break-all font-mono">
                        {record.content}
                    </pre>
                </div>
            )}
        </div>
    );
}

function FragmentRow({label, value}: { label: string; value: string | null | undefined }) {
    return (
        <>
            <dt className="text-ds-text-muted">{label}</dt>
            <dd className="text-ds-text-primary break-all">{value || '-'}</dd>
        </>
    );
}
