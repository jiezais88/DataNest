// Sprint 8 F2：CDC 管道配置向导（DI-04，整页 4 步：基本信息/源配置/目标配置/确认启动）。
// 新建 /engineering/cdc-pipelines/new；编辑 /engineering/cdc-pipelines/:id/edit（仅 STOPPED 可编辑，后端兜底）。
// 源支持 MySQL（binlog/ROW 预检）与 PostgreSQL（wal_level=logical/复制权限预检，本期仅 public schema，无「从最早」位点）；
// 目标 = Iceberg 湖仓（MinIO）→ Doris Iceberg Catalog 查询。
// 布局：方案 C 管道流向式（2026-08-10 用户选定）——3 步：基本信息 / 配置管道（源卡 → 目标卡双卡 + 横向配置带）/ 确认启动；居中卡片（max-w-1280），整页滚动。
// 高级配置：并行度 / Checkpoint 间隔，经 configJson（parallelism / checkpointIntervalSeconds）下发，全默认则不传。
import {useCallback, useEffect, useMemo, useState} from 'react';
import {useNavigate, useParams} from 'react-router-dom';
import {AutoComplete, Checkbox, Select, Spin} from 'antd';
import {
    HiOutlineArrowRight,
    HiOutlineCheckCircle,
    HiOutlineCircleStack,
    HiOutlineServer,
    HiOutlineXCircle,
} from 'react-icons/hi2';
import {
    createCdcPipeline,
    getCdcPipeline,
    listCdcSourceDatabases,
    listCdcSourceTables,
    listCdcTargetDatabases,
    startCdcPipeline,
    updateCdcPipeline,
    validateCdcSource,
} from '@/api/cdc';
import {getDataSources} from '@/api/datasource';
import DsButton from '@/components/DsButton';
import DsStatusBadge from '@/components/DsStatusBadge';
import {DataSourceTypeEnum} from '@/constants/datasource';
import {notify} from '@/utils/notify';
import type {
    CdcPipelineSaveRequest,
    CdcSourceTable,
    CdcSourceValidateResult,
    CdcStartupMode,
    CdcSyncMode,
    CdcTableMapping,
    CdcWriteMode,
} from '@/types/cdc';
import {STARTUP_MODE_LABEL, SYNC_MODE_LABEL, WRITE_MODE_LABEL} from '@/pages/engineering/cdc-pipelines/constants';

const STEPS = ['基本信息', '配置管道', '确认启动'];

/** 高级配置默认值：与默认值一致时不写入 configJson（后端走 Nacos/connector 默认） */
const DEFAULT_PARALLELISM = 1;
const DEFAULT_CHECKPOINT_SECONDS = 60;
const DEFAULT_SCAN_CHUNK_SIZE = 8096;
const DEFAULT_SCHEMA_CHANGE_BEHAVIOR = 'EVOLVE';

/** 表结构变更策略选项（CDC YAML schema.change.behavior） */
const SCHEMA_CHANGE_BEHAVIOR_OPTIONS = [
    {value: 'EVOLVE', label: 'EVOLVE（默认，自动同步到湖仓表）'},
    {value: 'LENIENT', label: 'LENIENT（容忍不兼容变更）'},
    {value: 'EXCEPTION', label: 'EXCEPTION（遇变更报错）'},
];

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
    // 高级配置（configJson：parallelism / checkpointIntervalSeconds / schemaChangeBehavior / scanChunkSize）
    const [parallelism, setParallelism] = useState(DEFAULT_PARALLELISM);
    const [checkpointIntervalSeconds, setCheckpointIntervalSeconds] = useState(DEFAULT_CHECKPOINT_SECONDS);
    const [schemaChangeBehavior, setSchemaChangeBehavior] = useState(DEFAULT_SCHEMA_CHANGE_BEHAVIOR);
    const [scanChunkSize, setScanChunkSize] = useState(DEFAULT_SCAN_CHUNK_SIZE);

    // ============ 下拉数据 ============
    const [datasourceOptions, setDatasourceOptions] = useState<{ value: string; label: string }[]>([]);
    const [databaseOptions, setDatabaseOptions] = useState<{ value: string; label: string }[]>([]);
    const [sourceTables, setSourceTables] = useState<CdcSourceTable[]>([]);
    const [tablesLoading, setTablesLoading] = useState(false);
    /** 现有湖仓库名（目标库 AutoComplete 选项，允许自由输入新库名） */
    const [targetDbOptions, setTargetDbOptions] = useState<{ value: string; label: string }[]>([]);
    /** 源数据源名称缓存（确认页展示） */
    const [datasourceName, setDatasourceName] = useState('');

    // ============ 第 4 步预检 ============
    const [precheck, setPrecheck] = useState<CdcSourceValidateResult | null>(null);
    const [precheckLoading, setPrecheckLoading] = useState(false);

    // CDC 源数据源下拉（MySQL + PostgreSQL；按 id 缓存类型，启动位点等按类型联动）
    const [datasourceTypeMap, setDatasourceTypeMap] = useState<Record<string, string>>({});
    useEffect(() => {
        Promise.all([
            getDataSources({type: DataSourceTypeEnum.MYSQL, page: 1, pageSize: 100}),
            getDataSources({type: DataSourceTypeEnum.POSTGRESQL, page: 1, pageSize: 100}),
        ])
            .then(([mysqlRes, pgRes]) => {
                const records = [...(mysqlRes.data?.records ?? []), ...(pgRes.data?.records ?? [])];
                setDatasourceOptions(records.map(d => ({value: d.id, label: `${d.name}（${d.host}）`})));
                setDatasourceTypeMap(Object.fromEntries(records.map(d => [d.id, d.type])));
            })
            .catch(() => setDatasourceOptions([]));
    }, []);

    // 现有湖仓库名（目标库下拉候选；接口失败降级为空，仍可自由输入）
    useEffect(() => {
        listCdcTargetDatabases()
            .then(list => setTargetDbOptions((list ?? []).map(db => ({value: db, label: db}))))
            .catch(() => setTargetDbOptions([]));
    }, []);

    // 编辑模式：加载详情预填（含 configJson 高级配置回填；非法 JSON 忽略走默认）
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
                if (p.configJson) {
                    try {
                        const cfg = JSON.parse(p.configJson) as Record<string, unknown>;
                        if (typeof cfg.parallelism === 'number') setParallelism(cfg.parallelism);
                        if (typeof cfg.checkpointIntervalSeconds === 'number') {
                            setCheckpointIntervalSeconds(cfg.checkpointIntervalSeconds);
                        }
                        if (typeof cfg.schemaChangeBehavior === 'string') {
                            setSchemaChangeBehavior(cfg.schemaChangeBehavior);
                        }
                        if (typeof cfg.scanChunkSize === 'number') setScanChunkSize(cfg.scanChunkSize);
                    } catch {
                        // configJson 非法：忽略，保存时后端会重新校验
                    }
                }
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

    /** 当前选中源数据源类型（MYSQL / POSTGRESQL；编辑模式选项未加载完时短暂为空） */
    const sourceType = datasourceId ? datasourceTypeMap[datasourceId] : undefined;
    const isPostgres = sourceType === DataSourceTypeEnum.POSTGRESQL;

    // PG connector 无 earliest-offset：选中 PG 源时禁用「从最早」，残留值回退到「从最新」（后端同样拦截）
    useEffect(() => {
        if (isPostgres && startupMode === 'EARLIEST_OFFSET') {
            setStartupMode('LATEST_OFFSET');
        }
    }, [isPostgres, startupMode]);

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
            if (!targetDatabase.trim()) {
                notify.warning('请输入目标库名');
                return false;
            }
            if (writeMode === 'UPSERT' && selectedTables.some(t => !t.primaryKey?.trim())) {
                notify.warning('Upsert 模式下每张表都必须配置主键列');
                return false;
            }
            if (!Number.isInteger(parallelism) || parallelism < 1 || parallelism > 8) {
                notify.warning('并行度需为 1~8 的整数');
                return false;
            }
            if (!Number.isInteger(checkpointIntervalSeconds) || checkpointIntervalSeconds < 3) {
                notify.warning('Checkpoint 间隔需为不小于 3 的整数秒');
                return false;
            }
            if (!Number.isInteger(scanChunkSize) || scanChunkSize < 16 || scanChunkSize > 1048576) {
                notify.warning('快照分块大小需为 16~1048576 的整数');
                return false;
            }
        }
        return true;
    };

    const goNext = () => {
        if (!validateStep(step)) return;
        const next = step + 1;
        setStep(next);
        if (next === 3) runPrecheck();
    };

    const goPrev = () => setStep(s => Math.max(1, s - 1));

    // ============ 表勾选/映射 ============
    const isTableSelected = (tableName: string) => selectedTables.some(t => t.sourceTable === tableName);

    /** 勾选时用源表主键预填映射主键（用户可手改；无主键则空，UPSERT 下标 warning） */
    const toggleTable = (table: CdcSourceTable, checked: boolean) => {
        setSelectedTables(prev => checked
            ? [...prev, {sourceTable: table.tableName, targetTable: table.tableName, primaryKey: table.primaryKey ?? ''}]
            : prev.filter(t => t.sourceTable !== table.tableName));
    };

    const updateMapping = (sourceTable: string, patch: Partial<CdcTableMapping>) => {
        setSelectedTables(prev => prev.map(t => t.sourceTable === sourceTable ? {...t, ...patch} : t));
    };

    const allChecked = sourceTables.length > 0 && selectedTables.length === sourceTables.length;

    // ============ 提交 ============
    const buildRequest = (): CdcPipelineSaveRequest => {
        // 高级配置：仅非默认值写键；全默认则不传 configJson（后端走 Nacos/connector 默认）
        const advancedConfig: Record<string, number | string> = {};
        if (parallelism !== DEFAULT_PARALLELISM) advancedConfig.parallelism = parallelism;
        if (checkpointIntervalSeconds !== DEFAULT_CHECKPOINT_SECONDS) {
            advancedConfig.checkpointIntervalSeconds = checkpointIntervalSeconds;
        }
        if (schemaChangeBehavior !== DEFAULT_SCHEMA_CHANGE_BEHAVIOR) {
            advancedConfig.schemaChangeBehavior = schemaChangeBehavior;
        }
        if (scanChunkSize !== DEFAULT_SCAN_CHUNK_SIZE) advancedConfig.scanChunkSize = scanChunkSize;
        return {
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
            configJson: Object.keys(advancedConfig).length > 0 ? JSON.stringify(advancedConfig) : undefined,
        };
    };

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
                            : 'MySQL Binlog / PostgreSQL WAL → Iceberg 湖仓（MinIO），Doris 经 Iceberg Catalog 查询。'}
                    </p>
                </div>
            </div>

            {/* 向导卡片：居中单列窄卡（方案 A，2026-08-10 用户选定），步骤条/内容/操作都在卡内 */}
            <div
                className="max-w-[1280px] w-full mx-auto bg-ds-bg-surface border border-ds-border-subtle rounded-ds-md overflow-hidden">
                {/* 步骤条 */}
                <div className="flex items-center px-ds-6 py-ds-4 border-b border-ds-border-subtle">
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
                <div className="p-ds-6">
                {step === 1 && (
                    <div className="max-w-[560px] mx-auto">
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
                    <div className="flex flex-col gap-ds-4">
                        {/* 源卡 → 目标卡（方案 C 管道流向布局） */}
                        <div className="flex items-stretch gap-ds-3">
                            {/* 源卡 */}
                            <div
                                className="flex-1 min-w-0 border border-ds-border-subtle rounded-ds-md flex flex-col overflow-hidden">
                                <div
                                    className="flex items-center gap-ds-2 px-ds-4 py-ds-3 border-b border-ds-border-subtle bg-ds-bg-root">
                                    <HiOutlineServer size={16} className="text-ds-accent"/>
                                    <span className="text-ds-small font-semibold text-ds-text-primary">源 SOURCE</span>
                                    <span className="text-ds-tiny text-ds-text-muted ml-auto">MySQL / PostgreSQL</span>
                                </div>
                                <div className="p-ds-4 flex-1 flex flex-col">
                                    <FormItem label="源数据源" required>
                                        <Select
                                            className="w-full"
                                            placeholder="选择 MySQL / PostgreSQL 数据源"
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
                                    <div className="text-ds-small font-semibold text-ds-text-primary mb-ds-2">
                                        同步表<span className="text-ds-danger ml-0.5">*</span>
                                    </div>
                                    <div
                                        className="border border-ds-border-subtle rounded-ds-sm overflow-hidden flex-1 flex flex-col min-h-[180px]">
                                        <div
                                            className="flex items-center gap-ds-2 px-ds-3 py-ds-2 bg-ds-bg-root border-b border-ds-border-subtle flex-shrink-0">
                                            <Checkbox
                                                checked={allChecked}
                                                indeterminate={selectedTables.length > 0 && !allChecked}
                                                disabled={sourceTables.length === 0}
                                                onChange={(e) => {
                                                    if (e.target.checked) {
                                                        setSelectedTables(sourceTables.map(t => ({
                                                            sourceTable: t.tableName,
                                                            targetTable: t.tableName,
                                                            primaryKey: t.primaryKey ?? '',
                                                        })));
                                                    } else {
                                                        setSelectedTables([]);
                                                    }
                                                }}
                                            >全选</Checkbox>
                                            <span className="ml-auto text-ds-tiny text-ds-text-muted">约估行数</span>
                                        </div>
                                        <div className="flex-1 min-h-0 overflow-y-auto max-h-[220px]">
                                            {tablesLoading ? (
                                                <div
                                                    className="px-ds-3 py-ds-4 text-center text-ds-small text-ds-text-muted">加载中...</div>
                                            ) : sourceTables.length === 0 ? (
                                                <div
                                                    className="px-ds-3 py-ds-4 text-center text-ds-small text-ds-text-muted">
                                                    {sourceDatabase ? '该库下无业务表' : '请先选择源数据源与源数据库'}
                                                </div>
                                            ) : sourceTables.map(t => (
                                                <label key={t.tableName}
                                                       className="flex items-center gap-ds-2 px-ds-3 py-ds-2 border-b border-ds-border-subtle last:border-b-0 hover:bg-ds-bg-hover cursor-pointer">
                                                    <Checkbox
                                                        checked={isTableSelected(t.tableName)}
                                                        onChange={(e) => toggleTable(t, e.target.checked)}
                                                    />
                                                    <span
                                                        className="font-mono text-ds-small text-ds-text-primary">{t.tableName}</span>
                                                    <span
                                                        className="ml-auto text-ds-tiny text-ds-text-muted font-mono">
                                                        {t.tableRows != null ? Number(t.tableRows).toLocaleString() : '—'}
                                                    </span>
                                                </label>
                                            ))}
                                        </div>
                                    </div>
                                </div>
                            </div>

                            {/* 流向箭头 */}
                            <div className="w-10 flex flex-col items-center justify-center flex-shrink-0">
                                <HiOutlineArrowRight size={22} className="text-ds-accent"/>
                                <span className="text-ds-tiny text-ds-text-muted mt-ds-1">CDC</span>
                            </div>

                            {/* 目标卡 */}
                            <div
                                className="flex-1 min-w-0 border border-ds-border-subtle rounded-ds-md flex flex-col overflow-hidden">
                                <div
                                    className="flex items-center gap-ds-2 px-ds-4 py-ds-3 border-b border-ds-border-subtle bg-ds-bg-root">
                                    <HiOutlineCircleStack size={16} className="text-ds-accent"/>
                                    <span className="text-ds-small font-semibold text-ds-text-primary">
                                        目标 TARGET
                                    </span>
                                    <span className="text-ds-tiny text-ds-text-muted ml-auto">Iceberg 湖仓（内置 MinIO）</span>
                                </div>
                                <div className="p-ds-4 flex-1 flex flex-col">
                                    <FormItem label="目标库（Iceberg 库名）" required
                                              hint={(
                                                  <>可自由输入新库名（namespace 自动创建）；Doris 查询：<span
                                                      className="font-mono">datalake_catalog.{targetDatabase || '<目标库>'}.{'<表名>'}</span></>
                                              )}>
                                        <AutoComplete
                                            className="w-full"
                                            value={targetDatabase}
                                            options={targetDbOptions}
                                            maxLength={100}
                                            placeholder="选择现有湖仓库，或输入新库名，例如：dwd"
                                            aria-label="目标库"
                                            onChange={(v) => setTargetDatabase(v)}
                                        />
                                    </FormItem>
                                    <div className="text-ds-small font-semibold text-ds-text-primary mb-ds-2">
                                        表名映射与主键<span className="text-ds-danger ml-0.5">*</span>
                                        <span className="text-ds-tiny text-ds-text-muted font-normal ml-ds-2">
                                            {writeMode === 'UPSERT' ? 'Upsert 模式每表必填主键（多列逗号分隔）' : 'Append 模式主键可留空'}
                                        </span>
                                    </div>
                                    <div
                                        className="border border-ds-border-subtle rounded-ds-sm flex-1 min-h-[180px] max-h-[280px] overflow-y-auto">
                                        {selectedTables.length === 0 ? (
                                            <div
                                                className="px-ds-3 py-ds-4 text-center text-ds-small text-ds-text-muted">
                                                勾选左侧源表后自动生成映射
                                            </div>
                                        ) : selectedTables.map(t => (
                                            <div key={t.sourceTable}
                                                 className="flex items-center gap-ds-2 px-ds-3 py-ds-2 border-b border-ds-border-subtle last:border-b-0">
                                                <span className="font-mono text-ds-small text-ds-text-secondary truncate max-w-[140px]"
                                                      title={`${sourceDatabase}.${t.sourceTable}`}>
                                                    {t.sourceTable}
                                                </span>
                                                <span className="text-ds-text-muted flex-shrink-0">→</span>
                                                {targetDatabase && (
                                                    <span className="font-mono text-ds-small text-ds-text-primary flex-shrink-0">
                                                        {targetDatabase}.
                                                    </span>
                                                )}
                                                <input
                                                    className="w-28 min-w-0 px-2 py-1 text-ds-small font-mono border border-ds-border-subtle rounded-ds-sm outline-none focus:border-ds-accent"
                                                    value={t.targetTable ?? t.sourceTable}
                                                    aria-label={`目标表名 ${t.sourceTable}`}
                                                    onChange={(e) => updateMapping(t.sourceTable, {targetTable: e.target.value})}
                                                />
                                                <span className="text-ds-tiny text-ds-text-muted ml-auto flex-shrink-0">主键</span>
                                                <input
                                                    className={`w-24 min-w-0 px-2 py-1 text-ds-small font-mono border rounded-ds-sm outline-none focus:border-ds-accent flex-shrink-0 ${
                                                        writeMode === 'UPSERT' && !t.primaryKey?.trim()
                                                            ? 'border-ds-warning' : 'border-ds-border-subtle'
                                                    }`}
                                                    value={t.primaryKey ?? ''}
                                                    placeholder={writeMode === 'UPSERT' ? '必填' : '可空'}
                                                    title={writeMode === 'UPSERT' && !t.primaryKey?.trim() ? '源表无主键且未填写，Upsert 模式必须配置主键列' : undefined}
                                                    aria-label={`主键列 ${t.sourceTable}`}
                                                    onChange={(e) => updateMapping(t.sourceTable, {primaryKey: e.target.value})}
                                                />
                                            </div>
                                        ))}
                                    </div>
                                </div>
                            </div>
                        </div>

                        {/* 横向配置带：同步模式 / 启动位点 / 写入模式 / 高级配置 */}
                        <div
                            className="border border-ds-border-subtle rounded-ds-md p-ds-4 grid grid-cols-2 gap-x-ds-6">
                            <FormItem label="同步模式">
                                <RadioRow checked={syncMode === 'FULL_AND_INCREMENT'}
                                          onChange={() => setSyncMode('FULL_AND_INCREMENT')}
                                          label="全量 + 增量（默认）" hint="先全量快照，再持续捕获增量"/>
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
                                        {isPostgres ? (
                                            <RadioRow checked={false} onChange={() => undefined} disabled
                                                      label="从最早" hint="PostgreSQL connector 不支持该位点"/>
                                        ) : (
                                            <RadioRow checked={startupMode === 'EARLIEST_OFFSET'}
                                                      onChange={() => setStartupMode('EARLIEST_OFFSET')}
                                                      label="从最早" hint="从 binlog 最早可用位点开始"/>
                                        )}
                                    </>
                                )}
                            </FormItem>
                            <FormItem label="写入模式">
                                <RadioRow checked={writeMode === 'UPSERT'} onChange={() => setWriteMode('UPSERT')}
                                          label="Upsert（按主键）" hint="增量按主键合并，目标表随变更更新"/>
                                <RadioRow checked={writeMode === 'APPEND'} onChange={() => setWriteMode('APPEND')}
                                          label="Append" hint="仅追加，不合并（适合日志 / 流水）"/>
                            </FormItem>
                            <FormItem label="高级配置（可选）"
                                      hint="留默认即可：并行度过大对小表是额外开销；间隔过小会增加湖仓提交频率。">
                                <div className="grid grid-cols-2 gap-ds-3">
                                    <div>
                                        <div className="text-ds-tiny text-ds-text-muted mb-ds-1">
                                            并行度（1~8，默认 {DEFAULT_PARALLELISM}）
                                        </div>
                                        <input type="number" className={INPUT_CLASS}
                                               min={1} max={8} step={1}
                                               value={parallelism}
                                               aria-label="并行度"
                                               onChange={(e) => setParallelism(Number(e.target.value))}/>
                                    </div>
                                    <div>
                                        <div className="text-ds-tiny text-ds-text-muted mb-ds-1">
                                            Checkpoint 间隔（秒，≥3，默认 {DEFAULT_CHECKPOINT_SECONDS}）
                                        </div>
                                        <input type="number" className={INPUT_CLASS}
                                               min={3} step={1}
                                               value={checkpointIntervalSeconds}
                                               aria-label="Checkpoint 间隔"
                                               onChange={(e) => setCheckpointIntervalSeconds(Number(e.target.value))}/>
                                    </div>
                                    <div>
                                        <div className="text-ds-tiny text-ds-text-muted mb-ds-1">
                                            快照分块大小（16~1048576，默认 {DEFAULT_SCAN_CHUNK_SIZE}）
                                        </div>
                                        <input type="number" className={INPUT_CLASS}
                                               min={16} max={1048576} step={1}
                                               value={scanChunkSize}
                                               aria-label="快照分块大小"
                                               onChange={(e) => setScanChunkSize(Number(e.target.value))}/>
                                    </div>
                                    <div>
                                        <div className="text-ds-tiny text-ds-text-muted mb-ds-1">
                                            表结构变更策略
                                        </div>
                                        <Select
                                            className="w-full"
                                            value={schemaChangeBehavior}
                                            options={SCHEMA_CHANGE_BEHAVIOR_OPTIONS}
                                            aria-label="表结构变更策略"
                                            onChange={(v) => setSchemaChangeBehavior(v)}
                                        />
                                    </div>
                                </div>
                            </FormItem>
                        </div>
                    </div>
                )}

                {step === 3 && (
                    <div>
                        {/* 源数据源预检 */}
                        <div className="border border-ds-border-subtle rounded-ds-md p-ds-4 mb-ds-4">
                            <div className="flex items-center gap-ds-2 mb-ds-3 flex-wrap">
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

                        {/* 配置摘要 */}
                        <div className="border border-ds-border-subtle rounded-ds-md p-ds-4 mb-ds-4">
                            <div className="text-ds-small font-semibold text-ds-text-primary mb-ds-3">配置摘要</div>
                            <ConfirmRow label="管道名称" value={name}/>
                            {description && <ConfirmRow label="管道描述" value={description}/>}
                            <ConfirmRow label="源数据源" value={dsLabel}/>
                            <ConfirmRow label="源库" value={sourceDatabase} mono/>
                            <ConfirmRow label="同步表"
                                        value={`${selectedTables.map(t => t.sourceTable).join('、')}（${selectedTables.length} 表）`}/>
                            <ConfirmRow label="同步模式" value={SYNC_MODE_LABEL[syncMode]}/>
                            <ConfirmRow label="启动位点" value={STARTUP_MODE_LABEL[startupMode]}/>
                            <ConfirmRow label="目标库" value={targetDatabase} mono/>
                            <ConfirmRow label="写入模式" value={WRITE_MODE_LABEL[writeMode]}/>
                            <ConfirmRow label="并行度"
                                        value={parallelism === DEFAULT_PARALLELISM ? `默认（${DEFAULT_PARALLELISM}）` : String(parallelism)}/>
                            <ConfirmRow label="Checkpoint"
                                        value={checkpointIntervalSeconds === DEFAULT_CHECKPOINT_SECONDS
                                            ? `默认（${DEFAULT_CHECKPOINT_SECONDS} 秒）` : `${checkpointIntervalSeconds} 秒`}/>
                            <ConfirmRow label="结构变更"
                                        value={schemaChangeBehavior === DEFAULT_SCHEMA_CHANGE_BEHAVIOR
                                            ? `默认（${DEFAULT_SCHEMA_CHANGE_BEHAVIOR}）` : schemaChangeBehavior}/>
                            <ConfirmRow label="快照分块"
                                        value={scanChunkSize === DEFAULT_SCAN_CHUNK_SIZE
                                            ? `默认（${DEFAULT_SCAN_CHUNK_SIZE}）` : String(scanChunkSize)}/>
                            <ConfirmRow label="查询入口" mono
                                        value={`datalake_catalog.${targetDatabase}.${selectedTables[0]?.targetTable?.trim() || selectedTables[0]?.sourceTable || '<表名>'}`}/>
                        </div>

                        {/* 表映射明细 */}
                        <div className="border border-ds-border-subtle rounded-ds-md p-ds-4">
                            <div className="flex items-center gap-ds-2 mb-ds-3">
                                <HiOutlineCircleStack size={16} className="text-ds-accent"/>
                                <span className="text-ds-small font-semibold text-ds-text-primary">
                                    表映射明细（{selectedTables.length} 表）
                                </span>
                            </div>
                            {selectedTables.map(t => (
                                <div key={t.sourceTable} className="flex items-center gap-ds-2 py-ds-1 flex-wrap">
                                    <span className="font-mono text-ds-small text-ds-text-secondary">
                                        {sourceDatabase}.{t.sourceTable}
                                    </span>
                                    <span className="text-ds-text-muted">→</span>
                                    <span className="font-mono text-ds-small text-ds-text-primary">
                                        datalake_catalog.{targetDatabase}.{t.targetTable?.trim() || t.sourceTable}
                                    </span>
                                    <span className="ml-auto text-ds-tiny text-ds-text-muted">
                                        主键：<span className="font-mono">{t.primaryKey?.trim() || '—'}</span>
                                    </span>
                                </div>
                            ))}
                        </div>
                    </div>
                )}
                </div>

                {/* 底部操作（卡内底部） */}
                <div
                    className="flex items-center justify-between px-ds-6 py-ds-4 border-t border-ds-border-subtle">
                    <span className="text-ds-tiny text-ds-text-muted">
                        保存前将校验：源库连通性 / 增量日志开启（MySQL binlog、PG wal_level=logical）/ PG 复制权限
                    </span>
                    <div className="flex items-center gap-ds-2">
                        {step > 1 && <DsButton variant="secondary" onClick={goPrev}>上一步</DsButton>}
                        {step < 3 && <DsButton onClick={goNext}>下一步</DsButton>}
                        {step === 3 && !isEdit && (
                            <DsButton onClick={handleSaveAndStart}
                                      disabled={saving || precheckLoading || !precheck?.success}
                                      title={!precheck?.success ? '预检未通过，无法启动（可仅保存）' : undefined}>
                                {saving ? '提交中...' : '保存并启动'}
                            </DsButton>
                        )}
                        {step === 3 && (
                            <DsButton variant={isEdit ? 'primary' : 'secondary'} onClick={handleSaveOnly}
                                      disabled={saving}>
                                {isEdit ? (saving ? '保存中...' : '保存') : '仅保存'}
                            </DsButton>
                        )}
                        <DsButton variant="ghost" onClick={() => navigate('/engineering/cdc-pipelines')}>取消</DsButton>
                    </div>
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
