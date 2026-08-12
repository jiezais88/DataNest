# DataNest 后端开发规范

> 本文件是 AGENTS.md §8 的详细版。核心硬约束见 AGENTS.md 正文，本文件供按需查阅。

## 1. 技术栈与版本

| 层/组件 | 选型/版本 | 说明 |
|---------|-----------|------|
| JDK | 25 | LTS（2026-08-11 由 21 升级；Record、Pattern 等新特性） |
| Spring Boot | 4.0.7 | 配套 Spring Framework 7 |
| Spring Cloud | 2025.1.2 | Gateway + Nacos 服务发现 |
| Spring Cloud Alibaba | 2025.1.0.0 | Nacos Config / Discovery |
| ORM | MyBatis-Plus 3.5.17 | PostgreSQL 分页插件已配置 |
| 安全/登录 | Sa-Token 1.45.0 | Redis 集中式 Token |
| JSON | Fastjson2 2.0.52（业务序列化）+ Jackson 3（Spring 默认） | Sprint 3 起 Fastjson2 替代 Jackson ObjectMapper |
| 数据库迁移 | Flyway 10.22.0 | 每服务独立管理本库 `src/main/resources/db/migration`（基线 V1.0.0），代码驱动（共享 `FlywayAutoConfiguration`，2026-08-12 下沉，禁止自建 FlywayConfig） |
| 密码加密 | Spring Security `PasswordEncoder`（BCrypt） | `data-nest-system` 已配置 |

## 2. 统一响应协议

所有 Controller 返回统一信封 `com.datanest.common.model.Result<T>`：

```java
public record Result<T>(int code, String message, T data) {
    public static <T> Result<T> ok(T data) { ... }
    public static <T> Result<T> fail(int code, String message) { ... }
}
```

分页返回 `PageResult<T>`：`record PageResult<T>(List<T> records, long total, long page, long pageSize)`

约定：
- `code == 200` 表示业务成功；其余为业务错误。
- Controller 直接 `return Result.ok(service.xxx(...))`，不要在 Controller 里 catch 业务异常。
- 无返回值时返回 `Result.ok(null)` 或 `Result.<Void>ok(null)`。

## 3. 异常与错误码

统一使用 `BusinessException(ErrorCode, [detail], [data])`：

```java
throw new BusinessException(ErrorCode.DATASOURCE_NOT_FOUND, "源数据源不存在: " + id);
```

`ErrorCode` 按模块分区，新增错误码必须落在对应区间：

| 区间 | 模块 |
|------|------|
| 1xxx | 认证/登录 |
| 2xxx | 用户管理 |
| 3xxx | 数据源 |
| 4xxx | 数据治理（采集任务等） |
| 5xxx | 数据标准 |
| 6xxx | 批量同步 |
| 7xxx | DAG / 数据开发 |
| 9xxx | 系统内部错误 |

全局异常处理 `GlobalExceptionHandler` 已覆盖：`BusinessException`→对应 code；`NotLoginException`→401；`NotRoleException`/`NotPermissionException`→403；`MethodArgumentNotValidException`/`BindException`/`ConstraintViolationException`→400（取第一条校验错误）；`Exception`→500。

## 4. 参数校验

Request DTO 使用 Jakarta Validation 注解：`@NotBlank`、`@NotNull`、`@Size`、`@Pattern`、`@Min`、`@Max`、`@AssertTrue`。Controller 方法签名加 `@Valid @RequestBody`。复杂跨字段校验（如 "Cron 触发必须填 Cron 表达式"）用 `@AssertTrue` 方法，不要散落在 Service 里。

## 5. 实体与数据库

- 主键统一用 `Long`，MyBatis-Plus `@TableId(type = IdType.ASSIGN_ID)` 生成 Snowflake ID。
- 所有 `Long`/`long` 类型通过 `JacksonConfig` 序列化为 **字符串**，防止前端 JS 精度丢失。
- 实体字段驼峰命名，自动映射数据库 `snake_case`。
- 时间字段统一用 `java.time.LocalDateTime`。
- 布尔字段在实体中用 `Boolean`，数据库中用 `SMALLINT` 或 `BOOLEAN` 按 Flyway 脚本约定。
- 涉及 JSONB 的字段（如 `sourceTablesDetail`、`fieldMapping`）在实体中用 `String`，Service 层用 Fastjson2 解析/组装。
- **实体/mapper 归 owner 服务本地包**（`com.datanest.<域>.entity/mapper`），不再共享实体模块（`data-nest-task-core-entity` 已删除）。跨服务取数一律走对应 Feign 契约模块，禁止跨域直读表。

### Flyway 脚本格式约定（硬约束）

- **每服务独立管理本库迁移**：脚本在各持库服务自己的 `src/main/resources/db/migration`（system/alert-service/engineering/governance），基线为 `V1.0.0__baseline.sql`，后续各自从 `V1.1.0+` 独立演进。worker/job/gateway 无库无迁移。
- **Flyway 是代码驱动的**：项目 jar 里没有 spring-boot-flyway autoconfigure 模块，`spring.flyway` 的 yaml **不生效**；持库服务统一由 common 的 `FlywayAutoConfiguration`（@Bean initMethod=migrate，baselineOnMigrate，`@ConditionalOnClass(Flyway)` + 方法级 `@ConditionalOnBean(DataSource)`）触发。**禁止再自建本地 FlywayConfig**。新增迁移只需放脚本 + 重启对应服务。
- 旧单库时代的 72 个迁移脚本已归档 `scripts/migration-legacy/`，仅供追溯，不再执行。
- **所有迁移脚本统一用紧凑单行 SQL 风格**（如 `id BIGSERIAL PRIMARY KEY,`、`VARCHAR(100) NOT NULL`、`COMMENT ON COLUMN x IS '...'` 单行）。
- **禁止用 IDE/格式化工具拆分迁移 SQL**：格式化工具会破坏已应用脚本的 checksum，触发 Flyway validate 失败（见 gotchas.md）。
- 已应用脚本**不要随意改动**；确需调整格式/语义时，必须用 flyway `repair` 固化 checksum 并重启对应服务验证。

## 6. Mapper 与 SQL

- Mapper 继承 `BaseMapper<T>`，简单 CRUD 不写 SQL。
- 简单自定义 SQL 优先用注解（`@Select`、`@Insert`、`@Delete`），复杂 SQL 用 `resources/mapper/*.xml`。
- 动态 SQL 用 MyBatis `<script>`，注意 PostgreSQL 关键字转义。
- 分页统一用 MyBatis-Plus `Page<T>` + `IPage<T>`，已在 `MybatisPlusConfig` 配置 PostgreSQL 方言。

## 7. Service 层约定

- 使用构造器注入（Lombok `@RequiredArgsConstructor` 也可用，但项目当前以显式构造器为主）。
- 写操作加 `@Transactional`；涉及 XXL-JOB 注册/更新/注销等外部调用，用 `TransactionSynchronizationManager.registerSynchronization` 在 `afterCommit` 执行。
- 查询结果需要脱敏或补充创建人/更新人名称时，批量查询后一次性回填，避免 N+1。
- DTO 与 Entity 转换写私有 `toDTO` / `toEntity` 方法，不要直接返回 Entity。

### 定时任务规范（2026-08-12 起，硬约束）

- **业务服务本地禁止 `@Scheduled` / `@EnableScheduling`**（用户拍板：本地不能有 @Scheduled，全部放到 app-job）。
- 所有定时任务（历史清理、状态刷新、定时扫描等）统一放 `data-nest-job`：
  1. 业务逻辑下沉目标服务 `/internal/**` 端点（如 `POST /data-service/internal/sql-history/cleanup`），由 `InternalTokenFilter` 鉴权；
  2. 在 `*-api` 契约模块新增对应 Ops 契约（`@FeignClient` + fallbackFactory + DTO），消费方启动类追加 scanBasePackages/EnableFeignClients；
  3. `job` 侧写 `PlatformJobHandler` 实现（`getName()` = handler 名，`RemoteCalls.execute` 容错，失败抛 `IllegalStateException` 本轮跳过下轮重试）；
  4. `JobRegistrar.platformJobs` 注册 cron（`handler -> "cron"`）+ `resolveJobDesc` 加中文描述。
- 反例：realtime 的 `MetricRetentionCleaner`（本地 `@Scheduled`）为历史遗留，Sprint 10 起新代码禁止再这样写；清理类任务保留天数配置统一 `datanest.job.<xx>-cleanup.retain-days`，服务端 internal 端点再做兜底默认值。

## 8. Controller 与 URL 规范

- Controller 加 `@RestController`，类级 `@RequestMapping("/<资源>")`。
- 路径使用 RESTful 风格，动作通过 HTTP 方法 + 路径表达：`GET /datasources/{id}`、`POST /datasources`、`PUT /datasources/{id}`、`DELETE /datasources/{id}`、`POST /datasources/page`、`POST /datasources/{id}/test`。
- 权限注解 `@SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)`，角色代码与前端 `src/constants/roles.ts` 保持一致；左侧菜单显隐以 `src/components/Sidebar.tsx` 为准。
- 网关路由：`/api/system/**` → `data-nest-system`，`/api/engineering/**` → `data-nest-engineering`，`/api/governance/**` → `data-nest-governance`，`/api/alert/**` → `data-nest-alert-service`。
- 微服务 `context-path` 分别为 `/system`、`/engineering`、`/governance`、`/alert`（worker `/worker`、job `/job` 无对外 Controller），Controller 路径不要重复写前缀。
- **列表接口**：当前代码实现多为 `POST /{resource}/page`（如 `/api/engineering/datasources/page`、`/api/engineering/sync-jobs/page`），请求体带 keyword + 筛选 + 分页；新增/详情/删除仍用 RESTful 方法表达。
- 工程侧 Controller 前缀：数据源/同步任务为 `/engineering/*`，DAG/项目管理为 `/dev/*`，执行历史为 `/dag-executions`；网关已配置 StripPrefix，前端统一以 `/api/engineering/...` 调用。
- **导出统一规范（2026-08-11 起为 xlsx；2026-08-12 列宽/表头自动适配）**：导出端点一律 `void` 返回 + 直写 `HttpServletResponse` 响应流，**禁止** `ResponseEntity<byte[]>` 内存拼装返回。写法：Controller 设 `Content-Disposition`（ASCII 兜底名 + RFC5987 中文名）与 `Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`，然后把 `response.getOutputStream()` 传给 Service；Service 用 **Apache POI**（`poi-ooxml`，不要引 `poi-ooxml-full` 全量 schema 包；`poi-ooxml-lite` 只是 schema jar 不含工作簿类）统一经 `governance/util/XlsxExportHelper`：SXSSFWorkbook 流式写（滚动 500 行）、**表头行用 `writeHeaderRow`**（加粗 + 浅灰底，参与列宽累计）、**数据行用 `writeRow`**（自动按内容累计列宽，数字列也按数值宽度估算避免 `####`），收尾 `applyColumnWidths`（**不要用 `autoSizeColumn`**——headless 容器无字体度量会失效；列宽估算分档加权：CJK/全角记 2，数字 0.6/小写 0.75/大写 0.85/空格 0.5，封顶 60 字符）、只 write 不 close 响应流；先把数据查完再开始写流，写流前的业务异常仍由全局异常处理器返回 JSON 错误。**时间单元格必须过 `XlsxExportHelper.time()`**（用户约定：呈现给用户的时间一律 `yyyy-MM-dd HH:mm:ss` 或 `yyyy-MM-dd`，禁止 `LocalDateTime.toString()` 的 ISO 带 T 格式）；**枚举值必须经 `ExportLabels` 转中文标签**（未知值原样兜底）；字符串经 POI 显式写 STRING 单元格，天然无公式注入面（CSV 时代的 `safe()` 前置单引号已随 `CsvExportHelper` 一并退役删除）。现有范例：质量报告 `QualityReportController.export`、我的收藏 `AssetCatalogController.exportMyFavorites`、合规检查 `DataStandardController.exportComplianceCheck`。

## 9. 服务间调用规范（微服务化后）

- **跨服务取数/写数一律走 Feign 契约模块**（`data-nest-apis/` 下 alert-api / engineering-api / governance-api / system-api），禁止跨域直读表、禁止跨域 SQL JOIN。
- **契约模块写法**：`@FeignClient` 接口 + `fallbackFactory`（fallback 包内 @Component 实现）+ DTO 独立（契约 DTO 放 api 模块自己的包，不依赖服务方实体）。消费方 pom 声明 api 模块依赖，启动类 `@EnableFeignClients(basePackages)` 追加对应 api 包，scanBasePackages 追加 fallback 所在包。
- **服务端点统一放 `/internal/**` 路径**（context-path 之内），由 common 的 `InternalTokenFilter` + `X-Internal-Token` 鉴权；token 经 Nacos `shared-rpc.yaml` 下发，本地未配置时放行。
- **降级统一走 common `RemoteCalls.execute(description, supplier, fallback)`**，不要手写 try-catch 样板。读路径降级空集合/空 Map；告警 fire 等旁路失败只记日志。
- **fail-closed 例外清单**（远端不可用必须拒绝操作，改动时注意保持）：`QualityJobService` 删除前告警引用校验、`AssetCatalogService.assignOwner` 用户存在性校验、`AlertRuleService` 保存规则时对象名解析。
- **禁 N+1**：循环里需要另一服务的数据时，在提供方加批量端点一次取回（如 `nodes/resolve`、`auto-trigger/batch`、`usernames` 批量回填），禁止逐条循环调 Feign。
- **Feign 查询/路径参数禁止用 `LocalDateTime`**（Feign 的 ConversionService 会按 locale 格式化，服务端 ISO 解析失败）——契约参数一律用 ISO String，调用方 `DateTimeFormatter.ISO_LOCAL_DATE_TIME` 格式化。请求体里的 LocalDateTime 走 Jackson 不受影响。
- 新增 Feign client 时确认消费方 pom 有 `spring-cloud-starter-loadbalancer`（`lb://` 必需），缺了启动报 `No Feign Client for loadBalancing defined`。

## 10. 配置与 Nacos

- `application.yml` 只保留端口、`spring.application.name`、`context-path`、`spring.config.import` 和模块级简单配置。
- 数据库、Redis、Doris、XXL-JOB、Addax、安全等配置走 Nacos `shared-configs`。
- 新增配置项优先放到对应 shared-config，不要硬编码在 `application.yml`。
- 环境变量默认值写法：`${NACOS_HOST:localhost}:${NACOS_PORT:8848}`。

## 11. 接口文档注解（springdoc，2026-08-09 起）

- **选型**：springdoc-openapi 3.0.x（Boot 4 线），不用 Knife4j（未适配 Boot 4）。聚合入口 `http://localhost:8080/swagger-ui.html`（网关 swagger-ui 右上角切服务）；实现与坑见 AGENTS.md §6 / gotchas §一。
- **注解标准**（样板：`system` 服务的 `UserController`/`UserVO`）：
  - Controller 类级 `@Tag(name = "中文模块名", description = "一句话")`；端点方法 `@Operation(summary = "中文动作短语")`（复杂语义加 description）。
  - 所有 `@PathVariable`/`@RequestParam` 加 `@Parameter(description=…)`；`@RequestBody` 不加。
  - DTO（class/record）类级 + 字段级 `@Schema(description=…)`；ID 字段加 `example = "1234567890123456789"`；枚举型字符串字段描述里列候选值（必须核实代码来源，不编造）；时间字段注明 ISO 8601。
  - 被 `@Operation` 取代的冗余单行 javadoc 删除；含 ADR/决策/调用链说明的 javadoc 保留。
- **Long/long 不写 `type="string"`**：common `DocsAutoConfiguration` 静态块用 `SpringDocUtils.replaceWithClass` 全局渲染为 string（对齐 Jackson Long→String）。
- **internal 端点（`/internal/**` Feign 契约）不进文档**：类级 `@Hidden`（已加，新 internal Controller 照做）。
- **新服务接文档三步**：引 `springdoc-openapi-starter-webmvc-ui` → application.yml 配 `datanest.docs.title/gateway-prefix` → 网关 `springdoc.swagger-ui.urls` 加一行。
- 共享 DTO 模块（common/task-core）的 swagger 注解依赖为 `provided`（`swagger-annotations-jakarta`），消费方经 springdoc starter 传递引入。

## 12. 日志

- **公用 logback 配置（生产级）**：`data-nest-common/src/main/resources/logback-spring.xml`，随 common 进入全部后端服务（gateway/system/engineering/governance/worker/job）classpath 根自动生效。
- **三通道**：console（docker logs 在线排查）+ 全量滚动文件 + ERROR 单独滚动文件（排障第一入口）；文件输出全部走 **AsyncAppender 异步队列**（queueSize 8192、`neverBlock=true` 队列满丢弃不阻塞业务、`discardingThreshold=0` 不主动丢 INFO）。
- **滚动策略**：按天 + 单文件 100MB、gzip、保留 30 天、总量上限（全量 3GB / 错误 1GB）。
- **文件位置**：默认 `./logs/<服务名>.log`（容器工作目录下），可经 `logging.file.path`（Nacos）覆盖；**容器重建文件即丢**，长期留存需挂卷或接日志采集。
- **日志级别不写死在 logback 文件**：仍由 Nacos `shared-common.yaml` 的 `logging.level.*` 控制（Boot 的 `LoggingApplicationListener` 对自定义 logback 配置继续生效）。
- 某服务需自定义日志时，在自身 `src/main/resources` 放同名 `logback-spring.xml` 覆盖（classes 目录优先于依赖 jar）。
- 改动该文件等于改 common，**必须全量重建所有后端服务镜像**才生效。
