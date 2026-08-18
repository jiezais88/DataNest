// API 详情页（Sprint 10 F2）：概览 + 接口定义 + 自动文档（curl 复制）+ 绑定 Key。
// 调用统计图表区（趋势/明细）依赖 F3 网关统计端点，本期展示占位提示。
import {useCallback, useEffect, useState} from 'react';
import {useNavigate, useParams} from 'react-router-dom';
import {
    HiOutlineChevronLeft,
    HiOutlineClipboardDocument,
    HiOutlineCodeBracketSquare,
    HiOutlineKey,
    HiOutlinePencil,
    HiOutlinePlay,
    HiOutlineStop,
    HiOutlineTrash,
} from 'react-icons/hi2';
import {notify} from '@/utils/notify';
import {getErrorMessage} from '@/utils/error';
import {formatDateTime, formatNumber} from '@/utils/format';
import {useCan} from '@/hooks/useCan';
import {DATA_SERVICE_WRITE_PERMS} from '@/constants/permissions';
import {
    deleteDataApi,
    disableDataApi,
    getDataApi,
    publishDataApi,
} from '@/api/data-service';
import DsButton from '@/components/DsButton';
import DsSpinner from '@/components/DsSpinner';
import ConfirmDialog from '@/components/ConfirmDialog';
import {ApiKeyStatusBadge, DataApiQueryTypeBadge, DataApiStatusBadge, SensitivityBadge} from '../badges';
import {CUSTOM_SQL_PARAM_TYPE_LABEL} from '@/types/data-service';
import type {DataApiDetail, InvolvedTable} from '@/types/data-service';
import {tokenizeSql, type SqlTokenKind} from './customSql';
import ApiStatsSection from './ApiStatsSection';

export default function ApiDetailPage() {
    const {id} = useParams<{ id: string }>();
    const navigate = useNavigate();
    const canWrite = useCan(...DATA_SERVICE_WRITE_PERMS);

    const [detail, setDetail] = useState<DataApiDetail | null>(null);
    const [loading, setLoading] = useState(true);
    const [notFound, setNotFound] = useState(false);
    const [actionLoading, setActionLoading] = useState(false);
    const [deleteOpen, setDeleteOpen] = useState(false);

    const load = useCallback(() => {
        if (!id) return;
        setLoading(true);
        getDataApi(id)
            .then((res) => setDetail(res.data))
            .catch(() => setNotFound(true))
            .finally(() => setLoading(false));
    }, [id]);
    useEffect(() => {
        load();
    }, [load]);

    const handlePublish = async () => {
        if (!id) return;
        setActionLoading(true);
        try {
            await publishDataApi(id);
            notify.success('已发布，业务系统可凭绑定的 Key 调用');
            load();
        } catch {
            // 拦截器已提示
        } finally {
            setActionLoading(false);
        }
    };

    const handleDisable = async () => {
        if (!id) return;
        setActionLoading(true);
        try {
            await disableDataApi(id);
            notify.success('已下线，业务系统将无法再调用该 API');
            load();
        } catch {
            // 拦截器已提示
        } finally {
            setActionLoading(false);
        }
    };

    const handleDelete = async () => {
        if (!id || !detail) return;
        setActionLoading(true);
        try {
            await deleteDataApi(id);
            notify.success(`API「${detail.name}」已删除`);
            navigate('/data-service/api-manage');
        } catch (err) {
            notify.error(getErrorMessage(err));
            setActionLoading(false);
        }
    };

    const copyCurl = async () => {
        if (!detail) return;
        try {
            await navigator.clipboard.writeText(detail.doc.curl);
            notify.success('调用示例已复制到剪贴板');
        } catch {
            notify.warning('复制失败，请检查浏览器剪贴板权限');
        }
    };

    if (loading) {
        return <p className="text-ds-small text-ds-text-muted text-center py-ds-8"><DsSpinner size={16}/> 加载中…</p>;
    }
    if (notFound || !detail) {
        return (
            <div className="text-center py-ds-8">
                <p className="text-ds-small text-ds-text-muted mb-ds-3">API 不存在或已删除</p>
                <DsButton variant="secondary" onClick={() => navigate('/data-service/api-manage')}>返回列表</DsButton>
            </div>
        );
    }

    const qualifiedTable = `${detail.databaseName}${detail.schemaName ? `.${detail.schemaName}` : ''}.${detail.tableName}`;
    const filters = detail.definition?.filters ?? [];
    const fields = detail.definition?.fields ?? [];
    const isCustomSql = detail.queryType === 'CUSTOM_SQL';
    const involvedTables = detail.involvedTables ?? [];
    const involvedTablesLabel = involvedTables.map((t: InvolvedTable) => [t.database, t.schema, t.table].filter(Boolean).join('.') || t.table);

    return (
        <div className="flex flex-col pr-ds-4">
            {/* 页头 */}
            <div className="flex items-start justify-between mb-ds-5 flex-shrink-0">
                <div>
                    <div className="flex items-center gap-ds-3 flex-wrap">
                        <h1 className="text-ds-display text-ds-text-primary">{detail.name}</h1>
                        <DataApiQueryTypeBadge queryType={detail.queryType}/>
                        <DataApiStatusBadge status={detail.status}/>
                        <SensitivityBadge level={detail.sensitivityLevel}/>
                    </div>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1 font-mono">
                        {detail.method} {detail.path}
                    </p>
                </div>
                <div className="flex items-center gap-ds-2">
                    <DsButton variant="secondary" onClick={() => navigate('/data-service/api-manage')}>
                        <HiOutlineChevronLeft size={14}/>
                        返回列表
                    </DsButton>
                    <DsButton variant="secondary" onClick={copyCurl}>
                        <HiOutlineClipboardDocument size={14}/>
                        复制调用示例
                    </DsButton>
                    {canWrite && (
                        <>
                            <DsButton variant="secondary" onClick={() => navigate(`/data-service/api-manage/${id}/edit`)}>
                                <HiOutlinePencil size={14}/>
                                编辑
                            </DsButton>
                            {detail.status !== 'PUBLISHED' ? (
                                <DsButton onClick={handlePublish} loading={actionLoading} disabled={actionLoading}>
                                    <HiOutlinePlay size={14}/>
                                    发布
                                </DsButton>
                            ) : (
                                <DsButton variant="secondary" onClick={handleDisable} loading={actionLoading}
                                          disabled={actionLoading}>
                                    <HiOutlineStop size={14}/>
                                    下线
                                </DsButton>
                            )}
                            <DsButton variant="danger" onClick={() => setDeleteOpen(true)}>
                                <HiOutlineTrash size={14}/>
                                删除
                            </DsButton>
                        </>
                    )}
                </div>
            </div>

            {/* 基本信息 */}
            <section
                className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle p-ds-5 mb-ds-4">
                <h3 className="text-ds-small font-semibold text-ds-text-primary mb-ds-3">基本信息</h3>
                <div className="grid grid-cols-4 gap-x-ds-4 gap-y-ds-3">
                    <InfoItem label="数据源" value={detail.datasourceName || '—'}/>
                    <InfoItem label={isCustomSql ? '涉及表' : '数据表'}
                              value={isCustomSql ? (involvedTablesLabel.length ? involvedTablesLabel.join(' · ') : '—') : qualifiedTable}
                              mono/>
                    <InfoItem label="绑定 Key" value={`${detail.boundKeys.length} 个`}/>
                    <InfoItem label="近 7 天调用" value={formatNumber(detail.calls7d)} mono/>
                    <InfoItem label="创建人" value={detail.createdByName || '—'}/>
                    <InfoItem label="创建时间" value={formatDateTime(detail.createdAt)}/>
                    <InfoItem label="修改人" value={detail.updatedByName || '—'}/>
                    <InfoItem label="修改时间" value={formatDateTime(detail.updatedAt)}/>
                </div>
            </section>

            {/* 接口定义 */}
            <section
                className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle p-ds-5 mb-ds-4">
                <h3 className="text-ds-small font-semibold text-ds-text-primary mb-ds-3">接口定义</h3>
                {isCustomSql ? (
                    <div className="grid grid-cols-2 gap-ds-5">
                        <div>
                            <p className="text-ds-caption text-ds-text-muted mb-ds-2">
                                SQL 参数（{(detail.sqlParams ?? []).length}）
                            </p>
                            {(detail.sqlParams ?? []).length === 0 ? (
                                <p className="text-ds-small text-ds-text-muted">无参数，仅支持分页拉取</p>
                            ) : (
                                <div className="flex flex-col gap-ds-2">
                                    {(detail.sqlParams ?? []).map((p) => (
                                        <div key={p.name} className="flex items-center gap-ds-2 flex-wrap">
                                            <span
                                                className="text-ds-small text-ds-text-primary font-mono">:{p.name}</span>
                                            <span
                                                className="px-ds-2 py-0.5 rounded text-ds-caption font-medium bg-ds-accent-light text-ds-accent">
                                                {CUSTOM_SQL_PARAM_TYPE_LABEL[p.type] || p.type}
                                            </span>
                                            <span className="text-ds-caption text-ds-text-muted">
                                                {p.required ? '必填' : '选填'}{p.defaultValue ? ` · 默认 ${p.defaultValue}` : ''}
                                            </span>
                                        </div>
                                    ))}
                                </div>
                            )}
                            <p className="text-ds-caption text-ds-text-muted mt-ds-3 mb-ds-1">排序 / 分页</p>
                            <p className="text-ds-small text-ds-text-secondary">
                                排序以 SQL 内写的排序为准（不支持外部传参排序）
                                <span className="text-ds-text-muted"> · </span>
                                {detail.paginated === 1 ? `分页启用（pageSize 上限 ${detail.pageSizeMax ?? 100}）` : '分页关闭'}
                            </p>
                        </div>
                        <div>
                            <p className="text-ds-caption text-ds-text-muted mb-ds-2">返回列</p>
                            <p className="text-ds-small text-ds-text-muted">
                                由 SQL 查询结果决定，不提供字段裁剪（已知边界：请确保 SQL 未暴露敏感列）。
                            </p>
                        </div>
                    </div>
                ) : (
                    <div className="grid grid-cols-2 gap-ds-5">
                        <div>
                            <p className="text-ds-caption text-ds-text-muted mb-ds-2">参数化筛选（{filters.length}）</p>
                            {filters.length === 0 ? (
                                <p className="text-ds-small text-ds-text-muted">未配置，仅支持分页拉取</p>
                            ) : (
                                <div className="flex flex-col gap-ds-2">
                                    {filters.map((f) => (
                                        <div key={`${f.field}:${f.type}`} className="flex items-center gap-ds-2">
                                        <span
                                            className="text-ds-small text-ds-text-primary font-mono">{f.field}</span>
                                        <span
                                            className={`px-ds-2 py-0.5 rounded text-ds-caption font-medium ${
                                                f.type === 'RANGE'
                                                    ? 'bg-ds-warning-light text-ds-warning'
                                                    : 'bg-ds-accent-light text-ds-accent'
                                            }`}>
                                            {f.type === 'RANGE' ? `范围（min_${f.field} / max_${f.field}）` : '等值（=）'}
                                        </span>
                                        </div>
                                    ))}
                                </div>
                            )}
                            <p className="text-ds-caption text-ds-text-muted mt-ds-3 mb-ds-1">排序 / 分页</p>
                            <p className="text-ds-small text-ds-text-secondary">
                                {detail.orderBy ? <span className="font-mono">{detail.orderBy}</span> : '无排序'}
                                <span className="text-ds-text-muted"> · </span>
                                {detail.paginated === 1 ? `分页启用（pageSize 上限 ${detail.pageSizeMax ?? 100}）` : '分页关闭'}
                            </p>
                        </div>
                        <div>
                            <p className="text-ds-caption text-ds-text-muted mb-ds-2">
                                返回字段（{fields.length === 0 ? '全部字段' : `${fields.length} 个`}）
                            </p>
                            {fields.length === 0 ? (
                                <p className="text-ds-small text-ds-text-muted">未裁剪，返回表全部字段</p>
                            ) : (
                                <div className="flex flex-wrap gap-ds-2">
                                    {fields.map((f) => (
                                        <span key={f}
                                              className="px-ds-2 py-0.5 rounded bg-ds-bg-hover text-ds-small text-ds-text-secondary font-mono">
                                        {f}
                                    </span>
                                    ))}
                                </div>
                            )}
                        </div>
                    </div>
                )}
            </section>

            {/* SQL 定义（仅 CUSTOM_SQL 形态；对齐原型 view-detail） */}
            {isCustomSql && detail.sqlText && (
                <section
                    className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle p-ds-5 mb-ds-4">
                    <div className="flex items-center justify-between mb-ds-3">
                        <h3 className="text-ds-small font-semibold text-ds-text-primary flex items-center gap-ds-2">
                            <HiOutlineCodeBracketSquare size={16} className="text-ds-accent"/>
                            SQL 定义
                        </h3>
                        <span className="text-ds-caption text-ds-text-muted">只读查询 · 保存时重新校验并检查权限</span>
                    </div>
                    <div className="border border-ds-border-subtle rounded-ds-md overflow-hidden bg-[#1e1e1e]">
                        <pre className="m-0 p-ds-4 font-mono text-ds-small leading-relaxed whitespace-pre overflow-x-auto">
                            <SqlHighlight sql={detail.sqlText}/>
                        </pre>
                    </div>
                    <div className="flex flex-col gap-ds-2 mt-ds-3">
                        <div className="flex items-start gap-ds-2">
                            <span className="text-ds-caption text-ds-text-muted w-14 flex-shrink-0 pt-0.5">参数</span>
                            {(detail.sqlParams ?? []).length === 0 ? (
                                <span className="text-ds-small text-ds-text-muted">无参数</span>
                            ) : (
                                <span className="flex flex-wrap gap-ds-2">
                                    {(detail.sqlParams ?? []).map((p) => (
                                        <span key={p.name}
                                              className="inline-flex items-center gap-1 px-ds-2 py-0.5 rounded-ds-xs bg-ds-bg-root border border-ds-border-subtle font-mono text-ds-small text-ds-text-primary">
                                            :{p.name}
                                            <span className="text-ds-caption text-ds-text-muted font-sans">
                                                {CUSTOM_SQL_PARAM_TYPE_LABEL[p.type] || p.type} · {p.required ? '必填' : '选填'}
                                                {p.defaultValue ? ` · 默认 ${p.defaultValue}` : ''}
                                            </span>
                                        </span>
                                    ))}
                                </span>
                            )}
                        </div>
                        <div className="flex items-start gap-ds-2">
                            <span className="text-ds-caption text-ds-text-muted w-14 flex-shrink-0 pt-0.5">涉及表</span>
                            {involvedTablesLabel.length === 0 ? (
                                <span className="text-ds-small text-ds-text-muted">—</span>
                            ) : (
                                <span className="flex flex-wrap gap-ds-2">
                                    {involvedTablesLabel.map((t) => (
                                        <span key={t}
                                              className="px-ds-2 py-0.5 rounded-ds-xs bg-ds-bg-root border border-ds-border-subtle font-mono text-ds-small text-ds-text-secondary">
                                            {t}
                                        </span>
                                    ))}
                                    <span className="text-ds-caption text-ds-text-muted">
                                        用于权限校验与血缘
                                    </span>
                                </span>
                            )}
                        </div>
                    </div>
                </section>
            )}

            {/* 调用文档 */}
            <section
                className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle p-ds-5 mb-ds-4">
                <div className="flex items-center justify-between mb-ds-3">
                    <h3 className="text-ds-small font-semibold text-ds-text-primary">调用文档</h3>
                    <DsButton variant="secondary" onClick={copyCurl}>
                        <HiOutlineClipboardDocument size={14}/>
                        复制 curl
                    </DsButton>
                </div>
                <div className="flex flex-col gap-ds-3">
                    <p className="text-ds-small text-ds-text-secondary">
                        <span className="text-ds-text-muted">认证方式：</span>{detail.doc.auth}
                    </p>
                    {detail.doc.params.length > 0 && (
                        <div className="border border-ds-border-subtle rounded-ds-sm overflow-hidden">
                            <div
                                className="flex px-ds-3 py-ds-2 bg-ds-bg-hover text-ds-caption font-semibold text-ds-text-muted">
                                <span className="w-56">参数（query）</span>
                                <span className="flex-1">说明</span>
                            </div>
                            {detail.doc.params.map((p) => (
                                <div key={p.name}
                                     className="flex px-ds-3 py-ds-2 border-t border-ds-border-subtle">
                                    <span
                                        className="w-56 text-ds-small text-ds-text-primary font-mono">{p.name}</span>
                                    <span
                                        className="flex-1 text-ds-small text-ds-text-secondary">{p.description}</span>
                                </div>
                            ))}
                        </div>
                    )}
                    <div>
                        <p className="text-ds-caption text-ds-text-muted mb-ds-1">返回结构</p>
                        <pre
                            className="p-ds-3 bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm text-ds-small text-ds-text-primary font-mono whitespace-pre-wrap break-all">
                            {detail.doc.response}
                        </pre>
                    </div>
                    <div>
                        <p className="text-ds-caption text-ds-text-muted mb-ds-1">调用示例（经网关完整路径）</p>
                        <pre
                            className="p-ds-3 bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm text-ds-small text-ds-text-primary font-mono whitespace-pre-wrap break-all">
                            {detail.doc.curl}
                        </pre>
                    </div>
                </div>
            </section>

            {/* 绑定 Key */}
            <section
                className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle p-ds-5 mb-ds-4">
                <div className="flex items-center justify-between mb-ds-3">
                    <h3 className="text-ds-small font-semibold text-ds-text-primary">
                        绑定 Key（{detail.boundKeys.length}）
                    </h3>
                    {canWrite && (
                        <DsButton variant="secondary" onClick={() => navigate('/data-service/api-keys')}>
                            <HiOutlineKey size={14}/>
                            去 Key 管理绑定
                        </DsButton>
                    )}
                </div>
                {detail.boundKeys.length === 0 ? (
                    <p className="text-ds-small text-ds-text-muted">
                        暂无绑定 Key。业务系统需持绑定本 API 的 Key 才能调用。
                    </p>
                ) : (
                    <div className="flex flex-col gap-ds-2">
                        {detail.boundKeys.map((k) => (
                            <div key={k.id}
                                 className="flex items-center gap-ds-3 border border-ds-border-subtle rounded-ds-sm px-ds-3 py-ds-2">
                                <HiOutlineKey size={14} className="text-ds-text-muted"/>
                                <span className="text-ds-small text-ds-text-primary font-medium">{k.name}</span>
                                <ApiKeyStatusBadge status={k.status}/>
                            </div>
                        ))}
                    </div>
                )}
            </section>

            {/* 调用统计（F3：单 API 深度观测） */}
            <ApiStatsSection apiId={detail.id} />

            <ConfirmDialog
                open={deleteOpen}
                title="删除 API"
                message={(
                    <>
                        确认删除 API「{detail.name}」（{detail.path}）？
                        删除后业务系统将无法再调用该 API，相关 Key 的绑定会自动解除；历史调用统计保留。
                    </>
                )}
                confirmLabel="删除"
                danger
                loading={actionLoading}
                onConfirm={handleDelete}
                onCancel={() => setDeleteOpen(false)}
            />
        </div>
    );
}

function InfoItem({label, value, mono}: { label: string; value: string; mono?: boolean }) {
    return (
        <div>
            <p className="text-ds-caption text-ds-text-muted">{label}</p>
            <p className={`text-ds-small text-ds-text-primary mt-ds-1 break-all ${mono ? 'font-mono' : ''}`}>{value}</p>
        </div>
    );
}

/** SQL 只读高亮（详情展示用；keyword/字符串/注释/:参数 着色，对齐原型 sql-kw/sql-str/sql-cm/sql-param） */
function SqlHighlight({sql}: { sql: string }) {
    const tokens = tokenizeSql(sql);
    const colorOf = (kind: SqlTokenKind) => {
        switch (kind) {
            case 'kw': return 'text-sky-300';
            case 'str': return 'text-emerald-300';
            case 'comment': return 'text-slate-500 italic';
            case 'param': return 'text-amber-300 font-bold';
            default: return 'text-slate-200';
        }
    };
    return (
        <>
            {tokens.map((t, i) => (
                <span key={i} className={colorOf(t.kind)}>{t.text}</span>
            ))}
        </>
    );
}
