import type {ReactNode} from 'react';

/**
 * 全局统一统计卡（对齐原型 stat-strip：圆角图标 + 大数值 + 小标签）。
 * 用于列表页顶部「一屏健康度」：运行态实体（CDC 管道/同步/采集/数据源/质量/DAG）补统计，
 * 配置类页面（用户/标准/模板）不加。
 *
 * 用法：
 * ```tsx
 * <StatsCards columns={4} loading={loading} items={[
 *     {label: '运行中', value: stats?.running ?? '—', icon: <HiOutlineBolt size={20}/>,
 *      iconClass: 'bg-ds-accent-light text-ds-accent', tip: '悬浮说明',
 *      onClick: () => setStatusFilter('RUNNING'), active: statusFilter === 'RUNNING'},
 * ]}/>
 * ```
 */
export interface StatsCardItem {
    label: string;
    /** 大数值：支持数字 / 字符串 / ReactNode */
    value: ReactNode;
    icon: ReactNode;
    /** 图标底色（如 'bg-ds-accent-light text-ds-accent'） */
    iconClass: string;
    /** 数值颜色（如 'text-ds-danger' / 'text-ds-success'，默认 text-ds-text-primary） */
    valueClass?: string;
    /** 悬浮说明（title 属性） */
    tip?: string;
    /** 点击回调（可点击下钻：点击卡片联动列表筛选） */
    onClick?: () => void;
    /** 激活态（当前列表筛选命中该卡片维度时高亮边框） */
    active?: boolean;
}

export default function StatsCards({items, columns = 4, loading = false}: {
    items: StatsCardItem[];
    /** 网格列数（默认 4；合规 3、CDC 4） */
    columns?: number;
    /** 加载中：数值统一显示 '…' */
    loading?: boolean;
}) {
    return (
        <div className="grid gap-ds-4 mb-ds-4 flex-shrink-0"
             style={{gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))`}}>
            {items.map((item) => (
                <div key={item.label}
                     role={item.onClick ? 'button' : undefined}
                     tabIndex={item.onClick ? 0 : undefined}
                     title={item.tip}
                     onClick={item.onClick}
                     onKeyDown={item.onClick ? (e) => {
                         if (e.key === 'Enter' || e.key === ' ') {
                             e.preventDefault();
                             item.onClick?.();
                         }
                     } : undefined}
                     className={`bg-ds-bg-surface border rounded-ds-md p-ds-4 flex items-center gap-ds-3 ${
                         item.active
                             ? 'border-ds-accent ring-1 ring-ds-accent'
                             : 'border-ds-border-subtle'
                     } ${item.onClick ? 'cursor-pointer transition-shadow hover:shadow-ds-md' : ''}`}>
                    <div className={`w-10 h-10 rounded-ds-md flex items-center justify-center flex-shrink-0 ${item.iconClass}`}>
                        {item.icon}
                    </div>
                    <div className="min-w-0">
                        <div className={`text-ds-heading font-bold leading-tight ${item.valueClass ?? 'text-ds-text-primary'}`}>
                            {loading ? '…' : item.value}
                        </div>
                        <div className="text-ds-tiny text-ds-text-muted mt-ds-1">{item.label}</div>
                    </div>
                </div>
            ))}
        </div>
    );
}
