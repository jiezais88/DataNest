import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {useSearchParams} from 'react-router-dom';
import {Table, Tooltip} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import {notify} from '../../../utils/notify';
import {formatDateTime} from '../../../utils/format';
import {useHasRole} from '../../../hooks/useHasRole';
import {GOVERNANCE_WRITE_ROLES} from '../../../constants/roles';
import {COL} from '../../../constants/table';
import {
    batchCreateQualityRules,
    createQualityJob,
    createQualityRule,
    deleteQualityJob,
    deleteQualityRule,
    listQualityRulesByJob,
    previewQualityRuleSql,
    queryQualityJobs,
    toggleQualityJob,
    toggleQualityRule,
    updateQualityJob,
    updateQualityRule,
} from '../../../api/quality';
import {getDataSources} from '../../../api/datasource';
import DsButton from '../../../components/DsButton';
import DsIconButton from '../../../components/DsIconButton';
import DsModal from '../../../components/DsModal';
import DsStatusBadge from '../../../components/DsStatusBadge';
import DsTableEmpty from '../../../components/DsTableEmpty';
import DsToolbar from '../../../components/DsToolbar';
import DsFilterSelect from '../../../components/DsFilterSelect';
import ConfirmDialog from '../../../components/ConfirmDialog';
import Pagination from '../../../components/Pagination';
import SearchInput from '../../../components/SearchInput';
import {
    HiOutlineEye,
    HiOutlinePencilSquare,
    HiOutlinePlay,
    HiOutlinePlus,
    HiOutlineScale,
    HiOutlineTrash,
    HiOutlineClipboardDocumentCheck,
} from 'react-icons/hi2';
import {
    QUALITY_TYPE_LABEL,
} from '../../../types/quality';
import type {
    QualityAlertLevel,
    QualityJob,
    QualityRule,
    QualityRuleType,
    AutoTriggerObjectType,
} from '../../../types/quality';
import QualityJobDrawer from './QualityJobDrawer';
import QualityRuleDrawer from './QualityRuleDrawer';
import BatchApplyModal from './BatchApplyModal';

type Tab = 'jobs' | 'rules';

const ALERT_LEVEL_LABEL: Record<QualityAlertLevel, string> = {
    SEVERE_ONLY: '仅严重',
    SEVERE_WARNING: '严重 + 警告',
};

const AUTO_TRIGGER_TYPE_LABEL: Record<AutoTriggerObjectType, string> = {
    DAG_NODE: 'DAG 节点',
    SYNC_JOB: '同步任务',
    COLLECT_TASK: '采集任务',
};

export default function DataQualityPage() {
    const [searchParams, setSearchParams] = useSearchParams();
    const canWrite = useHasRole(...GOVERNANCE_WRITE_ROLES);

    const [activeTab, setActiveTab] = useState<Tab>('jobs');

    // ============ 数据源（筛选 + 统计卡片） ============
    const [datasources, setDatasources] = useState<{ id: string; name: string }[]>([]);

    // ============ 质量任务 ============
    const [jobs, setJobs] = useState<QualityJob[]>([]);
    const [jobTotal, setJobTotal] = useState(0);
    const [jobPage, setJobPage] = useState(1);
    const [jobPageSize, setJobPageSize] = useState(10);
    const [jobKeyword, setJobKeyword] = useState('');
    const [jobDatasourceId, setJobDatasourceId] = useState('');
    const [jobEnabled, setJobEnabled] = useState<string>('');
    const [jobLoading, setJobLoading] = useState(false);
    const [stats, setStats] = useState({all: 0, enabled: 0, disabled: 0});
    const [jobDrawerOpen, setJobDrawerOpen] = useState(false);
    const [jobEditItem, setJobEditItem] = useState<QualityJob | null>(null);
    const [jobDrawerMode, setJobDrawerMode] = useState<'create' | 'edit' | 'view'>('create');
    const [deleteJobTarget, setDeleteJobTarget] = useState<{ id: string; name: string } | null>(null);
    const [deleteJobOpen, setDeleteJobOpen] = useState(false);
    const [deleteJobLoading, setDeleteJobLoading] = useState(false);

    // ============ 质量规则 ============
    const [jobOptions, setJobOptions] = useState<{ id: string; name: string; datasourceId?: string }[]>([]);
    const [selectedJobId, setSelectedJobId] = useState<string>('');
    const [rules, setRules] = useState<QualityRule[]>([]);
    const [rulesLoading, setRulesLoading] = useState(false);
    const [ruleDrawerOpen, setRuleDrawerOpen] = useState(false);
    const [ruleEditItem, setRuleEditItem] = useState<QualityRule | null>(null);
    const [ruleDrawerMode, setRuleDrawerMode] = useState<'create' | 'edit' | 'view'>('create');
    const [batchOpen, setBatchOpen] = useState(false);
    const [deleteRuleTarget, setDeleteRuleTarget] = useState<{ id: string; name: string } | null>(null);
    const [deleteRuleOpen, setDeleteRuleOpen] = useState(false);
    const [deleteRuleLoading, setDeleteRuleLoading] = useState(false);
    const [previewOpen, setPreviewOpen] = useState(false);
    const [previewSql, setPreviewSql] = useState('');
    const [previewLoading, setPreviewLoading] = useState(false);

    // 从全量任务下拉选项中取数据源（比 jobs 当前页更可靠，任务可能不在当前页）
    const selectedJobDatasourceId = useMemo(() => {
        const opt = jobOptions.find((x) => String(x.id) === String(selectedJobId));
        return opt?.datasourceId || '';
    }, [selectedJobId, jobOptions]);

    const loadJobDatasources = useCallback(() => {
        getDataSources({page: 1, pageSize: 1000})
            .then((res) => setDatasources((res.data.records || []).map((d) => ({id: String(d.id), name: d.name}))))
            .catch(() => setDatasources([]));
    }, []);

    const loadJobs = useCallback(async () => {
        setJobLoading(true);
        try {
            const res = await queryQualityJobs({
                page: jobPage,
                pageSize: jobPageSize,
                keyword: jobKeyword || undefined,
                datasourceId: jobDatasourceId || undefined,
                enabled: jobEnabled === '' ? undefined : Number(jobEnabled),
            });
            setJobs(res.data.records);
            setJobTotal(res.data.total);
        } finally {
            setJobLoading(false);
        }
    }, [jobPage, jobPageSize, jobKeyword, jobDatasourceId, jobEnabled]);

    const loadStats = useCallback(async () => {
        try {
            const [all, enabled, disabled] = await Promise.all([
                queryQualityJobs({page: 1, pageSize: 1}),
                queryQualityJobs({page: 1, pageSize: 1, enabled: 1}),
                queryQualityJobs({page: 1, pageSize: 1, enabled: 0}),
            ]);
            setStats({all: all.data.total, enabled: enabled.data.total, disabled: disabled.data.total});
        } catch {
            // ignore
        }
    }, []);

    const loadRules = useCallback(async () => {
        if (!selectedJobId) {
            setRules([]);
            return;
        }
        setRulesLoading(true);
        try {
            const res = await listQualityRulesByJob(selectedJobId);
            setRules(res.data || []);
        } finally {
            setRulesLoading(false);
        }
    }, [selectedJobId]);

    useEffect(() => {
        loadJobs();
    }, [loadJobs]);

    useEffect(() => {
        loadStats();
    }, [loadStats]);

    useEffect(() => {
        loadRules();
    }, [loadRules]);

    // 进页加载数据源 + 任务下拉选项
    useEffect(() => {
        loadJobDatasources();
        queryQualityJobs({page: 1, pageSize: 1000})
            .then((res) => setJobOptions((res.data.records || []).map((j) => ({
                id: String(j.id),
                name: j.name,
                datasourceId: j.datasourceId || undefined,
            }))))
            .catch(() => setJobOptions([]));
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    // URL 状态同步（对齐 data-standards）：进页初始化一次 Tab/任务页筛选与分页，深层跳转返回后筛选不丢
    const urlInitRef = useRef(false);
    useEffect(() => {
        if (urlInitRef.current) return;
        urlInitRef.current = true;
        const p = searchParams;
        const tab: Tab = p.get('tab') === 'rules' ? 'rules' : 'jobs';
        const en = p.get('jobEnabled');
        setActiveTab(tab);
        setJobKeyword(p.get('jobKeyword') || '');
        setJobDatasourceId(p.get('jobDatasourceId') || '');
        setJobEnabled(en === '1' || en === '0' ? en : '');
        setJobPage(Number(p.get('jobPage')) || 1);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    useEffect(() => {
        const next = new URLSearchParams();
        next.set('tab', activeTab);
        if (jobKeyword) next.set('jobKeyword', jobKeyword);
        if (jobDatasourceId) next.set('jobDatasourceId', jobDatasourceId);
        if (jobEnabled === '1' || jobEnabled === '0') next.set('jobEnabled', jobEnabled);
        if (jobPage > 1) next.set('jobPage', String(jobPage));
        if (next.toString() === searchParams.toString()) return;
        setSearchParams(next, {replace: true});
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [activeTab, jobKeyword, jobDatasourceId, jobEnabled, jobPage]);

    const resetJobFilters = () => {
        setJobKeyword('');
        setJobDatasourceId('');
        setJobEnabled('');
        setJobPage(1);
    };

    // ============ 质量任务操作 ============
    const openJobCreate = () => {
        setJobEditItem(null);
        setJobDrawerMode('create');
        setJobDrawerOpen(true);
    };

    const openJobEdit = useCallback((item: QualityJob) => {
        setJobEditItem(item);
        setJobDrawerMode('edit');
        setJobDrawerOpen(true);
    }, []);

    const openJobView = useCallback((item: QualityJob) => {
        setJobEditItem(item);
        setJobDrawerMode('view');
        setJobDrawerOpen(true);
    }, []);

    const handleJobSubmit = async (payload: Parameters<typeof createQualityJob>[0]) => {
        if (jobEditItem) {
            await updateQualityJob(jobEditItem.id, payload);
            notify.success('质量任务更新成功');
        } else {
            await createQualityJob(payload);
            notify.success('质量任务创建成功');
        }
        loadJobs();
        loadStats();
        // 刷新任务下拉选项
        queryQualityJobs({page: 1, pageSize: 1000})
            .then((res) => setJobOptions((res.data.records || []).map((j) => ({
                id: String(j.id),
                name: j.name,
                datasourceId: j.datasourceId || undefined,
            }))))
            .catch(() => setJobOptions([]));
    };

    const handleToggleJob = useCallback(async (item: QualityJob) => {
        const nextEnabled = item.enabled === 1 ? 0 : 1;
        await toggleQualityJob(item.id, nextEnabled);
        notify.success(nextEnabled === 1 ? '已启用' : '已停用');
        loadJobs();
        loadStats();
    }, [loadJobs, loadStats]);

    const handleExecuteJob = () => {
        notify.info('执行功能待实现（下一批交付）');
    };

    const handleDeleteJob = async () => {
        if (!deleteJobTarget) return;
        setDeleteJobLoading(true);
        try {
            await deleteQualityJob(deleteJobTarget.id);
            notify.success('删除成功');
            if (String(selectedJobId) === String(deleteJobTarget.id)) {
                setSelectedJobId('');
                setRules([]);
            }
            setDeleteJobOpen(false);
            setDeleteJobTarget(null);
            loadJobs();
            loadStats();
        } finally {
            setDeleteJobLoading(false);
        }
    };

    // ============ 质量规则操作 ============
    const openRuleCreate = () => {
        if (!selectedJobId) {
            notify.warning('请先选择质量任务');
            return;
        }
        setRuleEditItem(null);
        setRuleDrawerMode('create');
        setRuleDrawerOpen(true);
    };

    const openRuleEdit = useCallback((item: QualityRule) => {
        setRuleEditItem(item);
        setRuleDrawerMode('edit');
        setRuleDrawerOpen(true);
    }, []);

    const openRuleView = useCallback((item: QualityRule) => {
        setRuleEditItem(item);
        setRuleDrawerMode('view');
        setRuleDrawerOpen(true);
    }, []);

    const handleRuleSubmit = async (payload: Parameters<typeof createQualityRule>[0]) => {
        if (ruleEditItem) {
            await updateQualityRule(ruleEditItem.id, payload);
            notify.success('质量规则更新成功');
        } else {
            await createQualityRule(payload);
            notify.success('质量规则创建成功');
        }
        loadRules();
    };

    const handleToggleRule = useCallback(async (item: QualityRule) => {
        const nextEnabled = item.enabled === 1 ? 0 : 1;
        await toggleQualityRule(item.id, nextEnabled);
        notify.success(nextEnabled === 1 ? '已启用' : '已停用');
        loadRules();
    }, [loadRules]);

    const handleExecuteRule = () => {
        notify.info('执行功能待实现（下一批交付）');
    };

    const handlePreviewSql = async (item: QualityRule) => {
        setPreviewOpen(true);
        setPreviewSql('');
        setPreviewLoading(true);
        try {
            const res = await previewQualityRuleSql(item.id);
            setPreviewSql(res.data || '');
        } finally {
            setPreviewLoading(false);
        }
    };

    const handleBatchSubmit = async (payload: Parameters<typeof batchCreateQualityRules>[0]) => {
        await batchCreateQualityRules(payload);
        notify.success('规则批量应用成功');
        loadRules();
    };

    const handleDeleteRule = async () => {
        if (!deleteRuleTarget) return;
        setDeleteRuleLoading(true);
        try {
            await deleteQualityRule(deleteRuleTarget.id);
            notify.success('删除成功');
            setDeleteRuleOpen(false);
            setDeleteRuleTarget(null);
            loadRules();
        } finally {
            setDeleteRuleLoading(false);
        }
    };

    // ============ 质量任务列 ============
    const jobColumns = useMemo<ColumnsType<QualityJob>>(() => [
        {
            title: '任务名称',
            dataIndex: 'name',
            width: COL.NAME,
            ellipsis: true,
            render: (v: string) => (
                <span title={v} className="text-ds-small text-ds-text-primary font-medium">{v}</span>
            ),
        },
        {
            title: '状态',
            dataIndex: 'enabled',
            width: COL.STATUS,
            render: (enabled: number) => (
                enabled === 1 ? <DsStatusBadge label="启用" variant="success"/> :
                    <DsStatusBadge label="停用" variant="pending"/>
            ),
        },
        {
            title: '数据源范围',
            dataIndex: 'datasourceName',
            width: COL.NAME,
            ellipsis: true,
            render: (v?: string) => (
                <span title={v || '不限'} className="text-ds-small text-ds-text-secondary">{v || '不限'}</span>
            ),
        },
        {
            title: '定时调度',
            dataIndex: 'scheduledEnabled',
            width: COL.TRIGGER_TYPE,
            render: (scheduledEnabled: number, item) => (
                scheduledEnabled === 1 ? (
                    <div className="flex flex-col gap-y-0.5">
                        <DsStatusBadge label={item.scheduleStatusBadge || '已启用'} variant="running"/>
                        <span className="text-ds-nano text-ds-text-muted font-mono">{item.cron || '—'}</span>
                    </div>
                ) : (
                    <span className="text-ds-small text-ds-text-muted">未开启</span>
                )
            ),
        },
        {
            title: '自动触发',
            dataIndex: 'autoTriggerEnabled',
            width: 100,
            render: (autoTriggerEnabled: number, item) => (
                autoTriggerEnabled === 1 ? (
                    <span className="text-ds-small text-ds-text-secondary">
                        {item.autoTriggerObjectType ? AUTO_TRIGGER_TYPE_LABEL[item.autoTriggerObjectType] : '—'}
                    </span>
                ) : (
                    <span className="text-ds-small text-ds-text-muted">未开启</span>
                )
            ),
        },
        {
            title: '告警等级',
            dataIndex: 'alertLevel',
            width: 100,
            render: (v: QualityAlertLevel) => (
                <span className="text-ds-small text-ds-text-secondary">{ALERT_LEVEL_LABEL[v] || v}</span>
            ),
        },
        {
            title: '规则数',
            dataIndex: 'ruleCount',
            width: COL.COUNT,
            render: (v?: number) => (
                <span className="text-ds-small text-ds-text-secondary">{v ?? 0}</span>
            ),
        },
        {
            title: '最近触发',
            dataIndex: 'lastTriggerAt',
            width: COL.DATETIME,
            render: (v?: string) => (
                <span className="text-ds-small text-ds-text-secondary whitespace-nowrap">{v ? formatDateTime(v) : '—'}</span>
            ),
        },
        {
            title: '创建人',
            dataIndex: 'createdByName',
            width: COL.USERNAME,
            ellipsis: true,
            render: (v?: string) => (
                <span title={v || '—'} className="text-ds-small text-ds-text-secondary">{v || '—'}</span>
            ),
        },
        {
            title: '创建时间',
            dataIndex: 'createdAt',
            width: COL.DATETIME,
            render: (v?: string) => (
                <span className="text-ds-small text-ds-text-secondary whitespace-nowrap">{v ? formatDateTime(v) : '—'}</span>
            ),
        },
        {
            title: '操作',
            align: 'center',
            fixed: 'right' as const,
            width: COL.OPERATION_4,
            render: (_, item) => (
                <div className="flex items-center justify-center gap-1 whitespace-nowrap">
                    <Tooltip title="执行">
                        <DsIconButton tone="accent" onClick={() => handleExecuteJob()}>
                            <HiOutlinePlay size={14}/>
                        </DsIconButton>
                    </Tooltip>
                    <Tooltip title="详情">
                        <DsIconButton tone="accent" onClick={() => openJobView(item)}>
                            <HiOutlineEye size={14}/>
                        </DsIconButton>
                    </Tooltip>
                    {canWrite && (
                        <>
                            <Tooltip title={item.enabled === 1 ? '停用' : '启用'}>
                                <DsIconButton
                                    tone="success"
                                    active={item.enabled === 1}
                                    onClick={() => handleToggleJob(item)}
                                >
                                    <HiOutlineScale size={14}/>
                                </DsIconButton>
                            </Tooltip>
                            <Tooltip title="编辑">
                                <DsIconButton tone="accent" onClick={() => openJobEdit(item)}>
                                    <HiOutlinePencilSquare size={14}/>
                                </DsIconButton>
                            </Tooltip>
                            <Tooltip title="删除">
                                <DsIconButton
                                    tone="danger"
                                    onClick={() => {
                                        setDeleteJobTarget({id: item.id, name: item.name});
                                        setDeleteJobOpen(true);
                                    }}
                                >
                                    <HiOutlineTrash size={14}/>
                                </DsIconButton>
                            </Tooltip>
                        </>
                    )}
                </div>
            ),
        },
    ], [canWrite, handleToggleJob, openJobEdit, openJobView]);

    // ============ 质量规则列 ============
    const ruleColumns = useMemo<ColumnsType<QualityRule>>(() => [
        {
            title: '规则名称',
            dataIndex: 'name',
            width: COL.NAME,
            ellipsis: true,
            render: (v: string) => (
                <span title={v} className="text-ds-small text-ds-text-primary font-medium">{v}</span>
            ),
        },
        {
            title: '状态',
            dataIndex: 'enabled',
            width: COL.STATUS,
            render: (enabled: number) => (
                enabled === 1 ? <DsStatusBadge label="启用" variant="success"/> :
                    <DsStatusBadge label="停用" variant="pending"/>
            ),
        },
        {
            title: '类型',
            dataIndex: 'type',
            width: COL.TRIGGER_TYPE,
            render: (v: QualityRuleType) => (
                <span className="text-ds-small text-ds-text-secondary">{QUALITY_TYPE_LABEL[v] || v}</span>
            ),
        },
        {
            title: '对象表',
            dataIndex: 'tableName',
            width: COL.NAME,
            ellipsis: true,
            render: (v?: string) => (
                <span title={v || '—'} className="text-ds-small text-ds-text-secondary font-mono">{v || '—'}</span>
            ),
        },
        {
            title: '检查字段',
            dataIndex: 'columnName',
            width: 120,
            ellipsis: true,
            render: (v?: string, item?: QualityRule) => {
                if (item?.type === 'COMPLETENESS' && item.checkField !== 1) return <span className="text-ds-small text-ds-text-muted">整表</span>;
                return <span title={v || '—'} className="text-ds-small text-ds-text-secondary font-mono">{v || '—'}</span>;
            },
        },
        {
            title: '阈值',
            key: 'threshold',
            width: 140,
            render: (_, item) => (
                <span className="text-ds-small text-ds-text-secondary font-mono whitespace-nowrap">
                    {item.type === 'RANGE'
                        ? `${item.warningThreshold ?? '-'} ~ ${item.severeThreshold ?? '-'}`
                        : `${item.warningThreshold ?? '-'} / ${item.severeThreshold ?? '-'}`}
                </span>
            ),
        },
        {
            title: '权重',
            dataIndex: 'weight',
            width: COL.COUNT,
            render: (v?: number) => (
                <span className="text-ds-small text-ds-text-secondary">{v ?? 1}</span>
            ),
        },
        {
            title: '创建人',
            dataIndex: 'createdByName',
            width: COL.USERNAME,
            ellipsis: true,
            render: (v?: string) => (
                <span title={v || '—'} className="text-ds-small text-ds-text-secondary">{v || '—'}</span>
            ),
        },
        {
            title: '创建时间',
            dataIndex: 'createdAt',
            width: COL.DATETIME,
            render: (v?: string) => (
                <span className="text-ds-small text-ds-text-secondary whitespace-nowrap">{v ? formatDateTime(v) : '—'}</span>
            ),
        },
        {
            title: '操作',
            align: 'center',
            fixed: 'right' as const,
            width: COL.OPERATION_5,
            render: (_, item) => (
                <div className="flex items-center justify-center gap-1 whitespace-nowrap">
                    <Tooltip title="执行">
                        <DsIconButton tone="accent" onClick={() => handleExecuteRule()}>
                            <HiOutlinePlay size={14}/>
                        </DsIconButton>
                    </Tooltip>
                    <Tooltip title="预览 SQL">
                        <DsIconButton tone="accent" onClick={() => handlePreviewSql(item)}>
                            <HiOutlineClipboardDocumentCheck size={14}/>
                        </DsIconButton>
                    </Tooltip>
                    <Tooltip title="详情">
                        <DsIconButton tone="accent" onClick={() => openRuleView(item)}>
                            <HiOutlineEye size={14}/>
                        </DsIconButton>
                    </Tooltip>
                    {canWrite && (
                        <>
                            <Tooltip title={item.enabled === 1 ? '停用' : '启用'}>
                                <DsIconButton
                                    tone="success"
                                    active={item.enabled === 1}
                                    onClick={() => handleToggleRule(item)}
                                >
                                    <HiOutlineScale size={14}/>
                                </DsIconButton>
                            </Tooltip>
                            <Tooltip title="编辑">
                                <DsIconButton tone="accent" onClick={() => openRuleEdit(item)}>
                                    <HiOutlinePencilSquare size={14}/>
                                </DsIconButton>
                            </Tooltip>
                            <Tooltip title="删除">
                                <DsIconButton
                                    tone="danger"
                                    onClick={() => {
                                        setDeleteRuleTarget({id: item.id, name: item.name});
                                        setDeleteRuleOpen(true);
                                    }}
                                >
                                    <HiOutlineTrash size={14}/>
                                </DsIconButton>
                            </Tooltip>
                        </>
                    )}
                </div>
            ),
        },
    ], [canWrite, handleToggleRule, openRuleEdit, openRuleView]);

    const tabs = [
        {key: 'jobs' as Tab, label: '质量任务', icon: HiOutlineClipboardDocumentCheck},
        {key: 'rules' as Tab, label: '质量规则', icon: HiOutlineScale},
    ];

    const statCards = [
        {label: '全部任务', value: stats.all},
        {label: '已启用', value: stats.enabled},
        {label: '已停用', value: stats.disabled},
    ];

    return (
        <div className="flex flex-col">
            <div className="flex items-center justify-between mb-ds-5 flex-shrink-0">
                <div>
                    <h1 className="text-ds-display text-ds-text-primary">数据质量</h1>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1">配置质量任务与质量规则，对数据资产进行质量检查与评分</p>
                </div>
            </div>

            <div className="flex gap-ds-2 mb-ds-4 flex-shrink-0">
                {tabs.map((t) => {
                    const Icon = t.icon;
                    const active = activeTab === t.key;
                    return (
                        <button
                            key={t.key}
                            onClick={() => setActiveTab(t.key)}
                            className={`flex items-center gap-ds-2 px-ds-4 py-ds-2 rounded-ds-sm text-ds-small font-semibold transition-colors ${
                                active
                                    ? 'bg-ds-accent-light text-ds-accent'
                                    : 'text-ds-text-secondary hover:bg-ds-bg-hover'
                            }`}
                        >
                            <Icon size={18}/>
                            {t.label}
                        </button>
                    );
                })}
            </div>

            {activeTab === 'jobs' && (
                <div className="flex flex-col gap-ds-4">
                    {/* 统计卡片 */}
                    <div className="grid grid-cols-3 gap-ds-4">
                        {statCards.map((s) => (
                            <div
                                key={s.label}
                                className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle p-ds-4 flex items-center justify-between"
                            >
                                <span className="text-ds-small text-ds-text-secondary">{s.label}</span>
                                <span className="text-ds-title text-ds-text-primary font-bold tabular-nums">{s.value}</span>
                            </div>
                        ))}
                    </div>

                    <div
                        className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden flex flex-col">
                        <div className="p-ds-3 border-b border-ds-border-subtle flex-shrink-0">
                            <DsToolbar
                                extra={(
                                    <>
                                        <DsButton onClick={() => { setJobPage(1); loadJobs(); }} disabled={jobLoading}>
                                            {jobLoading ? '查询中...' : '查询'}
                                        </DsButton>
                                        <DsButton variant="secondary" onClick={resetJobFilters}>重置</DsButton>
                                        {canWrite && (
                                            <DsButton onClick={openJobCreate}>
                                                <HiOutlinePlus size={16}/>
                                                新增质量任务
                                            </DsButton>
                                        )}
                                    </>
                                )}
                            >
                                <SearchInput
                                    value={jobKeyword}
                                    onChange={(e) => setJobKeyword(e.target.value)}
                                    placeholder="搜索任务名称..."
                                />
                                <DsFilterSelect
                                    value={jobDatasourceId}
                                    onChange={setJobDatasourceId}
                                    aria-label="按数据源筛选"
                                    options={[
                                        {value: '', label: '全部数据源'},
                                        ...datasources.map((d) => ({value: d.id, label: d.name})),
                                    ]}
                                />
                                <DsFilterSelect
                                    value={jobEnabled}
                                    onChange={setJobEnabled}
                                    aria-label="按状态筛选"
                                    options={[
                                        {value: '', label: '全部状态'},
                                        {value: '1', label: '启用'},
                                        {value: '0', label: '停用'},
                                    ]}
                                />
                            </DsToolbar>
                        </div>

                        <div className="overflow-x-auto">
                            <Table<QualityJob>
                                dataSource={jobs}
                                rowKey="id"
                                loading={jobLoading}
                                pagination={false}
                                scroll={{x: 1400}}
                                columns={jobColumns}
                                className="prototype-table prototype-table-flush"
                                locale={{
                                    emptyText: (
                                        <DsTableEmpty
                                            description="暂无质量任务，创建第一个任务开始质量检查。"
                                            action={canWrite && (
                                                <DsButton onClick={openJobCreate}>
                                                    <HiOutlinePlus size={16}/>
                                                    新增质量任务
                                                </DsButton>
                                            )}
                                        />
                                    ),
                                }}
                            />
                        </div>

                        <Pagination
                            page={jobPage}
                            pageSize={jobPageSize}
                            total={jobTotal}
                            onChange={(p, s) => {
                                setJobPage(p);
                                setJobPageSize(s);
                            }}
                        />
                    </div>
                </div>
            )}

            {activeTab === 'rules' && (
                <div className="flex flex-col gap-ds-4">
                    <div
                        className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden flex flex-col">
                        <div className="p-ds-3 border-b border-ds-border-subtle flex-shrink-0">
                            <DsToolbar
                                extra={(
                                    <>
                                        {canWrite && selectedJobId && (
                                            <>
                                                <DsButton variant="secondary" onClick={() => setBatchOpen(true)}>
                                                    <HiOutlineClipboardDocumentCheck size={16}/>
                                                    模板批量应用
                                                </DsButton>
                                                <DsButton onClick={openRuleCreate}>
                                                    <HiOutlinePlus size={16}/>
                                                    新增规则
                                                </DsButton>
                                            </>
                                        )}
                                    </>
                                )}
                            >
                                <label className="text-ds-small font-semibold text-ds-text-secondary whitespace-nowrap">
                                    质量任务
                                </label>
                                <DsFilterSelect
                                    value={selectedJobId}
                                    onChange={setSelectedJobId}
                                    aria-label="选择质量任务"
                                    className="min-w-[220px]"
                                    options={[
                                        {value: '', label: '请选择任务'},
                                        ...jobOptions.map((j) => ({value: j.id, label: j.name})),
                                    ]}
                                />
                            </DsToolbar>
                        </div>

                        <div className="overflow-x-auto">
                            <Table<QualityRule>
                                dataSource={rules}
                                rowKey="id"
                                loading={rulesLoading}
                                pagination={false}
                                scroll={{x: 1300}}
                                columns={ruleColumns}
                                className="prototype-table prototype-table-flush"
                                locale={{
                                    emptyText: (
                                        <DsTableEmpty
                                            description={selectedJobId ? '该任务暂无质量规则，可新增或批量应用模板。' : '请先选择质量任务以查看其规则。'}
                                            action={canWrite && selectedJobId && (
                                                <DsButton onClick={openRuleCreate}>
                                                    <HiOutlinePlus size={16}/>
                                                    新增规则
                                                </DsButton>
                                            )}
                                        />
                                    ),
                                }}
                            />
                        </div>
                    </div>
                </div>
            )}

            <QualityJobDrawer
                open={jobDrawerOpen}
                mode={jobDrawerMode}
                editItem={jobEditItem}
                onClose={() => {
                    setJobDrawerOpen(false);
                    setJobEditItem(null);
                }}
                onSubmit={handleJobSubmit}
            />

            <QualityRuleDrawer
                open={ruleDrawerOpen}
                mode={ruleDrawerMode}
                editItem={ruleEditItem}
                jobId={selectedJobId}
                defaultDatasourceId={selectedJobDatasourceId}
                onClose={() => {
                    setRuleDrawerOpen(false);
                    setRuleEditItem(null);
                }}
                onSubmit={handleRuleSubmit}
            />

            <BatchApplyModal
                open={batchOpen}
                jobId={selectedJobId}
                defaultDatasourceId={selectedJobDatasourceId}
                onClose={() => setBatchOpen(false)}
                onSubmit={handleBatchSubmit}
            />

            <ConfirmDialog
                open={deleteJobOpen}
                title="删除确认"
                message={<p className="text-ds-body text-ds-text-secondary">确定删除质量任务 <strong>"{deleteJobTarget?.name}"</strong> 吗？其下规则将一并删除，且不可恢复。</p>}
                confirmLabel="确认删除"
                danger
                loading={deleteJobLoading}
                onConfirm={handleDeleteJob}
                onCancel={() => {
                    if (deleteJobLoading) return;
                    setDeleteJobOpen(false);
                    setDeleteJobTarget(null);
                }}
            />

            <ConfirmDialog
                open={deleteRuleOpen}
                title="删除确认"
                message={<p className="text-ds-body text-ds-text-secondary">确定删除质量规则 <strong>"{deleteRuleTarget?.name}"</strong> 吗？删除后不可恢复。</p>}
                confirmLabel="确认删除"
                danger
                loading={deleteRuleLoading}
                onConfirm={handleDeleteRule}
                onCancel={() => {
                    if (deleteRuleLoading) return;
                    setDeleteRuleOpen(false);
                    setDeleteRuleTarget(null);
                }}
            />

            <DsModal
                open={previewOpen}
                onClose={() => setPreviewOpen(false)}
                title="规则执行 SQL 预览"
                width="w-[680px]"
                bordered
                footer={<DsButton variant="secondary" onClick={() => setPreviewOpen(false)}>关闭</DsButton>}
            >
                {previewLoading ? (
                    <p className="text-ds-caption text-ds-text-muted text-center py-ds-6">加载中...</p>
                ) : (
                    <pre className="p-ds-3 bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm text-ds-small text-ds-text-primary font-mono whitespace-pre-wrap break-all">
                        {previewSql || '（无预览 SQL）'}
                    </pre>
                )}
            </DsModal>
        </div>
    );
}
