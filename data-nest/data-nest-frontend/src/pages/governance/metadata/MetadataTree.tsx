import {useEffect, useRef, useState} from 'react';
import {
    HiChevronRight,
    HiOutlineFolder,
    HiOutlineMagnifyingGlass,
    HiOutlineTableCells,
    HiOutlineXMark
} from 'react-icons/hi2';
import {
    listMetadataDatabases,
    listMetadataDatasourceIds,
    listMetadataSchemas,
    listMetadataTables,
    listMetadataTablesWithoutSchema,
    searchMetadataTree,
} from '../../../api/metadata';
import type {MetadataDatasource, MetadataTable, MetadataTreeNode} from '../../../types/metadata';
import DatabaseTypeIcon from '../../../components/DatabaseTypeIcon';
import DsSpinner from '../../../components/DsSpinner';
import {isWithoutSchema, SourceTypeEnum} from '../../../constants/datasource';

interface MetadataTreeProps {
    selectedNode: MetadataTreeNode | null;
    onSelect: (node: MetadataTreeNode) => void;
    expanded: Set<string>;
    onExpandedChange: (next: Set<string>) => void;
    onRootsLoaded?: (hasRoots: boolean) => void;
    autoSelectFirst?: boolean;
}

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
    const [searchKeyword, setSearchKeyword] = useState('');
    const [searchLoading, setSearchLoading] = useState(false);
    const [isSearchMode, setIsSearchMode] = useState(false);
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
            setSearchLoading(false);
            setIsSearchMode(false);
            loadedRef.current.clear();
            const result = await listMetadataDatasourceIds();
            const nodes: MetadataTreeNode[] = (result.data as MetadataDatasource[]).map((ds) => {
                const shortId = String(ds.id).slice(-6);
                const exists = ds.exists !== false;
                const isBuiltin = ds.sourceType === SourceTypeEnum.BUILTIN_DORIS;
                return {
                    id: `ds-${ds.id}`,
                    type: 'datasource',
                    name: isBuiltin ? 'Doris 数仓' : (ds.name ? ds.name : `数据源 ${shortId}${exists ? '' : '（已删除）'}`),
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
                const aBuiltin = a.sourceType === SourceTypeEnum.BUILTIN_DORIS ? 1 : 0;
                const bBuiltin = b.sourceType === SourceTypeEnum.BUILTIN_DORIS ? 1 : 0;
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
        } catch (err) {
            console.error('loadRoots failed', err);
            setRoots([]);
            onRootsLoaded?.(false);
        }
    };

    const handleSearch = async () => {
        const keyword = searchKeyword.trim();
        if (!keyword) {
            handleClearSearch();
            return;
        }
        setSearchLoading(true);
        try {
            const result = await searchMetadataTree(keyword);
            if (result.data) {
                const searchRoots = result.data;
                setRoots(searchRoots);
                setIsSearchMode(true);
                loadedRef.current.clear();
                // 搜索模式下默认展开所有非叶子节点
                const allExpanded = new Set<string>();
                const collectIds = (nodes: MetadataTreeNode[]) => {
                    for (const node of nodes) {
                        if (node.type !== 'table') {
                            allExpanded.add(node.id);
                        }
                        if (node.children) {
                            collectIds(node.children);
                        }
                    }
                };
                collectIds(searchRoots);
                onExpandedChange(allExpanded);
                onRootsLoaded?.(searchRoots.length > 0);
            }
        } catch (err) {
            console.error('search metadata tree failed', err);
        } finally {
            setSearchLoading(false);
        }
    };

    const handleClearSearch = () => {
        setSearchKeyword('');
        onExpandedChange(new Set());
        onSelect(selectedNode && selectedNode.type === 'table' ? selectedNode : null as unknown as MetadataTreeNode);
        loadRoots();
    };

    const loadChildren = async (node: MetadataTreeNode) => {
        if (node.type === 'table') {
            return;
        }
        if (node.type === 'datasource') {
            const datasourceId = node.datasourceId!;
            setLoading(node.id);
            try {
                const result = await listMetadataDatabases(datasourceId);
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
            } finally {
                setLoading(null);
            }
        } else if (node.type === 'database') {
            const datasourceId = node.datasourceId!;
            const databaseName = node.databaseName!;
            const datasourceType = node.datasourceType;
            // MySQL / Doris 的 database 与 schema 等价，直接加载表作为叶子
            if (isWithoutSchema(datasourceType)) {
                setLoading(node.id);
                try {
                    const result = await listMetadataTablesWithoutSchema(datasourceId, databaseName);
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
                } finally {
                    setLoading(null);
                }
                return;
            }
            // PostgreSQL 等需要展开 schema
            setLoading(node.id);
            try {
                const result = await listMetadataSchemas(datasourceId, databaseName);
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
            return <DatabaseTypeIcon type={node.datasourceType || ''} size={16} showLabel={false}/>;
        }
        if (node.type === 'database' || node.type === 'schema') {
            return <HiOutlineFolder size={16} className="text-ds-warning flex-shrink-0"/>;
        }
        return <HiOutlineTableCells size={16} className="text-ds-text-muted flex-shrink-0"/>;
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
                            <HiChevronRight size={14}/>
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
                        <DsSpinner size={12} className="ml-auto text-ds-accent"/>
                    )}
                </button>
                {expandedFlag && node.children && (
                    <div>{node.children.map((child) => renderNode(child, depth + 1))}</div>
                )}
            </div>
        );
    };

    return (
        <div className="w-full h-full flex flex-col overflow-hidden">
            <div className="px-ds-3 py-ds-2 border-b border-ds-border-subtle">
                <div className="relative">
                    <input
                        type="text"
                        value={searchKeyword}
                        onChange={(e) => setSearchKeyword(e.target.value)}
                        onKeyDown={(e) => {
                            if (e.key === 'Enter') {
                                handleSearch();
                            }
                        }}
                        placeholder="搜索库 / 模式 / 表"
                        className="w-full pl-ds-8 pr-ds-7 py-ds-1.5 text-ds-small bg-ds-bg-surface border border-ds-border-subtle rounded-ds-sm focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent"
                    />
                    <HiOutlineMagnifyingGlass size={14}
                                              className="absolute left-ds-2.5 top-1/2 -translate-y-1/2 text-ds-text-muted"/>
                    {searchKeyword && (
                        <button
                            onClick={handleClearSearch}
                            className="absolute right-ds-2 top-1/2 -translate-y-1/2 text-ds-text-muted hover:text-ds-text-primary"
                        >
                            <HiOutlineXMark size={14}/>
                        </button>
                    )}
                    {!searchKeyword && (
                        <button
                            onClick={handleSearch}
                            disabled={searchLoading}
                            className="absolute right-ds-2 top-1/2 -translate-y-1/2 text-ds-text-muted hover:text-ds-accent disabled:opacity-50"
                        >
                            <HiOutlineMagnifyingGlass size={14}/>
                        </button>
                    )}
                </div>
                {isSearchMode && (
                    <p className="mt-ds-1 text-ds-caption text-ds-text-muted truncate">
                        搜索结果：{roots.length > 0 ? `找到 ${roots.length} 个数据源` : '无匹配结果'}
                    </p>
                )}
            </div>
            <div className="flex-1 overflow-auto py-ds-2">
                {roots.length === 0 && !searchLoading && (
                    <p className="px-ds-4 py-ds-3 text-ds-small text-ds-text-muted">
                        {isSearchMode ? '未找到匹配的库 / 模式 / 表' : '暂无元数据，请先执行采集任务。'}
                    </p>
                )}
                {searchLoading && (
                    <p className="px-ds-4 py-ds-3 text-ds-small text-ds-text-muted">搜索中...</p>
                )}
                {roots.map((root) => renderNode(root))}
            </div>
        </div>
    );
}
