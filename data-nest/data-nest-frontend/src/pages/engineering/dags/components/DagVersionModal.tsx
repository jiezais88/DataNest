// Sprint 4 DAG 版本管理弹窗（PRD §6.7 / 技术文档 §15.4）
// - 版本列表：版本号 / 保存时间 / 保存人 / 变更摘要 / 操作（设为基准、设为对比、回滚）
// - 对比：在表格行选择基准版本与对比版本，按 节点/连线/参数 三组渲染差异。
// - 回滚：生成一个与目标版本内容一致的新版本，不删除历史；成功后回调父组件刷新画布。
// 权限：列表/对比所有角色可看；回滚仅 canEdit（PRD §8 权限矩阵）。
import {useCallback, useEffect, useMemo, useState} from 'react';
import {Modal, Spin} from 'antd';
import DsButton from '@/components/DsButton';
import DsModal from '@/components/DsModal';
import DsSelect from '@/components/DsSelect';
import {compareDagVersions, listDagVersions, rollbackDagVersion} from '@/pages/engineering/dags/api';
import type {DagNode, DagVersion, DagVersionDiff} from '@/pages/engineering/dags/types';
import {formatDateTime} from '@/utils/format';
import {notify} from '@/utils/notify';

interface DagVersionModalProps {
    open: boolean;
    dagId?: string | number;
    /** 是否有编辑权限：控制「回滚」按钮（治理员/分析师只读） */
    canEdit: boolean;
    onClose: () => void;
    /** 回滚成功后回调：父组件重新加载 DAG 刷新画布 */
    onRolledBack?: () => void;
}

type DiffKind = 'add' | 'mod' | 'del';

const DIFF_STYLE: Record<DiffKind, string> = {
    add: 'bg-ds-success/10 text-ds-success',
    mod: 'bg-ds-warning/10 text-ds-warning',
    del: 'bg-ds-danger/10 text-ds-danger',
};

const DIFF_PREFIX: Record<DiffKind, string> = {add: '+ 新增', mod: '~ 修改', del: '- 删除'};

interface Snapshot {
    nodes?: DagNode[];
    edges?: { sourceNodeId?: string; targetNodeId?: string }[];
    params?: { paramName?: string }[];
}

function DiffGroup({title, items}: { title: string; items: { kind: DiffKind; text: string }[] }) {
    if (items.length === 0) return null;
    return (
        <div className="mb-ds-3">
            <div className="text-ds-caption text-ds-text-muted font-bold uppercase tracking-wider mb-ds-1">
                {title}
            </div>
            <div className="space-y-1">
                {items.map((item, idx) => (
                    <div key={idx}
                         className={`inline-flex items-center px-ds-2 py-0.5 rounded-ds-sm text-ds-caption font-mono mr-ds-2 ${DIFF_STYLE[item.kind]}`}>
                        {DIFF_PREFIX[item.kind]}：{item.text}
                    </div>
                ))}
            </div>
        </div>
    );
}

function parseSnapshot(snapshot?: string): Snapshot {
    if (!snapshot) return {};
    try {
        return JSON.parse(snapshot) as Snapshot;
    } catch {
        return {};
    }
}

function buildNodeNameMap(versions: DagVersion[]): Map<string, string> {
    const map = new Map<string, string>();
    for (const v of versions) {
        const snap = parseSnapshot(v.snapshot);
        for (const n of snap.nodes || []) {
            if (n.nodeId && n.nodeName && !map.has(n.nodeId)) {
                map.set(n.nodeId, n.nodeName);
            }
        }
    }
    return map;
}

function formatNodeId(id: string, nodeNameMap: Map<string, string>): string {
    return nodeNameMap.get(id) || id;
}

function formatEdgeKey(key: string, nodeNameMap: Map<string, string>): string {
    const [source, target] = key.split('->');
    if (!source || !target) return key;
    const sourceName = nodeNameMap.get(source) || source;
    const targetName = nodeNameMap.get(target) || target;
    return `${sourceName} → ${targetName}`;
}

export default function DagVersionModal({
                                            open,
                                            dagId,
                                            canEdit,
                                            onClose,
                                            onRolledBack,
                                        }: DagVersionModalProps) {
    const [loading, setLoading] = useState(false);
    const [versions, setVersions] = useState<DagVersion[]>([]);
    // 对比：基准（left）vs 对比（right），diff = right 相对 left 的变化
    const [leftVersion, setLeftVersion] = useState<number | null>(null);
    const [rightVersion, setRightVersion] = useState<number | null>(null);
    const [diff, setDiff] = useState<DagVersionDiff | null>(null);
    const [diffLoading, setDiffLoading] = useState(false);
    const [rollingBack, setRollingBack] = useState(false);

    const loadVersions = useCallback(async () => {
        if (!dagId) return;
        setLoading(true);
        try {
            const list = (await listDagVersions(dagId)) || [];
            // 版本号倒序：最新在前
            const sorted = [...list].sort((a, b) => b.versionNo - a.versionNo);
            setVersions(sorted);
            // 弹窗打开时不预选任何版本
            setRightVersion(null);
            setLeftVersion(null);
        } finally {
            setLoading(false);
        }
    }, [dagId]);

    useEffect(() => {
        if (!open) return;
        setDiff(null);
        loadVersions();
    }, [open, loadVersions]);

    // 两个版本都选定后自动拉取 diff
    useEffect(() => {
        if (!dagId || leftVersion == null || rightVersion == null || leftVersion === rightVersion) {
            setDiff(null);
            return;
        }
        setDiffLoading(true);
        compareDagVersions(dagId, leftVersion, rightVersion)
            .then(setDiff)
            .catch(() => setDiff(null))
            .finally(() => setDiffLoading(false));
    }, [dagId, leftVersion, rightVersion]);

    const handleRollback = (version: DagVersion) => {
        if (!dagId) return;
        Modal.confirm({
            centered: true,
            wrapClassName: 'prototype-modal',
            title: '回滚确认',
            content: `确定回滚到 v${version.versionNo} 吗？回滚后会生成新版本，内容等同于 v${version.versionNo}。当前未保存的更改将丢失。`,
            okText: '回滚',
            cancelText: '取消',
            okButtonProps: {'data-testid': 'dag-version-rollback-confirm'},
            onOk: async () => {
                setRollingBack(true);
                try {
                    const created = await rollbackDagVersion(dagId, version.versionNo);
                    notify.success(`已回滚到 v${version.versionNo}，生成新版本 v${created?.versionNo ?? ''}`);
                    await loadVersions();
                    onRolledBack?.();
                } catch {
                    // 错误提示由 request 拦截器统一弹出
                } finally {
                    setRollingBack(false);
                }
            },
        });
    };

    const current = versions[0];
    const nodeNameMap = useMemo(() => buildNodeNameMap(versions), [versions]);

    // 下拉框选项：当前版本不可选；已选为对方时禁用
    const versionOptions = versions
        .filter(v => v.versionNo !== current?.versionNo)
        .map(v => ({value: v.versionNo, label: `v${v.versionNo}`}));

    const hasDiff = diff && [
        diff.addedNodes, diff.removedNodes, diff.modifiedNodes,
        diff.addedEdges, diff.removedEdges,
        diff.addedParams, diff.removedParams, diff.modifiedParams,
    ].some(list => (list?.length ?? 0) > 0);

    const clearComparison = () => {
        setLeftVersion(null);
        setRightVersion(null);
        setDiff(null);
    };

    return (
        <DsModal
            open={open}
            onClose={onClose}
            title="DAG 版本管理"
            width="w-[720px] max-w-[96vw]"
            bordered
            footer={
                <DsButton variant="secondary" onClick={onClose} disabled={rollingBack}>
                    关闭
                </DsButton>
            }
        >
            {loading ? (
                <div className="flex items-center justify-center py-ds-6 gap-ds-2 text-ds-small text-ds-text-secondary">
                    <Spin size="small"/> 加载版本中...
                </div>
            ) : versions.length === 0 ? (
                <div className="text-ds-small text-ds-text-muted text-center py-ds-6">
                    暂无版本记录。保存 DAG 后会自动生成版本。
                </div>
            ) : (
                <div>
                    {/* 版本表格 */}
                    <div className="border border-ds-border-subtle rounded-ds-sm overflow-hidden mb-ds-4">
                        <table className="w-full text-left">
                            <thead className="bg-ds-bg-hover">
                            <tr>
                                <th className="px-ds-3 py-ds-2 text-ds-caption text-ds-text-primary font-semibold">版本号</th>
                                <th className="px-ds-3 py-ds-2 text-ds-caption text-ds-text-primary font-semibold">保存时间</th>
                                <th className="px-ds-3 py-ds-2 text-ds-caption text-ds-text-primary font-semibold">保存人</th>
                                <th className="px-ds-3 py-ds-2 text-ds-caption text-ds-text-primary font-semibold">变更摘要</th>
                                <th className="px-ds-3 py-ds-2 text-ds-caption text-ds-text-primary font-semibold">操作</th>
                            </tr>
                            </thead>
                            <tbody>
                            {versions.map(v => {
                                const isCurrent = v.versionNo === current?.versionNo;
                                const isLeft = v.versionNo === leftVersion;
                                const isRight = v.versionNo === rightVersion;
                                return (
                                    <tr key={v.versionNo}
                                        className="border-t border-ds-border-subtle first:border-t-0">
                                        <td className="px-ds-3 py-ds-2 text-ds-small text-ds-text-primary font-semibold">
                                            v{v.versionNo}
                                            {isCurrent && (
                                                <span className="ml-ds-1 text-ds-caption text-ds-accent">（当前）</span>
                                            )}
                                        </td>
                                        <td className="px-ds-3 py-ds-2 text-ds-small text-ds-text-secondary whitespace-nowrap">
                                            {formatDateTime(v.createdAt)}
                                        </td>
                                        <td className="px-ds-3 py-ds-2 text-ds-small text-ds-text-secondary">
                                            {v.createdByName || '—'}
                                        </td>
                                        <td className="px-ds-3 py-ds-2 text-ds-small text-ds-text-secondary">
                                            {v.changeSummary || '—'}
                                        </td>
                                        <td className="px-ds-3 py-ds-2 text-ds-small whitespace-nowrap">
                                            {!isCurrent && (
                                                <>
                                                    <button
                                                        className="text-ds-accent hover:text-ds-accent-hover mr-ds-3 disabled:opacity-50 disabled:cursor-not-allowed"
                                                        onClick={() => setLeftVersion(v.versionNo)}
                                                        disabled={isRight}
                                                        title={isRight ? '已设为对比版本' : '设为基准版本'}
                                                    >
                                                        设为基准
                                                    </button>
                                                    <button
                                                        className="text-ds-accent hover:text-ds-accent-hover mr-ds-3 disabled:opacity-50 disabled:cursor-not-allowed"
                                                        onClick={() => setRightVersion(v.versionNo)}
                                                        disabled={isLeft}
                                                        title={isLeft ? '已设为基准版本' : '设为对比版本'}
                                                    >
                                                        设为对比
                                                    </button>
                                                </>
                                            )}
                                            {canEdit && (
                                                <button
                                                    data-testid={`dag-version-rollback-${v.versionNo}`}
                                                    className="text-ds-accent hover:text-ds-accent-hover disabled:opacity-50"
                                                    onClick={() => handleRollback(v)}
                                                    disabled={rollingBack || isCurrent}
                                                    title={isCurrent ? '已是当前版本' : '回滚到该版本'}
                                                >
                                                    回滚
                                                </button>
                                            )}
                                        </td>
                                    </tr>
                                );
                            })}
                            </tbody>
                        </table>
                    </div>

                    {/* 版本对比 */}
                    <div className="border-t border-ds-border-subtle pt-ds-4">
                        <div className="flex items-center justify-between mb-ds-3">
                            <div className="flex items-center gap-ds-3 flex-wrap">
                                <span className="text-ds-small font-semibold text-ds-text-secondary">版本对比</span>
                                <DsSelect
                                    value={String(leftVersion ?? '')}
                                    onChange={v => setLeftVersion(v ? Number(v) : null)}
                                    className="px-ds-2 py-ds-1.5 text-ds-small"
                                >
                                    <option value="" disabled hidden>基准版本</option>
                                    {versionOptions.map(o => (
                                        <option key={o.value} value={o.value} disabled={o.value === rightVersion}>
                                            {o.label}
                                        </option>
                                    ))}
                                </DsSelect>
                                <span className="text-ds-small text-ds-text-muted">vs</span>
                                <DsSelect
                                    value={String(rightVersion ?? '')}
                                    onChange={v => setRightVersion(v ? Number(v) : null)}
                                    className="px-ds-2 py-ds-1.5 text-ds-small"
                                >
                                    <option value="" disabled hidden>对比版本</option>
                                    {versionOptions.map(o => (
                                        <option key={o.value} value={o.value} disabled={o.value === leftVersion}>
                                            {o.label}
                                        </option>
                                    ))}
                                </DsSelect>
                                {leftVersion != null && rightVersion != null && (
                                    <span className="text-ds-caption text-ds-text-muted">
                                        v{leftVersion} → v{rightVersion} 的变化
                                    </span>
                                )}
                            </div>
                            {(leftVersion != null || rightVersion != null) && (
                                <button
                                    className="text-ds-caption text-ds-accent hover:text-ds-accent-hover"
                                    onClick={clearComparison}
                                >
                                    清除选择
                                </button>
                            )}
                        </div>

                        {diffLoading ? (
                            <div
                                className="flex items-center justify-center py-ds-4 gap-ds-2 text-ds-small text-ds-text-secondary">
                                <Spin size="small"/> 对比中...
                            </div>
                        ) : diff ? (
                            hasDiff ? (
                                <div>
                                    <DiffGroup
                                        title="节点变化"
                                        items={[
                                            ...(diff.addedNodes || []).map(t => ({
                                                kind: 'add' as const,
                                                text: formatNodeId(t, nodeNameMap)
                                            })),
                                            ...(diff.modifiedNodes || []).map(t => ({
                                                kind: 'mod' as const,
                                                text: formatNodeId(t, nodeNameMap)
                                            })),
                                            ...(diff.removedNodes || []).map(t => ({
                                                kind: 'del' as const,
                                                text: formatNodeId(t, nodeNameMap)
                                            })),
                                        ]}
                                    />
                                    <DiffGroup
                                        title="连线变化"
                                        items={[
                                            ...(diff.addedEdges || []).map(t => ({
                                                kind: 'add' as const,
                                                text: formatEdgeKey(t, nodeNameMap)
                                            })),
                                            ...(diff.removedEdges || []).map(t => ({
                                                kind: 'del' as const,
                                                text: formatEdgeKey(t, nodeNameMap)
                                            })),
                                        ]}
                                    />
                                    <DiffGroup
                                        title="参数变化"
                                        items={[
                                            ...(diff.addedParams || []).map(t => ({kind: 'add' as const, text: t})),
                                            ...(diff.modifiedParams || []).map(t => ({kind: 'mod' as const, text: t})),
                                            ...(diff.removedParams || []).map(t => ({kind: 'del' as const, text: t})),
                                        ]}
                                    />
                                </div>
                            ) : (
                                <div className="text-ds-small text-ds-text-muted text-center py-ds-4">
                                    两个版本内容一致，无差异。
                                </div>
                            )
                        ) : (
                            <div className="text-ds-small text-ds-text-muted text-center py-ds-4">
                                选择两个不同版本查看差异。
                            </div>
                        )}
                    </div>
                </div>
            )}
        </DsModal>
    );
}
