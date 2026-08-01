import type {PreviewResult} from '../api/preview';
import DsModal from './DsModal';
import DsSpinner from './DsSpinner';

interface PreviewModalProps {
    open: boolean;
    title: string;
    loading: boolean;
    result: PreviewResult | null;
    onClose: () => void;
}

export default function PreviewModal({open, title, loading, result, onClose}: PreviewModalProps) {
    return (
        <DsModal open={open} onClose={onClose} title={title} bordered width="w-[900px]">
            {loading ? (
                <div className="flex items-center justify-center gap-ds-2 py-ds-8">
                    <DsSpinner size={20} className="text-ds-accent"/>
                    <span className="text-ds-small text-ds-text-secondary">加载中...</span>
                </div>
            ) : !result ? (
                <div className="text-ds-small text-ds-text-muted text-center py-ds-8">暂无预览数据</div>
            ) : result.rows.length === 0 ? (
                <div className="text-ds-small text-ds-text-muted text-center py-ds-8">该表暂无数据</div>
            ) : (
                <div>
                    <div className="text-ds-small text-ds-text-secondary mb-ds-2">
                        共 {result.rowCount} 行（最多展示 100 行）
                    </div>
                    <div className="border border-ds-border-subtle rounded-ds-sm overflow-auto">
                        <table className="w-full text-left">
                            <thead className="bg-ds-bg-hover sticky top-0">
                            <tr>
                                {result.columns.map((col) => (
                                    <th key={col}
                                        className="px-ds-3 py-ds-2 text-ds-caption text-ds-text-primary font-semibold whitespace-nowrap">
                                        {col}
                                    </th>
                                ))}
                            </tr>
                            </thead>
                            <tbody>
                            {result.rows.map((row, idx) => (
                                <tr key={idx} className="border-t border-ds-border-subtle">
                                    {result.columns.map((col) => (
                                        <td key={col}
                                            className="px-ds-3 py-ds-2 text-ds-small text-ds-text-secondary whitespace-nowrap">
                                            {row[col] === null || row[col] === undefined ? 'NULL' : String(row[col])}
                                        </td>
                                    ))}
                                </tr>
                            ))}
                            </tbody>
                        </table>
                    </div>
                </div>
            )}
        </DsModal>
    );
}
