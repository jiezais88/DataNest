// DAG 自动布局（基于 dagre）
// Sprint 3 §6.10：顶部工具栏「自动布局」按钮调用，按拓扑层级重排节点
// 方向：LR（左→右），符合 PRD §6.4.3 中"画布支持缩放平移"习惯
import dagre from 'dagre';
import type {Edge, Node} from 'reactflow';

const DEFAULT_NODE_WIDTH = 200;
const DEFAULT_NODE_HEIGHT = 120;

export type LayoutDirection = 'LR' | 'TB' | 'RL' | 'BT';

export interface LayoutOptions {
    nodeWidth?: number;
    nodeHeight?: number;
    nodesep?: number;
    ranksep?: number;
}

/**
 * 用 dagre 重算每个节点的 position（中心点 → 左上角）
 * - nodes: 当前 ReactFlow 节点（只读，不改 type/data）
 * - edges: 当前 ReactFlow 边
 * - direction: 'LR' (默认，左→右) / 'TB' / 'RL' / 'BT'
 * - options: 可覆盖节点宽高与 dagre 间距
 */
export function layoutWithDagre<TNodeData = unknown>(
    nodes: Node<TNodeData>[],
    edges: Edge[],
    direction: LayoutDirection = 'LR',
    options: LayoutOptions = {},
): Node<TNodeData>[] {
    if (nodes.length === 0) return nodes;

    const nodeWidth = options.nodeWidth ?? DEFAULT_NODE_WIDTH;
    const nodeHeight = options.nodeHeight ?? DEFAULT_NODE_HEIGHT;
    const nodesep = options.nodesep ?? 100;
    const ranksep = options.ranksep ?? 140;

    const g = new dagre.graphlib.Graph();
    g.setDefaultEdgeLabel(() => ({}));
    g.setGraph({rankdir: direction, nodesep, ranksep});

    nodes.forEach(n => g.setNode(n.id, {width: nodeWidth, height: nodeHeight}));
    edges.forEach(e => g.setEdge(e.source, e.target));

    dagre.layout(g);

    return nodes.map(n => {
        const node = g.node(n.id);
        if (!node) return n;
        return {
            ...n,
            position: {
                x: node.x - nodeWidth / 2,
                y: node.y - nodeHeight / 2,
            },
        };
    });
}
