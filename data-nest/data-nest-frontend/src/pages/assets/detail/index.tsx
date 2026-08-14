// Sprint 7 F1：资产详情页（DC-02 详情聚合 + DC-03 血缘嵌入 + DC-04 质量展示）
// 独立路由 /assets/:tableId，与治理侧 /governance/metadata?tableId= 双入口并存。
// 不新建聚合接口：基础信息/字段用元数据 API，血缘用 getLineageGraph，质量用评分 API，
// 页签懒加载（antd Tabs 默认首个激活页签才挂载，切到才拉取）。
// Sprint 8 F1：协作条（标签/收藏/关注，DC-06/07）+ 热度指标卡与埋点（DC-09）+ 评论页签（DC-08）。
import {useCallback, useEffect, useState} from 'react';
import {useNavigate, useLocation, useParams, useSearchParams} from 'react-router-dom';
import {Spin, Tabs, Tooltip} from 'antd';
import {
    HiOutlineBolt,
    HiOutlineChatBubbleLeftRight,
    HiOutlineCheckCircle,
    HiOutlineCommandLine,
    HiOutlineFire,
    HiOutlineInformationCircle,
    HiOutlineQueueList,
    HiOutlineShare,
} from 'react-icons/hi2';
import {getAssetClassifications, getAssetCollaboration, recordAssetView} from '@/api/asset';
import {getLineageGraph} from '@/api/lineage';
import {getMetadataTable} from '@/api/metadata';
import {getQualityScoreByTable} from '@/api/quality';
import DatabaseTypeIcon from '@/components/DatabaseTypeIcon';
import DsButton from '@/components/DsButton';
import DsStatusBadge from '@/components/DsStatusBadge';
import QualityScoreBadge from '@/components/QualityScoreBadge';
import StatsCards from '@/components/StatsCards';
import {GOVERNANCE_WRITE_ROLES} from '@/constants/roles';
import {useHasRole} from '@/hooks/useHasRole';
import {formatDateTime} from '@/utils/format';
import type {AssetClassification, AssetCollaboration} from '@/types/asset';
import type {MetadataTable} from '@/types/metadata';
import type {QualityScore} from '@/types/quality';
import AssignClassificationModal from '@/pages/assets/modals/AssignClassificationModal';
import AssignOwnerModal from '@/pages/assets/modals/AssignOwnerModal';
import AssetLineageTab from './AssetLineageTab';
import CollaborationBar from './CollaborationBar';
import ColumnsTab from './ColumnsTab';
import CommentsTab from './CommentsTab';
import QualityTab from './QualityTab';
import {SensitivityBadge} from '@/pages/data-service/badges';

/** 热度埋点会话级去重 key（同一会话同一表只上报一次，PRD NAC-4） */
const viewedKey = (tableId: string) => `asset-viewed:${tableId}`;

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
        {
            label: '敏感度',
            value: (
                <span className="flex items-center gap-ds-2">
                    <SensitivityBadge level={table.sensitivityLevel}/>
                    {table.sensitivityLevel === 'INTERNAL' && (
                        <span className="text-ds-tiny text-ds-text-muted">
                            {table.apiExempted === 1 ? '已特批开放' : '未特批'}
                        </span>
                    )}
                </span>
            ),
        },
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

export default function AssetDetailPage() {
    const {tableId = ''} = useParams();
    const navigate = useNavigate();
    const location = useLocation();
    /** 来源页（质量报告等经 state.from 进入）：「返回」优先回来源，否则回资产目录 */
    const fromPath = (location.state as { from?: string } | null)?.from;
    const [searchParams, setSearchParams] = useSearchParams();
    const canWrite = useHasRole(...GOVERNANCE_WRITE_ROLES);

    const [table, setTable] = useState<MetadataTable | null>(null);
    const [score, setScore] = useState<QualityScore | null>(null);
    const [loading, setLoading] = useState(true);
    /** Sprint 8 F1：协作状态聚合（标签 + 收藏/关注状态 + 30 天热度 + 评论数），头部一次拉取 */
    const [collaboration, setCollaboration] = useState<AssetCollaboration | null>(null);
    // 初始 tab：优先 URL ?tab=（血缘图谱 → 查看完整血缘 → 返回时回到血缘图谱 tab）
    const [activeTab, setActiveTab] = useState(() => {
        const t = searchParams.get('tab');
        return ['basic', 'columns', 'lineage', 'quality', 'comments'].includes(t || '') ? t! : 'basic';
    });
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

    // Sprint 8 F1：协作聚合 + 热度埋点（会话级去重，上报失败静默不打扰浏览）
    const loadCollaboration = useCallback(() => {
        if (!tableId) return;
        getAssetCollaboration(tableId)
            .then(data => setCollaboration(data ?? null))
            .catch(() => setCollaboration(null));
    }, [tableId]);

    useEffect(() => {
        if (!tableId) return;
        try {
            if (!sessionStorage.getItem(viewedKey(tableId))) {
                sessionStorage.setItem(viewedKey(tableId), '1');
                recordAssetView(tableId).catch(() => {
                    // 埋点失败不影响页面
                });
            }
        } catch {
            // sessionStorage 不可用（隐私模式等）时跳过埋点
        }
        loadCollaboration();
    }, [tableId, loadCollaboration]);

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
                <DsButton variant="secondary" className="self-start mb-ds-4" onClick={() => navigate(fromPath || '/asset-catalog')}>
                    ← 返回
                </DsButton>
                <div className="bg-ds-bg-surface border border-ds-border-subtle rounded-ds-md p-ds-10 text-center text-ds-small text-ds-text-muted">
                    未找到该数据表（可能已被删除或元数据已下线）
                </div>
            </div>
        );
    }

    const fullName = `${table.databaseName}.${table.tableName}`;
    const confidential = table.sensitivityLevel === 'CONFIDENTIAL';
    const goQuery = () => {
        const p = new URLSearchParams();
        p.set('datasourceId', table.datasourceId);
        p.set('database', table.databaseName);
        if (table.schemaName) p.set('schema', table.schemaName);
        p.set('table', table.tableName);
        navigate(`/data-service/sql-console?${p.toString()}`);
    };
    const goApi = () => {
        const p = new URLSearchParams();
        p.set('datasourceId', table.datasourceId);
        p.set('database', table.databaseName);
        if (table.schemaName) p.set('schema', table.schemaName);
        p.set('table', table.tableName);
        navigate(`/data-service/api-manage/new?${p.toString()}`);
    };

    return (
        <div className="flex flex-col">
            {/* 头部：返回（平台深层页惯例：secondary「← 返回」按钮）+ 路径条 + 徽章 + 操作 */}
            <div className="flex items-start justify-between mb-ds-4 flex-shrink-0 gap-ds-4">
                <div className="min-w-0">
                    <DsButton variant="secondary" className="mb-ds-3" onClick={() => navigate(fromPath || '/asset-catalog')}>
                        ← 返回
                    </DsButton>
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
                        <SensitivityBadge level={table.sensitivityLevel}/>
                    </div>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1">
                        {table.tableComment || table.manualComment || '暂无注释'}
                        {'　'}负责人：{table.ownerName || '—'}
                    </p>
                </div>
                <div className="flex items-center gap-ds-2 flex-shrink-0 flex-wrap justify-end">
                    <Tooltip title={confidential ? '机密级表，无权查询' : '跳 SQL 终端预填查询'}>
                        <DsButton variant="secondary" onClick={goQuery} disabled={confidential}>
                            <HiOutlineCommandLine size={14}/>
                            去查询
                        </DsButton>
                    </Tooltip>
                    <Tooltip title={confidential ? '机密级表，禁止生成对外 API' : '跳 API 创建向导预选该表'}>
                        <DsButton variant="secondary" onClick={goApi} disabled={confidential}>
                            <HiOutlineBolt size={14}/>
                            生成 API
                        </DsButton>
                    </Tooltip>
                    {canWrite && (
                        <>
                            <DsButton variant="secondary" onClick={() => setClassifyOpen(true)}>分配分类</DsButton>
                            <DsButton variant="secondary" onClick={() => setOwnerOpen(true)}>配置负责人</DsButton>
                        </>
                    )}
                </div>
            </div>

            {/* Sprint 8 F1：协作条（标签区 + 收藏/关注，全角色可用） */}
            <CollaborationBar tableId={tableId} collaboration={collaboration} onChange={setCollaboration}/>

            {/* 指标卡（对齐原型 stat-strip：质量评分 / 字段数 / 直接上下游表数 / 近 30 天热度） */}
            <StatsCards
                items={[
                    {label: '质量评分', icon: <HiOutlineCheckCircle size={20}/>, iconClass: 'bg-ds-accent-light text-ds-accent',
                     value: <QualityScoreBadge score={score?.score ?? null} healthLevel={score?.healthLevel}/>},
                    {label: '字段数', icon: <HiOutlineQueueList size={20}/>, iconClass: 'bg-ds-success-light text-ds-success',
                     value: <span className="text-ds-heading font-bold text-ds-text-primary">{table.columnCount ?? '—'}</span>},
                    {label: '直接上游 / 下游表', icon: <HiOutlineShare size={20}/>, iconClass: 'bg-ds-warning-light text-ds-warning',
                     value: <span className="text-ds-heading font-bold text-ds-text-primary">{lineageStats ? `${lineageStats.up} / ${lineageStats.down}` : '—'}</span>},
                    {label: '热度（近 30 天访问）', icon: <HiOutlineFire size={20}/>, iconClass: 'bg-ds-danger-light text-ds-danger',
                     value: <span className="text-ds-heading font-bold text-ds-text-primary">{collaboration?.viewCount30d ?? '—'}</span>},
                ]}
            />

            {/* 四页签（懒加载：切到才挂载拉取） */}
            <div
                className="bg-ds-bg-surface border border-ds-border-subtle rounded-ds-md px-ds-4 pb-ds-4 flex-shrink-0">
                <Tabs
                    activeKey={activeTab}
                    onChange={(key) => {
                        setActiveTab(key);
                        // 手动切换 tab 同步到 URL（?tab=），返回/刷新保持一致
                        setSearchParams({tab: key}, {replace: true});
                    }}
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
                        {
                            key: 'comments',
                            label: (
                                <span className="flex items-center gap-ds-1">
                                    <HiOutlineChatBubbleLeftRight size={14}/>
                                    评论
                                    {collaboration?.commentCount != null && Number(collaboration.commentCount) > 0 && (
                                        <span className="text-ds-tiny text-ds-accent font-semibold">
                                            {collaboration.commentCount}
                                        </span>
                                    )}
                                </span>
                            ),
                            children: <CommentsTab tableId={tableId} onCountChange={loadCollaboration}/>,
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
