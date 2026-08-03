// Sprint 5：血缘图谱页（元数据表详情 → 「血缘图谱」按钮进入）
// 表级图谱 ReactFlow 渲染；支持影响分析/溯源分析高亮、展开层级、字段级血缘下钻。
// 路由：/governance/metadata/lineage?tableId=xxx&tableName=db.table
import {useCallback, useEffect, useMemo, useState} from 'react';
import {useNavigate, useSearchParams} from 'react-router-dom';
import ReactFlow, {
    Background,
    Controls,
    type Edge,
    Handle,
    type Node,
    type NodeProps,
    Position,
    useEdgesState,
    useNodesState,
} from 'reactflow';
import 'reactflow/dist/style.css';
import {Spin} from 'antd';
import DsButton from '../../../../components/DsButton';
import EmptyState from '../../../../components/EmptyState';
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
            style={{width: 180}}
        >
            <Handle
                type="target"
                position={Position.Left}
                className="!w-[8px] !h-[8px] !rounded-full !bg-ds-text-muted !border-2 !border-ds-bg-surface"
                style={{left: -5}}
            />
            <div className="text-ds-small font-semibold text-ds-text-primary truncate" title={data.name}>
                {data.name}
            </div>
            <div className="text-ds-nano text-ds-text-muted mt-0.5">
                {data.current ? '当前表' : (data.type ? TYPE_LABEL[data.type] || data.type : data.database || '')}
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

export default function LineageGraphPage() {
    const navigate = useNavigate();
    const [searchParams] = useSearchParams();
    const tableIdParam = searchParams.get('tableId') || '';
    const tableNameParam = searchParams.get('tableName') || '';

    const [tableId, setTableId] = useState(tableIdParam);
    const [tableName, setTableName] = useState(tableNameParam);
    const [graph, setGraph] = useState<LineageGraphDTO | null>(null);
    const [depth, setDepth] = useState(1);
    const [loading, setLoading] = useState(false);
    const [analysisMode, setAnalysisMode] = useState<AnalysisMode>(null);
    const [highlightedNodes, setHighlightedNodes] = useState<Set<string>>(new Set());
    const [highlightedEdges, setHighlightedEdges] = useState<Set<string>>(new Set());
    const [fieldOpen, setFieldOpen] = useState(false);

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
        const layouted = layoutWithDagre<LineageNodeData>(nodes, edges, 'LR');
        return {nodes: layouted, edges};
    }, [graph, highlightedNodes, highlightedEdges]);

    // 使用 ReactFlow 推荐的受控状态 hook，并同步计算结果
    const [rfNodes, setRfNodes, onNodesChange] = useNodesState<LineageNodeData>([]);
    const [rfEdges, setRfEdges, onEdgesChange] = useEdgesState([]);

    useEffect(() => {
        setRfNodes(computed.nodes);
        setRfEdges(computed.edges);
    }, [computed, setRfNodes, setRfEdges]);

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
        navigate(`/governance/metadata/lineage?tableName=${encodeURIComponent(node.data.name)}`);
    }, [analysisMode, navigate, handleAnalyzeNode]);

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
        if (tableId) {
            navigate(`/governance/metadata?tableId=${tableId}`);
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
                    <DsButton variant="primary" onClick={() => setFieldOpen(v => !v)}>
                        {fieldOpen ? '关闭字段血缘' : '字段血缘'}
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

            {fieldOpen && tableName && (
                <div
                    className="flex-shrink-0 mt-ds-4 border border-ds-border-subtle rounded-ds-md bg-ds-bg-surface p-ds-4">
                    <h3 className="text-ds-subhead text-ds-text-primary mb-ds-3">字段级血缘</h3>
                    <FieldLineagePanel tableId={tableId || undefined} tableName={tableName}/>
                </div>
            )}
        </div>
    );
}
