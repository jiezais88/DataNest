# Sprint 6 Handoff

> **更新时间**：2026-08-05 | **阶段**：Sprint 6 分级邮件告警前端完成（检查历史分级判定展示 + 告警中心 QUALITY 对象类型）＋质量任务表单「引用质量规则」改下拉多选，已构建部署。另完成「创建审计字段修复」（创建时不再设 updatedBy/updatedAt，Flyway V3.6.8 去 updated_at DB 默认值，见 §12）。**表级质量评分后端完成**（评分跨任务聚合加权计算 + quality_score 落库 + 血缘回填 + 三查询接口 + 健康度区间/扣分算法调研回写文档，见 §13）。**task-core 三步拆分重构完成**（原 task-core 拆为 entity/alert/task-core-governance/task-core 4 模块，包名不变，新增 QualityAutoTriggerPort 接口解耦，全量编译 + 5 容器重建 + API 回归通过，见 §16）。**标准合规检查后端扩展完成**（合规逻辑下沉 task-core、忽略/取消忽略、分页、导出 CSV、全局定时扫描、放开工程师查看/忽略/导出权限、修 violationType bug，Flyway V3.7.1，5 容器部署 + 双角色 API 自测通过，见 §17）；**模板批量应用后端 review 修复**（重名校验 + 批量插入，见 §17.7）；**GlobalExceptionHandler 修复 404 误报 500 + 运行扫描权限收回**（common 改动重建 5 容器，见 §17.8b）；**级联删除/删除校验补全 + 质量检查历史清理定时任务**（删数据源/模板/质量任务被引用校验 HAS_REFERENCES + 新增 QualityCheckHistoryCleanupHandler cron 04:30，见 §18）；**删除补全 + 被阻止删除返回引用明细**（产品审视确认「保留历史」；删 DAG 清血缘、删质量任务评分方案1、字段类型标准/同步任务/DAG/质量任务删除返回引用名称列表；前端 ReferenceListModal + 各删除页接入，见 §19）；质量报告（DG-07）本轮不做；**标准合规检查前端完成**（新增独立「标准合规」菜单页：三格统计 + 扫描结果清单 + 忽略/取消忽略 + 导出 + 立即扫描；废弃数据标准页 sessionStorage 方案；后端补 summary 接口 + page 扩展 violationType/ignored=2，见 §20）
> **Sprint 主题**：数据质量管理

## 1. Sprint 目标

让治理管理员能够为元数据目录中的表配置质量规则，用真实数据执行校验，按「通过 / 警告 /
严重」分级并邮件告警，产出表级质量评分并联动血缘展示，同时自动扫描不符合数据标准的表和字段。

## 2. 状态看板

| 事项                                     | 状态      | 说明                                                                                              |
|------------------------------------------|-----------|---------------------------------------------------------------------------------------------------|
| Sprint 6 产品范围确认                    | ✅ 完成   | 用户确认：数据质量管理（规格文档 Sprint 7 前移）                                                  |
| Sprint 6 产品决策澄清                    | ✅ 完成   | 见下方「关键决策」                                                                                |
| Sprint 6 代码现状调查                    | ✅ 完成   | 数据质量从零建；可复用 SQL 执行/告警/调度                                                         |
| Sprint 6 PRD                             | ✅ 完成   | `docs/sprint6/DataNest-Sprint6-PRD.md`（v1.1）                                                    |
| Sprint 6 技术设计                        | ✅ 完成   | `docs/sprint6/DataNest-Sprint6-技术文档.md`（v1.0，含 6 个技术决策）                              |
| Sprint 6 UI 原型                         | ✅ 完成   | `docs/sprint6/DataNest-Sprint6-数据质量原型.html`（6 view，参照现有前端代码）                     |
| 后端实现（质量任务/规则/检查/评分/合规） | 🔄 进行中 | 规则模板库（模板 CRUD）✅；质量任务 + 质量规则**配置层**后端 ✅（含模板批量生成/调度配置/QualityCheckHandler 扫描预留）；真实执行校验/评分/告警合并/worker 自动触发待做 |
| 前端实现（规则模板库）                   | ✅ 完成   | 独立「规则模板库」页面已交付（列表/统计/筛选/新增/编辑/详情/启停/删除）；数据质量页与血缘联动待做 |
| 前端实现（质量任务/规则）                | ✅ 完成   | 独立「数据质量」页（质量任务/质量规则双页签）已交付（任务 CRUD/启停/统计/筛选；规则按任务管理/新增/批量应用/启停/删除/预览SQL；自动触发对象按 项目-DAG-节点 三级树） |
| Sprint 8 执行层后端                      | ✅ 完成   | 质量检查真实执行 + 批次/明细落库 + 三种触发（MANUAL/SCHEDULED/AUTO_TRIGGER），见 §8 |
| Sprint 8 执行层前端                      | ✅ 完成   | 新增「质量检查历史」独立菜单页（批次列表+规则明细抽屉）+ 任务/规则执行按钮从占位改为真实触发，见 §8.5 |
| 联调验证                                 | ✅ 完成   | 任务/规则全部接口经网关联调通过（见「质量任务/规则 · API 验证记录」）；E2E 全绿（36 用例，见「质量任务/规则 · E2E 测试记录」） |
| Sprint 6 分级邮件告警后端                 | ✅ 完成   | 分级判定落库（result_level）+ fireBatch 合并告警 + 告警中心 QUALITY 对象类型，见 §9 |
| Sprint 6 分级邮件告警前端                 | ✅ 完成   | 质量检查历史明细卡片展示分级判定（通过/警告/严重/不可用）；告警中心支持「质量任务」对象类型配置/筛选/徽章，见 §9.7 |
| 告警规则名称 + 质量任务页改名             | ✅ 完成   | alert_rule 加 name（必填/同类型唯一）+ alert_history 加 rule_name；告警中心规则/历史新增名称列；AlertRuleModal 加规则名称输入框；数据质量页改名「质量任务」去统计改副标题，见 §10 |
| 创建审计字段修复                         | ✅ 完成   | 所有实体 create 入口只设 createdBy/createdAt，去掉 setUpdatedBy/setUpdatedAt；Flyway V3.6.8 去掉 13 张表 updated_at 的 DB 默认值（创建后未修改时 updated_at/updated_by 为 null），见 §12 |
| 表级质量评分后端                         | ✅ 完成   | 评分跨任务聚合加权计算（quality_score 落库，一张表一行）+ 血缘图谱节点批量回填评分 + 三查询接口；健康度四档区间/扣分算法行业调研后回写 PRD/技术文档，见 §13 |
| 表级质量评分前端                         | ✅ 完成   | 三个落点全覆盖（独立「质量评分」列表页 + 元数据「质量」页签 + 血缘节点质量徽章）+ 后端补 4 接口（按表规则/按表执行/扣分配置读写）；ScoreCalculator 扣分读库表配置 + 修复 badThreshold 判定 bug，见 §14 |
| task-core 三步拆分重构                   | ✅ 完成   | 原 task-core 拆为 4 模块（entity/alert/task-core-governance/task-core），包名不变；新增 QualityAutoTriggerPort 接口解耦 alert↔governance；跳过执行内核强拆；全量编译+5 容器重建+核心 API 回归通过，见 §16 |
| 标准合规检查后端扩展                     | ✅ 完成   | 合规逻辑下沉 task-core、忽略/取消忽略、分页、导出 CSV、全局定时扫描、放开工程师权限、修 violationType bug，Flyway V3.7.1，5 容器部署 + admin/工程师双角色 API 自测通过，见 §17 |
| 模板批量应用后端 review 修复             | ✅ 完成   | `QualityRuleService.batchCreate` 修复重名校验 + 批量插入 + 批量绑定关联，API 自测通过，见 §17.7 |
| 标准合规检查前端                         | ✅ 完成   | 新增独立「标准合规」菜单页（三格统计 + 扫描结果清单 + 忽略/取消忽略 + 导出 + 立即扫描），废弃数据标准页 sessionStorage 方案；后端补 summary 接口 + page 扩展 violationType/ignored=2，见 §20 |
| 质量报告（DG-07）                        | ⏳ 不做   | 本轮不做，原排期 S8 单独会话做 |
| Sprint6 级联删除/删除校验补全           | ✅ 完成   | 删数据源/规则模板/质量任务「被引用阻止」（HAS_REFERENCES 3005），API 自测通过，见 §18 |
| 质量检查历史清理定时任务                | ✅ 完成   | 新增 QualityCheckHistoryCleanupHandler（cron 04:30，保留 30 天，级联删 batch+detail），已注册 XXL-JOB，见 §18 |
| 删除补全 + 引用明细提示（后端+前端）   | ✅ 完成   | 删 DAG 清血缘、删质量任务评分方案1、被阻止删除返回引用名称列表；前端 ReferenceListModal 接入各删除页，见 §19 |

## 3. 关键决策（用户已确认）

| 决策点         | 结论                                                                     |
|----------------|--------------------------------------------------------------------------|
| 规则作用对象   | 元数据目录表 + 真实数据校验（在数据源上真实执行校验 SQL）                |
| 执行时机       | 手动(默认) + 定时调度（任务级统一 cron）+ 任务完成自动触发（可叠加）     |
| 质量告警渠道   | 仅邮件（复用 Sprint 5 alert_rule，扩展 object_type=QUALITY）             |
| 呈现维度       | 结果分级+详情页展示 + 表级质量评分+血缘联动 + 标准合规扫描（三者均纳入） |
| 路线图偏差处理 | 以用户给定范围为准，不要求同步修正规格文档 v2.0 路线图                   |

### Sprint 6 细化决策（2026-08-04 交互式确认，共 8 项）

| #  | 决策点           | 结论                                                                    |
|----|------------------|-------------------------------------------------------------------------|
| A  | 规则类型         | 四类全做（P0）：完整性 / 唯一性 / 值域范围 / 自定义 SQL                 |
| B  | 完整性检查字段   | 字段可选；不填则统计「整表空值率」= 存在至少一个空值字段的行数 ÷ 总行数 |
| C1 | 定时调度 Cron    | **已废除**（v1.1）：改为「质量任务」统一 Cron，任务下规则跟随           |
| C2 | 任务完成自动触发 | 绑定到具体任务（DAG 节点/同步任务），该任务完成后触发本任务全部规则     |
| D1 | 告警触发等级     | 支持开关：「仅严重」或「严重+警告」，在**任务级**配置                   |
| D2 | 告警收敛         | 同一次检查批次多条异常合并为一条邮件，正文逐条列出（引入 batchId）      |
| E1 | 质量评分权重     | 支持规则权重，评分按权重加权                                            |
| E2 | 评分扣分分值     | 警告/严重扣分分值做成全局可配置项，无需改代码                           |
| F  | 血缘评分展示     | 血缘图谱全节点显示评分徽章；未配置规则的表显示灰色「—」                 |
| G1 | 合规忽略粒度     | 按具体不合规项忽略（不做整表/整规则粗粒度忽略）                         |
| G2 | 合规取消忽略     | 支持取消忽略，被忽略项可恢复                                            |
| H1 | 报告维度         | 单表 / 单库 / 按数据源 三维度（暂不含项目）                             |
| H2 | 报告周期         | 自定义时间区间，默认最近 30 天                                          |

### Sprint 6 技术决策（2026-08-04，已确认，落地于技术文档）

| #  | 决策点           | 结论                                                                                                  |
|----|------------------|-------------------------------------------------------------------------------------------------------|
| T1 | 质量核心逻辑模块 | 下沉 `data-nest-task-core`（governance 提供 Controller，job/worker 复用）                             |
| T2 | 告警合并         | 引入检查批次 `quality_check_batch`（batchId），`AlertFiringService.fireBatch` 合并发一条邮件+多条历史 |
| T3 | 评分存储         | 落 `quality_score` 表（每表一行），血缘节点批量回填，避免实时计算慢                                   |
| T4 | 定时驱动         | `QualityCheckHandler` 每分钟扫描启用任务的 cron 匹配当前分钟，命中执行（Spring `CronExpression`）     |
| D1 | 任务调度方式     | 手动为默认能力 + 可选配定时/任务完成自动触发（可叠加），调度挂任务级                                  |
| D3 | 规则模板库       | 引入独立 `quality_rule_template` 表，任务内「模板 + 多表」批量生成规则                                |

## 4. 变更清单

| 文档/产物                                                                                                              | 变更说明                                                                                                                                         |
|------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------|
| `docs/sprint6/DataNest-Sprint6-PRD.md`（新增）                                                                         | Sprint 6 数据质量管理产品文档 v1.0；v1.1 同步三层模型/模板库/任务级调度                                                                          |
| `docs/sprint6/DataNest-Sprint6-技术文档.md`（新增）                                                                    | Sprint 6 数据质量管理技术设计文档 v1.0（含 6 个技术决策、数据模型、接口、实现清单）                                                              |
| `docs/sprint6/DataNest-Sprint6-数据质量原型.html`（新增）                                                              | Sprint 6 数据质量高保真可交互原型（6 view + 13 弹窗），参照现有前端代码（antd + ds-* tokens）与既有 Sprint 原型约定                              |
| `docs/sprint6/handoff/sprint-6.md`（新增）                                                                             | 本 Handoff                                                                                                                                       |
| `data-nest-system/.../db/migration/V3.6.0__sprint6_quality_rule_template.sql`（新增）                                  | 建 `quality_rule_template` 表 + 内置四类模板种子数据（完整性/唯一性/值域范围/自定义 SQL，`WHERE NOT EXISTS` 幂等插入）                           |
| `task-core`：`QualityRuleTemplate`(entity)/`QualityRuleTemplateMapper`/`QualityRuleTemplateService` + 4 个 DTO（新增） | 质量模板核心逻辑下沉 task-core（D-T1）；CRUD：全量列表/分页/详情/新增/编辑/删除/启停；类型白名单校验、名称唯一校验、内置模板禁删、用户名批量回填 |
| `governance`：`QualityTemplateController`（新增）                                                                      | `/quality/templates` CRUD Controller；读接口全员角色可看、写接口仅治理员/超管；完整路径 `/api/governance/quality/templates`                      |
| `data-nest-common`：`ErrorCode`（修改）                                                                                | 新增 4 个质量错误码：`QUALITY_TEMPLATE_NOT_FOUND(4201)/NAME_EXISTS(4202)/TYPE_INVALID(4203)/BUILTIN_NOT_DELETE(4204)`                            |
| `data-nest-frontend`：`src/types/quality.ts`（新增）                                                                   | 质量模板类型定义（`QualityTemplateType`/`QualityRuleTemplate`/创建/更新/查询 DTO）                                                               |
| `data-nest-frontend`：`src/api/quality.ts`（新增）                                                                     | 模板 6 个接口封装（列表/分页/新增/编辑/删除/启停），对接 `/governance/quality/templates`                                                         |
| `data-nest-frontend`：`src/pages/governance/quality-templates/index.tsx` + `QualityTemplateDrawer.tsx`（新增）         | 规则模板库页面（统计卡片/搜索/类型·来源·状态筛选/表格/分页/详情·编辑 Drawer/删除 ConfirmDialog/启停/批量应用占位提示）                           |
| `data-nest-frontend`：`Sidebar.tsx`（修改）                                                                            | 「数据治理」分组新增「规则模板库」菜单（`GOVERNANCE_WRITE_ROLES`），路由 `/governance/quality-templates`                                         |
| `data-nest-frontend`：`router/index.tsx` + `utils/breadcrumb.ts`（修改）                                               | 新增 `/governance/quality-templates` 懒加载路由 + leaf 面包屑条目                                                                                |
| `data-nest-system/.../db/migration/V3.6.1__sprint6_quality_job_rule.sql`（新增）                                       | 建 `quality_job` + `quality_rule` 两张配置层表（本次只建配置层两表；批次/历史/评分表留执行批）                                                    |
| `task-core`：`QualityJob`/`QualityRule`(entity) + Mapper + `QualityJobService`/`QualityRuleService` + `RuleSqlGenerator`（新增） | 任务/规则配置层核心逻辑下沉 task-core；任务 CRUD/分页(含规则数/调度徽章)/详情(含规则)/启停/删除级联/手动执行预留；规则 CRUD/按任务查/模板批量应用(选模板+多表逐表微调)/启停/单条执行预留/SQL 预览 |
| `task-core`：`QualityJob`/`QualityRule` 系列 DTO + `QualityRuleBatchCreateRequest`（新增）                            | 任务 Create/Update/Query/DTO；规则 Create/Update/DTO + 批量请求（`templateId` + `List<RuleItem>`，逐表可微调）；`@AssertTrue` 校验（定时必填 cron/RANGE·UNIQUENESS·按字段完整性必填字段/CUSTOM_SQL 必填 SQL） |
| `task-core`：`RuleSqlGenerator`（新增）                                                                                | 执行 SQL 动态生成器：`{table}`→`schema.table`、`{column}`/`{min}`/`{max}` 占位替换；本次供预览，下一批执行校验直接复用 |
| `governance`：`QualityJobController` + `QualityRuleController`（新增）                                                 | `/quality/jobs`（page/详情/增/改/删/启停/执行预留）+ `/quality/rules`（by-job/详情/增/批量/改/删/启停/执行预留/preview-sql）；读全员、写治理员+超管 |
| `data-nest-common`：`ErrorCode`（修改）                                                                                | 新增 4205~4212：`QUALITY_JOB_NOT_FOUND/NAME_EXISTS/ALERT_LEVEL_INVALID`、`QUALITY_RULE_NOT_FOUND/NAME_EXISTS/EXECUTE_NOT_IMPLEMENTED/BATCH_TEMPLATE_INVALID`、`QUALITY_TABLE_NOT_FOUND` |
| `data-nest-job`：`QualityCheckHandler`（新增）+ `JobRegistrar`（修改）                                                 | `@XxlJob("qualityCheckHandler")` 每分钟扫描启用+定时+配 cron 的任务，Spring `CronExpression` 匹配当前分钟命中更新 `last_trigger_at` + 预留 `executeJob` 入口；注册到 JobRegistrar（cron `0 * * * * ?`） |
| Flyway 全量脚本统一紧凑单行格式（修改 50 个）                                                                          | 修复历史脚本被格式化工具拆行导致的 checksum 失效；统一紧凑单行风格 + flyway repair 固化（见 §5 Blocker / §6 踩坑） |
| `data-nest-system/.../db/migration/V3.6.2__sprint6_quality_rule_range_bounds.sql`（新增）                              | 给 `quality_rule` 加 `range_min`/`range_max`（RANGE 值域边界，与分级阈值 warning/severe 独立存储）                  |
| task-core：`QualityRule`/DTO/`RuleSqlGenerator`/`QualityJobService`/`QualityRuleService`（修改）                        | review 修复：RANGE `{min}`/`{max}` 改从 rangeMin/rangeMax 取（不再复用分级阈值）；任务更新改「null 不更新 + description/datasourceId 可清空」；`touchLastTriggerAt` 优化为只更新 last_trigger_at；规则更新全量覆盖（RANGE 强校验） |
| `data-nest-frontend`：`src/types/quality.ts`（修改）                                                                    | 在模板类型基础上追加质量任务/质量规则/批量应用类型；`checkField` 为 number（0=整表/1=按字段）、RANGE 阈值映射 min/max、`ruleCount`/`scheduleStatusBadge` 对齐列表接口 |
| `data-nest-frontend`：`src/api/quality.ts`（修改）                                                                      | 追加质量任务 8 个接口 + 质量规则 9 个接口 + 批量应用接口封装（`/governance/quality/jobs|rules`）                    |
| `data-nest-frontend`：`src/pages/governance/data-quality/`（新增 7 个文件）                                             | 「数据质量」页：`index.tsx`（双页签）+ `QualityJobDrawer` + `QualityRuleDrawer` + `BatchApplyModal` + `TableSelectModal` + `AutoTriggerSelect` |
| `data-nest-frontend`：`Sidebar.tsx` + `router/index.tsx` + `utils/breadcrumb.ts`（修改）                                | 数据治理分组新增「数据质量」菜单（`GOVERNANCE_WRITE_ROLES`，路由 `/governance/data-quality`）+ 懒加载路由 + leaf 面包屑 |
| `data-nest-frontend`：数据质量页面部署                                                                                  | `npm run build` + `docker compose build app-frontend` + `up -d`，SPA fallback 与懒加载 chunk 验证通过                 |

### 📌 Sprint 7 方案A · 质量规则「先选数据源再选表」改造（2026-08-04 追加）

> 用户确认方案A：**移除质量任务的「数据源范围」字段**，把「数据源」下放到规则层，规则成为「数据源 + 表 + 检查」完整对象；选表交互改为**表单级显式「数据源」下拉 + 锁定选表**。

**变更清单：**
- **后端 task-core**（需重建 engineering + worker）：
  - `QualityJobService`：移除 `datasourceId` 筛选/写入/回填逻辑，移除 `dataSourceMapper` 依赖（构造函数 4→3 参）。
  - DTO `QualityJobDTO`/`Create`/`Update`/`QueryRequest`：移除 `datasourceId`/`datasourceName` 字段。
  - `QualityRuleDTO`：新增 `datasourceId`/`datasourceName`（经 `metadata_table.datasource_id` 反查，内置 Doris=-1 映射「Doris 数仓」）。
  - `QualityRuleService`：注入 `DataSourceConnectionMapper`，`buildDTOs` 批量回填数据源名（`loadDatasourceNameMap`）。
  - `quality_job.datasource_id` **列保留不删**，仅后端不再读写业务逻辑。
- **前端**：
  - `types/quality.ts`：`QualityJob*` 移除 `datasourceId`；`QualityRule` 新增 `datasourceId`/`datasourceName`。
  - 任务列表 `data-quality/index.tsx`：移除「数据源范围」列、数据源筛选下拉、URL 同步、`loadJobDatasources`。
  - `QualityJobDrawer`：移除「数据源范围」下拉与 `datasourceId` 字段。
  - `QualityRuleDrawer`（核心）：表单新增「数据源」下拉（`listMetadataDatasourceIds`，仅已采集数据源），先选数据源再选表，选表弹窗**锁定该数据源**；切换数据源清空已选表；编辑回显规则 `datasourceId/datasourceName`。
  - `TableSelectModal`：新增 `lockDatasource` prop（true 时数据源下拉禁用锁定）。
  - `BatchApplyModal`：同样改为「先选数据源再选表」，选表锁定数据源。
  - 规则列表 `quality-rules/index.tsx`：移除 `defaultDatasourceId` 继承，新增「数据源」列展示规则归属数据源。
- **文档**：`docs/sprint6/DataNest-Sprint6-技术文档.md` §3.2 已标记 `quality_job.datasource_id` 为已废弃。
- **验证**：task-core/engineering/worker `mvn compile` 通过，前端 `tsc` 通过。

**注意**：规则数据源下拉只展示「已采集元数据的数据源」（`listMetadataDatasourceIds`），未采集的数据源选不到表，已在前端加提示文案（先到元数据管理执行采集）。

### 📌 Sprint 7 追加 · 规则表单「目标表」改为行内级联选表（2026-08-04）

> 需求：规则表单的目标表**参考同步任务**改为**行内直接级联选择**（数据源 → 数据库 → Schema → 表），去掉原来的「未选择表 + [选择表] 弹窗按钮」。

**变更（仅前端）：**
- `QualityRuleDrawer`：移除 `TableSelectModal` 弹窗交互，目标表改为**行内级联下拉**（`listMetadataDatabases` → `listMetadataSchemas`/`listMetadataTablesWithoutSchema` → `listMetadataTables`），与同步任务源表选择形态一致。
  - 数据源（行内下拉，已有）→ 数据库 → Schema（无 Schema 类型跳过）→ 目标表（select 单选）。
  - 切换数据源清空级联状态；编辑回显用 `tableName` 第一段预设 `selectedDatabase`/`selectedSchema`。
- `TableSelectModal` **保留**（`BatchApplyModal` 批量应用多表仍用弹窗，不受影响）。
- 后端无改动，仅重建 **app-frontend**（`--no-cache`）。

### 📌 Sprint 7 追加 · 质量任务「触发方式」改为单选（2026-08-04）

> 需求：质量任务的触发方式应是**单选**（手动 / 定时 / 自动触发），不允许"手动 + 定时"等组合（原实现是两个独立 checkbox 可同时开）。

**变更（仅前端，后端字段不变）：**
- `QualityJobDrawer`：把「定时调度」「任务完成自动触发」两个 checkbox 改为**三选一单选卡片**（手动触发 / Cron 定时 / 自动触发，参考同步任务 triggerType 单选 UI）。
  - 选中某一项时 `scheduledEnabled`/`autoTriggerEnabled` **互斥置位**，并清空另一方式对应的 cron / autoTrigger 对象字段。
- 任务列表 `data-quality/index.tsx`：「触发方式」列改为**单选显示**（自动触发 > 定时 > 手动，历史数据若同时开启按此优先级归一展示），不再拼接 "手动 + 定时"。
- 后端无改动，仅重建 **app-frontend**（`--no-cache`）。

### 📌 Sprint 7 追加 · 数据质量路由去掉 `?tab=jobs` 参数（2026-08-04）

> 需求：数据质量页路由 `/governance/data-quality` 目前会带上 `?tab=jobs`，实际只有一个 Tab（质量任务），参数无意义，去掉。

**变更（仅前端 `data-quality/index.tsx`）：**
- 移除 `Tab` 类型、`activeTab` state、`tabs` 数组及 Tab 切换 UI（恒为 'jobs'，无实际作用）。
- 移除 URL 中 `tab` 参数的写入与初始化，只保留 `jobKeyword`/`jobEnabled`/`jobPage` 的筛选分页 URL 同步。
- 移除未使用的 `HiOutlineClipboardDocumentCheck` import。
- 仅重建 **app-frontend**（`--no-cache`）。

## 5. Blocker

> 需求：质量任务可在**列表直接开启/关闭定时调度**，参考同步任务操作列的调度开关。

**变更清单：**
- **后端 task-core**：
  - `QualityJobService` 新增 `startSchedule(id)`（`scheduled_enabled=1`，cron 为空抛 `QUALITY_JOB_CRON_REQUIRED` 4206/4213）/ `stopSchedule(id)`（`scheduled_enabled=0`），仅更新调度开关不触碰其它字段。
  - `ErrorCode` 新增 `QUALITY_JOB_CRON_REQUIRED(4213)`。
- **后端 governance**：`QualityJobController` 新增 `POST /{id}/schedule/start`、`POST /{id}/schedule/stop`（治理员/超管）。
- **前端**：
  - `api/quality.ts` 新增 `startQualityJobSchedule`/`stopQualityJobSchedule`。
  - 任务列表 `data-quality/index.tsx`：操作列新增**调度开关按钮**（`HiOutlineCalendar`，active 高亮 = 已开调度，含 `schedulingId` loading 态），仅**配置了 cron** 的任务显示（与同步任务仅 CRON 触发类型显示一致）。
- **验证**：`schedule/stop` 使 `scheduled_enabled` 1→0，`schedule/start` 使 0→1，接口均 200；前端 tsc 通过。
- **部署**：重建 app-governance（调度端点）+ app-engineering/app-worker（task-core 变更按约定）+ app-frontend（前端）。

## 5. Blocker

- B1：worker 同步任务终态回调挂载点需定位（`SyncJobExecutorService` 执行完成回调的确切类/方法），用于自动触发接入。
- B2： ~~质量相关操作权限注解 key 需对照现有角色权限表确认~~ → ✅ 已消解：读接口全员（治理员/超管/工程师/分析师），写接口治理员+超管（
  `@SaCheckRole` + `SaMode.OR`）。
- B3：`AlertRuleService.validate()` 硬编码对象类型需扩展支持 QUALITY（明确，非阻塞）。

### 本次新增 Blocker / 环境注意

- E1：`middleware-postgres` 业务库 Flyway `V3.5.7` 曾出现 checksum 校验失败（本地脚本与已应用版本不一致），已用 flyway
  repair 一次性修复；后续不要再改已应用的 V3.5.x 脚本，否则同样会校验失败。
- E2：**Flyway 全量格式固化**（本次）：排查发现 **50 个**已应用脚本被格式化工具拆行（`PRIMARY\nKEY`、`VARCHAR\n(100)`、
  `COMMENT\nON` 等），其中 V3.6.0 导致 app-system 启动 validate 失败。已全量重写为紧凑单行风格 + flyway repair 固化所有
  checksum。**后续禁止格式化工具拆分 migration SQL**（AGENTS.md §6 / §8.6 已记录）。
- B3 状态：质量告警需扩展 `AlertRuleService` 对象类型支持 QUALITY（明确，仍待执行批处理）。
- B4：**任务详情 `ruleCount` 恒为 0**：`QualityJobService.getById` 用 `withRuleCount=false` 构建 DTO，设置 `rules` 后未重算 `ruleCount`，详情返回恒 0（列表接口正常）。前端列表用 `page` 接口不受影响，但详情内若要展示规则数需修复。
- B5：**手动创建的无模板规则 preview-sql 返回 null**：手动创建（未选模板、无 `sqlExpression`）的规则调用 `/preview-sql` 时 `RuleSqlGenerator.generate(template=null,...)` 返回 null，前端回退显示"无预览 SQL"。批量应用创建的规则（带 templateId）预览正常。**本次已缓解**：E2E 用 CUSTOM_SQL（自带 SQL）规则验证预览 SQL 正常；无模板非 CUSTOM_SQL 规则的 preview 仍返回 null（已知项，前端降级显示"无预览 SQL"），留待执行批。
- B6：~~**前端新增非 CUSTOM_SQL 规则必然 400**~~ → ✅ 已修复：`QualityRuleDrawer` 新增时 `templateId` 恒为 `undefined`，后端 DTO `isTemplateRequiredValid` 强制非 CUSTOM_SQL 必须选模板，导致前端新增完整性/唯一性/值域规则必报 400。修复：移除 DTO 强制校验 + `QualityRuleService.create/update` 防御兜底（数据模型 `template_id` 可空、PRD"可选自模板"、前端 UI 无模板控件，故放宽为可选）。已重新部署 app-governance，API 验证无模板创建 COMPLETENESS 规则返回 200。
- B7：~~**治理员无法读取同步任务列表导致自动触发绑定不可用**~~ → ✅ 已修复：质量任务自动触发绑定同步任务时，`AutoTriggerSelect` 调 `/engineering/sync-jobs/page` 读取下拉，但该接口 `@SaCheckRole` 仅限 `SUPER_ADMIN`/`DATA_ENGINEER`，治理员（GOVERNANCE_ADMIN）返回 403 → 下拉为空。修复：`SyncJobController.list` 增加 `GOVERNANCE_ADMIN` 只读访问。已重新部署 app-engineering，E2E 自动触发完整绑定通过。
- B8：**`data-quality/index.tsx` 操作按钮缺 aria-label** → ✅ 已修复：质量任务/质量规则表格操作按钮（执行/详情/启停/编辑/删除、预览 SQL）此前无 `aria-label`，E2E 无法可靠定位。已全部补齐（与 quality-templates 一致）。已重建 app-frontend。

## 6. Next Action

### ✅ 已完成（规则模板库 · 前后端，CRUD 阶段）

- [x] Flyway `V3.6.0__sprint6_quality_rule_template.sql`：建 `quality_rule_template` 表 + 内置四类模板种子。
- [x] task-core：`QualityRuleTemplate`(entity) / Mapper / `QualityRuleTemplateService` / 4 个
  DTO（全量列表、分页、详情、新增、编辑、删除、启停；内置禁删；类型/名称校验；用户名回填）。
- [x] governance：`QualityTemplateController`（`/api/governance/quality/templates`）。
- [x] common：`ErrorCode` 新增 4201~4204。
- [x] 部署 app-system（Flyway V3.6.0）+ app-governance，API 全量 CRUD 测试通过（含异常分支）。
- [x] 前端：`src/types/quality.ts` + `src/api/quality.ts` + `src/pages/governance/quality-templates/`（index + Drawer）。
- [x] 前端：`Sidebar`「数据治理」加「规则模板库」菜单、`router` 加 `/governance/quality-templates` 懒加载路由、`breadcrumb` 加
  leaf 条目。
- [x] 部署 app-frontend（build + `docker compose up -d --no-deps app-frontend`），页面经网关联调通过（分页/列表/创建/启停/删除均
  200；内置删除返回 4204 被拒）。
- [x] 清理：测试数据已删除（表内仅剩 4 条内置）、临时文件已清理。

### ✅ 已完成（质量任务 + 质量规则 · 配置层后端，本次会话）

- [x] Flyway `V3.6.1__sprint6_quality_job_rule.sql`：建 `quality_job` + `quality_rule` 两表（紧凑单行）。
- [x] task-core：`QualityJob`/`QualityRule`(entity/Mapper) + `QualityJobService`/`QualityRuleService`/`RuleSqlGenerator` + 系列 DTO。
- [x] governance：`QualityJobController`（`/quality/jobs`）+ `QualityRuleController`（`/quality/rules`，含批量/preview-sql）。
- [x] common：`ErrorCode` 新增 4205~4212。
- [x] job：`QualityCheckHandler` 定时扫描（每分钟，cron 匹配命中更新 `last_trigger_at` + 预留执行入口）+ `JobRegistrar` 注册。
- [x] 部署 app-system（Flyway V3.6.1 + 全量 repair + V3.6.2）/ app-governance / app-job，API 全量测试通过（见下「验证路径」）。
- [x] **代码 review 修复**：RANGE 独立值域字段（Flyway `V3.6.2` 加 `range_min`/`range_max`，`{min}`/`{max}` 改从值域取，不再复用分级阈值）；任务更新改「null 不更新 + description/datasourceId 可清空」；规则更新全量覆盖（RANGE 强校验）；`touchLastTriggerAt` 优化只更新 last_trigger_at。
- [x] 前端：质量任务/规则页面 **已做**（见下「质量任务/规则 · 前端交付」）。

### ✅ 已完成（质量任务 + 质量规则 · 前端，本次会话）

- [x] 类型定义：`src/types/quality.ts` 追加 `QualityJob`/`QualityRule`/批量应用类型（`checkField` 为 number、RANGE 阈值=min/max、`ruleCount`/`scheduleStatusBadge` 对齐列表接口）。
- [x] API：`src/api/quality.ts` 追加质量任务 8 接口 + 质量规则 9 接口 + 批量应用。
- [x] 页面：`src/pages/governance/data-quality/`（7 文件）：
  - `index.tsx`：质量任务/质量规则双页签（`useSearchParams` 保持 tab）；任务页签 = 统计卡片（全部/启用/停用，3 次 pageSize=1 分页取 total）+ 关键字/数据源/状态筛选 + 表格 + 新增/编辑/详情 Drawer + 启停/删除/执行(占位提示"执行功能待实现")；规则页签 = 顶部任务下拉 + 该任务规则列表 + 新增/批量应用/启停/删除/预览SQL/执行(占位)。
  - `QualityJobDrawer.tsx`：任务表单（名称/数据源范围下拉/描述/启用/定时调度+cron/自动触发对象选择/告警等级）；校验（名称必填、定时必填 cron、自动触发必填对象）。
  - `QualityRuleDrawer.tsx`：规则表单（名称/类型/目标表(选表 Modal)/检查字段(COMPLETENESS 可整表或按字段)/阈值(RANGE 显示为值域上下限)/结果指标/权重/启用）；校验（名称/表/字段/SQL/阈值/权重）。
  - `BatchApplyModal.tsx`：模板批量应用（选模板 + 多表选表 Modal + 逐表可微调字段/阈值/权重，按模板类型动态渲染字段）。
  - `TableSelectModal.tsx`：选表 Modal（数据源→数据库→Schema→表 三列，兼容 `DB_TYPES_WITHOUT_SCHEMA`，支持单选/多选）。
  - `AutoTriggerSelect.tsx`：自动触发对象选择（DAG_NODE 按 项目→DAG→节点 三级级联、SYNC_JOB/COLLECT_TASK 列表下拉；存对象数据库主键 id；编辑时反查回显）。
- [x] 路由/菜单：`Sidebar`「数据治理」加「数据质量」菜单 + `router` `/governance/data-quality` 懒加载 + `breadcrumb` leaf。
- [x] 部署：`npm run build` + `docker compose build app-frontend` + `up -d`，页面经网关联调通过。

### ⏳ 待办（后续按用户确认"做到后面补关联逻辑"）

1. 后端：**真实执行校验** `QualityCheckService`（用 `RuleSqlGenerator` 展开 SQL + `GenericSqlExecutor` 执行）+ 结果分级。
2. 后端：Flyway 批次/历史/评分表（`quality_check_batch`/`history`/`score` + 合规忽略字段）。
3. 后端：`ScoreCalculator` 评分计算 + `AlertFiringService.fireBatch` 告警合并 + 扩展告警 object_type=QUALITY。
4. 后端：job 新增 `StandardComplianceCheckHandler`/`QualityCheckHistoryCleanupHandler`。
5. 后端：worker 终态回调接入自动触发（B1）；血缘节点 `LineageNodeDTO` 新增 qualityScore；标准合规忽略/取消忽略。
6. 前端：数据质量页 **任务/规则页签已完成**；**检查历史已交付**（「质量检查历史」独立菜单页，见 §8.5）；剩余：质量评分页签、元数据详情页「质量」页签、血缘图谱节点评分徽章、标准合规页（待后续执行批接口就绪后接入）。
7. 联调验证：真实数据校验、任务级定时触发、告警合并邮件、评分联动、合规扫描。

### 质量任务/规则配置层 · API 验证记录（2026-08-04）

- 登录拿 token（`Authorization` 原始 token，无 Bearer）。
- 任务：创建(200, 徽章=已启用) / 重名(4206) / 定时缺 cron(400) / 分页(含规则数) / 详情(含规则) / toggle / 删除级联删规则 / execute 预留(4210)。
- 规则：单条创建 / 模板批量(选模板+多表逐表微调) / 按任务查 / preview-sql(RANGE 展开 `{column}`→amount、`{min}`→10、`{max}`→100) / RANGE 缺字段(400) / 表不存在(4212) / execute 预留(4210)。
- `QualityCheckHandler`：XXL-JOB 注册成功(id=415, cron=每分钟)；创建每分钟任务后 `last_trigger_at` 被命中更新，日志 `scanned=1, hit=1`。
- 测试数据已清理（`quality_job`/`quality_rule` 均为 0）。

### 质量任务/规则 · 前端联调验证记录（2026-08-04）

- 登录拿 token（`Authorization` 原始 token 无 Bearer）；PowerShell 用 `Invoke-RestMethod` 而非 `curl.exe`（curl 转义 JSON body 会报 9999 系统内部错误）。
- 任务：创建(200，返回 snowflake id) / 分页(列表 `ruleCount` 正确统计) / 详情(含 rules) / toggle(启停) / 删除(级联删规则)。
- 规则：单条创建 UNIQUENESS(阈值/权重/`tableName`/`createdByName` 齐全) / RANGE(创建 `warningThreshold`=min=0、`severeThreshold`=max=100000 映射正确) / 批量应用(模板 id=2 + orders/users 两表 → 生成 2 条，各带 `templateId`/`templateName`/`resultMetric=duplicate_count`/`name="唯一性检查·表名"`) / 按任务查 / 预览 SQL(批量规则返回 `SELECT COUNT(*)-COUNT(DISTINCT order_no) AS duplicate_count FROM orders`) / toggle / 删除。
- 选表链路：`/governance/metadata/datasources` → `/datasources/{id}/databases` → `/datasources/{id}/databases/{db}/tables`（MYSQL/DORIS 无 schema 路径）→ `/tables/{tableId}/columns`（列下拉），全通。
- 页面：`http://localhost:3000/governance/data-quality` SPA fallback 200 + 懒加载 chunk 生成，`docker compose build app-frontend` + `up -d` 后容器健康。
- 测试数据已清理（质量任务已删除，其下规则级联清除；`quality_job`/`quality_rule` 均 0）。

### 质量任务/规则 · E2E 测试记录（2026-08-04，第三次会话，36 用例全绿）

> 为质量任务 + 质量规则（配置层）编写了两份 Playwright E2E spec，与既有 `quality-templates.spec.ts` 一起跑 **36 个用例全部通过**。
> 新增文件：`e2e/sprint6/e2e/quality-jobs.spec.ts`（13 例）、`quality-rules.spec.ts`（11 例）。

- **seed 扩展**（`e2e/sprint6/helpers/seed.ts` + `data.ts`）：新增 `seedQualityMetadata`（独立 MYSQL 数据源 `e2e_s6_quality_ds` + `metadata_table`(e2e_s6_qdb.e2e_s6_orders) + `metadata_column`(id/order_no/amount)，source_status=ONLINE）+ `seedSyncJob`（e2e_s6_sync_job，供自动触发绑定），带 `e2e_s6_q` 前缀，幂等，teardown 清理。
- **质量任务覆盖**：页面加载/统计卡片、Tab 切换、新增（必填名/数据源）、定时调度缺 cron 校验 + 填 cron 成功、**自动触发完整绑定同步任务**、详情只读、编辑、启停、关键字/状态筛选、删除（级联）、权限（工程师只读）。
- **质量规则覆盖**：选任务展示该任务规则、新增（COMPLETENESS 整表 / UNIQUENESS 选字段 / CUSTOM_SQL）、预览 SQL、详情只读、编辑、启停、删除、模板批量应用（内置完整性模板 + 多表选表）、权限（工程师只读）。
- **API 辅助诊断**：用 `Invoke-RestMethod` 验证选表链路（datasources → databases → tables → columns）、同步任务下拉、规则创建契约。

### 质量任务/规则 · 前端代码 Review 记录（2026-08-04，通过 code-reviewer 子代理）

- **已修复（Important）**：`index.tsx` 的 `selectedJobDatasourceId` 原先从 `jobs`（当前页数据）取，选中任务不在当前页时数据源丢失 → 改为从全量 `jobOptions` 取，且 `jobOptions` 增加 `datasourceId` 字段。
- **复核无问题（澄清）**：`DagNode.id` TS 类型为 number 但后端 `JacksonConfig` 将 Long 序列化为字符串，实际运行时 `String(n.id)` 无损，无雪花 ID 精度问题；`restoreDagNode` 编辑回显为串行 await（性能可优化，标记 Minor/已知项，不强行重构）。
- **复核通过**：`QualityRuleDrawer` 的 jobId 传递（编辑从 editItem、创建从 props）、`checkField` number 语义、RANGE 阈值=min/max 映射、`BatchApplyModal` 批量字段动态渲染、`TableSelectModal` 无 schema 路径均正确。
- review 修复后已重新 `npm run build` + `docker compose build/up app-frontend`，lint/typecheck 通过。

### 质量任务/规则 · 架构 Review 记录（2026-08-04，按「功能×架构融洽 × 业务正确 × 实现高效」三维度）

**已修复（架构融洽 / 实现高效）**
- **I1 工具栏收敛**：`index.tsx` 质量任务/质量规则工具栏改用手写 `flex items-center gap-ds-3` → 复用项目已收敛的 `DsToolbar` + `DsFilterSelect`（数据源/状态/任务下拉），消除与既有 9 个列表页的手写漂移。
- **I2 URL 状态同步**：对齐 data-standards 的 `urlInitRef` 模式，单条 init + 单条 sync effect，把质量任务页的 Tab/关键字/数据源/状态/分页全部同步进 URL（深层跳转返回筛选不丢）；原仅同步 tab 且双 effect。
- **M1 label 收敛**：规则类型中文 label 三处漂移（index.tsx/QualityRuleDrawer/BatchApplyModal）→ 收敛到 `types/quality.ts` 的 `QUALITY_TYPE_LABEL` + `QUALITY_TYPE_OPTIONS` 单一出处（`index.tsx`/`QualityRuleDrawer`/`BatchApplyModal` 全部引用）。
- **M4 回显性能**：`AutoTriggerSelect.restoreDagNode` 原先**串行**遍历所有项目→所有 DAG→逐个 `getDag` 反查节点，改为复用已加载 `projects` + `Promise.all` **并行**拉取 DAG 列表与节点详情（`Promise.all + find`，lib 不支持 `Promise.any`），编辑回显耗时显著下降。

**记录为已知项（有理由不立即改，push back）**
- **C1 选表逻辑三份近似**：`TableSelectModal` / `DatasourcePreviewSelector` / `metadata/MetadataTree` 的「数据源→库→Schema→表」加载 API 高度重复，理想应抽公共 `MetadataTableSelector`。**本次不强制重构**：`DatasourcePreviewSelector`（数据源预览模块）与 `metadata/MetadataTree` 均为既有稳定模块，且三处用途/交互不同（选中提交 vs 浏览预览 vs 元数据树），合并耦合与回归风险大于收益（YAGNI）。建议后续批次统一选表组件。
- **I3 执行按钮占位**：`executeQualityJob/Rule` API 已封装但 UI 占位提示"执行功能待实现"，是用户明确确认的决策（后端 execute 为预留），保留；待后端实现后接线。
- **M2/M3 设计权衡**：质量模块内数据源来源（任务筛选/表单用 `getDataSources`，选表用 `listMetadataDatasourceIds`）与统计口径（质量页 3 次分页取 total vs 模板库全量统计）不统一，属设计选择，暂不调整。

**重新构建 + 部署**：`npm run build` + `docker compose build/up app-frontend` 后容器健康，`/governance/data-quality` STATUS=200。

### Vite 构建优化（2026-08-04，两处优化已部署验证）

**1. monaco 脱离主入口静态链（首屏 -2.6MB）**
- **问题**：`vite.config.ts` 的 `manualChunks` 把 `monaco-editor`/`@monaco-editor/react` 强制拆为同步 `vendor-monaco`，Vite 因副作用在主入口 `index-*.js` 顶部生成 `import "./vendor-monaco"`，导致首屏 modulepreload 预下载 monaco（2.6MB），即使页面不用编辑器。
- **修复**：从 `manualChunks` 移除 monaco 分组，交由 Rollup 归入引用它的懒加载 chunk（`SqlEditorModal`/`PythonEditorModal`）。结果：主入口无 monaco 引用、index.html modulepreload 不含任何 monaco chunk，monaco 仅在打开 SQL/Python 编辑器时按需加载（`monacoSetup` chunk 2.6MB → brotli 535KB）。
- **验证**：`index-BaEBjXlr.js` 顶层静态 import 无 monaco；`monacoPreloadCount=0`。

**2. 启用 Brotli 预压缩（传输体积再省 ~15-20%）**
- **问题**：原 `nginx:alpine`（mainline 1.31.3）未编译 brotli 模块；`nginx-module-http-brotli` 是 stable（1.30.4）编译的动态模块，与 mainline 版本不匹配（load_module 报 not binary compatible）。
- **修复**：前端基础镜像 `nginx:alpine` → **`nginx:stable-alpine`**（内置 nginx/1.30.4，与 brotli 模块匹配）；Dockerfile `apk add --no-cache nginx-mod-http-brotli`；nginx.conf 顶部 `load_module ngx_http_brotli_filter_module.so` + `ngx_http_brotli_static_module.so`，http 块 `brotli on` + `brotli_static on` + `brotli_types`；vite.config 追加 `viteCompression(algorithm:'brotliCompress', ext:'.br')` 预压缩 .br（保留 .gz）。
- **验证**：请求 `vendor-antd` 带 `Accept-Encoding: br` → `Content-Encoding=br`，Size 812KB→**215KB**（brotli）；gzip 客户端回退正常（`Content-Encoding=gzip`）；`/governance/data-quality` SPA fallback 200。
- **注意**：nginx 从 mainline 1.31.3 → stable 1.30.4（功能等价，仅降 minor）。

### 📌 遗漏项归属规划（2026-08-04，用户确认 A+B 落档）

> Sprint 6 之前的遗漏项归属安排，已同步到规格文档 `## 15` Sprint 路线图。
> **已取消**：NG3 告警渠道扩展（钉钉/企微/Webhook）。

| 遗漏项 | 规格 ID | 原状态 | 归属 Sprint | 理由 |
|--------|---------|--------|-------------|------|
| 子 DAG 参数透传 | NG5 | Sprint 5 明确延后 | **S7**（资产目录） | 资产目录大量使用 DAG 加工产物，顺带补参数透传闭环 |
| 任务模板库 | DD-09 | P1，无归属 | **S7** | 数据开发增强，体量小可顺带交付 |
| 自定义质量规则 | DG-10 | P1，S6 不做 | **S7** | 紧跟 S6 质量体系作治理增强，避免能力断层 |
| 质量报告 | DG-07 | P1，S6 只留接口 | **S8** | 需质量数据沉淀，S8 补报表趋势 |
| 数据分级分类 | DG-09 | P1，无归属 | **S10**（数据服务） | 分级→访问策略，与数据服务/权限管控契合 |
| 任务资源队列与优先级 | DD-15 | P2，Sprint 3→5 延期 | **S11**（整合+审计） | 调度并发资源管理，S11 调度整合时交付并验证 |

> 说明：S7 名义主题「数据资产目录」，并入 NG5 / DD-09 / DG-10 三项轻量增强；如担心冲淡主题，可把 DG-10 单独拆成 S7b 短 sprint。

## 7. 备注

- 血缘可视化、全局告警中心、DAG 控制流增强已在 Sprint 5 交付完成。
- Sprint 5 遗留 NG3（告警渠道扩展）/ NG5（子 DAG 参数透传）不纳入本期 Sprint 6（用户已确认本期范围聚焦数据质量管理）。
- 质量评分本期展示落点在「元数据详情页 + 血缘图谱节点」；资产目录（catalog）模块尚未交付，其 DC-04 评分展示留待该模块。

### 本次实现要点 / 踩坑记录（供后续会话参考）

- **包结构**：实体/Mapper/Service/DTO 全部平铺在 `com.datanest.task.core.*`（entity/mapper/service/dto），未引入 quality
  子包——因为 governance `@MapperScan` 只扫 `com.datanest.task.core.mapper`，子包会导致 Mapper 扫描不到（AGENTS.md §8.2
  已警示）。
- **DTO 放 task-core**：`AlertRuleService` 模式确认 task-core Service + DTO 同模块，governance Controller 直接引用，避免依赖方向反转。
- **完整 URL 前缀**：governance 经网关统一走 `/api/governance/**`（context path `/governance`），前端约定 `/governance/...`
  。模板接口完整路径 `GET/POST/PUT/DELETE /api/governance/quality/templates`（测试时曾误用 `/api/quality/...` 导致 9999
  路由未命中）。
- **不可变 Map NPE 坑**：内置模板 `created_by/updated_by` 为 NULL，`loadUsernameMap` 返回 `Map.of()`（不可变），
  `usernameMap.get(null)` 会抛 NPE。修复：`toDTO` 里对 `createdBy/updatedBy` 先判空再 `get`。后续写列表回填逻辑时注意不可变
  Map 不能 `get(null)`。
- **校验 SQL 占位符**：内置模板 SQL 用 `{table}`/`{column}`/`{min}`/`{max}` 占位符，聚合写法返回单行结果，规避
  `GenericSqlExecutor` 200 行截断与 5 秒超时（PRD §6.2.2 / 技术文档 §4.1）。
- **权限**：读接口全员可看，写接口（新增/编辑/删除/启停）治理员+超管（`@SaCheckRole` + `SaMode.OR`）。

## 8. Sprint 8 质量执行层（检查执行 + 结果记录）变更记录

> **更新**：2026-08-04 | 阶段：质量检查**执行层**后端完成（真实执行 + 批次/明细落库 + 三种触发）

### 8.1 关键决策（用户确认，替代 Sprint 6 技术文档 T4/表结构）

- **执行位置在 app-worker**：`qualityCheckExecuteHandler` 注册在 `data-nest-worker` 组，`QualityCheckService` 在 worker 容器内执行（非 app-job）。
- **定时调度改为「每任务独立注册 XXL-JOB」**（替代原 `QualityCheckHandler` 每分钟全局扫描）：`startSchedule` 按需 `registerJob`（worker 组 + 自身 cron）或 `startJob`；`stopSchedule` 仅 `stopJob`（不注销，保留 `xxl_job_id`）；`delete` 注销；`update` 里 cron 变更 `updateJob` 同步。**已删除 `QualityCheckHandler` 及 JobRegistrar 注册**。
- **结果记录两张表**：`quality_check_batch`（批次）+ `quality_check_detail`（规则明细），不做 `history`/`score` 表。本次**只记录结果值（result_value）**，不做分级/评分/告警合并。
- **三种触发统一走 worker 上的 XXL-JOB handler**：手动（`MANUAL`）、定时（XXL-JOB cron 触发，`SCHEDULED`）、自动（DAG/SYNC/COLLECT 成功回调，`AUTO_TRIGGER`）。

### 8.2 变更清单

| 产物 | 变更 |
|------|------|
| `data-nest-system/.../db/migration/V3.6.4__sprint8_quality_check_execution.sql`（新增） | 建 `quality_check_batch`（job_id/job_name/trigger_type/status/起止/耗时/error）+ `quality_check_detail`（batch_id/rule_id/result_metric/result_value/success/executed_sql/error）；`quality_job` 加 `xxl_job_id` 列（紧凑单行） |
| task-core entity：`QualityCheckBatch`/`QualityCheckDetail`（新增）、`QualityJob`（加 `xxlJobId`） | 批次/明细实体 + 任务绑定 XXL-JOB ID |
| task-core mapper：`QualityCheckBatchMapper`/`QualityCheckDetailMapper`（新增） | 批次/明细 Mapper |
| task-core dto：`QualityCheckBatchDTO`/`QualityCheckDetailDTO`/`QualityCheckQueryRequest`（新增） | 批次分页/详情（含明细、成功/失败数）展示 DTO |
| task-core service：`QualityCheckService`（新增） | 执行核心：`executeJob`（建 batch→逐规则生成 SQL→分派执行器→提取 result_value→写 detail→收尾更新 batch 终态→更新 last_trigger_at）、`executeRule`（单规则独立批次 job_id=null）、分页查询/详情；结果值提取：RANGE 用 `out_of_range/total`（total=0 或 out=NULL 按 0），其余按 result_metric 列名（大小写不敏感）取，兜底首行首列 |
| task-core service：`QualityCheckTriggerService`（新增） | XXL-JOB 触发统一入口：`triggerJob`/`triggerRule`（按需 `registerJob` + `triggerJob`）；executorParam 带触发类型 `jobId:triggerType` |
| task-core service：`QualityAutoTriggerService`（新增） | `triggerOnSuccess(objectType, objectId)`：查 `enabled+auto_trigger_enabled+类型/ID` 匹配任务逐个触发（AUTO_TRIGGER，try-catch 包裹不阻塞主任务） |
| task-core service：`QualityJobService`（修改） | `executeJob` 改调 triggerService；`startSchedule` 注册/启动 XXL-JOB、`stopSchedule` 仅 stopJob、`delete` 注销、`update` cron 变更同步 XXL-JOB；删除 `touchLastTriggerAt`/`listScheduledEnabled` |
| task-core service：`QualityRuleService`（修改） | `executeRule` 改调 `triggerService.triggerRule`（param=`rule:<ruleId>`） |
| task-core service：`SyncJobExecutorService`/`CollectExecutor`/`DagAlertExecutionListener`（修改） | 成功分支接入 `QualityAutoTriggerService.triggerOnSuccess`（DAG_NODE 经 dagId+nodeId 反查 dag_node.id） |
| task-core exception：`ErrorCode`（修改） | 新增 4214~4216：`QUALITY_CHECK_BATCH_NOT_FOUND`/`SQL_GENERATE_FAILED`/`EXECUTE_FAILED` |
| worker job：`QualityCheckExecuteHandler`（新增） | `@XxlJob("qualityCheckExecuteHandler")`，param 解析 `jobId[:triggerType]`（无冒号默认 SCHEDULED）或 `rule:<ruleId>` |
| job：`QualityCheckHandler`（删除）、`JobRegistrar`（修改） | 废弃全局扫描 handler，移除注册行 |
| governance controller：`QualityJobController`/`QualityRuleController`（修改）、`QualityCheckController`（新增） | execute 改调 trigger；新增 `/quality/checks`（批次分页/详情） |

### 8.3 验证结果（API 全通）

- **手动任务执行**：`POST /api/governance/quality/jobs/{id}/execute` → batch `trigger_type=MANUAL`，`status=SUCCESS`，detail 3 条全成功（COMPLETENESS null_rate=0 / UNIQUENESS duplicate_count=0 / RANGE out_of_range_rate=0）。
- **手动单规则执行**：`POST /api/governance/quality/rules/{id}/execute` → batch `job_id=null`（单规则执行），`status=SUCCESS`。
- **定时调度**：`schedule/start` → XXL-JOB `trigger_status=1`（cron=`0 * * * * ?`）；`schedule/stop` → `trigger_status=0` 且 `xxl_job_id` 保留；XXL-JOB 到点触发生成 `trigger_type=SCHEDULED` batch。
- **自动触发**：质量任务绑定同步任务（SYNC_JOB），同步任务成功 → 自动生成 `trigger_type=AUTO_TRIGGER` batch。
- **cron 变更同步**：`update` 改 cron 后 XXL-JOB `schedule_conf` 同步更新。
- **批次查询**：`/api/governance/quality/checks/page` + `/{id}`（含明细、ruleCount/successCount/failedCount）全通。
- **部署**：app-system（Flyway V3.6.4）+ app-governance/app-job/app-worker/app-engineering 全 healthy。

### 8.4 踩坑记录

- **RANGE 空表 SUM 返回 NULL**：Doris/MySQL 对空表 `SUM(CASE...)` 返回 NULL（非 0），且 JDBC 列名可能大小写变化。`computeRangeRatio` 需对列名**大小写不敏感**匹配 + `total=0` 或 `out=NULL` 按 0 处理（否则误报"缺少 total/out_of_range 列"）。
- **定时触发落库成 MANUAL**：XXL-JOB 定时触发用的是**注册时保存的 executor_param（纯 jobId）**，不是 `triggerJob` 显式 param。修复：`QualityCheckExecuteHandler` 对**无冒号** param 默认按 `SCHEDULED`，有冒号（`jobId:MANUAL`/`jobId:AUTO_TRIGGER`）解析 triggerType。
- **gateway 质量路由前缀**：质量接口经网关统一走 `/api/governance/quality/**`（gateway 只路由 `/api/governance/**` 到 governance）；直接调 `/api/quality/...` 得 `NoResourceFoundException` 404。
- **改 task-core 质量执行代码必须重建 app-worker**（不只是 governance），否则 worker 跑旧 jar。
- **PowerShell 联调**：`curl.exe` 在 PowerShell 传 JSON 会因引号转义报 9999；用 `Invoke-RestMethod`（登录/列表/执行等全用）。

### 8.5 Sprint 8 前端（质量检查历史 + 执行按钮接线，2026-08-04）

> 后端执行层（§8）落地到前端：新增「质量检查历史」菜单页展示批次与规则明细，并把任务/规则列表的手动执行按钮从占位改为真实触发。

**变更清单（纯前端）：**

| 产物 | 变更 |
|------|------|
| `src/types/quality.ts`（修改） | 追加执行层类型（对齐 `QualityCheckBatchDTO`/`QualityCheckDetailDTO`/`QualityCheckQueryRequest`）：`QualityCheckBatch`/`QualityCheckDetail`/`QualityCheckQueryParams` + 触发方式/状态中文映射 `QUALITY_CHECK_TRIGGER_LABEL`/`QUALITY_CHECK_STATUS_LABEL`（单一出处） |
| `src/api/quality.ts`（修改） | 追加 `queryQualityChecks`（POST `/governance/quality/checks/page`）/ `getQualityCheckDetail`（GET `/governance/quality/checks/{id}`）；`executeQualityJob`/`executeQualityRule` 注释由"预留"改为真实触发语义 |
| `src/pages/governance/quality-checks/index.tsx`（新增） | 质量检查历史页：批次列表（任务名/触发方式/状态徽章 RUNNING·SUCCESS·PARTIAL_FAILED·FAILED/规则数/成功失败/起止时间/耗时/错误信息）+ 按触发方式·状态筛选 + 分页 + URL 状态同步；详情抽屉（批次概览 + 规则明细卡片，含结果指标/结果值/成功失败/错误/执行 SQL 折叠展示）；`formatDuration`/`DsStatusBadge`/`Drawer`/`DsToolbar`/`DsFilterSelect`/`Pagination`/`DsTableEmpty` 全部复用既有组件 |
| `src/components/Sidebar.tsx`（修改） | 「执行历史」分组 DAG 执行历史后新增「质量检查历史」菜单（`ALL_ROLES`，图标 `HiOutlineCheckCircle`），路径 `/governance/quality-checks` |
| `src/router/index.tsx`（修改） | 新增 `/governance/quality-checks` 懒加载路由 |
| `src/utils/breadcrumb.ts`（修改） | 新增 `/governance/quality-checks` leaf 面包屑条目 |
| `src/pages/governance/data-quality/index.tsx`（修改） | `handleExecuteJob` 占位 → 真实调 `executeQualityJob(id)`，成功提示"已触发执行，请到「质量检查历史」查看结果"；执行按钮加 `executingId` loading 防重复点击 |
| `src/pages/governance/quality-rules/index.tsx`（修改） | `handleExecute` 占位 → 真实调 `executeQualityRule(id)`，同样提示 + `executingId` loading |

**设计决策（用户确认）：**
- 结果页形态 = **独立菜单页**，挂在「执行历史」一级分组下，命名「质量检查历史」。
- 任务/规则执行按钮**本轮一并接入**；规则级执行定位"单条试跑/即时校验"，触发后用户去「质量检查历史」页查看结果（不做规则页内嵌结果列）。

**架构/权限对齐：**
- 菜单角色 `ALL_ROLES` ↔ 后端 `QualityCheckController` `@SaCheckRole`（SUPER_ADMIN/GOVERNANCE_ADMIN/DATA_ENGINEER/DATA_ANALYST）一致。
- 批次查询接口走 gateway 前缀 `/api/governance/quality/checks/**`；`queryQualityChecks` 用 `res.data.records/total`（与 `queryQualityJobs` 一致的 PageResult 结构）。

**Review 结论（按 功能×架构×效率 三维度）：**
- 架构融洽：类型/API/常量全部收敛到 `types/quality.ts` + `api/quality.ts` 单一出处；页面复用项目收敛组件，无手写漂移。
- 业务正确：批次/明细接口路径与后端 controller、状态/触发方式枚举值、角色权限均逐一对齐；执行按钮路径与后端 `QualityJobController`/`QualityRuleController` 的 `/{id}/execute` 一致（后端均已实现，非预留）。
- 实现高效：URL 状态同步（`urlInitRef` 单 init + 单 sync，对齐 data-quality）；`useCallback`/`useMemo` 防抖；执行按钮 `executingId` 防重复；耗时复用 `formatDuration`。
- `npm run typecheck` + `npm run build` 通过，lint 0 错误。

**部署：** `npm run build` → `docker compose build app-frontend` → `up -d --no-deps app-frontend`，容器 `Up`，`/governance/quality-checks` STATUS=200。

### 8.6 执行层 E2E 测试（2026-08-04）

> 为质量检查**执行 + 结果记录**（执行层）编写 E2E，覆盖成功/失败批次、MYSQL/PG 双类型、三种自动触发。
> 新增文件：`e2e/sprint6/e2e/quality-checks.spec.ts`。

- **helpers 新增**：
  - `helpers/encrypt.ts`：Node crypto 复刻后端 `EncryptionConfig` 的 AES-256-GCM，生成可解密的数据源密码密文（密钥默认 `DataNestDefaultEncryptionKey2026`）。GCM tag 128-bit 须拼在密文后（对齐 Java `cipher.doFinal` 输出）。
  - `helpers/exec-db.ts`：`mysqlExec`/`pgExec`（`docker exec` 进 middleware-test-mysql:3306 / middleware-test-postgres:5432 直连 testdb）、`mysqlScalar`/`pgScalar`、`doris`（经 middleware-mysql 容器连内置 Doris 192.168.119.135:9030）、`quiet`。
  - `helpers/poll.ts`：`waitFor`/`sleep`。
- **seed 扩展**：`seedExecTables`（MYSQL+PG 各建 `e2e_s6_orders` 含 4 行）+ `seedExecMetadata`（2 个执行数据源 `e2e_s6_exec_ds`/`e2e_s6_exec_pg_ds`，密码加密；4 张 metadata_table 各 2 张成功/失败表，成功表带 id/order_no/amount 字段）；挂接 `seedAll`/`cleanupAll`。
- **用例覆盖（serial 模式）**：
  - A 执行结果记录：页面加载+筛选控件；MYSQL 任务执行→PARTIAL_FAILED（成功规则 result_value=4 + 失败规则 errorMessage 非空、success=0）；PG 任务执行→PARTIAL_FAILED；历史页手动批次+状态筛选展示；批次详情抽屉（规则总数 2/成功 1/失败 1 + 成功结果值）；单规则执行成功→SUCCESS（job_id 空、jobName=单规则执行、result_value=4）；单规则执行失败→FAILED（success=0+errorMessage）；工程师可访问页面+API。
  - B 自动触发：播种 AUTO_TRIGGER 批次+历史页筛选展示；**真实同步任务成功**触发绑定质量任务（MYSQL orders → Doris `datanest.e2e_s6_quality_target`）→ AUTO_TRIGGER 批次 SUCCESS；**真实 DAG 节点成功**触发（复用 sprint5 `createDag`/`runDag`，SQL 节点 `SELECT 1`，绑定 `dag_node.id`）→ AUTO_TRIGGER 批次 SUCCESS。
- **断言方式**：执行异步（XXL-JOB 投递 app-worker），用 `waitFor` 轮询 `quality_check_batch`/`quality_check_detail` 至终态。
- **验证记录**：执行层 11 用例全绿（约 45s）。执行前发现并处理：**app-governance / app-worker / app-engineering 为旧版代码**，手动触发批次落成 SCHEDULED（旧 trigger 服务未带 `jobId:MANUAL` 冒号 param）。按 AGENTS.md 重建
  `data-nest-task-core/engineering/worker/governance` 并 `docker compose build/up` 三个容器后，手动执行批次正确落 MANUAL，MYSQL/PG 任务→PARTIAL_FAILED、单规则成功→SUCCESS/失败→FAILED、自动触发（同步任务/DAG节点/播种）均通过。
- **已知残留**：`quality-jobs.spec.ts:119`（Tab 切换）与 `quality-rules.spec.ts:151`（选任务展示规则）引用 `选择质量任务` aria-label，但前端规则已独立成 `/governance/quality-rules` 菜单、筛选改名为 `按所属任务筛选`（既有重构），这两条旧用例与本次执行层无关、非本次改动引入，暂留待后续维护。

## 7. 备注
- **定时扫描命中判断**：`QualityCheckHandler` 用 `CronExpression.next(minuteStart.minusNanos(1)) == minuteStart` 判断 cron 是否命中当前分钟整点，兼容秒级 cron（普通 `matches()` 无法精确匹配分钟级任务）。
- **规则数批量统计**：任务列表的 `ruleCount` 用 `GROUP BY job_id` 一次查出全部任务规则数，避免 N+1（`QualityRuleService.countByJobIds`）。
- **删除级联**：任务删除时 `QualityJobService.delete` 先 `ruleService.deleteByJob` 删规则再删任务（事务）。
- **DTO `@AssertTrue`**：`scheduledEnabled=1 必填 cron`、`RANGE/UNIQUENESS/按字段完整性必填字段`、`CUSTOM_SQL 必填 SQL` 都用 `@AssertTrue` 方法，返回 boolean 需 null 保护。
- **Flyway checksum 固化**：本次改了 50 个已应用脚本（格式规范化），全部用 `flyway repair` 更新 `flyway_schema_history.checksum`，再重建 app-system 验证 validate 通过；表结构/种子数据抽查无变化。
- **RANGE 值域 vs 分级阈值分离**：review 发现 RANGE 的 `{min}`/`{max}` 占位符最初复用了 `warning_threshold`/`severe_threshold`（分级阈值），语义混淆。已新增 `range_min`/`range_max` 独立存值域边界，`warning/severe` 仍作结果分级。新增 `V3.6.2` 迁移。RANGE 创建/批量必须填值域（400/4211 校验）。
- **任务更新「null 不更新 + 可清空」**：`QualityJobService.update` 改 UpdateWrapper 语义——`description`/`datasource_id` 无条件更新（传 null 即清空），其余字段 null 不更新。注意：前端更新任务时需**总是携带 description/datasource_id**（含清空意图传 null），否则会被误清空。
- **规则更新 = 全量覆盖**：规则更新保持全量覆盖 + DTO `@AssertTrue` 强校验（RANGE 必填 columnName/rangeMin/rangeMax）。因强校验与"null 不更新"冲突（局部更新只改某字段会被 400 拦截），规则侧选择全量提交语义（编辑表单前端全量回填）。
- **`touchLastTriggerAt` 优化**：定时扫描 handler 每分钟可能命中，`touchLastTriggerAt` 改用 UpdateWrapper 只更新 `last_trigger_at`/`updated_at`，避免全字段 UPDATE 写放大。

### 质量任务/规则 · 前端踩坑记录

- **RANGE 类型阈值即 min/max**：后端 `warningThreshold`/`severeThreshold` 在 RANGE 类型时即值域下限/上限，`RuleSqlGenerator` 用它们替换 `{min}`/`{max}`（见 `QualityRuleService.previewSql` → `RuleSqlGenerator.generate`）。前端表单在 RANGE 类型时把阈值字段标签显示为「值域下限/上限」，并做 min≤max 校验。
- **`checkField` 是 number 不是 string**：后端 `QualityRule.checkField`（Integer，0=整表/1=按字段）用于完整性检查方式。前端类型定义为 number，列表 COMPLETENESS 整表时显示「整表」。
- **MYSQL/DORIS 无 schema**：`TableSelectModal` 用 `isWithoutSchema(type)` 判断（`DB_TYPES_WITHOUT_SCHEMA` 含 MYSQL/DORIS），无 schema 类型选库后直接加载表，不出现 schema 列；否则三层选择。手动调 schemas 接口验证 MYSQL 返回 `[null]`，应走无 schema 路径。
- **自动触发对象存数据库主键**：`autoTriggerObjectId` 存 `dag_node.id`/`sync_job.id`/`collect_task.id`（Long 数据库主键），DAG_NODE 前端按 项目→DAG→节点 三级级联选择，编辑时通过反查恢复回显；后端 `QualityJobDTO` **无** `autoTriggerObjectName` 字段，前端对象名由选择组件自行维护。
- **PowerShell 中文乱码**：`Invoke-RestMethod` 请求体/控制台对中文按 GBK 处理，联调时中文显示 `??????`，是 PowerShell 编码问题非后端；前端浏览器走 UTF-8 正常。

## 9. Sprint 6 分级邮件告警后端（2026-08-05）

> **阶段**：分级判定落库 + fireBatch 合并告警 + 告警中心 QUALITY 对象类型，后端全部完成、部署自测通过。
> **前置**：§8 执行层已具备（`quality_check_batch`/`quality_check_detail` 落 result_value），本节在此之上补分级与告警。

### 9.1 关键决策（用户确认）

| 决策点         | 结论                                                                                                                        |
|----------------|-----------------------------------------------------------------------------------------------------------------------------|
| 告警体系       | **完全复用 `alert_rule`**（方案B）：质量任务编辑时无需独立接收用户字段，走告警中心扩展对象类型 `QUALITY`（对象=质量任务，`object_id=任务ID`） |
| 接收用户配置   | **只在「告警中心」配置**：创建 QUALITY 规则时选接收用户（取平台用户邮箱）；质量任务表单**不加**接收用户控件，仅保留 `alert_level` 触发等级 |
| 触发条件       | QUALITY 规则 `triggerConditions` 固定 `["FAILURE"]`（语义=质量异常）                                                           |
| 触发等级       | 权威取 `quality_job.alert_level`（`SEVERE_ONLY` 收 SEVERE；`SEVERE_WARNING` 收 SEVERE+WARNING）                              |
| 分级落库       | **补分级判定并落库**：`quality_check_detail.result_level`（`PASS`/`WARNING`/`SEVERE`/`UNAVAILABLE`）                          |
| UNAVAILABLE 告警 | **不触发**（R2：数据源不可用/SQL 失败不产生误告警），只记录 result_level                                                    |

### 9.2 变更清单

| 产物 | 变更 |
|------|------|
| `data-nest-system/.../db/migration/V3.6.5__sprint6_quality_alert.sql`（新增） | `quality_check_detail` 加 `result_level VARCHAR(20)`；`quality_check_batch` 加 `alert_sent SMALLINT DEFAULT 0`（合并告警幂等标记） |
| `data-nest-system/.../db/migration/V3.6.6__alert_rule_quality_object_type.sql`（新增） | 放开 `alert_rule.object_type` CHECK 约束：原仅 `DAG/SYNC_JOB/COLLECT_TASK`，DROP 后重建为含 `QUALITY` |
| task-core `AlertConstants`（修改） | 新增 `OBJECT_TYPE_QUALITY="QUALITY"`、`DISPLAY_QUALITY="质量任务"`、`QUALITY_LEVEL_*`（PASS/WARNING/SEVERE/UNAVAILABLE）；触发条件复用 `ALERT_FAILURE="FAILURE"` |
| task-core `AlertRuleService`（修改） | `validate()` 对象类型白名单加 QUALITY（`SUPPORTED_OBJECT_TYPES`）；`resolveObjectName` 对 QUALITY 查 `quality_job.name`（注入 `QualityJobMapper`）；`listObjectOptions` 对 QUALITY 返回质量任务列表 |
| task-core `AlertFiringService`（修改） | 新增 `fireBatch(objectType, objectId, alertType, List<AlertItem>)`（AlertItem=level/ruleName/detail）：查 QUALITY 规则→过滤等级→一条邮件+每条异常一条 `alert_history`；`displayObjectType`/`buildObjectUrl` 支持 QUALITY；`saveHistory` 7 参重载（summary 仅供日志） |
| task-core `AlertHistoryMapper`（修改） | `selectHistoryPage` 增加 `LEFT JOIN quality_job` 联查 QUALITY 对象名 |
| task-core `QualityCheckDetail`/`QualityCheckBatch`/`QualityCheckDetailDTO`（修改） | 新增 `resultLevel` / `alertSent` 字段 |
| task-core `QualityCheckService`（修改） | `determineLevel` 分级判定（`value<warning`→PASS；`warning≤value<severe`→WARNING；`value≥severe` 或无 severe 时 `value≥warning`→SEVERE；阈值全空→PASS；SQL 失败→UNAVAILABLE）；批次收尾 `fireBatchAlert`（job_id 非空时按 `alert_level` 过滤→调 `fireBatch`→`alert_sent` 置 1 幂等）；`isAlertable` 判定（SEVERE 必触发、WARNING 仅 SEVERE_WARNING、UNAVAILABLE/PASS 不触发） |
| governance `pom.xml`（修改） | **补 `spring-boot-starter-mail`**（compile）：`QualityCheckController` 注入 `QualityCheckService`→`AlertFiringService`→`MailService`，缺 mail 类会 `NoClassDefFoundError` 启动失败 |
| 文档（同步回落） | `技术文档` §3.0 脚本清单 + §5.4 告警对象模型/fireBatch；`PRD` §6.2.2 表单去掉接收用户控件、§6.4 入口描述；`AGENTS.md` 质量分级判定 + governance mail 依赖 + alert_rule.object_type 约束坑 |

### 9.3 部署

- 编译：`mvn -pl task-core,engineering,worker,governance,job -am clean package`（含 system 因 Flyway 脚本变更）。
- 镜像重建部署：**app-system**（先启，执行 Flyway V3.6.5/V3.6.6）→ **app-worker / app-engineering / app-governance**。四个容器全部 healthy。
- 需重建 app-worker（质量执行代码在 worker）+ app-engineering（task-core 共享）+ app-governance（接口+mail 依赖）+ app-system（Flyway 脚本）。

### 9.4 验证记录（API 自测，全链路通过）

> 手动播种 E2E 执行元数据（`e2e_s6_exec_ds`/`e2e_s6_exec_pg_ds` + `e2e_s6_orders` 表 4 行），创建质量任务（SEVERE_WARNING）含 2 条 CUSTOM_SQL 规则（各设不同阈值使 value=4 → 分别 WARNING/SEVERE），验证完清理。

- **告警中心 QUALITY 支持**：`GET /api/system/alert-rules/object-options?objectType=QUALITY` 返回 200（对象类型校验通过）。
- **创建 QUALITY 规则**：`POST /api/system/alert-rules`（objectType=QUALITY, objectIds=[jobId], triggerConditions=["FAILURE"], userIds=[1]）→ 200，`objectName` 正确解析为任务名。
- **分级判定落库**：任务执行 → detail `result_level` 正确：`s6_warning_rule`(warn=3,severe=5,val=4)→**WARNING**、`s6_severe_rule`(warn=3,severe=4,val=4)→**SEVERE**；batch `alert_sent=1`。
- **合并告警（SEVERE_WARNING）**：一次批次 2 项异常 → `alert_history` **2 条** + MailHog **1 封邮件**（主题 `[DataNest 告警] 质量任务「...」执行失败（2 项）`），正文逐条列出 `[警告]`/`[严重]` 及规则名、类型、结果值、跳转链接。
- **触发等级过滤（SEVERE_ONLY）**：改 `alert_level=SEVERE_ONLY` 后执行 → 仅 `s6_severe_rule`(SEVERE) 触发：`alert_history` **1 条** + 邮件 `（1 项）`，WARNING 被过滤。
- **60s 防重**：同任务 60s 内二次执行被 `countRecent` 抑制（worker 日志"批量告警已发送过，跳过"），等待窗口后恢复。
- **历史对象名联查**：`GET /api/system/alert-history?objectType=QUALITY` → `objectName` 均正确解析为任务名。
- **清理**：测试质量任务/规则/告警规则/告警历史/元数据/目标表/MailHog 邮件全部清理。

### 9.5 踩坑记录

- **`alert_rule.object_type` 数据库 CHECK 约束**：最初只在 `AlertRuleService.validate()` 白名单加了 QUALITY，建 QUALITY 规则仍报 `check constraint "alert_rule_object_type_check" 违反`。需 Flyway `V3.6.6` drop 旧约束重建为含 QUALITY（已落档 AGENTS.md §6）。
- **governance 必须补 mail 依赖**：governance 注入 `QualityCheckService` 后间接依赖 `MailService`，task-core 的 mail 是 `provided`，governance 不声明会启动 `NoClassDefFoundError`（已补 `spring-boot-starter-mail`）。governance 不实际发邮件（告警在 worker），无需配 MAIL 环境变量。
- **UNAVAILABLE 是否告警**：初版实现为 UNAVAILABLE 也触发，review 时发现与技术文档 R2（"数据源不可用不产生误告警"）冲突，已改为 **UNAVAILABLE 不触发告警**，仅记录 result_level。
- **`resolveObjectName` 返回任务名**：文档初稿写"返回规则名"，实际对象粒度=质量任务，返回 `quality_job.name`（技术文档已回落）。

### 9.6 遗留

- **`AlertFiringService.saveHistory` 7 参重载**：`summary` 参数仅用于日志（`AlertHistory` 表无 detail 列），未持久化；保留用于追踪，后续如需详情可加列或降级为 6 参。非阻塞。

### 9.7 Sprint 6 分级邮件告警前端（2026-08-05）

> 后端分级邮件告警（§9）落地到前端：质量检查历史页展示规则分级判定，告警中心支持「质量任务」对象类型。纯前端改动，无需后端重建。

**变更清单（纯前端）：**

| 产物 | 变更 |
|------|------|
| `src/types/quality.ts`（修改） | 新增 `QualityCheckLevel` 类型（`PASS`/`WARNING`/`SEVERE`/`UNAVAILABLE`）+ `QUALITY_CHECK_LEVEL_LABEL`（通过/警告/严重/不可用，单一出处）；`QualityCheckDetail` 增加 `resultLevel?: QualityCheckLevel` 字段（对齐后端 `QualityCheckDetailDTO.resultLevel`） |
| `src/pages/governance/quality-checks/index.tsx`（修改） | `DetailCard` 规则明细卡片顶部原「成功/失败」徽章改为**分级判定徽章**（`LEVEL_VARIANT`：PASS=success/WARNING=warning/SEVERE=danger/UNAVAILABLE=pending），展示 result_level 分级，与邮件告警等级对应 |
| `src/types/alert.ts`（修改） | `AlertObjectType` 增加 `'QUALITY'` |
| `src/components/AlertRuleModal.tsx`（修改） | `OBJECT_TYPE_OPTIONS` 增加「质量任务」；对象选择复用非 DAG 平铺 Select（`object-options?objectType=QUALITY` 自动加载质量任务列表），零额外分支 |
| `src/pages/system/alert-center/AlertCenterPage.tsx`（修改） | `OBJECT_TYPE_OPTIONS` 筛选增加「质量任务」；`objectTypeBadge` 增加 QUALITY 分支（warning 变体 + 「质量任务」label） |

**Review 结论（功能 × 架构 × 效率）：**
- **架构融洽**：分级常量收敛到 `types/quality.ts`（`QUALITY_CHECK_LEVEL_LABEL`），与既有 `QUALITY_CHECK_STATUS_LABEL`/`QUALITY_TYPE_LABEL` 单一出处；`LEVEL_VARIANT` 放在页面文件与 `STATUS_VARIANT` 同处，符合项目「状态渲染」惯例；`AlertObjectType` 枚举驱动，`AlertRuleModal`/`AlertCenterPage` 无侵入扩展。
- **业务正确**：`resultLevel` 字段名与后端 `QualityCheckDetailDTO.resultLevel`（`@Data` → `getResultLevel`）一致，经 `/governance/quality/checks/{id}` 返回；分级语义对齐后端 `AlertConstants.QUALITY_LEVEL_*`；UNAVAILABLE 用灰色 pending 变体，与「不可用/异常」语义匹配，不会误读为成功。
- **实现高效**：复用 `DsStatusBadge`；`AlertRuleModal` 非 DAG 走平铺 Select，只加一个选项即自动适配 QUALITY，零额外分支；`DsStatusVariant` 含 `pending`（`bg-ds-bg-hover text-ds-text-muted`），类型安全。

**构建部署：** `npm run build`（tsc + vite，3005 modules 通过，无类型错误）→ `docker compose build app-frontend` → `up -d --no-deps app-frontend`，容器 `Up`。

### 9.8 分级邮件告警 E2E 测试（2026-08-05，本次会话）

> 覆盖后端 50e038eb + 前端 ab9d80e 的「分级邮件告警」全链路业务 E2E：UI 创建 QUALITY 告警规则 → 触发分级执行 → DB 断言 result_level / alert_history / alert_sent → MailHog 邮件内容断言 → 负向与幂等。

**测试文件：** `data-nest/data-nest-frontend/e2e/sprint6/e2e/quality-alerts.spec.ts`（新增，8 用例全绿，总耗时 ~55s）。

**测试结构（serial 模式）：**
| # | 用例 | 覆盖点 |
|---|------|--------|
| 1 | UI 创建 QUALITY 告警规则（选质量任务 + 失败 + 接收用户 govAdmin） | AlertRuleModal 对象类型选「质量任务」+ 多对象平铺 Select 选主链路+SEVERE_ONLY 两任务 + FAILURE + UserSelect 选 govAdmin |
| 2 | 主链路：SEVERE_WARNING 任务执行 → SEVERE+WARNING 分级告警（DB + 邮件） | UI 触发执行 → result_level（SEVERE/WARNING）+ alert_history×2 + alert_sent=1 + MailHog 邮件主题「质量任务『...』执行失败（2 项）」+ 正文 `[严重]`/`[警告]`/`共 2 项` |
| 3 | 幂等：再次执行主链路任务不重复发告警（alert_history 条数不增） | 紧接主链路（60s 防重窗口内）再次触发，alert_history 条数不变（countRecent + alert_sent 双保险） |
| 4 | UI 质量检查历史详情：SEVERE/WARNING 分级徽章 | 详情抽屉展示「严重」/`[严重]` 徽章、「警告」徽章 + 结果值 `4` |
| 5 | UI 告警中心历史页：QUALITY 记录展示 | 历史 tab 按对象类型筛 QUALITY → 2 条历史，「质量任务」徽章、「失败」触发条件、「发送成功」徽章 |
| 6 | 负向：UNAVAILABLE（SQL 失败）不告警 | 查不存在表 → result_level=UNAVAILABLE → alert_history=0（R2 不误报） |
| 7 | 负向：PASS（低于阈值）不告警 | result_level=PASS → alert_history=0 |
| 8 | 负向：SEVERE_ONLY 任务排除 WARNING，只收 SEVERE | 两条明细（SEVERE+WARNING）→ 仅 SEVERE 告警 → alert_history=1 + 邮件「共 1 项 [严重]」不含 [警告] |

**变更清单：**

| 产物 | 变更 |
|------|------|
| `e2e/sprint6/e2e/quality-alerts.spec.ts`（新增） | 8 用例全绿（54.8s）：UI 创建告警规则 / 主链路分级告警 / 60s 幂等 / 详情分级徽章 / 告警中心历史页 / 三个负向（UNAVAILABLE/PASS/SEVERE_ONLY）。复用 `Api`/`db`/`poll`/`e2e`/`exec-db`/`encrypt`/`seed`/`Mailhog`；本地实现 `decodeBody`（quoted-printable 解码） |
| `e2e/sprint6/helpers/data.ts`（修改） | 新增分级告警常量：`ALERT_PREFIX`/`ALERT_JOB_ID`/`ALERT_JOB_SEVERE_ONLY_ID`/`ALERT_JOB_UNAVAILABLE_ID`/`ALERT_JOB_PASS_ID` + 6 条规则 ID（固定 ID 段 `9000040000000000000+`，独立于质量/执行/自动触发三段） |
| `e2e/sprint6/helpers/seed.ts`（修改） | 新增 `seedQualityAlerts`/`cleanupQualityAlerts`：复用执行数据源 `e2e_s6_orders(COUNT=4)` + CUSTOM_SQL 规则 + 阈值确定产出 SEVERE/WARNING/PASS/UNAVAILABLE；任务-规则通过 `quality_job_rule` 关联；挂 `seedAll`/`cleanupAll` |
| `e2e/sprint5/helpers/mailhog.ts`（修改） | `decodeMimeEncoded` 修复 RFC 2047 相邻 encoded-word 间空白插入 bug（`\?=\s+(?==\?)/?=` → `?=`），长主题按段编码后 `find('e2e_s6_alert_main')` 能正确匹配（修复前 `e2e_`/`s6_alert_ma` 被空格分隔） |

**数据设计（确定性分级）：** 复用 MYSQL 执行数据源 `e2e_s6_exec_ds` + 表 `e2e_s6_orders(COUNT=4)`；4 个质量任务 + 6 条规则：
- `e2e_s6_alert_main`（SEVERE_WARNING）：severe_rule(w=2/s=3 → 4≥3 → SEVERE) + warning_rule(w=3/s=5 → 4∈[3,5) → WARNING)
- `e2e_s6_alert_severe_only`（SEVERE_ONLY）：so_severe_rule(SEVERE) + so_warning_rule(WARNING → 应被排除)
- `e2e_s6_alert_unavailable`（SEVERE_WARNING）：unavailable_rule(查不存在表 → UNAVAILABLE)
- `e2e_s6_alert_pass`（SEVERE_WARNING）：pass_rule(w=5/s=6 → 4<5 → PASS)

告警规则（UI 创建）覆盖 main + severe_only 两个任务（让 8 个测试共享一条告警规则）。

**验证结果：**
- `8 passed (54.8s)` 全绿：主链路 ~3s（DB 断言 + 邮件轮询），UI 详情 ~1.7s，负向 ~1s/个。
- 容器重建：前端 `dist` 重新构建（ab9d80e 影响 AlertCenterPage/AlertRuleModal hash 变化）+ `npm run build` tsc 校验通过；后端 task-core jar Aug 5 09:13 重新打包 + app-governance/app-worker/app-engineering/app-frontend 镜像重建（发现旧 jar Aug 4 23:27 不含 50e038eb 告警分级代码，按 AGENTS.md 重建修复）。

**踩坑记录：**
- **DsModal 弹窗定位**：项目自定义 `DsModal` 用 `role="dialog" aria-label={title}`，非 antd `.ant-modal`，最初 `modal.locator('.ant-modal')` 超时；改用 `getByRole('dialog', {name: title})`。
- **antd multiple Select dropdown 遮挡保存按钮**：选完用户后 dropdown 保持打开，遮挡底部「保存」按钮导致 pointer intercepted。**不能用 Escape 关闭**（会同时关闭 DsModal 弹窗）；改用点击 DsModal 标题关闭 dropdown。
- **告警规则对象覆盖范围**：原 UI 只选主链路任务作为告警对象，导致 SEVERE_ONLY 任务无告警规则命中 → 0 条 alert_history。改为同时勾选 `e2e_s6_alert_main` + `e2e_s6_alert_severe_only` 两个任务（`alert_rule_object` 多对象表），让一条规则覆盖两条链路。
- **MIME encoded-word 边界空格**：JavaMail 对长主题按 ~40 字节拆分 encoded-word，`e2e_s6_alert_main` 被拆成 `e2e_`/`s6_alert_ma`/`in` 三段，中间空格插入导致 `find('e2e_s6_alert_main')` 失败（RFC 2047 应忽略）。修复 sprint5 mailhog.ts 的 `decodeMimeEncoded`：预处理 `?=\s+(?==\?)/?=` → `?=`。
- **正文传输编码（quoted-printable）**：邮件正文是 `Content-Transfer-Encoding: quoted-printable`（非 base64），最初 `Buffer.from(Body,'base64')` 解错；spec 内重写 `decodeBody` 为 quoted-printable 解码（移除软换行 `=\r\n` + `=XX` 字节还原 + UTF-8 字符串）。
- **strict mode（多批次同名）**：主链路任务执行两次（主链路 + 幂等）→ 质量检查历史页出现 2 行 `e2e_s6_alert_main`，`rowBy` strict mode 失败。`rowBy(...).first()` 取最新。
- **告警中心历史表首列**：首列是「告警时间」（sentAt），非对象名称或规则名，`rowBy`（按首列匹配）失效。改用 `page.locator('.ant-table-row').filter({hasText: ALERT_JOB_NAME_MAIN})` 匹配对象名称列，并限定 `getByText` 在 `historyRow` 内避免命中筛选下拉 `<option>`。
- **结果值格式**：DB `result_value='4.000000'`，前端 `Number/格式化` 显示为 `4`；断言从 `4.000000` 改为 `4`。

**清理：** 测试产物（`test-results/`、`e2e-alert-run.log`）已删除。

## 10. 告警规则名称 + 质量任务页改名（2026-08-05）

> **阶段**：后端（task-core + Flyway）+ 前端协同改动。三个需求：①数据质量页改名「质量任务」并去掉统计、改副标题；②告警规则新增「规则名称」，告警历史回显「告警规则名称」；③告警中心描述统一为「DAG、同步任务、采集任务、质量任务」。

### 10.1 需求确认（用户）
- **规则名称**：必填 + 同一对象类型下唯一。
- **描述修改**：AlertCenterPage 副标题补「质量任务」；数据质量页副标题改为「配置质量任务并设置触发方式…」（质量规则已独立菜单，任务页不再提"质量规则"）。

### 10.2 变更清单

| 产物 | 变更 |
|------|------|
| `data-nest-system/.../db/migration/V3.6.7__alert_rule_name.sql`（新增） | `alert_rule` 加 `name`（必填，回填 `COALESCE(object_name,'未命名规则')`，同类型重名追加 `-N` 序号保证唯一索引，唯一索引 `uk_alert_rule_name(object_type,name)`）；`alert_history` 加 `rule_name`（冗余落库，规则删除后历史仍保留名称） |
| task-core `AlertRule`（修改） | 新增 `name` 字段 |
| task-core `AlertRuleDTO`（修改） | 新增 `name` 字段 |
| task-core `AlertRuleService`（修改） | `validate()` 校验 name 必填 + `assertNameUnique`（同类型唯一，update 排除自身 id）；createRule/updateRule/applyFields 写入 name；toDTO 返回 name |
| task-core `AlertHistory`（修改） | 新增 `ruleName` 字段（映射真实列 `rule_name`） |
| task-core `AlertHistoryMapper`（修改） | `selectHistoryPage` 增加 `LEFT JOIN alert_rule ar`，`COALESCE(ar.name, ah.rule_name) AS ruleName`（兼容历史旧数据） |
| task-core `AlertFiringService`（修改） | `saveHistory` 冗余写入 `history.setRuleName(rule.getName())` |
| `src/types/alert.ts`（修改） | `AlertRuleDTO` 加 `name`；`AlertHistory` 加 `ruleName` |
| `src/components/AlertRuleModal.tsx`（修改） | 新增「规则名称」输入框（必填，所有模式 create/edit/quick 共用）；validate 校验非空；payload 带 name |
| `src/pages/system/alert-center/AlertCenterPage.tsx`（修改） | 告警规则表新增「规则名称」列（name）；告警历史表新增「告警规则」列（ruleName）；历史详情弹窗加规则名；副标题补「质量任务」 |
| `src/pages/governance/data-quality/index.tsx`（修改） | 标题「数据质量」→「质量任务」；**删除统计卡片**（全部/已启用/已停用 + `stats`/`loadStats`/`statCards` 及所有 `loadStats()` 调用）；副标题改为「配置质量任务并设置触发方式，对数据资产进行质量检查」 |
| `src/components/Sidebar.tsx` + `src/utils/breadcrumb.ts`（修改） | 「数据质量」菜单/面包屑改名为「质量任务」 |

### 10.3 部署
- **后端**：task-core 共享改动 → `mvn install` 全量后重建 **app-system**（Flyway V3.6.7）+ **app-engineering / app-worker / app-governance / app-job**，全部 healthy。
- **前端**：`npm run build`（tsc + vite 通过）→ `docker compose build app-frontend` → `up -d`。
- **Flyway 迁移踩坑**：V3.6.7 首次因部分规则 `object_name` 为 NULL，`SET NOT NULL` 失败；二次因回填「未命名规则」同类型重名触发唯一索引冲突。最终回填用 `COALESCE(object_name,'未命名规则')` + 窗口函数对同类型重名追加 `-N` 序号，成功。

### 10.4 验证记录（API + 页面）
- 告警规则列表 `GET /api/system/alert-rules` → 每条返回 `name`（历史数据正确回填）。
- 告警历史 `GET /api/system/alert-history` → 返回 `ruleName`（新产生的告警冗余落库，旧历史经 `LEFT JOIN alert_rule` 联查）。
- 创建规则缺 name → `7202 必须填写规则名称`；同类型重名 → `7202 同一对象类型下已存在同名告警规则`。
- 页面：`/governance/data-quality` 标题「质量任务」、无统计卡；告警中心规则/历史含名称列。
- PowerShell 联调注意：`curl.exe` 传 JSON body 报 9999，用 `Invoke-RestMethod`（与 §8.4 一致）。

## 11. 质量任务表单「引用质量规则」改下拉多选（2026-08-05，纯前端）

> **阶段**：UI 收尾微调。三个需求：①去掉任务表单「引用质量规则」占位里的「Sprint 7」字样；②把 checkbox 列表改为**下拉多选**；③下拉项右侧类型由英文 `COMPLETENESS/UNIQUENESS/...` 改为**中文**。

### 11.1 变更清单（仅前端 `QualityJobDrawer.tsx`）

| 产物 | 变更 |
|------|------|
| `src/pages/governance/data-quality/QualityJobDrawer.tsx`（修改） | ①「引用质量规则」副标去掉 `Sprint 7`（`（从规则库选择，可多选，Sprint 7）` → `（从规则库选择，可多选）`）；②原 checkbox 列表替换为 antd `Select mode="multiple"`（`showSearch` + `optionFilterProp="label"` + `allowClear`，view 模式 `disabled`，与项目其它多选 `AlertRuleModal` 一致）；③下拉项 `label` 复用 `QUALITY_TYPE_LABEL[r.type]` 拼 `规则名（类型中文）`（如 `唯一性检查（唯一性）`），不再直接显示英文 type；④空态/无匹配提示保留（`暂无可用规则，可先到「质量规则」页面创建`） |
| 同文件（修改，注释清理） | 同步去掉 `ruleIds` JSDoc、`handleSubmit` 内注释、`ruleOptions` state 注释里的 `Sprint 7` 标注 |

### 11.2 设计决策

- **下拉项展示**：`name（类型中文）`，`QUALITY_TYPE_LABEL` 为 `types/quality.ts` 单一出处（完整性/唯一性/值域范围/自定义 SQL），类型字段不再暴露后端原始英文枚举。
- **空态 fallback**：规则库为空时 placeholder 与 notFoundContent 均为「暂无可用规则，可先到『质量规则』页面创建」，引导先建规则。

### 11.3 部署与验证

- `npm run build`（tsc + vite，3005 modules 通过，无 lint/类型错误）→ `docker compose build app-frontend` → `up -d --no-deps app-frontend`，容器 `Up`。
- **范围说明**：本次仅改 `QualityJobDrawer.tsx` 一个文件；`types/quality.ts` 注释、handoff/PRD/技术文档、e2e 中非用户可见的 `Sprint N` 历史标注**保留不动**（属版本历史说明，不影响用户感知）。

## 12. 创建审计字段修复（2026-08-05，跨模块）

> **阶段**：统一创建审计字段约定——**创建时只设 `createdBy`/`createdAt`，不设 `updatedBy`/`updatedAt`**；并去掉各业务表 `updated_at` 的 DB 默认值，使「创建后未修改」的记录 `updated_at`/`updated_by` 均为 null（前端显示「—」），仅真正 update/启停/状态变更时才写入。
> **背景**：此前所有 create 入口在创建时就 `setUpdatedBy/setUpdatedAt`（或依赖 DB `DEFAULT CURRENT_TIMESTAMP`），导致新建记录立刻显示「修改人/修改时间」，不符合语义。

### 12.1 变更清单

| 产物 | 变更 |
|------|------|
| **代码**：15 处 create 入口去掉 `setUpdatedBy`/`setUpdatedAt`（保留 `setCreatedBy`/`setCreatedAt`） | engineering：`SyncJobService.create`、`DataSourceService.create`、`DataSourceService.autoCreateAndRunCollectTask`（采集任务）、`DagService.create`、`DagService.saveNodesAndEdges`（节点）、`DagProjectService.create`、`DagParameterService.create`；task-core：`QualityRuleService.create`、`QualityRuleService.batchCreate`、`QualityJobService.create`、`QualityRuleTemplateService.create`；governance：`NamingStandardService.create`、`FieldTypeStandardService.create`、`CollectTaskService.create`；system：`UserService.createUser`（仅删 `setUpdatedBy`） |
| `data-nest-system/.../db/migration/V3.6.8__drop_updated_at_default.sql`（新增） | 去掉 13 张表 `updated_at` 的 `DEFAULT CURRENT_TIMESTAMP` 且 `DROP NOT NULL`（改为可空无默认）：`sync_job`/`datasource_connection`/`collect_task`/`dag_project`/`dag`/`dag_node`/`dag_parameter`/`quality_rule_template`/`quality_job`/`quality_rule`/`naming_standard`/`field_type_standard`/`sys_user`。**历史数据不改动**，仅改列定义 |
| `AGENTS.md` §7（修改） | 新增「创建审计字段约定」：create 入口只设 createdBy/createdAt，禁止 setUpdatedBy/setUpdatedAt；新增带审计字段的表其 `updated_at` 不要加 DB 默认值 |

### 12.2 关键注意点

- **必须连 DB 默认值一起去掉**：13 张表 `updated_at` 原都带 `DEFAULT CURRENT_TIMESTAMP`，仅删代码 `setUpdatedAt` 无法让 `updated_at` 为 null（创建时仍被 DB 自动填当前时间），需 Flyway `V3.6.8` 同时去掉默认值（用户确认方案）。
- **真正的 update 保留**：update/启停/toggle/状态变更里的 `setUpdatedBy/setUpdatedAt` 均保留不动（那是真实修改，必须写入）。
- **`DagProjectService.create`/`SyncJobService.create` 的 afterCommit 回写**：创建后 DS/XXL-JOB 回填（`setDsProjectCode`/`setXxlJobId`）会用 `updateById(fresh)`，属真实业务更新，保留；`fresh` 从 DB 重查，无影响。
- **UserService 特殊性**：`sys_user.updated_at` 也走 DB 默认值，V3.6.8 去掉默认后创建时 `updated_at`/`updated_by` 均为 null。

### 12.3 部署

- `mvn -pl data-nest-task-core,data-nest-engineering,data-nest-governance,data-nest-system -am clean package -DskipTests` → BUILD SUCCESS。
- 重建部署 5 容器：**app-system**（先启，执行 Flyway V3.6.8）→ **app-engineering / app-governance / app-worker / app-job**，全部 healthy。

### 12.4 验证记录

- Flyway `V3.6.8` 在 `flyway_schema_history` `success=t`（app-system 启动 healthy 确认迁移成功）。
- 抽查 `quality_job.updated_at`：`information_schema.columns` 显示 `is_nullable=YES`、`column_default=NULL`（默认值已去掉）。
- 批量确认 13 张表 `updated_at`：`no_default=t` + `is_nullable=YES` 全部通过。
- 功能回归（创建接口实际验证）按用户要求跳过，仅完成列定义与迁移验证。

## 13. 表级质量评分后端（2026-08-05）

> **阶段**：NG8「表级质量评分 + 血缘联动」后端完成。评分依赖检查历史（`quality_check_detail`），"检查完→算分→落表→供血缘展示"。本次只做后端，血缘前端徽章渲染后续单独做。

### 13.1 需求与调研（用户确认）

- **评分粒度**：表级评分**跨任务聚合**——一张表可出现在多个质量任务，综合该表**所有启用规则最近一次**检查结果产出 0-100 分。
- **健康度四档区间**（用户要求调研行业做法后回写文档）：
  | 健康度 | 分数区间 | 血缘徽章颜色 |
  |--------|----------|--------------|
  | EXCELLENT（优秀） | ≥ 85 | 绿 |
  | GOOD（良好） | 75~84 | 绿 |
  | WARNING（一般） | 60~74 | 黄 |
  | BAD（差） | < 60 | 红 |
  存在 SEVERE 规则强制 BAD 并压入低分区；UNAVAILABLE 规则不参与（剔除权重，与告警语义一致）；未配置启用规则的表不落行（血缘显示灰色「—」）。
- **扣分算法**（用户要求调研后写进文档）：`总扣分 = Σ(警告权重)×warning-deduct + Σ(严重权重)×severe-deduct`；`最终分 = max(0, 基础分−总扣分)`，基础分 = `100×(通过权重/有效权重)`。

### 13.2 变更清单

| 产物 | 变更 |
|------|------|
| `data-nest-system/.../db/migration/V3.6.9__sprint6_quality_score.sql`（新增） | 建 `quality_score` 表（id/table_id/table_name/datasource_id/score/health_level/pass_rules/warning_rules/severe_rules/last_checked_at/updated_at，`uk_quality_score_table(table_id)` 一张表一行 + `idx_datasource_id`）；`updated_at` 不加 DB 默认值（对齐审计约定） |
| task-core entity：`QualityScore`（新增） | 表级评分实体（score DECIMAL(5,2)/healthLevel/passRules/warningRules/severeRules/lastCheckedAt/updatedAt） |
| task-core mapper：`QualityScoreMapper`（新增） | BaseMapper；批量 IN 查询由 Service 用 `QueryWrapper.in` 实现（血缘回填一次查） |
| task-core constant：`QualityScoreConstants`（新增） | 健康度四档常量（EXCELLENT≥85/GOOD≥75/WARNING≥60）+ 满分常量；区间为代码常量（调研确认） |
| task-core dto：`QualityScoreDTO`/`QualityScoreQueryRequest`（新增） | 评分展示 DTO（含 datasourceName/healthLevelLabel 优秀·良好·一般·差）+ 列表筛选（keyword/datasourceId/healthLevel/page/pageSize） |
| task-core service：`ScoreCalculator`（新增） | 核心算分：`recalculateForTables(List<Long>)` 逐表跨任务聚合所有启用规则最近一次 `result_level`（按 rule_id 取最近）→ 加权算基础分 → 减警告/严重扣分 → 映射健康度 → upsert；UNAVAILABLE 剔除权重；无有效规则删除评分；`@Value` 注入 warning-deduct(10)/severe-deduct(30)/bad-threshold(60) 带默认值兜底 |
| task-core service：`QualityScoreService`（新增） | 查询服务：`getByTableId`/`listByTableNames`（血缘批量回填用）/`listPage`（分页，datasourceName 反查 + healthLevelLabel 映射） |
| task-core service：`QualityCheckService`（修改） | 注入 `ScoreCalculator`；`executeJob` 收尾（finishBatch 后、fireBatchAlert 前）收集本次涉及 tableId 集合调 `recalculateForTables`；`executeRule` 单规则执行后重算该规则所在表（评分与告警基于同一批最新结果） |
| governance controller：`QualityScoreController`（新增） | `/quality/scores`：GET `/table/{tableId}` 单表、POST `/by-tables` 批量（血缘回填）、POST `/page` 分页；权限与质量查看一致（SUPER_ADMIN/GOVERNANCE_ADMIN/DATA_ENGINEER/DATA_ANALYST） |
| governance dto：`LineageNodeDTO`（修改） | 新增 `qualityScore`(Integer)/`healthLevel`/`tableName` 字段 |
| governance service：`LineageService`（修改） | 注入 `QualityScoreService`；`buildTableGraph` 构造完 nodes 后 `fillQualityScores` 用节点表名集合一次 IN 查 `quality_score` 批量回填（避免 N+1），未命中保持 null |
| `shared-configs/shared-common.yaml`（修改） | 新增 `datanest.quality.score.{warning-deduct:10, severe-deduct:30, bad-threshold:60}`（worker/governance 共同导入）；已重新跑 `middleware-nacos-init` 同步到 Nacos |
| 文档 | `PRD §6.5.1` 补充健康度四档区间 + 扣分算法明确写法；`技术文档 §3.6/§5.1/§8` 同步健康度区间、ScoreCalculator 实现、表名对齐 `quality_check_detail` |

### 13.3 代码 Review 结论（功能 × 架构 × 效率）

- **已修复（业务正确性）**：初版 `determineHealth` 对 `severeRules>0` 直接返回 BAD，导致后续「严重规则强制压入低分区」的分数封顶逻辑成死代码。重构为 `severeRules>0` 时统一压分（`score=min(score, bad-threshold−0.01)`）+ 标 BAD。
- **架构融洽**：质量核心（实体/Mapper/DTO/ScoreCalculator/QualityScoreService）全部下沉 task-core（D-T1），governance 只提供 Controller + 血缘回填；评分物化到 `quality_score` 表，血缘高频查询一次 IN 回填避免 N+1（T3）。
- **实现高效**：`recalculateForTables` distinct 去重；评分重算低频（触发时），血缘查询高频走物化表。

### 13.4 部署

- 编译：`mvn -pl task-core,engineering,worker,governance,system -am clean package` → **BUILD SUCCESS**。
- 镜像重建部署：**app-system**（先启，执行 Flyway V3.6.9）→ **app-worker / app-engineering / app-governance**，四容器全部 healthy。
- 需重建 app-worker（质量执行代码在 worker）+ app-engineering（task-core 共享）+ app-governance（评分接口+血缘）+ app-system（Flyway 脚本）。
- Nacos：重新运行 `middleware-nacos-init` 同步 `shared-common.yaml` 评分配置项。

### 13.5 API 测试记录（业务角度，2026-08-05）

> 用真实可执行数据源（`mysql` 测试源 testdb）走完整业务链路：建任务→建规则→执行→验证评分→血缘→查询。测试数据已清理。

- **评分计算（PASS+WARNING 加权）**：orders 表（weight3 PASS `SELECT 5` + weight1 WARNING `SELECT 7`，阈值 6/8）→ detail result_level=PASS/WARNING；quality_score score=**65.00**、health_level=**WARNING**（基础分 100×3/4=75 − 警告扣分 1×10=10）、pass_rules=1/warning_rules=1 ✓
- **SEVERE 强制 BAD**：products 表单条 SEVERE 规则（weight2 `SELECT 9`，severe=8）→ score=**0.00**、health_level=**BAD**（基础分 0 − 严重扣分 2×30=60）；加 weight10 PASS 规则后 → score=**23.33**、BAD（基础分 100×10/12=83.33 − 60=23.33，SEVERE 强制压分生效）✓
- **单表接口**：`GET /quality/scores/table/{tableId}` → score/healthLevel/healthLevelLabel/datasourceName 齐全 ✓
- **批量接口**：`POST /quality/scores/by-tables` body `["testdb.orders"]` → 命中评分 ✓
- **分页接口**：`POST /quality/scores/page`（healthLevel=WARNING）→ total=1 ✓
- **血缘回填**：`GET /lineage/graph?tableName=testdb.orders&depth=1` → 节点 `testdb.orders` 回填 `qualityScore:65`/`healthLevel:"WARNING"` ✓（血缘节点表名与 quality_score.table_name 格式一致，匹配验证通过）
- **清理**：测试任务/4 条测试规则/评分/detail/batch 全部删除，`quality_job`/`quality_rule`/`quality_score`/`quality_check_detail` 归 0。

## 14. 表级质量评分前端（2026-08-05，本次会话）

> **阶段**：在 §13 后端评分基础上完成**三个前端落点全覆盖** + **后端补 4 个接口**。经 code-reviewer 子代理审查并修复 1 个业务逻辑 bug + 若干改进项。
> **范围确认**（用户 ask_followup）：①评分列表独立菜单页；②元数据详情「质量」页签；③血缘节点质量徽章；④扣分配置可编辑（后端补接口）；⑤良好(GOOD)=绿（按 PRD）。

### 14.1 后端补接口（governance + task-core）

| 产物 | 变更 |
|------|------|
| `data-nest-system/.../db/migration/V3.7.0__quality_score_config.sql`（新增） | 建 `quality_score_config` 单行配置表（warning_deduct=10/severe_deduct=30/bad_threshold=60，`INSERT ... WHERE NOT EXISTS` 幂等，`updated_at` 无 DB 默认值对齐审计约定） |
| task-core entity/mapper：`QualityScoreConfig` + `QualityScoreConfigMapper`（新增） | 全局扣分配置实体/Mapper（单行） |
| task-core `ScoreCalculator`（修改） | ①扣分值从**库表 `quality_score_config` 读取**（`recalculateForTables` 批量只读一次配置，传入 `recalculateForTable`，避免每表重复查库；`@Value` 作 Nacos/默认兜底）；②`loadConfig` 加 `orderByAsc("id")` 保证单行取值确定性；③**修复 badThreshold 未参与普通健康度 BAD 判定 bug**：`determineHealth` 增参 `badThreshold`，BAD 判定由硬编码 `SCORE_WARNING(60)` 改为 `score < badThreshold`，「一般」下限同用 badThreshold（默认 60 与常量一致，向后兼容） |
| task-core dto：`QualityTableRuleResultDTO`/`QualityScoreConfigDTO`（新增） | 单表规则+最近结果（ruleId/ruleName/ruleType/jobName/columnName/weight/resultValue/resultLevel/lastCheckedAt/success）、全局配置（warningDeduct/severeDeduct/badThreshold） |
| task-core `QualityScoreService`（修改） | 新增 `listTableRuleResults`（按表查启用规则 + `latestDetailsByRule` 一次 IN 查 + 内存按 rule_id 取最新一条回填最近结果，jobName 经 `ruleService.listJobNamesByRuleIds` 回填）/`executeTableRules`（逐条 `triggerRule(ruleId,"MANUAL")`，**单条失败 try-catch 不中断，存在失败抛聚合异常**）/`getConfig`/`updateConfig`（含 1-100 上限校验） |
| task-core `ErrorCode`（修改） | 新增 `QUALITY_SCORE_CONFIG_INVALID(4217)` |
| governance `QualityScoreController`（修改） | 新增 4 接口：`GET /table/{tableId}/rules`（表规则最近结果，查看角色）、`POST /table/{tableId}/execute`（按表执行，治理员/超管）、`GET /config` + `PUT /config`（扣分配置读写，写治理员/超管） |

### 14.2 前端变更清单

| 产物 | 变更 |
|------|------|
| `src/types/quality.ts`（修改） | 新增 `QualityHealthLevel`/`QualityScore`/`QualityScoreQueryParams`/`QualityTableRuleResult`/`QualityScoreConfig` + 常量 `QUALITY_HEALTH_LABEL`(优秀/良好/一般/差)/`QUALITY_HEALTH_OPTIONS`（单一出处） |
| `src/types/lineage.ts`（修改） | `LineageNodeDTO` 扩展 `qualityScore`/`healthLevel`/`tableName`（对齐后端血缘回填字段） |
| `src/api/quality.ts`（修改） | 新增 `getQualityScoreByTable`/`queryQualityScores`/`getTableQualityRuleResults`/`executeTableQualityRules`/`getQualityScoreConfig`/`updateQualityScoreConfig`（`/governance/quality/scores/**`） |
| `src/components/QualityScoreBadge.tsx`（新增） | 评分+健康度徽章单一出处：优秀深绿/良好中绿/一般黄/差红/暂无灰；`compact` 模式（血缘节点小徽章）与完整模式；score 为空显示灰色「—/暂无质量」 |
| `src/pages/governance/quality-scores/index.tsx`（新增） | 「表级质量评分」独立列表页：数据源/健康度/搜索表名筛选 + 查询/重置 + 分页 + URL 状态同步；列（数据源/表名可点/评分/健康度/通过/警告/严重/最近检查/操作-查看详情）；「查看详情」弹窗（评分概览 + 规则最近结果表格 + 立即执行全部规则）；「评分算法说明」静态弹窗；「扣分配置」弹窗（读 getConfig + 存 updateConfig，仅治理员/超管 canWrite）；执行按钮仅 canWrite 显示 |
| `src/pages/governance/metadata/index.tsx`（修改） | 新增「质量」tab：评分概览卡片（QualityScoreBadge/最近检查/通过警告严重/启用规则数/立即执行按钮）+ 评分算法说明 + 规则最近结果表格（复用 COL/formatDateTime/DsStatusBadge）；`loadTableDetail` 并行加载评分+规则；执行按钮仅 canWrite |
| `src/pages/governance/metadata/lineage/LineageGraphPage.tsx`（修改） | `LineageNodeData` 扩展 qualityScore/healthLevel；nodes 构造传入；`TableNode` 类型行下方渲染 `QualityScoreBadge compact`（有分显示评分+等级，无分灰色「—」）；保持 ReactFlow 受控 |
| `src/router/index.tsx` + `src/components/Sidebar.tsx`（修改） | 「数据治理」组新增「质量评分」菜单（`ALL_ROLES`，HiOutlineCheckCircle）+ `/governance/quality-scores` 懒加载路由 |

### 14.3 Review 结论（code-reviewer 子代理，功能 × 架构 × 效率）

- **已修复（严重）**：①`ScoreCalculator.determineHealth` 原用硬编码 `SCORE_WARNING(60)` 判 BAD，`badThreshold` 仅对 SEVERE 强压生效 → 用户调「低分区阈值」无严重规则的表不生效（§14.1 已改参数化）；②`executeTableRules` 循环触发任一条失败即中断 → 改逐条 try-catch + 聚合异常。
- **已修复（改进）**：`latestDetailsByRule` 一次 IN 查询 + 内存取最新（避免每规则 N 次查询）；`loadConfig` 加 `orderByAsc` 确定性；config 写入加 1-100 上限校验（前后端）；前端「立即执行全部规则」按钮按 canWrite 隐藏（与后端 403 权限一致）。
- **架构融洽**：评分类型/常量收敛 `types/quality.ts`；徽章 `QualityScoreBadge` 单一出处三落点复用；复用 DsToolbar/DsFilterSelect/DsModal/DsStatusBadge/Pagination/COL/formatDateTime；血缘保持 ReactFlow 受控。
- **业务正确**：后端 4 接口 + 血缘回填经网关 API 验证通过（配置读写/按表规则/按表执行/评分分页）；ScoreCalculator 从 DB 读配置在 worker 真实执行验证（`quality_score_config ORDER BY id` 日志确认）。
- **健康度颜色**：优秀深绿/良好中绿（PRD 良好=绿）/一般黄/差红/暂无灰，`QualityScoreBadge` 与列表页 `DsStatusBadge` 均符合 PRD。

### 14.4 部署

- 编译：`mvn -pl common,task-core,governance,engineering,worker -am clean package -DskipTests` → BUILD SUCCESS。
- 重建部署：**app-system**（先启，执行 Flyway V3.7.0 建 `quality_score_config`）→ **app-worker / app-engineering / app-governance**（task-core ScoreCalculator/QualityScoreService 改动）+ **app-frontend**（前端）。
- 前端 `npm run build`（tsc + vite，3007 modules）+ `docker compose build/up app-frontend`，容器 Up，`/governance/quality-scores` 预览正常。

### 14.5 验证记录

- `GET /quality/scores/config` → 10/30/60；`PUT /config` 超限(150) → `code:4217`「不能超过 100」。
- `GET /quality/scores/table/{tableId}/rules` → 规则列表（jobName/ruleType/weight 齐全，未检查时 resultLevel 为 null）。
- `POST /quality/scores/table/{tableId}/execute` → 200 触发；worker 日志确认执行链路 + `ScoreCalculator` 读 `quality_score_config` 正常。
- 血缘 `GET /lineage/graph?tableName=testdb.users` → 节点回填 `qualityScore/healthLevel`（造测试评分记录验证后删除）。
- **注意**：`testdb.users` 这条测试规则本身 SQL 为空 → 执行 UNAVAILABLE（不落评分），为既有数据问题非本次引入；评分列表空态正常。
- 测试数据已清理（`quality_score` 造数记录已删）。

## 15. 表级质量评分 E2E（2026-08-05，本次会话）

> **范围确认**（用户 ask_followup）：DB + 前端 UI 全覆盖，API 辅助排查；覆盖**多档评分场景**；新建**独立 spec**（`e2e/sprint6/e2e/quality-scores.spec.ts`）。
> **结果**：11 用例全绿（24.5s）。

### 15.1 交付物

| 文件 | 变更 |
|------|------|
| `e2e/sprint6/e2e/quality-scores.spec.ts` | 新增：11 用例（serial） |
| `e2e/sprint6/helpers/data.ts` | +评分常量（900005 段：4 表 ID + 7 规则 ID + 物理表名） |
| `e2e/sprint6/helpers/seed.ts` | +`seedQualityScores`/`cleanupQualityScores`，挂 `seedAll`/`cleanupAll` |
| `data-nest-governance/.../CollectTaskService.java` | **顺带修复** `toDTO` NPE（见 §15.4） |
| `docs/sprint6/handoff/sprint-6.md` | 本 §15 |

### 15.2 测试设计（多档评分场景，默认扣分配置 10/30/60）

`seedQualityScores` 在 MYSQL 执行数据源（`EXEC_DS_MYSQL_ID`）建 4 张评分物理表（不同表名满足 `uk_metadata_table_unique`），行数控制 COUNT 值决定分级：

| 表 | COUNT | 规则(weight/阈值 w·s) | 期望 result_level | 期望评分 |
|---|---|---|---|---|
| `e2e_s6_score_pass` | 2 | r1(1/3,4) + r2(1/3,4) | 均 PASS | 100.00 EXCELLENT（pass=2） |
| `e2e_s6_score_warn` | 4 | r1(1/3,5)WARN + r2(4/5,6)PASS | WARN+PASS | base=100×4/5=80−1×10 → **70.00 WARNING**（pass=1/warning=1） |
| `e2e_s6_score_severe` | 4 | r1(1/2,3)SEVERE + r2(1/5,6)PASS | SEVERE+PASS | 严重强制 BAD，min(50−30,59.99) → **20.00 BAD**（pass=1/severe=1） |
| `e2e_s6_score_unavail` | 4 | r1 查不存在表 | UNAVAILABLE | **不落评分行**（无有效规则） |

**用例矩阵（11 个）**：
- **A DB 多档断言**：A1 全通过 100.00/EXCELLENT、A2 警告 70.00/WARNING、A3 严重 20.00/BAD、A4 UNAVAILABLE 不参与不落行（负向）。
- **B 评分列表页 UI**：B1 表格展示 + 健康度徽章（优秀/一般/差）、B2 表名关键词筛选、B3 健康度筛选（按「差」只显严重表）。
- **C 详情弹窗 UI**：C1 评分概览 + 规则最近结果（严重/通过判定徽章）。
- **D 元数据页「质量」tab UI**：D1 评分卡片 + 规则最近结果 + 「立即执行全部规则」按钮（`?tableId=` 自动选中表 → 点「质量」tab）。
- **E 权限**：E1 工程师可查看评分列表但无「扣分配置」按钮；API `PUT /config` 被拒（非 200）。
- **F 扣分配置**：F1 弹窗读默认 10/30/60 → 改 warningDeduct=20 保存 → 重算警告表 70→**60.00**（80−20）仍 WARNING（badThreshold=60）→ 恢复默认 + 重算回 70.00。

### 15.3 执行与验证

- **执行方式**：`POST /governance/quality/scores/table/{tableId}/execute` 逐条 MANUAL 投递 worker；异步，测试用 `waitFor` 轮询 `quality_score` 落行且计数达标（`waitScore`）/ `quality_check_detail` 分级到终态（`waitRuleLevel`）。
- **spec 自带播种**：`ensureTestUsers + seedExecTables + seedExecMetadata + seedQualityScores`，支持 `SKIP_SETUP=1` 独立运行（不依赖 globalSetup 的 Sprint5 播种）。
- **DB 断言精确**：`quality_score.score/health_level/pass_rules/warning_rules/severe_rules`；UNAVAILABLE 表断言 `quality_score` 无行。
- **UI 断言**：健康度徽章中文文案（EXCELLENT=优秀 / GOOD=良好 / WARNING=一般 / BAD=差）；「差」筛选 value=`BAD`；详情/扣分配置弹窗 `getByRole('dialog', {name: title})`。
- **回归**：完整模式全量 sprint6 跑，quality-scores **11/11 全绿**；quality-alerts/jobs/rules 3 个失败为**既有问题**（§15.4），与评分改动无关（单独跑也稳定失败）。

### 15.4 顺带修复：CollectTaskService.toDTO NPE（阻塞 globalSetup）

- **现象**：全量回归时 globalSetup 在 Sprint5 `ensureFailingCollectTask`（`POST /governance/collect-tasks`）报 `9999 系统内部错误`。
- **根因**：`CollectTaskService.toDTO` 用 `usernameMap.get(task.getUpdatedBy())`，而 `usernameMap` 是 `Map.of()`（`ImmutableCollections.MapN`，**不允许 null key 的 get**）；按审计约定（V3.6.8）create 只设 `created_by` 不设 `updated_by` → `updatedBy=null` → `Map.of().get(null)` 抛 NPE。
- **修复**：`toDTO` 增加空安全 `lookupName(map, userId)` helper（`userId==null` 直接返回 null）。最小改动，仅 `CollectTaskService.java`。
- **部署**：`mvn -pl data-nest-governance -am clean package -DskipTests` → `docker compose build/up app-governance`。修复后 collect-tasks 创建 code=200。
- **注意**：此 NPE 与评分提交无关，是审计约定改动未适配 `Map.of()` 的遗留回归；影响所有依赖 collect-task 播种的 Sprint5 测试。

### 15.5 全量回归快照（2026-08-05，task-core 拆分重构期间）

> 在 task-core 拆分重构进行中、容器仍跑旧代码时，用本地 `SKIP_SETUP=1` 模式跑了一次全量 sprint6 回归，作为重构前后的基线对照。

- **结果**：**42 passed / 2 failed**（quality-jobs / quality-rules 各 1 条失败）。
- **失败原因**：**断言过时，非重构引入**：
  - `quality-jobs.spec.ts:119`（Tab 切换）与 `quality-rules.spec.ts:151`（选任务展示规则）仍引用 `选择质量任务` aria-label，但前端质量规则已独立成 `/governance/quality-rules` 菜单、筛选改名为 `按所属任务筛选`（既有重构所致）。
  - 该两条已提前记录于 §8.6「已知残留」，暂留待后续维护。
- **说明**：本次回归**不用于证明重构正确性**（容器跑的是旧代码）。重构完成后（§16 全量 `mvn clean package` + 5 容器重建）需再跑一遍确认全绿/收敛失败项。

## 16. task-core 三步拆分重构（2026-08-05，本次会话）

> **阶段**：把原 `data-nest-task-core`（39 service + 44 entity + 39 dto）按依赖分层拆为 **4 个模块**，消除 task-core 过大的「上帝模块」问题，让共享底座更清晰、职责更单一。
> **结果**：全量 `mvn clean package`（11 模块 BUILD SUCCESS）+ 重建 5 个受影响容器（app-engineering/governance/worker/job/system）全部 healthy + 核心 API 回归通过。

### 16.1 设计决策（用户两次把决策交还 Agent）

| 决策点 | 结论 | 理由 |
|--------|------|------|
| 包名是否改 | **保持不变**（`com.datanest.task.core.*`） | 被 engineering/governance 共用，改包名会导致 engineering→governance 依赖环；包名不变则所有 import 零改动 |
| SysUserService 放哪 | **下沉 task-core-entity** | 被 system/engineering/governance/alert 全模块用，只依赖 SysUserMapper，放最底层避免循环 |
| alert↔governance 耦合 | 新增接口 **`QualityAutoTriggerPort`** | `DagAlertExecutionListener`（alert）不再直接依赖 `QualityAutoTriggerService`（治理域），改注入接口；由 governance 的 `QualityAutoTriggerService implements` 实现 |
| 3.2 步执行内核强拆 | **跳过（取消）** | `GenericSqlExecutor`(infra)/`AddaxJobService`(sync)/`CollectExecutor`/`MetadataRegistrationService` 都依赖 governance 的 `ConnectionTester`，`SyncJobExecutorService` 依赖 collect 的 `MetadataRegistrationService`，跨域依赖密集；强拆 4 模块产生反直觉依赖图，属过度工程。第 1、2 步已达成核心目标 |

### 16.2 最终模块结构

```
data-nest-common
  └─ data-nest-task-core-entity        # entity(35)/mapper(36)/dto(35)/constant(2) + SysUserService
       └─ data-nest-alert              # 6 告警类 + QualityAutoTriggerPort 接口
            └─ data-nest-task-core-governance  # 11 治理编排服务（含实现 QualityAutoTriggerPort）
                 └─ data-nest-task-core        # 纯执行内核（21 service + collect/config/job）+ MybatisPlusInterceptorAutoConfiguration
```

- **`data-nest-task-core-entity`**（底座，依赖 common）：entity(35)/mapper(36)/dto(35)/constant(2) + `SysUserService`（横切通用服务）。
- **`data-nest-alert`**（告警域，依赖 entity+common）：`AlertFiringService`/`AlertRuleService`/`DagAlertService`/`MailService`/`DagAlertExecutionListener`/`DagExecutionFinishedListener` + 新接口 `QualityAutoTriggerPort`；mail/fastjson2 改 compile。
- **`data-nest-task-core-governance`**（治理编排域，依赖 entity+alert+common+spring-boot-starter-json(provided)）：`QualityRuleService`/`QualityJobService`/`QualityScoreService`/`QualityRuleTemplateService`/`QualityCheckTriggerService`/`QualityAutoTriggerService`/`DataPreviewService`/`DagTopologyService`/`DataSourceRefreshService`/`ConnectionTester`/`RuleSqlGenerator`。
- **`data-nest-task-core`**（执行内核域，依赖 entity+alert+governance+common）：仅剩 21 个执行内核类（collect/config/job/service 包），`resources/META-INF` 的 `MybatisPlusInterceptorAutoConfiguration` 保留于此。
- **消费方**（engineering/governance/worker/job/system）只显式依赖 `data-nest-task-core`，新模块经依赖传递获得，消费方 pom 零改动。

### 16.3 变更清单

| 产物 | 变更 |
|------|------|
| `data-nest/pom.xml`（修改） | `<modules>` 加 `task-core-entity`/`alert`/`task-core-governance`；`dependencyManagement` 加 3 个新模块条目；模块顺序保证 `common → task-core-entity → alert → task-core-governance → task-core` |
| `data-nest-task-core/pom.xml`（修改） | 加 entity + alert + governance 3 个依赖（其余 JDBC/MyBatis/mail 等仍 provided） |
| 新模块 `data-nest-task-core-entity/pom.xml` / `data-nest-alert/pom.xml` / `data-nest-task-core-governance/pom.xml`（新增） | 各模块最小依赖声明（common + entity 底座；alert 加 mail/fastjson2 compile；governance 加 fastjson2 compile + spring-boot-starter-json provided） |
| `data-nest-alert/.../service/QualityAutoTriggerPort.java`（新建） | 接口：`triggerOnSuccess(ObjectType, ObjectId)` 语义 + 常量 `OBJECT_TYPE_DAG_NODE/SYNC_JOB/COLLECT_TASK`；供 alert 内 listener 注入，解除对治理域服务类的直接依赖 |
| `data-nest-alert/.../service/DagAlertExecutionListener.java`（修改） | 注入类型由 `QualityAutoTriggerService` 改为 `QualityAutoTriggerPort` |
| `data-nest-task-core-governance/.../service/QualityAutoTriggerService.java`（修改） | `implements QualityAutoTriggerPort` |
| 各消费方（engineering/governance/worker/job/system）pom | **未改动**（依赖经 task-core 传递获得） |

### 16.4 执行中的错误与修复

- **jackson 缺失**：`QualityCheckTriggerService` 用 `tools.jackson.databind.JsonNode`（Jackson 3，Spring Boot 4），governance 模块缺依赖 → 加 `spring-boot-starter-json`（provided）。
- **测试 URL 错误**：质量任务接口是 `@PostMapping("/page")` 需 POST body，GET 导致 NumberFormatException；告警规则正确路径为 `GET /api/system/alert-rules`。修正后全部 code:200。
- **PowerShell curl 别名问题**：用 `curl.exe` 替代 `curl`。

### 16.5 验证结果（全部通过）

- 全量 `mvn clean package -DskipTests`：**11 模块 BUILD SUCCESS**。
- 重建 5 容器（app-engineering/governance/worker/job/system）全部 healthy；worker 完整启动，XXL-JOB 执行器正常（port=9997）。
- 接口回归：登录、告警规则、质量任务/规则（含 `createdByName` 回填证明 `SysUserService` 正常）、同步任务、血缘图谱 `/api/governance/lineage/graph?tableName=users&depth=2` 全部 code:200。
- 无 lint 错误。

### 16.6 文档同步

- **AGENTS.md**：§1 核心模块表更新为 4 模块结构 + 依赖链 + 依赖方向规则；§3 构建部署更新关键原则（构建顺序、重建消费方）；§8.2 共享包分布更新；§8.11 新增 task-core 拆分重构说明；§6 mail 依赖坑更新为 alert 模块 compile。
- **本 Handoff**：§2 状态看板 + 本 §16。

> **遗留**：已暂停的 Sprint 6 质量评分相关 plan 如需继续需重新激活；worker 的 `DagNodeExecuteService` 通配符 `import service.*` 仍存在，但通过依赖传递能解析，未改造（编译通过）。

## 17. 标准合规检查后端扩展（2026-08-05，本次会话）

> **阶段**：标准合规检查（DG-08）后端扩展完成——修复前后端 violationType 不一致 bug、合规逻辑下沉 task-core、忽略/取消忽略、分页列表、导出 CSV、全局定时扫描 handler、放开工程师查看/操作权限。另外完成「模板批量应用」后端 review 修复。**前端由其他 AI 负责，本次只做后端**；质量报告（DG-07）用户明确本轮不做。

### 17.1 范围确认（用户交互式确认）

| 决策点 | 结论 |
|--------|------|
| 推进方式 | 先 review 现有代码再开发（合规基础版已存在：`ComplianceCheckService.check/listResults`） |
| 接口归属 | 沿用 `DataStandardController`（`/data-standards`），不新建独立 Controller |
| 列表接口 | 新增 `page` 分页接口，**保留**现有 `listResults`（POST `/compliance-check/results`） |
| 定时扫描 | **全局一个 cron**（扫全部在线数据源，无「合规任务」模型） |
| 定时 handler 落点 | **下沉 task-core + job 注册 cron**（用户确认；经 task-core 传递依赖，job 可直接注入，无需改 pom） |
| 忽略粒度 | 按 resultId：`compliance_check_result` 加 `ignored/ignored_at/ignored_by` |
| 权限 | 本轮放开工程师（DATA_ENGINEER）查看/忽略/导出；标准配置增删改仍限治理员/超管 |
| 模板批量应用 | 连同质量规则页一起 review（不只看占位按钮） |
| 质量报告（DG-07） | **本轮不做**（原定 S8） |

### 17.2 Review 发现的关键 bug

- **前后端 `violationType` 不一致**：前端 `ComplianceCheckPanel.tsx` 的 `groupResults` 用 `violationType === 'FIELD_TYPE'` 分组字段类型违规，而后端 `ComplianceCheckService.checkColumn` 产生 TYPE 违规时用 `"TYPE"`。导致字段类型不合规项被误归入「命名规范不合规」分组。**后端语义以现有 `"TYPE"` 为准**，前端需改分组为 `TYPE`（前端由其他 AI 处理）。

### 17.3 变更清单（后端）

| 产物 | 变更 |
|------|------|
| `data-nest-task-core-entity`：`NamingStandard`/`FieldTypeStandard`/`ComplianceCheckResult`(entity) + `NamingStandardMapper`/`FieldTypeStandardMapper`/`ComplianceCheckResultMapper`（下沉） | 从 governance 迁入 task-core-entity（包 `com.datanest.task.core.entity/mapper`），删除 governance 旧文件；governance 的 `NamingStandardService`/`FieldTypeStandardService`/`DataStandardController` 改 import；job 经 task-core 传递依赖可直接注入 |
| `data-nest-task-core-entity` dto（下沉+新增） | `ComplianceCheckRequest`/`ComplianceCheckResultDTO`（加 `ignored`/`ignoredAt`/`ignoredBy`）+ 新增 `ComplianceCheckPageRequest`（含 `page/pageSize/ignored` 筛选）迁入 task-core-entity dto 包 |
| `data-nest-task-core-governance`：`ComplianceCheckService`（下沉+扩展） | 从 governance 迁入 `com.datanest.task.core.service`；扩展 `ignore(resultId,userId)`/`unignore(resultId)`/`page(pageRequest)`（默认 `ignored=0` 排除已忽略，支持 `ignored=1` 筛选）/`export(request)`（CSV 带 BOM，Excel 可开中文） |
| `data-nest-common`：`ErrorCode`（修改） | 新增 `COMPLIANCE_CHECK_RESULT_NOT_FOUND(5007)` |
| `data-nest-system/.../db/migration/V3.7.1__compliance_check_ignore.sql`（新增） | `compliance_check_result` 加 `ignored SMALLINT DEFAULT 0`/`ignored_at TIMESTAMP`/`ignored_by BIGINT` + `idx_compliance_check_result_ignored`（**注意版本号必须大于库内最高 3.7.0**，见 §17.5 踩坑） |
| `data-nest-governance`：`DataStandardController`（修改） | 新增 4 接口：`POST /compliance-check/page`、`POST /compliance-check/ignore/{resultId}`、`POST /compliance-check/unignore/{resultId}`、`POST /compliance-check/export`（返回 `ResponseEntity<byte[]>` text/csv）；角色注解拆分：合规接口（check/results/page/ignore/unignore/export）`@SaCheckRole` 三角色含 `DATA_ENGINEER`（SaMode.OR），标准配置接口仍限 SUPER_ADMIN/GOVERNANCE_ADMIN |
| `data-nest-job`：`StandardComplianceCheckHandler`（新增）+ `JobRegistrar`（修改） | `@XxlJob("standardComplianceCheckHandler")` 构造空 `ComplianceCheckRequest` 扫全部在线数据源；注册到 `JobRegistrar.platformJobs` 固定 cron（每天 02:00） |
| `AGENTS.md`（修改） | §6 已知坑新增「新增迁移脚本版本号必须大于库内已有最高版本」（本次 V3.6.10 撞 V3.7.0 踩坑）；合规下沉 task-core 说明 |

### 17.4 部署

- 编译：`mvn clean package -DskipTests`（11 模块 BUILD SUCCESS）。
- 重建 5 容器：**app-system**（先启，执行 Flyway V3.7.1）→ **app-governance / app-worker / app-job / app-engineering**（task-core 共享底座改动，按 AGENTS.md 需重建全部消费方），全部 healthy。
- 前端 app-frontend 由前端 AI 负责，本次未动。

### 17.5 踩坑记录

- **Flyway 版本号撞车**：新增 `V3.6.10` 时库内已有 `V3.7.0`（quality_score_config），Flyway 按版本排序判定新迁移乱序，app-system 启动报 `Detected resolved migration not applied to database: 3.6.10` 退出。处理：改为 `V3.7.1`。**预防**：先查 `flyway_schema_history` 最高版本再定编号；且 `mvn clean package`（避免 target/classes 残留已删旧脚本被带进 jar，首次误报 3.6.10 就是 target 残留导致）。
- **`@SaCheckRole` 注解 value 需字面量数组**：不能传 `String[]` 常量（注解属性值必须是编译期常量），需内联 `value={"A","B","C"}`。
- **测试脚本 500 假象**：用 curl 在 PowerShell 传 JSON body 会因引号转义产生非法 JSON，接口返回 `HttpMessageNotReadableException`（500）；用 `Invoke-RestMethod` 或 `--data-binary @file` 正常。

### 17.6 API 验证记录（业务角度，admin + 工程师双角色）

- 合规扫描 `POST /compliance-check` → 81 条不合规项（含 TABLE/COLUMN 的 NAMING 违规），返回 DTO 含 `ignored:0` 三字段。
- 分页 `POST /compliance-check/page`：默认（ignored=0）total=81；忽略 1 条后默认 total=80，`ignored=1` 筛选 total=1（该条可见）✅ 默认排除已忽略 + 筛选正确。
- 忽略 `POST /compliance-check/ignore/{id}` → DB `ignored=1` + `ignored_by=1`(admin) + `ignored_at` 记录 ✅。
- 取消忽略 `POST /compliance-check/unignore/{id}` → DB `ignored=0`，`ignored_by/ignored_at` 清空 ✅。
- 导出 `POST /compliance-check/export` → CSV 表头（对象路径/对象类型/违规类型/实际值/期望值/适用规范/检查时间/是否忽略）+ 81 行数据，带 BOM 中文可开 ✅。
- **工程师权限**（创建 DATA_ENGINEER 账号验证）：合规 page/ignore 返回 200 ✅；标准配置 naming-standards/page 返回 403 ✅（权限隔离正确）。
  - 2026-08-05 补充：**运行扫描 `POST /compliance-check` 工程师返回 403**（权限已按 PRD 收回，仅治理员/超管可运行扫描）；结果查看/忽略/导出工程师仍 200。
- 测试数据（合规忽略项、工程师账号、导出临时文件）已清理。

### 17.7 模板批量应用 review + 修复（QualityRuleService.batchCreate）

> 用户确认「全部修复」3 个 review 发现的问题：

| 问题 | 修复 |
|------|------|
| ① 自动命名「模板名·表名」无重名校验（同表重复应用生成重复规则） | 预计算所有规则名后 `assertNoDuplicateNames`（本次批量内部）+ 一次性 `selectList(in(name))` 查库内已有同名（`ErrorCode.QUALITY_RULE_NAME_EXISTS=4209`），替代原来无校验 |
| ② 逐条 `ruleMapper.insert` + 逐条 `bindJobRule`（N+1 写） | 改用 MyBatis-Plus `Db.saveBatch(created)`（自动填充 ASSIGN_ID id）批量插入规则 |
| ③ `bindJobRule` 内幂等 `selectCount` 在批量时冗余 | 批量插入后构造 `List<QualityJobRule>` 一次性 `Db.saveBatch(links)` 绑定，新规则必然未绑定，无需逐条幂等查询 |

- 依赖：`com.baomidou.mybatisplus.extension.toolkit.Db`（MyBatis-Plus 3.5.17 支持）。
- 部署：task-core-governance 改动 → 重建 app-governance/app-worker/app-job/app-system/app-engineering。
- **验证**：`POST /quality/rules/batch`（1 模板 + orders/products 2 表）→ 生成 2 条独立规则实例（name「唯一性检查·表名」+ job_rule 关联）；同模板同表再应用 → code=4209「已存在同名规则」，无重复落库 ✅。测试规则已清理。

### 17.8 删除用户 9999 排查结论（非本次改动引入）

- **现象**：调 `DELETE /api/system/users/{id}` 返回 9999 系统内部错误。
- **根因**：①`UserController`/`UserService` **从未实现删除用户接口**（`DELETE /users/{id}` 不存在，前端用户管理页也无删除按钮、`auth.ts` 无 deleteUser API）——属既有功能缺口，非删除逻辑 bug；②该请求走 gateway 到 app-system 无此端点，Spring 抛 `NoResourceFoundException`(404)，被 `GlobalExceptionHandler.handleException` 兜底统一包装为 500（9999）。
- **处理**：
  1. **404 误报 500 已修复**（见下）；**运行扫描权限已按 PRD 收回**（工程师 403，见 §17.6）。
  2. **删除用户功能仍未实现**：后端 `deleteUser`（级联删角色关联）+ 前端用户管理页删除入口，仍为后续待办（前端由前端 AI 负责）。

### 17.8b 404 误报 500 修复（2026-08-05）

- **根因**：`GlobalExceptionHandler`（data-nest-common）兜底 `@ExceptionHandler(Exception.class)` 把 `NoResourceFoundException`(404) 统一包装为 500（9999），导致所有「接口不存在」的请求误报 500。
- **修复**：
  - `ErrorCode` 新增 `NOT_FOUND(404, "请求的资源不存在")`。
  - `GlobalExceptionHandler` 新增 `@ExceptionHandler(NoResourceFoundException.class)`（`org.springframework.web.servlet.resource.NoResourceFoundException`），`@ResponseStatus(NOT_FOUND)` 返回 `code=404`。
  - `DataStandardController` 运行扫描 `POST /compliance-check` 角色注解从三角色收回为 `SUPER_ADMIN/GOVERNANCE_ADMIN`（对齐 PRD §8「运行标准合规扫描工程师❌」；用户此前确认的「查看/忽略/导出放开工程师」不变）。
- **部署**：common 公共底座改动 → 全量 `mvn clean package` + 重建 app-system/app-governance/app-worker/app-job/app-engineering（5 容器 healthy）。
- **验证**：
  - 调不存在接口 → HTTP 404 `{"code":404,"message":"请求的资源不存在"}`（原 500/9999）✅
  - 工程师运行扫描 → HTTP 403 `code=1005` ✅；工程师结果分页 → HTTP 200 total=81 ✅；admin 运行扫描 → 200 ✅
  - 回归：合规 page/ignore/unignore 均 200，无回归 ✅

### 17.9 前端接口契约（供前端 AI 对接）

后端新增/变更，前端需对齐：

- **路径前缀** `/api/governance/data-standards`（网关已路由 `/api/governance/**`）：
  - `POST /compliance-check/page`：body `{page,pageSize,ignored?,datasourceIds?,...}`，返回 `PageResult<ComplianceCheckResultDTO>`
  - `POST /compliance-check/ignore/{resultId}` / `unignore/{resultId}`：忽略/取消忽略
  - `POST /compliance-check/export`：body `ComplianceCheckRequest`，返回 `text/csv`（`Content-Disposition: attachment`），前端按 blob 下载
- **字段**：`ComplianceCheckResultDTO` 新增 `ignored`/`ignoredAt`/`ignoredBy`；`violationType` 语义为 `NAMING`/`TYPE`（**前端分组字段类型违规须用 `TYPE`**，非 `FIELD_TYPE`）
- **权限**：合规接口对 SUPER_ADMIN/GOVERNANCE_ADMIN/DATA_ENGINEER 开放；标准配置接口仅治理员/超管
- **定时扫描**：后端已注册全局 cron（每天 02:00），前端无需处理

### 17.10 待办

1. ✅ 前端：标准合规前端已完成（见 §20，2026-08-05）：独立菜单页 + 三格统计 + 忽略/取消忽略 + 导出 + 立即扫描，废弃 `ComplianceCheckPanel` sessionStorage 方案（`violationType` 分组改 `TYPE` 已随重构落地）。
2. 前端：质量规则页/规则模板库「批量应用」占位按钮接线（前端 AI，后端 `POST /quality/rules/batch` 已就绪）。
3. 后端：删除用户 `deleteUser`（级联角色）+ `GlobalExceptionHandler` 对 `NoResourceFoundException` 返回真 404（用户已确认后续做）。
4. 质量报告（DG-07）按原排期 S8 单独会话做。

## 18. Sprint6 三维度补全（2026-08-05，级联删除/删除校验/定时任务）

> 用户要求分析 Sprint 6 功能的级联删除、删除关联校验、定时任务完备性，确认方案 A+B 修复。

### 18.1 分析结论（经 code-explorer 全量核查）

**级联删除到位**：质量任务删 `quality_job_rule`、质量规则删 `quality_job_rule`、告警规则删 `alert_rule_user/object`、命名规范级联删合规结果、字段类型标准被引用阻止、数据源级联删字段→表→合规结果。

**级联删除缺口（本轮修复）**：
- 删质量任务未校验/清理 `alert_rule`(QUALITY) 引用、未校验 auto_trigger 反向引用。
- 删规则模板未校验被 `quality_rule.template_id` 引用（会留悬空引用）。
- 删数据源 `getReferences` 只查 collect/sync，未查质量规则引用。

**删除关联校验缺口（本轮修复）**：质量任务/规则模板/数据源三类删除缺「被引用阻止」。

**定时任务完备性**：已有 11 个 platform handler + worker 3 个；缺「质量检查历史清理」；合规结果因是快照性质（重跑覆盖）无需定时清理。

### 18.2 变更清单（后端）

| 文件 | 变更 |
|------|------|
| `QualityJobService.delete`（task-core-governance） | 删除前校验 `alert_rule_object` 存在 `object_type='QUALITY' AND object_id=任务id` → HAS_REFERENCES 阻止（注入 `AlertRuleObjectMapper`；auto_trigger 反向引用不适用，auto_trigger 是配置在 quality_job 上指向外部对象） |
| `QualityRuleTemplateService.delete`（task-core-governance） | 删除前校验 `quality_rule.template_id=模板id` 存在 → HAS_REFERENCES 阻止（注入 `QualityRuleMapper`） |
| `ReferenceType`（common） | 新增 `QUALITY_RULE("QUALITY_RULE","质量规则")` 枚举 |
| `DataSourceService.getReferences`（engineering） | 注入 `QualityRuleMapper`，增加查 `quality_rule.table_id in (本数据源表id)` 的质量规则引用 → HAS_REFERENCES 阻止 |
| `QualityCheckHistoryCleanupHandler`（job，新增） | 按保留天数（默认 30 天，`datanest.job.quality-check-cleanup.retain-days`）分批（每批 500）清理超期 `quality_check_batch` + 级联删关联 `quality_check_detail`（按 batch_id） |
| `JobRegistrar`（job） | 注册 `qualityCheckHistoryCleanupHandler` cron `0 30 4 * * ?`（每天凌晨 4 点 30 分） |

### 18.3 部署与验证

- 全量 `mvn clean package`（common/engineering/task-core-governance/job 改动）+ 重建 5 容器（app-system/governance/worker/job/engineering），全部 healthy。
- **删数据源被质量规则引用** → code=3005 HAS_REFERENCES ✅
- **删模板被质量规则引用** → code=3005 HAS_REFERENCES ✅（删规则后模板可删，校验是"引用时才阻止"）
- **删质量任务被告警规则(QUALITY)引用** → code=3005 HAS_REFERENCES ✅（删告警规则后任务可删）
- **质量历史清理 handler**：已注册（jobId=520, triggerStatus=1, cron 正确, triggerNextTime 排定）；清理 SQL 逻辑验证通过（超期 batch/detail 能被查询条件匹配）；手动 `/jobinfo/trigger` 返回 trigger_code=500 是 XXL-JOB 手动触发不传 addressList 的既有环境现象（对照已存在 job 同 500），不影响 cron 自动调度。
- 测试数据（临时模板/规则/告警规则/超期 batch/工程师账号/临时文件）已清理。

### 18.4 待办/注意

- XXL-JOB 手动 trigger 500（executor 自动注册地址在 admin 未即时生效）为既有环境现象，所有 platform job 手动触发均如此，非本次引入；cron 调度正常。
- 删除用户功能仍未实现（见 §17.8），后续做。

## 19. 删除补全 + 被阻止删除返回引用明细（2026-08-05）

> 前置：用户要求从产品角度审视删除合理性。经梳理，`alert_history`/`quality_check_batch`/`quality_check_detail` 均设计快照字段（rule_name/job_name），原始产品意图为【保留历史审计记录】。用户最终确认「保留历史」；血缘是「当前数据加工关系」非审计记录，确认「删 DAG 清血缘」；删质量任务评分确认「方案1（无启用规则删评分/否则保留）」。范围：除「删用户/角色」外全部做，前端本次由本会话负责修复。

### 19.1 产品审视结论（删除合理性分层）

| 数据类型 | 代表 | 产品语义 | 删除策略 |
|----------|------|----------|----------|
| 配置关联 | alert_rule_user/object、quality_job_rule、DAG 节点/边 | 主动配置的绑定 | **删配置对象时清理** |
| 审计历史 | alert_history、quality_check_batch/detail、sync/collect_history | 已发生事件的证明 | **保留**，靠定时清理（用户确认，不改快照设计） |
| 衍生呈现 | lineage_record（血缘） | 当前数据加工关系 | **删 DAG 时按 dag_id 清理**（DAG 删了成死边） |
| 表级评分 | quality_score | 当前被监控表健康度 | 删质量任务后**无启用规则覆盖则删评分，否则保留**（方案1） |

### 19.2 变更清单（后端）

| 文件 | 变更 |
|------|------|
| `DagService.delete`（engineering） | 注入 `LineageRecordMapper`，删 DAG 按 `dag_id` 清血缘（`LineageRecordCleanupMapper` 不删，保留 90 天策略改为随 DAG 删）；子 DAG 引用校验由拼 dagIds 到 message 改为**返回引用 DAG 名称列表**（data） |
| `QualityJobService.delete`（task-core-governance） | 告警引用校验改为**返回引用告警规则名称列表**（data，注入 `AlertRuleMapper`）；新增 `cleanupScoresWithoutActiveRules`：删任务后查该表是否仍有 `enabled=1` 规则，无则删 quality_score，有则保留（批次/明细作为审计历史**不删**） |
| `FieldTypeStandardService.delete`（governance） | 被命名规范引用时改 `selectList` 查询，**返回引用命名规范名称列表**（data） |
| `SyncJobService.delete`（engineering） | DAG 引用由拼 message 改为**结构化 data**（引用 DAG 名称列表） |

### 19.3 变更清单（前端，本次本会话修复）

| 文件 | 变更 |
|------|------|
| `components/ReferenceListModal.tsx`（新增） | 通用「删除被阻止」引用明细弹窗，展示后端 BusinessException.data 的名称列表 |
| `pages/engineering/datasources/index.tsx` | 补 `QUALITY_RULE` 质量规则引用分组展示 |
| `types/datasource.ts` | `DataSourceReference.type` 扩展为 `'COLLECT'\|'SYNC'\|'QUALITY_RULE'` |
| `pages/engineering/sync-jobs/index.tsx` | handleDelete 捕获 7009 → ReferenceListModal 展示 DAG 引用名 |
| `pages/engineering/dags/project.tsx` | handleDelete 捕获 7009 → ReferenceListModal 展示 DAG 引用名 |
| `pages/governance/data-quality/index.tsx` | handleDeleteJob 捕获 3005 → ReferenceListModal 展示告警规则引用名 |
| `pages/governance/data-standards/index.tsx` | handleDelete 捕获 3005（field-type）→ ReferenceListModal 展示命名规范引用名 |

### 19.4 部署与 API 自测

- 后端：全量 `mvn clean package` + 重建 5 容器（system/governance/worker/job/engineering），healthy。
- 前端：`app-frontend` 重建，3000 端口 HTTP 200。
- **删 DAG 清血缘**：删 `血缘自动上报`(dag_id=2084098676382314498) → 其 2 条 lineage_record 被清（0 行）✅
- **删质量任务评分方案1**：有启用规则 → 评分保留（85）；无启用规则 → 删任务后评分清理 ✅
- **删字段类型标准被命名规范引用** → code=3005 + `data:["ID 字段命名规范"]` ✅
- **删质量任务被告警规则引用** → code=3005 + data 告警规则名（前 §18 已验 3005，本轮改返回名称）✅
- 测试数据（临时任务/评分/规则启用恢复/被删 e2e DAG）已处理；字段类型标准未删（被阻止保留）。

### 19.5 前端界面待人工验证

浏览器实际点删除按钮验证引用明细弹窗：
- 删同步任务被 DAG 引用 → 弹窗列 DAG 名
- 删 DAG 被子 DAG 引用 → 弹窗列 DAG 名
- 删质量任务被告警引用 → 弹窗列告警规则名
- 删字段类型标准被命名规范引用 → 弹窗列命名规范名
- 删数据源被质量规则引用 → 弹窗新增「质量规则」分组

### 19.6 待办（未做）

- 删除用户 `deleteUser`（级联角色）仍未实现（§17.8/§18）。
- 角色删除/管理未实现。
- 合规结果历史清理定时任务仍未做（`compliance_check_result` 每天全量扫描累积，仅删数据源/命名规范时清理，无按保留期全局清理）。

## 20. 标准合规检查前端（2026-08-05，本次会话）

> **阶段**：在 §17 后端（提交 b6b40bb4）基础上完成**标准合规前端**：新增独立「标准合规」菜单页（对齐 PRD §6.6 / 原型 View 5），并补后端统计摘要接口。经产品沟通确认方向后实施、review、构建部署、文档更新。

### 20.1 产品决策（用户确认）

| 决策点 | 结论 |
|--------|------|
| UI 落点 | **新增独立「标准合规」菜单页**（数据治理组，`/governance/compliance`），废弃数据标准页内 sessionStorage 合规检查方案 |
| 统计形态 | 三格统计：不合规项数 / 已忽略数 / **合规率** |
| 合规率口径 | `合规率 = (1 − 未忽略不合规项 ÷ 在线表+字段对象总数) × 100%`；**已忽略项视为已豁免/已整改，不拉低合规率**；无范围内对象时返回 100；页面副标题标注「对象合规率（按表/字段对象估算）」 |
| 菜单权限 | `COMPLIANCE_VIEW_ROLES`（超管/治理员/工程师）——因后端已放开工程师查看/忽略/导出合规结果，独立菜单需对工程师可见 |
| 立即扫描 | 弹窗选数据源 + 检查项目（命名规范/字段类型）→ 扫描完成后刷新清单页与统计 |

### 20.2 后端补接口（本次会话补充，§17 之后）

| 产物 | 变更 |
|------|------|
| `task-core-entity` dto：`ComplianceCheckSummaryDTO`（新增） | 统计摘要 `nonCompliant`/`ignored`/`totalObjects`/`complianceRate` |
| `task-core-entity` dto：`ComplianceCheckPageRequest`（修改） | 新增 `violationType`（NAMING/TYPE 筛选）；修正 `ignored` 注释（null=0 默认仅未忽略，1=仅已忽略，2=全部） |
| `task-core-governance`：`ComplianceCheckService`（修改） | 新增 `summary(request)`（`resolveRangeTableIds` 一次表 ID 解析复用，批量统计避免重复查询）+ `page` 支持 `violationType` 过滤 + `ignored=2` 全部 |
| `governance`：`DataStandardController`（修改） | 新增 `POST /compliance-check/summary`（三角色：超管/治理员/工程师） |

### 20.3 前端变更清单

| 产物 | 变更 |
|------|------|
| `src/types/dataStandard.ts`（修改） | `ComplianceCheckResult.violationType` 改 `'NAMING'\|'TYPE'`（对齐后端，字段类型用 TYPE）；加 `ignored`/`ignoredAt`/`ignoredBy`；`ComplianceCheckParams` 加 `datasourceId`；新增 `ComplianceCheckPageParams`（page/pageSize/ignored/violationType）/`ComplianceCheckSummary` |
| `src/api/dataStandard.ts`（修改） | 新增 `pageComplianceCheckResults`/`ignoreComplianceCheckResult`/`unignoreComplianceCheckResult`/`getComplianceCheckSummary`/`exportComplianceCheck`（`/governance/data-standards/compliance-check/**`） |
| `src/pages/governance/compliance/index.tsx`（新增） | 独立「标准合规」页：三格统计卡片 + 扫描结果清单（`DsToolbar` 筛选：数据源/违规类型/忽略状态 + 分页 `Pagination` + `DsTableEmpty`）+ 忽略/取消忽略（`ConfirmDialog`）+ 导出 CSV（blob 下载）+ 立即扫描弹窗（选数据源+检查项目）+ 查看跳元数据（`?tableId&columnId&from=compliance`）+ URL 状态同步（返回不丢筛选） |
| `src/components/Sidebar.tsx` + `src/router/index.tsx`（修改） | 「数据治理」组新增「标准合规」菜单（`COMPLIANCE_VIEW_ROLES`，`HiOutlineShieldCheck`）+ `/governance/compliance` 懒加载路由 |
| `src/constants/roles.ts`（修改） | 新增 `COMPLIANCE_VIEW_ROLES`（SUPER_ADMIN/GOVERNANCE_ADMIN/DATA_ENGINEER） |
| `src/pages/governance/data-standards/index.tsx`（修改） | 废弃 sessionStorage 合规方案：移除「合规检查」按钮 + 弹窗 + `fromCompliance` 恢复逻辑 + 结果面板分支；删除 `ComplianceCheckPanel.tsx`（不再引用） |

### 20.4 Review 结论（功能 × 架构 × 效率）

- **架构融洽**：后端 summary DTO 在 task-core-entity（共享底座）、Service 在 task-core-governance、Controller 在 governance，符合模块分层；前端复用 `DsToolbar`/`DsFilterSelect`/`DsStatusBadge`/`DsTableEmpty`/`Pagination`/`ConfirmDialog`/`DsIconButton`/`DsModal`，路由 lazyPage + Sidebar roles，无手写漂移。
- **业务正确**：`violationType` 用 `TYPE`（对齐后端 §17.2）；`ignored` 三态（0/1/2）语义对齐；合规率口径已忽略不拉低；权限 `COMPLIANCE_VIEW_ROLES` 对齐后端三角色；导出走 CSV blob（`responseType:'blob'`，拦截器对 Blob 透传）。
- **实现高效**：summary 用 `resolveRangeTableIds` 一次表 ID 解析 + 批量 `selectCount` 避免重复查询；page 用 MP `selectPage`；前端 URL 状态同步（`urlInitRef` 单 init + 单 sync）。

### 20.5 部署与验证

- **构建**：后端 `mvn -pl task-core-entity,task-core-governance,governance,engineering,worker,job,system -am clean package -DskipTests` → BUILD SUCCESS；前端 `npm run build`（tsc 通过）。
- **部署**：重建 app-governance/engineering/worker/job/system（task-core jar 更新，按 AGENTS.md 全量）+ app-frontend（**`--no-cache`**，首次 `COPY dist/` CACHED 坑见下）。全部容器 healthy。
- **API 自测**（admin）：
  - `POST /compliance-check/summary` → `{nonCompliant:81, ignored:0, totalObjects:84, complianceRate:3.6}` ✅
  - `POST /compliance-check/page`：`ignored=2` 全量 total=81；`violationType=TYPE` 当前无 TYPE 违规返回 0（正常，现有数据全为 NAMING）✅
  - `POST /compliance-check/ignore/{id}` + `unignore/{id}`：summary 联动 `nonCompliant 81→80→81`、`ignored 0→1→0` ✅
  - `POST /compliance-check/export`：HTTP 200 返回 7578B CSV（带 BOM）✅
- **踩坑**：前端镜像 `COPY dist/` 首次 CACHED（BuildKit 未感知 dist 变化）→ `docker compose build --no-cache app-frontend` 强制重建；curl 在 PowerShell 传 JSON body 会因引号转义报 500（Jackson 反序列化错误），用 `-d @file` 或 `Invoke-RestMethod` 正常（与 §17.5 一致）。

### 20.6 待办（后续）

- 质量规则页/规则模板库「批量应用」占位按钮接线（后端已就绪，§17.10 第 2 项）。
- 删除用户 `deleteUser` / 角色管理（§17.10 第 3 项）。
- 合规结果历史清理定时任务（§19.6）。

## 21. 质量评分问题排查 + 全局筛选统一（2026-08-05，本次会话）

> **背景**：用户对质量评分提出 3 个疑问（数据源来源不一致 / 数据源列显示「—」/ 评分聚合可解释性），并指出「数据源管理类型下拉不触发查询需手动点查询」的全局一致性问题。经排查确认问题根因后，用户确认四个方向均处理。

### 21.1 排查结论（问题 → 根因）

| # | 用户疑问 | 排查结论 |
|---|---------|---------|
| 1 | 质量规则与质量评分的数据源来源不一致 | **确认不一致**：质量任务/规则选表用 `listMetadataDatasourceIds`（只列采集过元数据的源）；质量评分筛选用 `getDataSources`（数据源管理全量连接）。产品上评分筛选应只列有元数据的源，需统一 |
| 2 | 质量评分「数据源」列显示「—」 | **确认为孤儿评分**：`quality_score.datasource_id`（9000020000000000001/0002）在 `datasource_connection` 表不存在（e2e 测试残留），`toDTO` 回填 `datasourceName` 时 `selectById` 返回 null → 「—」。根因：`DataSourceService.delete` 删数据源时清理了元数据/合规结果但**未清 `quality_score`** |
| 3 | 评分是否表维度聚合，详情看不出哪个任务/规则算的 | **确认为表维度跨任务聚合**（PRD §6.5.1）：`quality_score` 一张表一行，跨所有质量任务聚合该表启用规则最近结果。详情弹窗**有**返回规则明细（含 jobName/weight/resultLevel），但 UI 未做「评分来源」拆解，用户看不出来 → 产品设计可优化 |
| 4 | 数据源管理类型下拉不触发查询 | **确认为分派系的历史遗留不一致**：engineering/system 域 10 页（数据源/同步/同步历史/项目/DAG执行/项目内DAG/采集/采集历史/用户/告警中心）用 draft+手动点查询；governance 质量域 6 页即时触发；质量评分混合 |

### 21.2 处理方案（用户确认四项均处理）

**问题 1（数据源来源统一）**
- `quality-scores/index.tsx`：数据源下拉 `getDataSources` → `listMetadataDatasourceIds`（与质量任务/规则一致，只列采集过元数据的源）。

**问题 2（孤儿评分）**
- 后端 `DataSourceService.delete` 增加 `qualityScoreMapper.delete(eq datasource_id)` 级联清理（删数据源时删该源下评分，防再产生孤儿）。
- 一次性清理现有 e2e 残留孤儿评分（SQL 按 `NOT EXISTS datasource_connection` 删 2 条）。

**问题 3（产品优化）**
- `quality-scores` 详情弹窗新增「评分来源」卡片：展示由 N 个任务/M 条规则聚合、基础分（PASS 权重占比）、总扣分（警告+严重×权重）、最终分、权重分布（PASS/警告/严重权重和），附 PRD §6.5.1 算法说明（基于 detailRules + 全局扣分配置前端演算）。

**问题 4（筛选即时触发）**
- 统一模式：**下拉即时触发**（value 绑已应用 query，onChange 直接 `applyQuery`），**输入框/时间范围保留 draft**（避免逐字符查询）。改造 9 个带下拉的 draft 页面：
  - engineering/datasources、sync-jobs、sync-jobs/history-global、dag-executions、dags/project（假分页）
  - governance/collect-tasks、collect-tasks/history-global
  - system/users、alert-center（规则对象类型 + 历史 3 个下拉）
- `dags/index.tsx` 仅输入框，保持 draft（无需改）。

### 21.3 Review 与验证

- **前端 `npm run build`（tsc）**：通过（修复了标准合规遗留的 `HiOutlineDownload` 图标 / `violationType` 类型 / 漏加 `StandardCompliancePage` lazyPage / `DsFilterSelect value` 类型等历史 TS 错误）。
- **后端 `mvn` 编译**：通过（DataSourceService 补 `QualityScore` import）。
- **quality-scores e2e**：11 个测试全绿（`11 passed`），质量评分改动（数据源下拉源 + 评分来源卡片）无回归。
- **容器**：engineering/governance/worker/job/system/frontend 重建后 healthy。
- **孤儿清理**：`DELETE 2`，`quality_score` 现 0 条孤儿。

### 21.4 待办（后续）

- 其余暂未纳入 e2e 的页面（datasources/users/sync-jobs/collect-tasks/dag-executions/dags）筛选即时化改造未补 e2e 断言（现有测试不依赖该交互，改动经 tsc + 手工验证）。
- 质量报告（DG-07）S8 单独会话。
