# Sprint 5 Handoff

> 本文件用于 Sprint 5 内多个 Agent 会话之间的状态同步。每次会话结束时更新状态；新会话进入时先读此文件。
>
> **最后更新**：2026-08-03 — PRD、技术文档、UI 原型已确定；后端实现已完成并编译通过；待前端实现与联调。

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
- `DagService` 节点配置校验 + 子 DAG 循环引用检测 + 删除引用守卫 + 告警级联；`DagNodeMapper` 引用查询
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

## Blocker

无。全量 `mvn clean package`（含 gateway）编译通过。

## 跨会话状态看板

| 会话 | 负责人（Agent/人） | 状态   | 已完成                                    | 待办                                                                           | 备注                                                                                |
|------|--------------------|--------|-------------------------------------------|--------------------------------------------------------------------------------|-------------------------------------------------------------------------------------|
| 产品 | 当前 Agent         | 已完成 | Sprint 5 PRD、技术文档、UI 原型已确定     | —                                                                              | PRD/技术文档/原型范围已确认；技术栈版本已核对（ReactFlow 11.11.x、PostgreSQL 16）   |
| 后端 | 当前 Agent         | 已完成 | 三大模块后端实现 + 编译通过 + 代码 review | 待部署到环境做接口自测                                                         | 改动涉及 task-core → 需重编并部署 engineering+worker+governance+system+job 全部服务 |
| 前端 | -                  | 未开始 | UI 原型已就绪                             | 按原型与接口契约实现：血缘图谱页、告警中心页、条件分支/子DAG编辑器、用户选择器 | 后端接口契约已就绪；需同步 `/users/with-email`、`/lineage/*`、`/alert-rules` 等     |
| 测试 | -                  | 未开始 | -                                         | 功能/集成/回归测试                                                             | 建议后端接口自测通过后再进入联调                                                    |

## Next Action

1. 部署后端：
   `mvn -pl data-nest-task-core,data-nest-engineering,data-nest-worker,data-nest-governance,data-nest-system,data-nest-job -am clean package -DskipTests`
   后 `docker compose build` / `up -d --no-deps` 对应服务，检查镜像时间戳确认新 jar。
2. 后端接口自测（按 AGENTS.md 验证规范）：
    - 血缘：构造字段级 lineage_record 样例 → `/api/governance/lineage/graph`、`/columns`、`/impact`、`/source`；跑一次 SQL
      INSERT..SELECT 验证字段级落库
    - 告警：建 alert_rule → 触发失败同步 → 查 `alert_history` + MailHog；DAG 告警兼容回退；`/users/with-email` 过滤
    - 控制流：含 CONDITION（引用上游 row_count）+ SUB_DAG 的 DAG → 保存（验证循环引用阻断）→ 触发 → 验证命中分支执行、非命中分支
      SKIPPED、子 DAG 独立执行
3. 前端会话启动：按已确定的 UI 原型实现血缘图谱页 / 告警中心页 / 条件分支与子 DAG 编辑器 / 用户选择器，接入上述接口。

## 参考链接

- [项目级 Agent 约定](../../AGENTS.md)
- [Sprint 5 PRD](../DataNest-Sprint5-PRD.md)
- [Sprint 5 技术文档](../DataNest-Sprint5-技术文档.md)
- [Sprint 5 UI 原型](../Sprint5-血缘可视化告警中心控制流.html)
