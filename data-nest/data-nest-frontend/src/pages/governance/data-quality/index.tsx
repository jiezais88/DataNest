import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {useSearchParams} from 'react-router-dom';
import {Table, Tooltip} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import {notify} from '../../../utils/notify';
import {formatDateTime} from '../../../utils/format';
import {nextRunTime} from '../../../utils/cron';
import {useHasRole} from '../../../hooks/useHasRole';
import {GOVERNANCE_WRITE_ROLES} from '../../../constants/roles';
import {COL} from '../../../constants/table';
import {
    createQualityJob,
    deleteQualityJob,
    queryQualityJobs,
    toggleQualityJob,
    updateQualityJob,
} from '../../../api/quality';
import {getDataSources} from '../../../api/datasource';
import DsButton from '../../../components/DsButton';
import DsIconButton from '../../../components/DsIconButton';
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
import type {
    QualityAlertLevel,
    QualityJob,
    AutoTriggerObjectType,
} from '../../../types/quality';
import QualityJobDrawer from './QualityJobDrawer';

type Tab = 'jobs';

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

    // ============ 质量规则（已独立为独立菜单 /governance/quality-rules） ============

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

    useEffect(() => {
        loadJobs();
    }, [loadJobs]);

    useEffect(() => {
        loadStats();
    }, [loadStats]);

    // 进页加载数据源（筛选下拉）
    useEffect(() => {
        loadJobDatasources();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    // URL 状态同步（对齐 data-standards）：进页初始化一次 Tab/任务页筛选与分页，深层跳转返回后筛选不丢
    const urlInitRef = useRef(false);
    useEffect(() => {
        if (urlInitRef.current) return;
        urlInitRef.current = true;
        const p = searchParams;
        const tab: Tab = 'jobs';
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
            setDeleteJobOpen(false);
            setDeleteJobTarget(null);
            loadJobs();
            loadStats();
        } finally {
            setDeleteJobLoading(false);
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
            title: '触发方式',
            dataIndex: 'scheduledEnabled',
            width: COL.TRIGGER_TYPE,
            render: (_, item) => {
                const parts: string[] = ['手动'];
                if (item.scheduledEnabled === 1) parts.push('定时');
                if (item.autoTriggerEnabled === 1) parts.push('自动触发');
                return (
                    <span className="text-ds-small text-ds-text-secondary whitespace-nowrap">{parts.join(' + ')}</span>
                );
            },
        },
        {
            title: 'Cron 表达式',
            dataIndex: 'cron',
            width: COL.CRON,
            ellipsis: true,
            render: (cron?: string, item?) => (
                <span
                    title={cron || '—'}
                    className="text-ds-small text-ds-text-secondary font-mono whitespace-nowrap"
                >
                    {item?.scheduledEnabled === 1 && cron ? cron : '—'}
                </span>
            ),
        },
        {
            title: '调度状态',
            dataIndex: 'scheduleStatusBadge',
            width: COL.STATUS,
            render: (v?: string, item?) => (
                item?.scheduledEnabled === 1 ? (
                    <DsStatusBadge label={v || '已启用'} variant="running"/>
                ) : (
                    <span className="text-ds-small text-ds-text-muted">—</span>
                )
            ),
        },
        {
            title: '下次执行时间',
            key: 'nextRunTime',
            width: COL.DATETIME,
            render: (_, item) => {
                if (item.scheduledEnabled !== 1 || !item.cron) {
                    return <span className="text-ds-small text-ds-text-muted whitespace-nowrap">—</span>;
                }
                const next = nextRunTime(item.cron);
                return (
                    <span className="text-ds-small text-ds-text-secondary whitespace-nowrap">
                        {next ? formatDateTime(next.toISOString()) : '—'}
                    </span>
                );
            },
        },
        {
            title: '最近执行状态',
            key: 'lastRunStatus',
            width: COL.STATUS,
            render: () => (
                <span className="text-ds-small text-ds-text-muted">—</span>
            ),
        },
        {
            title: '最近执行',
            key: 'lastRunAt',
            width: COL.DATETIME,
            render: () => (
                <span className="text-ds-small text-ds-text-muted whitespace-nowrap">—</span>
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
            title: '修改人',
            dataIndex: 'updatedByName',
            width: COL.USERNAME,
            ellipsis: true,
            render: (v?: string) => (
                <span title={v || '—'} className="text-ds-small text-ds-text-secondary">{v || '—'}</span>
            ),
        },
        {
            title: '修改时间',
            dataIndex: 'updatedAt',
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
                        <DsIconButton tone="accent" onClick={() => handleExecuteJob()} aria-label="执行">
                            <HiOutlinePlay size={14}/>
                        </DsIconButton>
                    </Tooltip>
                    <Tooltip title="详情">
                        <DsIconButton tone="accent" onClick={() => openJobView(item)} aria-label="详情">
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
                                    aria-label={item.enabled === 1 ? '停用' : '启用'}
                                >
                                    <HiOutlineScale size={14}/>
                                </DsIconButton>
                            </Tooltip>
                            <Tooltip title="编辑">
                                <DsIconButton tone="accent" onClick={() => openJobEdit(item)} aria-label="编辑">
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
                                    aria-label="删除"
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

    const tabs = [
        {key: 'jobs' as Tab, label: '质量任务', icon: HiOutlineClipboardDocumentCheck},
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

            <ConfirmDialog
                open={deleteJobOpen}
                title="删除确认"
                message={<p className="text-ds-body text-ds-text-secondary">确定删除质量任务 <strong>"{deleteJobTarget?.name}"</strong> 吗？删除后不可恢复。</p>}
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
        </div>
    );
}
