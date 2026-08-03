// DAG 项目列表页（PRD §6.2）
// 进入项目 → 跳到 /engineering/dags/:projectId 看 DAG 列表
import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {Form, Input, Modal, Table, Tooltip} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import {
    HiOutlineArrowRightOnRectangle,
    HiOutlineEye,
    HiOutlinePencilSquare,
    HiOutlinePlus,
    HiOutlineTrash,
} from 'react-icons/hi2';
import {useNavigate, useSearchParams} from 'react-router-dom';
import {createDagProject, deleteDagProject, listDagProjects, listDags, updateDagProject} from './api';
import type {DagProject} from './types';
import usePagedList from '../../../hooks/usePagedList';
import {useCanEdit} from '../../../hooks/useCanEdit';
import SearchInput from '../../../components/SearchInput';
import Pagination from '../../../components/Pagination';
import DsButton from '../../../components/DsButton';
import DsIconButton from '../../../components/DsIconButton';
import DsToolbar from '../../../components/DsToolbar';
import DsTableEmpty from '../../../components/DsTableEmpty';
import {formatDateTime} from '../../../utils/format';
import {notify} from '../../../utils/notify';
import {COL} from '../../../constants/table';

export default function DagsPage() {
    const navigate = useNavigate();
    const canEdit = useCanEdit();
    // 草稿查询条件（搜索框输入中、未点查询的值）由页面持有；点「查询」才 applyQuery
    const [searchName, setSearchName] = useState('');
    const {
        list: projects, total, page, pageSize, loading, query,
        setPage, setPageSize, applyQuery, reload,
    } = usePagedList<{ name: string }, DagProject>({
        // 适配接口返回结构：records/total → {list, total}
        fetcher: useCallback(async ({name, page, pageSize}) => {
            const result = await listDagProjects({name: name || undefined, page, pageSize});
            return {list: result.records, total: result.total};
        }, []),
        initialQuery: {name: ''},
        defaultPageSize: 10,
    });
// L2：进页时从 URL 初始化筛选，进入子页/返回后筛选不丢
    const [searchParams, setSearchParams] = useSearchParams();
    const urlInitRef = useRef(false);
    useEffect(() => {
        if (urlInitRef.current) return;
        urlInitRef.current = true;
        const p = searchParams;
        const name = p.get('name') || '';
        const pageNum = Number(p.get('page')) || 1;
        const pageSizeNum = Number(p.get('pageSize')) || 10;
        setSearchName(name);
        if (pageSizeNum !== 10) setPageSize(pageSizeNum);
        applyQuery({name});
        if (pageNum > 1) setPage(pageNum);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    // L2：筛选/分页变化时同步到 URL
    useEffect(() => {
        const next = new URLSearchParams();
        if (query.name) next.set('name', query.name);
        next.set('page', String(page));
        if (pageSize !== 10) next.set('pageSize', String(pageSize));
        if (next.toString() === searchParams.toString()) return;
        setSearchParams(next, {replace: true});
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [query, page, pageSize]);
    const [projectModalOpen, setProjectModalOpen] = useState(false);
    const [projectModalMode, setProjectModalMode] = useState<'create' | 'edit' | 'detail'>('create');
    const [editingProject, setEditingProject] = useState<DagProject | null>(null);
    const [projectForm] = Form.useForm();
    // 删除确认弹框（对齐原型 md-project-del：列出项目下 DAG 名单）
    const [deleteTarget, setDeleteTarget] = useState<DagProject | null>(null);
    const [deleteDagNames, setDeleteDagNames] = useState<string[] | null>(null);
    const [deleting, setDeleting] = useState(false);

    const handleSaveProject = async () => {
        const values = await projectForm.validateFields();
        try {
            if (editingProject?.id != null) {
                await updateDagProject(editingProject.id, values);
                notify.success('项目已更新');
            } else {
                await createDagProject(values);
                notify.success('项目已创建');
            }
            setProjectModalOpen(false);
            setEditingProject(null);
            projectForm.resetFields();
            reload();
        } catch {
            // 错误提示由 request 拦截器统一弹出
        }
    };

    const openDeleteModal = async (r: DagProject) => {
        setDeleteTarget(r);
        setDeleteDagNames(null);
        try {
            const dags = await listDags(r.id);
            setDeleteDagNames(dags.map(d => d.name));
        } catch {
            // 名单拉取失败不阻塞删除，弹框里只显示数量
            setDeleteDagNames([]);
        }
    };

    const handleDeleteProject = async () => {
        if (!deleteTarget) return;
        setDeleting(true);
        try {
            await deleteDagProject(deleteTarget.id!);
            notify.success('项目已删除');
            setDeleteTarget(null);
            reload();
        } catch {
            // 错误提示由 request 拦截器统一弹出
        } finally {
            setDeleting(false);
        }
    };

    const handleQuery = () => {
        applyQuery({name: searchName});
    };
    const handleReset = () => {
        setSearchName('');
        applyQuery({name: ''});
    };

    const columns = useMemo<ColumnsType<DagProject>>(() => [
        {
            title: '项目名称',
            dataIndex: 'name',
            width: COL.NAME,
            ellipsis: true,
            render: (v: string) => <span className="text-ds-small font-medium text-ds-text-primary">{v}</span>
        },
        {
            title: '项目描述',
            dataIndex: 'description',
            width: 320,
            ellipsis: true,
            render: (v?: string) => v
                ? <span className="text-ds-small text-ds-text-secondary">{v}</span>
                : <span className="text-ds-small text-ds-text-muted">—</span>
        },
        {
            title: 'DAG 数',
            width: COL.COUNT_NORMAL,
            align: 'center',
            render: (_, r: DagProject) => (
                <span className="text-ds-small text-ds-text-secondary">{r.dagCount ?? 0}</span>
            )
        },
        {
            title: '创建时间',
            width: COL.DATETIME,
            dataIndex: 'createdAt',
            render: (v?: string) => (
                <Tooltip title={v || '无'}>
                    <span className="text-ds-small text-ds-text-secondary whitespace-nowrap">{formatDateTime(v)}</span>
                </Tooltip>
            )
        },
        {
            title: '创建人',
            width: COL.USERNAME,
            dataIndex: 'createdByName',
            render: (v?: string) => (
                <Tooltip title={v || '无'}>
                    {v
                        ? <span className="text-ds-small text-ds-text-secondary whitespace-nowrap">{v}</span>
                        : <span className="text-ds-small text-ds-text-muted whitespace-nowrap">—</span>}
                </Tooltip>
            )
        },
        {
            title: '修改时间',
            width: COL.DATETIME,
            dataIndex: 'updatedAt',
            render: (v?: string) => (
                <Tooltip title={v || '无'}>
                    <span className="text-ds-small text-ds-text-secondary whitespace-nowrap">{formatDateTime(v)}</span>
                </Tooltip>
            )
        },
        {
            title: '修改人',
            width: COL.USERNAME,
            dataIndex: 'updatedByName',
            render: (v?: string) => (
                <Tooltip title={v || '无'}>
                    {v
                        ? <span className="text-ds-small text-ds-text-secondary whitespace-nowrap">{v}</span>
                        : <span className="text-ds-small text-ds-text-muted whitespace-nowrap">—</span>}
                </Tooltip>
            )
        },
        {
            title: '操作',
            width: COL.OPERATION_4,
            align: 'center',
            fixed: 'right' as const,
            render: (_, r: DagProject) => (
                <div className="flex items-center justify-center gap-1 whitespace-nowrap">
                    <Tooltip title="进入">
                        <DsIconButton
                            tone="accent"
                            aria-label="进入"
                            onClick={() => navigate(`/engineering/dags/${r.id}`)}>
                            <HiOutlineArrowRightOnRectangle size={14}/>
                        </DsIconButton>
                    </Tooltip>
                    <Tooltip title="详情">
                        <DsIconButton
                            tone="accent"
                            aria-label="详情"
                            onClick={() => {
                                setEditingProject(r);
                                setProjectModalMode('detail');
                                projectForm.setFieldsValue(r);
                                setProjectModalOpen(true);
                            }}>
                            <HiOutlineEye size={14}/>
                        </DsIconButton>
                    </Tooltip>
                    {canEdit && (
                        <Tooltip title="编辑">
                            <DsIconButton
                                tone="accent"
                                aria-label="编辑"
                                onClick={() => {
                                    setEditingProject(r);
                                    setProjectModalMode('edit');
                                    projectForm.setFieldsValue(r);
                                    setProjectModalOpen(true);
                                }}>
                                <HiOutlinePencilSquare size={14}/>
                            </DsIconButton>
                        </Tooltip>
                    )}
                    {canEdit && (
                        <Tooltip title="删除">
                            <DsIconButton
                                tone="danger"
                                aria-label="删除"
                                onClick={() => openDeleteModal(r)}>
                                <HiOutlineTrash size={14}/>
                            </DsIconButton>
                        </Tooltip>
                    )}
                </div>
            )
        }
    ], [canEdit, navigate, projectForm]);

    return (
        <div className="flex flex-col">
            {/* 页头：标题 + 新建项目按钮 */}
            <div className="flex items-center justify-between mb-ds-5 flex-shrink-0">
                <div>
                    <h1 className="text-ds-display text-ds-text-primary">项目管理</h1>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1">按项目分组管理 DAG 编排与 SQL 任务</p>
                </div>
                <Tooltip title={canEdit ? '' : '只读模式：您没有编辑权限'}>
                    <DsButton
                        variant="primary"
                        data-testid="project-create"
                        disabled={!canEdit}
                        onClick={() => {
                            setEditingProject(null);
                            setProjectModalMode('create');
                            projectForm.resetFields();
                            setProjectModalOpen(true);
                        }}>
                        <HiOutlinePlus size={16}/>
                        新建项目
                    </DsButton>
                </Tooltip>
            </div>

            {/* 工具栏：独立卡片（与表格分离，对齐原型 .toolbar） */}
            <div
                className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle p-ds-3 mb-ds-4 flex-shrink-0">
                <DsToolbar extra={
                    <>
                        <DsButton variant="primary" onClick={handleQuery}>
                            查询
                        </DsButton>
                        <DsButton variant="secondary" onClick={handleReset}>
                            重置
                        </DsButton>
                    </>
                }>
                    <SearchInput
                        value={searchName}
                        onChange={(e) => setSearchName(e.target.value)}
                        onEnter={handleQuery}
                        placeholder="搜索项目名称..."
                    />
                </DsToolbar>
            </div>

            {/* 表格卡片 + 底部分页器 */}
            <div className="flex flex-col">
                <div
                    className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden flex flex-col">
                    <div className="overflow-x-auto">
                        <Table
                            dataSource={projects}
                            rowKey="id"
                            loading={loading}
                            pagination={false}
                            scroll={{x: 1340}}
                            className="prototype-table prototype-table-flush"
                            columns={columns}
                            locale={{emptyText: <DsTableEmpty description="暂无项目"/>}}
                        />
                    </div>
                    <Pagination
                        page={page}
                        pageSize={pageSize}
                        total={total}
                        onChange={(p, ps) => {
                            // 组件在改每页条数时已传 p=1，这里区分两种变更走对应入口
                            if (ps === pageSize) {
                                setPage(p);
                            } else {
                                setPageSize(ps);
                            }
                        }}
                    />
                </div>
            </div>

            {/* 新建/编辑项目弹框：对齐原型 md-project（.fg/.fl/.fi/.desc 表单样式） */}
            <Modal
                title={projectModalMode === 'detail' ? '详情' : editingProject ? '编辑项目' : '新建项目'}
                open={projectModalOpen}
                onCancel={() => {
                    setProjectModalOpen(false);
                    setEditingProject(null);
                    projectForm.resetFields();
                }}
                onOk={handleSaveProject}
                okText="保存"
                cancelText="取消"
                okButtonProps={projectModalMode === 'detail' ? {style: {display: 'none'}} : {'data-testid': 'project-save-btn'}}
                footer={projectModalMode === 'detail' ? (
                    <DsButton variant="secondary" onClick={() => setProjectModalOpen(false)}>关闭</DsButton>
                ) : undefined}
                width={480}
                centered
                wrapClassName="prototype-modal"
                destroyOnClose
            >
                <Form form={projectForm} layout="vertical" requiredMark={false} className="mt-ds-2">
                    <Form.Item
                        label={<span
                            className="block text-ds-caption font-bold text-ds-text-secondary uppercase tracking-[0.5px]">项目名称 <span
                            className="text-ds-danger">*</span></span>}
                        name="name"
                        rules={[
                            {required: true, message: '请输入项目名称'},
                            {pattern: /^[一-龥A-Za-z0-9_]{3,30}$/, message: '支持中文、字母、数字、下划线，3-30 位'},
                        ]}
                        extra={<span
                            className="text-ds-nano text-ds-text-muted">支持中文、字母、数字、下划线，3-30 位</span>}
                    >
                        <Input id="name" placeholder="输入项目名称，3-30 位"
                               disabled={projectModalMode !== 'create'}
                               className="px-[14px] py-[10px] bg-ds-bg-root border-ds-border-subtle rounded-ds-sm text-ds-small"/>
                    </Form.Item>
                    <Form.Item
                        label={<span
                            className="block text-ds-caption font-bold text-ds-text-secondary uppercase tracking-[0.5px]">项目描述</span>}
                        name="description"
                        rules={[{max: 200, message: '最多 200 字'}]}
                        extra={<span className="text-ds-nano text-ds-text-muted">最多 200 字</span>}
                    >
                        <Input placeholder="可选，最多 200 字"
                               data-testid="project-description"
                               disabled={projectModalMode === 'detail'}
                               className="px-[14px] py-[10px] bg-ds-bg-root border-ds-border-subtle rounded-ds-sm text-ds-small"/>
                    </Form.Item>
                </Form>
            </Modal>
            {/* 删除确认弹框：对齐原型 md-project-del */}
            <Modal
                title="删除确认"
                open={deleteTarget != null}
                onCancel={() => setDeleteTarget(null)}
                onOk={handleDeleteProject}
                okText="删除"
                cancelText="取消"
                okButtonProps={{danger: true, loading: deleting}}
                width={440}
                centered
                wrapClassName="prototype-modal"
            >
                <div className="text-ds-body mb-ds-4">
                    确定删除项目「<strong>{deleteTarget?.name}</strong>」吗？
                </div>
                <div
                    className="bg-ds-bg-root rounded-ds-sm px-ds-4 py-[14px] text-ds-small text-ds-text-secondary leading-[1.7] mb-ds-5">
                    <div className="font-semibold text-ds-text-primary mb-[6px]">
                        该项目包含 {deleteTarget?.dagCount ?? deleteDagNames?.length ?? 0} 个 DAG，将一并删除：
                    </div>
                    {deleteDagNames == null ? (
                        <div>加载 DAG 名单...</div>
                    ) : deleteDagNames.length === 0 ? (
                        <div>（项目下暂无 DAG）</div>
                    ) : (
                        deleteDagNames.map(n => <div key={n}>• {n}</div>)
                    )}
                    <div className="mt-[10px]">删除后不可恢复。</div>
                </div>
            </Modal>
        </div>
    );
}
