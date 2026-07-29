import {useNavigate} from 'react-router-dom';
import type {ComplianceCheckParams, ComplianceCheckResult} from '../../../types/dataStandard';
import type {MetadataDatasource} from '../../../types/metadata';
import EmptyState from '../../../components/EmptyState';
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

function groupResults(results: ComplianceCheckResult[]) {
    const naming = results.filter((r) => r.violationType === 'NAMING' || (!r.violationType && r.isCompliant === 0 && r.objectType));
    const fieldType = results.filter((r) => r.violationType === 'FIELD_TYPE' || (!r.violationType && r.isCompliant === 0 && !naming.includes(r)));
    const namingTables = naming.filter((r) => r.objectType === 'TABLE');
    const namingColumns = naming.filter((r) => r.objectType === 'COLUMN');
    return {naming, namingTables, namingColumns, fieldType};
}

function ResultTable({items, columns, onView}: {
    items: ComplianceCheckResult[];
    columns: { key: string; label: string; render: (r: ComplianceCheckResult) => React.ReactNode }[];
    onView: (r: ComplianceCheckResult) => void;
}) {
    if (items.length === 0) return null;
    return (
        <div className="border border-ds-border-subtle rounded-ds-md overflow-auto">
            <table className="w-full text-left">
                <thead className="bg-ds-bg-hover/80 sticky top-0">
                <tr className="border-b border-ds-border-subtle">
                    {columns.map((c) => (
                        <th key={c.key}
                            className="px-ds-3 py-ds-2 text-ds-caption text-ds-text-secondary font-semibold">
                            {c.label}
                        </th>
                    ))}
                    <th className="px-ds-3 py-ds-2 text-ds-caption text-ds-text-secondary font-semibold text-right">操作</th>
                </tr>
                </thead>
                <tbody>
                {items.map((r) => (
                    <tr key={r.id} className="border-b border-ds-border-subtle last:border-0">
                        {columns.map((c) => (
                            <td key={c.key} className="px-ds-3 py-ds-2 text-ds-small text-ds-text-secondary">
                                {c.render(r)}
                            </td>
                        ))}
                        <td className="px-ds-3 py-ds-2 text-right">
                            <button
                                onClick={() => onView(r)}
                                className="inline-flex items-center gap-ds-1 text-ds-small text-ds-accent hover:text-ds-accent-hover font-medium"
                            >
                                <HiOutlineEye size={14}/>
                                查看
                            </button>
                        </td>
                    </tr>
                ))}
                </tbody>
            </table>
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

    const handleView = (result: ComplianceCheckResult) => {
        const query = new URLSearchParams();
        if (result.tableId) query.set('tableId', result.tableId);
        if (result.columnId) query.set('columnId', result.columnId);
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
                    <button
                        onClick={onReCheck}
                        className="px-ds-4 py-ds-2 bg-white border border-ds-border-subtle hover:border-ds-accent text-ds-accent text-ds-small font-semibold rounded-ds-sm transition-colors"
                    >
                        重新检查
                    </button>
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
                                                render: (r) => <span
                                                    className="text-ds-text-primary font-medium">{formatObjectPath(r)}</span>
                                            },
                                            {key: 'standard', label: '违反规范', render: (r) => r.standardName || '-'},
                                            {key: 'expected', label: '规范要求', render: (r) => r.expectedValue || '-'},
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
                                                render: (r) => <span
                                                    className="text-ds-text-primary font-medium">{formatObjectPath(r)}</span>
                                            },
                                            {key: 'standard', label: '违反规范', render: (r) => r.standardName || '-'},
                                            {key: 'expected', label: '规范要求', render: (r) => r.expectedValue || '-'},
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
                                            render: (r) => <span
                                                className="text-ds-text-primary font-medium">{formatObjectPath(r)}</span>
                                        },
                                        {key: 'actual', label: '当前类型', render: (r) => r.actualValue || '-'},
                                        {key: 'expected', label: '标准类型', render: (r) => r.expectedValue || '-'},
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
