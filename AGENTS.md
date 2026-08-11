# DataNest Agent 工作约定

> 本文件面向 AI Agent，用于跨会话恢复项目上下文。人类开发者也可查阅。
> **结构说明**：本文件只保留概览、会话约定、构建规则、验证规范、环境速查和精简版已知坑。详细的架构说明、后端/前端编码规范、完整已知坑与 E2E 测试细节分别拆在 `docs/agent/` 下，按需查阅（见 §0 索引）。

## 0. Agent 文档索引

| 文件 | 内容 | 何时查阅 |
|------|------|----------|
| `docs/agent/architecture.md` | 模块/容器/包结构/task-core 拆分依赖关系 | 需要理解模块边界、依赖方向、改共享模块时 |
| `docs/agent/conventions-backend.md` | 后端技术栈、响应协议、异常、实体/Mapper/Flyway、Controller/URL、接口文档注解、Nacos | 写后端代码时 |
| `docs/agent/conventions-frontend.md` | 前端技术栈、目录结构、API/错误/状态/路由/样式规范 | 写前端代码时 |
| `docs/agent/gotchas.md` | 已知坑完整版 + 已解决坑 + E2E 测试细节 | 排查问题、写/改测试时 |
| `docs/agent/prototype-guide.md` | UI 原型高保真制作规范 + 真实 token/组件速查表 | 做静态高保真原型时 |

## 1. 项目概览

DataNest 是一个数据平台，技术栈如下：

- **后端**：Java 25 + Spring Boot 4.x，Maven 多模块（**三层目录**：`data-nest-libs/` 共享库、`data-nest-apis/` Feign 契约、`data-nest-services/` 可部署服务，目录名与聚合 artifactId 一致）
- **前端**：独立容器 `app-frontend`（源码目录 `data-nest/data-nest-frontend`），通过 `app-gateway:8080` 统一入口
- **部署**：Docker Compose，所有服务在同一 `datanest-net` 网络
- **配置中心**：Nacos，配置实际存储在 `middleware-mysql` 的 `nacos.config_info` 表
- **调度**：PowerJob 5.1.2（官方镜像，容器 `middleware-powerjob`，控制台/OpenAPI http://localhost:7700，DB 为 MySQL `powerjob` 库）。两个 App：`data-nest-job`（id=1，平台定时任务）/ `data-nest-worker`（id=2，业务任务与 DAG 节点执行）。worker 通信协议 HTTP、store-strategy=memory、max-result-length=32768（`shared-powerjob.yaml`，2026-08-08 调优）。原 XXL-JOB / DolphinScheduler / Zookeeper 已随迁移（2026-08-07）全部下线
- **目标数仓**：内置 Doris（**4.1.3**，2026-08-10 由 4.0.7-rc02 升级，裸机单节点 1FE+1BE，`/usr/local/apache-doris-4.0.7` 目录原位替换，systemd `doris-fe`/`doris-be` 守护，数据在 `/data/doris/`；当前在 `192.168.119.135:9030`）
- **业务库（按域拆 5 库）**：`datanest_system` / `datanest_alert` / `datanest_engineering` / `datanest_governance` / `datanest_realtime`（Sprint 8 F2 新增，CDC 管道），均在 middleware-postgres 同实例；**worker/job 无库**（纯执行/调度节点，已排除 DataSource 自动配置）。旧 `datanest` 库只读观察后下线。各服务 Flyway 独立管理本库（见 §6）

### 核心模块

> **task-core 历史拆分**：曾拆为 4 模块（entity/alert/task-core-governance/task-core），微服务化后 alert 独立为 app-alert、task-core-governance 已删、entity 只剩 dto+constant。消费方**只显式依赖 `data-nest-task-core`**。详见 `docs/agent/architecture.md`。
>
> **微服务化改造（2026-08-06 起，已全部完成）**：共享 jar 进程内调用 → OpenFeign + Nacos + 按域拆 4 库（最终一致，无分布式事务无 MQ）。全程记录见 `docs/microservices-refactor/handoff.md`。

| 模块 | 说明 |
|------|------|
| `data-nest-common` | 公共组件（SchedulerClient/PowerJobWorkflowClient（PowerJob OpenAPI 直连）、InternalTokenFilter/Feign 拦截器等），最底层底座 |
| `data-nest-task-core` | 执行内核（SyncJobExecutorService/QualityCheckService/CollectExecutor 等 + 共享 dto 包；全部 DB 访问经 Feign） |
| `data-nest-alert-api` | app-alert 的 Feign 契约（AlertApi + DTO）。worker/job/engineering/governance 依赖 |
| `data-nest-realtime-api` | app-realtime 的 Feign 契约（CdcPipelineApi + DTO，engineering 删除数据源校验依赖，fail-closed） |
| `data-nest-system-api` / `data-nest-engineering-api` / `data-nest-governance-api` | 各服务 Feign 契约（内部端点 + DTO + fallbackFactory） |
| `data-nest-alert-service` | **独立告警服务**（app-alert，com.datanest.alert.*）：告警规则/历史/触发/邮件 + dag_alert_config/history |
| `data-nest-realtime` | **实时 CDC 服务**（app-realtime，com.datanest.realtime.*，Sprint 8 F2）：CDC 管道 CRUD/启停/监控/日志 + Flink YAML 组装经 REST 提交独立 Flink 集群；持 `datanest_realtime` 库 |
| `data-nest-engineering` | 数据工程服务（同步任务 API、DAG API；13 表本地持有） |
| `data-nest-worker` | Addax 实际执行方（**无任何业务库**，全部经 Feign 回写） |
| `data-nest-governance` | 数据治理服务（元数据、数据标准、质量编排/评分；19 表本地持有） |
| `data-nest-job` | PowerJob worker（App `data-nest-job`），平台定时任务（**无任何业务库**，handler 全部端点化） |
| `data-nest-system` | 认证、用户、权限（SysUser 体系本地持有） |
| `data-nest-gateway` | 网关入口 |

> **服务间调用规则**：跨服务调用一律走 `*-api` 模块的 Feign client（`/internal/**` 端点，`X-Internal-Token` 头鉴权），禁止跨服务共享 Service/Mapper 进程内调用。**容错三件套已内置**：`shared-rpc.yaml` 超时/重试 + Resilience4j 熔断（各 client 配 fallbackFactory）+ 内部令牌；消费方降级统一用 common `RemoteCalls.execute(描述, 调用, 降级值)`，读路径降级空集合；**fail-closed 例外**：删除前置校验类调用必须让异常传播。
> **禁止逐条循环远程调用（N+1）**：循环场景必须提供批量端点（如 `usernames?ids=`、`dags/{dagId}/nodes/resolve`）。
> **Feign 契约的查询/路径参数禁止用 LocalDateTime**（会被按 locale 格式化导致服务端解析失败）——一律 ISO String；请求体里的 LocalDateTime 走 Jackson 不受影响。
> **用户名回填**：`SysUserService` 仅 app-system 内部用；其它服务一律经 `SystemUserApi.usernames`（批量，失败降级空 Map）。

### 核心容器

| 容器 | 说明 |
|------|------|
| `app-engineering` / `app-worker` / `app-governance` / `app-job` / `app-system` / `app-gateway` | 对应六个后端服务 |
| `app-alert` | 独立告警服务（容器端口 8088，**不暴露宿主机端口**：对外走 gateway `/api/alert/**`，容器间 Feign 走 datanest-net） |
| `app-realtime` | 实时 CDC 服务（容器端口 8089，**不暴露宿主机端口**：对外走 gateway `/api/realtime/**`） |
| `middleware-mysql` | MySQL：Nacos、PowerJob |
| `middleware-postgres` | PostgreSQL：业务主库 |
| `middleware-nacos` / `middleware-powerjob` / `middleware-redis` | Nacos / PowerJob server（调度，含 DAG 工作流）/ Redis |
| `middleware-minio` | MinIO 对象存储（Iceberg 湖仓数据/元数据 + savepoint，S3 9000 / Console 9001） |
| `middleware-flink-jobmanager` / `middleware-flink-taskmanager` | 独立 Flink 2.2.1 Session 集群（自定义镜像 `datanest-flink:2.2.1`，JM REST 宿主 18081→容器 8081） |

## 2. 会话约定

- **一个会话一个目标**。避免把技术选型、闲聊、无关 Bug 修复混进主线。
- 回复和说明使用 **中文**；代码注释/提交信息跟随项目现有风格（中文为主）。
- 跨会话恢复上下文时，先读 `docs/sprint<编号>/handoff/sprint-<编号>.md`；如不存在，请用户简述当前目标。
- 每个 Sprint 建议 2~4 个会话：规划/设计、后端实现、前端联调、验证收尾。
- 不要主动运行 `git commit` / `git push`，除非用户明确要求。

### 编码前约定

- **先读代码再动手**。修改代码前必须通过 `Read`/`Grep` 读透相关文件和调用链，不要凭记忆或猜测；特别是 `data-nest-task-core` 的改动，要确认 engineering、worker 等所有消费方。
- **改接口必须同步前端/文档**。修改 DTO、返回结构、URL 路径、字段含义时，必须同步检查前端调用点和接口文档，避免前后端不一致。

### 文档同步约定

- **全局 `AGENTS.md`**：当项目架构、环境信息、已知坑、构建规则发生变化时更新。判断标准：这个变更如果下个会话不知道，可能会踩坑或做错决策。更新到 `AGENTS.md` 或 `docs/agent/` 下的对应子文档，避免 AGENTS.md 无限膨胀。
- **适时自动优化（2026-08-07 起）**：Agent 在工作中发现 `AGENTS.md` 及 `docs/agent/` 内容有过时、缺失、重复或结构不合理时，**应主动顺手优化**（修正错误表述、补充已验证的新结论、精简冗余、把膨胀内容下沉到子文档），不必等用户明确要求；但**涉及删除有效信息、改变约定含义、或Agent 自己不确定的改动，必须先和用户沟通确认后再改**。
- **Sprint Handoff 文档**：每个子会话结束时更新当前 Sprint 的状态看板、Blocker、变更清单、Next Action。
- **Sprint 配套文档**：一个 Sprint 通常包含技术文档、产品文档、UI 原型。开发过程中如果对需求、接口、字段、页面交互做了微调，必须同步回落到对应文档，保持"代码实现 = 文档描述"。
- **代码与文档不一致时必须询问**：当 Agent 发现当前实现和已有文档、原型存在偏差时，**必须暂停并询问用户**"这是有意的临时调整，还是需要同步更新文档？"，不要擅自替用户决定。
- 不必更新的情况：纯临时调试命令、一次性验证、很快被覆盖的小尝试。

### 问题排查约定

- 遇到报错或不确定的问题时，优先检查日志、配置、数据库状态、容器健康度。
- 如果项目内无法快速定位根因，**先加载 `systematic-debugging` 技能，按四阶段法（根因调查 → 模式分析 → 假设验证 → 实现修复）排查**，禁止未定位根因就尝试修复。
- 在根因调查阶段，**应主动使用 WebSearch** 搜索相关错误信息、框架版本兼容性、最佳实践，而不是凭经验猜测。
- 搜索后把关键结论（来源 URL + 核心判断）记录到当前会话或 Sprint Handoff 中，避免后续重复搜索。
- 排查时优先查 `docs/agent/gotchas.md`，确认是否已有已知坑记录。

## 3. 构建与部署规则

### 关键原则

- **task-core 是共享执行内核**（原拆分模块 entity/task-core-governance 已删除），是 engineering、governance、worker、job、system 的 **共享底座**（原第 4 个模块 alert 已独立为 app-alert 服务）。
- 消费方只显式依赖 `data-nest-task-core`；告警调用另依赖 `data-nest-alert-api`（Feign 契约）。
- **构建顺序**：Maven 按 `<modules>` 声明顺序构建，顺序为 `common → *-api → task-core → 各服务`（已在根 pom 配置）。
- **只要改到 `data-nest-task-core`（含任一拆分模块），必须同时重新编译并部署所有消费方**（至少 engineering 和 worker；若涉及治理/质量还需 governance/job/system），否则执行节点还是旧代码。命令见下。

### 常用命令

```bash
cd data-nest
# 全量构建
mvn clean package -DskipTests -q
# 只构建 task-core 及主要消费方（engineering/worker）
mvn -pl data-nest-task-core,data-nest-engineering,data-nest-worker -am clean package -DskipTests -q
docker compose build app-engineering app-worker
docker compose up -d --no-deps app-engineering app-worker
```

> `-am`（also make）会自动把 `task-core` 依赖的 common 及各 api 模块一并构建。

### 注意

- 构建后检查镜像时间戳，确认用了新 jar（遇到过 buildkit 缓存未更新的情况）。
- **前端部署必须两步**：`app-frontend` 的 Dockerfile 只 `COPY dist/`（不在镜像内构建），改前端代码后必须先本地 `pnpm build` 再 `docker compose build app-frontend && up -d`，否则镜像里是旧产物。
- **前端顶级路由不得与静态目录 `assets/` 同名**（nginx `location /assets/` 是 Vite 产物长缓存目录）：Sprint 7 资产目录路由因此用 `/asset-catalog`。新增顶级路由前先对照 `data-nest-frontend/nginx.conf`。
- 只改动单一服务时，只重建该服务即可，不必全部重启。
- worker 镜像基于 `wgzhao/addax:6.0.11` 多阶段构建，首次构建会下载 Addax 二进制。

## 4. 验证规范

### 不要只在编译成功就报完成

功能改动必须做回归验证。

### 同步任务验证路径

1. 登录拿 token：
   ```bash
   curl -s -X POST http://localhost:8080/api/system/auth/login \
     -H 'Content-Type: application/json' \
     -d '{"username":"admin","password":"admin123"}'
   ```
2. 手动触发：
   ```bash
   curl -s -X POST "http://localhost:8080/api/engineering/sync-jobs/{sync_job_id}/execute" \
     -H "Authorization: $TOKEN"
   ```
3. 查历史：
   ```sql
   SELECT id, status, error_message, source_rows, target_rows, start_time, end_time
   FROM sync_job_history
   WHERE sync_job_id = {sync_job_id}
   ORDER BY start_time DESC;
   ```
4. 必要时查 Doris 目标表确认数据落地。

### 采集任务验证路径

- 查 `collect_history`
- 查 `collect_execution_log`
- 查 `collect_change_detail`（变更明细）

## 5. 环境速查

| 资源 | 用途 | 地址/命令 | 账号/密码 |
|------|------|-----------|-----------|
| 网关入口 | 所有 API 统一入口 | http://localhost:8080 | - |
| 接口文档聚合页 | springdoc swagger-ui（右上角下拉切服务） | http://localhost:8080/swagger-ui.html | 匿名可读，调试需配 Authorization 头 |
| admin 登录 | 获取全局 token | `POST /api/system/auth/login` | admin / admin123 |
| PostgreSQL 业务库 | 按域 4 库：`datanest_system` / `datanest_alert` / `datanest_engineering` / `datanest_governance` | `docker exec -it datanest-middleware-postgres psql -U datanest -d <库名>` | datanest / datanest123（旧 datanest 库只读观察，勿写） |
| MySQL root | 管理 MySQL 所有库 | `docker exec -it datanest-middleware-mysql mysql -u root -proot123` | root / root123 |
| MySQL nacos | 查 Nacos 配置、业务库 | `docker exec -it datanest-middleware-mysql mysql -u nacos -pnacos123` | nacos / nacos123 |
| Nacos 配置库 | 存储所有 shared-configs | `nacos.config_info` 表（在 middleware-mysql） | - |
| PowerJob 控制台/OpenAPI | 调度任务管理（含 DAG 工作流） | http://localhost:7700 | App 密码 `powerjob123`（App：`data-nest-job` id=1 / `data-nest-worker` id=2；DB 为 MySQL `powerjob` 库） |
| Flink Web UI / REST | CDC 作业观测（独立 Session 集群） | http://localhost:18081（容器内 `middleware-flink-jobmanager:8081`） | - |
| MinIO Console | 湖仓对象存储管理 | http://localhost:9001（S3 API :9000） | datanest / datanest123 |
| Doris 内置 | 目标数仓 | `192.168.119.135:9030` | root / password |

## 6. 已知坑（精简版）

> 完整版见 `docs/agent/gotchas.md`（含已解决坑与 E2E 测试细节）。此处仅保留当前有效、最易踩的坑。

### 环境 / 构建

> 细节与实证过程见 gotchas §一/§八。此处每条一行，只留结论。

- **worker 的 caffeine 依赖不要回退**（否则 `DagExecutionSyncService` 初始化 `ClassNotFoundException`）；`provided` 依赖需在消费方显式声明。
- **Addax writer 配置读 `datanest.addax.writer.*`**（非 `datanest.doris.writer.*`）；`writer.database` 无兜底，目标库名由同步任务 `target_database` 决定，为空直接抛异常。
- **`scheduler_job_id` 失效会惰性重注册**（PowerJob `deleteJob` 是软删），存量 `xxl_job_id` 老值同理，无须手工清理。
- **Nacos 查配置直接查 `middleware-mysql` 的 `nacos.config_info` 表**（API 可能 401）；**写配置必须走发布 API**（直插库不下发）；改配置后需重启对应服务（`logging.level.*` 除外，热生效）。
- **业务包日志默认 info**：排查时给服务加 `DATANEST_LOG_LEVEL=debug` 环境变量重启。
- **`PG_DATABASE` 无默认值（fail-fast）**：本地 IDE 启动必须显式配，避免误连已冻结的旧 `datanest` 库。
- **Doris 是外部主机**（不在 compose 里）；Addax 排查第一现场是 worker 容器内 `/opt/addax/log/sync_{id}.log` 与 `/opt/addax/job/job_sync_{id}.json`。
- **MailHog 清空**：`DELETE http://localhost:8025/api/v1/messages`（v2 端点 404）。
- **无库服务（worker/job）必须排除 DataSource 系自动配置**（Boot 4 类名在 `org.springframework.boot.jdbc.autoconfigure` 包），否则启动报 `Failed to configure a DataSource`。
- **docker exec 传 heredoc/管道 SQL 必须加 `-i`**，否则 stdin 关闭、SQL 一条不执行且返回成功。
- **接口文档用 springdoc 3.0.x，不用 Knife4j**（Knife4j 4.5.0 未适配 Boot 4）。新服务接文档 = 引 `springdoc-openapi-starter-webmvc-ui` + 配 `datanest.docs.title/gateway-prefix` + 网关 `swagger-ui.urls` 加一行；网关 `springdoc.api-docs.enabled` 保持默认（关 false 会连带 swagger-config 失效）。

### 告警（2026-08-06 起已独立为 app-alert 服务）

> 细节见 gotchas §二。此处每条一行。

- **告警域全部在 app-alert**（规则/历史/触发/邮件/DAG 告警），前端 `/api/alert/**`；**邮件只需 app-alert 配 `MAIL_*`**（本地 MailHog 需非空用户名密码）。
- **触发链路全部经 Feign**（`/alert/internal/**`）：排查告警问题先看 app-alert 日志，再看调用方的 Feign 异常。
- **`/internal/**` 端点由 common `InternalTokenFilter` 校验 `X-Internal-Token`**（空则放行），Feign 侧拦截器自动加头。
- **新服务三件套**：Feign `lb://` 必须引 `spring-cloud-starter-loadbalancer`；必须显式声明 `spring-boot-starter-validation`；启动类 scanBasePackages 只追加 `com.datanest.common.internal`。
- **`alert_rule.object_type` 有 DB CHECK 约束**：新增对象类型需同步改约束 + `AlertRuleService.validate()` 白名单。**Sprint 9（2026-08-11）**：已含 CDC_PIPELINE；**`alert_history.alert_type` 也有 DB CHECK**（FAILURE/TIMEOUT/SUCCESS + Sprint 9 增 LAG_EXCEEDED/EXTERNAL_STOP），新增告警类型必须一并放宽（V1.1.0 已做）。
- **告警跨域数据经 Feign 反查**（对象名/收件人），远端失败降级空值不阻断发送；**遗留**：`selectHistoryPage` 跨表 LEFT JOIN 拆库前必须改 Feign 反查。

### DAG / 条件节点

> 细节见 gotchas §三。此处每条一行。

- **ReactFlow 11 受控 edges/nodes 必须配 onEdgesChange/onNodesChange**（否则边不渲染且无报错）；**页面级 Provider 内第二个 ReactFlow 必须包独立 `ReactFlowProvider`**（否则内层卸载清掉主图）。
- **条件分支 SpEL 用索引语法 `#a['b']`**（SimpleEvaluationContext 无 MapAccessor，属性语法必抛错）；多前驱取值用按节点名写法 `${upstream['节点名'].row_count}`。
- **条件表达式不暴露 `dag_id`**，但参数解析层保留（供 SQL 占位符 `${dag_id}`），别误删。
- **执行状态同步走 PowerJob 快照**：节点匹配链 = 快照 nodeId → `dag_node.powerjob_node_id` → node_execution；状态映射 5→SUCCESS / 4→FAILED / 9,10→TERMINATED / 其余 RUNNING；wf 终态后未运行节点 WAITING→SKIPPED。
- **子 DAG 参数下发（Sprint 7 NG5）**：节点 config `paramMappings`（主→子单向），同步/异步双链路均注入子执行 `resolved_params`；运行时主参数无值 warn 跳过不阻断。

### Flyway / 迁移脚本

> 细节见 gotchas §四。此处每条一行。

- **Flyway 是代码驱动**：`spring.flyway` yaml 不生效；各持库服务靠本地 `config/FlywayConfig.java`（`@Bean(initMethod="migrate")` + baselineOnMigrate），新服务复制该类。
- **每服务独立管理本库迁移**（`src/main/resources/db/migration`，基线 V1.0.0 起各自演进）；改脚本后必须重 package + 重建对应镜像；拆分前旧脚本归档 `scripts/migration-legacy/`（不在 flyway 路径）。
- **新脚本版本号必须大于本库 `flyway_schema_history` 最高版本**（版本比较忽略尾随零），否则启动失败。
- **迁移脚本统一紧凑单行风格，禁用格式化工具拆行**（破坏已应用 checksum 触发 `Migration checksum mismatch`；用 flyway `repair` 固化）。
- **跨库搬数据后必须手动同步序列**（`pg_dump --data-only` 不带 setval，否则 serial 列插数据撞主键）。

### 质量

> 细节见 gotchas §五。此处每条一行。

- **质量执行在 app-worker**：改 task-core 质量执行代码后必须重建 **app-worker**（不只是 governance）；手动/定时/自动三触发统一投递 `qualityCheckExecuteHandler`。
- **PYTHON 质量规则（Sprint 7 DG-10）**：第 5 类规则类型；沙箱 `read_table(table, where, limit)` + `check(df)` 返回 dict 按 `result_metric` 取值复用 `determineLevel`，失败 UNAVAILABLE。坑：质量模式已放开 `socket` 禁令（pymysql 必需；DAG 节点保持禁令）；Doris 凭据必须从 `DorisDataSourceConfig` 静态 getter 取。新端点 `test-script`/`preview-execute` 见 §5 文档聚合页。
- **质量任务定时 = 每任务独立注册 PowerJob**（`scheduler_job_id` 字段）；调度参数带触发类型：手动/自动 `jobId:MANUAL`/`jobId:AUTO_TRIGGER`（带冒号），**定时是纯 `jobId`（无冒号默认 SCHEDULED）**。
- **质量任务创建 cron 不自动开启调度**（2026-08-09 起统一，对齐同步/采集任务）：`QualityJobService.create` 的 `scheduled_enabled` 恒存 0，cron 有值仅注册（`registerSchedule(entity, false)`）回填 `scheduler_job_id`，手动 `startSchedule` 才 start=true 启动；`update` 中「未注册→配 cron」事务内注册（按开关决定启动，失败回滚）、「已注册→cron/开关变化」事务提交后 `updateJob` 同步。**质量任务删除有 RUNNING 保护**：任务下存在 `quality_check_batch.status="RUNNING"` 批次时禁止删除（ErrorCode 4218，对齐采集/同步任务语义）。
- **结果值提取**：RANGE 按 `out_of_range/total`（列名大小写不敏感，NULL/0 按 0）；多指标按 `result_metric` 列名取。
- **质量接口前缀是 `/api/governance/quality/**`**（直接调 `/api/quality/**` 会 404）。
- **结果表 `quality_check_batch` + `quality_check_detail`**；明细 `result_level`（PASS/WARNING/SEVERE/UNAVAILABLE）；**执行层 successCount 与判定层 passCount 等四档语义不同，勿混淆**；UNAVAILABLE 不告警。
- **批次告警**：按任务 `alert_level` 过滤，一个批次一条 `alert_history`（命中多规则聚合进 `summary`），合并一条邮件；单规则批次 jobName 是「规则名（表名）」。
- **共享单规则质量 job `maxInstanceNum=1`**：快速连续触发多个单规则执行会被 server 静默丢弃。

### 实时 CDC（Sprint 8，F2 2026-08-10 已完成并实测）

> 细节见 gotchas §一。独立 Flink 2.2.1 Session 集群 + MinIO + Iceberg；app-realtime（第 8 个服务，持第 5 库 `datanest_realtime`）经 REST 提交（`FlinkPipelineComposer.ofRemoteCluster`，提交端依赖集照 `tmp/m0-cdc-verify/pom.xml`）。

- **依赖矩阵已固化**：集群用自定义镜像 `datanest-flink:2.2.1`（`docker/flink/Dockerfile`），预置 9 个 jar（`flink-cdc-dist/common/flink2-compat/三 connector(mysql/iceberg/postgres):3.6.0-2.2` + `mysql-connector-j:8.0.33` + `flink-shaded-hadoop-2-uber` + `flink-s3-fs-hadoop:2.2.1`）；**`flink-s3-fs-hadoop` 只能放 lib 不能放 plugins/**。
- **`classloader.resolve-order: parent-first` 必须写集群 config.yaml**（`-D` 不生效），否则 iceberg 双 classloader → `HadoopCatalog cannot be cast to Catalog`；**`classloader.check-leaked-classloader: false` 必须配**（compose FLINK_PROPERTIES），否则作业停止/重启后 S3A closed classloader、Iceberg 提交全失败。
- **CDC 源库账号需 `REPLICATION CLIENT/SLAVE/RELOAD` 权限**（root 不暴露，低权账号必授）。
- **S3A 配置**：凭据用容器环境变量 `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`；endpoint 用 `core-site.xml` + `HADOOP_CONF_DIR`（`s3.*`/`hadoop.*` 前缀均不透传）。
- **禁开 unaligned checkpoint**（与 CDC partitioner 冲突）；`pekko.ask.timeout` ≥120s；宿主 8081 被 nacos 占用，Flink JM 映射 18081。
- **Flink 2.2 REST 差异**：无 `/jobs/{id}/vertices` 子资源（vertices 内嵌 `/jobs/{id}`）；stop-with-savepoint body `{"drain":false,"targetDirectory":"s3a://datalake/savepoints"}`（无 formatType）；无 `SavepointRestoreSettings` 类（恢复走 `execution.savepoint.path` 配置键）；CDC 3.6 iceberg sink 无 upsert 选项。
- **Sprint 9 实时 CDC 深化（2026-08-11 已实施并实测）**：① 指标历史表 `cdc_metric_minute`（分钟降采样，`MetricSnapshotWriter` 60s flush upsert + `MetricRetentionCleaner` 每日 03:40 清理 30 天；**严禁 5s 轮询直写**）；② 新端点 `metrics/current`、`metrics/trend`（1h/6h 分钟点、24h 5 分钟桶、7d 小时桶）、`checkpoints`（实时转发不落库）、`savepoints`（手动触发）、`force-stop`（作业丢失降级，CAS 更新）；③ 监控 404 连续 N 轮（`not-found-threshold` 默认 3）归并外部停止；④ 告警对象类型 CDC_PIPELINE（FAILURE/LAG_EXCEEDED/EXTERNAL_STOP，realtime→alert 上报 fail-open + alert→realtime names 反查）；⑤ **Flink REST 新坑**：vertex per-second 指标是 **double**（Long.parseLong 会丢，需 double 解析路径）；手动 savepoint 触发 body 是 **kebab-case**（`{"target-directory":...,"cancel-job":false}`），与 stop 的 camelCase 不同；取消作业用 `PATCH /jobs/{id}` body `{"mode":"cancel"}`（POST /cancel 404）；⑥ MinIO Java Client 8.5.17 清理 savepoint 文件（`RemoveObjectsArgs.objects()` 需 `Iterable<DeleteObject>`，路径 `s3a://bucket/savepoints/xxx` 解析 bucket+前缀）；⑦ 错误码 8010/8011（savepoint 触发失败/管道非运行中）。
- **Doris Iceberg catalog 的 `s3.endpoint` 用宿主侧 `http://192.168.119.1:9000`**（VMnet8），不能写容器名；湖仓新数据/新表需 `REFRESH CATALOG/TABLE` 后 Doris 可见（realtime 有 refresh-catalog 端点）。
- **PG 源（2026-08-10 已全链路实测）**：镜像已加 `flink-cdc-pipeline-connector-postgres`（PG 驱动由 connector 内置 42.7.3，**不要再向 lib 单放 postgresql 驱动 jar**）；**oracle connector 与 postgres connector 不能同时放 lib**（同名 base 类 + shaded/未 shade Hikari 冲突，oracle 已移至 `docker/flink/lib-oracle-pending/`）；**PG 表必须 REPLICA IDENTITY FULL**（否则 UPDATE/DELETE 事件 before=null → NPE 毒消息无限重启）；PG 无 earliest-offset 位点；本期仅 public schema；复制权限检查用 `pg_roles.rolreplication OR rolsuper`（`pg_has_role(...,'replication')` 非法）。

## 7. 代码与提交约定

- **代码 Review 目的（2026-08-07 起）**：Review 开发的功能时聚焦三点——① **与当前架构融洽**（不破坏模块边界、依赖方向、服务间调用规则）；② **业务实现正确**（符合 PRD/技术文档语义，边界与异常路径处理到位）；③ **实现高效**（无过度设计、无 N+1/循环远程调用、无不必要的资源开销）。
- 做 **最小改动**，不要顺手重构无关代码。
- 改配置/改接口后，同步检查 yaml、Nacos 配置、注释、测试、前端调用点。
- 新增依赖时检查作用域：`provided` 依赖需要在消费方显式声明。
- 保持代码和周围风格一致，注释用中文。
- **创建审计字段约定（2026-08-05 起生效，V3.6.8）**：所有实体 `create` 入口（含批量 create/DAG 节点）**只设置 `setCreatedBy`/`setCreatedAt`，禁止 `setUpdatedBy`/`setUpdatedAt`**；`updated_at` 列已通过 Flyway `V3.6.8__drop_updated_at_default.sql` 去掉 `DEFAULT CURRENT_TIMESTAMP` 且允许 NULL，仅真正 update/启停/状态变更时才写入。新增带审计字段的表时，其 `updated_at` 不要加 DB 默认值。
- 不要主动运行 `git commit` / `git push`，除非用户明确要求。

## 8. 编码规范索引

- **后端规范**：见 `docs/agent/conventions-backend.md`（技术栈、响应协议/错误码、实体/Mapper/Flyway、Service/Controller/URL、Nacos）。
- **前端规范**：见 `docs/agent/conventions-frontend.md`（技术栈、目录结构、API/错误/状态/路由/样式/分页/通知）。
- 硬约束速记：
  - 后端统一返回 `Result<T>` / `PageResult<T>`，业务错误用 `BusinessException(ErrorCode)`。
  - `Long` 主键序列化为字符串；实体用 `LocalDateTime`；JSONB 字段实体用 `String` + Fastjson2。
  - Flyway 脚本统一紧凑单行风格（见 §6）。
  - 前端颜色唯一来源 `src/styles/tokens.css`；源码全部 `.tsx`；ID 全程 `string`；列表页用 `usePagedList`。
  - 接口联调先 Postman/curl 自测通过再联调前端。

## 9. 前后端联调约定

- 所有请求统一走 Gateway：`http://localhost:8080/api/<服务>/<路径>`。
- 后端 `Long` 类型主键会序列化为字符串，前端类型声明用 `string`，URL 拼接不要转 Number。
- **列表/分页接口**：优先用 `POST /.../page`（如 `/api/engineering/sync-jobs/page`），不要用 `GET` 列表；DAG 执行历史等场景用 `GET` + query params（如 `/api/engineering/dag-executions`）。
- **命名统一**：批量数据同步任务在代码/路由/API/表中均为 `sync-jobs`（不是 `sync-tasks`），DAG 菜单在代码中为「项目管理」。
- 修改 DTO、返回结构、URL 路径、字段含义时，必须同步检查：后端 Controller/Service/DTO、前端 `src/api/*`、前端 `src/types/*`、相关页面组件、接口文档/Sprint 文档。
- 分页字段：`page` 从 1 开始，`pageSize` 默认 10。

## 10. 安全与敏感信息

- 密码、token、密钥等敏感信息 **禁止硬编码**到代码或配置文件中；应走 Nacos 配置或环境变量注入。
- 日志中禁止打印密码、完整 token、数据库连接串密码部分；打印 DTO 时先脱敏敏感字段。
- 前端构建产物中不要包含 `.env.development` 等本地配置。
- 后端接口必须加 `@SaCheckRole` 等权限控制，匿名接口需经评审。
