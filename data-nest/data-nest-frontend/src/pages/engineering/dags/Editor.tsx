// DAG 编辑器（ReactFlow 画布 + 节点配置 + 右侧属性面板）
// Sprint 3: 节点状态边框/端口/状态图标 + design token + 三栏布局 + 同步任务摘要
import {Fragment, lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {useLocation, useNavigate, useParams, useSearchParams} from 'react-router-dom';
import ReactFlow, {
    addEdge,
    type Connection,
    Controls,
    type Edge,
    type EdgeChange,
    Handle,
    type Node,
    type NodeChange,
    type NodeProps,
    Position,
    ReactFlowProvider,
    useEdgesState,
    useNodesState,
    useReactFlow,
} from 'reactflow';
import 'reactflow/dist/style.css';
import {Form, Input, Modal, Popover, Select, Spin, Switch, Tag} from 'antd';
import {HiOutlineDocumentText, HiOutlineEye, HiOutlinePencilSquare, HiOutlinePlayCircle} from 'react-icons/hi2';
import DsButton from '../../../components/DsButton';
import Drawer from '../../../components/Drawer';
import {
    createDag,
    createDagParameter,
    getDag,
    getDagExecution,
    getNodeExecutionLogs,
    listDagParameters,
    listDags,
    putDagAlertRule,
    triggerDag,
    updateDag
} from './api';
import {validateDagParameters} from './utils/validateDagParameters';
import {getSyncJob, querySyncJobs} from '../../../api/sync';
import {formatDateTime, formatDuration} from '../../../utils/format';
import {notify} from '../../../utils/notify';
import {layoutWithDagre} from '../../../utils/dagLayout';
import CronPicker from '../../../components/CronPicker';
import DagParameterDrawer from './components/DagParameterDrawer';
import TriggerParamsModal from './components/TriggerParamsModal';
import DagVersionModal from './components/DagVersionModal';
import ConditionNodeModal from './components/ConditionNodeModal';
import SubDagNodeModal from './components/SubDagNodeModal';
import NodeRuntimeLogPanel from './components/NodeRuntimeLogPanel';
import AlertRuleModal from '../../../components/AlertRuleModal';
import {HistoryLogModal} from '../sync-jobs/history-common';
import {describeCron} from '../../../utils/cron';
import type {ConditionBranch, Dag, DagExecution, DagParameter, NodeExecution, NodeType} from './types';
import type {AlertRuleDTO} from '../../../types/alert';
import type {SyncJob, SyncJobLog} from '../../../types/sync';
import {useCanEdit} from '../../../hooks/useCanEdit';
import {usePollingWhile} from '../../../hooks/usePollingWhile';
import {NODE_STATUS_COLOR, NODE_STATUS_LABEL} from '../../../constants/statusColors';

const SqlEditorModal = lazy(() => import('./components/SqlEditorModal'));
const PythonEditorModal = lazy(() => import('./components/PythonEditorModal'));

// 后端 Result<T> 包裹的同步任务详情（取 data 字段）
type SyncJobDetail = SyncJob;

// 同步任务列表：与同步任务列表页同一接口（POST /page 分页查询）
const listSyncJobs = async (): Promise<SyncJob[]> => {
    const result = await querySyncJobs({page: 1, pageSize: 1000});
    return result.data.records;
};

// ─────────── 节点状态类型（保留字段以兼容历史数据，编辑器不再做运行态可视化） ───────────
type NodeStatus = 'IDLE' | 'WAITING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'SKIPPED';

type RFNodeData = {
    nodeName: string;
    nodeType: NodeType;
    sqlContent?: string;
    syncJobId?: number | string;
    syncJobName?: string;
    /** PYTHON 节点脚本与执行限制（Sprint 4） */
    pythonScript?: string;
    timeoutMinutes?: number;
    memoryLimitMb?: number;
    /** CONDITION 节点分支配置（Sprint 5） */
    branches?: ConditionBranch[];
    /** SUB_DAG 节点配置（Sprint 5） */
    subDagId?: number | string;
    subDagName?: string;
    syncExecution?: boolean;
    status?: NodeStatus;
    /** 执行视图：节点运行信息 */
    durationMs?: number;
    outputInfo?: string;
    errorMessage?: string;
    nodeExecutionStartTime?: string;
    nodeExecutionEndTime?: string;
    /** 重跑实例：该节点是否复用上轮结果（startTime 早于实例开始时间 ⇒ 复用，未重跑） */
    reused?: boolean;
    /** 执行视图：节点执行记录 id（SYNC 节点「查看日志」用） */
    nodeExecutionId?: string;
    /** SQL 节点最近一次「运行测试」结果（随 dag config 持久化） */
    lastTestStatus?: 'PASSED' | 'FAILED';
    /** 节点卡片铅笔图标的显式编辑入口（双击/属性面板共用同一处理） */
    onEditRequest?: (nodeId: string, data: RFNodeData) => void;
};

// 从 SQL 中粗略解析目标表（CREATE TABLE / CTAS）
function extractOutputTable(sql?: string): string | null {
    if (!sql) return null;
    const normalized = sql.replace(/\s+/g, ' ').trim();
    // CREATE TABLE [db.]table
    let m = normalized.match(/CREATE TABLE\s+(`?)([\w.]+)\1/i);
    if (m) return m[2];
    // CREATE TABLE [db.]table AS
    m = normalized.match(/CREATE TABLE\s+(`?)([\w.]+)\1\s+AS/i);
    if (m) return m[2];
    return null;
}

// ─────────── 自定义 DAG 节点组件（统一 SQL/SYNC/PYTHON/CONDITION/SUB_DAG；运行视图带状态色/耗时） ───────────
const NODE_TYPE_ICON: Record<NodeType, string> = {SQL: '📝', SYNC: '🔄', PYTHON: '🐍', CONDITION: '🔀', SUB_DAG: '📦'};
const NODE_TYPE_LABEL: Record<NodeType, string> = {
    SQL: 'SQL 任务',
    SYNC: '同步任务',
    PYTHON: 'Python 任务',
    CONDITION: '条件分支',
    SUB_DAG: '子 DAG',
};

function DagNode({id, data, selected}: NodeProps<RFNodeData>) {
    const icon = NODE_TYPE_ICON[data.nodeType] || '📝';
    const outputTable = data.nodeType === 'SQL' ? extractOutputTable(data.sqlContent) : null;
    const statusColor = data.status ? NODE_STATUS_COLOR[data.status] : undefined;
    // 条件分支/子 DAG 节点专属配色（对齐原型 .rf-node.condition / .rf-node.subdag）
    const typeAccent = data.nodeType === 'CONDITION'
        ? 'border-[#c4b5fd] bg-[#f5f3ff]'
        : data.nodeType === 'SUB_DAG'
            ? 'border-[#5eead4] bg-[#f0fdfa]'
            : 'border-ds-border-subtle bg-ds-bg-surface';
    const iconTone = data.nodeType === 'CONDITION'
        ? 'bg-[#ede9fe] text-[#7c3aed]'
        : data.nodeType === 'SUB_DAG'
            ? 'bg-[#ccfbf1] text-[#0d9488]'
            : data.nodeType === 'SQL'
                ? 'bg-ds-accent-light text-ds-accent'
                : data.nodeType === 'PYTHON'
                    ? 'bg-ds-warning-soft text-ds-warning'
                    : 'bg-ds-info-light text-ds-info';

    return (
        <div
            className={[
                'relative rounded-xl p-4 w-[220px] text-ds-small bg-ds-bg-surface font-sans shadow-sm',
                'border',
                typeAccent,
                selected ? 'ring-4 ring-ds-accent-glow' : '',
                // 重跑实例中复用上轮结果的节点置灰降噪，突出本次真正执行的节点
                data.reused ? 'opacity-50 grayscale' : '',
            ].filter(Boolean).join(' ')}
            style={statusColor ? {borderLeft: `4px solid ${statusColor}`} : undefined}
        >
            <Handle
                type="target"
                position={Position.Left}
                className="!w-[10px] !h-[10px] !rounded-full !bg-ds-text-muted !border-2 !border-ds-bg-surface hover:!bg-ds-accent"
                style={{left: -6}}
            />
            {/* SQL 节点最近运行测试结果小圆点（PASSED 绿 / FAILED 红） */}
            {data.lastTestStatus && (
                <span
                    className={`absolute top-2 right-2 w-2 h-2 rounded-full ${data.lastTestStatus === 'PASSED' ? 'bg-ds-success' : 'bg-ds-danger'}`}
                    title={data.lastTestStatus === 'PASSED' ? '最近一次运行测试通过' : '最近一次运行测试失败'}
                />
            )}
            {/* 显式编辑入口：仅编辑模式且选中时出现 */}
            {selected && data.onEditRequest && (
                <button
                    className={`nodrag absolute top-2 p-1 rounded-md text-ds-text-muted hover:text-ds-accent hover:bg-ds-accent-light transition-colors ${data.lastTestStatus ? 'right-6' : 'right-2'}`}
                    title="编辑节点"
                    onClick={e => {
                        e.stopPropagation();
                        data.onEditRequest?.(id, data);
                    }}
                >
                    <HiOutlinePencilSquare size={12}/>
                </button>
            )}
            <div className="flex items-center gap-2 mb-2 pb-2 border-b border-ds-border-subtle">
                <span
                    className={`w-6 h-6 rounded flex items-center justify-center text-ds-caption flex-shrink-0 ${iconTone}`}>{icon}</span>
                <span className="font-semibold text-ds-text-primary truncate flex-1">{data.nodeName}</span>
                {/* 重跑实例节点执行方式角标：复用上轮结果置灰「复用」，本次真正执行标「本次执行」 */}
                {data.reused === true && (
                    <span
                        className="shrink-0 px-1.5 py-0.5 rounded-full text-ds-caption font-medium bg-ds-bg-hover text-ds-text-muted">
                        复用
                    </span>
                )}
                {data.reused === false && data.status && (
                    <span
                        className="shrink-0 px-1.5 py-0.5 rounded-full text-ds-caption font-medium bg-ds-accent-light text-ds-accent">
                        本次执行
                    </span>
                )}
            </div>
            <div className="text-ds-text-secondary text-ds-caption leading-relaxed space-y-1">
                <div>类型：{NODE_TYPE_LABEL[data.nodeType] || data.nodeType}</div>
                {data.nodeType === 'SQL' ? (
                    <div className="truncate" title={outputTable || '（未配置输出表）'}>
                        输出：{outputTable || '—'}
                    </div>
                ) : data.nodeType === 'PYTHON' ? (
                    <div className="truncate"
                         title={data.pythonScript ? data.pythonScript.split('\n')[0] : '（未配置脚本）'}>
                        脚本：{data.pythonScript ? data.pythonScript.split('\n')[0] : '—'}
                    </div>
                ) : data.nodeType === 'CONDITION' ? (
                    <div className="truncate"
                         title={data.branches?.length ? `分支数：${data.branches.length}` : '（未配置分支）'}>
                        分支：{data.branches?.length ? `${data.branches.length} 个` : '—'}
                    </div>
                ) : data.nodeType === 'SUB_DAG' ? (
                    <div className="truncate" title={data.subDagName || String(data.subDagId) || '（未选择）'}>
                        {data.subDagName || data.subDagId || '（未选择）'}
                        {data.syncExecution === false ? '（异步）' : ''}
                    </div>
                ) : (
                    <div className="truncate" title={data.syncJobName || String(data.syncJobId) || '（未选择）'}>
                        {data.syncJobName || data.syncJobId || '（未选择）'}
                    </div>
                )}
                {data.status && (
                    <>
                        <div className="flex items-center gap-1">
                            <span>状态：</span>
                            <span style={{color: statusColor, fontWeight: 600}}>
                                {NODE_STATUS_LABEL[data.status] || data.status}
                            </span>
                        </div>
                        <div>耗时：{formatDuration(data.durationMs)}</div>
                        {data.nodeType === 'SQL' && (
                            <div className="truncate">
                                输出：{sqlOutputSummary(data.outputInfo) || '—'}
                            </div>
                        )}
                    </>
                )}
            </div>
            <Handle
                type="source"
                position={Position.Right}
                className="!w-[10px] !h-[10px] !rounded-full !bg-ds-text-muted !border-2 !border-ds-bg-surface hover:!bg-ds-accent"
                style={{right: -6}}
            />
        </div>
    );
}

const nodeTypes = {SQL: DagNode, SYNC: DagNode, PYTHON: DagNode, CONDITION: DagNode, SUB_DAG: DagNode};

// 画布点阵背景（与 DESIGN §4.24 canvas-area 对齐）
const dotBackgroundStyle: React.CSSProperties = {
    backgroundImage: 'radial-gradient(circle, #e2e6ed 1px, transparent 1px)',
    backgroundSize: '20px 20px',
};

// ─────────── 节点摘要（在 Drawer 中展示） ───────────
function NodeSummary({node, watchedSyncJobId}: { node: Node<RFNodeData>; watchedSyncJobId?: number | string }) {
    const [syncDetail, setSyncDetail] = useState<SyncJobDetail | null>(null);
    const [loading, setLoading] = useState(false);

    // 选完 syncJob 后拉详情（PR 1.16）
    useEffect(() => {
        if (node.data.nodeType !== 'SYNC') {
            setSyncDetail(null);
            return;
        }
        const id = watchedSyncJobId ?? node.data.syncJobId;
        if (!id) {
            setSyncDetail(null);
            return;
        }
        setLoading(true);
        getSyncJob(String(id))
            .then((res: { data?: SyncJobDetail }) => setSyncDetail(res?.data ?? null))
            .catch(() => setSyncDetail(null))
            .finally(() => setLoading(false));
    }, [node.data.nodeType, node.data.syncJobId, watchedSyncJobId]);

    if (node.data.nodeType === 'SQL') {
        return (
            <div className="bg-ds-bg-root border border-ds-border-subtle rounded-ds-sm p-ds-3 mb-ds-3">
                <div className="text-ds-caption text-ds-text-muted uppercase mb-ds-1">SQL 摘要</div>
                <pre
                    className="text-ds-caption text-ds-text-secondary font-mono whitespace-pre-wrap break-all m-0 max-h-32 overflow-auto">
                    {node.data.sqlContent || '（未配置 SQL）'}
                </pre>
            </div>
        );
    }

    if (node.data.nodeType === 'PYTHON') {
        return (
            <div className="bg-ds-bg-root border border-ds-border-subtle rounded-ds-sm p-ds-3 mb-ds-3">
                <div className="text-ds-caption text-ds-text-muted uppercase mb-ds-1">Python 脚本摘要</div>
                <pre
                    className="text-ds-caption text-ds-text-secondary font-mono whitespace-pre-wrap break-all m-0 max-h-32 overflow-auto">
                    {node.data.pythonScript || '（未配置脚本）'}
                </pre>
            </div>
        );
    }

    return (
        <div className="bg-ds-bg-root border border-ds-border-subtle rounded-ds-sm p-ds-3 mb-ds-3">
            <div className="text-ds-caption text-ds-text-muted uppercase mb-ds-2">同步任务摘要</div>
            {loading ? (
                <Spin size="small"/>
            ) : syncDetail ? (
                <div className="space-y-ds-1 text-ds-small">
                    <SummaryRow label="名称" value={syncDetail.name || '—'}/>
                    <SummaryRow
                        label="源表"
                        value={`${syncDetail.sourceDatabase || ''}.${syncDetail.sourceTables?.[0] || '—'}`}
                        mono
                    />
                    <SummaryRow
                        label="目标表"
                        value={`${syncDetail.targetDatabase || ''}.${syncDetail.targetTable || '—'}`}
                        mono
                    />
                    <SummaryRow
                        label="同步模式"
                        value={
                            syncDetail.syncMode === 'INCREMENTAL'
                                ? `增量同步${syncDetail.incrementalField ? ` (${syncDetail.incrementalField})` : ''}`
                                : syncDetail.syncMode === 'FULL'
                                    ? '全量同步'
                                    : syncDetail.syncMode || '—'
                        }
                    />
                    <SummaryRow
                        label="调度状态"
                        value={
                            // 与「批量数据同步」列表的调度状态列口径一致：
                            // MANUAL 无调度显示 —；CRON 按 scheduleEnabled 显示 已启用/已停用
                            syncDetail.triggerType === 'MANUAL' ? (
                                <span className="text-ds-text-muted">—</span>
                            ) : (
                                <Tag color={syncDetail.scheduleEnabled ? 'green' : 'default'} className="!m-0">
                                    {syncDetail.scheduleEnabled ? '已启用' : '已停用'}
                                </Tag>
                            )
                        }
                    />
                </div>
            ) : (
                <div className="text-ds-text-muted text-ds-small">暂无详情</div>
            )}
        </div>
    );
}

function SummaryRow({label, value, mono}: { label: string; value: React.ReactNode; mono?: boolean }) {
    return (
        <div className="flex justify-between gap-ds-2">
            <span className="text-ds-text-muted shrink-0">{label}</span>
            <span className={`text-ds-text-primary text-right truncate ${mono ? 'font-mono text-ds-caption' : ''}`}>
                {value}
            </span>
        </div>
    );
}

function PropertyRow({label, value}: { label: string; value: React.ReactNode }) {
    return (
        <div className="flex justify-between gap-ds-2 text-ds-small">
            <span className="text-ds-text-muted shrink-0">{label}</span>
            <span className="text-ds-text-primary text-right truncate ml-ds-2">{value}</span>
        </div>
    );
}

interface SqlOutputInfo {
    sqlType?: 'QUERY' | 'DML' | 'DDL' | 'UNKNOWN';
    affectedRows?: number;
    returnedRows?: number;
    columns?: string[];
    previewRows?: (string | number | boolean | null)[][];
    truncated?: boolean;
    targetTable?: string;
    registeredTables?: string[];
}

function parseSqlOutputInfo(outputInfo?: string): SqlOutputInfo | null {
    if (!outputInfo) return null;
    try {
        const parsed = JSON.parse(outputInfo) as SqlOutputInfo;
        if (parsed && typeof parsed === 'object') return parsed;
        return null;
    } catch {
        return null;
    }
}

function sqlOutputSummary(outputInfo?: string): string | null {
    const info = parseSqlOutputInfo(outputInfo);
    if (!info) return null;
    if (info.sqlType === 'QUERY') {
        return `返回 ${info.returnedRows ?? 0} 行`;
    }
    if (info.sqlType === 'DML') {
        return `影响 ${info.affectedRows ?? 0} 行`;
    }
    if (info.sqlType === 'DDL') {
        return info.targetTable ? `${info.targetTable}` : 'DDL 执行成功';
    }
    return null;
}

function SqlOutputDisplay({outputInfo}: { outputInfo?: string }) {
    const info = parseSqlOutputInfo(outputInfo);
    if (!info) {
        return (
            <pre
                className="text-ds-nano text-ds-text-secondary font-mono whitespace-pre-wrap break-all m-0 max-h-40 overflow-auto">
                {outputInfo}
            </pre>
        );
    }

    const summaryItems: { label: string; value: React.ReactNode }[] = [];
    if (info.targetTable) {
        summaryItems.push({
            label: '目标表',
            value: <span className="font-mono text-ds-caption">{info.targetTable}</span>,
        });
    }
    if (info.sqlType === 'QUERY') {
        summaryItems.push({
            label: '返回行数',
            value: (
                <span>
                    {info.returnedRows ?? 0}
                    {info.truncated && (
                        <span className="text-ds-text-muted ml-ds-1">（仅预览前 50 行）</span>
                    )}
                </span>
            ),
        });
    } else if (info.sqlType === 'DML') {
        summaryItems.push({label: '影响行数', value: info.affectedRows ?? 0});
    }
    if (info.registeredTables && info.registeredTables.length > 0) {
        summaryItems.push({label: '注册表', value: info.registeredTables.join(', ')});
    }

    return (
        <div className="space-y-ds-3">
            {/* 类型标签 + 摘要 */}
            <div className="flex items-center gap-ds-2">
                <span
                    className="text-ds-caption font-semibold px-ds-2 py-0.5 rounded bg-ds-bg-hover text-ds-text-secondary">
                    {info.sqlType || 'UNKNOWN'}
                </span>
                {info.sqlType === 'QUERY' && (
                    <span className="text-ds-small text-ds-text-muted">
                        返回 {info.returnedRows ?? 0} 行
                    </span>
                )}
                {info.sqlType === 'DML' && (
                    <span className="text-ds-small text-ds-text-muted">
                        影响 {info.affectedRows ?? 0} 行
                    </span>
                )}
                {info.sqlType === 'DDL' && info.targetTable && (
                    <span className="text-ds-small text-ds-text-muted truncate">
                        {info.targetTable}
                    </span>
                )}
            </div>

            {summaryItems.length > 0 && (
                <div className="grid grid-cols-[auto_1fr] gap-x-ds-4 gap-y-1.5 text-ds-small items-baseline">
                    {summaryItems.map(item => (
                        <Fragment key={item.label}>
                            <span className="text-ds-text-muted">{item.label}</span>
                            <span className="text-ds-text-primary text-right truncate" title={String(item.value)}>
                                {item.value}
                            </span>
                        </Fragment>
                    ))}
                </div>
            )}

            {/* QUERY 结果预览表 */}
            {info.sqlType === 'QUERY' && (
                <div className="border border-ds-border-subtle rounded-ds-sm overflow-hidden">
                    {(info.previewRows || []).length > 0 ? (
                        <div className="overflow-auto max-h-52">
                            <table className="w-full text-left">
                                <thead className="bg-ds-bg-hover sticky top-0">
                                <tr>
                                    {(info.columns || []).map(col => (
                                        <th
                                            key={col}
                                            className="px-ds-3 py-1.5 text-ds-caption text-ds-text-primary font-semibold whitespace-nowrap border-b border-ds-border-subtle"
                                        >
                                            {col}
                                        </th>
                                    ))}
                                </tr>
                                </thead>
                                <tbody>
                                {info.previewRows!.map((row, ri) => (
                                    <tr
                                        key={ri}
                                        className="border-t border-ds-border-subtle first:border-t-0 hover:bg-ds-bg-hover"
                                    >
                                        {row.map((cell, ci) => (
                                            <td
                                                key={ci}
                                                className="px-ds-3 py-1.5 text-ds-caption text-ds-text-secondary whitespace-nowrap"
                                            >
                                                {cell === null || cell === undefined
                                                    ? <span className="text-ds-text-muted italic">NULL</span>
                                                    : String(cell)}
                                            </td>
                                        ))}
                                    </tr>
                                ))}
                                </tbody>
                            </table>
                        </div>
                    ) : (
                        <div className="text-ds-small text-ds-text-muted text-center py-ds-4">
                            执行成功，返回 0 行
                        </div>
                    )}
                </div>
            )}
        </div>
    );
}

// ─────────── 右侧属性面板（编辑模式：只读摘要 + 编辑按钮；运行模式：运行信息 + 实时日志） ───────────
function PropertyPanel({
                           node,
                           onEdit,
                           readOnly,
                           onViewLogs,
                           executionId,
                       }: {
    node: Node<RFNodeData> | null;
    onEdit: () => void;
    readOnly?: boolean;
    /** 运行视图 SYNC 节点「查看日志」（仅 nodeExecutionId 存在时可点） */
    onViewLogs?: () => void;
    /** 运行视图执行实例 id：SQL/PYTHON 节点实时日志（Sprint 4） */
    executionId?: string;
}) {
    if (!node) {
        return (
            <div
                className="w-ds-property-panel bg-ds-bg-surface border-l border-ds-border-subtle p-ds-5 overflow-y-auto flex-shrink-0">
                <div
                    className="text-ds-subhead text-ds-text-primary mb-ds-4">{readOnly ? '节点运行信息' : '节点属性'}</div>
                <div className="text-ds-small text-ds-text-muted leading-relaxed">
                    {readOnly ? '选中节点查看本次执行状态、耗时及输出。' : '选中画布上的节点，查看节点基础信息。双击节点可进入编辑弹窗。'}
                </div>
            </div>
        );
    }
    const outputTable = node.data.nodeType === 'SQL' ? extractOutputTable(node.data.sqlContent) : null;
    const statusColor = node.data.status ? NODE_STATUS_COLOR[node.data.status] : undefined;
    return (
        <div
            className="w-ds-property-panel bg-ds-bg-surface border-l border-ds-border-subtle p-ds-5 overflow-y-auto flex-shrink-0">
            <div
                className="text-ds-subhead text-ds-text-primary mb-ds-4">{readOnly ? '节点运行信息' : '节点属性'}</div>
            <div className="space-y-ds-3 mb-ds-5 text-ds-small">
                <PropertyRow label="名称" value={node.data.nodeName}/>
                <PropertyRow label="类型" value={NODE_TYPE_LABEL[node.data.nodeType] || node.data.nodeType}/>
                {node.data.nodeType === 'SQL' ? (
                    <PropertyRow label="输出表" value={outputTable || '—'}/>
                ) : node.data.nodeType === 'PYTHON' ? (
                    <>
                        <PropertyRow label="超时" value={`${node.data.timeoutMinutes ?? 30} 分钟`}/>
                        <PropertyRow label="内存限制" value={`${node.data.memoryLimitMb ?? 2048} MB`}/>
                    </>
                ) : node.data.nodeType === 'CONDITION' ? (
                    <PropertyRow label="分支数" value={`${node.data.branches?.length ?? 0} 个`}/>
                ) : node.data.nodeType === 'SUB_DAG' ? (
                    <>
                        <PropertyRow label="子 DAG" value={node.data.subDagName || node.data.subDagId || '—'}/>
                        <PropertyRow label="执行方式"
                                     value={node.data.syncExecution === false ? '异步执行' : '同步执行'}/>
                    </>
                ) : (
                    <PropertyRow label="同步任务" value={node.data.syncJobName || node.data.syncJobId || '—'}/>
                )}
                {readOnly && node.data.status && (
                    <>
                        {node.data.reused != null && (
                            <PropertyRow
                                label="执行方式"
                                value={node.data.reused
                                    ? '复用上轮结果（未重跑）'
                                    : '本次重新执行'}
                            />
                        )}
                        <PropertyRow
                            label="状态"
                            value={
                                <span style={{color: statusColor, fontWeight: 600}}>
                                    {NODE_STATUS_LABEL[node.data.status] || node.data.status}
                                </span>
                            }
                        />
                        <PropertyRow label="耗时" value={formatDuration(node.data.durationMs)}/>
                        <PropertyRow label="开始时间"
                                     value={node.data.nodeExecutionStartTime ? formatDateTime(node.data.nodeExecutionStartTime) : '—'}/>
                        <PropertyRow label="结束时间"
                                     value={node.data.nodeExecutionEndTime ? formatDateTime(node.data.nodeExecutionEndTime) : '—'}/>
                        {node.data.errorMessage && (
                            <div className="text-ds-danger text-ds-caption break-all">{node.data.errorMessage}</div>
                        )}
                        {node.data.outputInfo && (
                            <div className="bg-ds-bg-root border border-ds-border-subtle rounded-ds-sm p-ds-2 mt-ds-2">
                                <div className="text-ds-caption text-ds-text-muted mb-ds-1">输出</div>
                                {node.data.nodeType === 'SQL' ? (
                                    <SqlOutputDisplay outputInfo={node.data.outputInfo}/>
                                ) : (
                                    <pre
                                        className="text-ds-nano text-ds-text-secondary font-mono whitespace-pre-wrap break-all m-0 max-h-40 overflow-auto">
                                        {node.data.outputInfo}
                                    </pre>
                                )}
                            </div>
                        )}
                        {/* SYNC 节点执行日志（复用同步任务的 HistoryLogModal） */}
                        {node.data.nodeType === 'SYNC' && node.data.nodeExecutionId && onViewLogs && (
                            <DsButton variant="secondary" onClick={onViewLogs} className="w-full">
                                <HiOutlineDocumentText size={14}/> 查看日志
                            </DsButton>
                        )}
                        {/* SQL/PYTHON 节点实时日志（Sprint 4：RUNNING 时每 3 秒轮询） */}
                        {(node.data.nodeType === 'SQL' || node.data.nodeType === 'PYTHON') && executionId && (
                            <NodeRuntimeLogPanel
                                executionId={executionId}
                                nodeId={node.id}
                                status={node.data.status}
                            />
                        )}
                    </>
                )}
            </div>
            {!readOnly ? (
                <DsButton onClick={onEdit} className="w-full">
                    <HiOutlinePencilSquare size={14}/> 编辑节点
                </DsButton>
            ) : !executionId ? (
                <DsButton variant="secondary" onClick={onEdit} className="w-full">
                    <HiOutlineEye size={14}/> 查看节点
                </DsButton>
            ) : null}
        </div>
    );
}

// ─────────── 主组件（外层包 ReactFlowProvider 以便 useReactFlow 可用）───────────
export default function DagEditor() {
    return (
        <ReactFlowProvider>
            <DagEditorInner/>
        </ReactFlowProvider>
    );
}

function DagEditorInner() {
    const {id, executionId} = useParams<{ id: string; executionId?: string }>();
    const [searchParams] = useSearchParams();
    const navigate = useNavigate();
    const location = useLocation();
    // 进入来源（如全局执行历史页通过 state.from 传入）：「返回」优先回到来源页
    const fromPath = (location.state as { from?: string } | null)?.from;
    const reactFlowInstance = useReactFlow();
    const userCanEdit = useCanEdit();
    // 执行详情画布强制只读，无论用户角色
    const isRunView = !!executionId;
    // URL 显式 mode=view 时为只读查看模式（来自 DAG 列表「详情」入口）
    const viewOnly = searchParams.get('mode') === 'view';
    const canEdit = userCanEdit && !isRunView && !viewOnly;
    const isNew = !id || id === 'new';
    // projectId 是 Snowflake id，保留 string 不转 number（避免 19 位精度丢失）
    const projectId = searchParams.get('projectId') || '';

    const [rfNodes, setRfNodes, onNodesChangeRaw] = useNodesState<RFNodeData>([]);
    const [rfEdges, setRfEdges, onEdgesChangeRaw] = useEdgesState<Edge>([]);
    // 未保存标记：onNodesChange / onEdgesChange / setDag / 节点保存 / 节点删除 时置 true；保存成功后置 false
    const [isDirty, setIsDirty] = useState(false);
    const [dag, setDag] = useState<Dag>({
        projectId,
        name: '',
        triggerType: 'MANUAL',
        cronExpression: '',
        scheduleEnabled: false,
        maxParallelism: 3,
        status: 'ENABLED',
        nodes: [],
        edges: [],
    });
    const [execution, setExecution] = useState<DagExecution | null>(null);
    // 重跑实例运行视图：仅显示本次执行的节点，隐藏复用上轮结果的节点
    const [showOnlyRerun, setShowOnlyRerun] = useState(false);
    const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
    const [drawerOpen, setDrawerOpen] = useState(false);
    // SQL 节点编辑 modal(Sprint 3 §6.5：900x600 dark Monaco modal,替代 Drawer)
    const [sqlModalOpen, setSqlModalOpen] = useState(false);
    // PYTHON 节点编辑 modal（Sprint 4：与 SQL 同一交互范式）
    const [pythonModalOpen, setPythonModalOpen] = useState(false);
    // Sprint 4：DAG 参数抽屉 / 触发参数覆盖弹窗
    const [paramDrawerOpen, setParamDrawerOpen] = useState(false);
    // 新建 DAG 尚未保存时，参数草稿保存在本地，保存 DAG 后统一提交
    const [draftParams, setDraftParams] = useState<DagParameter[]>([]);
    // 新建 DAG 尚未保存时，告警草稿保存在本地，保存 DAG 后统一提交（Sprint 5 起走 alert_rule）
    const [draftAlertConfig, setDraftAlertConfig] = useState<AlertRuleDTO | undefined>(undefined);
    const [triggerModalOpen, setTriggerModalOpen] = useState(false);
    const [dagParams, setDagParams] = useState<DagParameter[]>([]);
    const [triggering, setTriggering] = useState(false);
    // Sprint 4：版本管理 / 按 DAG 告警规则弹窗（Sprint 5 起统一走 alert_rule）
    const [versionModalOpen, setVersionModalOpen] = useState(false);
    const [alertModalOpen, setAlertModalOpen] = useState(false);
    // Sprint 5：条件分支 / 子 DAG 节点编辑弹窗
    const [conditionModalOpen, setConditionModalOpen] = useState(false);
    const [subDagModalOpen, setSubDagModalOpen] = useState(false);
    // 子 DAG 节点选择器候选：已启用且非当前 DAG（循环引用由后端保存时阻断）
    const [candidateDags, setCandidateDags] = useState<{ id: string | number; name: string }[]>([]);
    const [syncJobs, setSyncJobs] = useState<SyncJob[]>([]);
    // 运行视图 SYNC 节点「查看日志」弹窗（复用同步任务的 HistoryLogModal）
    const [nodeLogOpen, setNodeLogOpen] = useState(false);
    const [nodeLogs, setNodeLogs] = useState<SyncJobLog[]>([]);
    const [nodeLogsLoading, setNodeLogsLoading] = useState(false);
    const [form] = Form.useForm();
    const watchedSyncJobId = Form.useWatch('syncJobId', form);
    const nodeIdRef = useRef(0);
    const edgeIdRef = useRef(0);
    // 最新 dag 的 ref：refreshExecution 轮询用它取坐标/边，
    // 避免把 dag 加进 useCallback 依赖导致加载 effect 在 setDag 后反复触发
    const dagRef = useRef(dag);

    useEffect(() => {
        dagRef.current = dag;
    }, [dag]);

    // 当前选中节点（属性面板 + Drawer 摘要共用）
    const selectedNode = useMemo(
        () => rfNodes.find(n => n.id === selectedNodeId) || null,
        [rfNodes, selectedNodeId],
    );

    // 从执行历史「节点执行情况」列的失败节点链接跳入：定位并选中该节点（只聚焦一次，
    // 运行视图轮询重建节点时不重复跳动）
    const focusNodeId = (location.state as { focusNodeId?: string } | null)?.focusNodeId;
    const focusDoneRef = useRef(false);
    useEffect(() => {
        if (!isRunView || !focusNodeId || focusDoneRef.current) return;
        const target = rfNodes.find(n => n.id === focusNodeId);
        if (!target) return;
        focusDoneRef.current = true;
        setSelectedNodeId(focusNodeId);
        // 节点宽约 180、高约 60，取节点中心让视野居中
        reactFlowInstance.setCenter(target.position.x + 90, target.position.y + 30, {zoom: 1.2, duration: 400});
    }, [isRunView, focusNodeId, rfNodes, reactFlowInstance]);

    // 子 DAG 节点选择器候选：已启用且非当前 DAG（循环引用由后端保存时阻断）
    const loadCandidateDags = useCallback(() => {
        const currentId = String(id || '');
        listDags().then(dags => {
            setCandidateDags((dags || [])
                .filter(d => d.status === 'ENABLED' && String(d.id) !== currentId)
                .map(d => ({id: d.id!, name: d.name})));
        }).catch(() => {
            // 错误提示由拦截器统一弹出
        });
    }, [id]);

    // 统一节点编辑入口（铅笔图标 / 双击 / 属性面板「编辑节点」共用）：
    // - SQL 节点：打开 900x600 dark Monaco modal
    // - PYTHON 节点：打开 PythonEditorModal（Sprint 4）
    // - SYNC 节点：打开节点配置 Drawer
    // - CONDITION 节点：打开条件分支配置弹窗（Sprint 5）
    // - SUB_DAG 节点：打开子 DAG 配置弹窗（Sprint 5）
    // data 直接来自节点 props，不查 rfNodes，避免闭包拿到过期节点数据
    const handleEditRequest = useCallback((nodeId: string, nodeData: RFNodeData) => {
        setSelectedNodeId(nodeId);
        if (nodeData.nodeType === 'SQL') {
            setSqlModalOpen(true);
            return;
        }
        if (nodeData.nodeType === 'PYTHON') {
            setPythonModalOpen(true);
            return;
        }
        if (nodeData.nodeType === 'CONDITION') {
            setConditionModalOpen(true);
            return;
        }
        if (nodeData.nodeType === 'SUB_DAG') {
            loadCandidateDags();
            setSubDagModalOpen(true);
            return;
        }
        form.setFieldsValue({
            nodeName: nodeData.nodeName,
            sqlContent: nodeData.sqlContent || '',
            syncJobId: nodeData.syncJobId,
        });
        setDrawerOpen(true);
    }, [form, loadCandidateDags]);

    // 拉取执行实例并重建画布：节点以 nodeExecutions 执行快照、边以 edgeSnapshot 边快照为准
    // （执行详情模式的初始加载与轮询共用），当前 DAG 定义只补充坐标 —— 已删除节点/连线的历史执行记录仍然可见
    const refreshExecution = useCallback(() => {
        if (!id || !executionId) return Promise.resolve();
        // 不转 Number()：19 位 Snowflake id 保持 string 比较，防止精度丢失
        return getDagExecution(id, executionId).then(ex => {
            setExecution(ex);
            const {nodes, edges} = buildRunViewGraph(ex, dagRef.current);
            // 轮询重建会丢 selected 标志：按 id 保留选中态，避免选中高亮/属性面板闪断；
            // 「仅看本次执行」开启时隐藏复用上轮结果的节点
            setRfNodes(prev => {
                const selectedIds = new Set(prev.filter(n => n.selected).map(n => n.id));
                return nodes.map(n => {
                    const merged = selectedIds.has(n.id) ? {...n, selected: true} : n;
                    return showOnlyRerun && n.data.reused === true ? {...merged, hidden: true} : merged;
                });
            });
            setRfEdges(edges);
        });
    }, [id, executionId, setRfNodes, setRfEdges, showOnlyRerun]);

    // 重跑实例「仅看本次执行」开关：立即在现有节点上应用/移除隐藏，避免等待轮询
    const toggleShowOnlyRerun = useCallback((only: boolean) => {
        setShowOnlyRerun(only);
        setRfNodes(prev => prev.map(n => (n.data.reused === true ? {...n, hidden: only} : n)));
    }, [setRfNodes]);

    // 加载已有 DAG 并重建画布（编辑模式初始加载 / 版本回滚后刷新共用）
    // 不转 Number()：19 位 Snowflake id 会被截断精度，reactflow 拿不到 nodes
    const loadDag = useCallback(() => {
        if (isNew || !id) return Promise.resolve();
        return getDag(id).then(d => {
            setDag(d);
            const rfnodes: Node<RFNodeData>[] = (d.nodes || []).map(n => {
                const cfg = parseConfig(n.config);
                return {
                    id: n.nodeId,
                    type: n.nodeType,
                    position: {x: n.positionX || 0, y: n.positionY || 0},
                    data: {
                        nodeName: n.nodeName,
                        nodeType: n.nodeType,
                        sqlContent: cfg.sqlContent,
                        syncJobId: cfg.syncJobId,
                        syncJobName: cfg.syncJobName,
                        pythonScript: cfg.pythonScript,
                        timeoutMinutes: cfg.timeoutMinutes,
                        memoryLimitMb: cfg.memoryLimitMb,
                        branches: cfg.branches,
                        subDagId: cfg.subDagId,
                        subDagName: cfg.subDagName,
                        syncExecution: cfg.syncExecution,
                        lastTestStatus: cfg.lastTestStatus,
                        onEditRequest: handleEditRequest,
                    },
                };
            });
            const rfedges: Edge[] = (d.edges || []).map(e => ({
                id: e.edgeId,
                source: e.sourceNodeId,
                target: e.targetNodeId,
                animated: true,
            }));
            setRfNodes(rfnodes);
            setRfEdges(rfedges);
            nodeIdRef.current = rfnodes.length;
            edgeIdRef.current = rfedges.length;
        });
    }, [id, isNew, handleEditRequest, setRfNodes, setRfEdges]);

    // 加载已有 DAG（编辑模式）或 DAG + execution（执行详情模式）
    useEffect(() => {
        if (isNew || !id) return;
        loadDag().then(() => {
            if (isRunView && executionId) {
                return refreshExecution();
            }
        }).then(() => {
            // 加载完成的初始状态不算 dirty（避免首次进入就被拦截）
            setIsDirty(false);
        }).catch(e => notify.error('加载 DAG 失败: ' + (e?.message || '')));
    }, [id, executionId, isRunView, refreshExecution, loadDag, isNew]);

    // 执行详情模式：RUNNING 时轮询刷新节点状态（与列表页统一的 usePollingWhile，1s 间隔）
    usePollingWhile(isRunView && execution?.status === 'RUNNING', refreshExecution, {interval: 1000});

    // 加载同步任务列表
    useEffect(() => {
        listSyncJobs().then(setSyncJobs).catch(() => {
        });
    }, []);

    // 把 onNodesChange 包一层：过滤掉 select/reset 这些"非内容变更"，其余都标 dirty
    // 只读模式（执行详情/无编辑权限）下禁止标 dirty，避免点击节点后返回被误拦截
    const onNodesChange = useCallback((changes: NodeChange[]) => {
        if (canEdit) {
            const real = changes.some(c => c.type !== 'select' && c.type !== 'reset' && c.type !== 'dimensions');
            if (real) setIsDirty(true);
        }
        onNodesChangeRaw(changes);
    }, [onNodesChangeRaw, canEdit]);

    // onEdgesChange 同理（连线删除/重置等也算内容变更）
    // 条件节点出线被删除时同步删除对应分支，保证分支与连线一致
    const onEdgesChange = useCallback((changes: EdgeChange[]) => {
        if (canEdit) {
            const real = changes.some(c => c.type !== 'select' && c.type !== 'reset');
            if (real) setIsDirty(true);
            const removed = changes.filter(c => c.type === 'remove');
            if (removed.length > 0) {
                const removedIds = new Set(removed.map(c => c.id));
                const removedEdges = rfEdges.filter(e => removedIds.has(e.id));
                const condRemovals = removedEdges.filter(e => {
                    const src = rfNodes.find(n => n.id === e.source);
                    return src?.data.nodeType === 'CONDITION';
                });
                if (condRemovals.length > 0) {
                    const removedTargets = new Set(condRemovals.map(e => e.target));
                    setRfNodes(ns => ns.map(n => {
                        if (n.id !== condRemovals[0].source) return n;
                        const branches = (n.data.branches || []).filter(b => !removedTargets.has(b.nextNodeId));
                        return {...n, data: {...n.data, branches}};
                    }));
                }
            }
        }
        onEdgesChangeRaw(changes);
    }, [onEdgesChangeRaw, canEdit, rfEdges, rfNodes, setRfNodes]);

    const onConnect = useCallback((params: Connection) => {
        if (!canEdit) return;
        if (!params.source || !params.target) return;
        // 结构校验：DAG 必须无环、不自连、无重复边
        if (params.source === params.target) {
            notify.warning('不能将节点连接到自身');
            return;
        }
        if (rfEdges.some(e => e.source === params.source && e.target === params.target)) {
            notify.warning('两个节点之间已存在相同的连线');
            return;
        }
        // 子 DAG 节点只能引出一条出线（PRD §6.4.4）：已有一条出线时拒绝
        const sourceNode = rfNodes.find(n => n.id === params.source);
        if (sourceNode?.data.nodeType === 'SUB_DAG' &&
            rfEdges.some(e => e.source === params.source)) {
            notify.warning('子 DAG 节点只能引出一条出线');
            return;
        }
        if (wouldCreateCycle(rfEdges, params.source, params.target)) {
            notify.warning('该连线会形成循环依赖，DAG 不允许成环');
            return;
        }
        const newEdge: Edge = {
            id: `e${++edgeIdRef.current}_${Date.now()}`,
            source: params.source,
            target: params.target,
            animated: true,
        };
        setRfEdges(eds => addEdge(newEdge, eds));
        // 条件节点连出线 → 自动补一个分支指向目标节点，保持分支与连线一致（分支名/表达式留空待配置）
        if (sourceNode?.data.nodeType === 'CONDITION') {
            setRfNodes(ns => ns.map(n => {
                if (n.id !== params.source) return n;
                const branches = [...(n.data.branches || [])];
                if (!branches.some(b => b.nextNodeId === params.target)) {
                    branches.push({branchName: '', expression: '', nextNodeId: params.target!});
                }
                return {...n, data: {...n.data, branches}};
            }));
        }
        setIsDirty(true);
    }, [setRfEdges, setRfNodes, rfEdges, rfNodes, canEdit]);

    /**
     * 在指定画布坐标添加一个节点（拖拽 / 自动布局复用）
     * @param type SQL | SYNC | PYTHON | CONDITION | SUB_DAG
     * @param position 画布坐标（来自 screenToFlowPosition）
     */
    const addNodeAt = useCallback((type: NodeType, position: { x: number; y: number }) => {
        const newId = `n${++nodeIdRef.current}_${Date.now()}`;
        const newNode: Node<RFNodeData> = {
            id: newId,
            type,
            position,
            data: {
                nodeName: NODE_TYPE_LABEL[type] || 'SQL 任务',
                nodeType: type,
                sqlContent: undefined,
                // SYNC 节点不默认选中任务：静默绑定错任务的风险太大，让用户显式选择（Drawer 有 required 校验）
                syncJobId: undefined,
                syncJobName: undefined,
                pythonScript: undefined,
                // CONDITION 节点默认 2 个分支（含默认兜底），由用户配置
                branches: type === 'CONDITION' ? [
                    {branchName: '默认分支', expression: 'true', nextNodeId: ''},
                    {branchName: '', expression: '', nextNodeId: ''},
                ] : undefined,
                onEditRequest: handleEditRequest,
            },
        };
        setRfNodes(ns => [...ns, newNode]);
        setIsDirty(true);
    }, [handleEditRequest, setRfNodes]);

    /**
     * 自动布局：调用 dagre 重算所有节点 position（PRD §6.10）
     * 方向：LR（左→右），ranksep 100 / nodesep 50
     */
    const handleAutoLayout = () => {
        if (rfNodes.length === 0) {
            notify.warning('画布无节点');
            return;
        }
        const relaid = layoutWithDagre<RFNodeData>(rfNodes, rfEdges, 'LR');
        setRfNodes(relaid);
        setIsDirty(true);
        // 等 DOM 更新后再 fitView，否则新位置可能看不见
        setTimeout(() => reactFlowInstance.fitView({padding: 0.2, maxZoom: 0.9, duration: 300}), 50);
    };

    /**
     * 拖拽释放：在画布坐标上新增一个节点（PRD §6.4.2）
     * 只读模式：直接拒绝（不调 addNodeAt，避免画布出现可编辑节点）
     */
    const onDrop = useCallback((event: React.DragEvent) => {
        event.preventDefault();
        if (!canEdit) return;
        const type = event.dataTransfer.getData('application/reactflow') as NodeType;
        if (type !== 'SQL' && type !== 'SYNC' && type !== 'PYTHON' && type !== 'CONDITION' && type !== 'SUB_DAG') return;
        const position = reactFlowInstance.screenToFlowPosition({
            x: event.clientX,
            y: event.clientY,
        });
        addNodeAt(type, position);
    }, [reactFlowInstance, canEdit, addNodeAt]);

    const onDragOver = useCallback((event: React.DragEvent) => {
        event.preventDefault();
        event.dataTransfer.dropEffect = 'move';
    }, []);

    // 单击节点 → 仅选中（不打开 Drawer）
    const handleNodeClick = useCallback((_e: React.MouseEvent, node: Node<RFNodeData>) => {
        setSelectedNodeId(node.id);
    }, []);

    // 双击节点 → 与铅笔图标走同一入口（运行视图禁用，查看/只读模式仍可打开配置弹窗）
    const handleNodeDoubleClick = (_: React.MouseEvent, node: Node<RFNodeData>) => {
        if (isRunView) return;
        handleEditRequest(node.id, node.data);
    };

    // 属性面板的"编辑节点"按钮：同一入口
    const handleEditFromPanel = useCallback(() => {
        if (!selectedNode) return;
        handleEditRequest(selectedNode.id, selectedNode.data);
    }, [selectedNode, handleEditRequest]);

    // 运行视图 SYNC 节点「查看日志」：按节点执行记录 id 拉日志（返回结构对齐同步任务日志接口）
    const handleViewNodeLogs = useCallback(async () => {
        const neId = selectedNode?.data.nodeExecutionId;
        if (!neId) return;
        setNodeLogOpen(true);
        setNodeLogsLoading(true);
        try {
            const result = await getNodeExecutionLogs(neId);
            setNodeLogs(result.data || []);
        } catch {
            // 错误提示由 request 拦截器统一弹出
            setNodeLogs([]);
        } finally {
            setNodeLogsLoading(false);
        }
    }, [selectedNode]);

    // SQL/PYTHON 节点「运行测试」结果回写节点 data：驱动卡片右上角状态点，随 serializeConfig 持久化
    const handleSqlTested = useCallback((status: 'PASSED' | 'FAILED') => {
        if (!selectedNodeId) return;
        setRfNodes(ns => ns.map(n => n.id === selectedNodeId ? {
            ...n,
            data: {...n.data, lastTestStatus: status},
        } : n));
        setIsDirty(true);
    }, [selectedNodeId, setRfNodes]);

    const handleSaveNode = async () => {
        const v = await form.validateFields();
        const node = rfNodes.find(n => n.id === selectedNodeId);
        if (!node) return;
        const syncJob = syncJobs.find((j) => j.id === v.syncJobId);
        setRfNodes(ns => ns.map(n => n.id === selectedNodeId ? {
            ...n,
            data: {
                ...n.data,
                nodeName: v.nodeName,
                sqlContent: v.sqlContent,
                syncJobId: v.syncJobId,
                syncJobName: syncJob?.name,
            },
        } : n));
        setDrawerOpen(false);
        setIsDirty(true);
    };

    const handleDeleteNode = useCallback((nodeId: string) => {
        setRfNodes(ns => ns.map(n => {
            // 删除节点时同步清理条件分支中指向该节点的分支，避免保存被后端「分支指向的节点不存在」拦截
            if (n.data.nodeType !== 'CONDITION' || !n.data.branches) return n;
            const branches = n.data.branches.filter(b => b.nextNodeId !== nodeId);
            return branches.length === n.data.branches.length ? n : {...n, data: {...n.data, branches}};
        }).filter(n => n.id !== nodeId));
        setRfEdges(es => es.filter(e => e.source !== nodeId && e.target !== nodeId));
        if (selectedNodeId === nodeId) setSelectedNodeId(null);
        setIsDirty(true);
    }, [setRfNodes, setRfEdges, selectedNodeId]);

    // 条件分支节点保存：更新节点数据 + 同步画布连线（条件节点出线 = 各分支 nextNodeId）
    const handleConditionNodeSave = useCallback((nodeName: string, branches: ConditionBranch[]) => {
        if (!selectedNodeId) return;
        setRfNodes(ns => ns.map(n => {
            if (n.id !== selectedNodeId) return n;
            return {...n, data: {...n.data, nodeName, branches}};
        }));
        setRfEdges(es => {
            const other = es.filter(e => e.source !== selectedNodeId);
            const branchTargets = branches.map(b => b.nextNodeId).filter(Boolean);
            const newEdges = branchTargets.map((target, i) => ({
                id: `e${++edgeIdRef.current}_${Date.now()}_${i}`,
                source: selectedNodeId!,
                target,
                animated: true,
            }));
            return [...other, ...newEdges];
        });
        setConditionModalOpen(false);
        setIsDirty(true);
        notify.success('条件分支节点已更新');
    }, [selectedNodeId, setRfNodes, setRfEdges]);

    // 子 DAG 节点保存
    const handleSubDagNodeSave = useCallback((
        nodeName: string,
        subDagId: string | number,
        subDagName: string,
        syncExecution: boolean,
    ) => {
        if (!selectedNodeId) return;
        setRfNodes(ns => ns.map(n => n.id === selectedNodeId ? {
            ...n,
            data: {...n.data, nodeName, subDagId, subDagName, syncExecution},
        } : n));
        setSubDagModalOpen(false);
        setIsDirty(true);
        notify.success('子 DAG 节点已更新');
    }, [selectedNodeId, setRfNodes]);

    // 实际保存（通过校验后调用）
    const doSave = useCallback(async () => {
        // 新建 DAG 时：若配置了本地参数草稿，先校验，随 DAG 创建后统一提交
        if (isNew && draftParams.length > 0) {
            const paramError = validateDagParameters(draftParams);
            if (paramError) {
                notify.error(paramError);
                return;
            }
        }
        // Sprint 5：条件分支 / 子 DAG 节点配置前置校验（后端同样校验，前端先拦截）
        for (const n of rfNodes) {
            if (n.data.nodeType === 'CONDITION') {
                const branches = n.data.branches || [];
                if (branches.length < 2) {
                    notify.error(`条件分支「${n.data.nodeName}」至少需要 2 个分支（含默认分支）`);
                    return;
                }
                for (const b of branches) {
                    if (!b.branchName?.trim() || !b.expression?.trim() || !b.nextNodeId) {
                        notify.error(`条件分支「${n.data.nodeName}」存在未完整配置的分支（分支名称/表达式/下游节点必填）`);
                        return;
                    }
                }
                const targets = branches.map(b => b.nextNodeId);
                if (new Set(targets).size !== targets.length) {
                    notify.error(`条件分支「${n.data.nodeName}」每个分支必须连接不同的下游节点`);
                    return;
                }
            }
            if (n.data.nodeType === 'SUB_DAG' && !n.data.subDagId) {
                notify.error(`子 DAG 节点「${n.data.nodeName}」未选择子 DAG`);
                return;
            }
        }
        const payload: Dag = {
            ...dag,
            nodes: rfNodes.map(n => ({
                nodeId: n.id,
                nodeName: n.data.nodeName,
                nodeType: n.data.nodeType,
                positionX: n.position.x,
                positionY: n.position.y,
                config: serializeConfig(n.data),
            })),
            edges: rfEdges.map(e => ({
                edgeId: e.id,
                sourceNodeId: e.source,
                targetNodeId: e.target,
            })),
        };
        try {
            let savedId = id;
            if (isNew) {
                const created = await createDag(payload);
                savedId = String(created.id);
                // 创建参数：DAG 保存成功后统一提交，失败则提示但 DAG 已创建
                if (draftParams.length > 0 || draftAlertConfig) {
                    try {
                        for (const p of draftParams) {
                            await createDagParameter(savedId, p);
                        }
                        if (draftAlertConfig) {
                            await putDagAlertRule(savedId, draftAlertConfig);
                        }
                        notify.success('DAG 与配置已创建');
                    } catch {
                        notify.error('DAG 已创建，但参数/告警保存失败，请重新打开对应配置提交');
                    }
                } else {
                    notify.success('DAG 已创建');
                }
            } else {
                // 不转 Number()：保持 string id 避免精度丢失
                await updateDag(id, payload);
                notify.success('DAG 已更新');
            }
            // 保存成功：清 dirty 标志与本地草稿（navigate 之前，避免 onBack 再次拦截）
            setIsDirty(false);
            setDraftParams([]);
            setDraftAlertConfig(undefined);
            navigate(`/engineering/dags/${savedId}/edit`);
        } catch {
            // 错误提示由 request 拦截器统一弹出
        }
    }, [dag, rfNodes, rfEdges, id, isNew, navigate, draftParams, draftAlertConfig]);

    const handleSave = useCallback(() => {
        if (!dag.name) {
            notify.error('DAG 名称必填');
            return;
        }
        if (rfNodes.length === 0) {
            notify.error('至少一个节点');
            return;
        }
        // 孤立节点提示：未连任何边的节点会作为独立入口并行执行，常是漏连线的信号
        if (rfNodes.length > 1) {
            const connected = new Set<string>();
            rfEdges.forEach(e => {
                connected.add(e.source);
                connected.add(e.target);
            });
            const isolated = rfNodes.filter(n => !connected.has(n.id));
            if (isolated.length > 0) {
                Modal.confirm({
                    centered: true,
                    wrapClassName: 'prototype-modal',
                    title: '存在未连线的节点',
                    content: `「${isolated.map(n => n.data.nodeName).join('、')}」没有任何依赖连线，保存后会作为独立入口并行执行。确认继续保存吗？`,
                    okText: '继续保存',
                    cancelText: '返回检查',
                    onOk: () => doSave(),
                });
                return;
            }
        }
        doSave();
    }, [dag.name, rfNodes, rfEdges, doSave]);

    /**
     * 「返回」按钮：优先回到来源页（如全局执行历史经 state.from 进入）；
     * 否则回项目 DAG 列表（新建时取 query string，编辑时取已加载 dag.projectId）；
     * 未保存时弹确认（PRD §6.4.1）
     * - 放弃：直接离开
     * - 保存并离开：调 handleSave，保存成功后由它自己 navigate
     */
    const handleBack = useCallback(() => {
        // 运行视图返回全局执行历史时不带 dagId/dagName 过滤：从详情回来不应把名称过滤套上
        const backTarget = isRunView
            ? (fromPath || '/engineering/dag-executions')
            : (fromPath || (dag.projectId ? `/engineering/dags/${dag.projectId}` : '/engineering/dags'));
        if (!isDirty) {
            navigate(backTarget);
            return;
        }
        Modal.confirm({
            centered: true,
            wrapClassName: 'prototype-modal',
            title: '未保存的更改',
            content: '当前 DAG 有未保存的更改。是否保存后再离开？',
            okText: '保存并离开',
            cancelText: '放弃',
            onOk: () => handleSave(),
            onCancel: () => {
                setIsDirty(false);
                navigate(backTarget);
            },
        });
    }, [isDirty, navigate, handleSave, dag.projectId, fromPath, isRunView]);

    /**
     * 离开页面前拦截：浏览器关闭/刷新时也提示（PRD §6.4.1）
     * 注意：浏览器只接受 e.returnValue = '' 来触发原生确认弹窗
     */
    useEffect(() => {
        const handler = (e: BeforeUnloadEvent) => {
            if (isDirty) {
                e.preventDefault();
                e.returnValue = '';
            }
        };
        window.addEventListener('beforeunload', handler);
        return () => window.removeEventListener('beforeunload', handler);
    }, [isDirty]);

    /**
     * 全局键盘快捷键（PRD §6.4.3）：
     * - Ctrl/Cmd + S：保存
     * - Delete / Backspace：删除选中节点（仅在非输入元素聚焦时，避免冲突）
     * 只读模式：跳过 Delete / Backspace 处理（不允许删节点）；Ctrl+S 也禁用
     */
    useEffect(() => {
        const handler = (e: KeyboardEvent) => {
            if (!canEdit) return;
            // Ctrl+S / Cmd+S：保存
            if ((e.ctrlKey || e.metaKey) && (e.key === 's' || e.key === 'S')) {
                e.preventDefault();
                handleSave();
                return;
            }
            // Delete / Backspace：删除选中节点（仅 body 焦点，避免和 input/textarea 冲突）
            if ((e.key === 'Delete' || e.key === 'Backspace') && selectedNodeId) {
                const target = e.target as HTMLElement | null;
                const tag = target?.tagName?.toLowerCase();
                if (tag === 'input' || tag === 'textarea' || target?.isContentEditable) return;
                e.preventDefault();
                Modal.confirm({
                    centered: true,
                    wrapClassName: 'prototype-modal',
                    title: '删除节点',
                    content: '该节点将被删除，与其相关的连线同时移除',
                    okText: '删除',
                    okType: 'danger',
                    cancelText: '取消',
                    onOk: () => handleDeleteNode(selectedNodeId),
                });
            }
        };
        window.addEventListener('keydown', handler);
        return () => window.removeEventListener('keydown', handler);
    }, [selectedNodeId, handleSave, canEdit, handleDeleteNode]);

    // 实际触发执行（参数覆盖可选；Sprint 4 参数化）
    const doTrigger = useCallback(async (overrides?: Record<string, unknown>) => {
        if (!id) return;
        setTriggering(true);
        try {
            // 不转 Number()：保持 string id 避免精度丢失
            await triggerDag(id, overrides);
            setTriggerModalOpen(false);
            // 触发成功给出可点击的跳转，补上「执行 → 看结果」的反馈闭环
            notify.success(
                <span>
                    已触发执行。
                    <a
                        className="text-ds-accent underline cursor-pointer ml-1"
                        onClick={() => navigate(`/engineering/dag-executions?dagId=${id}&dagName=${encodeURIComponent(dag.name || '')}`)}
                    >
                        查看执行 →
                    </a>
                </span>,
                5,
            );
        } catch {
            // 错误提示由 request 拦截器统一弹出
        } finally {
            setTriggering(false);
        }
    }, [id, dag.name, navigate]);

    const handleTrigger = async () => {
        if (!id) {
            notify.warning('请先保存');
            return;
        }
        // Sprint 4：DAG 存在参数时先弹参数覆盖弹窗（PRD §6.4.4）；无参数直接触发
        try {
            const params = await listDagParameters(id);
            if (params && params.length > 0) {
                setDagParams(params);
                setTriggerModalOpen(true);
                return;
            }
        } catch {
            // 参数加载失败：拦截器已提示，不再继续触发（避免按错误参数集执行）
            return;
        }
        doTrigger();
    };

    const executionStatusColor = execution?.status
        ? NODE_STATUS_COLOR[execution.status] || NODE_STATUS_COLOR.WAITING
        : undefined;

    return (
        <div className="h-screen flex flex-col bg-ds-bg-root">
            {/* 顶部工具栏 */}
            <div
                className="h-[56px] bg-ds-bg-surface border-b border-ds-border-subtle flex items-center px-ds-4 gap-ds-4 flex-shrink-0">
                <button
                    onClick={handleBack}
                    className="text-ds-small text-ds-text-secondary hover:text-ds-text-primary flex items-center gap-ds-1 px-ds-3 py-1.5 rounded-lg hover:bg-ds-bg-hover transition-colors"
                >
                    <span>←</span> {isRunView
                    ? '返回执行历史'
                    : (fromPath
                        ? '返回'
                        : (viewOnly ? '返回 DAG 列表' : '返回项目 DAG 列表'))}
                </button>
                {isRunView ? (
                    <>
                        <div
                            className="h-[34px] px-ds-3 flex items-center text-ds-body font-semibold text-ds-text-primary bg-ds-bg-root border border-ds-border-subtle rounded-lg w-[220px]">
                            {dag.name || '—'}
                        </div>
                        {execution && (
                            <div className="flex flex-col gap-ds-1">
                                <div className="flex items-center gap-ds-4 text-ds-small">
                                    <span className="flex items-center gap-1">
                                        状态：
                                        <span style={{color: executionStatusColor, fontWeight: 600}}>
                                            {NODE_STATUS_LABEL[execution.status] || execution.status}
                                        </span>
                                    </span>
                                    <span>触发：{execution.triggerType === 'MANUAL' ? '手动' : '定时'}</span>
                                    <span>耗时：{formatDuration(execution.durationMs)}</span>
                                    <span>开始：{formatDateTime(execution.startTime)}</span>
                                    <span>结束：{formatDateTime(execution.endTime)}</span>
                                </div>
                                {execution.status === 'FAILED' && execution.errorMessage && (
                                    <div className="text-ds-danger text-ds-caption max-w-[600px] truncate"
                                         title={execution.errorMessage}>
                                        失败原因：{execution.errorMessage}
                                    </div>
                                )}
                                {/* 重跑实例提示：本次重跑节点数 + 复用上轮结果节点数 + 「仅看本次执行」开关 */}
                                {(() => {
                                    const execNodes = execution.nodeExecutions || [];
                                    const reusedCount = execNodes.filter(
                                        ne => ne.startTime != null && execution.startTime != null && ne.startTime < execution.startTime,
                                    ).length;
                                    if (reusedCount <= 0) return null;
                                    const runCount = execNodes.length - reusedCount;
                                    return (
                                        <div className="flex items-center gap-ds-3 text-ds-small">
                                            <span className="text-ds-text-secondary">
                                                重跑实例 · 本次重跑 <strong
                                                className="text-ds-text-primary">{runCount}</strong> 个节点，
                                                其余 <strong className="text-ds-text-primary">{reusedCount}</strong> 个复用上轮结果
                                            </span>
                                            <span className="flex items-center gap-ds-1 text-ds-text-muted">
                                                <Switch
                                                    size="small"
                                                    checked={showOnlyRerun}
                                                    onChange={toggleShowOnlyRerun}
                                                />
                                                仅看本次执行
                                            </span>
                                        </div>
                                    );
                                })()}
                            </div>
                        )}
                    </>
                ) : (
                    <>
                        <input
                            className="h-[34px] px-ds-3 text-ds-body font-semibold text-ds-text-primary bg-ds-bg-root border border-ds-border-subtle rounded-lg outline-none focus:border-ds-accent w-[220px] disabled:bg-ds-bg-hover disabled:text-ds-text-muted"
                            placeholder="DAG 名称"
                            value={dag.name}
                            readOnly={!canEdit}
                            disabled={!canEdit}
                            onChange={e => {
                                setDag({...dag, name: e.target.value});
                                setIsDirty(true);
                            }}
                        />
                        <div className="flex items-center gap-ds-2">
                            <span className="text-ds-small text-ds-text-secondary">触发方式</span>
                            <Select
                                value={dag.triggerType}
                                disabled={!canEdit}
                                onChange={v => {
                                    setDag({...dag, triggerType: v});
                                    setIsDirty(true);
                                }}
                                options={[
                                    {value: 'MANUAL', label: '手动'},
                                    {value: 'CRON', label: '定时'},
                                ]}
                                className="w-[100px]"
                            />
                        </div>
                        {dag.triggerType === 'CRON' && (
                            <div className="flex items-center gap-ds-2">
                                <span className="text-ds-small text-ds-text-secondary">Cron</span>
                                <Popover
                                    trigger="click"
                                    placement="bottomLeft"
                                    arrow={false}
                                    content={
                                        <div style={{width: 560, maxHeight: '70vh', overflowY: 'auto'}}>
                                            <CronPicker
                                                value={dag.cronExpression || ''}
                                                onChange={v => {
                                                    setDag({...dag, cronExpression: v});
                                                    setIsDirty(true);
                                                }}
                                            />
                                        </div>
                                    }
                                >
                                    <button
                                        disabled={!canEdit}
                                        title={!canEdit ? '只读模式：您没有编辑权限' : undefined}
                                        className="h-[34px] w-[200px] px-ds-3 flex items-center justify-between gap-ds-2 bg-white border border-ds-border-subtle rounded-lg text-ds-small text-ds-text-primary hover:border-ds-accent disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
                                    >
                                        <span className="truncate">
                                            {describeCron(dag.cronExpression) || '选择调度周期'}
                                        </span>
                                        <span className="text-ds-text-muted text-ds-nano flex-shrink-0">▼</span>
                                    </button>
                                </Popover>
                            </div>
                        )}
                    </>
                )}
                <div className="flex-1"/>
                {!isRunView && (
                    <>
                        <DsButton variant="secondary" onClick={() => setParamDrawerOpen(true)}>
                            参数
                        </DsButton>
                        <DsButton variant="secondary" onClick={() => setVersionModalOpen(true)}
                                  disabled={isNew}
                                  title={isNew ? '请先保存 DAG 后再查看版本' : undefined}>
                            版本
                        </DsButton>
                        <DsButton variant="secondary" onClick={() => setAlertModalOpen(true)}
                                  disabled={!canEdit}
                                  title={!canEdit ? '只读模式：您没有编辑权限' : undefined}>
                            告警
                        </DsButton>
                        <DsButton variant="secondary" onClick={handleAutoLayout}
                                  disabled={!canEdit || rfNodes.length === 0}
                                  title={!canEdit ? '只读模式：您没有编辑权限' : undefined}>
                            自动布局
                        </DsButton>
                        <DsButton variant="secondary" onClick={handleSave}
                                  disabled={!canEdit}
                                  title={!canEdit ? '只读模式：您没有编辑权限' : undefined}>
                            保存
                        </DsButton>
                        <DsButton onClick={handleTrigger}
                                  disabled={!canEdit || isNew || rfNodes.length === 0}
                                  title={!canEdit ? '只读模式：您没有编辑权限' : undefined}>
                            <HiOutlinePlayCircle size={14}/> 执行
                        </DsButton>
                    </>
                )}
            </div>

            {/* 三栏：节点面板 | 画布 | 属性面板 */}
            <div className="flex-1 flex overflow-hidden">
                {/* 左侧：编辑模式显示节点添加面板；运行模式隐藏 */}
                {!isRunView && (
                    <div
                        className="w-[200px] bg-ds-bg-surface border-r border-ds-border-subtle p-ds-4 flex-shrink-0 overflow-y-auto">
                        <div
                            className="text-ds-caption font-bold text-ds-text-muted uppercase tracking-wider mb-ds-3">节点面板
                        </div>
                        <div className="space-y-ds-3">
                            {/* 拖拽源：SQL 节点（对齐原型 .palette-node：1.5px 边框、radius 12、padding 16px 12px） */}
                            <div
                                draggable={canEdit}
                                onDragStart={e => {
                                    if (!canEdit) {
                                        e.preventDefault();
                                        return;
                                    }
                                    e.dataTransfer.setData('application/reactflow', 'SQL');
                                    e.dataTransfer.effectAllowed = 'move';
                                }}
                                className={`flex flex-col items-center justify-center gap-ds-2 px-ds-3 py-ds-4 rounded-xl border-[1.5px] border-ds-border-subtle bg-ds-bg-surface ${
                                    canEdit ? 'cursor-grab active:cursor-grabbing hover:border-ds-accent hover:bg-ds-accent-light' : 'cursor-not-allowed opacity-50'
                                } transition-colors`}
                                title={!canEdit ? '只读模式：您没有编辑权限' : undefined}
                            >
                                <span className="text-ds-heading">📝</span>
                                <span className="text-ds-small font-semibold text-ds-text-secondary">SQL 任务</span>
                            </div>
                            {/* 拖拽源：Python 节点（Sprint 4） */}
                            <div
                                draggable={canEdit}
                                onDragStart={e => {
                                    if (!canEdit) {
                                        e.preventDefault();
                                        return;
                                    }
                                    e.dataTransfer.setData('application/reactflow', 'PYTHON');
                                    e.dataTransfer.effectAllowed = 'move';
                                }}
                                className={`flex flex-col items-center justify-center gap-ds-2 px-ds-3 py-ds-4 rounded-xl border-[1.5px] border-ds-border-subtle bg-ds-bg-surface ${
                                    canEdit ? 'cursor-grab active:cursor-grabbing hover:border-ds-accent hover:bg-ds-accent-light' : 'cursor-not-allowed opacity-50'
                                } transition-colors`}
                                title={!canEdit ? '只读模式：您没有编辑权限' : '标准库 + pandas'}
                            >
                                <span className="text-ds-heading">🐍</span>
                                <span className="text-ds-small font-semibold text-ds-text-secondary">Python 任务</span>
                            </div>
                            {/* 拖拽源：同步节点 */}
                            <div
                                draggable={canEdit}
                                onDragStart={e => {
                                    if (!canEdit) {
                                        e.preventDefault();
                                        return;
                                    }
                                    e.dataTransfer.setData('application/reactflow', 'SYNC');
                                    e.dataTransfer.effectAllowed = 'move';
                                }}
                                className={`flex flex-col items-center justify-center gap-ds-2 px-ds-3 py-ds-4 rounded-xl border-[1.5px] border-ds-border-subtle bg-ds-bg-surface ${
                                    canEdit ? 'cursor-grab active:cursor-grabbing hover:border-ds-accent hover:bg-ds-accent-light' : 'cursor-not-allowed opacity-50'
                                } transition-colors`}
                                title={!canEdit ? '只读模式：您没有编辑权限' : undefined}
                            >
                                <span className="text-ds-heading">🔄</span>
                                <span className="text-ds-small font-semibold text-ds-text-secondary">同步任务</span>
                            </div>
                            {/* 拖拽源：条件分支节点（Sprint 5） */}
                            <div
                                draggable={canEdit}
                                onDragStart={e => {
                                    if (!canEdit) {
                                        e.preventDefault();
                                        return;
                                    }
                                    e.dataTransfer.setData('application/reactflow', 'CONDITION');
                                    e.dataTransfer.effectAllowed = 'move';
                                }}
                                className={`flex flex-col items-center justify-center gap-ds-2 px-ds-3 py-ds-4 rounded-xl border-[1.5px] border-[#c4b5fd] bg-[#f5f3ff] ${
                                    canEdit ? 'cursor-grab active:cursor-grabbing hover:border-[#7c3aed] hover:bg-[#ede9fe]' : 'cursor-not-allowed opacity-50'
                                } transition-colors`}
                                title={!canEdit ? '只读模式：您没有编辑权限' : '按表达式选择下游分支'}
                            >
                                <span className="text-ds-heading">🔀</span>
                                <span className="text-ds-small font-semibold text-[#7c3aed]">条件分支</span>
                            </div>
                            {/* 拖拽源：子 DAG 节点（Sprint 5） */}
                            <div
                                draggable={canEdit}
                                onDragStart={e => {
                                    if (!canEdit) {
                                        e.preventDefault();
                                        return;
                                    }
                                    e.dataTransfer.setData('application/reactflow', 'SUB_DAG');
                                    e.dataTransfer.effectAllowed = 'move';
                                }}
                                className={`flex flex-col items-center justify-center gap-ds-2 px-ds-3 py-ds-4 rounded-xl border-[1.5px] border-[#5eead4] bg-[#f0fdfa] ${
                                    canEdit ? 'cursor-grab active:cursor-grabbing hover:border-[#0d9488] hover:bg-[#ccfbf1]' : 'cursor-not-allowed opacity-50'
                                } transition-colors`}
                                title={!canEdit ? '只读模式：您没有编辑权限' : '引用其他 DAG 作为节点'}
                            >
                                <span className="text-ds-heading">📦</span>
                                <span className="text-ds-small font-semibold text-[#0d9488]">子 DAG</span>
                            </div>
                        </div>
                        <div className="mt-ds-6 text-ds-caption text-ds-text-muted text-center">
                            拖拽节点到画布上添加
                        </div>
                    </div>
                )}

                {/* 中间：画布（点阵背景） */}
                <div
                    data-testid="dag-canvas"
                    className="flex-1 bg-ds-bg-root relative"
                    style={dotBackgroundStyle}
                    onDragOver={onDragOver}
                    onDrop={onDrop}
                >
                    <ReactFlow
                        nodes={rfNodes}
                        edges={rfEdges}
                        onNodesChange={onNodesChange}
                        onEdgesChange={onEdgesChange}
                        onConnect={onConnect}
                        onNodeClick={handleNodeClick}
                        onNodeDoubleClick={handleNodeDoubleClick}
                        onPaneClick={() => setSelectedNodeId(null)}
                        nodeTypes={nodeTypes}
                        fitView
                        fitViewOptions={{padding: 0.2, maxZoom: 0.9}}
                        minZoom={0.3}
                        // 必须禁用空格平移激活键：ReactFlow 默认 panActivationKeyCode='Space'，
                        // 其 useKeyPress 会在 document 级 keydown 对空格 preventDefault；
                        // Monaco 0.56 的 EditContext 输入宿主是 div.native-edit-context
                        // （非 INPUT/TEXTAREA/contenteditable），isInputDOMNode 识别不到，
                        // 导致 SQL 编辑器里空格被吃掉。画布平移不受影响（panOnDrag 默认开）。
                        panActivationKeyCode={null}
                    >
                        <Controls/>
                    </ReactFlow>
                </div>

                {/* 右侧：属性面板（260px） */}
                <PropertyPanel node={selectedNode} onEdit={handleEditFromPanel} readOnly={isRunView || !canEdit}
                               onViewLogs={handleViewNodeLogs} executionId={executionId}/>
            </div>

            {/* 节点配置抽屉 */}
            <Drawer
                title="节点配置"
                open={drawerOpen}
                onClose={() => setDrawerOpen(false)}
                extra={canEdit && selectedNodeId && selectedNode?.data.nodeType !== 'SYNC' && (
                    <DsButton variant="danger" onClick={() => {
                        handleDeleteNode(selectedNodeId);
                        setDrawerOpen(false);
                    }}>删除</DsButton>
                )}
            >
                {selectedNode && <NodeSummary node={selectedNode} watchedSyncJobId={watchedSyncJobId}/>}
                <Form form={form} layout="vertical">
                    <Form.Item label="节点名称" name="nodeName" rules={[{required: true}]}>
                        <Input/>
                    </Form.Item>
                    {/* Drawer 只服务 SYNC 节点(SQL 节点已走 SqlEditorModal) */}
                    <Form.Item label="同步任务" name="syncJobId" rules={[{required: true}]}>
                        <Select
                            showSearch
                            placeholder="搜索并选择同步任务"
                            optionFilterProp="label"
                            options={syncJobs.map((j: SyncJob) => ({label: j.name || String(j.id), value: j.id}))}
                        />
                    </Form.Item>
                    <DsButton onClick={handleSaveNode}
                              disabled={!canEdit}
                              className="w-full"
                              title={!canEdit ? '只读模式：您没有编辑权限' : undefined}>保存节点</DsButton>
                </Form>
            </Drawer>

            {/* SQL 任务编辑器 Modal(900x600 dark Monaco,替代 SQL 节点的 Drawer) */}
            <Suspense fallback={<Spin/>}>
                <SqlEditorModal
                    open={sqlModalOpen}
                    onClose={() => setSqlModalOpen(false)}
                    initialSql={rfNodes.find(n => n.id === selectedNodeId)?.data.sqlContent}
                    initialNodeName={rfNodes.find(n => n.id === selectedNodeId)?.data.nodeName}
                    title={`编辑 SQL 任务 — ${rfNodes.find(n => n.id === selectedNodeId)?.data.nodeName || ''}`}
                    dagParams={isNew ? draftParams : dagParams}
                    readOnly={!canEdit}
                    onSave={(sql, nodeName) => {
                        if (!selectedNodeId) return;
                        setRfNodes(ns => ns.map(n => n.id === selectedNodeId ? {
                            ...n,
                            data: {...n.data, nodeName, sqlContent: sql},
                        } : n));
                        setSqlModalOpen(false);
                        setIsDirty(true);
                        notify.success('SQL 节点已更新');
                    }}
                    onTested={handleSqlTested}
                />
            </Suspense>

            {/* Python 任务编辑器 Modal（Sprint 4，与 SQL 编辑器同一交互范式） */}
            <Suspense fallback={<Spin/>}>
                <PythonEditorModal
                    open={pythonModalOpen}
                    onClose={() => setPythonModalOpen(false)}
                    dagId={id}
                    nodeId={selectedNodeId || undefined}
                    hasUnsavedChanges={isDirty}
                    initialScript={selectedNode?.data.pythonScript}
                    initialNodeName={selectedNode?.data.nodeName}
                    initialTimeoutMinutes={selectedNode?.data.timeoutMinutes}
                    initialMemoryLimitMb={selectedNode?.data.memoryLimitMb}
                    title={`编辑 Python 任务 — ${selectedNode?.data.nodeName || ''}`}
                    readOnly={!canEdit}
                    onSave={(script, nodeName, timeoutMinutes, memoryLimitMb) => {
                        if (!selectedNodeId) return;
                        setRfNodes(ns => ns.map(n => n.id === selectedNodeId ? {
                            ...n,
                            data: {...n.data, nodeName, pythonScript: script, timeoutMinutes, memoryLimitMb},
                        } : n));
                        setPythonModalOpen(false);
                        setIsDirty(true);
                        notify.success('Python 节点已更新');
                    }}
                    onTested={handleSqlTested}
                />
            </Suspense>

            {/* 运行视图 SYNC 节点执行日志（复用同步任务日志弹窗） */}
            <HistoryLogModal
                open={nodeLogOpen}
                title={selectedNode?.data.nodeName}
                logs={nodeLogs}
                loading={nodeLogsLoading}
                onClose={() => setNodeLogOpen(false)}
            />

            {/* Sprint 4：DAG 参数抽屉（删除参数时做 ${param} 引用校验） */}
            <DagParameterDrawer
                open={paramDrawerOpen}
                dagId={id}
                draftParams={draftParams}
                onDraftParamsChange={setDraftParams}
                referenceTexts={rfNodes.map(n => ({
                    nodeName: n.data.nodeName,
                    text: n.data.nodeType === 'SQL' ? n.data.sqlContent : n.data.pythonScript,
                }))}
                readOnly={!canEdit}
                onClose={() => setParamDrawerOpen(false)}
            />

            {/* Sprint 4：手动触发参数覆盖弹窗 */}
            <TriggerParamsModal
                open={triggerModalOpen}
                params={dagParams}
                executing={triggering}
                onCancel={() => setTriggerModalOpen(false)}
                onExecute={overrides => doTrigger(overrides)}
            />

            {/* Sprint 4：DAG 版本管理（回滚成功后刷新画布） */}
            <DagVersionModal
                open={versionModalOpen}
                dagId={id}
                canEdit={canEdit}
                onClose={() => setVersionModalOpen(false)}
                onRolledBack={() => {
                    loadDag().then(() => setIsDirty(false)).catch(() => {
                    });
                }}
            />

            {/* Sprint 5：按 DAG 告警规则（统一 alert_rule 数据源；新建未保存 DAG 走本地草稿） */}
            <AlertRuleModal
                open={alertModalOpen}
                onClose={() => setAlertModalOpen(false)}
                mode="quick"
                quickObjectType="DAG"
                quickObjectId={isNew ? undefined : id}
                quickObjectName={dag.name}
                readOnly={!canEdit}
                draftRule={isNew ? draftAlertConfig : undefined}
                onDraftChange={isNew ? setDraftAlertConfig : undefined}
            />

            {/* Sprint 5：条件分支节点配置 */}
            <ConditionNodeModal
                open={conditionModalOpen}
                initialNodeName={selectedNode?.data.nodeName}
                initialBranches={selectedNode?.data.branches}
                availableNodes={rfNodes
                    .filter(n => n.id !== selectedNodeId)
                    // 排除会导致成环的下游节点（条件节点连向其祖先会形成环）
                    .filter(n => selectedNodeId ? !wouldCreateCycle(rfEdges, selectedNodeId, n.id) : true)
                    .map(n => ({id: n.id, nodeName: n.data.nodeName}))}
                readOnly={!canEdit}
                onClose={() => setConditionModalOpen(false)}
                onSave={handleConditionNodeSave}
            />

            {/* Sprint 5：子 DAG 节点配置 */}
            <SubDagNodeModal
                open={subDagModalOpen}
                initialNodeName={selectedNode?.data.nodeName}
                initialSubDagId={selectedNode?.data.subDagId}
                initialSyncExecution={selectedNode?.data.syncExecution}
                candidateDags={candidateDags}
                readOnly={!canEdit}
                onClose={() => setSubDagModalOpen(false)}
                onSave={handleSubDagNodeSave}
            />
        </div>
    );
}

/**
 * 运行视图（执行快照）画布构建：节点以 execution.nodeExecutions 为准，边以 execution.edgeSnapshot 为准
 * （无快照时回退当前定义）。已删除节点/连线的历史执行记录仍然可见；
 * 用 dagre 整体自动布局，避免已删除节点与现存节点贴在一起。
 */
function buildRunViewGraph(
    ex: DagExecution,
    dag: Dag,
): { nodes: Node<RFNodeData>[]; edges: Edge[] } {
    const snapshot = (ex.nodeExecutions || []).filter(
        (ne): ne is NodeExecution & { nodeId: string } => !!ne.nodeId,
    );
    const snapshotIds = new Set(snapshot.map(ne => ne.nodeId));

    // 边优先取执行实例创建时的边快照（edgeSnapshot）—— 后续删除节点/改连线不影响历史视图；
    // 只保留两端都在快照节点集合里的；快照缺失或解析失败时回退当前定义 dag.edges（老执行实例无快照数据）
    const snapshotEdges = parseEdgeSnapshot(ex.edgeSnapshot);
    const edges: Edge[] = snapshotEdges
        ? snapshotEdges
            .filter(e => snapshotIds.has(e.source) && snapshotIds.has(e.target))
            .map(e => ({
                id: `${e.source}-${e.target}`,
                source: e.source,
                target: e.target,
                animated: true,
            }))
        : (dag.edges || [])
            .filter(e => snapshotIds.has(e.sourceNodeId) && snapshotIds.has(e.targetNodeId))
            .map(e => ({
                id: e.edgeId,
                source: e.sourceNodeId,
                target: e.targetNodeId,
                animated: true,
            }));

    // 用当前 DAG 定义里的 config 补充已删除节点的同步任务名/SQL 等信息（历史视图尽量展示完整）
    const defConfigByNodeId = new Map((dag.nodes || []).map(n => [n.nodeId, parseConfig(n.config)]));

    const nodes: Node<RFNodeData>[] = snapshot.map(ne => {
        // nodeType 直通：SYNC/PYTHON/CONDITION/SUB_DAG 保持原类型（Sprint 4/5），未知类型回退 SQL 展示
        const nodeType: NodeType = ne.nodeType === 'SYNC' || ne.nodeType === 'PYTHON' || ne.nodeType === 'CONDITION' || ne.nodeType === 'SUB_DAG'
            ? ne.nodeType
            : 'SQL';
        const cfg = defConfigByNodeId.get(ne.nodeId);
        return {
            id: ne.nodeId,
            type: nodeType,
            position: {x: 0, y: 0},
            data: {
                nodeName: ne.nodeName || ne.nodeId,
                nodeType,
                status: (ne.status as NodeStatus) || undefined,
                durationMs: ne.durationMs,
                outputInfo: ne.outputInfo,
                errorMessage: ne.errorMessage,
                nodeExecutionStartTime: ne.startTime,
                nodeExecutionEndTime: ne.endTime,
                // 重跑实例中复用上轮结果的节点：开始时间早于实例开始时间 ⇒ 未重跑
                reused: ne.startTime != null && ex.startTime != null && ne.startTime < ex.startTime,
                // 节点执行记录 id：SYNC 节点「查看日志」入口依赖它
                nodeExecutionId: ne.id != null ? String(ne.id) : undefined,
                // 同步任务名优先取当前定义；已删除节点回退到 execution 里的 syncJobId
                syncJobId: nodeType === 'SYNC' ? (cfg?.syncJobId ?? ne.syncJobId) : undefined,
                syncJobName: nodeType === 'SYNC' ? cfg?.syncJobName : undefined,
                sqlContent: nodeType === 'SQL' ? cfg?.sqlContent : undefined,
                pythonScript: nodeType === 'PYTHON' ? cfg?.pythonScript : undefined,
                branches: nodeType === 'CONDITION' ? cfg?.branches : undefined,
                subDagId: nodeType === 'SUB_DAG' ? cfg?.subDagId : undefined,
                subDagName: nodeType === 'SUB_DAG' ? cfg?.subDagName : undefined,
                syncExecution: nodeType === 'SUB_DAG' ? cfg?.syncExecution : undefined,
            },
        };
    });

    // 用 dagre 自动布局，避免手动补坐标导致节点贴在一起；dagre 对相同图输入是确定性的
    const layoutedNodes = layoutWithDagre(nodes, edges, 'LR');
    return {nodes: layoutedNodes, edges};
}

/**
 * 解析执行实例的边快照 JSON（[{source,target},...]）。
 * 返回 null 表示无快照或非法，调用方回退用当前 DAG 定义的边；合法的 [] 原样返回（触发时无边）。
 */
function parseEdgeSnapshot(edgeSnapshot?: string): { source: string; target: string }[] | null {
    if (!edgeSnapshot) return null;
    try {
        const parsed: unknown = JSON.parse(edgeSnapshot);
        if (!Array.isArray(parsed)) return null;
        return parsed.filter(
            (e): e is { source: string; target: string } =>
                !!e && typeof e === 'object' &&
                typeof (e as { source?: unknown }).source === 'string' &&
                typeof (e as { target?: unknown }).target === 'string',
        );
    } catch {
        return null;
    }
}

function parseConfig(config?: string): {
    sqlContent?: string;
    syncJobId?: number;
    syncJobName?: string;
    pythonScript?: string;
    timeoutMinutes?: number;
    memoryLimitMb?: number;
    lastTestStatus?: 'PASSED' | 'FAILED';
    branches?: ConditionBranch[];
    subDagId?: number | string;
    subDagName?: string;
    syncExecution?: boolean;
} {
    if (!config) return {};
    try {
        return JSON.parse(config);
    } catch {
        return {};
    }
}

// 判断新增边 source→target 是否成环：target 沿现有边能走到 source 即成环（DFS）
function wouldCreateCycle(edges: Edge[], source: string, target: string): boolean {
    const adj = new Map<string, string[]>();
    edges.forEach(e => {
        const list = adj.get(e.source) || [];
        list.push(e.target);
        adj.set(e.source, list);
    });
    const stack = [target];
    const visited = new Set<string>();
    while (stack.length) {
        const cur = stack.pop()!;
        if (cur === source) return true;
        if (visited.has(cur)) continue;
        visited.add(cur);
        (adj.get(cur) || []).forEach(n => stack.push(n));
    }
    return false;
}

function serializeConfig(data: RFNodeData): string {
    if (data.nodeType === 'SQL') {
        return JSON.stringify({
            type: 'SQL',
            sqlContent: data.sqlContent || '',
            // SQL 节点最近运行测试结果（有值才写入，避免污染历史 config）
            ...(data.lastTestStatus ? {lastTestStatus: data.lastTestStatus} : {}),
        });
    }
    if (data.nodeType === 'PYTHON') {
        return JSON.stringify({
            type: 'PYTHON',
            pythonScript: data.pythonScript || '',
            // 未显式设置时不写入，由后端 PythonNodeConfig 默认值兜底（30 分钟 / 2048MB）
            ...(data.timeoutMinutes != null ? {timeoutMinutes: data.timeoutMinutes} : {}),
            ...(data.memoryLimitMb != null ? {memoryLimitMb: data.memoryLimitMb} : {}),
            ...(data.lastTestStatus ? {lastTestStatus: data.lastTestStatus} : {}),
        });
    }
    if (data.nodeType === 'CONDITION') {
        return JSON.stringify({
            type: 'CONDITION',
            branches: data.branches || [],
        });
    }
    if (data.nodeType === 'SUB_DAG') {
        return JSON.stringify({
            type: 'SUB_DAG',
            subDagId: data.subDagId,
            subDagName: data.subDagName,
            syncExecution: data.syncExecution ?? true,
        });
    }
    return JSON.stringify({type: 'SYNC', syncJobId: data.syncJobId, syncJobName: data.syncJobName});
}
