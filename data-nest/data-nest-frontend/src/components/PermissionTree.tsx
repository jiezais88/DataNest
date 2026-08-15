import {memo, useMemo} from 'react';
import {Button, Tree} from 'antd';
import type {DataNode} from 'antd/es/tree';
import type {PermissionVO} from '@/types/role';
import {PERMISSION_MODULES, isReadOnlyPermission, permissionPrefix} from '@/constants/permissions';

interface PermissionTreeProps {
    permissions: PermissionVO[];
    checkedKeys: React.Key[];
    onChange: (keys: React.Key[]) => void;
    disabled?: boolean;
}

/** 功能权限勾选树（Sprint 11 F2，角色管理页 + 权限配置页共用）：按模块分组 + 查看档/全部档快捷勾选 */
export default memo(function PermissionTree({permissions, checkedKeys, onChange, disabled = false}: PermissionTreeProps) {
    const treeData = useMemo<DataNode[]>(() => {
        const modules = new Map<string, PermissionVO[]>();
        for (const p of permissions) {
            const prefix = permissionPrefix(p.code);
            if (!modules.has(prefix)) modules.set(prefix, []);
            modules.get(prefix)!.push(p);
        }
        const ordered: DataNode[] = [];
        for (const m of PERMISSION_MODULES) {
            const items = modules.get(m.prefix);
            if (!items || items.length === 0) continue;
            modules.delete(m.prefix);
            ordered.push({
                key: `module:${m.prefix}`,
                title: m.label,
                children: items.map(p => ({key: p.code, title: p.name})),
            });
        }
        modules.forEach((items, prefix) => {
            ordered.push({
                key: `module:${prefix}`,
                title: prefix,
                children: items.map(p => ({key: p.code, title: p.name})),
            });
        });
        return ordered;
    }, [permissions]);

    const leafCount = checkedKeys.filter(k => typeof k === 'string' && !k.startsWith('module:')).length;

    const selectReadOnly = () => onChange(permissions.filter(p => isReadOnlyPermission(p.code)).map(p => p.code));
    const selectAll = () => onChange(permissions.map(p => p.code));

    return (
        <div>
            <div className="flex items-center justify-between mb-ds-1">
                <span className="text-ds-nano text-ds-text-muted">已勾选 {leafCount} 项</span>
                {!disabled && (
                    <div className="flex items-center gap-ds-1">
                        <Button size="small" type="link" onClick={selectReadOnly}>查看档</Button>
                        <Button size="small" type="link" onClick={selectAll}>全部档</Button>
                    </div>
                )}
            </div>
            <div className="border border-ds-border-subtle rounded-ds-sm max-h-[360px] overflow-y-auto p-ds-2">
                <Tree
                    checkable
                    selectable={false}
                    defaultExpandAll
                    motion={false}
                    treeData={treeData}
                    checkedKeys={checkedKeys}
                    disabled={disabled}
                    onCheck={(keys) => onChange(Array.isArray(keys) ? keys : keys.checked)}
                />
            </div>
        </div>
    );
});
