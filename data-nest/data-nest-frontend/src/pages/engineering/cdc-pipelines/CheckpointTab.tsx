// Sprint 9 F2：CDC 管道详情抽屉「检查点」页签。
// 健康度三卡（最近成功/平均耗时/近期失败）+ 最近 20 条 checkpoint 历史表 + 手动触发 Savepoint（回写路径 + 文件治理说明）。
// 数据：GET /{id}/checkpoints（实时转发 Flink REST，不落库；作业不可达 reachable=false）。
import {useCallback, useEffect, useState} from 'react';
import {Spin} from 'antd';
import {HiOutlineCloudArrowDown, HiOutlineShieldCheck} from 'react-icons/hi2';
import {getCdcCheckpoints, triggerCdcSavepoint} from '@/api/cdc';
import DsButton from '@/components/DsButton';
import type {CdcCheckpointHistoryItem, CdcCheckpoints} from '@/types/cdc';
import {notify} from '@/utils/notify';
import {getErrorMessage} from '@/utils/error';

/** 健康度卡 */
function HealthCard({label, value, unit, sub, danger}: {
    label: string;
    value: string;
    unit?: string;
    sub?: string;
    danger?: boolean;
}) {
    return (
        <div className="bg-ds-bg-surface border border-ds-border-subtle rounded-ds-md p-ds-3 flex-1 min-w-0">
            <div className="text-ds-nano text-ds-text-muted mb-ds-1">{label}</div>
            <div className={`text-ds-body font-bold leading-tight truncate ${danger ? 'text-ds-danger' : 'text-ds-text-primary'}`}>
                {value}
                {unit && <span className="text-ds-tiny text-ds-text-muted font-normal ml-1">{unit}</span>}
            </div>
            {sub && <div className="text-ds-nano text-ds-text-muted mt-ds-1">{sub}</div>}
        </div>
    );
}

/** 字节数 -> 可读大小 */
function formatSize(bytes?: string | number | null): string {
    if (bytes == null) return '—';
    const n = Number(bytes);
    if (Number.isNaN(n) || n <= 0) return '—';
    if (n >= 1024 * 1024 * 1024) return `${(n / 1024 / 1024 / 1024).toFixed(2)} GB`;
    if (n >= 1024 * 1024) return `${(n / 1024 / 1024).toFixed(1)} MB`;
    return `${(n / 1024).toFixed(1)} KB`;
}

/** 毫秒 -> 秒（1 位小数） */
function formatMs(ms?: string | number | null): string {
    if (ms == null) return '—';
    const n = Number(ms);
    if (Number.isNaN(n) || n < 0) return '—';
    return `${(n / 1000).toFixed(1)}s`;
}

const CP_STATUS_BADGE: Record<string, { text: string; cls: string }> = {
    COMPLETED: {text: '完成', cls: 'bg-ds-success-light text-ds-success'},
    FAILED: {text: '失败', cls: 'bg-ds-danger-light text-ds-danger'},
    IN_PROGRESS: {text: '进行中', cls: 'bg-ds-warning-light text-ds-warning'},
};

export default function CheckpointTab({pipelineId, canWrite}: { pipelineId: string; canWrite: boolean }) {
    const [data, setData] = useState<CdcCheckpoints | null>(null);
    const [loading, setLoading] = useState(false);
    const [triggering, setTriggering] = useState(false);

    const load = useCallback(() => {
        setLoading(true);
        getCdcCheckpoints(pipelineId)
            .then(d => setData(d ?? null))
            .catch(() => setData(null))
            .finally(() => setLoading(false));
    }, [pipelineId]);

    useEffect(() => {
        load();
    }, [load]);

    const handleTriggerSavepoint = async () => {
        setTriggering(true);
        try {
            const res = await triggerCdcSavepoint(pipelineId);
            notify.success(res?.savepointPath ? 'Savepoint 已触发并保存' : 'Savepoint 已触发');
            // 触发成功后回写路径已由后端完成，重拉 checkpoint 数据刷新当前 savepoint
            load();
        } catch (e) {
            notify.error(getErrorMessage(e, '触发 Savepoint 失败'));
        } finally {
            setTriggering(false);
        }
    };

    if (loading) {
        return <div className="py-ds-10 flex justify-center"><Spin/></div>;
    }

    // 作业不可达：降级提示，健康度/历史为空
    if (data && data.reachable === false) {
        return (
            <div className="flex flex-col items-center justify-center py-ds-12 text-ds-small text-ds-text-muted gap-ds-2">
                <HiOutlineShieldCheck size={32} className="text-ds-text-muted"/>
                <span>Flink 作业不可达，无法获取检查点信息（可能集群已重启或作业已丢失）。</span>
            </div>
        );
    }

    const summary = data?.summary;
    const history = data?.history ?? [];
    const latestSavepoint = data?.latestSavepointPath;

    return (
        <div className="flex flex-col h-full min-h-0">
            {/* 健康度三卡（固定区） */}
            <div className="grid grid-cols-3 gap-ds-3 mb-ds-3 flex-shrink-0">
                <HealthCard label="最近 Checkpoint"
                            value={summary?.latestCompletedTime ? summary.latestCompletedTime.slice(11) : '—'}
                            sub="Checkpoint 间隔（提交侧配置）"/>
                <HealthCard label="平均耗时"
                            value={formatMs(summary?.avgDurationMs)}
                            unit={summary?.avgDurationMs != null ? '秒' : undefined}
                            sub="近期完成均值"/>
                <HealthCard label="近期失败"
                            value={summary?.recentFailedCount != null ? String(Number(summary.recentFailedCount)) : '—'}
                            unit={summary?.recentFailedCount != null ? '次' : undefined}
                            sub="受 Flink 保留窗口限制"
                            danger={Number(summary?.recentFailedCount ?? 0) > 0}/>
            </div>

            {/* Checkpoint 历史（弹性拉伸） */}
            <div className="bg-ds-bg-surface border border-ds-border-subtle rounded-ds-md p-ds-3 flex-1 min-h-0 flex flex-col">
                <div className="flex items-center gap-ds-2 mb-ds-2 flex-shrink-0">
                    <span className="text-ds-small font-semibold text-ds-text-primary">Checkpoint 历史</span>
                    <span className="text-ds-nano text-ds-text-muted">最近 20 条 · 实时取自 Flink</span>
                </div>
                <div className="flex-1 min-h-0 overflow-auto">
                    {history.length === 0 ? (
                        <div className="h-full flex items-center justify-center text-ds-small text-ds-text-muted">
                            暂无 Checkpoint 历史
                        </div>
                    ) : (
                        <table className="w-full text-ds-small">
                            <thead>
                            <tr className="text-left text-ds-nano text-ds-text-muted">
                                <th className="py-ds-1 pr-ds-2 font-normal whitespace-nowrap">触发时间</th>
                                <th className="py-ds-1 pr-ds-2 font-normal text-right whitespace-nowrap">耗时</th>
                                <th className="py-ds-1 pr-ds-2 font-normal text-right whitespace-nowrap">大小</th>
                                <th className="py-ds-1 pr-ds-2 font-normal whitespace-nowrap">类型</th>
                                <th className="py-ds-1 font-normal whitespace-nowrap">状态</th>
                            </tr>
                            </thead>
                            <tbody>
                            {history.map((h: CdcCheckpointHistoryItem, idx) => {
                                const badge = CP_STATUS_BADGE[h.status] || {text: h.status, cls: 'bg-ds-bg-hover text-ds-text-muted'};
                                return (
                                    <tr key={idx} className="border-t border-ds-border-subtle">
                                        <td className="py-ds-1.5 pr-ds-2 text-ds-text-secondary whitespace-nowrap">{h.triggerTime || '—'}</td>
                                        <td className="py-ds-1.5 pr-ds-2 font-mono text-ds-text-secondary text-right whitespace-nowrap">{formatMs(h.durationMs)}</td>
                                        <td className="py-ds-1.5 pr-ds-2 font-mono text-ds-text-secondary text-right whitespace-nowrap">{formatSize(h.stateSizeBytes)}</td>
                                        <td className="py-ds-1.5 pr-ds-2 text-ds-text-muted whitespace-nowrap">{h.checkpointType || (h.savepoint ? 'SAVEPOINT' : 'CHECKPOINT')}</td>
                                        <td className="py-ds-1.5 whitespace-nowrap">
                                            <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-ds-badge ${badge.cls}`}>
                                                {badge.text}
                                            </span>
                                        </td>
                                    </tr>
                                );
                            })}
                            </tbody>
                        </table>
                    )}
                </div>
            </div>

            {/* Savepoint 区（固定区） */}
            <div className="mt-ds-3 flex-shrink-0 bg-ds-bg-surface border border-ds-border-subtle rounded-ds-md p-ds-3">
                <div className="flex items-center gap-ds-2 mb-ds-2">
                    <span className="text-ds-small font-semibold text-ds-text-primary">Savepoint</span>
                    <span className="text-ds-nano text-ds-text-muted">下次启动优先从该位点恢复（不丢不重）</span>
                    <div className="ml-auto">
                        <DsButton disabled={!canWrite || triggering} onClick={handleTriggerSavepoint}
                                  title={!canWrite ? '仅工程师/超管可触发' : undefined}
                                  className="!px-ds-3 !py-ds-1">
                            <HiOutlineCloudArrowDown size={14}/>
                            {triggering ? '触发中...' : '触发 Savepoint'}
                        </DsButton>
                    </div>
                </div>
                <div className="flex items-center gap-ds-2 bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm px-ds-3 py-ds-2">
                    <HiOutlineCloudArrowDown size={16} className="text-ds-accent flex-shrink-0"/>
                    <div className="min-w-0">
                        <div className="text-ds-small font-mono text-ds-text-primary truncate" title={latestSavepoint || ''}>
                            {latestSavepoint || '暂无 savepoint（启动将按启动位点重新同步）'}
                        </div>
                    </div>
                </div>
                <div className="mt-ds-2 text-ds-nano text-ds-text-muted leading-relaxed">
                    手动触发新 savepoint 后旧文件自动清理；删除管道时其 savepoint 文件一并从 MinIO 删除（清理失败不阻断删除，留痕管道日志）。
                </div>
            </div>
        </div>
    );
}
