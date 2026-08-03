// Sprint 5：子 DAG 节点配置弹窗
// 选择已启用、且不会造成循环引用的 DAG；同步执行 = 等待子 DAG 完成，异步执行 = 触发后继续。
import {useEffect, useState} from 'react';
import {Select} from 'antd';
import DsButton from '../../../../components/DsButton';
import DsModal from '../../../../components/DsModal';

interface SubDagNodeModalProps {
    open: boolean;
    initialNodeName?: string;
    initialSubDagId?: string | number;
    initialSyncExecution?: boolean;
    /** 可选子 DAG（已启用且非循环引用，Editor 传入） */
    candidateDags: { id: string | number; name: string }[];
    readOnly?: boolean;
    onClose: () => void;
    onSave: (nodeName: string, subDagId: string | number, subDagName: string, syncExecution: boolean) => void;
}

export default function SubDagNodeModal({
                                            open,
                                            initialNodeName = '',
                                            initialSubDagId,
                                            initialSyncExecution = true,
                                            candidateDags,
                                            readOnly = false,
                                            onClose,
                                            onSave,
                                        }: SubDagNodeModalProps) {
    const [nodeName, setNodeName] = useState(initialNodeName);
    const [subDagId, setSubDagId] = useState<string | number | undefined>(initialSubDagId);
    const [syncExecution, setSyncExecution] = useState(true);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        if (!open) return;
        setError(null);
        setNodeName(initialNodeName || '子 DAG');
        setSubDagId(initialSubDagId);
        setSyncExecution(initialSyncExecution ?? true);
    }, [open, initialNodeName, initialSubDagId, initialSyncExecution]);

    const selectedDag = candidateDags.find(d => String(d.id) === String(subDagId));
    const subDagName = selectedDag?.name || '';

    const handleSave = () => {
        if (!nodeName.trim()) {
            setError('节点名称必填');
            return;
        }
        if (!subDagId) {
            setError('请选择子 DAG');
            return;
        }
        onSave(nodeName.trim(), subDagId, subDagName, syncExecution);
    };

    return (
        <DsModal
            open={open}
            onClose={onClose}
            title="子 DAG 配置"
            width="w-[520px] max-w-[96vw]"
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
                    <p className="mt-ds-1 text-ds-nano text-ds-text-muted">仅显示已启用、且不会造成循环引用的 DAG</p>
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

                <div className="bg-ds-info-light text-ds-info rounded-ds-sm px-ds-3 py-ds-2.5 text-ds-small">
                    提示：本期不支持子 DAG 参数透传与覆盖。
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
