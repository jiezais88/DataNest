// Sprint 7 F1：数据资产目录类型（对齐后端 governance AssetCatalogController DTO）
// 注意：后端 Long 统一序列化为 string；全局 non_null 配置下 null 字段整个键省略，
// 因此除主键/名称外全部按可选字段处理（如 qualityScore 缺省 = 未配置规则，展示「—」）。

/** 资产搜索/浏览结果项（对齐 AssetSearchItemDTO；score 仅 /assets/search 返回） */
export interface AssetSearchItem {
    tableId: string;
    tableName: string;
    tableComment?: string;
    databaseName?: string;
    schemaName?: string;
    datasourceId?: string;
    datasourceName?: string;
    datasourceType?: string;
    /** 0-100 分；未配置规则时缺省 */
    qualityScore?: number;
    /** EXCELLENT/GOOD/WARNING/BAD */
    healthLevel?: string;
    dataDomain?: string;
    dataTopic?: string;
    ownerUserId?: string;
    ownerName?: string;
    /** 相关度得分（表名 100+前缀 20 / 注释 60 / 字段 40 / 负责人 20），仅搜索接口返回 */
    score?: number;
    updatedAt?: string;
}

export type AssetClassificationLevel = 'DOMAIN' | 'TOPIC';

/** 分类树节点（对齐 AssetClassificationDTO；TOPIC 挂在 DOMAIN 的 children 下） */
export interface AssetClassification {
    id: string;
    level: AssetClassificationLevel;
    name: string;
    parentId?: string;
    sort?: number;
    children?: AssetClassification[];
    createdAt?: string;
    updatedAt?: string;
}

export interface ClassificationSaveRequest {
    level: AssetClassificationLevel;
    name: string;
    /** DOMAIN 必须为 null；TOPIC 必填且父级必须是 DOMAIN */
    parentId?: string | null;
    sort?: number;
}

/** 分配分类（传分类名而非 ID）；两者皆空 = 清除分类 */
export interface AssignClassificationRequest {
    dataDomain?: string | null;
    dataTopic?: string | null;
}

/** 分类浏览查询参数（GET /assets/browse） */
export interface AssetBrowseQuery {
    /** 数据域名（分类名，非 ID） */
    domain?: string;
    /** 主题名（需与 domain 同时传） */
    topic?: string;
    datasourceId?: string;
    /** true = 查未分类表，与 domain/topic 互斥（后端 uncategorized 优先） */
    uncategorized?: boolean;
    /** score = 质量分降序（null 排最后）；缺省按表名升序 */
    sort?: 'score';
    page?: number;
    pageSize?: number;
}
