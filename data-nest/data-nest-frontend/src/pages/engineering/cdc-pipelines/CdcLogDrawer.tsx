// Sprint 8 F2：CDC 管道运行日志抽屉。
// 运行中的管道每 5s 自动刷新（usePollingWhile）；「清屏」仅清当前视图并暂停自动刷新，点刷新恢复。
import {useCallback, useEffect, useState} from 'react';
import {HiOutlineArrowPath, HiOutlineTrash} from 'react-icons/hi2';
import {getCdcPipelineLogs} from '@/api/cdc';
import Drawer from '@/components/Drawer';
import DsButton from '@/components/DsButton';
import DsStatusBadge from '@/components/DsStatusBadge';
import Pagination from '@/components/Pagination';
import usePagedList from '@/hooks/usePagedList';
import {usePollingWhile} from '@/hooks/usePollingWhile';
import {formatDateTime} from '@/utils/format';
import type {CdcPipeline, CdcPipelineLog} from '@/types/cdc';

/** 日志级别 → 徽章变体 */
function levelVariant(level?: string): 'success' | 'warning' | 'danger' | 'pending' {
    if (level === 'ERROR') return 'danger';
    if (level === 'WARN') return 'warning';
    return 'pending';
}

interface CdcLogDrawerProps {
    pipeline: CdcPipeline | null;
    onClose: () => void;
}

export default function CdcLogDrawer({pipeline, onClose}: CdcLogDrawerProps) {
    const pipelineId = pipeline?.id;
    // 清屏：清空当前视图并暂停自动刷新（日志是 DB 记录，清屏只影响前端视图）
    const [cleared, setCleared] = useState(false);

    const {list, total, page, pageSize, loading, setPage, setPageSize, applyQuery, reload} =
        usePagedList<Record<string, never>, CdcPipelineLog>({
            fetcher: ({page: p, pageSize: ps}) => {
                if (!pipelineId || cleared) return Promise.resolve({list: [], total: 0});
                return getCdcPipelineLogs(pipelineId, p, ps)
                    .then(r => ({list: r?.records ?? [], total: Number(r?.total ?? 0)}));
            },
            initialQuery: {},
        });

    // 组件常驻挂载（Drawer 由 open 控制显隐），usePagedList 只在 mount 首跑；
    // 打开抽屉（pipeline 切换）时清屏位复位 + applyQuery 重置回第 1 页并重拉
    // （不能只 reload：page 跨管道保留，日志少的管道会请求到空页显示假「暂无日志」）
    useEffect(() => {
        if (pipelineId) {
            setCleared(false);
            applyQuery({});
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [pipelineId]);

    // 运行中且未清屏时自动刷新（5s 周期，10 分钟兜底自动停止）
    usePollingWhile(!!pipeline && pipeline.status === 'RUNNING' && !cleared, reload, {
        interval: 5000,
        timeout: 600000,
    });

    const handleRefresh = useCallback(() => {
        setCleared(false);
        reload();
    }, [reload]);

    return (
        <Drawer
            open={!!pipeline}
            onClose={onClose}
            title={(
                <span className="flex items-center gap-ds-2">
                    {pipeline?.name} · 运行日志
                    {pipeline && (
                        <DsStatusBadge
                            variant={pipeline.status === 'RUNNING' ? 'running' : pipeline.status === 'ERROR' ? 'danger' : 'pending'}
                            label={pipeline.status === 'RUNNING' ? '运行中' : pipeline.status === 'ERROR' ? '异常' : '已停止'}
                        />
                    )}
                </span>
            )}
            width="max-w-[720px]"
            extra={(
                <div className="flex items-center gap-ds-2">
                    {pipeline?.status === 'RUNNING' && !cleared && (
                        <span className="text-ds-tiny text-ds-text-muted">自动刷新（5s）</span>
                    )}
                    <DsButton variant="secondary" onClick={handleRefresh} disabled={loading}>
                        <HiOutlineArrowPath size={14}/>
                        刷新
                    </DsButton>
                    <DsButton variant="ghost" onClick={() => setCleared(true)}>
                        <HiOutlineTrash size={14}/>
                        清屏
                    </DsButton>
                </div>
            )}
        >
            {cleared ? (
                <div className="py-ds-10 text-center text-ds-small text-ds-text-muted">
                    已清屏（仅影响当前视图），点「刷新」重新加载
                </div>
            ) : list.length === 0 && !loading ? (
                <div className="py-ds-10 text-center text-ds-small text-ds-text-muted">暂无日志</div>
            ) : (
                <div className="flex flex-col">
                    {list.map(log => (
                        <div key={log.id}
                             className="flex items-start gap-ds-3 py-ds-2 border-b border-ds-border-subtle last:border-b-0">
                            <span className="text-ds-tiny text-ds-text-muted whitespace-nowrap pt-0.5 font-mono">
                                {formatDateTime(log.createdAt)}
                            </span>
                            <span className="flex-shrink-0 pt-0.5">
                                <DsStatusBadge variant={levelVariant(log.level)} label={log.level || 'INFO'}/>
                            </span>
                            <span
                                className={`text-ds-small break-all ${log.level === 'ERROR' ? 'text-ds-danger' : 'text-ds-text-secondary'}`}>
                                {log.message}
                            </span>
                        </div>
                    ))}
                </div>
            )}
            {!cleared && total > 0 && (
                <Pagination
                    page={page}
                    pageSize={pageSize}
                    total={total}
                    onChange={(p, s) => {
                        setPage(p);
                        if (s !== pageSize) setPageSize(s);
                    }}
                />
            )}
        </Drawer>
    );
}
