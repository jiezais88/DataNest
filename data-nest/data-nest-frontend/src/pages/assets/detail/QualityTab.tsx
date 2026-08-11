// Sprint 7 F1：资产详情页「质量」页签（DC-04）
// 评分概览 + 规则最近结果表格 + 「立即执行全部规则」（治理员/超管）。
// 列定义对齐质量评分页详情弹窗；执行为异步投递，触发后延迟自动刷新。
import {useCallback, useEffect, useRef, useState} from 'react';
import {Table} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import {executeTableQualityRules, getQualityScoreByTable, getTableQualityRuleResults} from '@/api/quality';
import DsButton from '@/components/DsButton';
import DsStatusBadge from '@/components/DsStatusBadge';
import type {DsStatusVariant} from '@/components/DsStatusBadge';
import DsTableEmpty from '@/components/DsTableEmpty';
import QualityScoreBadge from '@/components/QualityScoreBadge';
import {COL} from '@/constants/table';
import {formatDateTime} from '@/utils/format';
import {notify} from '@/utils/notify';
import {
    QUALITY_CHECK_LEVEL_LABEL,
    QUALITY_TYPE_LABEL,
} from '@/types/quality';
import type {QualityCheckLevel, QualityScore, QualityTableRuleResult} from '@/types/quality';

/** 规则分级判定 -> 徽章变体（对齐质量评分页） */
const LEVEL_VARIANT: Record<QualityCheckLevel, DsStatusVariant> = {
    PASS: 'success',
    WARNING: 'warning',
    SEVERE: 'danger',
    UNAVAILABLE: 'pending',
};

interface QualityTabProps {
    tableId: string;
    canWrite: boolean;
}

export default function QualityTab({tableId, canWrite}: QualityTabProps) {
    const [score, setScore] = useState<QualityScore | null>(null);
    const [rules, setRules] = useState<QualityTableRuleResult[]>([]);
    const [loading, setLoading] = useState(false);
    const [executing, setExecuting] = useState(false);

    const load = useCallback(() => {
        setLoading(true);
        Promise.all([
            getQualityScoreByTable(tableId),
            getTableQualityRuleResults(tableId),
        ])
            .then(([scoreRes, ruleRes]) => {
                setScore(scoreRes.data ?? null);
                setRules(ruleRes.data ?? []);
            })
            .catch(() => {
                setScore(null);
                setRules([]);
            })
            .finally(() => setLoading(false));
    }, [tableId]);

    useEffect(() => {
        load();
    }, [load]);

    // 延迟刷新定时器（执行是异步投递，触发后 5s 自动拉新结果）；卸载时清理
    const refreshTimerRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
    useEffect(() => {
        return () => {
            if (refreshTimerRef.current) clearTimeout(refreshTimerRef.current);
        };
    }, []);

    const handleExecute = async () => {
        setExecuting(true);
        try {
            await executeTableQualityRules(tableId);
            notify.success('已触发执行，全部启用规则已提交到执行节点，稍后自动刷新结果');
            refreshTimerRef.current = setTimeout(load, 5000);
        } catch {
            // 错误提示由拦截器统一弹出
        } finally {
            setExecuting(false);
        }
    };

    const columns: ColumnsType<QualityTableRuleResult> = [
        {
            title: '规则名称',
            dataIndex: 'ruleName',
            ellipsis: true,
            width: COL.NAME_COMPACT,
            render: (v?: string, r?: QualityTableRuleResult) => (
                <div>
                    <div className="text-ds-small text-ds-text-primary font-medium">{v || '—'}</div>
                    {r?.jobName && (
                        <div className="text-ds-tiny text-ds-text-muted truncate">{r.jobName}</div>
                    )}
                </div>
            ),
        },
        {
            title: '类型',
            dataIndex: 'ruleType',
            width: COL.STATUS,
            render: (v?: string) => (
                <span className="text-ds-small text-ds-text-secondary whitespace-nowrap">
                    {v ? (QUALITY_TYPE_LABEL[v as keyof typeof QUALITY_TYPE_LABEL] || v) : '—'}
                </span>
            ),
        },
        {
            title: '检查字段',
            dataIndex: 'columnName',
            width: COL.NAME_COMPACT,
            ellipsis: true,
            render: (v?: string) => (
                <span className="text-ds-small text-ds-text-secondary font-mono" title={v}>{v || '—'}</span>
            ),
        },
        {
            title: '权重',
            dataIndex: 'weight',
            width: COL.COUNT,
            align: 'right',
            render: (v?: number) => <span className="text-ds-small">{v ?? '—'}</span>,
        },
        {
            title: '最近结果',
            dataIndex: 'resultValue',
            width: COL.COUNT_NORMAL,
            align: 'right',
            render: (v?: number | string) => (
                <span className="text-ds-small text-ds-text-primary font-mono">{v ?? '—'}</span>
            ),
        },
        {
            title: '判定',
            dataIndex: 'resultLevel',
            width: COL.STATUS,
            render: (v?: QualityCheckLevel, r?: QualityTableRuleResult) => (
                v ? (
                    <DsStatusBadge variant={LEVEL_VARIANT[v]} label={QUALITY_CHECK_LEVEL_LABEL[v]}/>
                ) : (
                    <span className="text-ds-small text-ds-text-muted">
                        {r?.success === 0 ? '失败' : '未检查'}
                    </span>
                )
            ),
        },
        {
            title: '最近检查',
            dataIndex: 'lastCheckedAt',
            width: COL.DATETIME_COMPACT,
            render: (v?: string) => (
                <span className="text-ds-small text-ds-text-secondary whitespace-nowrap">
                    {formatDateTime(v)}
                </span>
            ),
        },
    ];

    return (
        <div className="space-y-ds-4">
            {/* 评分概览条 */}
            <div
                className="flex items-center gap-ds-6 flex-wrap bg-ds-bg-root border border-ds-border-subtle rounded-ds-md px-ds-4 py-ds-3">
                <QualityScoreBadge score={score?.score ?? null} healthLevel={score?.healthLevel}/>
                <div className="flex items-center gap-ds-5 text-ds-small text-ds-text-secondary">
                    <div>
                        <div className="text-ds-text-muted">最近检查</div>
                        <div className="text-ds-text-primary font-medium">
                            {formatDateTime(score?.lastCheckedAt)}
                        </div>
                    </div>
                    <div>
                        <div className="text-ds-text-muted">通过 / 警告 / 严重</div>
                        <div className="text-ds-text-primary font-medium">
                            <span className="text-ds-success">{score?.passRules ?? 0}</span>
                            {' / '}
                            <span className="text-ds-warning">{score?.warningRules ?? 0}</span>
                            {' / '}
                            <span className="text-ds-danger">{score?.severeRules ?? 0}</span>
                        </div>
                    </div>
                    <div>
                        <div className="text-ds-text-muted">启用规则数</div>
                        <div className="text-ds-text-primary font-medium">{rules.length}</div>
                    </div>
                </div>
                {canWrite && (
                    <div className="ml-auto">
                        <DsButton variant="primary" disabled={executing} loading={executing} onClick={handleExecute}>
                            立即执行全部规则
                        </DsButton>
                    </div>
                )}
            </div>

            {/* 规则最近结果 */}
            <Table
                rowKey={(r) => r?.ruleId ?? ''}
                columns={columns}
                dataSource={rules}
                loading={loading}
                pagination={false}
                scroll={{x: 700}}
                className="prototype-table prototype-table-flush"
                locale={{
                    emptyText: <DsTableEmpty description="该表暂无启用规则，或尚未执行检查"/>,
                }}
            />
        </div>
    );
}
