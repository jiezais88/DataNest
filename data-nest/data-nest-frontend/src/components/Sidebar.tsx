import {useLocation, useNavigate} from 'react-router-dom';
import {useAuthStore} from '../store/useAuthStore';
import {HiOutlineBookOpen, HiOutlineClock, HiOutlineHome, HiOutlineServer, HiOutlineUsers,} from 'react-icons/hi2';

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
            {label: '首页', path: '/', icon: <HiOutlineHome size={18}/>},
        ],
    },
    {
        group: '数据工程',
        items: [
            {
                label: '数据源',
                path: '/engineering/datasources',
                icon: <HiOutlineServer size={18}/>,
                roles: ['SUPER_ADMIN', 'DATA_ENGINEER', 'GOVERNANCE_ADMIN']
            },
        ],
    },
    {
        group: '数据治理',
        items: [
            {
                label: '采集任务',
                path: '/governance/collect-tasks',
                icon: <HiOutlineClock size={18}/>,
                roles: ['SUPER_ADMIN', 'GOVERNANCE_ADMIN']
            },
            {
                label: '元数据管理',
                path: '/governance/metadata',
                icon: <HiOutlineBookOpen size={18}/>,
                roles: ['SUPER_ADMIN', 'GOVERNANCE_ADMIN', 'DATA_ANALYST']
            },
        ],
    },
    {
        group: '系统管理',
        items: [
            {label: '用户管理', path: '/system/users', icon: <HiOutlineUsers size={18}/>, roles: ['SUPER_ADMIN']},
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
