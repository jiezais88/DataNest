# 微服务化改造 Handoff — 阶段 1：告警域远程化 + 新建 app-alert

> 独立改造（不属于任何 Sprint）。总目标：共享 jar 进程内调用 → OpenFeign 远程调用 + 按域拆库。
> 已确认决策：OpenFeign + Nacos；按域拆库（阶段 5 才拆）；最终一致性（Feign + 重试 + 对账，无分布式事务无 MQ）；新建 app-alert 独立告警服务；worker/job 最终不持库（纯执行节点，经 Feign 回写 owner）。

## 总体阶段规划

1. **阶段 1（本阶段）**：新建 app-alert + 告警域远程化（试点，验证模式）
2. 阶段 2：system 域远程化（SysUserService → Feign）
3. 阶段 3：engineering 域远程化（worker/job 执行回写链路，风险最高）
4. 阶段 4：governance 域远程化（质量/采集/元数据/血缘回写）
5. 阶段 5：拆库 + Flyway 基线 + 数据迁移（datanest_system / datanest_alert / datanest_engineering / datanest_governance）
6. 阶段 6：删除 task-core 四模块、文档收尾、全量回归

## 阶段 1 范围

- 新建 **app-alert** 服务（端口 8088，context `/alert`），收拢告警域全部代码与数据：
  - data-nest-alert 模块 7 个类（AlertRuleService/AlertFiringService/DagAlertService/MailService/DagAlertExecutionListener/QualityAutoTriggerPort/DagExecutionFinishedListener）
  - alert 四表 + **dag_alert_config / dag_alert_history**（方案调整：这两表随 DagAlertService 归 app-alert，不再归 engineering）
  - 收拢 Controller：system 的 AlertRuleController/AlertHistoryController、engineering 的 DagAlertRuleController/SyncJobAlertRuleController/DagAlertConfigController、governance 的 CollectTaskAlertRuleController
- 新建 4 个 api 模块骨架：data-nest-alert-api（完整）、system-api / engineering-api / governance-api（仅本阶段需要的端点，后续阶段扩充）
- 消费方改造：task-core 执行器 fire/fireBatch、DagExecutionFinishedListener 远程化、job 超时告警、engineering/governance 的 deleteByObject 级联 → 全部改 Feign
- app-alert 反向依赖：system（用户邮箱/用户名）、engineering（DAG/同步任务名称与下拉、dag_node 解析）、governance（采集/质量任务名称与下拉、质量自动触发）→ 各服务新增 `/internal/**` 端点
- 内部鉴权：`X-Internal-Token` 头（common 模块统一过滤器 + Feign 拦截器），仅拦截以 `/internal/` 开头的路径（不影响 DS 回调 `/dev/internal/**`）
- gateway 加 `/api/alert/**` 路由；前端告警 API 路径改 `/api/alert/**`
- 邮件配置集中到 app-alert，其余服务撤掉 shared-alert.yaml 引用与 MAIL_* 环境变量
- 本阶段**不拆库**（app-alert 暂连同一 datanest 库，阶段 5 统一拆）

## 状态看板

| 事项 | 状态 |
|---|---|
| 调研（模块结构/DB 矩阵/调用点） | ✅ 完成 |
| 方案批准 | ✅ 已批准 |
| Chunk A：api 模块 + 内部 token 基础设施 | ✅ 完成（4 api 模块 + InternalTokenFilter/Feign 拦截器 + shared-internal.yaml） |
| Chunk B：app-alert 服务 + 提供方内部端点 | ✅ 完成（data-nest-alert-service 模块，全量编译通过） |
| Chunk C：消费方 Feign 化 + 删 data-nest-alert | ✅ 完成（旧模块/旧实体已删，全量编译通过） |
| Chunk D：gateway/compose/Nacos/前端 | ✅ 完成（compose 加 app-alert、MAIL_* 收拢、shared-internal 已推送、前端 tsc 通过） |
| 构建部署 + 回归验证 | ✅ 完成（8 容器 healthy，验证记录见下） |

## 阶段 1 验证记录（2026-08-06，全部通过）

- 8 容器（含新 app-alert、重建的 gateway）全部 Up/healthy
- `GET /api/alert/alert-rules` 分页 ✅；旧路径 `/api/system/alert-rules` 已 404 ✅
- 对象下拉 4 类型（DAG 树/SYNC_JOB/COLLECT_TASK/QUALITY）✅ → app-alert→engineering/governance Feign 链路通
- `/alert/internal/fired` 无 token / 错 token → 401 ✅；Feign 互调自动带头 ✅
- `PUT /api/alert/rules/by-object` 建规则（objectName 经 engineering 远程解析）✅
- **fire 全链路 E2E**：触发同步任务 → worker SyncJobExecutorService → Feign fire → app-alert → system 邮箱反查 + engineering 名称解析 → MailHog 收到「[DataNest 通知] 同步任务…执行成功」→ alert_history 落库（rule_name/alert_type/send_status/recipients 正确）✅
- `/api/alert/alert-history` 分页、`/api/alert/dag-alert-config` ✅；验证规则已删除（未留脏数据）
- 前端 `npm run typecheck` 0 错误（浏览器端 UI 未逐页人工点验，建议下个会话快速过一遍告警中心/同步任务告警弹窗/DAG 告警配置弹窗）

## 关键调用点清单（改造时对照）

- fire/fireBatch：task-core `SyncJobExecutorService:100,122`、`CollectExecutor:204,214`、`QualityCheckService:204,623,631`（均在 worker 容器运行）
- DAG 终态：`DagAlertExecutionListener`（被 `DagExecutionSyncService` 经 `List<DagExecutionFinishedListener>` 回调，worker+job）→ 改为 task-core 内新 listener 调 AlertApi + GovernanceApi
- 节点超时：job `DagNodeTimeoutAlertHandler:48,56`（resolveConfig + onNodeTimeout）
- deleteByObject 级联：engineering `DagService:251`/`DagProjectService:175`/`SyncJobService:216`、governance `CollectTaskService:230`
- alert_history 反查：`QualityCheckService.getBatchDetail` 按 quality_batch_id 查 AlertHistoryMapper → app-alert 内部端点
- alert_history 清理：job AlertCleanupHandler → app-alert 内部清理端点
- system `MyBatisPlusConfig` `@Import({SysUserService.class, AlertRuleService.class})` → 移除 AlertRuleService

## Blocker

- 无

## 实施记录（Chunk A-D 关键变更与遗留）

**契约/结构**
- `data-nest-alert-api` 在 Chunk C 又扩了 4 个端点：`GET /alert/internal/dag-alert-config/resolve?dagId=`（job 超时阈值判断，返回 enabled+timeoutMinutes）、`DELETE /alert/internal/dag-alert-config/by-dag?dagId=` 与 `DELETE /alert/internal/dag-alert-histories/by-executions?executionIds=`（engineering 删 DAG/项目级联）、`GET /alert/internal/rules/by-object/names`（QualityJobService 删除前引用校验）。
- 最终 Feign 依赖：worker/job/engineering/governance → alert-api；app-alert → system-api/engineering-api/governance-api。
- 各服务启动类 `@EnableFeignClients(basePackages = "com.datanest.alert.api")`；app-alert 扫 system/engineering/governance 三个 api 包。

**重要实现细节**
- 启动类 scanBasePackages 只追加 `com.datanest.common.internal`（不扫整个 common）：扫全包会误装配 `SchedulerClient`（`@Value("${xxl.job.admin.addresses}")` 无默认值，未引 shared-xxljob 的服务会启动失败）。system 此前未扫 common，InternalTokenFilter 因此才补上。
- InternalTokenFilter 只拦截 servlet path 以 `/internal/` 开头的请求，DS 回调 `/dev/internal/**` 不受影响；token 为空放行（本地兜底），配置后经 Nacos `shared-internal.yaml` 下发。
- `QualityJobService` 删除前"被告警规则绑定"校验采用**失败关闭**（alert 服务不可用则禁止删除，抛 BusinessException），避免引用校验被静默跳过。
- 其余 Feign 调用（fire/fireBatch/dag-finished/deleteByObject 级联/超时告警）全部 try-catch 容错只记日志，符合最终一致性语义。
- `dag-finished` 端点内部完成 DAG 告警 + 质量自动触发（经 engineering 解析 dag_node.id → governance auto-trigger），调用方（task-core RemoteDagFinishedListener）只发一个请求。
- app-alert **不暴露宿主机端口**（对外统一走 gateway `/api/alert/**`，容器间 Feign 走 datanest-net:8088；调试 internal 端点需 `docker exec` 进容器或经其它服务发起）。

**遗留（后续阶段处理）**
- ⚠️ app-alert 的 `AlertHistoryMapper.selectHistoryPage` 仍有**跨表 LEFT JOIN**（dag/sync_job/collect_task/quality_job）——同库期间正常，**阶段 5 拆库前必须改为 Feign 反查对象名**。
- `constant/AlertConstants` 在 entity 模块和 app-alert 各有一份副本（entity 版保留因 ScoreCalculator/QualityCheckService/DagService 仍在用），阶段 6 清理。
- 其它服务 compose 里残留的 `depends_on: mailhog` 未清理（无害）。
- 质量批次详情 `alertHistories` 字段类型变为 alert-api 的 AlertHistoryDTO（字段同名，前端 JSON 不变）。

**部署期踩坑（已修复）**
- Feign `lb://` 调用必需 `spring-cloud-starter-loadbalancer`：governance/alert-service 原本就有，engineering/worker/job 缺失导致启动报 `No Feign Client for loadBalancing defined`，已在三个 pom 补齐。后续阶段给服务加 Feign client 时先确认该依赖。
- app-alert 缺 `spring-boot-starter-validation`（common 中是 provided，GlobalExceptionHandler 引用 jakarta.validation 类），启动报 `NoClassDefFoundError: jakarta/validation/ConstraintViolationException`，已补。新服务脚手架时记得带上。

## Next Action

**阶段 1 已完成**。下一阶段（阶段 2：system 域远程化，约 0.5 会话）：
1. system-api 扩充：批量用户查询已有（`/system/internal/users/usernames|emails`），按需补充。
2. `SysUserService` 收回 app-system 内部；engineering/governance 中经 task-core-entity 注入 `SysUserService` 做用户名回填的调用点改 Feign（先 grep `SysUserService` 全部消费点）。
3. 验证：各列表页创建人/更新人名称正常显示。

之后阶段 3（engineering 执行链路远程化，风险最高，重点回归 DAG 条件分支/SUB_DAG/超时告警/对账 handler）→ 阶段 4（governance 域）→ 阶段 5（拆库 + Flyway 基线 + 数据迁移，**先处理 app-alert `selectHistoryPage` 跨表 LEFT JOIN**）→ 阶段 6（删 task-core 四模块、AlertConstants 双份合并、docs/agent/* 文档同步、全量回归）。
