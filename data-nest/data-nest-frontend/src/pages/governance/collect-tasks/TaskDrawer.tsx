import {useEffect, useState} from 'react';
import type {CollectMode, CollectTask, CollectTaskCreateRequest, TaskTriggerType,} from '@/types/collect';
import type {DataSource} from '@/types/datasource';
import {DB_TYPES_WITHOUT_SCHEMA} from '@/constants/datasource';
import {CollectModeEnum, TaskTriggerTypeEnum} from '@/constants/task';
import {getDataSourceSchemas} from '@/api/engineering';
import Drawer from '@/components/Drawer';
import CronPicker from '@/components/CronPicker';
import DsButton from '@/components/DsButton';

interface TaskFormData {
    name: string;
    datasourceId: string;
    scope: string[];
    collectMode: CollectMode;
    triggerType: TaskTriggerType;
    cronExpression: string;
    description: string;
}

const MODE_OPTIONS: { value: CollectMode; label: string }[] = [
    {value: CollectModeEnum.FULL, label: '全量采集'},
    {value: CollectModeEnum.FULL_INCREMENT, label: '全量 + 增量'},
];

const TRIGGER_OPTIONS: { value: TaskTriggerType; label: string }[] = [
    {value: TaskTriggerTypeEnum.MANUAL, label: '手动触发'},
    {value: TaskTriggerTypeEnum.CRON, label: 'Cron 定时'},
];

const EMPTY_FORM: TaskFormData = {
    name: '',
    datasourceId: '',
    scope: [],
    collectMode: CollectModeEnum.FULL,
    triggerType: TaskTriggerTypeEnum.MANUAL,
    cronExpression: '',
    description: '',
};

interface TaskDrawerProps {
    open: boolean;
    mode?: 'create' | 'edit' | 'view';
    editItem: CollectTask | null;
    dataSources: DataSource[];
    onClose: () => void;
    onSubmit: (payload: CollectTaskCreateRequest) => Promise<{ code: number; message?: string } | undefined>;
}

export default function TaskDrawer({open, mode = 'create', editItem, dataSources, onClose, onSubmit}: TaskDrawerProps) {
    const [form, setForm] = useState<TaskFormData>(EMPTY_FORM);
    const [errors, setErrors] = useState<Partial<Record<keyof TaskFormData, string>>>({});
    const [submitting, setSubmitting] = useState(false);
    const [schemas, setSchemas] = useState<string[]>([]);
    const [schemasLoading, setSchemasLoading] = useState(false);

    const isEdit = mode === 'edit';
    const isView = mode === 'view';
    const readOnly = isView;

    useEffect(() => {
        if (open) {
            if (editItem) {
                setForm({
                    name: editItem.name,
                    datasourceId: editItem.datasourceId,
                    scope: editItem.scope || [],
                    collectMode: editItem.collectMode,
                    triggerType: editItem.triggerType,
                    cronExpression: editItem.cronExpression || '',
                    description: editItem.description || '',
                });
                loadSchemas(editItem.datasourceId);
            } else {
                setForm(EMPTY_FORM);
                setSchemas([]);
            }
            setErrors({});
        }
    }, [open, editItem]);

    const loadSchemas = async (datasourceId: string) => {
        if (!datasourceId) {
            setSchemas([]);
            return;
        }
        setSchemasLoading(true);
        try {
            const result = await getDataSourceSchemas(datasourceId);
            setSchemas(result.data);
        } catch {
            setSchemas([]);
        } finally {
            setSchemasLoading(false);
        }
    };

    const updateField = <K extends keyof TaskFormData>(field: K, value: TaskFormData[K]) => {
        if (readOnly) return;
        setForm((prev) => ({...prev, [field]: value}));
        if (errors[field]) {
            setErrors((prev) => ({...prev, [field]: undefined}));
        }
    };

    const handleDatasourceChange = (datasourceId: string) => {
        if (readOnly) return;
        updateField('datasourceId', datasourceId);
        updateField('scope', []);
        loadSchemas(datasourceId);
    };

    const validate = (): boolean => {
        const nextErrors: Partial<Record<keyof TaskFormData, string>> = {};
        if (!form.name.trim()) nextErrors.name = '请输入任务名称';
        if (!form.datasourceId) nextErrors.datasourceId = '请选择数据源';
        if (form.triggerType === TaskTriggerTypeEnum.CRON && !form.cronExpression.trim()) {
            nextErrors.cronExpression = 'Cron 触发必须填写 Cron 表达式';
        }
        setErrors(nextErrors);
        return Object.keys(nextErrors).length === 0;
    };

    const buildPayload = (): CollectTaskCreateRequest => {
        const base = {
            name: form.name.trim(),
            datasourceId: form.datasourceId,
            scope: form.scope.length > 0 ? form.scope : [],
            collectMode: form.collectMode,
            triggerType: form.triggerType,
            cronExpression: form.triggerType === TaskTriggerTypeEnum.CRON ? form.cronExpression.trim() : undefined,
            description: form.description.trim() || undefined,
        };
        if (editItem) {
            return {...base, status: editItem.status} as CollectTaskCreateRequest;
        }
        return base;
    };

    const handleSubmit = async () => {
        if (!validate()) return;
        setSubmitting(true);
        try {
            await onSubmit(buildPayload());
            onClose();
        } finally {
            setSubmitting(false);
        }
    };

    const toggleSchema = (schema: string) => {
        if (readOnly) return;
        setForm((prev) => {
            const exists = prev.scope.includes(schema);
            const next = exists ? prev.scope.filter((s) => s !== schema) : [...prev.scope, schema];
            return {...prev, scope: next};
        });
    };

    const drawerTitle = isEdit ? '编辑采集任务' : isView ? '详情' : '创建采集任务';

    return (
        <Drawer
            open={open}
            title={drawerTitle}
            onClose={onClose}
            footer={
                readOnly ? undefined : (
                    <>
                        <DsButton
                            variant="secondary"
                            data-testid="collect-task-cancel"
                            onClick={onClose}
                        >
                            取消
                        </DsButton>
                        <DsButton
                            variant="primary"
                            data-testid="collect-task-submit"
                            onClick={handleSubmit}
                            disabled={submitting}
                        >
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
                        data-testid="collect-task-name"
                        value={form.name}
                        onChange={(e) => updateField('name', e.target.value)}
                        className="w-full px-ds-3 py-ds-2 bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors"
                        placeholder="例如：订单库元数据采集"
                        disabled={isEdit || readOnly}
                    />
                    {errors.name && <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.name}</p>}
                </div>

                <div>
                    <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                        数据源 <span className="text-ds-danger">*</span>
                    </label>
                    <select
                        data-testid="collect-task-datasource"
                        value={form.datasourceId}
                        onChange={(e) => handleDatasourceChange(e.target.value)}
                        disabled={readOnly}
                        className="w-full px-ds-3 py-ds-2 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors disabled:opacity-60 disabled:cursor-not-allowed"
                    >
                        <option value="">请选择</option>
                        {dataSources.map((ds) => (
                            <option key={ds.id} value={ds.id} data-testid={`collect-task-datasource-option-${ds.id}`}>
                                {ds.name} ({ds.host}:{ds.port}/{ds.databaseName})
                            </option>
                        ))}
                    </select>
                    {errors.datasourceId &&
                        <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.datasourceId}</p>}
                </div>

                <div>
                    <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                        {(() => {
                            const ds = dataSources.find((d) => String(d.id) === form.datasourceId);
                            if (!ds) return '库 / Schema';
                            return DB_TYPES_WITHOUT_SCHEMA.has(ds.type) ? '数据库' : 'Schema';
                        })()}
                    </label>
                    <div className="border border-ds-border-subtle rounded-ds-sm p-ds-3 min-h-[120px] bg-ds-bg-hover">
                        {schemasLoading ? (
                            <p className="text-ds-small text-ds-text-muted">加载中...</p>
                        ) : schemas.length === 0 ? (
                            <p className="text-ds-small text-ds-text-muted">
                                {(() => {
                                    const ds = dataSources.find((d) => String(d.id) === form.datasourceId);
                                    if (!ds) return '请先选择数据源';
                                    return DB_TYPES_WITHOUT_SCHEMA.has(ds.type) ? '暂无数据库' : '暂无 Schema';
                                })()}
                            </p>
                        ) : (
                            <div className="flex flex-wrap gap-ds-2">
                                {schemas.map((schema) => {
                                    const selected = form.scope.includes(schema);
                                    return (
                                        <button
                                            key={schema}
                                            type="button"
                                            data-testid={`collect-task-scope-${schema}`}
                                            disabled={readOnly}
                                            onClick={() => toggleSchema(schema)}
                                            className={`px-ds-2 py-ds-1 rounded-ds-sm text-ds-small transition-colors disabled:opacity-60 disabled:cursor-not-allowed ${
                                                selected
                                                    ? 'bg-ds-accent text-white'
                                                    : 'bg-white text-ds-text-secondary hover:bg-ds-accent-light hover:text-ds-accent'
                                            }`}
                                        >
                                            {schema}
                                        </button>
                                    );
                                })}
                            </div>
                        )}
                    </div>
                </div>

                <div>
                    <label
                        className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">采集模式</label>
                    <div className="flex gap-ds-3">
                        {MODE_OPTIONS.map((o) => (
                            <button
                                key={o.value}
                                type="button"
                                disabled={readOnly}
                                onClick={() => updateField('collectMode', o.value)}
                                className={`flex-1 px-ds-4 py-ds-3 rounded-ds-sm border text-left transition-colors disabled:opacity-60 disabled:cursor-not-allowed ${
                                    form.collectMode === o.value
                                        ? 'border-ds-accent bg-ds-accent-light text-ds-accent'
                                        : 'border-ds-border-subtle bg-white text-ds-text-secondary hover:border-ds-accent hover:text-ds-accent'
                                }`}
                            >
                                <span className="text-ds-body font-semibold block">{o.label}</span>
                            </button>
                        ))}
                    </div>
                </div>

                <div>
                    <label
                        className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">触发方式</label>
                    <div className="flex gap-ds-3">
                        {TRIGGER_OPTIONS.map((o) => (
                            <button
                                key={o.value}
                                type="button"
                                disabled={readOnly}
                                onClick={() => updateField('triggerType', o.value)}
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
                </div>

                {form.triggerType === TaskTriggerTypeEnum.CRON && (
                    <div>
                        <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                            Cron 表达式 <span className="text-ds-danger">*</span>
                        </label>
                        <CronPicker
                            value={form.cronExpression}
                            onChange={(v) => updateField('cronExpression', v)}
                            disabled={readOnly}
                        />
                        {errors.cronExpression &&
                            <p className="mt-ds-1 text-ds-nano text-ds-danger">{errors.cronExpression}</p>}
                    </div>
                )}

                <div>
                    <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">描述</label>
                    <textarea
                        value={form.description}
                        onChange={(e) => updateField('description', e.target.value)}
                        rows={3}
                        disabled={readOnly}
                        className="w-full px-ds-3 py-ds-2 bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors resize-none disabled:opacity-60 disabled:cursor-not-allowed"
                        placeholder="可选：填写采集范围或业务说明"
                    />
                </div>
            </div>
        </Drawer>
    );
}
