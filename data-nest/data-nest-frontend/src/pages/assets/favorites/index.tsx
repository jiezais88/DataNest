// Sprint 8 F1：我的收藏（DC-07）。收藏为个人维度，仅自己可见。
// 列表 = 资产卡片字段 + 收藏时间（收藏时间倒序）；支持关键词/数据源/健康度筛选与 CSV 导出（2026-08-10 用户确认后端补齐）。
import {useCallback, useEffect, useMemo, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {Table, Tooltip} from 'antd';
import {HiOutlineArrowDownTray, HiOutlineStar} from 'react-icons/hi2';
import {HiStar} from 'react-icons/hi2';
import {exportMyFavorites, getMyFavorites, unfavoriteAsset} from '../../../api/asset';
import {listMetadataDatasourceIds} from '../../../api/metadata';
import ConfirmDialog from '../../../components/ConfirmDialog';
import DsButton from '../../../components/DsButton';
import DsFilterSelect from '../../../components/DsFilterSelect';
import DsIconButton from '../../../components/DsIconButton';
import DsTableEmpty from '../../../components/DsTableEmpty';
import DsToolbar from '../../../components/DsToolbar';
import Pagination from '../../../components/Pagination';
import SearchInput from '../../../components/SearchInput';
import {COL} from '../../../constants/table';
import usePagedList from '../../../hooks/usePagedList';
import {formatDateTime} from '../../../utils/format';
import {downloadCsvBlob} from '../../../utils/download';
import {notify} from '../../../utils/notify';
import {QUALITY_HEALTH_OPTIONS} from '../../../types/quality';
import type {AssetFavoriteItem, MyAssetQuery} from '../../../types/asset';
import {buildAssetColumns} from '../assetColumns';

const INITIAL_QUERY: MyAssetQuery = {};

export default function MyFavoritesPage() {
    const navigate = useNavigate();
    const openDetail = useCallback((tableId: string) => navigate(`/asset-catalog/${tableId}`), [navigate]);

    // 草稿条件（输入框）+ 已应用条件（usePagedList）
    const [keywordInput, setKeywordInput] = useState('');
    const [datasourceId, setDatasourceId] = useState('');
    const [healthLevel, setHealthLevel] = useState('');

    const [datasourceOptions, setDatasourceOptions] = useState<{ value: string; label: string }[]>([
        {value: '', label: '全部数据源'},
    ]);
    useEffect(() => {
        listMetadataDatasourceIds()
            .then(res => {
                const list = res.data ?? [];
                setDatasourceOptions([
                    {value: '', label: '全部数据源'},
                    ...list.map(d => ({value: String(d.id), label: d.name || `数据源 ${d.id}`})),
                ]);
            })
            .catch(() => {
                // 下拉失败保持仅「全部数据源」
            });
    }, []);

    const {list, total, page, pageSize, loading, query, setPage, setPageSize, applyQuery, reload} =
        usePagedList<MyAssetQuery, AssetFavoriteItem>({
            fetcher: (q) => getMyFavorites(q).then(r => ({list: r?.records ?? [], total: Number(r?.total ?? 0)})),
            initialQuery: INITIAL_QUERY,
        });

    const [removing, setRemoving] = useState<AssetFavoriteItem | null>(null);
    const [removeLoading, setRemoveLoading] = useState(false);
    const [exporting, setExporting] = useState(false);

    const handleSearch = () => {
        applyQuery({keyword: keywordInput.trim() || undefined, datasourceId: datasourceId || undefined, healthLevel: healthLevel || undefined});
    };

    const handleReset = () => {
        setKeywordInput('');
        setDatasourceId('');
        setHealthLevel('');
        applyQuery(INITIAL_QUERY);
    };

    const handleRemove = async () => {
        if (!removing) return;
        setRemoveLoading(true);
        try {
            await unfavoriteAsset(removing.tableId);
            notify.success(`已取消收藏「${removing.tableName}」`);
            reload();
        } catch {
            // 拦截器已提示
        } finally {
            setRemoveLoading(false);
            setRemoving(null);
        }
    };

    const handleExport = async () => {
        if (exporting) return;
        setExporting(true);
        try {
            const blob = await exportMyFavorites({
                keyword: query.keyword,
                datasourceId: query.datasourceId,
                healthLevel: query.healthLevel,
            });
            const date = new Date();
            const ymd = `${date.getFullYear()}${String(date.getMonth() + 1).padStart(2, '0')}${String(date.getDate()).padStart(2, '0')}`;
            // blob 错误检出在 downloadCsvBlob 内（业务异常返回 Result JSON，不能当 CSV 存盘）
            if (await downloadCsvBlob(blob, `DataNest-我的收藏-${ymd}.csv`)) {
                notify.success('已导出我的收藏');
            }
        } catch {
            // 拦截器已提示
        } finally {
            setExporting(false);
        }
    };

    const columns = useMemo(() => ([
        ...buildAssetColumns(openDetail),
        {
            title: '收藏时间',
            dataIndex: 'favoritedAt',
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
            render: (_: unknown, r: AssetFavoriteItem) => (
                <Tooltip title="取消收藏">
                    <DsIconButton tone="danger" aria-label="取消收藏"
                                  onClick={(e) => {
                                      e.stopPropagation();
                                      setRemoving(r);
                                  }}>
                        <HiStar size={14}/>
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
                        <HiOutlineStar className="text-ds-accent"/>
                        我的收藏
                    </h1>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1">
                        收藏常用数据表，快速进入。收藏为个人维度，仅自己可见。
                    </p>
                </div>
                <DsButton variant="secondary" onClick={handleExport} disabled={exporting}>
                    <HiOutlineArrowDownTray size={14}/>
                    {exporting ? '导出中...' : '导出收藏'}
                </DsButton>
            </div>

            <div
                className="bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden">
                <div className="p-ds-3 border-b border-ds-border-subtle">
                    <DsToolbar
                        extra={(
                            <>
                                <DsButton onClick={handleSearch} disabled={loading}>
                                    {loading ? '查询中...' : '查询'}
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
                            aria-label="搜索我的收藏"
                        />
                        <DsFilterSelect
                            value={datasourceId}
                            onChange={setDatasourceId}
                            aria-label="按数据源筛选"
                            options={datasourceOptions}
                        />
                        <DsFilterSelect
                            value={healthLevel}
                            onChange={setHealthLevel}
                            aria-label="按健康度筛选"
                            options={QUALITY_HEALTH_OPTIONS}
                        />
                    </DsToolbar>
                </div>

                <Table
                    rowKey={(r) => r.tableId}
                    columns={columns}
                    dataSource={list}
                    loading={loading}
                    pagination={false}
                    scroll={{x: 1470}}
                    className="prototype-table prototype-table-flush"
                    onRow={(r) => ({
                        onClick: () => openDetail(r.tableId),
                        className: 'cursor-pointer',
                    })}
                    locale={{
                        emptyText: <DsTableEmpty description="暂无收藏，去资产详情页点击「收藏」吧"/>,
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
                title="取消收藏"
                message={`确认取消收藏「${removing?.tableName}」？`}
                confirmLabel="取消收藏"
                danger
                loading={removeLoading}
                onConfirm={handleRemove}
                onCancel={() => setRemoving(null)}
            />
        </div>
    );
}
