// Sprint 5：全局告警中心（系统管理）
// Tab：告警规则 / 告警历史
// 权限：查看 = 超管/工程师/治理员；编辑 = 超管/工程师（PRD §8）
import {useCallback, useEffect, useMemo, useState} from 'react';
import type {IconType} from 'react-icons';
import {Table, Tooltip} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import {useHasRole} from '@/hooks/useHasRole';
import {ALERT_WRITE_ROLES} from '@/constants/roles';
import {COL} from '@/constants/table';
import {deleteAlertRule, getAlertHistory, getAlertHistoryStats, getAlertRules, getUsersWithEmail, toggleAlertRule,} from '@/api/alert';
import type {AlertHistoryStats} from '@/api/alert';
import type {
    AlertHistory,
    AlertObjectType,
    AlertRuleDTO,
    AlertSendStatus,
    AlertTriggerType,
} from '@/types/alert';
import {formatDateTime, getDefaultTimeRange} from '@/utils/format';
import {notify} from '@/utils/notify';
import usePagedList from '@/hooks/usePagedList';
import Pagination from '@/components/Pagination';
import DsButton from '@/components/DsButton';
import DsIconButton from '@/components/DsIconButton';
import DsStatusBadge from '@/components/DsStatusBadge';
import SearchInput from '@/components/SearchInput';
import DsFilterSelect from '@/components/DsFilterSelect';
import DsRangePicker from '@/components/DsRangePicker';
import DsToolbar from '@/components/DsToolbar';
import DsTableEmpty from '@/components/DsTableEmpty';
import DsModal from '@/components/DsModal';
import ConfirmDialog from '@/components/ConfirmDialog';
import AlertRuleModal from '@/components/AlertRuleModal';
import StatsCards from '@/components/StatsCards';
import {
    HiOutlineBell,
    HiOutlineBellAlert,
    HiOutlineBolt,
    HiOutlineCheckCircle,
    HiOutlineClock,
    HiOutlineEye,
    HiOutlinePencilSquare,
    HiOutlinePlus,
    HiOutlineStop,
    HiOutlineTrash,
    HiOutlineXCircle,
} from 'react-icons/hi2';

const OBJECT_TYPE_OPTIONS: { value: AlertObjectType | ''; label: string }[] = [
    {value: '', label: '全部类型'},
    {value: 'DAG', label: 'DAG'},
    {value: 'SYNC_JOB', label: '同步任务'},
    {value: 'COLLECT_TASK', label: '采集任务'},
    {value: 'QUALITY', label: '质量任务'},
    {value: 'CDC_PIPELINE', label: 'CDC 管道'},
];

const ALERT_TYPE_OPTIONS: { value: AlertTriggerType | ''; label: string }[] = [
    {value: '', label: '全部告警类型'},
    {value: 'FAILURE', label: '失败'},
    {value: 'TIMEOUT', label: '超时'},
    {value: 'LAG_EXCEEDED', label: '延迟超阈值'},
    {value: 'EXTERNAL_STOP', label: '外部停止'},
    {value: 'SUCCESS', label: '成功通知'},
];

const SEND_STATUS_OPTIONS: { value: AlertSendStatus | ''; label: string }[] = [
    {value: '', label: '全部发送状态'},
    {value: 'SUCCESS', label: '发送成功'},
    {value: 'FAILED', label: '发送失败'},
];

/** 页签定义：与数据标准页一致的胶囊页签样式 */
const ALERT_TABS: { key: 'rules' | 'history'; label: string; icon: IconType }[] = [
    {key: 'rules', label: '告警规则', icon: HiOutlineBellAlert},
    {key: 'history', label: '告警历史', icon: HiOutlineClock},
];

const TRIGGER_LABEL: Record<AlertTriggerType, string> = {
    FAILURE: '失败',
    TIMEOUT: '超时',
    SUCCESS: '成功',
    LAG_EXCEEDED: '延迟超阈值',
    EXTERNAL_STOP: '外部停止',
};

/** 质量批次告警 summary 逐行解析（每行一条「[等级] 规则名: 详情」），返回展示行 */
function parseQualitySummary(summary?: string): {level: string; ruleName: string; parts: {key: string; value: string}[]}[] {
    if (!summary) return [];
    return summary.split('\n').map(line => line.trim()).filter(Boolean).map(line => {
        const m = line.match(/^\[([^\]]+)\]\s*(.*)$/);
        const level = m ? m[1].trim() : '';
        const rest = m ? m[2] : line;
        // 「[等级] 规则名: 详情」，按首个全角冒号或半角冒号拆出规则名与详情
        const sepIdx = rest.indexOf('：') >= 0 ? rest.indexOf('：') : rest.indexOf(':');
        const ruleName = sepIdx > 0 ? rest.slice(0, sepIdx).trim() : rest.trim();
        const detail = sepIdx > 0 ? rest.slice(sepIdx + 1).trim() : '';
        // 详情按全角竖线「｜」拆字段（对齐后端 buildDetailDesc 输出格式：类型:完整性 ｜ 结果值:0.166670 ｜ 阈值:...）
        const parts = detail ? detail.split('｜').map(p => p.trim()).filter(Boolean).map(p => {
            const ci = p.indexOf(':') >= 0 ? p.indexOf(':') : p.indexOf('：');
            return ci > 0 ? {key: p.slice(0, ci).trim(), value: p.slice(ci + 1).trim()} : {key: '', value: p};
        }) : [];
        return {level, ruleName, parts};
    });
}

/** 质量等级显示名（对齐 QUALITY_CHECK_LEVEL_LABEL 语义） */
const QUALITY_LEVEL_TEXT: Record<string, string> = {
    PASS: '通过',
    WARNING: '警告',
    SEVERE: '严重',
    UNAVAILABLE: '不可用',
};

/** 质量等级徽章变体 */
const QUALITY_LEVEL_VARIANT: Record<string, string> = {
    PASS: 'bg-ds-success-light text-ds-success',
    WARNING: 'bg-ds-warning-light text-ds-warning',
    SEVERE: 'bg-ds-danger-light text-ds-danger',
    UNAVAILABLE: 'bg-ds-bg-hover text-ds-text-muted',
};

function objectTypeBadge(type: AlertObjectType) {
    const variant = type === 'SYNC_JOB' ? 'accent'
        : type === 'COLLECT_TASK' ? 'success'
            : type === 'QUALITY' ? 'warning'
                : type === 'CDC_PIPELINE' ? 'accent'
                    : 'running';
    const label = type === 'SYNC_JOB' ? '同步任务'
        : type === 'COLLECT_TASK' ? '采集任务'
            : type === 'QUALITY' ? '质量任务'
                : type === 'CDC_PIPELINE' ? 'CDC 管道'
                    : 'DAG';
    return <DsStatusBadge variant={variant} label={label}/>;
}

/** 触发条件徽章颜色映射（对齐后端告警类型语义） */
const TRIGGER_BADGE_CLASS: Record<AlertTriggerType, string> = {
    FAILURE: 'bg-ds-danger-light text-ds-danger',
    TIMEOUT: 'bg-ds-warning-light text-ds-warning',
    SUCCESS: 'bg-ds-success-light text-ds-success',
    LAG_EXCEEDED: 'bg-ds-warning-light text-ds-warning',
    EXTERNAL_STOP: 'bg-ds-danger-light text-ds-danger',
};

function triggerBadges(conditions: AlertTriggerType[]) {
    return (
        <div className="flex flex-wrap gap-1">
            {(conditions || []).map(c => (
                <span
                    key={c}
                    className={`inline-flex items-center px-2 py-0.5 rounded-full text-ds-badge ${TRIGGER_BADGE_CLASS[c] || 'bg-ds-bg-hover text-ds-text-muted'}`}
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
    sentAtFrom: string;
    sentAtTo: string;
}

/** 初始时间范围：近 7 天（对齐执行历史页，时间范围必填） */
const DEFAULT_HISTORY_RANGE = getDefaultTimeRange();
const INITIAL_HISTORY_QUERY: HistoryListQuery = {
    objectType: '',
    alertType: '',
    sendStatus: '',
    sentAtFrom: DEFAULT_HISTORY_RANGE.from,
    sentAtTo: DEFAULT_HISTORY_RANGE.to,
};

export default function AlertCenterPage() {
    const canWrite = useHasRole(...ALERT_WRITE_ROLES);

    // ==================== Tab ====================
    const [activeTab, setActiveTab] = useState<'rules' | 'history'>('rules');

    // ==================== 告警规则 ====================
    const [draftKeyword, setDraftKeyword] = useState('');

    const {
        list: rules,
        total: rulesTotal,
        page: rulesPage,
        pageSize: rulesPageSize,
        loading: rulesLoading,
        setPage: setRulesPage,
        setPageSize: setRulesPageSize,
        query: ruleQuery,
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
    const {
        list: history,
        total: historyTotal,
        page: historyPage,
        pageSize: historyPageSize,
        loading: historyLoading,
        setPage: setHistoryPage,
        setPageSize: setHistoryPageSize,
        query: historyQuery,
        applyQuery: applyHistoryQuery,
    } = usePagedList<HistoryListQuery, AlertHistory>({
        fetcher: async (query) => {
            const result = await getAlertHistory({
                page: query.page,
                pageSize: query.pageSize,
                objectType: query.objectType || undefined,
                alertType: query.alertType || undefined,
                sendStatus: query.sendStatus || undefined,
                sentAtFrom: query.sentAtFrom || undefined,
                sentAtTo: query.sentAtTo || undefined,
            });
            return {list: result.records, total: result.total};
        },
        initialQuery: INITIAL_HISTORY_QUERY,
        defaultPageSize: 10,
    });
// ==================== 告警历史统计卡（后端聚合端点，跟随时间范围 + 对象类型） ====================
    const [alertStats, setAlertStats] = useState<AlertHistoryStats | null>(null);
    const [alertStatsLoading, setAlertStatsLoading] = useState(false);
    const loadAlertStats = useCallback((query: HistoryListQuery) => {
        setAlertStatsLoading(true);
        getAlertHistoryStats({
            objectType: query.objectType || undefined,
            sentAtFrom: query.sentAtFrom || undefined,
            sentAtTo: query.sentAtTo || undefined,
        })
            .then(setAlertStats)
            .catch(() => {
                // 拦截器已提示，保持旧数据
            })
            .finally(() => setAlertStatsLoading(false));
    }, []);
    useEffect(() => {
        if (activeTab === 'history') {
            loadAlertStats(historyQuery);
        }
    }, [activeTab, historyQuery, loadAlertStats]);
    // 统计卡点击下钻（方案 A）：统计卡仅下钻告警类型维度，与列表「告警类型」筛选一一对应；
    // 点击时清空发送状态（发送失败提示条独立下钻），保证「统计卡数字 = 列表 total」严格一致。
    const toggleHistoryDrill = (target: AlertTriggerType) => {
        applyHistoryQuery({
            ...historyQuery,
            alertType: historyQuery.alertType === target ? '' : target,
            sendStatus: '',
        });
    };

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
        applyRuleQuery({...ruleQuery, keyword: draftKeyword});
    };

    const handleRuleReset = () => {
        setDraftKeyword('');
        applyRuleQuery(INITIAL_RULE_QUERY);
    };

    const handleHistorySearch = () => {
        applyHistoryQuery(historyQuery);
    };

    const handleHistoryReset = () => {
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
            title: '规则名称',
            dataIndex: 'name',
            width: COL.NAME,
            ellipsis: true,
            render: (v?: string) => (
                <span className="text-ds-small text-ds-text-primary font-medium" title={v || '-'}>{v || '-'}</span>
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
            title: '创建人',
            dataIndex: 'createdByName',
            width: COL.USERNAME,
            ellipsis: true,
            render: (v?: string) => (
                <span className="text-ds-small text-ds-text-secondary" title={v || '-'}>{v || '-'}</span>
            ),
        },
        {
            title: '创建时间',
            dataIndex: 'createdAt',
            width: COL.DATETIME,
            render: (v?: string) => (
                <span className="text-ds-small text-ds-text-secondary whitespace-nowrap">
                    {v ? formatDateTime(v) : '—'}
                </span>
            ),
        },
        {
            title: '修改人',
            dataIndex: 'updatedByName',
            width: COL.USERNAME,
            ellipsis: true,
            render: (v?: string) => (
                <span className="text-ds-small text-ds-text-secondary" title={v || '-'}>{v || '-'}</span>
            ),
        },
        {
            title: '修改时间',
            dataIndex: 'updatedAt',
            width: COL.DATETIME,
            render: (v?: string) => (
                <span className="text-ds-small text-ds-text-secondary whitespace-nowrap">
                    {v ? formatDateTime(v) : '—'}
                </span>
            ),
        },
        {
            title: '操作',
            align: 'center',
            width: COL.OPERATION_3,
            fixed: 'right' as const,
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
            title: '告警规则',
            dataIndex: 'ruleName',
            width: COL.NAME,
            ellipsis: true,
            render: (v?: string) => (
                <span className="text-ds-small text-ds-text-primary font-medium" title={v || '-'}>{v || '-'}</span>
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
                    className={`inline-flex items-center px-2 py-0.5 rounded-full text-ds-badge ${TRIGGER_BADGE_CLASS[v] || 'bg-ds-bg-hover text-ds-text-muted'}`}
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
            fixed: 'right' as const,
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
                        DAG、同步任务、采集任务、质量任务的邮件告警规则</p>
                </div>
                {activeTab === 'rules' && canWrite && (
                    <DsButton onClick={openCreate}>
                        <HiOutlinePlus size={16}/>
                        新增告警规则
                    </DsButton>
                )}
            </div>

            {/* 胶囊页签（对齐数据标准页） */}
            <div className="flex gap-ds-2 mb-ds-4 flex-shrink-0">
                {ALERT_TABS.map((t) => {
                    const active = activeTab === t.key;
                    return (
                        <button
                            key={t.key}
                            onClick={() => setActiveTab(t.key)}
                            className={`flex items-center gap-ds-2 px-ds-4 py-ds-2 rounded-ds-sm text-ds-small font-semibold transition-colors ${
                                active
                                    ? 'bg-ds-accent-light text-ds-accent'
                                    : 'text-ds-text-secondary hover:bg-ds-bg-hover'
                            }`}
                        >
                            <t.icon size={18}/>
                            {t.label}
                        </button>
                    );
                })}
            </div>

            {/* ==================== 告警规则 ==================== */}
            {activeTab === 'rules' && (
                <div
                    className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden flex flex-col">
                    <div className="p-ds-3 border-b border-ds-border-subtle flex-shrink-0">
                        <DsToolbar
                            extra={
                                <>
                                    <DsButton onClick={handleRuleSearch} disabled={rulesLoading} loading={rulesLoading}>
                                        查询
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
                                value={ruleQuery.objectType || ''}
                                onChange={v => applyRuleQuery({...ruleQuery, objectType: v as AlertObjectType | ''})}
                                options={OBJECT_TYPE_OPTIONS}
                                aria-label="按对象类型筛选"
                            />
                        </DsToolbar>
                    </div>

                    <div className="overflow-x-auto">
                        <Table<AlertRuleDTO>
                            dataSource={rules}
                            rowKey="id"
                            loading={rulesLoading}
                            pagination={false}
                            scroll={{x: 1460}}
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
            )}

            {/* ==================== 告警历史 ==================== */}
            {activeTab === 'history' && (
                <>
                    {/* 告警构成统计卡（后端聚合端点，点击卡片下钻列表筛选） */}
                    {/* 告警类型构成统计卡（方案 A：与「告警类型」筛选一一对应，点击下钻） */}
                    <StatsCards
                        columns={5}
                        loading={alertStatsLoading}
                        items={[
                            {label: '失败', value: alertStats?.failure ?? '—', icon: <HiOutlineXCircle size={20}/>,
                             iconClass: 'bg-ds-danger-light text-ds-danger', valueClass: 'text-ds-danger',
                             tip: '任务执行失败触发的告警，点击筛选列表',
                             active: historyQuery.alertType === 'FAILURE',
                             onClick: () => toggleHistoryDrill('FAILURE')},
                            {label: '超时', value: alertStats?.timeout ?? '—', icon: <HiOutlineClock size={20}/>,
                             iconClass: 'bg-ds-warning-light text-ds-warning', valueClass: 'text-ds-warning',
                             tip: '执行超时触发的告警，点击筛选列表',
                             active: historyQuery.alertType === 'TIMEOUT',
                             onClick: () => toggleHistoryDrill('TIMEOUT')},
                            {label: '延迟超阈值', value: alertStats?.lagExceeded ?? '—', icon: <HiOutlineBolt size={20}/>,
                             iconClass: 'bg-ds-warning-light text-ds-warning', valueClass: 'text-ds-warning',
                             tip: 'CDC 管道延迟超阈值告警，点击筛选列表',
                             active: historyQuery.alertType === 'LAG_EXCEEDED',
                             onClick: () => toggleHistoryDrill('LAG_EXCEEDED')},
                            {label: '外部停止', value: alertStats?.externalStop ?? '—', icon: <HiOutlineStop size={20}/>,
                             iconClass: 'bg-ds-bg-hover text-ds-text-muted', tip: '管道被外部停止的通知，点击筛选列表',
                             active: historyQuery.alertType === 'EXTERNAL_STOP',
                             onClick: () => toggleHistoryDrill('EXTERNAL_STOP')},
                            {label: '成功通知', value: alertStats?.success ?? '—', icon: <HiOutlineCheckCircle size={20}/>,
                             iconClass: 'bg-ds-success-light text-ds-success', valueClass: 'text-ds-success',
                             tip: '任务恢复/成功的通知，点击筛选列表',
                             active: historyQuery.alertType === 'SUCCESS',
                             onClick: () => toggleHistoryDrill('SUCCESS')},
                        ]}
                    />
                    

                    <div
                        className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden flex flex-col">
                    <div className="p-ds-3 border-b border-ds-border-subtle flex-shrink-0">
                        <DsToolbar
                            extra={
                                <>
                                    <DsButton onClick={handleHistorySearch} disabled={historyLoading} loading={historyLoading}>
                                        查询
                                    </DsButton>
                                    <DsButton variant="secondary" onClick={handleHistoryReset}
                                              disabled={historyLoading}>
                                        重置
                                    </DsButton>
                                </>
                            }
                        >
                            <DsFilterSelect
                                value={historyQuery.objectType || ''}
                                onChange={v => applyHistoryQuery({...historyQuery, objectType: v as AlertObjectType | ''})}
                                options={OBJECT_TYPE_OPTIONS}
                                aria-label="按对象类型筛选"
                            />
                            <DsFilterSelect
                                value={historyQuery.alertType || ''}
                                onChange={v => applyHistoryQuery({...historyQuery, alertType: v as AlertTriggerType | ''})}
                                options={ALERT_TYPE_OPTIONS}
                                aria-label="按告警类型筛选"
                            />
                            <DsFilterSelect
                                value={historyQuery.sendStatus || ''}
                                onChange={v => applyHistoryQuery({...historyQuery, sendStatus: v as AlertSendStatus | ''})}
                                options={SEND_STATUS_OPTIONS}
                                aria-label="按发送状态筛选"
                            />
                            <DsRangePicker
                                from={historyQuery.sentAtFrom}
                                to={historyQuery.sentAtTo}
                                allowClear={false}
                                onChange={(from, to) => {
                                    // 时间范围必填：清空时提示并保持原值，避免查全部
                                    if (!from || !to) {
                                        notify.warning('请选择告警时间范围');
                                        return;
                                    }
                                    applyHistoryQuery({...historyQuery, sentAtFrom: from, sentAtTo: to});
                                }}
                            />
                        </DsToolbar>
                    </div>

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

                        <span className="text-ds-text-muted">告警规则</span>
                        <span className="text-ds-text-primary">{detailHistory.ruleName || '—'}</span>

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

                        {detailHistory.objectType === 'QUALITY' && detailHistory.summary && (
                            <>
                                <span className="text-ds-text-muted align-top">命中规则</span>
                                <div className="space-y-ds-2">
                                    {parseQualitySummary(detailHistory.summary).map((row, idx) => (
                                        <div key={idx}
                                             className="bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm px-ds-3 py-ds-2">
                                            <div className="flex items-center gap-ds-2 mb-ds-1.5">
                                                <span
                                                    className={`inline-flex items-center px-2 py-0.5 rounded-full text-ds-badge whitespace-nowrap shrink-0 ${
                                                        QUALITY_LEVEL_VARIANT[row.level] || 'bg-ds-bg-hover text-ds-text-muted'
                                                    }`}
                                                >
                                                    {QUALITY_LEVEL_TEXT[row.level] || row.level || '—'}
                                                </span>
                                                <span className="text-ds-small font-semibold text-ds-text-primary break-words">{row.ruleName || '—'}</span>
                                            </div>
                                            {row.parts.length > 0 && (
                                                <div className="grid grid-cols-[auto_1fr] gap-x-ds-3 gap-y-ds-1 text-ds-small">
                                                    {row.parts.map((p, i) => (
                                                        <div key={i} className="contents">
                                                            <span className="text-ds-text-muted whitespace-nowrap">{p.key}</span>
                                                            <span className="text-ds-text-primary whitespace-nowrap overflow-hidden text-ellipsis" title={p.value || ''}>{p.value || '—'}</span>
                                                        </div>
                                                    ))}
                                                </div>
                                            )}
                                        </div>
                                    ))}
                                </div>
                            </>
                        )}
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
