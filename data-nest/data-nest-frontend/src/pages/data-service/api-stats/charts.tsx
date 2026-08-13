// Sprint 10 F3：数据服务域统计共享组件与工具（全局 API 运行统计页 + 单 API 详情统计区块复用）。
// 对齐质量报告 charts.tsx 手写 SVG/div 模式，无图表库依赖。
import {Tooltip} from 'antd';
import {HiOutlineArrowRight} from 'react-icons/hi2';
import DsSpinner from '@/components/DsSpinner';
import {formatNumber, formatTimeHm} from '@/utils/format';
import type {StatsRange} from '@/types/data-service';

/** 时间范围快捷项（对齐原型 seg：近24h/近7天/近30天，默认近7天） */
export const RANGE_OPTIONS: { value: StatsRange; label: string }[] = [
    {value: '24h', label: '近24小时'},
    {value: '7d', label: '近7天'},
    {value: '30d', label: '近30天'},
];

export const RANGE_LABEL: Record<StatsRange, string> = {
    '24h': '近 24 小时',
    '7d': '近 7 天',
    '30d': '近 30 天',
};

/** HTTP 状态码语义名（错误码分布 429 限流突出） */
export const HTTP_STATUS: Record<number, string> = {
    400: '参数错误',
    401: '未认证',
    403: '无权限',
    404: '未找到',
    408: '超时',
    429: '限流',
    500: '内部错误',
    502: '网关错误',
    503: '服务不可用',
};

export function statusName(code: number): string {
    return HTTP_STATUS[code] ?? `${code}`;
}

/** 比率 0~1 → 百分比字符串（'98.7%'），空值 → '—' */
export function pct(v: number | null | undefined, digits = 1): string {
    if (v == null) return '—';
    return `${(v * 100).toFixed(digits)}%`;
}

/** X 轴时间标签：24h 按小时（HH:mm），7d/30d 按天（MM-dd） */
export function bucketLabel(bucket: string, range: StatsRange): string {
    if (!bucket) return '';
    return range === '24h' ? formatTimeHm(bucket) : bucket.slice(5, 10);
}

/** 时间范围切换（三段式 seg，对齐原型） */
export function RangeSeg({range, onChange}: { range: StatsRange; onChange: (r: StatsRange) => void }) {
    return (
        <div className="flex items-center rounded-ds-sm border border-ds-border-subtle overflow-hidden">
            {RANGE_OPTIONS.map((opt) => (
                <button
                    key={opt.value}
                    type="button"
                    onClick={() => onChange(opt.value)}
                    className={`px-ds-3 py-ds-2 text-ds-small transition-colors ${
                        range === opt.value
                            ? 'bg-ds-accent text-white font-medium'
                            : 'bg-white text-ds-text-secondary hover:bg-ds-bg-hover'
                    }`}
                >
                    {opt.label}
                </button>
            ))}
        </div>
    );
}

/** KPI 卡（对齐原型 kpi-card：label + 大数值 + 说明） */
export function KpiCard({label, value, unit, sub, valueClass}: {
    label: string;
    value: string;
    unit?: string;
    sub: string;
    valueClass?: string;
}) {
    return (
        <div className="bg-ds-bg-surface border border-ds-border-subtle rounded-ds-md px-ds-4 py-ds-3">
            <div className="text-ds-tiny text-ds-text-muted">{label}</div>
            <div className={`text-ds-heading font-bold mt-ds-1 leading-tight ${valueClass ?? 'text-ds-text-primary'}`}>
                {value}
                {unit && <span className="text-ds-small font-normal text-ds-text-muted ml-1">{unit}</span>}
            </div>
            <div className="text-ds-tiny text-ds-text-muted mt-ds-1">{sub}</div>
        </div>
    );
}

/** 图表卡片容器（对齐质量报告 ChartCard） */
export function ChartCard({title, sub, action, children, className}: {
    title: string;
    sub?: string;
    action?: React.ReactNode;
    children: React.ReactNode;
    className?: string;
}) {
    return (
        <div className={`bg-ds-bg-surface border border-ds-border-subtle rounded-ds-md px-ds-4 py-ds-3 flex flex-col min-h-0 ${className ?? ''}`}>
            <div className="flex items-center gap-ds-2 mb-ds-3 flex-shrink-0">
                <span className="text-ds-small font-semibold text-ds-text-primary">{title}</span>
                {sub && <span className="text-ds-tiny text-ds-text-muted">{sub}</span>}
                {action && <div className="ml-auto flex items-center gap-ds-3">{action}</div>}
            </div>
            <div className="flex-1 min-h-0 overflow-hidden">{children}</div>
        </div>
    );
}

/** 图例色点 */
export function LegendDot({color, label}: { color: string; label: string }) {
    return (
        <span className="flex items-center gap-1 text-ds-tiny text-ds-text-muted whitespace-nowrap">
            <span className="w-2 h-2 rounded-full flex-shrink-0" style={{background: color}}/>
            {label}
        </span>
    );
}

/** 三档占比条（健康分布 / 错误码 4xx/5xx）；段带 label 时 hover 显示「label · 占比」 */
export function SplitBar({segments}: { segments: { color: string; ratio: number; label?: string }[] }) {
    const total = segments.reduce((s, x) => s + x.ratio, 0) || 1;
    return (
        <div className="flex h-2.5 rounded-full overflow-hidden bg-ds-bg-hover w-full">
            {segments.filter(s => s.ratio > 0).map((s, i) => (
                <Tooltip key={i} title={s.label ? `${s.label} · ${((s.ratio / total) * 100).toFixed(0)}%` : undefined} placement="top">
                    <div style={{width: `${(s.ratio / total) * 100}%`, background: s.color}}/>
                </Tooltip>
            ))}
        </div>
    );
}

/** 排名条目：序号 + 主标题/副标题 + 占比条 + 值；dimmed 灰显（僵尸 Key / 已删除，仅内容淡化徽章不淡化）；
 *  plain 用于问题分布类排行（错误码），序号一律中性灰；可点击跳详情 */
export function RankItem({rank, title, sub, value, maxValue, onClick, dimmed, barColor, plain}: {
    rank: number;
    title: string;
    sub?: string;
    value: string;
    maxValue: number;
    onClick?: () => void;
    dimmed?: boolean;
    barColor?: string;
    plain?: boolean;
}) {
    const width = maxValue > 0 ? Math.max(0, (Number(value) / maxValue) * 100) : 0;
    // 排名徽章由深到浅（同色系 accent 明度梯度：1 实心 → 2 中 → 3 浅底 → 4/5 中性灰）；
    // plain（问题分布类排行）不区分名次、一律中性灰（对齐原型错误码 rr-no）。
    const rankBadge = (r: number) => {
        if (plain) return 'bg-ds-bg-root text-ds-text-muted';
        if (r === 1) return 'bg-ds-accent text-white';
        if (r === 2) return 'bg-ds-accent/75 text-white';
        if (r === 3) return 'bg-ds-accent-light text-ds-accent';
        return 'bg-ds-bg-root text-ds-text-muted';
    };
    const content = (
        <>
            <span className={`w-5 h-5 rounded-full flex items-center justify-center text-ds-tiny font-bold flex-shrink-0 ${rankBadge(rank)}`}>
                {rank}
            </span>
            <div className="flex-1 min-w-0">
                <Tooltip title={title}>
                    <span className={`block text-ds-small truncate leading-tight ${dimmed ? 'text-ds-text-muted' : 'text-ds-text-primary'}`}>
                        {title}
                    </span>
                </Tooltip>
                {sub && (
                    <Tooltip title={sub}>
                        <span className="block text-ds-tiny text-ds-text-muted truncate leading-tight">{sub}</span>
                    </Tooltip>
                )}
            </div>
            <div className="flex items-center gap-ds-2 flex-shrink-0 ml-auto">
                <div className="w-24 flex-shrink-0 h-1.5 rounded-full bg-ds-bg-hover overflow-hidden">
                    <div className="h-full rounded-full" style={{width: `${width}%`, background: dimmed ? 'rgb(203 213 225)' : (barColor ?? 'rgb(var(--color-accent))')}}/>
                </div>
                <span className={`w-16 text-right text-ds-small font-mono tabular-nums flex-shrink-0 ${dimmed ? 'text-ds-text-muted' : 'text-ds-text-secondary'}`}>
                    {formatNumber(value)}
                </span>
                {onClick && (
                    <span className="text-ds-accent text-ds-tiny font-semibold flex-shrink-0 inline-flex items-center gap-0.5">
                        详情<HiOutlineArrowRight size={10}/>
                    </span>
                )}
            </div>
        </>
    );
    if (!onClick) {
        return <div className="flex-1 flex items-center gap-ds-3 py-1">{content}</div>;
    }
    return (
        <button type="button" onClick={onClick}
                className="flex-1 w-full flex items-center gap-ds-3 py-1 text-left hover:bg-ds-bg-hover rounded-ds-sm transition-colors">
            {content}
        </button>
    );
}

/** 柱状图（限流命中趋势 / 今日小时分布，按时间桶） */
export function Bars({data, range, color, emptyText, xLabel}: {
    data: { bucket: string; total: string }[];
    range: StatsRange;
    color?: string;
    emptyText?: string;
    xLabel?: (index: number) => string;
}) {
    if (data.length === 0) {
        return (
            <div className="h-full flex items-center justify-center text-ds-small text-ds-text-muted">
                {emptyText ?? '范围内暂无数据'}
            </div>
        );
    }
    const max = Math.max(1, ...data.map(d => Number(d.total ?? 0)));
    return (
        <div className="h-full flex flex-col min-h-0">
            <div className="flex-1 flex gap-1 min-h-0 justify-center">
                {data.map((d, i) => {
                    const v = Number(d.total ?? 0);
                    return (
                        <Tooltip key={d.bucket} title={`${formatNumber(d.total)} 次`} placement="top">
                            <div className="flex-1 max-w-[24px] flex items-end min-w-0">
                                <div className="w-full rounded-t-sm"
                                     style={{
                                         height: v > 0 ? `${Math.max(2, (v / max) * 100)}%` : '0',
                                         background: color ?? 'rgb(var(--color-warning))',
                                     }}/>
                            </div>
                        </Tooltip>
                    );
                })}
            </div>
            <div className="flex gap-1 mt-ds-1 flex-shrink-0">
                {data.map((d, i) => (
                    <span key={d.bucket} className="flex-1 text-center text-ds-tiny text-ds-text-muted truncate">
                        {xLabel ? xLabel(i) : bucketLabel(d.bucket, range)}
                    </span>
                ))}
            </div>
        </div>
    );
}

/** 加载占位（图表区统一） */
export function StatsLoading({minHeight = 180}: { minHeight?: number }) {
    return (
        <div className="h-full flex items-center justify-center" style={{minHeight}}>
            <DsSpinner size={20}/>
        </div>
    );
}
