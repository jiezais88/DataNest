// DAG 编辑器（ReactFlow 画布 + 节点配置 + 右侧属性面板）
// Sprint 3: 节点状态边框/端口/状态图标 + design token + 三栏布局 + 同步任务摘要
import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
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
import {Form, Input, Modal, Popover, Select, Spin, Tag} from 'antd';
import {HiOutlinePencilSquare, HiOutlinePlayCircle} from 'react-icons/hi2';
import DsButton from '../../../components/DsButton';
import Drawer from '../../../components/Drawer';
import {createDag, getDag, getDagExecution, triggerDag, updateDag} from './api';
import {getSyncJob, querySyncJobs} from '../../../api/sync';
import {formatDuration} from '../../../utils/format';
import {notify} from '../../../utils/notify';
import {layoutWithDagre} from '../../../utils/dagLayout';
import CronPicker from '../../../components/CronPicker';
import SqlEditorModal from './components/SqlEditorModal';
import {describeCron} from '../../../utils/cron';
import type {Dag, DagExecution, NodeExecution, NodeType} from './types';
import type {SyncJob} from '../../../types/sync';
import {useCanEdit} from '../../../hooks/useCanEdit';
import {usePollingWhile} from '../../../hooks/usePollingWhile';
import {NODE_STATUS_COLOR, NODE_STATUS_LABEL} from '../../../constants/statusColors';

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
    status?: NodeStatus;
    /** 执行视图：节点运行信息 */
    durationMs?: number;
    outputInfo?: string;
    errorMessage?: string;
    nodeExecutionStartTime?: string;
    nodeExecutionEndTime?: string;
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

// ─────────── 自定义 DAG 节点组件（统一 SQL/SYNC；运行视图带状态色/耗时） ───────────
function DagNode({id, data, selected}: NodeProps<RFNodeData>) {
    const icon = data.nodeType === 'SQL' ? '📝' : '🔄';
    const outputTable = data.nodeType === 'SQL' ? extractOutputTable(data.sqlContent) : null;
    const statusColor = data.status ? NODE_STATUS_COLOR[data.status] : undefined;

    return (
        <div
            className={[
                'relative rounded-xl p-4 w-[220px] text-[13px] bg-ds-bg-surface font-sans shadow-sm',
                'border border-ds-border-subtle',
                selected ? 'ring-4 ring-ds-accent-glow' : '',
            ].filter(Boolean).join(' ')}
            style={statusColor ? {borderLeft: `4px solid ${statusColor}`} : undefined}
        >
            <Handle
                type="target"
                position={Position.Left}
                className="!w-[10px] !h-[10px] !rounded-full !bg-ds-text-muted !border-2 !border-ds-bg-surface hover:!bg-ds-accent"
                style={{left: -6}}
            />
            {/* 显式编辑入口：仅编辑模式且选中时出现 */}
            {selected && data.onEditRequest && (
                <button
                    className="nodrag absolute top-2 right-2 p-1 rounded-md text-ds-text-muted hover:text-ds-accent hover:bg-ds-accent-light transition-colors"
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
                <span className="text-[15px]">{icon}</span>
                <span className="font-semibold text-ds-text-primary truncate flex-1">{data.nodeName}</span>
            </div>
            <div className="text-ds-text-secondary text-[12px] leading-relaxed space-y-1">
                <div>类型：{data.nodeType === 'SQL' ? 'SQL 任务' : '同步任务'}</div>
                {data.nodeType === 'SQL' ? (
                    <div className="truncate" title={outputTable || '（未配置输出表）'}>
                        输出：{outputTable || '—'}
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

const nodeTypes = {SQL: DagNode, SYNC: DagNode};

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
            <div className="bg-ds-bg-root border border-ds-border-subtle rounded-ds-sm p-ds-3 mb-3">
                <div className="text-ds-caption text-ds-text-muted uppercase mb-1">SQL 摘要</div>
                <pre
                    className="text-[12px] text-ds-text-secondary font-mono whitespace-pre-wrap break-all m-0 max-h-32 overflow-auto">
                    {node.data.sqlContent || '（未配置 SQL）'}
                </pre>
            </div>
        );
    }

    return (
        <div className="bg-ds-bg-root border border-ds-border-subtle rounded-ds-sm p-ds-3 mb-3">
            <div className="text-ds-caption text-ds-text-muted uppercase mb-2">同步任务摘要</div>
            {loading ? (
                <Spin size="small"/>
            ) : syncDetail ? (
                <div className="space-y-1 text-ds-small">
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
        <div className="flex justify-between gap-2">
            <span className="text-ds-text-muted shrink-0">{label}</span>
            <span className={`text-ds-text-primary text-right truncate ${mono ? 'font-mono text-[12px]' : ''}`}>
                {value}
            </span>
        </div>
    );
}

function PropertyRow({label, value}: { label: string; value: React.ReactNode }) {
    return (
        <div className="flex justify-between gap-2 text-ds-small">
            <span className="text-ds-text-muted shrink-0">{label}</span>
            <span className="text-ds-text-primary text-right truncate ml-2">{value}</span>
        </div>
    );
}

// ─────────── 右侧属性面板（编辑模式：只读摘要 + 编辑按钮；运行模式：运行信息） ───────────
function PropertyPanel({
                           node,
                           onEdit,
                           readOnly,
                       }: {
    node: Node<RFNodeData> | null;
    onEdit: () => void;
    readOnly?: boolean;
}) {
    if (!node) {
        return (
            <div
                className="w-[260px] bg-ds-bg-surface border-l border-ds-border-subtle p-5 overflow-y-auto flex-shrink-0">
                <div
                    className="text-[16px] font-bold text-ds-text-primary mb-4">{readOnly ? '节点运行信息' : '节点属性'}</div>
                <div className="text-[13px] text-ds-text-muted leading-relaxed">
                    {readOnly ? '选中节点查看本次执行状态、耗时及输出。' : '选中画布上的节点，查看节点基础信息。双击节点可进入编辑弹窗。'}
                </div>
            </div>
        );
    }
    const outputTable = node.data.nodeType === 'SQL' ? extractOutputTable(node.data.sqlContent) : null;
    const statusColor = node.data.status ? NODE_STATUS_COLOR[node.data.status] : undefined;
    return (
        <div className="w-[260px] bg-ds-bg-surface border-l border-ds-border-subtle p-5 overflow-y-auto flex-shrink-0">
            <div
                className="text-[16px] font-bold text-ds-text-primary mb-4">{readOnly ? '节点运行信息' : '节点属性'}</div>
            <div className="space-y-3 mb-5 text-[13px]">
                <PropertyRow label="名称" value={node.data.nodeName}/>
                <PropertyRow label="类型" value={node.data.nodeType === 'SQL' ? 'SQL 任务' : '同步任务'}/>
                {node.data.nodeType === 'SQL' ? (
                    <PropertyRow label="输出表" value={outputTable || '—'}/>
                ) : (
                    <PropertyRow label="同步任务" value={node.data.syncJobName || node.data.syncJobId || '—'}/>
                )}
                {readOnly && node.data.status && (
                    <>
                        <PropertyRow
                            label="状态"
                            value={
                                <span style={{color: statusColor, fontWeight: 600}}>
                                    {NODE_STATUS_LABEL[node.data.status] || node.data.status}
                                </span>
                            }
                        />
                        <PropertyRow label="耗时" value={formatDuration(node.data.durationMs)}/>
                        <PropertyRow label="开始时间" value={node.data.nodeExecutionStartTime || '—'}/>
                        <PropertyRow label="结束时间" value={node.data.nodeExecutionEndTime || '—'}/>
                        {node.data.errorMessage && (
                            <div className="text-ds-danger text-[12px] break-all">{node.data.errorMessage}</div>
                        )}
                        {node.data.outputInfo && (
                            <div className="bg-ds-bg-root border border-ds-border-subtle rounded-ds-sm p-2 mt-2">
                                <div className="text-ds-caption text-ds-text-muted mb-1">输出</div>
                                <pre
                                    className="text-[11px] text-ds-text-secondary font-mono whitespace-pre-wrap break-all m-0 max-h-40 overflow-auto">
                                    {node.data.outputInfo}
                                </pre>
                            </div>
                        )}
                    </>
                )}
            </div>
            {!readOnly && (
                <DsButton onClick={onEdit} className="w-full">
                    <HiOutlinePencilSquare size={14}/> 编辑节点
                </DsButton>
            )}
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
    const canEdit = userCanEdit && !isRunView;
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
    const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
    const [drawerOpen, setDrawerOpen] = useState(false);
    // SQL 节点编辑 modal(Sprint 3 §6.5：900x600 dark Monaco modal,替代 Drawer)
    const [sqlModalOpen, setSqlModalOpen] = useState(false);
    const [syncJobs, setSyncJobs] = useState<SyncJob[]>([]);
    const [form] = Form.useForm();
    const watchedSyncJobId = Form.useWatch('syncJobId', form);
    const nodeIdRef = useRef(0);
    const edgeIdRef = useRef(0);

    // 当前选中节点（属性面板 + Drawer 摘要共用）
    const selectedNode = useMemo(
        () => rfNodes.find(n => n.id === selectedNodeId) || null,
        [rfNodes, selectedNodeId],
    );

    // 统一节点编辑入口（铅笔图标 / 双击 / 属性面板「编辑节点」共用）：
    // - SQL 节点：打开 900x600 dark Monaco modal
    // - SYNC 节点：打开节点配置 Drawer
    // data 直接来自节点 props，不查 rfNodes，避免闭包拿到过期节点数据
    const handleEditRequest = useCallback((nodeId: string, nodeData: RFNodeData) => {
        setSelectedNodeId(nodeId);
        if (nodeData.nodeType === 'SQL') {
            setSqlModalOpen(true);
            return;
        }
        form.setFieldsValue({
            nodeId,
            nodeName: nodeData.nodeName,
            sqlContent: nodeData.sqlContent || '',
            syncJobId: nodeData.syncJobId,
        });
        setDrawerOpen(true);
    }, [form]);

    // 拉取执行实例并把节点运行状态映射到画布（执行详情模式的初始加载与轮询共用）
    const refreshExecution = useCallback(() => {
        if (!id || !executionId) return Promise.resolve();
        // 不转 Number()：19 位 Snowflake id 保持 string 比较，防止精度丢失
        return getDagExecution(id, executionId).then(ex => {
            setExecution(ex);
            const runByNodeId = new Map<string, NodeExecution>();
            (ex.nodeExecutions || []).forEach(ne => {
                if (ne.nodeId) runByNodeId.set(ne.nodeId, ne);
            });
            setRfNodes(prev => prev.map(n => {
                const run = runByNodeId.get(n.id);
                if (!run) return n;
                return {
                    ...n,
                    data: {
                        ...n.data,
                        status: (run.status as NodeStatus) || undefined,
                        durationMs: run.durationMs,
                        outputInfo: run.outputInfo,
                        errorMessage: run.errorMessage,
                        nodeExecutionStartTime: run.startTime,
                        nodeExecutionEndTime: run.endTime,
                    },
                };
            }));
        });
    }, [id, executionId]);

    // 加载已有 DAG（编辑模式）或 DAG + execution（执行详情模式）
    useEffect(() => {
        if (isNew || !id) return;
        // 不转 Number()：19 位 Snowflake id 会被截断精度，reactflow 拿不到 nodes
        getDag(id).then(d => {
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
                        onEditRequest: canEdit ? handleEditRequest : undefined,
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

            if (isRunView && executionId) {
                return refreshExecution();
            }
        }).then(() => {
            // 加载完成的初始状态不算 dirty（避免首次进入就被拦截）
            setIsDirty(false);
        }).catch(e => notify.error('加载 DAG 失败: ' + (e?.message || '')));
    }, [id, executionId, isRunView, handleEditRequest, refreshExecution]);

    // 执行详情模式：RUNNING 时轮询刷新节点状态（与列表页统一的 usePollingWhile，5s 间隔）
    usePollingWhile(isRunView && execution?.status === 'RUNNING', refreshExecution);

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
    const onEdgesChange = useCallback((changes: EdgeChange[]) => {
        if (canEdit) {
            const real = changes.some(c => c.type !== 'select' && c.type !== 'reset');
            if (real) setIsDirty(true);
        }
        onEdgesChangeRaw(changes);
    }, [onEdgesChangeRaw, canEdit]);

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
        setIsDirty(true);
    }, [setRfEdges, rfEdges]);

    /**
     * 在指定画布坐标添加一个节点（拖拽 / 自动布局复用）
     * @param type SQL | SYNC
     * @param position 画布坐标（来自 screenToFlowPosition）
     */
    const addNodeAt = (type: NodeType, position: { x: number; y: number }) => {
        const newId = `n${++nodeIdRef.current}_${Date.now()}`;
        const newNode: Node<RFNodeData> = {
            id: newId,
            type,
            position,
            data: {
                nodeName: type === 'SQL' ? 'SQL 任务' : '同步任务',
                nodeType: type,
                sqlContent: undefined,
                // SYNC 节点不默认选中任务：静默绑定错任务的风险太大，让用户显式选择（Drawer 有 required 校验）
                syncJobId: undefined,
                syncJobName: undefined,
                status: 'IDLE',
                onEditRequest: handleEditRequest,
            },
        };
        setRfNodes(ns => [...ns, newNode]);
        setIsDirty(true);
    };

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
        if (type !== 'SQL' && type !== 'SYNC') return;
        const position = reactFlowInstance.screenToFlowPosition({
            x: event.clientX,
            y: event.clientY,
        });
        addNodeAt(type, position);
    }, [reactFlowInstance, syncJobs, canEdit]);

    const onDragOver = useCallback((event: React.DragEvent) => {
        event.preventDefault();
        event.dataTransfer.dropEffect = 'move';
    }, []);

    // 单击节点 → 仅选中（不打开 Drawer）
    const handleNodeClick = useCallback((_e: React.MouseEvent, node: Node<RFNodeData>) => {
        setSelectedNodeId(node.id);
    }, []);

    // 双击节点 → 与铅笔图标走同一入口（只读模式禁用）
    const handleNodeDoubleClick = (_: React.MouseEvent, node: Node<RFNodeData>) => {
        if (!canEdit) return;
        handleEditRequest(node.id, node.data);
    };

    // 属性面板的"编辑节点"按钮：同一入口
    const handleEditFromPanel = useCallback(() => {
        if (!selectedNode) return;
        handleEditRequest(selectedNode.id, selectedNode.data);
    }, [selectedNode, handleEditRequest]);

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

    const handleDeleteNode = (nodeId: string) => {
        setRfNodes(ns => ns.filter(n => n.id !== nodeId));
        setRfEdges(es => es.filter(e => e.source !== nodeId && e.target !== nodeId));
        if (selectedNodeId === nodeId) setSelectedNodeId(null);
        setIsDirty(true);
    };

    // 实际保存（通过校验后调用）
    const doSave = useCallback(async () => {
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
                notify.success('DAG 已创建');
            } else {
                // 不转 Number()：保持 string id 避免精度丢失
                await updateDag(id, payload);
                notify.success('DAG 已更新');
            }
            // 保存成功：清 dirty 标志（navigate 之前，避免 onBack 再次拦截）
            setIsDirty(false);
            navigate(`/engineering/dags/${savedId}/edit`);
        } catch {
            // 错误提示由 request 拦截器统一弹出
        }
    }, [dag, rfNodes, rfEdges, id, isNew, navigate]);

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
        const backTarget = isRunView
            ? (fromPath || `/engineering/dag-executions?dagId=${id}&dagName=${encodeURIComponent(dag.name || '')}`)
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
    }, [isDirty, navigate, handleSave, dag.projectId, fromPath]);

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
    }, [selectedNodeId, handleSave, canEdit]);

    const handleTrigger = async () => {
        if (!id) {
            notify.warning('请先保存');
            return;
        }
        try {
            // 不转 Number()：保持 string id 避免精度丢失
            await triggerDag(id);
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
        }
    };

    const executionStatusColor = execution?.status
        ? NODE_STATUS_COLOR[execution.status] || NODE_STATUS_COLOR.WAITING
        : undefined;

    return (
        <div className="h-screen flex flex-col bg-ds-bg-root">
            {/* 顶部工具栏 */}
            <div
                className="h-[56px] bg-ds-bg-surface border-b border-ds-border-subtle flex items-center px-4 gap-4 flex-shrink-0">
                <button
                    onClick={handleBack}
                    className="text-[13px] text-ds-text-secondary hover:text-ds-text-primary flex items-center gap-1 px-3 py-1.5 rounded-lg hover:bg-ds-bg-hover transition-colors"
                >
                    <span>←</span> {isRunView ? '返回执行历史' : '返回项目 DAG 列表'}
                </button>
                {isRunView ? (
                    <>
                        <div
                            className="h-[34px] px-3 flex items-center text-[14px] font-semibold text-ds-text-primary bg-ds-bg-root border border-ds-border-subtle rounded-lg w-[220px]">
                            {dag.name || '—'}
                        </div>
                        {execution && (
                            <div className="flex items-center gap-4 text-[13px]">
                                <span className="flex items-center gap-1">
                                    状态：
                                    <span style={{color: executionStatusColor, fontWeight: 600}}>
                                        {NODE_STATUS_LABEL[execution.status] || execution.status}
                                    </span>
                                </span>
                                <span>触发：{execution.triggerType === 'MANUAL' ? '手动' : '定时'}</span>
                                <span>耗时：{formatDuration(execution.durationMs)}</span>
                            </div>
                        )}
                    </>
                ) : (
                    <>
                        <input
                            className="h-[34px] px-3 text-[14px] font-semibold text-ds-text-primary bg-ds-bg-root border border-ds-border-subtle rounded-lg outline-none focus:border-ds-accent w-[220px]"
                            placeholder="DAG 名称"
                            value={dag.name}
                            onChange={e => {
                                setDag({...dag, name: e.target.value});
                                setIsDirty(true);
                            }}
                        />
                        <div className="flex items-center gap-2">
                            <span className="text-[13px] text-ds-text-secondary">触发方式</span>
                            <Select
                                value={dag.triggerType}
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
                            <div className="flex items-center gap-2">
                                <span className="text-[13px] text-ds-text-secondary">Cron</span>
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
                                        className="h-[34px] w-[200px] px-3 flex items-center justify-between gap-2 bg-white border border-ds-border-subtle rounded-lg text-[13px] text-ds-text-primary hover:border-ds-accent disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
                                    >
                                        <span className="truncate">
                                            {describeCron(dag.cronExpression) || '选择调度周期'}
                                        </span>
                                        <span className="text-ds-text-muted text-[10px] flex-shrink-0">▼</span>
                                    </button>
                                </Popover>
                            </div>
                        )}
                    </>
                )}
                <div className="flex-1"/>
                {!isRunView && (
                    <>
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
                        className="w-[200px] bg-ds-bg-surface border-r border-ds-border-subtle p-4 flex-shrink-0 overflow-y-auto">
                        <div
                            className="text-[12px] font-bold text-ds-text-muted uppercase tracking-wider mb-3">节点面板
                        </div>
                        <div className="space-y-3">
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
                                className={`flex flex-col items-center justify-center gap-2 px-3 py-4 rounded-xl border-[1.5px] border-ds-border-subtle bg-ds-bg-surface ${
                                    canEdit ? 'cursor-grab active:cursor-grabbing hover:border-ds-accent hover:bg-ds-accent-light' : 'cursor-not-allowed opacity-50'
                                } transition-colors`}
                                title={!canEdit ? '只读模式：您没有编辑权限' : undefined}
                            >
                                <span className="text-[20px]">📝</span>
                                <span className="text-[13px] font-semibold text-ds-text-secondary">SQL 任务</span>
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
                                className={`flex flex-col items-center justify-center gap-2 px-3 py-4 rounded-xl border-[1.5px] border-ds-border-subtle bg-ds-bg-surface ${
                                    canEdit ? 'cursor-grab active:cursor-grabbing hover:border-ds-accent hover:bg-ds-accent-light' : 'cursor-not-allowed opacity-50'
                                } transition-colors`}
                                title={!canEdit ? '只读模式：您没有编辑权限' : undefined}
                            >
                                <span className="text-[20px]">🔄</span>
                                <span className="text-[13px] font-semibold text-ds-text-secondary">同步任务</span>
                            </div>
                        </div>
                        <div className="mt-6 text-[12px] text-ds-text-muted text-center">
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
                <PropertyPanel node={selectedNode} onEdit={handleEditFromPanel} readOnly={isRunView}/>
            </div>

            {/* 节点配置抽屉 */}
            <Drawer
                title="节点配置"
                open={drawerOpen}
                onClose={() => setDrawerOpen(false)}
                extra={canEdit && selectedNodeId && (
                    <DsButton variant="danger" onClick={() => {
                        handleDeleteNode(selectedNodeId);
                        setDrawerOpen(false);
                    }}>删除</DsButton>
                )}
            >
                {selectedNode && <NodeSummary node={selectedNode} watchedSyncJobId={watchedSyncJobId}/>}
                <Form form={form} layout="vertical">
                    <Form.Item label="节点 ID" name="nodeId"><Input disabled/></Form.Item>
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
            <SqlEditorModal
                open={sqlModalOpen}
                onClose={() => setSqlModalOpen(false)}
                initialSql={rfNodes.find(n => n.id === selectedNodeId)?.data.sqlContent}
                initialNodeName={rfNodes.find(n => n.id === selectedNodeId)?.data.nodeName}
                title={`编辑 SQL 任务 — ${rfNodes.find(n => n.id === selectedNodeId)?.data.nodeName || ''}`}
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
            />
        </div>
    );
}

function parseConfig(config?: string): { sqlContent?: string; syncJobId?: number; syncJobName?: string } {
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
        return JSON.stringify({type: 'SQL', sqlContent: data.sqlContent || ''});
    }
    return JSON.stringify({type: 'SYNC', syncJobId: data.syncJobId, syncJobName: data.syncJobName});
}
