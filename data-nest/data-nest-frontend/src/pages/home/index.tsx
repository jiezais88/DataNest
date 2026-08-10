import {useAuthStore} from '@/store/useAuthStore';
import {Card, List, Progress} from 'antd';
import {useNavigate} from 'react-router-dom';
import DsButton from '@/components/DsButton';
import DsStatusBadge from '@/components/DsStatusBadge';
import {executionStatusVariant} from '@/utils/status';
import {
    HiArrowTrendingDown,
    HiArrowTrendingUp,
    HiBolt,
    HiClock,
    HiCloudArrowUp,
    HiCog6Tooth,
    HiDocumentMagnifyingGlass,
    HiMiniCheckCircle,
    HiPlus,
    HiRectangleGroup,
    HiServer,
    HiShieldCheck,
    HiTableCells,
    HiUsers,
} from 'react-icons/hi2';

const MOCK_STATS = [
    {
        label: '数据源',
        value: 12,
        subtext: 'MySQL 5 · PostgreSQL 3 · Doris 1 · 其他 3',
        trend: '+2',
        trendUp: true,
        icon: <HiServer size={22}/>,
        colorClass: 'bg-blue-50 text-blue-600',
    },
    {
        label: '元数据资产',
        value: 186,
        suffix: '张表',
        subtext: '4,832 个字段 · 本周新增 14 张',
        trend: '+5.3%',
        trendUp: true,
        icon: <HiTableCells size={22}/>,
        colorClass: 'bg-violet-50 text-violet-600',
    },
    {
        label: '采集任务',
        value: 8,
        subtext: '成功 6 · 失败 1 · 运行中 1',
        trend: '-1',
        trendUp: false,
        icon: <HiCloudArrowUp size={22}/>,
        colorClass: 'bg-ds-success-light text-ds-success',
    },
    {
        label: '同步任务',
        value: 14,
        subtext: '成功 12 · 失败 2 · 运行中 0',
        trend: '+3',
        trendUp: true,
        icon: <HiBolt size={22}/>,
        colorClass: 'bg-ds-warning-light text-ds-warning',
    },
];

const MOCK_ACTIVITIES = [
    {type: 'collect', name: '自动采集-testdb', status: 'SUCCESS', time: '10 分钟前', duration: '3.2s'},
    {type: 'sync', name: 'users 全量同步', status: 'SUCCESS', time: '25 分钟前', duration: '5.4s'},
    {type: 'sync', name: 'orders 增量同步', status: 'FAILED', time: '1 小时前', duration: '12.1s'},
    {type: 'collect', name: '自动采集-ods', status: 'RUNNING', time: '2 小时前', duration: '-'},
    {type: 'collect', name: '手动采集-pg_dw', status: 'SUCCESS', time: '3 小时前', duration: '8.7s'},
];

// 图表 hex 色：antd Progress 的 strokeColor/trailColor 只接受具体颜色值，
// 无法使用 Tailwind 的 ds token class，故提取为常量集中管理（豁免 ds token 约束）。
const CHART_COLORS = {
    mysql: '#3b82f6',
    postgresql: '#6366f1',
    doris: '#f59e0b',
    other: '#94a3b8',
    trail: '#f1f3f6',
} as const;

const MOCK_SOURCE_DISTRIBUTION = [
    {name: 'MySQL', count: 5, percent: 42, color: CHART_COLORS.mysql},
    {name: 'PostgreSQL', count: 3, percent: 25, color: CHART_COLORS.postgresql},
    {name: 'Doris', count: 1, percent: 17, color: CHART_COLORS.doris},
    {name: '其他', count: 3, percent: 16, color: CHART_COLORS.other},
];

const MOCK_WEEKLY_TREND = [
    {day: '周一', success: 12, failed: 1},
    {day: '周二', success: 14, failed: 0},
    {day: '周三', success: 10, failed: 2},
    {day: '周四', success: 16, failed: 1},
    {day: '周五', success: 18, failed: 0},
    {day: '周六', success: 8, failed: 1},
    {day: '周日', success: 11, failed: 2},
];

const MOCK_QUICK_LINKS = [
    {
        label: '数据源管理',
        path: '/engineering/datasources',
        icon: <HiServer size={20}/>,
        color: 'bg-blue-50 text-blue-600'
    },
    {
        label: '采集任务',
        path: '/governance/collect-tasks',
        icon: <HiCloudArrowUp size={20}/>,
        color: 'bg-ds-success-light text-ds-success'
    },
    {
        label: '同步任务',
        path: '/engineering/sync-jobs',
        icon: <HiBolt size={20}/>,
        color: 'bg-ds-warning-light text-ds-warning'
    },
    {
        label: '元数据管理',
        path: '/governance/metadata',
        icon: <HiDocumentMagnifyingGlass size={20}/>,
        color: 'bg-violet-50 text-violet-600'
    },
    {
        label: '数据标准',
        path: '/governance/data-standards',
        icon: <HiShieldCheck size={20}/>,
        color: 'bg-ds-danger-light text-ds-danger'
    },
    {label: '用户管理', path: '/system/users', icon: <HiUsers size={20}/>, color: 'bg-slate-100 text-slate-600'},
];

const MOCK_NOTICES = [
    '建议每周 review 一次元数据变更，及时同步业务侧表结构变更。',
    'Doris 数仓已连接，可直接在元数据管理中查看表结构。',
    '批量数据同步任务支持失败重试，配置后可在执行历史中查看重试记录。',
];

/** 执行状态中文标签，variant 统一走 executionStatusVariant */
const STATUS_LABELS: Record<string, string> = {
    SUCCESS: '成功',
    RUNNING: '运行中',
    FAILED: '失败',
};

function StatCard({item}: { item: typeof MOCK_STATS[number] }) {
    return (
        <Card
            className="border-ds-border-subtle shadow-ds-xs hover:shadow-ds-md transition-shadow duration-200"
            bodyStyle={{padding: '12px'}}
        >
            <div className="flex items-start justify-between mb-ds-1">
                <div className={`w-9 h-9 rounded-ds-md flex items-center justify-center ${item.colorClass}`}>
                    {item.icon}
                </div>
                <div className="flex items-center gap-ds-1 text-ds-small font-semibold">
                    {item.trendUp ? (
                        <HiArrowTrendingUp size={14} className="text-ds-success"/>
                    ) : (
                        <HiArrowTrendingDown size={14} className="text-ds-danger"/>
                    )}
                    <span className={item.trendUp ? 'text-ds-success' : 'text-ds-danger'}>{item.trend}</span>
                </div>
            </div>
            <div className="text-ds-heading text-ds-text-primary font-bold">
                {item.value}
                {item.suffix && <span className="text-ds-body font-medium ml-ds-1">{item.suffix}</span>}
            </div>
            <div className="text-ds-small text-ds-text-secondary font-medium mt-0.5">{item.label}</div>
            <div className="text-ds-nano text-ds-text-muted mt-ds-1">{item.subtext}</div>
        </Card>
    );
}

function TrendBars() {
    const max = Math.max(...MOCK_WEEKLY_TREND.map((d) => d.success + d.failed));
    return (
        <div className="flex items-end justify-between gap-ds-2 h-14 pt-ds-2">
            {MOCK_WEEKLY_TREND.map((d) => {
                const total = d.success + d.failed;
                const height = max === 0 ? 0 : (total / max) * 100;
                const failRatio = total === 0 ? 0 : (d.failed / total) * 100;
                return (
                    <div key={d.day} className="flex-1 flex flex-col items-center gap-ds-1">
                        <div
                            className="w-full max-w-[28px] rounded-t-ds-sm overflow-hidden flex flex-col justify-end bg-ds-success-light"
                            style={{height: `${Math.max(height, 8)}%`}}
                        >
                            <div
                                className="bg-ds-danger"
                                style={{height: `${failRatio}%`}}
                            />
                        </div>
                        <span className="text-ds-nano text-ds-text-muted">{d.day}</span>
                    </div>
                );
            })}
        </div>
    );
}

export default function HomePage() {
    const {userInfo} = useAuthStore();
    const navigate = useNavigate();

    const today = new Date();
    const dateText = `${today.getFullYear()}年${today.getMonth() + 1}月${today.getDate()}日`;

    return (
        <div className="h-full flex flex-col overflow-hidden -m-ds-6 p-ds-6">
            {/* Welcome */}
            <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-ds-3 mb-ds-4 flex-shrink-0">
                <div>
                    <h1 className="text-ds-display text-ds-text-primary">
                        欢迎回来，{userInfo?.username || '管理员'}
                    </h1>
                    <p className="text-ds-body text-ds-text-secondary mt-ds-1">
                        {dateText} · 今日运行正常，近 24h 失败任务 2 个，建议优先处理。
                    </p>
                </div>
                <div className="flex items-center gap-ds-2 flex-wrap">
                    <DsButton onClick={() => navigate('/engineering/datasources')}>
                        <HiPlus size={16}/>
                        新建数据源
                    </DsButton>
                    <DsButton
                        variant="secondary"
                        onClick={() => navigate('/governance/collect-tasks')}
                    >
                        <HiCloudArrowUp size={16}/>
                        采集任务
                    </DsButton>
                    <DsButton
                        variant="secondary"
                        onClick={() => navigate('/engineering/sync-jobs')}
                    >
                        <HiBolt size={16}/>
                        同步任务
                    </DsButton>
                    <DsButton
                        variant="secondary"
                        onClick={() => navigate('/governance/metadata')}
                    >
                        <HiDocumentMagnifyingGlass size={16}/>
                        元数据
                    </DsButton>
                </div>
            </div>

            {/* Stats */}
            <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-4 gap-ds-4 mb-ds-4 flex-shrink-0">
                {MOCK_STATS.map((item, idx) => (
                    <StatCard key={idx} item={item}/>
                ))}
            </div>

            {/* Middle section */}
            <div className="grid grid-cols-1 xl:grid-cols-3 gap-ds-4 mb-ds-4 flex-shrink-0">
                {/* Recent activity */}
                <Card
                    title={
                        <div className="flex items-center gap-ds-2">
                            <HiClock size={18} className="text-ds-accent"/>
                            <span className="text-ds-subhead text-ds-text-primary">最近执行记录</span>
                        </div>
                    }
                    className="border-ds-border-subtle shadow-ds-xs xl:col-span-2"
                    headStyle={{padding: '10px 16px'}}
                >
                    <List
                        dataSource={MOCK_ACTIVITIES}
                        renderItem={(item) => (
                            <List.Item
                                className="!px-0 hover:bg-ds-bg-hover/50 rounded-ds-sm transition-colors !py-1"
                            >
                                <div className="flex items-center justify-between w-full px-ds-2">
                                    <div className="flex items-center gap-ds-2">
                                        <div
                                            className={`w-8 h-8 rounded-ds-md flex items-center justify-center ${
                                                item.type === 'collect'
                                                    ? 'bg-ds-success-light text-ds-success'
                                                    : 'bg-ds-warning-light text-ds-warning'
                                            }`}
                                        >
                                            {item.type === 'collect' ? <HiCloudArrowUp size={16}/> :
                                                <HiBolt size={16}/>}
                                        </div>
                                        <div>
                                            <div className="text-ds-body-strong text-ds-text-primary">{item.name}</div>
                                            <div className="text-ds-nano text-ds-text-muted mt-0.5">
                                                {item.type === 'collect' ? '元数据采集' : '批量数据同步'} · {item.time}
                                            </div>
                                        </div>
                                    </div>
                                    <div className="flex items-center gap-ds-4">
                                        <span
                                            className="text-ds-small text-ds-text-secondary">耗时 {item.duration}</span>
                                        <DsStatusBadge
                                            label={STATUS_LABELS[item.status] ?? item.status}
                                            variant={executionStatusVariant(item.status)}
                                        />
                                    </div>
                                </div>
                            </List.Item>
                        )}
                    />
                </Card>

                {/* Asset distribution */}
                <Card
                    title={
                        <div className="flex items-center gap-ds-2">
                            <HiRectangleGroup size={18} className="text-ds-accent"/>
                            <span className="text-ds-subhead text-ds-text-primary">资产概览</span>
                        </div>
                    }
                    className="border-ds-border-subtle shadow-ds-xs"
                    headStyle={{padding: '10px 16px'}}
                >
                    <div className="space-y-ds-2">
                        <div>
                            <div className="text-ds-small text-ds-text-secondary font-medium mb-ds-1">数据源类型分布
                            </div>
                            <div className="space-y-ds-1">
                                {MOCK_SOURCE_DISTRIBUTION.map((s) => (
                                    <div key={s.name} className="flex items-center gap-ds-3">
                                        <span className="w-14 text-ds-small text-ds-text-secondary">{s.name}</span>
                                        <Progress
                                            percent={s.percent}
                                            strokeColor={s.color}
                                            trailColor={CHART_COLORS.trail}
                                            showInfo={false}
                                            size="small"
                                            className="flex-1"
                                        />
                                        <span
                                            className="w-10 text-right text-ds-small text-ds-text-primary font-semibold">
                                            {s.count}
                                        </span>
                                    </div>
                                ))}
                            </div>
                        </div>

                        <div className="pt-ds-1 border-t border-ds-border-subtle">
                            <div className="flex items-center justify-between mb-ds-1">
                                <span
                                    className="text-ds-small text-ds-text-secondary font-medium">近 7 天任务执行趋势</span>
                                <div className="flex items-center gap-ds-3 text-ds-nano">
                                    <span className="flex items-center gap-ds-1">
                                        <span className="w-2 h-2 rounded-full bg-ds-success"/>成功
                                    </span>
                                    <span className="flex items-center gap-ds-1">
                                        <span className="w-2 h-2 rounded-full bg-ds-danger"/>失败
                                    </span>
                                </div>
                            </div>
                            <TrendBars/>
                        </div>
                    </div>
                </Card>
            </div>

            {/* Bottom section */}
            <div className="grid grid-cols-1 xl:grid-cols-3 gap-ds-3 flex-shrink-0">
                <Card
                    title={
                        <div className="flex items-center gap-ds-2">
                            <HiCog6Tooth size={18} className="text-ds-accent"/>
                            <span className="text-ds-subhead text-ds-text-primary">常用功能</span>
                        </div>
                    }
                    className="border-ds-border-subtle shadow-ds-xs xl:col-span-2"
                    headStyle={{padding: '10px 16px'}}
                >
                    <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-6 gap-ds-2">
                        {MOCK_QUICK_LINKS.map((link) => (
                            <button
                                key={link.path}
                                onClick={() => navigate(link.path)}
                                className="flex flex-col items-center gap-ds-1 p-1.5 rounded-ds-md border border-ds-border-subtle bg-ds-bg-surface hover:border-ds-accent hover:shadow-ds-sm transition-all group"
                            >
                                <div
                                    className={`w-9 h-9 rounded-ds-md flex items-center justify-center ${link.color} group-hover:scale-105 transition-transform`}>
                                    {link.icon}
                                </div>
                                <span className="text-ds-small text-ds-text-secondary font-medium">{link.label}</span>
                            </button>
                        ))}
                    </div>
                </Card>

                <Card
                    title={
                        <div className="flex items-center gap-ds-2">
                            <HiMiniCheckCircle size={18} className="text-ds-success"/>
                            <span className="text-ds-subhead text-ds-text-primary">小贴士</span>
                        </div>
                    }
                    className="border-ds-border-subtle shadow-ds-xs"
                    headStyle={{padding: '10px 16px'}}
                >
                    <ul className="space-y-1">
                        {MOCK_NOTICES.map((notice, idx) => (
                            <li key={idx}
                                className="flex items-start gap-ds-2 text-ds-nano text-ds-text-secondary leading-relaxed">
                                <span className="w-1.5 h-1.5 rounded-full bg-ds-accent mt-2 flex-shrink-0"/>
                                {notice}
                            </li>
                        ))}
                    </ul>
                </Card>
            </div>

        </div>
    );
}
