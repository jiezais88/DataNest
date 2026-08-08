// Sprint 7 F1：数据资产目录（搜索发现 + 分类维护合并单页，2026-08-07 用户确认方案 A）
// 全部角色：搜索 / 分类树浏览 / 进详情（只读）。
// 治理员/超管额外可见：树节点编辑/删除、新增数据域/主题、分配表到分类、表格操作列（负责人/移出）。
// 双态互斥：关键词非空 = 搜索态（/assets/search，相关度排序，上限 200 不分页）；否则 = 浏览态分页。
// 数据源/健康度下拉变更即时生效（浏览态走后端参数，搜索态同样传给后端）。
import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {Table, Tooltip} from 'antd';
import {HiOutlineArrowRightOnRectangle, HiOutlineUser} from 'react-icons/hi2';
import {
    browseAssets,
    deleteClassification,
    getAssetClassifications,
    assignTableClassification,
    searchAssets,
} from '../../api/asset';
import {listMetadataDatasourceIds} from '../../api/metadata';
import ConfirmDialog from '../../components/ConfirmDialog';
import DsButton from '../../components/DsButton';
import DsFilterSelect from '../../components/DsFilterSelect';
import DsIconButton from '../../components/DsIconButton';
import DsTableEmpty from '../../components/DsTableEmpty';
import DsToolbar from '../../components/DsToolbar';
import Pagination from '../../components/Pagination';
import SearchInput from '../../components/SearchInput';
import {GOVERNANCE_WRITE_ROLES} from '../../constants/roles';
import {COL} from '../../constants/table';
import usePagedList from '../../hooks/usePagedList';
import {useHasRole} from '../../hooks/useHasRole';
import {notify} from '../../utils/notify';
import {QUALITY_HEALTH_OPTIONS} from '../../types/quality';
import type {AssetClassification, AssetClassificationTree, AssetSearchItem} from '../../types/asset';
import AssetTree, {ALL_SELECTION, selectionKey, selectionToQuery} from './AssetTree';
import type {AssetTreeSelection} from './AssetTree';
import {buildAssetColumns} from './assetColumns';
import AssignOwnerModal from './modals/AssignOwnerModal';
import AssignTablesModal from './modals/AssignTablesModal';
import ClassificationFormModal from './modals/ClassificationFormModal';
import type {ClassificationFormState} from './modals/ClassificationFormModal';

const SEARCH_LIMIT = 200;

export default function AssetsPage() {
    const navigate = useNavigate();
    const canWrite = useHasRole(...GOVERNANCE_WRITE_ROLES);
    const openDetail = useCallback((tableId: string) => navigate(`/asset-catalog/${tableId}`), [navigate]);

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
        reload,
        setPage,
        setPageSize,
    } = usePagedList({
        fetcher: (q) => browseAssets(q).then(r => ({list: r?.records ?? [], total: Number(r?.total ?? 0)})),
        initialQuery: selectionToQuery(ALL_SELECTION),
    });

    // 树选中 / 数据源 / 健康度变化 → 即时重新浏览（跳过与 initialQuery 重复的首跑）
    useEffect(() => {
        if (browseQueryRef.current.skipFirst) {
            browseQueryRef.current.skipFirst = false;
            if (selection.type === 'all' && !datasourceId && !healthLevel) return;
        }
        applyQuery({
            ...selectionToQuery(selection),
            datasourceId: datasourceId || undefined,
            healthLevel: healthLevel || undefined,
        });
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [selection, datasourceId, healthLevel]);

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
        setKeyword(keywordInput.trim());
    };

    const handleReset = () => {
        setKeywordInput('');
        setKeyword('');
        setDatasourceId('');
        setHealthLevel('');
        setSelection(ALL_SELECTION);
        applyQuery(selectionToQuery(ALL_SELECTION));
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
        const base = buildAssetColumns(openDetail);
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
    }, [openDetail, canWrite, isClassified, isSearch]);

    const tableData = isSearch ? searchList : browseList;
    const loading = isSearch ? searchLoading : browseLoading;
    const selectionLabel = selection.type === 'domain'
        ? (selection.domain ?? '')
        : selection.type === 'topic'
            ? `${selection.domain} / ${selection.topic}`
            : '';

    return (
        <div className="h-[calc(100vh-9rem)] flex flex-col overflow-hidden">
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
                {/* 左：分类树（治理员可编辑）；固定高度，树内独立滚动 */}
                <div
                    className="w-[260px] flex-shrink-0 min-h-0 overflow-y-auto bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle">
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

                {/* 右：表格卡片（工具栏/管理条/分页器钉住，表格区内部滚动） */}
                <div
                    className="flex-1 min-w-0 min-h-0 bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden flex flex-col">
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

                    <div className="flex-1 min-h-0 overflow-auto">
                        <Table
                            rowKey={(r) => r.tableId}
                            columns={columns}
                            dataSource={tableData}
                            loading={loading}
                            pagination={false}
                            scroll={{x: 1280}}
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
