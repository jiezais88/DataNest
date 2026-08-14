# 共享能力复用与下沉规范

> 目标：杜绝「重复造轮子」——写代码先查共享层；发现已有能力立即复用；发现多服务重复实现立即下沉。
> 本文件是 AGENTS.md「共享能力治理」硬约束的详细版。

## 0. 核心原则（3 条）

1. **先查再建（DRY）**：任何通用能力（工具方法、配置类、常量、DTO、校验、回填逻辑）动手写之前，必须先检索 `data-nest-common` / `data-nest-task-core` 是否已有，已有则直接复用，**禁止**在服务本地再写一份。
2. **3 处即下沉**：同一段逻辑（方法体 ≥ 8 行或配置类 ≥ 15 行）在 ≥ 2 个服务出现即视为重复；≥ 3 个服务（或 2 个 + 逐字相同）**必须**下沉到共享层。
3. **共享层最下方**：被多服务共享的能力，落位优先级 `data-nest-common`（纯工具/配置/常量）> `data-nest-task-core`（执行内核 + 服务间调用承载）。**禁止**把共享能力塞进单个服务的本地包。

---

## 1. 已有共享能力清单（2026-08-12 快照）

> 新增能力下沉后必须同步更新本节；写新代码时优先对照本节检索。

### 1.1 data-nest-common（纯工具底座，所有服务可依赖）

| 类别 | 类 | 用途 / 何时复用 |
|------|-----|----------------|
| 响应协议 | `model.Result` / `model.PageResult` | Controller 统一返回信封；分页返回。禁止自建响应类 |
| 异常 | `exception.BusinessException` / `exception.ErrorCode` | 业务异常 + 分区错误码。禁止自建异常体系 |
| 全局处理 | `config.GlobalExceptionHandler` | 统一异常→HTTP/信封。禁止服务自建 `@RestControllerAdvice` |
| 远程调用 | `internal.RemoteCalls.execute(描述, 调用, 降级值)` | 所有 Feign 跨服务调用，读路径降级空集合。禁止裸调用 Feign 不降级 |
| 内部鉴权 | `internal.InternalTokenFilter` | `/internal/**` 端点令牌校验，Feign 拦截器自动加头 |
| Feign | `config.FeignClientsConfiguration` 等 | 服务间调用三件套（loadbalancer/validation/internal 包） |
| **Flyway** | `config.FlywayAutoConfiguration` | 持库服务统一迁移（baselineOnMigrate）。**禁止**服务自建 FlywayConfig |
| **MyBatis-Plus** | `config.MybatisPlusInterceptorAutoConfiguration` | 分页+乐观锁拦截器 + `IdentifierGenerator` 雪花 ID 兜底。**禁止**服务自建 MyBatisPlusConfig |
| **Doris 常量** | `constant.DorisConstants` | `BUILTIN_DORIS_DATASOURCE_ID = -1L` + `BUILTIN_DORIS_NAME`。判内置 Doris 一律用它，**禁止**写 `-1L` 字面量 |
| 其他常量 | `constant.*`（AlertConstants/DataSourceType/SourceType/QualityScoreConstants 等） | 跨服务共享枚举/常量。新增跨服务常量放这里 |
| **JDBC URL** | `util.JdbcUrlBuilder.buildJdbcUrl(type,host,port,db,schema[,socketTimeout])` | 构造 MySQL/Doris/PG/Oracle/SQLServer URL。**禁止**服务自拼 URL |
| **值格式化** | `util.JdbcPreviewHelper.formatValue` / `classifyError` | JDBC 结果值类型格式化 + SQL 异常友好归类。**禁止**服务自写 |
| 配置 | `config.EncryptionConfig` / `satoken.SaTokenCommonAutoConfiguration` / `jackson.JacksonConfig` / `config.DocsAutoConfiguration` | 加密、登录态、Long→String 序列化、OpenAPI 文档，均统一 |

### 1.2 data-nest-task-core（执行内核 + 服务间调用承载）

| 类别 | 类 | 用途 / 何时复用 |
|------|-----|----------------|
| **用户名反查** | `support.SystemUserResolver.usernames(SystemUserApi, Collection<Long>)` | 批量 userId→username，失败降级空 Map。所有服务回填用户名一律用它，**禁止**服务自写 `usernames()`/内联 RemoteCalls 反查 |
| **SQL 分类** | `service.SqlStatementSplitter.classify(sql)` | 四分类 QUERY/DDL/DML/UNKNOWN。DAG/SQL 预览判语句类型用它 |
| SQL 拆分 | `service.SqlStatementSplitter.split(sql)` | 多语句拆分 |
| Doris 执行 | `service.DorisSqlExecutor`（`openConnection()` 公开） | 内置 Doris 查询/连接（HikariCP 优先+DriverManager 降级） |
| 通用执行 | `service.GenericSqlExecutor` | 外部源 JDBC 执行 |
| Python | `service.PythonExecutor` / `service.PythonConnectionResolver` | Python 质量规则沙箱 + 连接解析 |
| 同步/采集/质量 | `service.SyncJobExecutorService` / `CollectExecutor` / `QualityCheckService` / `AddaxJobService` / `DagParameterResolver` / `DagExecutionSyncService` / `MetadataRegistrationService` / `SqlLineageExtractor` / `SyncNodeMutexService` | 执行内核，被 engineering/worker/governance 共享，改后必须重建所有消费方 |
| dto | `dto.*`（37 个执行契约） | 执行引擎内部参数/结果，跨服务反向 import |

### 1.3 已治理的记录（勿回归）

- FlywayConfig 6 份 → `FlywayAutoConfiguration`（2026-08-12）
- MyBatisPlusConfig 3 份 + task-core 1 份 → `MybatisPlusInterceptorAutoConfiguration`（2026-08-12）
- Doris 数据源 ID 私有常量 8 处 → `DorisConstants`（2026-08-12）
- `usernames()` 私有方法 engineering 6 + governance 8 → `SystemUserResolver`（2026-08-12）
- SQL 分类 3 处 → `SqlStatementSplitter.classify`（2026-08-12）
- PowerJob 装配 2 份 → `PowerJobWorkerSupport.buildWorker`（2026-08-12）
- data-service JDBC 层副本 → `JdbcUrlBuilder`/`JdbcPreviewHelper`/`DorisSqlExecutor.openConnection`（2026-08-12）
- 前端裸 `<select>` 13 处（含 4 处局部 `selectClass` 常量 + 9 处内联 className）→ `DsSelect`（2026-08-14，Sprint 11 下拉收编补漏）

---

## 2. 写代码前强制检查清单（Checklist）

新增任意以下能力前，**必须**先 grep 共享层，命中即复用：

- [ ] Controller 响应包装 → 用 `Result`
- [ ] 分页 → 用 `PageResult` + 共享分页拦截器
- [ ] 抛异常 → 用 `BusinessException(ErrorCode, ...)`
- [ ] 跨服务 Feign 调用 → 用 `RemoteCalls.execute(...)` 降级
- [ ] 回填用户名 → `SystemUserResolver.usernames(...)`
- [ ] 判内置 Doris → `DorisConstants.BUILTIN_DORIS_DATASOURCE_ID`
- [ ] 构造 JDBC URL / 格式化结果值 / 归类 SQL 异常 → `JdbcUrlBuilder` / `JdbcPreviewHelper`
- [ ] SQL 语句类型判断 → `SqlStatementSplitter.classify`
- [ ] 持库服务建 Flyway / MyBatis 配置 → 直接复用共享自动配置，**不要**建本地配置类
- [ ] 加密 / 登录态 / Long→String / OpenAPI 文档 → 共享配置

---

## 3. 下沉判断标准

发现「疑似重复」时按下表决策：

| 场景 | 判定 | 处置 |
|------|------|------|
| ≥ 3 个服务相同实现 | **必下沉** | 按 §4 下沉 |
| 2 个服务逐字相同（≥8 行） | **应下沉** | 按 §4 下沉 |
| 2 个服务逻辑相似但语义有差异（如降级值不同） | 谨慎 | 抽共享 + 保留差异点参数化；若差异是**本质业务语义**（如 `null` vs 空 Map），不强行合并 |
| 仅 1 个服务、且不涉共享能力 | 不算重复 | 保留本地 |
| 共享类依赖某服务独有 Bean | 需权衡 | 用「静态工具 + 参数注入」或「`@ConditionalOnMissingBean` 兜底」解耦，避免共享层反向依赖服务 |

**下沉收益评估**：改造前先算「消除的重复行数 vs 引入的共享层耦合」。若为消除几行重复而让 common/task-core 引入一个重型依赖（如某个 api 模块、某个框架），且收益 < 代价，**保留现状并记录原因**，不要为了下沉而下沉。

---

## 4. 下沉操作步骤（SOP）

### 4.1 落位选择

| 能力性质 | 落位 |
|----------|------|
| 纯工具方法 / 常量 / 通用配置 / 响应 / 异常 | `data-nest-common` |
| 执行内核 / 服务间 Feign 调用承载 | `data-nest-task-core` |
| 仅在服务本地、被 1 服务使用 | 不落位（保留本地） |

### 4.2 编码要求

- 共享类命名要语义化（如 `SystemUserResolver` / `DorisConstants`），注释标注「2026-0X-0X 下沉，收敛来源：xxx」。
- 服务本地原实现删除后，改为**一行委托**共享方法（行为 100% 不变优先），不要复制大段逻辑。
- 共享类若依赖某 Bean（如 Feign client），用「静态方法 + 参数传入」或「构造注入」；自动配置兜底用 `@ConditionalOnMissingBean`（消费方自定义优先）。
- **跨服务共享 DTO 禁止带 LocalDateTime** 路径/查询参数（会被按 locale 格式化），一律 ISO String。

### 4.3 验证与部署

1. 全量编译：`mvn clean package -DskipTests -q`（改到 common/task-core 必须全量，不要只编单模块）。
2. 残留扫描：确认服务本地已无重复实现（grep 原方法签名 / 字面量）。
3. **重建所有消费方**：改共享层后必须 `docker compose build + up -d` 全部相关服务（至少 engineering/worker，涉及治理/质量加 governance/job/system，涉及 data-service 加 data-service）。
4. 检查镜像时间戳确认用了新 jar（防 buildkit 缓存）。
5. 启动日志验证：Flyway 迁移（若动到 Flyway）、Bean 注入（若动到自动配置）、无 `APPLICATION FAILED`。
6. 端到端回归：网关登录 + 触发一条受影响链路。

### 4.4 文档同步（必做）

- 新增/迁移共享类后，**立即**更新：
  - 本文件 §1「已有共享能力清单」（新增行）与 §1.3「已治理记录」；
  - `docs/agent/architecture.md`（若模块职责/依赖变化）；
  - `AGENTS.md`（若影响顶层约定）。
- 若共享能力是**行为变更**（如某服务由本地配置改为共享兜底，导致行为不同），必须先和用户确认，不擅自决定。

---

## 5. 常见反模式（禁止）

1. ❌ 服务本地再写一个 `FlywayConfig` / `MyBatisPlusConfig` / `Result` / `GlobalExceptionHandler` / `usernames()`。
2. ❌ 判内置 Doris 写裸 `-1L` 字面量或服务私有 `DORIS_DATASOURCE_ID` 常量。
3. ❌ 自拼 JDBC URL / 自写结果值格式化 / 自写 SQL 异常归类。
4. ❌ 裸调 Feign 不降级（绕过 `RemoteCalls`）。
5. ❌ 为单个服务独有能力强行下沉，让共享层反向依赖一个重型模块。
6. ❌ 改共享层后只重建单个服务，导致执行节点/其他消费方跑旧代码。

---

## 6. 与现有约定的一致性

- 「共享层改后重建所有消费方」对齐 AGENTS.md §3 构建规则。
- **产品角度纪律**（AGENTS.md 全局纪律）：下沉/复用的取舍本身也要从产品价值出发——当「下沉的工程洁癖」与「产品迭代节奏/用户价值」冲突时，以产品价值优先，不为了架构纯粹而阻塞业务交付。
- 「禁止跨服务共享 Service/Mapper 进程内调用」不受影响：本规范下沉的是**工具/配置/常量/执行内核**，不含 Mapper/实体（实体归 owner 服务本地包，见 architecture.md）。
- 用户名回填：`SysUserService` 仅 app-system 内部用；其它服务统一 `SystemUserResolver`（内部走 `SystemUserApi`），不再各自实现。
