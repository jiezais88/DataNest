import {useEffect, useState} from 'react';
import Drawer from '@/components/Drawer';
import DsButton from '@/components/DsButton';
import DsSelect from '@/components/DsSelect';
import type {FieldTypeStandard} from '@/types/dataStandard';

interface FieldTypeStandardFormData {
    name: string;
    category: string;
    allowedTypes: string[];
    description: string;
}

const EMPTY_FORM: FieldTypeStandardFormData = {
    name: '',
    category: '',
    allowedTypes: [],
    description: '',
};

const CATEGORY_OPTIONS = [
    {value: '数值', label: '数值'},
    {value: '字符串', label: '字符串'},
    {value: '时间', label: '时间'},
    {value: '布尔', label: '布尔'},
    {value: '二进制', label: '二进制'},
    {value: '其他', label: '其他'},
];

const COMMON_TYPES = [
    'INT',
    'BIGINT',
    'VARCHAR(255)',
    'CHAR(50)',
    'DECIMAL(18,2)',
    'DATETIME',
    'TIMESTAMP',
    'DATE',
    'BOOLEAN',
    'TEXT',
];

interface FieldTypeStandardDrawerProps {
    open: boolean;
    mode?: 'create' | 'edit' | 'view';
    editItem: FieldTypeStandard | null;
    onClose: () => void;
    onSubmit: (payload: FieldTypeStandardFormData) => Promise<{ code: number; message?: string } | undefined>;
}

export default function FieldTypeStandardDrawer({
                                                    open,
                                                    mode = 'create',
                                                    editItem,
                                                    onClose,
                                                    onSubmit
                                                }: FieldTypeStandardDrawerProps) {
    const [form, setForm] = useState<FieldTypeStandardFormData>(EMPTY_FORM);
    const [errors, setErrors] = useState<Partial<Record<keyof FieldTypeStandardFormData, string>>>({});
    const [submitting, setSubmitting] = useState(false);
    const [typeInput, setTypeInput] = useState('');

    const isEdit = mode === 'edit';
    const isView = mode === 'view';
    const readOnly = isView;

    useEffect(() => {
        if (open) {
            if (editItem) {
                setForm({
                    name: editItem.name,
                    category: editItem.category || '',
                    allowedTypes: editItem.allowedTypes || [],
                    description: editItem.description || '',
                });
            } else {
                setForm(EMPTY_FORM);
                setTypeInput('');
            }
            setErrors({});
        }
    }, [open, editItem]);

    const updateField = <K extends keyof FieldTypeStandardFormData>(field: K, value: FieldTypeStandardFormData[K]) => {
        if (readOnly) return;
        setForm((prev) => ({...prev, [field]: value}));
        if (errors[field]) {
            setErrors((prev) => ({...prev, [field]: undefined}));
        }
    };

    const validate = (): boolean => {
        const nextErrors: Partial<Record<keyof FieldTypeStandardFormData, string>> = {};
        if (!form.name.trim()) nextErrors.name = '请输入标准名称';
        if (form.allowedTypes.length === 0) nextErrors.allowedTypes = '至少添加一个允许的字段类型';
        setErrors(nextErrors);
        return Object.keys(nextErrors).length === 0;
    };

    const handleSubmit = async () => {
        if (!validate()) return;
        setSubmitting(true);
        try {
            await onSubmit(form);
            onClose();
        } finally {
            setSubmitting(false);
        }
    };

    const addType = () => {
        if (readOnly) return;
        const value = typeInput.trim().toUpperCase();
        if (!value) return;
        if (form.allowedTypes.includes(value)) {
            setTypeInput('');
            return;
        }
        updateField('allowedTypes', [...form.allowedTypes, value]);
        setTypeInput('');
    };

    const removeType = (value: string) => {
        if (readOnly) return;
        updateField('allowedTypes', form.allowedTypes.filter((t) => t !== value));
    };

    const handleKeyDown = (e: React.KeyboardEvent) => {
        if (e.key === 'Enter') {
            e.preventDefault();
            addType();
        }
    };

    const drawerTitle = isEdit ? '编辑字段类型标准' : isView ? '详情' : '新建字段类型标准';

    return (
        <Drawer
            open={open}
            title={drawerTitle}
            onClose={onClose}
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
                        标准名称 <span className="text-ds-danger">*</span>
                    </label>
                    <input
                        value={form.name}
                        onChange={(e) => updateField('name', e.target.value)}
                        disabled={readOnly}
                        className="w-full px-ds-3 py-ds-2 bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors disabled:opacity-60 disabled:cursor-not-allowed"
                        placeholder="例如：BIGINT 主键标准"
                    />
                    {errors.name && <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.name}</p>}
                </div>

                <div>
                    <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">分类</label>
                    <DsSelect
                        value={form.category}
                        onChange={(v) => updateField('category', v)}
                        disabled={readOnly}
                    >
                        <option value="">请选择分类</option>
                        {CATEGORY_OPTIONS.map((o) => (
                            <option key={o.value} value={o.value}>{o.label}</option>
                        ))}
                    </DsSelect>
                </div>

                <div>
                    <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                        允许的字段类型 <span className="text-ds-danger">*</span>
                    </label>
                    <div className="flex flex-wrap gap-ds-2 mb-ds-2">
                        {COMMON_TYPES.map((t) => {
                            const selected = form.allowedTypes.includes(t);
                            return (
                                <button
                                    key={t}
                                    type="button"
                                    disabled={readOnly}
                                    onClick={() => selected ? removeType(t) : updateField('allowedTypes', [...form.allowedTypes, t])}
                                    className={`px-ds-2 py-ds-1 text-ds-small rounded-ds-sm border transition-colors disabled:opacity-60 disabled:cursor-not-allowed ${
                                        selected
                                            ? 'bg-ds-accent text-white border-ds-accent'
                                            : 'bg-white text-ds-text-secondary border-ds-border-subtle hover:border-ds-accent hover:text-ds-accent'
                                    }`}
                                >
                                    {t}
                                </button>
                            );
                        })}
                    </div>
                    <div className="flex gap-ds-2">
                        <input
                            value={typeInput}
                            onChange={(e) => setTypeInput(e.target.value)}
                            onKeyDown={handleKeyDown}
                            onBlur={addType}
                            disabled={readOnly}
                            className="flex-1 px-ds-3 py-ds-2 bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors disabled:opacity-60 disabled:cursor-not-allowed"
                            placeholder="输入自定义类型，例如 JSON"
                        />
                        <DsButton variant="secondary" type="button" onClick={addType} disabled={readOnly}>
                            添加
                        </DsButton>
                    </div>
                    {errors.allowedTypes &&
                        <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.allowedTypes}</p>}
                    <div className="flex flex-wrap gap-ds-2 mt-ds-2">
                        {form.allowedTypes.map((t) => (
                            <span
                                key={t}
                                className="inline-flex items-center gap-ds-1 px-ds-2 py-ds-1 bg-ds-accent-light text-ds-accent text-ds-small rounded-ds-sm"
                            >
                                {t}
                                {!readOnly && (
                                    <button
                                        type="button"
                                        onClick={() => removeType(t)}
                                        className="hover:text-ds-danger"
                                    >
                                        ×
                                    </button>
                                )}
                            </span>
                        ))}
                    </div>
                </div>

                <div>
                    <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">描述</label>
                    <textarea
                        value={form.description}
                        onChange={(e) => updateField('description', e.target.value)}
                        rows={3}
                        disabled={readOnly}
                        className="w-full px-ds-3 py-ds-2 bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors resize-none disabled:opacity-60 disabled:cursor-not-allowed"
                        placeholder="可选"
                    />
                </div>
            </div>
        </Drawer>
    );
}
