// Sprint 10 F5：数据分级分类页（/data-service/classification）。
// 数据表敏感度分级（公开/内部/机密）：敏感度+数据源筛选 + 关键词搜索 + 批量打标 + 单表改级 + 内部表 API 特批开放（超管）+ 审计记录。
// 数据来源：governance /metadata/sensitivity/**（改级/批量/特批/审计/列表），四角色里仅治理员/超管可写。
import {useCallback, useEffect, useMemo, useState} from 'react';
import {Modal, Select, Table} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import {HiOutlineDocumentText, HiOutlineLockClosed, HiOutlineShieldCheck} from 'react-icons/hi2';
import usePagedList from '@/hooks/usePagedList';
import {useHasRole} from '@/hooks/useHasRole';
import {useCan} from '@/hooks/useCan';
import {GOVERNANCE_WRITE_PERMS} from '@/constants/permissions';
import {ROLE} from '@/constants/roles';
import {COL} from '@/constants/table';
import {formatDateTime} from '@/utils/format';
import {notify} from '@/utils/notify';
import {getErrorMessage} from '@/utils/error';
import {
    batchUpdateTableSensitivity,
    listMetadataDatasourceIds,
    pageSensitivityAudit,
    pageSensitivityTables,
    updateTableApiExempt,
    updateTableSensitivity,
} from '@/api/metadata';
import DsButton from '@/components/DsButton';
import DsFilterSelect from '@/components/DsFilterSelect';
import DsModal from '@/components/DsModal';
import DsTableEmpty from '@/components/DsTableEmpty';
import DsToolbar from '@/components/DsToolbar';
import SearchInput from '@/components/SearchInput';
import Pagination from '@/components/Pagination';
import {SensitivityBadge} from '../badges';
import type {SensitivityAuditItem, SensitivityTableItem, MetadataDatasource} from '@/types/metadata';

const LEVEL_OPTIONS = [
    {value: '', label: '全部敏感度'},
    {value: 'PUBLIC', label: '公开'},
    {value: 'INTERNAL', label: '内部'},
    {value: 'CONFIDENTIAL', label: '机密'},
];

/** 任务来源类型 → 文案（手工录入为 null） */
const TASK_SOURCE_LABEL: Record<string, string> = {
    COLLECT: '采集任务',
    SYNC: '同步任务',
    SQL: 'SQL 任务',
    PYTHON: 'Python 任务',
};

const sourceLabel = (t?: string) => (t ? (TASK_SOURCE_LABEL[t] ?? t) : '手工录入');

/** API 特批列展示 */
function ExemptCell({item}: { item: SensitivityTableItem }) {
    if (item.sensitivityLevel === 'CONFIDENTIAL') {
        return <span className="text-ds-text-muted">禁止</span>;
    }
    if (item.sensitivityLevel === 'INTERNAL') {
        return item.apiExempted === 1
            ? <span className="text-ds-success font-medium">已特批</span>
            : <span className="text-ds-text-muted">未特批</span>;
    }
    return <span className="text-ds-text-muted">—</span>;
}

interface ClassificationQuery {
    sensitivityLevel: string;
    keyword: string;
    datasourceId: string;
}

const INITIAL_QUERY: ClassificationQuery = {sensitivityLevel: '', keyword: '', datasourceId: ''};

export default function ClassificationPage() {
    const canWrite = useCan(...GOVERNANCE_WRITE_PERMS);
    const isSuperAdmin = useHasRole(ROLE.SUPER_ADMIN);

    // ============ 列表（分页 + 筛选） ============
    const [draft, setDraft] = useState<ClassificationQuery>(INITIAL_QUERY);
    const {list, total, page, pageSize, loading, query, setPage, setPageSize, applyQuery, reload} =
        usePagedList<ClassificationQuery, SensitivityTableItem>({
            fetcher: async (q) => {
                const res = await pageSensitivityTables({
                    page: q.page,
                    pageSize: q.pageSize,
                    sensitivityLevel: q.sensitivityLevel || undefined,
                    keyword: q.keyword || undefined,
                    datasourceId: q.datasourceId || undefined,
                });
                return {list: res.data.records, total: Number(res.data.total ?? 0)};
            },
            initialQuery: INITIAL_QUERY,
            defaultPageSize: 10,
        });

    // ============ 数据源下拉 ============
    const [datasources, setDatasources] = useState<MetadataDatasource[]>([]);
    useEffect(() => {
        listMetadataDatasourceIds()
            .then((res) => setDatasources((res.data ?? []).filter(d => d.exists)))
            .catch(() => setDatasources([]));
    }, []);
    const datasourceOptions = [
        {value: '', label: '全部数据源'},
        ...datasources.map(d => ({value: d.id, label: d.name || d.id})),
    ];

    // ============ 批量勾选 ============
    const [selectedIds, setSelectedIds] = useState<string[]>([]);
    const [batchLoading, setBatchLoading] = useState(false);

    const doBatchSetLevel = async (newLevel: string) => {
        if (selectedIds.length === 0) return;
        setBatchLoading(true);
        try {
            const res = await batchUpdateTableSensitivity(selectedIds, newLevel);
            const label = LEVEL_OPTIONS.find(o => o.value === newLevel)?.label ?? newLevel;
            const disabled = res.data ?? 0;
            const extra = disabled > 0 ? `，已自动下线 ${disabled} 个 API` : '';
            notify.success(`已将 ${selectedIds.length} 张表设为「${label}」${extra}`);
            setSelectedIds([]);
            reload();
        } catch (err) {
            notify.error(getErrorMessage(err));
        } finally {
            setBatchLoading(false);
        }
    };

    /** 批量改级入口：选中项中任一张为降级（机密→内部/公开、内部→公开）则先弹确认框，纯升级直接执行 */
    const batchSetLevel = (newLevel: string) => {
        if (selectedIds.length === 0) return;
        const rank: Record<string, number> = {PUBLIC: 1, INTERNAL: 2, CONFIDENTIAL: 3};
        const newRank = rank[newLevel] ?? 1;
        const downgraded = list.filter(
            it => selectedIds.includes(it.tableId) && (rank[it.sensitivityLevel ?? 'PUBLIC'] ?? 1) > newRank);
        if (downgraded.length === 0) {
            void doBatchSetLevel(newLevel);
            return;
        }
        const newLabel = LEVEL_OPTIONS.find(o => o.value === newLevel)?.label ?? newLevel;
        const consequence = newLevel === 'PUBLIC'
            ? '降级后这些表所有用户均可在 SQL 终端查询，且可生成对外 API。'
            : '降级后这些表有查询权限的用户可在 SQL 终端查询。';
        Modal.confirm({
            centered: true,
            wrapClassName: 'prototype-modal',
            title: '确认批量降级',
            content: `选中的 ${selectedIds.length} 张表中有 ${downgraded.length} 张将降为「${newLabel}」。${consequence}`,
            okText: '确认降级',
            cancelText: '取消',
            onOk: () => doBatchSetLevel(newLevel),
        });
    };

    // ============ 单表改级 + 特批开放 ============
    const [actionId, setActionId] = useState<string | null>(null);

    const doChangeLevel = async (item: SensitivityTableItem, newLevel: string) => {
        setActionId(item.tableId);
        try {
            const res = await updateTableSensitivity(item.tableId, newLevel);
            const label = LEVEL_OPTIONS.find(o => o.value === newLevel)?.label ?? newLevel;
            const disabled = res.data ?? 0;
            const extra = disabled > 0 ? `，已自动下线 ${disabled} 个 API` : '';
            notify.success(`「${item.tableName}」已设为「${label}」${extra}`);
            reload();
        } catch (err) {
            notify.error(getErrorMessage(err));
        } finally {
            setActionId(null);
        }
    };

    /** 改级入口：降级（机密→内部/公开、内部→公开）先弹确认框说明后果，升级直接执行 */
    const changeLevel = (item: SensitivityTableItem, newLevel: string) => {
        const rank: Record<string, number> = {PUBLIC: 1, INTERNAL: 2, CONFIDENTIAL: 3};
        const oldRank = rank[item.sensitivityLevel ?? 'PUBLIC'] ?? 1;
        const newRank = rank[newLevel] ?? 1;
        if (newRank >= oldRank) {
            void doChangeLevel(item, newLevel);
            return;
        }
        const oldLabel = LEVEL_OPTIONS.find(o => o.value === item.sensitivityLevel)?.label ?? item.sensitivityLevel;
        const newLabel = LEVEL_OPTIONS.find(o => o.value === newLevel)?.label ?? newLevel;
        const consequence = newLevel === 'PUBLIC'
            ? '降级后所有用户均可在 SQL 终端查询此表，且可生成对外 API。'
            : '降级后有查询权限的用户可在 SQL 终端查询此表。';
        Modal.confirm({
            centered: true,
            wrapClassName: 'prototype-modal',
            title: '确认降级',
            content: `「${item.tableName}」将从「${oldLabel}」降为「${newLabel}」。${consequence}`,
            okText: '确认降级',
            cancelText: '取消',
            onOk: () => doChangeLevel(item, newLevel),
        });
    };

    const toggleExempt = async (item: SensitivityTableItem) => {
        const next = item.apiExempted === 1 ? 0 : 1;
        setActionId(item.tableId);
        try {
            await updateTableApiExempt(item.tableId, next);
            notify.success(next === 1 ? `「${item.tableName}」已特批开放` : `「${item.tableName}」已取消特批`);
            reload();
        } catch (err) {
            notify.error(getErrorMessage(err));
        } finally {
            setActionId(null);
        }
    };

    // ============ 审计弹窗 ============
    const [auditOpen, setAuditOpen] = useState(false);
    const [auditList, setAuditList] = useState<SensitivityAuditItem[]>([]);
    const [auditTotal, setAuditTotal] = useState(0);
    const [auditPage, setAuditPage] = useState(1);
    const [auditLoading, setAuditLoading] = useState(false);
    const auditPageSize = 10;

    const loadAudit = useCallback((p: number) => {
        setAuditLoading(true);
        pageSensitivityAudit(p, auditPageSize)
            .then((res) => {
                setAuditList(res.data.records ?? []);
                setAuditTotal(Number(res.data.total ?? 0));
            })
            .catch(() => {/* 拦截器已提示 */})
            .finally(() => setAuditLoading(false));
    }, []);

    const openAudit = () => {
        setAuditOpen(true);
        setAuditPage(1);
        loadAudit(1);
    };

    // ============ 列 ============
    const columns = useMemo<ColumnsType<SensitivityTableItem>>(() => [
        {
            title: '敏感度',
            dataIndex: 'sensitivityLevel',
            width: COL.STATUS,
            render: (v: string) => <SensitivityBadge level={v}/>,
        },
        {
            title: '表名',
            dataIndex: 'tableName',
            width: COL.NAME,
            ellipsis: true,
            render: (v: string) => <span className="font-mono text-ds-small text-ds-text-primary">{v}</span>,
        },
        {
            title: '库 / Schema',
            key: 'location',
            width: 160,
            ellipsis: true,
            render: (_, item) => (
                <span className="font-mono text-ds-tiny text-ds-text-secondary">
                    {item.databaseName}{item.schemaName && item.schemaName !== item.databaseName ? `.${item.schemaName}` : ''}
                </span>
            ),
        },
        {
            title: '数据源',
            dataIndex: 'datasourceName',
            width: 140,
            ellipsis: true,
            render: (v?: string) => v ?? '—',
        },
        {
            title: '来源',
            dataIndex: 'taskSourceType',
            width: 110,
            render: (v?: string) => <span className="text-ds-text-muted">{sourceLabel(v)}</span>,
        },
        {
            title: 'API 特批',
            dataIndex: 'apiExempted',
            width: 90,
            render: (_, item) => <ExemptCell item={item}/>,
        },
        {
            title: '创建人',
            dataIndex: 'createdByName',
            width: 100,
            ellipsis: true,
            render: (v?: string) => v ?? '—',
        },
        {
            title: '创建时间',
            dataIndex: 'createdAt',
            width: 160,
            render: (v?: string) => <span className="text-ds-tiny text-ds-text-muted">{formatDateTime(v)}</span>,
        },
        {
            title: '修改人',
            dataIndex: 'updatedByName',
            width: 100,
            ellipsis: true,
            render: (v?: string) => v ?? '—',
        },
        {
            title: '修改时间',
            dataIndex: 'updatedAt',
            width: 160,
            render: (v?: string) => <span className="text-ds-tiny text-ds-text-muted">{formatDateTime(v)}</span>,
        },
        {
            title: '操作',
            key: 'actions',
            width: 170,
            fixed: 'right',
            render: (_, item) => (
                <div className="flex items-center gap-ds-2">
                    <Select
                        size="small"
                        value={item.sensitivityLevel}
                        disabled={!canWrite || actionId === item.tableId}
                        onChange={(v) => changeLevel(item, v)}
                        style={{width: 96}}
                        options={[
                            {value: 'PUBLIC', label: '公开'},
                            {value: 'INTERNAL', label: '内部'},
                            {value: 'CONFIDENTIAL', label: '机密'},
                        ]}
                    />
                    {isSuperAdmin && item.sensitivityLevel === 'INTERNAL' && (
                        <DsButton
                            variant="secondary"
                            className="!px-ds-2 !py-ds-1 text-ds-tiny"
                            loading={actionId === item.tableId}
                            onClick={() => toggleExempt(item)}
                        >
                            {item.apiExempted === 1 ? '取消特批' : '特批开放'}
                        </DsButton>
                    )}
                </div>
            ),
        },
    ], [canWrite, isSuperAdmin, actionId]);

    const auditColumns: ColumnsType<SensitivityAuditItem> = [
        {title: '表名', dataIndex: 'tableName', ellipsis: true, render: (v: string) => <span className="font-mono text-ds-small">{v}</span>},
        {
            title: '变更',
            key: 'change',
            width: 160,
            render: (_, item) => (
                <span className="text-ds-small text-ds-text-secondary">
                    {item.action === 'API_EXEMPT'
                        ? (item.remark ?? '特批开放')
                        : `${item.oldLevel ? LEVEL_OPTIONS.find(o => o.value === item.oldLevel)?.label ?? item.oldLevel : '—'} → ${LEVEL_OPTIONS.find(o => o.value === item.newLevel)?.label ?? item.newLevel}`}
                </span>
            ),
        },
        {title: '操作人', dataIndex: 'operatorName', width: 100, render: (v?: string) => v ?? '—'},
        {
            title: '操作时间',
            dataIndex: 'createdAt',
            width: 160,
            render: (v?: string) => <span className="text-ds-tiny text-ds-text-muted">{formatDateTime(v)}</span>,
        },
    ];

    return (
        <div className="flex flex-col">
            <div className="flex items-center justify-between mb-ds-5 flex-shrink-0">
                <div>
                    <h1 className="text-ds-display text-ds-text-primary flex items-center gap-ds-2">
                        <HiOutlineShieldCheck size={24} className="text-ds-accent"/>
                        数据分级分类
                    </h1>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1">
                        数据表敏感度分级：公开 / 内部 / 机密。机密表 SQL 终端默认隐藏、不可生成对外 API；内部表默认禁止生成 API（超级管理员可特批开放）。分级变更留审计。
                    </p>
                </div>
                <div className="flex items-center gap-ds-2">
                    <DsButton variant="secondary" onClick={openAudit}>
                        <HiOutlineDocumentText size={14}/>
                        审计记录
                    </DsButton>
                </div>
            </div>

            <div className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden flex flex-col">
                <div className="p-ds-3 border-b border-ds-border-subtle flex-shrink-0">
                    <DsToolbar
                        extra={(
                            <>
                                <DsButton
                                    variant="secondary"
                                    disabled={selectedIds.length === 0 || !canWrite}
                                    loading={batchLoading}
                                    onClick={() => batchSetLevel('CONFIDENTIAL')}
                                >
                                    <HiOutlineLockClosed size={14}/>
                                    设为机密
                                </DsButton>
                                <DsButton
                                    variant="secondary"
                                    disabled={selectedIds.length === 0 || !canWrite}
                                    loading={batchLoading}
                                    onClick={() => batchSetLevel('INTERNAL')}
                                >
                                    设为内部
                                </DsButton>
                                <DsButton
                                    disabled={selectedIds.length === 0 || !canWrite}
                                    loading={batchLoading}
                                    onClick={() => batchSetLevel('PUBLIC')}
                                >
                                    设为公开
                                </DsButton>
                                <DsButton variant="secondary" onClick={() => {
                                    setDraft(INITIAL_QUERY);
                                    applyQuery(INITIAL_QUERY);
                                    setSelectedIds([]);
                                }}>
                                    重置
                                </DsButton>
                            </>
                        )}
                    >
                        <SearchInput
                            value={draft.keyword}
                            onChange={(e) => setDraft({...draft, keyword: e.target.value})}
                            onEnter={() => applyQuery({...query, keyword: draft.keyword})}
                            placeholder="搜索库名 / 表名"
                        />
                        <DsFilterSelect
                            value={draft.sensitivityLevel}
                            onChange={(v) => setDraft({...draft, sensitivityLevel: v})}
                            aria-label="按敏感度筛选"
                            options={LEVEL_OPTIONS}
                        />
                        <DsFilterSelect
                            value={draft.datasourceId}
                            onChange={(v) => setDraft({...draft, datasourceId: v})}
                            aria-label="按数据源筛选"
                            options={datasourceOptions}
                        />
                        <DsButton onClick={() => applyQuery({...query, keyword: draft.keyword, sensitivityLevel: draft.sensitivityLevel, datasourceId: draft.datasourceId})}
                                  disabled={loading} loading={loading}>
                            查询
                        </DsButton>
                    </DsToolbar>
                    {selectedIds.length > 0 && (
                        <div className="mt-ds-2 text-ds-tiny text-ds-text-muted">
                            已选 {selectedIds.length} 张表 · 可用上方「设为机密/内部/公开」批量打标
                        </div>
                    )}
                </div>

                <div className="overflow-x-auto">
                    <Table<SensitivityTableItem>
                        dataSource={list}
                        rowKey="tableId"
                        loading={loading}
                        pagination={false}
                        scroll={{x: 1500}}
                        rowSelection={canWrite ? {
                            selectedRowKeys: selectedIds,
                            onChange: (keys) => setSelectedIds(keys as string[]),
                        } : undefined}
                        columns={columns}
                        className="prototype-table prototype-table-flush"
                        locale={{
                            emptyText: (
                                <DsTableEmpty description="暂无匹配的数据表。元数据采集后可在本页对表进行敏感度分级。" />
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

            {/* 分级策略说明 */}
            <div className="mt-ds-4 flex-shrink-0 bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm px-ds-4 py-ds-3 text-ds-tiny text-ds-text-muted leading-relaxed">
                <span className="font-semibold text-ds-text-secondary">分级策略：</span>
                <b className="text-ds-success">公开</b>＝SQL 终端可查、可生成对外 API；
                <b className="text-ds-warning">内部</b>＝SQL 终端可查、生成 API 需超级管理员特批开放（本页「API 特批」列）；
                <b className="text-ds-danger">机密</b>＝SQL 终端默认隐藏、查询会被拒绝，禁止生成对外 API、禁止 WebSocket 订阅。
                降级操作需确认。改级操作写入审计日志（谁/何时/从哪级到哪级）。
            </div>

            {/* 审计弹窗 */}
            <DsModal
                open={auditOpen}
                onClose={() => setAuditOpen(false)}
                title="分级变更审计"
                width="max-w-[640px]"
                footer={<DsButton onClick={() => setAuditOpen(false)}>关闭</DsButton>}
            >
                <Table<SensitivityAuditItem>
                    dataSource={auditList}
                    rowKey="id"
                    loading={auditLoading}
                    pagination={false}
                    size="small"
                    columns={auditColumns}
                    locale={{emptyText: '暂无分级变更记录'}}
                />
                <Pagination
                    page={auditPage}
                    pageSize={auditPageSize}
                    total={auditTotal}
                    onChange={(p) => {
                        setAuditPage(p);
                        loadAudit(p);
                    }}
                />
            </DsModal>
        </div>
    );
}
