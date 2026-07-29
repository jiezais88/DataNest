import {useEffect, useState} from 'react';
import Drawer from '../../../components/Drawer';
import type {AppliesTo, FieldTypeStandard, NamingStandard, RuleType} from '../../../types/dataStandard';

interface NamingStandardFormData {
    name: string;
    appliesTo: AppliesTo;
    ruleType: RuleType;
    ruleValue: string;
    targetStandardId: string;
    priority: number;
    enabled: number;
    description: string;
}

const EMPTY_FORM: NamingStandardFormData = {
    name: '',
    appliesTo: 'COLUMN',
    ruleType: 'SUFFIX',
    ruleValue: '',
    targetStandardId: '',
    priority: 0,
    enabled: 1,
    description: '',
};

const APPLIES_TO_OPTIONS: { value: AppliesTo; label: string }[] = [
    {value: 'TABLE', label: '表名'},
    {value: 'COLUMN', label: '字段名'},
];

const RULE_TYPE_OPTIONS: { value: RuleType; label: string }[] = [
    {value: 'PREFIX', label: '前缀'},
    {value: 'SUFFIX', label: '后缀'},
    {value: 'REGEX', label: '正则'},
];

interface NamingStandardDrawerProps {
    open: boolean;
    editItem: NamingStandard | null;
    standards: FieldTypeStandard[];
    onClose: () => void;
    onSubmit: (payload: NamingStandardFormData) => Promise<{ code: number; message?: string } | undefined>;
}

export default function NamingStandardDrawer({
                                                 open,
                                                 editItem,
                                                 standards,
                                                 onClose,
                                                 onSubmit
                                             }: NamingStandardDrawerProps) {
    const [form, setForm] = useState<NamingStandardFormData>(EMPTY_FORM);
    const [errors, setErrors] = useState<Partial<Record<keyof NamingStandardFormData, string>>>({});
    const [submitting, setSubmitting] = useState(false);

    const isEdit = !!editItem;

    useEffect(() => {
        if (open) {
            if (editItem) {
                setForm({
                    name: editItem.name,
                    appliesTo: editItem.appliesTo,
                    ruleType: editItem.ruleType,
                    ruleValue: editItem.ruleValue,
                    targetStandardId: editItem.targetStandardId,
                    priority: editItem.priority || 0,
                    enabled: editItem.enabled ?? 1,
                    description: editItem.description || '',
                });
            } else {
                setForm(EMPTY_FORM);
            }
            setErrors({});
        }
    }, [open, editItem]);

    const updateField = <K extends keyof NamingStandardFormData>(field: K, value: NamingStandardFormData[K]) => {
        setForm((prev) => {
            const next = {...prev, [field]: value} as NamingStandardFormData;
            if (field === 'appliesTo' && value === 'TABLE') {
                next.targetStandardId = '';
            }
            return next;
        });
        if (errors[field]) {
            setErrors((prev) => ({...prev, [field]: undefined}));
        }
    };

    const validate = (): boolean => {
        const nextErrors: Partial<Record<keyof NamingStandardFormData, string>> = {};
        if (!form.name.trim()) nextErrors.name = '请输入规范名称';
        if (!form.ruleValue.trim()) nextErrors.ruleValue = '请输入规则值';
        if (form.appliesTo === 'COLUMN' && !form.targetStandardId) {
            nextErrors.targetStandardId = '字段级命名规范必须关联字段类型标准';
        }
        setErrors(nextErrors);
        return Object.keys(nextErrors).length === 0;
    };

    const buildPayload = (): NamingStandardFormData => {
        return {
            ...form,
            targetStandardId: form.appliesTo === 'TABLE' ? '' : form.targetStandardId,
        };
    };

    const handleSubmit = async () => {
        if (!validate()) return;
        setSubmitting(true);
        try {
            const result = await onSubmit(buildPayload());
            if (result && result.code === 200) {
                onClose();
            }
        } finally {
            setSubmitting(false);
        }
    };

    return (
        <Drawer
            open={open}
            title={isEdit ? '编辑命名规范' : '新建命名规范'}
            onClose={onClose}
            footer={
                <>
                    <button
                        onClick={onClose}
                        className="px-ds-4 py-ds-2 bg-white border border-ds-border-subtle hover:border-ds-border-strong text-ds-text-secondary text-ds-small font-semibold rounded-ds-sm transition-colors"
                    >
                        取消
                    </button>
                    <button
                        onClick={handleSubmit}
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
                        规范名称 <span className="text-ds-danger">*</span>
                    </label>
                    <input
                        value={form.name}
                        onChange={(e) => updateField('name', e.target.value)}
                        className="w-full px-ds-3 py-ds-2 bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors"
                        placeholder="例如：ID 字段命名规范"
                    />
                    {errors.name && <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.name}</p>}
                </div>

                <div className="grid grid-cols-2 gap-ds-3">
                    <div>
                        <label
                            className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">适用对象</label>
                        <div className="flex gap-ds-2">
                            {APPLIES_TO_OPTIONS.map((o) => (
                                <button
                                    key={o.value}
                                    type="button"
                                    onClick={() => updateField('appliesTo', o.value)}
                                    className={`flex-1 px-ds-3 py-ds-2 rounded-ds-sm border text-ds-small transition-colors ${
                                        form.appliesTo === o.value
                                            ? 'border-ds-accent bg-ds-accent-light text-ds-accent font-semibold'
                                            : 'border-ds-border-subtle bg-white text-ds-text-secondary hover:border-ds-accent'
                                    }`}
                                >
                                    {o.label}
                                </button>
                            ))}
                        </div>
                    </div>
                    <div>
                        <label
                            className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">规则类型</label>
                        <div className="flex gap-ds-2">
                            {RULE_TYPE_OPTIONS.map((o) => (
                                <button
                                    key={o.value}
                                    type="button"
                                    onClick={() => updateField('ruleType', o.value)}
                                    className={`flex-1 px-ds-3 py-ds-2 rounded-ds-sm border text-ds-small transition-colors ${
                                        form.ruleType === o.value
                                            ? 'border-ds-accent bg-ds-accent-light text-ds-accent font-semibold'
                                            : 'border-ds-border-subtle bg-white text-ds-text-secondary hover:border-ds-accent'
                                    }`}
                                >
                                    {o.label}
                                </button>
                            ))}
                        </div>
                    </div>
                </div>

                <div>
                    <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                        规则值 <span className="text-ds-danger">*</span>
                    </label>
                    <input
                        value={form.ruleValue}
                        onChange={(e) => updateField('ruleValue', e.target.value)}
                        className="w-full px-ds-3 py-ds-2 bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors"
                        placeholder={form.ruleType === 'REGEX' ? '例如：^[a-z][a-z0-9_]*$' : '例如：_id'}
                    />
                    {errors.ruleValue && <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.ruleValue}</p>}
                    <p className="mt-ds-1 text-ds-nano text-ds-text-muted">
                        {form.ruleType === 'PREFIX' && '匹配对象名称以该值开头'}
                        {form.ruleType === 'SUFFIX' && '匹配对象名称以该值结尾'}
                        {form.ruleType === 'REGEX' && '使用 Java 正则表达式匹配完整对象名称'}
                    </p>
                </div>

                {form.appliesTo === 'COLUMN' && (
                    <div>
                        <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                            关联字段类型标准 <span className="text-ds-danger">*</span>
                        </label>
                        <select
                            value={form.targetStandardId}
                            onChange={(e) => updateField('targetStandardId', e.target.value)}
                            className="w-full px-ds-3 py-ds-2 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors"
                        >
                            <option value="">请选择</option>
                            {standards.map((s) => (
                                <option key={s.id} value={s.id}>
                                    {s.name}（允许：{s.allowedTypes.join('、')}）
                                </option>
                            ))}
                        </select>
                        {errors.targetStandardId &&
                            <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.targetStandardId}</p>}
                    </div>
                )}

                <div>
                    <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">优先级</label>
                    <input
                        type="number"
                        value={form.priority}
                        onChange={(e) => updateField('priority', Number(e.target.value))}
                        className="w-full px-ds-3 py-ds-2 bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors"
                    />
                    <p className="mt-ds-1 text-ds-nano text-ds-text-muted">数字越大越优先，命中后不再继续匹配其他规范</p>
                </div>

                <div>
                    <label className="flex items-center gap-ds-2 text-ds-small font-semibold text-ds-text-secondary">
                        <input
                            type="checkbox"
                            checked={form.enabled === 1}
                            onChange={(e) => updateField('enabled', e.target.checked ? 1 : 0)}
                            className="rounded border-ds-border-subtle text-ds-accent focus:ring-ds-accent"
                        />
                        启用规范
                    </label>
                </div>

                <div>
                    <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">描述</label>
                    <textarea
                        value={form.description}
                        onChange={(e) => updateField('description', e.target.value)}
                        rows={3}
                        className="w-full px-ds-3 py-ds-2 bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors resize-none"
                        placeholder="可选"
                    />
                </div>
            </div>
        </Drawer>
    );
}
