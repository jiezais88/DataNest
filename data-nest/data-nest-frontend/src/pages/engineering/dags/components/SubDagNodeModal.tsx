// Sprint 5：子 DAG 节点配置弹窗
// 选择已启用、且不会造成循环引用的 DAG；同步执行 = 等待子 DAG 完成，异步执行 = 触发后继续。
// Sprint 7 F3（NG5）：参数下发编辑器（主 DAG 参数 → 子 DAG 参数映射表，对齐原型 subdag 视图）。
import {useEffect, useState} from 'react';
import {AutoComplete, Select} from 'antd';
import {HiOutlinePlus, HiOutlineTrash} from 'react-icons/hi2';
import DsButton from '../../../../components/DsButton';
import DsIconButton from '../../../../components/DsIconButton';
import DsModal from '../../../../components/DsModal';
import {listDagParameters} from '../api';
import type {ParamMapping} from '../types';

/** 主参数可选系统变量（与后端校验白名单一致） */
const SYSTEM_PARAMS = ['biz_date', 'current_time', 'dag_id'];

interface SubDagNodeModalProps {
    open: boolean;
    initialNodeName?: string;
    initialSubDagId?: string | number;
    initialSyncExecution?: boolean;
    /** Sprint 7 F3：既有参数映射（编辑回显） */
    initialParamMappings?: ParamMapping[];
    /** 可选子 DAG（已启用且非循环引用，Editor 传入） */
    candidateDags: { id: string | number; name: string }[];
    /** 主 DAG 已声明参数名列表（Editor 传入；系统变量由本组件补充） */
    mainParamNames: string[];
    readOnly?: boolean;
    onClose: () => void;
    onSave: (nodeName: string, subDagId: string | number, subDagName: string, syncExecution: boolean,
             paramMappings: ParamMapping[]) => void;
}

const inputClass = 'w-full px-ds-3 py-ds-2 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-body text-ds-text-primary focus:outline-none focus-visible:border-ds-accent focus-visible:ring-1 focus-visible:ring-ds-accent transition-colors disabled:opacity-60 disabled:cursor-not-allowed';

export default function SubDagNodeModal({
                                            open,
                                            initialNodeName = '',
                                            initialSubDagId,
                                            initialSyncExecution = true,
                                            initialParamMappings,
                                            candidateDags,
                                            mainParamNames,
                                            readOnly = false,
                                            onClose,
                                            onSave,
                                        }: SubDagNodeModalProps) {
    const [nodeName, setNodeName] = useState(initialNodeName);
    const [subDagId, setSubDagId] = useState<string | number | undefined>(initialSubDagId);
    const [syncExecution, setSyncExecution] = useState(true);
    const [mappings, setMappings] = useState<ParamMapping[]>([]);
    const [subParamNames, setSubParamNames] = useState<string[]>([]);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        if (!open) return;
        setError(null);
        setNodeName(initialNodeName || '子 DAG');
        setSubDagId(initialSubDagId);
        setSyncExecution(initialSyncExecution ?? true);
        setMappings(initialParamMappings?.map(m => ({...m})) ?? []);
    }, [open, initialNodeName, initialSubDagId, initialSyncExecution, initialParamMappings]);

    // 选中子 DAG 后拉取其已声明参数（子参数下拉候选；未声明也可手输）
    useEffect(() => {
        if (!open || !subDagId) {
            setSubParamNames([]);
            return;
        }
        listDagParameters(subDagId)
            .then(list => setSubParamNames((list ?? []).map(p => p.paramName)))
            .catch(() => setSubParamNames([]));
    }, [open, subDagId]);

    const selectedDag = candidateDags.find(d => String(d.id) === String(subDagId));
    const subDagName = selectedDag?.name || '';

    // 主参数候选 = 主 DAG 声明参数 + 系统变量（去重）
    const mainParamOptions = [...new Set([...mainParamNames, ...SYSTEM_PARAMS])]
        .map(p => ({value: p, label: p}));

    const updateMapping = (index: number, patch: Partial<ParamMapping>) => {
        setMappings(prev => prev.map((m, i) => (i === index ? {...m, ...patch} : m)));
    };

    const validateMappings = (): string | null => {
        const seen = new Set<string>();
        for (const m of mappings) {
            if (!m.mainParam.trim()) return '存在未选择主 DAG 参数的映射行';
            if (!m.subParam.trim()) return '存在未填写子 DAG 参数的映射行';
            const key = m.subParam.trim();
            if (seen.has(key)) return `子 DAG 参数「${key}」在映射中重复`;
            seen.add(key);
        }
        return null;
    };

    const handleSave = () => {
        if (!nodeName.trim()) {
            setError('节点名称必填');
            return;
        }
        if (!subDagId) {
            setError('请选择子 DAG');
            return;
        }
        const mappingError = validateMappings();
        if (mappingError) {
            setError(mappingError);
            return;
        }
        // 空行不上报；保存时后端再按 R5 校验（mainParam 存在性等，7106 兜底）
        const cleaned = mappings
            .map(m => ({mainParam: m.mainParam.trim(), subParam: m.subParam.trim()}))
            .filter(m => m.mainParam && m.subParam);
        onSave(nodeName.trim(), subDagId, subDagName, syncExecution, cleaned);
    };

    return (
        <DsModal
            open={open}
            onClose={onClose}
            title="子 DAG 配置"
            width="w-[640px] max-w-[96vw]"
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
                        className={inputClass}
                    />
                </div>

                <div>
                    <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                        选择子 DAG <span className="text-ds-danger">*</span>
                    </label>
                    <Select
                        showSearch
                        optionFilterProp="label"
                        value={subDagId}
                        onChange={setSubDagId}
                        disabled={readOnly}
                        placeholder="请选择 DAG"
                        options={candidateDags.map(d => ({value: d.id, label: d.name || String(d.id)}))}
                        className="w-full"
                    />
                    <p className="mt-ds-1 text-ds-nano text-ds-text-muted">仅显示同项目、已启用、且不会造成循环引用的
                        DAG</p>
                </div>

                <div>
                    <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                        执行方式
                    </label>
                    <div className="flex flex-col gap-ds-2">
                        <label className="flex items-center gap-ds-2 text-ds-body text-ds-text-primary">
                            <input
                                type="radio"
                                name="subdag-mode"
                                checked={syncExecution}
                                onChange={() => setSyncExecution(true)}
                                disabled={readOnly}
                            />
                            同步执行（等待子 DAG 完成后再继续）
                        </label>
                        <label className="flex items-center gap-ds-2 text-ds-body text-ds-text-primary">
                            <input
                                type="radio"
                                name="subdag-mode"
                                checked={!syncExecution}
                                onChange={() => setSyncExecution(false)}
                                disabled={readOnly}
                            />
                            异步执行（触发后继续，不关心子 DAG 结果）
                        </label>
                    </div>
                </div>

                {/* Sprint 7 F3：参数下发（可选） */}
                <div>
                    <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                        参数下发（可选）
                    </label>
                    {mappings.length > 0 && (
                        <div className="space-y-ds-2 mb-ds-2">
                            {mappings.map((m, index) => (
                                <div key={index} className="flex items-center gap-ds-2">
                                    <Select
                                        showSearch
                                        value={m.mainParam || undefined}
                                        onChange={(v) => updateMapping(index, {mainParam: v})}
                                        disabled={readOnly}
                                        placeholder="主 DAG 参数"
                                        options={mainParamOptions}
                                        className="flex-1"
                                        aria-label={`映射 ${index + 1} 主参数`}
                                    />
                                    <span className="text-ds-text-muted flex-shrink-0">→</span>
                                    <AutoComplete
                                        value={m.subParam}
                                        onChange={(v) => updateMapping(index, {subParam: v})}
                                        disabled={readOnly}
                                        placeholder="子 DAG 参数"
                                        options={subParamNames.map(p => ({value: p, label: p}))}
                                        className="flex-1"
                                        aria-label={`映射 ${index + 1} 子参数`}
                                    />
                                    <DsIconButton
                                        tone="danger"
                                        aria-label={`删除映射 ${index + 1}`}
                                        disabled={readOnly}
                                        onClick={() => setMappings(prev => prev.filter((_, i) => i !== index))}
                                    >
                                        <HiOutlineTrash size={14}/>
                                    </DsIconButton>
                                </div>
                            ))}
                        </div>
                    )}
                    {!readOnly && (
                        <DsButton
                            variant="secondary"
                            className="!px-ds-3 !py-ds-1.5"
                            onClick={() => setMappings(prev => [...prev, {mainParam: '', subParam: ''}])}
                        >
                            <HiOutlinePlus size={14}/>
                            添加映射
                        </DsButton>
                    )}
                    <p className="mt-ds-2 text-ds-nano text-ds-text-muted">
                        主参数需在主 DAG 已声明参数或系统变量（biz_date / current_time / dag_id）中存在；子参数名在映射内唯一。
                        仅支持主 → 子单向下发，子 DAG 结果回传本期不做。
                    </p>
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
