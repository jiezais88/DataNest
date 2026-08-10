import type {DataSourceType} from '@/constants/datasource';
import {DataSourceTypeEnum} from '@/constants/datasource';
import {SiMysql, SiPostgresql} from 'react-icons/si';
import {HiOutlineCircleStack} from 'react-icons/hi2';

export interface DatabaseTypeIconProps {
    type: DataSourceType | string;
    size?: number;
    showLabel?: boolean;
    className?: string;
}

// 数据库品牌色，豁免 ds token 约束
const TYPE_COLORS: Record<DataSourceType, string> = {
    [DataSourceTypeEnum.MYSQL]: '#4479A1',
    [DataSourceTypeEnum.POSTGRESQL]: '#4169E1',
    [DataSourceTypeEnum.DORIS]: '#1E90FF',
    [DataSourceTypeEnum.ORACLE]: '#F80000',
    [DataSourceTypeEnum.SQLSERVER]: '#A91D22',
};

const TYPE_LABELS: Record<DataSourceType, string> = {
    [DataSourceTypeEnum.MYSQL]: 'MySQL',
    [DataSourceTypeEnum.POSTGRESQL]: 'PostgreSQL',
    [DataSourceTypeEnum.DORIS]: 'Doris',
    [DataSourceTypeEnum.ORACLE]: 'Oracle',
    [DataSourceTypeEnum.SQLSERVER]: 'SQL Server',
};

function OracleIcon({size = 16, color}: { size?: number; color: string }) {
    return (
        <svg width={size} height={size} viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <circle cx="12" cy="12" r="9" stroke={color} strokeWidth="2.5"/>
            <path
                d="M8.5 16c.8-4.2 2.2-6.8 3.5-6.8s2.7 2.6 3.5 6.8"
                stroke={color}
                strokeWidth="2"
                strokeLinecap="round"
                fill="none"
            />
        </svg>
    );
}

function SqlServerIcon({size = 16, color}: { size?: number; color: string }) {
    return (
        <svg width={size} height={size} viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path
                d="M12 2L20 7v10l-8 5-8-5V7l8-5z"
                fill={color}
                opacity="0.15"
            />
            <path
                d="M12 2L20 7v10l-8 5-8-5V7l8-5z"
                stroke={color}
                strokeWidth="1.8"
                strokeLinejoin="round"
                fill="none"
            />
            <path
                d="M12 7v10M7 9.5l10 6M17 9.5L7 15.5"
                stroke={color}
                strokeWidth="1.5"
                strokeLinecap="round"
            />
        </svg>
    );
}

function DorisIcon({size = 16, color}: { size?: number; color: string }) {
    return (
        <svg width={size} height={size} viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <ellipse cx="12" cy="6" rx="7" ry="3" stroke={color} strokeWidth="2" fill="none"/>
            <path
                d="M5 6v12c0 1.66 3.13 3 7 3s7-1.34 7-3V6"
                stroke={color}
                strokeWidth="2"
                strokeLinecap="round"
                fill="none"
            />
            <path
                d="M5 12c0 1.66 3.13 3 7 3s7-1.34 7-3"
                stroke={color}
                strokeWidth="2"
                strokeLinecap="round"
                fill="none"
            />
        </svg>
    );
}

function IconForType({type, size}: { type: DataSourceType; size: number }) {
    const color = TYPE_COLORS[type];
    switch (type) {
        case DataSourceTypeEnum.MYSQL:
            return <SiMysql size={size} color={color}/>;
        case DataSourceTypeEnum.POSTGRESQL:
            return <SiPostgresql size={size} color={color}/>;
        case DataSourceTypeEnum.ORACLE:
            return <OracleIcon size={size} color={color}/>;
        case DataSourceTypeEnum.SQLSERVER:
            return <SqlServerIcon size={size} color={color}/>;
        case DataSourceTypeEnum.DORIS:
            return <DorisIcon size={size} color={color}/>;
        default:
            return <HiOutlineCircleStack size={size} color={color}/>;
    }
}

export default function DatabaseTypeIcon({type, size = 16, showLabel = true, className = ''}: DatabaseTypeIconProps) {
    const normalizedType = (type?.toUpperCase() || '') as DataSourceType;
    const label = TYPE_LABELS[normalizedType] || type;

    return (
        <span
            className={`inline-flex items-center gap-ds-1 ${className}`}
            title={label}
            aria-label={label}
        >
            <IconForType type={normalizedType} size={size}/>
            {showLabel && <span>{label}</span>}
        </span>
    );
}

export {TYPE_COLORS, TYPE_LABELS};
