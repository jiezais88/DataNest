// Sprint 5：字段级血缘面板
// 字段下拉（来自元数据字段列表）→ GET /governance/lineage/columns → 渲染字段链路图
import {useCallback, useEffect, useMemo, useState} from 'react';
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
} from 'reactflow';
import 'reactflow/dist/style.css';
import {Select, Spin, Tooltip} from 'antd';
import {getLineageColumns} from '@/api/lineage';
import {listMetadataColumns} from '@/api/metadata';
import EmptyState from '@/components/EmptyState';
import type {MetadataColumn} from '@/types/metadata';
import type {LineageColumnLink} from '@/types/lineage';
import {layoutWithDagre} from '@/utils/dagLayout';

interface FieldLineageData {
    label: string;
    table: string;
    column: string;
    current?: boolean;
    upstream?: boolean;
    downstream?: boolean;
}

const NODE_WIDTH = 220;

function ColumnNode({data}: NodeProps<FieldLineageData>) {
    return (
        <div
            className={[
                'relative px-ds-4 py-ds-3 rounded-ds-md border bg-ds-bg-surface text-center shadow-sm',
                data.current
                    ? 'border-ds-accent bg-ds-accent-light shadow-[0_0_0_3px_rgba(79,70,229,.12)]'
                    : data.upstream
                        // 上游=来源（中性 slate），方向由布局与箭头表达，不用绿色（绿色只做状态语义）
                        ? 'border-ds-border-strong bg-ds-bg-hover'
                        : data.downstream
                            // 下游=受影响方（amber 影响警示语义）
                            ? 'border-ds-warning bg-ds-warning-light'
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
            <Tooltip title={data.label} placement="top">
                <div className="text-ds-small font-semibold text-ds-text-primary truncate" title={data.label}>
                    {data.label}
                </div>
            </Tooltip>
            <div className="text-ds-nano text-ds-text-muted mt-0.5">
                {data.current ? '当前字段' : data.upstream ? '来源字段' : data.downstream ? '下游字段' : '中间字段'}
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

const nodeTypes = {column: ColumnNode};

interface FieldLineagePanelProps {
    tableId?: string;
    tableName: string;
}

export default function FieldLineagePanel({tableId, tableName}: FieldLineagePanelProps) {
    const [columns, setColumns] = useState<MetadataColumn[]>([]);
    const [columnsLoading, setColumnsLoading] = useState(false);
    const [selectedColumn, setSelectedColumn] = useState<string>('');
    const [links, setLinks] = useState<LineageColumnLink[]>([]);
    const [loading, setLoading] = useState(false);

    useEffect(() => {
        if (!tableId) return;
        let cancelled = false;
        setColumnsLoading(true);
        listMetadataColumns(tableId)
            .then(res => {
                if (cancelled) return;
                const list = res.data || [];
                setColumns(list);
                if (list.length > 0) {
                    setSelectedColumn(list[0].columnName);
                }
            })
            .catch(() => {/* 拦截器已提示 */
            })
            .finally(() => {
                if (!cancelled) setColumnsLoading(false);
            });
        return () => {
            cancelled = true;
        };
    }, [tableId]);

    const loadFieldLineage = useCallback(async (columnName: string) => {
        if (!columnName) return;
        setLoading(true);
        try {
            const result = await getLineageColumns(tableName, columnName);
            setLinks(result || []);
        } catch {
            setLinks([]);
        } finally {
            setLoading(false);
        }
    }, [tableName]);

    useEffect(() => {
        if (selectedColumn) {
            loadFieldLineage(selectedColumn);
        }
    }, [selectedColumn, loadFieldLineage]);

    // 计算字段链路图：节点 = 唯一「表.字段」引用；边 = 链路
    // 中心字段高亮为「当前字段」；其直接来源标「来源字段」、直接下游标「下游字段」
    const computed = useMemo(() => {
        const center = `${tableName}.${selectedColumn}`;
        const nodeById = new Map<string, FieldLineageData>();
        const edgeList: Edge[] = [];

        const ensureNode = (id: string, table: string, column: string): FieldLineageData => {
            let node = nodeById.get(id);
            if (!node) {
                node = {label: id, table, column};
                nodeById.set(id, node);
            }
            return node;
        };

        for (const link of links) {
            const srcId = `${link.sourceTable}.${link.sourceColumn}`;
            const tgtId = `${link.targetTable}.${link.targetColumn}`;
            const src = ensureNode(srcId, link.sourceTable, link.sourceColumn);
            const tgt = ensureNode(tgtId, link.targetTable, link.targetColumn);
            if (srcId === center) {
                src.current = true;
                tgt.downstream = true;
            } else if (tgtId === center) {
                tgt.current = true;
                src.upstream = true;
            }
            edgeList.push({
                id: `${srcId}→${tgtId}`,
                source: srcId,
                target: tgtId,
                animated: true,
            });
        }

        const nodes: Node<FieldLineageData>[] = Array.from(nodeById.entries()).map(([id, data], index) => ({
            id,
            type: 'column',
            position: {x: index * (NODE_WIDTH + 80), y: 0},
            data,
        }));

        const layouted = layoutWithDagre<FieldLineageData>(nodes, edgeList, 'LR');
        return {nodes: layouted, edges: edgeList};
    }, [links, tableName, selectedColumn]);

    // 使用 ReactFlow 推荐的受控状态 hook，并同步计算结果
    const [rfNodes, setRfNodes, onNodesChange] = useNodesState<FieldLineageData>([]);
    const [rfEdges, setRfEdges, onEdgesChange] = useEdgesState([]);

    useEffect(() => {
        setRfNodes(computed.nodes);
        setRfEdges(computed.edges);
    }, [computed, setRfNodes, setRfEdges]);

    const hasLineage = links.length > 0;

    return (
        <div className="h-full flex flex-col">
            <div className="flex items-end gap-ds-3 mb-ds-4 flex-shrink-0">
                <div className="w-[320px]">
                    <label
                        className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">选择字段</label>
                    <Select
                        value={selectedColumn || undefined}
                        onChange={setSelectedColumn}
                        loading={columnsLoading}
                        options={columns.map(c => ({value: c.columnName, label: c.columnName}))}
                        placeholder="请选择字段"
                        className="w-full"
                        showSearch
                    />
                </div>
                {loading && <Spin size="small"/>}
            </div>

            {!hasLineage ? (
                <EmptyState
                    title="暂无字段级血缘"
                    description={selectedColumn
                        ? `字段「${selectedColumn}」暂无字段级血缘记录，可换个字段试试`
                        : '请选择字段查看字段级血缘'}
                />
            ) : (
                <>
                    {/* 必须包独立 ReactFlowProvider：本面板位于血缘图谱页的 Provider 之内，
                        不包会复用外层 store，关闭 drawer 卸载时把主图节点一起清掉（2026-08-07 修复） */}
                    <div data-testid="field-lineage-flow"
                         className="flex-1 min-h-0 border border-ds-border-subtle rounded-ds-md overflow-hidden">
                        <ReactFlowProvider>
                            <ReactFlow
                                nodes={rfNodes}
                                edges={rfEdges}
                                onNodesChange={onNodesChange}
                                onEdgesChange={onEdgesChange}
                                nodeTypes={nodeTypes}
                                fitView
                                fitViewOptions={{padding: 0.25, maxZoom: 1}}
                                minZoom={0.3}
                                nodesConnectable={false}
                                nodesDraggable
                                panActivationKeyCode={null}
                                proOptions={{hideAttribution: true}}
                            >
                                <Background gap={20} color="#e2e6ed"/>
                                <Controls showInteractive={false}/>
                            </ReactFlow>
                        </ReactFlowProvider>
                    </div>
                    <div className="mt-ds-3 flex-shrink-0 text-ds-nano text-ds-text-muted">
                        来源：{links.map(l => [l.dagName, l.nodeName].filter(Boolean).join(' / ')).filter((v, i, arr) => arr.indexOf(v) === i).join('、') || '—'}
                    </div>
                </>
            )}
        </div>
    );
}
