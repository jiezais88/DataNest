// Sprint 4 按 DAG 告警配置弹窗（PRD §6.5.2 / 技术文档 §8.2）
// 范围说明：Sprint 4 前端只提供「按 DAG 覆盖」入口（全局默认配置仅 API 可用，无系统管理页面）。
// 后端按 DAG 读取时会回退全局默认配置：响应 dagId == null 即表示当前继承全局配置，
// 此时顶部提示「保存后将创建该 DAG 的专属配置」。
import {useEffect, useState} from 'react';
import {Spin, Switch} from 'antd';
import DsButton from '../../../../components/DsButton';
import DsModal from '../../../../components/DsModal';
import {getDagAlertConfig, putDagAlertConfig} from '../api';
import type {DagAlertConfig} from '../types';
import {notify} from '../../../../utils/notify';
import {getErrorMessage} from '../../../../utils/error';

interface DagAlertConfigModalProps {
    open: boolean;
    dagId?: string | number;
    dagName?: string;
    readOnly?: boolean;
    onClose: () => void;
}

const TRIGGER_OPTIONS: { value: string; label: string }[] = [
    {value: 'FAILURE', label: 'DAG 执行失败'},
    {value: 'TIMEOUT', label: 'DAG 节点超时'},
    {value: 'SUCCESS', label: 'DAG 执行成功'},
];

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/** 收件人分隔符兼容：; ， , ；（与后端 DagAlertConfigController 校验口径一致） */
function splitRecipients(raw: string): string[] {
    return raw.split(/[;；,，]/).map(s => s.trim()).filter(Boolean);
}

export default function DagAlertConfigModal({
                                                open,
                                                dagId,
                                                dagName,
                                                readOnly = false,
                                                onClose,
                                            }: DagAlertConfigModalProps) {
    const [loading, setLoading] = useState(false);
    const [saving, setSaving] = useState(false);
    const [enabled, setEnabled] = useState(false);
    const [recipients, setRecipients] = useState('');
    const [conditions, setConditions] = useState<string[]>(['FAILURE']);
    const [timeoutMinutes, setTimeoutMinutes] = useState<number>(30);
    // 当前是否继承全局默认配置（响应 dagId 为 null）
    const [inheritingGlobal, setInheritingGlobal] = useState(false);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        if (!open || !dagId) return;
        setError(null);
        setLoading(true);
        getDagAlertConfig(dagId)
            .then(cfg => {
                setEnabled(!!cfg?.enabled);
                setRecipients(cfg?.recipients || '');
                setConditions(cfg?.triggerConditions?.length ? cfg.triggerConditions : ['FAILURE']);
                setTimeoutMinutes(cfg?.timeoutMinutes ?? 30);
                setInheritingGlobal(cfg?.dagId == null);
            })
            .catch(e => setError(getErrorMessage(e, '加载告警配置失败')))
            .finally(() => setLoading(false));
    }, [open, dagId]);

    const toggleCondition = (value: string) => {
        setConditions(prev =>
            prev.includes(value) ? prev.filter(c => c !== value) : [...prev, value],
        );
    };

    const validate = (): string | null => {
        if (!enabled) return null;
        const emails = splitRecipients(recipients);
        if (emails.length === 0) return '启用告警时必须填写收件人';
        const invalid = emails.filter(e => !EMAIL_PATTERN.test(e));
        if (invalid.length > 0) return `邮箱格式不合法：${invalid.join('、')}`;
        if (conditions.length === 0) return '请至少选择一个触发条件';
        if (conditions.includes('TIMEOUT') && (!timeoutMinutes || timeoutMinutes <= 0)) {
            return '勾选「DAG 节点超时」时必须填写大于 0 的超时阈值';
        }
        return null;
    };

    const handleSave = async () => {
        if (!dagId) return;
        const invalid = validate();
        if (invalid) {
            setError(invalid);
            return;
        }
        setSaving(true);
        setError(null);
        try {
            const payload: DagAlertConfig = {
                enabled,
                // 统一用分号分隔提交（后端存储格式）
                recipients: splitRecipients(recipients).join(';'),
                triggerConditions: conditions,
                // 未勾选超时条件时不提交阈值，避免语义脏数据
                timeoutMinutes: conditions.includes('TIMEOUT') ? timeoutMinutes : undefined,
            };
            await putDagAlertConfig(dagId, payload);
            notify.success('告警配置已保存');
            onClose();
        } catch (e) {
            setError(getErrorMessage(e, '保存失败'));
        } finally {
            setSaving(false);
        }
    };

    return (
        <DsModal
            open={open}
            onClose={onClose}
            title={`DAG 告警配置${dagName ? ` — ${dagName}` : ''}`}
            width="w-[520px] max-w-[96vw]"
            footer={
                <>
                    <DsButton variant="secondary" onClick={onClose} disabled={saving}>
                        取消
                    </DsButton>
                    <DsButton
                        onClick={handleSave}
                        disabled={readOnly || saving || loading}
                        title={readOnly ? '只读模式：您没有编辑权限' : undefined}
                    >
                        {saving ? '保存中...' : '保存'}
                    </DsButton>
                </>
            }
        >
            {loading ? (
                <div className="flex items-center justify-center py-ds-6 gap-ds-2 text-ds-small text-ds-text-secondary">
                    <Spin size="small"/> 加载配置中...
                </div>
            ) : (
                <div className="space-y-ds-4">
                    {inheritingGlobal && (
                        <div
                            className="border border-ds-accent/30 bg-ds-accent-light text-ds-accent rounded-ds-sm p-ds-3 text-ds-small">
                            当前继承全局默认配置，保存后将创建该 DAG 的专属配置。
                        </div>
                    )}

                    <label className="flex items-center gap-ds-2 text-ds-body text-ds-text-primary">
                        <Switch
                            checked={enabled}
                            onChange={setEnabled}
                            disabled={readOnly}
                            size="small"
                        />
                        启用 DAG 邮件告警
                    </label>

                    <div>
                        <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                            收件人 {enabled && <span className="text-ds-danger">*</span>}
                        </label>
                        <textarea
                            value={recipients}
                            onChange={e => setRecipients(e.target.value)}
                            rows={2}
                            disabled={readOnly || !enabled}
                            placeholder="engineer@example.com; admin@example.com"
                            className="w-full px-ds-3 py-ds-2 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors resize-none disabled:opacity-60 disabled:cursor-not-allowed"
                        />
                        <p className="mt-ds-1 text-ds-nano text-ds-text-muted">多个收件人用分号分隔</p>
                    </div>

                    <div>
                        <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                            触发条件
                        </label>
                        <div className="space-y-ds-1.5">
                            {TRIGGER_OPTIONS.map(o => (
                                <label key={o.value}
                                       className="flex items-center gap-ds-2 text-ds-body text-ds-text-primary">
                                    <input
                                        type="checkbox"
                                        checked={conditions.includes(o.value)}
                                        onChange={() => toggleCondition(o.value)}
                                        disabled={readOnly || !enabled}
                                    />
                                    {o.label}
                                </label>
                            ))}
                        </div>
                    </div>

                    <div>
                        <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                            超时阈值（分钟）
                        </label>
                        <input
                            type="number"
                            min={1}
                            value={timeoutMinutes}
                            onChange={e => setTimeoutMinutes(Number(e.target.value) || 30)}
                            disabled={readOnly || !enabled || !conditions.includes('TIMEOUT')}
                            className="w-[120px] px-ds-3 py-ds-2 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors disabled:opacity-60 disabled:cursor-not-allowed"
                        />
                        <p className="mt-ds-1 text-ds-nano text-ds-text-muted">仅当勾选「DAG 节点超时」时生效</p>
                    </div>

                    {error && (
                        <div
                            className="border border-ds-danger/30 bg-ds-danger/5 text-ds-danger rounded-ds-sm p-ds-3 text-ds-small">
                            {error}
                        </div>
                    )}
                </div>
            )}
        </DsModal>
    );
}
