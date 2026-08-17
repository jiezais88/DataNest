// 数据服务域共享徽章（Sprint 10 F2 + Sprint 13 F1）：API 状态 / 表敏感度 / Key 状态 / 查询形态。
import DsStatusBadge from '@/components/DsStatusBadge';
import type {DsStatusVariant} from '@/components/DsStatusBadge';
import {API_KEY_STATUS_LABEL, DATA_API_STATUS_LABEL, SENSITIVITY_LABEL} from '@/types/data-service';
import type {ApiKeyStatus, DataApiQueryType, DataApiStatus, SensitivityLevel} from '@/types/data-service';

const API_STATUS_VARIANT: Record<DataApiStatus, DsStatusVariant> = {
    CREATED: 'accent',
    PUBLISHED: 'success',
    DISABLED: 'disabled',
};

export function DataApiStatusBadge({status}: { status?: DataApiStatus }) {
    if (!status) return <span className="text-ds-small text-ds-text-muted">—</span>;
    return <DsStatusBadge label={DATA_API_STATUS_LABEL[status] || status} variant={API_STATUS_VARIANT[status] || 'pending'}/>;
}

/** 查询定义形态徽章（Sprint 13）：自定义 SQL = indigo 徽章 / 选表 = 灰徽章（对齐原型 badge.indigo / badge.gray） */
export function DataApiQueryTypeBadge({queryType}: { queryType?: DataApiQueryType }) {
    if (!queryType || queryType === 'TABLE_SELECT') {
        return <span className="inline-flex items-center px-2.5 py-1 rounded-full text-ds-badge bg-ds-bg-hover text-ds-text-secondary">选表</span>;
    }
    return <span className="inline-flex items-center px-2.5 py-1 rounded-full text-ds-badge bg-ds-accent-light text-ds-accent">自定义 SQL</span>;
}

const SENSITIVITY_VARIANT: Record<SensitivityLevel, DsStatusVariant> = {
    PUBLIC: 'success',
    INTERNAL: 'warning',
    CONFIDENTIAL: 'danger',
};

/** 表敏感度徽章；level 为空（governance 降级）显示「未知」 */
export function SensitivityBadge({level}: { level?: string }) {
    if (!level) {
        return <DsStatusBadge label="未知" variant="pending"/>;
    }
    const lv = level as SensitivityLevel;
    return <DsStatusBadge label={SENSITIVITY_LABEL[lv] || level} variant={SENSITIVITY_VARIANT[lv] || 'pending'}/>;
}

const KEY_STATUS_VARIANT: Record<ApiKeyStatus, DsStatusVariant> = {
    ENABLED: 'success',
    DISABLED: 'disabled',
};

export function ApiKeyStatusBadge({status}: { status?: ApiKeyStatus }) {
    if (!status) return <span className="text-ds-small text-ds-text-muted">—</span>;
    return <DsStatusBadge label={API_KEY_STATUS_LABEL[status] || status} variant={KEY_STATUS_VARIANT[status] || 'pending'}/>;
}
