import {useEffect, useState} from 'react';
import {useNavigate, useSearchParams} from 'react-router-dom';
import {useAuthStore} from '../../../store/useAuthStore';
import {
    getMetadataTable,
    listMetadataColumns,
    listMetadataDatabases,
    listMetadataSchemas,
    listMetadataTables,
    listMetadataTablesWithoutSchema,
    updateColumnComment,
    updateColumnRemark,
    updateTableComment,
} from '../../../api/metadata';
import type {MetadataColumn, MetadataTable, MetadataTreeNode} from '../../../types/metadata';
import MetadataTree from './MetadataTree';
import EmptyState from '../../../components/EmptyState';
import {previewMetadataTable} from '../../../api/preview';
import PreviewModal from '../../../components/PreviewModal';
import {HiOutlineBookOpen, HiOutlineCircleStack, HiOutlineEye, HiOutlineTableCells} from 'react-icons/hi2';
import {formatDateTime} from '../../../utils/time';

const DB_TYPES_WITHOUT_SCHEMA = new Set(['MYSQL', 'DORIS']);

export default function MetadataPage() {
    const navigate = useNavigate();
    const [searchParams] = useSearchParams();
    const tableIdParam = searchParams.get('tableId');
    const columnIdParam = searchParams.get('columnId');
    const {userInfo} = useAuthStore();
    const roles = userInfo?.roles || [];
    const canWrite = roles.includes('SUPER_ADMIN') || roles.includes('GOVERNANCE_ADMIN');

    const [expanded, setExpanded] = useState<Set<string>>(new Set());
    const [selectedNode, setSelectedNode] = useState<MetadataTreeNode | null>(null);
    const [highlightedColumnId, setHighlightedColumnId] = useState<string | null>(null);
    const [databases, setDatabases] = useState<string[]>([]);
    const [databasesLoading, setDatabasesLoading] = useState(false);
    const [schemas, setSchemas] = useState<string[]>([]);
    const [schemasLoading, setSchemasLoading] = useState(false);
    const [tables, setTables] = useState<MetadataTable[]>([]);
    const [tablesLoading, setTablesLoading] = useState(false);
    const [selectedTable, setSelectedTable] = useState<MetadataTable | null>(null);
    const [columns, setColumns] = useState<MetadataColumn[]>([]);
    const [columnsLoading, setColumnsLoading] = useState(false);
    const [editingCell, setEditingCell] = useState<{
        type: 'table-comment' | 'column-comment' | 'column-remark';
        id: string;
        value: string;
    } | null>(null);
    const [hasRoots, setHasRoots] = useState(false);

    const [previewOpen, setPreviewOpen] = useState(false);
    const [previewLoading, setPreviewLoading] = useState(false);
    const [previewResult, setPreviewResult] = useState<{
        columns: string[];
        rows: Array<Record<string, any>>;
        rowCount: number
    } | null>(null);
    const [previewTitle, setPreviewTitle] = useState('');

    const canPreview = roles.includes('SUPER_ADMIN') || roles.includes('GOVERNANCE_ADMIN') || roles.includes('DATA_ENGINEER') || roles.includes('DATA_ANALYST');

    const resetDetail = () => {
        setSelectedTable(null);
        setColumns([]);
        setHighlightedColumnId(null);
    };

    const resetLists = () => {
        setDatabases([]);
        setSchemas([]);
        setTables([]);
    };

    // 根据 URL query 参数自动选中指定表并展开祖先节点
    useEffect(() => {
        if (!tableIdParam) return;
        let cancelled = false;
        const autoSelect = async () => {
            try {
                const result = await getMetadataTable(tableIdParam);
                if (cancelled || result.code !== 200 || !result.data) return;
                const table = result.data;
                const datasourceId = table.datasourceId;
                const databaseName = table.databaseName;
                const schemaName = table.schemaName;
                const withoutSchema = table.datasourceType && DB_TYPES_WITHOUT_SCHEMA.has(table.datasourceType.toUpperCase());
                setExpanded((prev) => {
                    const nextExpanded = new Set(prev);
                    nextExpanded.add(`ds-${datasourceId}`);
                    nextExpanded.add(`db-${datasourceId}-${databaseName}`);
                    if (!withoutSchema && schemaName) {
                        nextExpanded.add(`schema-${datasourceId}-${databaseName}-${schemaName}`);
                    }
                    return nextExpanded;
                });
                const node: MetadataTreeNode = {
                    id: `table-${table.id}`,
                    type: 'table',
                    name: table.tableName,
                    databaseName,
                    schemaName: schemaName || databaseName,
                    datasourceId: String(datasourceId),
                    datasourceType: table.datasourceType,
                };
                setSelectedNode(node);
                setHighlightedColumnId(columnIdParam || null);
            } catch (err) {
                console.error('auto select table failed', err);
            }
        };
        autoSelect();
        return () => {
            cancelled = true;
        };
    }, [tableIdParam, columnIdParam]);

    useEffect(() => {
        if (!selectedNode) {
            resetLists();
            resetDetail();
            return;
        }
        if (selectedNode.type === 'datasource') {
            loadDatabases(selectedNode);
            resetDetail();
        } else if (selectedNode.type === 'database') {
            if (isWithoutSchema(selectedNode.datasourceType)) {
                loadTables(selectedNode, selectedNode.databaseName!);
                resetDetail();
            } else {
                loadSchemas(selectedNode);
                resetDetail();
            }
        } else if (selectedNode.type === 'schema') {
            loadTables(selectedNode, selectedNode.schemaName!);
            resetDetail();
        } else if (selectedNode.type === 'table') {
            loadTableDetail(selectedNode);
        }
    }, [selectedNode]);

    const isWithoutSchema = (type?: string) => type && DB_TYPES_WITHOUT_SCHEMA.has(type.toUpperCase());

    const extractDatasourceId = (node: MetadataTreeNode) => {
        if (node.datasourceId) return node.datasourceId;
        if (node.type === 'datasource') return node.id.replace('ds-', '');
        return node.id.split('-')[1];
    };

    const expandAncestors = (node: MetadataTreeNode) => {
        const next = new Set(expanded);
        if (node.type === 'database' || node.type === 'schema' || node.type === 'table') {
            const datasourceId = extractDatasourceId(node);
            next.add(`ds-${datasourceId}`);
        }
        if (node.type === 'schema' || node.type === 'table') {
            const datasourceId = extractDatasourceId(node);
            next.add(`db-${datasourceId}-${node.databaseName}`);
        }
        setExpanded(next);
    };

    const loadDatabases = async (node: MetadataTreeNode) => {
        const datasourceId = extractDatasourceId(node);
        setDatabasesLoading(true);
        try {
            const result = await listMetadataDatabases(datasourceId);
            if (result.code === 200) {
                setDatabases(result.data);
            }
        } finally {
            setDatabasesLoading(false);
        }
    };

    const loadSchemas = async (node: MetadataTreeNode) => {
        const datasourceId = extractDatasourceId(node);
        const databaseName = node.databaseName!;
        setSchemasLoading(true);
        try {
            const result = await listMetadataSchemas(datasourceId, databaseName);
            if (result.code === 200) {
                setSchemas(result.data);
            }
        } finally {
            setSchemasLoading(false);
        }
    };

    const loadTables = async (node: MetadataTreeNode, schemaName: string) => {
        const datasourceId = extractDatasourceId(node);
        const databaseName = node.databaseName!;
        setTablesLoading(true);
        try {
            const result = isWithoutSchema(node.datasourceType)
                ? await listMetadataTablesWithoutSchema(datasourceId, databaseName)
                : await listMetadataTables(datasourceId, databaseName, schemaName);
            if (result.code === 200) {
                setTables(result.data);
            }
        } finally {
            setTablesLoading(false);
        }
    };

    const loadTableDetail = async (node: MetadataTreeNode) => {
        const tableId = node.id.replace('table-', '');
        setColumnsLoading(true);
        try {
            const [tableResult, columnsResult] = await Promise.all([
                getMetadataTable(tableId),
                listMetadataColumns(tableId),
            ]);
            if (tableResult.code === 200) {
                setSelectedTable(tableResult.data);
            }
            if (columnsResult.code === 200) {
                setColumns(columnsResult.data);
            }
        } finally {
            setColumnsLoading(false);
        }
    };

    const selectDatabase = (db: string) => {
        if (!selectedNode || selectedNode.type !== 'datasource') return;
        const datasourceId = extractDatasourceId(selectedNode);
        const node: MetadataTreeNode = {
            id: `db-${datasourceId}-${db}`,
            type: 'database',
            name: db,
            databaseName: db,
            schemaName: '',
            datasourceType: selectedNode.datasourceType,
        };
        expandAncestors(node);
        setSelectedNode(node);
    };

    const selectSchema = (schema: string) => {
        if (!selectedNode || selectedNode.type !== 'database') return;
        const datasourceId = extractDatasourceId(selectedNode);
        const node: MetadataTreeNode = {
            id: `schema-${datasourceId}-${selectedNode.databaseName}-${schema}`,
            type: 'schema',
            name: schema,
            databaseName: selectedNode.databaseName,
            schemaName: schema,
            datasourceType: selectedNode.datasourceType,
        };
        expandAncestors(node);
        setSelectedNode(node);
    };

    const selectTable = (table: MetadataTable) => {
        const node: MetadataTreeNode = {
            id: `table-${table.id}`,
            type: 'table',
            name: table.tableName,
            databaseName: table.databaseName,
            schemaName: table.schemaName,
            datasourceId: selectedNode?.datasourceId,
            datasourceType: selectedNode?.datasourceType,
        };
        expandAncestors(node);
        setSelectedNode(node);
    };

    const startEdit = (type: 'table-comment' | 'column-comment' | 'column-remark', id: string, value: string) => {
        if (!canWrite) return;
        setEditingCell({type, id, value: value || ''});
    };

    const handleSaveTableComment = async () => {
        if (!editingCell || !selectedTable || editingCell.type !== 'table-comment') return;
        const value = editingCell.value.trim();
        const result = await updateTableComment(selectedTable.id, value);
        if (result.code === 200) {
            setSelectedTable((prev) => prev ? {...prev, manualComment: value} : null);
            setTables((prev) => prev.map((t) => t.id === selectedTable.id ? {...t, manualComment: value} : t));
        }
        setEditingCell(null);
    };

    const handleSaveColumnComment = async (column: MetadataColumn) => {
        if (!editingCell || editingCell.type !== 'column-comment') return;
        const value = editingCell.value.trim();
        const result = await updateColumnComment(column.id, value);
        if (result.code === 200) {
            setColumns((prev) => prev.map((c) => c.id === column.id ? {...c, manualComment: value} : c));
        }
        setEditingCell(null);
    };

    const handleSaveColumnRemark = async (column: MetadataColumn) => {
        if (!editingCell || editingCell.type !== 'column-remark') return;
        const value = editingCell.value.trim();
        const result = await updateColumnRemark(column.id, value);
        if (result.code === 200) {
            setColumns((prev) => prev.map((c) => c.id === column.id ? {...c, remark: value} : c));
        }
        setEditingCell(null);
    };

    const renderEditableCell = (
        type: 'table-comment' | 'column-comment' | 'column-remark',
        id: string,
        value: string | undefined,
        save: () => void,
        width: string = 'w-full',
    ) => {
        const originalValue = (value || '').trim();
        const isEditing = editingCell?.type === type && editingCell.id === id;
        if (isEditing) {
            const handleFinish = () => {
                const newValue = editingCell.value.trim();
                if (newValue === '' || newValue === originalValue) {
                    setEditingCell(null);
                    return;
                }
                save();
            };
            return (
                <input
                    autoFocus
                    data-testid="metadata-comment-input"
                    value={editingCell.value}
                    onChange={(e) => setEditingCell({...editingCell, value: e.target.value})}
                    onBlur={handleFinish}
                    onKeyDown={(e) => {
                        if (e.key === 'Enter') {
                            e.preventDefault();
                            handleFinish();
                        } else if (e.key === 'Escape') {
                            setEditingCell(null);
                        }
                    }}
                    className={`${width} px-ds-2 py-ds-1 text-ds-small bg-ds-bg-surface border border-ds-border-subtle rounded-ds-sm focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent`}
                />
            );
        }
        return (
            <div className="group flex items-center gap-ds-2 min-h-[28px]">
                <span className="text-ds-small text-ds-text-secondary truncate">{value || '-'}</span>
                {canWrite && (
                    <button
                        onClick={() => startEdit(type, id, value || '')}
                        className="opacity-0 group-hover:opacity-100 text-ds-text-muted hover:text-ds-accent text-ds-caption transition-opacity"
                    >
                        编辑
                    </button>
                )}
            </div>
        );
    };

    const handlePreviewTable = async (table: MetadataTable) => {
        if (!table.id || !canPreview) return;
        setPreviewOpen(true);
        setPreviewLoading(true);
        setPreviewTitle(`${table.databaseName}${table.schemaName && table.schemaName !== table.databaseName ? ` / ${table.schemaName}` : ''} / ${table.tableName}`);
        setPreviewResult(null);
        try {
            const result = await previewMetadataTable(table.id);
            if (result.code === 200 && result.data) {
                setPreviewResult({
                    columns: result.data.columns,
                    rows: result.data.rows,
                    rowCount: result.data.rowCount,
                });
            }
        } finally {
            setPreviewLoading(false);
        }
    };

    const renderDatabaseList = () => (
        <div className="bg-white border border-ds-border-subtle rounded-ds-md overflow-hidden">
            <table className="w-full">
                <thead>
                <tr className="border-b border-ds-border-subtle bg-ds-bg-hover/80">
                    <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-secondary font-semibold">库名</th>
                </tr>
                </thead>
                <tbody>
                {databases.map((db) => (
                    <tr
                        key={db}
                        className="border-b border-ds-border-subtle last:border-0 hover:bg-ds-bg-hover cursor-pointer"
                        onClick={() => selectDatabase(db)}
                    >
                        <td className="px-ds-4 py-ds-3 text-ds-body text-ds-accent font-medium">{db}</td>
                    </tr>
                ))}
                </tbody>
            </table>
        </div>
    );

    const renderSchemaList = () => (
        <div className="bg-white border border-ds-border-subtle rounded-ds-md overflow-hidden">
            <table className="w-full">
                <thead>
                <tr className="border-b border-ds-border-subtle bg-ds-bg-hover/80">
                    <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-secondary font-semibold">Schema</th>
                </tr>
                </thead>
                <tbody>
                {schemas.map((schema) => (
                    <tr
                        key={schema}
                        className="border-b border-ds-border-subtle last:border-0 hover:bg-ds-bg-hover cursor-pointer"
                        onClick={() => selectSchema(schema)}
                    >
                        <td className="px-ds-4 py-ds-3 text-ds-body text-ds-accent font-medium">{schema}</td>
                    </tr>
                ))}
                </tbody>
            </table>
        </div>
    );

    const renderTableList = () => (
        <div className="bg-white border border-ds-border-subtle rounded-ds-md overflow-hidden">
            <table className="w-full">
                <thead>
                <tr className="border-b border-ds-border-subtle bg-ds-bg-hover/80">
                    <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-secondary font-semibold">表名</th>
                    <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-secondary font-semibold">注释</th>
                    <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-secondary font-semibold">字段数</th>
                    <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-secondary font-semibold">采集来源</th>
                </tr>
                </thead>
                <tbody>
                {tables.map((table) => (
                    <tr
                        key={table.id}
                        className="border-b border-ds-border-subtle last:border-0 hover:bg-ds-bg-hover cursor-pointer"
                        onClick={() => selectTable(table)}
                    >
                        <td className="px-ds-4 py-ds-3 text-ds-body text-ds-accent font-medium">{table.tableName}</td>
                        <td className="px-ds-4 py-ds-3 text-ds-small text-ds-text-secondary">{table.manualComment || table.tableComment || '-'}</td>
                        <td className="px-ds-4 py-ds-3 text-ds-small text-ds-text-secondary">{table.columnCount ?? '-'}</td>
                        <td className="px-ds-4 py-ds-3 text-ds-small text-ds-text-secondary">{table.sourceTaskName || '-'}</td>
                    </tr>
                ))}
                </tbody>
            </table>
        </div>
    );

    const renderTableDetail = () => {
        if (!selectedTable) return null;
        return (
            <div>
                <div className="text-ds-small text-ds-text-muted mb-ds-4">
                    {selectedTable.databaseName}
                    {selectedTable.schemaName && selectedTable.schemaName !== selectedTable.databaseName && (
                        <span> / <span className="text-ds-text-primary font-semibold">{selectedTable.schemaName}</span></span>
                    )}
                    <span> / <span
                        className="text-ds-text-primary font-semibold">{selectedTable.tableName}</span></span>
                </div>

                <div className="mb-ds-6">
                    <div className="flex items-center justify-between mb-ds-3">
                        <h3 className="text-ds-small font-semibold text-ds-text-secondary uppercase tracking-wider border-b border-ds-border-subtle pb-ds-2 mb-ds-0">
                            表信息
                        </h3>
                        {canPreview && (
                            <button
                                onClick={() => handlePreviewTable(selectedTable)}
                                className="inline-flex items-center gap-ds-1 px-ds-3 py-ds-1.5 text-ds-small font-medium text-ds-accent hover:bg-ds-accent-light rounded-ds-sm transition-colors"
                            >
                                <HiOutlineEye size={16}/>
                                预览
                            </button>
                        )}
                    </div>
                    <div className="grid grid-cols-[100px_1fr] gap-y-ds-3 text-ds-small">
                        <span className="text-ds-text-muted">表名</span>
                        <span className="text-ds-text-primary font-medium">{selectedTable.tableName}</span>

                        <span className="text-ds-text-muted">注释</span>
                        <div>
                            {renderEditableCell('table-comment', selectedTable.id, selectedTable.manualComment || selectedTable.tableComment, handleSaveTableComment)}
                        </div>

                        <span className="text-ds-text-muted">字段数</span>
                        <span className="text-ds-text-primary">{selectedTable.columnCount ?? '-'}</span>

                        <span className="text-ds-text-muted">最近采集</span>
                        <span className="text-ds-text-primary">
                            {formatDateTime(selectedTable.lastCollectTime)}
                            {selectedTable.sourceTaskName ? `（${selectedTable.sourceTaskName}）` : ''}
                        </span>

                        <span className="text-ds-text-muted">数据源</span>
                        <span className="text-ds-text-primary">
                            {selectedTable.datasourceName || selectedTable.databaseName}
                            {selectedTable.datasourceType && ` (${selectedTable.datasourceType})`}
                        </span>
                    </div>
                </div>

                <div>
                    <h3 className="text-ds-small font-semibold text-ds-text-secondary uppercase tracking-wider border-b border-ds-border-subtle pb-ds-2 mb-ds-3">
                        字段列表
                    </h3>
                    {columnsLoading ? (
                        <p className="text-ds-small text-ds-text-muted">加载中...</p>
                    ) : (
                        <div className="bg-white border border-ds-border-subtle rounded-ds-md overflow-hidden">
                            <table className="w-full">
                                <thead>
                                <tr className="border-b border-ds-border-subtle bg-ds-bg-hover/80">
                                    <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-secondary font-semibold">字段名</th>
                                    <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-secondary font-semibold">数据类型</th>
                                    <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-secondary font-semibold">中文注释</th>
                                    <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-secondary font-semibold">是否可空</th>
                                    <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-secondary font-semibold">备注</th>
                                </tr>
                                </thead>
                                <tbody>
                                {columns.map((column) => (
                                    <tr key={column.id}
                                        className={`border-b border-ds-border-subtle last:border-0 hover:bg-ds-bg-hover ${column.id === highlightedColumnId ? 'bg-ds-warning/10' : ''}`}>
                                        <td className="px-ds-4 py-ds-3 text-ds-body text-ds-text-primary font-medium">{column.columnName}</td>
                                        <td className="px-ds-4 py-ds-3 text-ds-small text-ds-text-secondary">{column.dataType || '-'}</td>
                                        <td className="px-ds-4 py-ds-3 min-w-[180px]">
                                            {renderEditableCell('column-comment', column.id, column.manualComment || column.columnComment, () => handleSaveColumnComment(column))}
                                        </td>
                                        <td className="px-ds-4 py-ds-3 text-ds-small text-ds-text-secondary">
                                            {column.nullable ? 'YES' : 'NO'}
                                        </td>
                                        <td className="px-ds-4 py-ds-3 min-w-[180px]">
                                            {renderEditableCell('column-remark', column.id, column.remark, () => handleSaveColumnRemark(column))}
                                        </td>
                                    </tr>
                                ))}
                                </tbody>
                            </table>
                        </div>
                    )}
                </div>
            </div>
        );
    };

    const renderRightPanel = () => {
        if (!selectedNode) {
            if (!hasRoots) {
                return (
                    <EmptyState
                        title="暂无元数据"
                        description="请先在「元数据采集任务」中创建并执行任务，完成后即可在这里查看库表结构。"
                        action={
                            <button
                                onClick={() => navigate('/governance/collect-tasks')}
                                className="inline-flex items-center gap-ds-2 px-ds-4 py-ds-2 bg-ds-accent text-white text-ds-small font-semibold rounded-ds-sm hover:bg-ds-accent-hover transition-colors"
                            >
                                前往元数据采集任务
                            </button>
                        }
                    />
                );
            }
            return <EmptyState title="请选择目录节点" description="在左侧选择数据源或库，查看库、Schema 或表结构。"/>;
        }

        if (selectedNode.type === 'datasource') {
            return (
                <div>
                    <h2 className="text-ds-title text-ds-text-primary font-semibold mb-ds-4 flex items-center gap-ds-2">
                        <HiOutlineCircleStack size={20} className="text-ds-accent"/>
                        {selectedNode.name} 库列表
                        <span
                            className="text-ds-caption text-ds-text-muted font-normal">（共 {databases.length} 个库）</span>
                    </h2>
                    {databasesLoading ? (
                        <p className="text-ds-small text-ds-text-muted">加载中...</p>
                    ) : databases.length === 0 ? (
                        <EmptyState title="暂无库" description="该数据源下没有采集到库。"/>
                    ) : (
                        renderDatabaseList()
                    )}
                </div>
            );
        }

        if (selectedNode.type === 'database') {
            if (isWithoutSchema(selectedNode.datasourceType)) {
                return (
                    <div>
                        <h2 className="text-ds-title text-ds-text-primary font-semibold mb-ds-4 flex items-center gap-ds-2">
                            <HiOutlineTableCells size={20} className="text-ds-accent"/>
                            {selectedNode.databaseName} 表列表
                            <span
                                className="text-ds-caption text-ds-text-muted font-normal">（共 {tables.length} 张表）</span>
                        </h2>
                        {tablesLoading ? (
                            <p className="text-ds-small text-ds-text-muted">加载中...</p>
                        ) : tables.length === 0 ? (
                            <EmptyState title="暂无表" description="当前库没有采集到表结构。"/>
                        ) : (
                            renderTableList()
                        )}
                    </div>
                );
            }
            return (
                <div>
                    <h2 className="text-ds-title text-ds-text-primary font-semibold mb-ds-4 flex items-center gap-ds-2">
                        <HiOutlineCircleStack size={20} className="text-ds-accent"/>
                        {selectedNode.databaseName} Schema 列表
                        <span className="text-ds-caption text-ds-text-muted font-normal">（共 {schemas.length} 个）</span>
                    </h2>
                    {schemasLoading ? (
                        <p className="text-ds-small text-ds-text-muted">加载中...</p>
                    ) : schemas.length === 0 ? (
                        <EmptyState title="暂无 Schema" description="当前库下没有采集到 Schema。"/>
                    ) : (
                        renderSchemaList()
                    )}
                </div>
            );
        }

        if (selectedNode.type === 'schema') {
            return (
                <div>
                    <h2 className="text-ds-title text-ds-text-primary font-semibold mb-ds-4 flex items-center gap-ds-2">
                        <HiOutlineTableCells size={20} className="text-ds-accent"/>
                        {selectedNode.schemaName} 表列表
                        <span
                            className="text-ds-caption text-ds-text-muted font-normal">（共 {tables.length} 张表）</span>
                    </h2>
                    {tablesLoading ? (
                        <p className="text-ds-small text-ds-text-muted">加载中...</p>
                    ) : tables.length === 0 ? (
                        <EmptyState title="暂无表" description="当前 Schema 没有采集到表结构。"/>
                    ) : (
                        renderTableList()
                    )}
                </div>
            );
        }

        return renderTableDetail();
    };

    return (
        <div className="h-full flex flex-col overflow-hidden">
            <div className="mb-ds-5 flex-shrink-0">
                <h1 className="text-ds-display text-ds-text-primary flex items-center gap-ds-2">
                    <HiOutlineBookOpen size={24} className="text-ds-accent"/>
                    元数据管理
                </h1>
                <p className="text-ds-small text-ds-text-muted mt-ds-1">浏览已采集的数据源库表结构，编辑表与字段注释</p>
            </div>

            <div
                className="flex-1 min-h-0 bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden flex">
                <div className="w-[260px] border-r border-ds-border-subtle flex flex-col">
                    <div className="px-ds-4 py-ds-3 border-b border-ds-border-subtle">
                        <h2 className="text-ds-small font-semibold text-ds-text-primary">数据目录</h2>
                    </div>
                    <MetadataTree
                        selectedNode={selectedNode}
                        onSelect={setSelectedNode}
                        expanded={expanded}
                        onExpandedChange={setExpanded}
                        onRootsLoaded={setHasRoots}
                        autoSelectFirst={!tableIdParam && !selectedNode}
                    />
                </div>

                <div className="flex-1 min-h-0 overflow-auto p-ds-6">
                    {renderRightPanel()}
                </div>
            </div>

            <PreviewModal
                open={previewOpen}
                loading={previewLoading}
                title={previewTitle}
                result={previewResult}
                onClose={() => setPreviewOpen(false)}
            />
        </div>
    );
}
