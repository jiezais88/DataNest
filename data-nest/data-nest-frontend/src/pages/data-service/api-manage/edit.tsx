// API 编辑页（Sprint 10 F2）：名称/路径/参数/字段/排序/分页可改；数据源/库/表绑定不可改（换表 = 新建 API）。
import {useEffect, useState} from 'react';
import {useNavigate, useParams} from 'react-router-dom';
import {HiOutlineChevronLeft} from 'react-icons/hi2';
import {notify} from '@/utils/notify';
import {getDataApi, updateDataApi} from '@/api/data-service';
import {
    listMetadataColumns,
    listMetadataTables,
    listMetadataTablesWithoutSchema,
} from '@/api/metadata';
import DsButton from '@/components/DsButton';
import DsSpinner from '@/components/DsSpinner';
import {DataApiStatusBadge, SensitivityBadge} from '../badges';
import ApiConfigForm from './ApiConfigForm';
import {
    buildFilters,
    buildOrderBy,
    validateApiConfig,
} from './apiConfig';
import type {ApiColumnRow, ApiConfigValue} from './apiConfig';
import type {DataApiDetail} from '@/types/data-service';

export default function ApiEditPage() {
    const {id} = useParams<{ id: string }>();
    const navigate = useNavigate();

    const [detail, setDetail] = useState<DataApiDetail | null>(null);
    const [columns, setColumns] = useState<ApiColumnRow[]>([]);
    const [loading, setLoading] = useState(true);
    const [config, setConfig] = useState<ApiConfigValue | null>(null);
    const [saving, setSaving] = useState(false);

    useEffect(() => {
        if (!id) return;
        setLoading(true);
        getDataApi(id)
            .then(async (res) => {
                const d = res.data;
                setDetail(d);

                // 列清单：优先 metadataTableId 直取；缺失时按 数据源+库+表 反查元数据
                let cols: ApiColumnRow[] = [];
                try {
                    let tableId = d.metadataTableId;
                    if (!tableId) {
                        const tables = d.schemaName
                            ? (await listMetadataTables(d.datasourceId, d.databaseName, d.schemaName)).data ?? []
                            : (await listMetadataTablesWithoutSchema(d.datasourceId, d.databaseName)).data ?? [];
                        tableId = tables.find((t) => t.tableName === d.tableName)?.id;
                    }
                    if (tableId) {
                        cols = ((await listMetadataColumns(tableId)).data ?? []).map((c) => ({
                            name: c.columnName,
                            dataType: c.dataType,
                            comment: c.columnComment || c.manualComment,
                        }));
                    }
                } catch {
                    // 元数据不可用：按当前定义退化展示
                }
                if (cols.length === 0) {
                    const names = new Set<string>([...(d.definition?.fields ?? [])]);
                    (d.definition?.filters ?? []).forEach((f) => names.add(f.field));
                    cols = [...names].map((name) => ({name}));
                }
                setColumns(cols);

                const filterTypes: ApiConfigValue['filterTypes'] = {};
                (d.definition?.filters ?? []).forEach((f) => {
                    filterTypes[f.field] = f.type;
                });
                const [orderByField, orderByDir] = (d.orderBy || '').trim().split(/\s+/);
                setConfig({
                    name: d.name,
                    path: d.path,
                    orderByField: orderByField || '',
                    orderByDir: orderByDir?.toUpperCase() === 'ASC' ? 'ASC' : 'DESC',
                    paginated: d.paginated !== 0,
                    pageSizeMax: d.pageSizeMax ?? 100,
                    // fields 空 = 全部字段：展开为全列勾选，与创建态语义一致
                    exposedFields: d.definition?.fields?.length ? d.definition.fields : cols.map((c) => c.name),
                    filterTypes,
                });
            })
            .catch(() => {
                // 拦截器已提示
            })
            .finally(() => setLoading(false));
    }, [id]);

    const handleSave = async () => {
        if (!id || !config) return;
        const err = validateApiConfig(config);
        if (err) {
            notify.warning(err);
            return;
        }
        setSaving(true);
        try {
            await updateDataApi(id, {
                name: config.name.trim(),
                path: config.path,
                filters: buildFilters(config),
                fields: config.exposedFields,
                orderBy: buildOrderBy(config),
                paginated: config.paginated ? 1 : 0,
                pageSizeMax: config.pageSizeMax,
            });
            notify.success('API 已保存');
            navigate(`/data-service/api-manage/${id}`);
        } catch {
            // 拦截器已提示
        } finally {
            setSaving(false);
        }
    };

    if (loading) {
        return <p className="text-ds-small text-ds-text-muted text-center py-ds-8"><DsSpinner size={16}/> 加载中…</p>;
    }
    if (!detail || !config) {
        return (
            <div className="text-center py-ds-8">
                <p className="text-ds-small text-ds-text-muted mb-ds-3">API 不存在或已删除</p>
                <DsButton variant="secondary" onClick={() => navigate('/data-service/api-manage')}>返回列表</DsButton>
            </div>
        );
    }

    const qualifiedTable = `${detail.databaseName}${detail.schemaName ? `.${detail.schemaName}` : ''}.${detail.tableName}`;

    return (
        <div className="flex flex-col">
            <div className="flex items-center justify-between mb-ds-5 flex-shrink-0">
                <div>
                    <h1 className="text-ds-display text-ds-text-primary">编辑 API</h1>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1">
                        API 与数据表的绑定创建后不可更换；需要换表时请新建 API。
                    </p>
                </div>
                <DsButton variant="secondary" onClick={() => navigate(`/data-service/api-manage/${id}`)}>
                    <HiOutlineChevronLeft size={14}/>
                    返回详情
                </DsButton>
            </div>

            {/* 只读来源信息 */}
            <div
                className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle p-ds-4 mb-ds-4 flex items-center gap-ds-6 flex-wrap">
                <span className="flex items-center gap-ds-2">
                    <DataApiStatusBadge status={detail.status}/>
                    <SensitivityBadge level={detail.sensitivityLevel}/>
                </span>
                <span className="text-ds-small text-ds-text-secondary">
                    数据源 <span className="text-ds-text-primary font-medium">{detail.datasourceName || '—'}</span>
                </span>
                <span className="text-ds-small text-ds-text-secondary">
                    数据表 <span className="text-ds-text-primary font-mono font-medium">{qualifiedTable}</span>
                </span>
            </div>

            <div className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle p-ds-6">
                <ApiConfigForm columns={columns} value={config} onChange={setConfig}/>
            </div>

            <div className="flex items-center justify-end gap-ds-2 mt-ds-4">
                <DsButton variant="secondary" onClick={() => navigate(`/data-service/api-manage/${id}`)}
                          disabled={saving}>
                    取消
                </DsButton>
                <DsButton onClick={handleSave} loading={saving} disabled={saving}>
                    保存
                </DsButton>
            </div>
        </div>
    );
}
