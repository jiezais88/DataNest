// Sprint 7 F2：任务模板 新增/编辑/复制 抽屉
// 实体主表单统一用右侧 Drawer（平台惯例：数据源/同步/采集/质量任务等同款）。
// 新增：名称 + 类型 + 说明 +「从已配置任务另存」（选任务则忽略 JSON 原文；手动配置则填 configTemplate JSON）。
// 编辑：仅名称/说明（类型不可变 7303；config 缺省保留原配置）。复制：内置模板复制为自定义（预填配置）。
import {useEffect, useState} from 'react';
import {createTaskTemplate, updateTaskTemplate} from '@/api/taskTemplate';
import {queryCollectTasks} from '@/api/collect';
import {querySyncJobs} from '@/api/sync';
import Drawer from '@/components/Drawer';
import DsButton from '@/components/DsButton';
import {notify} from '@/utils/notify';
import type {TaskTemplate, TaskTemplateType} from '@/types/taskTemplate';
import {TASK_TEMPLATE_TYPE_LABEL} from '@/types/taskTemplate';

export type TemplateFormMode = 'create' | 'edit' | 'copy';

interface TemplateFormDrawerProps {
    open: boolean;
    mode: TemplateFormMode;
    template?: TaskTemplate | null;
    onClose: () => void;
    onSaved: () => void;
}

const MANUAL = '';
const inputClass = 'w-full px-ds-3 py-[9px] bg-white border border-ds-border-subtle rounded-ds-sm text-sm focus:outline-none focus:border-ds-accent';

const CONFIG_PLACEHOLDER = `{
  "placeholders": [{"key": "source_table", "label": "源表名", "required": true}],
  "config": { ... 对应该类型的创建请求，字符串值内用 {key} 占位 ... }
}`;

export default function TemplateFormDrawer({open, mode, template, onClose, onSaved}: TemplateFormDrawerProps) {
    const [name, setName] = useState('');
    const [type, setType] = useState<TaskTemplateType>('SYNC');
    const [description, setDescription] = useState('');
    const [sourceTaskId, setSourceTaskId] = useState<string>(MANUAL);
    const [configTemplate, setConfigTemplate] = useState('');
    const [taskOptions, setTaskOptions] = useState<{ value: string; label: string }[]>([]);
    const [saving, setSaving] = useState(false);

    const isEdit = mode === 'edit';

    useEffect(() => {
        if (!open) return;
        setName(mode === 'copy' && template ? `${template.name} 副本` : (template?.name ?? ''));
        setType(template?.type ?? 'SYNC');
        setDescription(template?.description ?? '');
        setSourceTaskId(MANUAL);
        setConfigTemplate(mode === 'copy' ? (template?.configTemplate ?? '') : '');
    }, [open, mode, template]);

    // 另存候选任务列表：按类型拉取（仅新增模式需要）
    useEffect(() => {
        if (!open || isEdit) return;
        const fetcher = type === 'SYNC'
            ? querySyncJobs({page: 1, pageSize: 100}).then(r => r.data?.records ?? [])
            : queryCollectTasks({page: 1, pageSize: 100}).then(r => r.data?.records ?? []);
        fetcher
            .then(records => {
                setTaskOptions(records.map((t: { id: string; name?: string; taskName?: string }) => ({
                    value: String(t.id),
                    label: t.name ?? t.taskName ?? `任务 ${t.id}`,
                })));
            })
            .catch(() => setTaskOptions([]));
        setSourceTaskId(MANUAL);
    }, [open, isEdit, type]);

    const handleSave = async () => {
        if (!name.trim()) {
            notify.warning('请输入模板名称');
            return;
        }
        if (!isEdit && sourceTaskId === MANUAL && !configTemplate.trim()) {
            notify.warning('手动配置模式下请填写模板 JSON，或选择一个已有任务另存');
            return;
        }
        setSaving(true);
        try {
            if (isEdit && template) {
                // 仅改名称/说明，config 缺省保留（后端语义）
                await updateTaskTemplate(template.id, {name: name.trim(), type: template.type, description});
                notify.success('模板已保存');
            } else {
                await createTaskTemplate({
                    name: name.trim(),
                    type,
                    description,
                    ...(sourceTaskId !== MANUAL
                        ? {sourceTaskId}
                        : {configTemplate: configTemplate.trim()}),
                });
                notify.success(mode === 'copy' ? '已复制为自定义模板' : '模板已创建');
            }
            onSaved();
            onClose();
        } catch {
            // 7302 重名 / 7303 类型非法 / 7306 配置非法等由拦截器统一提示
        } finally {
            setSaving(false);
        }
    };

    const title = isEdit ? `编辑模板 · ${template?.name ?? ''}` : mode === 'copy' ? '复制为自定义模板' : '新增自定义模板';

    return (
        <Drawer
            open={open}
            onClose={onClose}
            title={title}
            width="max-w-[640px]"
            footer={
                <>
                    <DsButton variant="secondary" onClick={onClose} disabled={saving}>取消</DsButton>
                    <DsButton variant="primary" onClick={handleSave} disabled={saving} loading={saving}>
                        保存模板
                    </DsButton>
                </>
            }
        >
            <div className="space-y-ds-4">
                <div className="space-y-ds-2">
                    <span className="text-ds-small text-ds-text-primary font-medium block">
                        模板名称 <span className="text-ds-danger">*</span>
                    </span>
                    <input
                        value={name}
                        onChange={(e) => setName(e.target.value)}
                        placeholder="如：订单表每日同步"
                        className={inputClass}
                    />
                </div>
                <div className="space-y-ds-2">
                    <span className="text-ds-small text-ds-text-primary font-medium block">任务类型</span>
                    <select
                        value={type}
                        onChange={(e) => setType(e.target.value as TaskTemplateType)}
                        disabled={isEdit}
                        aria-label="任务类型"
                        className={`${inputClass} disabled:bg-ds-bg-hover disabled:text-ds-text-muted`}
                    >
                        {(Object.keys(TASK_TEMPLATE_TYPE_LABEL) as TaskTemplateType[]).map(t => (
                            <option key={t} value={t}>{TASK_TEMPLATE_TYPE_LABEL[t]}（{t}）</option>
                        ))}
                    </select>
                    {isEdit && <p className="text-ds-tiny text-ds-text-muted">类型创建后不可修改。</p>}
                </div>
                <div className="space-y-ds-2">
                    <span className="text-ds-small text-ds-text-primary font-medium block">模板说明</span>
                    <textarea
                        value={description}
                        onChange={(e) => setDescription(e.target.value)}
                        placeholder="描述模板用途与占位符说明…"
                        className={`${inputClass} min-h-[72px]`}
                    />
                </div>
                {!isEdit && (
                    <>
                        <div className="space-y-ds-2">
                            <span className="text-ds-small text-ds-text-primary font-medium block">从已配置任务另存</span>
                            <select
                                value={sourceTaskId}
                                onChange={(e) => setSourceTaskId(e.target.value)}
                                aria-label="从已配置任务另存"
                                className={inputClass}
                            >
                                <option value={MANUAL}>— 手动配置模板内容 —</option>
                                {taskOptions.map(o => (
                                    <option key={o.value} value={o.value}>{o.label}</option>
                                ))}
                            </select>
                            <p className="text-ds-tiny text-ds-text-muted">
                                选择任务后将读取其配置生成模板（单表同步的源表/Cron 会自动占位化）。
                            </p>
                        </div>
                        {sourceTaskId === MANUAL && (
                            <div className="space-y-ds-2">
                                <span className="text-ds-small text-ds-text-primary font-medium block">
                                    模板 JSON <span className="text-ds-danger">*</span>
                                </span>
                                <textarea
                                    value={configTemplate}
                                    onChange={(e) => setConfigTemplate(e.target.value)}
                                    placeholder={CONFIG_PLACEHOLDER}
                                    className={`${inputClass} font-mono min-h-[160px]`}
                                />
                            </div>
                        )}
                    </>
                )}
            </div>
        </Drawer>
    );
}
