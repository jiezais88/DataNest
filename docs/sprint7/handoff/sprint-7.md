# Sprint 7 Handoff

> **更新时间**：2026-08-07 | **阶段**：F1 前端+联调完成（含修订轮：合并单页 + 后端补 4 接口，冒烟全绿）→ 待 F1 E2E 或并行启动 F2
> **Sprint 主题**：数据资产目录（+ 三项轻量增强）

## 1. Sprint 目标

让数据分析师拥有一站式数据资产发现入口——找得到、看得懂、敢使用。在现有治理能力（元数据采集、血缘可视化、数据质量管理）之上，扩展**数据搜索、数据详情聚合、血缘与质量联动、按数据域分类浏览**五项 P0 能力，并顺带交付三项轻量增强（子 DAG 参数下发、任务模板库、自定义质量规则 Python）。

## 2. 状态看板

| 事项                                     | 状态      | 说明                                                                                          |
|------------------------------------------|-----------|-----------------------------------------------------------------------------------------------|
| Sprint 7 产品范围确认                    | ✅ 完成   | 用户确认：资产目录为主 + 三项增强（NG5/DD-09/DG-10）                                          |
| Sprint 7 产品决策澄清                    | ✅ 完成   | 见「关键决策」                                                                                |
| Sprint 7 代码现状调查                    | ✅ 完成   | 资产目录从零基于 governance 扩展；血缘/质量/搜索/子DAG/PythonExecutor 复用点已核验             |
| Sprint 7 PRD                             | ✅ 完成   | `docs/sprint7/DataNest-Sprint7-PRD.md`（v1.0）                                                |
| Sprint 7 技术设计                        | ✅ 完成   | `docs/sprint7/DataNest-Sprint7-技术文档.md`（v1.0，含 4 个技术决策 D1~D4）                     |
| Sprint 7 UI 原型                         | ✅ 对齐   | 已对照真实前端 token/组件逐项修正（见 §4 变更清单），可参考 Sprint6 原型约定                      |
| **F1 资产目录**（P0：搜索/详情/血缘/质量/分类） | ✅ 前端完成 | 后端 ✅ + 前端 ✅（2026-08-07，含修订轮：合并单页 + 后端补接口，联调冒烟全绿，见 §6.1）；E2E 待后续会话                |
| **F2 任务模板库**（DD-09）               | ⏳ 未开始 | 前后端+测试闭环（见 §6.2）                                                                     |
| **F3 子 DAG 参数下发**（NG5）            | ⏳ 未开始 | 前后端+测试闭环（见 §6.3）                                                                     |
| **F4 Python 质量规则**（DG-10）          | ⏳ 未开始 | 前后端+测试闭环（见 §6.4，最复杂放最后）                                                       |
| 联调验证                                 | ⏳ 未开始 | 每块内部：接口先 Postman/curl 自测，再联调前端，再 E2E                                              |
| Sprint 7 Handoff                         | 🔄 进行中 | 本文档（规划/设计阶段记录）                                                                   |

## 3. 关键决策（用户已确认）

### 产品决策（2026-08-05 确认）

| 决策点             | 结论                                                                 |
|--------------------|----------------------------------------------------------------------|
| 文档主题边界       | 资产目录为主 + 三项增强（NG5 子DAG参数透传 / DD-09 任务模板库 / DG-10 自定义质量规则） |
| S6 遗留 UI 改造    | 已实现，不纳入 S7 PRD（质量规则先选数据源再选表、行内级联选表、触发方式单选、去?tab=jobs） |
| 资产目录交付深度   | P0 五项全做（DC-01 搜索 / DC-02 详情 / DC-03 血缘嵌入 / DC-04 质量展示 / DC-05 分类浏览） |
| DG-10 边界         | A+B：新增 Python 规则类型 + 强化自定义 SQL（模板化/参数化/多指标）    |
| 资产目录落点       | 复用 governance 扩展，不新建独立 catalog-service                      |
| NG5 交付目标       | 仅主→子参数下发，不做子 DAG 结果回传                                 |

### 技术决策（2026-08-05，由我基于代码现状定，落地于技术文档）

| #  | 决策点               | 结论                                                                                                          |
|----|----------------------|---------------------------------------------------------------------------------------------------------------|
| D1 | 资产目录落点         | 复用 governance 扩展（`AssetCatalogController`/`AssetCatalogService`），不新建服务                            |
| D2 | 血缘嵌入             | 资产详情页血缘页签复用 `getLineageGraph` 数据 API + 精简 ReactFlow 自绘，**不改造现有 `LineageGraphPage`**（零影响） |
| D3 | 多维搜索接口         | **新增独立 `/assets/search`** 扁平结果接口，保留 `search-tree` 不动（血缘双击跳转等现有调用点零影响）         |
| D4 | Python 质量规则执行  | 复用 `PythonExecutor` 沙箱内核（临时目录/ulimit/超时），质量脚本用 `def check(df)` 独立约定；**数据拉取方案 B（通用连接注入）**——连接注入层从 Doris 写死抽象为通用 `conn.json` + 沙箱 `read_table` helper，用户确认 |

## 4. 变更清单（规划/设计阶段）

| 文档/产物                                                             | 变更说明                                                                                              |
|------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------|
| **F1 后端实现（2026-08-06，本段为开发阶段新增）**                      | 见下方「F1 后端变更明细」                                                                             |
| **F1 前端实现（2026-08-07，含当日修订轮）**                              | 见 §6.1「前端」+「F1 修订轮」：合并单页（方案 A）；后端补 4 接口（树计数/healthLevel/批量分配/用户选项）+ Doris 回显 + 2 处 `MetadataService` 补丁 |
| `docs/sprint7/DataNest-Sprint7-PRD.md`（新增）                        | Sprint 7 数据资产目录产品文档 v1.0（12 章，对齐 Sprint6 PRD 范本；复用 vs 新增边界经代码核验）        |
| `docs/sprint7/DataNest-Sprint7-技术文档.md`（新增）                   | Sprint 7 技术设计文档 v1.0（11 章，含 4 个技术决策、数据模型、接口、实现清单）；v1.1 补充 Python 数据拉取方案 B（通用连接注入） |
| `docs/sprint7/handoff/sprint-7.md`（新增）                            | 本 Handoff                                                                                            |
| `docs/sprint7/DataNest-Sprint7-资产目录原型.{css,html,js}`（修正）    | UI 原型高保真对齐真实前端：token/组件/侧边栏/表格/徽章等逐项修正（见下「原型对齐要点」）                |

**F1 后端变更明细（2026-08-06，curl 自测通过）**
- `data-nest-system/.../db/migration/V3.8.0__sprint7_asset_catalog.sql`（新增）：`asset_classification` 表（uk(level,name) + 2 索引，`updated_at` 无默认值）+ `metadata_table` 加 `data_domain`/`data_topic`/`owner_user_id`。已应用（flyway_schema_history 3.8.0 success）。
- `data-nest-task-core-entity`：`MetadataTable` 加 3 持久化字段 + `ownerName`（exist=false）；新增 `AssetClassification` entity（含 LEVEL_DOMAIN/LEVEL_TOPIC 常量、children exist=false）+ `AssetClassificationMapper`；`MetadataTableMapper` 两处手写列清单加新列 + 新增 `searchAssetTables`（多维搜索，script foreach）；`MetadataColumnMapper.selectTableIdsByColumnKeyword`；`SysUserMapper.selectIdsByUsernameKeyword` + `SysUserService.findUserIdsByNameKeyword`。
- `data-nest-task-core-governance`：`QualityScoreService.mapByTableIds(Collection<Long>)` 批量方法。
- `data-nest-common`：`ErrorCode` 治理段新增 4007/4008/4009/4010。
- `data-nest-governance`：`AssetCatalogController`（`/assets`，8 端点）+ `AssetCatalogService` + DTO×5（`AssetSearchItemDTO`/`AssetClassificationDTO`/`ClassificationSaveRequest`/`AssignClassificationRequest`/`AssignOwnerRequest`）。
- `data-nest/shared-configs/shared-common.yaml`：`datanest.asset.search.max-results: 200`（`@Value` 默认值兜底，未刷 Nacos 库）。
- 与技术文档的偏差：① 分类重命名策略经用户确认为**级联更新** metadata_table 冗余名（技术文档 §3.1 只写了删除校验）；② `browse` 增加 `uncategorized=true` 参数实现「未分类」浏览（PRD §6.4）；③ 搜索得分实现为表名 100（前缀 +20）/注释 60/字段 40/负责人 20，与技术文档 §4.1 示例权重一致。
- 部署：全量 `mvn clean package` + 重建 app-system/governance/engineering/worker/job 并 up，全部 healthy（镜像时间戳已核验）。
- 自测环境残留数据：`asset_classification` 有「交易域（含主题：订单）/用户域」；`metadata_table` 的 orders/order_items 已分配交易域·订单，orders 负责人=admin。**E2E 种子数据可直接复用或清理后重建**。

**原型对齐要点（2026-08-05-06，对照真实前端源码 + 自动化截图逐项修正）**
- **自动化截图对比**：曾用临时 Playwright Python 脚本（已删）截真实前端 3 页（`/governance/metadata` 展开树、`/governance/data-quality`、`/governance/quality-templates`）+ 截原型 5 视图（assets/classification/task-template/subdag/python-rule）逐屏对比。脚本核心要点已固化到 `docs/agent/prototype-guide.md §7`（UI 登录、press Enter 提交、flex 滚动诊断等）。
- **截图对比结论**：核心对齐已达成 — 侧边栏 `#334155` + indigo active、卡片圆角 12px、表头 uppercase + bg-root、工具栏查询按钮顺序、行高紧凑、质量徽章中文两段式、**数据库品牌图标（Doris 圆柱 / MySQL 多边形）**、**LogoMark 三层渐变六边形**、**极简分页**（上一页/1/下一页）、**操作列 icon-btn 风格**、**info notice 浅紫底**，与真实前端几乎一致。

**2026-08-06 残余差距补齐（自动化截图二次迭代）**
- **数据库品牌图标**：替换 `db-badge` 字母方块为真实 `i-doris` 圆柱 SVG（`#1E90FF` 蓝）和 `i-mysql` 多边形 SVG（`#4479A1`），对齐 `DatabaseTypeIcon` 组件
- **LogoMark 升级**：3 层六边形 + indigo 渐变（`#4f46e5 → #818cf8`），对齐 `LogoMark` 组件
- **极简分页**：View 1 分页从"5 个页码 + 上下页"简化为"上一页/1/下一页"，对齐真实 antd Pagination 紧凑模式
- **视图切换修复**：截图脚本 `data-view` 名修正为单数 `task-template`（匹配 HTML），5 个视图全部正确截图
- **Python view 滚动修复**：`.info-card { overflow:hidden }` 默认 `flex-shrink:1` 被 layout-main-inner flex 压缩，内部溢出 494px 被静默裁切（看似没滚动条）。修复：加 `flex-shrink:0` 让 info-card 自然撑高，inner 出现滚动条（sh=1338, ch=844，溢出 494px 可滚）。**坑已固化到 `docs/agent/prototype-guide.md §7 flex 容器滚动陷阱`**。

**原型对齐要点（2026-08-05，对照真实前端源码逐项修正）**
- **颜色 token**：accent 由 blue `#1d4ed8` 改 indigo `#4f46e5`（品牌主色，最关键差距）；bg-root `#eef0f7`、border `#d8dee8`、text-secondary `#475569`、text-muted `#64748b` 全部对齐 `src/styles/tokens.css`。
- **侧边栏**：`#111827` 近黑 → `#334155` slate-700 中等深 + slate-600 边框；item `py-9px rounded-lg`、active `bg-accent/25 text-white font-semibold`、group-label 11px uppercase。
- **圆角/阴影**：radius-sm 6→8px、md 8→12px、lg 10→16px；shadow-xs 对齐 `0 1px 2px rgba(0,0,0,.06)`。
- **表格**（antd）：表头 `bg-root` + `2px` 底边框 + `11px uppercase letter-spacing .5px`；行 `10px 16px` 紧凑 + `1px` 底边框 + hover bg-hover。
- **质量徽章**：英文 `85 GOOD` → 真实中文两段式「数字(13px粗体) + 胶囊(11px)」（良好/优秀/一般/差），`QUALITY_HEALTH_LABEL` 对齐 `types/quality.ts`。
- **DsButton/DsToolbar**：按钮 `px-16px py-8px 13px 600`；工具栏 `p-12px gap-12px`，查询在前/重置在后。
- **SearchInput/DsFilterSelect**：搜索框 `bg-root 浅灰底 py-9px min-w-240 max-w-360`；筛选 `min-w-140 py-8px`。
- **数据源徽章**：字母方块改品牌色（Doris `#1e90ff`、MySQL `#4479a1`）。
- **Pagination**：无边框数字钮，active `bg-accent`，左「共N条」右页码 justify-between。
- **DsModal**：content `padding 36px radius 16px`、标题 18px 700（对齐 `prototype-modal`）。

## 5. Blocker / 待实现确认点

| #  | 事项                                | 说明                                                                      | 状态   |
|----|-------------------------------------|---------------------------------------------------------------------------|--------|
| B1 | Python 质量规则数据拉取            | ✅ 已消解（方案 B，用户确认）：`PythonExecutor` 连接注入层抽象为通用连接注入（`conn.json`）+ 沙箱 `read_table(table, where, limit)` 按数据源 type 选驱动；不再用 `GenericSqlExecutor`（5s 超时+200 行截断不适合） | 明确 |
| B2 | 资产详情页路由与元数据详情页关系    | `/assets/:tableId` 独立路由，与现有 `/governance/metadata?tableId=` 双入口并存 | 明确   |
| B3 | 分类体系删除校验 SQL                | 删除分类时按 `data_domain`/`data_topic` 匹配 `metadata_table` 引用        | 明确   |
| B4 | 任务模板 config_template JSON 结构  | 同步/SQL/导出/采集四类任务的 config_template 具体字段占位                 | 待细化 |

## 6. 开发分阶段计划

> **划分原则**：按功能块切分，**每块 = 后端 → 前端 → 测试 完整闭环**，全部验证通过后再进入下一块。不做"先全部后端、再全部前端"的横切。
> **顺序**：F1（P0 主功能）优先；F2/F3/F4 为顺带增强，按**从易到难**（纯新增独立 → 改共享实体但简单 → 需 task-core + worker 镜像改造）。
> **每块验证口径**：① 后端 Postman/curl 自测 → ② 前端联调 → ③ 新建 `e2e/sprint7/e2e/*.spec.ts` 跑通 → ④ 更新本 Handoff 状态看板。

---

### ✅ 已完成（规划/设计）

- [x] Sprint 7 PRD（`DataNest-Sprint7-PRD.md` v1.0）
- [x] Sprint 7 技术设计（`DataNest-Sprint7-技术文档.md` v1.0）
- [x] 代码现状核验：搜索仅表名、子DAG不支持透传、质量类型无PYTHON、元数据无分类/负责人字段、PythonExecutor 沙箱已存在、Flyway 最高 V3.7.1
- [x] UI 原型高保真对齐（见 §4）

---

### 6.1 F1 资产目录（P0，核心块）🔄 后端完成（2026-08-06）

**范围**：DC-01 搜索 / DC-02 详情聚合 / DC-03 血缘嵌入 / DC-04 质量展示 / DC-05 分类浏览。
**块内依赖**：Flyway → task-core-entity → governance 服务 → 前端 3 页 → 联调。

**后端**（✅ 全部完成，curl 自测通过）
- [x] Flyway `V3.8.0`：`asset_classification` 表 + `metadata_table` 加 `data_domain`/`data_topic`/`owner_user_id`（已应用，库中验证成功）
- [x] task-core-entity：`MetadataTable` 扩展 3 字段 + `ownerName`（exist=false）；新增 `AssetClassification` entity/mapper；`MetadataTableMapper` 两个手写 `@Select` 列清单已同步加新列 + 新增 `searchAssetTables`（多维搜索 SQL）；`MetadataColumnMapper.selectTableIdsByColumnKeyword`；`SysUserService.findUserIdsByNameKeyword`
- [x] task-core-governance：`QualityScoreService.mapByTableIds`（批量回填，避免 N+1）
- [x] common：`ErrorCode` 新增 4007 CLASSIFICATION_NOT_FOUND / 4008 NAME_EXISTS / 4009 IN_USE / 4010 PARENT_INVALID
- [x] governance `AssetCatalogService`/`AssetCatalogController`（`/assets`）：`GET /search`（五维命中 + 相关度：表名 100+前缀 20/注释 60/字段 40/负责人 20）、分类树/CRUD（**改名级联更新 metadata_table 冗余名，用户确认**；删除校验引用 4009）、`GET /browse`（domain/topic/datasourceId/uncategorized/sort=score，分页）、`PUT /tables/{id}/classification`、`PUT /tables/{id}/owner`（类级四角色 OR，写接口治理员/超管）
- [x] `shared-configs/shared-common.yaml` 加 `datanest.asset.search.max-results: 200`（`@Value` 默认值兜底，Nacos 库未刷）
- [x] **全量重建并部署** app-system（Flyway V3.8.0 ✅）+ governance/engineering/worker/job，全部 healthy，镜像时间戳已确认
- [x] curl 自测全过：分类 CRUD/树、4007/4008/4009/4010 校验、四维搜索命中与 score 排序（前缀 120>表名 100>注释 60>字段 40>负责人 20）、质量分/健康度/负责人名/数据源回填、browse 分页/sort=score/未分类、改名级联（交易域→交易→改回均一致）、删除引用拦截 + 无引用可删、无 token 1004

**前端**（✅ 全部完成，2026-08-07 联调冒烟全绿；含当日修订轮）
- [x] `Sidebar.tsx` 新增「数据资产」组（单入口 ALL_ROLES）+ `router/index.tsx` 新增 `/asset-catalog`、`/asset-catalog/:tableId`
- [x] 数据资产首页（左树右表：分类树带计数 + 搜索/浏览双态表格，复用 `QualityScoreBadge`/`DsStatusBadge`/`Pagination`/`usePagedList`）
- [x] 资产详情页：三指标卡（质量评分/字段数/直接上下游表数，砍「更新频率」——无数据源）+ 四页签懒加载（基础信息/字段/血缘/质量；血缘页签复用 `getLineageGraph` + 精简 ReactFlow 自绘 `AssetLineageTab`，不改造现有 `LineageGraphPage`）
- [x] 分类维护**合并进首页**（方案 A，用户确认 2026-08-07：原独立「分类体系」页与首页产品定位混淆，已删页/删路由/删侧边栏项）：治理员在首页直接看到树编辑/删除、新增数据域/主题、分配表到分类、操作列（负责人/移出），按 `GOVERNANCE_WRITE_ROLES` 显隐
- [x] 新增 `src/types/asset.ts` + `src/api/asset.ts`（端点封装）；`types/metadata.ts` 补 `dataDomain/dataTopic/ownerUserId/ownerName`

**F1 修订轮（2026-08-07，用户反馈驱动：两页定位混淆 + 下拉不即时 + Doris 不回显 + 原型差距）**

后端补充（governance/system，curl 自测全过，已重建部署）：
- `GET /assets/classifications` 响应改为 `{list, totalCount, uncategorizedCount}`，节点带 `tableCount`（一次 GROUP BY 聚合，ONLINE 口径与 browse 一致）。**坑**：PostgreSQL 未加引号的驼峰别名会被折叠成小写，`AS dataDomain` 的 map key 取不到 → 别名用下划线小写。
- `GET /assets/browse` 加 `healthLevel` 参数（`QualityScoreService.findTableIdsByHealthLevel` 反查表 ID 拼 IN，无命中直接空页）；`GET /assets/search` 加 `datasourceId`/`healthLevel` 可选过滤。
- 新增 `PUT /assets/tables/classification/batch`（`tableIds[]` + domain/topic，一次校验一条 UPDATE，返回更新数）→ 前端不再循环单表 PUT。
- 新增 `GET /system/users/options`（`UserSelectorController`，超管/治理员）：全部启用用户轻量选项，**不要求邮箱**（替代复用 with-email 的限制）；负责人选择器已切换。
- 内置 Doris 回显：`toItemDTO` 按 `source_type=BUILTIN_DORIS` 兜底「Doris 数仓 / DORIS」（engineering 无连接记录）；资产详情页基础信息同口径前端兜底。
- 前端即时筛选：数据源/健康度下拉变更即查（不再要求点查询按钮），搜索态同样传后端。

其余偏差记录：
- **路由 `/assets` → `/asset-catalog`**（用户确认）：nginx `location /assets/` 是 Vite 静态产物目录；PRD/技术文档已同步。
- 树计数/健康度筛选/批量接口/负责人口径 → 本轮已全部补齐，不再是偏差。
- 后端补丁（`MetadataService.applyUsernameNames`）：`getTable` 补 `ownerName` 回填；修存量 NPE（immutable map `get(null)`，自动采集表 created_by 为 null 时详情 500），三处判空。

**测试**（后端自测 ✅ / 联调 ✅ / E2E ⏳）
- [x] 后端 Postman/curl 自测：`/assets/search`、分类 CRUD、分配分类/负责人、改名级联、删除校验 + 修订轮（树计数/healthLevel/search datasourceId/批量/用户选项/Doris 回显）
- [x] 前端联调冒烟（临时 Playwright 脚本，用完即删，共 5 轮）：合并页计数徽章、下拉即时过滤（BAD→1 行 orders）、管理条、Doris 回显、三指标卡、分类增删、批量分配+移出还原、负责人清除/重设（新选项接口）、删除被引用分类 4009 拦截——全绿无控制台错误，种子数据已还原
- [ ] 新建 `e2e/sprint7/e2e/asset-catalog.spec.ts`（搜索→详情→分类 主链路）
- [ ] F1 全块闭环后 §2 看板置 ✅（E2E 完成后）

---

### 6.2 F2 任务模板库（DD-09）⏳ 未开始

**范围**：任务模板 CRUD + 一键创建。
**块内依赖**：Flyway → task-core-entity → engineering 服务 → 前端 1 页 → 联调。

**后端**
- [ ] Flyway `V3.8.1`：`task_template` 表（`updated_at` 无默认值）
- [ ] task-core-entity：新增 `TaskTemplate` entity/mapper
- [ ] engineering `TaskTemplateService`/`TaskTemplateController`：模板 CRUD + 一键创建（按 config_template 生成真实同步/SQL/导出/采集任务）
- [ ] 重建 engineering + worker + 受影响消费方

**前端**
- [ ] `Sidebar.tsx` 新增「任务模板」（ENGINEERING_WRITE_ROLES）+ `router/index.tsx` 新增 `/engineering/task-templates`
- [ ] 任务模板库页（列表/新增/一键创建，对齐原型 `task-template` 视图：segmented 分组 + 内置/自定义徽章）

**测试**
- [ ] 后端 Postman/curl 自测：模板 CRUD、一键创建后 sync_job 落库
- [ ] 新建 `e2e/sprint7/e2e/task-templates.spec.ts`
- [ ] 更新 §2 看板：F2 置 ✅

> **待细化**：B4 `config_template` JSON 结构（同步/SQL/导出/采集四类占位）——开始 F2 后端前需先定。

---

### 6.3 F3 子 DAG 参数下发（NG5）⏳ 未开始

**范围**：主 DAG → 子 DAG 参数单向透传（`paramMappings`）。
**无需迁移**（`dag_node.config` 为 TEXT JSON，向后兼容；旧数据 paramMappings=null 视为不传参）。

**后端**
- [ ] task-core-entity：`SubDagNodeConfig` 加 `paramMappings: List<ParamMapping>`（DTO 字段，config JSON 持久化，无新 Controller）
- [ ] engineering `DagService`/`SubDagTriggerController`：触发子 DAG 时在主 DAG 执行上下文扩展参数下发链路（§4.4）
- [ ] 重建 engineering + worker

**前端**
- [ ] 子 DAG 节点配置面板：编辑 `paramMappings`（主参数 → 子参数映射，原型 `subdag` 视图）
- [ ] 对齐 DAG 编辑页现有节点配置 UI 风格

**测试**
- [ ] 后端 Postman/curl 自测：主 DAG 配 paramMappings → 触发 → 子 DAG 执行上下文收到透传参数
- [ ] 新建 `e2e/sprint7/e2e/subdag-param.spec.ts`（或并入 control-flow）
- [ ] 更新 §2 看板：F3 置 ✅

---

### 6.4 F4 Python 质量规则（DG-10）⏳ 未开始

**范围**：新增 PYTHON 规则类型 + 强化自定义 SQL。**最复杂，放最后**。
**块内依赖**：Flyway → task-core PythonExecutor 改造（方案 B）→ worker 镜像 → governance 质量增强 → 前端表单 → 联调。

**后端**
- [ ] Flyway `V3.8.2`：`quality_rule_template.type` CHECK drop 重建加 `PYTHON`（对齐 V3.6.6）+ `python_template`/`python_script` 字段
- [ ] task-core `PythonExecutor` 改造（方案 B）：连接注入层从 Doris 写死抽象为通用 `conn.json` + 沙箱 helper 增 `read_table(table, where, limit)`（保留 `read_doris_table`/`write_doris_table` 向后兼容）
- [ ] worker 镜像：预装 Python 数据源驱动（pymysql/psycopg2/cx_Oracle，按需）
- [ ] governance 质量增强：`QualityTemplateController`/`QualityRuleController` 支持 PYTHON；`QualityCheckService` PYTHON 执行链路（§4.6，脚本返回 dict 取 `result_metric`，复用 `determineLevel`，失败落 `UNAVAILABLE`）；强化 CUSTOM_SQL（`RuleSqlGenerator` 模板化 + 多指标 + preview-sql 增强）
- [ ] 重建 governance + worker + job + system

**前端**
- [ ] `types/quality.ts` 扩展 `QualityTemplateType`/`QUALITY_TYPE_LABEL`/`QUALITY_TYPE_OPTIONS` 加 PYTHON
- [ ] 质量规则表单扩展 PYTHON 类型（Python 脚本编辑区，对齐原型 `python-rule` 视图）
- [ ] 质量模板库支持 PYTHON 模板

**测试**
- [ ] 后端 Postman/curl 自测：PYTHON 规则新建 + 执行（Doris/MySQL 驱动拉数 + 沙箱执行）+ CUSTOM_SQL 强化
- [ ] 新建 `e2e/sprint7/e2e/quality-python.spec.ts`
- [ ] 更新 §2 看板：F4 置 ✅

---

### 6.5 收尾（全部块完成后）

- [ ] 全量回归：`docker compose up -d` 全部服务 + 前端 build + 各块 E2E 全跑
- [ ] 代码审查 + 更新 AGENTS.md / docs/agent（如需）
- [ ] 更新 §2 看板全部置 ✅ + 本文档归档

## 7. 备注 / 已知坑提醒

- **构建规则**：只要改到 task-core 共享模块（entity 字段扩展等），必须同时重建所有消费方（至少 engineering + worker；涉及治理/质量还需 governance/job/system）。F1 改 `MetadataTable`、F2 加 `TaskTemplate`、F3 改 `SubDagNodeConfig`、F4 改 `PythonExecutor`——**每块都需全量重建对应容器**。
- **Flyway**：最新脚本编号 `V3.7.1`，新脚本必须从 `V3.8.0` 起；`quality_rule_template.type` CHECK 约束扩展需 drop 重建（对齐 V3.6.6 做法）；统一紧凑单行风格，禁格式化工具拆行。
- **审计字段**（AGENTS.md §7）：新增表 `asset_classification`/`task_template` 的 `updated_at` 不要加 DB 默认值，create 只设 `created_by`/`created_at`。
- **搜索性能**：资产多维搜索需处理千级表规模（R1），先 LIKE + 首屏防抖，量大再上全文索引。
- **Python 规则**：执行失败落 `result_level=UNAVAILABLE`（不告警、不参与评分，对齐 Sprint 6 R2）；复用 PythonExecutor 超时/内存限制。
- **F3 无迁移**：`dag_node.config` 为 TEXT JSON，`paramMappings` 新增字段向后兼容，勿新增 DB 迁移脚本。
- **每块独立验证**：F2 完成后即可部署上线（不与 F1 耦合），无需等全部块完成。
