// Sprint 7 F1：单表分配分类弹窗（资产详情页用）
// 级联选择：先选数据域，再选其下主题；清空数据域 = 清除分类（后端两者皆空 = 清除）。
import {useEffect, useMemo, useState} from 'react';
import {Select} from 'antd';
import {assignTableClassification} from '@/api/asset';
import DsButton from '@/components/DsButton';
import DsModal from '@/components/DsModal';
import {notify} from '@/utils/notify';
import type {AssetClassification} from '@/types/asset';

interface AssignClassificationModalProps {
    open: boolean;
    tableId?: string;
    tableName?: string;
    currentDomain?: string;
    currentTopic?: string;
    /** 分类树（DOMAIN 根节点 + children TOPIC） */
    tree: AssetClassification[];
    onClose: () => void;
    onSaved: () => void;
}

export default function AssignClassificationModal({
                                                      open,
                                                      tableId,
                                                      tableName,
                                                      currentDomain,
                                                      currentTopic,
                                                      tree,
                                                      onClose,
                                                      onSaved,
                                                  }: AssignClassificationModalProps) {
    const [domain, setDomain] = useState<string | undefined>(undefined);
    const [topic, setTopic] = useState<string | undefined>(undefined);
    const [saving, setSaving] = useState(false);

    useEffect(() => {
        if (!open) return;
        setDomain(currentDomain || undefined);
        setTopic(currentTopic || undefined);
    }, [open, currentDomain, currentTopic]);

    const domainOptions = useMemo(
        () => tree.map(d => ({value: d.name, label: d.name})),
        [tree],
    );
    const topicOptions = useMemo(() => {
        const d = tree.find(item => item.name === domain);
        return (d?.children ?? []).map(t => ({value: t.name, label: t.name}));
    }, [tree, domain]);

    const handleSave = async () => {
        if (!tableId) return;
        setSaving(true);
        try {
            // 未选数据域 = 清除分类；选了域未选主题 = 仅分配到域
            await assignTableClassification(tableId, domain
                ? {dataDomain: domain, dataTopic: topic ?? null}
                : {dataDomain: null, dataTopic: null});
            notify.success(domain ? '分类已更新' : '已清除分类');
            onSaved();
            onClose();
        } catch {
            // 错误提示由拦截器统一弹出
        } finally {
            setSaving(false);
        }
    };

    return (
        <DsModal
            open={open}
            onClose={onClose}
            title={tableName ? `分配分类 · ${tableName}` : '分配分类'}
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
                    <span className="text-ds-small text-ds-text-primary font-medium block">数据域</span>
                    <Select
                        allowClear
                        value={domain}
                        onChange={(v) => {
                            setDomain(v);
                            // 换域/清域后主题失效，一并清空（只传主题不传域后端会报 4010）
                            setTopic(undefined);
                        }}
                        placeholder="选择数据域；留空 = 清除分类"
                        options={domainOptions}
                        className="w-full"
                    />
                </div>
                <div className="space-y-ds-2">
                    <span className="text-ds-small text-ds-text-primary font-medium block">主题</span>
                    <Select
                        allowClear
                        value={topic}
                        onChange={(v) => setTopic(v)}
                        disabled={!domain}
                        placeholder={domain ? '选择主题（可选）' : '请先选择数据域'}
                        options={topicOptions}
                        className="w-full"
                    />
                </div>
            </div>
        </DsModal>
    );
}
