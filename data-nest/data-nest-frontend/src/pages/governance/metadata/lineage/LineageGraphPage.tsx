// Sprint 5：血缘图谱页（元数据表详情 → 「血缘图谱」按钮进入）
// 表级图谱 ReactFlow 渲染；支持影响分析/溯源分析高亮、展开层级、字段级血缘下钻。
// 路由：/governance/metadata/lineage?tableId=xxx&tableName=db.table
import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {useNavigate, useSearchParams} from 'react-router-dom';
import ReactFlow, {
    Background,
    Controls,
    type Edge,
    Handle,
    type Node,
    type NodeProps,
    Position,
    ReactFlowProvider,
    useEdgesState,
    useNodesState,
    useReactFlow,
} from 'reactflow';
import 'reactflow/dist/style.css';
import {Spin, Tooltip} from 'antd';
import DsButton from '../../../../components/DsButton';
import Drawer from '../../../../components/Drawer';
import EmptyState from '../../../../components/EmptyState';
import QualityScoreBadge from '../../../../components/QualityScoreBadge';
import {getLineageGraph, getLineageImpact, getLineageSource,} from '../../../../api/lineage';
import {getMetadataTable, searchMetadataTree} from '../../../../api/metadata';
import type {MetadataTreeNode} from '../../../../types/metadata';
import type {LineageEdgeDTO, LineageGraphDTO, LineageNodeDTO} from '../../../../types/lineage';
import {layoutWithDagre} from '../../../../utils/dagLayout';
import {notify} from '../../../../utils/notify';
import FieldLineagePanel from './FieldLineagePanel';

interface LineageNodeData {
    name: string;
    database?: string;
    type?: string;
    current?: boolean;
    highlighted?: boolean;
    /** Sprint 6 表级质量评分：健康度与评分（后端血缘接口回填；未配置规则为 null） */
    qualityScore?: number | null;
    healthLevel?: string | null;
}

/**
 * 在搜索树（datasource → database → [schema] → table 嵌套结构）中递归查找目标表。
 * 表节点 name 仅为短表名，需同时匹配库名避免跨库同名误跳。
 */
function findTableNode(nodes: MetadataTreeNode[], database: string, table: string): MetadataTreeNode | null {
    for (const n of nodes) {
        if (n.type === 'table' && n.name === table && (!database || n.databaseName === database)) return n;
        if (n.children?.length) {
            const found = findTableNode(n.children, database, table);
            if (found) return found;
        }
    }
    return null;
}

const TYPE_LABEL: Record<string, string> = {
    SQL: 'SQL 节点',
    SYNC: '同步任务',
    PYTHON: 'Python 节点',
};

const TABLE_NODE_WIDTH = 240;
const TABLE_NODE_HEIGHT = 120;

function TableNode({data}: NodeProps<LineageNodeData>) {
    return (
        <div
            className={[
                'relative px-ds-4 py-ds-3 rounded-ds-md border text-center shadow-sm bg-ds-bg-surface',
                data.current
                    ? 'border-ds-accent bg-ds-accent-light shadow-[0_0_0_3px_rgba(79,70,229,.12)]'
                    : data.highlighted
                        ? 'border-ds-success bg-ds-success-light'
                        : 'border-ds-border-strong',
            ].join(' ')}
            style={{width: TABLE_NODE_WIDTH}}
        >
            <Handle
                type="target"
                position={Position.Left}
                className="!w-[8px] !h-[8px] !rounded-full !bg-ds-text-muted !border-2 !border-ds-bg-surface"
                style={{left: -5}}
            />
            <Tooltip title={data.name} placement="top">
                <div className="text-ds-small font-semibold text-ds-text-primary truncate" title={data.name}>
                    {data.name}
                </div>
            </Tooltip>
            <div className="text-ds-nano text-ds-text-muted mt-0.5">
                {data.current ? '当前表' : (data.type ? TYPE_LABEL[data.type] || data.type : data.database || '')}
            </div>
            <div className="flex justify-center mt-ds-2">
                <QualityScoreBadge compact score={data.qualityScore} healthLevel={data.healthLevel}/>
            </div>
            <Handle
                type="source"
                position={Position.Right}
                className="!w-[8px] !h-[8px] !rounded-full !bg-ds-text-muted !border-2 !border-ds-bg-surface"
                style={{right: -5}}
            />
        </div>
    );
}

const nodeTypes = {table: TableNode};

type AnalysisMode = 'impact' | 'source' | null;

function LineageGraphPageInner() {
    const navigate = useNavigate();
    const {fitView} = useReactFlow();
    const [searchParams] = useSearchParams();
    const tableIdParam = searchParams.get('tableId') || '';
    const tableNameParam = searchParams.get('tableName') || '';
    /** 来源页：asset-catalog = 从资产详情页「查看完整血缘」进入，返回到资产详情而非元数据管理 */
    const fromParam = searchParams.get('from') || '';
    /** 返回后要落到的详情页 tab（资产详情从「血缘图谱」页签进入时带回 lineage，避免停回默认「基础信息」tab） */
    const backTabParam = searchParams.get('tab') || '';

    const [tableId, setTableId] = useState(tableIdParam);
    const [tableName, setTableName] = useState(tableNameParam);
    const [graph, setGraph] = useState<LineageGraphDTO | null>(null);
    const [depth, setDepth] = useState(1);
    const [loading, setLoading] = useState(false);
    const [analysisMode, setAnalysisMode] = useState<AnalysisMode>(null);
    const [highlightedNodes, setHighlightedNodes] = useState<Set<string>>(new Set());
    const [highlightedEdges, setHighlightedEdges] = useState<Set<string>>(new Set());
    const [fieldOpen, setFieldOpen] = useState(false);

    // 进入血缘时的来源表 ID：点击节点切换后 URL 不再带 tableId（仅 tableName），
    // 「← 返回」始终回到来源表的「血缘图谱」tab，不做血缘内逐级回退。
    const originTableIdRef = useRef(tableIdParam);

    // 从 URL 读取并兜底解析 tableName（仅 URL 有 tableId 时）
    useEffect(() => {
        setTableId(tableIdParam);
        if (tableNameParam) {
            setTableName(tableNameParam);
            return;
        }
        if (tableIdParam) {
            getMetadataTable(tableIdParam)
                .then(res => {
                    const t = res.data;
                    if (t?.databaseName && t?.tableName) {
                        setTableName(`${t.databaseName}.${t.tableName}`);
                    }
                })
                .catch(() => {/* 拦截器已提示 */
                });
        }
    }, [tableIdParam, tableNameParam]);

    const loadGraph = useCallback(async (name: string, d: number) => {
        setLoading(true);
        try {
            const result = await getLineageGraph(name, d);
            setGraph(result || {nodes: [], edges: []});
        } catch {
            setGraph({nodes: [], edges: []});
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        if (tableName) {
            loadGraph(tableName, depth);
            setAnalysisMode(null);
            setHighlightedNodes(new Set());
            setHighlightedEdges(new Set());
        } else {
            setGraph(null);
        }
    }, [tableName, depth, loadGraph]);

    // 计算 ReactFlow 节点/边（用于受控状态同步）
    const computed = useMemo(() => {
        if (!graph) return {nodes: [] as Node<LineageNodeData>[], edges: [] as Edge[]};
        const nodes: Node<LineageNodeData>[] = (graph.nodes || []).map((n: LineageNodeDTO, index) => ({
            id: n.id,
            type: 'table',
            position: {x: index * 260, y: 0},
            data: {
                name: n.name,
                database: n.database,
                type: n.type,
                current: !!n.current,
                highlighted: highlightedNodes.has(n.id),
                qualityScore: n.qualityScore ?? null,
                healthLevel: n.healthLevel ?? null,
            },
        }));
        const edges: Edge[] = (graph.edges || []).map((e: LineageEdgeDTO) => ({
            id: `${e.source}→${e.target}`,
            source: e.source,
            target: e.target,
            animated: highlightedEdges.has(`${e.source}→${e.target}`),
            style: highlightedEdges.has(`${e.source}→${e.target}`)
                ? {stroke: '#16a34a', strokeWidth: 2.5}
                : {stroke: '#cbd5e1', strokeWidth: 1.8},
        }));
        const layouted = layoutWithDagre<LineageNodeData>(nodes, edges, 'LR', {
            nodeWidth: TABLE_NODE_WIDTH,
            nodeHeight: TABLE_NODE_HEIGHT,
        });
        return {nodes: layouted, edges};
    }, [graph, highlightedNodes, highlightedEdges]);

    // 使用 ReactFlow 推荐的受控状态 hook，并同步计算结果
    const [rfNodes, setRfNodes, onNodesChange] = useNodesState<LineageNodeData>([]);
    const [rfEdges, setRfEdges, onEdgesChange] = useEdgesState([]);

    useEffect(() => {
        setRfNodes(computed.nodes);
        setRfEdges(computed.edges);
        // 切换中心表（节点数据变化）后主动适配视口：ReactFlow 的 fitView 只在挂载时执行一次，
        // 同一路由内 nodes 变化（如点击节点切到大图→小图）不会自动重新适配，导致新图渲染到视口外而「空白」。
        if (computed.nodes.length > 0) {
            // 等 ReactFlow 用新 nodes 渲染后再 fitView，否则新图位置可能仍在视口外
            setTimeout(() => fitView({padding: 0.2, maxZoom: 0.9, duration: 300}), 50);
        }
    }, [computed, setRfNodes, setRfEdges, fitView]);

    const resetHighlights = useCallback(() => {
        setHighlightedNodes(new Set());
        setHighlightedEdges(new Set());
    }, []);

    const handleToolbar = (mode: AnalysisMode) => {
        if (analysisMode === mode) {
            setAnalysisMode(null);
            resetHighlights();
            return;
        }
        resetHighlights();
        setAnalysisMode(mode);
    };

    // 分析模式下点击节点：请求该节点的下游/上游子图并高亮
    const handleAnalyzeNode = useCallback(async (nodeName: string) => {
        if (!analysisMode) return;
        setLoading(true);
        try {
            const result = analysisMode === 'impact'
                ? await getLineageImpact(nodeName, 1)
                : await getLineageSource(nodeName, 1);
            const nodes = new Set((result?.nodes || []).map(n => n.id));
            const edges = new Set((result?.edges || []).map(e => `${e.source}→${e.target}`));
            nodes.add(nodeName);
            setHighlightedNodes(nodes);
            setHighlightedEdges(edges);
        } catch {
            notify.error('分析失败');
        } finally {
            setLoading(false);
        }
    }, [analysisMode]);

    // 普通模式点击节点 → 切换到该表的血缘图谱；双击 → 尝试打开元数据详情
    const handleNodeClick = useCallback((_: unknown, node: Node<LineageNodeData>) => {
        if (analysisMode) {
            handleAnalyzeNode(node.data.name);
            return;
        }
        if (node.data.current) return;
        // 保留 from 参数：血缘页内切换中心表后，「← 返回」仍回到最初来源页
        const fromSuffix = fromParam ? `&from=${encodeURIComponent(fromParam)}` : '';
        navigate(`/governance/metadata/lineage?tableName=${encodeURIComponent(node.data.name)}${fromSuffix}`);
    }, [analysisMode, navigate, handleAnalyzeNode, fromParam]);

    const handleNodeDoubleClick = useCallback(async (_: unknown, node: Node<LineageNodeData>) => {
        if (node.data.current) return;
        // 血缘名是「库名.表名」，搜索用短表名（LIKE 匹配），库名用于精确命中
        const [db = '', ...rest] = node.data.name.split('.');
        const shortTable = rest.length > 0 ? rest.join('.') : db;
        try {
            const result = await searchMetadataTree(shortTable);
            const found = findTableNode(result.data || [], db, shortTable);
            if (found?.id) {
                navigate(`/governance/metadata?tableId=${found.id.replace('table-', '')}`);
                return;
            }
        } catch {
            // 忽略，回退到血缘图谱
        }
        navigate(`/governance/metadata/lineage?tableName=${encodeURIComponent(node.data.name)}`);
    }, [navigate]);

    const handleBack = () => {
        // 从资产详情页进入 → 返回资产详情；否则回来源表的元数据「血缘图谱」tab（不做血缘内逐级回退）
        const originId = originTableIdRef.current;
        if (fromParam === 'asset-catalog' && originId) {
            // 从资产详情「血缘图谱」页签进入：返回时落到该表详情的血缘图谱 tab（而非默认基础信息）
            const tabSuffix = backTabParam ? `?tab=${encodeURIComponent(backTabParam)}` : '';
            navigate(`/asset-catalog/${originId}${tabSuffix}`);
        } else if (originId) {
            navigate(`/governance/metadata?tableId=${originId}&tab=lineage`);
        } else {
            navigate('/governance/metadata');
        }
    };

    // 有无血缘以「是否有血缘边」判断：后端 graph 恒返回中心表节点，仅按 nodes 判断会导致空状态永不出现
    const hasLineage = !!graph && (graph.edges?.length || 0) > 0;

    return (
        <div className="flex flex-col h-full">
            <div className="flex items-center justify-between mb-ds-4 flex-shrink-0">
                <div className="flex items-center gap-ds-3">
                    <DsButton variant="secondary" onClick={handleBack}>← 返回</DsButton>
                    <div>
                        <h1 className="text-ds-display text-ds-text-primary flex items-center gap-ds-2">
                            血缘图谱
                        </h1>
                        <p className="text-ds-small text-ds-text-muted mt-ds-1">{tableName || '—'}</p>
                    </div>
                </div>
                <div className="flex items-center gap-ds-2">
                    <DsButton
                        variant={analysisMode === 'impact' ? 'primary' : 'secondary'}
                        onClick={() => handleToolbar(analysisMode === 'impact' ? null : 'impact')}
                    >
                        影响分析
                    </DsButton>
                    <DsButton
                        variant={analysisMode === 'source' ? 'primary' : 'secondary'}
                        onClick={() => handleToolbar(analysisMode === 'source' ? null : 'source')}
                    >
                        溯源分析
                    </DsButton>
                    <DsButton variant="secondary" onClick={() => {
                        setAnalysisMode(null);
                        resetHighlights();
                    }}>
                        重置视图
                    </DsButton>
                    <DsButton variant="secondary" onClick={() => setDepth(d => d + 1)} disabled={depth >= 10}>
                        展开一层（当前 {depth} 层）
                    </DsButton>
                    <DsButton variant="primary" onClick={() => setFieldOpen(true)}>
                        字段血缘
                    </DsButton>
                </div>
            </div>

            {loading && !graph ? (
                <div className="flex-1 flex items-center justify-center">
                    <Spin size="large"/>
                </div>
            ) : !hasLineage ? (
                <div className="flex-1 flex items-center justify-center">
                    <EmptyState
                        title="暂无血缘数据"
                        description={
                            '该表暂无已上报的血缘关系。可通过以下方式产生血缘：\n' +
                            '• 在 DAG 中执行 SQL 任务（CTAS / INSERT）\n' +
                            '• 在 DAG 中执行 Python 任务并写入 Doris\n' +
                            '• 执行批量数据同步任务'
                        }
                    />
                </div>
            ) : (
                <div
                    className="flex-1 min-h-0 bg-ds-bg-surface border border-ds-border-subtle rounded-ds-md overflow-hidden relative">
                    <ReactFlow
                        nodes={rfNodes}
                        edges={rfEdges}
                        onNodesChange={onNodesChange}
                        onEdgesChange={onEdgesChange}
                        nodeTypes={nodeTypes}
                        onNodeClick={handleNodeClick}
                        onNodeDoubleClick={handleNodeDoubleClick}
                        fitView
                        fitViewOptions={{padding: 0.2, maxZoom: 0.9}}
                        minZoom={0.3}
                        nodesConnectable={false}
                        panActivationKeyCode={null}
                        proOptions={{hideAttribution: true}}
                    >
                        <Background gap={20} color="#e2e6ed"/>
                        <Controls showInteractive={false}/>
                    </ReactFlow>
                    <div
                        className="absolute top-ds-3 left-ds-3 px-ds-3 py-ds-1.5 bg-white/90 backdrop-blur border border-ds-border-subtle rounded-ds-sm text-ds-nano text-ds-text-muted shadow-sm pointer-events-none">
                        {analysisMode === 'impact'
                            ? '💡 点击节点查看其下游影响链路'
                            : analysisMode === 'source'
                                ? '💡 点击节点查看其上游溯源链路'
                                : '💡 单击节点切换血缘图谱；双击节点打开元数据详情'}
                    </div>
                </div>
            )}

            <Drawer
                open={fieldOpen}
                title={
                    <span className="flex items-center gap-ds-2">
                        <span>字段级血缘</span>
                        <span className="text-ds-caption text-ds-text-muted font-normal truncate max-w-[180px]"
                              title={tableName}>
                            {tableName}
                        </span>
                    </span>
                }
                width="max-w-[860px]"
                onClose={() => setFieldOpen(false)}
            >
                {tableName ? (
                    <FieldLineagePanel tableId={tableId || undefined} tableName={tableName}/>
                ) : (
                    <div className="flex items-center justify-center h-full text-ds-small text-ds-text-muted">
                        未获取到表名，无法查看字段血缘
                    </div>
                )}
            </Drawer>
        </div>
    );
}

// 外层包 ReactFlowProvider：useReactFlow()（fitView 等）必须在 Provider 内才能使用。
export default function LineageGraphPage() {
    return (
        <ReactFlowProvider>
            <LineageGraphPageInner/>
        </ReactFlowProvider>
    );
}
