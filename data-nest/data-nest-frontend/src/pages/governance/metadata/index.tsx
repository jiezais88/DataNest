import {useCallback, useEffect, useMemo, useState} from 'react';
import {useNavigate, useSearchParams} from 'react-router-dom';
import {Table, Tabs, Tooltip} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import {useHasRole} from '../../../hooks/useHasRole';
import {ALL_ROLES, GOVERNANCE_WRITE_ROLES} from '../../../constants/roles';
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
import {getLineageByTargetTable} from '../../../api/lineage';
import type {MetadataColumn, MetadataTable, MetadataTreeNode} from '../../../types/metadata';
import type {LineageRecord} from '../../../types/lineage';
import MetadataTree from './MetadataTree';
import EmptyState from '../../../components/EmptyState';
import {previewMetadataTable, type PreviewResult} from '../../../api/preview';
import {
    HiOutlineArrowPath,
    HiOutlineBookOpen,
    HiOutlineCircleStack,
    HiOutlineEye,
    HiOutlineInformationCircle,
    HiOutlineQueueList,
    HiOutlineShare,
    HiOutlineTableCells
} from 'react-icons/hi2';
import {formatDateTime} from '../../../utils/format';
import {COL} from '../../../constants/table';
import {isWithoutSchema} from '../../../constants/datasource';
import DatabaseTypeIcon from '../../../components/DatabaseTypeIcon';
import DsButton from '../../../components/DsButton';
import DsTableEmpty from '../../../components/DsTableEmpty';

const extractDatasourceId = (node: MetadataTreeNode) => {
    if (node.datasourceId) return node.datasourceId;
    if (node.type === 'datasource') return node.id.replace('ds-', '');
    return node.id.split('-')[1];
};

export default function MetadataPage() {
    const navigate = useNavigate();
    const [searchParams] = useSearchParams();
    const tableIdParam = searchParams.get('tableId');
    const columnIdParam = searchParams.get('columnId');
    const fromCompliance = searchParams.get('from') === 'compliance';
    const canWrite = useHasRole(...GOVERNANCE_WRITE_ROLES);

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
    const [lineageRecords, setLineageRecords] = useState<LineageRecord[]>([]);
    const [lineageLoading, setLineageLoading] = useState(false);
    const [editingCell, setEditingCell] = useState<{
        type: 'table-comment' | 'column-comment' | 'column-remark';
        id: string;
        value: string;
    } | null>(null);
    const [hasRoots, setHasRoots] = useState(false);

    const [previewLoading, setPreviewLoading] = useState(false);
    const [previewResult, setPreviewResult] = useState<PreviewResult | null>(null);
    const [previewError, setPreviewError] = useState<string | null>(null);
    const [detailTab, setDetailTab] = useState('basic');

    const canPreview = useHasRole(...ALL_ROLES);

    const resetDetail = useCallback(() => {
        setSelectedTable(null);
        setColumns([]);
        setHighlightedColumnId(null);
        setLineageRecords([]);
        setPreviewResult(null);
        setPreviewError(null);
    }, []);

    const resetLists = useCallback(() => {
        setDatabases([]);
        setSchemas([]);
        setTables([]);
    }, []);

    // 根据 URL query 参数自动选中指定表并展开祖先节点
    useEffect(() => {
        if (!tableIdParam) return;
        let cancelled = false;
        const autoSelect = async () => {
            try {
                const result = await getMetadataTable(tableIdParam);
                if (cancelled || !result.data) return;
                const table = result.data;
                const datasourceId = table.datasourceId;
                const databaseName = table.databaseName;
                const schemaName = table.schemaName;
                const withoutSchema = isWithoutSchema(table.datasourceType);
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

    const loadDatabases = useCallback(async (node: MetadataTreeNode) => {
        const datasourceId = extractDatasourceId(node);
        setDatabasesLoading(true);
        try {
            const result = await listMetadataDatabases(datasourceId);
            setDatabases(result.data);
        } finally {
            setDatabasesLoading(false);
        }
    }, []);

    const loadSchemas = useCallback(async (node: MetadataTreeNode) => {
        const datasourceId = extractDatasourceId(node);
        const databaseName = node.databaseName!;
        setSchemasLoading(true);
        try {
            const result = await listMetadataSchemas(datasourceId, databaseName);
            setSchemas(result.data);
        } finally {
            setSchemasLoading(false);
        }
    }, []);

    const loadTables = useCallback(async (node: MetadataTreeNode, schemaName: string) => {
        const datasourceId = extractDatasourceId(node);
        const databaseName = node.databaseName!;
        setTablesLoading(true);
        try {
            const result = isWithoutSchema(node.datasourceType)
                ? await listMetadataTablesWithoutSchema(datasourceId, databaseName)
                : await listMetadataTables(datasourceId, databaseName, schemaName);
            setTables(result.data);
        } finally {
            setTablesLoading(false);
        }
    }, []);

    const loadTableDetail = useCallback(async (node: MetadataTreeNode) => {
        const tableId = node.id.replace('table-', '');
        setColumnsLoading(true);
        setLineageLoading(true);
        try {
            const [tableResult, columnsResult] = await Promise.all([
                getMetadataTable(tableId),
                listMetadataColumns(tableId),
            ]);
            setSelectedTable(tableResult.data);
            setColumns(columnsResult.data);
            const table = tableResult.data;
            const sourceType = table.taskSourceType || table.sourceType;
            const isTaskRegistered = ['SYNC', 'SQL', 'PYTHON'].includes(sourceType || '') || table.sourceDagId != null;
            if (isTaskRegistered && table.databaseName && table.tableName) {
                const tableName = `${table.databaseName}.${table.tableName}`;
                try {
                    const lineageResult = await getLineageByTargetTable(tableName);
                    const records = lineageResult.data || [];
                    // 同一源表/目标表/血缘类型会随不同 DAG/多次执行重复落库，按 key 去重并保留最新记录
                    const seen = new Map<string, LineageRecord>();
                    for (const r of records) {
                        const key = [r.sourceTable, r.targetTable, r.lineageType].join('|');
                        const existing = seen.get(key);
                        if (!existing || (r.createdAt && existing.createdAt && r.createdAt > existing.createdAt)) {
                            seen.set(key, r);
                        }
                    }
                    setLineageRecords(
                        Array.from(seen.values()).sort((a, b) => (b.createdAt || '').localeCompare(a.createdAt || '')),
                    );
                } catch (err) {
                    console.error('load lineage failed', err);
                    setLineageRecords([]);
                }
            } else {
                setLineageRecords([]);
            }
        } finally {
            setColumnsLoading(false);
            setLineageLoading(false);
        }
    }, []);

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
    }, [selectedNode, resetLists, resetDetail, loadDatabases, loadSchemas, loadTables, loadTableDetail]);

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

    const startEdit = useCallback((type: 'table-comment' | 'column-comment' | 'column-remark', id: string, value: string) => {
        if (!canWrite) return;
        setEditingCell({type, id, value: value || ''});
    }, [canWrite]);

    const handleSaveTableComment = async () => {
        if (!editingCell || !selectedTable || editingCell.type !== 'table-comment') return;
        const value = editingCell.value.trim();
        await updateTableComment(selectedTable.id, value);
        setSelectedTable((prev) => prev ? {...prev, manualComment: value} : null);
        setTables((prev) => prev.map((t) => t.id === selectedTable.id ? {...t, manualComment: value} : t));
        setEditingCell(null);
    };

    const handleSaveColumnComment = useCallback(async (column: MetadataColumn) => {
        if (!editingCell || editingCell.type !== 'column-comment') return;
        const value = editingCell.value.trim();
        await updateColumnComment(column.id, value);
        setColumns((prev) => prev.map((c) => c.id === column.id ? {...c, manualComment: value} : c));
        setEditingCell(null);
    }, [editingCell]);

    const handleSaveColumnRemark = useCallback(async (column: MetadataColumn) => {
        if (!editingCell || editingCell.type !== 'column-remark') return;
        const value = editingCell.value.trim();
        await updateColumnRemark(column.id, value);
        setColumns((prev) => prev.map((c) => c.id === column.id ? {...c, remark: value} : c));
        setEditingCell(null);
    }, [editingCell]);

    const renderEditableCell = useCallback((
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
                    className={`${width} px-ds-2 py-0.5 text-ds-small bg-ds-bg-surface border border-ds-border-subtle rounded-ds-sm focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent`}
                />
            );
        }
        return (
            <div className="group flex items-center gap-ds-2 min-h-[24px]">
                <span className="text-ds-small text-ds-text-secondary truncate">{value || '-'}</span>
                {canWrite && (
                    <button
                        onClick={() => startEdit(type, id, value || '')}
                        className="opacity-0 group-hover:opacity-100 p-1.5 rounded text-ds-caption text-ds-text-muted hover:text-ds-accent hover:bg-ds-accent-light transition-colors"
                    >
                        编辑
                    </button>
                )}
            </div>
        );
    }, [editingCell, canWrite, startEdit]);

    const handlePreviewTable = useCallback(async (table: MetadataTable) => {
        if (!table.id || !canPreview) return;
        setPreviewLoading(true);
        setPreviewResult(null);
        setPreviewError(null);
        try {
            const result = await previewMetadataTable(table.id);
            if (result.data) {
                setPreviewResult({
                    columns: result.data.columns,
                    rows: result.data.rows,
                    rowCount: result.data.rowCount,
                });
            }
        } catch (err) {
            setPreviewError(err instanceof Error ? err.message : '预览数据加载失败');
        } finally {
            setPreviewLoading(false);
        }
    }, [canPreview]);

    // 切换到「数据预览」Tab 时自动加载预览数据（失败时不自动重试）
    useEffect(() => {
        if (detailTab === 'preview' && selectedTable?.id && canPreview && !previewResult && !previewLoading && !previewError) {
            handlePreviewTable(selectedTable);
        }
    }, [detailTab, selectedTable, canPreview, previewResult, previewLoading, previewError, handlePreviewTable]);

    const databaseColumns = useMemo<ColumnsType<string>>(() => [
        {
            title: '库名',
            ellipsis: true,
            render: (db: string) => (
                <Tooltip title={db} placement="top">
                    <span className="text-ds-small text-ds-accent font-medium" title={db}>{db}</span>
                </Tooltip>
            ),
        },
    ], []);

    const renderDatabaseList = () => (
        <div className="ds-table-card">
            <div className="ds-table-scroll">
                <Table<string>
                    dataSource={databases}
                    rowKey={(db) => db}
                    loading={databasesLoading}
                    pagination={false}
                    scroll={{x: 400}}
                    columns={databaseColumns}
                    className="prototype-table prototype-table-flush"
                    onRow={(db) => ({
                        className: 'cursor-pointer',
                        onClick: () => selectDatabase(db),
                    })}
                    locale={{
                        emptyText: (
                            <DsTableEmpty description="该数据源下没有采集到库。"/>
                        ),
                    }}
                />
            </div>
        </div>
    );

    const schemaColumns = useMemo<ColumnsType<string>>(() => [
        {
            title: 'Schema',
            ellipsis: true,
            render: (schema: string) => (
                <Tooltip title={schema} placement="top">
                    <span className="text-ds-small text-ds-accent font-medium" title={schema}>{schema}</span>
                </Tooltip>
            ),
        },
    ], []);

    const renderSchemaList = () => (
        <div className="ds-table-card">
            <div className="ds-table-scroll">
                <Table<string>
                    dataSource={schemas}
                    rowKey={(schema) => schema}
                    loading={schemasLoading}
                    pagination={false}
                    scroll={{x: 400}}
                    columns={schemaColumns}
                    className="prototype-table prototype-table-flush"
                    onRow={(schema) => ({
                        className: 'cursor-pointer',
                        onClick: () => selectSchema(schema),
                    })}
                    locale={{
                        emptyText: (
                            <DsTableEmpty description="当前库下没有采集到 Schema。"/>
                        ),
                    }}
                />
            </div>
        </div>
    );

    const tableColumns = useMemo<ColumnsType<MetadataTable>>(() => [
        {
            title: '表名',
            dataIndex: 'tableName',
            width: COL.NAME,
            ellipsis: true,
            render: (v: string) => (
                <Tooltip title={v} placement="top">
                    <span className="text-ds-small text-ds-accent font-medium" title={v}>{v}</span>
                </Tooltip>
            ),
        },
        {
            title: '字段数',
            dataIndex: 'columnCount',
            width: COL.COUNT_NORMAL,
            render: (v?: number) => (
                <span className="text-ds-small text-ds-text-secondary">{v ?? '-'}</span>
            ),
        },
        {
            title: '采集来源',
            dataIndex: 'sourceTaskName',
            width: COL.NAME,
            ellipsis: true,
            render: (v?: string) => (
                <Tooltip title={v || '-'} placement="top">
                    <span className="text-ds-small text-ds-text-secondary" title={v || '-'}>{v || '-'}</span>
                </Tooltip>
            ),
        },
        {
            title: '创建人',
            dataIndex: 'createdByName',
            width: COL.USERNAME,
            ellipsis: true,
            render: (v?: string) => (
                <Tooltip title={v || '-'} placement="top">
                    <span className="text-ds-small text-ds-text-secondary" title={v || '-'}>{v || '-'}</span>
                </Tooltip>
            ),
        },
        {
            title: '创建时间',
            dataIndex: 'createdAt',
            width: COL.DATETIME,
            render: (v?: string) => (
                <span
                    className="text-ds-small text-ds-text-secondary whitespace-nowrap">{v ? formatDateTime(v) : '-'}</span>
            ),
        },
        {
            title: '修改人',
            dataIndex: 'updatedByName',
            width: COL.USERNAME,
            ellipsis: true,
            render: (v?: string) => (
                <Tooltip title={v || '-'} placement="top">
                    <span className="text-ds-small text-ds-text-secondary" title={v || '-'}>{v || '-'}</span>
                </Tooltip>
            ),
        },
        {
            title: '修改时间',
            dataIndex: 'updatedAt',
            width: COL.DATETIME,
            render: (v?: string) => (
                <span
                    className="text-ds-small text-ds-text-secondary whitespace-nowrap">{v ? formatDateTime(v) : '-'}</span>
            ),
        },
    ], []);

    const renderTableList = (emptyText: string) => (
        <div className="ds-table-card">
            <div className="ds-table-scroll">
                <Table<MetadataTable>
                    dataSource={tables}
                    rowKey="id"
                    loading={tablesLoading}
                    pagination={false}
                    scroll={{x: 1090}}
                    columns={tableColumns}
                    className="prototype-table prototype-table-flush"
                    onRow={(table) => ({
                        className: 'cursor-pointer',
                        onClick: () => selectTable(table),
                    })}
                    locale={{
                        emptyText: (
                            <DsTableEmpty description={emptyText}/>
                        ),
                    }}
                />
            </div>
        </div>
    );

    const columnColumns = useMemo<ColumnsType<MetadataColumn>>(() => [
        {
            title: '字段名',
            dataIndex: 'columnName',
            ellipsis: true,
            render: (v: string) => (
                <Tooltip title={v} placement="top">
                    <span className="text-ds-small text-ds-text-primary font-medium" title={v}>{v}</span>
                </Tooltip>
            ),
        },
        {
            title: '数据类型',
            dataIndex: 'dataType',
            render: (v?: string) => (
                <span className="text-ds-small text-ds-text-secondary whitespace-nowrap">{v || '-'}</span>
            ),
        },
        {
            title: '中文注释',
            ellipsis: true,
            render: (_, column) => {
                const commentValue = column.manualComment || column.columnComment || '-';
                return (
                    <div title={commentValue}>
                        {renderEditableCell('column-comment', column.id, column.manualComment || column.columnComment, () => handleSaveColumnComment(column))}
                    </div>
                );
            },
        },
        {
            title: '是否可空',
            dataIndex: 'nullable',
            render: (v: boolean) => (
                <span className="text-ds-small text-ds-text-secondary">{v ? '是' : '否'}</span>
            ),
        },
        {
            title: '备注',
            ellipsis: true,
            render: (_, column) => {
                const remarkValue = column.remark || '-';
                return (
                    <div title={remarkValue}>
                        {renderEditableCell('column-remark', column.id, column.remark, () => handleSaveColumnRemark(column))}
                    </div>
                );
            },
        },
    ], [handleSaveColumnComment, handleSaveColumnRemark, renderEditableCell]);

    const renderTableDetail = () => {
        if (!selectedTable) return null;
        const sourceType = selectedTable.taskSourceType || selectedTable.sourceType;
        const isTaskRegistered = ['SYNC', 'SQL', 'PYTHON'].includes(sourceType || '') || selectedTable.sourceDagId != null;
        const SOURCE_TYPE_LABEL: Record<string, string> = {
            SYNC: '同步任务',
            SQL: 'DAG 任务（SQL 节点）',
            PYTHON: 'DAG 任务（Python 节点）',
        };
        const LINEAGE_TYPE_LABEL: Record<string, string> = {
            SQL: 'SQL 节点',
            SYNC: '同步任务',
            PYTHON: 'Python 节点',
        };
        const LINEAGE_TYPE_CLASS: Record<string, string> = {
            SQL: 'bg-ds-accent-light text-ds-accent',
            SYNC: 'bg-ds-success-light text-ds-success',
            PYTHON: 'bg-ds-warning-light text-ds-warning',
        };
        const fullTableName = `${selectedTable.databaseName}.${selectedTable.tableName}`;

        const renderBasicInfo = () => (
            <div className="space-y-ds-5">
                <div className="grid grid-cols-1 lg:grid-cols-2 gap-ds-5">
                    {/* 表基础信息 */}
                    <div className="border border-ds-border-subtle bg-ds-bg-surface rounded-ds-sm p-ds-4">
                        <h3 className="text-ds-small font-semibold text-ds-text-secondary uppercase tracking-wider mb-ds-4 flex items-center gap-ds-2">
                            <HiOutlineTableCells size={16} className="text-ds-accent"/>
                            表基础信息
                        </h3>
                        <div className="grid grid-cols-[100px_1fr] gap-y-ds-3 text-ds-small">
                            <span className="text-ds-text-muted">表全名</span>
                            <Tooltip title={fullTableName} placement="top">
                                <span className="text-ds-text-primary font-medium"
                                      title={fullTableName}>{fullTableName}</span>
                            </Tooltip>

                            <span className="text-ds-text-muted">表名</span>
                            <Tooltip title={selectedTable.tableName} placement="top">
                                <span className="text-ds-text-primary font-medium"
                                      title={selectedTable.tableName}>{selectedTable.tableName}</span>
                            </Tooltip>

                            <span className="text-ds-text-muted">注释</span>
                            <div>
                                {renderEditableCell('table-comment', selectedTable.id, selectedTable.manualComment || selectedTable.tableComment, handleSaveTableComment)}
                            </div>

                            <span className="text-ds-text-muted">字段数</span>
                            <span className="text-ds-text-primary">{selectedTable.columnCount ?? '-'}</span>

                            <span className="text-ds-text-muted">数据源</span>
                            <span className="text-ds-text-primary inline-flex items-center gap-ds-1">
                                {selectedTable.datasourceName || selectedTable.databaseName}
                                {selectedTable.datasourceType && (
                                    <DatabaseTypeIcon type={selectedTable.datasourceType} size={14} showLabel={false}/>
                                )}
                            </span>

                            {selectedTable.schemaName && selectedTable.schemaName !== selectedTable.databaseName && (
                                <>
                                    <span className="text-ds-text-muted">Schema</span>
                                    <span className="text-ds-text-primary">{selectedTable.schemaName}</span>
                                </>
                            )}
                        </div>
                    </div>

                    {/* 血缘关系 */}
                    <div className="border border-ds-border-subtle bg-ds-bg-surface rounded-ds-sm p-ds-4">
                        <h3 className="text-ds-small font-semibold text-ds-text-secondary uppercase tracking-wider mb-ds-4 flex items-center gap-ds-2">
                            <HiOutlineShare size={16} className="text-ds-accent"/>
                            血缘关系
                        </h3>
                        {lineageLoading ? (
                            <div className="flex items-center justify-center py-ds-6 text-ds-small text-ds-text-muted">
                                加载中…
                            </div>
                        ) : lineageRecords.length === 0 ? (
                            <div className="text-ds-small text-ds-text-muted py-ds-2">
                                暂无血缘记录
                            </div>
                        ) : (
                            <div className="space-y-ds-4">
                                <div className="grid grid-cols-2 gap-ds-3">
                                    <div className="border border-ds-border-subtle rounded-ds-sm p-ds-3 text-center">
                                        <div className="text-ds-display font-semibold text-ds-accent">
                                            {new Set(lineageRecords.filter(r => r.targetTable === fullTableName).map(r => r.sourceTable)).size}
                                        </div>
                                        <div className="text-ds-caption text-ds-text-muted mt-0.5">直接上游表</div>
                                    </div>
                                    <div className="border border-ds-border-subtle rounded-ds-sm p-ds-3 text-center">
                                        <div className="text-ds-display font-semibold text-ds-accent">
                                            {new Set(lineageRecords.filter(r => r.sourceTable === fullTableName).map(r => r.targetTable)).size}
                                        </div>
                                        <div className="text-ds-caption text-ds-text-muted mt-0.5">直接下游表</div>
                                    </div>
                                </div>

                                <div>
                                    <h4 className="text-ds-caption text-ds-text-muted mb-ds-2">最近血缘记录</h4>
                                    <div className="space-y-ds-2">
                                        {lineageRecords.slice(0, 5).map((record, index) => (
                                            <div key={record.id || index}
                                                 className="flex items-center justify-between gap-ds-2 text-ds-small">
                                                <div className="min-w-0 flex-1 truncate">
                                                    <Tooltip title={record.sourceTable || '—'} placement="top">
                                                        <span className="text-ds-text-primary"
                                                              title={record.sourceTable || '—'}>{record.sourceTable || '—'}</span>
                                                    </Tooltip>
                                                    <span className="text-ds-text-muted mx-ds-1">→</span>
                                                    <Tooltip title={record.targetTable || '—'} placement="top">
                                                        <span className="text-ds-text-primary"
                                                              title={record.targetTable || '—'}>{record.targetTable || '—'}</span>
                                                    </Tooltip>
                                                </div>
                                                <span
                                                    className={`px-2 py-0.5 rounded-full text-ds-caption font-medium whitespace-nowrap ${LINEAGE_TYPE_CLASS[record.lineageType] || LINEAGE_TYPE_CLASS.PYTHON}`}>
                                                    {LINEAGE_TYPE_LABEL[record.lineageType] || record.lineageType}
                                                </span>
                                            </div>
                                        ))}
                                    </div>
                                </div>

                            </div>
                        )}
                    </div>
                </div>

                {isTaskRegistered && (
                    <div data-testid="metadata-source-card"
                         className="border border-ds-border-subtle bg-ds-bg-surface rounded-ds-sm p-ds-4">
                        <h3 className="text-ds-small font-semibold text-ds-text-secondary uppercase tracking-wider mb-ds-4 flex items-center gap-ds-2">
                            <HiOutlineCircleStack size={16} className="text-ds-accent"/>
                            数据来源
                        </h3>
                        <div className="flex flex-col items-center text-center py-ds-2">
                            <span
                                className={`inline-flex items-center gap-ds-2 px-ds-4 py-ds-2 rounded-ds-md text-ds-subhead font-bold mb-ds-3 ${LINEAGE_TYPE_CLASS[sourceType || ''] || 'bg-ds-bg-hover text-ds-text-secondary'}`}>
                                <HiOutlineCircleStack size={20}/>
                                {SOURCE_TYPE_LABEL[sourceType || ''] || sourceType || '—'}
                            </span>

                            {selectedTable.sourceDagName ? (
                                <>
                                    <Tooltip title={selectedTable.sourceDagName} placement="top">
                                        <div className="text-ds-title text-ds-text-primary font-semibold mb-ds-1"
                                             title={selectedTable.sourceDagName}>
                                            {selectedTable.sourceDagName}
                                        </div>
                                    </Tooltip>
                                    {selectedTable.sourceNodeName && (
                                        <div className="text-ds-small text-ds-text-muted mb-ds-4">
                                            节点：{selectedTable.sourceNodeName}
                                        </div>
                                    )}
                                </>
                            ) : selectedTable.sourceTaskName ? (
                                <Tooltip title={selectedTable.sourceTaskName} placement="top">
                                    <div className="text-ds-title text-ds-text-primary font-semibold mb-ds-4"
                                         title={selectedTable.sourceTaskName}>
                                        {selectedTable.sourceTaskName}
                                    </div>
                                </Tooltip>
                            ) : (
                                <div className="text-ds-small text-ds-text-muted mb-ds-4">
                                    未识别具体来源
                                </div>
                            )}

                            {selectedTable.sourceDagId != null && (
                                <div className="flex items-center gap-ds-3">
                                    <DsButton
                                        variant="secondary"
                                        onClick={() => navigate(`/engineering/dags/${selectedTable.sourceDagId}/edit`, {
                                            state: {from: `/governance/metadata?tableId=${selectedTable.id}`},
                                        })}
                                    >
                                        查看 DAG
                                    </DsButton>
                                    <DsButton
                                        variant="secondary"
                                        onClick={() => navigate(`/engineering/dag-executions?dagId=${selectedTable.sourceDagId}&dagName=${encodeURIComponent(selectedTable.sourceDagName || '')}`, {
                                            state: {from: `/governance/metadata?tableId=${selectedTable.id}`},
                                        })}
                                    >
                                        查看执行历史
                                    </DsButton>
                                </div>
                            )}
                        </div>
                    </div>
                )}
            </div>
        );

        const renderColumnList = () => (
            <div className="ds-table-card">
                <div className="ds-table-scroll">
                    <Table<MetadataColumn>
                        dataSource={columns}
                        rowKey={(column) => column.id || column.columnName}
                        loading={columnsLoading}
                        pagination={false}
                        scroll={{x: 850}}
                        columns={columnColumns}
                        className="prototype-table prototype-table-flush"
                        onRow={(column) => ({
                            className: column.id && highlightedColumnId && column.id === highlightedColumnId
                                ? 'bg-ds-warning/10'
                                : '',
                        })}
                        locale={{
                            emptyText: (
                                <DsTableEmpty description="暂无字段"/>
                            ),
                        }}
                    />
                </div>
            </div>
        );

        const renderLineage = () => (
            <div className="space-y-ds-4">
                <div className="flex items-center justify-between">
                    <h3 className="text-ds-small font-semibold text-ds-text-secondary uppercase tracking-wider">
                        表级血缘
                    </h3>
                    <DsButton
                        variant="secondary"
                        onClick={() => navigate(
                            `/governance/metadata/lineage?tableId=${selectedTable.id}&tableName=${encodeURIComponent(fullTableName)}`,
                        )}
                    >
                        <HiOutlineShare size={16}/>
                        查看完整血缘图谱
                    </DsButton>
                </div>
                {lineageLoading ? (
                    <div className="flex items-center justify-center py-ds-6 text-ds-small text-ds-text-muted">
                        加载中…
                    </div>
                ) : lineageRecords.length === 0 ? (
                    <div className="text-ds-small text-ds-text-muted py-ds-2">
                        暂无血缘记录
                    </div>
                ) : (
                    <div className="flex flex-col border border-ds-border-subtle bg-ds-bg-surface rounded-ds-sm p-ds-4">
                        {lineageRecords.map((record, index) => (
                            <div
                                key={record.id}
                                className={`py-ds-3 text-ds-small ${index !== lineageRecords.length - 1 ? 'border-b border-ds-border-subtle' : ''}`}
                            >
                                <div className="flex items-start justify-between gap-ds-3">
                                    <div className="min-w-0 flex-1">
                                        <div className="text-ds-text-primary font-medium break-all"
                                             title={record.sourceTable || '—'}>
                                            {record.sourceTable || '—'}
                                        </div>
                                        <div className="text-ds-caption text-ds-text-secondary mt-0.5">
                                            → {record.targetTable}
                                        </div>
                                    </div>
                                    <div className="flex flex-col items-end gap-ds-1 shrink-0">
                                        <span
                                            className={`px-2 py-0.5 rounded-full text-ds-caption font-medium ${LINEAGE_TYPE_CLASS[record.lineageType] || LINEAGE_TYPE_CLASS.PYTHON}`}>
                                            {LINEAGE_TYPE_LABEL[record.lineageType] || record.lineageType}
                                        </span>
                                        {record.dagId != null && (
                                            <div className="flex items-center gap-ds-1">
                                                <DsButton
                                                    variant="ghost"
                                                    className="h-6 px-1.5 py-0 text-ds-caption"
                                                    onClick={() => navigate(`/engineering/dags/${record.dagId}/edit`, {
                                                        state: {from: `/governance/metadata?tableId=${selectedTable!.id}`},
                                                    })}
                                                >
                                                    查看 DAG
                                                </DsButton>
                                                <DsButton
                                                    variant="ghost"
                                                    className="h-6 px-1.5 py-0 text-ds-caption"
                                                    onClick={() => navigate(`/engineering/dag-executions?dagId=${record.dagId}&dagName=${encodeURIComponent(record.dagName || '')}`, {
                                                        state: {from: `/governance/metadata?tableId=${selectedTable!.id}`},
                                                    })}
                                                >
                                                    执行历史
                                                </DsButton>
                                            </div>
                                        )}
                                    </div>
                                </div>
                            </div>
                        ))}
                    </div>
                )}
            </div>
        );

        const renderPreview = () => (
            <div>
                {previewLoading ? (
                    <div className="flex items-center justify-center py-ds-10 text-ds-small text-ds-text-muted">
                        加载预览数据中…
                    </div>
                ) : previewError ? (
                    <div className="flex flex-col items-center justify-center py-ds-10 gap-ds-4">
                        <span className="text-ds-small text-ds-danger">{previewError}</span>
                        {canPreview && selectedTable && (
                            <DsButton
                                variant="secondary"
                                onClick={() => handlePreviewTable(selectedTable)}
                            >
                                <HiOutlineArrowPath size={16}/>
                                重新加载
                            </DsButton>
                        )}
                    </div>
                ) : previewResult ? (
                    <div>
                        <div className="text-ds-small text-ds-text-secondary mb-ds-2">
                            共 {previewResult.rowCount} 行（最多展示 100 行）
                        </div>
                        <div className="border border-ds-border-subtle rounded-ds-sm overflow-auto">
                            <table className="w-full text-left">
                                <thead className="bg-ds-bg-hover sticky top-0">
                                <tr>
                                    {previewResult.columns.map((col) => (
                                        <th key={col}
                                            className="px-ds-3 py-ds-2 text-ds-caption text-ds-text-primary font-semibold whitespace-nowrap">
                                            {col}
                                        </th>
                                    ))}
                                </tr>
                                </thead>
                                <tbody>
                                {previewResult.rows.map((row, idx) => (
                                    <tr key={idx} className="border-t border-ds-border-subtle">
                                        {previewResult.columns.map((col) => (
                                            <td key={col}
                                                className="px-ds-3 py-ds-2 text-ds-small text-ds-text-secondary whitespace-nowrap">
                                                {row[col] === null || row[col] === undefined ? 'NULL' : String(row[col])}
                                            </td>
                                        ))}
                                    </tr>
                                ))}
                                </tbody>
                            </table>
                        </div>
                    </div>
                ) : (
                    <div className="flex items-center justify-center py-ds-10 text-ds-small text-ds-text-muted">
                        暂无预览数据
                    </div>
                )}
            </div>
        );

        return (
            <div>
                <div className="text-ds-small text-ds-text-muted mb-ds-4">
                    {selectedTable.databaseName}
                    {selectedTable.schemaName && selectedTable.schemaName !== selectedTable.databaseName && (
                        <span> / <span className="text-ds-text-primary font-semibold">{selectedTable.schemaName}</span></span>
                    )}
                    <span> / </span>
                    <Tooltip title={selectedTable.tableName} placement="top">
                        <span className="text-ds-text-primary font-semibold">{selectedTable.tableName}</span>
                    </Tooltip>
                </div>

                <Tabs
                    activeKey={detailTab}
                    onChange={setDetailTab}
                    items={[
                        {
                            key: 'basic',
                            label: (
                                <span className="flex items-center gap-ds-1">
                                    <HiOutlineInformationCircle size={14}/>
                                    基础信息
                                </span>
                            ),
                            children: renderBasicInfo(),
                        },
                        {
                            key: 'columns',
                            label: (
                                <span className="flex items-center gap-ds-1">
                                    <HiOutlineQueueList size={14}/>
                                    字段列表
                                </span>
                            ),
                            children: renderColumnList(),
                        },
                        {
                            key: 'lineage',
                            label: (
                                <span className="flex items-center gap-ds-1">
                                    <HiOutlineShare size={14}/>
                                    血缘图谱
                                </span>
                            ),
                            children: renderLineage(),
                        },
                        {
                            key: 'preview',
                            label: (
                                <span className="flex items-center gap-ds-1">
                                    <HiOutlineEye size={14}/>
                                    数据预览
                                </span>
                            ),
                            children: renderPreview(),
                        },
                    ]}
                />
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
                            <DsButton
                                variant="primary"
                                onClick={() => navigate('/governance/collect-tasks')}
                            >
                                前往元数据采集任务
                            </DsButton>
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
                    {renderDatabaseList()}
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
                        {renderTableList('当前库没有采集到表结构。')}
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
                    {renderSchemaList()}
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
                    {renderTableList('当前 Schema 没有采集到表结构。')}
                </div>
            );
        }

        return renderTableDetail();
    };

    return (
        <div className="h-[calc(100vh-9rem)] flex flex-col overflow-hidden">
            <div className="mb-ds-5 flex-shrink-0 flex items-start justify-between">
                <div>
                    <h1 className="text-ds-display text-ds-text-primary flex items-center gap-ds-2">
                        <HiOutlineBookOpen size={24} className="text-ds-accent"/>
                        元数据管理
                    </h1>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1">浏览已采集的数据源库表结构，编辑表与字段注释</p>
                </div>
                {fromCompliance && (
                    <DsButton
                        variant="secondary"
                        onClick={() => navigate('/governance/data-standards?from=compliance')}
                    >
                        返回合规检查结果
                    </DsButton>
                )}
            </div>

            <div
                className="flex-1 min-h-0 bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden flex">
                <div className="w-[260px] border-r border-ds-border-subtle flex flex-col min-h-0 overflow-hidden">
                    <div className="px-ds-4 py-ds-3 border-b border-ds-border-subtle flex-shrink-0">
                        <h2 className="text-ds-small font-semibold text-ds-text-primary">数据目录</h2>
                    </div>
                    <div className="flex-1 min-h-0 overflow-hidden">
                        <MetadataTree
                            selectedNode={selectedNode}
                            onSelect={setSelectedNode}
                            expanded={expanded}
                            onExpandedChange={setExpanded}
                            onRootsLoaded={setHasRoots}
                            autoSelectFirst={false}
                        />
                    </div>
                </div>

                <div className="flex-1 min-h-0 overflow-auto p-ds-6">
                    {renderRightPanel()}
                </div>
            </div>

        </div>
    );
}
