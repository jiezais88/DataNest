/**
 * 流水线脊线（Phase 5 签名元素）：列表行首的细竖条，用状态色表达"数据在管道中流动"。
 * 颜色复用 --color-node-*（SUCCESS/FAILED/RUNNING/WAITING/SKIPPED 等），
 * 跨数据源/同步/采集/DAG/执行历史列表统一出现，形成"管道状态"记忆点。
 */
interface StatusSpineProps {
    /** 状态色（css color，如 rgb(var(--color-node-success))）；为空则不渲染 */
    color?: string;
    className?: string;
}

export default function StatusSpine({color, className = ''}: StatusSpineProps) {
    if (!color) return null;
    return (
        <span
            aria-hidden="true"
            className={`block w-[3px] h-full min-h-[26px] rounded-full ${className}`}
            style={{backgroundColor: color}}
        />
    );
}
