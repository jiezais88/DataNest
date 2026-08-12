// API 创建向导第 2 步「配置接口」表单（创建向导与编辑页共用）。
// 字段清单一行三配：暴露（进入 API 响应）+ 参数化筛选（EQ 等值 / RANGE 范围）。
import {useMemo} from 'react';
import DsFilterSelect from '@/components/DsFilterSelect';
import {normalizePathInput} from './apiConfig';
import type {ApiColumnRow, ApiConfigValue} from './apiConfig';

const INPUT_CLASS =
    'w-full px-ds-3 py-ds-2 border border-ds-border-subtle rounded-ds-sm text-ds-body focus:outline-none focus:border-ds-accent focus:ring-1 focus:ring-ds-accent transition-colors duration-ds-fast disabled:bg-ds-bg-hover disabled:text-ds-text-muted';

const FILTER_OPTIONS = [
    {value: '', label: '不筛选'},
    {value: 'EQ', label: '等值（=）'},
    {value: 'RANGE', label: '范围（min/max）'},
];

export default function ApiConfigForm({columns, value, onChange}: {
    columns: ApiColumnRow[];
    value: ApiConfigValue;
    onChange: (next: ApiConfigValue) => void;
}) {
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
                            placeholder="orders"
                            className={`${INPUT_CLASS} rounded-l-none font-mono`}
                        />
                    </div>
                    <p className="text-ds-caption text-ds-text-muted mt-1">小写字母/数字开头，可含 - _；调用方法固定 GET</p>
                </div>
            </div>

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

            <div className="grid grid-cols-3 gap-ds-4">
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
                </div>
            </div>
        </div>
    );
}
