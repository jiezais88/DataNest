// Sprint 5：告警接收用户选择器（只展示已填写邮箱的平台用户）
// 数据源：GET /system/users/with-email（仅返回 email 非空用户，ADR-S5-003）
// 交互对齐 DagAlertConfigModal：antd Select multiple + 远程搜索，已选显示「用户名（邮箱）」标签。
import {useEffect, useMemo, useState} from 'react';
import {Select, Spin} from 'antd';
import {getUsersWithEmail} from '@/api/alert';
import type {UserOption} from '@/types/alert';

interface UserSelectProps {
    value?: string[];
    onChange?: (userIds: string[]) => void;
    disabled?: boolean;
    placeholder?: string;
}

export default function UserSelect({
                                       value = [],
                                       onChange,
                                       disabled = false,
                                       placeholder = '搜索用户名或邮箱',
                                   }: UserSelectProps) {
    const [options, setOptions] = useState<UserOption[]>([]);
    const [loading, setLoading] = useState(false);

    // 已选用户兜底 map（保证已选但不当前候选列表里的用户不丢标签）
    const selectedMap = useMemo(() => {
        const map = new Map<string, { username: string; email: string }>();
        for (const opt of options) {
            if (opt.id) map.set(opt.id, {username: opt.username, email: opt.email});
        }
        return map;
    }, [options]);

    useEffect(() => {
        let cancelled = false;
        setLoading(true);
        getUsersWithEmail(undefined)
            .then(list => {
                if (!cancelled) setOptions(list || []);
            })
            .catch(() => {/* 静默，用户可继续搜索 */
            })
            .finally(() => {
                if (!cancelled) setLoading(false);
            });
        return () => {
            cancelled = true;
        };
    }, []);

    const handleSearch = async (keyword: string) => {
        if (!keyword) return;
        setLoading(true);
        try {
            const list = await getUsersWithEmail(keyword);
            setOptions(prev => {
                const map = new Map(prev.map(o => [o.id, o]));
                for (const item of list || []) {
                    map.set(item.id, item);
                }
                return Array.from(map.values());
            });
        } catch {
            // 静默
        } finally {
            setLoading(false);
        }
    };

    const selectOptions = useMemo(() => {
        const map = new Map<string, { label: string; value: string }>();
        // 已选值优先（保留标签）
        for (const id of value) {
            const found = selectedMap.get(id);
            map.set(id, {
                value: id,
                label: found ? `${found.username}（${found.email}）` : id,
            });
        }
        for (const opt of options) {
            if (!opt.id || map.has(opt.id)) continue;
            map.set(opt.id, {value: opt.id, label: `${opt.username}（${opt.email}）`});
        }
        return Array.from(map.values());
    }, [options, value, selectedMap]);

    return (
        <Select
            mode="multiple"
            showSearch
            filterOption={false}
            value={value}
            onChange={onChange}
            onSearch={handleSearch}
            loading={loading}
            disabled={disabled}
            placeholder={placeholder}
            options={selectOptions}
            className="w-full"
            notFoundContent={loading ? <Spin size="small"/> : '无匹配用户'}
        />
    );
}
