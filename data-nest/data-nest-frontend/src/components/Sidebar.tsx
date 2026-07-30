import {useLocation, useNavigate} from 'react-router-dom';
import {useAuthStore} from '../store/useAuthStore';
import {ArrowLeftRight, ClipboardList, Clock, Database, History, Home, Ruler, UserCog, Workflow,} from 'lucide-react';

interface MenuItem {
    label: string;
    path: string;
    icon: React.ReactNode;
    roles?: string[];
}

const allMenus: { group: string; items: MenuItem[] }[] = [
    {
        group: '数据平台',
        items: [
            {label: '首页', path: '/', icon: <Home size={18}/>},
        ],
    },
    {
        group: '数据工程',
        items: [
            {
                label: '数据源管理',
                path: '/engineering/datasources',
                icon: <Database size={18}/>,
                roles: ['SUPER_ADMIN', 'DATA_ENGINEER', 'GOVERNANCE_ADMIN']
            },
            {
                label: '批量数据同步任务',
                path: '/engineering/sync-jobs',
                icon: <ArrowLeftRight size={18}/>,
                roles: ['SUPER_ADMIN', 'DATA_ENGINEER']
            },
            {
                label: 'DAG 编排',
                path: '/engineering/dags',
                icon: <Workflow size={18}/>,
                roles: ['SUPER_ADMIN', 'DATA_ENGINEER']
            },
        ],
    },
    {
        group: '数据治理',
        items: [
            {
                label: '元数据采集任务',
                path: '/governance/collect-tasks',
                icon: <Clock size={18}/>,
                roles: ['SUPER_ADMIN', 'GOVERNANCE_ADMIN']
            },
            {
                label: '元数据管理',
                path: '/governance/metadata',
                icon: <ClipboardList size={18}/>,
                roles: ['SUPER_ADMIN', 'GOVERNANCE_ADMIN', 'DATA_ENGINEER', 'DATA_ANALYST']
            },
            {
                label: '数据标准',
                path: '/governance/data-standards',
                icon: <Ruler size={18}/>,
                roles: ['SUPER_ADMIN', 'GOVERNANCE_ADMIN']
            },
        ],
    },
    {
        group: '执行历史',
        items: [
            {
                label: '同步执行历史',
                path: '/engineering/sync-job-history',
                icon: <History size={18}/>,
                roles: ['SUPER_ADMIN', 'DATA_ENGINEER']
            },
            {
                label: '采集执行历史',
                path: '/governance/collect-task-history',
                icon: <History size={18}/>,
                roles: ['SUPER_ADMIN', 'GOVERNANCE_ADMIN']
            },
        ],
    },
    {
        group: '系统管理',
        items: [
            {label: '用户管理', path: '/system/users', icon: <UserCog size={18}/>, roles: ['SUPER_ADMIN']},
        ],
    },
];

export default function Sidebar() {
    const navigate = useNavigate();
    const location = useLocation();
    const {userInfo} = useAuthStore();
    const roles = userInfo?.roles || [];

    const hasAccess = (item: MenuItem) => {
        if (!item.roles || item.roles.length === 0) return true;
        return item.roles.some((r) => roles.includes(r));
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
                            <p className="text-ds-nano text-ds-text-muted uppercase tracking-widest px-ds-2 mb-ds-1">
                                {group.group}
                            </p>
                            {visibleItems.map((item) => {
                                const active = location.pathname === item.path;
                                return (
                                    <button
                                        key={item.path}
                                        onClick={() => navigate(item.path)}
                                        className={`w-full flex items-center gap-ds-2 px-ds-2 py-ds-2 rounded-ds-sm text-ds-small transition-colors ds-fast text-left
                      ${active
                                            ? 'bg-ds-accent-light text-ds-accent font-semibold'
                                            : 'text-ds-text-secondary hover:bg-ds-bg-hover hover:text-ds-text-primary'
                                        }`}
                                    >
                                        {item.icon}
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
