import {useEffect, useState} from 'react';
import {HiOutlineXMark} from 'react-icons/hi2';
import {
    listMetadataDatabases,
    listMetadataSchemas,
    listMetadataTables,
    listMetadataTablesWithoutSchema
} from '../../../api/metadata';
import type {DataSource, DataSourceType} from '../../../types/datasource';
import type {MetadataTable} from '../../../types/metadata';

const TYPES_WITHOUT_SCHEMA: Set<DataSourceType> = new Set(['MYSQL', 'DORIS']);

interface DatasourcePreviewSelectorProps {
    datasource: DataSource | null;
    open: boolean;
    onClose: () => void;
    onPreview: (database: string, schema: string | undefined, table: string) => void;
}

export default function DatasourcePreviewSelector({
                                                      datasource,
                                                      open,
                                                      onClose,
                                                      onPreview
                                                  }: DatasourcePreviewSelectorProps) {
    const [databases, setDatabases] = useState<string[]>([]);
    const [databaseLoading, setDatabaseLoading] = useState(false);
    const [selectedDatabase, setSelectedDatabase] = useState<string | null>(null);

    const [schemas, setSchemas] = useState<string[]>([]);
    const [schemaLoading, setSchemaLoading] = useState(false);
    const [selectedSchema, setSelectedSchema] = useState<string | null>(null);

    const [tables, setTables] = useState<MetadataTable[]>([]);
    const [tableLoading, setTableLoading] = useState(false);
    const [selectedTable, setSelectedTable] = useState<MetadataTable | null>(null);

    useEffect(() => {
        if (!open || !datasource) return;
        setSelectedDatabase(null);
        setSelectedSchema(null);
        setSelectedTable(null);
        setSchemas([]);
        setTables([]);
        setDatabaseLoading(true);
        listMetadataDatabases(datasource.id)
            .then((res) => {
                if (res.code === 200) {
                    setDatabases(res.data);
                }
            })
            .finally(() => setDatabaseLoading(false));
    }, [open, datasource]);

    useEffect(() => {
        if (!datasource || !selectedDatabase) return;
        setSelectedSchema(null);
        setSelectedTable(null);
        setSchemas([]);
        setTables([]);
        if (TYPES_WITHOUT_SCHEMA.has(datasource.type)) {
            loadTablesWithoutSchema(datasource.id, selectedDatabase);
        } else {
            setSchemaLoading(true);
            listMetadataSchemas(datasource.id, selectedDatabase)
                .then((res) => {
                    if (res.code === 200) {
                        setSchemas(res.data);
                    }
                })
                .finally(() => setSchemaLoading(false));
        }
    }, [datasource, selectedDatabase]);

    useEffect(() => {
        if (!datasource || !selectedDatabase || !selectedSchema) return;
        setSelectedTable(null);
        loadTables(datasource.id, selectedDatabase, selectedSchema);
    }, [datasource, selectedDatabase, selectedSchema]);

    const loadTables = (datasourceId: string, database: string, schema: string) => {
        setTableLoading(true);
        listMetadataTables(datasourceId, database, schema)
            .then((res) => {
                if (res.code === 200) {
                    setTables(res.data);
                }
            })
            .finally(() => setTableLoading(false));
    };

    const loadTablesWithoutSchema = (datasourceId: string, database: string) => {
        setTableLoading(true);
        listMetadataTablesWithoutSchema(datasourceId, database)
            .then((res) => {
                if (res.code === 200) {
                    setTables(res.data);
                }
            })
            .finally(() => setTableLoading(false));
    };

    if (!open || !datasource) return null;

    const handleConfirm = () => {
        if (!selectedDatabase || !selectedTable) return;
        const schema = TYPES_WITHOUT_SCHEMA.has(datasource.type) ? selectedDatabase : (selectedSchema || undefined);
        onPreview(selectedDatabase, schema, selectedTable.tableName);
    };

    const renderColumn = (
        title: string,
        items: { id: string; label: string; selected: boolean }[],
        loading: boolean,
        onSelect: (id: string) => void,
    ) => (
        <div
            className="flex-1 min-w-[180px] border border-ds-border-subtle rounded-ds-md overflow-hidden flex flex-col bg-white">
            <div
                className="px-ds-3 py-ds-2 border-b border-ds-border-subtle bg-ds-bg-hover/80 text-ds-small font-semibold text-ds-text-secondary">
                {title}
            </div>
            <div className="flex-1 overflow-auto p-ds-1">
                {loading ? (
                    <p className="text-ds-caption text-ds-text-muted text-center py-ds-4">加载中...</p>
                ) : items.length === 0 ? (
                    <p className="text-ds-caption text-ds-text-muted text-center py-ds-4">暂无数据</p>
                ) : (
                    items.map((item) => (
                        <button
                            key={item.id}
                            onClick={() => onSelect(item.id)}
                            className={`w-full text-left px-ds-3 py-ds-2 text-ds-small rounded-ds-sm transition-colors ${
                                item.selected
                                    ? 'bg-ds-accent-light text-ds-accent font-medium'
                                    : 'text-ds-text-primary hover:bg-ds-bg-hover'
                            }`}
                        >
                            {item.label}
                        </button>
                    ))
                )}
            </div>
        </div>
    );

    const databaseItems = databases.map((db) => ({id: db, label: db, selected: selectedDatabase === db}));
    const schemaItems = schemas.map((s) => ({id: s, label: s, selected: selectedSchema === s}));
    const tableItems = tables.map((t) => ({id: t.id, label: t.tableName, selected: selectedTable?.id === t.id}));

    return (
        <div className="fixed inset-0 z-ds-dialog flex items-center justify-center p-ds-6">
            <div className="absolute inset-0 bg-black/30" onClick={onClose}/>
            <div className="relative bg-ds-bg-surface rounded-ds-md shadow-ds-xl flex flex-col w-[720px] max-h-[85vh]">
                <div className="flex items-center justify-between px-ds-5 py-ds-4 border-b border-ds-border-subtle">
                    <h3 className="text-ds-subhead text-ds-text-primary font-semibold">选择要预览的表</h3>
                    <button
                        onClick={onClose}
                        className="p-1 text-ds-text-muted hover:text-ds-text-primary hover:bg-ds-bg-hover rounded transition-colors"
                        aria-label="关闭"
                    >
                        <HiOutlineXMark size={20}/>
                    </button>
                </div>
                <div className="flex-1 overflow-auto p-ds-5">
                    <div className="flex gap-ds-3 h-[360px]">
                        {renderColumn('数据库', databaseItems, databaseLoading, setSelectedDatabase)}
                        {!TYPES_WITHOUT_SCHEMA.has(datasource.type) && (
                            renderColumn('Schema', schemaItems, schemaLoading, setSelectedSchema)
                        )}
                        {renderColumn('表', tableItems, tableLoading, (id) => {
                            const table = tables.find((t) => t.id === id) || null;
                            setSelectedTable(table);
                        })}
                    </div>
                </div>
                <div className="flex justify-end gap-ds-3 px-ds-5 py-ds-4 border-t border-ds-border-subtle">
                    <button
                        onClick={onClose}
                        className="px-ds-4 py-ds-2 text-ds-small font-semibold text-ds-text-secondary hover:bg-ds-bg-hover rounded-ds-sm transition-colors"
                    >
                        取消
                    </button>
                    <button
                        onClick={handleConfirm}
                        disabled={!selectedTable}
                        className="px-ds-4 py-ds-2 text-ds-small font-semibold bg-ds-accent text-white hover:bg-ds-accent-hover disabled:opacity-60 disabled:cursor-not-allowed rounded-ds-sm transition-colors"
                    >
                        预览
                    </button>
                </div>
            </div>
        </div>
    );
}
