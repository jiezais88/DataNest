// API 配置表单（创建向导「配置接口」步骤与编辑页共用）。
// 选表形态（一期）：暴露 + 参数化筛选 + 排序 + 分页。
// 自定义 SQL 形态（Sprint 13）：返回列由 SQL 决定，隐藏字段裁剪与外部排序（PRD D7/D9），
// 保留名称/路径/分页，并展示 SQL 参数 + 分页参数的 API 预览。
import {useMemo} from 'react';
import {HiOutlineExclamationTriangle} from 'react-icons/hi2';
import DsFilterSelect from '@/components/DsFilterSelect';
import {normalizePathInput} from './apiConfig';
import type {ApiColumnRow, ApiConfigValue} from './apiConfig';
import type {CustomSqlParamDef} from '@/types/data-service';

const INPUT_CLASS =
    'w-full px-ds-3 py-ds-2 border border-ds-border-subtle rounded-ds-sm text-ds-body focus:outline-none focus:border-ds-accent focus:ring-1 focus:ring-ds-accent transition-colors duration-ds-fast disabled:bg-ds-bg-hover disabled:text-ds-text-muted';

const FILTER_OPTIONS = [
    {value: '', label: '不筛选'},
    {value: 'EQ', label: '等值（=）'},
    {value: 'RANGE', label: '范围（min/max）'},
];

export default function ApiConfigForm({columns, value, onChange, queryType, sqlParams}: {
    columns: ApiColumnRow[];
    value: ApiConfigValue;
    onChange: (next: ApiConfigValue) => void;
    /** 查询定义形态（Sprint 13）；CUSTOM_SQL 时隐藏字段裁剪/排序 */
    queryType?: 'TABLE_SELECT' | 'CUSTOM_SQL';
    /** 自定义 SQL 参数（CUSTOM_SQL 形态，用于 API 预览拼参数串） */
    sqlParams?: CustomSqlParamDef[];
}) {
    const isCustomSql = queryType === 'CUSTOM_SQL';
    const set = <K extends keyof ApiConfigValue>(key: K, v: ApiConfigValue[K]) => onChange({...value, [key]: v});

    const exposedSet = useMemo(() => new Set(value.exposedFields), [value.exposedFields]);
    const allExposed = columns.length > 0 && columns.every((c) => exposedSet.has(c.name));

    const toggleField = (name: string, checked: boolean) => {
        const next = checked ? [...value.exposedFields, name] : value.exposedFields.filter((f) => f !== name);
        set('exposedFields', next);
    };

    const toggleAll = (checked: boolean) => {
        set('exposedFields', checked ? columns.map((c) => c.name) : []);
    };

    const orderFieldOptions = useMemo(() => [
        {value: '', label: '无排序'},
        ...columns.map((c) => ({value: c.name, label: c.name})),
    ], [columns]);

    // API 预览参数串：SQL 参数 + 分页参数（对齐原型 view-create3）
    const previewParams = useMemo(() => {
        const parts: string[] = [];
        (sqlParams ?? []).forEach((p) => {
            const sample = p.defaultValue ?? (p.type === 'DATE' || p.type === 'DATETIME' ? '2026-01-01' : p.type === 'LONG' || p.type === 'DECIMAL' ? '1' : '示例');
            parts.push(`${p.name}=${sample}`);
        });
        if (value.paginated) parts.push('page=1', 'pageSize=20');
        return parts.join('&');
    }, [sqlParams, value.paginated]);

    return (
        <div className="flex flex-col gap-ds-5">
            <div className="grid grid-cols-2 gap-ds-4">
                <div>
                    <label className="block text-ds-small text-ds-text-secondary mb-1">
                        API 名称 <span className="text-ds-danger">*</span>
                    </label>
                    <input
                        value={value.name}
                        onChange={(e) => set('name', e.target.value)}
                        placeholder="例如：订单区域统计"
                        maxLength={100}
                        className={INPUT_CLASS}
                    />
                </div>
                <div>
                    <label className="block text-ds-small text-ds-text-secondary mb-1">
                        API 路径 <span className="text-ds-danger">*</span>
                    </label>
                    <div className="flex items-stretch">
                        <span
                            className="inline-flex items-center px-ds-3 border border-r-0 border-ds-border-subtle rounded-l-ds-sm bg-ds-bg-hover text-ds-small text-ds-text-muted font-mono whitespace-nowrap">
                            /open-api/v1/
                        </span>
                        <input
                            value={normalizePathInput(value.path)}
                            onChange={(e) => set('path', e.target.value)}
                            placeholder={isCustomSql ? 'region-sum' : 'orders'}
                            className={`${INPUT_CLASS} rounded-l-none font-mono`}
                        />
                    </div>
                    <p className="text-ds-caption text-ds-text-muted mt-1">小写字母/数字开头，可含 - _；调用方法固定 GET</p>
                </div>
            </div>

            {isCustomSql && (
                <div>
                    <label className="block text-ds-small text-ds-text-secondary mb-1">请求方法</label>
                    <div className="flex items-center gap-ds-2">
                        <span
                            className="px-ds-2 py-0.5 rounded text-ds-caption font-bold bg-ds-accent-light text-ds-accent">GET</span>
                        <span className="text-ds-caption text-ds-text-muted">自定义 SQL API 固定为 GET（只读定位）</span>
                    </div>
                </div>
            )}

            {!isCustomSql && (
                <div>
                    <div className="flex items-center justify-between mb-1">
                        <label className="block text-ds-small text-ds-text-secondary">
                            暴露字段与参数化筛选 <span className="text-ds-danger">*</span>
                        </label>
                        <span className="text-ds-caption text-ds-text-muted">
                            已暴露 {value.exposedFields.length} / {columns.length} 个字段
                        </span>
                    </div>
                    <div className="border border-ds-border-subtle rounded-ds-sm overflow-hidden">
                        <div
                            className="flex items-center gap-ds-3 px-ds-3 py-ds-2 bg-ds-bg-hover text-ds-caption font-semibold text-ds-text-muted">
                            <input
                                type="checkbox"
                                checked={allExposed}
                                onChange={(e) => toggleAll(e.target.checked)}
                                aria-label="全选暴露字段"
                                className="accent-ds-accent"
                            />
                            <span className="w-40">暴露</span>
                            <span className="flex-1">字段</span>
                            <span className="w-36">参数化筛选</span>
                        </div>
                        <div className="max-h-[280px] overflow-y-auto">
                            {columns.length === 0 && (
                                <p className="text-ds-small text-ds-text-muted text-center py-ds-4">请先选择数据表</p>
                            )}
                            {columns.map((col) => (
                                <label key={col.name}
                                       className="flex items-center gap-ds-3 px-ds-3 py-ds-2 border-t border-ds-border-subtle hover:bg-ds-bg-hover cursor-pointer">
                                    <input
                                        type="checkbox"
                                        checked={exposedSet.has(col.name)}
                                        onChange={(e) => toggleField(col.name, e.target.checked)}
                                        className="accent-ds-accent"
                                    />
                                    <span className="w-40 text-ds-small text-ds-text-muted">
                                        {exposedSet.has(col.name) ? '暴露' : '不暴露'}
                                    </span>
                                    <span className="flex-1 min-w-0">
                                        <span className="text-ds-small text-ds-text-primary font-mono">{col.name}</span>
                                        <span className="text-ds-caption text-ds-text-muted ml-ds-2">
                                            {col.dataType || ''}{col.comment ? ` · ${col.comment}` : ''}
                                        </span>
                                    </span>
                                    <span className="w-36" onClick={(e) => e.preventDefault()}>
                                        <DsFilterSelect
                                            value={value.filterTypes[col.name] || ''}
                                            onChange={(v) => set('filterTypes', {
                                                ...value.filterTypes,
                                                [col.name]: v as '' | 'EQ' | 'RANGE',
                                            })}
                                            aria-label={`字段 ${col.name} 的筛选方式`}
                                            options={FILTER_OPTIONS}
                                            className="min-w-0 w-full py-ds-1"
                                        />
                                    </span>
                                </label>
                            ))}
                        </div>
                    </div>
                    <p className="text-ds-caption text-ds-text-muted mt-1">
                        等值筛选生成参数 field=value；范围筛选生成 min_field / max_field 两个参数；多个条件 AND 组合
                    </p>
                </div>
            )}

            <div className={`grid gap-ds-4 ${isCustomSql ? 'grid-cols-1' : 'grid-cols-3'}`}>
                {!isCustomSql && (
                    <>
                        <div>
                            <label className="block text-ds-small text-ds-text-secondary mb-1">排序字段</label>
                            <DsFilterSelect
                                value={value.orderByField}
                                onChange={(v) => set('orderByField', v)}
                                aria-label="排序字段"
                                options={orderFieldOptions}
                                className="w-full"
                            />
                        </div>
                        <div>
                            <label className="block text-ds-small text-ds-text-secondary mb-1">排序方向</label>
                            <DsFilterSelect
                                value={value.orderByDir}
                                onChange={(v) => set('orderByDir', v as 'ASC' | 'DESC')}
                                aria-label="排序方向"
                                options={[{value: 'ASC', label: '升序（ASC）'}, {value: 'DESC', label: '降序（DESC）'}]}
                                className="w-full"
                                disabled={!value.orderByField}
                            />
                        </div>
                    </>
                )}
                <div>
                    <label className="block text-ds-small text-ds-text-secondary mb-1">分页</label>
                    <div className="flex items-center gap-ds-3 h-[38px]">
                        <label className="flex items-center gap-ds-2 text-ds-small text-ds-text-primary cursor-pointer">
                            <input
                                type="checkbox"
                                checked={value.paginated}
                                onChange={(e) => set('paginated', e.target.checked)}
                                className="accent-ds-accent"
                            />
                            启用分页
                        </label>
                        {value.paginated && (
                            <span className="flex items-center gap-ds-2 text-ds-small text-ds-text-muted">
                                pageSize 上限
                                <input
                                    type="number"
                                    min={1}
                                    max={1000}
                                    value={value.pageSizeMax}
                                    onChange={(e) => set('pageSizeMax', Number(e.target.value))}
                                    className="w-20 px-ds-2 py-ds-1 border border-ds-border-subtle rounded-ds-sm text-ds-small focus:outline-none focus:border-ds-accent"
                                />
                            </span>
                        )}
                    </div>
                    <p className="text-ds-caption text-ds-text-muted mt-1">
                        {isCustomSql ? 'page 从 1 起，pageSize 默认 20，上限 100' : 'page 从 1 起，pageSize 默认 10'}
                    </p>
                </div>
            </div>

            {/* API 预览（对齐原型 view-create3：路径 + SQL 参数 + 分页参数） */}
            <div>
                <label className="block text-ds-small text-ds-text-secondary mb-1">API 预览</label>
                <div
                    className="px-ds-3.5 py-ds-3 bg-ds-bg-root border border-ds-border-subtle rounded-ds-sm font-mono text-ds-small text-ds-text-primary break-all">
                    <span className="text-ds-accent font-bold">GET</span>{' '}
                    /open-api/v1/{normalizePathInput(value.path) || 'your-path'}{previewParams ? `?${previewParams}` : ''}
                </div>
                <p className="text-ds-caption text-ds-text-muted mt-1">
                    {isCustomSql
                        ? '自动生成 OpenAPI 文档：参数表（SQL 参数 + 分页参数）+ curl 调用示例'
                        : '按当前配置生成调用路径；参数化筛选与分页参数会自动并入文档'}
                </p>
            </div>

            {isCustomSql && (
                <div className="flex items-start gap-ds-2 px-ds-3 py-ds-2 rounded-ds-sm bg-ds-warning-light text-ds-warning text-ds-small">
                    <HiOutlineExclamationTriangle size={15} className="mt-0.5 flex-shrink-0"/>
                    <span>
                        已知边界：自定义 SQL 的返回列由 SQL 决定，不提供字段裁剪；请确保 SQL 未暴露敏感列（如手机号、身份证），
                        保存时后端会对涉及的每张表做敏感度与数据权限校验（fail-closed 整体拒绝）。
                    </span>
                </div>
            )}
        </div>
    );
}
