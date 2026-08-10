import type {DataSourceType} from '@/constants/datasource';
import DatabaseTypeIcon from './DatabaseTypeIcon';

// 数据源类型色走 ds-type-* token（品牌色体系，Phase 7-K），
// 背景/边框用中性 token，类型身份通过品牌色文字表达。
const TYPE_STYLES: Record<DataSourceType, string> = {
    MYSQL: 'text-ds-type-mysql',
    POSTGRESQL: 'text-ds-type-postgresql',
    DORIS: 'text-ds-type-doris',
    ORACLE: 'text-ds-type-oracle',
    SQLSERVER: 'text-ds-type-sqlserver',
};

export default function TypeBadge({type}: { type: DataSourceType }) {
    const style = TYPE_STYLES[type] || 'text-ds-text-secondary';
    return (
        <span
            className={`inline-flex items-center gap-ds-1 px-2.5 py-1 rounded-full text-ds-badge whitespace-nowrap border border-ds-border-subtle bg-ds-bg-hover ${style}`}
        >
            <DatabaseTypeIcon type={type} size={14} showLabel={true}/>
        </span>
    );
}
