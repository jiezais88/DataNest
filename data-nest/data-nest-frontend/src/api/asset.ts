// Sprint 7 F1：数据资产目录 API（governance AssetCatalogController，/governance/assets/**）
// 读接口四角色可用；写接口（分类 CRUD / 分配分类 / 配置负责人）仅超管+治理员（后端兜底 1005）。
import request from './request';
import type {PageResult, Result} from '@/types/common';
import type {
    AssetBrowseQuery,
    AssetClassification,
    AssetClassificationTree,
    AssetCollaboration,
    AssetComment,
    AssetFavoriteItem,
    AssetFollowItem,
    AssetSearchFilter,
    AssetSearchItem,
    AssetTableTag,
    AssetTag,
    AssignClassificationRequest,
    ClassificationSaveRequest,
    MyAssetQuery,
} from '@/types/asset';

/** 多维搜索（表名/注释/字段/负责人），按相关度 score 降序，上限 200 条不分页；可选数据源/健康度过滤 */
export const searchAssets = (keyword: string, filter?: AssetSearchFilter) =>
    request.get<Result<AssetSearchItem[]>>('/governance/assets/search', {
        params: {keyword, datasourceId: filter?.datasourceId || undefined, healthLevel: filter?.healthLevel || undefined},
    }).then(r => r.data);

/** 分类浏览分页（全部资产 / 域 / 主题 / 未分类 + 数据源/健康度筛选） */
export const browseAssets = (params: AssetBrowseQuery) =>
    request.get<Result<PageResult<AssetSearchItem>>>('/governance/assets/browse', {params}).then(r => r.data);

/** 分类树（含各分类表计数 + 全部/未分类计数） */
export const getAssetClassifications = () =>
    request.get<Result<AssetClassificationTree>>('/governance/assets/classifications').then(r => r.data);

export const createClassification = (data: ClassificationSaveRequest) =>
    request.post<Result<AssetClassification>>('/governance/assets/classifications', data).then(r => r.data);

/** 改名会级联更新 metadata_table 冗余的 data_domain/data_topic，保存后需刷新树和列表 */
export const updateClassification = (id: string, data: ClassificationSaveRequest) =>
    request.put<Result<AssetClassification>>(`/governance/assets/classifications/${id}`, data).then(r => r.data);

/** 删除校验：仍被表引用或域下仍有主题时 4009（动态 message 由拦截器统一提示） */
export const deleteClassification = (id: string) =>
    request.delete<Result<null>>(`/governance/assets/classifications/${id}`).then(r => r.data);

/** 分配/清除表分类（传分类名；{dataDomain: null, dataTopic: null} = 清除） */
export const assignTableClassification = (tableId: string, data: AssignClassificationRequest) =>
    request.put<Result<null>>(`/governance/assets/tables/${tableId}/classification`, data).then(r => r.data);

/** 批量分配/清除分类（一次事务；返回实际更新表数） */
export const assignTablesClassificationBatch = (tableIds: string[], data: AssignClassificationRequest) =>
    request.put<Result<number>>('/governance/assets/tables/classification/batch', {tableIds, ...data}).then(r => r.data);

/** 配置/清除表负责人（ownerUserId 空 = 清除） */
export const assignTableOwner = (tableId: string, ownerUserId?: string | null) =>
    request.put<Result<null>>(`/governance/assets/tables/${tableId}/owner`, {ownerUserId: ownerUserId ?? null}).then(r => r.data);

// ==================== Sprint 8 F1：资产协作（DC-06~09，全角色可用） ====================

/** 标签字典（标签云，按绑定表数降序） */
export const listAssetTags = () =>
    request.get<Result<AssetTag[]>>('/governance/assets/tags').then(r => r.data);

/** 详情页协作状态聚合：标签 + 当前用户收藏/关注状态 + 近 30 天热度 + 有效评论数（头部一次拉取） */
export const getAssetCollaboration = (tableId: string) =>
    request.get<Result<AssetCollaboration>>(`/governance/assets/tables/${tableId}/collaboration`).then(r => r.data);

/** 打标签（输入即建，同名复用；返回表当前标签列表） */
export const addTableTag = (tableId: string, tagName: string) =>
    request.post<Result<AssetTableTag[]>>(`/governance/assets/tables/${tableId}/tags`, {tagName}).then(r => r.data);

/** 删表标签绑定（返回表当前标签列表） */
export const removeTableTag = (tableId: string, tagId: string) =>
    request.delete<Result<AssetTableTag[]>>(`/governance/assets/tables/${tableId}/tags/${tagId}`).then(r => r.data);

export const favoriteAsset = (tableId: string) =>
    request.post<Result<null>>(`/governance/assets/tables/${tableId}/favorite`).then(r => r.data);

export const unfavoriteAsset = (tableId: string) =>
    request.delete<Result<null>>(`/governance/assets/tables/${tableId}/favorite`).then(r => r.data);

export const followAsset = (tableId: string) =>
    request.post<Result<null>>(`/governance/assets/tables/${tableId}/follow`).then(r => r.data);

export const unfollowAsset = (tableId: string) =>
    request.delete<Result<null>>(`/governance/assets/tables/${tableId}/follow`).then(r => r.data);

/** 我的收藏（收藏时间倒序分页，支持关键词/数据源/健康度筛选） */
export const getMyFavorites = (params: MyAssetQuery) =>
    request.get<Result<PageResult<AssetFavoriteItem>>>('/governance/assets/my-favorites', {params}).then(r => r.data);

/** 导出我的收藏 CSV（Blob，与列表同一套筛选条件） */
export const exportMyFavorites = (params: Omit<MyAssetQuery, 'page' | 'pageSize'>) =>
    request.get<Blob>('/governance/assets/my-favorites/export', {params, responseType: 'blob'});

/** 我的关注（关注时间倒序分页，每表附最近一次变更动态） */
export const getMyFollows = (params: MyAssetQuery) =>
    request.get<Result<PageResult<AssetFollowItem>>>('/governance/assets/my-follows', {params}).then(r => r.data);

/** 评论列表（时间倒序分页） */
export const listAssetComments = (tableId: string, page: number, pageSize: number) =>
    request.get<Result<PageResult<AssetComment>>>(`/governance/assets/tables/${tableId}/comments`, {
        params: {page, pageSize},
    }).then(r => r.data);

/** 发表评论（≤2000 字），返回带用户名的评论项 */
export const addAssetComment = (tableId: string, content: string) =>
    request.post<Result<AssetComment>>(`/governance/assets/tables/${tableId}/comments`, {content}).then(r => r.data);

/** 删除评论（作者可删自己的；治理员/超管可删任意，越权后端 4023 兜底） */
export const deleteAssetComment = (commentId: string) =>
    request.delete<Result<null>>(`/governance/assets/comments/${commentId}`).then(r => r.data);

/** 热度埋点（前端会话级去重后调用；失败静默，不打扰浏览） */
export const recordAssetView = (tableId: string) =>
    request.post<Result<null>>(`/governance/assets/tables/${tableId}/view`, null, {skipErrorMessage: true}).then(r => r.data);

/** 热门数据表 Top N（近 30 天热度降序，默认 10） */
export const getHotTables = (limit = 10) =>
    request.get<Result<AssetSearchItem[]>>('/governance/assets/hot-tables', {params: {limit}}).then(r => r.data);
