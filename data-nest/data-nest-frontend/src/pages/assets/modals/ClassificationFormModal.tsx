// Sprint 7 F1：分类新增/编辑弹窗（分类体系维护页用）
// 新增：选「作为一级数据域」或挂到某个数据域下作为主题；编辑：层级锁定，仅改名/排序。
// 改名会级联更新 metadata_table 冗余名，保存成功后父组件需刷新树和列表。
import {useEffect, useState} from 'react';
import {Select} from 'antd';
import {createClassification, updateClassification} from '@/api/asset';
import DsButton from '@/components/DsButton';
import DsModal from '@/components/DsModal';
import {notify} from '@/utils/notify';
import type {AssetClassification} from '@/types/asset';

export interface ClassificationFormState {
    /** create = 新增（parent 为空 = 数据域，否则 = 该域下主题）；edit = 编辑 node */
    mode: 'create' | 'edit';
    parent?: AssetClassification;
    node?: AssetClassification;
}

interface ClassificationFormModalProps {
    open: boolean;
    form: ClassificationFormState;
    /** 分类树（新增主题时的上级下拉 = DOMAIN 列表） */
    tree: AssetClassification[];
    onClose: () => void;
    onSaved: () => void;
}

const NO_PARENT = '';

export default function ClassificationFormModal({
                                                    open,
                                                    form,
                                                    tree,
                                                    onClose,
                                                    onSaved,
                                                }: ClassificationFormModalProps) {
    const [name, setName] = useState('');
    const [parentId, setParentId] = useState<string>(NO_PARENT);
    const [sort, setSort] = useState(0);
    const [saving, setSaving] = useState(false);

    useEffect(() => {
        if (!open) return;
        if (form.mode === 'edit' && form.node) {
            setName(form.node.name);
            setParentId(form.node.parentId ?? NO_PARENT);
            setSort(form.node.sort ?? 0);
        } else {
            setName('');
            setParentId(form.parent?.id ?? NO_PARENT);
            setSort(0);
        }
    }, [open, form]);

    const isEdit = form.mode === 'edit';
    const isTopic = isEdit ? form.node?.level === 'TOPIC' : parentId !== NO_PARENT;

    const handleSave = async () => {
        const trimmed = name.trim();
        if (!trimmed) {
            notify.warning('请输入分类名称');
            return;
        }
        setSaving(true);
        try {
            if (isEdit && form.node) {
                // 层级锁定：level/parentId 原样回传（全量语义 PUT）
                await updateClassification(form.node.id, {
                    level: form.node.level,
                    name: trimmed,
                    parentId: form.node.parentId ?? null,
                    sort,
                });
                notify.success('分类已保存（名称变更已级联同步到关联表）');
            } else {
                await createClassification({
                    level: parentId === NO_PARENT ? 'DOMAIN' : 'TOPIC',
                    name: trimmed,
                    parentId: parentId === NO_PARENT ? null : parentId,
                    sort,
                });
                notify.success('分类已创建');
            }
            onSaved();
            onClose();
        } catch {
            // 4008 同级重名 / 4010 层级非法等由拦截器统一提示
        } finally {
            setSaving(false);
        }
    };

    return (
        <DsModal
            open={open}
            onClose={onClose}
            title={isEdit ? `编辑${isTopic ? '主题' : '数据域'}` : '新增分类'}
            footer={
                <>
                    <DsButton variant="secondary" onClick={onClose} disabled={saving}>取消</DsButton>
                    <DsButton variant="primary" onClick={handleSave} disabled={saving}>
                        {saving ? '保存中...' : '保存'}
                    </DsButton>
                </>
            }
        >
            <div className="space-y-ds-4">
                <div className="space-y-ds-2">
                    <span className="text-ds-small text-ds-text-primary font-medium block">
                        名称 <span className="text-ds-danger">*</span>
                    </span>
                    <input
                        value={name}
                        onChange={(e) => setName(e.target.value)}
                        placeholder="如：交易域 / 订单"
                        className="w-full px-ds-3 py-[9px] bg-white border border-ds-border-subtle rounded-ds-sm text-sm focus:outline-none focus:border-ds-accent"
                    />
                </div>
                <div className="space-y-ds-2">
                    <span className="text-ds-small text-ds-text-primary font-medium block">上级</span>
                    {isEdit ? (
                        <input
                            value={isTopic ? `数据域：${tree.find(d => d.id === form.node?.parentId)?.name ?? form.node?.parentId}` : '— 一级数据域（层级不可修改）'}
                            disabled
                            className="w-full px-ds-3 py-[9px] bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm text-sm text-ds-text-muted"
                        />
                    ) : (
                        <Select
                            value={parentId}
                            onChange={setParentId}
                            className="w-full"
                            options={[
                                {value: NO_PARENT, label: '— 作为一级数据域 —'},
                                ...tree.map(d => ({value: d.id, label: d.name})),
                            ]}
                        />
                    )}
                </div>
                <div className="space-y-ds-2">
                    <span className="text-ds-small text-ds-text-primary font-medium block">排序</span>
                    <input
                        type="number"
                        value={sort}
                        onChange={(e) => setSort(Number(e.target.value) || 0)}
                        className="w-full px-ds-3 py-[9px] bg-white border border-ds-border-subtle rounded-ds-sm text-sm focus:outline-none focus:border-ds-accent"
                    />
                    <p className="text-ds-tiny text-ds-text-muted">同级按排序值升序展示，默认 0。</p>
                </div>
            </div>
        </DsModal>
    );
}
