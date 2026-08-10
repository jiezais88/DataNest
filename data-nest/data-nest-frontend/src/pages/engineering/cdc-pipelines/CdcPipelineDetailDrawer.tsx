// Sprint 8 F2 增强：CDC 管道详情抽屉（只读，全角色可见）。
// 打开时按 id 拉详情（列表行数据可能因无轮询而过期，且不含完整审计/高级配置信息）。
import {useEffect, useState} from 'react';
import {Spin} from 'antd';
import {getCdcPipeline} from '../../../api/cdc';
import Drawer from '../../../components/Drawer';
import {formatDateTime, formatRunningDuration} from '../../../utils/format';
import type {CdcPipeline} from '../../../types/cdc';
import {CdcStatusBadge, LagValue} from './shared';
import {STARTUP_MODE_LABEL, SYNC_MODE_LABEL, WRITE_MODE_LABEL} from './constants';

/** 分区容器 */
function Section({title, children}: { title: string; children: React.ReactNode }) {
    return (
        <div className="mb-ds-6 last:mb-0">
            <div
                className="text-ds-small font-semibold text-ds-text-primary mb-ds-3 pb-ds-2 border-b border-ds-border-subtle">
                {title}
            </div>
            {children}
        </div>
    );
}

/** 键值行 */
function Row({label, mono, children}: { label: string; mono?: boolean; children: React.ReactNode }) {
    return (
        <div className="flex items-start gap-ds-4 py-ds-1">
            <span className="w-24 flex-shrink-0 text-ds-tiny text-ds-text-muted pt-0.5">{label}</span>
            <span className={`text-ds-small text-ds-text-secondary break-all min-w-0 ${mono ? 'font-mono' : ''}`}>
                {children}
            </span>
        </div>
    );
}

/** 解析 configJson 高级配置（非法 JSON 兜底空对象，键缺失走「默认」展示） */
function parseAdvancedConfig(configJson?: string): { parallelism?: number; checkpointIntervalSeconds?: number } {
    if (!configJson) return {};
    try {
        const cfg = JSON.parse(configJson) as Record<string, unknown>;
        return {
            parallelism: typeof cfg.parallelism === 'number' ? cfg.parallelism : undefined,
            checkpointIntervalSeconds: typeof cfg.checkpointIntervalSeconds === 'number'
                ? cfg.checkpointIntervalSeconds : undefined,
        };
    } catch {
        return {};
    }
}

interface CdcPipelineDetailDrawerProps {
    pipelineId: string | null;
    onClose: () => void;
}

export default function CdcPipelineDetailDrawer({pipelineId, onClose}: CdcPipelineDetailDrawerProps) {
    const [detail, setDetail] = useState<CdcPipeline | null>(null);
    const [loading, setLoading] = useState(false);

    useEffect(() => {
        if (!pipelineId) {
            setDetail(null);
            return;
        }
        setLoading(true);
        getCdcPipeline(pipelineId)
            .then(p => setDetail(p ?? null))
            .catch(() => setDetail(null))
            .finally(() => setLoading(false));
    }, [pipelineId]);

    const advanced = parseAdvancedConfig(detail?.configJson);

    return (
        <Drawer
            open={!!pipelineId}
            onClose={onClose}
            title={detail ? `管道详情 · ${detail.name}` : '管道详情'}
            width="max-w-[640px]"
        >
            {loading || !detail ? (
                <div className="py-ds-10 flex justify-center"><Spin/></div>
            ) : (
                <>
                    <Section title="基本信息">
                        <Row label="名称">{detail.name || '—'}</Row>
                        <Row label="描述">{detail.description || '—'}</Row>
                        <Row label="创建人">{detail.createdByName || '—'}</Row>
                        <Row label="创建时间">{formatDateTime(detail.createdAt)}</Row>
                        <Row label="修改人">{detail.updatedByName || '—'}</Row>
                        <Row label="修改时间">{formatDateTime(detail.updatedAt)}</Row>
                    </Section>

                    <Section title="源配置">
                        <Row label="数据源">{detail.sourceDatasourceName || '—'}</Row>
                        <Row label="源库" mono>{detail.sourceDatabase || '—'}</Row>
                        <Row label="同步模式">{SYNC_MODE_LABEL[detail.syncMode] ?? detail.syncMode}</Row>
                        <Row label="启动位点">{STARTUP_MODE_LABEL[detail.startupMode] ?? detail.startupMode}</Row>
                    </Section>

                    <Section title="目标配置">
                        <Row label="目标库" mono>{detail.targetDatabase || '—'}</Row>
                        <Row label="写入模式">{WRITE_MODE_LABEL[detail.writeMode] ?? detail.writeMode}</Row>
                        <Row label="表映射">
                            <span className="flex flex-col gap-ds-1">
                                {(detail.tables ?? []).map(t => (
                                    <span key={t.sourceTable}>
                                        {detail.sourceDatabase}.{t.sourceTable} → {detail.targetDatabase}.
                                        {t.targetTable?.trim() || t.sourceTable}
                                        <span className="text-ds-text-muted">（主键：{t.primaryKey?.trim() || '—'}）</span>
                                    </span>
                                ))}
                                {(detail.tables ?? []).length === 0 && '—'}
                            </span>
                        </Row>
                    </Section>

                    <Section title="运行状态">
                        <Row label="状态"><CdcStatusBadge status={detail.status}/></Row>
                        <Row label="当前延迟"><LagValue seconds={detail.currentLagSeconds}/></Row>
                        <Row label="累计变更" mono>
                            {detail.totalChanges == null ? '—' : Number(detail.totalChanges).toLocaleString()}
                        </Row>
                        <Row label="运行时长">
                            {detail.status === 'RUNNING' && detail.startedAt ? (
                                <span title={`启动时间：${formatDateTime(detail.startedAt)}`}>
                                    {formatRunningDuration(detail.startedAt)}
                                </span>
                            ) : '—'}
                        </Row>
                        <Row label="Flink Job ID" mono>{detail.flinkJobId || '—'}</Row>
                        <Row label="Savepoint" mono>{detail.savepointPath || '—'}</Row>
                    </Section>

                    <Section title="高级配置">
                        <Row label="并行度">
                            {advanced.parallelism != null ? String(advanced.parallelism) : '默认'}
                        </Row>
                        <Row label="Checkpoint 间隔">
                            {advanced.checkpointIntervalSeconds != null
                                ? `${advanced.checkpointIntervalSeconds} 秒` : '默认'}
                        </Row>
                    </Section>
                </>
            )}
        </Drawer>
    );
}
