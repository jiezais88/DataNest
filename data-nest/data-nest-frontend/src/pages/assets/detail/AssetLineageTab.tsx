// Sprint 7 F1：资产详情页「血缘图谱」页签（DC-03 精简嵌入版）
// 复用 getLineageGraph 数据 API + dagre 布局 + ReactFlow 自绘，只读展示直接上下游；
// 不改造现有 LineageGraphPage（完整图谱通过底部链接跳转）。节点渲染参照该页 TableNode。
import {useEffect, useMemo, useState} from 'react';
import {useNavigate} from 'react-router-dom';
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
import {getLineageGraph} from '../../../api/lineage';
import DsButton from '../../../components/DsButton';
import EmptyState from '../../../components/EmptyState';
import QualityScoreBadge from '../../../components/QualityScoreBadge';
import type {LineageEdgeDTO, LineageGraphDTO, LineageNodeDTO} from '../../../types/lineage';
import {layoutWithDagre} from '../../../utils/dagLayout';

interface LineageNodeData {
    name: string;
    database?: string;
    type?: string;
    current?: boolean;
    qualityScore?: number | null;
    healthLevel?: string | null;
}

const TYPE_LABEL: Record<string, string> = {
    SQL: 'SQL 节点',
    SYNC: '同步任务',
    PYTHON: 'Python 节点',
};

const NODE_WIDTH = 240;
const NODE_HEIGHT = 120;

function AssetLineageNode({data}: NodeProps<LineageNodeData>) {
    return (
        <div
            className={[
                'relative px-ds-4 py-ds-3 rounded-ds-md border text-center shadow-sm bg-ds-bg-surface',
                data.current
                    ? 'border-ds-accent bg-ds-accent-light shadow-[0_0_0_3px_rgba(79,70,229,.12)]'
                    : 'border-ds-border-strong',
            ].join(' ')}
            style={{width: NODE_WIDTH}}
        >
            <Handle
                type="target"
                position={Position.Left}
                className="!w-[8px] !h-[8px] !rounded-full !bg-ds-text-muted !border-2 !border-ds-bg-surface"
                style={{left: -5}}
            />
            <Tooltip title={data.name} placement="top">
                <div className="text-ds-small font-semibold text-ds-text-primary truncate">{data.name}</div>
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

const nodeTypes = {table: AssetLineageNode};

interface AssetLineageTabProps {
    /** metadata_table.id（跳完整血缘时带上） */
    tableId: string;
    /** 「库名.表名」全名 */
    fullName: string;
}

function AssetLineageTabInner({tableId, fullName}: AssetLineageTabProps) {
    const navigate = useNavigate();
    const {fitView} = useReactFlow();
    const [graph, setGraph] = useState<LineageGraphDTO | null>(null);
    const [loading, setLoading] = useState(false);

    useEffect(() => {
        let cancelled = false;
        setLoading(true);
        getLineageGraph(fullName, 1)
            .then(result => {
                if (!cancelled) setGraph(result || {nodes: [], edges: []});
            })
            .catch(() => {
                if (!cancelled) setGraph({nodes: [], edges: []});
            })
            .finally(() => {
                if (!cancelled) setLoading(false);
            });
        return () => {
            cancelled = true;
        };
    }, [fullName]);

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
                qualityScore: n.qualityScore ?? null,
                healthLevel: n.healthLevel ?? null,
            },
        }));
        const edges: Edge[] = (graph.edges || []).map((e: LineageEdgeDTO) => ({
            id: `${e.source}→${e.target}`,
            source: e.source,
            target: e.target,
            style: {stroke: '#cbd5e1', strokeWidth: 1.8},
        }));
        const layouted = layoutWithDagre<LineageNodeData>(nodes, edges, 'LR', {
            nodeWidth: NODE_WIDTH,
            nodeHeight: NODE_HEIGHT,
        });
        return {nodes: layouted, edges};
    }, [graph]);

    // 受控 nodes/edges（ReactFlow 11 必须配 onNodesChange/onEdgesChange，否则边不渲染）
    const [rfNodes, setRfNodes, onNodesChange] = useNodesState<LineageNodeData>([]);
    const [rfEdges, setRfEdges, onEdgesChange] = useEdgesState([]);

    useEffect(() => {
        setRfNodes(computed.nodes);
        setRfEdges(computed.edges);
        // fitView 只在挂载时执行一次，nodes 变化后需等渲染再手动适配，否则图可能渲染到视口外
        if (computed.nodes.length > 0) {
            setTimeout(() => fitView({padding: 0.2, maxZoom: 0.9, duration: 300}), 50);
        }
    }, [computed, setRfNodes, setRfEdges, fitView]);

    // 后端 graph 恒返回中心表节点，有无血缘按「是否有边」判断
    const hasLineage = !!graph && (graph.edges?.length || 0) > 0;

    if (loading && !graph) {
        return (
            <div className="h-[480px] flex items-center justify-center">
                <Spin size="large"/>
            </div>
        );
    }

    if (!hasLineage) {
        return (
            <EmptyState
                title="暂无血缘数据"
                description="该表暂无已上报的血缘关系。执行 SQL / Python / 同步任务后会自动产生血缘。"
            />
        );
    }

    return (
        <div className="relative h-[480px] bg-ds-bg-surface border border-ds-border-subtle rounded-ds-md overflow-hidden">
            <ReactFlow
                nodes={rfNodes}
                edges={rfEdges}
                onNodesChange={onNodesChange}
                onEdgesChange={onEdgesChange}
                nodeTypes={nodeTypes}
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
                表级血缘 · 当前表居中（直接上下游）
            </div>
            <div className="absolute bottom-ds-3 right-ds-3">
                <DsButton
                    variant="secondary"
                    onClick={() => navigate(
                        `/governance/metadata/lineage?tableId=${tableId}&tableName=${encodeURIComponent(fullName)}&from=asset-catalog&tab=lineage`,
                    )}
                >
                    查看完整血缘图谱
                </DsButton>
            </div>
        </div>
    );
}

// 外层包 ReactFlowProvider：useReactFlow()（fitView）必须在 Provider 内使用
export default function AssetLineageTab(props: AssetLineageTabProps) {
    return (
        <ReactFlowProvider>
            <AssetLineageTabInner {...props}/>
        </ReactFlowProvider>
    );
}
