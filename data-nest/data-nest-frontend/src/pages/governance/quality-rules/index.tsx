import {useCallback, useEffect, useMemo, useState} from 'react';
import {Table, Tooltip} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import {notify} from '@/utils/notify';
import {formatDateTime} from '@/utils/format';
import {useHasRole} from '@/hooks/useHasRole';
import {GOVERNANCE_WRITE_ROLES} from '@/constants/roles';
import {COL} from '@/constants/table';
import {
    batchCreateQualityRules,
    createQualityRule,
    deleteQualityRule,
    executeQualityRule,
    previewQualityRuleSql,
    queryQualityJobs,
    queryQualityRules,
    toggleQualityRule,
    updateQualityRule,
} from '@/api/quality';
import DsButton from '@/components/DsButton';
import DsIconButton from '@/components/DsIconButton';
import DsModal from '@/components/DsModal';
import DsStatusBadge from '@/components/DsStatusBadge';
import DsTableEmpty from '@/components/DsTableEmpty';
import DsToolbar from '@/components/DsToolbar';
import DsFilterSelect from '@/components/DsFilterSelect';
import ConfirmDialog from '@/components/ConfirmDialog';
import Pagination from '@/components/Pagination';
import SearchInput from '@/components/SearchInput';
import {
    HiOutlineClipboardDocumentCheck,
    HiOutlineEye,
    HiOutlinePencilSquare,
    HiOutlinePlay,
    HiOutlinePlus,
    HiOutlineScale,
    HiOutlineTrash,
} from 'react-icons/hi2';
import {
    QUALITY_TYPE_LABEL,
    QUALITY_TYPE_OPTIONS,
} from '@/types/quality';
import type {
    QualityRule,
    QualityRuleType,
} from '@/types/quality';
import QualityRuleDrawer from '@/pages/governance/data-quality/QualityRuleDrawer';
import BatchApplyModal from '@/pages/governance/data-quality/BatchApplyModal';

export default function QualityRulesPage() {
    const canWrite = useHasRole(...GOVERNANCE_WRITE_ROLES);

    // ============ 分页 + 筛选 ============
    const [items, setItems] = useState<QualityRule[]>([]);
    const [total, setTotal] = useState(0);
    const [page, setPage] = useState(1);
    const [pageSize, setPageSize] = useState(10);
    const [keyword, setKeyword] = useState('');
    const [type, setType] = useState<QualityRuleType | ''>('');
    const [enabled, setEnabled] = useState<string>('');
    const [jobId, setJobId] = useState<string>('');
    const [loading, setLoading] = useState(false);

    // ============ 所属任务下拉 ============
    const [jobOptions, setJobOptions] = useState<{ id: string; name: string }[]>([]);

    // ============ 操作状态 ============
    const [drawerOpen, setDrawerOpen] = useState(false);
    const [drawerMode, setDrawerMode] = useState<'create' | 'edit' | 'view'>('create');
    const [editItem, setEditItem] = useState<QualityRule | null>(null);
    const [batchOpen, setBatchOpen] = useState(false);
    const [deleteTarget, setDeleteTarget] = useState<QualityRule | null>(null);
    const [deleteOpen, setDeleteOpen] = useState(false);
    const [deleteLoading, setDeleteLoading] = useState(false);
    const [previewOpen, setPreviewOpen] = useState(false);
    const [previewSql, setPreviewSql] = useState('');
    const [previewLoading, setPreviewLoading] = useState(false);
    /** 执行按钮 loading（记录当前正在触发的规则 ID） */
    const [executingId, setExecutingId] = useState<string>('');

    const loadRules = useCallback(async () => {
        setLoading(true);
        try {
            const res = await queryQualityRules({
                page,
                pageSize,
                keyword: keyword || undefined,
                type: type || undefined,
                enabled: enabled === '' ? undefined : Number(enabled),
                jobId: jobId || undefined,
            });
            setItems(res.data.records);
            setTotal(res.data.total);
        } finally {
            setLoading(false);
        }
    }, [page, pageSize, keyword, type, enabled, jobId]);

    const loadJobOptions = useCallback(() => {
        queryQualityJobs({page: 1, pageSize: 1000})
            .then((res) => setJobOptions((res.data.records || []).map((j) => ({
                id: String(j.id),
                name: j.name,
            }))))
            .catch(() => setJobOptions([]));
    }, []);

    useEffect(() => {
        loadRules();
    }, [loadRules]);

    useEffect(() => {
        loadJobOptions();
    }, [loadJobOptions]);

    const resetFilters = () => {
        setKeyword('');
        setType('');
        setEnabled('');
        setJobId('');
        setPage(1);
    };

    // ============ 操作 ============
    const openCreate = () => {
        setEditItem(null);
        setDrawerMode('create');
        setDrawerOpen(true);
    };

    const openEdit = (item: QualityRule) => {
        setEditItem(item);
        setDrawerMode('edit');
        setDrawerOpen(true);
    };

    const openView = (item: QualityRule) => {
        setEditItem(item);
        setDrawerMode('view');
        setDrawerOpen(true);
    };

    const handleSubmit = async (payload: Parameters<typeof createQualityRule>[0]) => {
        if (editItem) {
            await updateQualityRule(editItem.id, payload);
            notify.success('质量规则更新成功');
        } else {
            await createQualityRule(payload);
            notify.success('质量规则创建成功');
        }
        loadRules();
    };

    const handleToggle = async (item: QualityRule) => {
        const nextEnabled = item.enabled === 1 ? 0 : 1;
        await toggleQualityRule(item.id, nextEnabled);
        notify.success(nextEnabled === 1 ? '已启用' : '已停用');
        loadRules();
    };

    const handleExecute = async (item: QualityRule) => {
        setExecutingId(item.id);
        try {
            await executeQualityRule(item.id);
            notify.success('已触发执行，请到「质量检查历史」查看结果');
        } finally {
            setExecutingId('');
        }
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

    const handleDelete = async () => {
        if (!deleteTarget) return;
        setDeleteLoading(true);
        try {
            await deleteQualityRule(deleteTarget.id);
            notify.success('删除成功');
            setDeleteOpen(false);
            setDeleteTarget(null);
            loadRules();
        } finally {
            setDeleteLoading(false);
        }
    };

    const handleBatchSubmit = async (payload: Parameters<typeof batchCreateQualityRules>[0]) => {
        await batchCreateQualityRules(payload);
        notify.success('规则批量应用成功');
        loadRules();
    };

    // ============ 列 ============
    const columns = useMemo<ColumnsType<QualityRule>>(() => [
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
            title: '数据源',
            dataIndex: 'datasourceName',
            width: COL.NAME,
            ellipsis: true,
            render: (v?: string) => (
                <span title={v || '—'} className="text-ds-small text-ds-text-secondary">{v || '—'}</span>
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
            title: '所属任务',
            dataIndex: 'jobName',
            width: COL.NAME,
            ellipsis: true,
            render: (v?: string) => (
                <span title={v || '未绑定'} className="text-ds-small text-ds-text-secondary">{v || '未绑定'}</span>
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
            width: COL.OPERATION_5,
            render: (_, item) => (
                <div className="flex items-center justify-center gap-1 whitespace-nowrap">
                    <Tooltip title="执行">
                        <DsIconButton
                            tone="accent"
                            onClick={() => handleExecute(item)}
                            disabled={executingId === item.id}
                            aria-label="执行"
                        >
                            <HiOutlinePlay size={14}/>
                        </DsIconButton>
                    </Tooltip>
                    <Tooltip title="预览 SQL">
                        <DsIconButton tone="accent" onClick={() => handlePreviewSql(item)} aria-label="预览 SQL">
                            <HiOutlineClipboardDocumentCheck size={14}/>
                        </DsIconButton>
                    </Tooltip>
                    <Tooltip title="详情">
                        <DsIconButton tone="accent" onClick={() => openView(item)} aria-label="详情">
                            <HiOutlineEye size={14}/>
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
                                    <HiOutlineScale size={14}/>
                                </DsIconButton>
                            </Tooltip>
                            <Tooltip title="编辑">
                                <DsIconButton tone="accent" onClick={() => openEdit(item)} aria-label="编辑">
                                    <HiOutlinePencilSquare size={14}/>
                                </DsIconButton>
                            </Tooltip>
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
                        </>
                    )}
                </div>
            ),
        },
    ], [canWrite, handleToggle, executingId, openEdit, openView]);

    return (
        <div className="flex flex-col">
            <div className="flex items-center justify-between mb-ds-5 flex-shrink-0">
                <div>
                    <h1 className="text-ds-display text-ds-text-primary">质量规则</h1>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1">规则可独立创建，被多个质量任务引用，对数据资产进行质量检查</p>
                </div>
            </div>

            <div className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden flex flex-col">
                <div className="p-ds-3 border-b border-ds-border-subtle flex-shrink-0">
                    <DsToolbar
                        extra={(
                            <>
                                {canWrite && jobId && (
                                    <DsButton variant="secondary" onClick={() => setBatchOpen(true)}>
                                        <HiOutlineClipboardDocumentCheck size={16}/>
                                        模板批量应用
                                    </DsButton>
                                )}
                                {canWrite && (
                                    <DsButton onClick={openCreate}>
                                        <HiOutlinePlus size={16}/>
                                        新增规则
                                    </DsButton>
                                )}
                            </>
                        )}
                    >
                        <SearchInput
                            value={keyword}
                            onChange={(e) => setKeyword(e.target.value)}
                            placeholder="搜索规则名称..."
                        />
                        <DsFilterSelect
                            value={type}
                            onChange={(v) => setType(v as QualityRuleType | '')}
                            aria-label="按类型筛选"
                            options={[{value: '', label: '全部类型'}, ...QUALITY_TYPE_OPTIONS]}
                        />
                        <DsFilterSelect
                            value={enabled}
                            onChange={setEnabled}
                            aria-label="按状态筛选"
                            options={[
                                {value: '', label: '全部状态'},
                                {value: '1', label: '启用'},
                                {value: '0', label: '停用'},
                            ]}
                        />
                        <DsFilterSelect
                            value={jobId}
                            onChange={setJobId}
                            aria-label="按所属任务筛选"
                            className="min-w-[180px]"
                            options={[
                                {value: '', label: '全部任务'},
                                ...jobOptions.map((j) => ({value: j.id, label: j.name})),
                            ]}
                        />
                        <DsButton onClick={() => { setPage(1); loadRules(); }} disabled={loading}>
                            {loading ? '查询中...' : '查询'}
                        </DsButton>
                        <DsButton variant="secondary" onClick={resetFilters}>重置</DsButton>
                    </DsToolbar>
                </div>

                <div className="overflow-x-auto">
                    <Table<QualityRule>
                        dataSource={items}
                        rowKey="id"
                        loading={loading}
                        pagination={false}
                        scroll={{x: 1600}}
                        columns={columns}
                        className="prototype-table prototype-table-flush"
                        locale={{
                            emptyText: (
                                <DsTableEmpty
                                    description="暂无质量规则，创建第一个规则开始质量检查。"
                                    action={canWrite && (
                                        <DsButton onClick={openCreate}>
                                            <HiOutlinePlus size={16}/>
                                            新增规则
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

            <QualityRuleDrawer
                open={drawerOpen}
                mode={drawerMode}
                editItem={editItem}
                jobId={jobId}
                onClose={() => {
                    setDrawerOpen(false);
                    setEditItem(null);
                }}
                onSubmit={handleSubmit}
            />

            <BatchApplyModal
                open={batchOpen}
                jobId={jobId}
                onClose={() => setBatchOpen(false)}
                onSubmit={handleBatchSubmit}
            />

            <ConfirmDialog
                open={deleteOpen}
                title="删除确认"
                message={<p className="text-ds-body text-ds-text-secondary">确定删除质量规则 <strong>"{deleteTarget?.name}"</strong> 吗？删除后不可恢复。</p>}
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
