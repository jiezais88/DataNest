// Sprint 5：告警规则配置弹窗（全局通用，供告警中心 + DAG/同步任务/采集任务快捷入口复用）
// 三种模式（微服务化后统一走 app-alert）：
//  - create：全局告警中心新增规则 → POST /alert/alert-rules
//  - edit：全局告警中心编辑已有规则 → PUT /alert/alert-rules/{id}
//  - quick：业务模块快捷入口（DAG/同步任务/采集任务），对象类型与对象锁定 →
//           PUT /alert/rules/by-object?objectType=...&objectId=...；新建未保存 DAG 走本地草稿
import {useEffect, useMemo, useState} from 'react';
import {Select, Spin, Switch, TreeSelect} from 'antd';
import DsButton from './DsButton';
import DsModal from './DsModal';
import UserSelect from './UserSelect';
import {
    createAlertRule,
    getAlertRuleObjectOptions,
    getCollectTaskAlertRule,
    getDagAlertRule,
    getSyncJobAlertRule,
    putCollectTaskAlertRule,
    putDagAlertRule,
    putSyncJobAlertRule,
    updateAlertRule,
} from '@/api/alert';
import type {AlertObjectOption, AlertObjectType, AlertRuleDTO, AlertTriggerType} from '@/types/alert';
import {notify} from '@/utils/notify';
import {getErrorMessage} from '@/utils/error';

const OBJECT_TYPE_OPTIONS: { value: AlertObjectType; label: string }[] = [
    {value: 'DAG', label: 'DAG'},
    {value: 'SYNC_JOB', label: '同步任务'},
    {value: 'COLLECT_TASK', label: '采集任务'},
    {value: 'QUALITY', label: '质量任务'},
];

const TRIGGER_OPTIONS: { value: AlertTriggerType; label: string }[] = [
    {value: 'FAILURE', label: '失败'},
    {value: 'TIMEOUT', label: '超时'},
    {value: 'SUCCESS', label: '成功'},
];

export interface AlertRuleModalProps {
    open: boolean;
    onClose: () => void;
    /** 保存成功后回调（列表页刷新用） */
    onSaved?: () => void;
    mode: 'create' | 'edit' | 'quick';
    /** edit 模式：已有规则（列表行数据，含 userIds） */
    initialRule?: AlertRuleDTO;
    /** quick 模式：对象类型（锁定） */
    quickObjectType?: AlertObjectType;
    /** quick 模式：对象 ID（锁定）；为空表示新建 DAG 尚未保存，走草稿 */
    quickObjectId?: string;
    /** quick 模式：展示对象名 */
    quickObjectName?: string;
    readOnly?: boolean;
    /** quick 模式 + 对象未保存时的本地草稿（新建 DAG） */
    draftRule?: AlertRuleDTO;
    /** 草稿变化回调（新建 DAG 时使用） */
    onDraftChange?: (rule: AlertRuleDTO) => void;
}

export default function AlertRuleModal({
                                           open,
                                           onClose,
                                           onSaved,
                                           mode,
                                           initialRule,
                                           quickObjectType,
                                           quickObjectId,
                                           quickObjectName,
                                           readOnly = false,
                                           draftRule,
                                           onDraftChange,
                                       }: AlertRuleModalProps) {
    const [loading, setLoading] = useState(false);
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const [name, setName] = useState<string>('');
    const [objectType, setObjectType] = useState<AlertObjectType>('DAG');
    const [objectIds, setObjectIds] = useState<string[]>([]);
    const [objectOptions, setObjectOptions] = useState<AlertObjectOption[]>([]);
    const [objectOptionsLoading, setObjectOptionsLoading] = useState(false);
    const [conditions, setConditions] = useState<AlertTriggerType[]>(['FAILURE']);
    const [userIds, setUserIds] = useState<string[]>([]);
    const [timeoutMinutes, setTimeoutMinutes] = useState<number>(30);
    const [enabled, setEnabled] = useState(true);
    const [objectDropdownOpen, setObjectDropdownOpen] = useState(false);

    const isQuick = mode === 'quick';
    const isDraft = isQuick && !quickObjectId;

    // 当前规则对象类型与 ID 是否锁定（quick 模式锁定）
    const typeLocked = isQuick;
    const objectLocked = isQuick;

    // 打开时初始化：根据模式加载已有规则或默认值
    useEffect(() => {
        if (!open) return;
        setError(null);

        const applyRule = (rule?: AlertRuleDTO | null) => {
            if (!rule) {
                // 无规则 → 默认值（quick 模式带对象）
                setName('');
                setObjectType(quickObjectType || 'DAG');
                setObjectIds(quickObjectId ? [quickObjectId] : []);
                setConditions(['FAILURE']);
                setUserIds([]);
                setTimeoutMinutes(30);
                setEnabled(true);
                return;
            }
            setName(rule.name || '');
            setObjectType(rule.objectType || 'DAG');
            setObjectIds(rule.objectIds?.length ? rule.objectIds : (quickObjectId ? [quickObjectId] : []));
            setConditions(rule.triggerConditions?.length ? rule.triggerConditions : ['FAILURE']);
            setUserIds(rule.userIds || []);
            setTimeoutMinutes(rule.timeoutMinutes ?? 30);
            setEnabled(rule.enabled ?? true);
        };

        if (mode === 'edit') {
            applyRule(initialRule);
            return;
        }
        if (isDraft) {
            applyRule(draftRule);
            return;
        }
        if (isQuick) {
            setLoading(true);
            const loader = quickObjectType === 'SYNC_JOB'
                ? getSyncJobAlertRule(quickObjectId!)
                : quickObjectType === 'COLLECT_TASK'
                    ? getCollectTaskAlertRule(quickObjectId!)
                    : getDagAlertRule(quickObjectId!);
            loader
                .then(applyRule)
                .catch(e => setError(getErrorMessage(e, '加载告警配置失败')))
                .finally(() => setLoading(false));
            return;
        }
        // create：默认值
        applyRule(undefined);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [open, mode, initialRule, quickObjectType, quickObjectId, isDraft, draftRule]);

    // 对象类型变化时加载可选对象（create/edit 模式；quick 模式锁定不加载）
    useEffect(() => {
        if (!open || objectLocked) return;
        setObjectOptionsLoading(true);
        getAlertRuleObjectOptions(objectType)
            .then(list => setObjectOptions(list || []))
            .catch(() => setObjectOptions([]))
            .finally(() => setObjectOptionsLoading(false));
    }, [open, objectType, objectLocked]);

    // 用户手动切换对象类型时，重置已选对象
    const handleObjectTypeChange = (type: AlertObjectType) => {
        setObjectType(type);
        setObjectIds([]);
    };

    // DAG 选项：项目 → DAG 树形结构
    const dagTreeData = useMemo(() => {
        return objectOptions.map(project => ({
            value: project.id,
            title: project.name,
            children: project.children?.map(dag => ({
                value: dag.id,
                title: dag.name,
            })) || [],
        }));
    }, [objectOptions]);

    // 平铺选项（同步任务/采集任务）
    const flatSelectOptions = useMemo(() => {
        if (objectLocked) {
            return quickObjectId ? [{value: quickObjectId, label: quickObjectName || quickObjectId}] : [];
        }
        return objectOptions.map(o => ({value: o.id, label: o.name || String(o.id)}));
    }, [objectLocked, objectOptions, quickObjectId, quickObjectName]);

    const toggleCondition = (value: AlertTriggerType) => {
        setConditions(prev => (prev.includes(value) ? prev.filter(c => c !== value) : [...prev, value]));
    };

    const validate = (): string | null => {
        if (!name.trim()) return '请填写规则名称';
        if (conditions.length === 0) return '请至少选择一个触发条件';
        if (conditions.includes('TIMEOUT') && (!timeoutMinutes || timeoutMinutes <= 0)) {
            return '勾选「超时」时必须填写大于 0 的超时阈值';
        }
        if (userIds.length === 0) return '请至少选择一个接收用户';
        return null;
    };

    const saveByMode = async (payload: AlertRuleDTO) => {
        if (mode === 'edit' && initialRule?.id) {
            return updateAlertRule(initialRule.id, payload);
        }
        if (isQuick && quickObjectId) {
            if (quickObjectType === 'SYNC_JOB') return putSyncJobAlertRule(quickObjectId, payload);
            if (quickObjectType === 'COLLECT_TASK') return putCollectTaskAlertRule(quickObjectId, payload);
            return putDagAlertRule(quickObjectId, payload);
        }
        return createAlertRule(payload);
    };

    const handleSave = async () => {
        const invalid = validate();
        if (invalid) {
            setError(invalid);
            return;
        }
        const payload: AlertRuleDTO = {
            name: name.trim(),
            objectType,
            objectIds,
            triggerConditions: conditions,
            // 未勾选超时条件时不提交阈值，避免语义脏数据
            timeoutMinutes: conditions.includes('TIMEOUT') ? timeoutMinutes : undefined,
            enabled,
            userIds,
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
            await saveByMode(payload);
            notify.success('告警规则已保存');
            onSaved?.();
            onClose();
        } catch (e) {
            setError(getErrorMessage(e, '保存失败'));
        } finally {
            setSaving(false);
        }
    };

    const renderObjectSelector = () => {
        if (objectLocked) {
            return (
                <Select
                    value={objectIds[0] || undefined}
                    disabled
                    options={flatSelectOptions}
                    className="w-full"
                />
            );
        }
        if (objectType === 'DAG') {
            return (
                <TreeSelect
                    treeData={dagTreeData}
                    treeCheckable
                    showCheckedStrategy="SHOW_CHILD"
                    placeholder={objectOptionsLoading ? '加载中...' : '请选择 DAG（支持多选）'}
                    value={objectIds}
                    onChange={setObjectIds}
                    disabled={readOnly || objectOptionsLoading}
                    loading={objectOptionsLoading}
                    className="w-full"
                    treeDefaultExpandAll={false}
                    allowClear
                />
            );
        }
        return (
            <Select
                mode="multiple"
                showSearch
                optionFilterProp="label"
                value={objectIds}
                onChange={(value) => {
                    setObjectIds(value);
                    // 选中后自动关闭 dropdown，避免浮层遮挡下方「接收用户」「超时阈值」等字段。
                    // 表单场景下用户期望「选完即可操作下一项」而非继续连选。
                    setObjectDropdownOpen(false);
                }}
                disabled={readOnly || objectOptionsLoading}
                loading={objectOptionsLoading}
                placeholder={objectOptionsLoading ? '加载中...' : '请选择对象（支持多选）'}
                options={flatSelectOptions}
                className="w-full"
                notFoundContent={objectOptionsLoading ? <Spin size="small"/> : '无匹配对象'}
                allowClear
                open={objectDropdownOpen}
                onDropdownVisibleChange={setObjectDropdownOpen}
                listHeight={240}
                virtual={false}
            />
        );
    };

    return (
        <DsModal
            open={open}
            onClose={onClose}
            title={mode === 'edit' ? '编辑告警规则' : isQuick ? `告警配置${quickObjectName ? ` — ${quickObjectName}` : ''}` : '新增告警规则'}
            width="w-[560px] max-w-[96vw]"
            footer={
                <>
                    <DsButton variant="secondary" onClick={onClose} disabled={saving}>
                        取消
                    </DsButton>
                    <DsButton onClick={handleSave} disabled={readOnly || saving || loading}
                              title={readOnly ? '只读模式：您没有编辑权限' : undefined}>
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
                    <div>
                        <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                            规则名称 <span className="text-ds-danger">*</span>
                        </label>
                        <input
                            type="text"
                            value={name}
                            onChange={e => setName(e.target.value)}
                            disabled={readOnly}
                            placeholder="如：财务夜间同步失败告警"
                            maxLength={100}
                            className="w-full px-ds-3 py-ds-2 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors disabled:opacity-60 disabled:cursor-not-allowed"
                        />
                        <p className="mt-ds-1 text-ds-nano text-ds-text-muted">同一对象类型下名称需唯一</p>
                    </div>

                    <div>
                        <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                            对象类型 <span className="text-ds-danger">*</span>
                        </label>
                        <Select
                            value={objectType}
                            onChange={handleObjectTypeChange}
                            disabled={readOnly || typeLocked}
                            options={OBJECT_TYPE_OPTIONS}
                            className="w-full"
                        />
                    </div>

                    <div>
                        <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                            对象 <span className="text-ds-danger">*</span>
                        </label>
                        {renderObjectSelector()}
                    </div>

                    <div>
                        <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                            触发条件 <span className="text-ds-danger">*</span>
                        </label>
                        <div className="space-y-ds-1.5">
                            {TRIGGER_OPTIONS.map(o => (
                                <label key={o.value}
                                       className="flex items-center gap-ds-2 text-ds-body text-ds-text-primary">
                                    <input
                                        type="checkbox"
                                        checked={conditions.includes(o.value)}
                                        onChange={() => toggleCondition(o.value)}
                                        disabled={readOnly}
                                    />
                                    {o.label}
                                </label>
                            ))}
                        </div>
                        {objectType === 'QUALITY' && (
                            <div
                                className="mt-ds-2 bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm px-ds-3 py-ds-2 text-ds-nano text-ds-text-muted leading-relaxed">
                                <p className="font-semibold text-ds-text-secondary mb-0.5">质量任务触发语义说明</p>
                                <p>• 失败：批次中存在达到任务告警等级（严重/警告）的检查项</p>
                                <p>• 成功：批次全部检查项通过且执行成功</p>
                                <p>• 超时：任务配置了超时阈值（编辑任务 → 执行超时），批次执行超过该分钟数仍 RUNNING 即触发；未配置则永不触发</p>
                            </div>
                        )}
                    </div>

                    <div>
                        <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                            接收用户 <span className="text-ds-danger">*</span>
                        </label>
                        <UserSelect value={userIds} onChange={setUserIds} disabled={readOnly}/>
                        <p className="mt-ds-1 text-ds-nano text-ds-text-muted">仅显示已填写邮箱的平台用户</p>
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
                            disabled={readOnly || !conditions.includes('TIMEOUT')}
                            className="w-[120px] px-ds-3 py-ds-2 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors disabled:opacity-60 disabled:cursor-not-allowed"
                        />
                        <p className="mt-ds-1 text-ds-nano text-ds-text-muted">仅当勾选「超时」时生效</p>
                    </div>

                    <div className="flex items-center gap-ds-2">
                        <Switch checked={enabled} onChange={setEnabled} disabled={readOnly} size="small"/>
                        <span className="text-ds-body text-ds-text-primary">启用告警规则</span>
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
