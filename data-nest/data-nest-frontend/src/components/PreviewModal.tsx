import {HiOutlineXMark} from 'react-icons/hi2';
import type {PreviewResult} from '../api/preview';

interface PreviewModalProps {
    open: boolean;
    title: string;
    loading: boolean;
    result: PreviewResult | null;
    onClose: () => void;
}

export default function PreviewModal({open, title, loading, result, onClose}: PreviewModalProps) {
    if (!open) return null;

    return (
        <div className="fixed inset-0 z-ds-dialog flex items-center justify-center p-ds-6">
            <div className="absolute inset-0 bg-black/30" onClick={onClose}/>
            <div className="relative bg-ds-bg-surface rounded-ds-md shadow-ds-xl flex flex-col w-[900px] max-h-[85vh]">
                <div className="flex items-center justify-between px-ds-5 py-ds-4 border-b border-ds-border-subtle">
                    <h3 className="text-ds-subhead text-ds-text-primary font-semibold">{title}</h3>
                    <button
                        onClick={onClose}
                        className="p-1 text-ds-text-muted hover:text-ds-text-primary hover:bg-ds-bg-hover rounded transition-colors"
                        aria-label="关闭"
                    >
                        <HiOutlineXMark size={20}/>
                    </button>
                </div>
                <div className="flex-1 overflow-auto p-ds-5">
                    {loading ? (
                        <div className="flex items-center justify-center gap-ds-2 py-ds-8">
                            <svg className="animate-spin h-5 w-5 text-ds-accent" xmlns="http://www.w3.org/2000/svg"
                                 fill="none" viewBox="0 0 24 24">
                                <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor"
                                        strokeWidth="4"/>
                                <path className="opacity-75" fill="currentColor"
                                      d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"/>
                            </svg>
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
                </div>
            </div>
        </div>
    );
}
