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
    /** 相关度得分（表名 100+前缀 20 / 注释 60 / 字段 40 / 标签 40 / 负责人 20），仅搜索接口返回 */
    score?: number;
    /** 表标签名数组（Sprint 8 DC-06，搜索/浏览回填；无标签为空数组） */
    tags?: string[];
    /** 最近 30 天访问数（Sprint 8 DC-09，搜索/浏览/收藏/关注/热门全场景回填，无访问为 0） */
    viewCount?: number;
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
    /** 该分类下的 ONLINE 表数（域含其下所有主题） */
    tableCount?: number;
    children?: AssetClassification[];
    createdAt?: string;
    updatedAt?: string;
}

/** 分类树响应（对齐 AssetClassificationTreeDTO）：树 + 全部/未分类计数 */
export interface AssetClassificationTree {
    list: AssetClassification[];
    totalCount?: number;
    uncategorizedCount?: number;
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
    /** 健康度 EXCELLENT/GOOD/WARNING/BAD（后端经 quality_score 反查过滤） */
    healthLevel?: string;
    /** true = 查未分类表，与 domain/topic 互斥（后端 uncategorized 优先） */
    uncategorized?: boolean;
    /** score = 质量分降序；hot = 近 30 天热度降序；latest = 元数据更新时间降序；缺省按表名升序 */
    sort?: 'score' | 'hot' | 'latest';
    /** 标签名（按标签筛选，传名不传 ID） */
    tag?: string;
    page?: number;
    pageSize?: number;
}

/** 资产搜索过滤条件（GET /assets/search 可选参数） */
export interface AssetSearchFilter {
    datasourceId?: string;
    healthLevel?: string;
}

// ==================== Sprint 8 F1：资产协作（DC-06~09） ====================

/** 标签字典项（GET /assets/tags 标签云） */
export interface AssetTag {
    tagId: string;
    tagName: string;
    /** 绑定该标签的表数 */
    refCount?: number;
}

/** 表标签项（某表当前绑定的标签） */
export interface AssetTableTag {
    tagId: string;
    tagName: string;
}

/** 详情页协作状态聚合（GET /assets/tables/{id}/collaboration，头部一次拉取） */
export interface AssetCollaboration {
    tags?: AssetTableTag[];
    favorited?: boolean;
    followed?: boolean;
    /** 最近 30 天访问数 */
    viewCount30d?: number;
    /** 有效评论数 */
    commentCount?: number;
}

/** 评论列表项（username 已由后端回填：用户注销显示「已注销」，用户服务不可用降级「—」） */
export interface AssetComment {
    commentId: string;
    tableId: string;
    userId: string;
    username?: string;
    content: string;
    createdAt?: string;
}

/** 我的收藏/我的关注通用筛选（GET /assets/my-favorites|my-follows） */
export interface MyAssetQuery {
    /** 关键词（表名/注释模糊） */
    keyword?: string;
    datasourceId?: string;
    healthLevel?: string;
    page?: number;
    pageSize?: number;
}

/** 我的收藏列表项（资产卡片字段 + 收藏时间） */
export interface AssetFavoriteItem extends AssetSearchItem {
    favoritedAt?: string;
}

/** 表变更动态（复用 collect_change_detail 原始字段，前端按类型渲染摘要） */
export interface AssetChange {
    /** ADDED_TABLE/DELETED_TABLE/MODIFIED_TABLE/ADDED_COLUMN/DELETED_COLUMN/MODIFIED_COLUMN_* */
    changeType?: string;
    columnName?: string;
    oldValue?: string;
    newValue?: string;
    changeTime?: string;
}

/** 我的关注列表项（资产卡片字段 + 关注时间 + 最近一次变更动态） */
export interface AssetFollowItem extends AssetSearchItem {
    followedAt?: string;
    latestChange?: AssetChange;
}
