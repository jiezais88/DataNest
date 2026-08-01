import {useMemo} from 'react';
import {HiChevronLeft, HiChevronRight} from 'react-icons/hi2';
import DsFilterSelect from './DsFilterSelect';

export interface PaginationProps {
    page: number;
    pageSize: number;
    total: number;
    pageSizeOptions?: number[];
    onChange: (page: number, pageSize: number) => void;
}

export default function Pagination({
                                       page,
                                       pageSize,
                                       total,
                                       pageSizeOptions = [10, 20, 50],
                                       onChange,
                                   }: PaginationProps) {
    const totalPages = useMemo(() => Math.max(1, Math.ceil(total / pageSize)), [total, pageSize]);

    const pages = useMemo(() => {
        if (totalPages <= 7) {
            return Array.from({length: totalPages}, (_, i) => i + 1);
        }

        const items: (number | 'ellipsis')[] = [1];

        if (page > 4) {
            items.push('ellipsis');
        }

        const start = Math.max(2, page - 2);
        const end = Math.min(totalPages - 1, page + 2);

        for (let i = start; i <= end; i++) {
            items.push(i);
        }

        if (page < totalPages - 3) {
            items.push('ellipsis');
        }

        items.push(totalPages);
        return items;
    }, [page, totalPages]);

    const handlePageClick = (nextPage: number) => {
        if (nextPage < 1 || nextPage > totalPages || nextPage === page) return;
        onChange(nextPage, pageSize);
    };

    const handlePageSizeChange = (nextPageSize: number) => {
        onChange(1, nextPageSize);
    };

    if (total === 0) return null;

    return (
        <div
            className="flex shrink-0 items-center justify-between gap-ds-4 px-ds-4 py-ds-3 border-t border-ds-border-subtle">
            <div className="flex items-center gap-ds-3 text-ds-small text-ds-text-secondary">
                <span>共 {total} 条</span>
                <div className="flex items-center gap-ds-2">
                    <span>每页</span>
                    <DsFilterSelect
                        value={String(pageSize)}
                        onChange={(v) => handlePageSizeChange(Number(v))}
                        options={pageSizeOptions.map((option) => ({value: String(option), label: String(option)}))}
                        aria-label="每页条数"
                        className="min-w-0 pl-ds-2 pr-7 py-ds-1"
                    />
                    <span>条</span>
                </div>
            </div>

            <div className="flex items-center gap-ds-1">
                <button
                    onClick={() => handlePageClick(page - 1)}
                    disabled={page <= 1}
                    className="flex items-center gap-ds-1 px-ds-2 py-ds-1.5 text-ds-small text-ds-text-secondary rounded-ds-sm hover:bg-ds-bg-hover disabled:opacity-40 disabled:cursor-not-allowed disabled:hover:bg-transparent transition-colors"
                >
                    <HiChevronLeft size={14}/>
                    上一页
                </button>

                {pages.map((item, index) => {
                    if (item === 'ellipsis') {
                        return (
                            <span
                                key={`ellipsis-${index}`}
                                className="px-ds-2 py-ds-1.5 text-ds-small text-ds-text-muted"
                            >
                                ...
                            </span>
                        );
                    }

                    const isActive = item === page;
                    return (
                        <button
                            key={item}
                            onClick={() => handlePageClick(item)}
                            aria-current={isActive ? 'page' : undefined}
                            aria-label={`第 ${item} 页`}
                            className={`min-w-[32px] px-ds-2 py-ds-1.5 text-ds-small font-medium rounded-ds-sm transition-colors ${
                                isActive
                                    ? 'bg-ds-accent text-white'
                                    : 'text-ds-text-secondary hover:bg-ds-bg-hover'
                            }`}
                        >
                            {item}
                        </button>
                    );
                })}

                <button
                    onClick={() => handlePageClick(page + 1)}
                    disabled={page >= totalPages}
                    className="flex items-center gap-ds-1 px-ds-2 py-ds-1.5 text-ds-small text-ds-text-secondary rounded-ds-sm hover:bg-ds-bg-hover disabled:opacity-40 disabled:cursor-not-allowed disabled:hover:bg-transparent transition-colors"
                >
                    下一页
                    <HiChevronRight size={14}/>
                </button>
            </div>
        </div>
    );
}
