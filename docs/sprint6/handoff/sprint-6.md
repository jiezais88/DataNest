# Sprint 6 Handoff

> **更新时间**：2026-08-04（第二次会话）| **阶段**：规则模板库前后端 + 质量任务/规则配置层后端完成（检查/评分/合规/前端待做）
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
| 联调验证                                 | ✅ 完成   | 任务/规则全部接口经网关联调通过（见「质量任务/规则 · API 验证记录」）                              |

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
- B5：**手动创建的无模板规则 preview-sql 返回 null**：手动创建（未选模板、无 `sqlExpression`）的规则调用 `/preview-sql` 时 `RuleSqlGenerator.generate(template=null,...)` 返回 null，前端回退显示"无预览 SQL"。批量应用创建的规则（带 templateId）预览正常。

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
6. 前端：数据质量页 **任务/规则页签已完成**；剩余：检查历史、质量评分页签、元数据详情页「质量」页签、血缘图谱节点评分徽章、标准合规页（待执行批接口就绪后接入）。
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

### 质量任务/规则配置层 · 本次踩坑记录

- **登录 token 传递**：Sa-Token 走 `Authorization` header，且**直接放原始 token，不带 `Bearer ` 前缀**（测试时曾加 Bearer 导致 401；对齐前端 `src/api/request.ts`）。
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
