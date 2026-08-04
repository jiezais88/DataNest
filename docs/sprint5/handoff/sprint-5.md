# Sprint 5 Handoff

> 本文件用于 Sprint 5 内多个 Agent 会话之间的状态同步。每次会话结束时更新状态；新会话进入时先读此文件。
>
> **最后更新**：2026-08-04 — 告警规则多对象改造已部署；血缘记录/告警历史清理任务已上线；元数据详情页表级血缘去重已修复部署；子
> DAG 选择限制为同项目。

## Sprint 目标

让治理管理员和分析师能够直观地看懂数据血缘链路，让平台告警覆盖更广、管理更集中，让数据工程师能够编排更复杂的条件分支和可复用的子
DAG 流水线。

## 已确认范围

| 模块           | 优先级 | 说明                                                           | 状态       |
|----------------|--------|----------------------------------------------------------------|------------|
| 血缘可视化     | P0     | 表级血缘图谱、字段级血缘下钻、影响分析、溯源分析               | 后端已完成 |
| 全局告警中心   | P1     | 统一入口管理 DAG/同步任务/采集任务的邮件告警；保留模块快捷入口 | 后端已完成 |
| DAG 控制流增强 | P1     | 条件分支节点、子 DAG 节点                                      | 后端已完成 |

## 关键决策

- **血缘可视化本期做到字段级**：表级图谱 + 字段级下钻，不做 3D 复杂布局。
- **告警中心统一入口 + 模块快捷入口并存**：系统管理下新增「告警中心」，同时保留 DAG 编辑器、同步任务、采集任务的告警配置入口。
- **告警渠道本期仍只支持邮件**：钉钉/企微/Webhook 放到 Sprint 6。
- **条件分支和子 DAG 都做**：条件分支支持按表达式选择下游；子 DAG 支持同步/异步执行，本期不支持参数透传。
- **血缘可视化技术方案**：基于现有 PostgreSQL `lineage_record` 表，扩展 `source_column` / `target_column` 字段，前端用
  ReactFlow 做图布局，不引入 Neo4j。
- **告警规则表设计**：新建通用 `alert_rule` 表 + `alert_rule_user` 关联表，替代扩展 `dag_alert_config`；收件人改为选择平台用户，发送时反查
  `sys_user.email`。已做 `dag_alert_config` → `alert_rule` 数据迁移（V3.5.5），并保留兼容回退。
- **告警中心归属（后端实现确认）**：统一 CRUD/历史/用户选择器落在 `data-nest-system`（`/api/system/alert-rules` 等），与文档
  §10.2 及 PRD 菜单一致；`AlertRuleService` 在 task-core 共享（无 MailService 依赖，system 可安全 @Import）。
- **条件分支实现方案（后端实现确认，替代文档 §8.2 的 CONDITIONS/SWITCH 映射）**：
    - DS 原生 SWITCH 表达式只能引用 DAG 参数/varPool，而 HTTP 任务写入 varPool 的值是含引号 JSON，JS 解析必然失败 →
      无法引用上游输出。
    - 实际采用：CONDITION 节点映射为普通 DS HTTP 任务回调 worker，worker 用 SpEL（只读数据绑定，禁方法调用）按顺序求值分支，
      结果写入 `node_execution.output_info`；非命中分支的下游节点在回调时被 worker 标 SKIPPED 并返回成功，DS 视为成功继续。
    - 被跳过分支节点在 DS 不生成 task instance，本地 `WAITING→SKIPPED` 兜底（`DagExecutionSyncService`）天然兼容。
- **子 DAG 映射（后端实现确认，命名纠正）**：DS 3.4.2 任务类型是 **`SUB_WORKFLOW`**（非文档所写 SUB_PROCESS）；同步执行 = DS
  SUB_WORKFLOW 原生等待，异步执行 = DS HTTP 回调 engineering 内部端点 `/dev/internal/subdag/trigger` 触发子 DAG 后立即返回。
- **jsqlparser 版本统一**：移除 task-core 显式 jsqlparser 4.9，全项目统一 mybatis-plus-jsqlparser 传递的 5.2（
  `net.sf.jsqlparser` 包兼容， 消除与分页插件的版本仲裁冲突）。

## 变更清单（后端实现）

### Flyway 迁移（`data-nest-system/src/main/resources/db/migration`）

| 脚本                                       | 内容                                                             |
|--------------------------------------------|------------------------------------------------------------------|
| `V3.5.0__extend_lineage_record_column.sql` | lineage_record 加 `source_column` / `target_column`              |
| `V3.5.1__extend_dag_node_control_flow.sql` | dag_node.node_type CHECK 扩展 CONDITION / SUB_DAG                |
| `V3.5.2__alert_rule.sql`                   | 通用告警规则表 + `uk_alert_rule_object`                          |
| `V3.5.3__alert_rule_user.sql`              | 规则-接收用户关联表                                              |
| `V3.5.4__alert_history.sql`                | 告警发送历史                                                     |
| `V3.5.5__migrate_dag_alert_config.sql`     | dag_alert_config → alert_rule 数据迁移（收件人邮箱反查 user_id） |

> 注：`V3.4.4__sync_job_log_table_name.sql` 为用户在途工作，非本会话新增。

### data-nest-task-core

- 实体：`LineageRecord` 加 source/target column；新增 `AlertRule` / `AlertRuleUser` / `AlertHistory`；`SysUser` 加 email
- Mapper：`LineageRecordMapper` 加图谱 BFS + 字段级链路查询；新增 `AlertRuleMapper` / `AlertRuleUserMapper`（含批量查用户，避免
  N+1）/
  `AlertHistoryMapper`（历史联查对象名 + 60s 防重）；`SysUserMapper.selectEmailsByIds`；
  `DagNodeMapper.selectDagIdsReferencingSubDag`
- 服务：`SqlLineageExtractor` 字段级提取（INSERT..SELECT / CTAS 单源表列映射，ADR-S5-005 范围）；`AlertRuleService`（CRUD +
  对象选择 + 历史，无邮件依赖）；`AlertFiringService`（发邮件 + 写历史 + 60s 防重）；`DagAlertService` 兼容 alert_rule 优先 +
  dag_alert_config 回退；
  `SyncJobExecutorService` / `CollectExecutor` 终态触发告警
- 常量/DTO：`AlertConstants`；`AlertRuleDTO` / `AlertObjectOptionDTO` / `ColumnRef` / `LineageColumnLinkDTO` /
  `LineageTableEdge` /
  `ConditionNodeConfig` / `SubDagNodeConfig`

### data-nest-governance

- `LineageController` 新增 `/lineage/graph`、`/lineage/columns`、`/lineage/impact`、`/lineage/source`（4 角色可读）
- `LineageService` 表级图谱 BFS（默认 1 层，上限 10）、字段级链路、影响/溯源子图
- DTO：`LineageGraphDTO` / `LineageNodeDTO` / `LineageEdgeDTO`
- `CollectTaskAlertRuleController`（`/collect-tasks/{id}/alert-rule` 快捷入口）；`CollectTaskService.delete` 级联删告警规则

### data-nest-system

- `AlertRuleController`（`/alert-rules` CRUD + users + object-options）；`AlertHistoryController`（`/alert-history`）；
  `UserSelectorController`（`/users/with-email`）；`UserMapper.selectUsersWithEmail`；`UserOptionDTO`
- `MyBatisPlusConfig` 增加 `@Import(AlertRuleService.class)`
- 权限：告警查看 = 超管/工程师/治理员；编辑 = 超管/工程师（PRD §8）

### data-nest-engineering

- `DagDsConverter` 支持 CONDITION（HTTP 回调 worker）/ SUB_DAG（同步 SUB_WORKFLOW、异步 HTTP 调 engineering）；
  `DolphinSchedulerConfig` 加
  `engineeringCallbackBaseUrl`
- `DagService` 节点配置校验 + 子 DAG 循环引用检测 + **子 DAG 必须与父 DAG 同项目** + 删除引用守卫 + 告警级联（含 Sprint 4
  `dag_alert_config` /
  `dag_alert_history`）；`DagNodeMapper` 引用查询
- `DagProjectService.delete` 级联删除项目下所有 DAG 的 `alert_rule`（含关联表）、`dag_alert_config`、`dag_alert_history`
- `SubDagTriggerController`（`/dev/internal/subdag/trigger` 内部端点，异常返回非 2xx）
- `SyncJobAlertRuleController` / `DagAlertRuleController` 快捷入口；`SyncJobService` 删除级联告警
- 修复用户既有编译问题：`SyncJobService.getLogs(Long)` 1 参重载（DagExecutionService.getNodeExecutionLogs 需要）

### data-nest-worker

- `DagNodeCallbackController` 新增 `/condition/callback`
- `DagNodeExecuteService`：`handleConditionNode`（SpEL 求值写 output_info）+ 分支 gate `shouldSkipNode`
  （SQL/SYNC/PYTHON/CONDITION 统一入口， SYNC 跳过时释放互斥锁）

### data-nest-common

- `ErrorCode` 新增：`SUB_DAG_CYCLE_DETECTED(7101)` / `SUB_DAG_NOT_FOUND(7102)` / `SUB_DAG_DISABLED(7103)` /
  `CONDITION_CONFIG_INVALID(7104)` /
  `ALERT_RULE_NOT_FOUND(7201)` / `ALERT_RULE_OBJECT_INVALID(7202)`

### data-nest-job

- 新增 `LineageRecordCleanupHandler`：每天 03:30 清理 `lineage_record` 中超过保留天数（默认 90 天）的记录
- 新增 `AlertHistoryCleanupHandler`：每天 04:00 清理 `alert_history` 中超过保留天数（默认 90 天）的记录
- `JobRegistrar` 自动向 XXL-JOB 注册上述两个平台任务
- `application.yml` 增加 `datanest.job.lineage-cleanup.retain-days` /
  `datanest.job.alert-history-cleanup.retain-days` 配置项

## 变更清单（本会话：前端 + 后端补充字段）

### 后端补充（用户要求：`alert_history` 增加发送状态字段）

| 文件                                          | 内容                                                                             |
|-----------------------------------------------|----------------------------------------------------------------------------------|
| `V3.5.6__alert_history_send_status.sql`（新） | `alert_history` 加 `send_status VARCHAR(16) NOT NULL DEFAULT 'SUCCESS'`          |
| `AlertConstants`                              | 加 `SEND_STATUS_SUCCESS` / `SEND_STATUS_FAILED`                                  |
| `AlertHistory` 实体                           | 加 `sendStatus`                                                                  |
| `MailService.send`                            | `void` → `boolean`（成功 true；未配置 sender/无收件人/异常 false），旧调用方兼容 |
| `AlertFiringService.fire`                     | 发送后写 `sendStatus`；`saveHistory` 增加参数                                    |
| `AlertHistoryMapper.selectHistoryPage`        | 加可选 `sendStatus` 过滤                                                         |
| `AlertHistoryController.list`                 | 加 `@RequestParam sendStatus`                                                    |

> 注：`task-core` 有改动，需重编并部署 engineering/worker/governance/system/job 全部服务。

### 前端

| 文件                                                                                 | 内容                                                                                                                                                                 |
|--------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `src/api/lineage.ts` / `src/types/lineage.ts`                                        | 扩展血缘图谱/字段级/影响溯源接口与类型                                                                                                                               |
| `src/api/alert.ts` / `src/types/alert.ts`（新）                                      | 告警规则 CRUD/toggle/object-options/history（含 sendStatus）/users/with-email + 三个快捷入口                                                                         |
| `pages/governance/metadata/lineage/LineageGraphPage.tsx`（新）                       | 表级血缘 ReactFlow 图谱：影响/溯源高亮、展开层级、空状态、点击/双击跳转；路由 `/governance/metadata/lineage`                                                         |
| `pages/governance/metadata/lineage/FieldLineagePanel.tsx`（新）                      | 字段级血缘下钻                                                                                                                                                       |
| `pages/governance/metadata/index.tsx`                                                | 表详情加「血缘图谱」入口按钮                                                                                                                                         |
| `pages/system/alert-center/AlertCenterPage.tsx`（新）                                | 告警规则/历史两个 Tab，历史含发送状态列+过滤+详情弹窗                                                                                                                |
| `components/AlertRuleModal.tsx`（新，全局通用）                                      | create/edit/quick 三模式通用弹窗（DAG/同步任务/采集任务快捷入口复用）                                                                                                |
| `components/UserSelect.tsx`（新）                                                    | 告警接收用户选择器（仅邮箱用户）                                                                                                                                     |
| `pages/engineering/dags/*`                                                           | 节点面板/节点组件/解析序列化/连线校验（SUB_DAG 单出线、CONDITION 分支同步）/保存校验；`ConditionNodeModal`/`SubDagNodeModal`；告警按钮替换为 `AlertRuleModal(quick)` |
| `pages/engineering/sync-jobs/index.tsx` / `pages/governance/collect-tasks/index.tsx` | 操作列加「🔔 告警配置」快捷入口                                                                                                                                      |
| `router` / `Sidebar` / `breadcrumb` / `roles.ts`                                     | `/system/alert-center`、`/governance/metadata/lineage` 路由与菜单（告警中心仅超管/工程师/治理员可见）、`ALERT_VIEW_ROLES` / `ALERT_WRITE_ROLES`                      |

### 前端关键交互决策（已与用户确认）

- 血缘图谱 = 独立页面（元数据详情按钮进入），不做页签重构。
- 条件分支 = 弹窗驱动（每分支选下游节点），保存时自动同步画布连线；删除条件节点出线同步删分支。
- DAG 编辑器告警按钮 = 替换为新的 `alert_rule` 快捷入口（统一数据源），旧 `DagAlertConfigModal` 保留未删但已无引用。

## Blocker

无。全量 `mvn clean package`（含 gateway）编译通过。

## 跨会话状态看板

| 会话 | 负责人（Agent/人） | 状态   | 已完成                                                | 待办                                                                    | 备注                                                                                |
|------|--------------------|--------|-------------------------------------------------------|-------------------------------------------------------------------------|-------------------------------------------------------------------------------------|
| 产品 | 当前 Agent         | 已完成 | Sprint 5 PRD、技术文档、UI 原型已确定                 | —                                                                       | PRD/技术文档/原型范围已确认；技术栈版本已核对（ReactFlow 11.11.x、PostgreSQL 16）   |
| 后端 | 当前 Agent         | 已完成 | 三大模块后端实现 + 编译通过 + 代码 review             | 待部署到环境做接口自测                                                  | 改动涉及 task-core → 需重编并部署 engineering+worker+governance+system+job 全部服务 |
| 前端 | 当前 Agent         | 已完成 | 已按 PRD/技术文档/原型实现三大模块前端 + 后端补充字段 | 待后端部署后联调：血缘图谱接口、告警中心 CRUD、条件分支/子 DAG 保存触发 | 前端 typecheck/lint/build 通过；后端 task-core 改动需重编部署全部服务               |
| 测试 | 当前 Agent         | 已完成 | 见文末「Sprint 5 测试会话」                           | 见「待处理缺陷」                                                        | API+E2E 全量 85/85 通过；测试基建在 data-nest-frontend/e2e/sprint5                  |

## Next Action

1. 测试会话发现的缺陷已全部修复并验证（含 P0 血缘边渲染，前端修复 + 血缘 E2E 验收通过）。
2. 回归：后续改动后重跑 `cd data-nest-frontend && npx playwright test --project=chromium`。
3. 测试基建保留在 `data-nest-frontend/e2e/sprint5/`（globalSetup 播种 / globalTeardown 清理），可直接复用。

## Sprint 5 测试会话

> 2026-08-03：API 测试 + E2E 测试全量完成， **85/85 通过**。测试基建：`data-nest-frontend/e2e/sprint5/`（Playwright）。
> 运行方式：`cd data-nest-frontend && npx playwright test --project=chromium --timeout=300000`。

### 覆盖范围

| 层  | 文件                         | 数量 | 说明                                                           |
|-----|------------------------------|------|----------------------------------------------------------------|
| API | `api/lineage.spec.ts`        | 16   | 图谱/字段/影响/溯源/权限/字段级写入真实执行                    |
| API | `api/alert.spec.ts`          | 21   | 规则 CRUD/校验/快捷入口/真实告警触发+MailHog/60s 防重/兼容回退 |
| API | `api/control-flow.spec.ts`   | 13   | CONDITION/SUB_DAG 保存与校验/循环引用/删除守卫/级联删告警      |
| API | `api/permissions.spec.ts`    | 7    | 4 角色权限矩阵（血缘/告警/控制流）                             |
| API | `api/real-execution.spec.ts` | 4    | 条件分支命中/默认、子 DAG 同步/异步真实执行                    |
| E2E | `e2e/lineage.spec.ts`        | 7    | 入口按钮/图谱渲染/影响溯源/字段面板/空状态/权限                |
| E2E | `e2e/alert-center.spec.ts`   | 11   | 菜单可见性/规则 CRUD/启停/删除/历史/快捷入口/权限              |
| E2E | `e2e/control-flow.spec.ts`   | 6    | 节点面板/拖入条件分支与子DAG/配置弹窗/保存校验                 |

### 已修复缺陷（测试驱动）

| # | 缺陷                                                               | 根因                                                                                                                                                     | 修复                                                                                                         |
|---|--------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------|
| 1 | 采集任务告警快捷入口 500                                           | `data-nest-governance` 缺 fastjson2（task-core 中 provided）                                                                                             | governance pom 补 fastjson2 + fastjson2-extension                                                            |
| 2 | system 服务 `@SaCheckRole` 全部失效（任何登录用户可建用户/改告警） | `data-nest-system` 缺 `sa-token-spring-aop`                                                                                                              | system pom 补 sa-token-spring-aop                                                                            |
| 3 | 编辑告警规则时对象被清空、保存被拦截                               | `AlertRuleModal` 打开时对象选项 effect 无条件 `setObjectId('')`                                                                                          | 移除 effect 内清空，改为用户手动切对象类型时清空                                                             |
| 4 | 条件分支恒命中默认分支（P1，真实执行确认）                         | ① `evaluateBranches` 从 index 0 求值，branches[0]="true" 恒真；② 默认 SimpleEvaluationContext 不含 MapAccessor，`#upstream.row_count` 属性语法必然抛异常 | ① 从分支 1 开始求值、index 0 兜底；② `${a.b}` 转 SpEL 索引语法 `#a['b']`                                     |
| 5 | 子 DAG 节点状态恒 SKIPPED（P1，真实执行确认）                      | `DagExecutionSyncService` 用 nodeName 匹配 DS 任务实例，但 DS 任务名=`节点名_节点ID后8位`（nodeId 含 `_`），永不匹配                                     | 按相同规则构建「DS 任务名→node」反向映射                                                                     |
| 6 | 兼容回退告警不写入统一告警历史（告警中心历史缺回退记录）           | `DagAlertService` 回退路径只写 `dag_alert_history`，告警中心历史页查 `alert_history`                                                                     | 回退路径同步写 `alert_history`（alert_rule_id=NULL，send_status 按邮件结果）                                 |
| 7 | 无血缘表空状态文案不可达（PRD AC-7 差异）                          | 后端 graph 恒返回中心节点，前端 `hasLineage=nodes.length>0` 恒 true                                                                                      | 前端 `hasLineage` 改为 `edges.length>0` 判断（方案 B）                                                       |
| 8 | DAG 更新接口循环引用检测依赖请求体带 `id`                          | `DagService.update` 未回填 `payload.setId(id)`，无 body id 时 A→B→A 循环无法检测                                                                         | `update` 在 validateRequest 前 `payload.setId(id)`                                                           |
| 9 | 血缘图谱 ReactFlow 边不渲染（P0，前端修复）                        | 自定义节点（TableNode/ColumnNode）未声明 `Handle`，且受控 edges 未配 change handler，边无法连接/渲染                                                     | 节点组件加 `Handle`（source/target）+ 改用 `useNodesState`/`useEdgesState` + `onNodesChange`/`onEdgesChange` |

### 后续增强会话（条件节点变量按节点名精确取值）

> 2026-08-04：Sprint 5 交付后对条件分支做了一轮产品化增强，已部署并 E2E 验证通过。

#### 需求背景

- 条件节点表达式变量暴露了无业务语义的 `dag_id`（内部主键），用户无法感知/预测。
- 多前驱场景下旧实现把多个前驱 `outputInfo` 通过 `upstream.putAll(out)` 扁平合并，同名 key 互相覆盖，
  `${upstream.row_count}`
  语义模糊，无法按具体前驱精确判断。
- 产品约束：嵌套结构的键 **必须用节点名**（不能用内部节点 ID），表达式形如 `${upstream['节点名'].row_count}`。

#### 后端改动

| 文件                                              | 内容                                                                                                                                                                                                                                                                                                            |
|---------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `data-nest-worker/.../DagNodeExecuteService.java` | `buildConditionContext` 的 `upstream` 改为**以节点名为键的嵌套 map**，每个前驱独立子 map（`row_count`/`status`/`sql_type`/`target_table` 等），顶层保留最后遍历前驱的 `row_count`/`status` 兼容旧写法；`vars.remove("dag_id")` 从条件表达式移除内部主键（`DagParameterResolver` 的 `dag_id` 保留供 SQL 占位符） |

#### 前端改动

| 文件                                                       | 内容                                                                                                                                          |
|------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------|
| `pages/engineering/dags/types.ts`                          | 新增 `UpstreamNodeInfo` 类型                                                                                                                  |
| `pages/engineering/dags/Editor.tsx`                        | 从 `rfEdges` 推导条件节点的直接前驱（含 nodeName/nodeType）传给弹窗                                                                           |
| `pages/engineering/dags/components/ConditionNodeModal.tsx` | 移除 `dag_id`；「插入变量」下拉按直接前驱动态生成 `${upstream['节点名'].xxx}`，并按「上游节点变量 / DAG 参数 / 系统变量」分组展示、每项带说明 |

#### 部署

- `mvn -pl data-nest-task-core,data-nest-worker -am clean package -DskipTests -q` + 重建 `app-worker`
- 前端 `npm run build`（需先构建 `dist/`）+ 重建 `app-frontend`

#### E2E 验证（真实执行）

用 API 搭建「前驱A (1行) + 前驱B (3行) → 条件C → 下游D/E」的多前驱 DAG：

| 表达式                                                  | 期望/实际                                    | 结果 |
|---------------------------------------------------------|----------------------------------------------|------|
| `${upstream['前驱A_SQL'].row_count} > 0`（A=1行,B=3行） | A=1 → true → 命中「按A取值」分支             | ✓   |
| `${upstream['前驱A_SQL'].row_count} > 1`（A=1行,B=3行） | A=1 → false → 走默认分支（证明精确取A而非B） | ✓   |
| `${upstream.row_count} > 1`（旧写法）                   | 顶层兼容字段仍可求值 → 命中「旧写法」分支    | ✓   |

验证点：条件节点 `output_info.branchIndex` 与下游 SUCCESS/SKIPPED 均符合按节点名精确取值语义；旧写法未破坏。

### 待处理缺陷 / 差异

无（Sprint 5 测试发现的问题均已修复并验证）。

### 测试期间发现的环境/说明

- MailHog 清空端点：`DELETE http://localhost:8025/api/v1/messages`（v2 端点 404）。
- `alert_rule` 表中 `id=11~20` 的规则为 Sprint 4/5 既有数据（非测试数据），保留。
- E2E 告警历史用例在完整套件运行时需先 `DELETE FROM alert_history`（同轮 API 测试会产生孤儿历史记录）。
- 兼容回退告警历史（alert_rule_id=NULL）清理：teardown 需在删 DAG 前按 `object_type='DAG'` + e2e_s5 DAG 关联删除，否则成为孤儿记录。

## 参考链接

- [项目级 Agent 约定](../../AGENTS.md)
- [Sprint 5 PRD](../DataNest-Sprint5-PRD.md)
- [Sprint 5 技术文档](../DataNest-Sprint5-技术文档.md)
- [Sprint 5 UI 原型](../Sprint5-血缘可视化告警中心控制流.html)
