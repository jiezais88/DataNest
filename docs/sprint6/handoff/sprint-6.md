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
| 前端实现（质量任务/规则）                | ⏳ 待办   | 未开始（需对接已交付的质量任务/规则配置层接口）                                                  |
| 联调验证                                 | ⏳ 待办   | 未开始                                                                                            |

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
- [x] 部署 app-system（Flyway V3.6.1 + 全量 repair）/ app-governance / app-job，API 全量测试通过（见下「验证路径」）。
- [x] 前端：质量任务/规则页面 **未做**（接口已就绪，待前端批）。

### ⏳ 待办（后续按用户确认"做到后面补关联逻辑"）

1. 后端：**真实执行校验** `QualityCheckService`（用 `RuleSqlGenerator` 展开 SQL + `GenericSqlExecutor` 执行）+ 结果分级。
2. 后端：Flyway 批次/历史/评分表（`quality_check_batch`/`history`/`score` + 合规忽略字段）。
3. 后端：`ScoreCalculator` 评分计算 + `AlertFiringService.fireBatch` 告警合并 + 扩展告警 object_type=QUALITY。
4. 后端：job 新增 `StandardComplianceCheckHandler`/`QualityCheckHistoryCleanupHandler`。
5. 后端：worker 终态回调接入自动触发（B1）；血缘节点 `LineageNodeDTO` 新增 qualityScore；标准合规忽略/取消忽略。
6. 前端：数据质量页（任务/规则/历史/评分）对接已交付的任务/规则接口、元数据详情页「质量」页签、血缘图谱节点评分徽章、标准合规页。
7. 联调验证：真实数据校验、任务级定时触发、告警合并邮件、评分联动、合规扫描。

### 质量任务/规则配置层 · API 验证记录（2026-08-04）

- 登录拿 token（`Authorization` 原始 token，无 Bearer）。
- 任务：创建(200, 徽章=已启用) / 重名(4206) / 定时缺 cron(400) / 分页(含规则数) / 详情(含规则) / toggle / 删除级联删规则 / execute 预留(4210)。
- 规则：单条创建 / 模板批量(选模板+多表逐表微调) / 按任务查 / preview-sql(RANGE 展开 `{column}`→amount、`{min}`→10、`{max}`→100) / RANGE 缺字段(400) / 表不存在(4212) / execute 预留(4210)。
- `QualityCheckHandler`：XXL-JOB 注册成功(id=415, cron=每分钟)；创建每分钟任务后 `last_trigger_at` 被命中更新，日志 `scanned=1, hit=1`。
- 测试数据已清理（`quality_job`/`quality_rule` 均为 0）。

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
