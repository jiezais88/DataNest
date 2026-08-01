import {useEffect} from 'react';

/**
 * 条件轮询：condition 为 true 时按 interval 周期执行 callback。
 * timeout 兜底自动停止，避免 RUNNING 状态卡死时无限轮询。
 * callback 变化（如分页参数更新）会自动重启轮询。
 */
export function usePollingWhile(
    condition: boolean,
    callback: () => void,
    {interval = 5000, timeout = 60000}: { interval?: number; timeout?: number } = {},
) {
    useEffect(() => {
        if (!condition) return;
        const timer = setInterval(callback, interval);
        const stop = setTimeout(() => clearInterval(timer), timeout);
        return () => {
            clearInterval(timer);
            clearTimeout(stop);
        };
    }, [condition, callback, interval, timeout]);
}
