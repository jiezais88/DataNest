// Sprint 5：条件分支节点配置弹窗
// 约定（对齐后端 ConditionNodeConfig）：branches[0] 为默认兜底分支（表达式锁 "true"）；
// 每个分支的 nextNodeId 必须指向一个下游节点，保存时由 Editor 同步画布连线。
import {useEffect, useMemo, useState} from 'react';
import {Select} from 'antd';
import {HiOutlineVariable} from 'react-icons/hi2';
import DsButton from '@/components/DsButton';
import DsModal from '@/components/DsModal';
import type {ConditionBranch, DagParameter, NodeType, UpstreamNodeInfo} from '@/pages/engineering/dags/types';

interface ConditionNodeModalProps {
    open: boolean;
    initialNodeName?: string;
    initialBranches?: ConditionBranch[];
    /** 可选下游节点（除条件节点自身外的所有节点） */
    availableNodes: { id: string; nodeName: string }[];
    /** 当前条件节点在画布上的直接前驱节点（用于按节点名生成表达式变量下拉） */
    upstreamNodes?: UpstreamNodeInfo[];
    /** DAG 参数（用于表达式变量下拉），新建 DAG 传草稿、已保存传 dagParams */
    dagParams?: DagParameter[];
    readOnly?: boolean;
    onClose: () => void;
    onSave: (nodeName: string, branches: ConditionBranch[]) => void;
}

/** 表达式可引用的固定系统变量（对齐后端 DagNodeExecuteService.buildConditionContext） */
const SYSTEM_VARIABLES: { key: string; desc: string }[] = [
    {key: 'upstream.row_count', desc: '上游影响行数（DML/查询归一化，兼容旧写法，取最后一个前驱）'},
    {key: 'upstream.status', desc: '上游节点执行状态（兼容旧写法）'},
    {key: 'current_time', desc: '当前时间'},
];

/** 各类型节点对外暴露的上游输出变量（对齐后端各节点 outputInfo） */
const UPSTREAM_NODE_VARIABLES: Record<NodeType, { key: string; desc: string }[]> = {
    SQL: [
        {key: 'row_count', desc: 'SQL 影响/返回行数（DML/查询归一化）'},
        {key: 'status', desc: '节点执行状态'},
        {key: 'sql_type', desc: 'SQL 类型（QUERY/DML/DDL）'},
        {key: 'target_table', desc: '目标表'},
    ],
    SYNC: [
        {key: 'status', desc: '节点执行状态'},
        {key: 'affectedRows', desc: '同步影响行数'},
    ],
    PYTHON: [
        {key: 'status', desc: '节点执行状态'},
        {key: 'outputTables', desc: '输出表列表'},
    ],
    CONDITION: [
        {key: 'status', desc: '节点执行状态'},
    ],
    SUB_DAG: [
        {key: 'status', desc: '节点执行状态'},
    ],
};

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
                                               upstreamNodes = [],
                                               dagParams,
                                               readOnly = false,
                                               onClose,
                                               onSave,
                                           }: ConditionNodeModalProps) {
    const [nodeName, setNodeName] = useState(initialNodeName);
    const [branches, setBranches] = useState<ConditionBranch[]>([]);
    const [error, setError] = useState<string | null>(null);
    // 当前正在编辑的分支下标，用于「插入变量」定位
    const [activeBranchIndex, setActiveBranchIndex] = useState(0);

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

    // 「插入变量」下拉选项，按「上游节点变量 / DAG 参数 / 系统变量」分组展示（每项带说明）
    const variableOptions: { label: string; options: { value: string; label: string }[] }[] = [
        {
            label: '上游节点变量',
            options: upstreamNodes.length > 0
                ? upstreamNodes.flatMap(n =>
                    (UPSTREAM_NODE_VARIABLES[n.nodeType] || []).map(v => {
                        const expr = `\${upstream['${n.nodeName}'].${v.key}}`;
                        return {
                            value: expr,
                            label: `${expr} — ${n.nodeName} 的${v.desc}`,
                        };
                    }))
                : [{value: '', label: '无直接前驱节点，请先连线上游节点'}],
        },
        {
            label: 'DAG 参数',
            options: (dagParams || []).length > 0
                ? (dagParams || []).map(p => ({
                    value: `\${${p.paramName}}`,
                    label: `\${${p.paramName}} — DAG 参数${p.description ? '：' + p.description : ''}`,
                }))
                : [{value: '', label: '暂无 DAG 参数'}],
        },
        {
            label: '系统变量',
            options: SYSTEM_VARIABLES.map(v => ({
                value: `\${${v.key}}`,
                label: `\${${v.key}} — ${v.desc}`,
            })),
        },
    ];

    // 展开为 antd Select 的扁平 options（含 optGroup 结构由 Select 直接渲染）
    const flatVariableOptions = variableOptions.flatMap(g => g.options);

    /** 把选中的变量追加到指定分支（默认当前编辑分支）的表达式末尾 */
    const insertVariable = (varExpr: string | null, index?: number) => {
        if (readOnly || !varExpr) return; // 空字符串/ null 为无前驱/无参数占位项，不可插入
        const target = index ?? activeBranchIndex;
        const current = branches[target];
        if (!current || target === 0) return;
        const prev = current.expression.trim();
        const sep = prev && !prev.endsWith(' ') ? ' ' : '';
        updateBranch(target, {expression: prev + sep + varExpr});
    };

    /**
     * 展示顺序：真实分支数组约定 branches[0] 为默认兜底分支（表达式锁 "true"，后端 evaluateBranches
     * 从下标 1 开始求真实条件、全不满足才返回 0）。为避免"默认分支放第一行但最后才生效"的误导，
     * 渲染时把默认分支（真实 index 0）挪到列表末尾，真实条件分支（index 1..n-1）按序在前。
     * 所有操作仍基于 realIndex 映射回真实数组下标，保存顺序与后端约定保持一致。
     */
    const displayOrder: { realIndex: number; branch: ConditionBranch; isDefault: boolean }[] = useMemo(() => {
        const order: { realIndex: number; branch: ConditionBranch; isDefault: boolean }[] = [];
        for (let i = 1; i < branches.length; i++) {
            order.push({realIndex: i, branch: branches[i], isDefault: false});
        }
        if (branches.length > 0) {
            order.push({realIndex: 0, branch: branches[0], isDefault: true});
        }
        return order;
    }, [branches]);

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
                        {displayOrder.map(({realIndex, branch, isDefault}) => (
                            <div
                                key={realIndex}
                                className="p-ds-3 bg-ds-bg-subtle border border-ds-border-subtle rounded-ds-sm space-y-ds-3"
                            >
                                {/* 第一行：分支名称 + 下游节点 + 删除 */}
                                <div className="grid grid-cols-[1fr_1.2fr_auto] gap-ds-3 items-end">
                                    <div>
                                        <label className="block text-ds-nano font-semibold text-ds-text-muted mb-ds-1">
                                            分支名称
                                        </label>
                                        <div className="flex items-center gap-ds-1">
                                            <input
                                                value={branch.branchName}
                                                onChange={e => updateBranch(realIndex, {branchName: e.target.value})}
                                                disabled={readOnly}
                                                className="w-full px-ds-2 py-ds-1.5 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-small text-ds-text-primary focus:outline-none focus-visible:border-ds-accent disabled:opacity-60 disabled:cursor-not-allowed"
                                            />
                                            {isDefault && (
                                                <span
                                                    className="shrink-0 px-ds-1.5 py-0.5 rounded bg-ds-accent-soft text-ds-accent text-ds-nano font-bold uppercase">
                                                    DEFAULT
                                                </span>
                                            )}
                                        </div>
                                    </div>
                                    <div>
                                        <label className="block text-ds-nano font-semibold text-ds-text-muted mb-ds-1">
                                            下游节点
                                        </label>
                                        <Select
                                            value={branch.nextNodeId || undefined}
                                            onChange={v => updateBranch(realIndex, {nextNodeId: v})}
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
                                        onClick={() => removeBranch(realIndex)}
                                        disabled={readOnly || branches.length <= 1}
                                    >
                                        删除
                                    </DsButton>
                                </div>

                                {/* 第二行：条件表达式独占整行，左侧输入框占满、右侧「插入变量」下拉 */}
                                <div>
                                    <label className="block text-ds-nano font-semibold text-ds-text-muted mb-ds-1">
                                        条件表达式
                                    </label>
                                    <div className="flex items-center gap-ds-2">
                                        <input
                                            value={branch.expression}
                                            onChange={e => updateBranch(realIndex, {expression: e.target.value})}
                                            onFocus={() => setActiveBranchIndex(realIndex)}
                                            disabled={readOnly || isDefault}
                                            placeholder={isDefault ? 'true' : '如 ${upstream.row_count} > 0'}
                                            className="flex-1 min-w-0 px-ds-2 py-ds-1.5 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-small font-mono text-ds-text-primary focus:outline-none focus-visible:border-ds-accent disabled:opacity-60 disabled:cursor-not-allowed"
                                        />
                                        <Select
                                            value={null}
                                            onChange={(v) => {
                                                // 选中即追加到当前分支表达式；Select 自身保持占位（插入的是占位符而非最终值）
                                                setActiveBranchIndex(realIndex);
                                                insertVariable(v, realIndex);
                                            }}
                                            disabled={readOnly || isDefault}
                                            placeholder="插入变量"
                                            suffixIcon={<HiOutlineVariable size={14}/>}
                                            className="flex-none w-[140px]"
                                            popupMatchSelectWidth={false}
                                            options={flatVariableOptions}
                                            showSearch
                                            optionFilterProp="label"
                                        />
                                    </div>
                                </div>
                            </div>
                        ))}
                    </div>
                    <DsButton variant="secondary" onClick={addBranch} disabled={readOnly} className="mt-ds-3">
                        + 添加分支
                    </DsButton>
                </div>

                <div className="rounded-ds-sm border border-ds-border-subtle bg-ds-bg-subtle p-ds-3 space-y-ds-2">
                    <p className="text-ds-nano font-semibold text-ds-text-secondary">求值规则</p>
                    <ul className="text-ds-nano text-ds-text-muted leading-relaxed list-disc pl-ds-4 space-y-ds-1">
                        <li>
                            <span className="text-ds-text-secondary">顺序短路（互斥）：</span>按分支从上到下依次判断，
                            <span className="text-ds-text-primary">第一个满足条件的分支生效</span>，后续分支不再求值；
                            每个执行只走一条分支。
                        </li>
                        <li>
                            <span className="text-ds-text-secondary">默认兜底：</span>列表最后一行为默认分支
                            （DEFAULT），表达式固定为 <code> true</code>，上面所有条件分支都不满足时执行它。
                        </li>
                        <li>
                            <span className="text-ds-text-secondary">分支内「与」：</span>若需同一分支同时满足多个条件，
                            用 <code> and </code>（或 <code> && </code>）连接，如
                            <code> {'${upstream[\'节点名\'].row_count}'} &gt; 100
                                and {'${upstream[\'节点名\'].status'} == 'SUCCESS'</code>。
                        </li>
                        <li>
                            <span className="text-ds-text-secondary">变量引用：</span>可引用上游节点输出
                            <code> {'${upstream[\'节点名\'].row_count}'}</code>（按直接前驱节点名精确取值）、DAG 参数
                            （如 <code> {'${biz_date}'}</code>）和系统变量（如 <code> {'${current_time}'}</code>），
                            也可点击「插入变量」选择。
                        </li>
                    </ul>
                </div>

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
