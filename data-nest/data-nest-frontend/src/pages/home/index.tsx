// Sprint 11 首页 v4.1「值班态势总览」
// 设计文档：docs/sprint11/DataNest-Sprint11-首页重设计-v4.md（行业调研结论见 §0）
// 定位：值班运维盯盘 —— 3 秒回答「有没有事 → 什么事 → 去哪处理」
// 硬约束：一屏零滚动（1440×900 / 1920×1080）；不用红绿柱；语义色只做小面积高亮；文案产品化（禁技术术语）
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
    HomeAlertKpi,
    HomeEngineeringKpi,
    HomeGovernanceKpi,
    HomeRealtimeKpi,
} from '@/api/home';
import {getDataSourceStats} from '@/api/datasource';
import {getSyncJobStats, executeSyncJob} from '@/api/sync';
import {rerunFailed} from '@/pages/engineering/dags/api';
import DsButton from '@/components/DsButton';
import {QUALITY_CHECK_LEVEL_LABEL} from '@/types/quality';
import type {QualityCheckLevel} from '@/types/quality';
import {notify} from '@/utils/notify';
import {
    HiOutlineArrowPath,
    HiOutlineBolt,
    HiOutlineChevronRight,
    HiOutlineCircleStack,
    HiOutlineCommandLine,
    HiOutlineCpuChip,
    HiOutlinePlus,
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

// =================== 子组件 ===================

/** 态势判定呼吸点（tailwind animate-ping，无需自定义 keyframes） */
function VerdictDot({tone}: {tone: VerdictTone}) {
    const bg = {ok: 'bg-ds-success', warn: 'bg-ds-warning', down: 'bg-ds-danger', loading: 'bg-ds-text-muted'}[tone];
    return (
        <span className="relative flex w-3 h-3 flex-shrink-0">
            {tone !== 'loading' && <span className={`animate-ping absolute inline-flex h-full w-full rounded-full ${bg} opacity-50`}/>}
            <span className={`relative inline-flex rounded-full w-3 h-3 ${bg}`}/>
        </span>
    );
}

/** 今日任务状态分布（行业标配组件：分段条 + 每段点击钻取执行历史） */
function StatusDist({eng, onDrill}: {eng: HomeEngineeringKpi | null; onDrill: () => void}) {
    const segs = [
        {label: '成功', value: Number(eng?.todaySuccess ?? 0), dot: 'bg-ds-success', bar: 'bg-ds-success'},
        {label: '失败', value: Number(eng?.todayFailed ?? 0), dot: 'bg-ds-danger', bar: 'bg-ds-danger'},
        {label: '运行中', value: Number(eng?.running ?? 0), dot: 'bg-ds-accent', bar: 'bg-ds-accent'},
        {label: '等待', value: Number(eng?.waiting ?? 0), dot: 'bg-slate-300', bar: 'bg-slate-300'},
    ];
    const denom = Math.max(segs.reduce((s, x) => s + x.value, 0), 1);
    return (
        <div className="flex flex-col gap-ds-2 flex-1 min-w-0">
            <div className="flex items-center justify-between gap-ds-3 whitespace-nowrap">
                <span className="text-ds-nano text-ds-text-muted flex-shrink-0">
                    今日运行 <b className="text-ds-body font-bold text-ds-text-primary tabular-nums">{eng ? Number(eng.todayTotal) : '--'}</b> 次
                </span>
                <div className="flex items-center gap-[2px]">
                    {segs.map(s => (
                        <button
                            key={s.label}
                            type="button"
                            onClick={onDrill}
                            className="font-sans inline-flex items-center gap-ds-1 text-ds-caption font-medium text-ds-text-secondary px-[6px] py-[2px] rounded-ds-xs hover:bg-ds-bg-hover hover:text-ds-text-primary transition-colors"
                        >
                            <span className={`w-[7px] h-[7px] rounded-full flex-shrink-0 ${s.dot}`}/>
                            {s.label} <b className="font-bold tabular-nums">{eng ? s.value : '--'}</b>
                        </button>
                    ))}
                </div>
            </div>
            <div className="flex h-2 rounded-full overflow-hidden bg-ds-bg-hover">
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

/** 待处理异常工作队列行 */
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
        <div className="flex items-center gap-ds-4 px-ds-1 py-[10px] border-b border-ds-border-subtle last:border-0">
            <div className="flex-1 min-w-0">
                <div className="flex items-center gap-ds-2">
                    <span className={`flex-shrink-0 text-ds-badge font-semibold px-2 py-0.5 rounded-ds-xs ${badge.cls}`}>{badge.label}</span>
                    <span className="text-ds-small font-semibold text-ds-text-primary truncate">{item.title}</span>
                </div>
                {item.reason && (
                    <div className="text-ds-caption font-normal text-ds-text-muted mt-[2px] truncate">{item.reason}</div>
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
                    {item.kind === 'quality' ? '查看报告' : item.kind === 'alert' ? '告警中心' : '日志'}
                    <HiOutlineChevronRight size={12}/>
                </DsButton>
            </div>
        </div>
    );
}

/** 系统健康行：状态点 + 人话描述 */
function HealthRow({icon, label, value, down, loading}: {
    icon: React.ReactNode; label: string; value: string; down?: boolean; loading?: boolean;
}) {
    return (
        <div className={`flex items-center justify-between gap-ds-2 px-ds-2 py-ds-2 rounded-ds-sm text-ds-small text-ds-text-secondary ${down ? 'bg-ds-danger-light' : 'hover:bg-ds-bg-hover'}`}>
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

/** 近 14 日趋势 strip：indigo 单色面积图，失败日红点标记（非柱状、不占主视觉）；悬停显示当日明细浮层 */
function TrendStrip({eng}: {eng: HomeEngineeringKpi | null}) {
    const [hover, setHover] = useState<number | null>(null);
    const points = eng?.trend ?? [];
    const W = 1200;
    const H = 100;
    const BASE = 88;
    const max = Math.max(...points.map(p => Number(p.total)), 1);
    /** 左右各留 8px 内边距：贴边数据点（含失败红点）不被裁切，且红点与折线顶点天然重合 */
    const PAD = 8;
    const step = points.length > 1 ? (W - 2 * PAD) / (points.length - 1) : W;
    const xy = points.map((p, i) => [PAD + i * step, BASE - (Number(p.total) / max) * 70] as const);
    const line = xy.map(([x, y], i) => `${i === 0 ? 'M' : 'L'}${x.toFixed(1)},${y.toFixed(1)}`).join(' ');
    const area = xy.length ? `${line} L${xy[xy.length - 1][0]},${H} L${xy[0][0]},${H} Z` : '';
    const xLabels = points.filter((_, i) => i % 2 === 0 || i === points.length - 1);
    /** 贴边点半径 4 + 描边 2，钳制 6px 防裁切 */
    const clampX = (x: number) => Math.min(Math.max(x, 6), W - 6);

    return (
        <div className="bg-ds-bg-surface rounded-ds-md border border-ds-border-subtle shadow-ds-xs flex-shrink-0">
            <div className="flex items-center justify-between px-ds-4 py-[10px] border-b border-ds-border-subtle">
                <span className="text-ds-body font-bold text-ds-text-primary">
                    近 14 日运行趋势
                    {eng?.successRate7d != null && (
                        <span className="text-ds-caption font-medium text-ds-text-muted ml-ds-1">
                            近 7 天成功率 <b className="text-ds-text-primary tabular-nums">{eng.successRate7d}%</b>
                        </span>
                    )}
                </span>
                <div className="flex items-center gap-ds-3 text-ds-caption font-normal text-ds-text-muted">
                    <span className="flex items-center gap-ds-1"><span className="w-2 h-2 rounded-sm bg-ds-accent"/>运行量</span>
                    <span className="flex items-center gap-ds-1"><span className="w-[7px] h-[7px] rounded-full bg-ds-danger"/>有失败</span>
                </div>
            </div>
            <div className="px-ds-4 pt-ds-2 pb-[10px]">
                {points.length === 0
                    ? <div className="h-[100px] flex items-center justify-center text-ds-small text-ds-text-muted">暂无数据</div>
                    : (
                        <>
                            <div className="relative">
                                <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" className="w-full h-[100px] block">
                                    <defs>
                                        <linearGradient id="home-trend-area" x1="0" y1="0" x2="0" y2="1">
                                            <stop offset="0%" stopColor="#4f46e5" stopOpacity="0.22"/>
                                            <stop offset="100%" stopColor="#4f46e5" stopOpacity="0.02"/>
                                        </linearGradient>
                                    </defs>
                                    <line x1="0" y1={BASE + 0.5} x2={W} y2={BASE + 0.5} stroke="#d8dee8" strokeWidth="1"/>
                                    <line x1="0" y1="45" x2={W} y2="45" stroke="#eef0f7" strokeWidth="1" strokeDasharray="4 4"/>
                                    <path d={area} fill="url(#home-trend-area)"/>
                                    <path d={line} fill="none" stroke="#4f46e5" strokeWidth="2" strokeLinejoin="round"/>
                                    {points.map((p, i) => Number(p.failed) > 0 && (
                                        <circle
                                            key={p.day}
                                            cx={clampX(xy[i][0])}
                                            cy={xy[i][1]}
                                            r="4"
                                            fill="#fff"
                                            stroke="#dc2626"
                                            strokeWidth="2"
                                        />
                                    ))}
                                    {/* 悬停命中点（虚线参考线 + 高亮点） */}
                                    {hover !== null && (
                                        <>
                                            <line
                                                x1={xy[hover][0]} y1="4" x2={xy[hover][0]} y2={BASE}
                                                stroke="#4f46e5" strokeWidth="1" strokeDasharray="3 3" opacity="0.4"
                                            />
                                            <circle
                                                cx={clampX(xy[hover][0])}
                                                cy={xy[hover][1]}
                                                r="4"
                                                fill="#4f46e5"
                                                stroke="#fff"
                                                strokeWidth="2"
                                            />
                                        </>
                                    )}
                                    {/* 每天的透明悬停热区 */}
                                    {points.map((p, i) => (
                                        <rect
                                            key={`hot-${p.day}`}
                                            x={Math.max(i * step - step / 2, 0)}
                                            y="0"
                                            width={step}
                                            height={H}
                                            fill="transparent"
                                            onMouseEnter={() => setHover(i)}
                                            onMouseLeave={() => setHover(null)}
                                        />
                                    ))}
                                </svg>
                                {/* 悬停浮层：贴左边右对齐、贴右边左对齐，避免被裁 */}
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
                            <div className="flex justify-between pt-ds-1 text-ds-nano font-normal text-ds-text-muted">
                                {xLabels.map((p, i) => (
                                    <span key={p.day}>{i === xLabels.length - 1 ? '今天' : p.day}</span>
                                ))}
                            </div>
                        </>
                    )}
            </div>
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

    const [eng, setEng] = useState<HomeEngineeringKpi | null>(null);
    const [alert, setAlert] = useState<HomeAlertKpi | null>(null);
    const [gov, setGov] = useState<HomeGovernanceKpi | null>(null);
    const [rt, setRt] = useState<HomeRealtimeKpi | null>(null);
    const [dsStats, setDsStats] = useState<{normal: number; error: number} | null>(null);
    const [syncStats, setSyncStats] = useState<{running: number} | null>(null);
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

    // ---- 态势判定（v4.1：Doris/Flink DOWN → 故障；失败待处理/质量异常/数据源异常 → 需关注） ----
    // 计数与下方异常队列对齐：失败按任务去重（后端）、质量按「规则+表」去重
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
    const verdictReasons: string[] = [];
    if (pendingFailed > 0) verdictReasons.push(`${pendingFailed} 个任务失败待处理`);
    if (qualityCount > 0) verdictReasons.push(`${qualityCount} 项质量异常`);
    if (dsError > 0) verdictReasons.push(`${dsError} 个数据源连接失败`);
    if (flinkDown) verdictReasons.push('Flink 不可用');
    if (dorisDown) verdictReasons.push('Doris 不可用');

    const hour = new Date().getHours();
    const greeting = hour < 6 ? '夜深了' : hour < 12 ? '早上好' : hour < 18 ? '下午好' : '晚上好';

    // 空平台判定：全新用户三步引导（设计 §6）
    const isFreshPlatform = !!eng && Number(eng.todayTotal) === 0 && pendingFailed === 0
        && (eng.trend ?? []).every(p => Number(p.total) === 0)
        && (gov?.qualityIssues ?? []).length === 0;

    // ---- 待处理异常工作队列（最多 5 行） ----
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
    // 质量异常按「规则 + 表」去重，只保留最近一次结果（同一规则多批次只算 1 项）
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
    const visibleIssues = issues.slice(0, 5);

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
        <div className="h-full flex flex-col gap-ds-3 min-h-0">

            {/* ── L1 态势横幅（签名元素：判定 + 状态分布条） ── */}
            <section className="bg-ds-bg-surface rounded-ds-md border border-ds-border-subtle shadow-ds-xs px-ds-5 py-ds-4 flex items-center gap-ds-5 flex-shrink-0">
                <div className="flex items-center gap-ds-3 flex-shrink-0">
                    <VerdictDot tone={verdictTone}/>
                    <div>
                        <div className={`text-ds-display leading-tight ${
                            {ok: 'text-ds-success', warn: 'text-ds-warning', down: 'text-ds-danger', loading: 'text-ds-text-primary'}[verdictTone]
                        }`}>
                            {verdictText}
                        </div>
                        <div className="text-ds-caption font-normal text-ds-text-muted mt-[2px]">
                            {greeting}，{userInfo?.username || '管理员'} · {new Date().toLocaleDateString('zh-CN', {year: 'numeric', month: 'long', day: 'numeric', weekday: 'short'})}
                            {verdictReasons.length > 0 && ` · ${verdictReasons.join('，')}`}
                        </div>
                    </div>
                </div>

                {isFreshPlatform ? (
                    /* 空平台三步引导（替代分布条） */
                    <div className="flex items-center gap-ds-2 flex-1 min-w-0">
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
                ) : (
                    <>
                        <div className="w-px self-stretch bg-ds-border-subtle flex-shrink-0"/>
                        <StatusDist eng={eng} onDrill={() => navigate('/engineering/dag-executions')}/>
                    </>
                )}

                <div className="w-px self-stretch bg-ds-border-subtle flex-shrink-0"/>
                <button
                    type="button"
                    onClick={() => navigate('/system/alert-center')}
                    className="font-sans flex flex-col items-start gap-[2px] flex-shrink-0 px-[6px] py-[2px] -m-[2px] rounded-ds-sm hover:bg-ds-bg-hover transition-colors"
                    title="前往告警中心"
                >
                    <span className={`text-ds-heading leading-tight tabular-nums ${Number(alert?.total ?? 0) > 0 ? 'text-ds-warning' : 'text-ds-text-primary'}`}>
                        {alert ? Number(alert.total) : '--'}
                    </span>
                    <span className="text-ds-nano text-ds-text-muted">24h 告警</span>
                </button>

                <div className="flex items-center gap-ds-3 flex-shrink-0 whitespace-nowrap">
                    {lastUpdate && (
                        <span className="text-ds-caption font-normal text-ds-text-muted tabular-nums">
                            {lastUpdate.toLocaleTimeString('zh-CN', {hour: '2-digit', minute: '2-digit'})} 更新
                        </span>
                    )}
                    <DsButton variant="secondary" onClick={load} disabled={loading} className="gap-[6px]">
                        <HiOutlineArrowPath size={14} className={loading ? 'animate-spin' : ''}/>
                        刷新
                    </DsButton>
                </div>
            </section>

            {/* ── L2 主区：异常队列 + 右栏 ── */}
            <div className="flex-1 min-h-0 grid grid-cols-1 xl:grid-cols-[1fr_320px] gap-ds-3">

                {/* 待处理异常工作队列 */}
                <div className="bg-ds-bg-surface rounded-ds-md border border-ds-border-subtle shadow-ds-xs flex flex-col min-h-0">
                    <div className="flex items-center justify-between px-ds-4 py-ds-3 border-b border-ds-border-subtle flex-shrink-0">
                        <span className="text-ds-body font-bold text-ds-text-primary flex items-center gap-ds-2">
                            待处理异常
                            {issues.length > 0 && (
                                <span className="bg-ds-danger-light text-ds-danger rounded-ds-sm px-[6px] text-ds-caption font-semibold tabular-nums">{issues.length}</span>
                            )}
                        </span>
                        {issues.length > 0 && (
                            <DsButton variant="ghost" className="px-[6px] py-[2px] text-ds-caption" onClick={() => navigate('/engineering/dag-executions')}>
                                全部 {issues.length} 条<HiOutlineChevronRight size={12}/>
                            </DsButton>
                        )}
                    </div>
                    <div className="flex-1 min-h-0 overflow-y-auto px-ds-4 py-ds-1">
                        {engFailed
                            ? (
                                <div className="h-full flex items-center justify-center gap-ds-2 text-ds-small text-ds-text-muted">
                                    加载失败
                                    <DsButton variant="ghost" className="px-[6px] py-[2px] text-ds-caption" onClick={load}>重试</DsButton>
                                </div>
                            )
                            : visibleIssues.length === 0
                                ? <div className="h-full flex items-center justify-center text-ds-small text-ds-text-muted">近 24 小时无异常，运行平稳</div>
                                : visibleIssues.map(item => <QueueRow key={item.key} item={item} onRerun={handleRerun} onOpen={handleOpen}/>)}
                    </div>
                    <div className="flex-shrink-0 px-ds-4 py-ds-2 border-t border-ds-border-subtle text-ds-nano font-normal text-ds-text-muted">
                        等待超 4 小时标黄、超 24 小时标红 · 涵盖任务失败、数据质量与系统告警
                    </div>
                </div>

                {/* 右栏：系统健康 + 快捷操作 */}
                <div className="flex flex-col gap-ds-3 min-h-0">
                    <div className="bg-ds-bg-surface rounded-ds-md border border-ds-border-subtle shadow-ds-xs flex-1 min-h-0 flex flex-col">
                        <div className="px-ds-4 py-ds-3 border-b border-ds-border-subtle flex-shrink-0">
                            <span className="text-ds-body font-bold text-ds-text-primary">系统健康</span>
                        </div>
                        <div className="flex flex-col p-ds-2">
                            <HealthRow
                                icon={<HiOutlineServer size={15}/>}
                                label="数据源"
                                loading={!dsStats}
                                down={!!dsStats && dsStats.error > 0}
                                value={dsStats ? (dsStats.error > 0 ? `${dsStats.normal} 正常 · ${dsStats.error} 连接失败` : `${dsStats.normal} 正常`) : ''}
                            />
                            <HealthRow
                                icon={<HiOutlineBolt size={15}/>}
                                label="集成任务"
                                loading={!syncStats}
                                value={syncStats ? `${syncStats.running} 个运行中` : ''}
                            />
                            <HealthRow
                                icon={<HiOutlineCpuChip size={15}/>}
                                label="Flink CDC"
                                loading={!rt}
                                down={rt?.flink?.status === 'DOWN'}
                                value={rt?.flink ? (rt.flink.status === 'UP' ? `正常 · ${Number(rt.cdcSyncedTables ?? 0)} 张表同步中` : '不可用') : ''}
                            />
                            <HealthRow
                                icon={<HiOutlineCircleStack size={15}/>}
                                label="Doris"
                                loading={!gov}
                                down={gov?.doris?.status === 'DOWN'}
                                value={gov?.doris ? (gov.doris.status === 'UP' ? `正常 · ${gov.doris.latencyMs}ms` : '不可用') : ''}
                            />
                            <HealthRow
                                icon={<HiOutlineServerStack size={15}/>}
                                label="平台服务"
                                loading={!loaded}
                                down={svcFailedCount > 0}
                                value={svcFailedCount > 0 ? `${svcFailedCount} 项数据异常` : '全部正常'}
                            />
                        </div>
                    </div>

                    <div className="bg-ds-bg-surface rounded-ds-md border border-ds-border-subtle shadow-ds-xs flex-shrink-0">
                        <div className="px-ds-4 py-ds-3 border-b border-ds-border-subtle">
                            <span className="text-ds-body font-bold text-ds-text-primary">快捷操作</span>
                        </div>
                        <div className="grid grid-cols-2 gap-ds-2 p-ds-3">
                            {QUICK_ACTIONS.map(a => (
                                <button
                                    key={a.path + a.label}
                                    type="button"
                                    onClick={() => navigate(a.path)}
                                    className="font-sans flex items-center justify-center gap-[6px] px-ds-3 py-ds-2 rounded-ds-sm border border-ds-border-subtle bg-ds-bg-surface text-ds-small font-medium text-ds-text-secondary hover:border-ds-accent hover:text-ds-accent hover:bg-ds-accent-light transition-colors"
                                >
                                    {a.icon}
                                    {a.label}
                                </button>
                            ))}
                        </div>
                    </div>
                </div>
            </div>

            {/* ── L3 近 14 日趋势 strip ── */}
            <TrendStrip eng={eng}/>
        </div>
    );
}
