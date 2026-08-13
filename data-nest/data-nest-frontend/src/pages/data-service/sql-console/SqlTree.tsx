// Sprint 10 F1：SQL 终端左侧数据源树。
// 视觉完全对齐元数据管理页 MetadataTree：手写递归树、黄色文件夹、truncate+Tooltip（无左右滚动）、选中态高亮。
// 根 = sql-console 全部 NORMAL 数据源（含内置 Doris），库/表走元数据域接口懒加载。
// 内置 Doris 显示「Doris 数仓」+ 真实多库；未采集元数据的数据源展开库为空时 inline 触发采集（collect-now）。
// 点表节点 → onInsert(qualified) 插入 `SELECT * FROM 库.表 LIMIT 100`。
// 通过 ref 暴露 selectByPath(datasourceId, databaseName?, tableName?) 供查询历史回填联动高亮。
import {forwardRef, useCallback, useEffect, useImperativeHandle, useRef, useState} from 'react';
import {Empty, Input, Tooltip} from 'antd';
import {
    HiChevronRight,
    HiOutlineFolder,
    HiOutlineMagnifyingGlass,
    HiOutlineLockClosed,
    HiOutlineTableCells,
    HiOutlineXMark,
} from 'react-icons/hi2';
import DatabaseTypeIcon from '@/components/DatabaseTypeIcon';
import DsSpinner from '@/components/DsSpinner';
import {listSqlDatasources} from '@/api/data-service';
import {
    collectMetadataNow,
    getCollectTask,
    listMetadataDatabases,
    listMetadataSchemas,
    listMetadataTables,
    listMetadataTablesWithoutSchema,
    searchMetadataTree,
} from '@/api/metadata';
import {isWithoutSchema} from '@/constants/datasource';
import type {MetadataTable, MetadataTreeNode} from '@/types/metadata';
import type {SqlDatasource} from '@/types/data-service';
import DsButton from '@/components/DsButton';
import {notify} from '@/utils/notify';
import {usePollingWhile} from '@/hooks/usePollingWhile';

type NodeType = 'datasource' | 'database' | 'schema' | 'table';

interface TreeNodeMeta {
    type: NodeType;
    datasourceId: string;
    dbType?: string;
    databaseName?: string;
    schemaName?: string;
    name: string;
    builtin?: boolean;
    qualified?: string;
    sensitivityLevel?: string;
    /** 表数量（库/模式节点展示「N表」） */
    count?: number;
}

interface TreeItem {
    meta: TreeNodeMeta;
    children: TreeItem[];
}

export interface SqlTreeContext {
    datasourceId: string;
    dsName: string;
    databaseName?: string;
    schemaName?: string;
    tableName?: string;
}

export interface SqlTreeHandle {
    /** 外部按路径选中并展开到指定节点（查询历史回填联动）。 */
    selectByPath: (datasourceId: string, databaseName?: string, schemaName?: string, tableName?: string) => Promise<void>;
}

function nodeKey(meta: TreeNodeMeta): string {
    if (meta.type === 'datasource') return `ds:${meta.datasourceId}`;
    if (meta.type === 'database') return `db:${meta.datasourceId}:${meta.databaseName}`;
    if (meta.type === 'schema') return `sc:${meta.datasourceId}:${meta.databaseName}:${meta.schemaName}`;
    return `tb:${meta.qualified}`;
}

function renderIcon(meta: TreeNodeMeta): React.ReactNode {
    if (meta.type === 'datasource') {
        return meta.builtin
            ? <DatabaseTypeIcon type="DORIS" size={16} showLabel={false}/>
            : <DatabaseTypeIcon type={meta.dbType ?? ''} size={16} showLabel={false}/>;
    }
    if (meta.type === 'database' || meta.type === 'schema') {
        return <HiOutlineFolder size={16} className="text-ds-warning flex-shrink-0"/>;
    }
    return <HiOutlineTableCells size={16} className="text-ds-text-muted flex-shrink-0"/>;
}

function isSelected(meta: TreeNodeMeta, selectedKey: string | null): boolean {
    return selectedKey === nodeKey(meta);
}

function isExpanded(meta: TreeNodeMeta, expanded: Set<string>): boolean {
    return expanded.has(nodeKey(meta));
}

const SqlTree = forwardRef<SqlTreeHandle, {
    onInsert: (qualified: string) => void;
    onContextChange: (ctx: SqlTreeContext | null) => void;
}>(function SqlTree({onInsert, onContextChange}, ref) {
    const [items, setItems] = useState<TreeItem[]>([]);
    const [loading, setLoading] = useState(false);
    const [expanded, setExpanded] = useState<Set<string>>(new Set());
    const [selectedKey, setSelectedKey] = useState<string | null>(null);
    const [emptySource, setEmptySource] = useState<SqlDatasource | null>(null);
    const [collectingDatasourceId, setCollectingDatasourceId] = useState<string | null>(null);
    const [treeLoadingKey, setTreeLoadingKey] = useState<string | null>(null);
    const [searchKeyword, setSearchKeyword] = useState('');
    const [isSearchMode, setIsSearchMode] = useState(false);
    const [searchLoading, setSearchLoading] = useState(false);
    const loadedRef = useRef<Set<string>>(new Set());
    const dsNameMapRef = useRef<Map<string, string>>(new Map());
    const itemsRef = useRef<TreeItem[]>([]);
    itemsRef.current = items;
    // loadChildren 引用（loadRoots 默认展开 Doris 时主动加载子节点，避免 effect 依赖循环）
    const loadChildrenRef = useRef<(item: TreeItem) => Promise<void>>(() => Promise.resolve());
    const isSearchModeRef = useRef(false);
    isSearchModeRef.current = isSearchMode;

    const dsNameOf = useCallback((datasourceId: string) => {
        return dsNameMapRef.current.get(datasourceId) ?? '数据源';
    }, []);

    const findItem = useCallback((key: string, nodes: TreeItem[] = itemsRef.current): TreeItem | null => {
        for (const n of nodes) {
            if (nodeKey(n.meta) === key) return n;
            if (n.children) {
                const found = findItem(key, n.children);
                if (found) return found;
            }
        }
        return null;
    }, []);

    // 根：sql-console 全部 NORMAL 数据源
    const loadRoots = useCallback(async () => {
        setLoading(true);
        try {
            const res = await listSqlDatasources();
            const list = res.data ?? [];
            dsNameMapRef.current = new Map(list.map(d => [d.id, d.builtin ? 'Doris 数仓' : d.name]));
            const roots: TreeItem[] = list.map(ds => ({
                meta: {
                    type: 'datasource',
                    datasourceId: ds.id,
                    dbType: ds.type,
                    databaseName: ds.databaseName,
                    name: ds.builtin ? 'Doris 数仓' : ds.name,
                    builtin: ds.builtin,
                },
                children: [],
            }));
            setItems(roots);
            // 同步 itemsRef（loadChildren 内部用 itemsRef.current 展开树，setItems 异步时需先对齐）
            itemsRef.current = roots;
            // 内置 Doris 默认展开 + 首次进入自动选中（上下文条与树选中态一致）
            const doris = list.find(d => d.builtin);
            if (doris) {
                const dorisItem = roots.find(r => r.meta.datasourceId === doris.id);
                setExpanded(prev => new Set([...prev, `ds:${doris.id}`]));
                setSelectedKey(`ds:${doris.id}`);
                if (dorisItem) {
                    onContextChange({
                        datasourceId: doris.id,
                        dsName: dorisItem.meta.name,
                    });
                    // 默认展开的同时主动加载库列表，避免「展开但无子节点」的空态
                    await loadChildrenRef.current(dorisItem);
                }
            }
        } catch {
            /* 拦截器提示 */
        } finally {
            setLoading(false);
        }
    }, [onContextChange]);

    useEffect(() => {
        loadRoots();
    }, [loadRoots]);

    const loadChildren = useCallback(async (item: TreeItem) => {
        const meta = item.meta;
        const key = nodeKey(meta);
        if (loadedRef.current.has(key) || treeLoadingKey === key) return;

        setTreeLoadingKey(key);
        try {
            let children: TreeItem[] = [];
            if (meta.type === 'datasource') {
                const dbs = (await listMetadataDatabases(meta.datasourceId)).data ?? [];
                children = dbs.map(db => ({
                    meta: {
                        type: 'database',
                        datasourceId: meta.datasourceId,
                        dbType: meta.dbType,
                        databaseName: db,
                        name: db,
                    },
                    children: [],
                }));
                // 是否显示「立即采集」由 handleToggle 统一根据子节点是否为空决定，
                // 此处不 setEmptySource（避免与 handleToggle 竞态）。
            } else if (meta.type === 'database') {
                if (isWithoutSchema(meta.dbType)) {
                    const tables = (await listMetadataTablesWithoutSchema(meta.datasourceId, meta.databaseName!)).data ?? [];
                    children = (tables as MetadataTable[]).map(t => ({
                        meta: {
                            type: 'table',
                            datasourceId: meta.datasourceId,
                            dbType: meta.dbType,
                            databaseName: meta.databaseName,
                            name: t.tableName,
                            qualified: `${meta.databaseName}.${t.tableName}`,
                        },
                        children: [],
                    }));
                    item.meta.count = (tables as MetadataTable[]).length;
                } else {
                    const schemas = (await listMetadataSchemas(meta.datasourceId, meta.databaseName!)).data ?? [];
                    children = schemas.map(s => ({
                        meta: {
                            type: 'schema',
                            datasourceId: meta.datasourceId,
                            dbType: meta.dbType,
                            databaseName: meta.databaseName,
                            schemaName: s,
                            name: s,
                        },
                        children: [],
                    }));
                }
            } else if (meta.type === 'schema') {
                const tables = (await listMetadataTables(meta.datasourceId, meta.databaseName!, meta.schemaName!)).data ?? [];
                children = (tables as MetadataTable[]).map(t => ({
                    meta: {
                        type: 'table',
                        datasourceId: meta.datasourceId,
                        dbType: meta.dbType,
                        databaseName: meta.databaseName,
                        schemaName: meta.schemaName,
                        name: t.tableName,
                        qualified: `${meta.databaseName}.${meta.schemaName}.${t.tableName}`,
                        sensitivityLevel: t.sensitivityLevel,
                    },
                    children: [],
                }));
                item.meta.count = (tables as MetadataTable[]).length;
            }

            loadedRef.current.add(key);
            item.children = children;
            setItems([...itemsRef.current]);
        } finally {
            setTreeLoadingKey(null);
        }
    }, [treeLoadingKey]);
    loadChildrenRef.current = loadChildren;

    const handleToggle = useCallback(async (item: TreeItem) => {
        const key = nodeKey(item.meta);
        if (item.meta.type === 'table') {
            if (item.meta.sensitivityLevel === 'CONFIDENTIAL') {
                notify.warning(`表「${item.meta.name}」为机密级，无权查询`);
                return;
            }
            // 表节点：插入 SELECT 模板 + 更新上下文
            onInsert(item.meta.qualified!);
            setSelectedKey(key);
            onContextChange({
                datasourceId: item.meta.datasourceId,
                dsName: dsNameOf(item.meta.datasourceId),
                databaseName: item.meta.databaseName,
                tableName: item.meta.name,
            });
            return;
        }
        setSelectedKey(key);
        const dsName = dsNameOf(item.meta.datasourceId);
        // 任意层级节点都更新面包屑路径（数据源→库→模式逐层收窄）
        if (item.meta.type === 'datasource') {
            onContextChange({datasourceId: item.meta.datasourceId, dsName: item.meta.name});
        } else if (item.meta.type === 'database') {
            onContextChange({datasourceId: item.meta.datasourceId, dsName, databaseName: item.meta.databaseName});
        } else if (item.meta.type === 'schema') {
            onContextChange({
                datasourceId: item.meta.datasourceId,
                dsName,
                databaseName: item.meta.databaseName,
                schemaName: item.meta.schemaName,
            });
        }
        const next = new Set(expanded);
        const willExpand = !next.has(key);
        if (next.has(key)) {
            // 已展开但子节点尚未加载（如默认展开的 Doris）→ 点击改为加载库列表，
            // 避免「第一次点击收起、第二次点击才展开加载」的反直觉交互
            if (!loadedRef.current.has(key) && item.children.length === 0) {
                await loadChildren(item);
            } else {
                next.delete(key);
            }
        } else {
            next.add(key);
            if (!loadedRef.current.has(key)) {
                await loadChildren(item);
            }
        }
        setExpanded(next);
        // 展开数据源时根据「子节点是否为空」同步「立即采集」提示。
        // 无论是否走 loadChildren（含 loadedRef 缓存命中）都要执行，
        // 否则二次展开已缓存的空数据源时 emptySource 不会恢复。
        if (willExpand && item.meta.type === 'datasource') {
            if (item.children.length === 0) {
                setEmptySource({
                    id: item.meta.datasourceId,
                    name: item.meta.name,
                    type: item.meta.dbType ?? '',
                    builtin: !!item.meta.builtin,
                } as SqlDatasource);
            } else {
                setEmptySource(null);
            }
        }
    }, [expanded, loadChildren, onInsert, onContextChange, dsNameOf]);

    // ============ inline 采集（提交后自动轮询，采集完成自动刷新树） ============
    const collectingTaskIdRef = useRef<string | null>(null);
    const handleCollectNow = async () => {
        if (!emptySource || collectingDatasourceId) return;
        try {
            const res = await collectMetadataNow(emptySource.id);
            collectingTaskIdRef.current = res.data ?? null;
            notify.success(`「${emptySource.builtin ? 'Doris 数仓' : emptySource.name}」采集任务已提交，正在采集元数据…`);
            // 进入采集中状态 → 触发 usePollingWhile 轮询
            setCollectingDatasourceId(emptySource.id);
        } catch {
            /* 错误由拦截器统一提示 */
        }
    };

    /** 采集完成：更新该数据源子节点为库列表，展开并隐藏「立即采集」 */
    const markCollected = useCallback((datasourceId: string, dbs: string[]) => {
        const dsKey = `ds:${datasourceId}`;
        const dsItem = findItem(dsKey);
        const dsName = dsNameMapRef.current.get(datasourceId) ?? '数据源';
        const children: TreeItem[] = dbs.map(db => ({
            meta: {
                type: 'database',
                datasourceId,
                databaseName: db,
                name: db,
            },
            children: [],
        }));
        if (dsItem) {
            dsItem.children = children;
            loadedRef.current.add(dsKey);
            setItems([...itemsRef.current]);
        }
        setExpanded(prev => new Set([...prev, dsKey]));
        setEmptySource(null);
        setCollectingDatasourceId(null);
        collectingTaskIdRef.current = null;
        notify.success(`「${dsName}」元数据采集完成`);
    }, [findItem]);

    /** 采集失败/无元数据：停止轮询并提示 */
    const markCollectEnded = useCallback((datasourceId: string, message: string) => {
        const dsName = dsNameMapRef.current.get(datasourceId) ?? '数据源';
        setCollectingDatasourceId(null);
        collectingTaskIdRef.current = null;
        notify.warning(`「${dsName}」${message}`);
    }, []);

    // 轮询采集状态：每 3s 优先查库列表；库空则查采集任务 status 兜底判定
    const pollCollect = useCallback(async () => {
        const datasourceId = collectingDatasourceId;
        if (!datasourceId) return;
        try {
            const dbs = (await listMetadataDatabases(datasourceId)).data ?? [];
            if (dbs.length > 0) {
                markCollected(datasourceId, dbs);
                return;
            }
            // 库仍空：查采集任务状态兜底（区分「运行中」「成功但无元数据」「失败」）
            const taskId = collectingTaskIdRef.current;
            if (taskId) {
                const task = (await getCollectTask(taskId)).data;
                if (task && task.status === 'SUCCESS') {
                    // 任务成功但库为空 → 数据源可能无可见库，或采集逻辑未枚举到
                    markCollectEnded(datasourceId, '采集完成但未发现元数据，请检查数据源配置或采集任务');
                } else if (task && (task.status === 'FAILED' || task.status === 'TERMINATED')) {
                    markCollectEnded(datasourceId, '采集任务执行失败，请检查采集任务详情');
                }
                // RUNNING/NEVER_EXECUTED → 继续等待
            }
        } catch {
            // 网络抖动忽略，等待下次轮询
        }
    }, [collectingDatasourceId, markCollected, markCollectEnded]);

    usePollingWhile(
        !!collectingDatasourceId,
        () => { void pollCollect(); },
        {interval: 3000, timeout: 90000},
    );

    // ============ 搜索库/模式/表 ============
    /** MetadataTreeNode（搜索返回）→ TreeItem */
    const toTreeItem = useCallback((node: MetadataTreeNode): TreeItem => {
        const datasourceId = node.datasourceId ?? '';
        const qualified = node.type === 'table'
            ? `${node.databaseName ?? ''}${node.schemaName && node.schemaName !== node.databaseName ? `.${node.schemaName}` : ''}.${node.name}`
            : undefined;
        return {
            meta: {
                type: node.type,
                datasourceId,
                dbType: node.datasourceType,
                databaseName: node.databaseName,
                schemaName: node.schemaName,
                name: node.name,
                qualified,
                count: node.count,
                builtin: node.sourceType === 'BUILTIN_DORIS',
            },
            children: (node.children ?? []).map(toTreeItem),
        };
    }, []);

    const handleSearch = useCallback(async (keyword: string) => {
        const kw = keyword.trim();
        if (!kw) {
            // 清空搜索 → 回到正常数据源树
            setIsSearchMode(false);
            setExpanded(prev => new Set([...prev])); // 保留现有展开
            return;
        }
        setSearchLoading(true);
        try {
            const res = await searchMetadataTree(kw);
            const nodes = res.data ?? [];
            setItems(nodes.map(toTreeItem));
            setIsSearchMode(true);
            // 搜索模式：默认展开所有非叶子节点
            const allExpanded = new Set<string>();
            const collect = (items: TreeItem[]) => {
                for (const it of items) {
                    if (it.meta.type !== 'table') allExpanded.add(nodeKey(it.meta));
                    collect(it.children);
                }
            };
            collect(nodes.map(toTreeItem));
            setExpanded(allExpanded);
            setEmptySource(null);
        } catch {
            /* 拦截器提示 */
        } finally {
            setSearchLoading(false);
        }
    }, [toTreeItem]);

    const handleClearSearch = useCallback(() => {
        setSearchKeyword('');
        setIsSearchMode(false);
        setSelectedKey(null);
        loadRoots();
    }, [loadRoots]);

    // ============ 历史回填联动：按路径选中并展开 ============
    const selectByPath = useCallback(async (datasourceId: string, databaseName?: string, schemaName?: string, tableName?: string) => {
        // 搜索模式下先退出搜索回到正常数据源树
        if (isSearchModeRef.current) {
            setIsSearchMode(false);
            setSearchKeyword('');
            await loadRoots();
        }
        const dsKey = `ds:${datasourceId}`;
        let dsItem = findItem(dsKey);
        if (!dsItem) {
            await loadRoots();
            dsItem = findItem(dsKey);
        }
        if (!dsItem) return;
        if (!loadedRef.current.has(dsKey) && !dsItem.children.length) {
            await loadChildren(dsItem);
        }
        setExpanded(prev => new Set([...prev, dsKey]));
        if (databaseName) {
            const dbKey = `db:${datasourceId}:${databaseName}`;
            const dbItem = findItem(dbKey);
            if (dbItem) {
                if (!loadedRef.current.has(dbKey) && !dbItem.children.length) {
                    await loadChildren(dbItem);
                }
                setExpanded(prev => new Set([...prev, dbKey]));
                // 定位 schema 层（如有）
                const schemaItems = dbItem.children.filter(c => c.meta.type === 'schema');
                let schemaKey: string | null = null;
                if (schemaItems.length > 0) {
                    const targetSchema = schemaName
                        ? schemaItems.find(c => c.meta.schemaName === schemaName)
                        : undefined;
                    const target = targetSchema ?? schemaItems[0];
                    schemaKey = nodeKey(target.meta);
                    if (!loadedRef.current.has(schemaKey) && !target.children.length) {
                        await loadChildren(target);
                    }
                    setExpanded(prev => new Set([...prev, schemaKey!]));
                }
                if (tableName) {
                    // 在库（或 schema）的子节点中按表名定位（不依赖 key 拼接，兼容多 schema）
                    let container = dbItem;
                    if (schemaKey) {
                        const scItem = schemaItems.find(c => nodeKey(c.meta) === schemaKey);
                        if (scItem) container = scItem;
                    }
                    const tbItem = container.children.find(
                        c => c.meta.type === 'table' && c.meta.name === tableName,
                    );
                    if (tbItem) {
                        setSelectedKey(nodeKey(tbItem.meta));
                    }
                }
            }
        }
    }, [findItem, loadChildren, loadRoots]);

    useImperativeHandle(ref, () => ({selectByPath}), [selectByPath]);

    // ============ 递归渲染（对齐 MetadataTree 视觉） ============
    const renderNode = (item: TreeItem, depth = 0) => {
        const meta = item.meta;
        const hasChildren = meta.type !== 'table';
        const expandedFlag = isExpanded(meta, expanded);
        const selectedFlag = isSelected(meta, selectedKey);
        const paddingLeft = 12 + depth * 16;

        return (
            <div key={nodeKey(meta)}>
                <button
                    type="button"
                    onClick={() => handleToggle(item)}
                    style={{paddingLeft}}
                    className={`w-full flex items-center gap-ds-2 py-ds-2 pr-ds-2 text-left text-ds-small transition-colors ${
                        selectedFlag
                            ? 'bg-ds-accent-light text-ds-accent font-semibold'
                            : 'text-ds-text-secondary hover:bg-ds-bg-hover hover:text-ds-text-primary'
                    }`}
                >
                    {hasChildren && (
                        <span className={`w-4 flex-shrink-0 text-ds-text-muted transition-transform ${expandedFlag ? 'rotate-90' : ''}`}>
                            <HiChevronRight size={14}/>
                        </span>
                    )}
                    {!hasChildren && <span className="w-4 flex-shrink-0"/>}
                    {renderIcon(meta)}
                    {meta.type === 'table' && meta.sensitivityLevel === 'CONFIDENTIAL' && (
                        <HiOutlineLockClosed size={12} className="text-ds-danger flex-shrink-0"/>
                    )}
                    <Tooltip title={meta.type === 'table'
                        ? (meta.sensitivityLevel === 'CONFIDENTIAL' ? `机密级表，无权查询：${meta.qualified}` : `点击插入 SELECT：${meta.qualified}`)
                        : meta.name} placement="top">
                        <span className="truncate min-w-0 flex-1" title={meta.name}>{meta.name}</span>
                    </Tooltip>
                    {meta.count !== undefined && meta.count > 0 && meta.type !== 'table' && (
                        <span className="ml-auto text-ds-nano text-ds-text-muted tabular-nums">
                            {meta.count}表
                        </span>
                    )}
                    {treeLoadingKey === nodeKey(meta) && (
                        <DsSpinner size={12} className="ml-auto text-ds-accent"/>
                    )}
                </button>
                {expandedFlag && item.children && (
                    <div>{item.children.map((child) => renderNode(child, depth + 1))}</div>
                )}
            </div>
        );
    };

    return (
        <div className="h-full flex flex-col">
            <div className="px-ds-3 py-ds-2 flex items-center justify-between border-b border-ds-border-subtle flex-shrink-0">
                <span className="text-ds-small font-semibold text-ds-text-primary">数据目录</span>
                {isSearchMode ? (
                    <span className="text-ds-caption text-ds-accent">搜索结果</span>
                ) : (
                    <span className="text-ds-caption text-ds-text-muted">点表插入 SELECT</span>
                )}
            </div>
            {/* 搜索框 */}
            <div className="px-ds-3 py-ds-1.5 border-b border-ds-border-subtle flex-shrink-0">
                <Input
                    size="small"
                    allowClear={false}
                    value={searchKeyword}
                    onChange={(e) => {
                        const v = e.target.value;
                        setSearchKeyword(v);
                        handleSearch(v);
                    }}
                    onPressEnter={() => handleSearch(searchKeyword)}
                    placeholder="搜索库 / 模式 / 表"
                    prefix={<HiOutlineMagnifyingGlass size={13} className="text-ds-text-muted"/>}
                    suffix={searchLoading
                        ? <DsSpinner size={12} className="text-ds-accent"/>
                        : (searchKeyword
                            ? <HiOutlineXMark
                                size={14}
                                className="text-ds-text-muted cursor-pointer hover:text-ds-text-primary"
                                onClick={handleClearSearch}
                            />
                            : null)}
                    className="ds-input-compact"
                />
            </div>
            <div className="flex-1 min-h-0 overflow-y-auto py-ds-2">
                {loading && items.length === 0 && (
                    <p className="px-ds-4 py-ds-3 text-ds-small text-ds-text-muted">加载数据源…</p>
                )}
                {!loading && items.length === 0 && !isSearchMode && (
                    <p className="px-ds-4 py-ds-3 text-ds-small text-ds-text-muted">暂无数据源</p>
                )}
                {!loading && items.length === 0 && isSearchMode && (
                    <p className="px-ds-4 py-ds-3 text-ds-small text-ds-text-muted">未找到匹配的库或表</p>
                )}
                {items.map((root) => renderNode(root))}
                {emptySource && (
                    <div className="px-ds-3 py-ds-4 border-t border-ds-border-subtle">
                        <Empty
                            image={Empty.PRESENTED_IMAGE_SIMPLE}
                            description={
                                <span className="text-ds-caption text-ds-text-muted">
                                    「{emptySource.builtin ? 'Doris 数仓' : emptySource.name}」暂无元数据
                                </span>
                            }
                        >
                            <DsButton
                                variant="secondary"
                                onClick={handleCollectNow}
                                loading={!!collectingDatasourceId}
                                disabled={!!collectingDatasourceId}
                            >
                                {collectingDatasourceId ? '采集中…' : '立即采集'}
                            </DsButton>
                        </Empty>
                    </div>
                )}
            </div>
        </div>
    );
});

export default SqlTree;
