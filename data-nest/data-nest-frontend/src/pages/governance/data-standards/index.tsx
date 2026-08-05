import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {useSearchParams} from 'react-router-dom';
import {Table, Tooltip} from 'antd';
import {notify} from '../../../utils/notify';
import type {ColumnsType} from 'antd/es/table';
import {useHasRole} from '../../../hooks/useHasRole';
import {GOVERNANCE_WRITE_ROLES} from '../../../constants/roles';
import {
    createFieldTypeStandard,
    createNamingStandard,
    deleteFieldTypeStandard,
    deleteNamingStandard,
    queryFieldTypeStandards,
    queryNamingStandards,
    updateFieldTypeStandard,
    updateNamingStandard,
} from '../../../api/dataStandard';
import ReferenceListModal from '../../../components/ReferenceListModal';
import type {ApiError} from '../../../utils/error';
import Pagination from '../../../components/Pagination';
import SearchInput from '../../../components/SearchInput';
import ConfirmDialog from '../../../components/ConfirmDialog';
import DsButton from '../../../components/DsButton';
import DsIconButton from '../../../components/DsIconButton';
import DsStatusBadge from '../../../components/DsStatusBadge';
import DsTableEmpty from '../../../components/DsTableEmpty';
import {
    HiOutlineBookOpen,
    HiOutlineCalendar,
    HiOutlineDocumentText,
    HiOutlineEye,
    HiOutlinePencilSquare,
    HiOutlinePlus,
    HiOutlineTrash,
} from 'react-icons/hi2';
import type {
    FieldTypeStandard,
    FieldTypeStandardCreateRequest,
    NamingStandard,
    NamingStandardCreateRequest,
    NamingStandardQueryParams,
} from '../../../types/dataStandard';
import {formatDateTime} from '../../../utils/format';
import {COL} from '../../../constants/table';
import NamingStandardDrawer from './NamingStandardDrawer';
import FieldTypeStandardDrawer from './FieldTypeStandardDrawer';

type Tab = 'naming' | 'field-type';

const MATCH_TYPE_LABEL: Record<string, string> = {
    PREFIX: '前缀匹配',
    SUFFIX: '后缀匹配',
    REGEX: '正则匹配',
};

export default function DataStandardsPage() {
    const [searchParams, setSearchParams] = useSearchParams();
    const canWrite = useHasRole(...GOVERNANCE_WRITE_ROLES);

    const [activeTab, setActiveTab] = useState<Tab>('naming');

    // Naming standards
    const [namingItems, setNamingItems] = useState<NamingStandard[]>([]);
    const [namingTotal, setNamingTotal] = useState(0);
    const [namingPage, setNamingPage] = useState(1);
    const [namingPageSize, setNamingPageSize] = useState(10);
    const [namingKeyword, setNamingKeyword] = useState('');
    const [namingAppliesTo, setNamingAppliesTo] = useState<NamingStandardQueryParams['appliesTo']>('');
    const [namingEnabled, setNamingEnabled] = useState<number | undefined>(undefined);
    const [namingLoading, setNamingLoading] = useState(false);
    const [namingDrawerOpen, setNamingDrawerOpen] = useState(false);
    const [namingEditItem, setNamingEditItem] = useState<NamingStandard | null>(null);
    const [namingDrawerMode, setNamingDrawerMode] = useState<'create' | 'edit' | 'view'>('create');

    // Field type standards
    const [fieldTypeItems, setFieldTypeItems] = useState<FieldTypeStandard[]>([]);
    const [fieldTypeTotal, setFieldTypeTotal] = useState(0);
    const [fieldTypePage, setFieldTypePage] = useState(1);
    const [fieldTypePageSize, setFieldTypePageSize] = useState(10);
    const [fieldTypeKeyword, setFieldTypeKeyword] = useState('');
    const [fieldTypeLoading, setFieldTypeLoading] = useState(false);
    const [fieldTypeDrawerOpen, setFieldTypeDrawerOpen] = useState(false);
    const [fieldTypeEditItem, setFieldTypeEditItem] = useState<FieldTypeStandard | null>(null);
    const [fieldTypeDrawerMode, setFieldTypeDrawerMode] = useState<'create' | 'edit' | 'view'>('create');

    const [deleteTarget, setDeleteTarget] = useState<{
        type: 'naming' | 'field-type';
        id: string;
        name: string
    } | null>(null);
    const [deleteOpen, setDeleteOpen] = useState(false);
    const [deleteLoading, setDeleteLoading] = useState(false);
    const [deleteBlockedOpen, setDeleteBlockedOpen] = useState(false);
    const [deleteReferences, setDeleteReferences] = useState<string[]>([]);

    const loadNamingStandards = useCallback(async () => {
        setNamingLoading(true);
        try {
            const res = await queryNamingStandards({
                page: namingPage,
                pageSize: namingPageSize,
                keyword: namingKeyword || undefined,
                appliesTo: namingAppliesTo || undefined,
                enabled: namingEnabled,
            });
            setNamingItems(res.data.records);
            setNamingTotal(res.data.total);
        } finally {
            setNamingLoading(false);
        }
    }, [namingPage, namingPageSize, namingKeyword, namingAppliesTo, namingEnabled]);

    const loadFieldTypeStandards = useCallback(async () => {
        setFieldTypeLoading(true);
        try {
            const res = await queryFieldTypeStandards({
                page: fieldTypePage,
                pageSize: fieldTypePageSize,
                keyword: fieldTypeKeyword || undefined,
            });
            setFieldTypeItems(res.data.records);
            setFieldTypeTotal(res.data.total);
        } finally {
            setFieldTypeLoading(false);
        }
    }, [fieldTypePage, fieldTypePageSize, fieldTypeKeyword]);

    const resetNamingFilters = () => {
        setNamingKeyword('');
        setNamingAppliesTo('');
        setNamingEnabled(undefined);
        setNamingPage(1);
        loadNamingStandards();
    };

    const resetFieldTypeFilters = () => {
        setFieldTypeKeyword('');
        setFieldTypePage(1);
        loadFieldTypeStandards();
    };

    useEffect(() => {
        if (activeTab === 'naming') loadNamingStandards();
    }, [activeTab, loadNamingStandards]);

    useEffect(() => {
        if (activeTab === 'field-type') loadFieldTypeStandards();
    }, [activeTab, loadFieldTypeStandards]);

    // L2：进页时从 URL 初始化筛选（Tab/关键字/分页），深层跳转返回后筛选不丢
    const urlInitRef = useRef(false);
    useEffect(() => {
        if (urlInitRef.current) return;
        urlInitRef.current = true;
        const p = searchParams;
        const tab: Tab = p.get('tab') === 'field-type' ? 'field-type' : 'naming';
        const appliesTo = p.get('nameAppliesTo');
        const namingAppliesTo = (appliesTo === 'TABLE' || appliesTo === 'COLUMN' || appliesTo === 'DATABASE' || appliesTo === 'SCHEMA')
            ? appliesTo as NamingStandardQueryParams['appliesTo']
            : '';
        const en = p.get('nameEnabled');
        const namingEnabled = en === '1' ? 1 : en === '0' ? 0 : undefined;
        setActiveTab(tab);
        setNamingKeyword(p.get('nameKeyword') || '');
        setNamingAppliesTo(namingAppliesTo);
        setNamingEnabled(namingEnabled);
        setFieldTypeKeyword(p.get('ftKeyword') || '');
        setNamingPage(Number(p.get('namePage')) || 1);
        setFieldTypePage(Number(p.get('ftPage')) || 1);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    // L2：筛选/分页变化时同步到 URL（保留 from=compliance 跳转语义）
    useEffect(() => {
        const next = new URLSearchParams();
        if (searchParams.has('from')) next.set('from', searchParams.get('from')!);
        next.set('tab', activeTab);
        if (namingKeyword) next.set('nameKeyword', namingKeyword);
        if (namingAppliesTo) next.set('nameAppliesTo', namingAppliesTo);
        if (namingEnabled != null) next.set('nameEnabled', String(namingEnabled));
        if (fieldTypeKeyword) next.set('ftKeyword', fieldTypeKeyword);
        next.set('namePage', String(namingPage));
        next.set('ftPage', String(fieldTypePage));
        if (next.toString() === searchParams.toString()) return;
        setSearchParams(next, {replace: true});
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [activeTab, namingKeyword, namingAppliesTo, namingEnabled, namingPage, fieldTypeKeyword, fieldTypePage]);

    const handleNamingSubmit = async (form: NamingStandardCreateRequest) => {
        const res = namingEditItem
            ? await updateNamingStandard(namingEditItem.id, form)
            : await createNamingStandard(form);
        notify.success(namingEditItem ? '命名规范更新成功' : '命名规范创建成功');
        loadNamingStandards();
        return res;
    };

    const handleToggleNamingEnabled = useCallback(async (item: NamingStandard) => {
        const nextEnabled = item.enabled === 1 ? 0 : 1;
        const payload: NamingStandardCreateRequest = {
            name: item.name,
            appliesTo: item.appliesTo,
            ruleType: item.ruleType,
            ruleValue: item.ruleValue,
            targetStandardId: item.targetStandardId,
            priority: item.priority,
            enabled: nextEnabled,
            description: item.description,
        };
        const res = await updateNamingStandard(item.id, payload);
        notify.success(nextEnabled === 1 ? '已启用' : '已停用');
        loadNamingStandards();
        return res;
    }, [loadNamingStandards]);

    const handleFieldTypeSubmit = async (form: FieldTypeStandardCreateRequest) => {
        const res = fieldTypeEditItem
            ? await updateFieldTypeStandard(fieldTypeEditItem.id, form)
            : await createFieldTypeStandard(form);
        notify.success(fieldTypeEditItem ? '字段类型标准更新成功' : '字段类型标准创建成功');
        loadFieldTypeStandards();
        return res;
    };

    const handleDelete = async () => {
        if (!deleteTarget) return;
        setDeleteLoading(true);
        try {
            if (deleteTarget.type === 'naming') await deleteNamingStandard(deleteTarget.id);
            else await deleteFieldTypeStandard(deleteTarget.id);
            notify.success('删除成功');
            if (deleteTarget.type === 'naming') loadNamingStandards();
            else loadFieldTypeStandards();
            setDeleteOpen(false);
            setDeleteTarget(null);
        } catch (e) {
            const errorData = (e as ApiError)?.response?.data;
            // 字段类型标准被命名规范引用时（3005），后端 data 返回引用命名规范名称列表，弹窗展示
            if (deleteTarget?.type === 'field-type' && errorData?.code === 3005 && Array.isArray(errorData?.data)) {
                setDeleteReferences(errorData.data as string[]);
                setDeleteOpen(false);
                setDeleteBlockedOpen(true);
            }
        } finally {
            setDeleteLoading(false);
        }
    };

    const openNamingCreate = () => {
        setNamingEditItem(null);
        setNamingDrawerMode('create');
        loadFieldTypeStandards();
        setNamingDrawerOpen(true);
    };

    const openNamingEdit = useCallback((item: NamingStandard) => {
        setNamingEditItem(item);
        setNamingDrawerMode('edit');
        loadFieldTypeStandards();
        setNamingDrawerOpen(true);
    }, [loadFieldTypeStandards]);

    const openNamingView = useCallback((item: NamingStandard) => {
        setNamingEditItem(item);
        setNamingDrawerMode('view');
        loadFieldTypeStandards();
        setNamingDrawerOpen(true);
    }, [loadFieldTypeStandards]);

    const openFieldTypeCreate = () => {
        setFieldTypeEditItem(null);
        setFieldTypeDrawerMode('create');
        setFieldTypeDrawerOpen(true);
    };

    const openFieldTypeEdit = useCallback((item: FieldTypeStandard) => {
        setFieldTypeEditItem(item);
        setFieldTypeDrawerMode('edit');
        setFieldTypeDrawerOpen(true);
    }, []);

    const openFieldTypeView = useCallback((item: FieldTypeStandard) => {
        setFieldTypeEditItem(item);
        setFieldTypeDrawerMode('view');
        setFieldTypeDrawerOpen(true);
    }, []);

    const namingColumns = useMemo<ColumnsType<NamingStandard>>(() => [
        {
            title: '规范名称',
            dataIndex: 'name',
            width: COL.NAME,
            ellipsis: true,
            render: (v: string) => (
                <span title={v} className="text-ds-small text-ds-text-primary font-medium">{v}</span>
            ),
        },
        {
            title: '适用对象',
            dataIndex: 'appliesTo',
            width: COL.STATUS,
            render: (v: string) => (
                <span className="text-ds-small text-ds-text-secondary">{v === 'TABLE' ? '表名' : '字段名'}</span>
            ),
        },
        {
            title: '匹配方式',
            dataIndex: 'ruleType',
            width: COL.STATUS,
            render: (v: string) => (
                <span className="text-ds-small text-ds-text-secondary">{MATCH_TYPE_LABEL[v] || v}</span>
            ),
        },
        {
            title: '规范值',
            dataIndex: 'ruleValue',
            width: 160,
            ellipsis: true,
            render: (v: string) => (
                <span title={v} className="text-ds-small text-ds-text-secondary font-mono">{v}</span>
            ),
        },
        {
            title: '关联字段类型标准',
            dataIndex: 'targetStandardName',
            width: COL.NAME,
            ellipsis: true,
            render: (v?: string) => (
                <span title={v || '—'} className="text-ds-small text-ds-text-secondary">{v || '—'}</span>
            ),
        },
        {
            title: '优先级',
            dataIndex: 'priority',
            width: COL.COUNT,
            render: (v: number) => (
                <span className="text-ds-small text-ds-text-secondary">{v}</span>
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
                <span title={v || '—'} className="text-ds-small text-ds-text-secondary">{v || '—'}</span>
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
                <span title={v || '—'} className="text-ds-small text-ds-text-secondary">{v || '—'}</span>
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
            width: COL.OPERATION_4,
            render: (_, item) => (
                <div className="flex items-center justify-center gap-1 whitespace-nowrap">
                    <Tooltip title="详情">
                        <DsIconButton
                            tone="accent"
                            onClick={() => openNamingView(item)}
                        >
                            <HiOutlineEye size={14}/>
                        </DsIconButton>
                    </Tooltip>
                    {canWrite && (
                        <>
                            <Tooltip title={item.enabled === 1 ? '停用' : '启用'}>
                                <DsIconButton
                                    tone="success"
                                    active={item.enabled === 1}
                                    onClick={() => handleToggleNamingEnabled(item)}
                                    aria-label={item.enabled === 1 ? '停用' : '启用'}
                                >
                                    <HiOutlineCalendar size={14}/>
                                </DsIconButton>
                            </Tooltip>
                            <Tooltip title="编辑">
                                <DsIconButton
                                    tone="accent"
                                    onClick={() => openNamingEdit(item)}
                                >
                                    <HiOutlinePencilSquare size={14}/>
                                </DsIconButton>
                            </Tooltip>
                            <Tooltip title="删除">
                                <DsIconButton
                                    tone="danger"
                                    onClick={() => {
                                        setDeleteTarget({
                                            type: 'naming',
                                            id: item.id,
                                            name: item.name
                                        });
                                        setDeleteOpen(true);
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
    ], [canWrite, handleToggleNamingEnabled, openNamingEdit, openNamingView]);

    const fieldTypeColumns = useMemo<ColumnsType<FieldTypeStandard>>(() => [
        {
            title: '标准名称',
            dataIndex: 'name',
            width: COL.NAME,
            ellipsis: true,
            render: (v: string) => (
                <span title={v} className="text-ds-small text-ds-text-primary font-medium">{v}</span>
            ),
        },
        {
            title: '分类',
            dataIndex: 'category',
            width: 120,
            ellipsis: true,
            render: (v?: string) => (
                <span title={v || '—'} className="text-ds-small text-ds-text-secondary">{v || '—'}</span>
            ),
        },
        {
            title: '允许类型',
            dataIndex: 'allowedTypes',
            width: 200,
            render: (types: string[]) => (
                <div className="flex flex-wrap gap-ds-1">
                    {types.map((t) => (
                        <span key={t}
                              className="px-2.5 py-1 bg-ds-accent-light text-ds-accent text-ds-badge rounded-full whitespace-nowrap">{t}</span>
                    ))}
                </div>
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
                <span title={v || '—'} className="text-ds-small text-ds-text-secondary">{v || '—'}</span>
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
            width: COL.OPERATION_3,
            render: (_, item) => (
                <div className="flex items-center justify-center gap-1 whitespace-nowrap">
                    <Tooltip title="详情">
                        <DsIconButton
                            tone="accent"
                            onClick={() => openFieldTypeView(item)}
                        >
                            <HiOutlineEye size={14}/>
                        </DsIconButton>
                    </Tooltip>
                    {canWrite && (
                        <>
                            <Tooltip title="编辑">
                                <DsIconButton
                                    tone="accent"
                                    onClick={() => openFieldTypeEdit(item)}
                                >
                                    <HiOutlinePencilSquare size={14}/>
                                </DsIconButton>
                            </Tooltip>
                            <Tooltip title="删除">
                                <DsIconButton
                                    tone="danger"
                                    onClick={() => {
                                        setDeleteTarget({
                                            type: 'field-type',
                                            id: item.id,
                                            name: item.name
                                        });
                                        setDeleteOpen(true);
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
    ], [canWrite, openFieldTypeEdit, openFieldTypeView]);

    const tabs = [
        {key: 'naming', label: '命名规范', icon: HiOutlineDocumentText},
        {key: 'field-type', label: '字段类型标准', icon: HiOutlineBookOpen},
    ];

    return (
        <div className="flex flex-col">
            <div className="flex items-center justify-between mb-ds-5 flex-shrink-0">
                <div>
                    <h1 className="text-ds-display text-ds-text-primary">数据标准</h1>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1">定义命名规范与字段类型标准，对元数据进行合规检查</p>
                </div>
                <div className="flex items-center gap-ds-2">
                    {canWrite && (
                        <>
                            <DsButton
                                onClick={activeTab === 'naming' ? openNamingCreate : openFieldTypeCreate}
                            >
                                <HiOutlinePlus size={16}/>
                                {activeTab === 'naming' ? '新建命名规范' : '新建字段类型标准'}
                            </DsButton>
                        </>
                    )}
                </div>
            </div>

            <div className="flex gap-ds-2 mb-ds-4 flex-shrink-0">
                {tabs.map((t) => {
                    const Icon = t.icon;
                    const active = activeTab === t.key;
                    return (
                        <button
                            key={t.key}
                            onClick={() => setActiveTab(t.key as Tab)}
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

            <div className="flex flex-col">
                {activeTab === 'naming' && (
                    <div
                        className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden flex flex-col">
                        <div
                            className="p-ds-3 border-b border-ds-border-subtle flex items-center gap-ds-3 flex-wrap flex-shrink-0">
                            <SearchInput
                                value={namingKeyword}
                                onChange={(e) => setNamingKeyword(e.target.value)}
                                placeholder="搜索规范名称..."
                            />
                            <select
                                value={namingAppliesTo}
                                onChange={(e) => setNamingAppliesTo(e.target.value as NamingStandardQueryParams['appliesTo'])}
                                className="px-ds-3 py-ds-2 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-small text-ds-text-primary focus:outline-none focus-visible:border-ds-accent"
                            >
                                <option value="">全部对象</option>
                                <option value="TABLE">表名</option>
                                <option value="COLUMN">字段名</option>
                            </select>
                            <select
                                value={namingEnabled ?? ''}
                                onChange={(e) => {
                                    const v = e.target.value;
                                    setNamingEnabled(v === '' ? undefined : Number(v));
                                }}
                                className="px-ds-3 py-ds-2 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-small text-ds-text-primary focus:outline-none focus-visible:border-ds-accent"
                            >
                                <option value="">全部状态</option>
                                <option value="1">启用</option>
                                <option value="0">停用</option>
                            </select>
                            <div className="ml-auto flex items-center gap-ds-2">
                                <DsButton
                                    onClick={() => {
                                        setNamingPage(1);
                                        loadNamingStandards();
                                    }}
                                    disabled={namingLoading}
                                >
                                    {namingLoading ? '查询中...' : '查询'}
                                </DsButton>
                                <DsButton
                                    variant="secondary"
                                    onClick={resetNamingFilters}
                                >
                                    重置
                                </DsButton>
                            </div>
                        </div>

                        <div className="overflow-x-auto">
                            <Table<NamingStandard>
                                dataSource={namingItems}
                                rowKey="id"
                                loading={namingLoading}
                                pagination={false}
                                scroll={{x: 1660}}
                                columns={namingColumns}
                                className="prototype-table prototype-table-flush"
                                locale={{
                                    emptyText: (
                                        <DsTableEmpty
                                            description="暂无命名规范，创建第一条规范开始合规检查。"
                                            action={canWrite && (
                                                <DsButton
                                                    onClick={openNamingCreate}
                                                >
                                                    <HiOutlinePlus size={16}/>
                                                    新建命名规范
                                                </DsButton>
                                            )}
                                        />
                                    ),
                                }}
                            />
                        </div>

                        <Pagination
                            page={namingPage}
                            pageSize={namingPageSize}
                            total={namingTotal}
                            onChange={(p, s) => {
                                setNamingPage(p);
                                setNamingPageSize(s);
                            }}
                        />
                    </div>
                )}

                {activeTab === 'field-type' && (
                    <div
                        className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden flex flex-col">
                        <div
                            className="p-ds-3 border-b border-ds-border-subtle flex items-center gap-ds-3 flex-shrink-0">
                            <SearchInput
                                value={fieldTypeKeyword}
                                onChange={(e) => setFieldTypeKeyword(e.target.value)}
                                placeholder="搜索标准名称..."
                            />
                            <div className="ml-auto flex items-center gap-ds-2">
                                <DsButton
                                    onClick={() => {
                                        setFieldTypePage(1);
                                        loadFieldTypeStandards();
                                    }}
                                    disabled={fieldTypeLoading}
                                >
                                    {fieldTypeLoading ? '查询中...' : '查询'}
                                </DsButton>
                                <DsButton
                                    variant="secondary"
                                    onClick={resetFieldTypeFilters}
                                >
                                    重置
                                </DsButton>
                            </div>
                        </div>

                        <div className="overflow-x-auto">
                            <Table<FieldTypeStandard>
                                dataSource={fieldTypeItems}
                                rowKey="id"
                                loading={fieldTypeLoading}
                                pagination={false}
                                scroll={{x: 1220}}
                                columns={fieldTypeColumns}
                                className="prototype-table prototype-table-flush"
                                locale={{
                                    emptyText: (
                                        <DsTableEmpty
                                            description="暂无字段类型标准，创建第一条标准。"
                                            action={canWrite && (
                                                <DsButton
                                                    onClick={openFieldTypeCreate}
                                                >
                                                    <HiOutlinePlus size={16}/>
                                                    新建字段类型标准
                                                </DsButton>
                                            )}
                                        />
                                    ),
                                }}
                            />
                        </div>

                        <Pagination
                            page={fieldTypePage}
                            pageSize={fieldTypePageSize}
                            total={fieldTypeTotal}
                            onChange={(p, s) => {
                                setFieldTypePage(p);
                                setFieldTypePageSize(s);
                            }}
                        />
                    </div>
                )}
            </div>

            <NamingStandardDrawer
                open={namingDrawerOpen}
                mode={namingDrawerMode}
                editItem={namingEditItem}
                standards={fieldTypeItems}
                onClose={() => {
                    setNamingDrawerOpen(false);
                    setNamingEditItem(null);
                }}
                onSubmit={handleNamingSubmit}
            />

            <FieldTypeStandardDrawer
                open={fieldTypeDrawerOpen}
                mode={fieldTypeDrawerMode}
                editItem={fieldTypeEditItem}
                onClose={() => {
                    setFieldTypeDrawerOpen(false);
                    setFieldTypeEditItem(null);
                }}
                onSubmit={handleFieldTypeSubmit}
            />

            <ConfirmDialog
                open={deleteOpen}
                title="删除确认"
                message={
                    <p className="text-ds-body text-ds-text-secondary">
                        确定删除 <strong>"{deleteTarget?.name}"</strong> 吗？删除后不可恢复。
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

            <ReferenceListModal
                open={deleteBlockedOpen}
                title="无法删除字段类型标准"
                message={`字段类型标准 "${deleteTarget?.name ?? ''}" 已被以下命名规范引用，请先删除或修改相关命名规范后再删除。`}
                references={deleteReferences}
                onClose={() => setDeleteBlockedOpen(false)}
            />

        </div>
    );
}
