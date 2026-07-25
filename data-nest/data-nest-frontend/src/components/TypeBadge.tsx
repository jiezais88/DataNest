import type {DataSourceType} from '../types/datasource';

const TYPE_STYLES: Record<DataSourceType, { label: string; bg: string; text: string }> = {
    MYSQL: {label: 'MySQL', bg: 'bg-blue-50', text: 'text-blue-700'},
    POSTGRESQL: {label: 'PostgreSQL', bg: 'bg-indigo-50', text: 'text-indigo-700'},
    DORIS: {label: 'Doris', bg: 'bg-cyan-50', text: 'text-cyan-700'},
};

export default function TypeBadge({type}: { type: DataSourceType }) {
    const style = TYPE_STYLES[type] || {label: type, bg: 'bg-gray-50', text: 'text-gray-600'};
    return (
        <span
            className={`inline-flex items-center px-ds-2 py-ds-1 rounded-ds-full text-ds-small font-medium ${style.bg} ${style.text}`}>
            {style.label}
        </span>
    );
}
