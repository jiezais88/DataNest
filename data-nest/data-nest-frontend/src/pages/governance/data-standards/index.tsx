import {useCallback, useEffect, useMemo, useState} from 'react';
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
    runComplianceCheck,
    updateFieldTypeStandard,
    updateNamingStandard,
} from '../../../api/dataStandard';
import {listMetadataDatasourceIds} from '../../../api/metadata';
import Pagination from '../../../components/Pagination';
import SearchInput from '../../../components/SearchInput';
import ConfirmDialog from '../../../components/ConfirmDialog';
import DsButton from '../../../components/DsButton';
import DsIconButton from '../../../components/DsIconButton';
import DsModal from '../../../components/DsModal';
import DsStatusBadge from '../../../components/DsStatusBadge';
import DsTableEmpty from '../../../components/DsTableEmpty';
import {
    HiOutlineBookOpen,
    HiOutlineCalendar,
    HiOutlineDocumentText,
    HiOutlineEye,
    HiOutlinePencilSquare,
    HiOutlinePlus,
    HiOutlineShieldCheck,
    HiOutlineTrash,
} from 'react-icons/hi2';
import type {
    ComplianceCheckParams,
    ComplianceCheckResult,
    FieldTypeStandard,
    FieldTypeStandardCreateRequest,
    NamingStandard,
    NamingStandardCreateRequest,
    NamingStandardQueryParams,
} from '../../../types/dataStandard';
import type {MetadataDatasource} from '../../../types/metadata';
import {formatDateTime} from '../../../utils/format';
import {COL} from '../../../constants/table';
import NamingStandardDrawer from './NamingStandardDrawer';
import FieldTypeStandardDrawer from './FieldTypeStandardDrawer';
import ComplianceCheckPanel from './ComplianceCheckPanel';

type Tab = 'naming' | 'field-type';

const MATCH_TYPE_LABEL: Record<string, string> = {
    PREFIX: '前缀匹配',
    SUFFIX: '后缀匹配',
    REGEX: '正则匹配',
};

export default function DataStandardsPage() {
    const [searchParams] = useSearchParams();
    const fromCompliance = searchParams.get('from') === 'compliance';
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

    // Compliance check modal
    const [complianceModalOpen, setComplianceModalOpen] = useState(false);
    const [complianceChecking, setComplianceChecking] = useState(false);
    const [complianceDatasources, setComplianceDatasources] = useState<MetadataDatasource[]>([]);
    const [complianceDsIds, setComplianceDsIds] = useState<string[]>([]);
    const [checkNaming, setCheckNaming] = useState(true);
    const [checkFieldType, setCheckFieldType] = useState(true);

    // Compliance results panel
    const [complianceResults, setComplianceResults] = useState<ComplianceCheckResult[]>([]);
    const [complianceParams, setComplianceParams] = useState<ComplianceCheckParams | null>(null);
    const [complianceCheckedAt, setComplianceCheckedAt] = useState<string>('');
    const [showComplianceResults, setShowComplianceResults] = useState(false);

    useEffect(() => {
        if (!fromCompliance) return;
        try {
            const raw = sessionStorage.getItem('datanest:compliance-check-state');
            if (!raw) return;
            const state = JSON.parse(raw);
            if (state.params && state.results) {
                setComplianceParams(state.params);
                setComplianceResults(state.results);
                setComplianceCheckedAt(state.checkedAt || '');
                setShowComplianceResults(true);
            }
        } catch {
            // ignore
        }
    }, [fromCompliance]);

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

    const openComplianceModal = async () => {
        setComplianceModalOpen(true);
        setComplianceDsIds([]);
        setCheckNaming(true);
        setCheckFieldType(true);
        try {
            const res = await listMetadataDatasourceIds();
            setComplianceDatasources(res.data || []);
        } catch {
            setComplianceDatasources([]);
        }
    };

    const handleRunComplianceCheck = async () => {
        if (complianceDsIds.length === 0) {
            notify.warning('请选择检查数据源');
            return;
        }
        if (!checkNaming && !checkFieldType) {
            notify.warning('请至少选择一项检查项目');
            return;
        }
        const params: ComplianceCheckParams = {
            datasourceIds: complianceDsIds,
            checkNaming,
            checkFieldType,
        };
        setComplianceChecking(true);
        try {
            const res = await runComplianceCheck(params);
            notify.success('合规检查完成');
            const checkedAtValue = formatDateTime(new Date().toISOString());
            setComplianceResults(res.data || []);
            setComplianceParams(params);
            setComplianceCheckedAt(checkedAtValue);
            setComplianceModalOpen(false);
            setShowComplianceResults(true);
            try {
                sessionStorage.setItem('datanest:compliance-check-state', JSON.stringify({
                    params,
                    results: res.data || [],
                    checkedAt: checkedAtValue,
                }));
            } catch {
                // ignore
            }
        } finally {
            setComplianceChecking(false);
        }
    };

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
                              className="px-2.5 py-1 bg-ds-accent-light text-ds-accent text-[11px] font-semibold rounded-full whitespace-nowrap">{t}</span>
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

    if (showComplianceResults && complianceParams) {
        return (
            <div className="h-full flex flex-col overflow-hidden">
                <ComplianceCheckPanel
                    params={complianceParams}
                    datasources={complianceDatasources}
                    results={complianceResults}
                    checkedAt={complianceCheckedAt}
                    onReCheck={openComplianceModal}
                    onClose={() => setShowComplianceResults(false)}
                />
            </div>
        );
    }

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
                            <DsButton
                                onClick={openComplianceModal}
                            >
                                <HiOutlineShieldCheck size={16}/>
                                合规检查
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
                        className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden flex flex-col mb-ds-8">
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
                                scroll={{x: 1690}}
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
                        className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden flex flex-col mb-ds-8">
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
                                scroll={{x: 1250}}
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

            <DsModal
                open={complianceModalOpen}
                onClose={() => setComplianceModalOpen(false)}
                title="合规检查"
                width="w-[480px]"
                footer={
                    <>
                        <DsButton
                            variant="secondary"
                            onClick={() => setComplianceModalOpen(false)}
                        >
                            取消
                        </DsButton>
                        <DsButton
                            onClick={handleRunComplianceCheck}
                            disabled={complianceChecking}
                        >
                            {complianceChecking ? '检查中...' : '开始检查'}
                        </DsButton>
                    </>
                }
            >
                <div className="space-y-ds-4">
                    <div>
                        <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                            检查范围 <span className="text-ds-danger">*</span>
                        </label>
                        <div
                            className="space-y-ds-2 max-h-[200px] overflow-auto border border-ds-border-subtle rounded-ds-sm p-ds-3 bg-white">
                            <label className="flex items-center gap-ds-2 cursor-pointer">
                                <input
                                    type="checkbox"
                                    checked={complianceDsIds.length === complianceDatasources.length && complianceDatasources.length > 0}
                                    onChange={(e) => {
                                        if (e.target.checked) {
                                            setComplianceDsIds(complianceDatasources.map((ds) => String(ds.id)));
                                        } else {
                                            setComplianceDsIds([]);
                                        }
                                    }}
                                    className="w-4 h-4 text-ds-accent border-ds-border-subtle rounded focus:ring-ds-accent"
                                />
                                <span
                                    className="text-ds-small text-ds-text-secondary font-medium">全部数据源</span>
                            </label>
                            {complianceDatasources.map((ds) => {
                                const id = String(ds.id);
                                const checked = complianceDsIds.includes(id);
                                return (
                                    <label key={ds.id}
                                           className="flex items-center gap-ds-2 cursor-pointer pl-ds-4">
                                        <input
                                            type="checkbox"
                                            checked={checked}
                                            onChange={(e) => {
                                                setComplianceDsIds((prev) =>
                                                    e.target.checked ? [...prev, id] : prev.filter((v) => v !== id)
                                                );
                                            }}
                                            className="w-4 h-4 text-ds-accent border-ds-border-subtle rounded focus:ring-ds-accent"
                                        />
                                        <span
                                            className="text-ds-small text-ds-text-secondary">{ds.name || `数据源 ${ds.id}`}</span>
                                    </label>
                                );
                            })}
                        </div>
                    </div>

                    <div>
                        <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                            检查项目
                        </label>
                        <div className="space-y-ds-2">
                            <label className="flex items-center gap-ds-2 cursor-pointer">
                                <input
                                    type="checkbox"
                                    checked={checkNaming}
                                    onChange={(e) => setCheckNaming(e.target.checked)}
                                    className="w-4 h-4 text-ds-accent border-ds-border-subtle rounded focus:ring-ds-accent"
                                />
                                <span className="text-ds-small text-ds-text-secondary">命名规范</span>
                            </label>
                            <label className="flex items-center gap-ds-2 cursor-pointer">
                                <input
                                    type="checkbox"
                                    checked={checkFieldType}
                                    onChange={(e) => setCheckFieldType(e.target.checked)}
                                    className="w-4 h-4 text-ds-accent border-ds-border-subtle rounded focus:ring-ds-accent"
                                />
                                <span className="text-ds-small text-ds-text-secondary">字段类型标准</span>
                            </label>
                        </div>
                    </div>
                </div>
            </DsModal>

        </div>
    );
}
