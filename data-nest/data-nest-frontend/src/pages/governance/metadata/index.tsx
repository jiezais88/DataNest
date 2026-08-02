import {useCallback, useEffect, useMemo, useState} from 'react';
import {useNavigate, useSearchParams} from 'react-router-dom';
import {Table} from 'antd';
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
import PreviewModal from '../../../components/PreviewModal';
import {HiOutlineBookOpen, HiOutlineCircleStack, HiOutlineEye, HiOutlineTableCells} from 'react-icons/hi2';
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

    const [previewOpen, setPreviewOpen] = useState(false);
    const [previewLoading, setPreviewLoading] = useState(false);
    const [previewResult, setPreviewResult] = useState<PreviewResult | null>(null);
    const [previewTitle, setPreviewTitle] = useState('');

    const canPreview = useHasRole(...ALL_ROLES);

    const resetDetail = useCallback(() => {
        setSelectedTable(null);
        setColumns([]);
        setHighlightedColumnId(null);
        setLineageRecords([]);
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
                    setLineageRecords(lineageResult.data || []);
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

    const handlePreviewTable = async (table: MetadataTable) => {
        if (!table.id || !canPreview) return;
        setPreviewOpen(true);
        setPreviewLoading(true);
        setPreviewTitle(`${table.databaseName}${table.schemaName && table.schemaName !== table.databaseName ? ` / ${table.schemaName}` : ''} / ${table.tableName}`);
        setPreviewResult(null);
        try {
            const result = await previewMetadataTable(table.id);
            if (result.data) {
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

    const databaseColumns = useMemo<ColumnsType<string>>(() => [
        {
            title: '库名',
            ellipsis: true,
            render: (db: string) => (
                <span className="text-ds-small text-ds-accent font-medium" title={db}>{db}</span>
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
                <span className="text-ds-small text-ds-accent font-medium" title={schema}>{schema}</span>
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
                <span className="text-ds-small text-ds-accent font-medium" title={v}>{v}</span>
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
                <span className="text-ds-small text-ds-text-secondary" title={v || '-'}>{v || '-'}</span>
            ),
        },
        {
            title: '创建人',
            dataIndex: 'createdByName',
            width: COL.USERNAME,
            ellipsis: true,
            render: (v?: string) => (
                <span className="text-ds-small text-ds-text-secondary" title={v || '-'}>{v || '-'}</span>
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
                <span className="text-ds-small text-ds-text-secondary" title={v || '-'}>{v || '-'}</span>
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
                <span className="text-ds-small text-ds-text-primary font-medium" title={v}>{v}</span>
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
                <span className="text-ds-small text-ds-text-secondary">{v ? 'YES' : 'NO'}</span>
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
        // Sprint 4：由 SQL/Python/同步任务自动注册的表展示来源信息（PRD §6.6.3）
        const sourceType = selectedTable.taskSourceType || selectedTable.sourceType;
        const isTaskRegistered = ['SYNC', 'SQL', 'PYTHON'].includes(sourceType || '') || selectedTable.sourceDagId != null;
        const SOURCE_TYPE_LABEL: Record<string, string> = {
            SYNC: '同步任务',
            SQL: 'DAG 任务（SQL 节点）',
            PYTHON: 'DAG 任务（Python 节点）',
        };
        const LINEAGE_TYPE_LABEL: Record<string, string> = {
            SQL: 'SQL',
            SYNC: '同步任务',
            PYTHON: 'Python',
        };
        const LINEAGE_TYPE_CLASS: Record<string, string> = {
            SQL: 'bg-ds-accent-light text-ds-accent',
            SYNC: 'bg-ds-success-light text-ds-success',
            PYTHON: 'bg-ds-warning-light text-ds-warning',
        };
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
                            <DsButton
                                variant="secondary"
                                onClick={() => handlePreviewTable(selectedTable)}
                            >
                                <HiOutlineEye size={16}/>
                                预览
                            </DsButton>
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
                        <span className="text-ds-text-primary inline-flex items-center gap-ds-1">
                            {selectedTable.datasourceName || selectedTable.databaseName}
                            {selectedTable.datasourceType && (
                                <DatabaseTypeIcon type={selectedTable.datasourceType} size={14} showLabel={false}/>
                            )}
                        </span>
                    </div>
                </div>

                {/* Sprint 4：数据来源 + 表级血缘两栏卡片（SQL/Python/同步任务自动注册的表） */}
                {isTaskRegistered && (
                    <div className="grid grid-cols-1 lg:grid-cols-2 gap-ds-5 mb-ds-6">
                        <div data-testid="metadata-source-card"
                             className="border border-ds-accent/30 bg-ds-accent-light rounded-ds-sm p-ds-4">
                            <h3 className="text-ds-small font-semibold text-ds-accent uppercase tracking-wider mb-ds-3">
                                数据来源
                            </h3>
                            <div className="grid grid-cols-[100px_1fr] gap-y-ds-2 text-ds-small">
                                <span className="text-ds-text-muted">来源类型</span>
                                <span className="text-ds-text-primary">
                                    {SOURCE_TYPE_LABEL[sourceType || ''] || sourceType || '—'}
                                </span>
                                {selectedTable.sourceDagName && (
                                    <>
                                        <span className="text-ds-text-muted">来源 DAG</span>
                                        <span className="text-ds-text-primary">{selectedTable.sourceDagName}</span>
                                    </>
                                )}
                                {selectedTable.sourceNodeName && (
                                    <>
                                        <span className="text-ds-text-muted">来源节点</span>
                                        <span className="text-ds-text-primary">{selectedTable.sourceNodeName}</span>
                                    </>
                                )}
                                {!selectedTable.sourceDagName && selectedTable.sourceTaskName && (
                                    <>
                                        <span className="text-ds-text-muted">来源任务</span>
                                        <span className="text-ds-text-primary">{selectedTable.sourceTaskName}</span>
                                    </>
                                )}
                            </div>
                            {selectedTable.sourceDagId != null && (
                                <div className="flex items-center gap-ds-3 mt-ds-3">
                                    <DsButton
                                        variant="secondary"
                                        onClick={() => navigate(`/engineering/dags/${selectedTable.sourceDagId}/edit`)}
                                    >
                                        查看 DAG
                                    </DsButton>
                                    <DsButton
                                        variant="secondary"
                                        onClick={() => navigate(`/engineering/dag-executions?dagId=${selectedTable.sourceDagId}&dagName=${encodeURIComponent(selectedTable.sourceDagName || '')}`)}
                                    >
                                        查看执行历史
                                    </DsButton>
                                </div>
                            )}
                        </div>

                        <div data-testid="metadata-lineage-card"
                             className="border border-ds-border-subtle bg-ds-bg-surface rounded-ds-sm p-ds-4">
                            <h3 className="text-ds-small font-semibold text-ds-text-secondary uppercase tracking-wider mb-ds-3">
                                表级血缘
                            </h3>
                            {lineageLoading ? (
                                <div
                                    className="flex items-center justify-center py-ds-6 text-ds-small text-ds-text-muted">
                                    加载中…
                                </div>
                            ) : lineageRecords.length === 0 ? (
                                <div className="text-ds-small text-ds-text-muted py-ds-2">
                                    暂无血缘记录
                                </div>
                            ) : (
                                <div className="flex flex-col">
                                    {lineageRecords.map((record, index) => (
                                        <div
                                            key={record.id}
                                            className={`py-ds-3 text-ds-small ${index !== lineageRecords.length - 1 ? 'border-b border-ds-border-subtle' : ''}`}
                                        >
                                            <div className="mb-ds-2">
                                                <div className="text-ds-text-secondary break-all"
                                                     title={record.sourceTable || '—'}>
                                                    {record.sourceTable || '—'}
                                                </div>
                                                <div className="flex items-start gap-ds-2 mt-0.5">
                                                    <span className="text-ds-text-muted flex-shrink-0">→</span>
                                                    <span className="text-ds-text-primary font-medium break-all flex-1"
                                                          title={record.targetTable}>
                                                        {record.targetTable}
                                                    </span>
                                                    <span
                                                        className={`flex-shrink-0 px-2 py-0.5 rounded-full text-ds-caption font-medium ${LINEAGE_TYPE_CLASS[record.lineageType] || LINEAGE_TYPE_CLASS.PYTHON}`}>
                                                        {LINEAGE_TYPE_LABEL[record.lineageType] || record.lineageType}
                                                    </span>
                                                </div>
                                                {record.dagId != null && (
                                                    <div className="flex items-center justify-end gap-ds-2">
                                                        <DsButton
                                                            variant="ghost"
                                                            className="h-7 px-2 py-0.5 text-ds-caption"
                                                            onClick={() => navigate(`/engineering/dags/${record.dagId}/edit`)}
                                                        >
                                                            查看 DAG
                                                        </DsButton>
                                                        <DsButton
                                                            variant="ghost"
                                                            className="h-7 px-2 py-0.5 text-ds-caption"
                                                            onClick={() => navigate(`/engineering/dag-executions?dagId=${record.dagId}&dagName=${encodeURIComponent(record.dagName || '')}`)}
                                                        >
                                                            执行历史
                                                        </DsButton>
                                                    </div>
                                                )}
                                            </div>
                                        </div>
                                    ))}
                                </div>
                            )}
                        </div>
                    </div>
                )}

                <div>
                    <h3 className="text-ds-small font-semibold text-ds-text-secondary uppercase tracking-wider border-b border-ds-border-subtle pb-ds-2 mb-ds-3">
                        字段列表
                    </h3>
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
        <div className="h-full flex flex-col overflow-hidden">
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
                        autoSelectFirst={false}
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
