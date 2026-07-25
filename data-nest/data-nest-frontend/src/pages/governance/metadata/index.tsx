import {useEffect, useState} from 'react';
import {useAuthStore} from '../../../store/useAuthStore';
import {
    getMetadataTable,
    listMetadataColumns,
    listMetadataTables,
    updateColumnComment,
    updateTableComment,
} from '../../../api/metadata';
import type {MetadataColumn, MetadataTable, MetadataTreeNode} from '../../../types/metadata';
import MetadataTree from './MetadataTree';
import EmptyState from '../../../components/EmptyState';
import {HiOutlinePencilSquare, HiOutlineTableCells} from 'react-icons/hi2';

export default function MetadataPage() {
    const {userInfo} = useAuthStore();
    const roles = userInfo?.roles || [];
    const canWrite = roles.includes('SUPER_ADMIN') || roles.includes('GOVERNANCE_ADMIN');

    const [selectedNode, setSelectedNode] = useState<MetadataTreeNode | null>(null);
    const [tables, setTables] = useState<MetadataTable[]>([]);
    const [tablesLoading, setTablesLoading] = useState(false);
    const [selectedTable, setSelectedTable] = useState<MetadataTable | null>(null);
    const [columns, setColumns] = useState<MetadataColumn[]>([]);
    const [columnsLoading, setColumnsLoading] = useState(false);
    const [editingComment, setEditingComment] = useState<{
        type: 'table' | 'column';
        id: string;
        value: string
    } | null>(null);

    useEffect(() => {
        if (!selectedNode) {
            setTables([]);
            setSelectedTable(null);
            setColumns([]);
            return;
        }
        if (selectedNode.type === 'table') {
            loadTableDetail(selectedNode);
        } else if (selectedNode.type === 'schema') {
            loadTables(selectedNode);
            setSelectedTable(null);
            setColumns([]);
        } else {
            setTables([]);
            setSelectedTable(null);
            setColumns([]);
        }
    }, [selectedNode]);

    const loadTables = async (node: MetadataTreeNode) => {
        const datasourceId = node.id.split('-')[1];
        const databaseName = node.databaseName!;
        const schemaName = node.schemaName!;
        setTablesLoading(true);
        try {
            const result = await listMetadataTables(datasourceId, databaseName, schemaName);
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

    const handleSaveTableComment = async () => {
        if (!editingComment || !selectedTable) return;
        const result = await updateTableComment(selectedTable.id, editingComment.value.trim());
        if (result.code === 200) {
            setSelectedTable((prev) => prev ? {...prev, manualComment: editingComment.value.trim()} : null);
            setEditingComment(null);
        }
    };

    const handleSaveColumnComment = async (column: MetadataColumn) => {
        if (!editingComment) return;
        const result = await updateColumnComment(column.id, editingComment.value.trim());
        if (result.code === 200) {
            setColumns((prev) => prev.map((c) => c.id === column.id ? {
                ...c,
                manualComment: editingComment.value.trim()
            } : c));
            setEditingComment(null);
        }
    };

    const startEditComment = (type: 'table' | 'column', id: string, value: string) => {
        if (!canWrite) return;
        setEditingComment({type, id, value});
    };

    const renderComment = (type: 'table' | 'column', id: string, value?: string) => {
        const isEditing = editingComment?.type === type && editingComment.id === id;
        if (isEditing) {
            return (
                <div className="flex items-center gap-ds-2">
                    <input
                        autoFocus
                        data-testid={type === 'table' ? 'metadata-table-comment' : `metadata-column-comment-${id}`}
                        value={editingComment.value}
                        onChange={(e) => setEditingComment({...editingComment, value: e.target.value})}
                        onBlur={() => type === 'table' ? handleSaveTableComment() : handleSaveColumnComment(columns.find((c) => c.id === id)!)}
                        onKeyDown={(e) => {
                            if (e.key === 'Enter') {
                                type === 'table' ? handleSaveTableComment() : handleSaveColumnComment(columns.find((c) => c.id === id)!);
                            }
                        }}
                        className="w-full px-ds-2 py-ds-1 text-ds-small bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm focus:outline-none focus-visible:border-ds-accent"
                    />
                </div>
            );
        }
        return (
            <div className="flex items-center gap-ds-2 group">
                <span className="text-ds-small text-ds-text-secondary truncate">{value || '-'}</span>
                {canWrite && (
                    <button
                        data-testid={`metadata-edit-comment-${type}-${id}`}
                        onClick={() => startEditComment(type, id, value || '')}
                        className="opacity-0 group-hover:opacity-100 p-1 text-ds-text-muted hover:text-ds-accent transition-opacity"
                        aria-label="编辑注释"
                    >
                        <HiOutlinePencilSquare size={14}/>
                    </button>
                )}
            </div>
        );
    };

    return (
        <div className="h-full flex flex-col overflow-hidden">
            <div className="mb-ds-5 flex-shrink-0">
                <h1 className="text-ds-display text-ds-text-primary">元数据管理</h1>
                <p className="text-ds-small text-ds-text-muted mt-ds-1">浏览已采集的数据源表结构，编辑表与字段注释</p>
            </div>

            <div
                className="flex-1 min-h-0 bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden flex">
                <div className="w-[260px] border-r border-ds-border-subtle flex flex-col">
                    <div className="px-ds-4 py-ds-3 border-b border-ds-border-subtle">
                        <h2 className="text-ds-small font-semibold text-ds-text-primary">数据目录</h2>
                    </div>
                    <MetadataTree selectedNode={selectedNode} onSelect={setSelectedNode}/>
                </div>

                <div className="flex-1 min-h-0 overflow-auto p-ds-6">
                    {!selectedNode && (
                        <EmptyState
                            title="选择目录节点"
                            description="在左侧选择库或 Schema，即可查看表列表；选择表可查看字段与注释。"
                        />
                    )}

                    {selectedNode && selectedNode.type !== 'table' && (
                        <div>
                            <h2 className="text-ds-title text-ds-text-primary font-semibold mb-ds-4 flex items-center gap-ds-2">
                                <HiOutlineTableCells size={20} className="text-ds-accent"/>
                                {selectedNode.name} 表列表
                            </h2>
                            {tablesLoading ? (
                                <p className="text-ds-small text-ds-text-muted">加载中...</p>
                            ) : tables.length === 0 ? (
                                <EmptyState title="暂无表" description="当前层级没有采集到表结构。"/>
                            ) : (
                                <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-ds-4">
                                    {tables.map((table) => (
                                        <button
                                            key={table.id}
                                            data-testid={`metadata-table-card-${table.id}`}
                                            onClick={() => setSelectedNode({
                                                id: `table-${table.id}`,
                                                type: 'table',
                                                name: table.tableName,
                                                databaseName: table.databaseName,
                                                schemaName: table.schemaName,
                                            })}
                                            className="text-left p-ds-4 bg-white border border-ds-border-subtle rounded-ds-md hover:border-ds-accent hover:shadow-ds-sm transition-all"
                                        >
                                            <h3 className="text-ds-body font-semibold text-ds-text-primary mb-ds-1">{table.tableName}</h3>
                                            <p className="text-ds-small text-ds-text-muted line-clamp-2">{table.tableComment || table.manualComment || '暂无注释'}</p>
                                        </button>
                                    ))}
                                </div>
                            )}
                        </div>
                    )}

                    {selectedNode && selectedNode.type === 'table' && selectedTable && (
                        <div>
                            <h2 className="text-ds-title text-ds-text-primary font-semibold mb-ds-1">{selectedTable.tableName}</h2>
                            <p className="text-ds-small text-ds-text-muted mb-ds-4">
                                {selectedTable.databaseName}{selectedTable.schemaName ? ` / ${selectedTable.schemaName}` : ''}
                            </p>

                            <div className="bg-ds-bg-hover rounded-ds-md p-ds-4 mb-ds-6">
                                <h3 className="text-ds-small font-semibold text-ds-text-secondary mb-ds-2">表注释</h3>
                                {renderComment('table', selectedTable.id, selectedTable.manualComment || selectedTable.tableComment)}
                            </div>

                            <h3 className="text-ds-small font-semibold text-ds-text-secondary mb-ds-3">字段列表</h3>
                            {columnsLoading ? (
                                <p className="text-ds-small text-ds-text-muted">加载中...</p>
                            ) : (
                                <div className="bg-white border border-ds-border-subtle rounded-ds-md overflow-hidden">
                                    <table className="w-full">
                                        <thead>
                                        <tr className="border-b border-ds-border-subtle bg-ds-bg-hover/80">
                                            <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-muted">字段名</th>
                                            <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-muted">数据类型</th>
                                            <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-muted">可空</th>
                                            <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-muted">默认值</th>
                                            <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-muted">注释</th>
                                        </tr>
                                        </thead>
                                        <tbody>
                                        {columns.map((column) => (
                                            <tr key={column.id}
                                                className="border-b border-ds-border-subtle last:border-0">
                                                <td className="px-ds-4 py-ds-3 text-ds-body text-ds-text-primary font-medium">{column.columnName}</td>
                                                <td className="px-ds-4 py-ds-3 text-ds-small text-ds-text-secondary">{column.dataType || '-'}</td>
                                                <td className="px-ds-4 py-ds-3 text-ds-small text-ds-text-secondary">{column.isNullable ? '是' : '否'}</td>
                                                <td className="px-ds-4 py-ds-3 text-ds-small text-ds-text-secondary">{column.columnDefault || '-'}</td>
                                                <td className="px-ds-4 py-ds-3 min-w-[240px]">
                                                    {renderComment('column', column.id, column.manualComment || column.columnComment)}
                                                </td>
                                            </tr>
                                        ))}
                                        </tbody>
                                    </table>
                                </div>
                            )}
                        </div>
                    )}
                </div>
            </div>
        </div>
    );
}
