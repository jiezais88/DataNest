# DataNest 架构明细

> 本文件是 AGENTS.md 的详细版，供按需查阅。核心摘要见 AGENTS.md §1。
> **微服务化改造（阶段 1-5，2026-08-07 完成）后，本文件已重写**：共享 jar 进程内调用 → OpenFeign 远程调用 + 按域拆 4 库。

## 1. Maven 模块三层目录

根 pom 的 modules 顺序：`data-nest-libs` → `data-nest-apis` → `data-nest-services`。三个目录各有一个聚合 pom（目录名 = 聚合 artifactId，packaging=pom）。

```
data-nest/
├── data-nest-libs/          # 共享库（聚合 artifactId: data-nest-libs）
│   ├── data-nest-common     # 公共组件底座（internal 调用基础设施、Result、异常、工具类）
│   └── data-nest-task-core  # 执行内核（worker 运行的执行器/handler + dto 包）
├── data-nest-apis/          # Feign 契约模块（聚合 artifactId: data-nest-apis）
│   ├── data-nest-alert-api
│   ├── data-nest-engineering-api
│   ├── data-nest-governance-api
│   ├── data-nest-system-api
│   └── data-nest-realtime-api（Sprint 8 F2）
└── data-nest-services/      # 可部署服务（聚合 artifactId: data-nest-services）
    ├── data-nest-gateway
    ├── data-nest-system
    ├── data-nest-alert-service
    ├── data-nest-engineering
    ├── data-nest-governance
    ├── data-nest-worker
    ├── data-nest-job
    └── data-nest-data-service（Sprint 10 F1：SQL 终端 / 数据 API / 实时订阅）
```

**2026-08-12 pom 重构**：`data-nest-service-webmvc` 为"带库 WebMVC 服务"中间父 pom（system/alert/engineering/governance/realtime/data-service 继承，集中 web/nacos/持库/sa-token/springdoc/lombok 公共依赖与分层构建）；`data-nest-apis` 聚合 pom 统管 6 个 Feign 契约模块公共依赖（common/openfeign/resilience4j/feign-hc5/lombok）；根 pom `properties` + `dependencyManagement` 统一管理全部第三方版本（`mysql-connector.version`=8.0.33、`poi.version`=5.4.1、`powerjob.version`=5.1.2、`flink.version`=2.2.1、`cdc.version`=3.6.0-2.2、`flink-shaded-guava.version`=33.4.0-jre-20.0）。**子模块禁止再写第三方字面量版本或本地 properties 版本属性**。

**已删除的模块**（勿再引用）：
- `data-nest-alert`（阶段 1 删除，告警域收拢进 `data-nest-alert-service`）
- `data-nest-task-core-governance`（阶段 4.3 删除，质量编排迁 governance、ConnectionTester/DataPreview 迁 engineering）
- `data-nest-task-core-entity`（阶段 6.1 删除，dto 迁 `data-nest-task-core`、constant 迁 `data-nest-common`；实体/mapper 在此之前已逐域迁到各 owner 服务本地包）

## 2. 服务清单（8 个可部署服务）

| 服务 | 容器 | 端口 | context-path | 职责 | 持有库表 |
|------|------|------|--------------|------|----------|
| `data-nest-gateway` | app-gateway | 8080 | — | 网关入口（WebFlux），前端统一经 `/api/**` 访问 | 无 |
| `data-nest-system` | app-system | 8087 | `/system` | 认证、用户、权限（SysUser 体系已迁入本模块） | datanest_system（5 表） |
| `data-nest-alert-service` | app-alert | 8088 | `/alert` | 告警中心：告警规则/历史、邮件发送、dag_alert_config/history。**不暴露宿主机端口**，对外统一走 gateway `/api/alert/**` | datanest_alert（6 表） |
| `data-nest-realtime` | app-realtime | 8089 | `/realtime` | 实时 CDC（Sprint 8 F2）：CDC 管道 CRUD/启停/监控/日志，Flink CDC YAML 组装经 REST 提交独立 Flink Session 集群（不内嵌 Flink）。**不暴露宿主机端口**，对外走 gateway `/api/realtime/**` | datanest_realtime（3 表） |
| `data-nest-engineering` | app-engineering | 8082 | `/engineering` | 数据工程：数据源、同步任务、DAG 定义与执行实例 | datanest_engineering（13 表） |
| `data-nest-governance` | app-governance | 8084 | `/governance` | 数据治理：元数据、采集、质量（规则/任务/批次/评分）、标准、合规、血缘、资产目录与协作（标签/收藏/关注/评论/热度，Sprint 8 F1） | datanest_governance（25 表） |
| `data-nest-worker` | app-worker | 8085 | `/worker` | Addax 同步/DAG 节点/质量/采集的实际执行方，纯执行节点，回写全部走 Feign | 无库 |
| `data-nest-job` | app-job | 8086 | `/job` | PowerJob worker（App `data-nest-job`），平台定时任务（清理/对账/合规扫描/超时告警等，13 个 CRON 任务） | 无库 |
| `data-nest-data-service` | app-data-service | 8090 | `/data-service` | 数据服务（Sprint 10）：SQL 查询终端（F1：只读查询/超时中断/结果截断/导出/查询历史）、数据 API（F2）、实时订阅（F3）。**不暴露宿主机端口**，对外走 gateway `/api/data-service/**` | datanest_dataservice（6 表：data_api / api_key / api_key_binding / api_key_pipeline / api_call_log / sql_query_history） |

**库表归属**（5 库均在 middleware-postgres 同一实例，各服务独立 Flyway 管理，基线 V1.0.0）：

- **datanest_system（5）**：sys_user、sys_role、sys_permission、sys_user_role、sys_role_permission
- **datanest_alert（6）**：alert_rule、alert_rule_object、alert_rule_user、alert_history、dag_alert_config、dag_alert_history
- **datanest_engineering（13）**：sync_job、sync_job_history、sync_job_log、dag、dag_project、dag_node、dag_edge、dag_parameter、dag_version、dag_execution、node_execution、node_execution_log、datasource_connection
- **datanest_governance（25）**：metadata_table、metadata_column、collect_task、collect_history、collect_execution_log、collect_change_detail、quality_rule、quality_rule_template、quality_job、quality_job_rule、quality_check_batch、quality_check_detail、quality_score、quality_score_config、naming_standard、field_type_standard、compliance_check_result、asset_classification、lineage_record、asset_tag、asset_table_tag、asset_favorite、asset_follow、asset_comment、asset_view_log（Sprint 8 F1 新增 6 表）
- **datanest_realtime（3）**：cdc_pipeline、cdc_pipeline_table、cdc_pipeline_log（Sprint 8 F2 新增库）
- **datanest_dataservice（6）**：data_api、api_key、api_key_binding、api_key_pipeline、api_call_log、sql_query_history（Sprint 10 F1 新增第 6 业务库，F1 已建，F2/F3 表随功能演进复用）

## 3. 服务间调用拓扑

契约模块：`data-nest-apis/` 下 5 个 Feign 契约模块（alert-api / engineering-api / governance-api / system-api / realtime-api），各自包含 @FeignClient 接口 + DTO + fallbackFactory。

| 消费方 | 依赖的 api 模块 | 主要用途 |
|--------|----------------|----------|
| app-alert | system-api、engineering-api、governance-api | 用户邮箱/用户名；对象名称解析与下拉；质量自动触发 |
| app-engineering | alert-api、system-api、governance-api、realtime-api（+ 自用 engineering-api） | 删对象级联告警；创建人名回填；数据源引用检查/级联删除；**删除数据源前 CDC 管道引用校验（fail-closed 8009）** |
| app-governance | alert-api、system-api、engineering-api（+ 自用 governance-api） | 质量任务删除前告警引用校验（fail-closed）；用户名回填；数据源读取 |
| app-realtime | engineering-api | 读源数据源连接信息（含 encryptedPassword，本地解密）；数据源名回填 |
| app-worker | alert-api、system-api、engineering-api、governance-api | 执行回写全链路（同步/DAG/质量/采集/元数据/血缘）+ fire 告警 |
| app-job | alert-api、system-api、engineering-api、governance-api | 清理/对账/合规扫描/超时告警等 handler 全部端点化 |
| app-data-service | system-api、engineering-api、governance-api | SQL 终端：数据源下拉（engineering）、元数据树（governance）、用户信息（system） |
| app-system | 无 | 被其它服务消费，不消费别人 |

说明：
- worker/job 的 engineering-api/governance-api/alert-api 经 `data-nest-task-core` 传递获得（执行内核代码在 task-core，运行在 worker/job 进程内），system-api 在各自 pom 显式声明。
- governance/engineering 启动类把自己的 api 包也扫进 @EnableFeignClients（内部跨子域互调用同一套契约）。
- **实体/mapper 归 owner 服务本地包**（`com.datanest.<域>.entity/mapper`），不再共享；跨服务要数据一律走对应 api 模块的 Feign 端点。

## 4. 内部调用机制（/internal/** + X-Internal-Token）

- 服务间端点统一放在各服务 `/internal/**` 路径下（context-path 之内，如 `/engineering/internal/sync-jobs/{id}/trigger`）。
- **鉴权**：`X-Internal-Token` 头。common 的 `InternalTokenFilter`（服务端校验，仅拦截 servlet path 以 `/internal/` 开头的请求，token 未配置时放行）+ `InternalTokenFeignInterceptor`（Feign 客户端自动带头）。token 经 Nacos `shared-rpc.yaml` 下发。
- **容错体系**（common `internal` 包 + shared-rpc.yaml）：
  - `shared-rpc.yaml`：Feign 全局 connect 2s/read 5s、重试、`feign.circuitbreaker.enabled=true` + Resilience4j default（10 次滑动窗口/50% 失败率熔断/30s 半开）。
  - `InternalFeignErrorDecoder`：远端 Result 信封 message 提取；503 → RetryableException 触发重试。
  - `InternalFeignRetryer`：Retryer.Default(100ms, 1s, 3)。
  - `RemoteCalls.execute(description, supplier, fallback)`：统一降级入口 + warn 日志 + `remote_call_failed_total{target}` 计数。
  - 每个 @FeignClient 配 `fallbackFactory`（各 api 模块 fallback 包）；读路径降级空集合，写路径按语义降级或 fail-closed。
  - `FeignContextWarmup`：启动期逐 Feign client 子上下文强制初始化 `FeignHttpMessageConverters`（规避 spring-cloud-openfeign 上游并发缺陷 issue #1307，见 gotchas.md）。
- **fail-closed 例外**（降级为空会绕过校验的场景，远端不可用即拒绝操作）：`QualityJobService` 删除前告警引用校验、`AssetCatalogService.assignOwner` 用户存在性校验、`AlertRuleService` 保存规则时对象名解析。
- **语义红线**：执行开始处 fail-fast（登记失败则任务失败，不跑无登记执行）；执行结束处降级 + job 对账兜底（最终一致性，无分布式事务、无 MQ）。

## 5. 调度链路

**唯一调度中间件：PowerJob 5.1.2**（2026-08-07 起，原 XXL-JOB + DolphinScheduler 已全部下线）。server 容器 `middleware-powerjob`（控制台/OpenAPI :7700，DB 在 middleware-mysql 的 `powerjob` 库）。

- **client 在 common**：`SchedulerClient`（job 级：saveJob/enable/disable/runJob/deleteJob）+ `PowerJobWorkflowClient`（workflow 级：saveNodeJob/saveWorkflowNode/saveWorkflow/runWorkflow/fetchWfInstanceInfo/stopWfInstance/retryWfInstance 等），均为纯 HTTP 直连 OpenAPI（不引 powerjob-client）。配置键 `datanest.powerjob.server-address/app-password` 均带默认值。仅需要调度的服务才装配（启动类 scanBasePackages 只追加 `com.datanest.common.internal`，勿扫整个 common）。
- **两个 App**：`data-nest-job`（id=1，app-job 承载 13 个平台定时 handler，启动时 `scheduler/JobRegistrar` 按 handler 名 ensure 注册 CRON 任务）/ `data-nest-worker`（id=2，app-worker 承载 sync/collect/quality 3 个业务执行 handler + `job/dag/` 包 5 个 DAG 节点 handler：dagSql/dagSync/dagPython/dagCondition/dagSubDagAsync）。
- **处理器路由（两服务同构）**：16 个 handler 统一实现 `PlatformJobHandler`（getName/execute）；各服务自定义 `ProcessorFactory` 把 PowerJob 的 processorInfo 直接解释为 handler 名路由到同名 Spring Bean，未命中交内建 factory。
- **DAG（原 DS 链路）**：`DagService.syncToScheduler` 四步注册（saveNodeJob 回写 `dag_node.powerjob_job_id` → saveWorkflowNode 回写 `powerjob_node_id` → saveWorkflow → 回写 `dag.powerjob_workflow_id`）；触发 `runWorkflow(initParams={"dagExecutionId":N})`；状态同步由 task-core `DagExecutionSyncService` 走 `fetchWfInstanceInfo` 快照（节点经 `dag_node.powerjob_node_id` 桥接匹配）；重跑失败节点 `retryWfInstance` 就地续跑。原 DS HTTP 回调（`/dev/internal/**` + DagNodeCallbackController）已废弃。
- 质量任务定时 = 每任务独立注册 PowerJob；同步任务触发走 `POST /internal/sync-jobs/{id}/trigger`（SyncJobTriggerService 在 engineering）。

## 6. 后端包结构

每个业务服务统一扁平按层划分：

```
com.datanest.<域>
├── <域>Application.java      # @SpringBootApplication + @EnableFeignClients(+ @MapperScan，仅持库服务)
├── config/                   # MybatisPlusConfig、FlywayConfig（持库服务）等
├── controller/               # REST API 入口（含 /internal/** 端点）
├── dto/                      # Request / Response / Query DTO
├── service/                  # 业务逻辑
├── entity/                   # 本域实体（owner 本地，不共享）
└── mapper/                   # 本域 Mapper 接口（owner 本地，不共享）
```

不要引入 `dag/`、`dev/`、`sync/` 等子包，否则影响 MyBatis Mapper 扫描和依赖方引用。

`data-nest-common` 只放跨服务共享内容：

```
com.datanest.common
├── config/GlobalExceptionHandler.java   # 统一异常处理（MVC 专属，WebFlux 网关不注册）
├── constant/                            # AlertConstants/QualityScoreConstants（阶段 6.1 迁入）
├── dto/                                 # 少量公共 DTO
├── exception/                           # BusinessException、ErrorCode
├── internal/                            # 服务间调用基础设施（见 §4）
├── jackson/JacksonConfig.java           # Long 转 String 序列化
├── model/                               # Result、PageResult、LoginRequest
├── satoken/                             # Sa-Token 公共自动配置
├── scheduler/                           # SchedulerClient + PowerJobWorkflowClient（PowerJob OpenAPI 直连）
└── util/                                # 公共工具类
```

**2026-08-12 新增共享组件（common）**：`FlywayAutoConfiguration`（持库服务统一迁移）、`MybatisPlusInterceptorAutoConfiguration`（从 task-core 迁入，分页+乐观锁兜底）、`DorisConstants`（内置 Doris 数据源 ID=-1L 与展示名统一）、`JdbcUrlBuilder`（带 socketTimeout 重载）、`JdbcPreviewHelper.formatValue/classifyError` 公开。**禁止回归自建**。

`data-nest-task-core` 是执行内核：`SyncJobExecutorService`/`CollectExecutor`/`GenericSqlExecutor`/`QualityCheckService`/`DagExecutionSyncService` 等执行侧代码 + `dto` 包（阶段 6.1 迁入）。该 lib 被 worker/job 依赖（代码在其进程内运行），自身不持库、不独立部署。

**2026-08-12 新增（task-core）**：`SystemUserResolver`（用户名批量反查，engineering/governance 统一委托）、`SqlStatementSplitter.classify`（SQL 四分类，worker/engineering/GenericSqlExecutor 统一）。**禁止回归自建**。

## 7. 核心容器

| 容器 | 说明 |
|------|------|
| `app-gateway` / `app-system` / `app-alert` / `app-realtime` / `app-engineering` / `app-governance` / `app-worker` / `app-job` / `app-data-service` | 9 个后端服务（对应 §2） |
| `app-frontend` | 前端 |
| `middleware-mysql` | MySQL：Nacos、PowerJob 元数据 |
| `middleware-postgres` | PostgreSQL：5 个业务库（datanest_system/alert/engineering/governance/realtime） |
| `middleware-nacos` | Nacos（配置 + 服务发现） |
| `middleware-powerjob` | PowerJob server 5.1.2（唯一调度中间件，控制台/OpenAPI :7700） |
| `middleware-redis` | Redis（Sa-Token 集中式会话） |
| `middleware-mailhog` | 本地邮件捕获（仅 app-alert 发信） |
| `middleware-minio` | MinIO 对象存储（Iceberg 湖仓 + savepoint，S3 :9000 / Console :9001，Sprint 8 F2） |
| `middleware-flink-jobmanager` / `middleware-flink-taskmanager` | 独立 Flink 2.2.1 Session 集群（自定义镜像 `datanest-flink:2.2.1`，JM REST 宿主 18081，Sprint 8 F2） |

> 旧单库 `datanest` 已冻结写入，保留只读观察一个迭代后下线。
