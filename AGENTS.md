# DataNest Agent 工作约定

> 本文件面向 AI Agent，用于跨会话恢复项目上下文。人类开发者也可查阅。
> **结构说明**：本文件只保留概览、会话约定、构建规则、验证规范、环境速查和精简版已知坑。详细的架构说明、后端/前端编码规范、完整已知坑与 E2E 测试细节分别拆在 `docs/agent/` 下，按需查阅（见 §0 索引）。

## 0. Agent 文档索引

| 文件 | 内容 | 何时查阅 |
|------|------|----------|
| `docs/agent/architecture.md` | 模块/容器/包结构/task-core 拆分依赖关系 | 需要理解模块边界、依赖方向、改共享模块时 |
| `docs/agent/conventions-backend.md` | 后端技术栈、响应协议、异常、实体/Mapper/Flyway、Controller/URL、Nacos | 写后端代码时 |
| `docs/agent/conventions-frontend.md` | 前端技术栈、目录结构、API/错误/状态/路由/样式规范 | 写前端代码时 |
| `docs/agent/gotchas.md` | 已知坑完整版 + 已解决坑 + E2E 测试细节 | 排查问题、写/改测试时 |
| `docs/agent/prototype-guide.md` | UI 原型高保真制作规范 + 真实 token/组件速查表 | 做静态高保真原型时 |

## 1. 项目概览

DataNest 是一个数据平台，技术栈如下：

- **后端**：Java 21 + Spring Boot 4.x，Maven 多模块（**三层目录**：`data-nest-libs/` 共享库、`data-nest-apis/` Feign 契约、`data-nest-services/` 可部署服务，目录名与聚合 artifactId 一致）
- **前端**：独立容器 `app-frontend`（源码目录 `data-nest/data-nest-frontend`），通过 `app-gateway:8080` 统一入口
- **部署**：Docker Compose，所有服务在同一 `datanest-net` 网络
- **配置中心**：Nacos，配置实际存储在 `middleware-mysql` 的 `nacos.config_info` 表
- **调度**：XXL-JOB（官方镜像），数据库为 `datanest_scheduler`（不是 `xxl_job`）
- **目标数仓**：内置 Doris（当前在 `192.168.119.135:9030`）
- **业务库（2026-08-07 起按域拆 4 库）**：`datanest_system`（sys_*，app-system）、`datanest_alert`（alert_*+dag_alert_*，app-alert）、`datanest_engineering`（sync/dag/datasource 13 表，app-engineering）、`datanest_governance`（metadata/collect/quality 等 19 表，app-governance）；均在 middleware-postgres 同实例。**worker/job 无库**（纯执行/调度节点，application.yml 已排除 DataSource 自动配置）。旧 `datanest` 库保留只读观察后下线。各服务 Flyway 独立管理本库（`db/migration/V1.0.0__baseline.sql` 起，代码驱动见 §6）

### 核心模块

> **task-core 历史拆分**：原 `data-nest-task-core` 曾按依赖分层拆为 4 模块（entity/alert/task-core-governance/task-core），包名 `com.datanest.task.core.*` 不变。微服务化后：alert 独立为 app-alert、task-core-governance 已删除、entity 只剩 dto+constant（SysUser 已迁 system）。所有消费方服务（engineering/governance/worker/job/system）**只显式声明依赖 `data-nest-task-core`**。详见 `docs/agent/architecture.md`。
>
> **微服务化改造（2026-08-06 起，已全部完成）**：共享 jar 进程内调用 → OpenFeign + Nacos 远程调用 + 按域拆 4 库（最终一致性，无分布式事务无 MQ）。总方案与全程记录见 `docs/microservices-refactor/handoff.md`（跨会话恢复先读它）。已删除模块：data-nest-alert（独立为 app-alert）、task-core-governance、task-core-entity（dto 迁 task-core、constant 迁 common）。

| 模块 | 说明 |
|------|------|
| `data-nest-common` | 公共组件（SchedulerClient、InternalTokenFilter/Feign 拦截器等），最底层底座 |
| `data-nest-task-core` | 执行内核（SyncJobExecutorService/QualityCheckService/CollectExecutor 等 + 共享 dto 包；全部 DB 访问经 Feign） |
| `data-nest-alert-api` | app-alert 的 Feign 契约（AlertApi + DTO）。worker/job/engineering/governance 依赖 |
| `data-nest-system-api` / `data-nest-engineering-api` / `data-nest-governance-api` | 各服务 Feign 契约（内部端点 + DTO + fallbackFactory） |
| `data-nest-alert-service` | **独立告警服务**（app-alert，com.datanest.alert.*）：告警规则/历史/触发/邮件 + dag_alert_config/history |
| `data-nest-engineering` | 数据工程服务（同步任务 API、DAG API；13 表本地持有） |
| `data-nest-worker` | Addax 实际执行方（**无任何业务库**，全部经 Feign 回写） |
| `data-nest-governance` | 数据治理服务（元数据、数据标准、质量编排/评分；19 表本地持有） |
| `data-nest-job` | XXL-JOB executor，平台定时任务（**无任何业务库**，handler 全部端点化） |
| `data-nest-system` | 认证、用户、权限（SysUser 体系本地持有） |
| `data-nest-gateway` | 网关入口 |

> **服务间调用规则**：跨服务调用一律走对应 `*-api` 模块的 Feign client（`/internal/**` 端点，`X-Internal-Token` 头鉴权），禁止再跨服务共享 Service/Mapper 进程内调用。**容错三件套已内置**：`shared-rpc.yaml` 全局超时（connect 2s/read 5s）+ 重试（Retryer.Default ×3）+ Resilience4j 熔断（各 client 配 fallbackFactory）+ 内部令牌；消费方降级统一用 common 的 `RemoteCalls.execute(描述, 调用, 降级值)`（自动 warn 日志 + `remote_call_failed_total` 指标），不要手写 try-catch 样板。读路径降级空集合；**fail-closed 例外**：删除前置校验类调用必须让异常传播（现有 2 处：QualityJobService 告警引用校验、AssetCatalogService.assignOwner）。
> **禁止逐条循环远程调用（N+1）**：循环场景必须提供批量端点（如 `usernames?ids=`、`dags/{dagId}/nodes/resolve`、`quality/auto-trigger/batch`）。
> **Feign 契约的查询/路径参数禁止用 LocalDateTime**（Feign 的 ConversionService 会按 locale 格式化成 `8/7/26, 6:20 AM`，服务端解析失败）——一律 ISO String（`DateTimeFormatter.ISO_LOCAL_DATE_TIME`）；请求体里的 LocalDateTime 走 Jackson 不受影响。
> **用户名回填**：`SysUserService` 仅 app-system 内部使用；其它服务列表页的 createdBy/updatedBy 名称回填一律经 `data-nest-system-api` 的 `SystemUserApi.usernames`（批量，失败降级空 Map）。

### 核心容器

| 容器 | 说明 |
|------|------|
| `app-engineering` / `app-worker` / `app-governance` / `app-job` / `app-system` / `app-gateway` | 对应六个后端服务 |
| `app-alert` | 独立告警服务（容器端口 8088，**不暴露宿主机端口**：对外走 gateway `/api/alert/**`，容器间 Feign 走 datanest-net） |
| `middleware-mysql` | MySQL：Nacos、XXL-JOB、DolphinScheduler、业务库 |
| `middleware-postgres` | PostgreSQL：业务主库 |
| `middleware-nacos` / `middleware-xxljob` / `middleware-redis` | Nacos / XXL-JOB Admin / Redis |

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

- **task-core 拆分为 3 个共享模块**（entity/task-core-governance/task-core），是 engineering、governance、worker、job、system 的 **共享底座**（原第 4 个模块 alert 已独立为 app-alert 服务）。
- 消费方只显式依赖 `data-nest-task-core`（经其传递获得 entity/governance 模块）；告警调用另依赖 `data-nest-alert-api`（Feign 契约）。
- **构建顺序**：Maven 按 `<modules>` 声明顺序构建，顺序为 `common → *-api → task-core-entity → task-core-governance → task-core → 各服务`（已在根 pom 配置）。
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

> `-am`（also make）会自动把 `task-core` 依赖的 `task-core-entity/task-core-governance` 及各 api 模块一并构建。

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
| admin 登录 | 获取全局 token | `POST /api/system/auth/login` | admin / admin123 |
| PostgreSQL 业务库 | 按域 4 库：`datanest_system` / `datanest_alert` / `datanest_engineering` / `datanest_governance` | `docker exec -it datanest-middleware-postgres psql -U datanest -d <库名>` | datanest / datanest123（旧 datanest 库只读观察，勿写） |
| MySQL root | 管理 MySQL 所有库 | `docker exec -it datanest-middleware-mysql mysql -u root -proot123` | root / root123 |
| MySQL nacos | 查 Nacos 配置、业务库 | `docker exec -it datanest-middleware-mysql mysql -u nacos -pnacos123` | nacos / nacos123 |
| Nacos 配置库 | 存储所有 shared-configs | `nacos.config_info` 表（在 middleware-mysql） | - |
| XXL-JOB Admin | 调度任务管理 | http://localhost:8088 | admin / 123456（3.x API：`POST /auth/doLogin` 拿 cookie → `/jobinfo/trigger`，context-path 为 `/`） |
| XXL-JOB DB | XXL-JOB 任务信息 | `datanest_scheduler.xxl_job_info` | - |
| Doris 内置 | 目标数仓 | `192.168.119.135:9030` | root / password |
| DolphinScheduler | 工作流调度（当前保留） | http://localhost:12345 | admin / dolphinscheduler123 |

## 6. 已知坑（精简版）

> 完整版见 `docs/agent/gotchas.md`（含已解决坑与 E2E 测试细节）。此处仅保留当前有效、最易踩的坑。

### 环境 / 构建

- **worker 已补上 caffeine 依赖**；不要回退，否则 `DagExecutionSyncService` 初始化会 `ClassNotFoundException`。
- **Addax writer 配置路径已对齐**：代码读的是 `datanest.addax.writer.*`，不是 `datanest.doris.writer.*`。
- **`writer.database` 兜底已删除**：目标库名由同步任务 `target_database` 决定；为空时直接抛异常。
- **XXL-JOB 任务 ID 可能失效**：`sync_job.xxl_job_id` 若指向已删除/清理的任务，触发时报"任务ID非法"。处理：置空该字段，下次执行自动重新注册。
- **Nacos API 可能 401**：直接查 `middleware-mysql` 的 `nacos.config_info` 表更可靠。
- **Doris 是外部主机**：不在 docker-compose 里，部署/清理时不要以为重启容器会影响 Doris。
- **Addax 执行日志**：worker 容器内 `/opt/addax/log/sync_{sync_job_id}.log` 和 `/opt/addax/job/job_sync_{sync_job_id}.json` 是排查同步失败的第一现场。
- **Nacos 配置修改后可能不实时生效**：部分服务对 `@Value` 注入无热刷新能力，改完配置后需重启对应服务。
- **MailHog 清空**：`DELETE http://localhost:8025/api/v1/messages`（v2 端点会 404）。
- **无库服务必须排除 DataSource 自动配置（2026-08-07 起）**：worker/job 无任何业务库（纯执行/调度节点），两服务 application.yml 已 `spring.autoconfigure.exclude` 排除 `DataSourceAutoConfiguration`/`DataSourceTransactionManagerAutoConfiguration`/`MybatisPlusAutoConfiguration`（Boot 4 类名在 `org.springframework.boot.jdbc.autoconfigure` 包）——不排会启动报 `Failed to configure a DataSource`。注意 worker 的 Doris 连接是 `DorisDataSourceConfig` 手工构建，不受影响。

### 告警（2026-08-06 起已独立为 app-alert 服务）

- **告警域全部在 app-alert**（`data-nest-alert-service`，com.datanest.alert.*）：规则 CRUD、触发（fire/fireBatch）、DAG 告警、邮件、dag_alert_config/history。前端路径 `/api/alert/**`（规则/历史/by-object/dag-alert-config），旧的 `/api/system/alert-rules`、`/api/engineering/*/alert-rule` 等已下线。
- **邮件只需 app-alert 配**：`MAIL_*` 环境变量和 `shared-alert.yaml` 已从 engineering/worker/job 撤掉（本地 MailHog 仍需非空 `MAIL_USERNAME`/`MAIL_PASSWORD`）。其它服务不再有 MailService。
- **触发链路全部经 Feign**（契约 `data-nest-alert-api`，端点 `/alert/internal/**`）：同步/采集/质量 fire → worker 内 task-core 执行器调 AlertApi；DAG SUCCESS/FAILURE → task-core `RemoteDagFinishedListener` → `/alert/internal/dag-finished`（端点内部同时完成质量自动触发：engineering 解析 dag_node.id → governance auto-trigger）；TIMEOUT → job `DagNodeTimeoutAlertHandler`（阈值经 `/alert/internal/dag-alert-config/resolve` 获取）。排查告警问题**先看 app-alert 日志**，再看调用方容器日志的 Feign 异常。
- **内部调用鉴权**：`/internal/**` 端点由 common 的 `InternalTokenFilter` 校验 `X-Internal-Token`（配置 `datanest.internal.token`，Nacos `shared-internal.yaml`；为空则放行）。Feign 侧由 `InternalTokenFeignInterceptor` 自动加头。注意只拦截以 `/internal/` 开头的路径，DS 回调 `/dev/internal/**` 不受影响。
- **Feign lb:// 必须有 `spring-cloud-starter-loadbalancer`**：否则启动报 `No Feign Client for loadBalancing defined`（engineering/worker/job 已补）。**新服务必须显式声明 `spring-boot-starter-validation`**（common 中是 provided，GlobalExceptionHandler 需要，否则 `NoClassDefFoundError: jakarta/validation/ConstraintViolationException`）。
- **启动类 scanBasePackages 只追加 `com.datanest.common.internal`**：扫整个 `com.datanest.common` 会误装配 `SchedulerClient`（`@Value("${xxl.job.admin.addresses}")` 无默认值，未引 shared-xxljob 的服务启动失败）。
- **`alert_rule.object_type` 有数据库 CHECK 约束**：含 `DAG/SYNC_JOB/COLLECT_TASK/QUALITY`（V3.6.6 已重建）。新增对象类型需同步改约束 + app-alert 的 `AlertRuleService.validate()` 白名单。
- **告警跨域数据经 Feign 反查**：对象名/下拉（engineering、governance 的 `/internal/objects/names`、`/internal/alert-objects/options`）、收件人邮箱/用户名（system 的 `/internal/users/emails|usernames`）。远端失败均降级（warn + 空值），不阻断告警发送。
- **遗留**：app-alert 的 `selectHistoryPage` 仍有跨表 LEFT JOIN（dag/sync_job/collect_task/quality_job），同库期间正常，**拆库（阶段 5）前必须改为 Feign 反查**。

### DAG / 条件节点

- **ReactFlow 11 受控 edges/nodes 必须配 onEdgesChange/onNodesChange**：直接传 prop 而不传 handler 时边不渲染（节点正常、边为空且无报错）。排查"节点正常、边不渲染"先看此。
- **SimpleEvaluationContext 不含 MapAccessor**：条件分支 SpEL 里 `#upstream.row_count` 属性语法必然抛 "Property cannot be found"。需用索引语法 `#a['b']`（见 `DagNodeExecuteService.evaluateBranches`）。
- **条件节点 upstream 是嵌套结构**：`buildConditionContext` 以「前驱节点名」为 key 构造嵌套 map（支持 `${upstream['节点名'].row_count}`），顶层同时保留最后遍历前驱的 `row_count/status` 兼容 `${upstream.row_count}`。排查"多前驱条件分支取错值"先确认用的是按节点名写法。
- **条件表达式不再暴露 dag_id**：`buildConditionContext` 已 `vars.remove("dag_id")`；但 `DagParameterResolver` 的 `dag_id` 仍保留（供 SQL 占位符 `${dag_id}`）。改动条件表达式变量时别动参数解析层的 `dag_id`。
- **DagExecutionSyncService 匹配 DS 任务实例**：DS 任务名 = `节点名_节点ID后8位`（nodeId 可能含 `_`），应按相同规则构建「DS 任务名→node」反向映射，不能简单按 nodeName 或 strip 末尾 `_` 段匹配。SUB_DAG 等匹配失败会落 WAITING→SKIPPED。

### Flyway / 迁移脚本

- **Flyway 是代码驱动（2026-08-07 起）**：项目 jar 未引入 spring-boot-flyway autoconfigure 模块，`spring.flyway` 的 yaml 配置**不生效**；4 个持库服务各自有 `config/FlywayConfig.java`（`@Bean(initMethod="migrate")` + baselineOnMigrate）。新增服务要复制这个类。
- **每服务独立管理本库迁移**：migration 脚本在各服务 `src/main/resources/db/migration`（基线 V1.0.0，后续各自从 V1.1.0 起演进）。改脚本后必须重新 package 该服务并重建对应镜像。72 个拆分前旧脚本归档在 `data-nest/scripts/migration-legacy/`（仅供查阅，不在 flyway 路径）。
- **Flyway 版本比较忽略尾随零**：baseline marker "1" 与 "1.0.0" 排序相等（存量库跳过 V1.0.0 靠的就是这个）；新增脚本版本号必须大于本库 `flyway_schema_history` 最高版本，否则报 `Detected resolved migration not applied to database` 启动失败。
- **禁止用格式化工具拆分迁移 SQL**：会破坏已应用脚本 checksum，触发 `Migration checksum mismatch`。所有迁移脚本统一**紧凑单行风格**；确需调整时用 flyway `repair` 固化 checksum 并重启对应服务。
- **pg_dump --data-only 不带序列 setval**：跨库迁移数据后必须手动同步序列（`SELECT setval(seq, (SELECT max(id) FROM t))`，pg_depend 关联查询可批量生成），否则 serial 列插数据撞主键。

### 质量

- **质量执行在 app-worker（Sprint 8 执行层）**：`qualityCheckExecuteHandler` 注册在 `data-nest-worker` 组，`QualityCheckService` 在 worker 容器内执行。手动/定时/自动三种触发统一投递到该 handler。改 task-core 的质量执行代码后必须重建 **app-worker**（不只是 governance）。
- **质量任务定时 = 每任务独立注册 XXL-JOB（不再全局扫描）**：`startSchedule` 按需 `registerJob`/`startJob`，`stopSchedule` 仅 `stopJob`（保留 `xxl_job_id`），`delete` 注销，`update` 里 cron 变更会 `updateJob` 同步。已废弃 `QualityCheckHandler` 全局扫描（残留可手动删）。
- **质量执行 executorParam 带触发类型**：手动/自动显式传 `jobId:MANUAL` / `jobId:AUTO_TRIGGER`（带冒号）；**定时触发用注册时保存的纯 `jobId`（无冒号）**，handler 对无冒号 param 默认按 `SCHEDULED`。排查"定时触发落库成 MANUAL"先看 handler 的 param 解析。
- **质量结果值提取坑（RANGE）**：Doris/MySQL 对空表 `SUM(...)` 返回 **NULL**（非 0），且 JDBC 列名可能大小写变化。`computeRangeRatio` 已对列名大小写不敏感匹配，`total=0` 或 `out` 为 NULL 时按 0 处理。
- **质量接口经 gateway 前缀是 `/api/governance/quality/**`**，不是 `/api/quality/**`（直接调会 404）。路径形如 `/api/governance/quality/jobs/page`。
- **质量执行结果表**：`quality_check_batch`（批次）+ `quality_check_detail`（规则明细）。明细含 `result_value` + `result_level`（`PASS/WARNING/SEVERE/UNAVAILABLE`），不评分。批次状态 `RUNNING/SUCCESS/PARTIAL_FAILED/FAILED`（无规则视为 SUCCESS）。
- **质量分级判定（Sprint 6 分级邮件告警）**：阈值在 `QualityCheckService.determineLevel`（`value < warning`→PASS；`warning ≤ value < severe`→WARNING；`value ≥ severe` 或无 severe 时 `value ≥ warning`→SEVERE；warning/severe 都空→PASS；SQL 失败→UNAVAILABLE）。批次收尾 `fireBatchAlert` 按任务 `alert_level` 过滤，合并为**一条邮件** + **一个批次只落一条 `alert_history`**（命中多条规则聚合进 `summary`，`alert_history.summary` 每行一条规则「[等级] 规则名: 详情」）；`alert_sent` 置 1 幂等。**UNAVAILABLE 不触发告警**（R2 防误报）。告警复用 `alert_rule`，扩展对象类型 `QUALITY`。
- **批次列表「成功/失败」与「通过/警告/严重/不可用」两列并存（Sprint 6 UX，语义不同）**：`successCount/failedCount` 反映**执行层**（SQL 是否跑成功），`passCount/warningCount/severeCount/unavailableCount` 反映**判定层**（结果是否达标）。一条 SQL 跑成功（成功）但结果不达标 → 判「严重」；SQL 跑失败 → 判「不可用」。四档在 `QualityCheckService.toBatchDTO` 按 `result_level` 聚合，勿混淆两层语义。
- **批次↔告警对应 + 一个批次一条告警（Sprint 6 UX，Flyway V3.8.1 + V3.8.2）**：`alert_history` 加 `quality_batch_id` 列 + `summary TEXT`（聚合明细，每行一条「[等级] 规则名: 详情」）。`QualityCheckService.fireBatchAlert` 调 `AlertFiringService.fireBatch(objectType, objectId, alertType, items, batchId)`，**一个批次只写一条** `alert_history`（非循环逐条），命中多条规则聚合进 `summary`；批次详情 `getBatchDetail` 按 `quality_batch_id` 反查回填 `alertHistories`（前端 AlertSection 取第一条按 summary 逐行解析命中规则）。**注意 Flyway 版本号**：库内最高已到 `3.8.1`（V3.8.1 批次关联 + V3.8.2 summary），新增迁移必须用 `3.8.2+`，勿再用 3.7.x/3.8.0。
- **单规则执行批次 jobName 是「规则名（表名）」**：`QualityCheckService.executeRule` 在明细落库后按 `ruleName + tableName` 更新 jobName（非硬编码「单规则执行」）。单规则批次 `jobId` 为空，定位靠 jobName。
- **质量任务绑定对象名回填**：`QualityJobDTO.autoTriggerObjectName` 由 `QualityJobService` 注入 entity 层 `SyncJobMapper/CollectTaskMapper/DagMapper`（按 `autoTriggerObjectType` 分支）回填；`DAG_NODE` 映射 `DagMapper`，勿用 alert 的 `resolveObjectName`（其 DAG 类型值是 `DAG` 而非 `DAG_NODE`，映射不一致）。

## 7. 代码与提交约定

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
