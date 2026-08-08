// Sprint 7 F2：从模板一键创建任务弹窗
// 按 configTemplate.placeholders 动态渲染表单：DATASOURCE 类型渲染数据源下拉，其余为 mono 文本框
// （defaultValue 预填；required 为空时前端拦截，后端兜底 7305）。
import {useEffect, useState} from 'react';
import {Select} from 'antd';
import {getDataSources} from '../../../api/datasource';
import {createTaskFromTemplate} from '../../../api/taskTemplate';
import DsButton from '../../../components/DsButton';
import DsModal from '../../../components/DsModal';
import {notify} from '../../../utils/notify';
import type {TaskTemplate, TemplatePlaceholder} from '../../../types/taskTemplate';
import {TASK_TEMPLATE_TYPE_LABEL} from '../../../types/taskTemplate';

/** 解析模板占位符（configTemplate 非法 JSON 时按无占位符处理） */
export function parseTemplatePlaceholders(configTemplate?: string): TemplatePlaceholder[] {
    if (!configTemplate) return [];
    try {
        const parsed = JSON.parse(configTemplate);
        return Array.isArray(parsed?.placeholders) ? parsed.placeholders : [];
    } catch {
        return [];
    }
}

interface CreateTaskModalProps {
    open: boolean;
    template: TaskTemplate | null;
    onClose: () => void;
}

const inputClass = 'w-full px-ds-3 py-[9px] bg-white border border-ds-border-subtle rounded-ds-sm text-sm focus:outline-none focus:border-ds-accent font-mono';

export default function CreateTaskModal({open, template, onClose}: CreateTaskModalProps) {
    const [taskName, setTaskName] = useState('');
    const [values, setValues] = useState<Record<string, string>>({});
    const [placeholders, setPlaceholders] = useState<TemplatePlaceholder[]>([]);
    const [datasourceOptions, setDatasourceOptions] = useState<{ value: string; label: string }[]>([]);
    const [saving, setSaving] = useState(false);

    useEffect(() => {
        if (!open || !template) return;
        setTaskName('');
        const phs = parseTemplatePlaceholders(template.configTemplate);
        setPlaceholders(phs);
        // defaultValue 预填
        const init: Record<string, string> = {};
        for (const ph of phs) {
            if (ph.defaultValue) init[ph.key] = ph.defaultValue;
        }
        setValues(init);
        // 有 DATASOURCE 占位符才拉数据源下拉
        if (phs.some(p => p.valueType === 'DATASOURCE')) {
            getDataSources({page: 1, pageSize: 100})
                .then(res => {
                    setDatasourceOptions((res.data?.records ?? []).map(d => ({
                        value: String(d.id),
                        label: d.name || `数据源 ${d.id}`,
                    })));
                })
                .catch(() => setDatasourceOptions([]));
        }
    }, [open, template]);

    const handleSubmit = async () => {
        if (!template) return;
        if (!taskName.trim()) {
            notify.warning('请输入任务名称');
            return;
        }
        for (const ph of placeholders) {
            if (ph.required && !values[ph.key]?.trim()) {
                notify.warning(`请填写「${ph.label}」（{${ph.key}}）`);
                return;
            }
        }
        setSaving(true);
        try {
            await createTaskFromTemplate(template.id, {name: taskName.trim(), values});
            notify.success(`已创建${TASK_TEMPLATE_TYPE_LABEL[template.type]}「${taskName.trim()}」`);
            onClose();
        } catch {
            // 7305 占位符缺失 / 7306 配置非法 / 7307 模板停用等由拦截器统一提示
        } finally {
            setSaving(false);
        }
    };

    return (
        <DsModal
            open={open}
            onClose={onClose}
            title={template ? `从模板创建：${template.name}` : '从模板创建'}
            width="w-[520px]"
            bordered
            footer={
                <>
                    <DsButton variant="secondary" onClick={onClose} disabled={saving}>取消</DsButton>
                    <DsButton variant="primary" onClick={handleSubmit} disabled={saving}>
                        {saving ? '创建中...' : '生成任务'}
                    </DsButton>
                </>
            }
        >
            <div className="space-y-ds-4">
                <div className="space-y-ds-2">
                    <span className="text-ds-small text-ds-text-primary font-medium block">
                        任务名称 <span className="text-ds-danger">*</span>
                    </span>
                    <input
                        value={taskName}
                        onChange={(e) => setTaskName(e.target.value)}
                        placeholder="如：dwd_orders 每日同步"
                        className={inputClass.replace(' font-mono', '')}
                    />
                </div>
                {placeholders.map(ph => (
                    <div className="space-y-ds-2" key={ph.key}>
                        <span className="text-ds-small text-ds-text-primary font-medium block">
                            {ph.label}
                            {ph.required && <span className="text-ds-danger"> *</span>}
                            <span className="text-ds-tiny text-ds-text-muted font-normal">（{`{${ph.key}}`}）</span>
                        </span>
                        {ph.valueType === 'DATASOURCE' ? (
                            <Select
                                showSearch
                                allowClear
                                value={values[ph.key] || undefined}
                                onChange={(v) => setValues(prev => ({...prev, [ph.key]: v ?? ''}))}
                                placeholder="选择数据源"
                                options={datasourceOptions}
                                className="w-full"
                                optionFilterProp="label"
                            />
                        ) : (
                            <input
                                value={values[ph.key] ?? ''}
                                onChange={(e) => setValues(prev => ({...prev, [ph.key]: e.target.value}))}
                                placeholder={ph.defaultValue ? `默认：${ph.defaultValue}` : `请输入${ph.label}`}
                                className={inputClass}
                            />
                        )}
                    </div>
                ))}
                {placeholders.length === 0 && (
                    <p className="text-ds-tiny text-ds-text-muted">该模板无占位参数，直接生成任务。</p>
                )}
            </div>
        </DsModal>
    );
}
