// 全局 DAG 执行历史（PRD §6.7.3）
// 跨 DAG 的运行实例列表，支持按名称/状态/触发方式/时间范围过滤
import {useCallback, useEffect, useMemo, useState} from 'react';
import {useNavigate, useSearchParams} from 'react-router-dom';
import {Modal, Table, Tooltip,} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import {HiOutlineArrowPath, HiOutlineEye, HiOutlineStop} from 'react-icons/hi2';
import {listAllDagExecutions, rerunFailed, stopDag} from '../dags/api';
import type {DagExecution} from '../dags/types';
import {formatDateTime, formatExecutionDuration, getDefaultTimeRange} from '../../../utils/format';
import {useCanEdit} from '../../../hooks/useCanEdit';
import usePagedList from '../../../hooks/usePagedList';
import SearchInput from '../../../components/SearchInput';
import Pagination from '../../../components/Pagination';
import DsButton from '../../../components/DsButton';
import DsIconButton from '../../../components/DsIconButton';
import DsStatusBadge from '../../../components/DsStatusBadge';
import DsFilterSelect from '../../../components/DsFilterSelect';
import DsToolbar from '../../../components/DsToolbar';
import DsTableEmpty from '../../../components/DsTableEmpty';
import {executionStatusVariant} from '../../../utils/status';
import {notify} from '../../../utils/notify';
import {NODE_STATUS_LABEL} from '../../../constants/statusColors';
import {COL} from '../../../constants/table';

// =================== 常量映射 ===================
const TRIGGER_LABEL: Record<string, string> = {
    MANUAL: '手动触发',
    CRON: '定时触发',
    SCHEDULE: '定时触发',
};

const STATUS_OPTIONS = [
    {value: '', label: '全部状态'},
    {value: 'RUNNING', label: '运行中'},
    {value: 'SUCCESS', label: '成功'},
    {value: 'FAILED', label: '失败'},
    {value: 'TERMINATED', label: '已终止'},
];

const TRIGGER_OPTIONS = [
    {value: '', label: '全部触发方式'},
    {value: 'MANUAL', label: '手动触发'},
    {value: 'CRON', label: '定时触发'},
];

// =================== helpers ===================
function nodeSummary(record: DagExecution): string {
    const nodes = record.nodeExecutions || [];
    if (nodes.length === 0) return '-';
    const success = nodes.filter(n => n.status === 'SUCCESS').length;
    const failed = nodes.filter(n => n.status === 'FAILED');
    if (failed.length > 0) {
        const names = failed
            .map(f => f.nodeName || f.nodeId || '?')
            .join('、');
        return `${success}/${nodes.length} 成功，${failed.length} 失败（${names}）`;
    }
    const sqlCount = nodes.filter(n => n.nodeType === 'SQL').length;
    const syncCount = nodes.filter(n => n.nodeType === 'SYNC').length;
    return `${success}/${nodes.length} 成功（${sqlCount} SQL + ${syncCount} 同步）`;
}

// datetime-local 用户手动编辑后可能只有分钟（YYYY-MM-DDTHH:mm），补秒保证后端 LocalDateTime 解析一致
function normalizeDateTime(v: string): string {
    return v && v.length === 16 ? `${v}:00` : v;
}

// =================== 页面 ===================
interface AppliedFilters {
    dagName: string;
    /** 所属项目名称模糊匹配 */
    projectName: string;
    /** DAG id 精确过滤（从任务列表「历史」跳入时由 URL ?dagId= 提供） */
    dagId?: string;
    status?: string;
    triggerType?: string;
    startTimeFrom: string;
    startTimeTo: string;
}

function buildDefaultApplied(): AppliedFilters {
    const range = getDefaultTimeRange();
    return {
        dagName: '',
        projectName: '',
        status: undefined,
        triggerType: undefined,
        startTimeFrom: range.from,
        startTimeTo: range.to,
    };
}

export default function DagExecutionsGlobalPage() {
    const navigate = useNavigate();
    const canEdit = useCanEdit();
    const [searchParams, setSearchParams] = useSearchParams();

    // 草稿：用户在工具栏里编辑但未点查询
    const defaultRange = getDefaultTimeRange();
    const [draftDagName, setDraftDagName] = useState('');
    const [draftProjectName, setDraftProjectName] = useState('');
    const [draftStatus, setDraftStatus] = useState('');
    const [draftTriggerType, setDraftTriggerType] = useState('');
    const [draftStartTimeFrom, setDraftStartTimeFrom] = useState(defaultRange.from);
    const [draftStartTimeTo, setDraftStartTimeTo] = useState(defaultRange.to);


    // 分页 + 已应用查询条件统一走 usePagedList；接口返回 records/total，适配成 {list, total}
    const fetcher = useCallback(async (q: AppliedFilters & { page: number; pageSize: number }) => {
        const result = await listAllDagExecutions({
            dagName: q.dagName || undefined,
            projectName: q.projectName || undefined,
            dagId: q.dagId,
            status: q.status,
            triggerType: q.triggerType,
            startTimeFrom: q.startTimeFrom,
            startTimeTo: q.startTimeTo,
            page: q.page,
            pageSize: q.pageSize,
        });
        return {list: result.records || [], total: result.total || 0};
    }, []);
    const {
        list: data, total, page, pageSize, loading,
        query: applied, setPage, setPageSize, applyQuery, reload,
    } = usePagedList<AppliedFilters, DagExecution>({
        fetcher,
        initialQuery: buildDefaultApplied(),
        defaultPageSize: 10,
    });

    // 从任务列表「历史」跳入：URL ?dagId=xxx&dagName=yyy → 精确过滤该 DAG
    const urlDagId = searchParams.get('dagId');
    const urlDagName = searchParams.get('dagName') || '';
    useEffect(() => {
        if (!urlDagId) return;
        applyQuery({...applied, dagId: urlDagId});
        // 仅在 urlDagId 变化时套用精确过滤，与原 setApplied(prev => ...) 语义一致
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [urlDagId]);

    // 清除 dagId 精确过滤（chip × 或手动查询/重置时）
    const clearDagIdFilter = useCallback(() => {
        if (searchParams.has('dagId')) {
            const next = new URLSearchParams(searchParams);
            next.delete('dagId');
            next.delete('dagName');
            setSearchParams(next, {replace: true});
        }
        if (applied.dagId) {
            const rest = {...applied};
            delete rest.dagId;
            applyQuery(rest);
        }
    }, [searchParams, setSearchParams, applied, applyQuery]);

    const handleSearch = () => {
        if (!draftStartTimeFrom || !draftStartTimeTo) {
            notify.warning('请选择执行时间范围');
            return;
        }
        clearDagIdFilter();
        applyQuery({
            dagName: draftDagName.trim(),
            projectName: draftProjectName.trim(),
            status: draftStatus || undefined,
            triggerType: draftTriggerType || undefined,
            startTimeFrom: normalizeDateTime(draftStartTimeFrom),
            startTimeTo: normalizeDateTime(draftStartTimeTo),
        });
    };

    const handleReset = () => {
        const range = getDefaultTimeRange();
        clearDagIdFilter();
        setDraftDagName('');
        setDraftProjectName('');
        setDraftStatus('');
        setDraftTriggerType('');
        setDraftStartTimeFrom(range.from);
        setDraftStartTimeTo(range.to);
        applyQuery({
            dagName: '',
            projectName: '',
            status: undefined,
            triggerType: undefined,
            startTimeFrom: range.from,
            startTimeTo: range.to,
        });
    };

    // Sprint 4：真正的重跑失败节点 —— 仅重跑 FAILED/SKIPPED 节点，成功节点结果复用（PRD §6.8.3）
    const handleRerun = useCallback((record: DagExecution) => {
        if (record.dagId == null || record.id == null) {
            notify.warning('记录缺少 dagId/executionId，无法重跑');
            return;
        }
        const nodes = record.nodeExecutions || [];
        const rerunNodes = nodes.filter(n => n.status === 'FAILED' || n.status === 'SKIPPED');
        if (rerunNodes.length === 0) {
            notify.warning('该执行没有失败或被跳过的节点，无需重跑');
            return;
        }
        const successNodes = nodes.filter(n => n.status === 'SUCCESS');
        Modal.confirm({
            centered: true,
            wrapClassName: 'prototype-modal',
            title: '重跑失败节点',
            content: (
                <div>
                    <div>将基于 DAG「{record.dagName || record.dagId}」重新执行以下失败/被跳过的节点：</div>
                    <ul className="list-disc pl-5 my-2">
                        {rerunNodes.map((n, idx) => (
                            <li key={n.nodeId || String(n.id ?? idx)}>
                                {n.nodeName || n.nodeId}（{n.status === 'FAILED' ? '失败' : '被跳过'}）
                            </li>
                        ))}
                    </ul>
                    {successNodes.length > 0 && (
                        <div className="text-ds-text-muted">
                            已成功节点「{successNodes.map(n => n.nodeName || n.nodeId).join('、')}」结果将复用。
                        </div>
                    )}
                </div>
            ),
            okText: '确认重跑',
            cancelText: '取消',
            onOk: async () => {
                try {
                    // 不转 Number()：保持 string id 避免 19 位 Snowflake 精度丢失
                    await rerunFailed(record.dagId!, record.id!);
                    notify.success('已触发重跑，5s 后刷新列表');
                    setTimeout(reload, 5000);
                } catch {
                    // 错误提示由 request 拦截器统一弹出
                }
            },
        });
    }, [reload]);

    // 手动停止运行中的执行实例（停止后状态归一为 TERMINATED）
    const handleStop = useCallback((record: DagExecution) => {
        if (record.dagId == null || record.id == null) {
            notify.warning('记录缺少 dagId/executionId，无法停止');
            return;
        }
        Modal.confirm({
            centered: true,
            wrapClassName: 'prototype-modal',
            title: '停止执行',
            content: `确定停止 DAG「${record.dagName || record.dagId}」的本次执行吗？停止后状态将标记为「已终止」。`,
            okText: '停止',
            cancelText: '取消',
            onOk: async () => {
                try {
                    // 不转 Number()：保持 string id 避免 19 位 Snowflake 精度丢失
                    await stopDag(record.dagId!, record.id!);
                    notify.success('已发送停止指令，3s 后刷新列表');
                    setTimeout(reload, 3000);
                } catch {
                    // 错误提示由 request 拦截器统一弹出
                }
            },
        });
    }, [reload]);

    const columns = useMemo<ColumnsType<DagExecution>>(() => [
        {
            title: '所属 DAG',
            dataIndex: 'dagName',
            width: COL.NAME_COMPACT,
            ellipsis: true,
            render: (v) => <span style={{color: '#1e293b'}}>{v || '-'}</span>,
        },
        {
            title: '执行方式',
            dataIndex: 'triggerType',
            width: 90,
            render: (v?: string) => (
                <DsStatusBadge label={TRIGGER_LABEL[v || ''] || v || '-'} variant="accent"/>
            ),
        },
        {
            title: '状态',
            dataIndex: 'status',
            width: 90,
            render: (v: string) => (
                <DsStatusBadge
                    label={NODE_STATUS_LABEL[v] || v || '-'}
                    variant={executionStatusVariant(v)}
                />
            ),
        },
        {
            title: '开始时间',
            dataIndex: 'startTime',
            width: COL.DATETIME_COMPACT,
            render: (v?: string) => (
                <span className="text-ds-small text-ds-text-secondary whitespace-nowrap">{formatDateTime(v)}</span>
            ),
        },
        {
            title: '结束时间',
            dataIndex: 'endTime',
            width: COL.DATETIME_COMPACT,
            render: (v?: string) => (
                <span className="text-ds-small text-ds-text-secondary whitespace-nowrap">{formatDateTime(v)}</span>
            ),
        },
        {
            title: '耗时',
            dataIndex: 'durationMs',
            width: 90,
            ellipsis: true,
            // 运行中（endTime 为空）：用当前时间静态计算一次，不做定时刷新
            // 超宽截断 + title 悬浮提示（长耗时如 "72m 37s 737ms" 单行放不下）
            render: (v: number | undefined, r) => {
                const text = formatExecutionDuration(v, r.startTime, r.endTime);
                return <span title={text} className="text-ds-small text-ds-text-secondary">{text}</span>;
            },
        },
        {
            title: '节点执行情况',
            dataIndex: 'nodeExecutions',
            width: COL.NODE_SUMMARY,
            render: (_, r) => (
                <div style={{color: '#475569', fontSize: 13}}>
                    <div>{nodeSummary(r)}</div>
                    {r.status === 'FAILED' && r.errorMessage && (
                        <Tooltip title={r.errorMessage}>
                            <div className="text-ds-danger text-[12px] truncate max-w-[200px]">
                                {r.errorMessage}
                            </div>
                        </Tooltip>
                    )}
                </div>
            ),
        },
        {
            title: '操作',
            width: COL.OPERATION_3,
            align: 'center',
            render: (_, r) => (
                <div className="flex items-center justify-center gap-1 whitespace-nowrap">
                    <Tooltip title="详情">
                        <DsIconButton
                            tone="accent"
                            data-testid={`dag-execution-view-${r.id}`}
                            onClick={() => navigate(`/engineering/dags/${r.dagId}/executions/${r.id}`)}
                            aria-label="详情"
                        >
                            <HiOutlineEye size={14}/>
                        </DsIconButton>
                    </Tooltip>
                    {r.status === 'RUNNING' && (
                        <Tooltip title={canEdit ? '停止执行' : '只读模式：您没有编辑权限'}>
                            <DsIconButton
                                tone="danger"
                                disabled={!canEdit}
                                onClick={() => handleStop(r)}
                            >
                                <HiOutlineStop size={14}/>
                            </DsIconButton>
                        </Tooltip>
                    )}
                    {(r.status === 'FAILED' || r.status === 'TERMINATED') && (
                        <Tooltip title={canEdit ? '重跑失败节点' : '只读模式：您没有编辑权限'}>
                            <DsIconButton
                                tone="accent"
                                disabled={!canEdit}
                                onClick={() => handleRerun(r)}
                            >
                                <HiOutlineArrowPath size={14}/>
                            </DsIconButton>
                        </Tooltip>
                    )}
                </div>
            ),
        },
    ], [canEdit, handleRerun, handleStop]);

    return (
        <div className="flex flex-col">
            {/* 页头 */}
            <div className="mb-ds-5 flex-shrink-0">
                <h1 className="text-ds-display text-ds-text-primary">DAG 执行历史</h1>
                <p className="text-ds-small text-ds-text-muted mt-ds-1">查看跨 DAG 的运行实例与节点执行详情</p>
            </div>

            {/* 工具栏：独立卡片（与表格分离，对齐原型 .toolbar） */}
            <div
                className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle p-ds-3 mb-ds-4 flex-shrink-0">
                <DsToolbar
                    extra={
                        <>
                            <DsButton onClick={handleSearch}>
                                查询
                            </DsButton>
                            <DsButton variant="secondary" onClick={handleReset}>
                                重置
                            </DsButton>
                        </>
                    }
                >
                    {applied.dagId ? (
                        // 从任务列表「历史」跳入：按 dagId 精确过滤，名称框换成可清除的 chip
                        <span
                            className="inline-flex items-center gap-ds-2 px-ds-3 py-ds-2 bg-ds-accent-light text-ds-accent rounded-ds-sm text-ds-small font-semibold">
                            所属 DAG：{urlDagName || applied.dagId}
                            <button
                                onClick={clearDagIdFilter}
                                className="hover:text-ds-accent-hover font-bold"
                                aria-label="清除 DAG 过滤"
                                title="清除过滤，显示全部 DAG"
                            >
                                ×
                            </button>
                        </span>
                    ) : (
                        <SearchInput
                            value={draftDagName}
                            onChange={(e) => setDraftDagName(e.target.value)}
                            onEnter={handleSearch}
                            placeholder="搜索 DAG 名称"
                        />
                    )}
                    <SearchInput
                        value={draftProjectName}
                        onChange={(e) => setDraftProjectName(e.target.value)}
                        onEnter={handleSearch}
                        placeholder="搜索所属项目"
                    />
                    <DsFilterSelect
                        value={draftStatus}
                        onChange={(v) => setDraftStatus(v)}
                        options={STATUS_OPTIONS}
                        aria-label="按状态筛选"
                    />
                    <DsFilterSelect
                        value={draftTriggerType}
                        onChange={(v) => setDraftTriggerType(v)}
                        options={TRIGGER_OPTIONS}
                        aria-label="按触发方式筛选"
                    />
                    <div className="flex items-center gap-ds-2">
                        <input
                            type="datetime-local"
                            step={1}
                            value={draftStartTimeFrom}
                            onChange={(e) => setDraftStartTimeFrom(e.target.value)}
                            className="px-ds-3 py-ds-2 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-small text-ds-text-primary focus:outline-none focus-visible:border-ds-accent"
                            aria-label="开始时间起"
                        />
                        <span className="text-ds-small text-ds-text-muted">至</span>
                        <input
                            type="datetime-local"
                            step={1}
                            value={draftStartTimeTo}
                            onChange={(e) => setDraftStartTimeTo(e.target.value)}
                            className="px-ds-3 py-ds-2 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-small text-ds-text-primary focus:outline-none focus-visible:border-ds-accent"
                            aria-label="开始时间止"
                        />
                    </div>
                </DsToolbar>
            </div>

            {/* 表格卡片 + 底部分页器：卡片随内容高度，分页器紧贴表格；内容超高时整页滚动 */}
            <div className="flex flex-col">
                <div
                    data-testid="dag-executions-table"
                    className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden flex flex-col mb-ds-8">
                    <div className="overflow-x-auto">
                        <Table<DagExecution>
                            dataSource={data}
                            rowKey={r => String(r.id ?? '')}
                            loading={loading}
                            pagination={false}
                            columns={columns}
                            scroll={{x: 1160}}
                            className="prototype-table prototype-table-flush"
                            locale={{
                                emptyText: (
                                    <DsTableEmpty description="暂无执行记录"/>
                                ),
                            }}
                        />
                    </div>
                    <Pagination
                        page={page}
                        pageSize={pageSize}
                        total={total}
                        onChange={(p, ps) => {
                            // ps 变化时组件固定传 p=1，hook 的 setPageSize 自带回第 1 页
                            if (ps !== pageSize) setPageSize(ps);
                            else setPage(p);
                        }}
                    />
                </div>
            </div>

        </div>
    );
}
