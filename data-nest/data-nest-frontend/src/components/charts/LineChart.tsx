// Sprint 9：通用 SVG 折线/面积图组件（项目无图表库，沿用 quality-report/charts.tsx 手写 SVG 模式）。
// 能力：多系列（折线/面积）、null 值断点跳空（不插值造假）、超阈值区段标红、Y 轴刻度、X 轴标签采样、图例；
// hover 十字线 + 浮动数值卡（2026-08-13 增，质量报告/API 统计/CDC 监控共用）。
// 全部用 viewBox + preserveAspectRatio 自适应卡片宽度。
import {useState} from 'react';
import type {ReactNode} from 'react';

const W = 640;
const H = 170;
const PAD_L = 40;
const PAD_R = 14;
const PAD_T = 12;
const PAD_B = 26;

/** 单个系列配置 */
export interface LineSeries {
    key: string;
    label: string;
    /** 系列颜色（SVG 可直接使用的颜色值） */
    color: string;
    /** 取点函数：从数据项提取数值；返回 null 表示该点无数据（断点） */
    value: (item: unknown, index: number) => number | null;
    /** 是否为面积图（默认折线） */
    area?: boolean;
    /** 阈值标红：大于该值的点以 danger 色渲染线段 */
    threshold?: number;
    /** 阈值标红颜色（默认 danger） */
    thresholdColor?: string;
}

interface LineChartProps {
    /** 数据项（定长数组；取点函数按索引对齐） */
    data: unknown[];
    series: LineSeries[];
    /** X 轴标签函数（按索引返回展示文案） */
    xLabel: (index: number) => string;
    /** 图例（默认渲染 series 图例） */
    legend?: ReactNode;
    /** 空数据提示（series 全部无值时展示） */
    emptyText?: string;
    /** 统一 Y 轴最大值（如阈值需纳入刻度） */
    maxY?: number;
    /** 图表区保底高度（自适应网格场景防塌陷，默认 100） */
    minHeight?: number;
}

interface Scale {
    x: (i: number) => number;
    y: (v: number) => number;
    ticks: number[];
    niceMax: number;
}

function computeScale(count: number, maxValue: number): Scale {
    const max = Math.max(4, maxValue);
    const tick = Math.ceil(max / 4);
    const niceMax = tick * 4;
    return {
        x: (i) => PAD_L + (count <= 1 ? 0 : (i / (count - 1)) * (W - PAD_L - PAD_R)),
        y: (v) => PAD_T + (1 - v / niceMax) * (H - PAD_T - PAD_B),
        ticks: [0, 1, 2, 3, 4].map(i => i * tick),
        niceMax,
    };
}

/** Y 轴刻度文本：大数缩写（≥1000 → 1k），避免长数字贴边溢出 */
function formatTick(v: number): string {
    if (v >= 10000) return `${(v / 1000).toFixed(1)}k`;
    if (v >= 1000) return `${Math.round(v / 1000)}k`;
    return String(v);
}

/** 坐标网格线（含左缘 Y 轴基线；刻度文字改 HTML 渲染，避免 preserveAspectRatio 拉伸变形） */
function Grid({scale}: { scale: Scale }) {
    return (
        <>
            {/* Y 轴基线（数据区左缘） */}
            <line x1={PAD_L} y1={PAD_T} x2={PAD_L} y2={H - PAD_B} stroke="rgb(203 213 225)" strokeWidth={1}/>
            {scale.ticks.map((t) => (
                <line key={t} x1={PAD_L} y1={scale.y(t)} x2={W - PAD_R} y2={scale.y(t)}
                      stroke="rgb(226 232 240)" strokeWidth={1}/>
            ))}
        </>
    );
}

/**
 * 生成单条路径，null 值断点跳空（不插值造假）。
 * 返回 { path, segments }：segments 为连续非空点段（供阈值标红/面积填充复用）。
 */
function buildSegments(values: (number | null)[], scale: Scale) {
    const segments: { start: number; end: number }[] = [];
    let start = -1;
    values.forEach((v, i) => {
        if (v == null) {
            if (start >= 0) {
                segments.push({start, end: i - 1});
                start = -1;
            }
        } else if (start < 0) {
            start = i;
        }
    });
    if (start >= 0) segments.push({start, end: values.length - 1});

    const pathFor = (seg: { start: number; end: number }) =>
        values.slice(seg.start, seg.end + 1)
            .map((v, j) => {
                const i = seg.start + j;
                return `${j === 0 ? 'M' : 'L'}${scale.x(i).toFixed(1)},${scale.y(v!).toFixed(1)}`;
            })
            .join(' ');
    return {segments, pathFor};
}

/** X 轴基线（数据区底部；日期文字改 HTML 渲染，避免拉伸变形） */
function XAxis() {
    return <line x1={PAD_L} y1={H - PAD_B} x2={W - PAD_R} y2={H - PAD_B} stroke="rgb(203 213 225)" strokeWidth={1}/>;
}

/** hover 浮动数值卡（折线图十字线跟随；x 越界自动内收防溢出卡片） */
export function ChartTip({leftPct, label, rows}: {
    leftPct: number;
    label: string;
    rows: { color: string; label: string; value: string }[];
}) {
    const left = Math.max(16, Math.min(84, leftPct));
    return (
        <div className="absolute top-1 z-10 pointer-events-none -translate-x-1/2 bg-ds-bg-surface border border-ds-border-subtle rounded-ds-sm shadow-lg px-ds-2 py-ds-1.5 min-w-[110px]"
             style={{left: `${left}%`}}>
            <div className="text-ds-tiny text-ds-text-muted whitespace-nowrap">{label}</div>
            {rows.map(r => (
                <div key={r.label} className="flex items-center gap-1.5 text-ds-tiny mt-0.5 whitespace-nowrap">
                    <span className="w-1.5 h-1.5 rounded-full flex-shrink-0" style={{background: r.color}}/>
                    <span className="text-ds-text-secondary">{r.label}</span>
                    <span className="font-mono font-semibold text-ds-text-primary ml-auto pl-2">{r.value}</span>
                </div>
            ))}
        </div>
    );
}

export default function LineChart({data, series, xLabel, legend, emptyText, maxY, minHeight = 100}: LineChartProps) {
    const valuesBySeries = series.map(s => data.map((item, i) => s.value(item, i)));
    /** hover 数据点下标（十字线 + 浮动数值卡） */
    const [hoverIndex, setHoverIndex] = useState<number | null>(null);

    // 是否有任何有效值（无则显示空态）
    const hasAny = valuesBySeries.some(vals => vals.some(v => v != null));

    // Y 轴最大值：取所有系列最大值与 maxY（阈值）的较大者
    const dataMax = Math.max(0, ...valuesBySeries.flat().map(v => (v == null ? 0 : v)));
    const yMax = maxY != null ? Math.max(dataMax, maxY) : dataMax;
    const scale = computeScale(data.length, yMax);

    return (
        <div className="h-full flex flex-col">
            {legend != null && (
                <div className="flex items-center gap-ds-3 mb-ds-1 flex-shrink-0">{legend}</div>
            )}
            {!hasAny ? (
                <div className="h-full flex items-center justify-center text-ds-small text-ds-text-muted">
                    {emptyText || '范围内暂无数据'}
                </div>
            ) : (
                <div
                    className="relative flex-1 min-h-0"
                    style={{minHeight}}
                    onMouseMove={(e) => {
                        const rect = e.currentTarget.getBoundingClientRect();
                        const ratio = rect.width > 0 ? (e.clientX - rect.left) / rect.width : 0;
                        const idx = Math.round(ratio * (data.length - 1));
                        setHoverIndex(Math.max(0, Math.min(data.length - 1, idx)));
                    }}
                    onMouseLeave={() => setHoverIndex(null)}
                >
                    <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none"
                         className="absolute inset-0 w-full h-full">
                        <Grid scale={scale}/>
                        {series.map((s, si) => {
                            const values = valuesBySeries[si];
                            const {segments, pathFor} = buildSegments(values, scale);
                            return (
                                <g key={s.key}>
                                    {/* 面积填充 */}
                                    {s.area && segments.map((seg, j) => (
                                        <path key={j}
                                              d={`${pathFor(seg)} L${scale.x(seg.end).toFixed(1)},${scale.y(0)} L${scale.x(seg.start).toFixed(1)},${scale.y(0)} Z`}
                                              fill={s.color} opacity={0.18}/>
                                    ))}
                                    {/* 折线主体 */}
                                    {segments.map((seg, j) => (
                                        <path key={j} d={pathFor(seg)} fill="none"
                                              stroke={s.color} strokeWidth={2}/>
                                    ))}
                                    {/* 阈值标红：超过阈值的点之间线段用阈值色 */}
                                    {s.threshold != null && (() => {
                                        const thrSegs: { start: number; end: number }[] = [];
                                        let start = -1;
                                        values.forEach((v, i) => {
                                            const over = v != null && v > s.threshold!;
                                            if (over && start < 0) start = i;
                                            if (!over && start >= 0) {
                                                thrSegs.push({start, end: i - 1});
                                                start = -1;
                                            }
                                        });
                                        if (start >= 0) thrSegs.push({start, end: values.length - 1});
                                        return thrSegs.map((seg, j) => (
                                            <path key={j} d={pathFor(seg)} fill="none"
                                                  stroke={s.thresholdColor || 'rgb(var(--color-danger))'} strokeWidth={2}/>
                                        ));
                                    })()}
                                </g>
                            );
                        })}
                        {/* hover 十字线 + 各系列数据点 */}
                        {hoverIndex != null && (
                            <g>
                                <line x1={scale.x(hoverIndex)} y1={PAD_T} x2={scale.x(hoverIndex)} y2={H - PAD_B}
                                      stroke="rgb(148 163 184)" strokeWidth={1} strokeDasharray="3 3"/>
                                {series.map((s, si) => {
                                    const v = valuesBySeries[si][hoverIndex];
                                    return v != null ? (
                                        <circle key={s.key} cx={scale.x(hoverIndex)} cy={scale.y(v)} r={4}
                                                fill={s.color} stroke="#fff" strokeWidth={1.5}/>
                                    ) : null;
                                })}
                            </g>
                        )}
                        <XAxis/>
                    </svg>
                    {/* 轴文字改 HTML 绝对定位（preserveAspectRatio="none" 会非等比拉伸 SVG 文字；HTML 文字按容器百分比对齐不拉伸） */}
                    {scale.ticks.map((t) => (
                        <span key={t} className="absolute leading-none text-ds-text-muted pointer-events-none select-none"
                              style={{fontSize: 10, left: 0, width: `${(PAD_L / W) * 100}%`, top: `${(scale.y(t) / H) * 100}%`,
                                      transform: 'translateY(-50%)', textAlign: 'right', paddingRight: 8, boxSizing: 'border-box'}}>
                            {formatTick(t)}
                        </span>
                    ))}
                    {(() => {
                        const step = Math.max(1, Math.ceil(data.length / 7));
                        return Array.from({length: data.length}).map((_, i) => (i % step === 0 || i === data.length - 1) && (
                            <span key={i} className="absolute leading-none text-ds-text-muted pointer-events-none select-none whitespace-nowrap"
                                  style={{fontSize: 10, left: `${(scale.x(i) / W) * 100}%`, top: `${((H - PAD_B + 5) / H) * 100}%`,
                                          transform: 'translateX(-50%)'}}>
                                {xLabel(i)}
                            </span>
                        ));
                    })()}
                    {hoverIndex != null && (
                        <ChartTip
                            leftPct={(hoverIndex / Math.max(1, data.length - 1)) * 100}
                            label={xLabel(hoverIndex)}
                            rows={series.map((s, si) => {
                                const v = valuesBySeries[si][hoverIndex];
                                return {color: s.color, label: s.label, value: v == null ? '—' : String(v)};
                            })}
                        />
                    )}
                </div>
            )}
        </div>
    );
}
