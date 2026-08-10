// Sprint 4 DAG 参数定义抽屉（PRD §6.4.1 / 技术文档 §15.3）
// 交互：打开时加载服务端参数为本地草稿，行内编辑 + 添加/删除，底部「保存」统一提交 diff
// （新增 → POST / 变更 → PUT / 删除 → DELETE）。
// 删除前做前端引用校验：扫描画布节点 SQL/Python 脚本中的 ${paramName} 占位符，
// 被引用时阻止删除并列出引用节点（PRD §7 引用关系表）。
import {useEffect, useState} from 'react';
import {Spin} from 'antd';
import {HiOutlinePlus, HiOutlineTrash} from 'react-icons/hi2';
import Drawer from '@/components/Drawer';
import DsButton from '@/components/DsButton';
import DsIconButton from '@/components/DsIconButton';
import {createDagParameter, deleteDagParameter, listDagParameters, updateDagParameter,} from '@/pages/engineering/dags/api';
import type {DagParameter} from '@/pages/engineering/dags/types';
import {notify} from '@/utils/notify';
import {getErrorMessage} from '@/utils/error';
import {PARAM_TYPE_OPTIONS, validateDagParameters,} from '@/pages/engineering/dags/utils/validateDagParameters';

interface DagParameterDrawerProps {
    open: boolean;
    /** 已保存 DAG 的 id；新建未保存时为空，使用 draftParams/onDraftParamsChange 本地模式 */
    dagId?: string | number;
    /** 新建 DAG 时的本地参数草稿（受控） */
    draftParams?: DagParameter[];
    /** 本地草稿变更回调；本地模式下点击「完成」时触发 */
    onDraftParamsChange?: (params: DagParameter[]) => void;
    /** 画布节点脚本引用扫描源：节点名 + 脚本内容（SQL/Python） */
    referenceTexts?: { nodeName: string; text?: string }[];
    readOnly?: boolean;
    onClose: () => void;
}

// 草稿行：以本地 _key 稳定 key（新增行没有服务端 id）
interface DraftParam extends DagParameter {
    _key: string;
}

let keySeq = 0;
const nextKey = () => `k${++keySeq}_${Date.now()}`;

export default function DagParameterDrawer({
                                               open,
                                               dagId,
                                               draftParams = [],
                                               onDraftParamsChange,
                                               referenceTexts = [],
                                               readOnly = false,
                                               onClose,
                                           }: DagParameterDrawerProps) {
    const [loading, setLoading] = useState(false);
    const [saving, setSaving] = useState(false);
    const [draft, setDraft] = useState<DraftParam[]>([]);
    // 删除暂存：保存时才真正 DELETE
    const [removed, setRemoved] = useState<DagParameter[]>([]);
    // 服务端原始快照（变更检测用）：id → 原始记录
    const [original, setOriginal] = useState<Map<string, DagParameter>>(new Map());
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        if (!open) return;
        setError(null);
        setRemoved([]);
        if (!dagId) {
            // 本地模式：从父组件传入的草稿初始化，仍可继续编辑
            setDraft((draftParams || []).map(p => ({...p, _key: nextKey()})));
            setOriginal(new Map());
            return;
        }
        setLoading(true);
        listDagParameters(dagId)
            .then(list => {
                setDraft((list || []).map(p => ({...p, _key: nextKey()})));
                setOriginal(new Map((list || []).filter(p => p.id != null).map(p => [String(p.id), p])));
            })
            .catch(e => setError(getErrorMessage(e, '加载参数失败')))
            .finally(() => setLoading(false));
    }, [open, dagId, draftParams]);

    const addRow = () => {
        setDraft(prev => [...prev, {
            _key: nextKey(),
            paramName: '',
            paramType: 'STRING',
            defaultValue: '',
            required: true,
            description: '',
        }]);
    };

    const updateRow = (key: string, patch: Partial<DraftParam>) => {
        setDraft(prev => prev.map(p => p._key === key ? {...p, ...patch} : p));
    };

    /** 删除行：先做节点引用校验（PRD §7），通过后移入 removed 待保存时 DELETE */
    const removeRow = (row: DraftParam) => {
        const name = row.paramName.trim();
        if (name) {
            const referencing = referenceTexts
                .filter(r => r.text && r.text.includes(`\${${name}}`))
                .map(r => r.nodeName);
            if (referencing.length > 0) {
                notify.warning(`参数「${name}」正被节点 ${referencing.join('、')} 引用，请先在节点中移除 \${${name}} 引用`);
                return;
            }
        }
        setDraft(prev => prev.filter(p => p._key !== row._key));
        if (row.id != null) {
            setRemoved(prev => [...prev, row]);
        }
    };

    /** 保存/完成前校验：名称格式/重名/系统变量冲突/默认值按类型校验 */
    const validateDraft = (): string | null => validateDagParameters(draft);

    const handleSave = async () => {
        if (!dagId) return;
        const invalid = validateDraft();
        if (invalid) {
            setError(invalid);
            return;
        }
        setSaving(true);
        setError(null);
        try {
            // 1. 删除
            for (const p of removed) {
                await deleteDagParameter(dagId, p.id!);
            }
            // 2. 新增 / 变更
            for (const p of draft) {
                const payload: DagParameter = {
                    paramName: p.paramName.trim(),
                    paramType: p.paramType,
                    defaultValue: (p.defaultValue ?? '').trim(),
                    required: p.required,
                    description: p.description?.trim() || undefined,
                };
                if (p.id == null) {
                    await createDagParameter(dagId, payload);
                    continue;
                }
                const orig = original.get(String(p.id));
                const changed = !orig
                    || orig.paramName !== payload.paramName
                    || orig.paramType !== payload.paramType
                    || (orig.defaultValue ?? '') !== payload.defaultValue
                    || !!orig.required !== !!payload.required
                    || (orig.description ?? '') !== (payload.description ?? '');
                if (changed) {
                    await updateDagParameter(dagId, p.id, payload);
                }
            }
            notify.success('DAG 参数已保存');
            onClose();
        } catch (e) {
            // 业务错误已被拦截器弹出；这里补充行内提示便于定位
            setError(getErrorMessage(e, '保存失败'));
        } finally {
            setSaving(false);
        }
    };

    /** 本地模式：校验并把最终草稿回传给父组件 */
    const handleLocalConfirm = () => {
        const invalid = validateDraft();
        if (invalid) {
            setError(invalid);
            return;
        }
        const cleaned: DagParameter[] = draft.map(p => ({
            id: p.id,
            dagId: p.dagId,
            paramName: p.paramName,
            paramType: p.paramType,
            defaultValue: p.defaultValue,
            required: p.required,
            description: p.description,
            createdAt: p.createdAt,
            updatedAt: p.updatedAt,
        }));
        onDraftParamsChange?.(cleaned);
        onClose();
    };

    const footer = dagId ? (
        <>
            <DsButton variant="secondary" onClick={onClose} disabled={saving}>
                取消
            </DsButton>
            <DsButton
                data-testid="dag-param-save"
                onClick={handleSave}
                disabled={readOnly || saving || loading}
                title={readOnly ? '只读模式：您没有编辑权限' : undefined}
            >
                {saving ? '保存中...' : '保存'}
            </DsButton>
        </>
    ) : (
        <>
            <DsButton variant="secondary" onClick={onClose}>
                取消
            </DsButton>
            <DsButton onClick={handleLocalConfirm} disabled={readOnly}>
                完成
            </DsButton>
        </>
    );

    return (
        <Drawer
            title="DAG 参数"
            open={open}
            onClose={onClose}
            footer={footer}
        >
            {loading && dagId ? (
                <div className="flex items-center justify-center py-ds-6 gap-ds-2 text-ds-small text-ds-text-secondary">
                    <Spin size="small"/> 加载参数中...
                </div>
            ) : (
                <div className="space-y-ds-4">
                    <div className="flex items-center justify-between">
                        <span className="text-ds-small font-semibold text-ds-text-secondary">自定义参数</span>
                        <DsButton
                            variant="secondary"
                            onClick={addRow}
                            disabled={readOnly}
                            title={readOnly ? '只读模式：您没有编辑权限' : undefined}
                        >
                            <HiOutlinePlus size={14}/> 添加参数
                        </DsButton>
                    </div>

                    {draft.length === 0 ? (
                        <div className="text-ds-small text-ds-text-muted text-center py-ds-4">
                            暂无参数。添加后可在 SQL/Python 节点中通过 ${'{paramName}'} 引用。
                        </div>
                    ) : (
                        <div className="space-y-ds-3">
                            {draft.map(p => (
                                <div key={p._key}
                                     className="border border-ds-border-subtle rounded-ds-sm p-ds-3 space-y-ds-2">
                                    <div className="flex items-center gap-ds-2">
                                        <input
                                            value={p.paramName}
                                            onChange={e => updateRow(p._key, {paramName: e.target.value})}
                                            disabled={readOnly}
                                            placeholder="参数名称"
                                            className="flex-1 min-w-0 px-ds-2 py-ds-1.5 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-small font-mono focus:outline-none focus-visible:border-ds-accent disabled:opacity-60"
                                        />
                                        <select
                                            value={p.paramType}
                                            onChange={e => updateRow(p._key, {paramType: e.target.value})}
                                            disabled={readOnly}
                                            data-testid="param-type-select"
                                            className="w-[110px] px-ds-2 py-ds-1.5 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-small focus:outline-none focus-visible:border-ds-accent disabled:opacity-60"
                                        >
                                            {PARAM_TYPE_OPTIONS.map(t => (
                                                <option key={t} value={t}>{t}</option>
                                            ))}
                                        </select>
                                        <label
                                            className="flex items-center gap-1 text-ds-small text-ds-text-secondary shrink-0">
                                            <input
                                                type="checkbox"
                                                checked={p.required}
                                                onChange={e => updateRow(p._key, {required: e.target.checked})}
                                                disabled={readOnly}
                                            />
                                            必填
                                        </label>
                                        <DsIconButton
                                            tone="danger"
                                            onClick={() => removeRow(p)}
                                            disabled={readOnly}
                                            title="删除"
                                            aria-label="删除"
                                        >
                                            <HiOutlineTrash size={16}/>
                                        </DsIconButton>
                                    </div>
                                    <div className="flex items-center gap-ds-2">
                                        {p.paramType === 'DATE' ? (
                                            <input
                                                type="date"
                                                value={p.defaultValue || ''}
                                                onChange={e => updateRow(p._key, {defaultValue: e.target.value})}
                                                disabled={readOnly}
                                                data-testid="param-default-value"
                                                className="flex-1 min-w-0 px-ds-2 py-ds-1.5 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-small focus:outline-none focus-visible:border-ds-accent disabled:opacity-60"
                                            />
                                        ) : p.paramType === 'BOOLEAN' ? (
                                            <select
                                                value={p.defaultValue || 'true'}
                                                onChange={e => updateRow(p._key, {defaultValue: e.target.value})}
                                                disabled={readOnly}
                                                className="flex-1 min-w-0 px-ds-2 py-ds-1.5 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-small focus:outline-none focus-visible:border-ds-accent disabled:opacity-60"
                                            >
                                                <option value="true">true</option>
                                                <option value="false">false</option>
                                            </select>
                                        ) : (
                                            <input
                                                type={p.paramType === 'NUMBER' ? 'number' : 'text'}
                                                value={p.defaultValue || ''}
                                                onChange={e => updateRow(p._key, {defaultValue: e.target.value})}
                                                disabled={readOnly}
                                                placeholder="默认值"
                                                data-testid="param-default-value"
                                                className="flex-1 min-w-0 px-ds-2 py-ds-1.5 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-small focus:outline-none focus-visible:border-ds-accent disabled:opacity-60"
                                            />
                                        )}
                                        <input
                                            value={p.description || ''}
                                            onChange={e => updateRow(p._key, {description: e.target.value})}
                                            disabled={readOnly}
                                            placeholder="描述（可选）"
                                            data-testid="param-description"
                                            className="flex-1 min-w-0 px-ds-2 py-ds-1.5 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-small focus:outline-none focus-visible:border-ds-accent disabled:opacity-60"
                                        />
                                    </div>
                                </div>
                            ))}
                        </div>
                    )}

                    {error && (
                        <div
                            className="border border-ds-danger/30 bg-ds-danger/5 text-ds-danger rounded-ds-sm p-ds-3 text-ds-small">
                            {error}
                        </div>
                    )}

                    {/* 系统变量说明 */}
                    <div className="bg-ds-bg-root border border-ds-border-subtle rounded-ds-sm p-ds-3">
                        <div className="text-ds-caption text-ds-text-muted font-bold uppercase tracking-wider mb-ds-1">
                            系统变量（可直接在节点中引用）
                        </div>
                        <div className="space-y-0.5 text-ds-caption text-ds-text-secondary">
                            <div><span className="font-mono text-ds-accent">{'${biz_date}'}</span> — 业务日期，默认昨天
                            </div>
                            <div><span className="font-mono text-ds-accent">{'${current_time}'}</span> — 当前时间</div>
                            <div><span className="font-mono text-ds-accent">{'${dag_id}'}</span> — 当前 DAG ID</div>
                        </div>
                    </div>

                    {/* 引用示例 */}
                    <div className="bg-ds-bg-root border border-ds-border-subtle rounded-ds-sm p-ds-3">
                        <div className="text-ds-caption text-ds-text-muted font-bold uppercase tracking-wider mb-ds-1">
                            引用示例
                        </div>
                        <div className="space-y-0.5 text-ds-caption font-mono text-ds-text-secondary">
                            <div>SQL 节点：WHERE dt = &apos;${'{biz_date}'}&apos;</div>
                            <div>Python 节点：date = get_param(&apos;biz_date&apos;)</div>
                        </div>
                    </div>
                </div>
            )}
        </Drawer>
    );
}
