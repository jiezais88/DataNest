import {useEffect, useState} from 'react';
import Drawer from '../../../components/Drawer';
import DsButton from '../../../components/DsButton';
import {listMetadataColumns} from '../../../api/metadata';
import type {MetadataColumn, MetadataTable} from '../../../types/metadata';
import {QUALITY_TYPE_OPTIONS} from '../../../types/quality';
import type {QualityRule, QualityRuleCreateRequest, QualityRuleType} from '../../../types/quality';
import TableSelectModal from './TableSelectModal';

interface QualityRuleFormData {
    name: string;
    type: QualityRuleType;
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
    /** 默认数据源（从任务继承，用于选表） */
    defaultDatasourceId?: string;
    onClose: () => void;
    onSubmit: (payload: QualityRuleCreateRequest) => Promise<unknown>;
}

export default function QualityRuleDrawer({
                                               open,
                                               mode = 'create',
                                               editItem,
                                               jobId,
                                               defaultDatasourceId,
                                               onClose,
                                               onSubmit,
                                           }: QualityRuleDrawerProps) {
    const [form, setForm] = useState<QualityRuleFormData>(EMPTY_FORM);
    const [errors, setErrors] = useState<Partial<Record<keyof QualityRuleFormData, string>>>({});
    const [submitting, setSubmitting] = useState(false);
    const [columns, setColumns] = useState<MetadataColumn[]>([]);
    const [columnsLoading, setColumnsLoading] = useState(false);
    const [tableSelectOpen, setTableSelectOpen] = useState(false);

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
            } else {
                setForm({...EMPTY_FORM});
            }
            setErrors({});
        }
    }, [open, editItem]);

    const updateField = <K extends keyof QualityRuleFormData>(field: K, value: QualityRuleFormData[K]) => {
        if (readOnly) return;
        setForm((prev) => ({...prev, [field]: value}));
        if (errors[field]) {
            setErrors((prev) => ({...prev, [field]: undefined}));
        }
    };

    const handleTableSelect = (tables: MetadataTable[]) => {
        const table = tables[0];
        if (table) {
            updateField('tableId', String(table.id));
            updateField('tableName', table.schemaName ? `${table.schemaName}.${table.tableName}` : table.tableName);
        }
        setTableSelectOpen(false);
    };

    const validate = (): boolean => {
        const nextErrors: Partial<Record<keyof QualityRuleFormData, string>> = {};
        if (!form.name.trim()) nextErrors.name = '请输入规则名称';
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
                templateId: editItem?.templateId,
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

                    <div>
                        <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                            目标表 <span className="text-ds-danger">*</span>
                        </label>
                        <div className="flex gap-ds-2">
                            <div
                                className={`flex-1 px-ds-3 py-ds-2 rounded-ds-sm border border-ds-border-subtle text-ds-small ${
                                    form.tableName ? 'text-ds-text-primary' : 'text-ds-text-muted'
                                }`}
                            >
                                {form.tableName || '未选择表'}
                            </div>
                            <DsButton
                                variant="secondary"
                                onClick={() => setTableSelectOpen(true)}
                                disabled={readOnly}
                            >
                                选择表
                            </DsButton>
                        </div>
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

            <TableSelectModal
                open={tableSelectOpen}
                onClose={() => setTableSelectOpen(false)}
                defaultDatasourceId={defaultDatasourceId}
                selectedTables={[]}
                multiple={false}
                onConfirm={handleTableSelect}
            />
        </>
    );
}
