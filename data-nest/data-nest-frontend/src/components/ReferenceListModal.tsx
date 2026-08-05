import DsButton from './DsButton';
import DsModal from './DsModal';

interface Props {
    open: boolean;
    title: string;
    message: string;
    /** 被引用的名称列表（由后端删除失败时 BusinessException.data 返回） */
    references: string[];
    onClose: () => void;
}

/**
 * 删除被阻止时的引用明细弹窗。
 * 用于「被其他对象引用，禁止删除」的场景：后端在 BusinessException 的 data 里
 * 返回具体被哪些对象引用，前端统一弹窗展示，让用户知道要先解除哪些引用。
 * 支持两种引用格式：
 *  - 纯名称列表（string[]，如同步任务/DAG/质量任务/字段类型标准场景）
 */
export default function ReferenceListModal({open, title, message, references, onClose}: Props) {
    return (
        <DsModal
            open={open}
            onClose={onClose}
            title={title}
            closable={false}
            maskClosable
            footer={
                <DsButton variant="primary" onClick={onClose}>
                    我知道了
                </DsButton>
            }
        >
            <p className="text-ds-body text-ds-text-secondary mb-ds-4">{message}</p>
            <ul className="list-disc list-inside text-ds-small text-ds-text-secondary space-y-ds-1">
                {references.map((name, idx) => (
                    <li key={`${name}-${idx}`}>{name}</li>
                ))}
            </ul>
        </DsModal>
    );
}
