// 通用格式化工具（单一出处）。历史背景：formatDateTime/formatDuration/
// getDefaultTimeRange 等曾在 users、datasources、collect-tasks、history-common、
// collect history 里各复制一份，耗时还有「25 秒」「25.4s」「25.358s」三种写法。
// 现在全部收敛到这里，任何页面不得再定义本地格式化函数。

/**
 * 将毫秒格式化为人类可读耗时。秒级以下保留毫秒精度；分钟级不再显示毫秒
 * （"72m 37s 737ms" 这种过宽文本会撑破耗时列/被迫截断，分钟尺度上毫秒也无意义）。
 * - 123 -> "123ms"
 * - 1234 -> "1.234s"
 * - 65000 -> "1m 5s"
 * - 3600000 -> "60m"
 * - null / undefined -> "-"
 */
export function formatDuration(ms: number | string | null | undefined): string {
    if (ms == null) return '-';
    const n = typeof ms === 'string' ? Number(ms) : ms;
    if (Number.isNaN(n)) return '-';
    // 负数耗时（时钟漂移/数据异常）无意义，兜底显示 "-"
    if (n < 0) return '-';
    if (n < 1000) return `${n}ms`;
    const s = Math.floor(n / 1000);
    const remMs = n % 1000;
    if (s < 60) {
        return remMs === 0 ? `${s}s` : `${s}.${String(remMs).padStart(3, '0')}s`;
    }
    const m = Math.floor(s / 60);
    const rs = s % 60;
    const parts = [`${m}m`];
    if (rs > 0) parts.push(`${rs}s`);
    return parts.join(' ');
}

/**
 * 执行历史耗时展示（DAG / 同步 / 采集共用）：
 * - 已结束：优先用后端 durationMs 格式化；
 * - 否则 endTime 与 startTime 都存在：以 endTime - startTime 格式化（覆盖 TERMINATED/SUCCESS/FAILED）；
 * - 运行中（endTime 为空）且 startTime 存在：以当前时间静态计算一次
 *   （仅渲染时取值，不做定时刷新，页面上的时间不会跳动）；
 * - 其余情况 -> "-"。
 * startTime/endTime 兼容 'YYYY-MM-DD HH:mm:ss' 与 ISO 8601 两种格式。
 */
export function formatExecutionDuration(
    durationMs: number | string | null | undefined,
    startTime?: string,
    endTime?: string,
): string {
    if (durationMs != null) return formatDuration(durationMs);
    const start = parseTime(startTime);
    if (start == null) return '-';
    if (endTime) {
        const end = parseTime(endTime);
        if (end != null) return formatDuration(end - start);
    }
    return formatDuration(Date.now() - start);
}

function parseTime(value?: string): number | null {
    if (!value) return null;
    const ts = new Date(value.includes('T') ? value : value.replace(' ', 'T')).getTime();
    return Number.isNaN(ts) ? null : ts;
}

/**
 * 运行时长（CDC 管道等常驻任务）：由启动时间到当前时刻静态计算一次
 * （仅渲染时取值，不做定时刷新，页面上的时间不会跳动）。
 * 格式：X 天 X 小时 / X 小时 X 分 / X 分；不足 1 分钟显示「<1 分」；空值/非法值/未来时间 -> "-"。
 */
export function formatRunningDuration(startedAt?: string): string {
    const start = parseTime(startedAt);
    if (start == null) return '-';
    const ms = Date.now() - start;
    if (ms < 0) return '-';
    // 自适应粒度（2026-08-11 验收反馈）：先秒再分 —— 秒 → 分秒 → 小时分 → 天小时
    const totalSeconds = Math.floor(ms / 1000);
    if (totalSeconds < 60) return `${totalSeconds} 秒`;
    const totalMinutes = Math.floor(totalSeconds / 60);
    if (totalMinutes < 60) return `${totalMinutes} 分 ${totalSeconds % 60} 秒`;
    const days = Math.floor(totalMinutes / 1440);
    const hours = Math.floor((totalMinutes % 1440) / 60);
    const minutes = totalMinutes % 60;
    if (days > 0) return `${days} 天 ${hours} 小时`;
    return `${hours} 小时 ${minutes} 分`;
}

/** 吞吐量：12345 -> "1.2 万行/秒"，0.5 -> "0.50 行/秒" */
export function formatThroughput(value?: number | null): string {
    if (value === undefined || value === null) return '-';
    if (value >= 10000) return `${(value / 10000).toFixed(1)} 万行/秒`;
    if (value < 1) return `${value.toFixed(2)} 行/秒`;
    return `${value.toFixed(1)} 行/秒`;
}

/**
 * 数字千分位（Phase 6-D）：1234567 -> "1,234,567"。
 * 大数字（行数/字段数/资产数等）扫读更快，配合表格列的 tabular-nums 对齐。
 * null / undefined / NaN -> "-"。
 */
export function formatNumber(value: number | string | null | undefined): string {
    if (value === undefined || value === null || value === '') return '-';
    const n = typeof value === 'string' ? Number(value) : value;
    if (Number.isNaN(n)) return '-';
    return n.toLocaleString('zh-CN');
}

/** ISO 字符串 -> "2026-07-31 22:36:00"（本地时区），空值/非法值 -> "-" */
export function formatDateTime(value?: string): string {
    if (!value) return '-';
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return '-';
    const pad = (n: number) => String(n).padStart(2, '0');
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

/** 相对时间：1 分钟内"刚刚"，之后"N 分钟/小时/天前"，超过 7 天显示日期 */
export function formatRelativeTime(value?: string): string {
    if (!value) return '-';
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return '-';

    const now = Date.now();
    const diff = now - date.getTime();
    const seconds = Math.floor(diff / 1000);
    const minutes = Math.floor(seconds / 60);
    const hours = Math.floor(minutes / 60);
    const days = Math.floor(hours / 24);

    if (seconds < 60) return '刚刚';
    if (minutes < 60) return `${minutes} 分钟前`;
    if (hours < 24) return `${hours} 小时前`;
    if (days < 7) return `${days} 天前`;

    const pad = (n: number) => String(n).padStart(2, '0');
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

/** Date -> datetime-local input 需要的 "2026-07-31T22:36:00" */
export function formatDateTimeLocalInput(date: Date): string {
    const pad = (n: number) => String(n).padStart(2, '0');
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

/** 执行历史的默认时间范围：近 7 天（今天 23:59:59 往前 7 天的 00:00:00） */
export function getDefaultTimeRange(): { from: string; to: string } {
    const now = new Date();
    const to = new Date(now.getFullYear(), now.getMonth(), now.getDate(), 23, 59, 59);
    const from = new Date(to.getTime() - 7 * 24 * 60 * 60 * 1000);
    from.setHours(0, 0, 0, 0);
    return {from: formatDateTimeLocalInput(from), to: formatDateTimeLocalInput(to)};
}
