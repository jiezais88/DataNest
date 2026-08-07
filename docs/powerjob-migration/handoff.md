# PowerJob 迁移 Handoff（XXL-JOB + DolphinScheduler → PowerJob 5.1.2）

> **更新时间**：2026-08-07 | **阶段**：P0 ✅ / P1 ✅ / P2 XXL-JOB 替换 ✅ / **P3 DS 替换 ✅（DAG 链路已上线验证）** → 仅剩 P4 切流清理
> **决策（用户已确认）**：① server 用官方镜像；② SchedulerClient 保持接口不变换实现（调用方零改动）；③ 与微服务重构串行（重构已全部完成，库已拆分为 datanest_system/alert/engineering/governance）。

## 0. 目标与范围

把项目两套调度中间件整体收敛到 PowerJob：
- **XXL-JOB**（middleware-xxljob:8088）：12 个 job 组平台定时 handler + 3 个 worker 组执行 handler + sync_job/collect_task/quality_job 动态注册
- **DolphinScheduler**（middleware-ds-* 5 容器 + zookeeper）：DAG 编排执行（HTTP 回调模型）

终态：`middleware-powerjob` 一个容器（7700 控制台/OpenAPI + 10010/10086 通讯），XXL-JOB/DS/ZK 全部下线。

## 1. P0 Spike 结果（2026-08-07，全部通过）

| 验证项 | 结果 |
|---|---|
| powerjob-server 5.1.2 容器 | ✅ healthy（镜像 latest 即 5.1.2，已本地 tag；compose 服务名 `middleware-powerjob`） |
| Boot 4.0.7 + powerjob-worker-spring-boot-starter 5.1.2 | ✅ **运行时验证通过**：autoconfig 装配、AKKA 注册、processor 加载执行全通 |
| Hello job 端到端 | ✅ saveJob（枚举按字符串名传）→ runJob → processor 执行 → fetchInstanceStatus=5(SUCCEED) |
| 依赖冲突 | ✅ 无需 exclusions，Boot 4.0.7 BOM 全部仲裁（含 snakeyaml/HikariCP/okhttp/jackson） |

**已就绪的基础设施**：
- `docker-compose.yml`：`middleware-powerjob`（PARAMS 必须同时覆盖 `spring.datasource.core.*` 和 `oms.storage.dfs.mysql_series.*` 两组数据源，否则启动报 UnknownHostException）
- MySQL `powerjob` 库（15 张表）；两个已注册 App：**data-nest-job id=1 / data-nest-worker id=2**（密码 powerjob123，DB 明文可过校验）
- `shared-configs/shared-powerjob.yaml`（+Nacos 已发布）：通用 worker 配置；app-name 在各服务本地 application.yml（worker 已配 `data-nest-worker`）
- worker 已引 starter + `HelloPowerJobProcessor`（spike 验证用，P2 时可删）
- 迁移脚本已备未执行：engineering/governance 各 `V1.1.0__powerjob_scheduler_columns.sql`（scheduler_job_id + powerjob_* 列，纯 ADD）；7 个实体已加字段
- **spike job 残留**：server 上 jobId=1 `hello-powerjob-spike`（appId=2），P2 清理

**Spike 期踩坑（重要）**：
- **Nacos 3.1.1 直插 config_info 不生效**：读取走内存缓存+磁盘 dump（启动时加载），新配置必须走鉴权发布 API（见 `docs/agent/gotchas.md`）。查可读库，写必须 API。
- OpenAPI `/openApi/assert` 参数是 query param 不是 JSON body。
- saveJob 的枚举字段（timeExpressionType/executeType/processorType）JSON 直接传字符串名（"API"/"STANDALONE"/"BUILT_IN"）。

## 2. 替换映射总表（已核实）

| XXL-JOB / DS | PowerJob | 备注 |
|---|---|---|
| XXL Admin / DS API | powerjob-server :7700 OpenAPI | 全部 POST `/openApi/*` |
| 执行器组 data-nest-job / data-nest-worker | 两个 App（appName 同名） | 已注册 |
| @XxlJob handler ×16 | @Component BasicProcessor Bean | `XxlJobHelper.getJobParam()` → `TaskContext.getJobParams()` |
| SchedulerClient 动态注册 | 保持接口，实现换 powerjob OpenAPI | registerJob→saveJob、start/stop→enable/disable、trigger→runJob、unregister→delete |
| xxl_job_id 三表字段 | scheduler_job_id BIGINT（新列已备） | 旧列切流后清理 |
| DS workflow | saveWorkflow + PEWorkflowDAG（nodes+edges） | |
| DS HTTP 回调 worker | JOB 节点 processorInfo=worker Bean | DagNodeCallbackController 回调废弃 |
| SUB_WORKFLOW | NESTED_WORKFLOW 节点 | |
| DS cron schedule | workflow timeExpressionType=CRON | |
| 重跑失败节点（startNodeList） | retryWfInstance / markWorkflowNodeAsSuccess | 语义需适配 |
| DS 状态码 5/6/7/9 | WfInstanceStatus 3/4/10；InstanceStatus 4/5/9/10 | mapDsState 重写 |
| 自适应 5s 轮询 | FIXED_RATE job | |

**executorParam 三套格式保留**：sync/collect 逗号（`id[,triggerType[,historyId]]`）、quality 冒号（`jobId[:triggerType]` 或 `rule:<ruleId>`）。

## 3. 后续阶段

### P1 基础设施收尾（小部分已做）
- [x] powerjob-server 容器 + 库 + App 注册 + shared-powerjob.yaml + Nacos 发布
- [x] Flyway 脚本（待随 P2 一起部署执行）
- [ ] app-job 接入 starter + `powerjob.worker.app-name: data-nest-worker`→`data-nest-job`（注意 port 冲突：两服务同机部署时 POWERJOB_WORKER_PORT 需区分）

### P2 XXL-JOB 替换（✅ 2026-08-07 完成并上线验证）

**实现要点**：
- common `SchedulerClient` 重写（纯 HTTP RestTemplate 直连 OpenAPI，不引 powerjob-client；方法名不变、jobId Integer→Long）。语义映射：scheduleEnabled=false→API 类型；CRON 任务注册即带 cron、enable 由启停控制；`enableJob/disableJob` 对应 start/stop。appName→appId 走 `/openApi/assert` 反查 + 本地缓存；jobId→appId 走 `queryJob` 反查缓存（重启自愈）。配置键 `datanest.powerjob.server-address/app-password` 均带默认值（alert-service 不装配调度配置也不会炸）。
- 处理器路由（两服务同构）：自定义 `ProcessorFactory`（`tech.powerjob.worker.extension.processor`，挂载点 `PowerJobWorkerConfig.setProcessorFactoryList`，starter 的 autoconfig 有 `@ConditionalOnMissingBean` 可让位），processorInfo 直接解释为 handler 名；未命中返回 null 交内建 factory。param：instanceParams 非空优先否则 jobParams。
- 16 个 handler 全部改 `PlatformJobHandler`（getName/execute）：job 组 13 个（12 平台 + dagExecutionSync 自适应触发保留）+ worker 组 3 个（sync/collect/quality，三套 param 格式原样保留）。`XxlJobConfig`/xxl-job-core 依赖已删。
- JobRegistrar 平移到 `scheduler/JobRegistrar`：`saveOrUpdateCronJob` 按 jobName ensure 13 个平台 CRON 任务。
- 业务调用方全切 `schedulerJobId`（engineering/governance + 两个 api 模块 DTO/端点改名 updateSchedulerJobId）；`singleRuleJobId` 改为以 server 为准按 jobName 收敛复用。
- Flyway V1.1.0（engineering/governance 两库）已应用。

**验收结果（全部通过）**：
- 13 个平台 CRON 任务在 PowerJob server 注册成功（appId=1，status=ENABLE，cron 正确）
- 同步任务手动触发端到端 SUCCESS（6 行）；质量任务手动触发 SUCCESS（MANUAL 批次）；**同步成功后质量 AUTO_TRIGGER 批次自动触发成功**（自动触发链完整）
- 质量任务 schedule/start→server status=1（cron 保留）、schedule/stop→status=2（jobId 保留），与 XXL 语义对齐
- **middleware-xxljob 容器已停止**，全部 7 个服务 healthy——XXL-JOB 运行时已无依赖

**P2 期踩坑**：
- **PowerJob server 不解码 query param**：`runJob` 的 `instanceParams` 若被 URLEncoder 编码（`,`→`%2C`），server 原样传给 processor 导致参数解析失败。`SchedulerClient.postWithQuery` 对 `instanceParams` 特判不编码（内容为内部逗号/冒号格式，无需转义字符）。
- PowerJob `deleteJob` 是软删除（status=99），fetchAllJob 仍列出。
- XXL 的 SERIAL_EXECUTION 语义未映射（PowerJob maxInstanceNum 默认 0=不限），同一任务可能并发实例；现有任务均有自身幂等/锁保护，观察中。
- spike hello job 已删（软删除 status=99）；`HelloPowerJobProcessor` 类已随 worker 迁移删除。

**P2 遗留（不阻塞）**：
- compose 里 4 个应用容器的 `XXL_JOB_HOST/PORT` env + `depends_on: middleware-xxljob`、governance/engineering/job application.yml 的 `shared-xxljob.yaml` import（optional 无害）、`shared-xxljob.yaml` 本身——随 P4 统一清理。
- E2E `helpers/xxl.ts` 未重写（quality-checks/compliance/quality-alerts 三个 spec 依赖，E2E 任务时处理）。
- 存量行 `xxl_job_id` 有值、`scheduler_job_id` 为 NULL → 视为未注册，触发时惰性重新注册（已验证无碍）。

### P3 DS 替换（DAG 链路）（✅ 2026-08-07 完成并上线验证）

**实现形态**：
- common `PowerJobWorkflowClient`：saveNodeJob（支持按 id 更新）/saveWorkflowNode（addWorkflowNode 端点）/saveWorkflow/runWorkflow/fetchWfInstanceInfo/stopWfInstance/retryWfInstance/enable/disable/deleteWorkflow/deleteNodeJob。
- **注册流四步**（DagService.syncToScheduler）：saveNodeJob（回写 dag_node.powerjob_job_id）→ saveWorkflowNode（回写 dag_node.powerjob_node_id，V1.2.0 新列）→ saveWorkflow（PJDag 的 Node.nodeId/Edge 全用 powerjob_node_id）→ 回写 powerjob_workflow_id。被移除节点 deleteNodeJob 清理；游离 workflow_node_info 由 server 自动物理删。
- **worker 5 个节点 handler**（job/dag/ 包，PlatformJobHandler 路由）：dagSql/dagSync/dagPython/dagCondition/dagSubDagAsync。节点上下文：jobParams={dagId,nodeId,nodeType}；dagExecutionId 从 wfContext 读（见坑③），cron 触发缺失时经 Feign `ensureExecution`（engineering 新端点，按 powerjob_wf_instance_id 幂等补齐）。
- **状态同步**（task-core DagExecutionSyncService 重写）：fetchWfInstanceInfo 一次拿全图；节点匹配链 = 快照 nodeId（workflow_node_info.id）→ DagNodeInfo.powerjobNodeId → node_execution；状态映射 5→SUCCESS/4→FAILED/9,10→TERMINATED/其余 RUNNING；wf 终态 3/4/10 后 WAITING→SKIPPED。存量 DS 执行一次性标 FAILED 兜底。
- **trigger/stop/rerunFailed**：runWorkflow(initParams={"dagExecutionId":N}) / stopWfInstance / retryWfInstance（原执行就地续跑，不再新建执行记录；要求 workflow ENABLE）。
- **懒注册**：trigger 时 release_state≠ONLINE **或 powerjobWorkflowId 为空** 都先同步（兼容 DS 时代存量 ONLINE DAG，见坑②）。

**E2E 验收（2026-08-07，全部通过）**：
- 单节点 SQL DAG（告警测试）：懒注册→执行 SUCCESS，wfInstanceId 回写，initParams 直取（无补齐路径调用）
- 条件分支 DAG（E2E-条件节点多前驱）：A/B SQL SUCCESS → 条件C SUCCESS → 命中分支 D SUCCESS、未命中 E SKIPPED → 执行 SUCCESS
- 重跑失败节点（重跑验收 DAG）：同 executionId 就地续跑，失败节点重跑（新时间戳）、成功节点保留未重跑——语义与 DS 的 startNodeList 等价
- 未实测：stop（时序难捕捉，客户端方法已实测）、cron 触发 DAG（ensureExecution 补齐路径已在首轮验证中经过）、子 DAG 同步/异步（库中无 SUB_DAG 类型节点，需补测试夹具后验证）

**P3 期踩坑（已修/已记）**：
- ① **saveWorkflow 强制节点预注册**：dag.nodes[].nodeId 必须先经 `/openApi/addWorkflowNode` 落入 workflow_node_info 表，否则报 `can't find node info by id`；节点记录与 workflow 一经绑定不可复用到其他 workflow。
- ② **存量 DAG 懒注册盲区**：DS 时代 release_state=ONLINE 但 powerjob_workflow_id=NULL，原懒注册条件只看 release_state 导致触发报「未同步」，已修为双条件。
- ③ **wfContext 的 initParams 形态**：server 端 WorkflowInstanceManager 对合法 Map JSON 的 initParams**直接整体作为 wfContext**（dagExecutionId 是顶层 key），只有非 Map 时才包 `{"initParams":...}`——worker 侧两种形态都兼容（先读顶层 dagExecutionId，再回退 initParams key）。
- ④ runWorkflow/fetchWfInstanceInfo 的 query param server 会正常解码（P2 的 instanceParams 问题是 RestTemplate String URL 模板展开双编码）；新 client 统一 URLEncoder 一次 + `postForEntity(URI,...)`。
- ⑤ retryWfInstance 要求 workflow ENABLE（停调度的 DAG 不能重跑失败节点）；stopWfInstance 对已结束实例报 already stopped。
- ⑥ `deleteJob`/`deleteWorkflow` 均为软删（status=99）；workflow_node_info 无 OpenAPI 删除接口，游离节点由 saveWorkflow 时 server 自动清理。

### P4 切流清理（待做）
- 停 middleware-ds-*/zookeeper 容器、compose 清理（含 XXL env/depends_on 残留、ds-* 服务与卷、mysql-connector jar 挂载、init-dolphinscheduler-db.sql/init-xxl-job 脚本挂载）
- 删除 DS 侧死代码：DolphinSchedulerClient/DagDsConverter/DolphinSchedulerConfig/DagNodeCallbackController + worker 的 ensureDagExecution/resolveNodeExecution/getByDsInstance DS 链路 + EngineeringSubDagInternalApi 临时 Feign（需先在 engineering-api 落正式契约）
- shared-dolphinscheduler.yaml / shared-xxljob.yaml 下线（Nacos 删 config_info 行 + 本地文件删除 + 各 application.yml 摘 import）
- 旧列清理脚本：dag.ds_*/dag_node.ds_task_code/dag_execution.ds_process_instance_id/node_execution.ds_task_instance_id/dag_project.ds_project_code + 三表 xxl_job_id
- ErrorCode.DS_API_ERROR 更名或保留（当前 PowerJob 错误也用它）
- 文档全量更新：AGENTS.md（调度/容器/环境速查/已知坑大量 DS/XXL 描述）、docs/agent/architecture.md、README
- E2E：`helpers/xxl.ts` 重写为 PowerJob client；补子 DAG 测试夹具验证 NESTED_WORKFLOW/dagSubDagAsyncHandler

## 4. 与微服务重构的协调（重构已全部完成）

- 库已拆分：datanest_system / datanest_alert / datanest_engineering / datanest_governance（各服务独立 Flyway，`V1.0.0__baseline.sql` + `baselineOnMigrate`，后续脚本从 V1.1.0 起；本迁移的 V1.1.0 两脚本已按此约定应用）。
- 构建用 `mvn clean package -DskipTests -q` 全量或 `mvn -pl <模块> -am package` 源码链构建；本地仓库 `D:\mavenRepository` 的快照已在 P2 构建中刷新。
