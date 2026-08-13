// Sprint 8 F3：质量报告 SVG 图表组件（项目无图表库，对齐原型手写 SVG）。
// 全部用 viewBox + preserveAspectRatio 自适应卡片宽度。
// 2026-08-13：折线图加 hover 十字线 + 浮动数值卡（复用 LineChart 的 ChartTip）。
import {useState} from 'react';
import type {MouseEvent} from 'react';
import {Tooltip} from 'antd';
import {ChartTip} from '@/components/charts/LineChart';
import type {
    DatasourceScoreComparison,
    QualityLevelTrendPoint,
    QualityScoreDistribution,
    QualityScoreTrendPoint,
} from '@/types/quality-report';

/** 折线图 hover 状态（十字线 + 浮动数值卡共用逻辑） */
function useLineHover(count: number) {
    const [hoverIndex, setHoverIndex] = useState<number | null>(null);
    return {
        hoverIndex,
        handlers: {
            onMouseMove: (e: MouseEvent<HTMLDivElement>) => {
                const rect = e.currentTarget.getBoundingClientRect();
                const ratio = rect.width > 0 ? (e.clientX - rect.left) / rect.width : 0;
                const idx = Math.round(ratio * (count - 1));
                setHoverIndex(Math.max(0, Math.min(count - 1, idx)));
            },
            onMouseLeave: () => setHoverIndex(null),
        },
    };
}

/** 四档配色（SVG 属性直读 ds token；与原型图例一致） */
const LEVEL_COLORS = {
    pass: 'rgb(var(--color-success))',
    warning: 'rgb(217 119 6)',
    severe: 'rgb(var(--color-danger))',
    unavailable: 'rgb(148 163 184)',
} as const;
const ACCENT = 'rgb(var(--color-accent))';

const W = 880;
const H = 230;
const PAD_L = 50;
const PAD_R = 20;
const PAD_T = 18;
const PAD_B = 30;

interface Scale {
    x: (i: number) => number;
    y: (v: number) => number;
    max: number;
    ticks: number[];
}

function computeScale(count: number, maxValue: number): Scale {
    const max = Math.max(4, maxValue);
    const tick = Math.ceil(max / 4);
    const niceMax = tick * 4;
    return {
        x: (i) => PAD_L + (count <= 1 ? 0 : (i / (count - 1)) * (W - PAD_L - PAD_R)),
        y: (v) => PAD_T + (1 - v / niceMax) * (H - PAD_T - PAD_B),
        max: niceMax,
        ticks: [0, 1, 2, 3, 4].map(i => i * tick),
    };
}

function toPath(values: number[], scale: Scale): string {
    return values.map((v, i) => `${i === 0 ? 'M' : 'L'}${scale.x(i).toFixed(1)},${scale.y(v).toFixed(1)}`).join(' ');
}

function dayLabel(day: string): string {
    return day.length >= 10 ? day.slice(5) : day;
}

/** 坐标网格 + 轴文本（折线图共用） */
function Grid({scale}: { scale: Scale }) {
    return (
        <>
            {scale.ticks.map((t) => (
                <g key={t}>
                    <line x1={PAD_L} y1={scale.y(t)} x2={W - PAD_R} y2={scale.y(t)}
                          stroke="rgb(226 232 240)" /* border-subtle */ strokeWidth={1}/>
                    <text x={PAD_L - 10} y={scale.y(t) + 4} fontSize={11} fill="rgb(148 163 184)" /* text-muted */ textAnchor="end">
                        {t}
                    </text>
                </g>
            ))}
        </>
    );
}

/** X 轴日期标签（最多 7 个均匀采样） */
function XAxis({days, scale}: { days: string[]; scale: Scale }) {
    const step = Math.max(1, Math.ceil(days.length / 7));
    return (
        <>
            {days.map((d, i) => (i % step === 0 || i === days.length - 1) && (
                <text key={d + i} x={scale.x(i)} y={H - 8} fontSize={11} fill="rgb(148 163 184)" /* text-muted */ textAnchor="middle">
                    {dayLabel(d)}
                </text>
            ))}
        </>
    );
}

/** 四档分布趋势（多系列折线） */
export function LevelTrendChart({data}: { data: QualityLevelTrendPoint[] }) {
    if (data.length === 0) {
        return <div className="h-full flex items-center justify-center text-ds-small text-ds-text-muted">范围内暂无检查明细</div>;
    }
    const nums = (k: 'passCount' | 'warningCount' | 'severeCount' | 'unavailableCount') =>
        data.map(p => Number(p[k] ?? 0));
    const series = [
        {key: 'pass' as const, label: '通过', values: nums('passCount')},
        {key: 'warning' as const, label: '警告', values: nums('warningCount')},
        {key: 'severe' as const, label: '严重', values: nums('severeCount')},
        {key: 'unavailable' as const, label: '不可用', values: nums('unavailableCount')},
    ];
    const maxValue = Math.max(...series.flatMap(s => s.values));
    const scale = computeScale(data.length, maxValue);
    const {hoverIndex, handlers} = useLineHover(data.length);
    return (
        <div className="h-full flex flex-col">
            <div className="flex items-center gap-ds-3 mb-ds-1 flex-shrink-0">
                {series.map(s => (
                    <span key={s.key} className="flex items-center gap-1 text-ds-tiny text-ds-text-muted">
                        <span className="w-2 h-2 rounded-full" style={{background: LEVEL_COLORS[s.key]}}/>
                        {s.label}
                    </span>
                ))}
            </div>
            <div className="relative flex-1 min-h-0" {...handlers}>
                <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" className="absolute inset-0 w-full h-full">
                    <Grid scale={scale}/>
                    {series.map(s => (
                        <path key={s.key} d={toPath(s.values, scale)} fill="none"
                              stroke={LEVEL_COLORS[s.key]} strokeWidth={2}/>
                    ))}
                    {hoverIndex != null && (
                        <g>
                            <line x1={scale.x(hoverIndex)} y1={PAD_T} x2={scale.x(hoverIndex)} y2={H - PAD_B}
                                  stroke="rgb(148 163 184)" strokeWidth={1} strokeDasharray="3 3"/>
                            {series.map(s => {
                                const v = s.values[hoverIndex];
                                return (
                                    <circle key={s.key} cx={scale.x(hoverIndex)} cy={scale.y(v)} r={3.5}
                                            fill={LEVEL_COLORS[s.key]} stroke="#fff" strokeWidth={1.5}/>
                                );
                            })}
                        </g>
                    )}
                    <XAxis days={data.map(p => p.day)} scale={scale}/>
                </svg>
                {hoverIndex != null && (
                    <ChartTip
                        leftPct={(hoverIndex / Math.max(1, data.length - 1)) * 100}
                        label={dayLabel(data[hoverIndex].day)}
                        rows={series.map(s => ({color: LEVEL_COLORS[s.key], label: s.label, value: String(s.values[hoverIndex])}))}
                    />
                )}
            </div>
        </div>
    );
}

/** 表评分分布环图（donut + 图例；hover 环段/图例行 → 中间联动该段数量与占比 + 其余段淡化） */
export function ScoreDonut({data, avgScore}: { data: QualityScoreDistribution; avgScore?: number }) {
    const [hoverSeg, setHoverSeg] = useState<string | null>(null);
    const segments = [
        {label: '优秀', count: Number(data.excellentCount ?? 0), color: 'rgb(var(--color-success))'},
        {label: '良好', count: Number(data.goodCount ?? 0), color: 'rgb(74 222 128)'}, // green-400：与优秀同系递进（深→浅）
        {label: '一般', count: Number(data.warningCount ?? 0), color: '#d97706'},
        {label: '差', count: Number(data.badCount ?? 0), color: 'rgb(var(--color-danger))'},
        {label: '无评分', count: Number(data.noScoreCount ?? 0), color: 'rgb(148 163 184)'},
    ];
    const total = segments.reduce((s, x) => s + x.count, 0);
    if (total === 0) {
        return <div className="h-full flex items-center justify-center text-ds-small text-ds-text-muted">范围内暂无评分表</div>;
    }
    const active = hoverSeg ? segments.find(s => s.label === hoverSeg) : undefined;
    const R = 34;
    const C = 2 * Math.PI * R;
    let offset = C / 4; // 从 12 点方向开始
    return (
        <div className="h-full flex items-center gap-ds-4">
            <svg viewBox="0 0 104 104" className="h-full max-h-[140px] flex-shrink-0">
                <circle cx={52} cy={52} r={R} fill="none" stroke="rgb(238 242 255)" /* accent-light */ strokeWidth={10}/>
                {segments.filter(s => s.count > 0).map(s => {
                    const len = (s.count / total) * C;
                    const el = (
                        <circle key={s.label} cx={52} cy={52} r={R} fill="none"
                                stroke={s.color}
                                strokeWidth={hoverSeg === s.label ? 14 : 10}
                                strokeDasharray={`${len} ${C - len}`} strokeDashoffset={offset}
                                transform="rotate(-90 52 52)"
                                opacity={hoverSeg && hoverSeg !== s.label ? 0.35 : 1}
                                onMouseEnter={() => setHoverSeg(s.label)}
                                onMouseLeave={() => setHoverSeg(null)}/>
                    );
                    offset -= len;
                    return el;
                })}
                <text x={52} y={52} fontSize={16} fontWeight={700} fill="rgb(15 23 42)" /* text-primary */ textAnchor="middle">
                    {active ? String(active.count) : (avgScore != null ? avgScore : '—')}
                </text>
                <text x={52} y={66} fontSize={9} fill="rgb(148 163 184)" /* text-muted */ textAnchor="middle">
                    {active ? `${active.label} · ${((active.count / total) * 100).toFixed(1)}%` : '平均分'}
                </text>
            </svg>
            <div className="flex flex-col gap-ds-1 min-w-0">
                {segments.map(s => (
                    <div key={s.label}
                         className={`flex items-center gap-ds-2 text-ds-tiny rounded-ds-sm px-ds-1 transition-opacity ${hoverSeg && hoverSeg !== s.label ? 'opacity-40' : ''}`}
                         onMouseEnter={() => setHoverSeg(s.label)}
                         onMouseLeave={() => setHoverSeg(null)}>
                        <span className="w-2 h-2 rounded-full flex-shrink-0" style={{background: s.color}}/>
                        <span className="text-ds-text-secondary w-10">{s.label}</span>
                        <span className="text-ds-text-muted w-12">{((s.count / total) * 100).toFixed(1)}%</span>
                        <span className="text-ds-text-primary font-semibold ml-auto">{s.count}</span>
                    </div>
                ))}
            </div>
        </div>
    );
}

/** 数据源质量对比（横向条） */
export function ComparisonBars({data}: { data: DatasourceScoreComparison[] }) {
    if (data.length === 0) {
        return <div className="h-full flex items-center justify-center text-ds-small text-ds-text-muted">范围内暂无评分数据</div>;
    }
    const barColor = (score?: number) => score == null ? 'rgb(148 163 184)' : score >= 80 ? 'rgb(var(--color-success))' : score >= 60 ? 'rgb(217 119 6)' : 'rgb(var(--color-danger))';
    return (
        <div className="h-full flex flex-col justify-center gap-ds-3">
            {data.map(d => (
                <div key={d.datasourceId} className="flex items-center gap-ds-2">
                    <Tooltip title={d.datasourceName ?? `数据源 ${d.datasourceId}`}>
                        <span className="w-24 truncate text-ds-tiny text-ds-text-secondary">
                            {d.datasourceName ?? `数据源 ${d.datasourceId}`}
                        </span>
                    </Tooltip>
                    <Tooltip title={`${d.datasourceName ?? `数据源 ${d.datasourceId}`} · ${d.avgScore ?? '无评分'} 分`} placement="top">
                        <div className="flex-1 h-2.5 rounded-full bg-ds-bg-hover overflow-hidden">
                            <div className="h-full rounded-full transition-all"
                                 style={{width: `${Math.min(100, d.avgScore ?? 0)}%`, background: barColor(d.avgScore)}}/>
                        </div>
                    </Tooltip>
                    <span className="w-10 text-right text-ds-tiny font-semibold text-ds-text-primary">
                        {d.avgScore ?? '—'}
                    </span>
                </div>
            ))}
        </div>
    );
}

/** 评分趋势（面积折线；聚合模式按天 avgScore，单表模式按批次 score） */
export function ScoreTrendChart({data}: { data: QualityScoreTrendPoint[] }) {
    if (data.length === 0) {
        return <div className="h-full flex items-center justify-center text-ds-small text-ds-text-muted">范围内暂无评分历史</div>;
    }
    const pointValue = (p: QualityScoreTrendPoint) => Number(p.avgScore ?? p.score ?? 0);
    const pointDay = (p: QualityScoreTrendPoint) => p.day ?? (p.checkedAt || '').slice(0, 10);
    const values = data.map(pointValue);
    const scale = computeScale(data.length, 100);
    const line = toPath(values, scale);
    const area = `${line} L${scale.x(data.length - 1).toFixed(1)},${scale.y(0)} L${scale.x(0).toFixed(1)},${scale.y(0)} Z`;
    const {hoverIndex, handlers} = useLineHover(data.length);
    return (
        <div className="relative h-full w-full" {...handlers}>
            <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" className="absolute inset-0 w-full h-full">
                <defs>
                    <linearGradient id="scoreAreaGrad" x1="0" y1="0" x2="0" y2="1">
                        <stop offset="0%" stopColor={ACCENT} stopOpacity={0.25}/>
                        <stop offset="100%" stopColor={ACCENT} stopOpacity={0}/>
                    </linearGradient>
                </defs>
                <Grid scale={scale}/>
                <path d={area} fill="url(#scoreAreaGrad)"/>
                <path d={line} fill="none" stroke={ACCENT} strokeWidth={2}/>
                {data.map((p, i) => (
                    <circle key={i} cx={scale.x(i)} cy={scale.y(pointValue(p))} r={3} fill={ACCENT}/>
                ))}
                {hoverIndex != null && (
                    <g>
                        <line x1={scale.x(hoverIndex)} y1={PAD_T} x2={scale.x(hoverIndex)} y2={H - PAD_B}
                              stroke="rgb(148 163 184)" strokeWidth={1} strokeDasharray="3 3"/>
                        <circle cx={scale.x(hoverIndex)} cy={scale.y(values[hoverIndex])} r={4}
                                fill={ACCENT} stroke="#fff" strokeWidth={1.5}/>
                    </g>
                )}
                <XAxis days={data.map(pointDay)} scale={scale}/>
            </svg>
            {hoverIndex != null && (
                <ChartTip
                    leftPct={(hoverIndex / Math.max(1, data.length - 1)) * 100}
                    label={dayLabel(pointDay(data[hoverIndex]))}
                    rows={[{color: ACCENT, label: '评分', value: String(values[hoverIndex])}]}
                />
            )}
        </div>
    );
}
