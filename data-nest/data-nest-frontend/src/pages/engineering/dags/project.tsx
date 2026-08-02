// 项目内的 DAG 列表页（PRD §6.3：8 列布局）
import {useCallback, useEffect, useMemo, useState} from 'react';
import {Modal, Table, Tooltip} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import {
    HiOutlineCalendar,
    HiOutlineClock,
    HiOutlineEye,
    HiOutlinePencilSquare,
    HiOutlinePlayCircle,
    HiOutlinePlus,
    HiOutlineTrash
} from 'react-icons/hi2';
import {Link, useNavigate, useParams} from 'react-router-dom';
import {deleteDag, getDagProject, listDags, startDagSchedule, stopDagSchedule, triggerDag} from './api';
import type {Dag, DagProject} from './types';
import {useCanEdit} from '../../../hooks/useCanEdit';
import SearchInput from '../../../components/SearchInput';
import Pagination from '../../../components/Pagination';
import DsButton from '../../../components/DsButton';
import DsIconButton from '../../../components/DsIconButton';
import DsFilterSelect from '../../../components/DsFilterSelect';
import DsToolbar from '../../../components/DsToolbar';
import DsStatusBadge, {type DsStatusVariant} from '../../../components/DsStatusBadge';
import DsTableEmpty from '../../../components/DsTableEmpty';
import {executionStatusVariant} from '../../../utils/status';
import {formatDateTime} from '../../../utils/format';
import {notify} from '../../../utils/notify';

const STATUS_OPTIONS: { label: string; value: DsStatusVariant | '' }[] = [
    {label: '全部状态', value: ''},
    {label: '成功', value: 'success'},
    {label: '未执行', value: 'pending'},
    {label: '失败', value: 'danger'},
    {label: '运行中', value: 'running'},
];

export default function ProjectDagsPage() {
    const navigate = useNavigate();
    const canEdit = useCanEdit();
    const {projectId: projectIdParam} = useParams<{ projectId: string }>();
    const projectId = projectIdParam;

    const [project, setProject] = useState<DagProject | null>(null);
    const [dags, setDags] = useState<Dag[]>([]);
    const [loading, setLoading] = useState(false);
    // 删除确认弹框（对齐原型 md-dag-del）
    const [deleteTarget, setDeleteTarget] = useState<Dag | null>(null);
    const [deleting, setDeleting] = useState(false);
    const [schedulingId, setSchedulingId] = useState<string | number | null>(null);

    const [searchName, setSearchName] = useState('');
    const [appliedName, setAppliedName] = useState('');
    const [statusFilter, setStatusFilter] = useState<DsStatusVariant | ''>('');
    const [appliedStatus, setAppliedStatus] = useState<DsStatusVariant | ''>('');
    // 后端 GET /engineering/dev/dags 只接收 projectId、一次性返回全量列表（不支持 page/pageSize），
    // 因此本页保留前端假分页，不接入 usePagedList
    const [page, setPage] = useState(1);
    const [pageSize, setPageSize] = useState(20);

    const refresh = useCallback(async () => {
        if (!projectId) return;
        setLoading(true);
        try {
            // 项目名（用于顶部标题）
            const p = await getDagProject(projectId);
            setProject(p);

            const list = await listDags(projectId);
            setDags(list);
        } catch {
            // 错误提示由 request 拦截器统一弹出
        } finally {
            setLoading(false);
        }
    }, [projectId]);

    useEffect(() => {
        refresh();
    }, [refresh]);

    const handleDelete = async () => {
        if (!deleteTarget) return;
        setDeleting(true);
        try {
            await deleteDag(deleteTarget.id!);
            notify.success('DAG 已删除');
            setDeleteTarget(null);
            await refresh();
        } catch {
            // 错误提示由 request 拦截器统一弹出
        } finally {
            setDeleting(false);
        }
    };

    const handleTrigger = useCallback(async (id: string | number) => {
        try {
            await triggerDag(id);
            notify.success('触发成功');
            // 触发后停留在列表页，由用户主动查看历史
        } catch {
            // 错误提示由 request 拦截器统一弹出
        }
    }, []);

    const handleToggleSchedule = useCallback(async (dag: Dag) => {
        if (!dag.id) return;
        setSchedulingId(dag.id);
        try {
            const enabled = !dag.scheduleEnabled;
            if (enabled) {
                await startDagSchedule(dag.id);
                notify.success(`DAG「${dag.name}」调度已启用`);
            } else {
                await stopDagSchedule(dag.id);
                notify.success(`DAG「${dag.name}」调度已停用`);
            }
            refresh();
        } catch {
            // 错误提示由 request 拦截器统一弹出
        } finally {
            setSchedulingId(null);
        }
    }, [refresh]);

    const handleQuery = () => {
        setAppliedName(searchName);
        setAppliedStatus(statusFilter);
        setPage(1);
        refresh();
    };

    const handleReset = () => {
        setSearchName('');
        setStatusFilter('');
        setAppliedName('');
        setAppliedStatus('');
        setPage(1);
        refresh();
    };

    // 过滤
    const filtered = dags.filter(d => {
        if (appliedName && !d.name.toLowerCase().includes(appliedName.toLowerCase())) return false;
        if (appliedStatus && executionStatusVariant(d.latestExecution?.status) !== appliedStatus) return false;
        return true;
    });
    const paged = filtered.slice((page - 1) * pageSize, page * pageSize);

    const columns = useMemo<ColumnsType<Dag>>(() => [
        {
            title: 'DAG 名称',
            dataIndex: 'name',
            width: 180,
            render: (v: string) => <span className="font-semibold text-ds-text-primary">{v}</span>
        },
        {
            title: '触发方式', width: 100,
            render: (_, r: Dag) => (
                r.triggerType === 'CRON'
                    ? <DsStatusBadge label="定时" variant="accent"/>
                    : <DsStatusBadge label="手动" variant="accent"/>
            )
        },
        {
            title: 'Cron 表达式', dataIndex: 'cronExpression', width: 160,
            render: (v?: string) => v
                ? <span className="font-mono text-ds-caption text-ds-text-secondary">{v}</span>
                : <span className="text-ds-text-muted">—</span>
        },
        {
            title: '调度状态', width: 110,
            render: (_, r: Dag) => {
                if (r.triggerType !== 'CRON') {
                    return <span className="text-ds-text-muted">—</span>;
                }
                return r.scheduleEnabled
                    ? <DsStatusBadge label="已启用" variant="success"/>
                    : <DsStatusBadge label="已停用" variant="disabled"/>;
            }
        },
        {
            title: '创建人',
            width: 120,
            dataIndex: 'createdByName',
            render: (v?: string) => (
                <Tooltip title={v || '无'}>
                    <span className="text-ds-text-muted whitespace-nowrap">{v || '—'}</span>
                </Tooltip>
            )
        },
        {
            title: '创建时间',
            width: 170,
            dataIndex: 'createdAt',
            render: (v?: string) => (
                <Tooltip title={v || '无'}>
                    <span className="text-ds-text-muted whitespace-nowrap">{formatDateTime(v)}</span>
                </Tooltip>
            )
        },
        {
            title: '修改人',
            width: 120,
            dataIndex: 'updatedByName',
            render: (v?: string) => (
                <Tooltip title={v || '无'}>
                    <span className="text-ds-text-muted whitespace-nowrap">{v || '—'}</span>
                </Tooltip>
            )
        },
        {
            title: '修改时间',
            width: 170,
            dataIndex: 'updatedAt',
            render: (v?: string) => (
                <Tooltip title={v || '无'}>
                    <span className="text-ds-text-muted whitespace-nowrap">{formatDateTime(v)}</span>
                </Tooltip>
            )
        },
        {
            title: '最近执行状态', width: 120,
            render: (_, r: Dag) => {
                const status = r.latestExecution?.status;
                const label = status === 'SUCCESS' ? '成功'
                    : status === 'FAILED' ? '失败'
                        : status === 'RUNNING' ? '运行中'
                            : '未执行';
                return <DsStatusBadge label={label} variant={executionStatusVariant(status)}/>;
            }
        },
        {
            title: '最近执行', width: 170,
            render: (_, r: Dag) => {
                const t = r.latestExecution?.startTime;
                return (
                    <Tooltip title={t || '无'}>
                        <span className="text-ds-text-muted whitespace-nowrap">{formatDateTime(t)}</span>
                    </Tooltip>
                );
            }
        },
        {
            title: '操作', width: 220, align: 'center', fixed: 'right' as const,
            render: (_, r: Dag) => (
                <div className="flex items-center justify-center gap-1 whitespace-nowrap">
                    <Tooltip title="详情">
                        <DsIconButton
                            tone="accent"
                            aria-label="详情"
                            onClick={() => navigate(`/engineering/dags/${r.id}/edit`)}
                        >
                            <HiOutlineEye size={14}/>
                        </DsIconButton>
                    </Tooltip>
                    {canEdit && r.triggerType === 'CRON' && (
                        <Tooltip title={r.scheduleEnabled ? '停用调度' : '启用调度'}>
                            <DsIconButton
                                tone="success"
                                active={!!r.scheduleEnabled}
                                onClick={() => handleToggleSchedule(r)}
                                disabled={schedulingId === r.id}
                                aria-label={r.scheduleEnabled ? '停用调度' : '启用调度'}
                            >
                                <HiOutlineCalendar size={14}/>
                            </DsIconButton>
                        </Tooltip>
                    )}
                    {canEdit && (
                        <Tooltip title="编辑">
                            <DsIconButton onClick={() => navigate(`/engineering/dags/${r.id}/edit`)}>
                                <HiOutlinePencilSquare size={14}/>
                            </DsIconButton>
                        </Tooltip>
                    )}
                    <Tooltip title={canEdit ? '执行' : '只读模式'}>
                        <DsIconButton
                            tone="accent"
                            disabled={!canEdit}
                            onClick={() => handleTrigger(r.id!)}
                        >
                            <HiOutlinePlayCircle size={14}/>
                        </DsIconButton>
                    </Tooltip>
                    <Tooltip title="历史">
                        <DsIconButton
                            onClick={() => navigate(`/engineering/dag-executions?dagId=${r.id}&dagName=${encodeURIComponent(r.name || '')}`)}
                        >
                            <HiOutlineClock size={14}/>
                        </DsIconButton>
                    </Tooltip>
                    {canEdit && (
                        <Tooltip title="删除">
                            <DsIconButton tone="danger" onClick={() => setDeleteTarget(r)}>
                                <HiOutlineTrash size={14}/>
                            </DsIconButton>
                        </Tooltip>
                    )}
                </div>
            )
        }
    ], [canEdit, navigate, handleTrigger, handleToggleSchedule, schedulingId]);

    return (
        <div className="flex flex-col">
            {/* 面包屑 */}
            <div className="text-ds-small text-ds-text-muted mb-ds-3 flex-shrink-0">
                <Link to="/engineering/dags" className="text-ds-accent hover:underline">数据开发</Link>
                <span className="mx-2">/</span>
                <span className="text-ds-text-secondary">{project?.name ?? '加载中...'}</span>
            </div>

            {/* 页头：项目名 + 刷新 + 新建 DAG */}
            <div className="flex items-center justify-between mb-ds-5 flex-shrink-0">
                <div>
                    <h1 className="text-ds-display text-ds-text-primary">{project?.name ?? '加载中...'}</h1>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1">管理项目下的 DAG 编排</p>
                </div>
                <div className="flex items-center gap-ds-2">
                    <Tooltip title={canEdit ? '' : '只读模式：您没有编辑权限'}>
                        <DsButton
                            disabled={!canEdit}
                            onClick={() => navigate(`/engineering/dags/new?projectId=${projectId}`)}>
                            <HiOutlinePlus size={16}/>
                            新建 DAG
                        </DsButton>
                    </Tooltip>
                </div>
            </div>

            {/* 工具栏：独立卡片（与表格分离，对齐原型 .toolbar） */}
            <div
                className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle p-ds-3 mb-ds-4 flex-shrink-0">
                <DsToolbar
                    extra={
                        <>
                            <DsButton onClick={handleQuery}>
                                查询
                            </DsButton>
                            <DsButton variant="secondary" onClick={handleReset}>
                                重置
                            </DsButton>
                        </>
                    }
                >
                    <SearchInput
                        value={searchName}
                        onChange={(e) => setSearchName(e.target.value)}
                        onEnter={handleQuery}
                        placeholder="搜索 DAG 名称..."
                    />
                    <DsFilterSelect
                        value={statusFilter}
                        onChange={(v) => setStatusFilter(v as DsStatusVariant | '')}
                        options={STATUS_OPTIONS}
                        aria-label="按状态筛选"
                    />
                </DsToolbar>
            </div>

            {/* 表格卡片 + 底部分页器 */}
            <div className="flex flex-col">
                <div
                    className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden flex flex-col mb-ds-8">
                    <div className="overflow-x-auto">
                        <Table
                            dataSource={paged}
                            rowKey="id"
                            loading={loading}
                            pagination={false}
                            scroll={{x: 1740}}
                            className="prototype-table prototype-table-flush"
                            columns={columns}
                            locale={{emptyText: <DsTableEmpty description={loading ? '加载中...' : '暂无 DAG'}/>}}
                        />
                    </div>
                    <Pagination
                        page={page}
                        pageSize={pageSize}
                        total={filtered.length}
                        onChange={(p, ps) => {
                            setPage(p);
                            setPageSize(ps);
                        }}
                    />
                </div>
            </div>

            {/* 删除确认弹框：对齐原型 md-dag-del */}
            <Modal
                title="删除确认"
                open={deleteTarget != null}
                onCancel={() => setDeleteTarget(null)}
                onOk={handleDelete}
                okText="删除"
                cancelText="取消"
                okButtonProps={{danger: true, loading: deleting}}
                width={440}
                centered
                wrapClassName="prototype-modal"
            >
                <div className="text-ds-body mb-ds-4">
                    确定删除 DAG「<strong>{deleteTarget?.name}</strong>」吗？
                </div>
                <div
                    className="bg-ds-bg-root rounded-ds-sm px-ds-4 py-[14px] text-ds-small text-ds-text-secondary leading-[1.7] mb-ds-5">
                    <div>• 该 DAG 的所有节点和连线将被删除</div>
                    <div>• 该 DAG 的执行历史将被删除</div>
                    <div>• 被引用的同步任务不会删除，仅解除引用关系</div>
                    <div className="mt-[10px]">删除后不可恢复。</div>
                </div>
            </Modal>
        </div>
    );
}
