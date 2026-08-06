# Sprint 7 Handoff

> **更新时间**：2026-08-05 | **阶段**：Sprint 7 规划/设计（PRD + 技术设计文档已完成，后端/前端实现未开始）
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
| 后端实现（资产目录/模板/质量增强/子DAG） | ⏳ 未开始 | 见 §9 实现清单                                                                                |
| 前端实现（数据资产页/详情页/分类/模板）  | ⏳ 未开始 | 见 §9 实现清单                                                                                |
| 联调验证                                 | ⏳ 未开始 | 接口先 Postman/curl 自测再联调前端                                                             |
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
| `docs/sprint7/DataNest-Sprint7-PRD.md`（新增）                        | Sprint 7 数据资产目录产品文档 v1.0（12 章，对齐 Sprint6 PRD 范本；复用 vs 新增边界经代码核验）        |
| `docs/sprint7/DataNest-Sprint7-技术文档.md`（新增）                   | Sprint 7 技术设计文档 v1.0（11 章，含 4 个技术决策、数据模型、接口、实现清单）；v1.1 补充 Python 数据拉取方案 B（通用连接注入） |
| `docs/sprint7/handoff/sprint-7.md`（新增）                            | 本 Handoff                                                                                            |
| `docs/sprint7/DataNest-Sprint7-资产目录原型.{css,html,js}`（修正）    | UI 原型高保真对齐真实前端：token/组件/侧边栏/表格/徽章等逐项修正（见下「原型对齐要点」）                |

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

## 6. Next Action

### ✅ 已完成（规划/设计）

- [x] Sprint 7 PRD（`DataNest-Sprint7-PRD.md` v1.0）
- [x] Sprint 7 技术设计（`DataNest-Sprint7-技术文档.md` v1.0）
- [x] 代码现状核验：搜索仅表名、子DAG不支持透传、质量类型无PYTHON、元数据无分类/负责人字段、PythonExecutor 沙箱已存在、Flyway 最高 V3.7.1

### ⏳ 待做（后端实现）

- [ ] Flyway `V3.8.0`（`asset_classification` + `metadata_table` 加 `data_domain`/`data_topic`/`owner_user_id`）
- [ ] Flyway `V3.8.1`（`task_template` 任务模板库）
- [ ] Flyway `V3.8.2`（`quality_rule_template.type` CHECK 加 PYTHON + `python_template`/`python_script` 字段）
- [ ] task-core-entity：`MetadataTable` 扩展字段；新增 `AssetClassification` entity/mapper；`SubDagNodeConfig` 加 `paramMappings`
- [ ] governance `AssetCatalogService`/`AssetCatalogController`（`/assets/search`、分类维护、分类浏览、分配分类/负责人）
- [ ] engineering `TaskTemplateService`/`TaskTemplateController`（任务模板 CRUD + 一键创建）
- [ ] governance 质量增强：模板/规则支持 PYTHON；`QualityCheckService` PYTHON 执行链路（§4.6）
- [ ] task-core `PythonExecutor` 改造（方案 B）：连接注入层抽象为通用连接注入（`conn.json`）+ 沙箱 helper 增 `read_table`（保留 `read_doris_table`/`write_doris_table` 向后兼容）
- [ ] worker 镜像：预装 Python 数据源驱动（pymysql/psycopg2/cx_Oracle，按需）
- [ ] engineering `DagService`/`SubDagTriggerController` 子 DAG 参数下发链路

### ⏳ 待做（前端实现）

- [ ] `Sidebar.tsx` 新增「数据资产」顶级入口（ALL_ROLES）+「任务模板」（ENGINEERING_WRITE_ROLES）
- [ ] `router/index.tsx` 新增 `/assets`、`/assets/:tableId`、`/engineering/task-templates`
- [ ] 数据资产首页（大搜索框 + 分类树 + 资产卡片流，复用 `QualityScoreBadge`）
- [ ] 资产详情页（基础信息/字段/血缘/质量四页签；血缘页签复用 `getLineageGraph` + 精简 ReactFlow）
- [ ] 分类体系维护 + 表分配分类/负责人
- [ ] 任务模板库页（列表/新增/一键创建）
- [ ] 质量规则表单扩展 PYTHON 类型（`types/quality.ts` 扩展 `QualityTemplateType`/`QUALITY_TYPE_LABEL`/`QUALITY_TYPE_OPTIONS`）

### ⏳ 待做（验证/收尾）

- [x] UI 原型（已对照真实前端 token/组件高保真对齐，见 §4）
- [ ] 后端接口 Postman/curl 自测（`/assets/search`、分类维护、任务模板、PYTHON 规则、子DAG 参数下发）
- [ ] 前端联调 + 构建部署（app-governance / app-engineering / app-worker / app-frontend）

## 7. 备注 / 已知坑提醒

- **构建规则**：只要改到 task-core 共享模块（entity 字段扩展等），必须同时重建所有消费方（至少 engineering + worker；涉及治理/质量还需 governance/job/system）。本 Sprint 扩展了 task-core-entity 的 `MetadataTable`/`SubDagNodeConfig`，需全量重建。
- **Flyway**：最新脚本编号 `V3.7.1`，新脚本必须从 `V3.8.0` 起；`quality_rule_template.type` CHECK 约束扩展需 drop 重建（对齐 V3.6.6 做法）；统一紧凑单行风格，禁格式化工具拆行。
- **审计字段**（AGENTS.md §7）：新增表 `asset_classification`/`task_template` 的 `updated_at` 不要加 DB 默认值，create 只设 `created_by`/`created_at`。
- **搜索性能**：资产多维搜索需处理千级表规模（R1），先 LIKE + 首屏防抖，量大再上全文索引。
- **Python 规则**：执行失败落 `result_level=UNAVAILABLE`（不告警、不参与评分，对齐 Sprint 6 R2）；复用 PythonExecutor 超时/内存限制。
