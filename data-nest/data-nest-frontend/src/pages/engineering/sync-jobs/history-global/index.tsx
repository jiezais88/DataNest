import {useCallback, useEffect, useMemo, useState} from 'react';
import {useNavigate, useSearchParams} from 'react-router-dom';
import {Table, Tooltip} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import {getSyncJobLogs, queryAllSyncJobHistory} from '../../../../api/sync';
import type {SyncHistoryStatus, SyncJobHistory, SyncJobLog,} from '../../../../types/sync';
import Pagination from '../../../../components/Pagination';
import DsTableEmpty from '../../../../components/DsTableEmpty';
import SearchInput from '../../../../components/SearchInput';
import DsButton from '../../../../components/DsButton';
import DsFilterSelect from '../../../../components/DsFilterSelect';
import DsIconButton from '../../../../components/DsIconButton';
import DsToolbar from '../../../../components/DsToolbar';
import {HiChevronRight, HiOutlineDocumentText, HiOutlineEye,} from 'react-icons/hi2';
import {formatDateTime, formatDuration, getDefaultTimeRange} from '../../../../utils/format';
import {HistoryDetailModal, HistoryLogModal,} from '../history-common';
import {STATUS_OPTIONS, statusLabel, triggerBadge,} from '../history-common-utils';
import DsStatusBadge from '../../../../components/DsStatusBadge';
import {executionStatusVariant} from '../../../../utils/status';
import usePagedList from '../../../../hooks/usePagedList';

interface HistoryQuery {
    syncJobId?: string;
    status?: SyncHistoryStatus;
    keyword?: string;
    startTimeFrom: string;
    startTimeTo: string;
}

export default function SyncJobHistoryGlobalPage() {
    const navigate = useNavigate();
    const [searchParams, setSearchParams] = useSearchParams();

    // 草稿查询条件（点「查询」才应用）
    const [draftStatus, setDraftStatus] = useState<SyncHistoryStatus | ''>('');
    const [draftKeyword, setDraftKeyword] = useState('');
    const defaultRange = getDefaultTimeRange();
    const [draftStartTimeFrom, setDraftStartTimeFrom] = useState(defaultRange.from);
    const [draftStartTimeTo, setDraftStartTimeTo] = useState(defaultRange.to);

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
    } = usePagedList<HistoryQuery, SyncJobHistory>({
        fetcher: async (q) => {
            const result = await queryAllSyncJobHistory({
                page: q.page,
                pageSize: q.pageSize,
                syncJobId: q.syncJobId,
                status: q.status,
                keyword: q.keyword,
                startTimeFrom: q.startTimeFrom,
                startTimeTo: q.startTimeTo,
            });
            return {list: result.data.records, total: result.data.total};
        },
        initialQuery: {
            syncJobId: undefined,
            status: undefined,
            keyword: undefined,
            startTimeFrom: defaultRange.from,
            startTimeTo: defaultRange.to,
        },
        defaultPageSize: 10,
    });

    // 从任务列表「历史」跳入：URL ?syncJobId=xxx&jobName=yyy → 精确过滤该任务
    const urlSyncJobId = searchParams.get('syncJobId');
    const urlJobName = searchParams.get('jobName') || '';
    useEffect(() => {
        if (!urlSyncJobId) return;
        applyQuery({...query, syncJobId: urlSyncJobId});
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [urlSyncJobId]);

    // 清除 URL 上的 ?syncJobId=xxx&jobName=yyy 参数
    const clearSyncJobIdUrl = useCallback(() => {
        if (searchParams.has('syncJobId')) {
            const next = new URLSearchParams(searchParams);
            next.delete('syncJobId');
            next.delete('jobName');
            setSearchParams(next, {replace: true});
        }
    }, [searchParams, setSearchParams]);

    // 清除 syncJobId 精确过滤（chip ×）
    const clearSyncJobIdFilter = () => {
        clearSyncJobIdUrl();
        applyQuery({...query, syncJobId: undefined});
    };

    const [selectedHistory, setSelectedHistory] = useState<SyncJobHistory | null>(null);
    const [logs, setLogs] = useState<SyncJobLog[]>([]);
    const [logsLoading, setLogsLoading] = useState(false);
    const [logOpen, setLogOpen] = useState(false);
    const [detailOpen, setDetailOpen] = useState(false);

    const handleSearch = () => {
        clearSyncJobIdUrl();
        applyQuery({
            syncJobId: undefined,
            status: draftStatus || undefined,
            keyword: draftKeyword || undefined,
            startTimeFrom: draftStartTimeFrom,
            startTimeTo: draftStartTimeTo,
        });
    };

    const handleReset = () => {
        const range = getDefaultTimeRange();
        clearSyncJobIdUrl();
        setDraftStatus('');
        setDraftKeyword('');
        setDraftStartTimeFrom(range.from);
        setDraftStartTimeTo(range.to);
        applyQuery({
            syncJobId: undefined,
            status: undefined,
            keyword: undefined,
            startTimeFrom: range.from,
            startTimeTo: range.to,
        });
    };

    const handlePageChange = (nextPage: number, nextPageSize: number) => {
        if (nextPageSize !== pageSize) {
            setPageSize(nextPageSize);
        } else {
            setPage(nextPage);
        }
    };

    const handleOpenDetail = (item: SyncJobHistory) => {
        setSelectedHistory(item);
        setDetailOpen(true);
    };

    const handleOpenLogs = async (item: SyncJobHistory) => {
        setSelectedHistory(item);
        setLogOpen(true);
        setLogsLoading(true);
        const result = await getSyncJobLogs(item.syncJobId, item.id);
        setLogs(result.data || []);
        setLogsLoading(false);
    };

    const closeDetail = () => {
        setDetailOpen(false);
        setSelectedHistory(null);
    };

    const closeLogs = () => {
        setLogOpen(false);
        setSelectedHistory(null);
    };

    const columns = useMemo<ColumnsType<SyncJobHistory>>(() => [
        {
            title: '任务名称',
            dataIndex: 'taskName',
            ellipsis: true,
            render: (v: string) => (
                <Tooltip title={v || '-'}>
                    <span
                        className="text-ds-body text-ds-text-primary font-medium">{v || '-'}</span>
                </Tooltip>
            ),
        },
        {
            title: '触发方式',
            dataIndex: 'triggerType',
            render: (v: SyncJobHistory['triggerType']) => triggerBadge(v),
        },
        {
            title: '状态',
            dataIndex: 'status',
            render: (v: SyncJobHistory['status']) => (
                <DsStatusBadge label={statusLabel(v)} variant={executionStatusVariant(v)}/>
            ),
        },
        {
            title: '开始时间',
            dataIndex: 'startTime',
            render: (v: string) => (
                <span className="text-ds-small text-ds-text-secondary">{formatDateTime(v)}</span>
            ),
        },
        {
            title: '结束时间',
            dataIndex: 'endTime',
            render: (v: string) => (
                <span className="text-ds-small text-ds-text-secondary">{formatDateTime(v)}</span>
            ),
        },
        {
            title: '耗时',
            render: (_, item) => (
                <span
                    className="text-ds-body text-ds-text-secondary">{formatDuration(item.durationMs ?? (item.durationSeconds != null ? item.durationSeconds * 1000 : undefined))}</span>
            ),
        },
        {
            title: '源行数',
            dataIndex: 'sourceRows',
            render: (v?: number) => (
                <span className="text-ds-body text-ds-text-secondary">{v ?? '—'}</span>
            ),
        },
        {
            title: '目标行数',
            dataIndex: 'targetRows',
            render: (v?: number) => (
                <span className="text-ds-body text-ds-text-secondary">{v ?? '—'}</span>
            ),
        },
        {
            title: '错误信息',
            dataIndex: 'errorMessage',
            ellipsis: true,
            render: (v?: string) => (
                <Tooltip title={v || ''}>
                    <span className="text-ds-small text-ds-danger">{v || '—'}</span>
                </Tooltip>
            ),
        },
        {
            title: '操作',
            align: 'center',
            render: (_, item) => (
                <div className="flex items-center justify-center w-full gap-1 whitespace-nowrap">
                    <Tooltip title="详情">
                        <DsIconButton
                            tone="accent"
                            onClick={() => handleOpenDetail(item)}
                            aria-label="详情"
                        >
                            <HiOutlineEye size={16}/>
                        </DsIconButton>
                    </Tooltip>
                    <Tooltip title="查看日志">
                        <DsIconButton
                            tone="accent"
                            onClick={() => handleOpenLogs(item)}
                            aria-label="查看日志"
                        >
                            <HiOutlineDocumentText size={16}/>
                        </DsIconButton>
                    </Tooltip>
                </div>
            ),
        },
    ], []);

    return (
        <div className="flex flex-col">
            <div className="flex items-center justify-between mb-ds-5 flex-shrink-0">
                <div>
                    <h1 className="text-ds-display text-ds-text-primary">同步执行历史</h1>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1">查看所有批量同步任务的执行记录、统计与日志</p>
                </div>
                <DsButton
                    variant="secondary"
                    onClick={() => navigate('/engineering/sync-jobs')}
                >
                    <HiChevronRight size={16} className="rotate-180"/>
                    返回任务列表
                </DsButton>
            </div>

            <div
                className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle p-ds-3 mb-ds-4 flex-shrink-0">
                <DsToolbar
                    extra={
                        <>
                            <DsButton
                                variant="primary"
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
                    {query.syncJobId ? (
                        // 从任务列表「历史」跳入：按 syncJobId 精确过滤，名称框换成可清除的 chip
                        <span
                            className="inline-flex items-center gap-ds-2 px-ds-3 py-ds-2 bg-ds-accent-light text-ds-accent rounded-ds-sm text-ds-small font-semibold">
                            任务：{urlJobName || query.syncJobId}
                            <button
                                onClick={clearSyncJobIdFilter}
                                className="hover:text-ds-accent-hover font-bold"
                                aria-label="清除任务过滤"
                                title="清除过滤，显示全部任务"
                            >
                                ×
                            </button>
                        </span>
                    ) : (
                        <SearchInput
                            value={draftKeyword}
                            onChange={(e) => setDraftKeyword(e.target.value)}
                            onEnter={handleSearch}
                            placeholder="搜索任务名称..."
                        />
                    )}

                    <DsFilterSelect
                        value={draftStatus}
                        onChange={(v) => setDraftStatus(v as SyncHistoryStatus | '')}
                        options={STATUS_OPTIONS}
                        aria-label="按状态筛选"
                    />
                    <div className="flex items-center gap-ds-2">
                        <input
                            type="datetime-local"
                            value={draftStartTimeFrom}
                            onChange={(e) => setDraftStartTimeFrom(e.target.value)}
                            className="px-ds-3 py-ds-2 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-small text-ds-text-primary focus:outline-none focus-visible:border-ds-accent"
                            aria-label="开始时间起"
                        />
                        <span className="text-ds-small text-ds-text-muted">至</span>
                        <input
                            type="datetime-local"
                            value={draftStartTimeTo}
                            onChange={(e) => setDraftStartTimeTo(e.target.value)}
                            className="px-ds-3 py-ds-2 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-small text-ds-text-primary focus:outline-none focus-visible:border-ds-accent"
                            aria-label="开始时间止"
                        />
                    </div>
                </DsToolbar>
            </div>

            <div className="flex flex-col">
                <div
                    data-testid="sync-history-table"
                    className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden flex flex-col mb-ds-8">
                    <div className="overflow-x-auto">
                        <Table<SyncJobHistory>
                            dataSource={list}
                            rowKey="id"
                            loading={loading}
                            pagination={false}
                            columns={columns}
                            className="prototype-table prototype-table-flush"
                            locale={{
                                emptyText: (
                                    <DsTableEmpty description="暂无执行历史"/>
                                ),
                            }}
                        />
                    </div>

                    <Pagination page={page} pageSize={pageSize} total={total} onChange={handlePageChange}/>
                </div>
            </div>

            <HistoryDetailModal
                open={detailOpen}
                item={selectedHistory}
                onClose={closeDetail}
                onViewLogs={handleOpenLogs}
            />

            <HistoryLogModal
                open={logOpen}
                title={selectedHistory ? formatDateTime(selectedHistory.startTime) : undefined}
                logs={logs}
                loading={logsLoading}
                onClose={closeLogs}
            />
        </div>
    );
}
