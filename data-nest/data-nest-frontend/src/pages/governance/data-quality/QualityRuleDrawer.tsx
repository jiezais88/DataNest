import {useEffect, useRef, useState} from 'react';
import Drawer from '../../../components/Drawer';
import DsButton from '../../../components/DsButton';
import {
    listMetadataColumns,
    listMetadataDatabases,
    listMetadataDatasourceIds,
    listMetadataSchemas,
    listMetadataTables,
    listMetadataTablesWithoutSchema,
} from '../../../api/metadata';
import {isWithoutSchema} from '../../../constants/datasource';
import type {MetadataColumn, MetadataTable, MetadataDatasource} from '../../../types/metadata';
import {QUALITY_TYPE_OPTIONS} from '../../../types/quality';
import {listQualityTemplates} from '../../../api/quality';
import type {QualityRule, QualityRuleCreateRequest, QualityRuleTemplate, QualityRuleType} from '../../../types/quality';

interface QualityRuleFormData {
    name: string;
    type: QualityRuleType;
    /** 来源模板（模板类规则必填；CUSTOM_SQL 可不填，用用户 SQL） */
    templateId: string;
    /** 目标表归属数据源（Sprint 7 方案A：表单级显式字段） */
    datasourceId: string;
    datasourceName: string;
    tableId: string;
    tableName: string;
    columnName: string;
    checkField: number;
    sqlExpression: string;
    warningThreshold: string;
    severeThreshold: string;
    resultMetric: string;
    weight: string;
    enabled: number;
}

const EMPTY_FORM: QualityRuleFormData = {
    name: '',
    type: 'COMPLETENESS',
    templateId: '',
    datasourceId: '',
    datasourceName: '',
    tableId: '',
    tableName: '',
    columnName: '',
    checkField: 0,
    sqlExpression: '',
    warningThreshold: '',
    severeThreshold: '',
    resultMetric: '',
    weight: '1',
    enabled: 1,
};

interface QualityRuleDrawerProps {
    open: boolean;
    mode?: 'create' | 'edit' | 'view';
    editItem: QualityRule | null;
    /** 所属任务 ID（创建必传；编辑从 editItem 取） */
    jobId: string;
    onClose: () => void;
    onSubmit: (payload: QualityRuleCreateRequest) => Promise<unknown>;
}

export default function QualityRuleDrawer({
                                               open,
                                               mode = 'create',
                                               editItem,
                                               jobId,
                                               onClose,
                                               onSubmit,
                                           }: QualityRuleDrawerProps) {
    const [form, setForm] = useState<QualityRuleFormData>(EMPTY_FORM);
    const [errors, setErrors] = useState<Partial<Record<keyof QualityRuleFormData, string>>>({});
    const [submitting, setSubmitting] = useState(false);
    const [columns, setColumns] = useState<MetadataColumn[]>([]);
    const [columnsLoading, setColumnsLoading] = useState(false);
    /** 可选模板（按规则类型联动，仅启用；模板类规则必选） */
    const [templates, setTemplates] = useState<QualityRuleTemplate[]>([]);
    const [templatesLoading, setTemplatesLoading] = useState(false);
    /** 可选数据源（仅已采集元数据的数据源） */
    const [datasourceOptions, setDatasourceOptions] = useState<MetadataDatasource[]>([]);
    // 行内选表级联：数据库 / Schema / 表
    const [databases, setDatabases] = useState<string[]>([]);
    const [databaseLoading, setDatabaseLoading] = useState(false);
    const [selectedDatabase, setSelectedDatabase] = useState('');
    const [schemas, setSchemas] = useState<string[]>([]);
    const [schemaLoading, setSchemaLoading] = useState(false);
    const [selectedSchema, setSelectedSchema] = useState('');
    const [tables, setTables] = useState<MetadataTable[]>([]);
    const [tableLoading, setTableLoading] = useState(false);
    // 编辑回显时的目标表归属数据库 / Schema（form 更新为异步，effect 闭包读不到，用 ref 暂存供各加载 effect 回填级联选中值）
    const editDatabaseRef = useRef('');
    const editSchemaRef = useRef('');

    const datasourceType = datasourceOptions.find((d) => String(d.id) === String(form.datasourceId))?.type;
    const noSchema = isWithoutSchema(datasourceType);

    const isEdit = mode === 'edit';
    const isView = mode === 'view';
    const readOnly = isView;

    const isRange = form.type === 'RANGE';
    const isCustomSql = form.type === 'CUSTOM_SQL';
    const needsColumn = form.type === 'UNIQUENESS' || form.type === 'RANGE' || (form.type === 'COMPLETENESS' && form.checkField === 1);

    // 选表后加载字段
    useEffect(() => {
        if (!open || !form.tableId || readOnly) {
            setColumns([]);
            return;
        }
        setColumnsLoading(true);
        listMetadataColumns(form.tableId)
            .then((res) => setColumns(res.data || []))
            .catch(() => setColumns([]))
            .finally(() => setColumnsLoading(false));
    }, [open, form.tableId, readOnly]);

    useEffect(() => {
        if (open) {
            if (editItem) {
                setForm({
                    name: editItem.name,
                    type: editItem.type,
                    templateId: editItem.templateId ? String(editItem.templateId) : '',
                    datasourceId: editItem.datasourceId ? String(editItem.datasourceId) : '',
                    datasourceName: editItem.datasourceName || '',
                    tableId: editItem.tableId || '',
                    tableName: editItem.tableName || '',
                    columnName: editItem.columnName || '',
                    checkField: editItem.checkField ?? 0,
                    sqlExpression: editItem.sqlExpression || '',
                    warningThreshold: editItem.warningThreshold != null ? String(editItem.warningThreshold) : '',
                    severeThreshold: editItem.severeThreshold != null ? String(editItem.severeThreshold) : '',
                    resultMetric: editItem.resultMetric || '',
                    weight: editItem.weight != null ? String(editItem.weight) : '1',
                    enabled: editItem.enabled ?? 1,
                });
                // 编辑回显：暂存目标表归属数据库/Schema 到 ref，由各加载 effect 在数据库/Schema 列表到达后回填级联选中值
                editDatabaseRef.current = editItem.databaseName || '';
                editSchemaRef.current = editItem.schemaName || '';
                setSelectedDatabase('');
                setSelectedSchema('');
            } else {
                setForm({...EMPTY_FORM});
                editDatabaseRef.current = '';
                editSchemaRef.current = '';
                setSelectedDatabase('');
                setSelectedSchema('');
                setDatabases([]);
                setSchemas([]);
                setTables([]);
            }
            setErrors({});
            // 加载可选数据源（仅已采集元数据的数据源）
            listMetadataDatasourceIds()
                .then((res) => setDatasourceOptions(res.data || []))
                .catch(() => setDatasourceOptions([]));
        }
    }, [open, editItem]);

    // 规则类型变化：按类型加载对应模板（模板类规则必选；CUSTOM_SQL 无模板依赖）
    useEffect(() => {
        if (!open || readOnly) return;
        const type = form.type;
        if (type === 'CUSTOM_SQL') {
            setTemplates([]);
            updateField('templateId', '');
            return;
        }
        setTemplatesLoading(true);
        listQualityTemplates(type)
            .then((res) => {
                const list = res.data || [];
                setTemplates(list);
                // 若当前已选模板不在新列表（类型变更后失效），清空
                setForm((prev) => {
                    const stillValid = list.some((t) => String(t.id) === String(prev.templateId));
                    return stillValid ? prev : {...prev, templateId: ''};
                });
            })
            .catch(() => setTemplates([]))
            .finally(() => setTemplatesLoading(false));
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [open, form.type, readOnly]);

    const updateField = <K extends keyof QualityRuleFormData>(field: K, value: QualityRuleFormData[K]) => {
        if (readOnly) return;
        setForm((prev) => ({...prev, [field]: value}));
        if (errors[field]) {
            setErrors((prev) => ({...prev, [field]: undefined}));
        }
    };

    const handleDatasourceChange = (id: string) => {
        updateField('datasourceId', id);
        const ds = datasourceOptions.find((d) => String(d.id) === String(id));
        updateField('datasourceName', ds?.name || '');
        // 切换数据源时清空已选表及级联状态（表归属发生变化）；同时清空回显 ref，避免误按旧库/Schema 回填
        editDatabaseRef.current = '';
        editSchemaRef.current = '';
        updateField('tableId', '');
        updateField('tableName', '');
        updateField('columnName', '');
        setSelectedDatabase('');
        setSelectedSchema('');
        setDatabases([]);
        setSchemas([]);
        setTables([]);
    };

    // 数据源变化：加载数据库
    useEffect(() => {
        if (!open || !form.datasourceId) return;
        setSelectedDatabase('');
        setSelectedSchema('');
        setSchemas([]);
        setTables([]);
        setDatabaseLoading(true);
        listMetadataDatabases(form.datasourceId)
            .then((res) => {
                const dbs = res.data || [];
                setDatabases(dbs);
                // 编辑回显：目标表归属库名在数据库列表中存在时回填选中值（避免被清空导致回显失败）
                const editDb = editDatabaseRef.current;
                setSelectedDatabase(editDb && dbs.includes(editDb) ? editDb : '');
            })
            .catch(() => {
                setDatabases([]);
                setSelectedDatabase('');
            })
            .finally(() => setDatabaseLoading(false));
    }, [open, form.datasourceId]);

    // 选数据库：无 Schema 类型直接加载表，否则加载 Schema
    useEffect(() => {
        if (!open || !form.datasourceId || !selectedDatabase) return;
        setSelectedSchema('');
        setSchemas([]);
        setTables([]);
        if (noSchema) {
            setTableLoading(true);
            listMetadataTablesWithoutSchema(form.datasourceId, selectedDatabase)
                .then((res) => setTables(res.data || []))
                .catch(() => setTables([]))
                .finally(() => setTableLoading(false));
        } else {
            setSchemaLoading(true);
            listMetadataSchemas(form.datasourceId, selectedDatabase)
                .then((res) => {
                    const schemaList = res.data || [];
                    setSchemas(schemaList);
                    // 编辑回显：目标表归属 Schema 在列表中存在时回填选中值（避免被清空导致回显失败）
                    const editSchema = editSchemaRef.current;
                    setSelectedSchema(editSchema && schemaList.includes(editSchema) ? editSchema : '');
                })
                .catch(() => {
                    setSchemas([]);
                    setSelectedSchema('');
                })
                .finally(() => setSchemaLoading(false));
        }
    }, [open, form.datasourceId, selectedDatabase, noSchema]);

    // 选 Schema：加载表
    useEffect(() => {
        if (!open || !form.datasourceId || !selectedDatabase || !selectedSchema || noSchema) return;
        setTableLoading(true);
        listMetadataTables(form.datasourceId, selectedDatabase, selectedSchema)
            .then((res) => setTables(res.data || []))
            .catch(() => setTables([]))
            .finally(() => setTableLoading(false));
    }, [open, form.datasourceId, selectedDatabase, selectedSchema, noSchema]);

    const handleTableChange = (tableId: string) => {
        const table = tables.find((t) => String(t.id) === String(tableId));
        updateField('tableId', tableId);
        updateField('tableName', table ? (table.schemaName ? `${table.schemaName}.${table.tableName}` : table.tableName) : '');
    };

    const validate = (): boolean => {
        const nextErrors: Partial<Record<keyof QualityRuleFormData, string>> = {};
        if (!form.name.trim()) nextErrors.name = '请输入规则名称';
        if (!isCustomSql && !form.templateId) nextErrors.templateId = '请选择规则模板';
        if (!form.datasourceId) nextErrors.datasourceId = '请选择数据源';
        if (!form.tableId) nextErrors.tableId = '请选择目标表';
        if (needsColumn && !form.columnName.trim()) nextErrors.columnName = '请选择检查字段';
        if (isCustomSql && !form.sqlExpression.trim()) nextErrors.sqlExpression = '请输入自定义校验 SQL';
        if (isRange) {
            if (!form.warningThreshold.trim() || !form.severeThreshold.trim()) {
                nextErrors.warningThreshold = '请填写值域下限';
                nextErrors.severeThreshold = '请填写值域上限';
            } else if (Number(form.warningThreshold) > Number(form.severeThreshold)) {
                nextErrors.warningThreshold = '值域下限不能大于上限';
            }
        }
        if (!form.weight.trim() || Number(form.weight) < 1) nextErrors.weight = '权重最小为 1';
        setErrors(nextErrors);
        return Object.keys(nextErrors).length === 0;
    };

    const handleSubmit = async () => {
        if (!validate()) return;
        setSubmitting(true);
        try {
            const effectiveJobId = editItem?.jobId || jobId;
            const payload: QualityRuleCreateRequest = {
                // Sprint 7：规则可独立创建，jobId 为空则不下发（避免后端按空串校验任务）
                jobId: effectiveJobId || undefined,
                templateId: form.templateId || undefined,
                name: form.name.trim(),
                type: form.type,
                tableId: form.tableId,
                columnName: needsColumn ? form.columnName.trim() : undefined,
                checkField: form.checkField,
                sqlExpression: isCustomSql ? form.sqlExpression.trim() : undefined,
                warningThreshold: form.warningThreshold.trim() ? Number(form.warningThreshold) : undefined,
                severeThreshold: form.severeThreshold.trim() ? Number(form.severeThreshold) : undefined,
                resultMetric: form.resultMetric.trim() || undefined,
                weight: Number(form.weight),
                enabled: form.enabled,
            };
            await onSubmit(payload);
            onClose();
        } finally {
            setSubmitting(false);
        }
    };

    const drawerTitle = isEdit ? '编辑质量规则' : isView ? '质量规则详情' : '新增质量规则';

    const inputClass = 'w-full px-ds-3 py-ds-2 bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors disabled:opacity-60 disabled:cursor-not-allowed';
    const selectClass = 'w-full px-ds-3 py-ds-2 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-small text-ds-text-primary focus:outline-none focus-visible:border-ds-accent disabled:opacity-60 disabled:cursor-not-allowed';

    return (
        <>
            <Drawer
                open={open}
                title={drawerTitle}
                width="max-w-[640px]"
                onClose={onClose}
                footer={
                    readOnly ? undefined : (
                        <>
                            <DsButton variant="secondary" onClick={onClose}>
                                取消
                            </DsButton>
                            <DsButton onClick={handleSubmit} disabled={submitting}>
                                {submitting ? '保存中...' : '保存'}
                            </DsButton>
                        </>
                    )
                }
            >
                <div className="space-y-ds-4">
                    <div>
                        <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                            规则名称 <span className="text-ds-danger">*</span>
                        </label>
                        <input
                            value={form.name}
                            onChange={(e) => updateField('name', e.target.value)}
                            disabled={readOnly}
                            maxLength={100}
                            className={inputClass}
                            placeholder="例如：订单表订单号唯一性检查"
                        />
                        {errors.name && <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.name}</p>}
                    </div>

                    <div>
                        <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                            规则类型 <span className="text-ds-danger">*</span>
                        </label>
                        <select
                            value={form.type}
                            onChange={(e) => {
                                updateField('type', e.target.value as QualityRuleType);
                                updateField('sqlExpression', '');
                            }}
                            disabled={readOnly}
                            className={selectClass}
                        >
                            {QUALITY_TYPE_OPTIONS.map((o) => (
                                <option key={o.value} value={o.value}>{o.label}</option>
                            ))}
                        </select>
                    </div>

                    {!isCustomSql && (
                        <div>
                            <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                                规则模板 <span className="text-ds-danger">*</span>
                            </label>
                            <select
                                value={form.templateId}
                                onChange={(e) => updateField('templateId', e.target.value)}
                                disabled={readOnly || templatesLoading}
                                className={selectClass}
                            >
                                <option value="">
                                    {templatesLoading ? '加载模板中...' : '请选择规则模板'}
                                </option>
                                {templates.map((t) => (
                                    <option key={String(t.id)} value={String(t.id)}>
                                        {t.name}（{t.resultMetric || t.type}）
                                    </option>
                                ))}
                            </select>
                            <p className="mt-ds-1 text-ds-nano text-ds-text-muted">
                                完整性/唯一性/值域规则需关联模板以生成校验 SQL
                            </p>
                            {errors.templateId &&
                                <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.templateId}</p>}
                        </div>
                    )}

                    <div>
                        <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                            数据源 <span className="text-ds-danger">*</span>
                        </label>
                        <select
                            value={form.datasourceId}
                            onChange={(e) => handleDatasourceChange(e.target.value)}
                            disabled={readOnly}
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
                            仅展示已采集元数据的数据源，未采集请先到「元数据管理」执行采集
                        </p>
                        {errors.datasourceId && <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.datasourceId}</p>}
                    </div>

                    <div>
                        <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                            数据库
                        </label>
                        <select
                            value={selectedDatabase}
                            onChange={(e) => setSelectedDatabase(e.target.value)}
                            disabled={readOnly || !form.datasourceId}
                            className={selectClass}
                        >
                            <option value="">{databaseLoading ? '加载中...' : '请选择数据库'}</option>
                            {databases.map((db) => (
                                <option key={db} value={db}>{db}</option>
                            ))}
                        </select>
                    </div>

                    {selectedDatabase && !noSchema && (
                        <div>
                            <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                                Schema
                            </label>
                            <select
                                value={selectedSchema}
                                onChange={(e) => setSelectedSchema(e.target.value)}
                                disabled={readOnly}
                                className={selectClass}
                            >
                                <option value="">{schemaLoading ? '加载中...' : '请选择 Schema'}</option>
                                {schemas.map((s) => (
                                    <option key={s} value={s}>{s}</option>
                                ))}
                            </select>
                        </div>
                    )}

                    <div>
                        <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                            目标表 <span className="text-ds-danger">*</span>
                        </label>
                        <select
                            value={form.tableId}
                            onChange={(e) => handleTableChange(e.target.value)}
                            disabled={readOnly || !form.datasourceId || !selectedDatabase || (!noSchema && !selectedSchema)}
                            className={selectClass}
                        >
                            <option value="">
                                {tableLoading ? '加载中...' : (form.datasourceId && selectedDatabase ? '请选择目标表' : '请先选择数据源 / 数据库')}
                            </option>
                            {tables.map((t) => (
                                <option key={t.id} value={String(t.id)}>
                                    {t.schemaName ? `${t.schemaName}.${t.tableName}` : t.tableName}
                                </option>
                            ))}
                        </select>
                        {errors.tableId && <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.tableId}</p>}
                    </div>

                    {/* 检查字段 / 检查方式 */}
                    {form.type !== 'CUSTOM_SQL' && (
                        <div>
                            <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                                {form.type === 'COMPLETENESS' ? '检查方式' : '检查字段'}
                                {needsColumn && <span className="text-ds-danger"> *</span>}
                            </label>
                            {form.type === 'COMPLETENESS' && (
                                <div className="flex gap-ds-2 mb-ds-2">
                                    <button
                                        type="button"
                                        disabled={readOnly}
                                        onClick={() => updateField('checkField', 0)}
                                        className={`flex-1 px-ds-3 py-ds-2 rounded-ds-sm border text-ds-small transition-colors disabled:opacity-60 disabled:cursor-not-allowed ${
                                            form.checkField === 0
                                                ? 'border-ds-accent bg-ds-accent-light text-ds-accent font-semibold'
                                                : 'border-ds-border-subtle bg-white text-ds-text-secondary hover:border-ds-accent'
                                        }`}
                                    >
                                        整表检查
                                    </button>
                                    <button
                                        type="button"
                                        disabled={readOnly}
                                        onClick={() => updateField('checkField', 1)}
                                        className={`flex-1 px-ds-3 py-ds-2 rounded-ds-sm border text-ds-small transition-colors disabled:opacity-60 disabled:cursor-not-allowed ${
                                            form.checkField === 1
                                                ? 'border-ds-accent bg-ds-accent-light text-ds-accent font-semibold'
                                                : 'border-ds-border-subtle bg-white text-ds-text-secondary hover:border-ds-accent'
                                        }`}
                                    >
                                        按字段检查
                                    </button>
                                </div>
                            )}
                            {needsColumn && (
                                <select
                                    value={form.columnName}
                                    onChange={(e) => updateField('columnName', e.target.value)}
                                    disabled={readOnly || !form.tableId}
                                    className={selectClass}
                                >
                                    <option value="">{columnsLoading ? '加载字段中...' : '请选择检查字段'}</option>
                                    {columns.map((c) => (
                                        <option key={c.id} value={c.columnName}>{c.columnName}</option>
                                    ))}
                                </select>
                            )}
                            {needsColumn && errors.columnName &&
                                <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.columnName}</p>}
                        </div>
                    )}

                    {/* 自定义 SQL */}
                    {isCustomSql && (
                        <div>
                            <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                                校验 SQL <span className="text-ds-danger">*</span>
                            </label>
                            <textarea
                                value={form.sqlExpression}
                                onChange={(e) => updateField('sqlExpression', e.target.value)}
                                rows={4}
                                disabled={readOnly}
                                className={`${inputClass} resize-none font-mono`}
                                placeholder="返回单个统计值的自定义校验 SQL"
                            />
                            {errors.sqlExpression &&
                                <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.sqlExpression}</p>}
                        </div>
                    )}

                    {/* 阈值 */}
                    <div className="grid grid-cols-2 gap-ds-3">
                        <div>
                            <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                                {isRange ? '值域下限' : '警告阈值'}
                            </label>
                            <input
                                value={form.warningThreshold}
                                onChange={(e) => updateField('warningThreshold', e.target.value)}
                                disabled={readOnly}
                                type="number"
                                step="any"
                                className={`${inputClass} font-mono`}
                                placeholder={isRange ? '最小值' : '结果 ≥ 此值 → 警告'}
                            />
                            {errors.warningThreshold &&
                                <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.warningThreshold}</p>}
                        </div>
                        <div>
                            <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                                {isRange ? '值域上限' : '严重阈值'}
                            </label>
                            <input
                                value={form.severeThreshold}
                                onChange={(e) => updateField('severeThreshold', e.target.value)}
                                disabled={readOnly}
                                type="number"
                                step="any"
                                className={`${inputClass} font-mono`}
                                placeholder={isRange ? '最大值' : '结果 ≥ 此值 → 严重'}
                            />
                            {errors.severeThreshold &&
                                <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.severeThreshold}</p>}
                        </div>
                    </div>

                    <div className="grid grid-cols-2 gap-ds-3">
                        <div>
                            <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                                结果指标名
                            </label>
                            <input
                                value={form.resultMetric}
                                onChange={(e) => updateField('resultMetric', e.target.value)}
                                disabled={readOnly}
                                maxLength={50}
                                className={`${inputClass} font-mono`}
                                placeholder="如：null_rate"
                            />
                        </div>
                        <div>
                            <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                                权重 <span className="text-ds-danger">*</span>
                            </label>
                            <input
                                value={form.weight}
                                onChange={(e) => updateField('weight', e.target.value)}
                                disabled={readOnly}
                                type="number"
                                min={1}
                                className={`${inputClass} font-mono`}
                                placeholder="默认 1"
                            />
                            {errors.weight && <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.weight}</p>}
                        </div>
                    </div>

                    <div>
                        <label className="flex items-center gap-ds-2 text-ds-small font-semibold text-ds-text-secondary">
                            <input
                                type="checkbox"
                                checked={form.enabled === 1}
                                onChange={(e) => updateField('enabled', e.target.checked ? 1 : 0)}
                                disabled={readOnly}
                                className="w-4 h-4 rounded border-ds-border-subtle text-ds-accent focus:ring-ds-accent disabled:opacity-60 disabled:cursor-not-allowed"
                            />
                            启用规则
                        </label>
                    </div>
                </div>
            </Drawer>
        </>
    );
}
