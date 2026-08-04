# Sprint 6 Handoff

> **更新时间**：2026-08-04 | **阶段**：规则模板库前后端完成（质量任务/规则/检查/评分/合规待做）
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
| 后端实现（质量任务/规则/检查/评分/合规） | 🔄 进行中 | 规则模板库（模板 CRUD）已交付并验证通过；任务/规则/检查/评分/合规待做                             |
| 前端实现（规则模板库）                   | ✅ 完成   | 独立「规则模板库」页面已交付（列表/统计/筛选/新增/编辑/详情/启停/删除）；数据质量页与血缘联动待做 |
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

## 5. Blocker

- B1：worker 同步任务终态回调挂载点需定位（`SyncJobExecutorService` 执行完成回调的确切类/方法），用于自动触发接入。
- B2： ~~质量相关操作权限注解 key 需对照现有角色权限表确认~~ → ✅ 已消解：读接口全员（治理员/超管/工程师/分析师），写接口治理员+超管（
  `@SaCheckRole` + `SaMode.OR`）。
- B3：`AlertRuleService.validate()` 硬编码对象类型需扩展支持 QUALITY（明确，非阻塞）。

### 本次新增 Blocker / 环境注意

- E1：`middleware-postgres` 业务库 Flyway `V3.5.7` 曾出现 checksum 校验失败（本地脚本与已应用版本不一致），已用 flyway
  repair 一次性修复；后续不要再改已应用的 V3.5.x 脚本，否则同样会校验失败。

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

### ⏳ 待办（后续按用户确认"做到后面补关联逻辑"）

1. 后端：模板「选择模板 + 多表」批量生成规则实例的关联逻辑（`QualityRuleController.batch` + 校验 SQL 展开）。
2. 后端：Flyway `V3.6.x__sprint6_data_quality.sql` 其余表（质量任务/规则/批次/历史/评分表 + 合规忽略字段）。
3. 后端：task-core 实体/Mapper/Service（`QualityJob`/`QualityRule`/`QualityCheckService`/`ScoreCalculator`）。
4. 后端：governance 其余质量 Controller；扩展告警 object_type=QUALITY + `AlertFiringService.fireBatch`。
5. 后端：job 新增 `QualityCheckHandler`/`StandardComplianceCheckHandler`/`QualityCheckHistoryCleanupHandler`。
6. 后端：worker 终态回调接入自动触发（B1）；血缘节点 `LineageNodeDTO` 新增 qualityScore；标准合规忽略/取消忽略。
7. 前端：数据质量页（任务/规则/历史/评分）、元数据详情页「质量」页签、血缘图谱节点评分徽章、标准合规页（规则模板库页已交付）。
8. 联调验证：真实数据校验、任务级定时触发、告警合并邮件、评分联动、合规扫描。

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
