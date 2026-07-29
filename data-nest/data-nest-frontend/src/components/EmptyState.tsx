import {HiOutlineInbox} from 'react-icons/hi2';

interface EmptyStateProps {
    title?: string;
    description?: string;
    action?: React.ReactNode;
}

export default function EmptyState({
                                       title = '暂无数据',
                                       description = '当前列表为空，你可以从上方创建第一条记录开始。',
                                       action,
                                   }: EmptyStateProps) {
    return (
        <div className="flex flex-col items-center justify-center py-ds-16 text-center">
            <div className="w-14 h-14 rounded-full bg-ds-accent-light flex items-center justify-center mb-ds-4">
                <HiOutlineInbox size={28} className="text-ds-accent"/>
            </div>
            <h3 className="text-ds-body font-semibold text-ds-text-primary mb-ds-1">{title}</h3>
            <p className="text-ds-small text-ds-text-muted max-w-[320px] mb-ds-4">{description}</p>
            {action && <div className="mb-ds-4">{action}</div>}
        </div>
    );
}
