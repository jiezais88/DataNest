# DataNest Agent 工作约定

> 本文件面向 AI Agent，用于跨会话恢复项目上下文。人类开发者也可查阅。

## 1. 项目概览

DataNest 是一个数据平台，技术栈如下：

- **后端**：Java 21 + Spring Boot 4.x，Maven 多模块
- **前端**：独立容器 `app-frontend`（源码目录 `data-nest/data-nest-frontend`），通过 `app-gateway:8080` 统一入口
- **部署**：Docker Compose，所有服务在同一 `datanest-net` 网络
- **配置中心**：Nacos，配置实际存储在 `middleware-mysql` 的 `nacos.config_info` 表
- **调度**：XXL-JOB（官方镜像），数据库为 `datanest_scheduler`（不是 `xxl_job`）
- **目标数仓**：内置 Doris（当前在 `192.168.119.135:9030`）

### 核心模块

| 模块                    | 说明                                                 |
|-------------------------|------------------------------------------------------|
| `data-nest-task-core`   | 同步/采集任务核心逻辑，被 engineering 和 worker 共用 |
| `data-nest-engineering` | 数据工程服务（同步任务 API、DAG API）                |
| `data-nest-worker`      | Addax 实际执行方                                     |
| `data-nest-governance`  | 元数据采集任务、元数据管理、数据标准                 |
| `data-nest-job`         | XXL-JOB executor，平台定时任务                       |
| `data-nest-system`      | 认证、用户、权限                                     |
| `data-nest-gateway`     | 网关入口                                             |
| `data-nest-common`      | 公共组件（SchedulerClient 等）                       |

### 核心容器

| 容器                  | 说明                                            |
|-----------------------|-------------------------------------------------|
| `app-engineering`     | 数据工程服务                                    |
| `app-worker`          | 同步/采集任务执行                               |
| `app-governance`      | 数据治理服务                                    |
| `app-job`             | XXL-JOB executor                                |
| `app-system`          | 系统服务                                        |
| `app-gateway`         | 网关                                            |
| `middleware-mysql`    | MySQL：Nacos、XXL-JOB、DolphinScheduler、业务库 |
| `middleware-postgres` | PostgreSQL：业务主库                            |
| `middleware-nacos`    | Nacos 服务                                      |
| `middleware-xxljob`   | XXL-JOB Admin                                   |
| `middleware-redis`    | Redis                                           |

## 2. 会话约定

- **一个会话一个目标**。避免把技术选型、闲聊、无关 Bug 修复混进主线。
- 回复和说明使用 **中文**；代码注释/提交信息跟随项目现有风格（中文为主）。
- 跨会话恢复上下文时，先读 `docs/sprint<编号>/handoff/sprint-<编号>.md`；如不存在，请用户简述当前目标。
- 每个 Sprint 建议 2~4 个会话：规划/设计、后端实现、前端联调、验证收尾。
- 不要主动运行 `git commit` / `git push`，除非用户明确要求。

### 编码前约定

- **先读代码再动手**。修改代码前必须通过 `Read`/`Grep` 读透相关文件和调用链，不要凭记忆或猜测；特别是 `data-nest-task-core`
  的改动，要确认 engineering、worker 等所有消费方。
- **改接口必须同步前端/文档**。修改 DTO、返回结构、URL 路径、字段含义时，必须同步检查前端调用点和接口文档，避免前后端不一致。

### 文档同步约定

- **全局 `AGENTS.md`**：当项目架构、环境信息、已知坑、构建规则发生变化时更新。判断标准：这个变更如果下个会话不知道，可能会踩坑或做错决策。
- **Sprint Handoff 文档**：每个子会话结束时更新当前 Sprint 的状态看板、Blocker、变更清单、Next Action。
- **Sprint 配套文档**：一个 Sprint 通常包含技术文档、产品文档、UI
  原型。开发过程中如果对需求、接口、字段、页面交互做了微调，必须同步回落到对应文档，保持"代码实现 = 文档描述"。
- **代码与文档不一致时必须询问**：开发过程中经常出现代码已实现但文档/原型未更新的情况。当 Agent 发现当前实现和已有文档、原型存在偏差时，
  **必须暂停并询问用户**"这是有意的临时调整，还是需要同步更新文档？"，不要擅自替用户决定。
- 不必更新的情况：纯临时调试命令、一次性验证、很快被覆盖的小尝试。

### 问题排查约定

- 遇到报错或不确定的问题时，优先检查日志、配置、数据库状态、容器健康度。
- 如果项目内无法快速定位根因， **先加载 `systematic-debugging` 技能，按四阶段法（根因调查 → 模式分析 → 假设验证 →
  实现修复）排查**，禁止未定位根因就尝试修复。
- 在根因调查阶段， **应主动使用 WebSearch 搜索相关错误信息、框架版本兼容性、最佳实践**，而不是凭经验猜测。
- 搜索后把关键结论（来源 URL + 核心判断）记录到当前会话或 Sprint Handoff 中，避免后续重复搜索。

## 3. 构建与部署规则

### 关键原则

- `data-nest-task-core` 是 `data-nest-engineering` 和 `data-nest-worker` 的 **共享模块**。
- **只要改到 task-core，必须同时重新编译并部署 engineering 和 worker**，否则执行节点还是旧代码。

### 常用命令

```bash
cd data-nest
mvn -pl data-nest-task-core,data-nest-engineering,data-nest-worker -am clean package -DskipTests -q
docker compose build app-engineering app-worker
docker compose up -d --no-deps app-engineering app-worker
```

### 注意

- 构建后检查镜像时间戳，确认用了新 jar（遇到过 buildkit 缓存未更新的情况）。
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

| 资源              | 用途                    | 地址/命令                                                                   | 账号/密码                   |
|-------------------|-------------------------|-----------------------------------------------------------------------------|-----------------------------|
| 网关入口          | 所有 API 统一入口       | http://localhost:8080                                                       | -                           |
| admin 登录        | 获取全局 token          | `POST /api/system/auth/login`                                               | admin / admin123            |
| PostgreSQL 业务库 | DataNest 业务主库       | `docker exec -it datanest-middleware-postgres psql -U datanest -d datanest` | datanest / datanest123      |
| MySQL root        | 管理 MySQL 所有库       | `docker exec -it datanest-middleware-mysql mysql -u root -proot123`         | root / root123              |
| MySQL nacos       | 查 Nacos 配置、业务库   | `docker exec -it datanest-middleware-mysql mysql -u nacos -pnacos123`       | nacos / nacos123            |
| Nacos 配置库      | 存储所有 shared-configs | `nacos.config_info` 表（在 middleware-mysql）                               | -                           |
| XXL-JOB Admin     | 调度任务管理            | http://localhost:8088                                                       | admin / 123456              |
| XXL-JOB DB        | XXL-JOB 任务信息        | `datanest_scheduler.xxl_job_info`                                           | -                           |
| Doris 内置        | 目标数仓                | `192.168.119.135:9030`                                                      | root / password             |
| DolphinScheduler  | 工作流调度（当前保留）  | http://localhost:12345                                                      | admin / dolphinscheduler123 |

## 6. 已知坑

- **worker 已补上 caffeine 依赖**；不要回退，否则 `DagExecutionSyncService` 初始化会 `ClassNotFoundException`。
- **Addax writer 配置路径已对齐**：代码读的是 `datanest.addax.writer.*`，不是 `datanest.doris.writer.*`。
- **`writer.database` 兜底已删除**：目标库名由同步任务 `target_database` 决定；为空时直接抛异常。
- **XXL-JOB 任务 ID 可能失效**：如果 admin 侧的任务被手动删除或清理，`sync_job.xxl_job_id`
  会指向不存在的任务，触发时报"任务ID非法"。处理办法：将 `sync_job.xxl_job_id` 置空，下次执行会自动重新注册。
- **Nacos API 可能 401**：直接查 `middleware-mysql` 的 `nacos.config_info` 表更可靠。
- **Doris 是外部主机**：不在 docker-compose 里，部署/清理时不要以为重启容器会影响 Doris。
- **worker 启动 unhealthy 不一定是 caffeine**：如果日志报其他 `ClassNotFoundException`，说明还有 `provided` 依赖没在生活方声明。
- **Addax 执行日志**：worker 容器内 `/opt/addax/log/sync_{sync_job_id}.log` 和生成的 job json
  `/opt/addax/job/job_sync_{sync_job_id}.json` 是排查同步失败的第一现场。
- **Nacos 配置修改后可能不实时生效**：部分服务对 `@Value` 注入无热刷新能力，改完配置后需要重启对应服务。

## 7. 代码与提交约定

- 做 **最小改动**，不要顺手重构无关代码。
- 改配置/改接口后，同步检查 yaml、Nacos 配置、注释、测试、前端调用点。
- 新增依赖时检查作用域：`provided` 依赖需要在消费方显式声明。
- 保持代码和周围风格一致，注释用中文。
- 不要主动运行 `git commit` / `git push`，除非用户明确要求。

## 8. 后端开发规范

### 8.1 技术栈与版本

| 层/组件              | 选型/版本                                                | 说明                                                          |
|----------------------|----------------------------------------------------------|---------------------------------------------------------------|
| JDK                  | 21                                                       | LTS，使用 Record、Pattern 等新特性                            |
| Spring Boot          | 4.0.7                                                    | 配套 Spring Framework 7                                       |
| Spring Cloud         | 2025.1.2                                                 | Gateway + Nacos 服务发现                                      |
| Spring Cloud Alibaba | 2025.1.0.0                                               | Nacos Config / Discovery                                      |
| ORM                  | MyBatis-Plus 3.5.17                                      | PostgreSQL 分页插件已配置                                     |
| 安全/登录            | Sa-Token 1.45.0                                          | Redis 集中式 Token                                            |
| JSON                 | Fastjson2 2.0.52（业务序列化）+ Jackson 3（Spring 默认） | Sprint 3 起 Fastjson2 替代 Jackson ObjectMapper               |
| 数据库迁移           | Flyway 10.22.0                                           | 脚本统一在 `data-nest-system/src/main/resources/db/migration` |
| 密码加密             | Spring Security `PasswordEncoder`（BCrypt）              | `data-nest-system` 已配置                                     |

### 8.2 模块与包结构

每个业务模块（`engineering`/`governance`/`system`/`job`）统一按以下结构组织：

```
com.datanest.<模块>
├── <模块>Application.java        # @SpringBootApplication + @MapperScan
├── config/                      # MybatisPlusConfig 等模块级配置
├── controller/                  # REST API 入口
├── dto/                         # Request / Response / Query DTO
├── service/                     # 业务逻辑
├── entity/                      # MyBatis-Plus 实体（共享实体放在 task-core）
└── mapper/                      # Mapper 接口（共享 Mapper 放在 task-core）
```

实际代码中包结构保持 **扁平按层划分**：`controller`/`service`/`dto`/`config` 直接挂在 `com.datanest.<模块>` 下， 不要引入
`dag/`、`dev/`、`sync/` 等子包，否则会影响 MyBatis Mapper 扫描和依赖方引用。 共享的 `entity`、`mapper`、`service` 集中在
`data-nest-task-core` 的同名包中。

`data-nest-common` 只放跨服务共享内容：

```
com.datanest.common
├── config/GlobalExceptionHandler.java   # 统一异常处理
├── dto/                                 # 少量公共 DTO
├── exception/                           # BusinessException、ErrorCode
├── jackson/JacksonConfig.java           # Long 转 String 序列化
├── model/                               # Result、PageResult、LoginRequest
├── satoken/                             # Sa-Token 公共自动配置
└── util/                                # 公共工具类
```

### 8.3 统一响应协议

所有 Controller 返回统一信封 `com.datanest.common.model.Result<T>`：

```java
public record Result<T>(int code, String message, T data) {
    public static <T> Result<T> ok(T data) { ...}

    public static <T> Result<T> fail(int code, String message) { ...}
}
```

分页返回 `PageResult<T>`：

```java
public record PageResult<T>(List<T> records, long total, long page, long pageSize) {
}
```

约定：

- `code == 200` 表示业务成功；其余为业务错误。
- Controller 直接 `return Result.ok(service.xxx(...))`，不要在 Controller 里 catch 业务异常。
- 无返回值时返回 `Result.ok(null)` 或 `Result.<Void>ok(null)`。

### 8.4 异常与错误码

统一使用 `BusinessException(ErrorCode, [detail], [data])`：

```java
throw new BusinessException(ErrorCode.DATASOURCE_NOT_FOUND, "源数据源不存在: "+id);
```

`ErrorCode` 按模块分区，新增错误码必须落在对应区间：

| 区间 | 模块                   |
|------|------------------------|
| 1xxx | 认证/登录              |
| 2xxx | 用户管理               |
| 3xxx | 数据源                 |
| 4xxx | 数据治理（采集任务等） |
| 5xxx | 数据标准               |
| 6xxx | 批量同步               |
| 7xxx | DAG / 数据开发         |
| 9xxx | 系统内部错误           |

全局异常处理 `GlobalExceptionHandler` 已覆盖：

- `BusinessException` → 返回对应 code/message/data。
- `NotLoginException` → 401。
- `NotRoleException` / `NotPermissionException` → 403。
- `MethodArgumentNotValidException` / `BindException` / `ConstraintViolationException` → 400，取第一条校验错误。
- `Exception` → 500，日志打印堆栈。

### 8.5 参数校验

Request DTO 使用 Jakarta Validation 注解：`@NotBlank`、`@NotNull`、`@Size`、`@Pattern`、`@Min`、`@Max`、`@AssertTrue`。

Controller 方法签名：

```java

@PostMapping
public Result<SyncJobDTO> create(@Valid @RequestBody SyncJobCreateRequest request) { ...}
```

复杂跨字段校验（如 "Cron 触发必须填 Cron 表达式"）用 `@AssertTrue` 方法，不要散落在 Service 里。

### 8.6 实体与数据库

- 主键统一用 `Long`，MyBatis-Plus `@TableId(type = IdType.ASSIGN_ID)` 生成 Snowflake ID。
- 所有 `Long` / `long` 类型通过 `JacksonConfig` 序列化为 **字符串**，防止前端 JS 精度丢失。
- 实体字段驼峰命名，自动映射数据库 `snake_case`。
- 时间字段统一用 `java.time.LocalDateTime`。
- 布尔字段在实体中用 `Boolean`，数据库中用 `SMALLINT` 或 `BOOLEAN` 按 Flyway 脚本约定。
- 涉及 JSONB 的字段（如 `sourceTablesDetail`、`fieldMapping`）在实体中用 `String`，Service 层用 Fastjson2 解析/组装。

### 8.7 Mapper 与 SQL

- Mapper 继承 `BaseMapper<T>`，简单 CRUD 不写 SQL。
- 简单自定义 SQL 优先用注解（`@Select`、`@Insert`、`@Delete`），复杂 SQL 用 `resources/mapper/*.xml`。
- 动态 SQL 用 MyBatis `<script>`，注意 PostgreSQL 关键字转义。
- 分页统一用 MyBatis-Plus `Page<T>` + `IPage<T>`，已在 `MybatisPlusConfig` 配置 PostgreSQL 方言。

### 8.8 Service 层约定

- 使用构造器注入（Lombok  `@RequiredArgsConstructor` 也可用，但项目当前以显式构造器为主）。
- 写操作加 `@Transactional`；涉及 XXL-JOB 注册/更新/注销等外部调用，用
  `TransactionSynchronizationManager.registerSynchronization` 在 `afterCommit` 执行。
- 查询结果需要脱敏或补充创建人/更新人名称时，批量查询后一次性回填，避免 N+1。
- DTO 与 Entity 转换写私有 `toDTO` / `toEntity` 方法，不要直接返回 Entity。

### 8.9 Controller 与 URL 规范

- Controller 加 `@RestController`，类级 `@RequestMapping("/<资源>")`。
- 路径使用 RESTful 风格，动作通过 HTTP 方法 + 路径表达：

```
GET    /datasources/{id}              # 详情
POST   /datasources                   # 创建
PUT    /datasources/{id}              # 更新
DELETE /datasources/{id}              # 删除
POST   /datasources/page              # 分页列表
POST   /datasources/{id}/test         # 动作类接口
```

- 权限注解 `@SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)`，角色代码与前端
  `src/constants/roles.ts` 保持一致；左侧菜单显隐以 `src/components/Sidebar.tsx` 为准。
- 网关路由：`/api/system/**` → `data-nest-system`，`/api/engineering/**` → `data-nest-engineering`，`/api/governance/**` →
  `data-nest-governance`。
- 微服务 `context-path` 分别为 `/system`、`/engineering`、`/governance`，Controller 路径不要重复写前缀。
- **列表接口**：当前代码实现多为 `POST /{resource}/page`（如 `/api/engineering/datasources/page`、
  `/api/engineering/sync-jobs/page`），请求体带 keyword + 筛选 + 分页；新增/详情/删除仍用 RESTful 方法表达。
- 工程侧 Controller 前缀：数据源/同步任务为 `/engineering/*`，DAG/项目管理为 `/dev/*`，执行历史为 `/dag-executions`； 网关已配置
  StripPrefix，前端统一以 `/api/engineering/...` 调用。

### 8.10 配置与 Nacos

- `application.yml` 只保留端口、`spring.application.name`、`context-path`、`spring.config.import` 和模块级简单配置。
- 数据库、Redis、Doris、XXL-JOB、Addax、安全等配置走 Nacos `shared-configs`。
- 新增配置项优先放到对应 shared-config，不要硬编码在 `application.yml`。
- 环境变量默认值写法：`${NACOS_HOST:localhost}:${NACOS_PORT:8848}`。

### 8.11 task-core 共享模块

- `data-nest-task-core` 是 `data-nest-engineering` 和 `data-nest-worker` 的共享模块。
- **只要改到 task-core，必须同时重新编译并部署 engineering 和 worker**。
- task-core 中的 `entity`、`mapper`、`service` 会被两个服务共同扫描，注意 Bean 冲突和事务边界。

## 9. 前端开发规范

### 9.1 技术栈与版本

| 层/组件   | 选型/版本               | 说明                            |
|-----------|-------------------------|---------------------------------|
| 框架      | React 18.3              | 函数组件 + Hooks                |
| 语言      | TypeScript ~5.6         | `strict: true`                  |
| 构建工具  | Vite 5.4                | 开发服务器端口 3000             |
| UI 组件库 | Ant Design 6            | 主题/样式通过 `tokens.css` 覆盖 |
| 样式      | Tailwind CSS 3.4        | 自定义 `ds-*` 设计 token        |
| 路由      | React Router 6          | `createBrowserRouter`           |
| 状态管理  | Zustand 5               | 当前仅 `useAuthStore`           |
| HTTP      | Axios 1.18              | 统一封装在 `src/api/request.ts` |
| 图标      | react-icons (Heroicons) | 统一用 `HiOutline*` 系列        |
| 代码规范  | ESLint 9 flat config    | `eslint.config.js`              |

### 9.2 目录结构

```
src
├── api/               # 按模块封装的 API（auth.ts、sync.ts、engineering.ts...）
│   └── request.ts     # axios 统一实例 + 拦截器
├── components/        # 全局通用组件（DsButton、DsModal、Pagination...）
├── constants/         # 常量：roles.ts、datasource.ts、table.ts、statusColors.ts...
├── hooks/             # 通用 Hooks：usePagedList、useHasRole、useCanEdit、usePollingWhile
├── lib/               # 第三方封装或工具库
├── pages/             # 页面组件，按模块分 engineering/governance/system/home/login
├── router/            # 路由配置 + 路由组件（ProtectedRoute、LazyDagEditor）
├── store/             # Zustand store
├── styles/            # tokens.css（颜色唯一来源）
├── types/             # TypeScript 类型：common.ts、sync.ts、datasource.ts...
└── utils/             # 工具函数：notify.ts、error.ts、format.ts、cn.ts...
```

### 9.3 API 请求规范

统一使用 `src/api/request.ts` 导出的 `request`：

```ts
import request from './request';

export function getSyncJob(id: string) {
    return request.get<Result<SyncJob>>(`/engineering/sync-jobs/${id}`);
}
```

约定：

- `baseURL = '/api'`，gateway 自动路由到对应服务。
- 响应拦截器校验 `code !== 200` 时统一弹错误提示并 `reject`； **不拆信封**，返回的是 `{code, message, data}` 本身。
- API 层通过 `.then(r => r.data)` 拆信封，与 `request.get<Result<T>>` / `request.post<Result<T>>` 的泛型配合。
- 需要自行处理错误时传 `{skipErrorMessage: true}`（如 SQL 预览行内展示错误、DAG 运行日志轮询）。
- 19 位 Snowflake ID 全程用 `string` 类型， **不要** `Number(id)`，避免精度丢失。

### 9.4 错误处理

- 普通接口错误由 `request.ts` 统一弹出 `notify.error`，页面无需重复提示。
- 需要取错误文案时用 `getErrorMessage(e)`：

```ts
import {getErrorMessage} from '../utils/error';

catch
(err)
{
    notify.error(getErrorMessage(err));
}
```

- 401 时拦截器自动清除 token 并跳 `/login`。

### 9.5 状态管理

- 全局状态统一用 Zustand，当前只有 `useAuthStore`。
- 列表页状态不走全局 store，页面内用 `useState` + `usePagedList`。
- token / userInfo 持久化到 `localStorage`，key 名统一在 store 中定义。

### 9.6 路由与权限

- 路由定义在 `src/router/index.tsx`，使用 `createBrowserRouter`。
- 需要登录的页面用 `<ProtectedRoute>` 包裹。
- 角色判断用 `useHasRole(...roles)` 或 `useCanEdit()`，角色代码从 `src/constants/roles.ts` 引入，不要硬编码字符串。
- **菜单权限唯一出处**：`src/components/Sidebar.tsx` 中的 `allMenus` + `src/constants/roles.ts` 中的角色数组；
  PRD/原型中的权限矩阵必须与此二者保持一致。

### 9.7 UI 与样式规范

- **颜色唯一来源**：`src/styles/tokens.css` `:root` 变量。新增颜色先加变量，再在 `tailwind.config.js` 桥接，不要写死 hex。
- Tailwind 使用项目自定义 token：`ds-bg-root`、`ds-text-primary`、`ds-accent`、`ds-danger` 等。
- 字体、字号、间距、圆角、阴影、z-index 等均使用 `ds-*` token。
- antd Table 统一用 `className="prototype-table prototype-table-flush"` + `pagination={false}`，分页用手写
  `components/Pagination`。
- 弹窗统一用 `components/DsModal`，按钮用 `components/DsButton`，状态徽章用 `components/DsStatusBadge`。
- 表格列宽参考 `src/constants/table.ts` 中的 `COL`，同类列在不同页面保持相近宽度。
- **源码全部为 `.tsx`**，不要新增 `.jsx`；图标统一使用 `react-icons`（以 `HiOutline*` 系列为主）。

### 9.8 列表页与分页

统一使用 `src/hooks/usePagedList.ts`：

```ts
const {list, total, page, pageSize, loading, setPage, setPageSize, applyQuery, reload} =
    usePagedList<DataSourceQuery, DataSource>({
        fetcher: async ({keyword, page, pageSize}) => {
            const result = await getDataSources({keyword, page, pageSize});
            return {list: result.data.records, total: result.data.total};
        },
        initialQuery: INITIAL_QUERY,
        defaultPageSize: 10,
    });
```

- 查询按钮调用 `applyQuery(draftQuery)`。
- 重置按钮调用 `applyQuery(INITIAL_QUERY)`。
- 增删改成功后调用 `reload()`。

### 9.9 消息提示

统一使用 `src/utils/notify.ts`：

```ts
import {notify} from '../utils/notify';

notify.success('操作成功');
notify.error('操作失败');
```

不要直接 `import {message} from 'antd'`，避免静态 message 无法消费动态主题上下文。

### 9.10 类型定义

- 后端协议类型统一放在 `src/types/common.ts`：`Result<T>`、`PageResult<T>`、`PagedQuery`。
- 各业务类型按模块分文件：`sync.ts`、`datasource.ts`、`metadata.ts` 等。
- API 函数签名使用泛型：`request.get<Result<SyncJob>>(...)`。

### 9.11 构建与部署

- 本地开发：`pnpm dev` / `npm run dev`（Vite dev server 端口 3000，代理 `/api` 到 `http://localhost:8080`）。
- 类型检查：`pnpm typecheck` / `npm run typecheck`。
- 构建：`pnpm build` / `npm run build`（会执行 `tsc -b && vite build`）。
- 生产部署：Docker 镜像基于 `nginx:alpine`，`dist/` 产物挂载到 `/usr/share/nginx/html/`。
- 生产构建会 `drop_console` 和 `drop_debugger`。

## 10. 前后端联调约定

- 所有请求统一走 Gateway：`http://localhost:8080/api/<服务>/<路径>`。
- 后端 `Long` 类型主键会序列化为字符串，前端类型声明用 `string`，URL 拼接不要转 Number。
- **列表/分页接口**：优先用 `POST /.../page`（如 `/api/engineering/sync-jobs/page`），不要用 `GET` 列表； DAG 执行历史等场景用
  `GET` + query params（如 `/api/engineering/dag-executions`）。
- **命名统一**：批量数据同步任务在代码/路由/API/表中均为 `sync-jobs`（不是 `sync-tasks`），DAG 菜单在代码中为「项目管理」。
- 修改 DTO、返回结构、URL 路径、字段含义时，必须同步检查：
    1. 后端 Controller / Service / DTO
    2. 前端 `src/api/*` 调用点
    3. 前端 `src/types/*` 类型
    4. 相关页面组件
    5. 接口文档 / Sprint 文档
- 新增接口先在 Postman/curl 自测通过再联调前端。
- 分页字段：`page` 从 1 开始，`pageSize` 默认 10。

## 11. 安全与敏感信息

- 密码、token、密钥等敏感信息 **禁止硬编码**到代码或配置文件中；应走 Nacos 配置或环境变量注入。
- 日志中禁止打印密码、完整 token、数据库连接串密码部分；打印 DTO 时先脱敏敏感字段。
- 前端构建产物中不要包含 `.env.development` 等本地配置。
- 后端接口必须加 `@SaCheckRole` 等权限控制，匿名接口需经评审。
