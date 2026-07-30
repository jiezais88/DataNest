import type {DataSourceType} from '../constants/datasource';
import DatabaseTypeIcon from './DatabaseTypeIcon';

const TYPE_STYLES: Record<DataSourceType, { bg: string; text: string; border: string }> = {
    MYSQL: {bg: 'bg-gray-50', text: 'text-blue-700', border: 'border-gray-100'},
    POSTGRESQL: {bg: 'bg-gray-50', text: 'text-indigo-700', border: 'border-gray-100'},
    DORIS: {bg: 'bg-gray-50', text: 'text-cyan-700', border: 'border-gray-100'},
    ORACLE: {bg: 'bg-gray-50', text: 'text-red-700', border: 'border-gray-100'},
    SQLSERVER: {bg: 'bg-gray-50', text: 'text-yellow-700', border: 'border-gray-100'},
};

export default function TypeBadge({type}: { type: DataSourceType }) {
    const style = TYPE_STYLES[type] || {bg: 'bg-gray-50', text: 'text-gray-600', border: 'border-gray-100'};
    return (
        <span
            className={`inline-flex items-center gap-ds-1 px-ds-2 py-ds-1 rounded-ds-full text-ds-small font-medium border ${style.bg} ${style.text} ${style.border}`}
        >
            <DatabaseTypeIcon type={type} size={14} showLabel={true}/>
        </span>
    );
}
