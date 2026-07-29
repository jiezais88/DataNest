import {useEffect, useRef, useState} from 'react';
import {ChevronRight, Database, Folder, Table} from 'lucide-react';
import {
    listMetadataDatabases,
    listMetadataDatasourceIds,
    listMetadataSchemas,
    listMetadataTables,
    listMetadataTablesWithoutSchema,
} from '../../../api/metadata';
import type {MetadataDatasource, MetadataTable, MetadataTreeNode} from '../../../types/metadata';

interface MetadataTreeProps {
    selectedNode: MetadataTreeNode | null;
    onSelect: (node: MetadataTreeNode) => void;
    expanded: Set<string>;
    onExpandedChange: (next: Set<string>) => void;
    onRootsLoaded?: (hasRoots: boolean) => void;
    autoSelectFirst?: boolean;
}

const DB_TYPES_WITHOUT_SCHEMA = new Set(['MYSQL', 'DORIS']);

export default function MetadataTree({
                                         selectedNode,
                                         onSelect,
                                         expanded,
                                         onExpandedChange,
                                         onRootsLoaded,
                                         autoSelectFirst,
                                     }: MetadataTreeProps) {
    const [roots, setRoots] = useState<MetadataTreeNode[]>([]);
    const [loading, setLoading] = useState<string | null>(null);
    const autoSelectRef = useRef<{ active: boolean; pendingId: string | null }>({active: false, pendingId: null});
    const loadedRef = useRef<Set<string>>(new Set());

    useEffect(() => {
        loadRoots();
    }, []);

    // 当外部通过 expanded 展开尚未加载子节点的节点时，自动加载其子节点
    useEffect(() => {
        const collectNodes = (nodes: MetadataTreeNode[]): MetadataTreeNode[] => {
            return nodes.reduce<MetadataTreeNode[]>((acc, n) => {
                acc.push(n);
                if (n.children) acc.push(...collectNodes(n.children));
                return acc;
            }, []);
        };
        const allNodes = collectNodes(roots);
        for (const node of allNodes) {
            if (expanded.has(node.id) && (!node.children || node.children.length === 0) && !loadedRef.current.has(node.id) && loading !== node.id) {
                loadedRef.current.add(node.id);
                loadChildren(node);
            }
        }
    }, [expanded, roots, loading]);

    const isExpanded = (id: string) => expanded.has(id);
    const setExpanded = (id: string, value: boolean) => {
        const next = new Set(expanded);
        if (value) next.add(id);
        else next.delete(id);
        onExpandedChange(next);
    };

    const updateNodeChildren = (nodes: MetadataTreeNode[], nodeId: string, children: MetadataTreeNode[]): MetadataTreeNode[] => {
        return nodes.map(n => {
            if (n.id === nodeId) {
                return {...n, children};
            }
            if (n.children) {
                return {...n, children: updateNodeChildren(n.children, nodeId, children)};
            }
            return n;
        });
    };

    const updateNodeCount = (nodes: MetadataTreeNode[], nodeId: string, count: number): MetadataTreeNode[] => {
        return nodes.map(n => {
            if (n.id === nodeId) {
                return {...n, count};
            }
            if (n.children) {
                return {...n, children: updateNodeCount(n.children, nodeId, count)};
            }
            return n;
        });
    };

    const loadRoots = async () => {
        try {
            const result = await listMetadataDatasourceIds();
            if (result.code === 200) {
                const nodes: MetadataTreeNode[] = (result.data as MetadataDatasource[]).map((ds) => {
                    const shortId = String(ds.id).slice(-6);
                    const exists = ds.exists !== false;
                    const typeSuffix = ds.type ? ` (${ds.type})` : '';
                    const isBuiltin = ds.sourceType === 'BUILTIN_DORIS';
                    return {
                        id: `ds-${ds.id}`,
                        type: 'datasource',
                        name: isBuiltin ? 'Doris（内置）' : (ds.name ? `${ds.name}${typeSuffix}` : `数据源 ${shortId}${exists ? '' : '（已删除）'}`),
                        exists,
                        datasourceId: ds.id,
                        datasourceType: ds.type,
                        sourceType: ds.sourceType,
                        databaseName: '',
                        schemaName: '',
                        children: [],
                    };
                });
                nodes.sort((a, b) => {
                    const aBuiltin = a.sourceType === 'BUILTIN_DORIS' ? 1 : 0;
                    const bBuiltin = b.sourceType === 'BUILTIN_DORIS' ? 1 : 0;
                    if (aBuiltin !== bBuiltin) return bBuiltin - aBuiltin;
                    return a.name.localeCompare(b.name, 'zh-CN');
                });
                setRoots(nodes);
                onRootsLoaded?.(nodes.length > 0);
                if (autoSelectFirst && nodes.length > 0 && !selectedNode && !autoSelectRef.current.active) {
                    const root = nodes[0];
                    autoSelectRef.current = {active: true, pendingId: root.id};
                    const nextExpanded = new Set([root.id]);
                    onExpandedChange(nextExpanded);
                    onSelect(root);
                    loadChildren(root);
                }
            } else {
                onRootsLoaded?.(false);
            }
        } catch (err) {
            console.error('loadRoots failed', err);
            setRoots([]);
            onRootsLoaded?.(false);
        }
    };

    const loadChildren = async (node: MetadataTreeNode) => {
        if (node.type === 'datasource') {
            const datasourceId = node.datasourceId!;
            setLoading(node.id);
            try {
                const result = await listMetadataDatabases(datasourceId);
                if (result.code === 200) {
                    const children = result.data.map((db) => ({
                        id: `db-${datasourceId}-${db}`,
                        type: 'database' as const,
                        name: db,
                        databaseName: db,
                        schemaName: '',
                        datasourceId,
                        datasourceType: node.datasourceType,
                        children: [],
                    }));
                    setRoots(prev => updateNodeChildren(prev, node.id, children));
                    handleAutoSelectChildren(node, children);
                }
            } finally {
                setLoading(null);
            }
        } else if (node.type === 'database') {
            const datasourceId = node.datasourceId!;
            const databaseName = node.databaseName!;
            const datasourceType = node.datasourceType;
            // MySQL / Doris 的 database 与 schema 等价，直接加载表作为叶子
            if (datasourceType && DB_TYPES_WITHOUT_SCHEMA.has(datasourceType.toUpperCase())) {
                setLoading(node.id);
                try {
                    const result = await listMetadataTablesWithoutSchema(datasourceId, databaseName);
                    if (result.code === 200) {
                        const children = (result.data as MetadataTable[]).map((table) => ({
                            id: `table-${table.id}`,
                            type: 'table' as const,
                            name: table.tableName,
                            databaseName,
                            schemaName: databaseName,
                            datasourceId,
                            datasourceType,
                            count: table.columnCount ?? 0,
                        }));
                        setRoots(prev => updateNodeChildren(prev, node.id, children));
                        setRoots(prev => updateNodeCount(prev, node.id, children.length));
                    }
                } finally {
                    setLoading(null);
                }
                return;
            }
            // PostgreSQL 等需要展开 schema
            setLoading(node.id);
            try {
                const result = await listMetadataSchemas(datasourceId, databaseName);
                if (result.code === 200) {
                    const children = result.data.map((schema) => ({
                        id: `schema-${datasourceId}-${databaseName}-${schema}`,
                        type: 'schema' as const,
                        name: schema,
                        databaseName,
                        schemaName: schema,
                        datasourceId,
                        datasourceType,
                        children: [],
                    }));
                    setRoots(prev => updateNodeChildren(prev, node.id, children));
                    setRoots(prev => updateNodeCount(prev, node.id, children.length));
                }
            } finally {
                setLoading(null);
            }
        } else if (node.type === 'schema') {
            const datasourceId = node.datasourceId!;
            const databaseName = node.databaseName!;
            const schemaName = node.schemaName!;
            const datasourceType = node.datasourceType;
            setLoading(node.id);
            try {
                const result = await listMetadataTables(datasourceId, databaseName, schemaName);
                if (result.code === 200) {
                    const children = (result.data as MetadataTable[]).map((table) => ({
                        id: `table-${table.id}`,
                        type: 'table' as const,
                        name: table.tableName,
                        databaseName,
                        schemaName,
                        datasourceId,
                        datasourceType,
                        count: table.columnCount ?? 0,
                    }));
                    setRoots(prev => updateNodeChildren(prev, node.id, children));
                    setRoots(prev => updateNodeCount(prev, node.id, children.length));
                }
            } finally {
                setLoading(null);
            }
        }
    };

    const handleAutoSelectChildren = (node: MetadataTreeNode, children: MetadataTreeNode[]) => {
        if (!autoSelectRef.current.active || autoSelectRef.current.pendingId !== node.id || children.length === 0) {
            return;
        }
        const firstChild = children[0];
        if (firstChild.type === 'database') {
            autoSelectRef.current = {active: false, pendingId: null};
            onSelect(firstChild);
            const nextExpanded = new Set(expanded);
            nextExpanded.add(firstChild.id);
            onExpandedChange(nextExpanded);
            loadChildren(firstChild);
        }
    };

    const handleToggle = (node: MetadataTreeNode) => {
        if (!node.children || node.children.length === 0) {
            loadChildren(node);
        }
        setExpanded(node.id, !isExpanded(node.id));
    };

    const isSelected = (node: MetadataTreeNode) => selectedNode?.id === node.id;

    const renderIcon = (node: MetadataTreeNode) => {
        if (node.type === 'datasource') {
            return <Database size={16} className="text-ds-accent flex-shrink-0"/>;
        }
        if (node.type === 'database' || node.type === 'schema') {
            return <Folder size={16} className="text-ds-warning flex-shrink-0"/>;
        }
        return <Table size={16} className="text-ds-text-muted flex-shrink-0"/>;
    };

    const renderNode = (node: MetadataTreeNode, depth = 0) => {
        const hasChildren = node.type !== 'table';
        const expandedFlag = isExpanded(node.id);
        const selectedFlag = isSelected(node);
        const paddingLeft = 12 + depth * 16;
        const deletedFlag = node.type === 'datasource' && node.exists === false;

        return (
            <div key={node.id}>
                <button
                    data-testid={node.id}
                    data-node-type={node.type}
                    data-node-name={node.name}
                    onClick={() => {
                        if (hasChildren) handleToggle(node);
                        onSelect(node);
                    }}
                    style={{paddingLeft}}
                    className={`w-full flex items-center gap-ds-2 py-ds-2 pr-ds-2 text-left text-ds-small transition-colors ${
                        selectedFlag
                            ? 'bg-ds-accent-light text-ds-accent font-semibold'
                            : deletedFlag
                                ? 'text-ds-text-muted hover:bg-ds-bg-hover'
                                : 'text-ds-text-secondary hover:bg-ds-bg-hover hover:text-ds-text-primary'
                    }`}
                >
                    {hasChildren && (
                        <span
                            className={`w-4 flex-shrink-0 text-ds-text-muted transition-transform ${expandedFlag ? 'rotate-90' : ''}`}>
                            <ChevronRight size={14}/>
                        </span>
                    )}
                    {!hasChildren && <span className="w-4 flex-shrink-0"/>}
                    {renderIcon(node)}
                    <span className="truncate">{node.name}</span>
                    {node.count !== undefined && node.count > 0 && (
                        <span
                            className="ml-auto px-ds-1.5 py-ds-0.5 text-ds-caption bg-ds-bg-hover text-ds-text-muted rounded-ds-xs">
                            {node.type === 'table' ? `${node.count}列` : `${node.count}表`}
                        </span>
                    )}
                    {deletedFlag && (
                        <span
                            className="ml-ds-1 px-ds-1.5 py-ds-0.5 text-ds-caption bg-ds-text-muted/10 text-ds-text-muted rounded-ds-xs">已删除</span>
                    )}
                    {loading === node.id && (
                        <span
                            className="ml-auto w-3 h-3 border-2 border-ds-accent border-t-transparent rounded-full animate-spin"/>
                    )}
                </button>
                {expandedFlag && node.children && (
                    <div>{node.children.map((child) => renderNode(child, depth + 1))}</div>
                )}
            </div>
        );
    };

    return (
        <div className="w-full h-full overflow-auto py-ds-2">
            {roots.length === 0 && (
                <p className="px-ds-4 py-ds-3 text-ds-small text-ds-text-muted">暂无元数据，请先执行采集任务。</p>
            )}
            {roots.map((root) => renderNode(root))}
        </div>
    );
}
