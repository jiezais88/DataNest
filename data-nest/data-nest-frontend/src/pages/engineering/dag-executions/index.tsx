// 全局 DAG 执行历史（PRD §6.7.3）
// 跨 DAG 的运行实例列表，支持按名称/状态/触发方式/时间范围过滤
// 展开行展示「微缩 DAG 拓扑图」（简化版 v1：节点按数组顺序水平排列）
import {useCallback, useEffect, useMemo, useState} from 'react';
import {useNavigate, useSearchParams} from 'react-router-dom';
import {Button, Modal, Space, Table, Tooltip,} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import {HiOutlineArrowPath, HiOutlineDocumentText, HiOutlineShare} from 'react-icons/hi2';
import {listAllDagExecutions, rerunFailed} from '../dags/api';
import type {DagExecution, NodeExecution} from '../dags/types';
import {formatDateTime, formatDuration, getDefaultTimeRange} from '../../../utils/format';
import {useCanEdit} from '../../../hooks/useCanEdit';
import {usePollingWhile} from '../../../hooks/usePollingWhile';
import usePagedList from '../../../hooks/usePagedList';
import SearchInput from '../../../components/SearchInput';
import Pagination from '../../../components/Pagination';
import DsButton from '../../../components/DsButton';
import DsIconButton from '../../../components/DsIconButton';
import DsStatusBadge from '../../../components/DsStatusBadge';
import DsFilterSelect from '../../../components/DsFilterSelect';
import DsToolbar from '../../../components/DsToolbar';
import DsTableEmpty from '../../../components/DsTableEmpty';
import Drawer from '../../../components/Drawer';
import {executionStatusVariant} from '../../../utils/status';
import {notify} from '../../../utils/notify';
import {NODE_STATUS_COLOR, NODE_STATUS_LABEL} from '../../../constants/statusColors';

// =================== 常量映射 ===================
const TRIGGER_LABEL: Record<string, string> = {
    MANUAL: '手动触发',
    CRON: '定时触发',
    SCHEDULE: '定时触发',
};

const NODE_TYPE_ICON: Record<string, string> = {
    SQL: '📝',
    SYNC: '🔄',
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

// =================== 微缩 DAG 图 ===================
// 简化版 v1：节点按 NodeExecution[] 数组顺序水平排列，相邻节点之间画一个箭头
// 暂不画真实 DAG 拓扑（后端 NodeExecution 暂无 parentId）— PRD 已说明这是「简化版」
function MiniDagNode({node}: { node: NodeExecution }) {
    const borderColor = NODE_STATUS_COLOR[node.status] || NODE_STATUS_COLOR.WAITING;
    const stateLabel = NODE_STATUS_LABEL[node.status] || node.status || '-';
    const icon = NODE_TYPE_ICON[node.nodeType || ''] || '📦';
    const name = node.nodeName || node.nodeId || '?';
    return (
        <Tooltip
            title={node.errorMessage || name}
            mouseEnterDelay={0.3}
            placement="top"
        >
            <div
                className="w-[150px] min-h-[74px] p-3 bg-white border border-ds-border-subtle rounded-ds-sm shadow-ds-xs flex flex-col gap-1 shrink-0"
                style={{borderLeft: `4px solid ${borderColor}`}}
            >
                <div className="flex items-center gap-2 overflow-hidden whitespace-nowrap">
                    <span className="text-[15px] leading-none">{icon}</span>
                    <span className="text-[13px] font-semibold text-ds-text-primary truncate flex-1" title={name}>
                        {name}
                    </span>
                </div>
                <div className="text-[12px] font-medium" style={{color: borderColor}}>{stateLabel}</div>
                <div className="text-[12px] text-ds-text-muted">{formatDuration(node.durationMs)}</div>
                {node.errorMessage && (
                    <div className="text-[11px] text-ds-danger truncate" title={node.errorMessage}>
                        {node.errorMessage}
                    </div>
                )}
            </div>
        </Tooltip>
    );
}

function MiniDagArrow() {
    return (
        <svg width="40" height="74" className="shrink-0 block" viewBox="0 0 40 74">
            <line x1="0" y1="37" x2="32" y2="37" stroke="#cbd5e1" strokeWidth={2}/>
            <polygon points="32,32 40,37 32,42" fill="#cbd5e1"/>
        </svg>
    );
}

function MiniDag({nodes}: { nodes: NodeExecution[] }) {
    if (nodes.length === 0) {
        return <DsTableEmpty description="无节点执行记录"/>;
    }
    return (
        <div>
            <div className="text-ds-small font-semibold text-ds-text-secondary mb-ds-3">
                节点执行详情
            </div>
            <div
                className="flex items-center p-ds-3 bg-ds-bg-secondary border border-ds-border-subtle rounded-ds-md overflow-x-auto min-h-[110px]">
                {nodes.map((n, i) => (
                    <span key={String(n.id ?? n.nodeId ?? i)} className="inline-flex items-center">
                        {i > 0 && <MiniDagArrow/>}
                        <MiniDagNode node={n}/>
                    </span>
                ))}
            </div>
            <div className="flex gap-ds-4 mt-ds-3 text-ds-small text-ds-text-muted">
                <span className="inline-flex items-center gap-ds-2">
                    <span className="w-2.5 h-2.5 rounded-full" style={{background: NODE_STATUS_COLOR.SUCCESS}}/>
                    成功
                </span>
                <span className="inline-flex items-center gap-ds-2">
                    <span className="w-2.5 h-2.5 rounded-full" style={{background: NODE_STATUS_COLOR.FAILED}}/>
                    失败
                </span>
                <span className="inline-flex items-center gap-ds-2">
                    <span className="w-2.5 h-2.5 rounded-full" style={{background: NODE_STATUS_COLOR.SKIPPED}}/>
                    被跳过
                </span>
            </div>
        </div>
    );
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
    const [drawerOpen, setDrawerOpen] = useState(false);
    const [selectedExecution, setSelectedExecution] = useState<DagExecution | null>(null);

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

    // RUNNING 自动刷新：5s 轮询，60s 兜底停止（统一走 usePollingWhile）
    const hasRunning = useMemo(() => data.some(d => d.status === 'RUNNING'), [data]);
    usePollingWhile(hasRunning, reload);

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

    const handleDagNameClick = useCallback((record: DagExecution) => {
        if (record.dagId != null && record.id != null) {
            // 跳转到只读运行画布，展示该次 execution 的实际节点运行信息
            navigate(`/engineering/dags/${record.dagId}/executions/${record.id}`, {
                state: {from: '/engineering/dag-executions'},
            });
        }
    }, [navigate]);

    // Sprint 3 P1-13：重跑失败节点（Mvp 简化版：复用 trigger 重新跑所有节点）
    const handleRerun = useCallback((record: DagExecution) => {
        if (record.dagId == null || record.id == null) {
            notify.warning('记录缺少 dagId/executionId，无法重跑');
            return;
        }
        Modal.confirm({
            centered: true,
            wrapClassName: 'prototype-modal',
            title: '重跑失败节点',
            content: `将基于 DAG「${record.dagName || record.dagId}」重新触发一次执行（当前实现会重跑所有节点，真正的"只重跑失败节点"将在 P2 实现）。是否继续？`,
            okText: '重跑',
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

    const columns = useMemo<ColumnsType<DagExecution>>(() => [
        {
            title: '执行时间',
            dataIndex: 'startTime',
            width: 170,
            render: (v?: string) => formatDateTime(v),
        },
        {
            title: '所属 DAG',
            dataIndex: 'dagName',
            width: 200,
            render: (v, r) =>
                r.dagId != null ? (
                    <Button
                        type="link"
                        size="small"
                        style={{padding: 0, height: 'auto'}}
                        onClick={() => handleDagNameClick(r)}
                    >
                        {v || '-'}
                    </Button>
                ) : (
                    <span style={{color: '#1e293b'}}>{v || '-'}</span>
                ),
        },
        {
            title: '执行方式',
            dataIndex: 'triggerType',
            width: 110,
            render: (v?: string) => (
                <DsStatusBadge label={TRIGGER_LABEL[v || ''] || v || '-'} variant="accent"/>
            ),
        },
        {
            title: '状态',
            dataIndex: 'status',
            width: 100,
            render: (v: string) => (
                <DsStatusBadge
                    label={NODE_STATUS_LABEL[v] || v || '-'}
                    variant={executionStatusVariant(v)}
                />
            ),
        },
        {
            title: '耗时',
            dataIndex: 'durationMs',
            width: 100,
            render: (v?: number) => formatDuration(v),
        },
        {
            title: '节点执行情况',
            dataIndex: 'nodeExecutions',
            width: 280,
            render: (_, r) => (
                <span style={{color: '#475569', fontSize: 13}}>{nodeSummary(r)}</span>
            ),
        },
        {
            title: '操作',
            width: 110,
            align: 'center',
            fixed: 'right' as const,
            render: (_, r) => (
                <Space size={4}>
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
                    <Tooltip title="节点执行详情">
                        <DsIconButton
                            tone="accent"
                            onClick={() => {
                                setSelectedExecution(r);
                                setDrawerOpen(true);
                            }}
                        >
                            <HiOutlineShare size={14}/>
                        </DsIconButton>
                    </Tooltip>
                    <Tooltip title="Sprint 5 支持">
                        <DsIconButton tone="default" disabled>
                            <HiOutlineDocumentText size={14}/>
                        </DsIconButton>
                    </Tooltip>
                </Space>
            ),
        },
    ], [canEdit, handleDagNameClick, handleRerun]);

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
                            scroll={{x: 1100}}
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

            {/* 右侧节点执行详情抽屉 */}
            <Drawer
                title={
                    <div>
                        <div className="text-ds-text-primary font-semibold">
                            {selectedExecution?.dagName || 'DAG'} 节点执行详情
                        </div>
                        <div className="text-ds-text-muted text-ds-small mt-ds-1">
                            {formatDateTime(selectedExecution?.startTime)}
                            {' · '}
                            <DsStatusBadge
                                label={NODE_STATUS_LABEL[selectedExecution?.status || ''] || selectedExecution?.status || '-'}
                                variant={executionStatusVariant(selectedExecution?.status)}
                            />
                        </div>
                    </div>
                }
                open={drawerOpen}
                onClose={() => setDrawerOpen(false)}
            >
                {selectedExecution && (
                    <MiniDag nodes={selectedExecution.nodeExecutions || []}/>
                )}
            </Drawer>
        </div>
    );
}
