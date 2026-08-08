import {useCallback, useEffect, useMemo, useState} from 'react';
import {Table, Tooltip} from 'antd';
import {notify} from '../../../utils/notify';
import type {ColumnsType} from 'antd/es/table';
import {useHasRole} from '../../../hooks/useHasRole';
import {GOVERNANCE_WRITE_ROLES} from '../../../constants/roles';
import {
    batchCreateQualityRules,
    createQualityTemplate,
    deleteQualityTemplate,
    queryQualityTemplates,
    toggleQualityTemplate,
    updateQualityTemplate,
} from '../../../api/quality';
import Pagination from '../../../components/Pagination';
import SearchInput from '../../../components/SearchInput';
import ConfirmDialog from '../../../components/ConfirmDialog';
import DsButton from '../../../components/DsButton';
import DsFilterSelect from '../../../components/DsFilterSelect';
import DsIconButton from '../../../components/DsIconButton';
import DsStatusBadge from '../../../components/DsStatusBadge';
import DsTableEmpty from '../../../components/DsTableEmpty';
import {
    HiOutlineCalendar,
    HiOutlineCodeBracketSquare,
    HiOutlineEye,
    HiOutlinePlus,
    HiOutlineRocketLaunch,
    HiOutlineTrash,
} from 'react-icons/hi2';
import type {QualityRuleTemplate, QualityRuleTemplateCreateRequest, QualityTemplateType,} from '../../../types/quality';
import {formatDateTime} from '../../../utils/format';
import {COL} from '../../../constants/table';
import QualityTemplateDrawer, {TYPE_OPTIONS} from './QualityTemplateDrawer';
import BatchApplyModal from '../data-quality/BatchApplyModal';

/** 模板类型中文名 */
const TYPE_LABEL: Record<QualityTemplateType, string> = {
    COMPLETENESS: '完整性检查',
    UNIQUENESS: '唯一性检查',
    RANGE: '值域范围检查',
    CUSTOM_SQL: '自定义 SQL',
    PYTHON: 'Python',
};

/** 内置模板创建人/修改人展示"系统"（内置数据 createdByName/updatedByName 为 null） */
function ownerName(name?: string): string {
    return name || '系统';
}

export default function QualityTemplatesPage() {
    const canWrite = useHasRole(...GOVERNANCE_WRITE_ROLES);

    const [items, setItems] = useState<QualityRuleTemplate[]>([]);
    const [total, setTotal] = useState(0);
    const [page, setPage] = useState(1);
    const [pageSize, setPageSize] = useState(10);
    const [keyword, setKeyword] = useState('');
    const [type, setType] = useState<QualityTemplateType | ''>('');
    const [builtin, setBuiltin] = useState<number | undefined>(undefined);
    const [enabled, setEnabled] = useState<number | undefined>(undefined);
    const [loading, setLoading] = useState(false);

    const [drawerOpen, setDrawerOpen] = useState(false);
    const [drawerMode, setDrawerMode] = useState<'create' | 'edit' | 'view'>('create');
    const [editItem, setEditItem] = useState<QualityRuleTemplate | null>(null);

    const [deleteTarget, setDeleteTarget] = useState<QualityRuleTemplate | null>(null);
    const [deleteOpen, setDeleteOpen] = useState(false);
    const [deleteLoading, setDeleteLoading] = useState(false);

    /** 批量应用弹窗（模板库页无任务上下文，jobId 留空由弹窗内选择） */
    const [batchOpen, setBatchOpen] = useState(false);
    const [batchTemplateId, setBatchTemplateId] = useState<string>('');

    const loadTemplates = useCallback(async () => {
        setLoading(true);
        try {
            const res = await queryQualityTemplates({
                page,
                pageSize,
                keyword: keyword || undefined,
                type: type || undefined,
                builtin,
                enabled,
            });
            setItems(res.data.records);
            setTotal(res.data.total);
        } finally {
            setLoading(false);
        }
    }, [page, pageSize, keyword, type, builtin, enabled]);

    useEffect(() => {
        loadTemplates();
    }, [loadTemplates]);

    const resetFilters = () => {
        setKeyword('');
        setType('');
        setBuiltin(undefined);
        setEnabled(undefined);
        setPage(1);
        loadTemplates();
    };

    const handleSubmit = async (form: QualityRuleTemplateCreateRequest) => {
        const res = editItem
            ? await updateQualityTemplate(editItem.id, form)
            : await createQualityTemplate(form);
        notify.success(editItem ? '模板更新成功' : '模板创建成功');
        loadTemplates();
        return res;
    };

    const handleToggle = useCallback(async (item: QualityRuleTemplate) => {
        const nextEnabled = item.enabled === 1 ? 0 : 1;
        const res = await toggleQualityTemplate(item.id, nextEnabled);
        notify.success(nextEnabled === 1 ? '已启用' : '已停用');
        loadTemplates();
        return res;
    }, [loadTemplates]);

    const handleDelete = async () => {
        if (!deleteTarget) return;
        setDeleteLoading(true);
        try {
            await deleteQualityTemplate(deleteTarget.id);
            notify.success('删除成功');
            loadTemplates();
            setDeleteOpen(false);
            setDeleteTarget(null);
        } finally {
            setDeleteLoading(false);
        }
    };

    const openCreate = () => {
        setEditItem(null);
        setDrawerMode('create');
        setDrawerOpen(true);
    };

    const openEdit = useCallback((item: QualityRuleTemplate) => {
        setEditItem(item);
        setDrawerMode('edit');
        setDrawerOpen(true);
    }, []);

    const openView = useCallback((item: QualityRuleTemplate) => {
        setEditItem(item);
        setDrawerMode('view');
        setDrawerOpen(true);
    }, []);

    const handleBatchApply = useCallback((item: QualityRuleTemplate) => {
        setBatchTemplateId(String(item.id));
        setBatchOpen(true);
    }, []);

    const handleBatchSubmit = async (payload: Parameters<typeof batchCreateQualityRules>[0]) => {
        await batchCreateQualityRules(payload);
        notify.success('规则批量应用成功');
    };

    const columns = useMemo<ColumnsType<QualityRuleTemplate>>(() => [
        {
            title: '模板名称',
            dataIndex: 'name',
            width: COL.NAME,
            ellipsis: true,
            render: (v: string) => (
                <span title={v} className="text-ds-small text-ds-text-primary font-medium">{v}</span>
            ),
        },
        {
            title: '类型',
            dataIndex: 'type',
            width: COL.STATUS,
            render: (v: QualityTemplateType) => (
                <span
                    className="inline-flex items-center px-2.5 py-1 bg-ds-bg-hover text-ds-text-secondary border border-ds-border-subtle rounded-full text-ds-badge whitespace-nowrap">
                    {TYPE_LABEL[v] || v}
                </span>
            ),
        },
        {
            title: '来源',
            dataIndex: 'builtin',
            width: COL.STATUS,
            render: (builtin: number) => (
                builtin === 1 ? (
                    <DsStatusBadge label="内置" variant="accent"/>
                ) : (
                    <DsStatusBadge label="自定义" variant="disabled"/>
                )
            ),
        },
        {
            title: '结果指标',
            dataIndex: 'resultMetric',
            width: 140,
            ellipsis: true,
            render: (v?: string) => (
                <span title={v || '—'} className="text-ds-small text-ds-text-secondary font-mono">{v || '—'}</span>
            ),
        },
        {
            title: 'SQL 模板',
            dataIndex: 'sqlTemplate',
            width: 240,
            ellipsis: true,
            render: (v?: string) => (
                <span title={v || '—'} className="text-ds-small text-ds-text-muted font-mono">{v || '—'}</span>
            ),
        },
        {
            title: '状态',
            dataIndex: 'enabled',
            width: COL.STATUS,
            render: (enabled: number) => (
                enabled === 1 ? (
                    <DsStatusBadge label="启用" variant="success"/>
                ) : (
                    <DsStatusBadge label="停用" variant="pending"/>
                )
            ),
        },
        {
            title: '创建人',
            dataIndex: 'createdByName',
            width: COL.USERNAME,
            ellipsis: true,
            render: (v?: string) => (
                <span title={ownerName(v)} className="text-ds-small text-ds-text-secondary">{ownerName(v)}</span>
            ),
        },
        {
            title: '创建时间',
            dataIndex: 'createdAt',
            width: COL.DATETIME,
            render: (v?: string) => (
                <span
                    className="text-ds-small text-ds-text-secondary whitespace-nowrap">{v ? formatDateTime(v) : '—'}</span>
            ),
        },
        {
            title: '修改人',
            dataIndex: 'updatedByName',
            width: COL.USERNAME,
            ellipsis: true,
            render: (v?: string) => (
                <span title={ownerName(v)} className="text-ds-small text-ds-text-secondary">{ownerName(v)}</span>
            ),
        },
        {
            title: '修改时间',
            dataIndex: 'updatedAt',
            width: COL.DATETIME,
            render: (v?: string) => (
                <span
                    className="text-ds-small text-ds-text-secondary whitespace-nowrap">{v ? formatDateTime(v) : '—'}</span>
            ),
        },
        {
            title: '操作',
            align: 'center',
            fixed: 'right' as const,
            width: COL.OPERATION_5,
            render: (_, item) => (
                <div className="flex items-center justify-center gap-1 whitespace-nowrap">
                    <Tooltip title="详情">
                        <DsIconButton tone="accent" onClick={() => openView(item)} aria-label="详情">
                            <HiOutlineEye size={14}/>
                        </DsIconButton>
                    </Tooltip>
                    <Tooltip title="批量应用">
                        <DsIconButton tone="accent" onClick={() => handleBatchApply(item)} aria-label="批量应用">
                            <HiOutlineRocketLaunch size={14}/>
                        </DsIconButton>
                    </Tooltip>
                    {canWrite && (
                        <>
                            <Tooltip title={item.enabled === 1 ? '停用' : '启用'}>
                                <DsIconButton
                                    tone="success"
                                    active={item.enabled === 1}
                                    onClick={() => handleToggle(item)}
                                    aria-label={item.enabled === 1 ? '停用' : '启用'}
                                >
                                    <HiOutlineCalendar size={14}/>
                                </DsIconButton>
                            </Tooltip>
                            <Tooltip title="编辑">
                                <DsIconButton tone="accent" onClick={() => openEdit(item)} aria-label="编辑">
                                    <HiOutlineCodeBracketSquare size={14}/>
                                </DsIconButton>
                            </Tooltip>
                            {item.builtin !== 1 && (
                                <Tooltip title="删除">
                                    <DsIconButton
                                        tone="danger"
                                        onClick={() => {
                                            setDeleteTarget(item);
                                            setDeleteOpen(true);
                                        }}
                                        aria-label="删除"
                                    >
                                        <HiOutlineTrash size={14}/>
                                    </DsIconButton>
                                </Tooltip>
                            )}
                        </>
                    )}
                </div>
            ),
        },
    ], [canWrite, handleToggle, openEdit, openView, handleBatchApply]);

    return (
        <div className="flex flex-col">
            <div className="flex items-center justify-between mb-ds-5 flex-shrink-0">
                <div>
                    <h1 className="text-ds-display text-ds-text-primary">规则模板库</h1>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1">预置校验逻辑模板，任务内选择模板 +
                        多表批量生成质量规则</p>
                </div>
                {canWrite && (
                    <DsButton onClick={openCreate}>
                        <HiOutlinePlus size={16}/>
                        新增自定义模板
                    </DsButton>
                )}
            </div>

            <div
                className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden flex flex-col">
                <div
                    className="p-ds-3 border-b border-ds-border-subtle flex items-center gap-ds-3 flex-wrap flex-shrink-0">
                    <SearchInput
                        value={keyword}
                        onChange={(e) => setKeyword(e.target.value)}
                        placeholder="搜索模板名称..."
                    />
                    <DsFilterSelect
                        value={type}
                        onChange={(v) => setType(v as QualityTemplateType | '')}
                        options={[{value: '', label: '全部类型'}, ...TYPE_OPTIONS]}
                        aria-label="按类型筛选"
                    />
                    <DsFilterSelect
                        value={builtin != null ? String(builtin) : ''}
                        onChange={(v) => setBuiltin(v === '' ? undefined : Number(v))}
                        options={[{value: '', label: '全部来源'}, {value: '1', label: '内置'}, {value: '0', label: '自定义'}]}
                        aria-label="按来源筛选"
                    />
                    <DsFilterSelect
                        value={enabled != null ? String(enabled) : ''}
                        onChange={(v) => setEnabled(v === '' ? undefined : Number(v))}
                        options={[{value: '', label: '全部状态'}, {value: '1', label: '启用'}, {value: '0', label: '停用'}]}
                        aria-label="按状态筛选"
                    />
                    <div className="ml-auto flex items-center gap-ds-2">
                        <DsButton
                            onClick={() => {
                                setPage(1);
                                loadTemplates();
                            }}
                            disabled={loading}
                        >
                            {loading ? '查询中...' : '查询'}
                        </DsButton>
                        <DsButton variant="secondary" onClick={resetFilters}>
                            重置
                        </DsButton>
                    </div>
                </div>

                <div className="overflow-x-auto">
                    <Table<QualityRuleTemplate>
                        dataSource={items}
                        rowKey="id"
                        loading={loading}
                        pagination={false}
                        scroll={{x: 1660}}
                        columns={columns}
                        className="prototype-table prototype-table-flush"
                        locale={{
                            emptyText: (
                                <DsTableEmpty
                                    description="暂无规则模板，创建第一条自定义模板。"
                                    action={canWrite && (
                                        <DsButton onClick={openCreate}>
                                            <HiOutlinePlus size={16}/>
                                            新增自定义模板
                                        </DsButton>
                                    )}
                                />
                            ),
                        }}
                    />
                </div>

                <Pagination
                    page={page}
                    pageSize={pageSize}
                    total={total}
                    onChange={(p, s) => {
                        setPage(p);
                        setPageSize(s);
                    }}
                />
            </div>

            <QualityTemplateDrawer
                open={drawerOpen}
                mode={drawerMode}
                editItem={editItem}
                onClose={() => {
                    setDrawerOpen(false);
                    setEditItem(null);
                }}
                onSubmit={handleSubmit}
            />

            <BatchApplyModal
                open={batchOpen}
                jobId=""
                initialTemplateId={batchTemplateId}
                onClose={() => setBatchOpen(false)}
                onSubmit={handleBatchSubmit}
            />

            <ConfirmDialog
                open={deleteOpen}
                title="删除确认"
                message={
                    <p className="text-ds-body text-ds-text-secondary">
                        确定删除自定义模板 <strong>"{deleteTarget?.name}"</strong> 吗？删除后不可恢复。
                    </p>
                }
                confirmLabel="确认删除"
                danger
                loading={deleteLoading}
                onConfirm={handleDelete}
                onCancel={() => {
                    if (deleteLoading) return;
                    setDeleteOpen(false);
                    setDeleteTarget(null);
                }}
            />
        </div>
    );
}
