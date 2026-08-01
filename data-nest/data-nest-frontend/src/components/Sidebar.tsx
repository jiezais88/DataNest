import {useLocation, useNavigate} from 'react-router-dom';
import {useAuthStore} from '../store/useAuthStore';
import type {RoleCode} from '../constants/roles';
import {ALL_ROLES, ENGINEERING_WRITE_ROLES, GOVERNANCE_WRITE_ROLES, ROLE,} from '../constants/roles';

interface MenuItem {
    label: string;
    path: string;
    icon: string;
    roles?: RoleCode[];
}

const allMenus: { group: string; items: MenuItem[] }[] = [
    {
        group: '数据平台',
        items: [
            {label: '首页', path: '/', icon: '🏠'},
        ],
    },
    {
        group: '数据工程',
        items: [
            {
                label: '数据源管理',
                path: '/engineering/datasources',
                icon: '📦',
                roles: [ROLE.SUPER_ADMIN, ROLE.DATA_ENGINEER, ROLE.GOVERNANCE_ADMIN]
            },
            {
                label: '批量数据同步任务',
                path: '/engineering/sync-jobs',
                icon: '🔄',
                roles: ENGINEERING_WRITE_ROLES
            },
        ],
    },
    {
        group: '数据开发',
        items: [
            {
                label: '项目管理',
                path: '/engineering/dags',
                icon: '🔧',
                roles: ALL_ROLES
            },
        ],
    },
    {
        group: '数据治理',
        items: [
            {
                label: '元数据采集任务',
                path: '/governance/collect-tasks',
                icon: '⏱',
                roles: GOVERNANCE_WRITE_ROLES
            },
            {
                label: '元数据管理',
                path: '/governance/metadata',
                icon: '📋',
                roles: ALL_ROLES
            },
            {
                label: '数据标准',
                path: '/governance/data-standards',
                icon: '📏',
                roles: GOVERNANCE_WRITE_ROLES
            },
        ],
    },
    {
        group: '执行历史',
        items: [
            {
                label: '同步执行历史',
                path: '/engineering/sync-job-history',
                icon: '🔄',
                roles: ENGINEERING_WRITE_ROLES
            },
            {
                label: '采集执行历史',
                path: '/governance/collect-task-history',
                icon: '⏱',
                roles: GOVERNANCE_WRITE_ROLES
            },
            {
                label: 'DAG 执行历史',
                path: '/engineering/dag-executions',
                icon: '🔧',
                roles: ALL_ROLES
            },
        ],
    },
    {
        group: '系统管理',
        items: [
            {label: '用户管理', path: '/system/users', icon: '👥', roles: [ROLE.SUPER_ADMIN]},
        ],
    },
];

export default function Sidebar() {
    const navigate = useNavigate();
    const location = useLocation();
    const {userInfo} = useAuthStore();
    const userRoles = userInfo?.roles || [];

    const hasAccess = (item: MenuItem) => {
        if (!item.roles || item.roles.length === 0) return true;
        return item.roles.some((role) => userRoles.includes(role));
    };

    return (
        <aside
            className="fixed left-0 top-0 h-full w-[248px] bg-ds-bg-surface border-r border-ds-border-subtle flex flex-col z-ds-elevated">
            {/* Logo */}
            <div className="h-14 flex items-center gap-2 px-ds-4 border-b border-ds-border-subtle">
                <div className="w-7 h-7 bg-ds-accent rounded-ds-sm flex items-center justify-center">
                    <span className="text-white font-extrabold text-xs">DN</span>
                </div>
                <span className="text-ds-text-primary font-bold text-sm tracking-tight">DataNest</span>
            </div>

            {/* Menus */}
            <nav className="flex-1 overflow-y-auto py-ds-3 px-ds-2">
                {allMenus.map((group) => {
                    const visibleItems = group.items.filter(hasAccess);
                    if (visibleItems.length === 0) return null;

                    return (
                        <div key={group.group} className="mb-ds-4">
                            <p className="text-[11px] font-bold text-ds-text-muted uppercase tracking-[0.8px] px-3 pt-4 pb-[6px]">
                                {group.group}
                            </p>
                            {visibleItems.map((item) => {
                                const active = location.pathname === item.path;
                                return (
                                    <button
                                        key={item.path}
                                        onClick={() => navigate(item.path)}
                                        className={`w-full flex items-center gap-[10px] px-4 py-[9px] rounded-lg text-[13px] font-medium transition-colors duration-150 text-left
                      ${active
                                            ? 'bg-ds-accent-light text-ds-accent font-semibold'
                                            : 'text-ds-text-secondary hover:bg-ds-bg-hover hover:text-ds-text-primary'
                                        }`}
                                    >
                                        <span className="text-[15px] leading-none">{item.icon}</span>
                                        {item.label}
                                    </button>
                                );
                            })}
                        </div>
                    );
                })}
            </nav>
        </aside>
    );
}
