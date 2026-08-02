// Sprint 4 节点实时日志面板（PRD §6.5.1 / 技术文档 §8.4）
// 执行详情画布（只读运行视图）选中 SQL/PYTHON 节点后展示：
// - 深色日志框，行格式 `HH:mm:ss [LEVEL] message`，INFO 蓝 / ERROR 红
// - 节点 RUNNING 时每 3 秒轮询一次；终态只加载一次
// SYNC 节点不走这里：复用同步任务日志 HistoryLogModal（PropertyPanel 的「查看日志」按钮）。
import {useCallback, useEffect, useState} from 'react';
import {Spin} from 'antd';
import {getNodeRuntimeLogs} from '../api';
import type {NodeExecutionLog} from '../types';
import {usePollingWhile} from '../../../../hooks/usePollingWhile';

interface NodeRuntimeLogPanelProps {
    executionId: string | number;
    nodeId: string;
    /** 节点状态：RUNNING 时开启 3s 轮询 */
    status?: string;
}

// RUNNING 轮询兜底 30 分钟（usePollingWhile 默认 60s 会在长任务上提前停轮询）
const RUNNING_POLL_TIMEOUT = 30 * 60 * 1000;

function formatLogTime(createdAt?: string): string {
    if (!createdAt) return '--:--:--';
    // createdAt 为 ISO 无时区（后端 LocalDateTime）：直接取时间部分，避免时区换算偏差
    const t = createdAt.includes('T') ? createdAt.split('T')[1] : createdAt.split(' ')[1];
    return (t || '').slice(0, 8) || '--:--:--';
}

export default function NodeRuntimeLogPanel({executionId, nodeId, status}: NodeRuntimeLogPanelProps) {
    const [logs, setLogs] = useState<NodeExecutionLog[]>([]);
    const [loading, setLoading] = useState(false);

    const load = useCallback(() => {
        return getNodeRuntimeLogs(executionId, nodeId)
            .then(list => setLogs(list || []))
            .catch(() => {
                // 轮询场景不打扰用户（拦截器已统一提示首次失败）；保持上次内容
            });
    }, [executionId, nodeId]);

    // 节点切换时重置并立即加载一次
    useEffect(() => {
        setLogs([]);
        setLoading(true);
        void load().finally(() => setLoading(false));
    }, [load]);

    // RUNNING 时每 3 秒自动刷新（PRD §6.5.1）
    usePollingWhile(status === 'RUNNING', load, {interval: 3000, timeout: RUNNING_POLL_TIMEOUT});

    return (
        <div className="mt-ds-3">
            <div className="text-ds-caption text-ds-text-muted font-bold uppercase tracking-wider mb-ds-1">
                实时日志
            </div>
            <div className="bg-[#1e293b] rounded-ds-sm px-ds-3 py-ds-2 max-h-[200px] overflow-auto">
                {loading ? (
                    <div className="flex items-center gap-ds-2 py-ds-2 text-ds-caption text-[#94a3b8]">
                        <Spin size="small"/> 加载日志中...
                    </div>
                ) : logs.length === 0 ? (
                    <div className="text-ds-caption text-[#64748b] py-ds-2">暂无日志</div>
                ) : (
                    logs.map(log => (
                        <div key={log.id ?? `${log.lineNum}-${log.createdAt}`}
                             className="text-ds-nano font-mono leading-relaxed whitespace-pre-wrap break-all">
                            <span className="text-[#64748b]">{formatLogTime(log.createdAt)} </span>
                            <span className={log.level === 'ERROR' ? 'text-[#f87171]' : 'text-[#38bdf8]'}>
                                [{log.level || 'INFO'}]
                            </span>
                            <span className="text-[#e2e8f0]"> {log.message}</span>
                        </div>
                    ))
                )}
            </div>
            {status === 'RUNNING' && (
                <div className="mt-ds-1 text-ds-nano text-ds-text-muted">
                    RUNNING 状态时每 3 秒自动刷新
                </div>
            )}
        </div>
    );
}
