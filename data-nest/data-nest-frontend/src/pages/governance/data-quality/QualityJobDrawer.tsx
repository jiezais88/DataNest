import {useEffect, useState} from 'react';
import {Select} from 'antd';
import Drawer from '../../../components/Drawer';
import DsButton from '../../../components/DsButton';
import {queryQualityRules} from '../../../api/quality';
import CronPicker from '../../../components/CronPicker';
import type {QualityAlertLevel, QualityJob, QualityJobCreateRequest, AutoTriggerObjectType, QualityRule, QualityRuleType} from '../../../types/quality';
import {QUALITY_TYPE_LABEL} from '../../../types/quality';
import AutoTriggerSelect from './AutoTriggerSelect';

interface QualityJobFormData {
    name: string;
    description: string;
    enabled: number;
    scheduledEnabled: number;
    cron: string;
    autoTriggerEnabled: number;
    autoTriggerObjectType: AutoTriggerObjectType | '';
    autoTriggerObjectId: string;
    alertLevel: QualityAlertLevel;
    /** 引用的质量规则 ID 集合（多对多） */
    ruleIds: string[];
}

const EMPTY_FORM: QualityJobFormData = {
    name: '',
    description: '',
    enabled: 1,
    scheduledEnabled: 0,
    cron: '',
    autoTriggerEnabled: 0,
    autoTriggerObjectType: '',
    autoTriggerObjectId: '',
    alertLevel: 'SEVERE_WARNING',
    ruleIds: [],
};

const ALERT_LEVEL_OPTIONS: { value: QualityAlertLevel; label: string }[] = [
    {value: 'SEVERE_ONLY', label: '仅严重'},
    {value: 'SEVERE_WARNING', label: '严重 + 警告'},
];

interface QualityJobDrawerProps {
    open: boolean;
    mode?: 'create' | 'edit' | 'view';
    editItem: QualityJob | null;
    onClose: () => void;
    onSubmit: (payload: QualityJobCreateRequest) => Promise<unknown>;
}

export default function QualityJobDrawer({
                                             open,
                                             mode = 'create',
                                             editItem,
                                             onClose,
                                             onSubmit,
                                         }: QualityJobDrawerProps) {
    const [form, setForm] = useState<QualityJobFormData>(EMPTY_FORM);
    const [errors, setErrors] = useState<Partial<Record<keyof QualityJobFormData, string>>>({});
    const [submitting, setSubmitting] = useState(false);
    // 规则库（供任务引用规则）
    const [ruleOptions, setRuleOptions] = useState<QualityRule[]>([]);

    const isEdit = mode === 'edit';
    const isView = mode === 'view';
    const readOnly = isView;

    // 触发方式单选（手动 / 定时 / 自动触发 三选一，互斥）
    type TriggerMode = 'MANUAL' | 'SCHEDULED' | 'AUTO_TRIGGER';
    const TRIGGER_OPTIONS: { value: TriggerMode; label: string }[] = [
        {value: 'MANUAL', label: '手动触发'},
        {value: 'SCHEDULED', label: 'Cron 定时'},
        {value: 'AUTO_TRIGGER', label: '自动触发'},
    ];
    // 由 scheduledEnabled/autoTriggerEnabled 派生当前选中
    const triggerMode: TriggerMode = form.autoTriggerEnabled === 1 ? 'AUTO_TRIGGER'
        : (form.scheduledEnabled === 1 ? 'SCHEDULED' : 'MANUAL');
    const handleTriggerModeChange = (mode: TriggerMode) => {
        updateField('scheduledEnabled', mode === 'SCHEDULED' ? 1 : 0);
        updateField('autoTriggerEnabled', mode === 'AUTO_TRIGGER' ? 1 : 0);
        if (mode !== 'SCHEDULED') updateField('cron', '');
        if (mode !== 'AUTO_TRIGGER') {
            updateField('autoTriggerObjectType', '');
            updateField('autoTriggerObjectId', '');
        }
    };

    useEffect(() => {
        if (open) {
            if (editItem) {
                setForm({
                    name: editItem.name,
                    description: editItem.description || '',
                    enabled: editItem.enabled ?? 1,
                    scheduledEnabled: editItem.scheduledEnabled ?? 0,
                    cron: editItem.cron || '',
                    autoTriggerEnabled: editItem.autoTriggerEnabled ?? 0,
                    autoTriggerObjectType: editItem.autoTriggerObjectType || '',
                    autoTriggerObjectId: editItem.autoTriggerObjectId || '',
                    alertLevel: editItem.alertLevel || 'SEVERE_WARNING',
                    ruleIds: editItem.ruleIds || [],
                });
            } else {
                setForm(EMPTY_FORM);
            }
            setErrors({});
            // 加载规则库（供引用规则多选）
            queryQualityRules({page: 1, pageSize: 1000})
                .then((res) => setRuleOptions(res.data.records || []))
                .catch(() => setRuleOptions([]));
        }
    }, [open, editItem]);

    const updateField = <K extends keyof QualityJobFormData>(field: K, value: QualityJobFormData[K]) => {
        if (readOnly) return;
        setForm((prev) => ({...prev, [field]: value}));
        if (errors[field]) {
            setErrors((prev) => ({...prev, [field]: undefined}));
        }
    };

    const validate = (): boolean => {
        const nextErrors: Partial<Record<keyof QualityJobFormData, string>> = {};
        if (!form.name.trim()) nextErrors.name = '请输入任务名称';
        if (form.scheduledEnabled === 1 && !form.cron.trim()) nextErrors.cron = '开启定时调度时请输入 Cron 表达式';
        if (form.autoTriggerEnabled === 1) {
            if (!form.autoTriggerObjectType) nextErrors.autoTriggerObjectType = '开启自动触发时请选择对象类型';
            else if (!form.autoTriggerObjectId) nextErrors.autoTriggerObjectId = '请选择绑定对象';
        }
        setErrors(nextErrors);
        return Object.keys(nextErrors).length === 0;
    };

    const handleSubmit = async () => {
        if (!validate()) return;
        setSubmitting(true);
        try {
            const payload: QualityJobCreateRequest = {
                name: form.name.trim(),
                description: form.description.trim() || undefined,
                enabled: form.enabled,
                scheduledEnabled: form.scheduledEnabled,
                cron: form.scheduledEnabled === 1 ? form.cron.trim() : undefined,
                autoTriggerEnabled: form.autoTriggerEnabled,
                autoTriggerObjectType: form.autoTriggerEnabled === 1 ? form.autoTriggerObjectType || undefined : undefined,
                autoTriggerObjectId: form.autoTriggerEnabled === 1 ? form.autoTriggerObjectId || undefined : undefined,
                alertLevel: form.alertLevel,
                // 任务引用的规则集合
                ruleIds: form.ruleIds.length > 0 ? form.ruleIds : undefined,
            };
            await onSubmit(payload);
            onClose();
        } finally {
            setSubmitting(false);
        }
    };

    const drawerTitle = isEdit ? '编辑质量任务' : isView ? '质量任务详情' : '新增质量任务';

    const inputClass = 'w-full px-ds-3 py-ds-2 bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors disabled:opacity-60 disabled:cursor-not-allowed';
    const selectClass = 'w-full px-ds-3 py-ds-2 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-small text-ds-text-primary focus:outline-none focus-visible:border-ds-accent disabled:opacity-60 disabled:cursor-not-allowed';

    return (
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
                        任务名称 <span className="text-ds-danger">*</span>
                    </label>
                    <input
                        value={form.name}
                        onChange={(e) => updateField('name', e.target.value)}
                        disabled={readOnly}
                        maxLength={100}
                        className={inputClass}
                        placeholder="例如：核心业务表完整性检查"
                    />
                    {errors.name && <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.name}</p>}
                </div>

                <div>
                    <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                        任务描述
                    </label>
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

                <div className="border border-ds-border-subtle rounded-ds-md p-ds-4 space-y-ds-3">
                    <label className="flex items-center justify-between cursor-pointer">
                        <span className="text-ds-small font-semibold text-ds-text-secondary">启用任务</span>
                        <input
                            type="checkbox"
                            checked={form.enabled === 1}
                            onChange={(e) => updateField('enabled', e.target.checked ? 1 : 0)}
                            disabled={readOnly}
                            className="w-4 h-4 rounded border-ds-border-subtle text-ds-accent focus:ring-ds-accent disabled:opacity-60 disabled:cursor-not-allowed"
                        />
                    </label>

                    <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                        触发方式 <span className="text-ds-danger">*</span>
                    </label>
                    <div className="flex gap-ds-3">
                        {TRIGGER_OPTIONS.map((o) => (
                            <button
                                key={o.value}
                                type="button"
                                onClick={() => handleTriggerModeChange(o.value)}
                                disabled={readOnly}
                                className={`flex-1 px-ds-3 py-ds-2.5 rounded-ds-sm border text-ds-small transition-colors disabled:opacity-60 disabled:cursor-not-allowed ${
                                    triggerMode === o.value
                                        ? 'border-ds-accent bg-ds-accent-light text-ds-accent font-semibold'
                                        : 'border-ds-border-subtle bg-white text-ds-text-secondary hover:border-ds-accent hover:text-ds-accent'
                                }`}
                            >
                                {o.label}
                            </button>
                        ))}
                    </div>
                    {triggerMode === 'SCHEDULED' && (
                        <div>
                            <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                                Cron 表达式 <span className="text-ds-danger">*</span>
                            </label>
                            {readOnly ? (
                                <div
                                    className="px-ds-3 py-ds-2 bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary font-mono">
                                    {form.cron || '-'}
                                </div>
                            ) : (
                                <CronPicker
                                    value={form.cron}
                                    onChange={(v) => updateField('cron', v)}
                                />
                            )}
                            {errors.cron && <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.cron}</p>}
                        </div>
                    )}
                    {triggerMode === 'AUTO_TRIGGER' && (
                        <>
                            <AutoTriggerSelect
                                objectType={form.autoTriggerObjectType}
                                objectId={form.autoTriggerObjectId}
                                readOnly={readOnly}
                                onChange={(type, id) => {
                                    updateField('autoTriggerObjectType', type);
                                    updateField('autoTriggerObjectId', id);
                                }}
                            />
                            {errors.autoTriggerObjectType &&
                                <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.autoTriggerObjectType}</p>}
                        </>
                    )}
                </div>

                <div>
                    <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                        告警触发等级 <span className="text-ds-danger">*</span>
                    </label>
                    <select
                        value={form.alertLevel}
                        onChange={(e) => updateField('alertLevel', e.target.value as QualityAlertLevel)}
                        disabled={readOnly}
                        className={selectClass}
                    >
                        {ALERT_LEVEL_OPTIONS.map((o) => (
                            <option key={o.value} value={o.value}>{o.label}</option>
                        ))}
                    </select>
                </div>

                <div>
                    <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                        引用质量规则 <span className="text-ds-nano text-ds-text-muted font-normal">（从规则库选择，可多选）</span>
                    </label>
                    <Select
                        mode="multiple"
                        showSearch
                        optionFilterProp="label"
                        value={form.ruleIds}
                        onChange={(v) => updateField('ruleIds', v as string[])}
                        disabled={readOnly}
                        loading={false}
                        placeholder={ruleOptions.length === 0 ? '暂无可用规则，可先到「质量规则」页面创建' : '请选择规则（可多选）'}
                        notFoundContent={ruleOptions.length === 0 ? '暂无可用规则，可先到「质量规则」页面创建' : '无匹配规则'}
                        options={ruleOptions.map((r) => {
                            const typeLabel = QUALITY_TYPE_LABEL[r.type as QualityRuleType] || r.type || '';
                            return {
                                value: String(r.id),
                                label: typeLabel ? `${r.name}（${typeLabel}）` : r.name,
                            };
                        })}
                        allowClear
                        className="w-full"
                    />
                </div>
            </div>
        </Drawer>
    );
}
