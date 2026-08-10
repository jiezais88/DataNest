import {useEffect, useState} from 'react';
import DsButton from '@/components/DsButton';
import DsModal from '@/components/DsModal';
import {listQualityTemplates, queryQualityJobs} from '@/api/quality';
import {
    listMetadataColumns,
    listMetadataDatasourceIds,
    listMetadataDatabases,
    listMetadataSchemas,
    listMetadataTables,
    listMetadataTablesWithoutSchema,
} from '@/api/metadata';
import {isWithoutSchema} from '@/constants/datasource';
import {QUALITY_TYPE_LABEL} from '@/types/quality';
import type {QualityRuleTemplate, QualityRuleBatchCreateRequest, RuleBatchItem} from '@/types/quality';
import type {MetadataColumn, MetadataDatasource, MetadataTable} from '@/types/metadata';

interface BatchApplyItem extends RuleBatchItem {
    tableName: string;
}

interface BatchApplyModalProps {
    open: boolean;
    /** 所属质量任务；为空时在弹窗内选择（供规则模板库页使用） */
    jobId?: string;
    /** 预选模板 ID（供规则模板库页从某行「批量应用」进入时预选当前模板） */
    initialTemplateId?: string;
    onClose: () => void;
    onSubmit: (payload: QualityRuleBatchCreateRequest) => Promise<unknown>;
}

export default function BatchApplyModal({
                                            open,
                                            jobId = '',
                                            initialTemplateId = '',
                                            onClose,
                                            onSubmit,
                                        }: BatchApplyModalProps) {
    const [templates, setTemplates] = useState<QualityRuleTemplate[]>([]);
    const [templateId, setTemplateId] = useState<string>('');
    /** 弹窗内选择的任务（仅当外部未传 jobId 时使用） */
    const [selectedJobId, setSelectedJobId] = useState<string>('');
    const [jobOptions, setJobOptions] = useState<{id: string; name: string}[]>([]);
    /** 目标数据源（仅已采集元数据的数据源；先选数据源再选表） */
    const [datasourceId, setDatasourceId] = useState<string>('');
    const [datasourceOptions, setDatasourceOptions] = useState<MetadataDatasource[]>([]);
    const [items, setItems] = useState<BatchApplyItem[]>([]);
    const [columnsMap, setColumnsMap] = useState<Record<string, MetadataColumn[]>>({});
    const [submitting, setSubmitting] = useState(false);
    const [errors, setErrors] = useState<string>('');

    // 主弹窗内嵌库/表两列选择（取消子弹窗）
    const [databases, setDatabases] = useState<string[]>([]);
    const [databaseLoading, setDatabaseLoading] = useState(false);
    const [selectedDatabase, setSelectedDatabase] = useState<string | null>(null);
    const [schemas, setSchemas] = useState<string[]>([]);
    const [schemaLoading, setSchemaLoading] = useState(false);
    const [selectedSchema, setSelectedSchema] = useState<string | null>(null);
    const [tables, setTables] = useState<MetadataTable[]>([]);
    const [tableLoading, setTableLoading] = useState(false);

    const selectedTemplate = templates.find((t) => String(t.id) === String(templateId));
    const datasourceType = datasourceOptions.find((d) => String(d.id) === String(datasourceId))?.type;
    const noSchema = isWithoutSchema(datasourceType);

    useEffect(() => {
        if (!open) return;
        listQualityTemplates()
            .then((res) => {
                setTemplates(res.data || []);
            })
            .catch(() => setTemplates([]));
        listMetadataDatasourceIds()
            .then((res) => setDatasourceOptions(res.data || []))
            .catch(() => setDatasourceOptions([]));
        // 外部未指定任务时，加载任务下拉供弹窗内选择
        if (!jobId) {
            queryQualityJobs({page: 1, pageSize: 1000})
                .then((res) => setJobOptions((res.data.records || []).map((j) => ({
                    id: String(j.id),
                    name: j.name,
                }))))
                .catch(() => setJobOptions([]));
        }
        setSelectedJobId('');
        setTemplateId(initialTemplateId);
        setDatasourceId('');
        setItems([]);
        setColumnsMap({});
        setErrors('');
        setSelectedDatabase(null);
        setSelectedSchema(null);
        setSchemas([]);
        setTables([]);
        setDatabases([]);
    }, [open, jobId, initialTemplateId]);

    // 切换数据源时清空库/表与已选表，并加载数据库列表
    const handleDatasourceChange = (id: string) => {
        setDatasourceId(id);
        setItems([]);
        setColumnsMap({});
        setErrors('');
        setSelectedDatabase(null);
        setSelectedSchema(null);
        setSchemas([]);
        setTables([]);
        setDatabases([]);
        if (!id) return;
        setDatabaseLoading(true);
        listMetadataDatabases(id)
            .then((res) => setDatabases(res.data || []))
            .finally(() => setDatabaseLoading(false));
    };

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

    const loadColumns = (tableId: string) => {
        if (columnsMap[tableId]) return;
        listMetadataColumns(tableId)
            .then((res) => {
                setColumnsMap((prev) => ({...prev, [tableId]: res.data || []}));
            })
            .catch(() => setColumnsMap((prev) => ({...prev, [tableId]: []})));
    };

    const isTablePicked = (id: string) => items.some((i) => String(i.tableId) === String(id));

    /** 内嵌表列勾选：多选切换，回填 items 并预取字段 */
    const toggleTable = (table: MetadataTable) => {
        const id = String(table.id);
        setItems((prev) => {
            const exists = prev.some((i) => String(i.tableId) === id);
            if (exists) {
                return prev.filter((i) => String(i.tableId) !== id);
            }
            const tableName = table.schemaName ? `${table.schemaName}.${table.tableName}` : table.tableName;
            return [...prev, {tableId: id, tableName, weight: 1}];
        });
        if (!isTablePicked(id)) {
            loadColumns(id);
        }
    };

    const removeItem = (tableId: string) => {
        setItems((prev) => prev.filter((i) => String(i.tableId) !== String(tableId)));
    };

    const updateItem = (tableId: string, patch: Partial<BatchApplyItem>) => {
        setItems((prev) => prev.map((i) => (String(i.tableId) === String(tableId) ? {...i, ...patch} : i)));
    };

    const needsColumn = selectedTemplate && (selectedTemplate.type === 'UNIQUENESS' || selectedTemplate.type === 'RANGE');
    const isRange = selectedTemplate?.type === 'RANGE';
    const isCustomSql = selectedTemplate?.type === 'CUSTOM_SQL';

    const validate = (): boolean => {
        if (!jobId && !selectedJobId) {
            setErrors('请选择目标任务');
            return false;
        }
        if (!templateId) {
            setErrors('请选择模板');
            return false;
        }
        if (!datasourceId) {
            setErrors('请选择数据源');
            return false;
        }
        if (items.length === 0) {
            setErrors('请至少选择一张表');
            return false;
        }
        if (needsColumn && items.some((i) => !i.columnName)) {
            setErrors('部分表未选择检查字段');
            return false;
        }
        if (isRange && items.some((i) => !i.warningThreshold || !i.severeThreshold)) {
            setErrors('部分表未填写值域上下限');
            return false;
        }
        if (isCustomSql && items.some((i) => !i.sqlExpression)) {
            setErrors('部分表未填写自定义 SQL');
            return false;
        }
        setErrors('');
        return true;
    };

    const handleSubmit = async () => {
        if (!validate() || !selectedTemplate) return;
        setSubmitting(true);
        try {
            const payload: QualityRuleBatchCreateRequest = {
                jobId: jobId || selectedJobId,
                templateId,
                items: items.map((i) => ({
                    tableId: i.tableId,
                    columnName: i.columnName,
                    name: i.name,
                    sqlExpression: isCustomSql ? i.sqlExpression : undefined,
                    warningThreshold: i.warningThreshold,
                    severeThreshold: i.severeThreshold,
                    weight: i.weight,
                })),
            };
            await onSubmit(payload);
            onClose();
        } finally {
            setSubmitting(false);
        }
    };

    const inputClass = 'w-full px-ds-3 py-ds-1.5 bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm text-ds-small text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors';
    const selectClass = 'w-full px-ds-3 py-ds-1.5 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-small text-ds-text-primary focus:outline-none focus-visible:border-ds-accent';

    // 内嵌两列选择面板：数据库列（+Schema 列）→ 表列（多选勾表）
    const renderColumn = (title: string, selectedKey: string | null, onSelect: (key: string) => void) => {
        const list = title === '数据库'
            ? databases
            : title === 'Schema'
                ? schemas
                : tables.map((t) => (t.schemaName ? `${t.schemaName}.${t.tableName}` : t.tableName));
        return (
            <div className="flex min-h-[220px] flex-1 flex-col border border-ds-border-subtle rounded-ds-md overflow-hidden">
                <div className="px-ds-3 py-ds-2 bg-ds-bg-hover border-b border-ds-border-subtle text-ds-small font-semibold text-ds-text-secondary">
                    {title}
                </div>
                <div className="max-h-[260px] overflow-y-auto p-ds-2">
                    {list.length === 0 && (
                        <div className="px-ds-2 py-ds-4 text-ds-nano text-ds-text-muted">
                            {title === '数据库' ? '暂无数据库' : '请先选择上一级'}
                        </div>
                    )}
                    {list.map((key) => {
                        const active = selectedKey === key;
                        return (
                            <div
                                key={key}
                                onClick={() => onSelect(key)}
                                className={`cursor-pointer rounded-ds-sm px-ds-2 py-ds-1.5 text-ds-small transition-colors ${
                                    active ? 'bg-ds-accent text-white' : 'text-ds-text-primary hover:bg-ds-bg-hover'
                                }`}
                            >
                                {key}
                            </div>
                        );
                    })}
                </div>
            </div>
        );
    };

    const renderTableColumn = () => {
        const pickedIds = new Set(items.map((i) => String(i.tableId)));
        return (
            <div className="flex min-h-[220px] flex-1 flex-col border border-ds-border-subtle rounded-ds-md overflow-hidden">
                <div className="px-ds-3 py-ds-2 bg-ds-bg-hover border-b border-ds-border-subtle text-ds-small font-semibold text-ds-text-secondary">
                    选择表
                </div>
                <div className="max-h-[260px] overflow-y-auto p-ds-2">
                    {tableLoading ? (
                        <div className="px-ds-2 py-ds-4 text-ds-nano text-ds-text-muted">加载中...</div>
                    ) : tables.length === 0 ? (
                        <div className="px-ds-2 py-ds-4 text-ds-nano text-ds-text-muted">
                            {selectedDatabase ? '暂无表' : '请先选择数据库'}
                        </div>
                    ) : (
                        tables.map((t) => {
                            const id = String(t.id);
                            const name = t.schemaName ? `${t.schemaName}.${t.tableName}` : t.tableName;
                            const picked = pickedIds.has(id);
                            return (
                                <div
                                    key={id}
                                    onClick={() => toggleTable(t)}
                                    className={`cursor-pointer rounded-ds-sm px-ds-2 py-ds-1.5 text-ds-small transition-colors ${
                                        picked ? 'bg-ds-accent text-white' : 'text-ds-text-primary hover:bg-ds-bg-hover'
                                    }`}
                                >
                                    {name}
                                </div>
                            );
                        })
                    )}
                </div>
            </div>
        );
    };

    return (
        <>
            <DsModal
                open={open}
                onClose={onClose}
                title="模板批量应用"
                width="w-[760px]"
                bordered
                footer={
                    <>
                        <DsButton variant="ghost" onClick={onClose}>
                            取消
                        </DsButton>
                        <DsButton onClick={handleSubmit} disabled={submitting}>
                            {submitting ? '应用生成中...' : '生成规则'}
                        </DsButton>
                    </>
                }
            >
                <div className="space-y-ds-4">
                    {!jobId && (
                        <div>
                            <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                                目标任务 <span className="text-ds-danger">*</span>
                            </label>
                            <select
                                value={selectedJobId}
                                onChange={(e) => {
                                    setSelectedJobId(e.target.value);
                                    setErrors('');
                                }}
                                className={selectClass}
                            >
                                <option value="">请选择目标任务</option>
                                {jobOptions.map((j) => (
                                    <option key={j.id} value={j.id}>{j.name}</option>
                                ))}
                            </select>
                            <p className="mt-ds-1 text-ds-nano text-ds-text-muted">
                                批量生成的规则将绑定到该质量任务
                            </p>
                        </div>
                    )}
                    <div>
                        <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                            选择模板 <span className="text-ds-danger">*</span>
                        </label>
                        <select
                            value={templateId}
                            onChange={(e) => {
                                setTemplateId(e.target.value);
                                setItems([]);
                                setErrors('');
                            }}
                            className={selectClass}
                        >
                            <option value="">请选择模板</option>
                            {templates.map((t) => (
                                <option key={String(t.id)} value={String(t.id)}>{t.name}</option>
                            ))}
                        </select>
                        {selectedTemplate && (
                            <p className="mt-ds-1 text-ds-nano text-ds-text-muted">
                                模板类型：{QUALITY_TYPE_LABEL[selectedTemplate.type] || selectedTemplate.type}
                            </p>
                        )}
                    </div>

                    <div>
                        <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                            数据源 <span className="text-ds-danger">*</span>
                        </label>
                        <select
                            value={datasourceId}
                            onChange={(e) => handleDatasourceChange(e.target.value)}
                            className={selectClass}
                        >
                            <option value="">请选择数据源</option>
                            {datasourceOptions.map((d) => (
                                <option key={String(d.id)} value={String(d.id)}>
                                    {d.name || `数据源 ${d.id}`}
                                </option>
                            ))}
                        </select>
                        <p className="mt-ds-1 text-ds-nano text-ds-text-muted">
                            仅展示已采集元数据的数据源，批量应用的表将取自该数据源
                        </p>
                    </div>

                    <div>
                        <label className="text-ds-small font-semibold text-ds-text-secondary">
                            选择目标表 {items.length > 0 && `（已选 ${items.length} 张）`}
                        </label>
                        {datasourceId ? (
                            <div className="mt-ds-2 flex gap-ds-2">
                                {renderColumn('数据库', selectedDatabase, (k) => setSelectedDatabase(k))}
                                {!noSchema && renderColumn('Schema', selectedSchema, (k) => setSelectedSchema(k))}
                                {renderTableColumn()}
                            </div>
                        ) : (
                            <p className="text-ds-caption text-ds-text-muted py-ds-4">请先选择数据源，再选择目标表</p>
                        )}
                    </div>

                    {items.length === 0 ? (
                        <p className="text-ds-caption text-ds-text-muted text-center py-ds-6">尚未选择表</p>
                    ) : (
                        <div className="border border-ds-border-subtle rounded-ds-md overflow-hidden">
                            <div className="max-h-[300px] overflow-auto">
                                <table className="w-full text-left">
                                    <thead className="bg-ds-bg-hover/80 text-ds-nano font-semibold text-ds-text-secondary">
                                    <tr>
                                        <th className="px-ds-3 py-ds-2 w-[180px]">表</th>
                                        {needsColumn && <th className="px-ds-2 py-ds-2 w-[150px]">检查字段</th>}
                                        {isRange && (
                                            <>
                                                <th className="px-ds-2 py-ds-2 w-[90px]">下限</th>
                                                <th className="px-ds-2 py-ds-2 w-[90px]">上限</th>
                                            </>
                                        )}
                                        {isCustomSql && <th className="px-ds-2 py-ds-2">自定义 SQL</th>}
                                        <th className="px-ds-2 py-ds-2 w-[70px]">权重</th>
                                        <th className="px-ds-2 py-ds-2 w-[40px]"/>
                                    </tr>
                                    </thead>
                                    <tbody>
                                    {items.map((item) => {
                                        const cols = columnsMap[item.tableId] || [];
                                        return (
                                            <tr key={String(item.tableId)} className="border-t border-ds-border-subtle">
                                                <td className="px-ds-3 py-ds-2">
                                                    <span className="text-ds-small text-ds-text-primary" title={item.tableName}>
                                                        {item.tableName}
                                                    </span>
                                                </td>
                                                {needsColumn && (
                                                    <td className="px-ds-2 py-ds-2">
                                                        <select
                                                            value={item.columnName || ''}
                                                            onChange={(e) => updateItem(String(item.tableId), {columnName: e.target.value})}
                                                            className={selectClass}
                                                        >
                                                            <option value="">选择字段</option>
                                                            {cols.map((c) => (
                                                                <option key={c.id} value={c.columnName}>{c.columnName}</option>
                                                            ))}
                                                        </select>
                                                    </td>
                                                )}
                                                {isRange && (
                                                    <>
                                                        <td className="px-ds-2 py-ds-2">
                                                            <input
                                                                type="number"
                                                                step="any"
                                                                value={item.warningThreshold ?? ''}
                                                                onChange={(e) => updateItem(String(item.tableId), {warningThreshold: e.target.value === '' ? undefined : Number(e.target.value)})}
                                                                className={inputClass}
                                                                placeholder="min"
                                                            />
                                                        </td>
                                                        <td className="px-ds-2 py-ds-2">
                                                            <input
                                                                type="number"
                                                                step="any"
                                                                value={item.severeThreshold ?? ''}
                                                                onChange={(e) => updateItem(String(item.tableId), {severeThreshold: e.target.value === '' ? undefined : Number(e.target.value)})}
                                                                className={inputClass}
                                                                placeholder="max"
                                                            />
                                                        </td>
                                                    </>
                                                )}
                                                {isCustomSql && (
                                                    <td className="px-ds-2 py-ds-2">
                                                        <input
                                                            value={item.sqlExpression || ''}
                                                            onChange={(e) => updateItem(String(item.tableId), {sqlExpression: e.target.value})}
                                                            className={`${inputClass} font-mono`}
                                                            placeholder="校验 SQL"
                                                        />
                                                    </td>
                                                )}
                                                <td className="px-ds-2 py-ds-2">
                                                    <input
                                                        type="number"
                                                        min={1}
                                                        value={item.weight ?? 1}
                                                        onChange={(e) => updateItem(String(item.tableId), {weight: Number(e.target.value)})}
                                                        className={inputClass}
                                                    />
                                                </td>
                                                <td className="px-ds-2 py-ds-2">
                                                    <button
                                                        type="button"
                                                        onClick={() => removeItem(String(item.tableId))}
                                                        className="text-ds-danger text-ds-nano hover:opacity-70"
                                                    >
                                                        移除
                                                    </button>
                                                </td>
                                            </tr>
                                        );
                                    })}
                                    </tbody>
                                </table>
                            </div>
                        </div>
                    )}

                    {errors && <p className="text-ds-nano text-ds-danger">{errors}</p>}
                </div>
            </DsModal>
        </>
    );
}
