import {useCallback, useEffect, useState} from 'react';
import {message} from 'antd';
import {useAuthStore} from '../../../store/useAuthStore';
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
import EmptyState from '../../../components/EmptyState';
import SearchInput from '../../../components/SearchInput';
import ConfirmDialog from '../../../components/ConfirmDialog';
import {
    HiOutlineBookOpen,
    HiOutlineDocumentText,
    HiOutlinePencilSquare,
    HiOutlinePlus,
    HiOutlineShieldCheck,
    HiOutlineTrash,
    HiOutlineXMark,
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
    const {userInfo} = useAuthStore();
    const roles = userInfo?.roles || [];
    const canWrite = roles.includes('SUPER_ADMIN') || roles.includes('GOVERNANCE_ADMIN');

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

    // Field type standards
    const [fieldTypeItems, setFieldTypeItems] = useState<FieldTypeStandard[]>([]);
    const [fieldTypeTotal, setFieldTypeTotal] = useState(0);
    const [fieldTypePage, setFieldTypePage] = useState(1);
    const [fieldTypePageSize, setFieldTypePageSize] = useState(10);
    const [fieldTypeKeyword, setFieldTypeKeyword] = useState('');
    const [fieldTypeLoading, setFieldTypeLoading] = useState(false);
    const [fieldTypeDrawerOpen, setFieldTypeDrawerOpen] = useState(false);
    const [fieldTypeEditItem, setFieldTypeEditItem] = useState<FieldTypeStandard | null>(null);

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
            if (res.code === 200) {
                setNamingItems(res.data.records);
                setNamingTotal(res.data.total);
            }
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
            if (res.code === 200) {
                setFieldTypeItems(res.data.records);
                setFieldTypeTotal(res.data.total);
            }
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
            if (res.code === 200) {
                setComplianceDatasources(res.data || []);
            }
        } catch {
            setComplianceDatasources([]);
        }
    };

    const handleRunComplianceCheck = async () => {
        if (complianceDsIds.length === 0) {
            message.warning('请选择检查数据源');
            return;
        }
        if (!checkNaming && !checkFieldType) {
            message.warning('请至少选择一项检查项目');
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
            if (res.code === 200) {
                message.success('合规检查完成');
                setComplianceResults(res.data || []);
                setComplianceParams(params);
                setComplianceCheckedAt(new Date().toLocaleString('zh-CN'));
                setComplianceModalOpen(false);
                setShowComplianceResults(true);
            }
        } finally {
            setComplianceChecking(false);
        }
    };

    const handleNamingSubmit = async (form: NamingStandardCreateRequest) => {
        const res = namingEditItem
            ? await updateNamingStandard(namingEditItem.id, form)
            : await createNamingStandard(form);
        if (res.code === 200) {
            message.success(namingEditItem ? '命名规范更新成功' : '命名规范创建成功');
            loadNamingStandards();
        }
        return res;
    };

    const handleFieldTypeSubmit = async (form: FieldTypeStandardCreateRequest) => {
        const res = fieldTypeEditItem
            ? await updateFieldTypeStandard(fieldTypeEditItem.id, form)
            : await createFieldTypeStandard(form);
        if (res.code === 200) {
            message.success(fieldTypeEditItem ? '字段类型标准更新成功' : '字段类型标准创建成功');
            loadFieldTypeStandards();
        }
        return res;
    };

    const handleDelete = async () => {
        if (!deleteTarget) return;
        setDeleteLoading(true);
        try {
            const res = deleteTarget.type === 'naming'
                ? await deleteNamingStandard(deleteTarget.id)
                : await deleteFieldTypeStandard(deleteTarget.id);
            if (res.code === 200) {
                message.success('删除成功');
                if (deleteTarget.type === 'naming') loadNamingStandards();
                else loadFieldTypeStandards();
                setDeleteOpen(false);
                setDeleteTarget(null);
            }
        } finally {
            setDeleteLoading(false);
        }
    };

    const openNamingCreate = () => {
        setNamingEditItem(null);
        loadFieldTypeStandards();
        setNamingDrawerOpen(true);
    };

    const openNamingEdit = (item: NamingStandard) => {
        setNamingEditItem(item);
        loadFieldTypeStandards();
        setNamingDrawerOpen(true);
    };

    const openFieldTypeCreate = () => {
        setFieldTypeEditItem(null);
        setFieldTypeDrawerOpen(true);
    };

    const openFieldTypeEdit = (item: FieldTypeStandard) => {
        setFieldTypeEditItem(item);
        setFieldTypeDrawerOpen(true);
    };

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
        <div className="h-full flex flex-col overflow-hidden">
            <div className="flex items-center justify-between mb-ds-5 flex-shrink-0">
                <div>
                    <h1 className="text-ds-display text-ds-text-primary">数据标准</h1>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1">定义命名规范与字段类型标准，对元数据进行合规检查</p>
                </div>
                <div className="flex items-center gap-ds-2">
                    {canWrite && (
                        <>
                            <button
                                onClick={activeTab === 'naming' ? openNamingCreate : openFieldTypeCreate}
                                className="flex items-center gap-ds-1 px-ds-3 py-ds-2 bg-ds-accent hover:bg-ds-accent-hover text-white text-ds-small font-semibold rounded-ds-sm transition-colors ds-fast"
                            >
                                <HiOutlinePlus size={16}/>
                                {activeTab === 'naming' ? '新建规范' : '新建类型标准'}
                            </button>
                            <button
                                onClick={openComplianceModal}
                                className="flex items-center gap-ds-1 px-ds-3 py-ds-2 bg-ds-accent hover:bg-ds-accent-hover text-white text-ds-small font-semibold rounded-ds-sm transition-colors ds-fast"
                            >
                                <HiOutlineShieldCheck size={16}/>
                                合规检查
                            </button>
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

            <div className="flex-1 min-h-0 overflow-auto">
                {activeTab === 'naming' && (
                    <div
                        className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden">
                        <div className="p-ds-3 border-b border-ds-border-subtle flex items-center gap-ds-3 flex-wrap">
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
                                <button
                                    onClick={() => {
                                        setNamingPage(1);
                                        loadNamingStandards();
                                    }}
                                    disabled={namingLoading}
                                    className="px-ds-4 py-ds-2 bg-ds-accent hover:bg-ds-accent-hover disabled:opacity-60 text-white text-ds-small font-semibold rounded-ds-sm transition-colors"
                                >
                                    {namingLoading ? '查询中...' : '查询'}
                                </button>
                                <button
                                    onClick={resetNamingFilters}
                                    className="px-ds-4 py-ds-2 bg-white border border-ds-border-subtle hover:border-ds-accent text-ds-text-secondary text-ds-small font-semibold rounded-ds-sm transition-colors"
                                >
                                    重置
                                </button>
                            </div>
                        </div>

                        <table className="w-full">
                            <thead className="sticky top-0 z-10">
                            <tr className="border-b border-ds-border-subtle bg-ds-bg-hover/80">
                                <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-secondary uppercase tracking-wider">规范名称</th>
                                <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-secondary uppercase tracking-wider">适用对象</th>
                                <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-secondary uppercase tracking-wider">匹配方式</th>
                                <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-secondary uppercase tracking-wider">规范值</th>
                                <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-secondary uppercase tracking-wider">关联字段类型标准</th>
                                <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-secondary uppercase tracking-wider">优先级</th>
                                <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-secondary uppercase tracking-wider">状态</th>
                                <th className="text-right px-ds-4 py-ds-3 text-ds-caption text-ds-text-secondary uppercase tracking-wider">操作</th>
                            </tr>
                            </thead>
                            <tbody>
                            {namingItems.map((item) => (
                                <tr key={item.id}
                                    className="border-b border-ds-border-subtle last:border-0 hover:bg-ds-bg-hover/50 transition-colors">
                                    <td className="px-ds-4 py-ds-3 text-ds-body text-ds-text-primary font-medium">{item.name}</td>
                                    <td className="px-ds-4 py-ds-3 text-ds-body text-ds-text-secondary">{item.appliesTo === 'TABLE' ? '表名' : '字段名'}</td>
                                    <td className="px-ds-4 py-ds-3 text-ds-small text-ds-text-secondary">{MATCH_TYPE_LABEL[item.ruleType] || item.ruleType}</td>
                                    <td className="px-ds-4 py-ds-3 text-ds-small text-ds-text-secondary font-mono">{item.ruleValue}</td>
                                    <td className="px-ds-4 py-ds-3 text-ds-small text-ds-text-secondary">{item.targetStandardName || '—'}</td>
                                    <td className="px-ds-4 py-ds-3 text-ds-small text-ds-text-secondary">{item.priority}</td>
                                    <td className="px-ds-4 py-ds-3">
                                        {item.enabled === 1 ? (
                                            <span
                                                className="inline-flex items-center gap-ds-1 px-ds-2 py-ds-1 rounded-ds-full text-ds-small font-medium bg-emerald-50 text-emerald-700">
                                                <span className="w-1.5 h-1.5 rounded-full bg-emerald-500"/>
                                                启用
                                            </span>
                                        ) : (
                                            <span
                                                className="inline-flex items-center gap-ds-1 px-ds-2 py-ds-1 rounded-ds-full text-ds-small font-medium bg-gray-100 text-gray-600">
                                                <span className="w-1.5 h-1.5 rounded-full bg-gray-400"/>
                                                停用
                                            </span>
                                        )}
                                    </td>
                                    <td className="px-ds-4 py-ds-3">
                                        <div className="flex items-center justify-end gap-1">
                                            {canWrite && (
                                                <>
                                                    <button
                                                        onClick={() => openNamingEdit(item)}
                                                        className="p-1.5 text-ds-text-muted hover:text-ds-accent hover:bg-ds-accent-light rounded transition-colors"
                                                        title="编辑"
                                                    >
                                                        <HiOutlinePencilSquare size={16}/>
                                                    </button>
                                                    <button
                                                        onClick={() => {
                                                            setDeleteTarget({
                                                                type: 'naming',
                                                                id: item.id,
                                                                name: item.name
                                                            });
                                                            setDeleteOpen(true);
                                                        }}
                                                        className="p-1.5 text-ds-text-muted hover:text-ds-danger hover:bg-ds-danger-light rounded transition-colors"
                                                        title="删除"
                                                    >
                                                        <HiOutlineTrash size={16}/>
                                                    </button>
                                                </>
                                            )}
                                        </div>
                                    </td>
                                </tr>
                            ))}
                            </tbody>
                        </table>

                        {namingItems.length === 0 && !namingLoading && (
                            <EmptyState
                                title="暂无命名规范"
                                description="还没有命名规范，创建第一条规范开始合规检查。"
                                action={canWrite ? (
                                    <button
                                        onClick={openNamingCreate}
                                        className="flex items-center gap-ds-1 px-ds-4 py-ds-2 bg-ds-accent hover:bg-ds-accent-hover text-white text-ds-small font-semibold rounded-ds-sm transition-colors"
                                    >
                                        <HiOutlinePlus size={16}/>
                                        新建命名规范
                                    </button>
                                ) : null}
                            />
                        )}

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
                        className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden">
                        <div className="p-ds-3 border-b border-ds-border-subtle flex items-center gap-ds-3">
                            <SearchInput
                                value={fieldTypeKeyword}
                                onChange={(e) => setFieldTypeKeyword(e.target.value)}
                                placeholder="搜索标准名称..."
                            />
                            <div className="ml-auto flex items-center gap-ds-2">
                                <button
                                    onClick={() => {
                                        setFieldTypePage(1);
                                        loadFieldTypeStandards();
                                    }}
                                    disabled={fieldTypeLoading}
                                    className="px-ds-4 py-ds-2 bg-ds-accent hover:bg-ds-accent-hover disabled:opacity-60 text-white text-ds-small font-semibold rounded-ds-sm transition-colors"
                                >
                                    {fieldTypeLoading ? '查询中...' : '查询'}
                                </button>
                                <button
                                    onClick={resetFieldTypeFilters}
                                    className="px-ds-4 py-ds-2 bg-white border border-ds-border-subtle hover:border-ds-accent text-ds-text-secondary text-ds-small font-semibold rounded-ds-sm transition-colors"
                                >
                                    重置
                                </button>
                            </div>
                        </div>

                        <table className="w-full">
                            <thead className="sticky top-0 z-10">
                            <tr className="border-b border-ds-border-subtle bg-ds-bg-hover/80">
                                <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-secondary uppercase tracking-wider">标准名称</th>
                                <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-secondary uppercase tracking-wider">分类</th>
                                <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-secondary uppercase tracking-wider">允许类型</th>
                                <th className="text-left px-ds-4 py-ds-3 text-ds-caption text-ds-text-secondary uppercase tracking-wider">描述</th>
                                <th className="text-right px-ds-4 py-ds-3 text-ds-caption text-ds-text-secondary uppercase tracking-wider">操作</th>
                            </tr>
                            </thead>
                            <tbody>
                            {fieldTypeItems.map((item) => (
                                <tr key={item.id}
                                    className="border-b border-ds-border-subtle last:border-0 hover:bg-ds-bg-hover/50 transition-colors">
                                    <td className="px-ds-4 py-ds-3 text-ds-body text-ds-text-primary font-medium">{item.name}</td>
                                    <td className="px-ds-4 py-ds-3 text-ds-body text-ds-text-secondary">{item.category || '—'}</td>
                                    <td className="px-ds-4 py-ds-3">
                                        <div className="flex flex-wrap gap-ds-1">
                                            {item.allowedTypes.map((t) => (
                                                <span key={t}
                                                      className="px-ds-2 py-ds-1 bg-ds-accent-light text-ds-accent text-ds-small rounded-ds-sm">{t}</span>
                                            ))}
                                        </div>
                                    </td>
                                    <td className="px-ds-4 py-ds-3 text-ds-small text-ds-text-secondary max-w-xs truncate">{item.description || '—'}</td>
                                    <td className="px-ds-4 py-ds-3">
                                        <div className="flex items-center justify-end gap-1">
                                            {canWrite && (
                                                <>
                                                    <button
                                                        onClick={() => openFieldTypeEdit(item)}
                                                        className="p-1.5 text-ds-text-muted hover:text-ds-accent hover:bg-ds-accent-light rounded transition-colors"
                                                        title="编辑"
                                                    >
                                                        <HiOutlinePencilSquare size={16}/>
                                                    </button>
                                                    <button
                                                        onClick={() => {
                                                            setDeleteTarget({
                                                                type: 'field-type',
                                                                id: item.id,
                                                                name: item.name
                                                            });
                                                            setDeleteOpen(true);
                                                        }}
                                                        className="p-1.5 text-ds-text-muted hover:text-ds-danger hover:bg-ds-danger-light rounded transition-colors"
                                                        title="删除"
                                                    >
                                                        <HiOutlineTrash size={16}/>
                                                    </button>
                                                </>
                                            )}
                                        </div>
                                    </td>
                                </tr>
                            ))}
                            </tbody>
                        </table>

                        {fieldTypeItems.length === 0 && !fieldTypeLoading && (
                            <EmptyState
                                title="暂无字段类型标准"
                                description="还没有字段类型标准，创建第一条标准。"
                                action={canWrite ? (
                                    <button
                                        onClick={openFieldTypeCreate}
                                        className="flex items-center gap-ds-1 px-ds-4 py-ds-2 bg-ds-accent hover:bg-ds-accent-hover text-white text-ds-small font-semibold rounded-ds-sm transition-colors"
                                    >
                                        <HiOutlinePlus size={16}/>
                                        新建字段类型标准
                                    </button>
                                ) : null}
                            />
                        )}

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

            {complianceModalOpen && (
                <div className="fixed inset-0 z-ds-dialog flex items-center justify-center p-ds-6">
                    <div className="absolute inset-0 bg-black/30" onClick={() => setComplianceModalOpen(false)}/>
                    <div
                        className="relative bg-ds-bg-surface rounded-ds-md shadow-ds-xl w-[480px] max-h-[85vh] flex flex-col">
                        <div
                            className="flex items-center justify-between px-ds-5 py-ds-4 border-b border-ds-border-subtle">
                            <h3 className="text-ds-subhead text-ds-text-primary font-semibold">合规检查</h3>
                            <button
                                onClick={() => setComplianceModalOpen(false)}
                                className="p-1 text-ds-text-muted hover:text-ds-text-primary hover:bg-ds-bg-hover rounded transition-colors"
                                aria-label="关闭"
                            >
                                <HiOutlineXMark size={20}/>
                            </button>
                        </div>
                        <div className="p-ds-5 space-y-ds-4 overflow-auto">
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
                        <div className="px-ds-5 py-ds-4 border-t border-ds-border-subtle flex justify-end gap-ds-3">
                            <button
                                onClick={() => setComplianceModalOpen(false)}
                                className="px-ds-4 py-ds-2 bg-white border border-ds-border-subtle hover:border-ds-border-strong text-ds-text-secondary text-ds-small font-semibold rounded-ds-sm transition-colors"
                            >
                                取消
                            </button>
                            <button
                                onClick={handleRunComplianceCheck}
                                disabled={complianceChecking}
                                className="px-ds-4 py-ds-2 bg-ds-accent hover:bg-ds-accent-hover disabled:opacity-60 disabled:cursor-not-allowed text-white text-ds-small font-semibold rounded-ds-sm transition-colors"
                            >
                                {complianceChecking ? '检查中...' : '开始检查'}
                            </button>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
}

// Need to keep update functions referenced in handlers even though not defined above? They are missing imports.
// Actually we need to add update imports. Since we wrote full file, we need to import updateNamingStandard and updateFieldTypeStandard.
// Let's fix this by reading the file and adding imports. But the Write tool already wrote it. We can use Edit to add imports.
