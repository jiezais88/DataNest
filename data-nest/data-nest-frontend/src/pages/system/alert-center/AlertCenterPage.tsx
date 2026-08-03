// Sprint 5：全局告警中心（系统管理）
// Tab：告警规则 / 告警历史
// 权限：查看 = 超管/工程师/治理员；编辑 = 超管/工程师（PRD §8）
import {useCallback, useEffect, useMemo, useState} from 'react';
import {Table, Tooltip} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import {useHasRole} from '../../../hooks/useHasRole';
import {ALERT_WRITE_ROLES} from '../../../constants/roles';
import {COL} from '../../../constants/table';
import {deleteAlertRule, getAlertHistory, getAlertRules, getUsersWithEmail, toggleAlertRule,} from '../../../api/alert';
import type {
    AlertHistory,
    AlertObjectType,
    AlertRuleDTO,
    AlertSendStatus,
    AlertTriggerType,
} from '../../../types/alert';
import {formatDateTime} from '../../../utils/format';
import {notify} from '../../../utils/notify';
import usePagedList from '../../../hooks/usePagedList';
import Pagination from '../../../components/Pagination';
import DsButton from '../../../components/DsButton';
import DsIconButton from '../../../components/DsIconButton';
import DsStatusBadge from '../../../components/DsStatusBadge';
import SearchInput from '../../../components/SearchInput';
import DsFilterSelect from '../../../components/DsFilterSelect';
import DsToolbar from '../../../components/DsToolbar';
import DsTableEmpty from '../../../components/DsTableEmpty';
import DsModal from '../../../components/DsModal';
import ConfirmDialog from '../../../components/ConfirmDialog';
import AlertRuleModal from '../../../components/AlertRuleModal';
import {HiOutlineBell, HiOutlineEye, HiOutlinePencilSquare, HiOutlinePlus, HiOutlineTrash,} from 'react-icons/hi2';

const OBJECT_TYPE_OPTIONS: { value: AlertObjectType | ''; label: string }[] = [
    {value: '', label: '全部类型'},
    {value: 'DAG', label: 'DAG'},
    {value: 'SYNC_JOB', label: '同步任务'},
    {value: 'COLLECT_TASK', label: '采集任务'},
];

const ALERT_TYPE_OPTIONS: { value: AlertTriggerType | ''; label: string }[] = [
    {value: '', label: '全部告警类型'},
    {value: 'FAILURE', label: '失败'},
    {value: 'TIMEOUT', label: '超时'},
    {value: 'SUCCESS', label: '成功'},
];

const SEND_STATUS_OPTIONS: { value: AlertSendStatus | ''; label: string }[] = [
    {value: '', label: '全部发送状态'},
    {value: 'SUCCESS', label: '发送成功'},
    {value: 'FAILED', label: '发送失败'},
];

const TRIGGER_LABEL: Record<AlertTriggerType, string> = {
    FAILURE: '失败',
    TIMEOUT: '超时',
    SUCCESS: '成功',
};

function objectTypeBadge(type: AlertObjectType) {
    const variant = type === 'SYNC_JOB' ? 'accent' : type === 'COLLECT_TASK' ? 'success' : 'running';
    const label = type === 'SYNC_JOB' ? '同步任务' : type === 'COLLECT_TASK' ? '采集任务' : 'DAG';
    return <DsStatusBadge variant={variant} label={label}/>;
}

function triggerBadges(conditions: AlertTriggerType[]) {
    return (
        <div className="flex flex-wrap gap-1">
            {(conditions || []).map(c => (
                <span
                    key={c}
                    className={`inline-flex items-center px-2 py-0.5 rounded-full text-[11px] font-semibold ${
                        c === 'FAILURE'
                            ? 'bg-ds-danger-light text-ds-danger'
                            : c === 'TIMEOUT'
                                ? 'bg-ds-warning-light text-ds-warning'
                                : 'bg-ds-success-light text-ds-success'
                    }`}
                >
                    {TRIGGER_LABEL[c] || c}
                </span>
            ))}
        </div>
    );
}

interface RuleListQuery {
    keyword: string;
    objectType: AlertObjectType | '';
}

const INITIAL_RULE_QUERY: RuleListQuery = {keyword: '', objectType: ''};

interface HistoryListQuery {
    objectType: AlertObjectType | '';
    alertType: AlertTriggerType | '';
    sendStatus: AlertSendStatus | '';
}

const INITIAL_HISTORY_QUERY: HistoryListQuery = {objectType: '', alertType: '', sendStatus: ''};

export default function AlertCenterPage() {
    const canWrite = useHasRole(...ALERT_WRITE_ROLES);

    // ==================== Tab ====================
    const [activeTab, setActiveTab] = useState<'rules' | 'history'>('rules');

    // ==================== 告警规则 ====================
    const [draftKeyword, setDraftKeyword] = useState('');
    const [draftObjectType, setDraftObjectType] = useState<AlertObjectType | ''>('');

    const {
        list: rules,
        total: rulesTotal,
        page: rulesPage,
        pageSize: rulesPageSize,
        loading: rulesLoading,
        setPage: setRulesPage,
        setPageSize: setRulesPageSize,
        applyQuery: applyRuleQuery,
        reload: reloadRules,
    } = usePagedList<RuleListQuery, AlertRuleDTO>({
        fetcher: async (query) => {
            const result = await getAlertRules({
                page: query.page,
                pageSize: query.pageSize,
                objectType: query.objectType || undefined,
                keyword: query.keyword || undefined,
            });
            return {list: result.records, total: result.total};
        },
        initialQuery: INITIAL_RULE_QUERY,
        defaultPageSize: 10,
    });

    // ==================== 告警历史 ====================
    const [draftHistoryType, setDraftHistoryType] = useState<AlertObjectType | ''>('');
    const [draftAlertType, setDraftAlertType] = useState<AlertTriggerType | ''>('');
    const [draftSendStatus, setDraftSendStatus] = useState<AlertSendStatus | ''>('');

    const {
        list: history,
        total: historyTotal,
        page: historyPage,
        pageSize: historyPageSize,
        loading: historyLoading,
        setPage: setHistoryPage,
        setPageSize: setHistoryPageSize,
        applyQuery: applyHistoryQuery,
    } = usePagedList<HistoryListQuery, AlertHistory>({
        fetcher: async (query) => {
            const result = await getAlertHistory({
                page: query.page,
                pageSize: query.pageSize,
                objectType: query.objectType || undefined,
                alertType: query.alertType || undefined,
                sendStatus: query.sendStatus || undefined,
            });
            return {list: result.records, total: result.total};
        },
        initialQuery: INITIAL_HISTORY_QUERY,
        defaultPageSize: 10,
    });

    // 接收用户显示：加载全部有邮箱用户建 id→username 映射
    const [userMap, setUserMap] = useState<Map<string, string>>(new Map());
    useEffect(() => {
        let cancelled = false;
        getUsersWithEmail(undefined)
            .then(list => {
                if (cancelled) return;
                const map = new Map<string, string>();
                for (const u of list || []) {
                    if (u.id) map.set(u.id, u.username);
                }
                setUserMap(map);
            })
            .catch(() => {/* 静默 */
            });
        return () => {
            cancelled = true;
        };
    }, []);

    // ==================== 弹窗状态 ====================
    const [modalOpen, setModalOpen] = useState(false);
    const [modalMode, setModalMode] = useState<'create' | 'edit'>('create');
    const [editRule, setEditRule] = useState<AlertRuleDTO | null>(null);

    const [detailHistory, setDetailHistory] = useState<AlertHistory | null>(null);

    const [deleteTarget, setDeleteTarget] = useState<AlertRuleDTO | null>(null);
    const [deleteOpen, setDeleteOpen] = useState(false);
    const [deleteLoading, setDeleteLoading] = useState(false);
    const [togglingId, setTogglingId] = useState<string | null>(null);

    const openCreate = () => {
        setEditRule(null);
        setModalMode('create');
        setModalOpen(true);
    };

    const openEdit = (rule: AlertRuleDTO) => {
        setEditRule(rule);
        setModalMode('edit');
        setModalOpen(true);
    };

    const handleToggle = useCallback(async (rule: AlertRuleDTO) => {
        if (!rule.id) return;
        setTogglingId(rule.id);
        try {
            await toggleAlertRule(rule.id, !rule.enabled);
            notify.success(rule.enabled ? '告警规则已停用' : '告警规则已启用');
            reloadRules();
        } finally {
            setTogglingId(null);
        }
    }, [reloadRules]);

    const handleDelete = async () => {
        if (!deleteTarget?.id) return;
        setDeleteLoading(true);
        try {
            await deleteAlertRule(deleteTarget.id);
            notify.success('告警规则已删除');
            setDeleteOpen(false);
            setDeleteTarget(null);
            reloadRules();
        } finally {
            setDeleteLoading(false);
        }
    };

    const handleRuleSearch = () => {
        applyRuleQuery({keyword: draftKeyword, objectType: draftObjectType});
    };

    const handleRuleReset = () => {
        setDraftKeyword('');
        setDraftObjectType('');
        applyRuleQuery(INITIAL_RULE_QUERY);
    };

    const handleHistorySearch = () => {
        applyHistoryQuery({objectType: draftHistoryType, alertType: draftAlertType, sendStatus: draftSendStatus});
    };

    const handleHistoryReset = () => {
        setDraftHistoryType('');
        setDraftAlertType('');
        setDraftSendStatus('');
        applyHistoryQuery(INITIAL_HISTORY_QUERY);
    };

    const handleRulePageChange = (nextPage: number, nextPageSize: number) => {
        if (nextPageSize !== rulesPageSize) {
            setRulesPageSize(nextPageSize);
        } else {
            setRulesPage(nextPage);
        }
    };

    const handleHistoryPageChange = (nextPage: number, nextPageSize: number) => {
        if (nextPageSize !== historyPageSize) {
            setHistoryPageSize(nextPageSize);
        } else {
            setHistoryPage(nextPage);
        }
    };

    // ==================== 列定义 ====================
    const ruleColumns = useMemo<ColumnsType<AlertRuleDTO>>(() => [
        {
            title: '对象类型',
            dataIndex: 'objectType',
            width: COL.STATUS,
            render: (v: AlertObjectType) => objectTypeBadge(v),
        },
        {
            title: '对象名称',
            dataIndex: 'objectName',
            width: COL.NAME,
            ellipsis: true,
            render: (v?: string) => (
                <span className="text-ds-small text-ds-text-primary font-medium" title={v || '-'}>{v || '-'}</span>
            ),
        },
        {
            title: '触发条件',
            dataIndex: 'triggerConditions',
            width: 160,
            render: (v: AlertTriggerType[]) => triggerBadges(v || []),
        },
        {
            title: '接收用户',
            dataIndex: 'userIds',
            width: 200,
            ellipsis: true,
            render: (v?: string[]) => {
                if (!v || v.length === 0) return <span className="text-ds-small text-ds-text-muted">—</span>;
                const names = v.map(id => userMap.get(id) || id);
                return (
                    <span className="text-ds-small text-ds-text-secondary" title={names.join('、')}>
                        {names.join('、')}
                    </span>
                );
            },
        },
        {
            title: '状态',
            dataIndex: 'enabled',
            width: COL.STATUS,
            render: (v: boolean) => (
                v
                    ? <DsStatusBadge variant="success" label="启用"/>
                    : <DsStatusBadge variant="disabled" label="停用"/>
            ),
        },
        {
            title: '操作',
            align: 'center',
            width: COL.OPERATION_3,
            render: (_, rule) => (
                <div className="flex items-center justify-center gap-1 whitespace-nowrap">
                    <Tooltip title="编辑">
                        <DsIconButton tone="accent" onClick={() => openEdit(rule)} disabled={!canWrite}
                                      aria-label="编辑">
                            <HiOutlinePencilSquare size={14}/>
                        </DsIconButton>
                    </Tooltip>
                    <Tooltip title={rule.enabled ? '停用' : '启用'}>
                        <DsIconButton
                            tone={rule.enabled ? 'danger' : 'success'}
                            onClick={() => handleToggle(rule)}
                            disabled={!canWrite || togglingId === rule.id}
                            aria-label={rule.enabled ? '停用' : '启用'}
                        >
                            <HiOutlineBell size={14}/>
                        </DsIconButton>
                    </Tooltip>
                    <Tooltip title="删除">
                        <DsIconButton
                            tone="danger"
                            onClick={() => {
                                setDeleteTarget(rule);
                                setDeleteOpen(true);
                            }}
                            disabled={!canWrite}
                            aria-label="删除"
                        >
                            <HiOutlineTrash size={14}/>
                        </DsIconButton>
                    </Tooltip>
                </div>
            ),
        },
    ], [canWrite, userMap, togglingId, handleToggle]);

    const historyColumns = useMemo<ColumnsType<AlertHistory>>(() => [
        {
            title: '告警时间',
            dataIndex: 'sentAt',
            width: COL.DATETIME,
            render: (v?: string) => (
                <span className="text-ds-small text-ds-text-secondary whitespace-nowrap">
                    {v ? formatDateTime(v) : '—'}
                </span>
            ),
        },
        {
            title: '对象类型',
            dataIndex: 'objectType',
            width: COL.STATUS,
            render: (v: AlertObjectType) => objectTypeBadge(v),
        },
        {
            title: '对象名称',
            dataIndex: 'objectName',
            width: COL.NAME,
            ellipsis: true,
            render: (v?: string) => (
                <span className="text-ds-small text-ds-text-primary font-medium" title={v || '-'}>{v || '-'}</span>
            ),
        },
        {
            title: '触发条件',
            dataIndex: 'alertType',
            width: COL.STATUS,
            render: (v: AlertTriggerType) => (
                <span
                    className={`inline-flex items-center px-2 py-0.5 rounded-full text-[11px] font-semibold ${
                        v === 'FAILURE'
                            ? 'bg-ds-danger-light text-ds-danger'
                            : v === 'TIMEOUT'
                                ? 'bg-ds-warning-light text-ds-warning'
                                : 'bg-ds-success-light text-ds-success'
                    }`}
                >
                    {TRIGGER_LABEL[v] || v}
                </span>
            ),
        },
        {
            title: '接收人',
            dataIndex: 'recipients',
            width: 240,
            ellipsis: true,
            render: (v?: string) => (
                <span className="text-ds-small text-ds-text-secondary" title={v || ''}>{v || '—'}</span>
            ),
        },
        {
            title: '发送状态',
            dataIndex: 'sendStatus',
            width: COL.STATUS,
            render: (v?: AlertSendStatus) => (
                v === 'FAILED'
                    ? <DsStatusBadge variant="danger" label="发送失败"/>
                    : <DsStatusBadge variant="success" label="发送成功"/>
            ),
        },
        {
            title: '操作',
            align: 'center',
            width: COL.OPERATION_2,
            render: (_, item) => (
                <div className="flex items-center justify-center gap-1 whitespace-nowrap">
                    <Tooltip title="详情">
                        <DsIconButton tone="accent" onClick={() => setDetailHistory(item)} aria-label="详情">
                            <HiOutlineEye size={14}/>
                        </DsIconButton>
                    </Tooltip>
                </div>
            ),
        },
    ], []);

    return (
        <div className="flex flex-col">
            <div className="flex items-center justify-between mb-ds-5 flex-shrink-0">
                <div>
                    <h1 className="text-ds-display text-ds-text-primary">告警中心</h1>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1">统一管理
                        DAG、同步任务、采集任务的邮件告警规则</p>
                </div>
                {activeTab === 'rules' && canWrite && (
                    <DsButton onClick={openCreate}>
                        <HiOutlinePlus size={16}/>
                        新增告警规则
                    </DsButton>
                )}
            </div>

            <div className="flex gap-ds-6 border-b border-ds-border-subtle mb-ds-4 flex-shrink-0">
                <button
                    onClick={() => setActiveTab('rules')}
                    className={`pb-ds-2 text-ds-body font-semibold transition-colors ${
                        activeTab === 'rules'
                            ? 'text-ds-accent border-b-2 border-ds-accent'
                            : 'text-ds-text-muted hover:text-ds-text-primary'
                    }`}
                >
                    告警规则
                </button>
                <button
                    onClick={() => setActiveTab('history')}
                    className={`pb-ds-2 text-ds-body font-semibold transition-colors ${
                        activeTab === 'history'
                            ? 'text-ds-accent border-b-2 border-ds-accent'
                            : 'text-ds-text-muted hover:text-ds-text-primary'
                    }`}
                >
                    告警历史
                </button>
            </div>

            {/* ==================== 告警规则 ==================== */}
            {activeTab === 'rules' && (
                <>
                    <div
                        className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle p-ds-3 mb-ds-4 flex-shrink-0">
                        <DsToolbar
                            extra={
                                <>
                                    <DsButton onClick={handleRuleSearch} disabled={rulesLoading}>
                                        {rulesLoading ? '查询中...' : '查询'}
                                    </DsButton>
                                    <DsButton variant="secondary" onClick={handleRuleReset} disabled={rulesLoading}>
                                        重置
                                    </DsButton>
                                </>
                            }
                        >
                            <SearchInput
                                value={draftKeyword}
                                onChange={e => setDraftKeyword(e.target.value)}
                                onEnter={handleRuleSearch}
                                placeholder="搜索对象名称..."
                            />
                            <DsFilterSelect
                                value={draftObjectType}
                                onChange={v => setDraftObjectType(v as AlertObjectType | '')}
                                options={OBJECT_TYPE_OPTIONS}
                                aria-label="按对象类型筛选"
                            />
                        </DsToolbar>
                    </div>

                    <div
                        className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden flex flex-col mb-ds-8">
                        <div className="overflow-x-auto">
                            <Table<AlertRuleDTO>
                                dataSource={rules}
                                rowKey="id"
                                loading={rulesLoading}
                                pagination={false}
                                scroll={{x: 1100}}
                                columns={ruleColumns}
                                className="prototype-table prototype-table-flush"
                                locale={{
                                    emptyText: <DsTableEmpty description="暂无告警规则，点击右上角新增。"/>,
                                }}
                            />
                        </div>
                        <Pagination page={rulesPage} pageSize={rulesPageSize} total={rulesTotal}
                                    onChange={handleRulePageChange}/>
                    </div>
                </>
            )}

            {/* ==================== 告警历史 ==================== */}
            {activeTab === 'history' && (
                <>
                    <div
                        className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle p-ds-3 mb-ds-4 flex-shrink-0">
                        <DsToolbar
                            extra={
                                <>
                                    <DsButton onClick={handleHistorySearch} disabled={historyLoading}>
                                        {historyLoading ? '查询中...' : '查询'}
                                    </DsButton>
                                    <DsButton variant="secondary" onClick={handleHistoryReset}
                                              disabled={historyLoading}>
                                        重置
                                    </DsButton>
                                </>
                            }
                        >
                            <DsFilterSelect
                                value={draftHistoryType}
                                onChange={v => setDraftHistoryType(v as AlertObjectType | '')}
                                options={OBJECT_TYPE_OPTIONS}
                                aria-label="按对象类型筛选"
                            />
                            <DsFilterSelect
                                value={draftAlertType}
                                onChange={v => setDraftAlertType(v as AlertTriggerType | '')}
                                options={ALERT_TYPE_OPTIONS}
                                aria-label="按告警类型筛选"
                            />
                            <DsFilterSelect
                                value={draftSendStatus}
                                onChange={v => setDraftSendStatus(v as AlertSendStatus | '')}
                                options={SEND_STATUS_OPTIONS}
                                aria-label="按发送状态筛选"
                            />
                        </DsToolbar>
                    </div>

                    <div
                        className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden flex flex-col mb-ds-8">
                        <div className="overflow-x-auto">
                            <Table<AlertHistory>
                                dataSource={history}
                                rowKey="id"
                                loading={historyLoading}
                                pagination={false}
                                scroll={{x: 1100}}
                                columns={historyColumns}
                                className="prototype-table prototype-table-flush"
                                locale={{
                                    emptyText: <DsTableEmpty description="暂无告警历史。"/>,
                                }}
                            />
                        </div>
                        <Pagination page={historyPage} pageSize={historyPageSize} total={historyTotal}
                                    onChange={handleHistoryPageChange}/>
                    </div>
                </>
            )}

            {/* 规则新增/编辑弹窗 */}
            <AlertRuleModal
                open={modalOpen}
                onClose={() => setModalOpen(false)}
                onSaved={reloadRules}
                mode={modalMode}
                initialRule={modalMode === 'edit' ? editRule || undefined : undefined}
                readOnly={!canWrite}
            />

            {/* 历史详情弹窗 */}
            <DsModal
                open={!!detailHistory}
                onClose={() => setDetailHistory(null)}
                title="告警历史详情"
                bordered
                footer={
                    <DsButton variant="secondary" onClick={() => setDetailHistory(null)}>
                        关闭
                    </DsButton>
                }
            >
                {detailHistory && (
                    <div className="grid grid-cols-[100px_1fr] gap-y-ds-3 text-ds-small">
                        <span className="text-ds-text-muted">告警时间</span>
                        <span className="text-ds-text-primary">
                            {detailHistory.sentAt ? formatDateTime(detailHistory.sentAt) : '—'}
                        </span>

                        <span className="text-ds-text-muted">对象类型</span>
                        <span>{objectTypeBadge(detailHistory.objectType)}</span>

                        <span className="text-ds-text-muted">对象名称</span>
                        <span className="text-ds-text-primary">{detailHistory.objectName || '—'}</span>

                        <span className="text-ds-text-muted">触发条件</span>
                        <span>{triggerBadges([detailHistory.alertType])}</span>

                        <span className="text-ds-text-muted">接收人</span>
                        <span className="text-ds-text-primary break-all">{detailHistory.recipients || '—'}</span>

                        <span className="text-ds-text-muted">发送状态</span>
                        <span>
                            {detailHistory.sendStatus === 'FAILED'
                                ? <DsStatusBadge variant="danger" label="发送失败"/>
                                : <DsStatusBadge variant="success" label="发送成功"/>}
                        </span>
                    </div>
                )}
            </DsModal>

            {/* 删除确认 */}
            <ConfirmDialog
                open={deleteOpen}
                title="删除确认"
                message={
                    <div>
                        <p>
                            确定删除告警规则 <strong>"{deleteTarget?.objectName}"</strong> 吗？
                        </p>
                        <p className="mt-ds-2 text-ds-small text-ds-text-muted">删除后该规则不再触发邮件告警，历史记录保留。</p>
                    </div>
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
