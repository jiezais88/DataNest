// Sprint 8 F2：CDC 管道配置向导（DI-04，整页 4 步：基本信息/源配置/目标配置/确认启动）。
// 新建 /engineering/cdc-pipelines/new；编辑 /engineering/cdc-pipelines/:id/edit（仅 STOPPED 可编辑，后端兜底）。
// 源仅支持 MySQL（binlog/ROW 预检）；目标 = Iceberg 湖仓（MinIO）→ Doris Iceberg Catalog 查询。
import {useCallback, useEffect, useMemo, useState} from 'react';
import {useNavigate, useParams} from 'react-router-dom';
import {Checkbox, Select, Spin} from 'antd';
import {
    HiOutlineCheckCircle,
    HiOutlineCircleStack,
    HiOutlineInformationCircle,
    HiOutlineServer,
    HiOutlineXCircle,
} from 'react-icons/hi2';
import {
    createCdcPipeline,
    getCdcPipeline,
    listCdcSourceDatabases,
    listCdcSourceTables,
    startCdcPipeline,
    updateCdcPipeline,
    validateCdcSource,
} from '../../../../api/cdc';
import {getDataSources} from '../../../../api/datasource';
import DsButton from '../../../../components/DsButton';
import DsStatusBadge from '../../../../components/DsStatusBadge';
import {DataSourceTypeEnum} from '../../../../constants/datasource';
import {notify} from '../../../../utils/notify';
import type {
    CdcPipelineSaveRequest,
    CdcSourceValidateResult,
    CdcStartupMode,
    CdcSyncMode,
    CdcTableMapping,
    CdcWriteMode,
} from '../../../../types/cdc';

const STEPS = ['基本信息', '源配置', '目标配置', '确认启动'];

const SYNC_MODE_LABEL: Record<CdcSyncMode, string> = {
    FULL_AND_INCREMENT: '全量 + 增量',
    INCREMENTAL_ONLY: '仅增量',
};
const STARTUP_MODE_LABEL: Record<CdcStartupMode, string> = {
    INITIAL: '全量快照 + 增量',
    LATEST_OFFSET: '从最新位点',
    EARLIEST_OFFSET: '从最早位点',
};
const WRITE_MODE_LABEL: Record<CdcWriteMode, string> = {
    UPSERT: 'Upsert（按主键）',
    APPEND: 'Append（仅追加）',
};

/** 表单项容器 */
function FormItem({label, required, hint, children}: {
    label: string;
    required?: boolean;
    hint?: React.ReactNode;
    children: React.ReactNode;
}) {
    return (
        <div className="mb-ds-5">
            <div className="text-ds-small font-semibold text-ds-text-primary mb-ds-2">
                {label}{required && <span className="text-ds-danger ml-0.5">*</span>}
            </div>
            {children}
            {hint && <div className="text-ds-tiny text-ds-text-muted mt-ds-1">{hint}</div>}
        </div>
    );
}

/** 单选卡片行（对齐原型 radio-row） */
function RadioRow({checked, onChange, label, hint, disabled}: {
    checked: boolean;
    onChange: () => void;
    label: string;
    hint: string;
    disabled?: boolean;
}) {
    return (
        <button
            type="button"
            onClick={onChange}
            disabled={disabled}
            className={`w-full flex items-center gap-ds-3 px-ds-3 py-ds-2 mb-ds-2 rounded-ds-sm border text-left transition-colors disabled:opacity-50 disabled:cursor-not-allowed ${
                checked ? 'border-ds-accent bg-ds-accent-light' : 'border-ds-border-subtle hover:border-ds-border-strong'
            }`}
        >
            <span className={`w-3.5 h-3.5 rounded-full border flex-shrink-0 flex items-center justify-center ${
                checked ? 'border-ds-accent' : 'border-ds-border-strong'
            }`}>
                {checked && <span className="w-2 h-2 rounded-full bg-ds-accent"/>}
            </span>
            <span className="text-ds-small text-ds-text-primary font-medium">{label}</span>
            <span className="text-ds-tiny text-ds-text-muted">{hint}</span>
        </button>
    );
}

const INPUT_CLASS = 'w-full px-ds-3 py-ds-2 text-ds-small border border-ds-border-subtle rounded-ds-sm outline-none focus:border-ds-accent bg-ds-bg-surface text-ds-text-primary';

export default function CdcPipelineWizardPage() {
    const {id} = useParams();
    const isEdit = !!id;
    const navigate = useNavigate();

    const [step, setStep] = useState(1);
    const [loadingDetail, setLoadingDetail] = useState(isEdit);
    const [saving, setSaving] = useState(false);

    // ============ 表单状态 ============
    const [name, setName] = useState('');
    const [description, setDescription] = useState('');
    const [datasourceId, setDatasourceId] = useState('');
    const [sourceDatabase, setSourceDatabase] = useState('');
    const [selectedTables, setSelectedTables] = useState<CdcTableMapping[]>([]);
    const [syncMode, setSyncMode] = useState<CdcSyncMode>('FULL_AND_INCREMENT');
    const [startupMode, setStartupMode] = useState<CdcStartupMode>('INITIAL');
    const [writeMode, setWriteMode] = useState<CdcWriteMode>('UPSERT');
    const [targetDatabase, setTargetDatabase] = useState('');

    // ============ 下拉数据 ============
    const [datasourceOptions, setDatasourceOptions] = useState<{ value: string; label: string }[]>([]);
    const [databaseOptions, setDatabaseOptions] = useState<{ value: string; label: string }[]>([]);
    const [sourceTables, setSourceTables] = useState<{ tableName: string; tableRows?: string }[]>([]);
    const [tablesLoading, setTablesLoading] = useState(false);
    /** 源数据源名称缓存（确认页展示） */
    const [datasourceName, setDatasourceName] = useState('');

    // ============ 第 4 步预检 ============
    const [precheck, setPrecheck] = useState<CdcSourceValidateResult | null>(null);
    const [precheckLoading, setPrecheckLoading] = useState(false);

    // MySQL 数据源下拉（仅 MySQL 支持 CDC 源）
    useEffect(() => {
        getDataSources({type: DataSourceTypeEnum.MYSQL, page: 1, pageSize: 100})
            .then(res => {
                const records = res.data?.records ?? [];
                setDatasourceOptions(records.map(d => ({value: d.id, label: `${d.name}（${d.host}）`})));
            })
            .catch(() => setDatasourceOptions([]));
    }, []);

    // 编辑模式：加载详情预填
    useEffect(() => {
        if (!isEdit || !id) return;
        setLoadingDetail(true);
        getCdcPipeline(id)
            .then(p => {
                if (!p) return;
                setName(p.name);
                setDescription(p.description ?? '');
                setDatasourceId(p.sourceDatasourceId);
                setDatasourceName(p.sourceDatasourceName ?? '');
                setSourceDatabase(p.sourceDatabase);
                setSelectedTables(p.tables ?? []);
                setSyncMode(p.syncMode);
                setStartupMode(p.startupMode);
                setWriteMode(p.writeMode);
                setTargetDatabase(p.targetDatabase);
            })
            .catch(() => {
                // 拦截器已提示
            })
            .finally(() => setLoadingDetail(false));
    }, [isEdit, id]);

    // 数据源变化 → 拉库列表
    useEffect(() => {
        if (!datasourceId) {
            setDatabaseOptions([]);
            return;
        }
        listCdcSourceDatabases(datasourceId)
            .then(list => setDatabaseOptions((list ?? []).map(db => ({value: db, label: db}))))
            .catch(() => setDatabaseOptions([]));
    }, [datasourceId]);

    // 库变化 → 拉表列表
    useEffect(() => {
        if (!datasourceId || !sourceDatabase) {
            setSourceTables([]);
            return;
        }
        setTablesLoading(true);
        listCdcSourceTables(datasourceId, sourceDatabase)
            .then(list => setSourceTables(list ?? []))
            .catch(() => setSourceTables([]))
            .finally(() => setTablesLoading(false));
    }, [datasourceId, sourceDatabase]);

    // 同步模式联动启动位点：全量+增量固定 INITIAL（后端约束）；
    // 切到仅增量时默认 LATEST_OFFSET（残留 INITIAL 会被 Flink 当 initial 全量快照跑，静默违背用户意图）
    useEffect(() => {
        setStartupMode(syncMode === 'FULL_AND_INCREMENT' ? 'INITIAL' : 'LATEST_OFFSET');
    }, [syncMode]);

    const runPrecheck = useCallback(() => {
        if (!datasourceId) return;
        setPrecheckLoading(true);
        setPrecheck(null);
        validateCdcSource(datasourceId, sourceDatabase)
            .then(r => setPrecheck(r ?? null))
            .catch(() => setPrecheck(null))
            .finally(() => setPrecheckLoading(false));
    }, [datasourceId, sourceDatabase]);

    // ============ 步骤校验 ============
    const validateStep = (s: number): boolean => {
        if (s === 1 && !name.trim()) {
            notify.warning('请输入管道名称');
            return false;
        }
        if (s === 2) {
            if (!datasourceId) {
                notify.warning('请选择源数据源');
                return false;
            }
            if (!sourceDatabase) {
                notify.warning('请选择源数据库');
                return false;
            }
            if (selectedTables.length === 0) {
                notify.warning('请至少勾选一张同步表');
                return false;
            }
            // 防御：仅增量模式不允许残留 INITIAL（Flink 会按 initial 跑全量快照）
            if (syncMode === 'INCREMENTAL_ONLY' && startupMode === 'INITIAL') {
                notify.warning('仅增量模式请选择启动位点（从最新 / 从最早）');
                return false;
            }
        }
        if (s === 3) {
            if (!targetDatabase.trim()) {
                notify.warning('请输入目标库名');
                return false;
            }
            if (writeMode === 'UPSERT' && selectedTables.some(t => !t.primaryKey?.trim())) {
                notify.warning('Upsert 模式下每张表都必须配置主键列');
                return false;
            }
        }
        return true;
    };

    const goNext = () => {
        if (!validateStep(step)) return;
        const next = step + 1;
        setStep(next);
        if (next === 4) runPrecheck();
    };

    const goPrev = () => setStep(s => Math.max(1, s - 1));

    // ============ 表勾选/映射 ============
    const isTableSelected = (tableName: string) => selectedTables.some(t => t.sourceTable === tableName);

    const toggleTable = (tableName: string, checked: boolean) => {
        setSelectedTables(prev => checked
            ? [...prev, {sourceTable: tableName, targetTable: tableName, primaryKey: ''}]
            : prev.filter(t => t.sourceTable !== tableName));
    };

    const updateMapping = (sourceTable: string, patch: Partial<CdcTableMapping>) => {
        setSelectedTables(prev => prev.map(t => t.sourceTable === sourceTable ? {...t, ...patch} : t));
    };

    const allChecked = sourceTables.length > 0 && selectedTables.length === sourceTables.length;

    // ============ 提交 ============
    const buildRequest = (): CdcPipelineSaveRequest => ({
        name: name.trim(),
        description: description.trim() || undefined,
        sourceDatasourceId: datasourceId,
        sourceDatabase,
        targetDatabase: targetDatabase.trim(),
        syncMode,
        startupMode,
        writeMode,
        tables: selectedTables.map(t => ({
            sourceTable: t.sourceTable,
            targetTable: t.targetTable?.trim() || t.sourceTable,
            primaryKey: t.primaryKey?.trim() || undefined,
        })),
    });

    /** 仅保存（新建/编辑）→ 回列表 */
    const handleSaveOnly = async () => {
        setSaving(true);
        try {
            if (isEdit && id) {
                await updateCdcPipeline(id, buildRequest());
                notify.success('管道已保存（savepoint 已清空，下次启动从头跑）');
            } else {
                await createCdcPipeline(buildRequest());
                notify.success('管道已创建（未启动）');
            }
            navigate('/engineering/cdc-pipelines');
        } catch {
            // 拦截器已提示
        } finally {
            setSaving(false);
        }
    };

    /** 保存并启动（仅新建）：启动失败（8007 等）由拦截器提示，仍回列表看 ERROR 状态与 lastError */
    const handleSaveAndStart = async () => {
        setSaving(true);
        try {
            const created = await createCdcPipeline(buildRequest());
            if (!created) return;
            try {
                await startCdcPipeline(created.id);
                notify.success(`管道「${created.name}」已创建并启动`);
            } catch {
                // 启动失败：拦截器已提示，列表页可见 ERROR + lastError
            }
            navigate('/engineering/cdc-pipelines');
        } catch {
            // 创建失败：拦截器已提示
        } finally {
            setSaving(false);
        }
    };

    const dsLabel = useMemo(() => {
        if (datasourceName) return datasourceName;
        return datasourceOptions.find(o => o.value === datasourceId)?.label ?? '—';
    }, [datasourceName, datasourceOptions, datasourceId]);

    if (loadingDetail) {
        return (
            <div className="h-[320px] flex items-center justify-center">
                <Spin size="large"/>
            </div>
        );
    }

    return (
        <div className="flex flex-col">
            {/* 头部 */}
            <div className="flex items-center justify-between mb-ds-5">
                <div>
                    <DsButton variant="secondary" className="mb-ds-3"
                              onClick={() => navigate('/engineering/cdc-pipelines')}>
                        ← 返回
                    </DsButton>
                    <h1 className="text-ds-display text-ds-text-primary">
                        {isEdit ? '编辑 CDC 管道' : '新建 CDC 管道'}
                    </h1>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1">
                        {isEdit ? '仅停止状态可编辑；保存后清空 savepoint，下次启动按启动位点从头跑。'
                            : 'MySQL Binlog → Iceberg 湖仓（MinIO），Doris 经 Iceberg Catalog 查询。'}
                    </p>
                </div>
            </div>

            {/* 步骤条 */}
            <div
                className="flex items-center mb-ds-5 bg-ds-bg-surface border border-ds-border-subtle rounded-ds-md px-ds-6 py-ds-4">
                {STEPS.map((label, idx) => {
                    const num = idx + 1;
                    const done = num < step;
                    const active = num === step;
                    return (
                        <div key={label} className="flex items-center flex-1 last:flex-none">
                            <div className="flex items-center gap-ds-2 flex-shrink-0">
                                <span className={`w-6 h-6 rounded-full flex items-center justify-center text-ds-tiny font-bold ${
                                    done ? 'bg-ds-success text-white'
                                        : active ? 'bg-ds-accent text-white'
                                            : 'bg-ds-bg-hover text-ds-text-muted'
                                }`}>
                                    {done ? '✓' : num}
                                </span>
                                <span className={`text-ds-small ${
                                    active ? 'text-ds-accent font-semibold' : done ? 'text-ds-text-secondary' : 'text-ds-text-muted'
                                }`}>
                                    {label}
                                </span>
                            </div>
                            {num < STEPS.length && (
                                <div className={`flex-1 h-px mx-ds-3 ${done ? 'bg-ds-success' : 'bg-ds-border-subtle'}`}/>
                            )}
                        </div>
                    );
                })}
            </div>

            {/* 步骤内容 */}
            <div className="bg-ds-bg-surface border border-ds-border-subtle rounded-ds-md p-ds-6 mb-ds-4">
                {step === 1 && (
                    <div className="max-w-[560px]">
                        <FormItem label="管道名称" required hint="用于区分多个 CDC 管道，建议包含业务含义；平台内唯一。">
                            <input className={INPUT_CLASS} value={name} maxLength={100}
                                   placeholder="例如：订单实时同步"
                                   onChange={(e) => setName(e.target.value)}/>
                        </FormItem>
                        <FormItem label="管道描述" hint="描述管道用途 / 对接人 / 注意事项…">
                            <textarea className={`${INPUT_CLASS} resize-y`} rows={3} value={description}
                                      maxLength={500}
                                      placeholder="同步订单库到 Iceberg 湖仓，供实时分析使用"
                                      onChange={(e) => setDescription(e.target.value)}/>
                        </FormItem>
                    </div>
                )}

                {step === 2 && (
                    <div className="max-w-[720px]">
                        <FormItem label="源数据源" required hint="仅支持 MySQL 数据源；保存前将校验连通性与 binlog 开启状态。">
                            <Select
                                className="w-full"
                                placeholder="选择 MySQL 数据源"
                                value={datasourceId || undefined}
                                options={datasourceOptions}
                                onChange={(v) => {
                                    setDatasourceId(v);
                                    setDatasourceName('');
                                    setSourceDatabase('');
                                    setSelectedTables([]);
                                }}
                                aria-label="源数据源"
                            />
                        </FormItem>
                        <FormItem label="源数据库" required>
                            <Select
                                className="w-full"
                                placeholder={datasourceId ? '选择源数据库' : '请先选择源数据源'}
                                value={sourceDatabase || undefined}
                                options={databaseOptions}
                                disabled={!datasourceId}
                                onChange={(v) => {
                                    setSourceDatabase(v);
                                    setSelectedTables([]);
                                }}
                                aria-label="源数据库"
                            />
                        </FormItem>
                        <FormItem label="同步表" required>
                            <div className="border border-ds-border-subtle rounded-ds-sm overflow-hidden">
                                <div
                                    className="flex items-center gap-ds-2 px-ds-3 py-ds-2 bg-ds-bg-root border-b border-ds-border-subtle">
                                    <Checkbox
                                        checked={allChecked}
                                        indeterminate={selectedTables.length > 0 && !allChecked}
                                        disabled={sourceTables.length === 0}
                                        onChange={(e) => {
                                            if (e.target.checked) {
                                                setSelectedTables(sourceTables.map(t => ({
                                                    sourceTable: t.tableName,
                                                    targetTable: t.tableName,
                                                    primaryKey: '',
                                                })));
                                            } else {
                                                setSelectedTables([]);
                                            }
                                        }}
                                    >全选</Checkbox>
                                    <span className="ml-auto text-ds-tiny text-ds-text-muted">约估行数</span>
                                </div>
                                <div className="max-h-[240px] overflow-y-auto">
                                    {tablesLoading ? (
                                        <div className="px-ds-3 py-ds-4 text-center text-ds-small text-ds-text-muted">加载中...</div>
                                    ) : sourceTables.length === 0 ? (
                                        <div className="px-ds-3 py-ds-4 text-center text-ds-small text-ds-text-muted">
                                            {sourceDatabase ? '该库下无业务表' : '请先选择源数据源与源数据库'}
                                        </div>
                                    ) : sourceTables.map(t => (
                                        <label key={t.tableName}
                                               className="flex items-center gap-ds-2 px-ds-3 py-ds-2 border-b border-ds-border-subtle last:border-b-0 hover:bg-ds-bg-hover cursor-pointer">
                                            <Checkbox
                                                checked={isTableSelected(t.tableName)}
                                                onChange={(e) => toggleTable(t.tableName, e.target.checked)}
                                            />
                                            <span className="font-mono text-ds-small text-ds-text-primary">{t.tableName}</span>
                                            <span className="ml-auto text-ds-tiny text-ds-text-muted font-mono">
                                                {t.tableRows != null ? Number(t.tableRows).toLocaleString() : '—'}
                                            </span>
                                        </label>
                                    ))}
                                </div>
                            </div>
                        </FormItem>
                        <FormItem label="同步模式">
                            <RadioRow checked={syncMode === 'FULL_AND_INCREMENT'}
                                      onChange={() => setSyncMode('FULL_AND_INCREMENT')}
                                      label="全量 + 增量（默认）" hint="启动先做全量快照，再持续捕获增量变更"/>
                            <RadioRow checked={syncMode === 'INCREMENTAL_ONLY'}
                                      onChange={() => setSyncMode('INCREMENTAL_ONLY')}
                                      label="仅增量" hint="需目标表已有历史快照"/>
                        </FormItem>
                        <FormItem label="启动位点">
                            {syncMode === 'FULL_AND_INCREMENT' ? (
                                <RadioRow checked onChange={() => undefined} disabled
                                          label="全量快照 + 增量" hint="全量 + 增量模式固定从 initial 位点启动"/>
                            ) : (
                                <>
                                    <RadioRow checked={startupMode === 'LATEST_OFFSET'}
                                              onChange={() => setStartupMode('LATEST_OFFSET')}
                                              label="从最新" hint="只捕获启动后产生的变更"/>
                                    <RadioRow checked={startupMode === 'EARLIEST_OFFSET'}
                                              onChange={() => setStartupMode('EARLIEST_OFFSET')}
                                              label="从最早" hint="从 binlog 最早可用位点开始"/>
                                </>
                            )}
                        </FormItem>
                    </div>
                )}

                {step === 3 && (
                    <div className="max-w-[720px]">
                        <FormItem label="湖仓存储">
                            <RadioRow checked onChange={() => undefined} disabled
                                      label="内置 MinIO（推荐）" hint="数据文件 + Iceberg 元数据统一存储，无需额外配置"/>
                        </FormItem>
                        <FormItem label="目标库（Iceberg 库名）" required
                                  hint={(
                                      <>Doris 通过 Iceberg Catalog 查询：<span
                                          className="font-mono">datalake_catalog.{targetDatabase || '<目标库>'}.{'<表名>'}</span></>
                                  )}>
                            <input className={INPUT_CLASS} value={targetDatabase} maxLength={100}
                                   placeholder="例如：dwd"
                                   onChange={(e) => setTargetDatabase(e.target.value)}/>
                        </FormItem>
                        <FormItem label="表名映射与主键" required
                                  hint={writeMode === 'UPSERT' ? 'Upsert 模式每表必须配置主键列（多列逗号分隔）' : 'Append 模式主键可留空'}>
                            <div className="border border-ds-border-subtle rounded-ds-sm">
                                {selectedTables.map(t => (
                                    <div key={t.sourceTable}
                                         className="flex items-center gap-ds-2 px-ds-3 py-ds-2 border-b border-ds-border-subtle last:border-b-0 flex-wrap">
                                        <span className="font-mono text-ds-small text-ds-text-secondary">
                                            {sourceDatabase}.{t.sourceTable}
                                        </span>
                                        <span className="text-ds-text-muted">→</span>
                                        <span className="font-mono text-ds-small text-ds-text-primary">
                                            {targetDatabase || '<目标库>'}.
                                        </span>
                                        <input
                                            className="w-40 px-2 py-1 text-ds-small font-mono border border-ds-border-subtle rounded-ds-sm outline-none focus:border-ds-accent"
                                            value={t.targetTable ?? t.sourceTable}
                                            aria-label={`目标表名 ${t.sourceTable}`}
                                            onChange={(e) => updateMapping(t.sourceTable, {targetTable: e.target.value})}
                                        />
                                        <span className="text-ds-tiny text-ds-text-muted ml-auto">主键：</span>
                                        <input
                                            className="w-32 px-2 py-1 text-ds-small font-mono border border-ds-border-subtle rounded-ds-sm outline-none focus:border-ds-accent"
                                            value={t.primaryKey ?? ''}
                                            placeholder={writeMode === 'UPSERT' ? '必填，如 id' : '可空'}
                                            aria-label={`主键列 ${t.sourceTable}`}
                                            onChange={(e) => updateMapping(t.sourceTable, {primaryKey: e.target.value})}
                                        />
                                    </div>
                                ))}
                            </div>
                        </FormItem>
                        <FormItem label="写入模式">
                            <RadioRow checked={writeMode === 'UPSERT'} onChange={() => setWriteMode('UPSERT')}
                                      label="Upsert（按主键）" hint="增量变更按主键合并，目标表随变更更新"/>
                            <RadioRow checked={writeMode === 'APPEND'} onChange={() => setWriteMode('APPEND')}
                                      label="Append" hint="仅追加，不合并（适合日志 / 流水）"/>
                        </FormItem>
                    </div>
                )}

                {step === 4 && (
                    <div className="max-w-[720px]">
                        {/* 预检结果 */}
                        <div className="border border-ds-border-subtle rounded-ds-md p-ds-4 mb-ds-4">
                            <div className="flex items-center gap-ds-2 mb-ds-3">
                                <HiOutlineCheckCircle size={16} className="text-ds-accent"/>
                                <span className="text-ds-small font-semibold text-ds-text-primary">源数据源预检</span>
                                {precheckLoading && <span className="text-ds-tiny text-ds-text-muted">检查中...</span>}
                                {precheck && (
                                    <DsStatusBadge
                                        variant={precheck.success ? 'success' : 'danger'}
                                        label={precheck.success ? '全部通过' : '存在未通过项'}
                                    />
                                )}
                            </div>
                            {precheck?.checks?.map(c => (
                                <div key={c.name} className="flex items-center gap-ds-2 py-ds-1">
                                    {c.passed
                                        ? <HiOutlineCheckCircle size={14} className="text-ds-success flex-shrink-0"/>
                                        : <HiOutlineXCircle size={14} className="text-ds-danger flex-shrink-0"/>}
                                    <span className="text-ds-small text-ds-text-primary">{c.name}</span>
                                    <span className="text-ds-tiny text-ds-text-muted">{c.message}</span>
                                </div>
                            ))}
                        </div>

                        {/* 确认信息 */}
                        <div className="border border-ds-border-subtle rounded-ds-md p-ds-4 mb-ds-4">
                            <div className="flex items-center gap-ds-2 mb-ds-3">
                                <HiOutlineInformationCircle size={16} className="text-ds-accent"/>
                                <span className="text-ds-small font-semibold text-ds-text-primary">基本信息</span>
                            </div>
                            <ConfirmRow label="管道名称" value={name}/>
                            {description && <ConfirmRow label="管道描述" value={description}/>}
                        </div>
                        <div className="border border-ds-border-subtle rounded-ds-md p-ds-4 mb-ds-4">
                            <div className="flex items-center gap-ds-2 mb-ds-3">
                                <HiOutlineServer size={16} className="text-ds-accent"/>
                                <span className="text-ds-small font-semibold text-ds-text-primary">源配置</span>
                            </div>
                            <ConfirmRow label="源数据源" value={dsLabel}/>
                            <ConfirmRow label="源数据库" value={sourceDatabase} mono/>
                            <ConfirmRow label="同步表"
                                        value={`${selectedTables.map(t => t.sourceTable).join('、')}（${selectedTables.length} 表）`}/>
                            <ConfirmRow label="同步模式" value={SYNC_MODE_LABEL[syncMode]}/>
                            <ConfirmRow label="启动位点" value={STARTUP_MODE_LABEL[startupMode]}/>
                        </div>
                        <div className="border border-ds-border-subtle rounded-ds-md p-ds-4">
                            <div className="flex items-center gap-ds-2 mb-ds-3">
                                <HiOutlineCircleStack size={16} className="text-ds-accent"/>
                                <span className="text-ds-small font-semibold text-ds-text-primary">目标配置</span>
                            </div>
                            <ConfirmRow label="湖仓存储" value="内置 MinIO"/>
                            <ConfirmRow label="目标库" value={targetDatabase} mono/>
                            <ConfirmRow label="表映射" mono
                                        value={selectedTables.map(t => `${t.sourceTable} → ${targetDatabase}.${t.targetTable?.trim() || t.sourceTable}`).join('；')}/>
                            <ConfirmRow label="写入模式" value={WRITE_MODE_LABEL[writeMode]}/>
                            <ConfirmRow label="查询入口" mono
                                        value={`datalake_catalog.${targetDatabase}.${selectedTables[0]?.targetTable?.trim() || selectedTables[0]?.sourceTable || '<表名>'}`}/>
                        </div>
                    </div>
                )}
            </div>

            {/* 底部操作 */}
            <div
                className="flex items-center justify-between bg-ds-bg-surface border border-ds-border-subtle rounded-ds-md px-ds-6 py-ds-4">
                <span className="text-ds-tiny text-ds-text-muted">
                    保存前将校验：源库连通性 / binlog 开启 / binlog 格式 ROW
                </span>
                <div className="flex items-center gap-ds-2">
                    {step > 1 && <DsButton variant="secondary" onClick={goPrev}>上一步</DsButton>}
                    {step < 4 && <DsButton onClick={goNext}>下一步</DsButton>}
                    {step === 4 && !isEdit && (
                        <DsButton onClick={handleSaveAndStart}
                                  disabled={saving || precheckLoading || !precheck?.success}
                                  title={!precheck?.success ? '预检未通过，无法启动（可仅保存）' : undefined}>
                            {saving ? '提交中...' : '保存并启动'}
                        </DsButton>
                    )}
                    {step === 4 && (
                        <DsButton variant={isEdit ? 'primary' : 'secondary'} onClick={handleSaveOnly}
                                  disabled={saving}>
                            {isEdit ? (saving ? '保存中...' : '保存') : '仅保存'}
                        </DsButton>
                    )}
                    <DsButton variant="ghost" onClick={() => navigate('/engineering/cdc-pipelines')}>取消</DsButton>
                </div>
            </div>
        </div>
    );
}

/** 确认页键值行 */
function ConfirmRow({label, value, mono}: { label: string; value: string; mono?: boolean }) {
    return (
        <div className="flex items-start gap-ds-4 py-ds-1">
            <span className="w-20 flex-shrink-0 text-ds-tiny text-ds-text-muted pt-0.5">{label}</span>
            <span className={`text-ds-small text-ds-text-secondary break-all ${mono ? 'font-mono' : ''}`}>{value}</span>
        </div>
    );
}
