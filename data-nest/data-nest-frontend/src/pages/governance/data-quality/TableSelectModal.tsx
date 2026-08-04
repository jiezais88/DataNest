import {useEffect, useState} from 'react';
import DsButton from '../../../components/DsButton';
import DsModal from '../../../components/DsModal';
import {
    listMetadataDatabases,
    listMetadataSchemas,
    listMetadataTables,
    listMetadataTablesWithoutSchema,
    listMetadataDatasourceIds,
} from '../../../api/metadata';
import {isWithoutSchema} from '../../../constants/datasource';
import type {MetadataDatasource, MetadataTable} from '../../../types/metadata';

interface TableSelectModalProps {
    open: boolean;
    onClose: () => void;
    /** 默认数据源（可为空；用于默认选中 + lockDatasource 时锁定） */
    defaultDatasourceId?: string;
    /** 是否锁定数据源（true 时数据源下拉禁用，仅可在该数据源下选库/表） */
    lockDatasource?: boolean;
    /** 已选表（用于高亮，多选场景） */
    selectedTables?: MetadataTable[];
    /** 是否多选，默认 false */
    multiple?: boolean;
    onConfirm: (tables: MetadataTable[]) => void;
}

/**
 * 选表 Modal：数据源 → 数据库 → Schema → 表 的层级选择。
 * 表支持多选；确认时回传选中的 MetadataTable 列表。
 */
export default function TableSelectModal({
                                             open,
                                             onClose,
                                             defaultDatasourceId,
                                             lockDatasource = false,
                                             selectedTables = [],
                                             multiple = false,
                                             onConfirm,
                                         }: TableSelectModalProps) {
    const [datasources, setDatasources] = useState<MetadataDatasource[]>([]);
    const [datasourceId, setDatasourceId] = useState<string>('');

    const [databases, setDatabases] = useState<string[]>([]);
    const [databaseLoading, setDatabaseLoading] = useState(false);
    const [selectedDatabase, setSelectedDatabase] = useState<string | null>(null);

    const [schemas, setSchemas] = useState<string[]>([]);
    const [schemaLoading, setSchemaLoading] = useState(false);
    const [selectedSchema, setSelectedSchema] = useState<string | null>(null);

    const [tables, setTables] = useState<MetadataTable[]>([]);
    const [tableLoading, setTableLoading] = useState(false);
    const [picked, setPicked] = useState<MetadataTable[]>([]);

    const datasourceType = datasources.find((d) => String(d.id) === String(datasourceId))?.type;
    const noSchema = isWithoutSchema(datasourceType);

    // 打开时加载数据源列表 + 恢复选中
    useEffect(() => {
        if (!open) return;
        listMetadataDatasourceIds()
            .then((res) => {
                const list = res.data || [];
                setDatasources(list);
                let initial = '';
                // 锁定模式：强制使用 defaultDatasourceId（若在列表中）
                if (lockDatasource) {
                    initial = defaultDatasourceId && list.some((d) => String(d.id) === String(defaultDatasourceId))
                        ? String(defaultDatasourceId)
                        : '';
                } else if (defaultDatasourceId && list.some((d) => String(d.id) === String(defaultDatasourceId))) {
                    initial = String(defaultDatasourceId);
                } else if (list.length === 1) {
                    initial = String(list[0].id);
                }
                setDatasourceId(initial);
            })
            .catch(() => setDatasources([]));
        setPicked(selectedTables);
        setSelectedDatabase(null);
        setSelectedSchema(null);
        setSchemas([]);
        setTables([]);
        setDatabases([]);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [open]);

    // 数据源变化时加载数据库
    useEffect(() => {
        if (!open || !datasourceId) return;
        setSelectedDatabase(null);
        setSelectedSchema(null);
        setSchemas([]);
        setTables([]);
        setDatabaseLoading(true);
        listMetadataDatabases(datasourceId)
            .then((res) => setDatabases(res.data || []))
            .finally(() => setDatabaseLoading(false));
    }, [open, datasourceId]);

    // 选数据库后：无 Schema 类型直接加载表，否则加载 Schema
    useEffect(() => {
        if (!open || !datasourceId || !selectedDatabase) return;
        setSelectedSchema(null);
        setSchemas([]);
        setTables([]);
        if (noSchema) {
            setTableLoading(true);
            listMetadataTablesWithoutSchema(datasourceId, selectedDatabase)
                .then((res) => setTables(res.data || []))
                .finally(() => setTableLoading(false));
        } else {
            setSchemaLoading(true);
            listMetadataSchemas(datasourceId, selectedDatabase)
                .then((res) => setSchemas(res.data || []))
                .finally(() => setSchemaLoading(false));
        }
    }, [open, datasourceId, selectedDatabase, noSchema]);

    // 选 Schema 后加载表
    useEffect(() => {
        if (!open || !datasourceId || !selectedDatabase || !selectedSchema || noSchema) return;
        setTableLoading(true);
        listMetadataTables(datasourceId, selectedDatabase, selectedSchema)
            .then((res) => setTables(res.data || []))
            .finally(() => setTableLoading(false));
    }, [open, datasourceId, selectedDatabase, selectedSchema, noSchema]);

    const isPicked = (id: string) => picked.some((t) => String(t.id) === String(id));

    const toggleTable = (table: MetadataTable) => {
        setPicked((prev) => {
            if (multiple) {
                return isPicked(table.id) ? prev.filter((t) => String(t.id) !== String(table.id)) : [...prev, table];
            }
            return [table];
        });
    };

    const renderColumn = (
        title: string,
        items: { id: string; label: string; selected: boolean }[],
        loading: boolean,
        onSelect: (id: string) => void,
    ) => (
        <div className="flex-1 min-w-[170px] border border-ds-border-subtle rounded-ds-md overflow-hidden flex flex-col bg-white">
            <div className="px-ds-3 py-ds-2 border-b border-ds-border-subtle bg-ds-bg-hover/80 text-ds-small font-semibold text-ds-text-secondary">
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
    const tableItems = tables.map((t) => ({id: t.id, label: t.tableName, selected: isPicked(t.id)}));

    return (
        <DsModal
            open={open}
            onClose={onClose}
            title={multiple ? '选择表（可多选）' : '选择表'}
            width="w-[760px]"
            bordered
            footer={
                <>
                    <DsButton variant="ghost" onClick={onClose}>
                        取消
                    </DsButton>
                    <DsButton onClick={() => onConfirm(picked)} disabled={picked.length === 0}>
                        确认{multiple && picked.length > 0 ? `（${picked.length}）` : ''}
                    </DsButton>
                </>
            }
        >
            <div className="space-y-ds-3">
                <div className="flex items-center gap-ds-3">
                    <label className="text-ds-small font-semibold text-ds-text-secondary whitespace-nowrap">
                        数据源
                    </label>
                    <select
                        value={datasourceId}
                        onChange={(e) => setDatasourceId(e.target.value)}
                        disabled={lockDatasource}
                        className="flex-1 px-ds-3 py-ds-2 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-small text-ds-text-primary focus:outline-none focus-visible:border-ds-accent disabled:opacity-60 disabled:cursor-not-allowed"
                    >
                        <option value="">请选择数据源</option>
                        {datasources.map((d) => (
                            <option key={String(d.id)} value={String(d.id)}>
                                {d.name || `数据源 ${d.id}`}
                            </option>
                        ))}
                    </select>
                </div>

                {datasourceId ? (
                    <div className="flex gap-ds-3 h-[340px]">
                        {renderColumn('数据库', databaseItems, databaseLoading, setSelectedDatabase)}
                        {!noSchema && renderColumn('Schema', schemaItems, schemaLoading, setSelectedSchema)}
                        {renderColumn('表', tableItems, tableLoading, (id) => {
                            const table = tables.find((t) => String(t.id) === String(id));
                            if (table) toggleTable(table);
                        })}
                    </div>
                ) : (
                    <p className="text-ds-caption text-ds-text-muted text-center py-ds-8">
                        请先选择数据源以浏览元数据表
                    </p>
                )}
            </div>
        </DsModal>
    );
}
