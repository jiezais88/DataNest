// Sprint 7 F1：配置表负责人弹窗（数据资产首页 / 资产详情页共用）
// 单选 + 可清空（清空 = 清除负责人）。候选人 = 全部启用用户（/system/users/options，不要求邮箱）。
import {useEffect, useMemo, useState} from 'react';
import {Select, Spin} from 'antd';
import {assignTableOwner} from '../../../api/asset';
import {getUserOptions} from '../../../api/auth';
import DsButton from '../../../components/DsButton';
import DsModal from '../../../components/DsModal';
import {notify} from '../../../utils/notify';

interface AssignOwnerModalProps {
    open: boolean;
    tableId?: string;
    /** 当前负责人（用于回显已选标签） */
    currentOwnerId?: string;
    currentOwnerName?: string;
    tableName?: string;
    onClose: () => void;
    onSaved: () => void;
}

export default function AssignOwnerModal({
                                             open,
                                             tableId,
                                             currentOwnerId,
                                             currentOwnerName,
                                             tableName,
                                             onClose,
                                             onSaved,
                                         }: AssignOwnerModalProps) {
    const [userId, setUserId] = useState<string | undefined>(undefined);
    const [options, setOptions] = useState<{ value: string; label: string }[]>([]);
    const [loading, setLoading] = useState(false);
    const [saving, setSaving] = useState(false);

    // 打开时回显当前负责人 + 拉取候选用户
    useEffect(() => {
        if (!open) return;
        setUserId(currentOwnerId || undefined);
        setLoading(true);
        getUserOptions(undefined)
            .then(list => {
                const opts = (list ?? [])
                    .filter(u => u.id)
                    .map(u => ({value: u.id!, label: u.email ? `${u.username}（${u.email}）` : u.username}));
                // 当前负责人不在候选里（如已禁用）时补一条，保证回显
                if (currentOwnerId && !opts.some(o => o.value === currentOwnerId)) {
                    opts.unshift({value: currentOwnerId, label: currentOwnerName || currentOwnerId});
                }
                setOptions(opts);
            })
            .catch(() => setOptions([]))
            .finally(() => setLoading(false));
    }, [open, currentOwnerId, currentOwnerName]);

    const handleSearch = (kw: string) => {
        if (!kw) return;
        setLoading(true);
        getUserOptions(kw)
            .then(list => {
                setOptions(prev => {
                    const map = new Map(prev.map(o => [o.value, o]));
                    for (const u of list ?? []) {
                        if (u.id && !map.has(u.id)) {
                            map.set(u.id, {value: u.id, label: u.email ? `${u.username}（${u.email}）` : u.username});
                        }
                    }
                    return Array.from(map.values());
                });
            })
            .catch(() => {/* 静默 */})
            .finally(() => setLoading(false));
    };

    const handleSave = async () => {
        if (!tableId) return;
        setSaving(true);
        try {
            await assignTableOwner(tableId, userId ?? null);
            notify.success(userId ? '负责人已更新' : '已清除负责人');
            onSaved();
            onClose();
        } catch {
            // 错误提示由拦截器统一弹出
        } finally {
            setSaving(false);
        }
    };

    const selectValue = useMemo(() => userId, [userId]);

    return (
        <DsModal
            open={open}
            onClose={onClose}
            title={tableName ? `配置负责人 · ${tableName}` : '配置负责人'}
            footer={
                <>
                    <DsButton variant="secondary" onClick={onClose} disabled={saving}>取消</DsButton>
                    <DsButton variant="primary" onClick={handleSave} disabled={saving || loading}>
                        {saving ? '保存中...' : '保存'}
                    </DsButton>
                </>
            }
        >
            <div className="space-y-ds-2">
                <span className="text-ds-small text-ds-text-primary font-medium block">负责人</span>
                <Select
                    showSearch
                    allowClear
                    filterOption={false}
                    value={selectValue}
                    onChange={(v) => setUserId(v)}
                    onSearch={handleSearch}
                    loading={loading}
                    placeholder="搜索用户名或邮箱；留空 = 清除负责人"
                    options={options}
                    className="w-full"
                    notFoundContent={loading ? <Spin size="small"/> : '无匹配用户'}
                />
                <p className="text-ds-tiny text-ds-text-muted">候选人来自平台全部启用用户。</p>
            </div>
        </DsModal>
    );
}
