import {useLocation, useNavigate} from 'react-router-dom';
import type {ReactNode} from 'react';
import {useAuthStore} from '../store/useAuthStore';
import type {RoleCode} from '../constants/roles';
import {ALERT_VIEW_ROLES, ALL_ROLES, ENGINEERING_WRITE_ROLES, GOVERNANCE_WRITE_ROLES, ROLE,} from '../constants/roles';
import LogoMark from './LogoMark';
import {
    HiOutlineArrowsRightLeft,
    HiOutlineBellAlert,
    HiOutlineClipboardDocumentCheck,
    HiOutlineClipboardDocumentList,
    HiOutlineClock,
    HiOutlineFolderOpen,
    HiOutlineHome,
    HiOutlineQueueList,
    HiOutlineScale,
    HiOutlineServer,
    HiOutlineTableCells,
    HiOutlineUsers,
} from 'react-icons/hi2';

interface MenuItem {
    label: string;
    path: string;
    icon: ReactNode;
    roles?: RoleCode[];
}

const allMenus: { group: string; items: MenuItem[] }[] = [
    {
        group: '数据平台',
        items: [
            {label: '首页', path: '/', icon: <HiOutlineHome size={18}/>},
        ],
    },
    {
        group: '数据工程',
        items: [
            {
                label: '数据源管理',
                path: '/engineering/datasources',
                icon: <HiOutlineServer size={18}/>,
                roles: [ROLE.SUPER_ADMIN, ROLE.DATA_ENGINEER, ROLE.GOVERNANCE_ADMIN]
            },
            {
                label: '批量数据同步任务',
                path: '/engineering/sync-jobs',
                icon: <HiOutlineArrowsRightLeft size={18}/>,
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
                icon: <HiOutlineFolderOpen size={18}/>,
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
                icon: <HiOutlineClock size={18}/>,
                roles: GOVERNANCE_WRITE_ROLES
            },
            {
                label: '元数据管理',
                path: '/governance/metadata',
                icon: <HiOutlineTableCells size={18}/>,
                roles: ALL_ROLES
            },
            {
                label: '数据标准',
                path: '/governance/data-standards',
                icon: <HiOutlineScale size={18}/>,
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
                icon: <HiOutlineClipboardDocumentList size={18}/>,
                roles: ENGINEERING_WRITE_ROLES
            },
            {
                label: '采集执行历史',
                path: '/governance/collect-task-history',
                icon: <HiOutlineClipboardDocumentCheck size={18}/>,
                roles: GOVERNANCE_WRITE_ROLES
            },
            {
                label: 'DAG 执行历史',
                path: '/engineering/dag-executions',
                icon: <HiOutlineQueueList size={18}/>,
                roles: ALL_ROLES
            },
        ],
    },
    {
        group: '系统管理',
        items: [
            {label: '用户管理', path: '/system/users', icon: <HiOutlineUsers size={18}/>, roles: [ROLE.SUPER_ADMIN]},
            {
                label: '告警中心',
                path: '/system/alert-center',
                icon: <HiOutlineBellAlert size={18}/>,
                roles: ALERT_VIEW_ROLES,
            },
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
            className="sb-sidebar fixed left-0 top-0 h-full w-[248px] bg-ds-sidebar-bg border-r border-ds-sidebar-border flex flex-col z-ds-elevated">
            {/* Logo */}
            <div className="sb-brand h-14 flex items-center gap-2 px-ds-4 border-b border-ds-sidebar-border">
                <LogoMark size={28} className="flex-shrink-0"/>
                <span className="sb-brand-text text-white font-bold text-sm tracking-tight">DataNest</span>
            </div>

            {/* Menus */}
            <nav className="sb-nav flex-1 overflow-y-auto py-ds-3 px-ds-2">
                {allMenus.map((group) => {
                    const visibleItems = group.items.filter(hasAccess);
                    if (visibleItems.length === 0) return null;

                    return (
                        <div key={group.group} className="mb-ds-4">
                            <p className="sb-group text-ds-nano font-bold text-ds-sidebar-text uppercase tracking-[0.8px] px-3 pt-4 pb-[6px]">
                                {group.group}
                            </p>
                            {visibleItems.map((item) => {
                                const active = location.pathname === item.path;
                                return (
                                    <button
                                        key={item.path}
                                        onClick={() => navigate(item.path)}
                                        className={`sb-item w-full flex items-center gap-[10px] px-4 py-[9px] rounded-lg text-ds-small font-medium transition-colors duration-150 text-left
                      ${active
                                            ? 'bg-ds-accent/25 text-white font-semibold'
                                            : 'text-ds-sidebar-text hover:bg-ds-sidebar-hover hover:text-white'
                                        }`}
                                    >
                                        <span
                                            className="sb-icon text-ds-body leading-none flex-shrink-0">{item.icon}</span>
                                        <span className="sb-item-label">{item.label}</span>
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
