import {useEffect, useMemo, useState} from 'react';
import {Input, Modal, Radio, Tabs, Transfer, Tree} from 'antd';
import type {DataNode} from 'antd/es/tree';
import {
    createRole,
    getDataPermission,
    getPermissions,
    getRoleUsers,
    getRoles,
    saveDataPermission,
    setRoleUsers,
    updateRole,
} from '@/api/role';
import {getPermissionTree} from '@/api/metadata';
import {getUserOptions} from '@/api/auth';
import type {PermissionTreeDatasource} from '@/types/metadata';
import type {DataPermissionGrant, PermissionVO, RoleUser, RoleVO} from '@/types/role';
import {notify} from '@/utils/notify';
import DsButton from '@/components/DsButton';
import DsIconButton from '@/components/DsIconButton';
import DsModal from '@/components/DsModal';
import PermissionTree from '@/components/PermissionTree';
import {
    HiChevronRight,
    HiOutlineCloudArrowDown,
    HiOutlineFolder,
    HiOutlineKey,
    HiOutlineMagnifyingGlass,
    HiOutlinePlus,
    HiOutlineServer,
    HiOutlineShieldCheck,
    HiOutlineTableCells,
    HiOutlineTrash,
    HiOutlineUserGroup,
    HiOutlineUsers,
} from 'react-icons/hi2';

// ============ 工具：key ↔ grant 转换 ============
const dsKey = (id: string) => `ds:${id}`;
const dbKey = (id: string, db: string) => `ds:${id}|db:${db}`;
const tblKey = (id: string, db: string, table: string) => `ds:${id}|db:${db}|tbl:${table}`;

function keyToGrant(key: string): DataPermissionGrant | null {
    let m = key.match(/^ds:([^|]+)\|db:([^|]+)\|tbl:(.+)$/);
    if (m) return {datasourceId: m[1], databaseName: m[2], tableName: m[3]};
    m = key.match(/^ds:([^|]+)\|db:([^|]+)$/);
    if (m) return {datasourceId: m[1], databaseName: m[2], tableName: undefined};
    m = key.match(/^ds:([^|]+)$/);
    if (m) return {datasourceId: m[1], databaseName: undefined, tableName: undefined};
    return null;
}

function grantsToKeys(grants: DataPermissionGrant[]): React.Key[] {
    return grants.map((g) =>
        g.tableName ? tblKey(g.datasourceId, g.databaseName || '', g.tableName)
            : g.databaseName ? dbKey(g.datasourceId, g.databaseName)
                : dsKey(g.datasourceId),
    );
}

/** 压缩：数据源级 > 库级 > 表级，去冗余 */
function compressGrants(list: DataPermissionGrant[]): DataPermissionGrant[] {
    const dsSet = new Set<string>();
    const dbSet = new Set<string>();
    const out: DataPermissionGrant[] = [];
    for (const g of list) {
        if (!g.databaseName && !g.tableName && !dsSet.has(g.datasourceId)) {
            dsSet.add(g.datasourceId);
            out.push(g);
        }
    }
    for (const g of list) {
        if (g.databaseName && !g.tableName && !dsSet.has(g.datasourceId)) {
            const k = `${g.datasourceId}\u0000${g.databaseName}`;
            if (!dbSet.has(k)) {
                dbSet.add(k);
                out.push(g);
            }
        }
    }
    for (const g of list) {
        if (g.tableName && !dsSet.has(g.datasourceId) && !dbSet.has(`${g.datasourceId}\u0000${g.databaseName}`)) {
            out.push(g);
        }
    }
    return out;
}

// ============ 比较工具（脏标记 diff 用） ============
function sameKeyArr(a: React.Key[], b: React.Key[]): boolean {
    if (a.length !== b.length) return false;
    const sa = a.map(String).sort();
    const sb = b.map(String).sort();
    return sa.every((v, i) => v === sb[i]);
}

function sameGrantArr(a: DataPermissionGrant[], b: DataPermissionGrant[]): boolean {
    if (a.length !== b.length) return false;
    const k = (g: DataPermissionGrant) => `${g.datasourceId}\u0000${g.databaseName ?? ''}\u0000${g.tableName ?? ''}`;
    const sa = a.map(k).sort();
    const sb = b.map(k).sort();
    return sa.every((v, i) => v === sb[i]);
}

// ============ 分组结构 ============
interface DsGroup {
    datasourceId: string;
    dsGrant?: DataPermissionGrant;
    dbGrants: DataPermissionGrant[];
    tblGrants: DataPermissionGrant[];
}

function groupGrants(grants: DataPermissionGrant[]): DsGroup[] {
    const map = new Map<string, DsGroup>();
    for (const g of grants) {
        let e = map.get(g.datasourceId);
        if (!e) {
            e = {datasourceId: g.datasourceId, dbGrants: [], tblGrants: []};
            map.set(g.datasourceId, e);
        }
        if (!g.databaseName && !g.tableName) e.dsGrant = g;
        else if (g.databaseName && !g.tableName) e.dbGrants.push(g);
        else e.tblGrants.push(g);
    }
    return [...map.values()];
}

type TabKey = 'perm' | 'data' | 'member';

/** antd Tree 节点扩展：附加 searchText 供搜索过滤用，避免遍历 React 节点提取文本 */
interface DsTreeNode extends DataNode {
    searchText?: string;
}

/**
 * 添加授权弹窗（Sprint 11 F2）。
 * <p>
 * 独立成组件 + 搜索/勾选状态下沉，避免勾选树节点时触发父组件（含 88 节点功能权限树）整体 re-render
 * 导致的卡顿。默认折叠 + 搜索时 DFS 过滤并自动展开匹配项父链。
 */
function GrantSelectModal({open, tree, initialGrants, onClose, onOk}: {
    open: boolean;
    tree: PermissionTreeDatasource[];
    initialGrants: DataPermissionGrant[];
    onClose: () => void;
    onOk: (grants: DataPermissionGrant[]) => void;
}) {
    const [search, setSearch] = useState('');
    const [checked, setChecked] = useState<React.Key[]>([]);

    // 打开时用当前已授权初始化勾选
    useEffect(() => {
        if (open) {
            setChecked(grantsToKeys(initialGrants));
            setSearch('');
        }
    }, [open, initialGrants]);

    const treeData = useMemo<DsTreeNode[]>(() => tree.map(ds => ({
        key: dsKey(ds.datasourceId),
        searchText: ds.datasourceName || `数据源 ${ds.datasourceId}`,
        title: (
            <span className="inline-flex items-center gap-ds-1">
                <HiOutlineServer size={15} className="text-ds-accent shrink-0"/>
                <span className="font-medium">{ds.datasourceName || `数据源 ${ds.datasourceId}`}</span>
            </span>
        ),
        children: ds.databases.map(db => ({
            key: dbKey(ds.datasourceId, db.databaseName),
            searchText: db.databaseName,
            title: (
                <span className="inline-flex items-center gap-ds-1">
                    <HiOutlineFolder size={14} className="text-ds-warning shrink-0"/>
                    <span>{db.databaseName}</span>
                </span>
            ),
            children: db.tables.map(t => ({
                key: tblKey(ds.datasourceId, db.databaseName, t),
                searchText: t,
                title: t,
                isLeaf: true,
            })),
        })),
    })), [tree]);

    /** 搜索时 DFS 过滤：保留匹配项 + 其父链；空搜索返回原树（默认折叠） */
    const filteredTreeData = useMemo<DsTreeNode[]>(() => {
        const kw = search.trim().toLowerCase();
        if (!kw) return treeData;
        const isMatch = (n: DsTreeNode) => !!n.searchText && n.searchText.toLowerCase().includes(kw);
        const filter = (nodes: DsTreeNode[]): DsTreeNode[] => {
            const result: DsTreeNode[] = [];
            for (const n of nodes) {
                const children = n.children ? filter(n.children as DsTreeNode[]) : [];
                if (isMatch(n) || children.length > 0) {
                    result.push({...n, children: children.length ? children : n.children});
                }
            }
            return result;
        };
        return filter(treeData);
    }, [search, treeData]);

    return (
        <DsModal
            open={open}
            onClose={onClose}
            title="添加授权"
            width="w-[720px]"
            bordered
            bodyMaxHeight="max-h-[60vh]"
            footer={
                <>
                    <span className="text-ds-small text-ds-text-muted mr-auto">已选 {checked.length} 项</span>
                    <DsButton variant="ghost" onClick={onClose}>取消</DsButton>
                    <DsButton onClick={() => {
                        const list = checked
                            .map(k => keyToGrant(String(k)))
                            .filter((g): g is DataPermissionGrant => g !== null);
                        onOk(compressGrants(list));
                    }}>确定</DsButton>
                </>
            }
        >
            <Input
                prefix={<HiOutlineMagnifyingGlass size={15} className="text-ds-text-muted"/>}
                placeholder="搜索数据源 / 库 / 表名"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                allowClear
                className="mb-ds-3"
            />
            <Tree
                checkable
                selectable={false}
                motion={false}
                treeData={filteredTreeData}
                defaultExpandAll={!!search.trim()}
                checkedKeys={checked}
                onCheck={(keys) => setChecked(Array.isArray(keys) ? keys : keys.checked)}
            />
        </DsModal>
    );
}

/** 权限配置（Sprint 11 F2）：左角色清单 + 右三 Tab（功能权限 / 数据权限 / 成员） */
export default function DataPermissionPage() {
    // ===== 基础数据 =====
    const [roles, setRoles] = useState<RoleVO[]>([]);
    const [roleId, setRoleId] = useState<string>();
    const [keyword, setKeyword] = useState('');
    const [permissions, setPermissions] = useState<PermissionVO[]>([]);
    const [tree, setTree] = useState<PermissionTreeDatasource[]>([]);
    const [allUsers, setAllUsers] = useState<RoleUser[]>([]);
    const [activeTab, setActiveTab] = useState<TabKey>('perm');

    // ===== 功能权限 Tab（当前编辑值 + 已保存基线） =====
    const [permChecked, setPermChecked] = useState<React.Key[]>([]);
    const [savedPerms, setSavedPerms] = useState<React.Key[]>([]);

    // ===== 数据权限 Tab =====
    const [dataScope, setDataScope] = useState<'FULL' | 'WHITELIST'>('FULL');
    const [grants, setGrants] = useState<DataPermissionGrant[]>([]);
    const [savedDataScope, setSavedDataScope] = useState<'FULL' | 'WHITELIST'>('FULL');
    const [savedGrants, setSavedGrants] = useState<DataPermissionGrant[]>([]);
    const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
    const [modalOpen, setModalOpen] = useState(false);

    // ===== 成员 Tab =====
    const [memberIds, setMemberIds] = useState<string[]>([]);
    const [savedMemberIds, setSavedMemberIds] = useState<string[]>([]);

    // ===== 新建角色弹窗 =====
    const [createOpen, setCreateOpen] = useState(false);
    const [createName, setCreateName] = useState('');
    const [createCode, setCreateCode] = useState('');
    const [createDesc, setCreateDesc] = useState('');
    const [createPerms, setCreatePerms] = useState<React.Key[]>([]);
    const [createSaving, setCreateSaving] = useState(false);

    // ===== 统一保存 =====
    const [saveAllLoading, setSaveAllLoading] = useState(false);

    const selectedRole = useMemo(() => roles.find(r => r.id === roleId), [roles, roleId]);
    const selectableRoles = useMemo(() => roles.filter(r => r.code !== 'SUPER_ADMIN'), [roles]);
    const filteredRoles = useMemo(() => {
        const kw = keyword.trim().toLowerCase();
        if (!kw) return selectableRoles;
        return selectableRoles.filter(r =>
            r.name.toLowerCase().includes(kw) || r.code.toLowerCase().includes(kw));
    }, [selectableRoles, keyword]);

    const dsNameMap = useMemo(() => {
        const m = new Map<string, string>();
        for (const ds of tree) m.set(ds.datasourceId, ds.datasourceName || `数据源 ${ds.datasourceId}`);
        return m;
    }, [tree]);

    // ===== 脏标记 diff 派生：当前值 vs 已保存值，值相等即不脏（点过去又点回来会自动消除） =====
    const permDirty = useMemo(() => !sameKeyArr(permChecked, savedPerms), [permChecked, savedPerms]);
    const dpDirty = useMemo(
        () => dataScope !== savedDataScope || !sameGrantArr(grants, savedGrants),
        [dataScope, savedDataScope, grants, savedGrants],
    );
    const memberDirty = useMemo(() => !sameKeyArr(memberIds, savedMemberIds), [memberIds, savedMemberIds]);
    const hasDirty = permDirty || dpDirty || memberDirty;

    useEffect(() => {
        getRoles().then(setRoles).catch(() => undefined);
        getPermissions().then(setPermissions).catch(() => undefined);
        getPermissionTree().then(r => setTree(r.data)).catch(() => undefined);
        getUserOptions().then(setAllUsers).catch(() => undefined);
    }, []);

    // ===== 切换角色：加载三 Tab 数据 + 重置基线（依赖仅 roleId，避免保存后 setRoles 触发重复加载） =====
    useEffect(() => {
        if (!roleId) {
            setPermChecked([]);
            setSavedPerms([]);
            setDataScope('FULL');
            setSavedDataScope('FULL');
            setGrants([]);
            setSavedGrants([]);
            setMemberIds([]);
            setSavedMemberIds([]);
            setCollapsed(new Set());
            return;
        }
        const role = roles.find(r => r.id === roleId);
        if (role) {
            setPermChecked(role.permissions);
            setSavedPerms(role.permissions);
            setDataScope(role.dataScope ?? 'FULL');
            setSavedDataScope(role.dataScope ?? 'FULL');
        }
        setCollapsed(new Set());
        getDataPermission(roleId)
            .then(vos => {
                const gs = vos.map(v => ({
                    datasourceId: v.datasourceId,
                    databaseName: v.databaseName,
                    tableName: v.tableName,
                }));
                setGrants(gs);
                setSavedGrants(gs);
            })
            .catch(() => {
                setGrants([]);
                setSavedGrants([]);
            });
        getRoleUsers(roleId)
            .then(users => {
                const ids = users.map(u => u.id);
                setMemberIds(ids);
                setSavedMemberIds(ids);
            })
            .catch(() => {
                setMemberIds([]);
                setSavedMemberIds([]);
            });
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [roleId]);

    const handleSelectRole = (id: string) => {
        if (id === roleId) return;
        if (hasDirty) {
            Modal.confirm({
                title: '放弃未保存的修改？',
                content: '当前角色有未保存的修改，切换角色将丢弃这些修改。',
                okText: '放弃修改',
                cancelText: '继续编辑',
                onOk: () => setRoleId(id),
            });
        } else {
            setRoleId(id);
        }
    };

    // ===== 统一保存所有修改 =====
    const saveAll = async () => {
        if (!roleId || !hasDirty) return;

        if (permDirty) {
            const codes = permChecked.filter(k => typeof k === 'string' && !k.startsWith('module:')) as string[];
            if (codes.length === 0) {
                notify.error('请至少勾选一项功能权限');
                setActiveTab('perm');
                return;
            }
        }
        if (dpDirty && dataScope === 'WHITELIST' && grants.length === 0) {
            notify.error('已选择「仅授权数据」，请至少添加一条授权，或切换为「全部数据」');
            setActiveTab('data');
            return;
        }

        setSaveAllLoading(true);
        try {
            const done: string[] = [];
            if (permDirty) {
                const codes = permChecked.filter(k => typeof k === 'string' && !k.startsWith('module:')) as string[];
                await updateRole(roleId, {permissions: codes});
                setSavedPerms(permChecked);
                setRoles(prev => prev.map(r => r.id === roleId ? {...r, permissions: codes} : r));
                done.push('功能权限');
            }
            if (dpDirty) {
                const finalGrants = dataScope === 'FULL' ? [] : grants;
                await saveDataPermission({roleId, dataScope, grants: finalGrants});
                setSavedDataScope(dataScope);
                setSavedGrants(finalGrants);
                setGrants(finalGrants);
                setRoles(prev => prev.map(r => r.id === roleId ? {...r, dataScope} : r));
                done.push('数据权限');
            }
            if (memberDirty) {
                await setRoleUsers(roleId, memberIds);
                setSavedMemberIds(memberIds);
                done.push('成员');
            }
            notify.success(`已保存：${done.join(' / ')}`);
        } finally {
            setSaveAllLoading(false);
        }
    };

    // ===== 数据权限操作 =====
    const groups = useMemo(() => groupGrants(grants), [grants]);
    const toggleCollapse = (id: string) => {
        setCollapsed(prev => {
            const next = new Set(prev);
            if (next.has(id)) next.delete(id);
            else next.add(id);
            return next;
        });
    };
    const openAddModal = () => setModalOpen(true);
    const handleGrantOk = (newGrants: DataPermissionGrant[]) => {
        setGrants(newGrants);
        setModalOpen(false);
    };
    const removeDatasource = (datasourceId: string) => {
        setGrants(prev => prev.filter(g => g.datasourceId !== datasourceId));
    };
    const removeGrant = (g: DataPermissionGrant) => {
        setGrants(prev => prev.filter(x =>
            !(x.datasourceId === g.datasourceId && x.databaseName === g.databaseName && x.tableName === g.tableName),
        ));
    };

    // ===== 新建角色 =====
    const openCreate = () => {
        setCreateName('');
        setCreateCode('');
        setCreateDesc('');
        setCreatePerms([]);
        setCreateOpen(true);
    };
    const submitCreate = async () => {
        if (!createName.trim() || createName.trim().length < 2 || createName.trim().length > 20) {
            notify.error('角色名称需 2~20 个字符');
            return;
        }
        if (!/^[A-Za-z][A-Za-z0-9_]{1,29}$/.test(createCode.trim())) {
            notify.error('角色编码需以字母开头，仅含字母/数字/下划线（2~30 位）');
            return;
        }
        const codes = createPerms.filter(k => typeof k === 'string' && !k.startsWith('module:')) as string[];
        if (codes.length === 0) {
            notify.error('请至少勾选一项功能权限');
            return;
        }
        setCreateSaving(true);
        try {
            const created = await createRole({name: createName.trim(), code: createCode.trim(), description: createDesc.trim(), permissions: codes});
            setRoles(prev => [...prev, created]);
            setCreateOpen(false);
            setRoleId(created.id);
            setActiveTab('perm');
            notify.success('角色创建成功');
        } finally {
            setCreateSaving(false);
        }
    };

    const transferData = useMemo(() =>
        allUsers.map(u => ({key: u.id, title: u.username, description: u.email})),
    [allUsers]);

    // ===== Tab 内容 =====
    const renderPermTab = () => (
        <div className="flex flex-col gap-ds-4">
            {selectedRole?.builtin && (
                <div className="text-ds-small text-ds-text-muted">预置角色的功能权限已固化，不可修改。</div>
            )}
            <PermissionTree
                permissions={permissions}
                checkedKeys={permChecked}
                onChange={setPermChecked}
                disabled={!!selectedRole?.builtin}
            />
        </div>
    );

    const renderDataTab = () => (
        <div className="flex flex-col gap-ds-4">
            <div className="bg-ds-bg-hover rounded-ds-md p-ds-4">
                <div className="text-ds-small font-medium text-ds-text-primary mb-ds-2">默认范围</div>
                <Radio.Group value={dataScope} onChange={(e) => setDataScope(e.target.value)}>
                    <Radio value="FULL">
                        <span className="text-ds-small">全部数据（默认全量可见）</span>
                    </Radio>
                    <Radio value="WHITELIST">
                        <span className="text-ds-small">仅授权数据（白名单，其余不可见）</span>
                    </Radio>
                </Radio.Group>
                <div className="text-ds-nano text-ds-text-muted mt-ds-2">
                    内置 Doris 数仓恒为全量可见。粒度：数据源（全部库表）→ 库（全部表）→ 表（单表）。
                </div>
            </div>

            {dataScope === 'WHITELIST' ? (
                <>
                    <div className="flex items-center justify-between">
                        <span className="text-ds-body text-ds-text-primary font-medium">授权规则</span>
                        <DsButton variant="secondary" onClick={openAddModal} disabled={!roleId}>
                            <HiOutlinePlus size={16}/>
                            添加授权
                        </DsButton>
                    </div>
                    {groups.length === 0 ? (
                        <div className="text-center text-ds-small text-ds-text-muted py-ds-8">
                            尚未添加授权。点击「添加授权」选择数据源/库/表。
                        </div>
                    ) : (
                        <div className="space-y-ds-3">
                            {groups.map(grp => {
                                const name = dsNameMap.get(grp.datasourceId) || `数据源 ${grp.datasourceId}`;
                                const isCollapsed = collapsed.has(grp.datasourceId);
                                const dsLevel = !!grp.dsGrant;
                                return (
                                    <div key={grp.datasourceId}
                                         className="bg-ds-bg-surface rounded-ds-md border border-ds-border-subtle overflow-hidden">
                                        <div className="flex items-center gap-ds-2 px-ds-3 py-ds-2">
                                            <HiChevronRight
                                                size={15}
                                                className={`flex-shrink-0 text-ds-text-muted transition-transform cursor-pointer ${isCollapsed ? '' : 'rotate-90'}`}
                                                onClick={() => toggleCollapse(grp.datasourceId)}
                                            />
                                            <HiOutlineServer size={15} className="text-ds-accent flex-shrink-0"/>
                                            <span className="text-ds-small font-medium text-ds-text-primary">{name}</span>
                                            <span className="text-ds-nano text-ds-text-muted">
                                                {dsLevel ? '全量' : `${grp.dbGrants.length} 库 · ${grp.tblGrants.length} 表`}
                                            </span>
                                            <div className="flex-1"/>
                                            {dsLevel && (
                                                <DsIconButton tone="danger" onClick={() => removeDatasource(grp.datasourceId)} aria-label="移除整组授权">
                                                    <HiOutlineTrash size={14}/>
                                                </DsIconButton>
                                            )}
                                        </div>
                                        {!isCollapsed && (
                                            <div className="border-t border-ds-border-subtle">
                                                {dsLevel ? (
                                                    <div className="px-ds-3 py-ds-2 text-ds-small text-ds-text-muted">
                                                        该数据源下所有库表均已授权
                                                    </div>
                                                ) : (
                                                    <div className="divide-y divide-ds-border-subtle">
                                                        {grp.dbGrants.map(g => (
                                                            <GrantRow key={dbKey(g.datasourceId, g.databaseName || '')}
                                                                      icon={<HiOutlineFolder size={14} className="text-ds-warning"/>}
                                                                      label={g.databaseName || ''} tag="库 · 全部表"
                                                                      onRemove={() => removeGrant(g)}/>
                                                        ))}
                                                        {grp.tblGrants.map(g => (
                                                            <GrantRow key={tblKey(g.datasourceId, g.databaseName || '', g.tableName || '')}
                                                                      icon={<HiOutlineTableCells size={14} className="text-ds-text-muted"/>}
                                                                      label={`${g.databaseName}.${g.tableName}`} tag="表" indent
                                                                      onRemove={() => removeGrant(g)}/>
                                                        ))}
                                                    </div>
                                                )}
                                            </div>
                                        )}
                                    </div>
                                );
                            })}
                        </div>
                    )}
                </>
            ) : (
                <div className="text-center text-ds-small text-ds-text-muted py-ds-8">
                    已选择「全部数据」，该角色可访问全部数据源，无需配置白名单。
                </div>
            )}
        </div>
    );

    const renderMemberTab = () => (
        <div className="flex flex-col gap-ds-4">
            <div className="text-ds-small text-ds-text-muted">
                该角色当前关联 {memberIds.length} 名成员。将用户从左侧移入右侧即加入该角色。
            </div>
            <Transfer
                dataSource={transferData}
                targetKeys={memberIds}
                onChange={(keys) => setMemberIds(keys as string[])}
                render={(item) => `${item.title}${item.description ? `（${item.description}）` : ''}`}
                showSearch
                filterOption={(input, item) =>
                    item.title.toLowerCase().includes(input.toLowerCase()) ||
                    (item.description || '').toLowerCase().includes(input.toLowerCase())
                }
                titles={['可选成员', '已选成员']}
                listStyle={{width: 280, height: 340}}
            />
        </div>
    );

    return (
        <div className="flex flex-col h-full">
            {/* 顶部：标题 + 描述 + 统一保存 */}
            <div className="flex items-start justify-between mb-ds-5 flex-shrink-0 gap-ds-6">
                <div>
                    <h1 className="text-ds-display text-ds-text-primary">权限配置</h1>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1">
                        配置角色的功能权限、数据权限与成员；预置角色只读
                    </p>
                </div>
                <div className="flex items-center gap-ds-3 pt-ds-2">
                    {hasDirty && roleId && (
                        <span className="inline-flex items-center gap-ds-1 text-ds-small text-ds-warning">
                            <span className="w-1.5 h-1.5 rounded-full bg-ds-warning"/>
                            有未保存的修改
                        </span>
                    )}
                    <DsButton
                        onClick={saveAll}
                        loading={saveAllLoading}
                        disabled={!roleId || !hasDirty}
                    >
                        <HiOutlineCloudArrowDown size={16}/>
                        保存所有修改
                    </DsButton>
                </div>
            </div>

            {/* 下方：左右分栏 */}
            <div className="flex-1 min-h-0 flex gap-ds-4">
                {/* 左栏：角色清单 */}
                <aside className="w-[240px] flex-shrink-0 bg-ds-bg-surface rounded-ds-md border border-ds-border-subtle flex flex-col overflow-hidden">
                    <div className="p-ds-3 border-b border-ds-border-subtle">
                        <Input
                            prefix={<HiOutlineMagnifyingGlass size={15} className="text-ds-text-muted"/>}
                            placeholder="搜索角色"
                            value={keyword}
                            onChange={(e) => setKeyword(e.target.value)}
                            allowClear
                        />
                    </div>
                    <div className="flex-1 min-h-0 overflow-auto py-ds-2">
                        {filteredRoles.length === 0 ? (
                            <div className="text-center text-ds-small text-ds-text-muted py-ds-6">无匹配角色</div>
                        ) : (
                            filteredRoles.map(role => {
                                const active = role.id === roleId;
                                return (
                                    <button
                                        key={role.id}
                                        onClick={() => handleSelectRole(role.id)}
                                        className={`w-full text-left px-ds-3 py-ds-2 transition-colors ${
                                            active ? 'bg-ds-bg-hover border-l-2 border-ds-accent' : 'hover:bg-ds-bg-hover border-l-2 border-transparent'
                                        }`}
                                    >
                                        <div className="flex items-center gap-ds-2">
                                            <HiOutlineUserGroup size={15} className={active ? 'text-ds-accent' : 'text-ds-text-muted'}/>
                                            <span className={`text-ds-small font-medium ${active ? 'text-ds-text-primary' : 'text-ds-text-secondary'}`}>
                                                {role.name}
                                            </span>
                                            {role.builtin && (
                                                <span className="text-ds-nano text-ds-text-muted bg-ds-bg-hover rounded-ds-sm px-ds-1">预置</span>
                                            )}
                                        </div>
                                        <div className="text-ds-nano text-ds-text-muted font-mono mt-ds-1 ml-ds-6">{role.code}</div>
                                    </button>
                                );
                            })
                        )}
                    </div>
                    <div className="p-ds-3 border-t border-ds-border-subtle">
                        <DsButton className="w-full" onClick={openCreate}>
                            <HiOutlinePlus size={16}/>
                            新建角色
                        </DsButton>
                    </div>
                </aside>

                {/* 右栏：角色详情三 Tab */}
                <div className="flex-1 min-w-0 bg-ds-bg-surface rounded-ds-md border border-ds-border-subtle flex flex-col overflow-hidden">
                    {!roleId ? (
                        <div className="flex-1 flex flex-col items-center justify-center text-center py-ds-12 px-ds-6">
                            <div className="w-14 h-14 rounded-full bg-ds-bg-hover flex items-center justify-center">
                                <HiOutlineShieldCheck size={30} className="text-ds-text-muted"/>
                            </div>
                            <div className="text-ds-body text-ds-text-primary font-medium mt-ds-3">请选择左侧角色</div>
                            <div className="text-ds-small text-ds-text-muted mt-ds-1">
                                选择角色后，可配置其功能权限、数据权限与成员
                            </div>
                        </div>
                    ) : (
                        <>
                            <div className="px-ds-5 py-ds-4 border-b border-ds-border-subtle flex-shrink-0">
                                <div className="text-ds-subhead text-ds-text-primary font-semibold">
                                    {selectedRole?.name}
                                </div>
                                <div className="text-ds-nano text-ds-text-muted font-mono mt-ds-1">
                                    {selectedRole?.code} · {selectedRole?.description || '暂无描述'}
                                </div>
                            </div>
                            <div className="flex-1 min-h-0 overflow-auto px-ds-5 py-ds-4">
                                <Tabs
                                    activeKey={activeTab}
                                    onChange={(k) => setActiveTab(k as TabKey)}
                                    destroyInactiveTabPane
                                    items={[
                                        {
                                            key: 'perm',
                                            label: (
                                                <span className="inline-flex items-center gap-ds-1">
                                                    <HiOutlineKey size={14}/>功能权限
                                                    {permDirty && <span className="w-1.5 h-1.5 rounded-full bg-ds-warning ml-ds-1"/>}
                                                </span>
                                            ),
                                            children: renderPermTab(),
                                        },
                                        {
                                            key: 'data',
                                            label: (
                                                <span className="inline-flex items-center gap-ds-1">
                                                    <HiOutlineShieldCheck size={14}/>数据权限
                                                    {dpDirty && <span className="w-1.5 h-1.5 rounded-full bg-ds-warning ml-ds-1"/>}
                                                </span>
                                            ),
                                            children: renderDataTab(),
                                        },
                                        {
                                            key: 'member',
                                            label: (
                                                <span className="inline-flex items-center gap-ds-1">
                                                    <HiOutlineUsers size={14}/>成员
                                                    {memberDirty && <span className="w-1.5 h-1.5 rounded-full bg-ds-warning ml-ds-1"/>}
                                                </span>
                                            ),
                                            children: renderMemberTab(),
                                        },
                                    ]}
                                />
                            </div>
                        </>
                    )}
                </div>
            </div>

            {/* 添加授权弹窗（独立组件，勾选/搜索不触发主页面 re-render） */}
            <GrantSelectModal
                open={modalOpen}
                tree={tree}
                initialGrants={grants}
                onClose={() => setModalOpen(false)}
                onOk={handleGrantOk}
            />

            {/* 新建角色弹窗 */}
            <DsModal
                open={createOpen}
                onClose={() => {
                    if (createSaving) return;
                    setCreateOpen(false);
                }}
                title="新建角色"
                width="w-[640px]"
                footer={
                    <>
                        <DsButton variant="ghost" onClick={() => setCreateOpen(false)} disabled={createSaving}>取消</DsButton>
                        <DsButton onClick={submitCreate} loading={createSaving}>保存</DsButton>
                    </>
                }
            >
                <div className="space-y-ds-4">
                    <div>
                        <label className="block text-ds-small font-medium text-ds-text-primary mb-ds-1">
                            角色名称 <span className="text-ds-danger">*</span>
                        </label>
                        <Input value={createName} onChange={(e) => setCreateName(e.target.value)}
                               placeholder="2~20 字符" maxLength={20}/>
                    </div>
                    <div>
                        <label className="block text-ds-small font-medium text-ds-text-primary mb-ds-1">
                            角色编码 <span className="text-ds-danger">*</span>
                        </label>
                        <Input value={createCode} onChange={(e) => setCreateCode(e.target.value.toUpperCase())}
                               placeholder="英文可读，如 READONLY_AUDITOR" maxLength={30}/>
                    </div>
                    <div>
                        <label className="block text-ds-small font-medium text-ds-text-primary mb-ds-1">描述</label>
                        <Input.TextArea value={createDesc} onChange={(e) => setCreateDesc(e.target.value)}
                                        placeholder="不超过 100 字" maxLength={100} rows={2}/>
                    </div>
                    <div>
                        <label className="block text-ds-small font-medium text-ds-text-primary mb-ds-1">
                            功能权限 <span className="text-ds-danger">*</span>
                        </label>
                        <PermissionTree permissions={permissions} checkedKeys={createPerms} onChange={setCreatePerms}/>
                    </div>
                </div>
            </DsModal>
        </div>
    );
}

/** 授权明细行 */
function GrantRow({icon, label, tag, indent, onRemove}: {
    icon: React.ReactNode;
    label: string;
    tag: string;
    indent?: boolean;
    onRemove: () => void;
}) {
    return (
        <div className={`flex items-center gap-ds-2 px-ds-3 py-ds-2 ${indent ? 'pl-ds-8' : ''}`}>
            {icon}
            <span className="text-ds-small text-ds-text-primary font-mono">{label}</span>
            <span className="text-ds-nano text-ds-text-muted bg-ds-bg-hover rounded-ds-sm px-ds-1">{tag}</span>
            <div className="flex-1"/>
            <DsIconButton tone="danger" onClick={onRemove} aria-label="移除授权">
                <HiOutlineTrash size={14}/>
            </DsIconButton>
        </div>
    );
}
