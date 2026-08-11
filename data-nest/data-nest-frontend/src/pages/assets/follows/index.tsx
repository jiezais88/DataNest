// Sprint 8 F1：我的关注（DC-07 表变更动态）。关注为个人维度。
// 列表 = 资产卡片字段 + 关注时间 + 最近一次采集变更动态（复用 collect_change_detail，后端三元组匹配取最新一条）。
import {useCallback, useMemo, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {Table, Tooltip} from 'antd';
import {HiOutlineBell, HiOutlineBolt} from 'react-icons/hi2';
import {HiBell} from 'react-icons/hi2';
import {getMyFollows, unfollowAsset} from '@/api/asset';
import ConfirmDialog from '@/components/ConfirmDialog';
import DsButton from '@/components/DsButton';
import DsIconButton from '@/components/DsIconButton';
import DsTableEmpty from '@/components/DsTableEmpty';
import DsToolbar from '@/components/DsToolbar';
import Pagination from '@/components/Pagination';
import QualityScoreBadge from '@/components/QualityScoreBadge';
import SearchInput from '@/components/SearchInput';
import {COL} from '@/constants/table';
import usePagedList from '@/hooks/usePagedList';
import {formatDateTime} from '@/utils/format';
import {notify} from '@/utils/notify';
import type {AssetChange, AssetFollowItem, MyAssetQuery} from '@/types/asset';

const INITIAL_QUERY: MyAssetQuery = {};

/** 字段属性变更标签（对齐 CollectLogModal 的 COLUMN_PROP_LABEL） */
const COLUMN_PROP_LABEL: Record<string, string> = {
    MODIFIED_COLUMN_TYPE: '类型',
    MODIFIED_COLUMN_COMMENT: '注释',
    MODIFIED_COLUMN_ORDINAL: '顺序',
    MODIFIED_COLUMN_NULLABLE: '可空性',
    MODIFIED_COLUMN_DEFAULT: '默认值',
};

/** 变更动态摘要（后端返回 collect_change_detail 原始字段，前端按类型渲染，PRD §6.3.2） */
function changeSummary(c: AssetChange): string {
    const type = c.changeType ?? '';
    if (type === 'ADDED_TABLE') return '元数据变更：新增表';
    if (type === 'DELETED_TABLE') return '元数据变更：表已删除';
    if (type === 'MODIFIED_TABLE') return '元数据变更：表注释修改';
    if (type === 'ADDED_COLUMN') return `元数据变更：新增字段 ${c.columnName ?? ''}`;
    if (type === 'DELETED_COLUMN') return `元数据变更：删除字段 ${c.columnName ?? ''}`;
    if (type.startsWith('MODIFIED_COLUMN_')) {
        const label = COLUMN_PROP_LABEL[type] || '属性';
        return `元数据变更：字段 ${c.columnName ?? ''}${label}修改`;
    }
    return '元数据变更';
}

export default function MyFollowsPage() {
    const navigate = useNavigate();
    const openDetail = useCallback((tableId: string) => navigate(`/asset-catalog/${tableId}`), [navigate]);

    const [keywordInput, setKeywordInput] = useState('');

    const {list, total, page, pageSize, loading, setPage, setPageSize, applyQuery, reload} =
        usePagedList<MyAssetQuery, AssetFollowItem>({
            fetcher: (q) => getMyFollows(q).then(r => ({list: r?.records ?? [], total: Number(r?.total ?? 0)})),
            initialQuery: INITIAL_QUERY,
        });

    const [removing, setRemoving] = useState<AssetFollowItem | null>(null);
    const [removeLoading, setRemoveLoading] = useState(false);

    const handleSearch = () => {
        applyQuery({keyword: keywordInput.trim() || undefined});
    };

    const handleReset = () => {
        setKeywordInput('');
        applyQuery(INITIAL_QUERY);
    };

    const handleRemove = async () => {
        if (!removing) return;
        setRemoveLoading(true);
        try {
            await unfollowAsset(removing.tableId);
            notify.success(`已取消关注「${removing.tableName}」`);
            reload();
        } catch {
            // 拦截器已提示
        } finally {
            setRemoveLoading(false);
            setRemoving(null);
        }
    };

    const columns = useMemo(() => ([
        {
            title: '表名',
            dataIndex: 'tableName',
            width: COL.NAME,
            ellipsis: true,
            render: (v?: string, r?: AssetFollowItem) => (
                <button
                    type="button"
                    onClick={(e) => {
                        e.stopPropagation();
                        if (r?.tableId) openDetail(r.tableId);
                    }}
                    title={r?.databaseName ? `${r.databaseName}.${v}` : v}
                    className="text-ds-small text-ds-accent hover:underline text-left font-medium"
                >
                    {v || '—'}
                </button>
            ),
        },
        {
            title: '注释',
            dataIndex: 'tableComment',
            width: COL.NAME,
            ellipsis: true,
            render: (v?: string) => (
                <span title={v || ''} className="text-ds-small text-ds-text-secondary">{v || '—'}</span>
            ),
        },
        {
            title: '质量评分',
            dataIndex: 'qualityScore',
            width: 140,
            render: (v?: number, r?: AssetFollowItem) => (
                <QualityScoreBadge table score={v ?? null} healthLevel={r?.healthLevel}/>
            ),
        },
        {
            title: '最近变更动态',
            key: 'latestChange',
            render: (_: unknown, r: AssetFollowItem) => {
                const change = r.latestChange;
                if (!change || !change.changeType) {
                    return <span className="text-ds-small text-ds-text-muted">暂无变更</span>;
                }
                return (
                    <span className="flex items-center gap-ds-2">
                        <HiOutlineBolt size={14} className="text-ds-warning flex-shrink-0"/>
                        <span className="text-ds-small text-ds-text-secondary truncate"
                              title={changeSummary(change)}>
                            {changeSummary(change)}
                        </span>
                        <span className="text-ds-tiny text-ds-text-muted flex-shrink-0 whitespace-nowrap">
                            {formatDateTime(change.changeTime)}
                        </span>
                    </span>
                );
            },
        },
        {
            title: '关注时间',
            dataIndex: 'followedAt',
            width: COL.DATETIME_COMPACT,
            render: (v?: string) => (
                <span className="text-ds-small text-ds-text-secondary whitespace-nowrap">{formatDateTime(v)}</span>
            ),
        },
        {
            title: '操作',
            key: 'action',
            width: 80,
            fixed: 'right' as const,
            render: (_: unknown, r: AssetFollowItem) => (
                <Tooltip title="取消关注">
                    <DsIconButton tone="danger" aria-label="取消关注"
                                  onClick={(e) => {
                                      e.stopPropagation();
                                      setRemoving(r);
                                  }}>
                        <HiBell size={14}/>
                    </DsIconButton>
                </Tooltip>
            ),
        },
    ]), [openDetail]);

    return (
        <div className="flex flex-col">
            <div className="flex items-center justify-between mb-ds-5">
                <div>
                    <h1 className="text-ds-display text-ds-text-primary flex items-center gap-ds-2">
                        <HiOutlineBell className="text-ds-accent"/>
                        我的关注
                    </h1>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1">
                        关注表在元数据采集到变更时产生站内动态，帮助及时感知表结构变化。
                    </p>
                </div>
            </div>

            <div
                className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden">
                <div className="p-ds-3 border-b border-ds-border-subtle">
                    <DsToolbar
                        extra={(
                            <>
                                <DsButton onClick={handleSearch} disabled={loading} loading={loading}>
                                    查询
                                </DsButton>
                                <DsButton variant="secondary" onClick={handleReset}>重置</DsButton>
                            </>
                        )}
                    >
                        <SearchInput
                            value={keywordInput}
                            onChange={(e) => setKeywordInput(e.target.value)}
                            onEnter={handleSearch}
                            placeholder="搜索表名 / 注释…"
                            aria-label="搜索我的关注"
                        />
                    </DsToolbar>
                </div>

                <Table
                    rowKey={(r) => r.tableId}
                    columns={columns}
                    dataSource={list}
                    loading={loading}
                    pagination={false}
                    scroll={{x: 1200}}
                    className="prototype-table prototype-table-flush"
                    onRow={(r) => ({
                        onClick: () => openDetail(r.tableId),
                        className: 'cursor-pointer',
                    })}
                    locale={{
                        emptyText: <DsTableEmpty description="暂无关注，去资产详情页点击「关注」吧"/>,
                    }}
                />

                {total > 0 && (
                    <Pagination
                        page={page}
                        pageSize={pageSize}
                        total={total}
                        onChange={(p, s) => {
                            setPage(p);
                            if (s !== pageSize) setPageSize(s);
                        }}
                    />
                )}
            </div>

            <ConfirmDialog
                open={!!removing}
                title="取消关注"
                message={`确认取消关注「${removing?.tableName}」？取消后不再接收该表变更动态。`}
                confirmLabel="取消关注"
                danger
                loading={removeLoading}
                onConfirm={handleRemove}
                onCancel={() => setRemoving(null)}
            />
        </div>
    );
}
