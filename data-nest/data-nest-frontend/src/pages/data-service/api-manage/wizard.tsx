// API 创建向导（Sprint 10 F2）：3 步 —— 选择数据表 → 配置接口 → 绑定 API Key。
// 选表即生成接口雏形（右侧 API 预览：路径 + 暴露字段勾选）；机密表禁选、内部表提示需超级管理员特批开放。
import {useCallback, useEffect, useMemo, useState} from 'react';
import {useNavigate, useSearchParams} from 'react-router-dom';
import {Tooltip} from 'antd';
import {
    HiOutlineCheckCircle,
    HiOutlineChevronLeft,
    HiOutlineChevronRight,
    HiOutlineKey,
    HiOutlineLockClosed,
    HiOutlineTableCells,
} from 'react-icons/hi2';
import {notify} from '@/utils/notify';
import {getErrorMessage} from '@/utils/error';
import {createApiKey, createDataApi, getApiKey, listSqlDatasources, pageApiKeys, updateApiKey} from '@/api/data-service';
import {
    listMetadataColumns,
    listMetadataDatabases,
    listMetadataSchemas,
    listMetadataTables,
    listMetadataTablesWithoutSchema,
} from '@/api/metadata';
import {isWithoutSchema} from '@/constants/datasource';
import DsButton from '@/components/DsButton';
import DsFilterSelect from '@/components/DsFilterSelect';
import DsSpinner from '@/components/DsSpinner';
import DsModal from '@/components/DsModal';
import {SensitivityBadge} from '../badges';
import ApiConfigForm from './ApiConfigForm';
import {
    buildFilters,
    buildOrderBy,
    derivePathSegment,
    normalizePathInput,
    validateApiConfig,
} from './apiConfig';
import type {ApiColumnRow, ApiConfigValue} from './apiConfig';
import type {MetadataTable} from '@/types/metadata';
import type {ApiKeyCreateResult, ApiKeyPageItem, SqlDatasource} from '@/types/data-service';

const STEPS = ['选择数据表', '配置接口', '绑定 API Key'];

type BindMode = 'none' | 'existing' | 'new';

export default function ApiCreateWizardPage() {
    const navigate = useNavigate();
    const [step, setStep] = useState(0);
    const [searchParams] = useSearchParams();
    const preset = useMemo(() => {
        const table = searchParams.get('table');
        if (!table) return null;
        return {
            datasourceId: searchParams.get('datasourceId'),
            database: searchParams.get('database'),
            schema: searchParams.get('schema'),
            table,
        };
    }, [searchParams]);

    // ============ 第 1 步：选表 ============
    const [datasources, setDatasources] = useState<SqlDatasource[]>([]);
    const [datasourceId, setDatasourceId] = useState('');
    const [databases, setDatabases] = useState<string[]>([]);
    const [databaseName, setDatabaseName] = useState('');
    const [schemas, setSchemas] = useState<string[]>([]);
    const [schemaName, setSchemaName] = useState('');
    const [tables, setTables] = useState<MetadataTable[]>([]);
    const [tablesLoading, setTablesLoading] = useState(false);
    const [selectedTable, setSelectedTable] = useState<MetadataTable | null>(null);
    const [columns, setColumns] = useState<ApiColumnRow[]>([]);
    const [columnsLoading, setColumnsLoading] = useState(false);

    // ============ 第 2 步：接口配置 ============
    const [config, setConfig] = useState<ApiConfigValue>({
        name: '',
        path: '',
        orderByField: '',
        orderByDir: 'DESC',
        paginated: true,
        pageSizeMax: 100,
        exposedFields: [],
        filterTypes: {},
    });

    // ============ 第 3 步：绑定 Key ============
    const [bindMode, setBindMode] = useState<BindMode>('none');
    const [enabledKeys, setEnabledKeys] = useState<ApiKeyPageItem[]>([]);
    const [keysLoading, setKeysLoading] = useState(false);
    const [selectedKeyIds, setSelectedKeyIds] = useState<string[]>([]);
    const [newKeyName, setNewKeyName] = useState('');
    const [newKeyQps, setNewKeyQps] = useState(50);

    const [submitting, setSubmitting] = useState(false);
    const [createdKey, setCreatedKey] = useState<ApiKeyCreateResult | null>(null);
    const [createdApiId, setCreatedApiId] = useState<string | null>(null);

    const currentDatasource = useMemo(
        () => datasources.find((d) => d.id === datasourceId),
        [datasources, datasourceId],
    );
    const needSchema = currentDatasource ? !isWithoutSchema(currentDatasource.type) : false;
    const dsDisplayName = (ds: SqlDatasource) => (ds.builtin ? 'Doris 数仓' : `${ds.name}（${ds.type}）`);

    // 数据源下拉（含内置 Doris）
    useEffect(() => {
        listSqlDatasources()
            .then((res) => {
                const list = res.data ?? [];
                setDatasources(list);
                const doris = list.find((d) => d.builtin);
                if (preset?.datasourceId && list.some(d => d.id === preset.datasourceId)) {
                    setDatasourceId(preset.datasourceId);
                } else if (doris) {
                    setDatasourceId(doris.id);
                }
            })
            .catch(() => {
                // 拦截器已提示
            });
    }, []);

    // 数据源 → 库列表
    useEffect(() => {
        if (!datasourceId) return;
        setDatabases([]);
        setDatabaseName('');
        setSchemas([]);
        setSchemaName('');
        setTables([]);
        setSelectedTable(null);
        listMetadataDatabases(datasourceId)
            .then((res) => {
                const dbs = res.data ?? [];
                setDatabases(dbs);
                if (preset?.database && dbs.includes(preset.database)) {
                    setDatabaseName(preset.database);
                } else if (dbs.length > 0) {
                    setDatabaseName(dbs[0]);
                }
            })
            .catch(() => {
                // 拦截器已提示
            });
    }, [datasourceId]);

    // 库 → schema 列表（PG/Oracle/SQLServer）或直接查表（MySQL/Doris）
    useEffect(() => {
        if (!datasourceId || !databaseName) return;
        setSchemas([]);
        setSchemaName('');
        setTables([]);
        setSelectedTable(null);
        if (needSchema) {
            listMetadataSchemas(datasourceId, databaseName)
                .then((res) => {
                    const list = res.data ?? [];
                    setSchemas(list);
                    if (preset?.schema && list.includes(preset.schema)) {
                        setSchemaName(preset.schema);
                    } else if (list.length > 0) {
                        setSchemaName(list[0]);
                    }
                })
                .catch(() => {
                    // 拦截器已提示
                });
        }
    }, [datasourceId, databaseName, needSchema]);

    // 库/schema → 表列表（含敏感度；机密表禁选）
    useEffect(() => {
        if (!datasourceId || !databaseName) return;
        if (needSchema && !schemaName) return;
        setTablesLoading(true);
        setSelectedTable(null);
        const fetch = needSchema
            ? listMetadataTables(datasourceId, databaseName, schemaName)
            : listMetadataTablesWithoutSchema(datasourceId, databaseName);
        fetch
            .then((res) => setTables(res.data ?? []))
            .catch(() => setTables([]))
            .finally(() => setTablesLoading(false));
    }, [datasourceId, databaseName, schemaName, needSchema]);


    // 选表 → 列清单 + 生成接口雏形（默认名称/路径/全字段暴露）
    const handleSelectTable = useCallback((table: MetadataTable) => {
        setSelectedTable(table);
        setColumns([]);
        setColumnsLoading(true);
        listMetadataColumns(table.id)
            .then((res) => {
                const cols: ApiColumnRow[] = (res.data ?? []).map((c) => ({
                    name: c.columnName,
                    dataType: c.dataType,
                    comment: c.columnComment || c.manualComment,
                }));
                setColumns(cols);
                setConfig((prev) => ({
                    ...prev,
                    name: table.tableComment || table.tableName,
                    path: derivePathSegment(table.tableName),
                    exposedFields: cols.map((c) => c.name),
                    filterTypes: {},
                    orderByField: '',
                }));
            })
            .catch(() => {
                // 拦截器已提示
            })
            .finally(() => setColumnsLoading(false));
    }, []);
    // 资产详情页「生成 API」跳转预填：表列表加载后自动选中 URL 指定表（机密表不预选）
    useEffect(() => {
        if (!preset) return;
        const target = tables.find(t => t.tableName === preset.table && t.sensitivityLevel !== 'CONFIDENTIAL');
        if (target && selectedTable?.id !== target.id) {
            handleSelectTable(target);
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [tables, selectedTable, handleSelectTable, preset]);

    // 进入第 3 步时加载启用态 Key
    useEffect(() => {
        if (step !== 2) return;
        setKeysLoading(true);
        pageApiKeys({page: 1, pageSize: 100, status: 'ENABLED'})
            .then((res) => setEnabledKeys(res.data.records ?? []))
            .catch(() => setEnabledKeys([]))
            .finally(() => setKeysLoading(false));
    }, [step]);

    const goNext = () => {
        if (step === 0) {
            if (!selectedTable) {
                notify.warning('请先选择数据表');
                return;
            }
            setStep(1);
            return;
        }
        if (step === 1) {
            const err = validateApiConfig(config);
            if (err) {
                notify.warning(err);
                return;
            }
            setStep(2);
        }
    };

    const handleSubmit = async () => {
        const err = validateApiConfig(config);
        if (err) {
            notify.warning(err);
            return;
        }
        if (bindMode === 'new' && !newKeyName.trim()) {
            notify.warning('请填写新 Key 名称');
            return;
        }
        if (!selectedTable) return;
        setSubmitting(true);
        try {
            const res = await createDataApi({
                name: config.name.trim(),
                path: normalizePathInput(config.path),
                datasourceId,
                databaseName,
                schemaName: needSchema ? schemaName : undefined,
                tableName: selectedTable.tableName,
                metadataTableId: selectedTable.id,
                filters: buildFilters(config),
                fields: config.exposedFields,
                orderBy: buildOrderBy(config),
                paginated: config.paginated ? 1 : 0,
                pageSizeMax: config.pageSizeMax,
            });
            const apiId = res.data.id;
            setCreatedApiId(apiId);

            // 绑定 Key：失败不阻断创建结果，提示后到详情页处理
            if (bindMode === 'existing' && selectedKeyIds.length > 0) {
                try {
                    for (const keyId of selectedKeyIds) {
                        const key = (await getApiKey(keyId)).data;
                        await updateApiKey(keyId, {
                            name: key.name,
                            qpsLimit: key.qpsLimit,
                            apiIds: [...key.apiIds, apiId],
                        });
                    }
                } catch (bindErr) {
                    notify.warning(`API 已创建，但绑定 Key 失败：${getErrorMessage(bindErr)}`);
                }
            }
            if (bindMode === 'new') {
                try {
                    const keyRes = await createApiKey({
                        name: newKeyName.trim(),
                        qpsLimit: newKeyQps,
                        apiIds: [apiId],
                    });
                    setCreatedKey(keyRes.data);
                    return; // 明文弹窗关闭后再跳详情
                } catch (keyErr) {
                    notify.warning(`API 已创建，但新建 Key 失败：${getErrorMessage(keyErr)}`);
                }
            }
            notify.success(`API「${res.data.name}」已创建（未发布）`);
            navigate(`/data-service/api-manage/${apiId}`);
        } catch {
            // 拦截器已提示（含敏感度 9004 / 路径重复 9010 等）
        } finally {
            setSubmitting(false);
        }
    };

    const copyCreatedKey = async () => {
        if (!createdKey) return;
        try {
            await navigator.clipboard.writeText(createdKey.apiKey);
            notify.success('Key 已复制到剪贴板');
        } catch {
            notify.warning('复制失败，请检查浏览器剪贴板权限');
        }
    };

    // ============ 渲染 ============
    return (
        <div className="flex flex-col">
            <div className="flex items-center justify-between mb-ds-5 flex-shrink-0">
                <div>
                    <h1 className="text-ds-display text-ds-text-primary">新建 API</h1>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1">
                        三步完成：选择数据表 → 配置调用方式 → 绑定调用凭证（Key）。选好数据表，右侧即可预览生成的 API。
                    </p>
                </div>
                <DsButton variant="secondary" onClick={() => navigate('/data-service/api-manage')}>
                    <HiOutlineChevronLeft size={14}/>
                    返回列表
                </DsButton>
            </div>

            {/* 步骤条 */}
            <div className="flex items-center gap-ds-2 mb-ds-4 flex-shrink-0">
                {STEPS.map((label, idx) => (
                    <div key={label} className="flex items-center gap-ds-2">
                        {idx > 0 && <div className="w-10 h-px bg-ds-border-strong"/>}
                        <div className={`flex items-center gap-ds-2 px-ds-3 py-ds-2 rounded-ds-sm ${
                            idx === step ? 'bg-ds-accent-light text-ds-accent font-semibold'
                                : idx < step ? 'text-ds-success' : 'text-ds-text-muted'
                        }`}>
                            <span className={`w-5 h-5 rounded-full text-ds-caption flex items-center justify-center ${
                                idx === step ? 'bg-ds-accent text-white'
                                    : idx < step ? 'bg-ds-success-light text-ds-success' : 'bg-ds-bg-hover text-ds-text-muted'
                            }`}>
                                {idx < step ? <HiOutlineCheckCircle size={14}/> : idx + 1}
                            </span>
                            <span className="text-ds-small">{label}</span>
                        </div>
                    </div>
                ))}
            </div>

            <div className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle p-ds-6">
                {step === 0 && (
                    <div className="grid grid-cols-2 gap-ds-6">
                        {/* 左：数据源/库/表 */}
                        <div>
                            <h3 className="text-ds-small font-semibold text-ds-text-primary mb-ds-3">
                                <HiOutlineTableCells className="inline mr-1 text-ds-accent" size={14}/>
                                数据源
                                <span className="text-ds-caption text-ds-text-muted font-normal ml-ds-2">选择数据表，作为接口的数据来源</span>
                            </h3>
                            <div className="flex flex-col gap-ds-3">
                                <div>
                                    <label className="block text-ds-small text-ds-text-secondary mb-1">
                                        数据源 <span className="text-ds-danger">*</span>
                                    </label>
                                    <DsFilterSelect
                                        value={datasourceId}
                                        onChange={setDatasourceId}
                                        aria-label="数据源"
                                        options={datasources.map((d) => ({value: d.id, label: dsDisplayName(d)}))}
                                        className="w-full"
                                    />
                                </div>
                                <div className={`grid ${needSchema ? 'grid-cols-2' : 'grid-cols-1'} gap-ds-3`}>
                                    <div>
                                        <label className="block text-ds-small text-ds-text-secondary mb-1">
                                            数据库 <span className="text-ds-danger">*</span>
                                        </label>
                                        <DsFilterSelect
                                            value={databaseName}
                                            onChange={setDatabaseName}
                                            aria-label="数据库"
                                            options={databases.map((d) => ({value: d, label: d}))}
                                            className="w-full"
                                            disabled={databases.length === 0}
                                        />
                                    </div>
                                    {needSchema && (
                                        <div>
                                            <label className="block text-ds-small text-ds-text-secondary mb-1">Schema</label>
                                            <DsFilterSelect
                                                value={schemaName}
                                                onChange={setSchemaName}
                                                aria-label="Schema"
                                                options={schemas.map((s) => ({value: s, label: s}))}
                                                className="w-full"
                                                disabled={schemas.length === 0}
                                            />
                                        </div>
                                    )}
                                </div>
                                <div className="border border-ds-border-subtle rounded-ds-sm overflow-hidden">
                                    <div
                                        className="flex items-center justify-between px-ds-3 py-ds-2 bg-ds-bg-hover text-ds-caption font-semibold text-ds-text-muted">
                                        <span>数据表（单选）<span className="text-ds-danger">*</span></span>
                                        <span>{tables.length} 张</span>
                                    </div>
                                    <div className="max-h-[260px] overflow-y-auto">
                                        {tablesLoading && (
                                            <p className="text-ds-small text-ds-text-muted text-center py-ds-4">
                                                <DsSpinner size={14}/> 加载中…
                                            </p>
                                        )}
                                        {!tablesLoading && tables.length === 0 && (
                                            <p className="text-ds-small text-ds-text-muted text-center py-ds-4">
                                                暂无元数据表，请先在「元数据管理」采集该数据源
                                            </p>
                                        )}
                                        {tables.map((t) => {
                                            const confidential = t.sensitivityLevel === 'CONFIDENTIAL';
                                            const selected = selectedTable?.id === t.id;
                                            return (
                                                <label
                                                    key={t.id}
                                                    className={`flex items-center gap-ds-2 px-ds-3 py-ds-2 border-t border-ds-border-subtle ${
                                                        confidential ? 'opacity-55 cursor-not-allowed'
                                                            : `cursor-pointer hover:bg-ds-bg-hover ${selected ? 'bg-ds-accent-light' : ''}`
                                                    }`}
                                                >
                                                    <input
                                                        type="radio"
                                                        name="api-table"
                                                        checked={selected}
                                                        disabled={confidential}
                                                        onChange={() => handleSelectTable(t)}
                                                        className="accent-ds-accent"
                                                    />
                                                    <span className="flex-1 min-w-0 truncate">
                                                        <span className="text-ds-small text-ds-text-primary font-mono">{t.tableName}</span>
                                                        {t.tableComment && (
                                                            <span
                                                                className="text-ds-caption text-ds-text-muted ml-ds-2">{t.tableComment}</span>
                                                        )}
                                                    </span>
                                                    {confidential ? (
                                                        <Tooltip title="表敏感度为机密，禁止对外提供 API">
                                                            <span className="inline-flex items-center gap-1">
                                                                <HiOutlineLockClosed size={12} className="text-ds-danger"/>
                                                                <SensitivityBadge level={t.sensitivityLevel}/>
                                                            </span>
                                                        </Tooltip>
                                                    ) : (
                                                        <SensitivityBadge level={t.sensitivityLevel || 'PUBLIC'}/>
                                                    )}
                                                </label>
                                            );
                                        })}
                                    </div>
                                </div>
                                {selectedTable?.sensitivityLevel === 'INTERNAL' && (
                                    <p className="text-ds-caption text-ds-warning">
                                        该表为内部级数据，需超级管理员在「数据分级分类」中特批开放后才能生成 API。
                                    </p>
                                )}
                            </div>
                        </div>

                        {/* 右：API 预览 */}
                        <div>
                            <h3 className="text-ds-small font-semibold text-ds-text-primary mb-ds-3">
                                <HiOutlineKey className="inline mr-1 text-ds-accent" size={14}/>
                                API 预览
                                <span className="text-ds-caption text-ds-text-muted font-normal ml-ds-2">选好数据表即可预览</span>
                            </h3>
                            {!selectedTable ? (
                                <div
                                    className="border border-dashed border-ds-border-strong rounded-ds-sm p-ds-8 text-center text-ds-small text-ds-text-muted">
                                    选择左侧数据表后，这里实时预览生成的接口路径与暴露字段
                                </div>
                            ) : (
                                <div className="flex flex-col gap-ds-3">
                                    <div
                                        className="flex items-center gap-ds-2 border border-ds-border-subtle rounded-ds-sm px-ds-3 py-ds-2 bg-ds-bg-hover">
                                        <span
                                            className="px-ds-2 py-0.5 rounded text-ds-caption font-bold bg-ds-success-light text-ds-success">GET</span>
                                        <span
                                            className="text-ds-small text-ds-text-primary font-mono truncate">/open-api/v1/{derivePathSegment(selectedTable.tableName)}</span>
                                    </div>
                                    <p className="text-ds-caption text-ds-text-muted">按表名自动生成 API 路径与名称，下一步可修改</p>
                                    <div className="border border-ds-border-subtle rounded-ds-sm overflow-hidden">
                                        <div
                                            className="flex items-center justify-between px-ds-3 py-ds-2 bg-ds-bg-hover text-ds-caption font-semibold text-ds-text-muted">
                                            <span>暴露字段</span>
                                            <span>{columnsLoading ? '加载中…' : `默认暴露 ${columns.length} 个`}</span>
                                        </div>
                                        <div className="max-h-[240px] overflow-y-auto">
                                            {columns.map((c) => (
                                                <div key={c.name}
                                                     className="flex items-center gap-ds-2 px-ds-3 py-ds-1.5 border-t border-ds-border-subtle">
                                                    <HiOutlineCheckCircle size={13} className="text-ds-success"/>
                                                    <span
                                                        className="text-ds-small text-ds-text-primary font-mono">{c.name}</span>
                                                    <span
                                                        className="text-ds-caption text-ds-text-muted">{c.dataType || ''}{c.comment ? ` · ${c.comment}` : ''}</span>
                                                </div>
                                            ))}
                                        </div>
                                    </div>
                                    <p className="text-ds-caption text-ds-text-muted">
                                        公开字段默认全量暴露，下一步可裁剪字段并配置参数化筛选。
                                    </p>
                                </div>
                            )}
                        </div>
                    </div>
                )}

                {step === 1 && (
                    <ApiConfigForm columns={columns} value={config} onChange={setConfig}/>
                )}

                {step === 2 && (
                    <div className="flex flex-col gap-ds-4 max-w-[640px]">
                        <p className="text-ds-small text-ds-text-muted">
                            业务系统凭 Key 调用本 API；也可以稍后在「API Key 管理」中随时绑定。
                        </p>
                        {([
                            {value: 'none' as BindMode, label: '暂不绑定', desc: '创建后到 API 详情或 Key 管理页绑定'},
                            {value: 'existing' as BindMode, label: '绑定已有 Key', desc: '勾选一个或多个启用态 Key'},
                            {value: 'new' as BindMode, label: '新建 Key', desc: '创建即绑定本 API，完整 Key 仅展示一次'},
                        ]).map((opt) => (
                            <label key={opt.value}
                                   className={`flex items-start gap-ds-3 border rounded-ds-sm px-ds-4 py-ds-3 cursor-pointer ${
                                       bindMode === opt.value ? 'border-ds-accent bg-ds-accent-light' : 'border-ds-border-subtle hover:bg-ds-bg-hover'
                                   }`}>
                                <input
                                    type="radio"
                                    name="bind-mode"
                                    checked={bindMode === opt.value}
                                    onChange={() => setBindMode(opt.value)}
                                    className="accent-ds-accent mt-1"
                                />
                                <span>
                                    <span className="text-ds-small text-ds-text-primary font-medium">{opt.label}</span>
                                    <span className="block text-ds-caption text-ds-text-muted mt-0.5">{opt.desc}</span>
                                </span>
                            </label>
                        ))}

                        {bindMode === 'existing' && (
                            <div className="border border-ds-border-subtle rounded-ds-sm overflow-hidden">
                                <div
                                    className="px-ds-3 py-ds-2 bg-ds-bg-hover text-ds-caption font-semibold text-ds-text-muted">
                                    选择要绑定的 Key（{selectedKeyIds.length} 已选）
                                </div>
                                <div className="max-h-[220px] overflow-y-auto">
                                    {keysLoading && (
                                        <p className="text-ds-small text-ds-text-muted text-center py-ds-4">
                                            <DsSpinner size={14}/> 加载中…
                                        </p>
                                    )}
                                    {!keysLoading && enabledKeys.length === 0 && (
                                        <p className="text-ds-small text-ds-text-muted text-center py-ds-4">
                                            暂无启用态 Key，可选择「新建 Key」
                                        </p>
                                    )}
                                    {enabledKeys.map((k) => (
                                        <label key={k.id}
                                               className="flex items-center gap-ds-2 px-ds-3 py-ds-2 border-t border-ds-border-subtle hover:bg-ds-bg-hover cursor-pointer">
                                            <input
                                                type="checkbox"
                                                checked={selectedKeyIds.includes(k.id)}
                                                onChange={(e) => setSelectedKeyIds(
                                                    e.target.checked
                                                        ? [...selectedKeyIds, k.id]
                                                        : selectedKeyIds.filter((id) => id !== k.id),
                                                )}
                                                className="accent-ds-accent"
                                            />
                                            <span className="text-ds-small text-ds-text-primary">{k.name}</span>
                                            <span className="text-ds-caption text-ds-text-muted">QPS {k.qpsLimit}</span>
                                        </label>
                                    ))}
                                </div>
                            </div>
                        )}

                        {bindMode === 'new' && (
                            <div className="grid grid-cols-2 gap-ds-4">
                                <div>
                                    <label className="block text-ds-small text-ds-text-secondary mb-1">
                                        Key 名称 <span className="text-ds-danger">*</span>
                                    </label>
                                    <input
                                        value={newKeyName}
                                        onChange={(e) => setNewKeyName(e.target.value)}
                                        placeholder="例如：业务-订单组"
                                        maxLength={100}
                                        className="w-full px-ds-3 py-ds-2 border border-ds-border-subtle rounded-ds-sm text-ds-body focus:outline-none focus:border-ds-accent focus:ring-1 focus:ring-ds-accent"
                                    />
                                </div>
                                <div>
                                    <label className="block text-ds-small text-ds-text-secondary mb-1">
                                        限流 QPS <span className="text-ds-danger">*</span>
                                    </label>
                                    <input
                                        type="number"
                                        min={1}
                                        max={10000}
                                        value={newKeyQps}
                                        onChange={(e) => setNewKeyQps(Number(e.target.value))}
                                        className="w-full px-ds-3 py-ds-2 border border-ds-border-subtle rounded-ds-sm text-ds-body focus:outline-none focus:border-ds-accent focus:ring-1 focus:ring-ds-accent"
                                    />
                                    <p className="text-ds-caption text-ds-text-muted mt-1">该 Key 下所有 API 共享此总上限</p>
                                </div>
                            </div>
                        )}
                    </div>
                )}
            </div>

            {/* 底部操作条 */}
            <div className="flex items-center justify-end gap-ds-2 mt-ds-4">
                {step > 0 && (
                    <DsButton variant="secondary" onClick={() => setStep(step - 1)} disabled={submitting}>
                        <HiOutlineChevronLeft size={14}/>
                        上一步
                    </DsButton>
                )}
                {step < 2 ? (
                    <DsButton onClick={goNext}>
                        下一步
                        <HiOutlineChevronRight size={14}/>
                    </DsButton>
                ) : (
                    <DsButton onClick={handleSubmit} loading={submitting} disabled={submitting}>
                        完成创建
                    </DsButton>
                )}
            </div>

            {/* 新建 Key 明文一次性展示 */}
            <DsModal
                open={!!createdKey}
                onClose={() => {
                }}
                title="API Key 创建成功"
                width="w-[520px]"
                closable={false}
                maskClosable={false}
                footer={(
                    <DsButton onClick={() => navigate(`/data-service/api-manage/${createdApiId}`)}>
                        我已保存，前往 API 详情
                    </DsButton>
                )}
            >
                <p className="text-ds-small text-ds-text-secondary mb-ds-3">
                    Key「{createdKey?.name}」已创建并绑定本 API。完整 Key 仅在此展示一次，请立即复制并妥善保管。
                </p>
                <div
                    className="flex items-center gap-ds-2 border border-ds-accent rounded-ds-sm px-ds-3 py-ds-2 bg-ds-accent-light mb-ds-3">
                    <HiOutlineKey size={16} className="text-ds-accent"/>
                    <span
                        className="flex-1 text-ds-body text-ds-text-primary font-mono break-all">{createdKey?.apiKey}</span>
                    <DsButton variant="secondary" onClick={copyCreatedKey}>复制</DsButton>
                </div>
                <p className="text-ds-caption text-ds-warning">
                    关闭后将无法再次查看完整 Key；如怀疑泄露，可禁用后重新创建。
                </p>
            </DsModal>
        </div>
    );
}
