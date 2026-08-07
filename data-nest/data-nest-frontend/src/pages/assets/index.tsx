// Sprint 7 F1：数据资产目录首页（DC-01 多维搜索 + DC-05 分类浏览）
// 布局对齐原型 assets 视图：左侧分类树（全部/域/主题/未分类）+ 右侧表格卡片。
// 双态互斥：关键词非空 = 搜索态（/assets/search 扁平结果，相关度排序，上限 200 不分页）；
// 否则 = 浏览态（/assets/browse 分页）。健康度筛选后端无参数，F1 仅数据源筛选
// （浏览态走后端 datasourceId，搜索态在前端对 200 条结果内存过滤）。
import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {Table} from 'antd';
import {browseAssets, getAssetClassifications, searchAssets} from '../../api/asset';
import {listMetadataDatasourceIds} from '../../api/metadata';
import DsButton from '../../components/DsButton';
import DsFilterSelect from '../../components/DsFilterSelect';
import DsTableEmpty from '../../components/DsTableEmpty';
import DsToolbar from '../../components/DsToolbar';
import Pagination from '../../components/Pagination';
import SearchInput from '../../components/SearchInput';
import usePagedList from '../../hooks/usePagedList';
import type {AssetClassification, AssetSearchItem} from '../../types/asset';
import AssetTree, {ALL_SELECTION, selectionKey, selectionToQuery} from './AssetTree';
import type {AssetTreeSelection} from './AssetTree';
import {buildAssetColumns} from './assetColumns';

const SEARCH_LIMIT = 200;

export default function AssetsPage() {
    const navigate = useNavigate();
    const openDetail = useCallback((tableId: string) => navigate(`/asset-catalog/${tableId}`), [navigate]);

    // ============ 分类树 ============
    const [tree, setTree] = useState<AssetClassification[]>([]);
    const [selection, setSelection] = useState<AssetTreeSelection>(ALL_SELECTION);

    useEffect(() => {
        getAssetClassifications()
            .then(list => setTree(list ?? []))
            .catch(() => {
                // 拦截器已提示，树留空
            });
    }, []);

    // ============ 筛选（查询按钮应用） ============
    const [keywordInput, setKeywordInput] = useState('');
    const [keyword, setKeyword] = useState('');
    const [dsDraft, setDsDraft] = useState('');
    const [datasourceId, setDatasourceId] = useState('');
    const isSearch = keyword.trim() !== '';

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

    // ============ 浏览态（usePagedList） ============
    const browseQueryRef = useRef({skipFirst: true});
    const {
        list: browseList,
        total,
        page,
        pageSize,
        loading: browseLoading,
        applyQuery,
        setPage,
        setPageSize,
    } = usePagedList({
        fetcher: (q) => browseAssets(q).then(r => ({list: r?.records ?? [], total: Number(r?.total ?? 0)})),
        initialQuery: selectionToQuery(ALL_SELECTION),
    });

    // 树选中 / 数据源变化 → 重新浏览（跳过与 initialQuery 重复的首跑）
    useEffect(() => {
        if (browseQueryRef.current.skipFirst) {
            browseQueryRef.current.skipFirst = false;
            if (selection.type === 'all' && !datasourceId) return;
        }
        applyQuery({...selectionToQuery(selection), datasourceId: datasourceId || undefined});
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [selection, datasourceId]);

    // ============ 搜索态 ============
    const [searchList, setSearchList] = useState<AssetSearchItem[]>([]);
    const [searchLoading, setSearchLoading] = useState(false);

    useEffect(() => {
        const kw = keyword.trim();
        if (!kw) {
            setSearchList([]);
            return;
        }
        let cancelled = false;
        setSearchLoading(true);
        searchAssets(kw)
            .then(list => {
                if (!cancelled) setSearchList(list ?? []);
            })
            .catch(() => {
                if (!cancelled) setSearchList([]);
            })
            .finally(() => {
                if (!cancelled) setSearchLoading(false);
            });
        return () => {
            cancelled = true;
        };
    }, [keyword]);

    // 搜索态数据源筛选：search 接口不支持 datasourceId，200 条结果内存过滤
    const filteredSearchList = useMemo(
        () => (datasourceId ? searchList.filter(i => i.datasourceId === datasourceId) : searchList),
        [searchList, datasourceId],
    );

    // ============ 交互 ============
    const handleSearch = () => {
        setKeyword(keywordInput.trim());
        setDatasourceId(dsDraft);
    };

    const handleReset = () => {
        setKeywordInput('');
        setKeyword('');
        setDsDraft('');
        setDatasourceId('');
        setSelection(ALL_SELECTION);
        applyQuery(selectionToQuery(ALL_SELECTION));
    };

    const handleTreeSelect = (sel: AssetTreeSelection) => {
        // 点树节点 = 退出搜索态进入浏览态
        setSelection(sel);
        setKeyword('');
        setKeywordInput('');
    };

    const columns = useMemo(() => buildAssetColumns(openDetail), [openDetail]);
    const tableData = isSearch ? filteredSearchList : browseList;
    const loading = isSearch ? searchLoading : browseLoading;

    return (
        <div className="flex flex-col">
            <div className="mb-ds-5 flex-shrink-0">
                <h1 className="text-ds-display text-ds-text-primary">数据资产</h1>
                <p className="text-ds-small text-ds-text-muted mt-ds-1">
                    一站式发现、检索并了解平台数据表，找得到、看得懂、敢使用。
                </p>
            </div>

            <div className="flex gap-ds-4 items-start">
                {/* 左：分类树 */}
                <div
                    className="w-[260px] flex-shrink-0 bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle">
                    <AssetTree
                        tree={tree}
                        selectedKey={isSearch ? '' : selectionKey(selection)}
                        onSelect={handleTreeSelect}
                    />
                </div>

                {/* 右：表格卡片 */}
                <div
                    className="flex-1 min-w-0 bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden flex flex-col">
                    <div className="p-ds-3 border-b border-ds-border-subtle flex-shrink-0">
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
                                placeholder="搜索表名 / 注释 / 负责人…"
                                aria-label="搜索数据资产"
                            />
                            <DsFilterSelect
                                value={dsDraft}
                                onChange={setDsDraft}
                                aria-label="按数据源筛选"
                                options={datasourceOptions}
                            />
                        </DsToolbar>
                    </div>

                    <div className="overflow-x-auto">
                        <Table
                            rowKey={(r) => r.tableId}
                            columns={columns}
                            dataSource={tableData}
                            loading={loading}
                            pagination={false}
                            className="prototype-table prototype-table-flush"
                            onRow={(r) => ({
                                onClick: () => openDetail(r.tableId),
                                className: 'cursor-pointer',
                            })}
                            locale={{
                                emptyText: (
                                    <DsTableEmpty
                                        description={isSearch
                                            ? '未找到匹配的资产，换个关键词试试'
                                            : '该分类下暂无数据表'}
                                    />
                                ),
                            }}
                        />
                    </div>

                    {isSearch && tableData.length >= SEARCH_LIMIT && (
                        <div className="px-ds-4 py-ds-2 border-t border-ds-border-subtle text-ds-tiny text-ds-text-muted">
                            结果较多，仅展示相关度最高的前 {SEARCH_LIMIT} 条，请精确关键词
                        </div>
                    )}

                    {!isSearch && total > 0 && (
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
            </div>
        </div>
    );
}
