/**
 * 轮询工具：等待条件成立或超时
 */

export async function waitFor<T>(
    fn: () => Promise<T>,
    predicate: (v: T) => boolean,
    opts: { timeoutMs?: number; intervalMs?: number; label?: string } = {},
): Promise<T> {
    const {timeoutMs = 60_000, intervalMs = 2000, label = '条件'} = opts;
    const start = Date.now();
    let lastErr: unknown;
    while (Date.now() - start < timeoutMs) {
        try {
            const v = await fn();
            if (predicate(v)) return v;
        } catch (e) {
            lastErr = e;
        }
        await sleep(intervalMs);
    }
    const detail = lastErr ? ` 最后错误: ${String(lastErr).slice(0, 300)}` : '';
    throw new Error(`等待超时(${timeoutMs}ms): ${label}${detail}`);
}

export function sleep(ms: number): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, ms));
}
