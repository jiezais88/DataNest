// Sprint 4 按 DAG 告警配置弹窗（PRD §6.5.2 / 技术文档 §8.2）
import {useEffect, useMemo, useState} from 'react';
import {Select, Spin, Switch} from 'antd';
import DsButton from '../../../../components/DsButton';
import DsModal from '../../../../components/DsModal';
import {getDagAlertConfig, putDagAlertConfig} from '../api';
import type {UserVO} from '../../../../api/auth';
import {getUsers} from '../../../../api/auth';
import type {DagAlertConfig} from '../types';
import {notify} from '../../../../utils/notify';
import {getErrorMessage} from '../../../../utils/error';

interface DagAlertConfigModalProps {
    open: boolean;
    dagId?: string | number;
    dagName?: string;
    readOnly?: boolean;
    onClose: () => void;
    /** 新建 DAG 尚未保存时的本地告警草稿 */
    draftConfig?: DagAlertConfig;
    /** 草稿变化回调（新建 DAG 时使用） */
    onDraftChange?: (config: DagAlertConfig) => void;
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
                                                draftConfig,
                                                onDraftChange,
                                            }: DagAlertConfigModalProps) {
    const [loading, setLoading] = useState(false);
    const [saving, setSaving] = useState(false);
    const [enabled, setEnabled] = useState(false);
    // 收件人以 email 数组维护
    const [recipientEmails, setRecipientEmails] = useState<string[]>([]);
    // 已加载的候选用户（搜索结果 + 已选用户）
    const [candidateUsers, setCandidateUsers] = useState<UserVO[]>([]);
    const [usersLoading, setUsersLoading] = useState(false);
    const [conditions, setConditions] = useState<string[]>(['FAILURE']);
    const [timeoutMinutes, setTimeoutMinutes] = useState<number>(30);
    const [error, setError] = useState<string | null>(null);

    const isDraft = !dagId;

    const options = useMemo(() => {
        // 候选用户 + 已选邮箱（避免已选用户不在当前候选列表时丢失标签）
        const map = new Map<string, string>();
        for (const email of recipientEmails) {
            map.set(email, email);
        }
        for (const u of candidateUsers) {
            if (u.email) map.set(u.email, `${u.username}（${u.email}）`);
        }
        return Array.from(map.entries()).map(([email, label]) => ({
            value: email,
            label,
        }));
    }, [candidateUsers, recipientEmails]);

    useEffect(() => {
        if (!open) return;
        setError(null);
        const cfg = isDraft ? draftConfig : undefined;
        const applyConfig = (loaded?: DagAlertConfig) => {
            setEnabled(!!loaded?.enabled);
            const emails = splitRecipients(loaded?.recipients || '');
            setRecipientEmails(emails);
            setConditions(loaded?.triggerConditions?.length ? loaded.triggerConditions : ['FAILURE']);
            setTimeoutMinutes(loaded?.timeoutMinutes ?? 30);
        };
        if (isDraft) {
            applyConfig(cfg);
            return;
        }
        setLoading(true);
        getDagAlertConfig(dagId)
            .then(applyConfig)
            .catch(e => setError(getErrorMessage(e, '加载告警配置失败')))
            .finally(() => setLoading(false));
    }, [open, dagId, isDraft, draftConfig]);

    // 弹窗打开时预加载一批用户作为候选
    useEffect(() => {
        if (!open) return;
        setUsersLoading(true);
        getUsers({page: 1, pageSize: 50, keyword: undefined})
            .then(res => setCandidateUsers(res.data?.records || []))
            .catch(() => {/* 静默失败，用户可继续搜索 */
            })
            .finally(() => setUsersLoading(false));
    }, [open]);

    const handleSearchUsers = async (keyword: string) => {
        if (!keyword) return;
        setUsersLoading(true);
        try {
            const res = await getUsers({page: 1, pageSize: 20, keyword});
            const list = res.data?.records || [];
            setCandidateUsers(prev => {
                const map = new Map(prev.map(u => [u.email, u]));
                for (const u of list) {
                    if (u.email) map.set(u.email, u);
                }
                return Array.from(map.values());
            });
        } catch {
            // 静默失败
        } finally {
            setUsersLoading(false);
        }
    };

    const toggleCondition = (value: string) => {
        setConditions(prev =>
            prev.includes(value) ? prev.filter(c => c !== value) : [...prev, value],
        );
    };

    const validate = (): string | null => {
        if (!enabled) return null;
        if (recipientEmails.length === 0) return '启用告警时必须选择收件人';
        const invalid = recipientEmails.filter(e => !EMAIL_PATTERN.test(e));
        if (invalid.length > 0) return `邮箱格式不合法：${invalid.join('、')}`;
        if (conditions.length === 0) return '请至少选择一个触发条件';
        if (conditions.includes('TIMEOUT') && (!timeoutMinutes || timeoutMinutes <= 0)) {
            return '勾选「DAG 节点超时」时必须填写大于 0 的超时阈值';
        }
        return null;
    };

    const handleSave = async () => {
        const invalid = validate();
        if (invalid) {
            setError(invalid);
            return;
        }
        const payload: DagAlertConfig = {
            enabled,
            // 统一用分号分隔提交（后端存储格式）
            recipients: recipientEmails.join(';'),
            triggerConditions: conditions,
            // 未勾选超时条件时不提交阈值，避免语义脏数据
            timeoutMinutes: conditions.includes('TIMEOUT') ? timeoutMinutes : undefined,
        };
        if (isDraft) {
            onDraftChange?.(payload);
            notify.success('告警配置已暂存，保存 DAG 后生效');
            onClose();
            return;
        }
        setSaving(true);
        setError(null);
        try {
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
                    <div className="flex items-center gap-ds-2">
                        <span data-testid="dag-alert-enabled">
                            <Switch
                                id="dag-alert-switch"
                                checked={enabled}
                                onChange={setEnabled}
                                disabled={readOnly}
                                size="small"
                            />
                        </span>
                        <label
                            htmlFor="dag-alert-switch"
                            className="text-ds-body text-ds-text-primary cursor-pointer"
                        >
                            启用 DAG 邮件告警
                        </label>
                    </div>

                    <div>
                        <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                            收件人 {enabled && <span className="text-ds-danger">*</span>}
                        </label>
                        <Select
                            mode="multiple"
                            showSearch
                            filterOption={false}
                            value={recipientEmails}
                            onChange={setRecipientEmails}
                            onSearch={handleSearchUsers}
                            disabled={readOnly || !enabled}
                            loading={usersLoading}
                            placeholder="搜索用户名或邮箱"
                            options={options}
                            className="w-full"
                            notFoundContent={usersLoading ? <Spin size="small"/> : '无匹配用户'}
                        />
                        <p className="mt-ds-1 text-ds-nano text-ds-text-muted">支持搜索用户名/邮箱并多选</p>
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
