// 数据服务域共享徽章（Sprint 10 F2）：API 状态 / 表敏感度 / Key 状态。
import DsStatusBadge from '@/components/DsStatusBadge';
import type {DsStatusVariant} from '@/components/DsStatusBadge';
import {API_KEY_STATUS_LABEL, DATA_API_STATUS_LABEL, SENSITIVITY_LABEL} from '@/types/data-service';
import type {ApiKeyStatus, DataApiStatus, SensitivityLevel} from '@/types/data-service';

const API_STATUS_VARIANT: Record<DataApiStatus, DsStatusVariant> = {
    CREATED: 'accent',
    PUBLISHED: 'success',
    DISABLED: 'disabled',
};

export function DataApiStatusBadge({status}: { status?: DataApiStatus }) {
    if (!status) return <span className="text-ds-small text-ds-text-muted">—</span>;
    return <DsStatusBadge label={DATA_API_STATUS_LABEL[status] || status} variant={API_STATUS_VARIANT[status] || 'pending'}/>;
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
