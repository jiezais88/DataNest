// API 编辑页（Sprint 10 F2 + Sprint 13 F1）。
// 选表形态（一期）：名称/路径/参数/字段/排序/分页可改；数据源/库/表绑定不可改（换表 = 新建 API）。
// 自定义 SQL 形态（Sprint 13）：SQL 文本/参数表可改（改后提示重新校验，保存时后端重新校验 + 过闸门 + 更新血缘）。
import {useCallback, useEffect, useState} from 'react';
import {useNavigate, useParams} from 'react-router-dom';
import {HiOutlineChevronLeft, HiOutlineExclamationTriangle} from 'react-icons/hi2';
import {notify} from '@/utils/notify';
import {getDataApi, listSqlDatasources, updateDataApi} from '@/api/data-service';
import {
    listMetadataColumns,
    listMetadataTables,
    listMetadataTablesWithoutSchema,
} from '@/api/metadata';
import DsButton from '@/components/DsButton';
import DsSpinner from '@/components/DsSpinner';
import {DataApiQueryTypeBadge, DataApiStatusBadge, SensitivityBadge} from '../badges';
import ApiConfigForm from './ApiConfigForm';
import CustomSqlForm from './CustomSqlForm';
import {
    buildFilters,
    buildOrderBy,
    validateApiConfig,
} from './apiConfig';
import {clientCheckReadOnly, scanSqlParams} from './customSql';
import type {CustomSqlState} from './customSql';
import type {ApiColumnRow, ApiConfigValue} from './apiConfig';
import type {DataApiDetail, DataApiQueryType, SqlDatasource} from '@/types/data-service';

export default function ApiEditPage() {
    const {id} = useParams<{ id: string }>();
    const navigate = useNavigate();

    const [detail, setDetail] = useState<DataApiDetail | null>(null);
    const [columns, setColumns] = useState<ApiColumnRow[]>([]);
    const [datasources, setDatasources] = useState<SqlDatasource[]>([]);
    const [loading, setLoading] = useState(true);
    const [config, setConfig] = useState<ApiConfigValue | null>(null);
    const [customSql, setCustomSql] = useState<CustomSqlState | null>(null);
    const [saving, setSaving] = useState(false);

    // 自定义 SQL 编辑：数据源下拉展示用（数据源创建后不可更换）
    useEffect(() => {
        listSqlDatasources()
            .then((res) => setDatasources(res.data ?? []))
            .catch(() => {
                // 拦截器已提示
            });
    }, []);

    useEffect(() => {
        if (!id) return;
        setLoading(true);
        getDataApi(id)
            .then(async (res) => {
                const d = res.data;
                setDetail(d);
                const isCustomSql = d.queryType === 'CUSTOM_SQL';

                if (!isCustomSql) {
                    // 选表形态：列清单优先 metadataTableId 直取；缺失时按 数据源+库+表 反查元数据
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
                    return;
                }

                // 自定义 SQL 形态：SQL/参数/数据源回填，已落库 SQL 视为已通过校验
                setCustomSql({
                    datasourceId: d.datasourceId,
                    sqlText: d.sqlText ?? '',
                    sqlParams: d.sqlParams ?? [],
                    involvedTables: (d.involvedTables ?? []).map((t) =>
                        [t.database, t.schema, t.table].filter(Boolean).join('.') || t.table),
                    validated: true,
                    validateMessage: '已保存的 SQL 定义；修改后请重新校验，保存时系统会再次校验并检查权限',
                    dirty: false,
                });
                setConfig({
                    name: d.name,
                    path: d.path,
                    orderByField: '',
                    orderByDir: 'DESC',
                    paginated: d.paginated !== 0,
                    pageSizeMax: d.pageSizeMax ?? 100,
                    exposedFields: [],
                    filterTypes: {},
                });
            })
            .catch(() => {
                // 拦截器已提示
            })
            .finally(() => setLoading(false));
    }, [id]);

    const validateCustomSql = useCallback((state: CustomSqlState): string | null => {
        if (!state.datasourceId) return '请选择数据源';
        if (!state.sqlText.trim()) return '请编写只读 SQL';
        const err = clientCheckReadOnly(state.sqlText);
        if (err) return err;
        if (!state.validated) return 'SQL 已修改，请先点击「校验 SQL」通过后再保存';
        const placeholders = scanSqlParams(state.sqlText);
        const defs = new Set(state.sqlParams.map((p) => p.name));
        const missing = placeholders.filter((n) => !defs.has(n));
        if (missing.length > 0) return `参数 ${missing.map((n) => `:${n}`).join('、')} 未定义，请先校验 SQL`;
        return null;
    }, []);

    const handleSave = async () => {
        if (!id || !config) return;
        const isCustomSql = detail?.queryType === 'CUSTOM_SQL';
        if (isCustomSql) {
            if (!customSql) return;
            const err = validateCustomSql(customSql);
            if (err) {
                notify.warning(err);
                return;
            }
        } else {
            const err = validateApiConfig(config);
            if (err) {
                notify.warning(err);
                return;
            }
        }
        setSaving(true);
        try {
            await updateDataApi(id, {
                name: config.name.trim(),
                path: config.path,
                ...(isCustomSql
                    ? {
                        queryType: 'CUSTOM_SQL' as DataApiQueryType,
                        sqlText: customSql!.sqlText.trim(),
                        sqlParams: customSql!.sqlParams,
                    }
                    : {
                        filters: buildFilters(config),
                        fields: config.exposedFields,
                        orderBy: buildOrderBy(config),
                    }),
                paginated: config.paginated ? 1 : 0,
                pageSizeMax: config.pageSizeMax,
            });
            notify.success('API 已保存');
            navigate(`/data-service/api-manage/${id}`);
        } catch {
            // 拦截器已提示（含 9016~9018 自定义 SQL 校验/闸门错误）
        } finally {
            setSaving(false);
        }
    };

    if (loading) {
        return <p className="text-ds-small text-ds-text-muted text-center py-ds-8"><DsSpinner size={16}/> 加载中…</p>;
    }
    if (!detail || !config || (detail.queryType === 'CUSTOM_SQL' && !customSql)) {
        return (
            <div className="text-center py-ds-8">
                <p className="text-ds-small text-ds-text-muted mb-ds-3">API 不存在或已删除</p>
                <DsButton variant="secondary" onClick={() => navigate('/data-service/api-manage')}>返回列表</DsButton>
            </div>
        );
    }

    const isCustomSql = detail.queryType === 'CUSTOM_SQL';
    const qualifiedTable = `${detail.databaseName}${detail.schemaName ? `.${detail.schemaName}` : ''}.${detail.tableName}`;

    return (
        <div className="flex flex-col">
            <div className="flex items-center justify-between mb-ds-5 flex-shrink-0">
                <div>
                    <h1 className="text-ds-display text-ds-text-primary">编辑 API</h1>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1">
                        {isCustomSql
                            ? '自定义 SQL 形态：可修改 SQL 与参数定义，保存时系统将再次校验（只读/参数/权限）并更新血缘。'
                            : 'API 与数据表的绑定创建后不可更换；需要换表时请新建 API。'}
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
                    <DataApiQueryTypeBadge queryType={detail.queryType}/>
                    <DataApiStatusBadge status={detail.status}/>
                    <SensitivityBadge level={detail.sensitivityLevel}/>
                </span>
                <span className="text-ds-small text-ds-text-secondary">
                    数据源 <span className="text-ds-text-primary font-medium">{detail.datasourceName || '—'}</span>
                </span>
                {!isCustomSql && (
                    <span className="text-ds-small text-ds-text-secondary">
                        数据表 <span className="text-ds-text-primary font-mono font-medium">{qualifiedTable}</span>
                    </span>
                )}
                {isCustomSql && customSql && (
                    <span className="text-ds-small text-ds-text-secondary">
                        涉及表 <span className="text-ds-text-primary font-mono font-medium">
                            {customSql.involvedTables.length ? customSql.involvedTables.join(' · ') : '—'}
                        </span>
                    </span>
                )}
            </div>

            {/* 自定义 SQL：改后提示重新校验 */}
            {isCustomSql && customSql?.dirty && (
                <div className="flex items-start gap-ds-2 px-ds-3 py-ds-2 rounded-ds-sm bg-ds-warning-light text-ds-warning text-ds-small mb-ds-4">
                    <HiOutlineExclamationTriangle size={15} className="mt-0.5 flex-shrink-0"/>
                    <span>SQL 或参数已修改，请点击「校验 SQL」通过后再保存；保存时系统会再次校验并检查敏感度/数据权限。</span>
                </div>
            )}

            <div className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle p-ds-6">
                {isCustomSql && customSql ? (
                    <div className="flex flex-col gap-ds-6">
                        <CustomSqlForm value={customSql} onChange={setCustomSql} datasources={datasources} readOnly/>
                        <div className="border-t border-ds-border-subtle pt-ds-5">
                            <ApiConfigForm
                                columns={[]}
                                value={config}
                                onChange={setConfig}
                                queryType="CUSTOM_SQL"
                                sqlParams={customSql.sqlParams}
                            />
                        </div>
                    </div>
                ) : (
                    <ApiConfigForm columns={columns} value={config} onChange={setConfig}/>
                )}
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
