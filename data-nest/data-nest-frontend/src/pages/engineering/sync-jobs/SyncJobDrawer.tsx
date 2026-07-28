import {useEffect, useState} from 'react';
import type {DataSource} from '../../../types/datasource';
import type {SyncFieldMapping, SyncJob, SyncJobCreateRequest, SyncMode, SyncTriggerType,} from '../../../types/sync';
import {getDataSourceSchemas} from '../../../api/engineering';
import {listMetadataDatabases, listMetadataTables} from '../../../api/metadata';
import {previewDataSource} from '../../../api/preview';
import Drawer from '../../../components/Drawer';
import CronPicker from '../../../components/CronPicker';
import {HiOutlinePlus, HiOutlineTrash} from 'react-icons/hi2';

interface FormData {
    name: string;
    sourceDatasourceId: string;
    selectedSchema: string;
    sourceTable: string;
    targetDatabase: string;
    targetTable: string;
    syncMode: SyncMode;
    incrementalField: string;
    triggerType: SyncTriggerType;
    cronExpression: string;
    retryTimes: number;
    retryInterval: number;
    description: string;
}

interface SyncJobDrawerProps {
    open: boolean;
    editItem: SyncJob | null;
    sourceDataSources: DataSource[];
    onClose: () => void;
    onSubmit: (payload: SyncJobCreateRequest) => Promise<{
        code: number;
        message?: string;
        data?: SyncJob
    } | undefined>;
    onExecute?: (job: SyncJob) => void;
}

const MODE_OPTIONS: { value: SyncMode; label: string }[] = [
    {value: 'FULL', label: '全量同步'},
    {value: 'INCREMENTAL', label: '增量同步'},
];

const TRIGGER_OPTIONS: { value: SyncTriggerType; label: string }[] = [
    {value: 'MANUAL', label: '手动触发'},
    {value: 'CRON', label: 'Cron 定时'},
];

const EMPTY_FORM: FormData = {
    name: '',
    sourceDatasourceId: '',
    selectedSchema: '',
    sourceTable: '',
    targetDatabase: '',
    targetTable: '',
    syncMode: 'FULL',
    incrementalField: '',
    triggerType: 'MANUAL',
    cronExpression: '',
    retryTimes: 3,
    retryInterval: 5,
    description: '',
};

function datasourceLabel(ds: DataSource) {
    return `${ds.name} (${ds.host}:${ds.port}/${ds.databaseName})`;
}

function buildSchemaLabel(ds: DataSource, schema: string) {
    if (ds.type === 'POSTGRESQL') {
        return `${ds.databaseName}.${schema}`;
    }
    return schema;
}

export default function SyncJobDrawer({
                                          open,
                                          editItem,
                                          sourceDataSources,
                                          onClose,
                                          onSubmit,
                                          onExecute,
                                      }: SyncJobDrawerProps) {
    const [form, setForm] = useState<FormData>(EMPTY_FORM);
    const [errors, setErrors] = useState<Partial<Record<keyof FormData | 'fieldMapping', string>>>({});
    const [submitting, setSubmitting] = useState(false);
    const [schemas, setSchemas] = useState<string[]>([]);
    const [schemasLoading, setSchemasLoading] = useState(false);
    const [tables, setTables] = useState<string[]>([]);
    const [tablesLoading, setTablesLoading] = useState(false);
    const [columnOptions, setColumnOptions] = useState<string[]>([]);
    const [columnsLoading, setColumnsLoading] = useState(false);
    const [fieldMapping, setFieldMapping] = useState<SyncFieldMapping[]>([]);
    const [targetDatabases, setTargetDatabases] = useState<string[]>([]);
    const [targetDbsLoading, setTargetDbsLoading] = useState(false);

    const isEdit = !!editItem;

    useEffect(() => {
        if (!open) return;
        if (editItem) {
            const selectedSchema = editItem.sourceSchema || editItem.sourceDatabase || '';
            setForm({
                name: editItem.name,
                sourceDatasourceId: editItem.sourceDatasourceId,
                selectedSchema,
                sourceTable: editItem.sourceTables?.[0] || '',
                targetDatabase: editItem.targetDatabase || '',
                targetTable: editItem.targetTable || '',
                syncMode: editItem.syncMode,
                incrementalField: editItem.incrementalField || '',
                triggerType: editItem.triggerType,
                cronExpression: editItem.cronExpression || '',
                retryTimes: editItem.retryTimes ?? 3,
                retryInterval: editItem.retryInterval ?? 5,
                description: editItem.description || '',
            });
            setFieldMapping(editItem.fieldMapping?.length ? editItem.fieldMapping : []);
            loadSchemas(editItem.sourceDatasourceId, selectedSchema, editItem.sourceTables?.[0] || '');
        } else {
            setForm(EMPTY_FORM);
            setSchemas([]);
            setTables([]);
            setColumnOptions([]);
            setFieldMapping([]);
        }
        loadTargetDatabases();
        setErrors({});
    }, [open, editItem]);

    useEffect(() => {
        if (form.sourceTable && form.selectedSchema && form.sourceDatasourceId) {
            loadTableColumns(form.sourceDatasourceId, form.selectedSchema, form.sourceTable, fieldMapping);
        } else {
            setColumnOptions([]);
        }
    }, [form.sourceDatasourceId, form.selectedSchema, form.sourceTable]);

    const loadTargetDatabases = async () => {
        setTargetDbsLoading(true);
        try {
            // 内置 Doris 在元数据中的 datasourceId 约定为 -1
            const result = await listMetadataDatabases('-1');
            if (result.code === 200) {
                setTargetDatabases(result.data || []);
            }
        } catch {
            setTargetDatabases([]);
        } finally {
            setTargetDbsLoading(false);
        }
    };

    const loadSchemas = async (datasourceId: string, preselectSchema?: string, preselectTable?: string) => {
        if (!datasourceId) {
            setSchemas([]);
            return;
        }
        setSchemasLoading(true);
        try {
            const result = await getDataSourceSchemas(datasourceId);
            if (result.code === 200) {
                const list = result.data || [];
                setSchemas(list);
                if (preselectSchema && list.includes(preselectSchema)) {
                    loadTables(datasourceId, preselectSchema, preselectTable);
                }
            }
        } finally {
            setSchemasLoading(false);
        }
    };

    const resolveDatabaseSchema = (datasourceId: string, selectedSchema: string) => {
        const ds = sourceDataSources.find((d) => d.id === datasourceId);
        if (!ds || !selectedSchema) return null;
        if (ds.type === 'POSTGRESQL') {
            return {sourceDatabase: ds.databaseName, sourceSchema: selectedSchema};
        }
        return {sourceDatabase: selectedSchema, sourceSchema: selectedSchema};
    };

    const loadTables = async (datasourceId: string, selectedSchema: string, preselectTable?: string) => {
        if (!datasourceId || !selectedSchema) {
            setTables([]);
            return;
        }
        const resolved = resolveDatabaseSchema(datasourceId, selectedSchema);
        if (!resolved) {
            setTables([]);
            return;
        }
        setTablesLoading(true);
        try {
            const result = await listMetadataTables(
                datasourceId,
                resolved.sourceDatabase,
                resolved.sourceSchema || resolved.sourceDatabase,
            );
            if (result.code === 200) {
                const names = (result.data || []).map((t) => t.tableName);
                setTables(names);
            }
        } finally {
            setTablesLoading(false);
        }
    };

    const loadTableColumns = async (datasourceId: string, selectedSchema: string, tableName: string, existingMapping: SyncFieldMapping[]) => {
        const resolved = resolveDatabaseSchema(datasourceId, selectedSchema);
        if (!resolved) return;
        setColumnsLoading(true);
        try {
            const result = await previewDataSource(
                datasourceId,
                resolved.sourceDatabase,
                resolved.sourceSchema || resolved.sourceDatabase,
                tableName,
            );
            if (result.code === 200) {
                const columns = result.data.columns || [];
                setColumnOptions(columns);
                applyAutoMapping(columns, existingMapping);
                if (form.incrementalField && !columns.includes(form.incrementalField)) {
                    setForm((prev) => ({...prev, incrementalField: ''}));
                }
            }
        } finally {
            setColumnsLoading(false);
        }
    };

    const applyAutoMapping = (columns: string[], existingMapping: SyncFieldMapping[]) => {
        const existingMap = new Map(
            existingMapping
                .filter((m) => columns.includes(m.sourceColumn))
                .map((m) => [m.sourceColumn, m.targetColumn]),
        );
        const next = columns.map((col) => ({
            sourceColumn: col,
            targetColumn: existingMap.get(col) || col,
        }));
        setFieldMapping(next);
    };

    const updateField = <K extends keyof FormData>(field: K, value: FormData[K]) => {
        setForm((prev) => ({...prev, [field]: value}));
        if (errors[field]) {
            setErrors((prev) => ({...prev, [field]: undefined}));
        }
    };

    const handleSourceDatasourceChange = (datasourceId: string) => {
        updateField('sourceDatasourceId', datasourceId);
        updateField('selectedSchema', '');
        updateField('sourceTable', '');
        updateField('targetTable', '');
        updateField('incrementalField', '');
        setTables([]);
        setColumnOptions([]);
        setFieldMapping([]);
        loadSchemas(datasourceId);
    };

    const handleSchemaChange = (selectedSchema: string) => {
        updateField('selectedSchema', selectedSchema);
        updateField('sourceTable', '');
        updateField('targetTable', '');
        updateField('incrementalField', '');
        setColumnOptions([]);
        setFieldMapping([]);
        if (form.sourceDatasourceId && selectedSchema) {
            loadTables(form.sourceDatasourceId, selectedSchema);
        } else {
            setTables([]);
        }
    };

    const handleSourceTableChange = (sourceTable: string) => {
        setForm((prev) => ({
            ...prev,
            sourceTable,
            targetTable: !prev.targetTable || prev.targetTable === prev.sourceTable ? sourceTable : prev.targetTable,
            incrementalField: '',
        }));
        setFieldMapping([]);
    };

    const updateMappingField = (index: number, key: keyof SyncFieldMapping, value: string) => {
        setFieldMapping((prev) => {
            const next = [...prev];
            next[index] = {...next[index], [key]: value};
            return next;
        });
        if (errors.fieldMapping) {
            setErrors((prev) => ({...prev, fieldMapping: undefined}));
        }
    };

    const addMappingRow = () => {
        setFieldMapping((prev) => [...prev, {sourceColumn: '', targetColumn: ''}]);
    };

    const removeMappingRow = (index: number) => {
        setFieldMapping((prev) => prev.filter((_, i) => i !== index));
    };

    const validate = (): boolean => {
        const nextErrors: Partial<Record<keyof FormData | 'fieldMapping', string>> = {};
        if (!form.name.trim()) nextErrors.name = '请输入任务名称';
        if (!form.sourceDatasourceId) nextErrors.sourceDatasourceId = '请选择源数据源';
        if (!form.selectedSchema) nextErrors.selectedSchema = '请选择源库 / Schema';
        if (!form.sourceTable.trim()) nextErrors.sourceTable = '请选择源表';
        if (!form.targetDatabase.trim()) nextErrors.targetDatabase = '请选择或输入目标 Doris 库';
        if (!form.targetTable.trim()) nextErrors.targetTable = '请输入目标表名';
        if (!form.syncMode) nextErrors.syncMode = '请选择同步模式';
        if (form.syncMode === 'INCREMENTAL' && !form.incrementalField) {
            nextErrors.incrementalField = '请选择增量字段';
        }
        if (!form.triggerType) nextErrors.triggerType = '请选择触发方式';
        if (form.triggerType === 'CRON' && !form.cronExpression.trim()) {
            nextErrors.cronExpression = 'Cron 触发必须填写 Cron 表达式';
        }
        if (form.retryTimes < 0 || form.retryTimes > 3) {
            nextErrors.retryTimes = '重试次数需在 0-3 之间';
        }
        if (form.retryInterval < 1 || form.retryInterval > 30) {
            nextErrors.retryInterval = '重试间隔需在 1-30 分钟之间';
        }
        setErrors(nextErrors);
        return Object.keys(nextErrors).length === 0;
    };

    const buildFieldMapping = (): SyncFieldMapping[] | undefined => {
        const valid = fieldMapping.filter((row) => row.sourceColumn.trim() || row.targetColumn.trim());
        if (valid.length === 0) return undefined;
        return valid.map((row) => ({
            sourceColumn: row.sourceColumn.trim(),
            targetColumn: row.targetColumn.trim(),
            targetType: undefined,
        }));
    };

    const buildPayload = (): SyncJobCreateRequest => {
        const resolved = resolveDatabaseSchema(form.sourceDatasourceId, form.selectedSchema);
        const base: SyncJobCreateRequest = {
            name: form.name.trim(),
            sourceDatasourceId: form.sourceDatasourceId,
            sourceDatabase: resolved?.sourceDatabase,
            sourceSchema: resolved?.sourceSchema,
            sourceTables: [form.sourceTable.trim()],
            syncMode: form.syncMode,
            incrementalField: form.syncMode === 'INCREMENTAL' ? form.incrementalField : undefined,
            targetDatabase: form.targetDatabase.trim(),
            targetTable: form.targetTable.trim(),
            triggerType: form.triggerType,
            cronExpression: form.triggerType === 'CRON' ? form.cronExpression.trim() : undefined,
            retryTimes: Number(form.retryTimes),
            retryInterval: Number(form.retryInterval),
            fieldMapping: buildFieldMapping(),
            description: form.description.trim() || undefined,
        };
        if (editItem) {
            return {...base, status: editItem.status, scheduleEnabled: editItem.scheduleEnabled};
        }
        return base;
    };

    const handleSubmit = async (runImmediately = false) => {
        if (!validate()) return;
        setSubmitting(true);
        try {
            const result = await onSubmit(buildPayload());
            if (result && result.code === 200) {
                if (runImmediately && result.data && onExecute) {
                    onExecute(result.data);
                }
                onClose();
            }
        } finally {
            setSubmitting(false);
        }
    };

    const selectedSource = sourceDataSources.find((d) => d.id === form.sourceDatasourceId);
    const schemaLabel = selectedSource?.type === 'POSTGRESQL' ? 'Schema' : '数据库';

    return (
        <Drawer
            open={open}
            title={isEdit ? '编辑同步任务' : '创建同步任务'}
            width="max-w-[640px]"
            onClose={onClose}
            footer={
                <>
                    <button
                        data-testid="sync-job-cancel"
                        onClick={onClose}
                        className="px-ds-4 py-ds-2 bg-white border border-ds-border-subtle hover:border-ds-border-strong text-ds-text-secondary text-ds-small font-semibold rounded-ds-sm transition-colors"
                    >
                        取消
                    </button>
                    <button
                        data-testid="sync-job-submit-run"
                        onClick={() => handleSubmit(true)}
                        disabled={submitting}
                        className="px-ds-4 py-ds-2 bg-white border border-ds-accent text-ds-accent hover:bg-ds-accent-light disabled:opacity-60 disabled:cursor-not-allowed text-ds-small font-semibold rounded-ds-sm transition-colors"
                    >
                        {submitting ? '保存中...' : '保存并立即执行'}
                    </button>
                    <button
                        data-testid="sync-job-submit"
                        onClick={() => handleSubmit(false)}
                        disabled={submitting}
                        className="px-ds-4 py-ds-2 bg-ds-accent hover:bg-ds-accent-hover disabled:opacity-60 disabled:cursor-not-allowed text-white text-ds-small font-semibold rounded-ds-sm transition-colors"
                    >
                        {submitting ? '保存中...' : '保存'}
                    </button>
                </>
            }
        >
            <div className="space-y-ds-4">
                <div>
                    <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                        任务名称 <span className="text-ds-danger">*</span>
                    </label>
                    <input
                        data-testid="sync-job-name"
                        value={form.name}
                        onChange={(e) => updateField('name', e.target.value)}
                        disabled={isEdit}
                        className="w-full px-ds-3 py-ds-2 bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary disabled:bg-ds-bg-disabled disabled:text-ds-text-muted focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors"
                        placeholder="例如：订单表同步到 Doris"
                    />
                    {errors.name && <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.name}</p>}
                </div>

                <div className="border-t border-ds-border-subtle pt-ds-4">
                    <h3 className="text-ds-small font-semibold text-ds-text-secondary mb-ds-2">源端配置</h3>
                    <div className="space-y-ds-4">
                        <div>
                            <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                                源数据源 <span className="text-ds-danger">*</span>
                            </label>
                            <select
                                data-testid="sync-job-source-datasource"
                                value={form.sourceDatasourceId}
                                onChange={(e) => handleSourceDatasourceChange(e.target.value)}
                                className="w-full px-ds-3 py-ds-2 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors"
                            >
                                <option value="">请选择</option>
                                {sourceDataSources.map((ds) => (
                                    <option key={ds.id} value={ds.id}
                                            data-testid={`sync-job-source-datasource-option-${ds.id}`}>
                                        {datasourceLabel(ds)}
                                    </option>
                                ))}
                            </select>
                            {errors.sourceDatasourceId && (
                                <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.sourceDatasourceId}</p>
                            )}
                        </div>

                        <div>
                            <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                                选择{schemaLabel} <span className="text-ds-danger">*</span>
                            </label>
                            <select
                                data-testid="sync-job-schema"
                                value={form.selectedSchema}
                                onChange={(e) => handleSchemaChange(e.target.value)}
                                disabled={!form.sourceDatasourceId || schemasLoading}
                                className="w-full px-ds-3 py-ds-2 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors disabled:opacity-60 disabled:cursor-not-allowed"
                            >
                                <option value="">{schemasLoading ? '加载中...' : '请选择'}</option>
                                {schemas.map((schema) => (
                                    <option key={schema} value={schema}
                                            data-testid={`sync-job-schema-option-${schema}`}>
                                        {selectedSource ? buildSchemaLabel(selectedSource, schema) : schema}
                                    </option>
                                ))}
                            </select>
                            {errors.selectedSchema && (
                                <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.selectedSchema}</p>
                            )}
                        </div>

                        <div>
                            <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                                源表 <span className="text-ds-danger">*</span>
                            </label>
                            <div className="relative">
                                <input
                                    data-testid="sync-job-source-table"
                                    list="sync-job-source-tables"
                                    value={form.sourceTable}
                                    onChange={(e) => handleSourceTableChange(e.target.value)}
                                    disabled={!form.selectedSchema || tablesLoading}
                                    placeholder={tablesLoading ? '加载中...' : '请选择或输入源表名'}
                                    className="w-full px-ds-3 py-ds-2 bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors disabled:opacity-60 disabled:cursor-not-allowed"
                                />
                                <datalist id="sync-job-source-tables">
                                    {tables.map((t) => (
                                        <option key={t} value={t}/>
                                    ))}
                                </datalist>
                            </div>
                            {errors.sourceTable && (
                                <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.sourceTable}</p>
                            )}
                        </div>
                    </div>
                </div>

                <div className="border-t border-ds-border-subtle pt-ds-4">
                    <h3 className="text-ds-small font-semibold text-ds-text-secondary mb-ds-2">目标端配置（内置
                        Doris）</h3>
                    <div className="space-y-ds-4">
                        <div>
                            <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                                目标 Doris 库 <span className="text-ds-danger">*</span>
                            </label>
                            <div className="relative">
                                <input
                                    data-testid="sync-job-target-database"
                                    list="builtin-doris-dbs"
                                    value={form.targetDatabase}
                                    onChange={(e) => updateField('targetDatabase', e.target.value)}
                                    disabled={targetDbsLoading}
                                    placeholder={targetDbsLoading ? '加载中...' : '选择已有库或输入新库名'}
                                    className="w-full px-ds-3 py-ds-2 bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors disabled:opacity-60 disabled:cursor-not-allowed"
                                />
                                <datalist id="builtin-doris-dbs">
                                    {targetDatabases.map((db) => (
                                        <option key={db} value={db}/>
                                    ))}
                                </datalist>
                            </div>
                            {errors.targetDatabase && (
                                <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.targetDatabase}</p>
                            )}
                        </div>

                        <div>
                            <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                                目标表名 <span className="text-ds-danger">*</span>
                            </label>
                            <input
                                data-testid="sync-job-target-table"
                                value={form.targetTable}
                                onChange={(e) => updateField('targetTable', e.target.value)}
                                className="w-full px-ds-3 py-ds-2 bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors"
                                placeholder="默认与源表名相同"
                            />
                            {errors.targetTable && (
                                <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.targetTable}</p>
                            )}
                        </div>
                    </div>
                </div>

                <div className="border-t border-ds-border-subtle pt-ds-4">
                    <h3 className="text-ds-small font-semibold text-ds-text-secondary mb-ds-2">字段映射</h3>
                    <div className="border border-ds-border-subtle rounded-ds-sm p-ds-3 bg-ds-bg-hover space-y-ds-2">
                        {columnsLoading ? (
                            <p className="text-ds-small text-ds-text-muted">加载字段中...</p>
                        ) : fieldMapping.length === 0 ? (
                            <p className="text-ds-small text-ds-text-muted">选择源表后将自动匹配字段</p>
                        ) : (
                            fieldMapping.map((row, index) => (
                                <div key={index} className="flex items-center gap-ds-2">
                                    <input
                                        data-testid={`sync-job-mapping-source-${index}`}
                                        value={row.sourceColumn}
                                        onChange={(e) => updateMappingField(index, 'sourceColumn', e.target.value)}
                                        placeholder="源字段"
                                        className="flex-1 min-w-0 px-ds-2 py-ds-1.5 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-small text-ds-text-primary focus:outline-none focus-visible:border-ds-accent transition-colors"
                                    />
                                    <span className="text-ds-small text-ds-text-muted">→</span>
                                    <input
                                        data-testid={`sync-job-mapping-target-${index}`}
                                        value={row.targetColumn}
                                        onChange={(e) => updateMappingField(index, 'targetColumn', e.target.value)}
                                        placeholder="目标字段"
                                        className="flex-1 min-w-0 px-ds-2 py-ds-1.5 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-small text-ds-text-primary focus:outline-none focus-visible:border-ds-accent transition-colors"
                                    />
                                    <button
                                        type="button"
                                        data-testid={`sync-job-mapping-remove-${index}`}
                                        onClick={() => removeMappingRow(index)}
                                        className="p-1.5 text-ds-text-muted hover:text-ds-danger hover:bg-ds-danger-light rounded transition-colors"
                                        title="删除"
                                        aria-label="删除"
                                    >
                                        <HiOutlineTrash size={16}/>
                                    </button>
                                </div>
                            ))
                        )}
                        <button
                            type="button"
                            data-testid="sync-job-mapping-add"
                            onClick={addMappingRow}
                            className="flex items-center gap-ds-1 text-ds-small text-ds-accent hover:text-ds-accent-hover font-medium transition-colors"
                        >
                            <HiOutlinePlus size={16}/>
                            添加字段映射
                        </button>
                    </div>
                    {errors.fieldMapping && (
                        <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.fieldMapping}</p>
                    )}
                </div>

                <div className="border-t border-ds-border-subtle pt-ds-4">
                    <h3 className="text-ds-small font-semibold text-ds-text-secondary mb-ds-2">同步策略</h3>
                    <div className="space-y-ds-4">
                        <div>
                            <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                                同步模式 <span className="text-ds-danger">*</span>
                            </label>
                            <div className="flex gap-ds-3">
                                {MODE_OPTIONS.map((o) => (
                                    <button
                                        key={o.value}
                                        type="button"
                                        data-testid={`sync-job-mode-${o.value}`}
                                        onClick={() => updateField('syncMode', o.value)}
                                        className={`flex-1 px-ds-4 py-ds-3 rounded-ds-sm border text-left transition-colors ${
                                            form.syncMode === o.value
                                                ? 'border-ds-accent bg-ds-accent-light text-ds-accent'
                                                : 'border-ds-border-subtle bg-white text-ds-text-secondary hover:border-ds-accent hover:text-ds-accent'
                                        }`}
                                    >
                                        <span className="text-ds-body font-semibold block">{o.label}</span>
                                    </button>
                                ))}
                            </div>
                            {errors.syncMode &&
                                <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.syncMode}</p>}
                        </div>

                        {form.syncMode === 'INCREMENTAL' && (
                            <div>
                                <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                                    增量字段 <span className="text-ds-danger">*</span>
                                </label>
                                <select
                                    data-testid="sync-job-incremental-field"
                                    value={form.incrementalField}
                                    onChange={(e) => updateField('incrementalField', e.target.value)}
                                    disabled={columnOptions.length === 0 || columnsLoading}
                                    className="w-full px-ds-3 py-ds-2 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors disabled:opacity-60 disabled:cursor-not-allowed"
                                >
                                    <option value="">请选择</option>
                                    {columnOptions.map((col) => (
                                        <option key={col} value={col}>{col}</option>
                                    ))}
                                </select>
                                {errors.incrementalField && (
                                    <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.incrementalField}</p>
                                )}
                            </div>
                        )}

                        <div>
                            <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                                触发方式 <span className="text-ds-danger">*</span>
                            </label>
                            <div className="flex gap-ds-3">
                                {TRIGGER_OPTIONS.map((o) => (
                                    <button
                                        key={o.value}
                                        type="button"
                                        data-testid={`sync-job-trigger-${o.value}`}
                                        onClick={() => updateField('triggerType', o.value)}
                                        className={`flex-1 px-ds-4 py-ds-3 rounded-ds-sm border text-left transition-colors ${
                                            form.triggerType === o.value
                                                ? 'border-ds-accent bg-ds-accent-light text-ds-accent'
                                                : 'border-ds-border-subtle bg-white text-ds-text-secondary hover:border-ds-accent hover:text-ds-accent'
                                        }`}
                                    >
                                        <span className="text-ds-body font-semibold block">{o.label}</span>
                                    </button>
                                ))}
                            </div>
                            {errors.triggerType &&
                                <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.triggerType}</p>}
                        </div>

                        {form.triggerType === 'CRON' && (
                            <div>
                                <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                                    Cron 表达式 <span className="text-ds-danger">*</span>
                                </label>
                                <CronPicker
                                    value={form.cronExpression}
                                    onChange={(v) => updateField('cronExpression', v)}
                                />
                                {errors.cronExpression && (
                                    <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.cronExpression}</p>
                                )}
                            </div>
                        )}
                    </div>
                </div>

                <div className="border-t border-ds-border-subtle pt-ds-4">
                    <h3 className="text-ds-small font-semibold text-ds-text-secondary mb-ds-2">容错配置</h3>
                    <div className="grid grid-cols-2 gap-ds-4">
                        <div>
                            <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                                失败重试次数 <span className="text-ds-danger">*</span>
                            </label>
                            <select
                                data-testid="sync-job-retry-times"
                                value={form.retryTimes}
                                onChange={(e) => updateField('retryTimes', Number(e.target.value))}
                                className="w-full px-ds-3 py-ds-2 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors"
                            >
                                {[0, 1, 2, 3].map((n) => (
                                    <option key={n} value={n}>{n} 次</option>
                                ))}
                            </select>
                            {errors.retryTimes && (
                                <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.retryTimes}</p>
                            )}
                        </div>
                        <div>
                            <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                                重试间隔 <span className="text-ds-danger">*</span>
                            </label>
                            <select
                                data-testid="sync-job-retry-interval"
                                value={form.retryInterval}
                                onChange={(e) => updateField('retryInterval', Number(e.target.value))}
                                className="w-full px-ds-3 py-ds-2 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors"
                            >
                                {Array.from({length: 30}, (_, i) => i + 1).map((n) => (
                                    <option key={n} value={n}>{n} 分钟</option>
                                ))}
                            </select>
                            {errors.retryInterval && (
                                <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.retryInterval}</p>
                            )}
                        </div>
                    </div>
                </div>

                <div>
                    <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">描述</label>
                    <textarea
                        data-testid="sync-job-description"
                        value={form.description}
                        onChange={(e) => updateField('description', e.target.value)}
                        rows={3}
                        className="w-full px-ds-3 py-ds-2 bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors resize-none"
                        placeholder="可选：填写同步任务的业务说明"
                    />
                </div>
            </div>
        </Drawer>
    );
}
