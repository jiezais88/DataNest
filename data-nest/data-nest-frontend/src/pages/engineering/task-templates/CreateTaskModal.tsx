// Sprint 7 F2：从模板一键创建任务弹窗
// 按 configTemplate.placeholders 动态渲染表单（2026-08-10 扩展下拉类型）：
//   DATASOURCE        数据源下拉（值为数据源 ID）
//   SOURCE_DATABASE   源库/Schema 下拉（依赖数据源；有模式 PG/Oracle/SQLServer 显示 库.schema）
//   SOURCE_TABLE      源表下拉（依赖数据源 + 源库/Schema）
//   INCREMENTAL_FIELD 增量字段下拉（依赖源表，取源表列）
//   TARGET_DATABASE   Doris 目标库下拉
//   TARGET_TABLE      Doris 目标表下拉（依赖目标库）
//   SCOPE             采集库/Schema 下拉（依赖数据源，单选）
//   TEXT              文本框（兜底）
// 提交时对 SOURCE_DATABASE 做有模式适配：有模式 → source_db=数据源库名 + source_schema=选中 Schema；
// 无模式 → source_db=source_schema=选中库（对齐普通表单 resolveDatabaseSchema 语义）。
// defaultValue 预填；required 为空时前端拦截，后端兜底 7305。
import {useCallback, useEffect, useState} from 'react';
import {Select} from 'antd';
import {getDataSources} from '@/api/datasource';
import {getDataSourceSchemas, getDataSourceTables} from '@/api/engineering';
import {listBuiltinDorisDatabases, listBuiltinDorisTables} from '@/api/metadata';
import {previewDataSource} from '@/api/preview';
import {createTaskFromTemplate} from '@/api/taskTemplate';
import DsButton from '@/components/DsButton';
import DsModal from '@/components/DsModal';
import {notify} from '@/utils/notify';
import type {DataSource} from '@/types/datasource';
import {DataSourceTypeEnum} from '@/constants/datasource';
import type {TaskTemplate, TemplatePlaceholder} from '@/types/taskTemplate';
import {TASK_TEMPLATE_TYPE_LABEL} from '@/types/taskTemplate';

/** 解析模板占位符（configTemplate 非法 JSON 时按无占位符处理） */
export function parseTemplatePlaceholders(configTemplate?: string): TemplatePlaceholder[] {
    if (!configTemplate) return [];
    try {
        const parsed = JSON.parse(configTemplate);
        return Array.isArray(parsed?.placeholders) ? parsed.placeholders : [];
    } catch {
        return [];
    }
}

interface CreateTaskModalProps {
    open: boolean;
    template: TaskTemplate | null;
    onClose: () => void;
}

const inputClass = 'w-full px-ds-3 py-[9px] bg-white border border-ds-border-subtle rounded-ds-sm text-sm focus:outline-none focus:border-ds-accent font-mono';

/** 有模式数据源（库 + Schema 两级）：PG / Oracle / SQL Server */
function isSchemaType(type?: string): boolean {
    return type === DataSourceTypeEnum.POSTGRESQL
        || type === DataSourceTypeEnum.ORACLE
        || type === DataSourceTypeEnum.SQLSERVER;
}

/** 源库下拉展示：PG 显示 库.schema（对齐普通表单 buildSchemaLabel），其余直接显示 */
function schemaLabel(ds: DataSource | undefined, schema: string): string {
    if (ds?.type === DataSourceTypeEnum.POSTGRESQL) {
        return `${ds.databaseName}.${schema}`;
    }
    return schema;
}

/** 库/Schema 解析（对齐普通表单 resolveDatabaseSchema）：有模式库=数据源库名；无模式库即 Schema */
function resolveDatabaseSchema(ds: DataSource, selectedSchema: string) {
    if (isSchemaType(ds.type)) {
        return {sourceDatabase: ds.databaseName, sourceSchema: selectedSchema};
    }
    return {sourceDatabase: selectedSchema, sourceSchema: selectedSchema};
}

export default function CreateTaskModal({open, template, onClose}: CreateTaskModalProps) {
    const [taskName, setTaskName] = useState('');
    const [values, setValues] = useState<Record<string, string>>({});
    const [placeholders, setPlaceholders] = useState<TemplatePlaceholder[]>([]);
    const [datasourceOptions, setDatasourceOptions] = useState<DataSource[]>([]);
    const [schemas, setSchemas] = useState<string[]>([]);
    const [sourceTables, setSourceTables] = useState<string[]>([]);
    const [fieldOptions, setFieldOptions] = useState<string[]>([]);
    const [targetDatabases, setTargetDatabases] = useState<string[]>([]);
    const [targetTables, setTargetTables] = useState<string[]>([]);
    const [loadingSchemas, setLoadingSchemas] = useState(false);
    const [loadingTables, setLoadingTables] = useState(false);
    const [loadingFields, setLoadingFields] = useState(false);
    const [loadingTargetDbs, setLoadingTargetDbs] = useState(false);
    const [loadingTargetTables, setLoadingTargetTables] = useState(false);
    const [saving, setSaving] = useState(false);

    /** 取某类型的占位符 key（内置模板每种类型唯一；自定义模板取第一个） */
    const keyOf = useCallback((vt: TemplatePlaceholder['valueType']) =>
        placeholders.find(p => p.valueType === vt)?.key, [placeholders]);

    const dsKey = keyOf('DATASOURCE');
    const dsId = dsKey ? (values[dsKey] ?? '') : '';
    const selectedDs = datasourceOptions.find(d => d.id === dsId);

    const loadSchemas = useCallback(async (id: string) => {
        if (!id) {
            setSchemas([]);
            return;
        }
        setLoadingSchemas(true);
        try {
            const res = await getDataSourceSchemas(id);
            setSchemas(res.data || []);
        } catch {
            setSchemas([]);
        } finally {
            setLoadingSchemas(false);
        }
    }, []);

    const loadSourceTables = useCallback(async (id: string, schema: string, ds: DataSource) => {
        if (!id || !schema) {
            setSourceTables([]);
            return;
        }
        const resolved = resolveDatabaseSchema(ds, schema);
        setLoadingTables(true);
        try {
            const res = await getDataSourceTables(id, resolved.sourceDatabase, resolved.sourceSchema);
            setSourceTables(res.data || []);
        } catch {
            setSourceTables([]);
        } finally {
            setLoadingTables(false);
        }
    }, []);

    const loadFieldOptions = useCallback(async (id: string, schema: string, table: string, ds: DataSource) => {
        if (!id || !table) {
            setFieldOptions([]);
            return;
        }
        const resolved = resolveDatabaseSchema(ds, schema);
        setLoadingFields(true);
        try {
            const res = await previewDataSource(id, resolved.sourceDatabase, resolved.sourceSchema, table);
            setFieldOptions(res.data?.columns || []);
        } catch {
            setFieldOptions([]);
        } finally {
            setLoadingFields(false);
        }
    }, []);

    const loadTargetDatabases = useCallback(async () => {
        setLoadingTargetDbs(true);
        try {
            const res = await listBuiltinDorisDatabases();
            setTargetDatabases(res.data || []);
        } catch {
            setTargetDatabases([]);
        } finally {
            setLoadingTargetDbs(false);
        }
    }, []);

    const loadTargetTables = useCallback(async (db: string) => {
        if (!db) {
            setTargetTables([]);
            return;
        }
        setLoadingTargetTables(true);
        try {
            const res = await listBuiltinDorisTables(db);
            setTargetTables(res.data || []);
        } catch {
            setTargetTables([]);
        } finally {
            setLoadingTargetTables(false);
        }
    }, []);

    useEffect(() => {
        if (!open || !template) return;
        setTaskName('');
        const phs = parseTemplatePlaceholders(template.configTemplate);
        setPlaceholders(phs);
        // defaultValue 预填
        const init: Record<string, string> = {};
        for (const ph of phs) {
            if (ph.defaultValue) init[ph.key] = ph.defaultValue;
        }
        setValues(init);
        setSchemas([]);
        setSourceTables([]);
        setFieldOptions([]);
        setTargetTables([]);
        // 数据源下拉 + Doris 目标库（无依赖，模板一打开就拉）
        getDataSources({page: 1, pageSize: 100})
            .then(res => setDatasourceOptions(res.data?.records ?? []))
            .catch(() => setDatasourceOptions([]));
        loadTargetDatabases();
    }, [open, template, loadTargetDatabases]);

    /** 清空指定 valueType 占位符的值（级联下游：数据源变化 → 清库/表/字段） */
    const clearValues = (vts: TemplatePlaceholder['valueType'][]) => {
        setValues(prev => {
            const next = {...prev};
            for (const vt of vts) {
                const k = keyOf(vt);
                if (k) next[k] = '';
            }
            return next;
        });
    };

    const handleChange = (key: string, value: string) => {
        const ph = placeholders.find(p => p.key === key);
        setValues(prev => ({...prev, [key]: value}));
        if (!ph) return;
        switch (ph.valueType) {
            case 'DATASOURCE':
                clearValues(['SOURCE_DATABASE', 'SOURCE_TABLE', 'INCREMENTAL_FIELD', 'SCOPE']);
                loadSchemas(value);
                break;
            case 'SOURCE_DATABASE': {
                clearValues(['SOURCE_TABLE', 'INCREMENTAL_FIELD']);
                const curDs = datasourceOptions.find(d => d.id === dsId);
                if (value && curDs) loadSourceTables(dsId, value, curDs);
                break;
            }
            case 'SOURCE_TABLE': {
                clearValues(['INCREMENTAL_FIELD']);
                const curDs = datasourceOptions.find(d => d.id === dsId);
                const dbKey = keyOf('SOURCE_DATABASE');
                const schema = dbKey ? (values[dbKey] ?? '') : '';
                if (value && curDs && schema) loadFieldOptions(dsId, schema, value, curDs);
                break;
            }
            case 'TARGET_DATABASE':
                clearValues(['TARGET_TABLE']);
                loadTargetTables(value);
                break;
            case 'SCOPE':
                // 无下游，仅记录值
                break;
            default:
                break;
        }
    };

    const handleSubmit = async () => {
        if (!template) return;
        if (!taskName.trim()) {
            notify.warning('请输入任务名称');
            return;
        }
        for (const ph of placeholders) {
            if (ph.required && !values[ph.key]?.trim()) {
                notify.warning(`请填写「${ph.label}」（{${ph.key}}）`);
                return;
            }
        }
        setSaving(true);
        try {
            // 提交前做 SOURCE_DATABASE 有模式适配：
            // 有模式 → source_db=数据源库名 + source_schema=选中 Schema；无模式 → 两者同值
            const submitValues: Record<string, string> = {...values};
            const sdKey = keyOf('SOURCE_DATABASE');
            if (sdKey && submitValues[sdKey] && selectedDs) {
                const schema = submitValues[sdKey];
                if (isSchemaType(selectedDs.type)) {
                    submitValues[sdKey] = selectedDs.databaseName;
                    submitValues.source_schema = schema;
                } else {
                    submitValues.source_schema = schema;
                }
            }
            await createTaskFromTemplate(template.id, {name: taskName.trim(), values: submitValues});
            notify.success(`已创建${TASK_TEMPLATE_TYPE_LABEL[template.type]}「${taskName.trim()}」`);
            onClose();
        } catch {
            // 7305 占位符缺失 / 7306 配置非法 / 7307 模板停用等由拦截器统一提示
        } finally {
            setSaving(false);
        }
    };

    const renderField = (ph: TemplatePlaceholder) => {
        const disabledTip = (() => {
            if (ph.valueType === 'SOURCE_DATABASE' || ph.valueType === 'SCOPE') return dsId ? undefined : '请先选择数据源';
            if (ph.valueType === 'SOURCE_TABLE') return keyOf('SOURCE_DATABASE') && values[keyOf('SOURCE_DATABASE')!] ? undefined : '请先选择源库/Schema';
            if (ph.valueType === 'INCREMENTAL_FIELD') return keyOf('SOURCE_TABLE') && values[keyOf('SOURCE_TABLE')!] ? undefined : '请先选择源表';
            if (ph.valueType === 'TARGET_TABLE') return keyOf('TARGET_DATABASE') && values[keyOf('TARGET_DATABASE')!] ? undefined : '请先选择目标库';
            return undefined;
        })();

        switch (ph.valueType) {
            case 'DATASOURCE':
                return (
                    <Select
                        showSearch
                        allowClear
                        value={values[ph.key] || undefined}
                        onChange={(v) => handleChange(ph.key, v ?? '')}
                        placeholder="选择数据源"
                        options={datasourceOptions.map(d => ({
                            value: d.id,
                            label: `${d.name} (${d.host}:${d.port}/${d.databaseName})`,
                        }))}
                        className="w-full"
                        optionFilterProp="label"
                    />
                );
            case 'SOURCE_DATABASE':
            case 'SCOPE':
                return (
                    <Select
                        showSearch
                        allowClear
                        value={values[ph.key] || undefined}
                        onChange={(v) => handleChange(ph.key, v ?? '')}
                        placeholder={disabledTip ?? (ph.valueType === 'SCOPE' ? '选择采集库/Schema' : '选择源库/Schema')}
                        options={schemas.map(s => ({value: s, label: ph.valueType === 'SCOPE' ? s : schemaLabel(selectedDs, s)}))}
                        className="w-full"
                        loading={loadingSchemas}
                        disabled={!!disabledTip}
                        optionFilterProp="label"
                    />
                );
            case 'SOURCE_TABLE':
                return (
                    <Select
                        showSearch
                        allowClear
                        value={values[ph.key] || undefined}
                        onChange={(v) => handleChange(ph.key, v ?? '')}
                        placeholder={disabledTip ?? '选择源表'}
                        options={sourceTables.map(t => ({value: t, label: t}))}
                        className="w-full"
                        loading={loadingTables}
                        disabled={!!disabledTip}
                        optionFilterProp="label"
                    />
                );
            case 'INCREMENTAL_FIELD':
                return (
                    <Select
                        showSearch
                        allowClear
                        value={values[ph.key] || undefined}
                        onChange={(v) => handleChange(ph.key, v ?? '')}
                        placeholder={disabledTip ?? '选择增量字段'}
                        options={fieldOptions.map(f => ({value: f, label: f}))}
                        className="w-full"
                        loading={loadingFields}
                        disabled={!!disabledTip}
                        optionFilterProp="label"
                    />
                );
            case 'TARGET_DATABASE':
                return (
                    <Select
                        showSearch
                        allowClear
                        value={values[ph.key] || undefined}
                        onChange={(v) => handleChange(ph.key, v ?? '')}
                        placeholder="选择 Doris 目标库"
                        options={targetDatabases.map(db => ({value: db, label: db}))}
                        className="w-full"
                        loading={loadingTargetDbs}
                        optionFilterProp="label"
                    />
                );
            case 'TARGET_TABLE':
                return (
                    <Select
                        showSearch
                        allowClear
                        value={values[ph.key] || undefined}
                        onChange={(v) => handleChange(ph.key, v ?? '')}
                        placeholder={disabledTip ?? '选择 Doris 目标表'}
                        options={targetTables.map(t => ({value: t, label: t}))}
                        className="w-full"
                        loading={loadingTargetTables}
                        disabled={!!disabledTip}
                        optionFilterProp="label"
                    />
                );
            default:
                return (
                    <input
                        value={values[ph.key] ?? ''}
                        onChange={(e) => handleChange(ph.key, e.target.value)}
                        placeholder={ph.defaultValue ? `默认：${ph.defaultValue}` : `请输入${ph.label}`}
                        className={inputClass}
                    />
                );
        }
    };

    return (
        <DsModal
            open={open}
            onClose={onClose}
            title={template ? `从模板创建：${template.name}` : '从模板创建'}
            width="w-[520px]"
            bordered
            footer={
                <>
                    <DsButton variant="secondary" onClick={onClose} disabled={saving}>取消</DsButton>
                    <DsButton variant="primary" onClick={handleSubmit} disabled={saving} loading={saving}>
                        生成任务
                    </DsButton>
                </>
            }
        >
            <div className="space-y-ds-4">
                <div className="space-y-ds-2">
                    <span className="text-ds-small text-ds-text-primary font-medium block">
                        任务名称 <span className="text-ds-danger">*</span>
                    </span>
                    <input
                        value={taskName}
                        onChange={(e) => setTaskName(e.target.value)}
                        placeholder="如：dwd_orders 每日同步"
                        className={inputClass.replace(' font-mono', '')}
                    />
                </div>
                {placeholders.map(ph => (
                    <div className="space-y-ds-2" key={ph.key}>
                        <span className="text-ds-small text-ds-text-primary font-medium block">
                            {ph.label}
                            {ph.required && <span className="text-ds-danger"> *</span>}
                            <span className="text-ds-tiny text-ds-text-muted font-normal">（{`{${ph.key}}`}）</span>
                        </span>
                        {renderField(ph)}
                    </div>
                ))}
                {placeholders.length === 0 && (
                    <p className="text-ds-tiny text-ds-text-muted">该模板无占位参数，直接生成任务。</p>
                )}
            </div>
        </DsModal>
    );
}
