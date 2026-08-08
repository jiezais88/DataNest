// Sprint 7 F2：任务模板库页（DD-09）
// 布局对齐原型 task-template 视图：segmented 类型分组 + 内置/自定义徽章 + 一键创建。
// 仅超管/工程师可见（侧边栏 ENGINEERING_WRITE_ROLES + 后端全端点鉴权）。
// 单栏列表页按 §8 约定整页滚动；列表走 POST /page 分页，segmented 切换即服务端过滤。
import {useMemo, useState} from 'react';
import {Table, Tooltip} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import {
    HiOutlineClipboardDocumentList,
    HiOutlineDocumentDuplicate,
    HiOutlineInformationCircle,
    HiOutlinePencilSquare,
    HiOutlinePlay,
    HiOutlineTrash,
} from 'react-icons/hi2';
import {deleteTaskTemplate, queryTaskTemplates} from '../../../api/taskTemplate';
import ConfirmDialog from '../../../components/ConfirmDialog';
import DsButton from '../../../components/DsButton';
import DsIconButton from '../../../components/DsIconButton';
import DsStatusBadge from '../../../components/DsStatusBadge';
import DsTableEmpty from '../../../components/DsTableEmpty';
import Pagination from '../../../components/Pagination';
import {COL} from '../../../constants/table';
import usePagedList from '../../../hooks/usePagedList';
import {formatDateTime} from '../../../utils/format';
import {notify} from '../../../utils/notify';
import type {TaskTemplate, TaskTemplateType} from '../../../types/taskTemplate';
import CreateTaskModal, {parseTemplatePlaceholders} from './CreateTaskModal';
import TemplateFormDrawer from './TemplateFormDrawer';
import type {TemplateFormMode} from './TemplateFormDrawer';

/** 类型 mono 徽章（对齐原型 mono-badge：accent 浅底小圆角等宽字体） */
function TypeMonoBadge({type}: { type: TaskTemplateType }) {
    return (
        <span className="inline-flex items-center px-1.5 py-0.5 rounded-ds-xs bg-ds-accent-light text-ds-accent font-mono text-[11px] font-semibold">
            {type}
        </span>
    );
}

export default function TaskTemplatesPage() {
    const [typeFilter, setTypeFilter] = useState<'' | TaskTemplateType>('');

    const [createTarget, setCreateTarget] = useState<TaskTemplate | null>(null);
    const [formOpen, setFormOpen] = useState(false);
    const [formMode, setFormMode] = useState<TemplateFormMode>('create');
    const [formTemplate, setFormTemplate] = useState<TaskTemplate | null>(null);
    const [deleting, setDeleting] = useState<TaskTemplate | null>(null);
    const [deleteLoading, setDeleteLoading] = useState(false);

    const {
        list: templates,
        total,
        page,
        pageSize,
        loading,
        applyQuery,
        reload,
        setPage,
        setPageSize,
    } = usePagedList({
        fetcher: (q) => queryTaskTemplates(q).then(r => ({list: r?.records ?? [], total: Number(r?.total ?? 0)})),
        initialQuery: {} as { type?: string },
    });

    /** segmented 切换 → 服务端过滤 */
    const handleTypeFilter = (type: '' | TaskTemplateType) => {
        setTypeFilter(type);
        applyQuery({type: type || undefined});
    };

    const openForm = (mode: TemplateFormMode, template?: TaskTemplate) => {
        setFormMode(mode);
        setFormTemplate(template ?? null);
        setFormOpen(true);
    };

    const handleDelete = async () => {
        if (!deleting) return;
        setDeleteLoading(true);
        try {
            await deleteTaskTemplate(deleting.id);
            notify.success(`已删除模板「${deleting.name}」`);
            reload();
        } catch {
            // 7301/7304 由拦截器统一提示
        } finally {
            setDeleteLoading(false);
            setDeleting(null);
        }
    };

    const columns = useMemo<ColumnsType<TaskTemplate>>(() => [
        {
            title: '模板名称',
            dataIndex: 'name',
            width: COL.NAME_COMPACT,
            ellipsis: true,
            render: (v?: string) => (
                <span className="text-ds-small text-ds-text-primary font-medium" title={v}>{v}</span>
            ),
        },
        {
            title: '类型',
            dataIndex: 'type',
            width: 90,
            render: (v: TaskTemplateType) => <TypeMonoBadge type={v}/>,
        },
        {
            title: '来源',
            dataIndex: 'category',
            width: 80,
            render: (v?: string) => (
                v === 'BUILTIN'
                    ? <DsStatusBadge variant="accent" label="内置"/>
                    : <DsStatusBadge variant="disabled" label="自定义"/>
            ),
        },
        {
            title: '说明',
            dataIndex: 'description',
            ellipsis: true,
            render: (v?: string) => (
                <span className="text-ds-small text-ds-text-secondary" title={v}>{v || '—'}</span>
            ),
        },
        {
            title: '占位参数',
            key: 'placeholders',
            width: 220,
            ellipsis: true,
            render: (_, r) => {
                const keys = parseTemplatePlaceholders(r.configTemplate).map(p => `{${p.key}}`);
                return keys.length > 0 ? (
                    <span className="font-mono text-ds-tiny text-ds-text-muted" title={keys.join(' ')}>
                        {keys.join(' ')}
                    </span>
                ) : (
                    <span className="text-ds-small text-ds-text-muted">—</span>
                );
            },
        },
        {
            title: '状态',
            dataIndex: 'enabled',
            width: 90,
            render: (v?: number) => (
                v === 1
                    ? <DsStatusBadge variant="success" label="启用"/>
                    : <DsStatusBadge variant="disabled" label="停用"/>
            ),
        },
        {
            title: '创建人',
            dataIndex: 'createdByName',
            width: 100,
            render: (v?: string) => (
                <span className="text-ds-small text-ds-text-secondary">{v || '系统'}</span>
            ),
        },
        {
            title: '创建时间',
            dataIndex: 'createdAt',
            width: COL.DATETIME,
            render: (v?: string) => (
                <span className="text-ds-small text-ds-text-secondary whitespace-nowrap">{formatDateTime(v)}</span>
            ),
        },
        {
            title: '操作',
            key: 'action',
            width: COL.OPERATION_4,
            fixed: 'right' as const,
            render: (_, r) => {
                const builtin = r.category === 'BUILTIN';
                return (
                    <div className="flex items-center gap-ds-1">
                        <Tooltip title={r.enabled === 1 ? '一键创建任务' : '模板已停用，无法创建'}>
                            <DsIconButton tone="success" aria-label="一键创建"
                                          disabled={r.enabled !== 1}
                                          onClick={() => setCreateTarget(r)}>
                                <HiOutlinePlay size={14}/>
                            </DsIconButton>
                        </Tooltip>
                        {builtin ? (
                            <Tooltip title="复制为自定义模板（内置模板禁改禁删）">
                                <DsIconButton tone="accent" aria-label="复制为自定义"
                                              onClick={() => openForm('copy', r)}>
                                    <HiOutlineDocumentDuplicate size={14}/>
                                </DsIconButton>
                            </Tooltip>
                        ) : (
                            <>
                                <Tooltip title="编辑">
                                    <DsIconButton tone="accent" aria-label="编辑"
                                                  onClick={() => openForm('edit', r)}>
                                        <HiOutlinePencilSquare size={14}/>
                                    </DsIconButton>
                                </Tooltip>
                                <Tooltip title="删除">
                                    <DsIconButton tone="danger" aria-label="删除"
                                                  onClick={() => setDeleting(r)}>
                                        <HiOutlineTrash size={14}/>
                                    </DsIconButton>
                                </Tooltip>
                            </>
                        )}
                    </div>
                );
            },
        },
    ], []);

    const segments: { value: '' | TaskTemplateType; label: string }[] = [
        {value: '', label: '全部'},
        {value: 'SYNC', label: '同步任务'},
        {value: 'COLLECT', label: '采集任务'},
    ];

    return (
        <div className="flex flex-col">
            <div className="flex items-center justify-between mb-ds-5 flex-shrink-0">
                <div>
                    <h1 className="text-ds-display text-ds-text-primary flex items-center gap-ds-2">
                        <HiOutlineClipboardDocumentList size={24} className="text-ds-accent"/>
                        任务模板库
                    </h1>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1">
                        常用数据开发任务模板，从模板一键创建任务。区别于「质量规则模板库」——本库面向同步 / 采集任务。
                    </p>
                </div>
                <DsButton onClick={() => openForm('create')}>新增自定义模板</DsButton>
            </div>

            <div
                className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden flex flex-col">
                <div className="flex items-center p-ds-3 border-b border-ds-border-subtle flex-shrink-0">
                    {/* segmented 类型分组（原型 seg-item；切换即服务端过滤） */}
                    <div className="inline-flex items-center bg-ds-bg-root rounded-ds-sm p-0.5">
                        {segments.map(seg => (
                            <button
                                key={seg.value}
                                type="button"
                                onClick={() => handleTypeFilter(seg.value)}
                                className={`px-ds-3 py-1.5 text-ds-small rounded-ds-xs transition-colors ${
                                    typeFilter === seg.value
                                        ? 'bg-white text-ds-accent font-semibold shadow-ds-xs'
                                        : 'text-ds-text-secondary hover:text-ds-text-primary'
                                }`}
                            >
                                {seg.label}
                            </button>
                        ))}
                    </div>
                </div>

                <div className="overflow-x-auto">
                    <Table
                        rowKey={(r) => r.id}
                        columns={columns}
                        dataSource={templates}
                        loading={loading}
                        pagination={false}
                        scroll={{x: 1240}}
                        className="prototype-table prototype-table-flush"
                        locale={{
                            emptyText: (
                                <DsTableEmpty
                                    description="暂无任务模板"
                                    action={
                                        <DsButton onClick={() => openForm('create')}>新增自定义模板</DsButton>
                                    }
                                />
                            ),
                        }}
                    />
                </div>
                {total > 0 && (
                    <Pagination
                        page={page}
                        pageSize={pageSize}
                        total={total}
                        onChange={(p, s) => {
                            setPage(p);
                            if (s !== pageSize) setPageSize(s);
                        }}
                    />
                )}
            </div>

            {/* 说明条（原型 info notice 浅紫底） */}
            <div
                className="flex items-center gap-ds-2 mt-ds-4 px-ds-4 py-ds-3 bg-ds-accent-light rounded-ds-md text-ds-small text-ds-text-secondary">
                <HiOutlineInformationCircle size={16} className="text-ds-accent flex-shrink-0"/>
                模板被删除不影响已创建任务（快照式）；内置模板禁删，可复制为自定义后修改。
            </div>

            {/* 一键创建 */}
            <CreateTaskModal
                open={!!createTarget}
                template={createTarget}
                onClose={() => setCreateTarget(null)}
            />

            {/* 新增/编辑/复制（实体主表单 = 右侧 Drawer） */}
            <TemplateFormDrawer
                open={formOpen}
                mode={formMode}
                template={formTemplate}
                onClose={() => setFormOpen(false)}
                onSaved={reload}
            />

            {/* 删除确认 */}
            <ConfirmDialog
                open={!!deleting}
                title="删除模板"
                message={`确认删除模板「${deleting?.name}」？已创建的任务不受影响（快照式）。`}
                confirmLabel="删除"
                danger
                loading={deleteLoading}
                onConfirm={handleDelete}
                onCancel={() => setDeleting(null)}
            />
        </div>
    );
}
