import {useEffect, useMemo, useState} from 'react';
import {describeCron, nextRunTimes} from '@/utils/cron';
import {formatDateTime} from '@/utils/format';

interface CronPickerProps {
    value: string;
    onChange: (cron: string) => void;
    disabled?: boolean;
}

const PRESETS: { label: string; cron: string }[] = [
    {label: '每分钟', cron: '0 * * * * ?'},
    {label: '每 5 分钟', cron: '0 0/5 * * * ?'},
    {label: '每 10 分钟', cron: '0 0/10 * * * ?'},
    {label: '每 15 分钟', cron: '0 0/15 * * * ?'},
    {label: '每 30 分钟', cron: '0 0/30 * * * ?'},
    {label: '每 1 小时', cron: '0 0 * * * ?'},
    {label: '每 2 小时', cron: '0 0 0/2 * * ?'},
    {label: '每天 0 点', cron: '0 0 0 * * ?'},
    {label: '每天凌晨 2 点', cron: '0 0 2 * * ?'},
    {label: '工作日 9 点', cron: '0 0 9 ? * MON-FRI'},
    {label: '每周一 0 点', cron: '0 0 0 ? * MON'},
    {label: '每月 1 日 0 点', cron: '0 0 0 1 * ?'},
];

type MinuteMode = 'every' | 'at' | 'interval';

function buildCron(minute: string, hour: string, day: string, month: string, week: string): string {
    const m = minute || '*';
    const h = hour || '*';
    const d = day || '*';
    const mo = month || '*';
    const w = week || '?';
    return `0 ${m} ${h} ${d} ${mo} ${w}`;
}

function parseMinuteMode(minute: string): { mode: MinuteMode; number: string } {
    if (minute === '*') return {mode: 'every', number: '0'};
    if (minute.includes('/')) return {mode: 'interval', number: minute.split('/')[1] || '5'};
    return {mode: 'at', number: minute};
}

function buildMinute(mode: MinuteMode, number: string): string {
    if (mode === 'every') return '*';
    if (mode === 'interval') return `0/${number}`;
    return number;
}

export default function CronPicker({value, onChange, disabled}: CronPickerProps) {
    const [minute, setMinute] = useState('*');
    const [minuteMode, setMinuteMode] = useState<MinuteMode>('every');
    const [minuteNumber, setMinuteNumber] = useState('0');
    const [hour, setHour] = useState('0');
    const [day, setDay] = useState('*');
    const [month, setMonth] = useState('*');
    const [week, setWeek] = useState('?');

    useEffect(() => {
        if (value) {
            const parts = value.trim().split(/\s+/);
            if (parts.length >= 5) {
                const hasSeconds = parts.length === 6;
                const m = hasSeconds ? parts[1] : parts[0];
                const h = hasSeconds ? parts[2] : parts[1];
                const d = hasSeconds ? parts[3] : parts[2];
                const mo = hasSeconds ? parts[4] : parts[3];
                const w = hasSeconds ? parts[5] : parts[4];
                const {mode, number} = parseMinuteMode(m);
                setMinute(m);
                setMinuteMode(mode);
                setMinuteNumber(number);
                setHour(h);
                setDay(d);
                setMonth(mo);
                setWeek(w);
            }
        }
    }, [value]);

    const cron = useMemo(() => buildCron(minute, hour, day, month, week), [minute, hour, day, month, week]);

    const description = useMemo(() => describeCron(cron, ''), [cron]);

    const nextRuns = useMemo(
        () => nextRunTimes(cron, 5).map((d) => formatDateTime(d.toISOString())),
        [cron],
    );

    const handlePreset = (cronStr: string) => {
        if (disabled) return;
        onChange(cronStr);
    };

    const updateMinute = (mode: MinuteMode, number: string) => {
        if (disabled) return;
        const clamped = Math.max(0, Math.min(59, Number(number) || 0));
        const validNumber = String(clamped);
        const newMinute = buildMinute(mode, validNumber);
        setMinuteMode(mode);
        setMinuteNumber(validNumber);
        setMinute(newMinute);
        onChange(buildCron(newMinute, hour, day, month, week));
    };

    const handleFieldChange = (newMinute: string, newHour: string, newDay: string, newMonth: string, newWeek: string) => {
        onChange(buildCron(newMinute, newHour, newDay, newMonth, newWeek));
    };

    const selectClass = "px-ds-2 py-ds-1 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-small text-ds-text-primary focus:outline-none focus-visible:border-ds-accent cursor-pointer min-w-[80px] disabled:opacity-60 disabled:cursor-not-allowed";
    const numberInputClass = "px-ds-2 py-ds-1 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-small text-ds-text-primary focus:outline-none focus-visible:border-ds-accent w-[72px] disabled:opacity-60 disabled:cursor-not-allowed";

    return (
        <div className="space-y-ds-3">
            {/* 预设区 */}
            <div>
                <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                    常用预设
                </label>
                <div className="flex flex-wrap gap-ds-2">
                    {PRESETS.map((p) => (
                        <button
                            key={p.cron}
                            type="button"
                            disabled={disabled}
                            onClick={() => handlePreset(p.cron)}
                            className={`px-ds-3 py-ds-1 rounded-ds-sm text-ds-small border transition-colors disabled:opacity-60 disabled:cursor-not-allowed ${
                                value === p.cron
                                    ? 'border-ds-accent bg-ds-accent-light text-ds-accent font-medium'
                                    : 'border-ds-border-subtle text-ds-text-secondary hover:border-ds-accent hover:text-ds-accent'
                            }`}
                        >
                            {p.label}
                        </button>
                    ))}
                </div>
            </div>

            {/* 自定义区 */}
            <div>
                <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                    自定义
                </label>
                <div className="flex items-center gap-ds-2 flex-wrap">
                    <div className="flex items-center gap-ds-1">
                        <select
                            value={minuteMode}
                            onChange={(e) => updateMinute(e.target.value as MinuteMode, minuteNumber)}
                            disabled={disabled}
                            className={selectClass}
                        >
                            <option value="every">每分钟</option>
                            <option value="interval">每隔</option>
                            <option value="at">第</option>
                        </select>
                        {minuteMode !== 'every' && (
                            <>
                                <input
                                    type="number"
                                    min={0}
                                    max={59}
                                    value={minuteNumber}
                                    onChange={(e) => updateMinute(minuteMode, e.target.value)}
                                    disabled={disabled}
                                    className={numberInputClass}
                                />
                                <span className="text-ds-small text-ds-text-secondary">分钟</span>
                            </>
                        )}
                    </div>
                    <select
                        value={hour}
                        onChange={(e) => {
                            if (disabled) return;
                            const v = e.target.value;
                            setHour(v);
                            handleFieldChange(minute, v, day, month, week);
                        }}
                        disabled={disabled}
                        className={selectClass}
                    >
                        <option value="*">每小时</option>
                        {Array.from({length: 24}, (_, i) => (
                            <option key={i} value={String(i)}>{i} 点</option>
                        ))}
                    </select>
                    <select
                        value={day}
                        onChange={(e) => {
                            if (disabled) return;
                            const v = e.target.value;
                            setDay(v);
                            handleFieldChange(minute, hour, v, month, week);
                        }}
                        disabled={disabled}
                        className={selectClass}
                    >
                        <option value="*">每天</option>
                        {Array.from({length: 31}, (_, i) => (
                            <option key={i + 1} value={String(i + 1)}>{i + 1} 日</option>
                        ))}
                    </select>
                    <select
                        value={month}
                        onChange={(e) => {
                            if (disabled) return;
                            const v = e.target.value;
                            setMonth(v);
                            handleFieldChange(minute, hour, day, v, week);
                        }}
                        disabled={disabled}
                        className={selectClass}
                    >
                        <option value="*">每月</option>
                        {Array.from({length: 12}, (_, i) => (
                            <option key={i + 1} value={String(i + 1)}>{i + 1} 月</option>
                        ))}
                    </select>
                    <select
                        value={week}
                        onChange={(e) => {
                            if (disabled) return;
                            const v = e.target.value;
                            setWeek(v);
                            handleFieldChange(minute, hour, day, month, v);
                        }}
                        disabled={disabled}
                        className={selectClass}
                    >
                        <option value="?">不限星期</option>
                        <option value="MON">周一</option>
                        <option value="TUE">周二</option>
                        <option value="WED">周三</option>
                        <option value="THU">周四</option>
                        <option value="FRI">周五</option>
                        <option value="SAT">周六</option>
                        <option value="SUN">周日</option>
                    </select>
                </div>
            </div>

            {/* 预览区 */}
            {value && (
                <div className="bg-ds-bg-hover rounded-ds-sm p-ds-3 space-y-ds-2">
                    <div>
                        <span className="text-ds-caption text-ds-text-muted">Cron 表达式</span>
                        <code
                            className="ml-ds-2 text-ds-small text-ds-text-primary font-mono bg-ds-bg-surface px-ds-1 py-0.5 rounded">{value}</code>
                    </div>
                    {description && (
                        <div>
                            <span className="text-ds-caption text-ds-text-muted">说明</span>
                            <span className="ml-ds-2 text-ds-small text-ds-text-primary">{description}</span>
                        </div>
                    )}
                    {nextRuns.length > 0 && (
                        <div>
                            <span className="text-ds-caption text-ds-text-muted block mb-ds-1">未来 5 次执行时间</span>
                            <ul className="space-y-0.5">
                                {nextRuns.map((r, i) => (
                                    <li key={i} className="text-ds-small text-ds-text-secondary">{r}</li>
                                ))}
                            </ul>
                        </div>
                    )}
                </div>
            )}
        </div>
    );
}
