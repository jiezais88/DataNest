// Sprint 5：条件分支节点配置弹窗
// 约定（对齐后端 ConditionNodeConfig）：branches[0] 为默认兜底分支（表达式锁 "true"）；
// 每个分支的 nextNodeId 必须指向一个下游节点，保存时由 Editor 同步画布连线。
import {useEffect, useState} from 'react';
import {Select} from 'antd';
import DsButton from '../../../../components/DsButton';
import DsModal from '../../../../components/DsModal';
import type {ConditionBranch} from '../types';

interface ConditionNodeModalProps {
    open: boolean;
    initialNodeName?: string;
    initialBranches?: ConditionBranch[];
    /** 可选下游节点（除条件节点自身外的所有节点） */
    availableNodes: { id: string; nodeName: string }[];
    readOnly?: boolean;
    onClose: () => void;
    onSave: (nodeName: string, branches: ConditionBranch[]) => void;
}

const DEFAULT_BRANCH: ConditionBranch = {
    branchName: '默认分支',
    expression: 'true',
    nextNodeId: '',
};

export default function ConditionNodeModal({
                                               open,
                                               initialNodeName = '',
                                               initialBranches,
                                               availableNodes,
                                               readOnly = false,
                                               onClose,
                                               onSave,
                                           }: ConditionNodeModalProps) {
    const [nodeName, setNodeName] = useState(initialNodeName);
    const [branches, setBranches] = useState<ConditionBranch[]>([]);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        if (!open) return;
        setError(null);
        setNodeName(initialNodeName || '条件分支');
        const existing = initialBranches && initialBranches.length > 0 ? initialBranches : [DEFAULT_BRANCH];
        setBranches(existing.map(b => ({...b})));
    }, [open, initialNodeName, initialBranches]);

    const nodeOptions = availableNodes.map(n => ({value: n.id, label: n.nodeName}));

    const updateBranch = (index: number, patch: Partial<ConditionBranch>) => {
        setBranches(prev => prev.map((b, i) => (i === index ? {...b, ...patch} : b)));
    };

    const addBranch = () => {
        setBranches(prev => [...prev, {branchName: '', expression: '', nextNodeId: ''}]);
    };

    const removeBranch = (index: number) => {
        setBranches(prev => prev.filter((_, i) => i !== index));
    };

    const handleSave = () => {
        if (!nodeName.trim()) {
            setError('节点名称必填');
            return;
        }
        if (branches.length < 2) {
            setError('条件分支节点至少需要 2 个分支（含默认分支）');
            return;
        }
        for (const [index, branch] of branches.entries()) {
            if (!branch.branchName.trim()) {
                setError(`分支 ${index + 1} 缺少分支名称`);
                return;
            }
            if (!branch.expression.trim()) {
                setError(`分支「${branch.branchName}」缺少条件表达式`);
                return;
            }
            if (!branch.nextNodeId) {
                setError(`分支「${branch.branchName}」未选择下游节点`);
                return;
            }
        }
        const targets = branches.map(b => b.nextNodeId);
        if (new Set(targets).size !== targets.length) {
            setError('每个分支必须连接不同的下游节点');
            return;
        }
        onSave(nodeName.trim(), branches);
    };

    return (
        <DsModal
            open={open}
            onClose={onClose}
            title="条件分支配置"
            width="w-[720px] max-w-[96vw]"
            bordered
            footer={
                <>
                    <DsButton variant="secondary" onClick={onClose}>
                        取消
                    </DsButton>
                    <DsButton onClick={handleSave} disabled={readOnly}
                              title={readOnly ? '只读模式：您没有编辑权限' : undefined}>
                        保存
                    </DsButton>
                </>
            }
        >
            <div className="space-y-ds-4">
                <div>
                    <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                        节点名称 <span className="text-ds-danger">*</span>
                    </label>
                    <input
                        value={nodeName}
                        onChange={e => setNodeName(e.target.value)}
                        disabled={readOnly}
                        placeholder="请输入节点名称"
                        className="w-full px-ds-3 py-ds-2 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors disabled:opacity-60 disabled:cursor-not-allowed"
                    />
                </div>

                <div>
                    <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                        分支 <span className="text-ds-danger">*</span>
                    </label>
                    <div className="space-y-ds-3">
                        {branches.map((branch, index) => (
                            <div
                                key={index}
                                className="grid grid-cols-[1fr_1fr_1.2fr_auto] gap-ds-3 items-center p-ds-3 bg-ds-bg-subtle border border-ds-border-subtle rounded-ds-sm"
                            >
                                <div>
                                    <label className="block text-ds-nano font-semibold text-ds-text-muted mb-ds-1">
                                        分支名称
                                    </label>
                                    <div className="flex items-center gap-ds-1">
                                        <input
                                            value={branch.branchName}
                                            onChange={e => updateBranch(index, {branchName: e.target.value})}
                                            disabled={readOnly}
                                            className="w-full px-ds-2 py-ds-1.5 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-small text-ds-text-primary focus:outline-none focus-visible:border-ds-accent disabled:opacity-60 disabled:cursor-not-allowed"
                                        />
                                        {index === 0 && (
                                            <span
                                                className="shrink-0 px-ds-1.5 py-0.5 rounded bg-ds-accent-soft text-ds-accent text-ds-nano font-bold uppercase">
                                                DEFAULT
                                            </span>
                                        )}
                                    </div>
                                </div>
                                <div>
                                    <label className="block text-ds-nano font-semibold text-ds-text-muted mb-ds-1">
                                        条件表达式
                                    </label>
                                    <input
                                        value={branch.expression}
                                        onChange={e => updateBranch(index, {expression: e.target.value})}
                                        disabled={readOnly || index === 0}
                                        placeholder={index === 0 ? 'true' : '如 ${upstream.row_count} > 0'}
                                        className="w-full px-ds-2 py-ds-1.5 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-small font-mono text-ds-text-primary focus:outline-none focus-visible:border-ds-accent disabled:opacity-60 disabled:cursor-not-allowed"
                                    />
                                </div>
                                <div>
                                    <label className="block text-ds-nano font-semibold text-ds-text-muted mb-ds-1">
                                        下游节点
                                    </label>
                                    <Select
                                        value={branch.nextNodeId || undefined}
                                        onChange={v => updateBranch(index, {nextNodeId: v})}
                                        disabled={readOnly}
                                        placeholder="选择下游节点"
                                        options={nodeOptions}
                                        className="w-full"
                                        showSearch
                                        optionFilterProp="label"
                                    />
                                </div>
                                <DsButton
                                    variant="danger"
                                    onClick={() => removeBranch(index)}
                                    disabled={readOnly || branches.length <= 1}
                                    className="mt-ds-5"
                                >
                                    删除
                                </DsButton>
                            </div>
                        ))}
                    </div>
                    <DsButton variant="secondary" onClick={addBranch} disabled={readOnly} className="mt-ds-3">
                        + 添加分支
                    </DsButton>
                </div>

                <p className="text-ds-nano text-ds-text-muted leading-relaxed">
                    表达式可引用上游节点输出（如 <code>${'{upstream.nodeId.row_count}'}</code>）和 DAG
                    参数。按分支顺序匹配，第一个满足条件的分支被执行；均不满足时执行默认分支。
                </p>

                {error && (
                    <div
                        className="border border-ds-danger/30 bg-ds-danger/5 text-ds-danger rounded-ds-sm p-ds-3 text-ds-small">
                        {error}
                    </div>
                )}
            </div>
        </DsModal>
    );
}
