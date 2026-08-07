// Sprint 7 F1：资产详情页（DC-02 详情聚合 + DC-03 血缘嵌入 + DC-04 质量展示）
// 独立路由 /assets/:tableId，与治理侧 /governance/metadata?tableId= 双入口并存。
// 不新建聚合接口：基础信息/字段用元数据 API，血缘用 getLineageGraph，质量用评分 API，
// 页签懒加载（antd Tabs 默认首个激活页签才挂载，切到才拉取）。
import {useCallback, useEffect, useState} from 'react';
import {useNavigate, useParams} from 'react-router-dom';
import {Spin, Tabs, Tooltip} from 'antd';
import {
    HiOutlineCheckCircle,
    HiOutlineInformationCircle,
    HiOutlineQueueList,
    HiOutlineShare,
} from 'react-icons/hi2';
import {getAssetClassifications} from '../../../api/asset';
import {getLineageGraph} from '../../../api/lineage';
import {getMetadataTable} from '../../../api/metadata';
import {getQualityScoreByTable} from '../../../api/quality';
import DatabaseTypeIcon from '../../../components/DatabaseTypeIcon';
import DsButton from '../../../components/DsButton';
import DsStatusBadge from '../../../components/DsStatusBadge';
import QualityScoreBadge from '../../../components/QualityScoreBadge';
import {GOVERNANCE_WRITE_ROLES} from '../../../constants/roles';
import {useHasRole} from '../../../hooks/useHasRole';
import {formatDateTime} from '../../../utils/format';
import type {AssetClassification} from '../../../types/asset';
import type {MetadataTable} from '../../../types/metadata';
import type {QualityScore} from '../../../types/quality';
import AssignClassificationModal from '../modals/AssignClassificationModal';
import AssignOwnerModal from '../modals/AssignOwnerModal';
import AssetLineageTab from './AssetLineageTab';
import ColumnsTab from './ColumnsTab';
import QualityTab from './QualityTab';

/** 基础信息页签：三列 kv 网格 */
function BasicInfoTab({table}: { table: MetadataTable }) {
    // 内置 Doris 表（伪 datasource_id=-1）在 engineering 无连接记录，详情接口保持 null；
    // 资产详情按 sourceType 兜底回显「Doris 数仓 / DORIS」（与资产列表口径一致）
    const isBuiltinDoris = table.sourceType === 'BUILTIN_DORIS';
    const dsType = table.datasourceType || (isBuiltinDoris ? 'DORIS' : undefined);
    const dsName = table.datasourceName || (isBuiltinDoris ? 'Doris 数仓' : '—');
    const items: { label: string; value: React.ReactNode }[] = [
        {
            label: '表全名',
            value: (
                <span className="font-mono text-ds-small">
                    {table.databaseName}{table.schemaName && table.schemaName !== table.databaseName ? `.${table.schemaName}` : ''}.{table.tableName}
                </span>
            ),
        },
        {label: '表名', value: table.tableName},
        {label: '注释', value: table.tableComment || table.manualComment || '—'},
        {
            label: '数据源',
            value: dsType
                ? <DatabaseTypeIcon type={dsType} size={16} showLabel={false}/>
                : '—',
        },
        {label: '数据源名称', value: dsName},
        {label: 'Schema', value: table.schemaName || '—'},
        {label: '字段数', value: table.columnCount ?? '—'},
        {
            label: '数据来源',
            value: table.sourceDagName
                ? `DAG 任务（${table.sourceDagName}${table.sourceNodeName ? ` / ${table.sourceNodeName}` : ''}）`
                : (table.sourceType || '—'),
        },
        {label: '数据域 / 主题', value: table.dataDomain ? `${table.dataDomain}${table.dataTopic ? ` / ${table.dataTopic}` : ''}` : '—'},
        {label: '负责人', value: table.ownerName || '—'},
        {label: '最近采集时间', value: formatDateTime(table.lastCollectTime)},
        {label: '最近更新', value: formatDateTime(table.updatedAt)},
    ];
    return (
        <div
            className="bg-ds-bg-surface border border-ds-border-subtle rounded-ds-md p-ds-6 grid grid-cols-3 gap-x-ds-6 gap-y-ds-4">
            {items.map(item => (
                <div key={item.label}>
                    <div className="text-ds-tiny text-ds-text-muted mb-ds-1">{item.label}</div>
                    <div className="text-ds-small text-ds-text-primary">{item.value}</div>
                </div>
            ))}
        </div>
    );
}

/** 指标卡（对齐原型 stat-strip：圆角图标 + 大数值 + 小标签） */
function StatCard({icon, iconClass, label, value}: {
    icon: React.ReactNode;
    iconClass: string;
    label: string;
    value: React.ReactNode;
}) {
    return (
        <div className="bg-ds-bg-surface border border-ds-border-subtle rounded-ds-md p-ds-4 flex items-center gap-ds-3">
            <div className={`w-10 h-10 rounded-ds-md flex items-center justify-center flex-shrink-0 ${iconClass}`}>
                {icon}
            </div>
            <div className="min-w-0">
                <div className="leading-tight">{value}</div>
                <div className="text-ds-tiny text-ds-text-muted mt-ds-1">{label}</div>
            </div>
        </div>
    );
}

export default function AssetDetailPage() {
    const {tableId = ''} = useParams();
    const navigate = useNavigate();
    const canWrite = useHasRole(...GOVERNANCE_WRITE_ROLES);

    const [table, setTable] = useState<MetadataTable | null>(null);
    const [score, setScore] = useState<QualityScore | null>(null);
    const [loading, setLoading] = useState(true);
    const [activeTab, setActiveTab] = useState('basic');
    /** 直接上游/下游表数（指标卡，取自血缘 graph depth=1 的边） */
    const [lineageStats, setLineageStats] = useState<{ up: number; down: number } | null>(null);

    const [tree, setTree] = useState<AssetClassification[]>([]);
    const [classifyOpen, setClassifyOpen] = useState(false);
    const [ownerOpen, setOwnerOpen] = useState(false);

    const loadTable = useCallback(() => {
        if (!tableId) return;
        setLoading(true);
        getMetadataTable(tableId)
            .then(res => setTable(res.data ?? null))
            .catch(() => setTable(null))
            .finally(() => setLoading(false));
        // 头部质量徽章单独拉取（未配置规则时为 null，展示「—」）
        getQualityScoreByTable(tableId)
            .then(res => setScore(res.data ?? null))
            .catch(() => setScore(null));
    }, [tableId]);

    useEffect(() => {
        loadTable();
    }, [loadTable]);

    // 指标卡的上下游表数：血缘 graph（depth=1）的边按 source/target 统计
    useEffect(() => {
        if (!table?.databaseName || !table?.tableName) return;
        const name = `${table.databaseName}.${table.tableName}`;
        getLineageGraph(name, 1)
            .then(g => {
                const edges = g?.edges ?? [];
                setLineageStats({
                    up: edges.filter(e => e.target === name).length,
                    down: edges.filter(e => e.source === name).length,
                });
            })
            .catch(() => setLineageStats(null));
    }, [table]);

    // 分配分类弹窗需要分类树（仅治理员可打开，懒加载一次）
    useEffect(() => {
        if (!canWrite) return;
        getAssetClassifications()
            .then(data => setTree(data?.list ?? []))
            .catch(() => setTree([]));
    }, [canWrite]);

    if (loading) {
        return (
            <div className="h-[320px] flex items-center justify-center">
                <Spin size="large"/>
            </div>
        );
    }

    if (!table) {
        return (
            <div className="flex flex-col">
                <DsButton variant="secondary" className="self-start mb-ds-4" onClick={() => navigate('/asset-catalog')}>
                    ← 数据资产
                </DsButton>
                <div className="bg-ds-bg-surface border border-ds-border-subtle rounded-ds-md p-ds-10 text-center text-ds-small text-ds-text-muted">
                    未找到该数据表（可能已被删除或元数据已下线）
                </div>
            </div>
        );
    }

    const fullName = `${table.databaseName}.${table.tableName}`;

    return (
        <div className="flex flex-col">
            {/* 头部：返回 + 路径条 + 徽章 + 操作 */}
            <div className="flex items-start justify-between mb-ds-4 flex-shrink-0 gap-ds-4">
                <div className="min-w-0">
                    <button
                        type="button"
                        onClick={() => navigate('/asset-catalog')}
                        className="text-ds-small text-ds-accent hover:underline mb-ds-2"
                    >
                        ← 数据资产
                    </button>
                    <div className="flex items-center gap-ds-2 flex-wrap">
                        <span className="text-ds-body text-ds-text-muted font-mono">{table.databaseName} /</span>
                        <Tooltip title={table.tableName} placement="top">
                            <span className="text-ds-heading text-ds-text-primary font-bold font-mono">
                                {table.tableName}
                            </span>
                        </Tooltip>
                        {table.dataDomain && <DsStatusBadge variant="accent" label={table.dataDomain}/>}
                        {table.dataTopic && <DsStatusBadge variant="disabled" label={table.dataTopic}/>}
                        <QualityScoreBadge score={score?.score ?? null} healthLevel={score?.healthLevel}/>
                    </div>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1">
                        {table.tableComment || table.manualComment || '暂无注释'}
                        {'　'}负责人：{table.ownerName || '—'}
                    </p>
                </div>
                {canWrite && (
                    <div className="flex items-center gap-ds-2 flex-shrink-0">
                        <DsButton variant="secondary" onClick={() => setClassifyOpen(true)}>分配分类</DsButton>
                        <DsButton variant="secondary" onClick={() => setOwnerOpen(true)}>配置负责人</DsButton>
                    </div>
                )}
            </div>

            {/* 指标卡（对齐原型 stat-strip，三张：质量评分 / 字段数 / 直接上下游表数） */}
            <div className="grid grid-cols-3 gap-ds-4 mb-ds-4 flex-shrink-0">
                <StatCard
                    icon={<HiOutlineCheckCircle size={20}/>}
                    iconClass="bg-ds-accent-light text-ds-accent"
                    label="质量评分"
                    value={<QualityScoreBadge score={score?.score ?? null} healthLevel={score?.healthLevel}/>}
                />
                <StatCard
                    icon={<HiOutlineQueueList size={20}/>}
                    iconClass="bg-ds-success-light text-ds-success"
                    label="字段数"
                    value={<span className="text-ds-heading font-bold text-ds-text-primary">{table.columnCount ?? '—'}</span>}
                />
                <StatCard
                    icon={<HiOutlineShare size={20}/>}
                    iconClass="bg-ds-warning-light text-ds-warning"
                    label="直接上游 / 下游表"
                    value={
                        <span className="text-ds-heading font-bold text-ds-text-primary">
                            {lineageStats ? `${lineageStats.up} / ${lineageStats.down}` : '—'}
                        </span>
                    }
                />
            </div>

            {/* 四页签（懒加载：切到才挂载拉取） */}
            <div
                className="bg-ds-bg-surface border border-ds-border-subtle rounded-ds-md px-ds-4 pb-ds-4 flex-shrink-0">
                <Tabs
                    activeKey={activeTab}
                    onChange={setActiveTab}
                    items={[
                        {
                            key: 'basic',
                            label: (
                                <span className="flex items-center gap-ds-1">
                                    <HiOutlineInformationCircle size={14}/>
                                    基础信息
                                </span>
                            ),
                            children: <BasicInfoTab table={table}/>,
                        },
                        {
                            key: 'columns',
                            label: (
                                <span className="flex items-center gap-ds-1">
                                    <HiOutlineQueueList size={14}/>
                                    字段列表
                                </span>
                            ),
                            children: <ColumnsTab tableId={tableId}/>,
                        },
                        {
                            key: 'lineage',
                            label: (
                                <span className="flex items-center gap-ds-1">
                                    <HiOutlineShare size={14}/>
                                    血缘图谱
                                </span>
                            ),
                            children: <AssetLineageTab tableId={tableId} fullName={fullName}/>,
                        },
                        {
                            key: 'quality',
                            label: (
                                <span className="flex items-center gap-ds-1">
                                    <HiOutlineCheckCircle size={14}/>
                                    质量
                                </span>
                            ),
                            children: <QualityTab tableId={tableId} canWrite={canWrite}/>,
                        },
                    ]}
                />
            </div>

            <AssignClassificationModal
                open={classifyOpen}
                tableId={tableId}
                tableName={table.tableName}
                currentDomain={table.dataDomain}
                currentTopic={table.dataTopic}
                tree={tree}
                onClose={() => setClassifyOpen(false)}
                onSaved={loadTable}
            />
            <AssignOwnerModal
                open={ownerOpen}
                tableId={tableId}
                tableName={table.tableName}
                currentOwnerId={table.ownerUserId}
                currentOwnerName={table.ownerName}
                onClose={() => setOwnerOpen(false)}
                onSaved={loadTable}
            />
        </div>
    );
}
