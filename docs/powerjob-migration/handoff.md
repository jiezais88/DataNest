# PowerJob 迁移 Handoff（XXL-JOB + DolphinScheduler → PowerJob 5.1.2）

> **更新时间**：2026-08-07 | **阶段**：P0 Spike ✅ / P1 ✅ / **P2 XXL-JOB 替换 ✅（已上线验证）** → 待启动 P3（DS 替换）
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

### P3 DS 替换（DAG 链路）
- DagPowerJobConverter（节点→saveJob API 类型 + saveWorkflow PEWorkflowDAG）、worker 4 个节点 processor（调 DagNodeExecuteService 现有 handle*）、DagExecutionSyncService fetcher 换 fetchWfInstanceInfo、子 DAG NESTED_WORKFLOW、resolveExecution 补齐逻辑重写

### P4 切流清理
- 停 middleware-ds-*/xxljob/zookeeper 容器、compose 清理（含 XXL env/depends_on 残留）、shared-xxljob.yaml 下线、ds_*/xxl_job_id 旧列清理脚本、文档全量更新（AGENTS.md/docs/agent 大量 DS/XXL 描述）

## 4. 与微服务重构的协调（重构已全部完成）

- 库已拆分：datanest_system / datanest_alert / datanest_engineering / datanest_governance（各服务独立 Flyway，`V1.0.0__baseline.sql` + `baselineOnMigrate`，后续脚本从 V1.1.0 起；本迁移的 V1.1.0 两脚本已按此约定应用）。
- 构建用 `mvn clean package -DskipTests -q` 全量或 `mvn -pl <模块> -am package` 源码链构建；本地仓库 `D:\mavenRepository` 的快照已在 P2 构建中刷新。
