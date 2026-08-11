// 批量数据同步任务抽屉（Sprint 4 重构：多表同步 + 速率限流）
// 关键模型：
// - selectedTables：勾选的源表列表（创建/多表任务可多选；Sprint 3 的单表任务编辑时锁定单选，PRD §6.9.5）
// - tableMappings：源表 → 目标表名（默认同名，可逐个修改）
// - fieldMappings：源表 → 字段映射（按源表逐个配置，技术文档 §12.2；列信息按表缓存避免重复拉取）
// 提交口径：
// - sourceTables = 勾选列表；targetTable / 顶层 fieldMapping 取第一张表（兼容后端必填与单表旧行为）
// - 多表（>1）时 sourceTablesDetail = JSON.stringify([{sourceTable,targetTable,fieldMapping}])
//   （注意：后端要求请求体为 JSON 字符串，响应里是对象数组）
// - 限流：rateLimitEnabled + readRateLimitMbps(MB/s) + writeRateLimitRowsPerSecond(行/s)，不启用/不填 = 不限
import {useCallback, useEffect, useState} from 'react';
import type {DataSource} from '@/types/datasource';
import {DataSourceTypeEnum} from '@/constants/datasource';
import type {
    SourceTableDetail,
    SyncFieldMapping,
    SyncJob,
    SyncJobCreateRequest,
    SyncMode,
    SyncTriggerType,
} from '@/types/sync';
import {SyncModeEnum, TaskTriggerTypeEnum} from '@/constants/task';
import {getDataSourceSchemas, getDataSourceTables} from '@/api/engineering';
import {listBuiltinDorisDatabases, listBuiltinDorisTables} from '@/api/metadata';
import {previewDataSource} from '@/api/preview';
import Drawer from '@/components/Drawer';
import CronPicker from '@/components/CronPicker';
import DsButton from '@/components/DsButton';
import DsIconButton from '@/components/DsIconButton';
import {HiOutlinePlus, HiOutlineTrash} from 'react-icons/hi2';

interface FormData {
    name: string;
    sourceDatasourceId: string;
    selectedSchema: string;
    targetDatabase: string;
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
    mode?: 'create' | 'edit' | 'view';
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
    {value: SyncModeEnum.FULL, label: '全量同步'},
    {value: SyncModeEnum.INCREMENTAL, label: '增量同步'},
];

const TRIGGER_OPTIONS: { value: SyncTriggerType; label: string }[] = [
    {value: TaskTriggerTypeEnum.MANUAL, label: '手动触发'},
    {value: TaskTriggerTypeEnum.CRON, label: 'Cron 定时'},
];

const EMPTY_FORM: FormData = {
    name: '',
    sourceDatasourceId: '',
    selectedSchema: '',
    targetDatabase: '',
    syncMode: SyncModeEnum.FULL,
    incrementalField: '',
    triggerType: TaskTriggerTypeEnum.MANUAL,
    cronExpression: '',
    retryTimes: 3,
    retryInterval: 5,
    description: '',
};

type ErrorKey = keyof FormData | 'sourceTable' | 'targetTable' | 'fieldMapping' | 'rateLimit';

function datasourceLabel(ds: DataSource) {
    return `${ds.name} (${ds.host}:${ds.port}/${ds.databaseName})`;
}

function buildSchemaLabel(ds: DataSource, schema: string) {
    if (ds.type === DataSourceTypeEnum.POSTGRESQL) {
        return `${ds.databaseName}.${schema}`;
    }
    return schema;
}

// 字段自动映射：源列按名列出，已存在的同名映射保留目标列名/类型，否则目标列默认同名
function applyAutoMapping(columns: string[], existingMapping: SyncFieldMapping[]): SyncFieldMapping[] {
    const existingMap = new Map(
        existingMapping
            .filter((m) => columns.includes(m.sourceColumn))
            .map((m) => [m.sourceColumn, m]),
    );
    return columns.map((col) => existingMap.get(col) || {sourceColumn: col, targetColumn: col});
}

export default function SyncJobDrawer({
                                          open,
                                          editItem,
                                          mode = editItem ? 'edit' : 'create',
                                          sourceDataSources,
                                          onClose,
                                          onSubmit,
                                          onExecute,
                                      }: SyncJobDrawerProps) {
    const [form, setForm] = useState<FormData>(EMPTY_FORM);
    const [errors, setErrors] = useState<Partial<Record<ErrorKey, string>>>({});
    const [submitting, setSubmitting] = useState(false);
    const [schemas, setSchemas] = useState<string[]>([]);
    const [schemasLoading, setSchemasLoading] = useState(false);
    const [tables, setTables] = useState<string[]>([]);
    const [tablesLoading, setTablesLoading] = useState(false);
    // 多表状态：勾选源表 / 目标表名映射 / 按表字段映射 / 字段映射区当前表
    const [selectedTables, setSelectedTables] = useState<string[]>([]);
    const [tableSearch, setTableSearch] = useState('');
    const [tableMappings, setTableMappings] = useState<Record<string, string>>({});
    const [fieldMappings, setFieldMappings] = useState<Record<string, SyncFieldMapping[]>>({});
    const [activeMappingTable, setActiveMappingTable] = useState('');
    // 列信息按表缓存：切换字段映射源表时避免重复拉取预览接口
    const [columnsCache, setColumnsCache] = useState<Record<string, {
        columns: string[];
        types: Record<string, string>
    }>>({});
    const [columnsLoading, setColumnsLoading] = useState(false);
    const [targetDatabases, setTargetDatabases] = useState<string[]>([]);
    const [targetDbsLoading, setTargetDbsLoading] = useState(false);
    const [targetTables, setTargetTables] = useState<string[]>([]);
    const [targetTablesLoading, setTargetTablesLoading] = useState(false);
    // 限流配置（输入框用 string 暂存，提交时转 number）
    const [rateLimitEnabled, setRateLimitEnabled] = useState(false);
    const [readRateLimitMbps, setReadRateLimitMbps] = useState('');
    const [writeRateLimitRowsPerSecond, setWriteRateLimitRowsPerSecond] = useState('');

    const isEdit = mode === 'edit';
    const isView = mode === 'view';
    // 已有多表任务：编辑时保持多表；Sprint 3 单表任务编辑时锁定单选（PRD §6.9.5）
    const editIsMulti = (editItem?.sourceTablesDetail?.length ?? 0) > 1;
    const multiSelectable = !isEdit || editIsMulti;
    const isMultiTable = selectedTables.length > 1;
    // 字段映射区当前生效的源表：多表用切换器选中的表，单表就是唯一勾选表
    const boundMappingTable = isMultiTable ? activeMappingTable : selectedTables[0];
    const currentFieldMapping = fieldMappings[boundMappingTable] || [];
    // 增量字段选项取第一张勾选表的列（后端 incrementalField 为任务级单值）
    const firstTableColumns = columnsCache[selectedTables[0]]?.columns || [];
    const firstTableColumnTypes = columnsCache[selectedTables[0]]?.types || {};

    const loadTargetDatabases = async () => {
        setTargetDbsLoading(true);
        try {
            const result = await listBuiltinDorisDatabases();
            setTargetDatabases(result.data || []);
        } catch {
            setTargetDatabases([]);
        } finally {
            setTargetDbsLoading(false);
        }
    };

    const loadTargetTables = useCallback(async (database: string) => {
        if (!database) {
            setTargetTables([]);
            return;
        }
        setTargetTablesLoading(true);
        try {
            const result = await listBuiltinDorisTables(database);
            setTargetTables(result.data || []);
        } catch {
            setTargetTables([]);
        } finally {
            setTargetTablesLoading(false);
        }
    }, []);

    const resolveDatabaseSchema = useCallback((datasourceId: string, selectedSchema: string) => {
        const ds = sourceDataSources.find((d) => d.id === datasourceId);
        if (!ds || !selectedSchema) return null;
        if (ds.type === DataSourceTypeEnum.POSTGRESQL || ds.type === DataSourceTypeEnum.ORACLE || ds.type === DataSourceTypeEnum.SQLSERVER) {
            return {sourceDatabase: ds.databaseName, sourceSchema: selectedSchema};
        }
        return {sourceDatabase: selectedSchema, sourceSchema: selectedSchema};
    }, [sourceDataSources]);

    const loadTables = useCallback(async (datasourceId: string, selectedSchema: string) => {
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
            const result = await getDataSourceTables(
                datasourceId,
                resolved.sourceDatabase,
                resolved.sourceSchema,
            );
            setTables(result.data || []);
        } finally {
            setTablesLoading(false);
        }
    }, [resolveDatabaseSchema]);

    const loadSchemas = useCallback(async (datasourceId: string, preselectSchema?: string) => {
        if (!datasourceId) {
            setSchemas([]);
            return;
        }
        setSchemasLoading(true);
        try {
            const result = await getDataSourceSchemas(datasourceId);
            const list = result.data || [];
            setSchemas(list);
            if (preselectSchema && list.includes(preselectSchema)) {
                loadTables(datasourceId, preselectSchema);
            }
        } finally {
            setSchemasLoading(false);
        }
    }, [loadTables]);

    // 拉取某张源表的列信息（预览接口）：写缓存 + 自动生成该表字段映射（保留已有同名映射）
    const loadTableColumns = useCallback(async (tableName: string) => {
        const resolved = resolveDatabaseSchema(form.sourceDatasourceId, form.selectedSchema);
        if (!resolved || !tableName) return;
        setColumnsLoading(true);
        try {
            const result = await previewDataSource(
                form.sourceDatasourceId,
                resolved.sourceDatabase,
                resolved.sourceSchema || resolved.sourceDatabase,
                tableName,
            );
            const columns = result.data.columns || [];
            const types = result.data.columnTypes || {};
            setColumnsCache(prev => ({...prev, [tableName]: {columns, types}}));
            setFieldMappings(prev => ({...prev, [tableName]: applyAutoMapping(columns, prev[tableName] || [])}));
            // 第一表的列变化后，清理失效的增量字段
            setForm(prev =>
                prev.incrementalField && !columns.includes(prev.incrementalField)
                    ? {...prev, incrementalField: ''}
                    : prev
            );
        } finally {
            setColumnsLoading(false);
        }
    }, [form.sourceDatasourceId, form.selectedSchema, resolveDatabaseSchema]);

    // 初始化：创建重置 / 编辑回显（含多表明细与限流）
    useEffect(() => {
        if (!open) return;
        setErrors({});
        setTableSearch('');
        setActiveMappingTable('');
        setColumnsCache({});
        if (editItem) {
            const selectedSchema = editItem.sourceSchema || editItem.sourceDatabase || '';
            setForm({
                name: editItem.name,
                sourceDatasourceId: editItem.sourceDatasourceId,
                selectedSchema,
                targetDatabase: editItem.targetDatabase || '',
                syncMode: editItem.syncMode,
                incrementalField: editItem.incrementalField || '',
                triggerType: editItem.triggerType,
                cronExpression: editItem.cronExpression || '',
                retryTimes: editItem.retryTimes ?? 3,
                retryInterval: editItem.retryInterval ?? 5,
                description: editItem.description || '',
            });
            // 多表明细优先（响应里 sourceTablesDetail 是对象数组）；单表任务走旧字段回显
            const details = editItem.sourceTablesDetail || [];
            if (details.length > 0) {
                setSelectedTables(details.map(d => d.sourceTable));
                setTableMappings(Object.fromEntries(details.map(d => [d.sourceTable, d.targetTable || d.sourceTable])));
                setFieldMappings(Object.fromEntries(details.map(d => [d.sourceTable, d.fieldMapping || []])));
                setActiveMappingTable(details[0].sourceTable);
            } else {
                const table = editItem.sourceTables?.[0] || '';
                setSelectedTables(table ? [table] : []);
                setTableMappings(table ? {[table]: editItem.targetTable || table} : {});
                setFieldMappings(table ? {[table]: editItem.fieldMapping || []} : {});
            }
            setRateLimitEnabled(!!editItem.rateLimitEnabled);
            setReadRateLimitMbps(editItem.readRateLimitMbps ? String(editItem.readRateLimitMbps) : '');
            setWriteRateLimitRowsPerSecond(editItem.writeRateLimitRowsPerSecond ? String(editItem.writeRateLimitRowsPerSecond) : '');
            loadSchemas(editItem.sourceDatasourceId, selectedSchema);
        } else {
            setForm(EMPTY_FORM);
            setSchemas([]);
            setTables([]);
            setSelectedTables([]);
            setTableMappings({});
            setFieldMappings({});
            setRateLimitEnabled(false);
            setReadRateLimitMbps('');
            setWriteRateLimitRowsPerSecond('');
        }
        loadTargetDatabases();
    }, [open, editItem, loadSchemas]);

    // 按需加载列信息：字段映射区当前表（多表）或唯一勾选表（单表）+ 第一张勾选表（增量字段选项来源）。
    // 合并在一个 effect 里用 Set 去重，避免单表模式（boundMappingTable === selectedTables[0]）重复拉取
    useEffect(() => {
        if (!form.sourceDatasourceId || !form.selectedSchema) {
            return;
        }
        const needed = new Set([boundMappingTable, selectedTables[0]].filter(Boolean) as string[]);
        needed.forEach(table => {
            if (!columnsCache[table]) {
                loadTableColumns(table);
            }
        });
    }, [boundMappingTable, selectedTables, columnsCache, form.sourceDatasourceId, form.selectedSchema, loadTableColumns]);

    useEffect(() => {
        if (form.targetDatabase) {
            loadTargetTables(form.targetDatabase);
        } else {
            setTargetTables([]);
        }
    }, [form.targetDatabase, loadTargetTables]);

    const isIncrementalRecommended = (column: string): boolean => {
        const type = (firstTableColumnTypes[column] || '').toLowerCase();
        if (!type) {
            return true;
        }
        return /\b(int|tinyint|smallint|mediumint|bigint|decimal|numeric|float|double|real|number|serial|date|time|datetime|timestamp|year)\b/.test(type);
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
        updateField('incrementalField', '');
        setTables([]);
        setSelectedTables([]);
        setTableMappings({});
        setFieldMappings({});
        setActiveMappingTable('');
        setColumnsCache({});
        loadSchemas(datasourceId);
    };

    const handleSchemaChange = (selectedSchema: string) => {
        updateField('selectedSchema', selectedSchema);
        updateField('incrementalField', '');
        setSelectedTables([]);
        setTableMappings({});
        setFieldMappings({});
        setActiveMappingTable('');
        setColumnsCache({});
        if (form.sourceDatasourceId && selectedSchema) {
            loadTables(form.sourceDatasourceId, selectedSchema);
        } else {
            setTables([]);
        }
    };

    /** 勾选/取消勾选源表：联动目标表映射行、字段映射切换器、hint 计数 */
    const toggleSourceTable = (table: string, checked: boolean) => {
        if (checked) {
            setSelectedTables(prev => [...prev, table]);
            setTableMappings(prev => ({...prev, [table]: prev[table] ?? table}));
            setActiveMappingTable(prev => prev || table);
        } else {
            setSelectedTables(prev => prev.filter(t => t !== table));
            setTableMappings(prev => {
                const next = {...prev};
                delete next[table];
                return next;
            });
            setFieldMappings(prev => {
                const next = {...prev};
                delete next[table];
                return next;
            });
            setActiveMappingTable(prev => {
                if (prev !== table) return prev;
                // 当前表被取消勾选：切到剩余第一张
                const remaining = selectedTables.filter(t => t !== table);
                return remaining[0] || '';
            });
        }
        if (errors.sourceTable) {
            setErrors(prev => ({...prev, sourceTable: undefined}));
        }
    };

    // 单表（锁定）模式切换源表：重置映射，目标表名跟随源表名
    const handleSingleSourceTableChange = (sourceTable: string) => {
        setSelectedTables(sourceTable ? [sourceTable] : []);
        setTableMappings(sourceTable ? {[sourceTable]: sourceTable} : {});
        setFieldMappings({});
        setColumnsCache({});
        updateField('incrementalField', '');
        if (errors.sourceTable) {
            setErrors(prev => ({...prev, sourceTable: undefined}));
        }
    };

    const updateTableMapping = (table: string, targetTable: string) => {
        setTableMappings(prev => ({...prev, [table]: targetTable}));
        if (errors.targetTable) {
            setErrors(prev => ({...prev, targetTable: undefined}));
        }
    };

    const handleTargetDatabaseChange = (targetDatabase: string) => {
        updateField('targetDatabase', targetDatabase);
        setTargetTables([]);
    };

    // 字段映射行操作（作用于 boundMappingTable 的映射）
    const updateMappingField = (index: number, key: keyof SyncFieldMapping, value: string) => {
        if (!boundMappingTable) return;
        setFieldMappings(prev => {
            const rows = [...(prev[boundMappingTable] || [])];
            rows[index] = {...rows[index], [key]: value};
            return {...prev, [boundMappingTable]: rows};
        });
        if (errors.fieldMapping) {
            setErrors(prev => ({...prev, fieldMapping: undefined}));
        }
    };

    const addMappingRow = () => {
        if (!boundMappingTable) return;
        setFieldMappings(prev => ({
            ...prev,
            [boundMappingTable]: [...(prev[boundMappingTable] || []), {sourceColumn: '', targetColumn: ''}],
        }));
    };

    const removeMappingRow = (index: number) => {
        if (!boundMappingTable) return;
        setFieldMappings(prev => ({
            ...prev,
            [boundMappingTable]: (prev[boundMappingTable] || []).filter((_, i) => i !== index),
        }));
    };

    const validate = (): boolean => {
        const nextErrors: Partial<Record<ErrorKey, string>> = {};
        if (!form.name.trim()) nextErrors.name = '请输入任务名称';
        if (!form.sourceDatasourceId) nextErrors.sourceDatasourceId = '请选择源数据源';
        if (!form.selectedSchema) nextErrors.selectedSchema = '请选择源库 / Schema';
        if (selectedTables.length === 0) nextErrors.sourceTable = '请至少选择一个源表';
        if (!form.targetDatabase.trim()) nextErrors.targetDatabase = '请选择目标 Doris 库';
        // 每张勾选源表都必须有目标表名（多表批量映射，PRD §6.9.2）
        if (selectedTables.some(t => !(tableMappings[t] || '').trim())) {
            nextErrors.targetTable = '每个源表都必须填写目标表名';
        }
        // 多表模式：每张表的字段映射不能为空（技术文档 §12.2 保存前校验）
        if (isMultiTable && selectedTables.some(t =>
            !(fieldMappings[t] || []).some(row => row.sourceColumn.trim() || row.targetColumn.trim()))) {
            nextErrors.fieldMapping = '多表模式下每个源表的字段映射不能为空';
        }
        if (!form.syncMode) nextErrors.syncMode = '请选择同步模式';
        if (form.syncMode === SyncModeEnum.INCREMENTAL && !form.incrementalField) {
            nextErrors.incrementalField = '请选择增量字段';
        }
        if (!form.triggerType) nextErrors.triggerType = '请选择触发方式';
        if (form.triggerType === TaskTriggerTypeEnum.CRON && !form.cronExpression.trim()) {
            nextErrors.cronExpression = 'Cron 触发必须填写 Cron 表达式';
        }
        if (form.retryTimes < 0 || form.retryTimes > 3) {
            nextErrors.retryTimes = '重试次数需在 0-3 之间';
        }
        if (form.retryInterval < 1 || form.retryInterval > 30) {
            nextErrors.retryInterval = '重试间隔需在 1-30 分钟之间';
        }
        if (rateLimitEnabled) {
            if (readRateLimitMbps && (Number.isNaN(Number(readRateLimitMbps)) || Number(readRateLimitMbps) <= 0)) {
                nextErrors.rateLimit = '读取速率上限必须是大于 0 的数字';
            }
            if (writeRateLimitRowsPerSecond && (Number.isNaN(Number(writeRateLimitRowsPerSecond)) || Number(writeRateLimitRowsPerSecond) <= 0)) {
                nextErrors.rateLimit = '写入速率上限必须是大于 0 的数字';
            }
        }
        setErrors(nextErrors);
        return Object.keys(nextErrors).length === 0;
    };

    const buildValidMapping = (table: string): SyncFieldMapping[] | undefined => {
        const valid = (fieldMappings[table] || []).filter((row) => row.sourceColumn.trim() || row.targetColumn.trim());
        if (valid.length === 0) return undefined;
        return valid.map((row) => ({
            sourceColumn: row.sourceColumn.trim(),
            targetColumn: row.targetColumn.trim(),
            targetType: row.targetType?.trim() || undefined,
        }));
    };

    const buildPayload = (): SyncJobCreateRequest => {
        const resolved = resolveDatabaseSchema(form.sourceDatasourceId, form.selectedSchema);
        const details: SourceTableDetail[] = selectedTables.map(t => ({
            sourceTable: t,
            targetTable: (tableMappings[t] || '').trim(),
            fieldMapping: buildValidMapping(t),
        }));
        const first = details[0];
        const base: SyncJobCreateRequest = {
            name: form.name.trim(),
            sourceDatasourceId: form.sourceDatasourceId,
            sourceDatabase: resolved?.sourceDatabase,
            sourceSchema: resolved?.sourceSchema,
            sourceTables: selectedTables,
            syncMode: form.syncMode,
            incrementalField: form.syncMode === SyncModeEnum.INCREMENTAL ? form.incrementalField : undefined,
            targetDatabase: form.targetDatabase.trim(),
            // 顶层 targetTable / fieldMapping 取第一张表：兼容后端必填校验与单表旧行为
            targetTable: first?.targetTable || '',
            fieldMapping: first?.fieldMapping,
            // 多表提交明细（JSON 字符串；后端按 sourceTables 多表生成 Addax content）。
            // editIsMulti 兜底：已有多表任务即使被减到只剩 1 张表也保持多表口径，避免静默降级为单表（PRD §6.9.5）
            sourceTablesDetail: (isMultiTable || editIsMulti) ? JSON.stringify(details) : undefined,
            triggerType: form.triggerType,
            cronExpression: form.triggerType === TaskTriggerTypeEnum.CRON ? form.cronExpression.trim() : undefined,
            retryTimes: Number(form.retryTimes),
            retryInterval: Number(form.retryInterval),
            rateLimitEnabled,
            readRateLimitMbps: rateLimitEnabled && readRateLimitMbps ? Number(readRateLimitMbps) : undefined,
            writeRateLimitRowsPerSecond: rateLimitEnabled && writeRateLimitRowsPerSecond ? Number(writeRateLimitRowsPerSecond) : undefined,
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
            if (result) {
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
    const schemaLabel = selectedSource?.type && selectedSource.type !== DataSourceTypeEnum.MYSQL && selectedSource.type !== DataSourceTypeEnum.DORIS ? 'Schema' : '数据库';
    // 多表 checkbox 列表：搜索过滤后的可选源表
    const filteredTables = tableSearch.trim()
        ? tables.filter(t => t.toLowerCase().includes(tableSearch.trim().toLowerCase()))
        : tables;

    return (
        <Drawer
            open={open}
            title={isView ? '详情' : isEdit ? '编辑同步任务' : '创建同步任务'}
            width="max-w-[640px]"
            onClose={onClose}
            footer={
                isView ? (
                    <DsButton
                        variant="secondary"
                        data-testid="sync-job-close"
                        onClick={onClose}
                    >
                        关闭
                    </DsButton>
                ) : (
                    <>
                        <DsButton
                            variant="secondary"
                            data-testid="sync-job-cancel"
                            onClick={onClose}
                        >
                            取消
                        </DsButton>
                        <DsButton
                            data-testid="sync-job-submit-run"
                            onClick={() => handleSubmit(true)}
                            disabled={submitting}
                            loading={submitting}
                        >
                            保存并立即执行
                        </DsButton>
                        <DsButton
                            variant="secondary"
                            data-testid="sync-job-submit"
                            onClick={() => handleSubmit(false)}
                            disabled={submitting}
                            loading={submitting}
                        >
                            保存
                        </DsButton>
                    </>
                )
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
                        disabled={isEdit || isView}
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
                                disabled={isView}
                                className="w-full px-ds-3 py-ds-2 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors disabled:opacity-60 disabled:cursor-not-allowed"
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
                                disabled={!form.sourceDatasourceId || schemasLoading || isView}
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
                            {multiSelectable ? (
                                // 多选模式（创建 / 已有多表任务编辑）：搜索 + checkbox 列表
                                <>
                                    <input
                                        data-testid="sync-job-source-table-search"
                                        value={tableSearch}
                                        onChange={(e) => setTableSearch(e.target.value)}
                                        disabled={!form.selectedSchema || tablesLoading || isView}
                                        placeholder="搜索表名"
                                        className="w-full mb-ds-2 px-ds-3 py-ds-2 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-small text-ds-text-primary focus:outline-none focus-visible:border-ds-accent transition-colors disabled:opacity-60 disabled:cursor-not-allowed"
                                    />
                                    <div
                                        className="border border-ds-border-subtle rounded-ds-sm max-h-40 overflow-auto divide-y divide-ds-border-subtle">
                                        {tablesLoading ? (
                                            <p className="px-ds-3 py-ds-2 text-ds-small text-ds-text-muted">加载中...</p>
                                        ) : filteredTables.length === 0 ? (
                                            <p className="px-ds-3 py-ds-2 text-ds-small text-ds-text-muted">
                                                {form.selectedSchema ? '无可选源表' : '请先选择源库 / Schema'}
                                            </p>
                                        ) : (
                                            filteredTables.map(t => (
                                                <label key={t}
                                                       data-testid={`sync-job-source-table-option-${t}`}
                                                       className="flex items-center gap-ds-2 px-ds-3 py-ds-2 text-ds-small text-ds-text-primary hover:bg-ds-bg-hover cursor-pointer">
                                                    <input
                                                        type="checkbox"
                                                        checked={selectedTables.includes(t)}
                                                        onChange={(e) => toggleSourceTable(t, e.target.checked)}
                                                        disabled={isView}
                                                    />
                                                    {t}
                                                </label>
                                            ))
                                        )}
                                    </div>
                                    <p className="mt-ds-1 text-ds-nano text-ds-text-muted">
                                        已选 {selectedTables.length} 个源表
                                    </p>
                                </>
                            ) : (
                                // 单表锁定模式（Sprint 3 单表任务编辑，PRD §6.9.5：不可切换为多表）
                                <select
                                    data-testid="sync-job-source-table"
                                    value={selectedTables[0] || ''}
                                    onChange={(e) => handleSingleSourceTableChange(e.target.value)}
                                    disabled={!form.selectedSchema || tablesLoading || isView}
                                    className="w-full px-ds-3 py-ds-2 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors disabled:opacity-60 disabled:cursor-not-allowed"
                                >
                                    <option value="">{tablesLoading ? '加载中...' : '请选择'}</option>
                                    {selectedTables[0] && !tables.includes(selectedTables[0]) && (
                                        <option value={selectedTables[0]}>{selectedTables[0]}</option>
                                    )}
                                    {tables.map((t) => (
                                        <option key={t} value={t}
                                                data-testid={`sync-job-source-table-option-${t}`}>{t}</option>
                                    ))}
                                </select>
                            )}
                            {errors.sourceTable && (
                                <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.sourceTable}</p>
                            )}
                        </div>
                    </div>
                </div>

                <div className="border-t border-ds-border-subtle pt-ds-4">
                    <h3 className="text-ds-small font-semibold text-ds-text-secondary mb-ds-2">目标端配置（Doris
                        数仓）</h3>
                    <div className="space-y-ds-4">
                        <div>
                            <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                                目标 Doris 库 <span className="text-ds-danger">*</span>
                            </label>
                            <select
                                data-testid="sync-job-target-database"
                                value={form.targetDatabase}
                                onChange={(e) => handleTargetDatabaseChange(e.target.value)}
                                disabled={targetDbsLoading || isView}
                                className="w-full px-ds-3 py-ds-2 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors disabled:opacity-60 disabled:cursor-not-allowed"
                            >
                                <option value="">{targetDbsLoading ? '加载中...' : '请选择'}</option>
                                {form.targetDatabase && !targetDatabases.includes(form.targetDatabase) && (
                                    <option value={form.targetDatabase}>{form.targetDatabase}</option>
                                )}
                                {targetDatabases.map((db) => (
                                    <option key={db} value={db}
                                            data-testid={`sync-job-target-database-option-${db}`}>{db}</option>
                                ))}
                            </select>
                            {errors.targetDatabase && (
                                <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.targetDatabase}</p>
                            )}
                        </div>

                        {!isMultiTable ? (
                            <div>
                                <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                                    目标表名 <span className="text-ds-danger">*</span>
                                </label>
                                <select
                                    data-testid="sync-job-target-table"
                                    value={tableMappings[selectedTables[0]] || ''}
                                    onChange={(e) => selectedTables[0] && updateTableMapping(selectedTables[0], e.target.value)}
                                    disabled={!form.targetDatabase || targetTablesLoading || isView || !selectedTables[0]}
                                    className="w-full px-ds-3 py-ds-2 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors disabled:opacity-60 disabled:cursor-not-allowed"
                                >
                                    <option value="">{targetTablesLoading ? '加载中...' : '请选择'}</option>
                                    {selectedTables[0] && tableMappings[selectedTables[0]] && !targetTables.includes(tableMappings[selectedTables[0]]) && (
                                        <option value={tableMappings[selectedTables[0]]}>
                                            {tableMappings[selectedTables[0]]}
                                        </option>
                                    )}
                                    {targetTables.map((t) => (
                                        <option key={t} value={t}
                                                data-testid={`sync-job-target-table-option-${t}`}>{t}</option>
                                    ))}
                                </select>
                            </div>
                        ) : (
                            // 多表批量映射：源表 → 目标表名，默认同名可逐个修改（PRD §6.9.2）
                            <div>
                                <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                                    源表 → 目标表映射 <span className="text-ds-danger">*</span>
                                </label>
                                <div className="border border-ds-border-subtle rounded-ds-sm overflow-hidden">
                                    <table className="w-full text-left">
                                        <thead className="bg-ds-bg-hover">
                                        <tr>
                                            <th className="px-ds-3 py-ds-1.5 text-ds-caption text-ds-text-primary font-semibold w-1/2">源表</th>
                                            <th className="px-ds-3 py-ds-1.5 text-ds-caption text-ds-text-primary font-semibold">目标表名</th>
                                        </tr>
                                        </thead>
                                        <tbody>
                                        {selectedTables.map(t => (
                                            <tr key={t} className="border-t border-ds-border-subtle">
                                                <td className="px-ds-3 py-ds-1.5 text-ds-small text-ds-text-secondary font-mono">
                                                    {t}
                                                </td>
                                                <td className="px-ds-3 py-ds-1.5">
                                                    <input
                                                        data-testid={`sync-job-target-table-input-${t}`}
                                                        value={tableMappings[t] || ''}
                                                        onChange={(e) => updateTableMapping(t, e.target.value)}
                                                        disabled={isView}
                                                        placeholder={t}
                                                        className="w-full px-ds-2 py-ds-1 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-small text-ds-text-primary focus:outline-none focus-visible:border-ds-accent transition-colors disabled:opacity-60 disabled:cursor-not-allowed"
                                                    />
                                                </td>
                                            </tr>
                                        ))}
                                        </tbody>
                                    </table>
                                </div>
                                <p className="mt-ds-1 text-ds-nano text-ds-text-muted">
                                    默认：目标表名 = 源表名，可逐个修改
                                </p>
                            </div>
                        )}
                        {errors.targetTable && (
                            <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.targetTable}</p>
                        )}
                    </div>
                </div>

                <div className="border-t border-ds-border-subtle pt-ds-4">
                    <h3 className="text-ds-small font-semibold text-ds-text-secondary mb-ds-2">字段映射</h3>
                    {isMultiTable && (
                        // 多表模式：按源表逐个配置（技术文档 §12.2），切换器选择当前配置的源表
                        <select
                            data-testid="sync-job-mapping-table-switcher"
                            value={activeMappingTable}
                            onChange={(e) => setActiveMappingTable(e.target.value)}
                            disabled={isView}
                            className="w-full mb-ds-2 px-ds-3 py-ds-2 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-small text-ds-text-primary focus:outline-none focus-visible:border-ds-accent transition-colors disabled:opacity-60 disabled:cursor-not-allowed"
                        >
                            {selectedTables.map(t => (
                                <option key={t} value={t}>配置源表：{t}</option>
                            ))}
                        </select>
                    )}
                    <div className="border border-ds-border-subtle rounded-ds-sm p-ds-3 bg-ds-bg-hover space-y-ds-2">
                        {!boundMappingTable ? (
                            <p className="text-ds-small text-ds-text-muted">选择源表后将自动匹配字段</p>
                        ) : columnsLoading && currentFieldMapping.length === 0 ? (
                            <p className="text-ds-small text-ds-text-muted">加载字段中...</p>
                        ) : currentFieldMapping.length === 0 ? (
                            <p className="text-ds-small text-ds-text-muted">选择源表后将自动匹配字段</p>
                        ) : (
                            currentFieldMapping.map((row, index) => (
                                <div key={index} className="flex items-center gap-ds-2">
                                    <input
                                        data-testid={`sync-job-mapping-source-${index}`}
                                        value={row.sourceColumn}
                                        onChange={(e) => updateMappingField(index, 'sourceColumn', e.target.value)}
                                        disabled={isView}
                                        placeholder="源字段"
                                        className="flex-1 min-w-0 px-ds-2 py-ds-1.5 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-small text-ds-text-primary focus:outline-none focus-visible:border-ds-accent transition-colors disabled:opacity-60 disabled:cursor-not-allowed"
                                    />
                                    <span className="text-ds-small text-ds-text-muted">→</span>
                                    <input
                                        data-testid={`sync-job-mapping-target-${index}`}
                                        value={row.targetColumn}
                                        onChange={(e) => updateMappingField(index, 'targetColumn', e.target.value)}
                                        disabled={isView}
                                        placeholder="目标字段"
                                        className="flex-1 min-w-0 px-ds-2 py-ds-1.5 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-small text-ds-text-primary focus:outline-none focus-visible:border-ds-accent transition-colors disabled:opacity-60 disabled:cursor-not-allowed"
                                    />
                                    <input
                                        data-testid={`sync-job-mapping-type-${index}`}
                                        value={row.targetType || ''}
                                        onChange={(e) => updateMappingField(index, 'targetType', e.target.value)}
                                        disabled={isView}
                                        placeholder="目标类型(可选)"
                                        className="w-[110px] px-ds-2 py-ds-1.5 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-small text-ds-text-primary focus:outline-none focus-visible:border-ds-accent transition-colors disabled:opacity-60 disabled:cursor-not-allowed"
                                    />
                                    <DsIconButton
                                        tone="danger"
                                        data-testid={`sync-job-mapping-remove-${index}`}
                                        onClick={() => removeMappingRow(index)}
                                        disabled={isView}
                                        title="删除"
                                        aria-label="删除"
                                    >
                                        <HiOutlineTrash size={16}/>
                                    </DsIconButton>
                                </div>
                            ))
                        )}
                        <button
                            type="button"
                            data-testid="sync-job-mapping-add"
                            onClick={addMappingRow}
                            disabled={isView || !boundMappingTable}
                            className="flex items-center gap-ds-1 text-ds-small text-ds-accent hover:text-ds-accent-hover font-medium transition-colors disabled:opacity-60 disabled:cursor-not-allowed"
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
                                        disabled={isView}
                                        className={`flex-1 px-ds-4 py-ds-3 rounded-ds-sm border text-left transition-colors disabled:opacity-60 disabled:cursor-not-allowed ${
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

                        {form.syncMode === SyncModeEnum.INCREMENTAL && (
                            <div>
                                <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                                    增量字段 <span className="text-ds-danger">*</span>
                                </label>
                                <select
                                    data-testid="sync-job-incremental-field"
                                    value={form.incrementalField}
                                    onChange={(e) => updateField('incrementalField', e.target.value)}
                                    disabled={firstTableColumns.length === 0 || columnsLoading || isView}
                                    className="w-full px-ds-3 py-ds-2 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors disabled:opacity-60 disabled:cursor-not-allowed"
                                >
                                    <option value="">请选择</option>
                                    {firstTableColumns.map((col) => {
                                        const type = firstTableColumnTypes[col];
                                        const recommended = isIncrementalRecommended(col);
                                        return (
                                            <option key={col} value={col} disabled={!recommended}>
                                                {col}{type ? ` (${type})` : ''}{!recommended ? ' - 不推荐' : ''}
                                            </option>
                                        );
                                    })}
                                </select>
                                <p className="mt-ds-1 text-ds-nano text-ds-text-muted">
                                    建议选择数值型（int / bigint / decimal 等）或时间型（date / datetime / timestamp）字段
                                </p>
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
                                        disabled={isView}
                                        className={`flex-1 px-ds-4 py-ds-3 rounded-ds-sm border text-left transition-colors disabled:opacity-60 disabled:cursor-not-allowed ${
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

                        {form.triggerType === TaskTriggerTypeEnum.CRON && (
                            <div>
                                <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                                    Cron 表达式 <span className="text-ds-danger">*</span>
                                </label>
                                {isView ? (
                                    <div
                                        className="px-ds-3 py-ds-2 bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary">
                                        {form.cronExpression || '-'}
                                    </div>
                                ) : (
                                    <CronPicker
                                        value={form.cronExpression}
                                        onChange={(v) => updateField('cronExpression', v)}
                                    />
                                )}
                                {errors.cronExpression && !isView && (
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
                                disabled={isView}
                                className="w-full px-ds-3 py-ds-2 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors disabled:opacity-60 disabled:cursor-not-allowed"
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
                                disabled={isView}
                                className="w-full px-ds-3 py-ds-2 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors disabled:opacity-60 disabled:cursor-not-allowed"
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

                {/* Sprint 4：限流配置（可选）。读取按 MB/s（源库网络/IO），写入按 行/s（Doris 写入行数） */}
                <div className="border-t border-ds-border-subtle pt-ds-4">
                    <h3 className="text-ds-small font-semibold text-ds-text-secondary mb-ds-2">限流配置（可选）</h3>
                    <label className="flex items-center gap-ds-2 text-ds-body text-ds-text-primary mb-ds-3">
                        <input
                            type="checkbox"
                            data-testid="sync-job-rate-limit-enabled"
                            checked={rateLimitEnabled}
                            onChange={(e) => setRateLimitEnabled(e.target.checked)}
                            disabled={isView}
                        />
                        启用速率限制
                    </label>
                    <div className="grid grid-cols-2 gap-ds-4">
                        <div>
                            <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                                读取速率上限（MB/s）
                            </label>
                            <input
                                type="number"
                                min={0}
                                data-testid="sync-job-read-rate-limit"
                                value={readRateLimitMbps}
                                onChange={(e) => setReadRateLimitMbps(e.target.value)}
                                disabled={!rateLimitEnabled || isView}
                                placeholder="不填表示不限制"
                                className="w-full px-ds-3 py-ds-2 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors disabled:opacity-60 disabled:cursor-not-allowed"
                            />
                        </div>
                        <div>
                            <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                                写入速率上限（行/s）
                            </label>
                            <input
                                type="number"
                                min={0}
                                data-testid="sync-job-write-rate-limit"
                                value={writeRateLimitRowsPerSecond}
                                onChange={(e) => setWriteRateLimitRowsPerSecond(e.target.value)}
                                disabled={!rateLimitEnabled || isView}
                                placeholder="不填表示不限制"
                                className="w-full px-ds-3 py-ds-2 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors disabled:opacity-60 disabled:cursor-not-allowed"
                            />
                        </div>
                    </div>
                    {errors.rateLimit && (
                        <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.rateLimit}</p>
                    )}
                </div>

                <div>
                    <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">描述</label>
                    <textarea
                        data-testid="sync-job-description"
                        value={form.description}
                        onChange={(e) => updateField('description', e.target.value)}
                        rows={3}
                        disabled={isView}
                        className="w-full px-ds-3 py-ds-2 bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors resize-none disabled:bg-ds-bg-disabled disabled:text-ds-text-muted"
                        placeholder="可选：填写同步任务的业务说明"
                    />
                </div>
            </div>
        </Drawer>
    );
}
