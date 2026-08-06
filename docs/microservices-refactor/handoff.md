# 微服务化改造 Handoff — 阶段 1：告警域远程化 + 新建 app-alert

> 独立改造（不属于任何 Sprint）。总目标：共享 jar 进程内调用 → OpenFeign 远程调用 + 按域拆库。
> 已确认决策：OpenFeign + Nacos；按域拆库（阶段 5 才拆）；最终一致性（Feign + 重试 + 对账，无分布式事务无 MQ）；新建 app-alert 独立告警服务；worker/job 最终不持库（纯执行节点，经 Feign 回写 owner）。

## 阶段 2 范围（system 域远程化，2026-08-06 完成）

- `SysUserService`（entity 模块）的进程内消费全部改 Feign：engineering 5 个 Service、governance 5 个 Service、task-core-governance 3 个 Service（QualityRule/QualityJob/QualityRuleTemplate）。
- system-api 扩充：`GET /system/internal/users/ids-by-name-keyword?keyword=`（资产搜索负责人维度）；原有的 `usernames`/`emails` 批量端点复用。
- 降级语义：usernames Feign 失败 → warn + 空 Map（列表页名称列为空/「-」，接口不 500）；负责人名搜索失败 → 空列表；assignOwner 存在性校验为 fail-closed（system 不可用时拒绝写）。
- **N+1 修复（阶段 1 遗留）**：dag-finished 质量自动触发原逐节点调 `findDagNodeId`（N 次）+ 逐节点 `qualityAutoTrigger`（M 次）→ 改为批量端点：engineering `POST /engineering/internal/dags/{dagId}/nodes/resolve`（一次拿 nodeId→dagNodeId 映射）+ governance `POST /governance/internal/quality/auto-trigger/batch`；单条 auto-trigger 端点与 findDagNodeId 已删除。
- 消费方 13 个 Service 各自持有私有 `usernames()` helper（项目偏好简单重复，不在 common 抽象）。
- SysUserService/SysUserMapper/SysUser 实体保留在 entity 模块（仅 system 使用），entity 清退属阶段 6。

## 阶段 2 验证记录

- 全量 `mvn clean package` exit 0；grep 无 SysUserService/SysUserMapper 残留（system 模块除外）
- 6 个重建服务（system/engineering/governance/worker/job/alert）全部 healthy，日志无 Feign 异常
- engineering `/api/engineering/sync-jobs/page`：`createdByName=admin` ✅（经 Feign 调 system usernames）
- 质量任务 `/api/governance/quality/jobs/page`：`createdByName=admin` ✅（task-core-governance 的 QualityJobService 远程回填）
- 资产搜索 `/api/governance/assets/search?keyword=admin`：命中负责人维度（ownerName=admin）✅（ids-by-name-keyword + ownerName 回填）
- 未 e2e 项：dag-finished 批量质量触发链路（resolveDagNodeIds/auto-trigger/batch）只做了代码级验证，待下次 DAG 真实执行时观察 app-alert 日志（应只有 2 次远程调用，无逐节点循环）

## 结构整改：模块三层目录 + 容错体系（2026-08-06 完成，用户决策）

### Maven 模块三层目录（data-nest-libs / data-nest-apis / data-nest-services）

- `data-nest-libs/`：data-nest-common、data-nest-task-core-entity、data-nest-task-core-governance、data-nest-task-core（阶段 6 后只剩 common）
- `data-nest-apis/`：4 个 Feign 契约模块
- `data-nest-services/`：7 个可部署服务（gateway/system/alert-service/engineering/governance/worker/job）
- 三个目录各有聚合 pom（**目录名 = 聚合 artifactId**：data-nest-libs/apis/services，packaging=pom）；根 pom modules = data-nest-libs → data-nest-apis → data-nest-services
- **artifactId 不变，依赖零改动**；仅 pom relativePath（../../pom.xml）与 7 个 Dockerfile 的 jar COPY 路径（加 services/ 前缀）调整
- 全部 `git mv` 完成（484 项 rename，历史保留）；全量构建 + compose config + app-system 镜像构建验证通过
- ⚠️ 后续引用模块物理路径时注意新位置（AGENTS.md §3 命令、docs 已同步）

### 远程调用容错体系（L1 统一设施 + L2 熔断）

**L1**：
- `shared-feign.yaml`（已推送 Nacos，消费方 alert/engineering/governance/worker/job 已 import）：Feign 全局 connect 2s/read 5s、loggerLevel basic、`feign.circuitbreaker.enabled=true`、resilience4j default 配置（10 次滑动窗口/5 次最小调用/50% 失败率熔断/30s 半开）
- common `InternalFeignErrorDecoder`：远端 Result 信封 message 提取；503→RetryableException 触发重试；其它→BusinessException("远程调用失败[svc path]: msg")
- common `InternalFeignRetryer`：Retryer.Default(100ms, 1s, 3)，全 client 生效
- common `RemoteCalls.execute(description, supplier, fallback)`：统一降级入口 + warn 日志 + Micrometer `remote_call_failed_total{target}` 计数；已替换 25 处手写 try-catch 样板

**L2**：4 个 @FeignClient 全部配 `fallbackFactory`（各 api 模块 fallback 包，@Component；消费方启动类 scanBasePackages 追加对应 api 包）。降级语义：读路径空集合/空 Map；fire→false；**`listRuleNamesByObject` 抛 BusinessException（fail-closed，QualityJobService 删除前置校验）**；`AssetCatalogService.assignOwner` 不包装（空 Map → 抛用户不存在，fail-closed）。resilience4j starter 由 spring-cloud BOM 管理（5.0.2），不要在根 pom 显式声明无版本条目（会报 version missing）。

**fail-closed 清单（改动时注意保持）**：QualityJobService 删除前告警引用校验、AssetCatalogService.assignOwner 用户存在性校验。

**验证记录（全部实测通过）**：
- 7 后端服务重建后全部 healthy；正常路径（sync-jobs 用户名回填、alert-rules 分页）✅
- 故障注入 1：`docker stop app-alert` → 同步任务执行 SUCCESS 不受影响；worker 日志 `远程调用失败，按降级处理: target=alert.fire`（Retryer 重试 3 次后快速降级，无线程挂起）✅
- 故障注入 2：`docker stop app-system` → sync-jobs page 仍 200，名称列降级为「-」✅
- 恢复：两服务重启后用户名回填/告警 fire 自动恢复（alert_history 正常落库）✅；测试规则与测试告警历史已清理

### 降级副作用两项修复（2026-08-06，用户评审发现）

**(c) 规则保存持久化污染 → fail-closed（app-alert）**：
- `AlertRuleService` 新增 `resolveObjectNamesForSave`：`createRule`/`updateRule`/`saveRuleObjects` 保存路径上，objectIds 非空而名称解析为空（远端宕机或对象不存在，不区分）→ 抛 BusinessException「对象服务不可用或对象不存在，请稍后重试」，事务回滚，空 object_name 不再落库。
- 双道守卫（updateRule + saveRuleObjects 各自独立 Feign 调用处）；`updateRule` 用 `effectiveType`（dto 缺省回退原类型）避免空类型误抛/误写。
- fire/展示路径保持降级语义不变。
- **实测**：停 app-engineering 保存规则 → 被拒且 0 落库；恢复后保存成功；测试数据已清理 ✅

**(b) 质量自动触发丢失 → 对账补发（app-job）**：
- 新增 `QualityAutoTriggerReconcileHandler`（cron `0 0/10 * * * ?`，JobRegistrar 已注册，可配 `datanest.job.quality-auto-trigger-reconcile.cron`）。
- 逻辑：扫描近 2h 内 SUCCESS 的 dag_execution（排除最近 5 分钟在途）→ 成功节点批量解析 dag_node.id → 查绑定的启用质量任务（DAG_NODE）→ 缺 `trigger_type='AUTO_TRIGGER'` 批次的判定漏触发 → `qualityAutoTriggerBatch` 补发（RemoteCalls 容错，失败下轮再补，幂等）。
- 全部批量查询无逐条循环；每轮补发上限 50。
- ⚠️ 该 handler 直接读 dag_execution/node_execution/quality_job/quality_check_batch 表——阶段 3/4 拆表归属后需改为经 engineering-api/governance-api 读取（记入阶段 3 改造清单）。

## 总体阶段规划

1. **阶段 1（已完成 ✅）**：新建 app-alert + 告警域远程化
2. **阶段 2（已完成 ✅）**：system 域远程化（SysUserService → Feign）+ dag-finished N+1 批量化修复
3. 阶段 3：engineering 域远程化（worker/job 执行回写链路，风险最高）
4. 阶段 4：governance 域远程化（质量/采集/元数据/血缘回写）
5. 阶段 5：拆库 + Flyway 基线 + 数据迁移（datanest_system / datanest_alert / datanest_engineering / datanest_governance）
6. 阶段 6：删除 task-core 剩余共享模块、文档收尾、全量回归

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

**阶段 2 已完成**。下一阶段（阶段 3：engineering 域远程化，风险最高，约 3-4 会话）：
1. 新建 engineering-api 扩充：执行回写端点（sync_job_history/log、dag_execution/node_execution 读写、datasource 读取、sync_job/dag 定义读取）。
2. entity 模块中 engineering 归属表（同步 + DAG 全族 + datasource_connection）实体/mapper 迁入 engineering，包名改 com.datanest.engineering.*。
3. worker 的 SyncJobExecutor/DagNodeExecuteService、job 的 DagExecutionSyncService/SyncJobRetryService/StuckExecutionReaperService 改 Feign 回写。
4. 重点回归：同步任务执行、DAG 全流程（条件分支/SUB_DAG/超时告警/对账 handler）、数据源 CRUD。
5. **别忘了**：`QualityAutoTriggerReconcileHandler`（app-job）直接读 dag_execution/node_execution/quality_job/quality_check_batch 四表，阶段 3/4 需随表归属改为经 engineering-api/governance-api 读取。

阶段 4（governance 域）→ 阶段 5（拆库，**先处理 app-alert `selectHistoryPage` 跨表 LEFT JOIN**）→ 阶段 6（删 task-core 剩余模块、AlertConstants 双份合并、docs/agent/* 同步、全量回归）。
