import {useEffect, useState} from 'react';
import Drawer from '../../../components/Drawer';
import type {FieldTypeStandard} from '../../../types/dataStandard';

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

interface FieldTypeStandardDrawerProps {
    open: boolean;
    editItem: FieldTypeStandard | null;
    onClose: () => void;
    onSubmit: (payload: FieldTypeStandardFormData) => Promise<{ code: number; message?: string } | undefined>;
}

export default function FieldTypeStandardDrawer({open, editItem, onClose, onSubmit}: FieldTypeStandardDrawerProps) {
    const [form, setForm] = useState<FieldTypeStandardFormData>(EMPTY_FORM);
    const [errors, setErrors] = useState<Partial<Record<keyof FieldTypeStandardFormData, string>>>({});
    const [submitting, setSubmitting] = useState(false);
    const [typeInput, setTypeInput] = useState('');

    const isEdit = !!editItem;

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
            const result = await onSubmit(form);
            if (result && result.code === 200) {
                onClose();
            }
        } finally {
            setSubmitting(false);
        }
    };

    const addType = () => {
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
        updateField('allowedTypes', form.allowedTypes.filter((t) => t !== value));
    };

    const handleKeyDown = (e: React.KeyboardEvent) => {
        if (e.key === 'Enter') {
            e.preventDefault();
            addType();
        }
    };

    return (
        <Drawer
            open={open}
            title={isEdit ? '编辑字段类型标准' : '新建字段类型标准'}
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
                        标准名称 <span className="text-ds-danger">*</span>
                    </label>
                    <input
                        value={form.name}
                        onChange={(e) => updateField('name', e.target.value)}
                        className="w-full px-ds-3 py-ds-2 bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors"
                        placeholder="例如：BIGINT 主键标准"
                    />
                    {errors.name && <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.name}</p>}
                </div>

                <div>
                    <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">分类</label>
                    <input
                        value={form.category}
                        onChange={(e) => updateField('category', e.target.value)}
                        className="w-full px-ds-3 py-ds-2 bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors"
                        placeholder="例如：数值、字符串、时间"
                    />
                </div>

                <div>
                    <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                        允许的字段类型 <span className="text-ds-danger">*</span>
                    </label>
                    <div className="flex gap-ds-2">
                        <input
                            value={typeInput}
                            onChange={(e) => setTypeInput(e.target.value)}
                            onKeyDown={handleKeyDown}
                            className="flex-1 px-ds-3 py-ds-2 bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors"
                            placeholder="输入类型后按回车，例如：INT"
                        />
                        <button
                            type="button"
                            onClick={addType}
                            className="px-ds-4 py-ds-2 bg-white border border-ds-border-subtle hover:border-ds-accent text-ds-accent text-ds-small font-semibold rounded-ds-sm transition-colors"
                        >
                            添加
                        </button>
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
                                <button
                                    type="button"
                                    onClick={() => removeType(t)}
                                    className="hover:text-ds-danger"
                                >
                                    ×
                                </button>
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
                        className="w-full px-ds-3 py-ds-2 bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors resize-none"
                        placeholder="可选"
                    />
                </div>
            </div>
        </Drawer>
    );
}
