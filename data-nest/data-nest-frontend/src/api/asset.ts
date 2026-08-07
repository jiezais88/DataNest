// Sprint 7 F1：数据资产目录 API（governance AssetCatalogController，/governance/assets/**）
// 读接口四角色可用；写接口（分类 CRUD / 分配分类 / 配置负责人）仅超管+治理员（后端兜底 1005）。
import request from './request';
import type {PageResult, Result} from '../types/common';
import type {
    AssetBrowseQuery,
    AssetClassification,
    AssetSearchItem,
    AssignClassificationRequest,
    ClassificationSaveRequest,
} from '../types/asset';

/** 多维搜索（表名/注释/字段/负责人），按相关度 score 降序，上限 200 条不分页 */
export const searchAssets = (keyword: string) =>
    request.get<Result<AssetSearchItem[]>>('/governance/assets/search', {params: {keyword}}).then(r => r.data);

/** 分类浏览分页（全部资产 / 域 / 主题 / 未分类 + 数据源筛选） */
export const browseAssets = (params: AssetBrowseQuery) =>
    request.get<Result<PageResult<AssetSearchItem>>>('/governance/assets/browse', {params}).then(r => r.data);

/** 分类树（只含 DOMAIN 根节点，TOPIC 挂 children） */
export const getAssetClassifications = () =>
    request.get<Result<AssetClassification[]>>('/governance/assets/classifications').then(r => r.data);

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

/** 配置/清除表负责人（ownerUserId 空 = 清除） */
export const assignTableOwner = (tableId: string, ownerUserId?: string | null) =>
    request.put<Result<null>>(`/governance/assets/tables/${tableId}/owner`, {ownerUserId: ownerUserId ?? null}).then(r => r.data);
