import type {ReactNode} from 'react';
import {useMemo} from 'react';
import {useNavigate} from 'react-router-dom';
import {Table} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import type {ComplianceCheckParams, ComplianceCheckResult} from '../../../types/dataStandard';
import type {MetadataDatasource} from '../../../types/metadata';
import EmptyState from '../../../components/EmptyState';
import DsButton from '../../../components/DsButton';
import DsTableEmpty from '../../../components/DsTableEmpty';
import {HiOutlineArrowLeft, HiOutlineEye} from 'react-icons/hi2';

interface ComplianceCheckPanelProps {
    params: ComplianceCheckParams;
    datasources: MetadataDatasource[];
    results: ComplianceCheckResult[];
    checkedAt?: string;
    onReCheck: () => void;
    onClose: () => void;
}

function formatScope(params: ComplianceCheckParams, datasources: MetadataDatasource[]) {
    if (params.tableId) return '指定表';
    const ids = params.datasourceIds || [];
    if (ids.length === 0) return '未指定';
    if (ids.length === datasources.length) return '全部数据源';
    const names = ids
        .map((id) => datasources.find((d) => String(d.id) === String(id))?.name || String(id))
        .join('、');
    let scope = names;
    if (params.databaseName) {
        scope += ` / ${params.databaseName}`;
        if (params.schemaName && params.schemaName !== params.databaseName) {
            scope += ` / ${params.schemaName}`;
        }
    }
    return scope;
}

function formatObjectPath(result: ComplianceCheckResult) {
    if (result.objectPath) return result.objectPath;
    const parts = [result.tableName, result.columnName].filter(Boolean);
    return parts.length ? parts.join('.') : result.objectName;
}

function formatRuleTypeLabel(ruleType?: string) {
    switch (ruleType) {
        case 'PREFIX':
            return '前缀';
        case 'SUFFIX':
            return '后缀';
        case 'REGEX':
            return '正则';
        default:
            return ruleType || '';
    }
}

function formatNamingProblem(result: ComplianceCheckResult) {
    const objectLabel = result.objectType === 'TABLE' ? '表名' : '字段名';
    return `${objectLabel} ${result.objectName || ''} 未命中任何命名规范`;
}

function formatNamingStandards(result: ComplianceCheckResult) {
    const standards = result.applicableStandards || [];
    if (standards.length === 0) return '未配置命名规范';
    return standards.map((s) => `${s.standardName}（${formatRuleTypeLabel(s.ruleType)}: ${s.ruleValue}）`).join('；');
}

function formatNamingSuggestion() {
    return '请修改名称以命中上述任一命名规范';
}

function formatTypeProblem(result: ComplianceCheckResult) {
    const standardName = result.standardName || '字段类型标准';
    return `字段 ${result.objectName || ''} 当前类型为 ${result.actualValue || '-'}，不符合 ${standardName} 要求`;
}

function formatTypeStandards(result: ComplianceCheckResult) {
    const standards = result.applicableStandards || [];
    if (standards.length === 0) return '-';
    return standards
        .map((s) => {
            const allowed = s.allowedTypes?.length ? s.allowedTypes.join(', ') : '-';
            return `${s.standardName}：允许 ${allowed}`;
        })
        .join('；');
}

function formatTypeSuggestion(result: ComplianceCheckResult) {
    return `请将字段类型改为 ${result.expectedValue || '符合标准的类型'}`;
}

function groupResults(results: ComplianceCheckResult[]) {
    const naming = results.filter((r) => r.violationType === 'NAMING' || (!r.violationType && r.isCompliant === 0 && r.objectType));
    const fieldType = results.filter((r) => r.violationType === 'FIELD_TYPE' || (!r.violationType && r.isCompliant === 0 && !naming.includes(r)));
    const namingTables = naming.filter((r) => r.objectType === 'TABLE');
    const namingColumns = naming.filter((r) => r.objectType === 'COLUMN');
    return {naming, namingTables, namingColumns, fieldType};
}

function ResultTable({items, columns, onView}: {
    items: ComplianceCheckResult[];
    columns: {
        key: string;
        label: string;
        className?: string;
        title?: (r: ComplianceCheckResult) => string;
        render: (r: ComplianceCheckResult) => ReactNode;
    }[];
    onView: (r: ComplianceCheckResult) => void;
}) {
    const tableColumns = useMemo<ColumnsType<ComplianceCheckResult>>(() => [
        ...columns.map((c) => ({
            title: c.label,
            key: c.key,
            className: c.className,
            onCell: (r: ComplianceCheckResult) => ({title: c.title?.(r)}),
            render: (_: unknown, r: ComplianceCheckResult) => c.render(r),
        })),
        {
            title: '操作',
            key: 'actions',
            align: 'center' as const,
            className: 'ds-table-cell-no-truncate',
            render: (_: unknown, r: ComplianceCheckResult) => (
                <button
                    onClick={() => onView(r)}
                    className="inline-flex items-center gap-ds-1 text-ds-small text-ds-accent hover:text-ds-accent-hover font-medium"
                >
                    <HiOutlineEye size={14}/>
                    查看
                </button>
            ),
        },
    ], [columns, onView]);

    if (items.length === 0) return null;
    return (
        <div className="ds-table-card">
            <div className="ds-table-scroll">
                <Table<ComplianceCheckResult>
                    dataSource={items}
                    rowKey="id"
                    pagination={false}
                    columns={tableColumns}
                    className="prototype-table prototype-table-flush"
                    locale={{
                        emptyText: (
                            <DsTableEmpty description="暂无不合规项"/>
                        ),
                    }}
                />
            </div>
        </div>
    );
}

export default function ComplianceCheckPanel({
                                                 params,
                                                 datasources,
                                                 results,
                                                 checkedAt,
                                                 onReCheck,
                                                 onClose
                                             }: ComplianceCheckPanelProps) {
    const navigate = useNavigate();
    const {namingTables, namingColumns, fieldType} = groupResults(results);
    const totalNonCompliant = results.filter((r) => r.isCompliant === 0).length;

    const COMPLIANCE_STATE_KEY = 'datanest:compliance-check-state';

    const saveComplianceState = () => {
        try {
            sessionStorage.setItem(COMPLIANCE_STATE_KEY, JSON.stringify({params, results, checkedAt}));
        } catch {
            // ignore
        }
    };

    const handleView = (result: ComplianceCheckResult) => {
        saveComplianceState();
        const query = new URLSearchParams();
        if (result.tableId) query.set('tableId', result.tableId);
        if (result.columnId) query.set('columnId', result.columnId);
        query.set('from', 'compliance');
        navigate(`/governance/metadata?${query.toString()}`);
    };

    return (
        <div
            className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden h-full flex flex-col">
            <div className="p-ds-4 border-b border-ds-border-subtle flex items-center justify-between flex-shrink-0">
                <div className="flex items-center gap-ds-3">
                    <button
                        onClick={onClose}
                        className="flex items-center gap-ds-1 text-ds-small text-ds-text-muted hover:text-ds-accent transition-colors"
                    >
                        <HiOutlineArrowLeft size={16}/>
                        返回命名规范
                    </button>
                    <span className="text-ds-subhead text-ds-text-primary font-semibold">合规检查结果</span>
                </div>
                <div className="flex items-center gap-ds-3">
                    <DsButton variant="secondary" onClick={onReCheck}>
                        重新检查
                    </DsButton>
                </div>
            </div>

            <div className="p-ds-4 border-b border-ds-border-subtle bg-ds-bg-root/50 flex-shrink-0">
                <div className="flex items-center gap-ds-4 text-ds-small text-ds-text-secondary flex-wrap">
                    <span>检查范围：<span
                        className="text-ds-text-primary font-medium">{formatScope(params, datasources)}</span></span>
                    <span>检查时间：<span className="text-ds-text-primary font-medium">{checkedAt || '-'}</span></span>
                    <span>不合规项：<span className="text-ds-danger font-semibold">{totalNonCompliant}</span></span>
                </div>
            </div>

            <div className="flex-1 overflow-auto p-ds-4 space-y-ds-5">
                {results.length === 0 ? (
                    <EmptyState title="暂无不合规项" description="本次检查未发现任何违规记录。"/>
                ) : (
                    <>
                        <div>
                            <h3 className="text-ds-body font-semibold text-ds-text-primary mb-ds-3 flex items-center gap-ds-2">
                                命名规范不合规
                                <span
                                    className="px-ds-2 py-ds-0.5 bg-ds-danger-light text-ds-danger text-ds-caption rounded-ds-full">
                                    {namingTables.length + namingColumns.length}
                                </span>
                            </h3>

                            {namingTables.length > 0 && (
                                <div className="mb-ds-4">
                                    <h4 className="text-ds-small font-semibold text-ds-text-secondary mb-ds-2">表名不合规</h4>
                                    <ResultTable
                                        items={namingTables}
                                        columns={[
                                            {
                                                key: 'path',
                                                label: '数据源 / 库 / 表',
                                                className: 'ds-table-cell-wide',
                                                title: formatObjectPath,
                                                render: (r) => <span
                                                    className="text-ds-text-primary font-medium">{formatObjectPath(r)}</span>
                                            },
                                            {
                                                key: 'problem',
                                                label: '问题描述',
                                                className: 'ds-table-cell-truncate',
                                                title: formatNamingProblem,
                                                render: formatNamingProblem
                                            },
                                            {
                                                key: 'standards',
                                                label: '涉及规范',
                                                className: 'ds-table-cell-wide',
                                                title: formatNamingStandards,
                                                render: formatNamingStandards
                                            },
                                            {
                                                key: 'suggestion',
                                                label: '整改建议',
                                                className: 'ds-table-cell-truncate',
                                                title: formatNamingSuggestion,
                                                render: formatNamingSuggestion
                                            },
                                        ]}
                                        onView={handleView}
                                    />
                                </div>
                            )}

                            {namingColumns.length > 0 && (
                                <div>
                                    <h4 className="text-ds-small font-semibold text-ds-text-secondary mb-ds-2">字段名不合规</h4>
                                    <ResultTable
                                        items={namingColumns}
                                        columns={[
                                            {
                                                key: 'path',
                                                label: '数据源 / 库 / 表 / 字段',
                                                className: 'ds-table-cell-wide',
                                                title: formatObjectPath,
                                                render: (r) => <span
                                                    className="text-ds-text-primary font-medium">{formatObjectPath(r)}</span>
                                            },
                                            {
                                                key: 'problem',
                                                label: '问题描述',
                                                className: 'ds-table-cell-truncate',
                                                title: formatNamingProblem,
                                                render: formatNamingProblem
                                            },
                                            {
                                                key: 'standards',
                                                label: '涉及规范',
                                                className: 'ds-table-cell-wide',
                                                title: formatNamingStandards,
                                                render: formatNamingStandards
                                            },
                                            {
                                                key: 'suggestion',
                                                label: '整改建议',
                                                className: 'ds-table-cell-truncate',
                                                title: formatNamingSuggestion,
                                                render: formatNamingSuggestion
                                            },
                                        ]}
                                        onView={handleView}
                                    />
                                </div>
                            )}

                            {namingTables.length === 0 && namingColumns.length === 0 && (
                                <p className="text-ds-small text-ds-text-muted">无命名规范违规</p>
                            )}
                        </div>

                        <div>
                            <h3 className="text-ds-body font-semibold text-ds-text-primary mb-ds-3 flex items-center gap-ds-2">
                                字段类型不合规
                                <span
                                    className="px-ds-2 py-ds-0.5 bg-ds-danger-light text-ds-danger text-ds-caption rounded-ds-full">
                                    {fieldType.length}
                                </span>
                            </h3>

                            {fieldType.length > 0 ? (
                                <ResultTable
                                    items={fieldType}
                                    columns={[
                                        {
                                            key: 'path',
                                            label: '数据源 / 库 / 表 / 字段',
                                            className: 'ds-table-cell-wide',
                                            title: formatObjectPath,
                                            render: (r) => <span
                                                className="text-ds-text-primary font-medium">{formatObjectPath(r)}</span>
                                        },
                                        {
                                            key: 'problem',
                                            label: '问题描述',
                                            className: 'ds-table-cell-truncate',
                                            title: formatTypeProblem,
                                            render: formatTypeProblem
                                        },
                                        {
                                            key: 'standards',
                                            label: '涉及规范',
                                            className: 'ds-table-cell-wide',
                                            title: formatTypeStandards,
                                            render: formatTypeStandards
                                        },
                                        {
                                            key: 'suggestion',
                                            label: '整改建议',
                                            className: 'ds-table-cell-truncate',
                                            title: formatTypeSuggestion,
                                            render: formatTypeSuggestion
                                        },
                                    ]}
                                    onView={handleView}
                                />
                            ) : (
                                <p className="text-ds-small text-ds-text-muted">无字段类型违规</p>
                            )}
                        </div>
                    </>
                )}
            </div>
        </div>
    );
}
