import {useCallback, useEffect, useMemo, useState} from 'react';
import {Table, Tooltip} from 'antd';
import {notify} from '../../../utils/notify';
import type {ColumnsType} from 'antd/es/table';
import {useHasRole} from '../../../hooks/useHasRole';
import {GOVERNANCE_WRITE_ROLES} from '../../../constants/roles';
import {
    createQualityTemplate,
    deleteQualityTemplate,
    listQualityTemplates,
    queryQualityTemplates,
    toggleQualityTemplate,
    updateQualityTemplate,
} from '../../../api/quality';
import Pagination from '../../../components/Pagination';
import SearchInput from '../../../components/SearchInput';
import ConfirmDialog from '../../../components/ConfirmDialog';
import DsButton from '../../../components/DsButton';
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

/** 模板类型中文名 */
const TYPE_LABEL: Record<QualityTemplateType, string> = {
    COMPLETENESS: '完整性检查',
    UNIQUENESS: '唯一性检查',
    RANGE: '值域范围检查',
    CUSTOM_SQL: '自定义 SQL',
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

    // 统计卡片（模板总数/内置/自定义），用全量列表前端统计
    const [allTemplates, setAllTemplates] = useState<QualityRuleTemplate[]>([]);

    const [drawerOpen, setDrawerOpen] = useState(false);
    const [drawerMode, setDrawerMode] = useState<'create' | 'edit' | 'view'>('create');
    const [editItem, setEditItem] = useState<QualityRuleTemplate | null>(null);

    const [deleteTarget, setDeleteTarget] = useState<QualityRuleTemplate | null>(null);
    const [deleteOpen, setDeleteOpen] = useState(false);
    const [deleteLoading, setDeleteLoading] = useState(false);

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

    const loadStats = useCallback(async () => {
        try {
            const res = await listQualityTemplates();
            setAllTemplates(res.data || []);
        } catch {
            setAllTemplates([]);
        }
    }, []);

    useEffect(() => {
        loadTemplates();
    }, [loadTemplates]);

    useEffect(() => {
        loadStats();
    }, [loadStats]);

    const resetFilters = () => {
        setKeyword('');
        setType('');
        setBuiltin(undefined);
        setEnabled(undefined);
        setPage(1);
        loadTemplates();
    };

    const stats = useMemo(() => {
        const totalCount = allTemplates.length;
        const builtinCount = allTemplates.filter((t) => t.builtin === 1).length;
        return {
            total: totalCount,
            builtin: builtinCount,
            custom: totalCount - builtinCount,
        };
    }, [allTemplates]);

    const handleSubmit = async (form: QualityRuleTemplateCreateRequest) => {
        const res = editItem
            ? await updateQualityTemplate(editItem.id, form)
            : await createQualityTemplate(form);
        notify.success(editItem ? '模板更新成功' : '模板创建成功');
        loadTemplates();
        loadStats();
        return res;
    };

    const handleToggle = useCallback(async (item: QualityRuleTemplate) => {
        const nextEnabled = item.enabled === 1 ? 0 : 1;
        const res = await toggleQualityTemplate(item.id, nextEnabled);
        notify.success(nextEnabled === 1 ? '已启用' : '已停用');
        loadTemplates();
        loadStats();
        return res;
    }, [loadTemplates, loadStats]);

    const handleDelete = async () => {
        if (!deleteTarget) return;
        setDeleteLoading(true);
        try {
            await deleteQualityTemplate(deleteTarget.id);
            notify.success('删除成功');
            loadTemplates();
            loadStats();
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
        notify.info(`「${item.name}」批量应用功能开发中，后端接口尚未就绪`);
    }, []);

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
                        <DsIconButton tone="accent" onClick={() => openView(item)}>
                            <HiOutlineEye size={14}/>
                        </DsIconButton>
                    </Tooltip>
                    <Tooltip title="批量应用">
                        <DsIconButton tone="accent" onClick={() => handleBatchApply(item)}>
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
                                <DsIconButton tone="accent" onClick={() => openEdit(item)}>
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

    const statCards = [
        {label: '模板总数', value: stats.total},
        {label: '内置模板', value: stats.builtin},
        {label: '自定义模板', value: stats.custom},
    ];

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

            {/* 统计卡片 */}
            <div className="grid grid-cols-3 gap-ds-3 mb-ds-4 flex-shrink-0">
                {statCards.map((c) => (
                    <div key={c.label}
                         className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle px-ds-4 py-ds-3">
                        <p className="text-ds-nano text-ds-text-muted">{c.label}</p>
                        <p className="text-ds-title text-ds-text-primary font-bold mt-ds-0.5 tabular-nums">{c.value}</p>
                    </div>
                ))}
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
                    <select
                        value={type}
                        onChange={(e) => setType(e.target.value as QualityTemplateType | '')}
                        className="px-ds-3 py-ds-2 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-small text-ds-text-primary focus:outline-none focus-visible:border-ds-accent"
                    >
                        <option value="">全部类型</option>
                        {TYPE_OPTIONS.map((o) => (
                            <option key={o.value} value={o.value}>{o.label}</option>
                        ))}
                    </select>
                    <select
                        value={builtin ?? ''}
                        onChange={(e) => setBuiltin(e.target.value === '' ? undefined : Number(e.target.value))}
                        className="px-ds-3 py-ds-2 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-small text-ds-text-primary focus:outline-none focus-visible:border-ds-accent"
                    >
                        <option value="">全部来源</option>
                        <option value="1">内置</option>
                        <option value="0">自定义</option>
                    </select>
                    <select
                        value={enabled ?? ''}
                        onChange={(e) => setEnabled(e.target.value === '' ? undefined : Number(e.target.value))}
                        className="px-ds-3 py-ds-2 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-small text-ds-text-primary focus:outline-none focus-visible:border-ds-accent"
                    >
                        <option value="">全部状态</option>
                        <option value="1">启用</option>
                        <option value="0">停用</option>
                    </select>
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
