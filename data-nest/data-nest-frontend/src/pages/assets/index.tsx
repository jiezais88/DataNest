// Sprint 7 F1：数据资产目录（搜索发现 + 分类维护合并单页，2026-08-07 用户确认方案 A）
// 全部角色：搜索 / 分类树浏览 / 进详情（只读）。
// 治理员/超管额外可见：树节点编辑/删除、新增数据域/主题、分配表到分类、表格操作列（负责人/移出）。
// 双态互斥：关键词非空 = 搜索态（/assets/search，相关度排序，上限 200 不分页）；否则 = 浏览态分页。
// 数据源/健康度下拉变更即时生效（浏览态走后端参数，搜索态同样传给后端）。
// Sprint 8 F1：标签云筛选（DC-06，仅浏览态）+ 排序（热度/最新/评分，仅浏览态）+ 热度列 + 热门 Top10 面板（DC-09）。
import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {useNavigate, useSearchParams} from 'react-router-dom';
import {Table, Tooltip} from 'antd';
import {HiOutlineArrowRightOnRectangle, HiOutlineChevronLeft, HiOutlineChevronRight, HiOutlineFire, HiOutlineUser} from 'react-icons/hi2';
import {
    browseAssets,
    deleteClassification,
    getAssetClassifications,
    getHotTables,
    assignTableClassification,
    listAssetTags,
    searchAssets,
} from '@/api/asset';
import {listMetadataDatasourceIds} from '@/api/metadata';
import ConfirmDialog from '@/components/ConfirmDialog';
import DsButton from '@/components/DsButton';
import DsFilterSelect from '@/components/DsFilterSelect';
import DsIconButton from '@/components/DsIconButton';
import DsTableEmpty from '@/components/DsTableEmpty';
import DsToolbar from '@/components/DsToolbar';
import Pagination from '@/components/Pagination';
import QualityScoreBadge from '@/components/QualityScoreBadge';
import SearchInput from '@/components/SearchInput';
import {GOVERNANCE_WRITE_ROLES} from '@/constants/roles';
import {COL} from '@/constants/table';
import usePagedList from '@/hooks/usePagedList';
import {useHasRole} from '@/hooks/useHasRole';
import {notify} from '@/utils/notify';
import {QUALITY_HEALTH_OPTIONS} from '@/types/quality';
import type {AssetBrowseQuery, AssetClassification, AssetClassificationTree, AssetSearchItem, AssetTag} from '@/types/asset';
import AssetTree, {ALL_SELECTION, selectionKey, selectionToQuery} from './AssetTree';
import type {AssetTreeSelection} from './AssetTree';
import {buildAssetColumns} from './assetColumns';
import AssignOwnerModal from './modals/AssignOwnerModal';
import AssignTablesModal from './modals/AssignTablesModal';
import ClassificationFormModal from './modals/ClassificationFormModal';
import type {ClassificationFormState} from './modals/ClassificationFormModal';

const SEARCH_LIMIT = 200;

/** 浏览态排序选项（搜索态按相关度排序，排序下拉禁用） */
const SORT_OPTIONS = [
    {value: '', label: '默认排序'},
    {value: 'hot', label: '按热度'},
    {value: 'latest', label: '按最新'},
    {value: 'score', label: '按评分'},
];

// 左侧分类树可拖拽宽度（方案 C）：范围 + 持久化到 localStorage
const TREE_MIN_W = 220;
const TREE_MAX_W = 420;
const TREE_DEFAULT_W = 320;
const TREE_WIDTH_KEY = 'asset-catalog.treeWidth';

export default function AssetsPage() {
    const navigate = useNavigate();
    const canWrite = useHasRole(...GOVERNANCE_WRITE_ROLES);
    const openDetail = useCallback((tableId: string) => navigate(`/asset-catalog/${tableId}`), [navigate]);

    // ============ 分类树宽度（可拖拽调宽，持久化） ============
    const [treeWidth, setTreeWidth] = useState<number>(() => {
        try {
            // 仅当用户主动拖拽过（localStorage 已存该 key）才采用其偏好宽度；
            // 未设置过/值为旧默认时用当前 TREE_DEFAULT_W，保证默认宽度更新后对已访问用户也生效
            const raw = localStorage.getItem(TREE_WIDTH_KEY);
            if (raw != null) {
                const w = Number(raw);
                if (Number.isFinite(w) && w >= TREE_MIN_W && w <= TREE_MAX_W) return w;
            }
        } catch {
            // 读取失败用默认宽度
        }
        return TREE_DEFAULT_W;
    });
    const treeResizeRef = useRef<{startX: number; startW: number} | null>(null);

    useEffect(() => {
        try {
            localStorage.setItem(TREE_WIDTH_KEY, String(treeWidth));
        } catch {
            // 写入失败（隐私模式等）忽略
        }
    }, [treeWidth]);

    const startTreeResize = (e: React.MouseEvent<HTMLDivElement>) => {
        e.preventDefault();
        treeResizeRef.current = {startX: e.clientX, startW: treeWidth};
        const onMove = (ev: MouseEvent) => {
            const ref = treeResizeRef.current;
            if (!ref) return;
            const w = Math.min(TREE_MAX_W, Math.max(TREE_MIN_W, ref.startW + (ev.clientX - ref.startX)));
            setTreeWidth(w);
        };
        const onUp = () => {
            treeResizeRef.current = null;
            document.body.style.userSelect = '';
            document.removeEventListener('mousemove', onMove);
            document.removeEventListener('mouseup', onUp);
        };
        // 拖拽期间禁用文本选中，避免选中页面内容
        document.body.style.userSelect = 'none';
        document.addEventListener('mousemove', onMove);
        document.addEventListener('mouseup', onUp);
    };

    // ============ 分类树 ============
    const [treeData, setTreeData] = useState<AssetClassificationTree | null>(null);
    const [selection, setSelection] = useState<AssetTreeSelection>(ALL_SELECTION);
    const tree = treeData?.list ?? [];

    const loadTree = useCallback(() => {
        getAssetClassifications()
            .then(data => setTreeData(data ?? {list: []}))
            .catch(() => {
                // 拦截器已提示，保持旧数据
            });
    }, []);

    useEffect(() => {
        loadTree();
    }, [loadTree]);

    // ============ 筛选（下拉即时生效；关键词走查询按钮/回车） ============
    const [keywordInput, setKeywordInput] = useState('');
    const [keyword, setKeyword] = useState('');
    const [datasourceId, setDatasourceId] = useState('');
    const [healthLevel, setHealthLevel] = useState('');
    // Sprint 8 F1：标签筛选 + 排序（仅浏览态生效；支持详情页标签 chip 跳转带来的 ?tag= 初始值）
    const [searchParams, setSearchParams] = useSearchParams();
    const [tag, setTag] = useState(() => searchParams.get('tag') ?? '');
    const [sort, setSort] = useState('');
    const isSearch = keyword.trim() !== '';

    // ?tag= 一次性消费：读作初值后立即从 URL 清掉，避免页面内取消筛选后刷新又“复活”
    useEffect(() => {
        if (searchParams.get('tag')) {
            setSearchParams({}, {replace: true});
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

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
        reload,
        setPage,
        setPageSize,
    } = usePagedList<AssetBrowseQuery, AssetSearchItem>({
        fetcher: (q) => browseAssets(q).then(r => ({list: r?.records ?? [], total: Number(r?.total ?? 0)})),
        // 初始查询带上 URL 初始 tag/sort（?tag= 跳转场景），避免 mount 首跑 + effect 二跑的双请求
        initialQuery: {
            ...selectionToQuery(ALL_SELECTION),
            tag: tag || undefined,
            sort: (sort || undefined) as 'score' | 'hot' | 'latest' | undefined,
        },
    });

    // 树选中 / 数据源 / 健康度 / 标签 / 排序变化 → 即时重新浏览（跳过与 initialQuery 重复的首跑）
    useEffect(() => {
        if (browseQueryRef.current.skipFirst) {
            browseQueryRef.current.skipFirst = false;
            // tag/sort 初值已含在 initialQuery 里，首跑跳过只需看树/数据源/健康度是否默认
            if (selection.type === 'all' && !datasourceId && !healthLevel) return;
        }
        applyQuery({
            ...selectionToQuery(selection),
            datasourceId: datasourceId || undefined,
            healthLevel: healthLevel || undefined,
            tag: tag || undefined,
            sort: (sort || undefined) as 'score' | 'hot' | 'latest' | undefined,
        });
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [selection, datasourceId, healthLevel, tag, sort]);

    // ============ Sprint 8 F1：标签云 + 热门 Top10 ============
    const [tagCloud, setTagCloud] = useState<AssetTag[]>([]);
    const [hotList, setHotList] = useState<AssetSearchItem[]>([]);
    // 热门面板可折叠（表格列多，折叠后释放 280px 宽度减少横向滚动；偏好持久化）
    const [hotCollapsed, setHotCollapsed] = useState(() => {
        try {
            return localStorage.getItem('asset-catalog.hotCollapsed') === '1';
        } catch {
            return false;
        }
    });
    const toggleHotPanel = () => {
        setHotCollapsed(prev => {
            try {
                localStorage.setItem('asset-catalog.hotCollapsed', prev ? '0' : '1');
            } catch {
                // 写入失败（隐私模式等）忽略
            }
            return !prev;
        });
    };

    useEffect(() => {
        listAssetTags()
            .then(list => setTagCloud(list ?? []))
            .catch(() => setTagCloud([]));
        getHotTables(10)
            .then(list => setHotList(list ?? []))
            .catch(() => setHotList([]));
    }, []);

    // ============ 搜索态（关键词或下拉变化即时重搜） ============
    const [searchList, setSearchList] = useState<AssetSearchItem[]>([]);
    const [searchLoading, setSearchLoading] = useState(false);
    /** 搜索态刷新信号：治理员操作（配置负责人等）保存后需重搜，否则列表仍是旧值 */
    const [searchRefreshKey, setSearchRefreshKey] = useState(0);

    useEffect(() => {
        const kw = keyword.trim();
        if (!kw) {
            setSearchList([]);
            return;
        }
        let cancelled = false;
        setSearchLoading(true);
        searchAssets(kw, {datasourceId: datasourceId || undefined, healthLevel: healthLevel || undefined})
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
    }, [keyword, datasourceId, healthLevel, searchRefreshKey]);

    /** 按当前态刷新列表：搜索态重搜，浏览态重查 */
    const reloadCurrent = () => {
        if (isSearch) setSearchRefreshKey(k => k + 1);
        else reload();
    };

    // ============ 搜索/重置 ============
    const handleSearch = () => {
        // 标签筛选/排序仅浏览态生效，进入搜索态时清掉避免误解
        setTag('');
        setSort('');
        setKeyword(keywordInput.trim());
    };

    const handleReset = () => {
        setKeywordInput('');
        setKeyword('');
        setDatasourceId('');
        setHealthLevel('');
        setTag('');
        setSort('');
        setSelection(ALL_SELECTION);
        applyQuery(selectionToQuery(ALL_SELECTION));
    };

    /** 点标签云 chip：退出搜索态进入浏览态并按标签筛选（再次点击当前标签 = 取消筛选） */
    const handleTagSelect = (name: string) => {
        setKeyword('');
        setKeywordInput('');
        setTag(prev => (prev === name ? '' : name));
    };

    const handleTreeSelect = (sel: AssetTreeSelection) => {
        // 点树节点 = 退出搜索态进入浏览态
        setSelection(sel);
        setKeyword('');
        setKeywordInput('');
    };

    // ============ 分类维护（治理员） ============
    const [formState, setFormState] = useState<ClassificationFormState>({mode: 'create'});
    const [formOpen, setFormOpen] = useState(false);
    const [deleting, setDeleting] = useState<AssetClassification | null>(null);
    const [deleteLoading, setDeleteLoading] = useState(false);
    const [assignOpen, setAssignOpen] = useState(false);
    const [ownerTarget, setOwnerTarget] = useState<AssetSearchItem | null>(null);
    const [removing, setRemoving] = useState<AssetSearchItem | null>(null);
    const [removeLoading, setRemoveLoading] = useState(false);

    const isClassified = selection.type === 'domain' || selection.type === 'topic';
    const findDomain = (name?: string) => tree.find(d => d.name === name);

    const handleEditClassification = (node: AssetClassification, parent?: AssetClassification) => {
        setFormState({mode: 'edit', node, parent});
        setFormOpen(true);
    };

    const handleDeleteClassification = async () => {
        if (!deleting) return;
        setDeleteLoading(true);
        try {
            await deleteClassification(deleting.id);
            notify.success(`已删除「${deleting.name}」`);
            // 删掉的是当前选中节点时回到全部资产
            if (selection.domain === deleting.name || selection.topic === deleting.name) {
                setSelection(ALL_SELECTION);
                applyQuery(selectionToQuery(ALL_SELECTION));
            }
            loadTree();
            reload();
        } catch {
            // 4007/4009 由拦截器统一提示
        } finally {
            setDeleteLoading(false);
            setDeleting(null);
        }
    };

    const handleRemoveFromClassification = async () => {
        if (!removing) return;
        setRemoveLoading(true);
        try {
            await assignTableClassification(removing.tableId, {dataDomain: null, dataTopic: null});
            notify.success(`已将「${removing.tableName}」移出分类`);
            reload();
            loadTree();
        } catch {
            // 拦截器已提示
        } finally {
            setRemoveLoading(false);
            setRemoving(null);
        }
    };

    // ============ 列 ============
    const columns = useMemo(() => {
        const base = buildAssetColumns(openDetail, handleTagSelect);
        if (!canWrite) return base;
        return [
            ...base,
            {
                title: '操作',
                key: 'action',
                width: COL.OPERATION_2,
                // 列总宽超过卡片时横向滚动，操作列钉在右侧
                fixed: 'right' as const,
                render: (_: unknown, r: AssetSearchItem) => (
                    <div className="flex items-center gap-ds-1">
                        <Tooltip title="配置负责人">
                            <DsIconButton tone="accent" aria-label="配置负责人"
                                          onClick={(e) => {
                                              e.stopPropagation();
                                              setOwnerTarget(r);
                                          }}>
                                <HiOutlineUser size={14}/>
                            </DsIconButton>
                        </Tooltip>
                        {isClassified && !isSearch && (
                            <Tooltip title="移出当前分类">
                                <DsIconButton tone="danger" aria-label="移出当前分类"
                                              onClick={(e) => {
                                                  e.stopPropagation();
                                                  setRemoving(r);
                                              }}>
                                    <HiOutlineArrowRightOnRectangle size={14}/>
                                </DsIconButton>
                            </Tooltip>
                        )}
                    </div>
                ),
            },
        ];
    }, [openDetail, canWrite, isClassified, isSearch, handleTagSelect]);

    const tableData = isSearch ? searchList : browseList;
    const loading = isSearch ? searchLoading : browseLoading;
    const selectionLabel = selection.type === 'domain'
        ? (selection.domain ?? '')
        : selection.type === 'topic'
            ? `${selection.domain} / ${selection.topic}`
            : '';

    return (
        <div className="h-full flex flex-col overflow-hidden">
            <div className="flex items-center justify-between mb-ds-5 flex-shrink-0">
                <div>
                    <h1 className="text-ds-display text-ds-text-primary">数据资产</h1>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1">
                        一站式发现、检索并了解平台数据表，找得到、看得懂、敢使用。
                    </p>
                </div>
                {canWrite && (
                    <DsButton onClick={() => {
                        setFormState({mode: 'create'});
                        setFormOpen(true);
                    }}>
                        新增数据域
                    </DsButton>
                )}
            </div>

            <div className="flex-1 min-h-0 flex gap-ds-4">
                {/* 左：分类树（治理员可编辑）；可拖拽调宽，树内独立滚动 */}
                <div className="flex flex-shrink-0 min-h-0">
                    <div
                        style={{width: treeWidth}}
                        className="flex-shrink-0 min-h-0 overflow-y-auto bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle">
                        <AssetTree
                            tree={tree}
                            selectedKey={isSearch ? '' : selectionKey(selection)}
                            onSelect={handleTreeSelect}
                            allCount={treeData?.totalCount}
                            uncategorizedCount={treeData?.uncategorizedCount}
                            editable={canWrite}
                            onEdit={handleEditClassification}
                            onDelete={setDeleting}
                        />
                    </div>
                    {/* 拖拽手柄：拉宽/收窄分类树 */}
                    <div
                        role="separator"
                        aria-orientation="vertical"
                        title="拖拽调整分类树宽度"
                        onMouseDown={startTreeResize}
                        className="ml-1 w-[6px] flex-shrink-0 cursor-col-resize select-none rounded-full bg-ds-bg-hover hover:bg-ds-accent transition-colors"
                    />
                </div>

                {/* 右：表格卡片（工具栏/管理条/分页器钉住，表格区内部滚动） */}
                <div
                    className="flex-1 min-w-0 min-h-0 bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden flex flex-col">
                    <div className="p-ds-3 border-b border-ds-border-subtle flex-shrink-0">
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
                                placeholder="搜索表名 / 注释 / 字段 / 标签 / 负责人…"
                                aria-label="搜索数据资产"
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
                            <Tooltip title={isSearch ? '搜索结果按相关度排序' : undefined}>
                                <span>
                                    <DsFilterSelect
                                        value={sort}
                                        onChange={setSort}
                                        aria-label="排序方式"
                                        options={SORT_OPTIONS}
                                        disabled={isSearch}
                                    />
                                </span>
                            </Tooltip>
                        </DsToolbar>
                    </div>

                    {/* Sprint 8 F1：标签云筛选行（仅浏览态生效；有标签才展示） */}
                    {tagCloud.length > 0 && (
                        <div
                            className="flex items-center gap-ds-2 flex-wrap px-ds-4 py-ds-2 border-b border-ds-border-subtle flex-shrink-0">
                            <span className="text-ds-tiny text-ds-text-muted flex-shrink-0">标签筛选</span>
                            <button
                                type="button"
                                onClick={() => handleTagSelect('')}
                                className={`px-2.5 py-1 rounded-full text-ds-badge transition-colors ${
                                    !tag
                                        ? 'bg-ds-accent text-white'
                                        : 'bg-ds-bg-hover text-ds-text-secondary hover:text-ds-accent'
                                }`}
                            >
                                全部
                            </button>
                            {tagCloud.map(t => (
                                <button
                                    key={t.tagId}
                                    type="button"
                                    onClick={() => handleTagSelect(t.tagName)}
                                    title={`${t.refCount ?? 0} 张表`}
                                    className={`px-2.5 py-1 rounded-full text-ds-badge transition-colors ${
                                        tag === t.tagName
                                            ? 'bg-ds-accent text-white'
                                            : 'bg-ds-accent-light text-ds-accent hover:bg-ds-accent hover:text-white'
                                    }`}
                                >
                                    {t.tagName}
                                </button>
                            ))}
                        </div>
                    )}

                    {/* 治理员 + 浏览态选中具体分类时的管理条 */}
                    {canWrite && !isSearch && isClassified && (
                        <div
                            className="flex items-center gap-ds-3 px-ds-4 py-ds-2 border-b border-ds-border-subtle flex-shrink-0 bg-ds-bg-root">
                            <span className="text-ds-small text-ds-text-secondary">
                                当前分类：<span className="text-ds-text-primary font-semibold">{selectionLabel}</span>
                                <span className="text-ds-text-muted">（{total} 张表）</span>
                            </span>
                            <div className="flex items-center gap-ds-2 ml-auto">
                                <DsButton
                                    variant="secondary"
                                    onClick={() => {
                                        setFormState({mode: 'create', parent: findDomain(selection.domain)});
                                        setFormOpen(true);
                                    }}
                                >
                                    新增主题
                                </DsButton>
                                <DsButton
                                    variant="secondary"
                                    onClick={() => setAssignOpen(true)}
                                >
                                    分配表到此分类
                                </DsButton>
                            </div>
                        </div>
                    )}

                    <div className="ds-table-fill flex-1 min-h-0 overflow-hidden">
                        <Table
                            rowKey={(r) => r.tableId}
                            columns={columns}
                            dataSource={tableData}
                            loading={loading}
                            pagination={false}
                            scroll={{x: 1360}}
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
                        <div className="flex-shrink-0 px-ds-4 py-ds-2 border-t border-ds-border-subtle text-ds-tiny text-ds-text-muted">
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

                {/* Sprint 8 F1：热门数据表面板（DC-09，近 30 天访问 Top10，点击进详情；可折叠释放表格宽度） */}
                {hotCollapsed ? (
                    <button
                        type="button"
                        onClick={toggleHotPanel}
                        title="展开热门数据表面板"
                        aria-label="展开热门数据表面板"
                        className="w-[36px] flex-shrink-0 min-h-0 bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle flex flex-col items-center py-ds-3 gap-ds-2 hover:bg-ds-bg-hover transition-colors"
                    >
                        <HiOutlineFire size={16} className="text-ds-warning"/>
                        <HiOutlineChevronLeft size={14} className="text-ds-text-muted"/>
                    </button>
                ) : (
                    <div
                        className="w-[280px] flex-shrink-0 min-h-0 bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden flex flex-col">
                        <div
                            className="flex items-center gap-ds-2 px-ds-4 py-ds-3 border-b border-ds-border-subtle flex-shrink-0">
                            <HiOutlineFire size={16} className="text-ds-warning"/>
                            <span className="text-ds-small font-semibold text-ds-text-primary">热门数据表</span>
                            <span className="text-ds-tiny text-ds-text-muted ml-auto">近 30 天</span>
                            <Tooltip title="收起面板，表格更宽">
                                <button
                                    type="button"
                                    onClick={toggleHotPanel}
                                    aria-label="收起热门数据表面板"
                                    className="text-ds-text-muted hover:text-ds-text-primary transition-colors"
                                >
                                    <HiOutlineChevronRight size={14}/>
                                </button>
                            </Tooltip>
                        </div>
                        <div className="flex-1 min-h-0 overflow-y-auto">
                            {hotList.length === 0 ? (
                                <div className="px-ds-4 py-ds-8 text-center text-ds-tiny text-ds-text-muted">
                                    暂无访问数据<br/>访问任意表详情后，这里会出现热门排行
                                </div>
                            ) : (
                                hotList.map((item, idx) => (
                                    <button
                                        key={item.tableId}
                                        type="button"
                                        onClick={() => openDetail(item.tableId)}
                                        className="w-full flex items-center gap-ds-3 px-ds-4 py-ds-3 text-left hover:bg-ds-bg-hover transition-colors border-b border-ds-border-subtle last:border-b-0"
                                    >
                                        <span
                                            className={`w-5 text-center text-ds-small font-bold flex-shrink-0 ${idx < 3 ? 'text-ds-warning' : 'text-ds-text-muted'}`}>
                                            {idx + 1}
                                        </span>
                                        <span className="flex-1 min-w-0">
                                            <span className="flex items-center gap-ds-2">
                                                <span
                                                    className="font-mono text-ds-small text-ds-text-primary truncate">{item.tableName}</span>
                                                <QualityScoreBadge table score={item.qualityScore ?? null}
                                                                   healthLevel={item.healthLevel}/>
                                            </span>
                                            <span
                                                className="block text-ds-tiny text-ds-text-muted truncate mt-0.5">{item.tableComment || '—'}</span>
                                        </span>
                                        <span
                                            className="inline-flex items-center gap-ds-1 text-ds-tiny text-ds-warning flex-shrink-0">
                                            <HiOutlineFire size={12}/>
                                            {item.viewCount ?? '0'}
                                        </span>
                                    </button>
                                ))
                            )}
                        </div>
                        <div
                            className="px-ds-4 py-ds-2 border-t border-ds-border-subtle flex-shrink-0 text-ds-tiny text-ds-text-muted">
                            按详情页访问埋点聚合
                        </div>
                    </div>
                )}
            </div>

            {/* 新增/编辑分类 */}
            <ClassificationFormModal
                open={formOpen}
                form={formState}
                tree={tree}
                onClose={() => setFormOpen(false)}
                onSaved={() => {
                    loadTree();
                    reload();
                }}
            />

            {/* 删除分类确认 */}
            <ConfirmDialog
                open={!!deleting}
                title="删除分类"
                message={deleting?.level === 'DOMAIN'
                    ? `确认删除数据域「${deleting.name}」？删除前需先删除其下全部主题，且无任何表引用该分类。`
                    : `确认删除主题「${deleting?.name}」？删除前需先移出全部引用表。`}
                confirmLabel="删除"
                danger
                loading={deleteLoading}
                onConfirm={handleDeleteClassification}
                onCancel={() => setDeleting(null)}
            />

            {/* 批量分配表到当前分类 */}
            {isClassified && (
                <AssignTablesModal
                    open={assignOpen}
                    domain={selection.domain ?? ''}
                    topic={selection.type === 'topic' ? selection.topic : undefined}
                    onClose={() => setAssignOpen(false)}
                    onSaved={() => {
                        reload();
                        loadTree();
                    }}
                />
            )}

            {/* 配置负责人 */}
            <AssignOwnerModal
                open={!!ownerTarget}
                tableId={ownerTarget?.tableId}
                tableName={ownerTarget?.tableName}
                currentOwnerId={ownerTarget?.ownerUserId}
                currentOwnerName={ownerTarget?.ownerName}
                onClose={() => setOwnerTarget(null)}
                onSaved={reloadCurrent}
            />

            {/* 移出分类确认 */}
            <ConfirmDialog
                open={!!removing}
                title="移出分类"
                message={`确认将「${removing?.tableName}」移出分类「${selectionLabel}」？`}
                confirmLabel="移出"
                danger
                loading={removeLoading}
                onConfirm={handleRemoveFromClassification}
                onCancel={() => setRemoving(null)}
            />
        </div>
    );
}
