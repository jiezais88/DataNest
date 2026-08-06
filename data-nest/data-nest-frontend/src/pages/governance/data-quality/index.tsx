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
    executeQualityJob,
    queryQualityJobs,
    startQualityJobSchedule,
    stopQualityJobSchedule,
    toggleQualityJob,
    updateQualityJob,
} from '../../../api/quality';
import DsButton from '../../../components/DsButton';
import DsIconButton from '../../../components/DsIconButton';
import DsStatusBadge from '../../../components/DsStatusBadge';
import DsTableEmpty from '../../../components/DsTableEmpty';
import DsToolbar from '../../../components/DsToolbar';
import DsFilterSelect from '../../../components/DsFilterSelect';
import ConfirmDialog from '../../../components/ConfirmDialog';
import ReferenceListModal from '../../../components/ReferenceListModal';
import type {ApiError} from '../../../utils/error';
import Pagination from '../../../components/Pagination';
import SearchInput from '../../../components/SearchInput';
import {
    HiOutlineCalendar,
    HiOutlineEye,
    HiOutlinePencilSquare,
    HiOutlinePlay,
    HiOutlinePlus,
    HiOutlineScale,
    HiOutlineTrash,
} from 'react-icons/hi2';
import type {
    QualityAlertLevel,
    QualityJob,
    AutoTriggerObjectType,
} from '../../../types/quality';
import QualityJobDrawer from './QualityJobDrawer';

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

    // ============ 质量任务 ============
    const [jobs, setJobs] = useState<QualityJob[]>([]);
    const [jobTotal, setJobTotal] = useState(0);
    const [jobPage, setJobPage] = useState(1);
    const [jobPageSize, setJobPageSize] = useState(10);
    const [jobKeyword, setJobKeyword] = useState('');
    const [jobEnabled, setJobEnabled] = useState<string>('');
    const [jobLoading, setJobLoading] = useState(false);
    /** 调度开关 loading（记录当前正在切换调度的任务 ID） */
    const [schedulingId, setSchedulingId] = useState<string>('');
    /** 执行按钮 loading（记录当前正在触发的任务 ID） */
    const [executingId, setExecutingId] = useState<string>('');
    const [jobDrawerOpen, setJobDrawerOpen] = useState(false);
    const [jobEditItem, setJobEditItem] = useState<QualityJob | null>(null);
    const [jobDrawerMode, setJobDrawerMode] = useState<'create' | 'edit' | 'view'>('create');
    const [deleteJobTarget, setDeleteJobTarget] = useState<{ id: string; name: string } | null>(null);
    const [deleteJobOpen, setDeleteJobOpen] = useState(false);
    const [deleteJobLoading, setDeleteJobLoading] = useState(false);
    const [deleteBlockedOpen, setDeleteBlockedOpen] = useState(false);
    const [deleteReferences, setDeleteReferences] = useState<string[]>([]);

    // ============ 质量规则（已独立为独立菜单 /governance/quality-rules） ============

    const loadJobs = useCallback(async () => {
        setJobLoading(true);
        try {
            const res = await queryQualityJobs({
                page: jobPage,
                pageSize: jobPageSize,
                keyword: jobKeyword || undefined,
                enabled: jobEnabled === '' ? undefined : Number(jobEnabled),
            });
            setJobs(res.data.records);
            setJobTotal(res.data.total);
        } finally {
            setJobLoading(false);
        }
    }, [jobPage, jobPageSize, jobKeyword, jobEnabled]);

    useEffect(() => {
        loadJobs();
    }, [loadJobs]);

    // URL 状态同步（对齐 data-standards）：进页初始化一次任务筛选与分页，深层跳转返回后筛选不丢
    const urlInitRef = useRef(false);
    useEffect(() => {
        if (urlInitRef.current) return;
        urlInitRef.current = true;
        const p = searchParams;
        const en = p.get('jobEnabled');
        setJobKeyword(p.get('jobKeyword') || '');
        setJobEnabled(en === '1' || en === '0' ? en : '');
        setJobPage(Number(p.get('jobPage')) || 1);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    useEffect(() => {
        const next = new URLSearchParams();
        if (jobKeyword) next.set('jobKeyword', jobKeyword);
        if (jobEnabled === '1' || jobEnabled === '0') next.set('jobEnabled', jobEnabled);
        if (jobPage > 1) next.set('jobPage', String(jobPage));
        if (next.toString() === searchParams.toString()) return;
        setSearchParams(next, {replace: true});
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [jobKeyword, jobEnabled, jobPage]);

    const resetJobFilters = () => {
        setJobKeyword('');
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
    };

    const handleToggleJob = useCallback(async (item: QualityJob) => {
        const nextEnabled = item.enabled === 1 ? 0 : 1;
        await toggleQualityJob(item.id, nextEnabled);
        notify.success(nextEnabled === 1 ? '已启用' : '已停用');
        loadJobs();
    }, [loadJobs]);

    /** 列表内直接开启/关闭调度（参考同步任务操作列调度开关） */
    const handleToggleSchedule = useCallback(async (item: QualityJob) => {
        const enabling = item.scheduledEnabled !== 1;
        setSchedulingId(item.id);
        try {
            if (enabling) {
                await startQualityJobSchedule(item.id);
            } else {
                await stopQualityJobSchedule(item.id);
            }
            notify.success(`已${enabling ? '开启' : '关闭'}定时调度`);
            loadJobs();
        } finally {
            setSchedulingId('');
        }
    }, [loadJobs]);

    const handleExecuteJob = useCallback(async (item: QualityJob) => {
        setExecutingId(item.id);
        try {
            await executeQualityJob(item.id);
            notify.success('已触发执行，请到「质量检查历史」查看结果');
        } finally {
            setExecutingId('');
        }
    }, []);

    const handleDeleteJob = async () => {
        if (!deleteJobTarget) return;
        setDeleteJobLoading(true);
        try {
            await deleteQualityJob(deleteJobTarget.id);
            notify.success('删除成功');
            setDeleteJobOpen(false);
            setDeleteJobTarget(null);
            loadJobs();
        } catch (e) {
            const errorData = (e as ApiError)?.response?.data;
            // 被告警规则引用时（3005），后端 data 返回引用告警规则名称列表，弹窗展示
            if (errorData?.code === 3005 && Array.isArray(errorData?.data)) {
                setDeleteReferences(errorData.data as string[]);
                setDeleteJobOpen(false);
                setDeleteBlockedOpen(true);
            }
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
            title: '触发方式',
            dataIndex: 'scheduledEnabled',
            width: COL.TRIGGER_TYPE,
            render: (_, item) => {
                // 触发方式为单选：自动触发 > 定时 > 手动（历史数据若同时开启，按此优先级归一展示）
                const mode = item.autoTriggerEnabled === 1 ? '自动触发'
                    : (item.scheduledEnabled === 1 ? '定时' : '手动');
                return (
                    <span className="text-ds-small text-ds-text-secondary whitespace-nowrap">{mode}</span>
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
            width: 160,
            render: (autoTriggerEnabled: number, item) => (
                autoTriggerEnabled === 1 ? (
                    <span className="text-ds-small text-ds-text-secondary whitespace-nowrap">
                        {item.autoTriggerObjectType ? AUTO_TRIGGER_TYPE_LABEL[item.autoTriggerObjectType] : '—'}
                        {item.autoTriggerObjectName ? (
                            <span
                                title={item.autoTriggerObjectName}
                                className="text-ds-text-primary font-medium"
                            >
                                （{item.autoTriggerObjectName}）
                            </span>
                        ) : null}
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
            width: COL.OPERATION_3,
            render: (_, item) => (
                <div className="flex flex-col items-center justify-center gap-y-1 whitespace-nowrap">
                    {/* 主操作：执行 / 详情 / 编辑 */}
                    <div className="flex items-center justify-center gap-1">
                        <Tooltip title="执行">
                            <DsIconButton
                                tone="accent"
                                onClick={() => handleExecuteJob(item)}
                                disabled={executingId === item.id}
                                aria-label="执行"
                            >
                                <HiOutlinePlay size={14}/>
                            </DsIconButton>
                        </Tooltip>
                        <Tooltip title="详情">
                            <DsIconButton tone="accent" onClick={() => openJobView(item)} aria-label="详情">
                                <HiOutlineEye size={14}/>
                            </DsIconButton>
                        </Tooltip>
                        {canWrite && (
                            <Tooltip title="编辑">
                                <DsIconButton tone="accent" onClick={() => openJobEdit(item)} aria-label="编辑">
                                    <HiOutlinePencilSquare size={14}/>
                                </DsIconButton>
                            </Tooltip>
                        )}
                    </div>
                    {/* 次要操作：启用 / 调度 / 删除 */}
                    {canWrite && (
                        <div className="flex items-center justify-center gap-1">
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
                            {item.cron && (
                                <Tooltip title={item.scheduledEnabled === 1 ? '关闭定时调度' : '开启定时调度'}>
                                    <DsIconButton
                                        tone="success"
                                        active={item.scheduledEnabled === 1}
                                        data-testid={`quality-job-schedule-${item.name}`}
                                        onClick={() => handleToggleSchedule(item)}
                                        disabled={schedulingId === item.id}
                                        aria-label={item.scheduledEnabled === 1 ? '关闭定时调度' : '开启定时调度'}
                                    >
                                        <HiOutlineCalendar size={14}/>
                                    </DsIconButton>
                                </Tooltip>
                            )}
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
                        </div>
                    )}
                </div>
            ),
        },
    ], [canWrite, handleToggleJob, handleToggleSchedule, handleExecuteJob, schedulingId, executingId, openJobEdit, openJobView]);

    return (
        <div className="flex flex-col">
            <div className="flex items-center justify-between mb-ds-5 flex-shrink-0">
                <div>
                    <h1 className="text-ds-display text-ds-text-primary">质量任务</h1>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1">配置质量任务并设置触发方式，对数据资产进行质量检查</p>
                </div>
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

            <ReferenceListModal
                open={deleteBlockedOpen}
                title="无法删除质量任务"
                message={`质量任务 "${deleteJobTarget?.name ?? ''}" 已被以下告警规则引用，请先删除相关告警规则后再删除。`}
                references={deleteReferences}
                onClose={() => setDeleteBlockedOpen(false)}
            />
        </div>
    );
}
