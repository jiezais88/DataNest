// 首页 v5「运营仪表盘」（2026-08-17，推倒 v4.1 值班台重来）
// 设计文档：docs/sprint12/DataNest-首页重设计-v5.md（原型：DataNest-首页原型-v5.html）
// 定位：平台驾驶舱 —— 规模感 / 态势感 / 风险感 / 行动入口；任何状态下信息密度恒定
// 硬约束：一屏零滚动；色彩语义 indigo=正常/体量、red=失败/警报（唯一红色）、amber=警告、sky=运行中
import {useCallback, useEffect, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {Modal} from 'antd';
import {useAuthStore} from '@/store/useAuthStore';
import {
    fetchEngineeringKpi,
    fetchAlertKpi,
    fetchGovernanceKpi,
    fetchRealtimeKpi,
} from '@/api/home';
import type {
    CountField,
    HomeAlertKpi,
    HomeEngineeringKpi,
    HomeGovernanceKpi,
    HomeRealtimeKpi,
} from '@/api/home';
import {getDataSourceStats} from '@/api/datasource';
import {getSyncJobStats, executeSyncJob} from '@/api/sync';
import {getDataApiSummary, getStatsOverview} from '@/api/data-service';
import {rerunFailed} from '@/pages/engineering/dags/api';
import DsButton from '@/components/DsButton';
import {QUALITY_CHECK_LEVEL_LABEL} from '@/types/quality';
import type {QualityCheckLevel} from '@/types/quality';
import {notify} from '@/utils/notify';
import {
    HiOutlineArrowPath,
    HiOutlineBolt,
    HiOutlineCheckCircle,
    HiOutlineChevronRight,
    HiOutlineCircleStack,
    HiOutlineClock,
    HiOutlineCommandLine,
    HiOutlineCpuChip,
    HiOutlineExclamationTriangle,
    HiOutlinePlus,
    HiOutlineQueueList,
    HiOutlineServer,
    HiOutlineServerStack,
} from 'react-icons/hi2';

// =================== 类型与工具 ===================

type VerdictTone = 'ok' | 'warn' | 'down' | 'loading';

/** 等待时长：<1h 分钟级、<24h 小时级、否则天级；>4h 标黄、>24h 标红（值班升级语义） */
function waitInfo(iso?: string): {text: string; level: 'normal' | 'warn' | 'overdue'} | null {
    if (!iso) return null;
    const ms = Date.now() - new Date(iso.replace(' ', 'T')).getTime();
    if (Number.isNaN(ms) || ms < 0) return null;
    const mins = Math.floor(ms / 60000);
    const hours = Math.floor(ms / 3600000);
    const days = Math.floor(ms / 86400000);
    const text = mins < 60 ? `等待 ${Math.max(mins, 1)} 分钟` : hours < 24 ? `等待 ${hours}h` : `等待 ${days} 天`;
    const level = ms > 86400000 ? 'overdue' : ms > 4 * 3600000 ? 'warn' : 'normal';
    return {text, level};
}

/** 相对时间：x 分钟前 / x 小时前 / x 天前 */
function relTime(iso?: string): string {
    if (!iso) return '—';
    const ms = Date.now() - new Date(iso.replace(' ', 'T')).getTime();
    if (Number.isNaN(ms) || ms < 0) return '—';
    const mins = Math.floor(ms / 60000);
    const hours = Math.floor(ms / 3600000);
    const days = Math.floor(ms / 86400000);
    return mins < 60 ? `${Math.max(mins, 1)} 分钟前` : hours < 24 ? `${hours} 小时前` : `${days} 天前`;
}

/** 耗时格式化：8s / 3m41s / 250ms */
function fmtDuration(ms?: CountField): string {
    const v = Number(ms ?? 0);
    if (!v) return '—';
    if (v < 1000) return `${v}ms`;
    const s = Math.round(v / 1000);
    if (s < 60) return `${s}s`;
    return `${Math.floor(s / 60)}m${String(s % 60).padStart(2, '0')}s`;
}

/** 运行状态 → 中文 + 状态点（feed 用，米粒级语义点） */
const RUN_STATUS: Record<string, {label: string; dot: string}> = {
    SUCCESS: {label: '成功', dot: 'bg-ds-success'},
    FAILED: {label: '失败', dot: 'bg-ds-danger'},
    RUNNING: {label: '运行中', dot: 'bg-ds-accent'},
    WAITING: {label: '等待', dot: 'bg-slate-300'},
    TERMINATED: {label: '终止', dot: 'bg-slate-400'},
    SKIPPED: {label: '跳过', dot: 'bg-slate-300'},
};

// =================== 子组件 ===================

/** R2 统计卡：规模卡 + 唯一风险卡（amber 左边条高亮）；矮窗口（≤700px 高）自动切紧凑密度 */
function StatCard({icon, label, value, sub, onClick, risk}: {
    icon: React.ReactNode; label: string; value: React.ReactNode; sub: React.ReactNode;
    onClick: () => void; risk?: boolean;
}) {
    return (
        <button
            type="button"
            onClick={onClick}
            className={`relative text-left bg-ds-bg-surface rounded-ds-md border shadow-ds-xs px-ds-4 py-ds-3 flex flex-col gap-[3px] overflow-hidden transition-all hover:shadow-ds-md hover:-translate-y-px [@media(max-height:700px)]:py-[6px] ${
                risk ? 'border-ds-warning/50 bg-gradient-to-b from-ds-warning-light/60 to-ds-bg-surface' : 'border-ds-border-subtle'
            }`}
        >
            {risk && <span className="absolute left-0 top-0 bottom-0 w-[3px] bg-ds-warning"/>}
            <span className="flex items-center gap-[6px] text-ds-caption font-medium text-ds-text-muted [@media(max-height:700px)]:text-ds-nano">{icon}{label}</span>
            <span className={`text-[27px] leading-tight font-extrabold tracking-tight tabular-nums [@media(max-height:700px)]:text-[19px] ${risk ? 'text-ds-warning' : 'text-ds-text-primary'}`}>
                {value}
            </span>
            <span className="text-ds-caption font-normal text-ds-text-muted flex items-center gap-ds-1 truncate [@media(max-height:700px)]:text-ds-nano">{sub}</span>
        </button>
    );
}

/** 今日状态分布（V5-D5：成功=indigo、失败=red、运行中=sky、等待=slate；分段条 + 图例点击钻取） */
function StatusDist({eng, onDrill}: {eng: HomeEngineeringKpi | null; onDrill: () => void}) {
    const segs = [
        {label: '成功', value: Number(eng?.todaySuccess ?? 0), dot: 'bg-ds-accent', bar: 'bg-ds-accent'},
        {label: '失败', value: Number(eng?.todayFailed ?? 0), dot: 'bg-ds-danger', bar: 'bg-ds-danger'},
        {label: '运行中', value: Number(eng?.running ?? 0), dot: 'bg-sky-500', bar: 'bg-sky-500'},
        {label: '等待', value: Number(eng?.waiting ?? 0), dot: 'bg-slate-300', bar: 'bg-slate-300'},
    ];
    const denom = Math.max(segs.reduce((s, x) => s + x.value, 0), 1);
    return (
        <div className="flex items-center gap-ds-3">
            <span className="text-ds-nano text-ds-text-muted flex-shrink-0">今日</span>
            <div className="flex w-[180px] h-2 rounded-full overflow-hidden bg-ds-bg-hover flex-shrink-0">
                {segs.filter(s => s.value > 0).map(s => (
                    <div
                        key={s.label}
                        className={`h-full cursor-pointer hover:opacity-75 transition-opacity ${s.bar}`}
                        style={{width: `${(s.value / denom) * 100}%`}}
                        title={`${s.label} ${s.value} · 点击查看执行历史`}
                        onClick={onDrill}
                    />
                ))}
            </div>
            <div className="flex items-center gap-ds-2 whitespace-nowrap">
                {segs.map(s => (
                    <button
                        key={s.label}
                        type="button"
                        onClick={onDrill}
                        className="font-sans inline-flex items-center gap-ds-1 text-ds-caption font-medium text-ds-text-secondary px-[4px] py-[2px] rounded-ds-xs hover:bg-ds-bg-hover hover:text-ds-text-primary transition-colors"
                    >
                        <span className={`w-[7px] h-[7px] rounded-full flex-shrink-0 ${s.dot}`}/>
                        {s.label} <b className="font-bold tabular-nums">{eng ? s.value : '--'}</b>
                    </button>
                ))}
            </div>
        </div>
    );
}

/** 14 日运行趋势面积图（坐标系 8px 内边距：贴边点不裁切、红点与顶点重合；悬停浮层） */
function TrendChart({eng}: {eng: HomeEngineeringKpi | null}) {
    const [hover, setHover] = useState<number | null>(null);
    const points = eng?.trend ?? [];
    const W = 1200;
    const H = 180;
    const BASE = 158;
    const max = Math.max(...points.map(p => Number(p.total)), 1);
    const PAD = 8;
    const step = points.length > 1 ? (W - 2 * PAD) / (points.length - 1) : W;
    const xy = points.map((p, i) => [PAD + i * step, BASE - (Number(p.total) / max) * 130] as const);
    const line = xy.map(([x, y], i) => `${i === 0 ? 'M' : 'L'}${x.toFixed(1)},${y.toFixed(1)}`).join(' ');
    const area = xy.length ? `${line} L${xy[xy.length - 1][0]},${H} L${xy[0][0]},${H} Z` : '';
    const xLabels = points.filter((_, i) => i % 2 === 0 || i === points.length - 1);
    const clampX = (x: number) => Math.min(Math.max(x, 6), W - 6);

    if (points.length === 0) {
        return <div className="flex-1 flex items-center justify-center text-ds-small text-ds-text-muted">暂无数据</div>;
    }
    return (
        <div className="flex-1 min-h-0 flex flex-col justify-end">
            <div className="relative flex-1 min-h-0">
                <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" className="w-full h-full block">
                    <defs>
                        <linearGradient id="home-trend-area" x1="0" y1="0" x2="0" y2="1">
                            <stop offset="0%" stopColor="#4f46e5" stopOpacity="0.22"/>
                            <stop offset="100%" stopColor="#4f46e5" stopOpacity="0.02"/>
                        </linearGradient>
                    </defs>
                    <line x1="0" y1={BASE + 0.5} x2={W} y2={BASE + 0.5} stroke="#d8dee8" strokeWidth="1"/>
                    <line x1="0" y1="50" x2={W} y2="50" stroke="#eef0f7" strokeWidth="1" strokeDasharray="4 4"/>
                    <line x1="0" y1="104" x2={W} y2="104" stroke="#eef0f7" strokeWidth="1" strokeDasharray="4 4"/>
                    <path d={area} fill="url(#home-trend-area)"/>
                    <path d={line} fill="none" stroke="#4f46e5" strokeWidth="2" strokeLinejoin="round"/>
                    {hover !== null && (
                        <line
                            x1={xy[hover][0]} y1="4" x2={xy[hover][0]} y2={BASE}
                            stroke="#4f46e5" strokeWidth="1" strokeDasharray="3 3" opacity="0.4"
                        />
                    )}
                    {points.map((p, i) => (
                        <rect
                            key={`hot-${p.day}`}
                            x={Math.max(xy[i][0] - step / 2, 0)}
                            y="0"
                            width={step}
                            height={H}
                            fill="transparent"
                            onMouseEnter={() => setHover(i)}
                            onMouseLeave={() => setHover(null)}
                        />
                    ))}
                </svg>
                {/* 失败红点/悬停点用 HTML 渲染：SVG preserveAspectRatio=none 拉伸会把 circle 压成椭圆，
                    HTML 圆点按百分比定位、永远正圆（v5 评审发现变形问题） */}
                {points.map((p, i) => Number(p.failed) > 0 && (
                    <span
                        key={`fail-${p.day}`}
                        className="absolute w-[10px] h-[10px] rounded-full bg-white border-[2.5px] border-ds-danger pointer-events-none z-[1]"
                        style={{
                            left: `${(clampX(xy[i][0]) / W) * 100}%`,
                            top: `${(xy[i][1] / H) * 100}%`,
                            transform: 'translate(-50%, -50%)',
                        }}
                    />
                ))}
                {hover !== null && (
                    <span
                        className="absolute w-[9px] h-[9px] rounded-full bg-ds-accent border-2 border-white pointer-events-none z-[1]"
                        style={{
                            left: `${(clampX(xy[hover][0]) / W) * 100}%`,
                            top: `${(xy[hover][1] / H) * 100}%`,
                            transform: 'translate(-50%, -50%)',
                        }}
                    />
                )}
                {hover !== null && (
                    <div
                        className="absolute pointer-events-none z-10 bg-ds-text-primary text-white text-ds-caption font-medium rounded-ds-xs px-ds-2 py-ds-1 whitespace-nowrap shadow-ds-md"
                        style={{
                            left: `${(xy[hover][0] / W) * 100}%`,
                            top: 0,
                            transform: hover === 0
                                ? 'translateX(0)'
                                : hover === points.length - 1
                                    ? 'translateX(-100%)'
                                    : 'translateX(-50%)',
                        }}
                    >
                        {hover === points.length - 1 ? '今天' : points[hover].day}
                        ：运行 <b className="tabular-nums">{points[hover].total}</b>
                        ，失败 <b className="tabular-nums">{points[hover].failed}</b>
                    </div>
                )}
            </div>
            <div className="flex justify-between pt-ds-1 text-ds-nano font-normal text-ds-text-muted flex-shrink-0">
                {xLabels.map((p, i) => (
                    <span key={p.day}>{i === xLabels.length - 1 ? '今天' : p.day}</span>
                ))}
            </div>
        </div>
    );
}

interface QueueItem {
    key: string;
    kind: 'dag' | 'sync' | 'quality' | 'alert';
    title: string;
    reason?: string;
    since?: string;
    executionId?: string;
    refId?: string;
}

/** 待处理异常行（紧凑版，R4 三栏之一） */
function QueueRow({item, onRerun, onOpen}: {item: QueueItem; onRerun: (item: QueueItem) => void; onOpen: (item: QueueItem) => void}) {
    const badge = {
        dag: {label: 'DAG', cls: 'bg-ds-danger-light text-ds-danger'},
        sync: {label: '同步', cls: 'bg-ds-danger-light text-ds-danger'},
        quality: {label: '质量', cls: 'bg-ds-warning-light text-ds-warning'},
        alert: {label: '告警', cls: 'bg-ds-warning-light text-ds-warning'},
    }[item.kind];
    const wait = waitInfo(item.since);
    const waitCls = wait?.level === 'overdue' ? 'text-ds-danger' : wait?.level === 'warn' ? 'text-ds-warning' : 'text-ds-text-muted';
    const actionable = item.kind === 'dag' || item.kind === 'sync';
    return (
        <div className="flex items-center gap-ds-3 px-ds-1 py-[9px] border-b border-ds-border-subtle last:border-0 [@media(max-height:700px)]:py-[5px]">
            <span className={`flex-shrink-0 text-ds-badge font-semibold px-2 py-0.5 rounded-ds-xs ${badge.cls}`}>{badge.label}</span>
            <div className="flex-1 min-w-0">
                <div className="text-ds-small font-semibold text-ds-text-primary truncate">{item.title}</div>
                {item.reason && (
                    <div className="text-ds-caption font-normal text-ds-text-muted mt-[1px] truncate">{item.reason}</div>
                )}
            </div>
            {wait && <span className={`flex-shrink-0 text-ds-caption font-semibold tabular-nums ${waitCls}`}>{wait.text}</span>}
            <div className="flex-shrink-0 flex items-center gap-ds-2">
                {actionable && (
                    <DsButton variant="secondary" className="px-ds-3 py-ds-1 text-ds-caption" onClick={() => onRerun(item)}>
                        重跑
                    </DsButton>
                )}
                <DsButton variant="ghost" className="px-[6px] py-ds-1 text-ds-caption" onClick={() => onOpen(item)}>
                    {item.kind === 'quality' ? '报告' : item.kind === 'alert' ? '告警' : '日志'}
                    <HiOutlineChevronRight size={12}/>
                </DsButton>
            </div>
        </div>
    );
}

/** 系统健康行：状态点 + 人话描述（dense=紧凑行距，右栏高密度布局用） */
function HealthRow({icon, label, value, down, loading, dense}: {
    icon: React.ReactNode; label: string; value: string; down?: boolean; loading?: boolean; dense?: boolean;
}) {
    return (
        <div className={`flex items-center justify-between gap-ds-2 px-ds-2 ${dense ? 'py-[3px] [@media(max-height:700px)]:py-[1px] [@media(max-height:700px)]:text-ds-caption [@media(max-height:700px)]:leading-tight' : 'py-ds-2'} rounded-ds-sm text-ds-small text-ds-text-secondary ${down ? 'bg-ds-danger-light' : 'hover:bg-ds-bg-hover'}`}>
            <span className="flex items-center gap-ds-2">
                <span className={`w-2 h-2 rounded-full flex-shrink-0 ${loading ? 'bg-ds-text-muted' : down ? 'bg-ds-danger' : 'bg-ds-success'}`}/>
                <span className="text-ds-text-muted">{icon}</span>
                {label}
            </span>
            <span className={`font-semibold text-ds-caption tabular-nums text-right ${down ? 'text-ds-danger' : 'text-ds-text-primary'}`}>
                {loading ? '—' : value}
            </span>
        </div>
    );
}

/** 矮窗口（≤700px 高）判定：feed 等列表行数随之收紧（纯 CSS 无法改行数） */
function useShortViewport(): boolean {
    const [short, setShort] = useState(() => window.matchMedia('(max-height: 700px)').matches);
    useEffect(() => {
        const mq = window.matchMedia('(max-height: 700px)');
        const fn = (e: MediaQueryListEvent) => setShort(e.matches);
        mq.addEventListener('change', fn);
        return () => mq.removeEventListener('change', fn);
    }, []);
    return short;
}

/** 通用卡片骨架（v5 统一卡头；矮窗口紧凑卡头） */
function Card({title, sub, more, onMore, children, className = ''}: {
    title: React.ReactNode; sub?: React.ReactNode; more?: React.ReactNode; onMore?: () => void;
    children: React.ReactNode; className?: string;
}) {
    return (
        <div className={`bg-ds-bg-surface rounded-ds-md border border-ds-border-subtle shadow-ds-xs flex flex-col min-h-0 ${className}`}>
            <div className="flex items-center justify-between px-ds-4 pt-[8px] pb-[6px] border-b border-ds-border-subtle flex-shrink-0 [@media(max-height:700px)]:pt-[5px] [@media(max-height:700px)]:pb-[3px]">
                <span className="text-ds-body font-bold text-ds-text-primary flex items-baseline gap-ds-2">
                    {title}
                    {sub && <span className="text-ds-caption font-medium text-ds-text-muted">{sub}</span>}
                </span>
                {more && (
                    <DsButton variant="ghost" className="px-[6px] py-[2px] text-ds-caption" onClick={onMore}>
                        {more}
                    </DsButton>
                )}
            </div>
            <div className="flex-1 min-h-0 px-ds-4 py-ds-2 flex flex-col overflow-y-auto [@media(max-height:700px)]:py-ds-1">{children}</div>
        </div>
    );
}

// =================== 主页面 ===================

const QUICK_ACTIONS = [
    {label: '同步任务', path: '/engineering/sync-jobs', icon: <HiOutlinePlus size={14}/>},
    {label: '新建 DAG', path: '/engineering/dags', icon: <HiOutlinePlus size={14}/>},
    {label: 'SQL 查询', path: '/data-service/sql-console', icon: <HiOutlineCommandLine size={14}/>},
    {label: '数据源', path: '/engineering/datasources', icon: <HiOutlinePlus size={14}/>},
];

export default function HomePage() {
    const {userInfo} = useAuthStore();
    const navigate = useNavigate();
    const shortVp = useShortViewport();

    const [eng, setEng] = useState<HomeEngineeringKpi | null>(null);
    const [alert, setAlert] = useState<HomeAlertKpi | null>(null);
    const [gov, setGov] = useState<HomeGovernanceKpi | null>(null);
    const [rt, setRt] = useState<HomeRealtimeKpi | null>(null);
    const [dsStats, setDsStats] = useState<{normal: number; error: number} | null>(null);
    const [syncStats, setSyncStats] = useState<{running: number} | null>(null);
    const [apiTotal, setApiTotal] = useState<number | null>(null);
    const [apiCalls24h, setApiCalls24h] = useState<number | null>(null);
    const [engFailed, setEngFailed] = useState(false);
    const [svcFailedCount, setSvcFailedCount] = useState(0);
    const [lastUpdate, setLastUpdate] = useState<Date | null>(null);
    const [loading, setLoading] = useState(false);

    const load = useCallback(async () => {
        setLoading(true);
        const results = await Promise.allSettled([
            fetchEngineeringKpi().then(r => { setEng(r); setEngFailed(false); }),
            fetchAlertKpi().then(setAlert),
            fetchGovernanceKpi().then(setGov),
            fetchRealtimeKpi().then(setRt),
            getDataSourceStats().then(r => setDsStats({normal: r.data.normal, error: r.data.error})),
            getSyncJobStats().then(r => setSyncStats({running: r.data.running})),
            getDataApiSummary().then(r => setApiTotal(
                Number(r.data.publishedCount) + Number(r.data.createdCount) + Number(r.data.disabledCount))),
            getStatsOverview('24h').then(r => setApiCalls24h(Number(r.data.totalCalls))),
        ]);
        if (results[0].status === 'rejected') setEngFailed(true);
        setSvcFailedCount(results.filter(r => r.status === 'rejected').length);
        setLastUpdate(new Date());
        setLoading(false);
    }, []);

    useEffect(() => {
        load();
        const t = setInterval(load, 60_000);
        return () => clearInterval(t);
    }, [load]);

    // ---- 态势判定（沿用 v4.1 口径：Doris/Flink DOWN → 故障；失败待处理/质量异常/数据源异常 → 需关注） ----
    const pendingFailed = Number(eng?.pendingFailed ?? 0);
    const dsError = Number(dsStats?.error ?? 0);
    const qualityKeys = new Set((gov?.qualityIssues ?? []).map(i => `${i.ruleName}|${i.tableName}`));
    const qualityCount = qualityKeys.size;
    const dorisDown = gov?.doris?.status === 'DOWN';
    const flinkDown = rt?.flink?.status === 'DOWN';
    const downCount = (dorisDown ? 1 : 0) + (flinkDown ? 1 : 0);
    const warnCount = pendingFailed + qualityCount + dsError;
    const loaded = !!(eng || gov || rt || dsStats);
    const verdictTone: VerdictTone = !loaded ? 'loading' : downCount > 0 ? 'down' : warnCount > 0 ? 'warn' : 'ok';
    const verdictText = {
        loading: '状态检查中', ok: '运行正常',
        warn: `${warnCount} 项需关注`, down: `${downCount} 项故障`,
    }[verdictTone];
    const verdictPill = {
        loading: 'bg-ds-bg-hover text-ds-text-muted',
        ok: 'bg-ds-success-light text-ds-success',
        warn: 'bg-ds-warning-light text-ds-warning',
        down: 'bg-ds-danger-light text-ds-danger',
    }[verdictTone];

    const hour = new Date().getHours();
    const greeting = hour < 6 ? '夜深了' : hour < 12 ? '早上好' : hour < 18 ? '下午好' : '晚上好';

    // 空平台判定：全新用户三步引导
    const isFreshPlatform = !!eng && Number(eng.todayTotal) === 0 && pendingFailed === 0
        && (eng.trend ?? []).every(p => Number(p.total) === 0)
        && (gov?.qualityIssues ?? []).length === 0;

    // ---- 待处理异常队列（紧凑，R4 左栏，最多 3 行） ----
    const issues: QueueItem[] = [];
    (eng?.failedItems ?? []).forEach(item => {
        issues.push({
            key: `${item.type}-${item.executionId}`,
            kind: item.type,
            title: item.name,
            reason: item.reason ? `失败原因：${item.reason}` : undefined,
            since: item.failedAt,
            executionId: item.executionId,
            refId: item.refId,
        });
    });
    const seenQuality = new Set<string>();
    (gov?.qualityIssues ?? []).forEach(item => {
        const dedupKey = `${item.ruleName}|${item.tableName}`;
        if (seenQuality.has(dedupKey)) return;
        seenQuality.add(dedupKey);
        const levelLabel = QUALITY_CHECK_LEVEL_LABEL[item.resultLevel as QualityCheckLevel] ?? item.resultLevel;
        issues.push({
            key: `quality-${item.detailId}`,
            kind: 'quality',
            title: `${item.ruleName}（${item.tableName}）`,
            reason: `结果：${levelLabel}`,
            since: item.checkedAt,
        });
    });
    if (issues.length === 0 && alert && Number(alert.total) > 0) {
        issues.push({key: 'alert-summary', kind: 'alert', title: alert.summary, reason: '近 24 小时告警汇总'});
    }
    const visibleIssues = issues.slice(0, 3);
    // 风险卡副行：最早等待时长
    const oldestWait = issues.map(i => i.since).filter(Boolean).sort()[0];
    const oldestWaitText = oldestWait ? waitInfo(oldestWait)?.text.replace('等待 ', '') : null;

    // ---- 行内操作 ----
    const handleRerun = useCallback((item: QueueItem) => {
        if (!item.refId || !item.executionId) {
            notify.warning('记录缺少关联信息，请前往执行历史处理');
            return;
        }
        const isDag = item.kind === 'dag';
        Modal.confirm({
            centered: true,
            wrapClassName: 'prototype-modal',
            title: isDag ? '重跑失败节点' : '重新执行同步任务',
            content: isDag
                ? `将重新执行 DAG「${item.title}」的失败节点，已成功节点结果复用。`
                : `将重新执行同步任务「${item.title}」。`,
            okText: '确认重跑',
            cancelText: '取消',
            onOk: async () => {
                try {
                    if (isDag) {
                        await rerunFailed(item.refId!, item.executionId!);
                    } else {
                        await executeSyncJob(item.refId!);
                    }
                    notify.success('已触发重跑，5s 后刷新');
                    setTimeout(load, 5000);
                } catch {
                    // 错误提示由 request 拦截器统一弹出
                }
            },
        });
    }, [load]);

    const handleOpen = useCallback((item: QueueItem) => {
        switch (item.kind) {
            case 'dag':
                navigate(item.refId ? `/engineering/dag-executions?dagId=${item.refId}` : '/engineering/dag-executions');
                break;
            case 'sync':
                navigate(item.refId ? `/engineering/sync-job-history?syncJobId=${item.refId}` : '/engineering/sync-job-history');
                break;
            case 'quality':
                navigate('/governance/quality-report');
                break;
            case 'alert':
                navigate('/system/alert-center');
                break;
        }
    }, [navigate]);

    return (
        <div className="h-full flex flex-col gap-ds-3 min-h-0 [@media(max-height:700px)]:gap-[6px]">

            {/* ── R1 问候 + 状态 ── */}
            <div className="flex items-center justify-between flex-shrink-0 px-[4px]">
                <div className="flex items-baseline gap-ds-3">
                    <span className="text-ds-heading font-bold tracking-tight [@media(max-height:700px)]:text-ds-body">
                        {greeting}，{userInfo?.username || '管理员'}
                    </span>
                    <span className="text-ds-caption font-normal text-ds-text-muted">
                        {new Date().toLocaleDateString('zh-CN', {year: 'numeric', month: 'long', day: 'numeric', weekday: 'short'})}
                    </span>
                </div>
                <div className="flex items-center gap-ds-3">
                    <span className={`inline-flex items-center gap-[6px] text-ds-caption font-semibold px-[10px] py-[4px] rounded-full ${verdictPill}`}>
                        <span className="w-[7px] h-[7px] rounded-full bg-current"/>
                        {verdictText}
                    </span>
                    <button
                        type="button"
                        onClick={() => navigate('/system/alert-center')}
                        className="font-sans inline-flex items-center gap-[6px] text-ds-caption font-medium text-ds-text-secondary bg-ds-bg-surface border border-ds-border-subtle rounded-full px-[10px] py-[4px] hover:bg-ds-bg-hover transition-colors"
                        title="前往告警中心"
                    >
                        24h 告警 <b className={`tabular-nums ${Number(alert?.total ?? 0) > 0 ? 'text-ds-warning' : 'text-ds-text-primary'}`}>{alert ? Number(alert.total) : '--'}</b>
                    </button>
                    {lastUpdate && (
                        <span className="text-ds-nano text-ds-text-muted tabular-nums">
                            {lastUpdate.toLocaleTimeString('zh-CN', {hour: '2-digit', minute: '2-digit'})} 更新
                        </span>
                    )}
                    <DsButton variant="secondary" onClick={load} disabled={loading} className="gap-[6px] px-ds-3 py-ds-1 text-ds-caption">
                        <HiOutlineArrowPath size={13} className={loading ? 'animate-spin' : ''}/>
                        刷新
                    </DsButton>
                </div>
            </div>

            {/* ── R2 统计卡 ×5（规模感 + 唯一风险卡） ── */}
            <div className="grid grid-cols-5 gap-ds-3 flex-shrink-0">
                <StatCard
                    icon={<HiOutlineCircleStack size={14}/>}
                    label="数据源"
                    value={eng ? Number(eng.datasourceTotal ?? 0) : '—'}
                    sub={<><span className="text-ds-success font-semibold">●</span>{eng ? `${Number(eng.datasourceNormal ?? 0)} 正常 · ${Number(eng.datasourceFailed ?? 0)} 连接失败` : '—'}</>}
                    onClick={() => navigate('/engineering/datasources')}
                />
                <StatCard
                    icon={<HiOutlineQueueList size={14}/>}
                    label="数据表"
                    value={gov?.assets ? Number(gov.assets.tableTotal ?? 0) : '—'}
                    sub={<><span className="text-ds-success font-semibold">▲</span>{gov?.assets ? `近 7 天新增 ${Number(gov.assets.tableNew7d ?? 0)}` : '—'}</>}
                    onClick={() => navigate('/asset-catalog')}
                />
                <StatCard
                    icon={<HiOutlineClock size={14}/>}
                    label="调度任务"
                    value={eng ? Number(eng.taskTotal ?? 0) : '—'}
                    sub={<>今日已运行 <b className="text-ds-text-primary tabular-nums">{eng ? Number(eng.todayTotal) : '—'}</b> 次</>}
                    onClick={() => navigate('/engineering/dags')}
                />
                <StatCard
                    icon={<HiOutlineBolt size={14}/>}
                    label="数据 API"
                    value={apiTotal ?? '—'}
                    sub={`近 24h 调用 ${apiCalls24h ?? '—'} 次`}
                    onClick={() => navigate('/data-service/api-manage')}
                />
                <StatCard
                    icon={<HiOutlineExclamationTriangle size={14}/>}
                    label="待处理异常"
                    value={issues.length}
                    risk={issues.length > 0}
                    sub={issues.length > 0
                        ? <><span className="text-ds-warning font-semibold">●</span>{oldestWaitText ? `最早已等待 ${oldestWaitText} · ` : ''}去处理 ›</>
                        : <><span className="text-ds-success font-semibold">●</span>近 24 小时无异常</>}
                    onClick={() => navigate('/engineering/dag-executions')}
                />
            </div>

            {isFreshPlatform ? (
                /* 空平台三步引导（替代 R3/R4） */
                <div className="flex-1 min-h-0 bg-ds-bg-surface rounded-ds-md border border-ds-border-subtle shadow-ds-xs flex flex-col items-center justify-center gap-ds-4">
                    <div className="text-ds-heading font-bold text-ds-text-primary">欢迎使用 DataNest</div>
                    <div className="text-ds-small text-ds-text-muted">三步跑通你的第一条数据链路</div>
                    <div className="flex items-center gap-ds-2">
                        {[
                            {step: '① 创建数据源', path: '/engineering/datasources'},
                            {step: '② 创建同步任务', path: '/engineering/sync-jobs'},
                            {step: '③ 查看运行', path: '/engineering/dag-executions'},
                        ].map(s => (
                            <button
                                key={s.step}
                                type="button"
                                onClick={() => navigate(s.path)}
                                className="font-sans text-ds-small font-medium text-ds-accent bg-ds-accent-light px-ds-3 py-[6px] rounded-ds-sm hover:bg-ds-accent hover:text-white transition-colors"
                            >
                                {s.step}
                            </button>
                        ))}
                    </div>
                </div>
            ) : (
                <>
                    {/* ── R3 运行态势（主视觉）+ 右栏 ── */}
                    <div className="flex-[1.15] min-h-0 grid grid-cols-1 xl:grid-cols-[1fr_340px] gap-ds-3 [@media(max-height:700px)]:flex-[1.4] [@media(max-height:700px)]:gap-ds-2">
                        <Card
                            title="运行态势"
                            sub={<>近 14 日 · 近 7 天成功率 <b className="text-ds-text-primary tabular-nums">{eng?.successRate7d != null ? `${eng.successRate7d}%` : '—'}</b></>}
                            more={<StatusDist eng={eng} onDrill={() => navigate('/engineering/dag-executions')}/>}
                        >
                            {engFailed
                                ? (
                                    <div className="flex-1 flex items-center justify-center gap-ds-2 text-ds-small text-ds-text-muted">
                                        加载失败
                                        <DsButton variant="ghost" className="px-[6px] py-[2px] text-ds-caption" onClick={load}>重试</DsButton>
                                    </div>
                                )
                                : <TrendChart eng={eng}/>}
                        </Card>

                        <div className="flex flex-col gap-ds-3 min-h-0">
                            <Card title="系统健康" className="flex-[1.35] [@media(max-height:700px)]:flex-[1.9]">
                                <div className="flex flex-col justify-around flex-1 -mx-ds-2 px-ds-1">
                                    <HealthRow dense
                                        icon={<HiOutlineServer size={15}/>}
                                        label="数据源"
                                        loading={!dsStats}
                                        down={!!dsStats && dsStats.error > 0}
                                        value={dsStats ? (dsStats.error > 0 ? `${dsStats.normal} 正常 · ${dsStats.error} 连接失败` : `${dsStats.normal} 正常`) : ''}
                                    />
                                    <HealthRow dense
                                        icon={<HiOutlineBolt size={15}/>}
                                        label="集成任务"
                                        loading={!syncStats}
                                        value={syncStats ? `${syncStats.running} 个运行中` : ''}
                                    />
                                    <HealthRow dense
                                        icon={<HiOutlineCpuChip size={15}/>}
                                        label="Flink CDC"
                                        loading={!rt}
                                        down={rt?.flink?.status === 'DOWN'}
                                        value={rt?.flink ? (rt.flink.status === 'UP' ? `正常 · ${Number(rt.cdcSyncedTables ?? 0)} 张表同步中` : '不可用') : ''}
                                    />
                                    <HealthRow dense
                                        icon={<HiOutlineCircleStack size={15}/>}
                                        label="Doris"
                                        loading={!gov}
                                        down={gov?.doris?.status === 'DOWN'}
                                        value={gov?.doris ? (gov.doris.status === 'UP' ? `正常 · ${gov.doris.latencyMs}ms` : '不可用') : ''}
                                    />
                                    <HealthRow dense
                                        icon={<HiOutlineServerStack size={15}/>}
                                        label="平台服务"
                                        loading={!loaded}
                                        down={svcFailedCount > 0}
                                        value={svcFailedCount > 0 ? `${svcFailedCount} 项数据异常` : '全部正常'}
                                    />
                                </div>
                            </Card>
                            <Card title="快捷操作" className="flex-1">
                                <div className="grid grid-cols-2 gap-ds-2 flex-1 content-center [@media(max-height:700px)]:grid-cols-4 [@media(max-height:700px)]:gap-[4px]">
                                    {QUICK_ACTIONS.map(a => (
                                        <button
                                            key={a.path + a.label}
                                            type="button"
                                            onClick={() => navigate(a.path)}
                                            className="font-sans flex items-center justify-center gap-[6px] px-ds-3 py-[6px] rounded-ds-sm border border-ds-border-subtle bg-ds-bg-surface text-ds-small font-medium text-ds-text-secondary hover:border-ds-accent hover:text-ds-accent hover:bg-ds-accent-light transition-colors [@media(max-height:700px)]:py-[1px] [@media(max-height:700px)]:px-ds-1 [@media(max-height:700px)]:text-ds-nano [@media(max-height:700px)]:leading-tight"
                                        >
                                            {a.icon}
                                            {a.label}
                                        </button>
                                    ))}
                                </div>
                            </Card>
                        </div>
                    </div>

                    {/* ── R4 三栏：待处理异常 / 失败任务排行 / 最近运行 ── */}
                    <div className="flex-1 min-h-0 grid grid-cols-3 gap-ds-3">
                        <Card
                            title="待处理异常"
                            sub={issues.length > 0 ? <span className="bg-ds-danger-light text-ds-danger rounded-ds-sm px-[6px] text-ds-caption font-semibold tabular-nums">{issues.length}</span> : undefined}
                            more={issues.length > 0 ? <>全部 {issues.length} 条<HiOutlineChevronRight size={12}/></> : undefined}
                            onMore={() => navigate('/engineering/dag-executions')}
                        >
                            {visibleIssues.length === 0
                                ? (
                                    <div className="flex-1 flex flex-col items-center justify-center gap-ds-2 text-ds-text-muted">
                                        <HiOutlineCheckCircle size={26} className="text-ds-success"/>
                                        <span className="text-ds-small">近 24 小时无异常</span>
                                    </div>
                                )
                                : visibleIssues.map(item => <QueueRow key={item.key} item={item} onRerun={handleRerun} onOpen={handleOpen}/>)}
                        </Card>

                        <Card
                            title="失败任务排行"
                            sub="近 14 日"
                            more={<>执行历史<HiOutlineChevronRight size={12}/></>}
                            onMore={() => navigate('/engineering/dag-executions')}
                        >
                            {(eng?.topFailures ?? []).length === 0
                                ? (
                                    <div className="flex-1 flex flex-col items-center justify-center gap-ds-2 text-ds-text-muted">
                                        <HiOutlineCheckCircle size={26} className="text-ds-success"/>
                                        <span className="text-ds-small">近 14 日零失败</span>
                                    </div>
                                )
                                : (eng?.topFailures ?? []).map((f, idx) => (
                                    <button
                                        key={`${f.type}-${f.refId}`}
                                        type="button"
                                        onClick={() => navigate(f.type === 'dag'
                                            ? `/engineering/dag-executions?dagId=${f.refId}`
                                            : `/engineering/sync-job-history?syncJobId=${f.refId}`)}
                                        className="font-sans flex items-center gap-ds-3 px-ds-1 py-[9px] border-b border-ds-border-subtle last:border-0 text-left hover:bg-ds-bg-hover rounded-ds-xs transition-colors [@media(max-height:700px)]:py-[5px]"
                                    >
                                        <span className={`flex-shrink-0 w-5 h-5 rounded-ds-xs flex items-center justify-center text-ds-nano font-bold ${
                                            idx === 0 ? 'bg-ds-danger-light text-ds-danger' : 'bg-ds-bg-hover text-ds-text-muted'
                                        }`}>{idx + 1}</span>
                                        <span className="flex-1 min-w-0 text-ds-small font-medium text-ds-text-primary truncate">{f.name}</span>
                                        <span className="flex-shrink-0 text-ds-nano text-ds-text-muted">{relTime(f.lastFailedAt)}</span>
                                        <span className="flex-shrink-0 text-ds-caption font-bold text-ds-danger tabular-nums">{Number(f.failCount)} 次</span>
                                    </button>
                                ))}
                        </Card>

                        <Card
                            title="最近运行"
                            more={<>执行历史<HiOutlineChevronRight size={12}/></>}
                            onMore={() => navigate('/engineering/dag-executions')}
                        >
                            {(eng?.recentRuns ?? []).length === 0
                                ? (
                                    <div className="flex-1 flex items-center justify-center text-ds-small text-ds-text-muted">暂无运行记录</div>
                                )
                                : (eng?.recentRuns ?? []).slice(0, shortVp ? 4 : 7).map(r => {
                                    const st = RUN_STATUS[r.status] ?? {label: r.status, dot: 'bg-slate-300'};
                                    return (
                                        <button
                                            key={`${r.type}-${r.executionId}`}
                                            type="button"
                                            onClick={() => navigate(r.type === 'dag'
                                                ? `/engineering/dag-executions?dagId=${r.refId}`
                                                : `/engineering/sync-job-history?syncJobId=${r.refId}`)}
                                            className="font-sans flex items-center gap-ds-2 px-ds-1 py-[5px] border-b border-ds-border-subtle last:border-0 text-left hover:bg-ds-bg-hover rounded-ds-xs transition-colors [@media(max-height:700px)]:py-[3px]"
                                        >
                                            <span className={`flex-shrink-0 w-[7px] h-[7px] rounded-full ${st.dot}`}/>
                                            <span className="flex-1 min-w-0 text-ds-caption text-ds-text-secondary truncate">
                                                <b className="text-ds-text-primary font-semibold">{r.name}</b> {st.label}
                                            </span>
                                            <span className="flex-shrink-0 text-ds-nano text-ds-text-muted tabular-nums">{fmtDuration(r.durationMs)}</span>
                                            <span className="flex-shrink-0 w-[64px] text-right text-ds-nano text-ds-text-muted">{relTime(r.startTime)}</span>
                                        </button>
                                    );
                                })}
                        </Card>
                    </div>
                </>
            )}
        </div>
    );
}
