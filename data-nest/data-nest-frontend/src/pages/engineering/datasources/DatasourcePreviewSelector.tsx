import {useEffect, useState} from 'react';
import {
    listMetadataDatabases,
    listMetadataSchemas,
    listMetadataTables,
    listMetadataTablesWithoutSchema
} from '../../../api/metadata';
import type {DataSource} from '../../../types/datasource';
import {DB_TYPES_WITHOUT_SCHEMA} from '../../../constants/datasource';
import type {MetadataTable} from '../../../types/metadata';
import DsButton from '../../../components/DsButton';
import DsModal from '../../../components/DsModal';


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
                setDatabases(res.data);
            })
            .finally(() => setDatabaseLoading(false));
    }, [open, datasource]);

    useEffect(() => {
        if (!datasource || !selectedDatabase) return;
        setSelectedSchema(null);
        setSelectedTable(null);
        setSchemas([]);
        setTables([]);
        if (DB_TYPES_WITHOUT_SCHEMA.has(datasource.type)) {
            loadTablesWithoutSchema(datasource.id, selectedDatabase);
        } else {
            setSchemaLoading(true);
            listMetadataSchemas(datasource.id, selectedDatabase)
                .then((res) => {
                    setSchemas(res.data);
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
                setTables(res.data);
            })
            .finally(() => setTableLoading(false));
    };

    const loadTablesWithoutSchema = (datasourceId: string, database: string) => {
        setTableLoading(true);
        listMetadataTablesWithoutSchema(datasourceId, database)
            .then((res) => {
                setTables(res.data);
            })
            .finally(() => setTableLoading(false));
    };

    if (!open || !datasource) return null;

    const handleConfirm = () => {
        if (!selectedDatabase || !selectedTable) return;
        const schema = DB_TYPES_WITHOUT_SCHEMA.has(datasource.type) ? selectedDatabase : (selectedSchema || undefined);
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
        <DsModal
            open={open}
            onClose={onClose}
            title="选择要预览的表"
            width="w-[720px]"
            bordered
            footer={
                <>
                    <DsButton variant="ghost" onClick={onClose}>
                        取消
                    </DsButton>
                    <DsButton
                        onClick={handleConfirm}
                        disabled={!selectedTable}
                    >
                        预览
                    </DsButton>
                </>
            }
        >
            <div className="flex gap-ds-3 h-[360px]">
                {renderColumn('数据库', databaseItems, databaseLoading, setSelectedDatabase)}
                {!DB_TYPES_WITHOUT_SCHEMA.has(datasource.type) && (
                    renderColumn('Schema', schemaItems, schemaLoading, setSelectedSchema)
                )}
                {renderColumn('表', tableItems, tableLoading, (id) => {
                    const table = tables.find((t) => t.id === id) || null;
                    setSelectedTable(table);
                })}
            </div>
        </DsModal>
    );
}
