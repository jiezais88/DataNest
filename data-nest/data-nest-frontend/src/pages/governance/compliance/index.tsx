// 标准合规（Sprint 6，独立菜单页，对齐 PRD §6.6 / 原型 View 5）
// 三格统计（不合规项 / 已忽略 / 合规率）+ 扫描结果清单（分页 / 违规类型 / 忽略状态筛选）
// + 忽略/取消忽略 + 导出问题清单 + 立即扫描。废弃数据标准页内 sessionStorage 方案。
// 数据来源：POST /governance/data-standards/compliance-check/page + /summary + /ignore + /unignore + /export + /compliance-check
import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {useNavigate, useSearchParams} from 'react-router-dom';
import {Table, Tooltip} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import {
    HiOutlineCheckCircle,
    HiOutlineDownload,
    HiOutlineEye,
    HiOutlineHandThumbUp,
    HiOutlineShieldCheck,
    HiOutlineXCircle,
} from 'react-icons/hi2';
import {formatDateTime} from '../../../utils/format';
import {COL} from '../../../constants/table';
import {notify} from '../../../utils/notify';
import {useHasRole} from '../../../hooks/useHasRole';
import {GOVERNANCE_WRITE_ROLES} from '../../../constants/roles';
import {listMetadataDatasourceIds} from '../../../api/metadata';
import {
    exportComplianceCheck,
    getComplianceCheckSummary,
    ignoreComplianceCheckResult,
    pageComplianceCheckResults,
    runComplianceCheck,
    unignoreComplianceCheckResult,
} from '../../../api/dataStandard';
import DsButton from '../../../components/DsButton';
import DsIconButton from '../../../components/DsIconButton';
import DsFilterSelect from '../../../components/DsFilterSelect';
import DsModal from '../../../components/DsModal';
import DsStatusBadge from '../../../components/DsStatusBadge';
import type {DsStatusVariant} from '../../../components/DsStatusBadge';
import DsToolbar from '../../../components/DsToolbar';
import DsTableEmpty from '../../../components/DsTableEmpty';
import Pagination from '../../../components/Pagination';
import ConfirmDialog from '../../../components/ConfirmDialog';
import type {MetadataDatasource} from '../../../types/metadata';
import type {
    ComplianceCheckParams,
    ComplianceCheckResult,
    ComplianceCheckSummary,
} from '../../../types/dataStandard';

/** 违规类型 -> 徽章变体（列表「违规类型」列） */
const VIOLATION_VARIANT: Record<string, DsStatusVariant> = {
    NAMING: 'warning',
    TYPE: 'danger',
};

/** 违规类型文案 */
const VIOLATION_LABEL: Record<string, string> = {
    NAMING: '命名规范',
    TYPE: '字段类型',
};

/** 对象类型 -> 徽章变体（「对象类型」列：表名/字段名） */
const OBJECT_LABEL: Record<string, string> = {
    TABLE: '表名',
    COLUMN: '字段名',
};

/** 违规类型筛选下拉 */
const VIOLATION_OPTIONS = [
    {value: '', label: '全部违规类型'},
    {value: 'NAMING', label: '命名规范'},
    {value: 'TYPE', label: '字段类型'},
];

/** 忽略状态筛选下拉（ignored: 0=未忽略 1=已忽略 2=全部） */
const IGNORED_OPTIONS = [
    {value: '0', label: '仅未忽略'},
    {value: '2', label: '全部'},
    {value: '1', label: '仅已忽略'},
];

/** 触发浏览器下载 Blob 文件（CSV 导出） */
function downloadBlob(blob: Blob, filename: string) {
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = filename;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
}

export default function StandardCompliancePage() {
    const navigate = useNavigate();
    const [searchParams, setSearchParams] = useSearchParams();
    const canWrite = useHasRole(...GOVERNANCE_WRITE_ROLES);

    // ============ 分页 + 筛选 ============
    const [items, setItems] = useState<ComplianceCheckResult[]>([]);
    const [total, setTotal] = useState(0);
    const [page, setPage] = useState(1);
    const [pageSize, setPageSize] = useState(10);
    const [datasourceId, setDatasourceId] = useState('');
    const [violationType, setViolationType] = useState('');
    const [ignored, setIgnored] = useState('0');
    const [loading, setLoading] = useState(false);

    // 数据源下拉
    const [datasources, setDatasources] = useState<MetadataDatasource[]>([]);
    const [datasourceOptions, setDatasourceOptions] = useState<{value: string; label: string}[]>([
        {value: '', label: '全部数据源'},
    ]);

    // ============ 三格统计 ============
    const [summary, setSummary] = useState<ComplianceCheckSummary | null>(null);
    const [summaryLoading, setSummaryLoading] = useState(false);

    // ============ 立即扫描弹窗 ============
    const [scanOpen, setScanOpen] = useState(false);
    const [scanDsIds, setScanDsIds] = useState<string[]>([]);
    const [checkNaming, setCheckNaming] = useState(true);
    const [checkFieldType, setCheckFieldType] = useState(true);
    const [scanning, setScanning] = useState(false);

    // ============ 忽略确认 ============
    const [ignoreTarget, setIgnoreTarget] = useState<{id: string; objectPath: string; ignore: boolean} | null>(null);
    const [ignoreOpen, setIgnoreOpen] = useState(false);
    const [ignoreLoading, setIgnoreLoading] = useState(false);

    const loadDataSources = useCallback(async () => {
        try {
            const res = await listMetadataDatasourceIds();
            const list = res.data ?? [];
            setDatasources(list);
            setDatasourceOptions([
                {value: '', label: '全部数据源'},
                ...list.map((d) => ({value: String(d.id), label: d.name || `数据源 ${d.id}`})),
            ]);
        } catch {
            setDatasourceOptions([{value: '', label: '全部数据源'}]);
        }
    }, []);

    useEffect(() => {
        loadDataSources();
    }, [loadDataSources]);

    /** 当前筛选对应的检查范围参数（summary / export 用，不含分页与忽略/违规类型） */
    const buildRangeParams = useCallback((): ComplianceCheckParams => {
        const params: ComplianceCheckParams = {
            checkNaming: true,
            checkFieldType: true,
        };
        if (datasourceId) params.datasourceIds = [datasourceId];
        return params;
    }, [datasourceId]);

    const loadList = useCallback(async () => {
        setLoading(true);
        try {
            const params = buildRangeParams();
            const res = await pageComplianceCheckResults({
                ...params,
                page,
                pageSize,
                violationType: violationType || undefined,
                ignored: Number(ignored),
            });
            setItems(res.data.records ?? []);
            setTotal(res.data.total ?? 0);
        } finally {
            setLoading(false);
        }
    }, [buildRangeParams, page, pageSize, violationType, ignored]);

    const loadSummary = useCallback(async () => {
        setSummaryLoading(true);
        try {
            const res = await getComplianceCheckSummary(buildRangeParams());
            setSummary(res.data ?? null);
        } finally {
            setSummaryLoading(false);
        }
    }, [buildRangeParams]);

    useEffect(() => {
        loadList();
    }, [loadList]);

    useEffect(() => {
        loadSummary();
    }, [loadSummary]);

    // ============ URL 状态同步（进页恢复筛选，跳元数据返回后不丢） ============
    const urlInitRef = useRef(false);
    useEffect(() => {
        if (urlInitRef.current) return;
        urlInitRef.current = true;
        const p = searchParams;
        const ds = p.get('datasourceId');
        setDatasourceId(ds && datasources.some((d) => String(d.id) === ds) ? ds : '');
        const vt = p.get('violationType');
        setViolationType(vt === 'NAMING' || vt === 'TYPE' ? vt : '');
        const ig = p.get('ignored');
        setIgnored(ig === '1' || ig === '2' ? ig : '0');
        setPage(Number(p.get('page')) || 1);
        const ps = Number(p.get('pageSize')) || 10;
        if (ps !== 10) setPageSize(ps);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [datasources]);

    useEffect(() => {
        const next = new URLSearchParams();
        if (datasourceId) next.set('datasourceId', datasourceId);
        if (violationType) next.set('violationType', violationType);
        if (ignored !== '0') next.set('ignored', ignored);
        if (page > 1) next.set('page', String(page));
        if (pageSize !== 10) next.set('pageSize', String(pageSize));
        if (next.toString() === searchParams.toString()) return;
        setSearchParams(next, {replace: true});
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [datasourceId, violationType, ignored, page, pageSize]);

    const resetFilters = () => {
        setDatasourceId('');
        setViolationType('');
        setIgnored('0');
        setPage(1);
    };

    // ============ 立即扫描 ============
    const openScan = async () => {
        setScanOpen(true);
        setScanDsIds([]);
        setCheckNaming(true);
        setCheckFieldType(true);
        try {
            const res = await listMetadataDatasourceIds();
            setDatasources(res.data ?? []);
        } catch {
            // 忽略
        }
    };

    const handleScan = async () => {
        if (scanDsIds.length === 0) {
            notify.warning('请选择检查数据源');
            return;
        }
        if (!checkNaming && !checkFieldType) {
            notify.warning('请至少选择一项检查项目');
            return;
        }
        setScanning(true);
        try {
            await runComplianceCheck({
                datasourceIds: scanDsIds,
                checkNaming,
                checkFieldType,
            });
            notify.success('标准合规扫描完成');
            setScanOpen(false);
            setPage(1);
            loadList();
            loadSummary();
        } finally {
            setScanning(false);
        }
    };

    // ============ 忽略 / 取消忽略 ============
    const openIgnoreConfirm = (item: ComplianceCheckResult, ignore: boolean) => {
        setIgnoreTarget({id: item.id, objectPath: item.objectPath || item.objectName, ignore});
        setIgnoreOpen(true);
    };

    const handleIgnore = async () => {
        if (!ignoreTarget) return;
        setIgnoreLoading(true);
        try {
            if (ignoreTarget.ignore) await ignoreComplianceCheckResult(ignoreTarget.id);
            else await unignoreComplianceCheckResult(ignoreTarget.id);
            notify.success(ignoreTarget.ignore ? '已忽略该问题' : '已取消忽略');
            setIgnoreOpen(false);
            setIgnoreTarget(null);
            loadList();
            loadSummary();
        } finally {
            setIgnoreLoading(false);
        }
    };

    // ============ 导出 ============
    const handleExport = async () => {
        try {
            const blob = await exportComplianceCheck(buildRangeParams());
            downloadBlob(blob, `compliance_check_${Date.now()}.csv`);
            notify.success('问题清单已导出');
        } catch {
            // 错误提示由拦截器统一处理
        }
    };

    // ============ 查看（跳元数据） ============
    const handleView = (item: ComplianceCheckResult) => {
        const query = new URLSearchParams();
        if (item.tableId) query.set('tableId', item.tableId);
        if (item.columnId) query.set('columnId', item.columnId);
        query.set('from', 'compliance');
        navigate(`/governance/metadata?${query.toString()}`);
    };

    // ============ 列 ============
    const columns = useMemo<ColumnsType<ComplianceCheckResult>>(() => [
        {
            title: '对象路径',
            dataIndex: 'objectPath',
            width: COL.NAME + 80,
            ellipsis: true,
            render: (v?: string, r?: ComplianceCheckResult) => (
                <span title={v || ''} className="text-ds-small text-ds-text-primary font-medium">
                    {v || r?.objectName || '—'}
                </span>
            ),
        },
        {
            title: '对象类型',
            dataIndex: 'objectType',
            width: COL.STATUS,
            render: (v?: string) => (
                <DsStatusBadge variant="pending" label={OBJECT_LABEL[v || ''] || v || '—'}/>
            ),
        },
        {
            title: '违规类型',
            dataIndex: 'violationType',
            width: COL.STATUS,
            render: (v?: string) => (
                v ? (
                    <DsStatusBadge variant={VIOLATION_VARIANT[v] || 'pending'} label={VIOLATION_LABEL[v] || v}/>
                ) : (
                    <span className="text-ds-small text-ds-text-muted">—</span>
                )
            ),
        },
        {
            title: '标准要求',
            dataIndex: 'expectedValue',
            width: COL.NAME,
            ellipsis: true,
            render: (v?: string) => (
                <span title={v || ''} className="text-ds-small text-ds-text-secondary whitespace-nowrap">
                    {v || '—'}
                </span>
            ),
        },
        {
            title: '实际值',
            dataIndex: 'actualValue',
            width: COL.NAME_COMPACT,
            ellipsis: true,
            render: (v?: string) => (
                <span title={v || ''} className="text-ds-small text-ds-text-secondary font-mono whitespace-nowrap">
                    {v || '—'}
                </span>
            ),
        },
        {
            title: '检查时间',
            dataIndex: 'checkedAt',
            width: COL.DATETIME_COMPACT,
            render: (v?: string) => (
                <span className="text-ds-small text-ds-text-secondary whitespace-nowrap">{formatDateTime(v) || '—'}</span>
            ),
        },
        {
            title: '忽略状态',
            dataIndex: 'ignored',
            width: COL.STATUS,
            render: (v?: number) => (
                v === 1 ? (
                    <DsStatusBadge variant="disabled" label="已忽略"/>
                ) : (
                    <DsStatusBadge variant="success" label="未忽略"/>
                )
            ),
        },
        {
            title: '操作',
            key: 'action',
            width: COL.OPERATION_3,
            render: (_, r) => (
                <div className="flex items-center gap-ds-1 whitespace-nowrap">
                    <Tooltip title="查看元数据">
                        <DsIconButton tone="accent" onClick={() => handleView(r)} aria-label="查看元数据">
                            <HiOutlineEye size={14}/>
                        </DsIconButton>
                    </Tooltip>
                    {r.ignored === 1 ? (
                        <Tooltip title="取消忽略">
                            <DsIconButton tone="accent" onClick={() => openIgnoreConfirm(r, false)} aria-label="取消忽略">
                                <HiOutlineHandThumbUp size={14}/>
                            </DsIconButton>
                        </Tooltip>
                    ) : (
                        <Tooltip title="忽略">
                            <DsIconButton tone="danger" onClick={() => openIgnoreConfirm(r, true)} aria-label="忽略">
                                <HiOutlineXCircle size={14}/>
                            </DsIconButton>
                        </Tooltip>
                    )}
                </div>
            ),
        },
    ], []);

    // ============ 三格统计卡片 ============
    const statCards = [
        {
            label: '不合规项',
            value: summaryLoading ? '…' : (summary?.nonCompliant ?? 0),
            accent: 'text-ds-danger',
            icon: <HiOutlineXCircle size={22}/>,
            iconBg: 'bg-ds-danger-light text-ds-danger',
        },
        {
            label: '已忽略',
            value: summaryLoading ? '…' : (summary?.ignored ?? 0),
            accent: 'text-ds-text-secondary',
            icon: <HiOutlineCheckCircle size={22}/>,
            iconBg: 'bg-ds-bg-hover text-ds-text-muted',
        },
        {
            label: '合规率',
            value: summaryLoading ? '…' : `${(summary?.complianceRate ?? 0).toFixed(1)}%`,
            accent: 'text-ds-success',
            icon: <HiOutlineShieldCheck size={22}/>,
            iconBg: 'bg-ds-success-light text-ds-success',
            tip: '对象合规率（按表/字段对象估算，已忽略项视为已豁免）',
        },
    ];

    // ============ 渲染 ============
    return (
        <div className="flex flex-col">
            <div className="flex items-center justify-between mb-ds-5 flex-shrink-0">
                <div>
                    <h1 className="text-ds-display text-ds-text-primary">标准合规</h1>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1">
                        对照命名规范与字段类型标准扫描元数据，管理不合规问题清单
                    </p>
                </div>
                <div className="flex items-center gap-ds-2 flex-shrink-0">
                    <DsButton variant="secondary" onClick={handleExport} disabled={loading}>
                        <HiOutlineDownload size={16}/>
                        导出问题清单
                    </DsButton>
                    {canWrite && (
                        <DsButton onClick={openScan}>
                            <HiOutlineShieldCheck size={16}/>
                            立即扫描
                        </DsButton>
                    )}
                </div>
            </div>

            {/* 三格统计 */}
            <div className="grid grid-cols-3 gap-ds-4 mb-ds-5 flex-shrink-0">
                {statCards.map((card) => (
                    <div
                        key={card.label}
                        className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle p-ds-4 flex items-center gap-ds-4"
                        title={card.tip}
                    >
                        <div className={`w-11 h-11 rounded-ds-md flex items-center justify-center flex-shrink-0 ${card.iconBg}`}>
                            {card.icon}
                        </div>
                        <div className="min-w-0">
                            <div className={`text-ds-display font-bold leading-none ${card.accent}`}>
                                {card.value}
                            </div>
                            <div className="text-ds-small text-ds-text-muted mt-ds-1.5">{card.label}</div>
                        </div>
                    </div>
                ))}
            </div>

            {/* 扫描结果清单 */}
            <div className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden flex flex-col">
                <div className="p-ds-3 border-b border-ds-border-subtle flex-shrink-0">
                    <DsToolbar
                        extra={(
                            <>
                                <DsButton onClick={() => { setPage(1); loadList(); }} disabled={loading}>
                                    {loading ? '查询中...' : '查询'}
                                </DsButton>
                                <DsButton variant="secondary" onClick={resetFilters}>重置</DsButton>
                            </>
                        )}
                    >
                        <DsFilterSelect
                            value={datasourceId}
                            onChange={setDatasourceId}
                            aria-label="按数据源筛选"
                            options={datasourceOptions}
                        />
                        <DsFilterSelect
                            value={violationType}
                            onChange={setViolationType}
                            aria-label="按违规类型筛选"
                            options={VIOLATION_OPTIONS}
                        />
                        <DsFilterSelect
                            value={ignored}
                            onChange={setIgnored}
                            aria-label="按忽略状态筛选"
                            options={IGNORED_OPTIONS}
                        />
                    </DsToolbar>
                </div>
                <div className="overflow-x-auto">
                    <Table
                        rowKey="id"
                        columns={columns}
                        dataSource={items}
                        loading={loading}
                        pagination={false}
                        scroll={{x: 1200}}
                        className="prototype-table prototype-table-flush"
                        locale={{emptyText: <DsTableEmpty description="当前筛选范围内暂无不合规项"/>}}
                    />
                </div>
                {total > 0 && (
                    <Pagination
                        page={page}
                        pageSize={pageSize}
                        total={total}
                        onChange={(p, s) => {
                            setPage(p);
                            setPageSize(s);
                        }}
                    />
                )}
            </div>

            {/* 立即扫描弹窗 */}
            <DsModal
                open={scanOpen}
                onClose={() => setScanOpen(false)}
                title="标准合规扫描"
                width="w-[480px]"
                footer={
                    <>
                        <DsButton variant="secondary" onClick={() => setScanOpen(false)}>取消</DsButton>
                        <DsButton onClick={handleScan} disabled={scanning}>
                            {scanning ? '扫描中...' : '开始扫描'}
                        </DsButton>
                    </>
                }
            >
                <div className="space-y-ds-4">
                    <div>
                        <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                            检查范围 <span className="text-ds-danger">*</span>
                        </label>
                        <div className="space-y-ds-2 max-h-[200px] overflow-auto border border-ds-border-subtle rounded-ds-sm p-ds-3 bg-white">
                            <label className="flex items-center gap-ds-2 cursor-pointer">
                                <input
                                    type="checkbox"
                                    checked={scanDsIds.length === datasources.length && datasources.length > 0}
                                    onChange={(e) => {
                                        setScanDsIds(e.target.checked ? datasources.map((ds) => String(ds.id)) : []);
                                    }}
                                    className="w-4 h-4 text-ds-accent border-ds-border-subtle rounded focus:ring-ds-accent"
                                />
                                <span className="text-ds-small text-ds-text-secondary font-medium">全部数据源</span>
                            </label>
                            {datasources.map((ds) => {
                                const id = String(ds.id);
                                const checked = scanDsIds.includes(id);
                                return (
                                    <label key={ds.id} className="flex items-center gap-ds-2 cursor-pointer pl-ds-4">
                                        <input
                                            type="checkbox"
                                            checked={checked}
                                            onChange={(e) => {
                                                setScanDsIds((prev) =>
                                                    e.target.checked ? [...prev, id] : prev.filter((v) => v !== id)
                                                );
                                            }}
                                            className="w-4 h-4 text-ds-accent border-ds-border-subtle rounded focus:ring-ds-accent"
                                        />
                                        <span className="text-ds-small text-ds-text-secondary">{ds.name || `数据源 ${ds.id}`}</span>
                                    </label>
                                );
                            })}
                        </div>
                    </div>

                    <div>
                        <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">检查项目</label>
                        <div className="space-y-ds-2">
                            <label className="flex items-center gap-ds-2 cursor-pointer">
                                <input
                                    type="checkbox"
                                    checked={checkNaming}
                                    onChange={(e) => setCheckNaming(e.target.checked)}
                                    className="w-4 h-4 text-ds-accent border-ds-border-subtle rounded focus:ring-ds-accent"
                                />
                                <span className="text-ds-small text-ds-text-secondary">命名规范</span>
                            </label>
                            <label className="flex items-center gap-ds-2 cursor-pointer">
                                <input
                                    type="checkbox"
                                    checked={checkFieldType}
                                    onChange={(e) => setCheckFieldType(e.target.checked)}
                                    className="w-4 h-4 text-ds-accent border-ds-border-subtle rounded focus:ring-ds-accent"
                                />
                                <span className="text-ds-small text-ds-text-secondary">字段类型标准</span>
                            </label>
                        </div>
                    </div>
                </div>
            </DsModal>

            {/* 忽略/取消忽略确认 */}
            <ConfirmDialog
                open={ignoreOpen}
                title={ignoreTarget?.ignore ? '忽略问题' : '取消忽略'}
                message={
                    <p className="text-ds-body text-ds-text-secondary">
                        {ignoreTarget?.ignore
                            ? <>确定忽略 <strong>"{ignoreTarget?.objectPath}"</strong> 吗？忽略后视为已豁免/已整改，不再计入不合规项。</>
                            : <>确定取消忽略 <strong>"{ignoreTarget?.objectPath}"</strong> 吗？取消后将重新计入不合规项。</>}
                    </p>
                }
                confirmLabel={ignoreTarget?.ignore ? '确认忽略' : '确认取消'}
                loading={ignoreLoading}
                onConfirm={handleIgnore}
                onCancel={() => {
                    if (ignoreLoading) return;
                    setIgnoreOpen(false);
                    setIgnoreTarget(null);
                }}
            />
        </div>
    );
}
