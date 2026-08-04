import {useEffect, useState} from 'react';
import Drawer from '../../../components/Drawer';
import DsButton from '../../../components/DsButton';
import {getDataSources} from '../../../api/datasource';
import {queryQualityRules} from '../../../api/quality';
import CronPicker from '../../../components/CronPicker';
import type {QualityAlertLevel, QualityJob, QualityJobCreateRequest, AutoTriggerObjectType, QualityRule} from '../../../types/quality';
import AutoTriggerSelect from './AutoTriggerSelect';

interface QualityJobFormData {
    name: string;
    description: string;
    datasourceId: string;
    enabled: number;
    scheduledEnabled: number;
    cron: string;
    autoTriggerEnabled: number;
    autoTriggerObjectType: AutoTriggerObjectType | '';
    autoTriggerObjectId: string;
    alertLevel: QualityAlertLevel;
    /** 引用的质量规则 ID 集合（Sprint 7 多对多） */
    ruleIds: string[];
}

const EMPTY_FORM: QualityJobFormData = {
    name: '',
    description: '',
    datasourceId: '',
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
    const [datasources, setDatasources] = useState<{ id: string; name: string }[]>([]);
    // 规则库（供任务引用规则，Sprint 7）
    const [ruleOptions, setRuleOptions] = useState<QualityRule[]>([]);

    const isEdit = mode === 'edit';
    const isView = mode === 'view';
    const readOnly = isView;

    useEffect(() => {
        if (open) {
            if (editItem) {
                setForm({
                    name: editItem.name,
                    description: editItem.description || '',
                    datasourceId: editItem.datasourceId || '',
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
            // 加载数据源下拉（全量）
            getDataSources({page: 1, pageSize: 1000})
                .then((res) => {
                    setDatasources((res.data.records || []).map((d) => ({id: String(d.id), name: d.name})));
                })
                .catch(() => setDatasources([]));
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
                datasourceId: form.datasourceId || undefined,
                enabled: form.enabled,
                scheduledEnabled: form.scheduledEnabled,
                cron: form.scheduledEnabled === 1 ? form.cron.trim() : undefined,
                autoTriggerEnabled: form.autoTriggerEnabled,
                autoTriggerObjectType: form.autoTriggerEnabled === 1 ? form.autoTriggerObjectType || undefined : undefined,
                autoTriggerObjectId: form.autoTriggerEnabled === 1 ? form.autoTriggerObjectId || undefined : undefined,
                alertLevel: form.alertLevel,
                // Sprint 7：任务引用的规则集合
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
                        数据源范围
                    </label>
                    <select
                        value={form.datasourceId}
                        onChange={(e) => updateField('datasourceId', e.target.value)}
                        disabled={readOnly}
                        className={selectClass}
                    >
                        <option value="">不限数据源</option>
                        {datasources.map((d) => (
                            <option key={d.id} value={d.id}>{d.name}</option>
                        ))}
                    </select>
                    <p className="mt-ds-1 text-ds-nano text-ds-text-muted">用于限定检查范围，规则选表时以此为默认数据源</p>
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

                    <label className="flex items-center justify-between cursor-pointer">
                        <span className="text-ds-small font-semibold text-ds-text-secondary">定时调度</span>
                        <input
                            type="checkbox"
                            checked={form.scheduledEnabled === 1}
                            onChange={(e) => updateField('scheduledEnabled', e.target.checked ? 1 : 0)}
                            disabled={readOnly}
                            className="w-4 h-4 rounded border-ds-border-subtle text-ds-accent focus:ring-ds-accent disabled:opacity-60 disabled:cursor-not-allowed"
                        />
                    </label>
                    {form.scheduledEnabled === 1 && (
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

                    <label className="flex items-center justify-between cursor-pointer">
                        <span className="text-ds-small font-semibold text-ds-text-secondary">任务完成自动触发</span>
                        <input
                            type="checkbox"
                            checked={form.autoTriggerEnabled === 1}
                            onChange={(e) => updateField('autoTriggerEnabled', e.target.checked ? 1 : 0)}
                            disabled={readOnly}
                            className="w-4 h-4 rounded border-ds-border-subtle text-ds-accent focus:ring-ds-accent disabled:opacity-60 disabled:cursor-not-allowed"
                        />
                    </label>
                    {form.autoTriggerEnabled === 1 && (
                        <AutoTriggerSelect
                            objectType={form.autoTriggerObjectType}
                            objectId={form.autoTriggerObjectId}
                            readOnly={readOnly}
                            onChange={(type, id) => {
                                updateField('autoTriggerObjectType', type);
                                updateField('autoTriggerObjectId', id);
                            }}
                        />
                    )}
                    {errors.autoTriggerObjectType &&
                        <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.autoTriggerObjectType}</p>}
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
                        引用质量规则 <span className="text-ds-nano text-ds-text-muted font-normal">（从规则库选择，可多选，Sprint 7）</span>
                    </label>
                    {ruleOptions.length === 0 ? (
                        <p className="text-ds-nano text-ds-text-muted">暂无可用规则，可先到「质量规则」页面创建。</p>
                    ) : (
                        <div className="border border-ds-border-subtle rounded-ds-md p-ds-3 max-h-[200px] overflow-auto space-y-ds-1.5">
                            {ruleOptions.map((r) => {
                                const checked = form.ruleIds.some((id) => String(id) === String(r.id));
                                return (
                                    <label
                                        key={String(r.id)}
                                        className={`flex items-center gap-ds-2 px-ds-2 py-ds-1.5 rounded-ds-sm cursor-pointer transition-colors ${
                                            checked ? 'bg-ds-accent-light' : 'hover:bg-ds-bg-hover'
                                        }`}
                                    >
                                        <input
                                            type="checkbox"
                                            checked={checked}
                                            disabled={readOnly}
                                            onChange={(e) => {
                                                const id = String(r.id);
                                                setForm((prev) => ({
                                                    ...prev,
                                                    ruleIds: e.target.checked
                                                        ? [...prev.ruleIds, id]
                                                        : prev.ruleIds.filter((x) => x !== id),
                                                }));
                                            }}
                                            className="w-4 h-4 rounded border-ds-border-subtle text-ds-accent focus:ring-ds-accent disabled:opacity-60 disabled:cursor-not-allowed"
                                        />
                                        <div className="flex items-center justify-between flex-1 min-w-0">
                                            <span className="text-ds-small text-ds-text-primary truncate" title={r.name}>{r.name}</span>
                                            <span className="text-ds-nano text-ds-text-muted ml-ds-2 whitespace-nowrap">{r.type || ''}</span>
                                        </div>
                                    </label>
                                );
                            })}
                        </div>
                    )}
                </div>
            </div>
        </Drawer>
    );
}
