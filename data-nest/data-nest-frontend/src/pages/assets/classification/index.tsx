// Sprint 7 F1：分类体系维护页（DC-05 写操作，仅治理员/超管）
// 左树（可编辑：新增/改名/删除）+ 右侧该分类下的表（分配负责人 / 移出分类 / 批量分配）。
// 删除分类被引用或域下仍有主题时后端报 4009，动态 message 由拦截器统一提示。
import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {Table, Tooltip} from 'antd';
import {HiOutlineArrowRightOnRectangle, HiOutlineUser} from 'react-icons/hi2';
import {browseAssets, deleteClassification, getAssetClassifications, assignTableClassification} from '../../../api/asset';
import ConfirmDialog from '../../../components/ConfirmDialog';
import DsButton from '../../../components/DsButton';
import DsIconButton from '../../../components/DsIconButton';
import DsTableEmpty from '../../../components/DsTableEmpty';
import EmptyState from '../../../components/EmptyState';
import Pagination from '../../../components/Pagination';
import {GOVERNANCE_WRITE_ROLES} from '../../../constants/roles';
import {COL} from '../../../constants/table';
import usePagedList from '../../../hooks/usePagedList';
import {useHasRole} from '../../../hooks/useHasRole';
import {notify} from '../../../utils/notify';
import type {AssetClassification, AssetSearchItem} from '../../../types/asset';
import AssetTree, {ALL_SELECTION, selectionKey, selectionToQuery} from '../AssetTree';
import type {AssetTreeSelection} from '../AssetTree';
import {buildAssetColumns} from '../assetColumns';
import AssignOwnerModal from '../modals/AssignOwnerModal';
import AssignTablesModal from '../modals/AssignTablesModal';
import ClassificationFormModal from '../modals/ClassificationFormModal';
import type {ClassificationFormState} from '../modals/ClassificationFormModal';

export default function AssetClassificationPage() {
    const navigate = useNavigate();
    const canWrite = useHasRole(...GOVERNANCE_WRITE_ROLES);
    const openDetail = useCallback((tableId: string) => navigate(`/asset-catalog/${tableId}`), [navigate]);

    // ============ 分类树 ============
    const [tree, setTree] = useState<AssetClassification[]>([]);
    const [selection, setSelection] = useState<AssetTreeSelection>(ALL_SELECTION);

    const loadTree = useCallback(() => {
        getAssetClassifications()
            .then(list => setTree(list ?? []))
            .catch(() => setTree([]));
    }, []);

    useEffect(() => {
        loadTree();
    }, [loadTree]);

    // ============ 分类下表列表 ============
    const skipFirstRef = useRef(true);
    const {
        list,
        total,
        page,
        pageSize,
        loading,
        applyQuery,
        reload,
        setPage,
        setPageSize,
    } = usePagedList({
        fetcher: (q) => browseAssets(q).then(r => ({list: r?.records ?? [], total: Number(r?.total ?? 0)})),
        initialQuery: selectionToQuery(ALL_SELECTION),
    });

    useEffect(() => {
        if (skipFirstRef.current) {
            skipFirstRef.current = false;
            if (selection.type === 'all') return;
        }
        applyQuery(selectionToQuery(selection));
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [selection]);

    // ============ 弹窗状态 ============
    const [formState, setFormState] = useState<ClassificationFormState>({mode: 'create'});
    const [formOpen, setFormOpen] = useState(false);
    const [deleting, setDeleting] = useState<AssetClassification | null>(null);
    const [deleteLoading, setDeleteLoading] = useState(false);
    const [assignOpen, setAssignOpen] = useState(false);
    const [ownerTarget, setOwnerTarget] = useState<AssetSearchItem | null>(null);
    const [removing, setRemoving] = useState<AssetSearchItem | null>(null);
    const [removeLoading, setRemoveLoading] = useState(false);

    const isClassified = selection.type === 'domain' || selection.type === 'topic';
    const selectionLabel = selection.type === 'all'
        ? '全部资产'
        : selection.type === 'domain'
            ? (selection.domain ?? '')
            : `${selection.domain} / ${selection.topic}`;

    const findDomain = (name?: string) => tree.find(d => d.name === name);

    // ============ 分类 CRUD ============
    const handleEdit = (node: AssetClassification, parent?: AssetClassification) => {
        setFormState({mode: 'edit', node, parent});
        setFormOpen(true);
    };

    const handleDelete = async () => {
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

    // ============ 表操作 ============
    const handleRemove = async () => {
        if (!removing) return;
        setRemoveLoading(true);
        try {
            await assignTableClassification(removing.tableId, {dataDomain: null, dataTopic: null});
            notify.success(`已将「${removing.tableName}」移出分类`);
            reload();
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
        return [
            ...base,
            {
                title: '操作',
                key: 'action',
                width: COL.OPERATION_2,
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
                        {isClassified && (
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
    }, [openDetail, isClassified]);

    if (!canWrite) {
        return (
            <EmptyState
                title="无权限访问"
                description="分类体系维护仅治理管理员 / 超级管理员可用。"
            />
        );
    }

    return (
        <div className="flex flex-col">
            <div className="flex items-center justify-between mb-ds-5 flex-shrink-0">
                <div>
                    <h1 className="text-ds-display text-ds-text-primary">分类体系</h1>
                    <p className="text-ds-small text-ds-text-muted mt-ds-1">
                        维护数据域（一级）→ 主题（二级）分类体系，并将表分配到分类、配置负责人
                    </p>
                </div>
                <DsButton onClick={() => {
                    setFormState({mode: 'create'});
                    setFormOpen(true);
                }}>
                    新增数据域
                </DsButton>
            </div>

            <div className="flex gap-ds-4 items-start">
                {/* 左：分类树（可编辑） */}
                <div
                    className="w-[260px] flex-shrink-0 bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle">
                    <AssetTree
                        tree={tree}
                        selectedKey={selectionKey(selection)}
                        onSelect={setSelection}
                        showUncategorized={false}
                        editable
                        onEdit={handleEdit}
                        onDelete={setDeleting}
                    />
                </div>

                {/* 右：分类下的表 */}
                <div
                    className="flex-1 min-w-0 bg-ds-bg-surface rounded-ds-md shadow-ds-xs border border-ds-border-subtle overflow-hidden flex flex-col">
                    <div
                        className="flex items-center gap-ds-3 p-ds-3 border-b border-ds-border-subtle flex-shrink-0">
                        <span className="text-ds-small text-ds-text-secondary">
                            当前分类：<span className="text-ds-text-primary font-semibold">{selectionLabel}</span>
                            <span className="text-ds-text-muted">（{total} 张表）</span>
                        </span>
                        <div className="flex items-center gap-ds-2 ml-auto">
                            {isClassified && (
                                <>
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
                                </>
                            )}
                        </div>
                    </div>

                    <div className="overflow-x-auto">
                        <Table
                            rowKey={(r) => r.tableId}
                            columns={columns}
                            dataSource={list}
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
                                        description={isClassified
                                            ? '该分类下暂无数据表，可点击右上「分配表到此分类」'
                                            : '暂无数据表'}
                                    />
                                ),
                            }}
                        />
                    </div>

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

                    {isClassified && (
                        <div className="px-ds-4 py-ds-2 border-t border-ds-border-subtle text-ds-tiny text-ds-text-muted">
                            删除「{selection.topic ?? selection.domain}」前需先移出全部引用表（删除时后端会校验引用）
                        </div>
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
                onConfirm={handleDelete}
                onCancel={() => setDeleting(null)}
            />

            {/* 批量分配表到当前分类 */}
            {isClassified && (
                <AssignTablesModal
                    open={assignOpen}
                    domain={selection.domain ?? ''}
                    topic={selection.type === 'topic' ? selection.topic : undefined}
                    onClose={() => setAssignOpen(false)}
                    onSaved={reload}
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
                onSaved={reload}
            />

            {/* 移出分类确认 */}
            <ConfirmDialog
                open={!!removing}
                title="移出分类"
                message={`确认将「${removing?.tableName}」移出分类「${selectionLabel}」？`}
                confirmLabel="移出"
                danger
                loading={removeLoading}
                onConfirm={handleRemove}
                onCancel={() => setRemoving(null)}
            />
        </div>
    );
}
