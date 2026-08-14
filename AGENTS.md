# DataNest Agent 工作约定

> 本文件面向 AI Agent，用于跨会话恢复项目上下文。人类开发者也可查阅。
> **结构说明**：本文件只保留概览、会话约定、构建/验证红线、环境速查和精简版已知坑。详细的架构、后端/前端规范、完整已知坑、构建部署验证细节分别拆在 `docs/agent/` 下（见 §0 索引）。

## 0. Agent 文档索引

| 文件 | 内容 | 何时查阅 |
|------|------|----------|
| `docs/agent/architecture.md` | 模块/容器/包结构/task-core 拆分依赖关系 + 服务间调用拓扑 | 需要理解模块边界、依赖方向、改共享模块时 |
| `docs/agent/conventions-backend.md` | 后端技术栈、响应协议、异常、实体/Mapper/Flyway、Controller/URL、接口文档注解、Nacos | 写后端代码时 |
| `docs/agent/conventions-frontend.md` | 前端技术栈、目录结构、API/错误/状态/路由/样式规范 | 写前端代码时 |
| `docs/agent/gotchas.md` | 已知坑完整版 + 已解决坑 + E2E 测试细节 | 排查问题、写/改测试时 |
| `docs/agent/build-and-deploy.md` | 构建与部署规则、验证路径（同步/采集）、测试产物清理约定、代码与提交约定 | 构建/部署/验证/清理测试产物时 |
| `docs/agent/prototype-guide.md` | UI 原型高保真制作规范 + 真实 token/组件速查表 | 做静态高保真原型时 |
| `docs/agent/shared-code-governance.md` | 共享能力清单（common/task-core 已有能力）+ 复用检查清单 + 下沉判断标准与 SOP | 写任何通用能力/工具/配置/常量前，或发现多服务重复实现时 |

## 1. 项目概览（精简版）

DataNest 是一个数据平台。架构详情、模块/容器清单、服务间调用拓扑见 `docs/agent/architecture.md`。此处只留跨会话最常用的事实：

- **后端**：Java 25 + Spring Boot 4.x，Maven 多模块（**三层目录**：`data-nest-libs/` 共享库、`data-nest-apis/` Feign 契约、`data-nest-services/` 可部署服务，目录名与聚合 artifactId 一致）。**2026-08-12 pom 重构**：新增 `data-nest-service-webmvc` 中间父 pom（system/alert/engineering/governance/realtime/data-service 继承）与 `data-nest-apis` 聚合 pom；根 pom 统一管理第三方版本。**子模块禁止再写第三方字面量版本或本地 properties 版本属性**。
- **前端**：独立容器 `app-frontend`（源码目录 `data-nest/data-nest-frontend`），通过 `app-gateway:8080` 统一入口。
- **配置中心**：Nacos（配置存 `middleware-mysql` 的 `nacos.config_info` 表）；**调度**：PowerJob 5.1.2（控制台 :7700，App `data-nest-job` id=1 / `data-nest-worker` id=2）。
- **目标数仓**：内置 Doris 4.1.3（外部主机 `192.168.119.135:9030`，不在 compose 里）。
- **业务库（按域拆 6 库）**：`datanest_system` / `datanest_alert` / `datanest_engineering` / `datanest_governance` / `datanest_realtime` / `datanest_dataservice`（Sprint 10 F1 新增），均在 middleware-postgres 同实例；**worker/job 无库**。各服务 Flyway 独立管理本库。

> **服务间调用规则**：跨服务调用一律走 `*-api` 模块的 Feign client（`/internal/**` 端点，`X-Internal-Token` 头鉴权），禁止进程内共享 Service/Mapper。容错三件套（超时重试 + 熔断 + 内部令牌）已内置；读路径降级用 common `RemoteCalls.execute`，**fail-closed 例外**（删除前置校验类）必须让异常传播。
> **禁止逐条循环远程调用（N+1）**：循环场景必须提供批量端点。
> **Feign 契约的查询/路径参数禁止用 LocalDateTime**——一律 ISO String；请求体里的 LocalDateTime 走 Jackson 不受影响。
> **用户名回填**：其它服务一律经 `SystemUserApi.usernames`（批量，失败降级空 Map），不直接用 `SysUserService`。

## 2. 会话约定

- **所有 agent 一律从产品角度思考**（全局纪律，不限会话类型、不限角色，包括子 agent/子会话）。做任何需求分析、技术方案、编码实现、接口设计、页面交互、Bug 修复前，先回答三问：①这个功能/改动给用户带来什么价值，解决什么真实场景？②现有的交互和边界是否符合用户心智（例如"数据源状态"是给运维看的，就要贴近运维认知而非技术字段名）？③有没有更贴合产品目标、更省用户操作的方案？技术可行性不能凌驾于产品合理性之上；当产品意图不明确或实现与产品初衷冲突时，先向用户澄清，不擅自替用户做产品决策。
- **一个会话一个目标**。避免把技术选型、闲聊、无关 Bug 修复混进主线。
- 回复和说明使用 **中文**；代码注释/提交信息跟随项目现有风格（中文为主）。
- 跨会话恢复上下文时，先读 `docs/sprint<编号>/handoff/sprint-<编号>.md`；如不存在，请用户简述当前目标。
- 每个 Sprint 建议 2~4 个会话：规划/设计、后端实现、前端联调、验证收尾。
- 不要主动运行 `git commit` / `git push`，除非用户明确要求。

### 编码前约定

- **先读代码再动手**。修改代码前必须通过 `Read`/`Grep` 读透相关文件和调用链，不要凭记忆或猜测；特别是 `data-nest-task-core` 的改动，要确认 engineering、worker 等所有消费方。
- **改接口必须同步前端/文档**。修改 DTO、返回结构、URL 路径、字段含义时，必须同步检查前端调用点和接口文档，避免前后端不一致。
- **共享能力先查再建（DRY 硬约束）**。写任何通用能力（工具方法/配置类/常量/DTO/回填/校验/响应/异常）之前，**必须**先查 `data-nest-common` / `data-nest-task-core` 是否已有（详见 `docs/agent/shared-code-governance.md`），已有则直接复用，**禁止**在服务本地再造一份；发现 ≥2 个服务重复实现时必须按下沉规范处理。已治理项（FlywayConfig、MyBatisPlusConfig、DorisConstants、SystemUserResolver、SqlStatementSplitter.classify、PowerJobWorkerSupport、JdbcUrlBuilder/JdbcPreviewHelper）**禁止回归自建**。

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

## 3. 构建与部署红线

> 完整命令、验证路径见 `docs/agent/build-and-deploy.md`。

- **只要改到 `data-nest-task-core`，必须同时重新编译并部署所有消费方**（至少 engineering 和 worker；若涉及治理/质量还需 governance/job/system），否则执行节点还是旧代码。
- **前端部署必须两步**：`app-frontend` 的 Dockerfile 只 `COPY dist/`（不在镜像内构建），改前端代码后必须先本地 `pnpm build` 再 `docker compose build app-frontend && up -d`。
- 构建后检查镜像时间戳，确认用了新 jar（遇到过 buildkit 缓存未更新的情况）。
- **前端顶级路由不得与静态目录 `assets/` 同名**（Sprint 7 资产目录路由因此用 `/asset-catalog`）。

## 4. 验证红线

> 完整同步/采集任务验证路径见 `docs/agent/build-and-deploy.md`。

- **不要只在编译成功就报完成**，功能改动必须做回归验证。
- **测试产物清理约定（2026-08-12 起，全局纪律）**：每次 E2E/API 测试结束必须清理临时产物——前端 `test-results*` 目录、`%TEMP%` 下 `sql_e2e*.ps1`/`verify_fix*.ps1`/`body_*.json`/`login*.json`/`create_*.json`/`export_*.json`。`e2e/sprint10/` 下的正式测试套件（如 `sql-console.spec.ts`）属源码，不清理。测试创建的数据库用户（如 `analyst_test`）清理前先问用户。

## 5. 环境速查

| 资源 | 用途 | 地址/命令 | 账号/密码 |
|------|------|-----------|-----------|
| 网关入口 | 所有 API 统一入口 | http://localhost:8080 | - |
| 接口文档聚合页 | springdoc swagger-ui（右上角下拉切服务） | http://localhost:8080/swagger-ui.html | 匿名可读，调试需配 Authorization 头 |
| admin 登录 | 获取全局 token | `POST /api/system/auth/login` | admin / admin123 |
| PostgreSQL 业务库 | 按域 6 库：`datanest_system`/`datanest_alert`/`datanest_engineering`/`datanest_governance`/`datanest_realtime`/`datanest_dataservice` | `docker exec -it datanest-middleware-postgres psql -U datanest -d <库名>` | datanest / datanest123 |
| MySQL root | 管理 MySQL 所有库 | `docker exec -it datanest-middleware-mysql mysql -u root -proot123` | root / root123 |
| MySQL nacos | 查 Nacos 配置、业务库 | `docker exec -it datanest-middleware-mysql mysql -u nacos -pnacos123` | nacos / nacos123 |
| Nacos 配置库 | 存储所有 shared-configs | `nacos.config_info` 表（在 middleware-mysql） | - |
| PowerJob 控制台/OpenAPI | 调度任务管理（含 DAG 工作流） | http://localhost:7700 | App 密码 `powerjob123`（App：`data-nest-job` id=1 / `data-nest-worker` id=2；DB 为 MySQL `powerjob` 库） |
| Flink Web UI / REST | CDC 作业观测（独立 Session 集群） | http://localhost:18081（容器内 `middleware-flink-jobmanager:8081`） | - |
| MinIO Console | 湖仓对象存储管理 | http://localhost:9001（S3 API :9000） | datanest / datanest123 |
| Doris 内置 | 目标数仓 | `192.168.119.135:9030` | root / password |

## 6. 已知坑（精简版）

> 完整版见 `docs/agent/gotchas.md`（按域分节 + 已解决坑 + E2E 细节）。此处每域只留最高频的 2~3 条。

### 环境 / 构建

- **worker 的 caffeine 依赖不要回退**（否则 `DagExecutionSyncService` 初始化 `ClassNotFoundException`）；`provided` 依赖需在消费方显式声明。
- **Nacos 查配置直接查 `middleware-mysql` 的 `nacos.config_info` 表**（API 可能 401）；**写配置必须走发布 API**（直插库不下发）；改配置后需重启对应服务（`logging.level.*` 除外，热生效）。
- **Doris 是外部主机**（不在 compose 里）；Addax 排查第一现场是 worker 容器内 `/opt/addax/log/sync_{id}.log` 与 `/opt/addax/job/job_sync_{id}.json`。
- **docker exec 传 heredoc/管道 SQL 必须加 `-i`**，否则 stdin 关闭、SQL 一条不执行且返回成功。
- **无库服务（worker/job）必须排除 DataSource 系自动配置**，否则启动报 `Failed to configure a DataSource`。
- **Git Bash 下跑 Maven 用 classworlds 包装启动**（Bash 禁调 `cmd.exe`，裸 `mvn` sh 脚本会把 MAVEN_HOME 解析成 `/d/...` 导致找不到 classworlds）：`java -classpath "D:/apache-maven-3.9.16/boot/plexus-classworlds-2.11.0.jar" -Dmaven.home="D:/apache-maven-3.9.16" -Dmaven.multiModuleProjectDirectory="D:/Desktop/Data Platform/data-nest" -Dclassworlds.conf="D:/apache-maven-3.9.16/bin/m2.conf" org.codehaus.plexus.classworlds.launcher.Launcher <mvn args>`（Java 25 在 PATH，路径含空格需整体引号；示例：`... Launcher install -DskipTests -pl data-nest-libs/data-nest-common,data-nest-apis/data-nest-system-api,data-nest-services/data-nest-system -am`）。
- **Spring Boot 4 的 AOP starter 已更名 `spring-boot-starter-aspectj`**（原 `spring-boot-starter-aop` 在 4.x BOM 已移除，声明旧名报 version missing）；切面类装配用 `@ConditionalOnClass(name={"org.aspectj.lang.annotation.Aspect","cn.dev33.satoken.stp.StpUtil"})` 避免无 AOP/无 sa-token 服务（gateway/worker/job）误加载。

### 告警

- **告警域全部在 app-alert**（规则/历史/触发/邮件/DAG 告警），前端 `/api/alert/**`；触发链路全部经 Feign（`/alert/internal/**`）：排查先看 app-alert 日志，再看调用方 Feign 异常。
- **`alert_rule.object_type` 与 `alert_history.alert_type` 有 DB CHECK 约束**：新增对象/告警类型需同步改约束 + `AlertRuleService.validate()` 白名单。

### DAG / 条件节点

- **ReactFlow 11 受控 edges/nodes 必须配 onEdgesChange/onNodesChange**（否则边不渲染且无报错）；**页面级 Provider 内第二个 ReactFlow 必须包独立 `ReactFlowProvider`**（否则内层卸载清掉主图）。
- **条件分支 SpEL 用索引语法 `#a['b']`**（SimpleEvaluationContext 无 MapAccessor，属性语法必抛错）；条件表达式不暴露 `dag_id`，但参数解析层保留。
- **执行状态同步走 PowerJob 快照**：状态映射 5→SUCCESS / 4→FAILED / 9,10→TERMINATED / 其余 RUNNING；wf 终态后未运行节点 WAITING→SKIPPED。

### Flyway / 迁移脚本

- **Flyway 是代码驱动**：`spring.flyway` yaml 不生效；各持库服务靠本地 `config/FlywayConfig.java`（`@Bean(initMethod="migrate")` + baselineOnMigrate）。
- **新脚本版本号必须大于本库 `flyway_schema_history` 最高版本**（版本比较忽略尾随零），否则启动失败。
- **迁移脚本统一紧凑单行风格，禁用格式化工具拆行**（破坏已应用 checksum 触发 `Migration checksum mismatch`）。

### 质量

- **质量执行在 app-worker**：改 task-core 质量执行代码后必须重建 **app-worker**（不只是 governance）。
- **质量接口前缀是 `/api/governance/quality/**`**（直接调 `/api/quality/**` 会 404）。
- **结果表 `quality_check_batch` + `quality_check_detail`**；明细 `result_level`（PASS/WARNING/SEVERE/UNAVAILABLE）；**执行层 successCount 与判定层 passCount 等四档语义不同，勿混淆**；UNAVAILABLE 不告警。

### 实时 CDC（Sprint 8/9，独立 Flink 2.2.1 Session 集群）

- **依赖矩阵与 5 大坑已固化**（`flink-s3-fs-hadoop` 只能放 lib 不能放 plugins/、`classloader.resolve-order: parent-first` 写 config.yaml、`classloader.check-leaked-classloader: false`、禁 unaligned checkpoint、S3A 凭据走环境变量 + core-site.xml），详见 gotchas §一。
- **oracle connector 与 postgres connector 不能同时放 lib**；**PG 表必须 REPLICA IDENTITY FULL**；PG 无 earliest-offset 位点；复制权限检查用 `pg_roles.rolreplication OR rolsuper`。
- **Sprint 9 深化**：指标历史 `cdc_metric_minute`（分钟降采样，**严禁 5s 轮询直写**）；Flink REST 新坑（vertex 指标是 double、手动 savepoint body 是 kebab-case、取消作业用 `PATCH /jobs/{id}`）；错误码 8010/8011。详见 gotchas §一。

## 7. 代码与提交约定

> 完整版见 `docs/agent/build-and-deploy.md` §三。

- **代码 Review 目的（2026-08-07 起）**：Review 开发的功能时聚焦三点——① **与当前架构融洽**（不破坏模块边界、依赖方向、服务间调用规则）；② **业务实现正确**（符合 PRD/技术文档语义，边界与异常路径处理到位）；③ **实现高效**（无过度设计、无 N+1/循环远程调用、无不必要的资源开销）。
- 做 **最小改动**，不要顺手重构无关代码；改配置/改接口后同步检查 yaml、Nacos 配置、注释、测试、前端调用点。
- 新增依赖时检查作用域：`provided` 依赖需要在消费方显式声明。
- **创建审计字段约定（2026-08-05 起生效，V3.6.8）**：所有实体 `create` 入口（含批量 create/DAG 节点）**只设置 `setCreatedBy`/`setCreatedAt`，禁止 `setUpdatedBy`/`setUpdatedAt`**；新增带审计字段的表时，其 `updated_at` 不要加 DB 默认值。
- 不要主动运行 `git commit` / `git push`，除非用户明确要求。

## 8. 编码规范索引

- **后端规范**：见 `docs/agent/conventions-backend.md`（技术栈、响应协议/错误码、实体/Mapper/Flyway、Service/Controller/URL、Nacos）。
- **前端规范**：见 `docs/agent/conventions-frontend.md`（技术栈、目录结构、API/错误/状态/路由/样式/分页/通知）。
- 硬约束速记：
  - 后端统一返回 `Result<T>` / `PageResult<T>`，业务错误用 `BusinessException(ErrorCode)`。
  - `Long` 主键序列化为字符串；实体用 `LocalDateTime`；JSONB 字段实体用 `String` + Fastjson2。
  - Flyway 脚本统一紧凑单行风格。
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
