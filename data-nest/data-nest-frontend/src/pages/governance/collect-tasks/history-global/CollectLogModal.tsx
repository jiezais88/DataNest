// 采集执行「变更日志」弹窗（对齐 Sprint 1 原型 md-log）：
// 顶部结构化变更区（新增表/删除表/变化表）+ 底部可折叠「原始日志」。
// 数据：打开时并行拉 getCollectHistoryLogs（原始日志）+ getCollectHistory（changeDetails 变更明细）。
//
// 后端变更明细口径（CollectExecutor）：
// - ADDED_TABLE：每张新表一行（columnName=null）+ 每个字段一行（newValue="type|nullable|comment"），
//   新增表分类只按 columnName=null 的行列表名（对齐原型，不展开字段）；
// - MODIFIED_TABLE：表注释变更（columnName=null，oldValue=旧注释，newValue=新注释），
//   在变化表的表块内独立一行展示，不与字段变更混在一个表格里；
// - ADDED_COLUMN/DELETED_COLUMN：value 为 "type|true/false|comment"；
// - MODIFIED_COLUMN_*：oldValue/newValue 为属性原值，按类型加前缀标签渲染。

import {type ReactNode, useEffect, useMemo, useState} from 'react';
import DsModal from '../../../../components/DsModal';
import DsButton from '../../../../components/DsButton';
import {getCollectHistory, getCollectHistoryLogs} from '../../../../api/collect';
import type {CollectChangeDetailDTO, CollectExecutionLog, CollectTaskExecution,} from '../../../../types/collect';
import {formatDateTime} from '../../../../utils/format';
import {notify} from '../../../../utils/notify';

interface CollectLogModalProps {
    open: boolean;
    history: CollectTaskExecution | null;
    onClose: () => void;
}

interface TableChangeGroup {
    key: string;
    name: string;
    /** 表注释变更（MODIFIED_TABLE），一个表最多一条 */
    commentChange?: CollectChangeDetailDTO;
    columnChanges: CollectChangeDetailDTO[];
}

const NULLABLE_LABEL: Record<string, string> = {true: '可为空', false: '不可为空'};

const COLUMN_PROP_LABEL: Record<string, string> = {
    MODIFIED_COLUMN_TYPE: '类型',
    MODIFIED_COLUMN_COMMENT: '注释',
    MODIFIED_COLUMN_ORDINAL: '顺序',
    MODIFIED_COLUMN_NULLABLE: '可空性',
    MODIFIED_COLUMN_DEFAULT: '默认值',
};

const BADGE_CLASS = {
    add: 'bg-ds-success/10 text-ds-success',
    mod: 'bg-ds-warning/10 text-ds-warning',
    del: 'bg-ds-danger/10 text-ds-danger',
} as const;

function fullTableName(d: CollectChangeDetailDTO): string {
    return [d.databaseName, d.schemaName, d.tableName].filter(Boolean).join('.');
}

function tableKey(d: CollectChangeDetailDTO): string {
    return `${d.databaseName}|${d.schemaName ?? ''}|${d.tableName}`;
}

/** "varchar(20)|true|姓名" → "varchar(20)，可为空，注释：姓名" */
function formatColumnValue(v?: string | null): string {
    if (!v) return '—';
    const [type, nullable, comment] = v.split('|');
    const parts = [type, NULLABLE_LABEL[nullable] || nullable].filter(Boolean);
    if (comment) parts.push(`注释：${comment}`);
    return parts.join('，');
}

function quote(v?: string | null): string {
    return v == null || v === '' ? '""' : v;
}

function columnChangeText(d: CollectChangeDetailDTO): string {
    if (d.changeType === 'ADDED_COLUMN') return formatColumnValue(d.newValue);
    if (d.changeType === 'DELETED_COLUMN') return formatColumnValue(d.oldValue);
    const label = COLUMN_PROP_LABEL[d.changeType] || '变更';
    // 注释类加引号（对齐原型 注释："0-禁用" → "是否启用"），其余原值直出（类型：int → bigint）
    const quoted = d.changeType === 'MODIFIED_COLUMN_COMMENT';
    const oldV = quoted ? quote(d.oldValue) : (d.oldValue ?? '—');
    const newV = quoted ? quote(d.newValue) : (d.newValue ?? '—');
    return `${label}：${oldV} → ${newV}`;
}

function columnChangeBadge(d: CollectChangeDetailDTO): { text: string; tone: keyof typeof BADGE_CLASS } {
    if (d.changeType === 'ADDED_COLUMN') return {text: '新增字段', tone: 'add'};
    if (d.changeType === 'DELETED_COLUMN') return {text: '删除字段', tone: 'del'};
    return {text: '修改字段', tone: 'mod'};
}

function logLevelColor(level?: string): string {
    if (level === 'ERROR') return 'text-[#f87171]';
    if (level === 'WARN') return 'text-[#fbbf24]';
    return 'text-[#94a3b8]';
}

/** 表块头摘要：表注释变更，新增字段 x 个，修改字段 y 个，删除字段 z 个（为 0 不显示） */
function groupSummary(g: TableChangeGroup): string {
    const parts: string[] = [];
    if (g.commentChange) parts.push('表注释变更');
    const added = g.columnChanges.filter(c => c.changeType === 'ADDED_COLUMN').length;
    const modified = g.columnChanges.filter(c => c.changeType.startsWith('MODIFIED_COLUMN_')).length;
    const deleted = g.columnChanges.filter(c => c.changeType === 'DELETED_COLUMN').length;
    if (added > 0) parts.push(`新增字段 ${added} 个`);
    if (modified > 0) parts.push(`修改字段 ${modified} 个`);
    if (deleted > 0) parts.push(`删除字段 ${deleted} 个`);
    return parts.join('，');
}

function ChangeBadge({text, tone}: { text: string; tone: keyof typeof BADGE_CLASS }) {
    return (
        <span
            className={`inline-flex items-center px-2 py-0.5 rounded text-ds-nano font-semibold whitespace-nowrap ${BADGE_CLASS[tone]}`}>
            {text}
        </span>
    );
}

export default function CollectLogModal({open, history, onClose}: CollectLogModalProps) {
    const [logs, setLogs] = useState<CollectExecutionLog[]>([]);
    const [changeDetails, setChangeDetails] = useState<CollectChangeDetailDTO[]>([]);
    const [loading, setLoading] = useState(false);
    // 折叠状态：原始日志（FAILED 默认展开）；变化表的表块默认全部展开
    const [rawLogOpen, setRawLogOpen] = useState(false);
    const [collapsedTables, setCollapsedTables] = useState<Set<string>>(new Set());

    useEffect(() => {
        if (!open || !history) return;
        setLogs([]);
        setChangeDetails([]);
        setCollapsedTables(new Set());
        setRawLogOpen(history.status === 'FAILED');
        setLoading(true);
        Promise.all([
            getCollectHistoryLogs(history.taskId, history.id).then(r => r.data || []),
            getCollectHistory(history.taskId, history.id).then(r => r.data?.changeDetails || []),
        ])
            .then(([l, d]) => {
                setLogs(l);
                setChangeDetails(d);
            })
            .catch(() => {
                // 错误提示由 request 拦截器统一弹出；保持空数据展示
            })
            .finally(() => setLoading(false));
    }, [open, history]);

    const addedTables = useMemo(
        () => changeDetails.filter(d => d.changeType === 'ADDED_TABLE' && d.columnName == null),
        [changeDetails],
    );
    const deletedTables = useMemo(
        () => changeDetails.filter(d => d.changeType === 'DELETED_TABLE'),
        [changeDetails],
    );
    const modifiedGroups = useMemo<TableChangeGroup[]>(() => {
        const groups = new Map<string, TableChangeGroup>();
        for (const d of changeDetails) {
            const isTableComment = d.changeType === 'MODIFIED_TABLE';
            const isColumnChange = d.changeType === 'ADDED_COLUMN' || d.changeType === 'DELETED_COLUMN'
                || d.changeType.startsWith('MODIFIED_COLUMN_');
            if (!isTableComment && !isColumnChange) continue;
            const key = tableKey(d);
            let g = groups.get(key);
            if (!g) {
                g = {key, name: fullTableName(d), columnChanges: []};
                groups.set(key, g);
            }
            if (isTableComment) g.commentChange = d;
            else g.columnChanges.push(d);
        }
        return [...groups.values()];
    }, [changeDetails]);

    const toggleTable = (key: string) => {
        setCollapsedTables(prev => {
            const next = new Set(prev);
            if (next.has(key)) next.delete(key);
            else next.add(key);
            return next;
        });
    };

    const handleCopyLogs = async () => {
        const text = logs.map(l => `[${l.level}] ${formatDateTime(l.createdAt)} ${l.message}`).join('\n');
        try {
            await navigator.clipboard.writeText(text);
            notify.success('日志已复制到剪贴板');
        } catch {
            notify.warning('复制失败，请检查浏览器剪贴板权限');
        }
    };

    function SectionCard({
                             title,
                             header,
                             children,
                         }: {
        title?: string;
        header?: ReactNode;
        children: ReactNode;
    }) {
        return (
            <div className="border border-ds-border-subtle rounded-ds-sm overflow-hidden">
                {header || (
                    <div
                        className="px-ds-3 py-ds-2 bg-ds-bg-hover text-ds-caption text-ds-text-muted font-bold uppercase tracking-wider">
                        {title}
                    </div>
                )}
                <div className="divide-y divide-ds-border-subtle">
                    {children}
                </div>
            </div>
        );
    }

    return (
        <DsModal
            open={open}
            onClose={onClose}
            title={`变更日志 — ${history?.taskName || '-'} / ${formatDateTime(history?.startedAt)}`}
            width="w-[720px] max-w-[96vw]"
            bordered
            footer={
                <>
                    <DsButton variant="secondary" onClick={handleCopyLogs} disabled={logs.length === 0}>
                        复制日志
                    </DsButton>
                    <DsButton variant="secondary" onClick={onClose}>
                        关闭
                    </DsButton>
                </>
            }
        >
            {loading ? (
                <div className="text-ds-small text-ds-text-secondary">加载中...</div>
            ) : (
                <div className="space-y-ds-4">
                    {/* ══ 新增表 ══ */}
                    <SectionCard title={`新增表（${addedTables.length}）`}>
                        {addedTables.length === 0 ? (
                            <div className="px-ds-3 py-ds-2 text-ds-small text-ds-text-muted">无</div>
                        ) : (
                            addedTables.map((d, idx) => (
                                <div
                                    key={d.id ?? idx}
                                    className="px-ds-3 py-ds-2 text-ds-small text-ds-text-secondary font-mono"
                                >
                                    {fullTableName(d)}
                                </div>
                            ))
                        )}
                    </SectionCard>

                    {/* ══ 删除表 ══ */}
                    <SectionCard title={`删除表（${deletedTables.length}）`}>
                        {deletedTables.length === 0 ? (
                            <div className="px-ds-3 py-ds-2 text-ds-small text-ds-text-muted">无</div>
                        ) : (
                            deletedTables.map((d, idx) => (
                                <div
                                    key={d.id ?? idx}
                                    className="px-ds-3 py-ds-2 text-ds-small text-ds-text-secondary font-mono"
                                >
                                    {fullTableName(d)}
                                </div>
                            ))
                        )}
                    </SectionCard>

                    {/* ══ 变化表 ══ */}
                    <SectionCard title={`变化表（${modifiedGroups.length}）`}>
                        {modifiedGroups.length === 0 ? (
                            <div className="px-ds-3 py-ds-2 text-ds-small text-ds-text-muted">无</div>
                        ) : (
                            modifiedGroups.map(g => {
                                const collapsed = collapsedTables.has(g.key);
                                return (
                                    <div key={g.key}>
                                        <button
                                            type="button"
                                            onClick={() => toggleTable(g.key)}
                                            className="w-full flex items-center gap-ds-2 px-ds-3 py-ds-2 bg-ds-bg-hover text-left"
                                        >
                                                <span
                                                    className={`text-ds-text-muted transition-transform ${collapsed ? '' : 'rotate-90'}`}>
                                                    ▸
                                                </span>
                                            <span
                                                className="font-mono text-ds-small text-ds-text-primary font-semibold">
                                                    {g.name}
                                                </span>
                                            <span className="text-ds-caption text-ds-text-muted">
                                                    {groupSummary(g)}
                                                </span>
                                        </button>
                                        {!collapsed && (
                                            <div className="px-ds-3 py-ds-3 space-y-ds-2">
                                                {/* 表注释变更：独立一行，不与字段变更混在一个表格里 */}
                                                {g.commentChange && (
                                                    <div className="flex items-center gap-ds-2 text-ds-small">
                                                        <ChangeBadge text="表注释" tone="mod"/>
                                                        <span className="text-ds-text-secondary">
                                                                注释：{quote(g.commentChange.oldValue)} → {quote(g.commentChange.newValue)}
                                                            </span>
                                                    </div>
                                                )}
                                                {g.columnChanges.length > 0 && (
                                                    <table className="w-full text-left">
                                                        <thead>
                                                        <tr className="text-ds-caption text-ds-text-muted">
                                                            <th className="w-[80px] font-semibold pb-ds-1">变更类型</th>
                                                            <th className="w-[120px] font-semibold pb-ds-1">字段名</th>
                                                            <th className="font-semibold pb-ds-1">变化详情</th>
                                                        </tr>
                                                        </thead>
                                                        <tbody>
                                                        {g.columnChanges.map((c, idx) => {
                                                            const badge = columnChangeBadge(c);
                                                            return (
                                                                <tr key={c.id ?? idx}
                                                                    className="border-t border-ds-border-subtle">
                                                                    <td className="py-ds-1.5">
                                                                        <ChangeBadge text={badge.text}
                                                                                     tone={badge.tone}/>
                                                                    </td>
                                                                    <td className="py-ds-1.5 text-ds-small font-mono text-ds-text-primary">
                                                                        {c.columnName}
                                                                    </td>
                                                                    <td className="py-ds-1.5 text-ds-small text-ds-text-secondary">
                                                                        {columnChangeText(c)}
                                                                    </td>
                                                                </tr>
                                                            );
                                                        })}
                                                        </tbody>
                                                    </table>
                                                )}
                                            </div>
                                        )}
                                    </div>
                                );
                            })
                        )}
                    </SectionCard>

                    {/* ══ 原始日志（可折叠；FAILED 默认展开 + danger 头部） ══ */}
                    <SectionCard
                        header={
                            <button
                                type="button"
                                onClick={() => setRawLogOpen(v => !v)}
                                className={`w-full flex items-center gap-ds-2 px-ds-3 py-ds-2 text-left text-ds-caption font-bold uppercase tracking-wider ${
                                    history?.status === 'FAILED'
                                        ? 'bg-ds-danger-light text-ds-danger'
                                        : 'bg-ds-bg-hover text-ds-text-muted'
                                }`}
                            >
                                <span className={`transition-transform ${rawLogOpen ? 'rotate-90' : ''}`}>▸</span>
                                原始日志{history?.status === 'FAILED' ? '（执行失败）' : ''}
                            </button>
                        }
                    >
                        {rawLogOpen && (
                            <div className="bg-[#1e293b] px-ds-3 py-ds-2 max-h-[280px] overflow-auto">
                                {logs.length === 0 ? (
                                    <div className="text-ds-caption text-[#94a3b8] py-ds-2">暂无日志</div>
                                ) : (
                                    logs.map((log, idx) => (
                                        <div key={log.id ?? idx}
                                             className="text-ds-caption font-mono break-all leading-relaxed">
                                            <span
                                                className="text-[#64748b]">[{formatDateTime(log.createdAt)}]</span>{' '}
                                            <span
                                                className={`font-semibold ${logLevelColor(log.level)}`}>{log.level}</span>{' '}
                                            <span className="text-[#e2e8f0]">{log.message}</span>
                                        </div>
                                    ))
                                )}
                            </div>
                        )}
                    </SectionCard>
                </div>
            )}
        </DsModal>
    );
}
