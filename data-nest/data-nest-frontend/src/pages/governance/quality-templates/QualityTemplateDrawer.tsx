import {useEffect, useState} from 'react';
import Drawer from '@/components/Drawer';
import DsButton from '@/components/DsButton';
import DsStatusBadge from '@/components/DsStatusBadge';
import type {QualityRuleTemplate, QualityRuleTemplateCreateRequest, QualityTemplateType,} from '@/types/quality';

interface TemplateFormData {
    name: string;
    type: QualityTemplateType;
    description: string;
    sqlTemplate: string;
    /** Sprint 7 F4：PYTHON 模板脚本 */
    pythonTemplate: string;
    resultMetric: string;
    enabled: number;
}

const EMPTY_FORM: TemplateFormData = {
    name: '',
    type: 'CUSTOM_SQL',
    description: '',
    sqlTemplate: '',
    pythonTemplate: '',
    resultMetric: '',
    enabled: 1,
};

export const TYPE_OPTIONS: { value: QualityTemplateType; label: string }[] = [
    {value: 'COMPLETENESS', label: '完整性检查'},
    {value: 'UNIQUENESS', label: '唯一性检查'},
    {value: 'RANGE', label: '值域范围检查'},
    {value: 'CUSTOM_SQL', label: '自定义 SQL'},
    {value: 'PYTHON', label: 'Python'},
];

/** SQL 模板占位符说明，按类型动态提示 */
const PLACEHOLDER_HINT: Record<QualityTemplateType, string> = {
    COMPLETENESS: '如：SELECT (COUNT(*) - COUNT({column})) * 1.0 / COUNT(*) AS null_rate FROM {table}',
    UNIQUENESS: '如：SELECT COUNT(*) - COUNT(DISTINCT {column}) AS duplicate_count FROM {table}',
    RANGE: '如：SELECT COUNT(*) AS total, SUM(CASE WHEN {column} < {min} OR {column} > {max} THEN 1 ELSE 0 END) AS out_of_range FROM {table}',
    CUSTOM_SQL: '返回单个统计值的自定义校验 SQL，执行结果作为规则结果值',
    PYTHON: 'def check(df)：接收目标表 DataFrame，返回 {\'指标名\': 数值}；可用 read_table(table, where, limit) 采样',
};

interface QualityTemplateDrawerProps {
    open: boolean;
    mode?: 'create' | 'edit' | 'view';
    editItem: QualityRuleTemplate | null;
    onClose: () => void;
    onSubmit: (payload: QualityRuleTemplateCreateRequest) => Promise<{ code: number; message?: string } | undefined>;
}

export default function QualityTemplateDrawer({
                                                  open,
                                                  mode = 'create',
                                                  editItem,
                                                  onClose,
                                                  onSubmit
                                              }: QualityTemplateDrawerProps) {
    const [form, setForm] = useState<TemplateFormData>(EMPTY_FORM);
    const [errors, setErrors] = useState<Partial<Record<keyof TemplateFormData, string>>>({});
    const [submitting, setSubmitting] = useState(false);

    const isEdit = mode === 'edit';
    const isView = mode === 'view';
    const readOnly = isView;

    useEffect(() => {
        if (open) {
            if (editItem) {
                setForm({
                    name: editItem.name,
                    type: editItem.type,
                    description: editItem.description || '',
                    sqlTemplate: editItem.sqlTemplate || '',
                    pythonTemplate: editItem.pythonTemplate || '',
                    resultMetric: editItem.resultMetric || '',
                    enabled: editItem.enabled ?? 1,
                });
            } else {
                setForm(EMPTY_FORM);
            }
            setErrors({});
        }
    }, [open, editItem]);

    const updateField = <K extends keyof TemplateFormData>(field: K, value: TemplateFormData[K]) => {
        if (readOnly) return;
        setForm((prev) => ({...prev, [field]: value}));
        if (errors[field]) {
            setErrors((prev) => ({...prev, [field]: undefined}));
        }
    };

    const validate = (): boolean => {
        const nextErrors: Partial<Record<keyof TemplateFormData, string>> = {};
        if (!form.name.trim()) nextErrors.name = '请输入模板名称';
        if (!form.type) nextErrors.type = '请选择模板类型';
        // PYTHON 模板校验脚本原文，其余类型校验 SQL 模板
        if (form.type === 'PYTHON' ? !form.pythonTemplate.trim() : !form.sqlTemplate.trim()) {
            nextErrors.sqlTemplate = form.type === 'PYTHON' ? '请输入 Python 校验脚本' : '请输入校验 SQL 模板';
        }
        if (!form.resultMetric.trim()) nextErrors.resultMetric = '请输入结果指标名';
        setErrors(nextErrors);
        return Object.keys(nextErrors).length === 0;
    };

    const handleSubmit = async () => {
        if (!validate()) return;
        setSubmitting(true);
        try {
            await onSubmit({...form});
            onClose();
        } finally {
            setSubmitting(false);
        }
    };

    const drawerTitle = isEdit ? '编辑模板' : isView ? '模板详情' : '新增自定义模板';

    const inputClass = 'w-full px-ds-3 py-ds-2 bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors disabled:opacity-60 disabled:cursor-not-allowed';

    return (
        <Drawer
            open={open}
            title={drawerTitle}
            width="max-w-[640px]"
            onClose={onClose}
            extra={isView && editItem ? (
                editItem.builtin === 1
                    ? <DsStatusBadge label="内置" variant="accent"/>
                    : <DsStatusBadge label="自定义" variant="disabled"/>
            ) : undefined}
            footer={
                readOnly ? undefined : (
                    <>
                        <DsButton variant="secondary" onClick={onClose}>
                            取消
                        </DsButton>
                        <DsButton onClick={handleSubmit} disabled={submitting} loading={submitting}>
                            保存
                        </DsButton>
                    </>
                )
            }
        >
            <div className="space-y-ds-4">
                <div>
                    <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                        模板名称 <span className="text-ds-danger">*</span>
                    </label>
                    <input
                        value={form.name}
                        onChange={(e) => updateField('name', e.target.value)}
                        disabled={readOnly}
                        maxLength={100}
                        className={inputClass}
                        placeholder="例如：身份证号非空检查"
                    />
                    {errors.name && <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.name}</p>}
                </div>

                <div>
                    <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                        模板类型 <span className="text-ds-danger">*</span>
                    </label>
                    <div className="flex flex-wrap gap-ds-2">
                        {TYPE_OPTIONS.map((o) => (
                            <button
                                key={o.value}
                                type="button"
                                disabled={readOnly}
                                onClick={() => updateField('type', o.value)}
                                className={`flex-1 min-w-[120px] px-ds-3 py-ds-2 rounded-ds-sm border text-ds-small transition-colors disabled:opacity-60 disabled:cursor-not-allowed ${
                                    form.type === o.value
                                        ? 'border-ds-accent bg-ds-accent-light text-ds-accent font-semibold'
                                        : 'border-ds-border-subtle bg-white text-ds-text-secondary hover:border-ds-accent'
                                }`}
                            >
                                {o.label}
                            </button>
                        ))}
                    </div>
                    {errors.type && <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.type}</p>}
                </div>

                <div>
                    <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                        结果指标名 <span className="text-ds-danger">*</span>
                    </label>
                    <input
                        value={form.resultMetric}
                        onChange={(e) => updateField('resultMetric', e.target.value)}
                        disabled={readOnly}
                        maxLength={50}
                        className={`${inputClass} font-mono`}
                        placeholder="如：null_rate / duplicate_count / out_of_range_rate"
                    />
                    {errors.resultMetric &&
                        <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.resultMetric}</p>}
                </div>

                <div>
                    <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                        {form.type === 'PYTHON' ? 'Python 校验脚本' : '校验 SQL 模板'} <span className="text-ds-danger">*</span>
                    </label>
                    {form.type === 'PYTHON' ? (
                        <textarea
                            value={form.pythonTemplate}
                            onChange={(e) => updateField('pythonTemplate', e.target.value)}
                            rows={10}
                            disabled={readOnly}
                            spellCheck={false}
                            className={`${inputClass} resize-y font-mono`}
                            placeholder={PLACEHOLDER_HINT[form.type]}
                        />
                    ) : (
                        <textarea
                            value={form.sqlTemplate}
                            onChange={(e) => updateField('sqlTemplate', e.target.value)}
                            rows={5}
                            disabled={readOnly}
                            className={`${inputClass} resize-none font-mono`}
                            placeholder={PLACEHOLDER_HINT[form.type]}
                        />
                    )}
                    {errors.sqlTemplate &&
                        <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.sqlTemplate}</p>}
                    <p className="mt-ds-1 text-ds-nano text-ds-text-muted">
                        {form.type === 'PYTHON'
                            ? '批量应用时脚本原文带入规则；规则执行时按结果指标名从返回 dict 取值分级'
                            : <>占位符：{'{table}'} / {'{column}'} / {'{min}'} / {'{max}'}，应用时替换为具体表/字段/阈值</>}
                    </p>
                </div>

                <div>
                    <label className="flex items-center gap-ds-2 text-ds-small font-semibold text-ds-text-secondary">
                        <input
                            type="checkbox"
                            checked={form.enabled === 1}
                            onChange={(e) => updateField('enabled', e.target.checked ? 1 : 0)}
                            disabled={readOnly}
                            className="rounded border-ds-border-subtle text-ds-accent focus:ring-ds-accent disabled:opacity-60 disabled:cursor-not-allowed"
                        />
                        启用模板
                    </label>
                </div>

                <div>
                    <label
                        className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">模板说明</label>
                    <textarea
                        value={form.description}
                        onChange={(e) => updateField('description', e.target.value)}
                        rows={3}
                        disabled={readOnly}
                        maxLength={500}
                        className={`${inputClass} resize-none`}
                        placeholder="可选"
                    />
                </div>
            </div>
        </Drawer>
    );
}
