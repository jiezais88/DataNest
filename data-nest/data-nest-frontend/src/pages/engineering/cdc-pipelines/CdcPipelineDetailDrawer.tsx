// Sprint 8 F2 增强：CDC 管道详情抽屉（只读，全角色可见）。
// Sprint 9 扩展：改为多页签 —— 基本信息（Sprint 8 现有）+ 运行监控（F1）+ 检查点（F2）。
// 打开时按 id 拉详情（列表行数据可能因无轮询而过期，且不含完整审计/高级配置信息）。
import {useEffect, useState} from 'react';
import {Spin} from 'antd';
import {getCdcPipeline} from '@/api/cdc';
import Drawer from '@/components/Drawer';
import {useHasRole} from '@/hooks/useHasRole';
import {ENGINEERING_WRITE_ROLES} from '@/constants/roles';
import {formatDateTime, formatRunningDuration} from '@/utils/format';
import type {CdcPipeline} from '@/types/cdc';
import {CdcStatusBadge, LagValue} from './shared';
import {STARTUP_MODE_LABEL, SYNC_MODE_LABEL, WRITE_MODE_LABEL} from './constants';
import MonitoringTab from './MonitoringTab';
import CheckpointTab from './CheckpointTab';

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
function parseAdvancedConfig(configJson?: string): {
    parallelism?: number;
    checkpointIntervalSeconds?: number;
    schemaChangeBehavior?: string;
    scanChunkSize?: number;
} {
    if (!configJson) return {};
    try {
        const cfg = JSON.parse(configJson) as Record<string, unknown>;
        return {
            parallelism: typeof cfg.parallelism === 'number' ? cfg.parallelism : undefined,
            checkpointIntervalSeconds: typeof cfg.checkpointIntervalSeconds === 'number'
                ? cfg.checkpointIntervalSeconds : undefined,
            schemaChangeBehavior: typeof cfg.schemaChangeBehavior === 'string'
                ? cfg.schemaChangeBehavior : undefined,
            scanChunkSize: typeof cfg.scanChunkSize === 'number' ? cfg.scanChunkSize : undefined,
        };
    } catch {
        return {};
    }
}

/** 基本信息页签（Sprint 8 原内容） */
function BasicInfoTab({detail}: { detail: CdcPipeline }) {
    const advanced = parseAdvancedConfig(detail.configJson);
    return (
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
                <Row label="表结构变更策略">
                    {advanced.schemaChangeBehavior ?? '默认（EVOLVE）'}
                </Row>
                <Row label="快照分块大小">
                    {advanced.scanChunkSize != null ? String(advanced.scanChunkSize) : '默认'}
                </Row>
            </Section>
        </>
    );
}

/** 页签定义 */
const TABS = [
    {key: 'basic', label: '基本信息'},
    {key: 'monitor', label: '运行监控'},
    {key: 'checkpoint', label: '检查点'},
] as const;

interface CdcPipelineDetailDrawerProps {
    pipelineId: string | null;
    onClose: () => void;
}

export default function CdcPipelineDetailDrawer({pipelineId, onClose}: CdcPipelineDetailDrawerProps) {
    const [detail, setDetail] = useState<CdcPipeline | null>(null);
    const [loading, setLoading] = useState(false);
    const [activeTab, setActiveTab] = useState<(typeof TABS)[number]['key']>('basic');
    const canWrite = useHasRole(...ENGINEERING_WRITE_ROLES);

    useEffect(() => {
        if (!pipelineId) {
            setDetail(null);
            setActiveTab('basic');
            return;
        }
        setLoading(true);
        getCdcPipeline(pipelineId)
            .then(p => setDetail(p ?? null))
            .catch(() => setDetail(null))
            .finally(() => setLoading(false));
    }, [pipelineId]);

    return (
        <Drawer
            open={!!pipelineId}
            onClose={onClose}
            title={detail ? `管道详情 · ${detail.name}` : '管道详情'}
            width="max-w-[720px]"
        >
            {loading || !detail ? (
                <div className="py-ds-10 flex justify-center"><Spin/></div>
            ) : (
                <div className="flex flex-col h-full min-h-0">
                    {/* 页签导航（固定区） */}
                    <div className="flex items-center gap-ds-1 border-b border-ds-border-subtle mb-ds-4 flex-shrink-0">
                        {TABS.map(t => (
                            <button
                                key={t.key}
                                type="button"
                                onClick={() => setActiveTab(t.key)}
                                className={`px-ds-3 py-ds-2 text-ds-small rounded-t-ds-sm transition-colors border-b-2 -mb-px ${
                                    activeTab === t.key
                                        ? 'text-ds-accent font-semibold border-ds-accent'
                                        : 'text-ds-text-secondary hover:text-ds-text-primary border-transparent'
                                }`}
                            >
                                {t.label}
                                {t.key !== 'basic' && (
                                    <span className="ml-ds-1 text-ds-nano text-ds-accent">新</span>
                                )}
                            </button>
                        ))}
                    </div>

                    {/* 页签内容区（弹性填充） */}
                    {activeTab === 'basic' && (
                        <div className="overflow-auto flex-1 min-h-0">
                            <BasicInfoTab detail={detail}/>
                        </div>
                    )}
                    {activeTab === 'monitor' && (
                        <div className="flex-1 min-h-0">
                            <MonitoringTab pipelineId={detail.id}/>
                        </div>
                    )}
                    {activeTab === 'checkpoint' && (
                        <div className="flex-1 min-h-0">
                            <CheckpointTab pipelineId={detail.id} canWrite={canWrite}/>
                        </div>
                    )}
                </div>
            )}
        </Drawer>
    );
}
