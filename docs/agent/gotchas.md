# DataNest 已知坑（完整版）

> 本文件是 AGENTS.md §6 的详细版。**已解决的临时坑只在此记录**（供复现时查阅），当前仍有效的坑在 AGENTS.md 正文 §6 保留精简版。新增坑请先记录到本文件，若影响后续开发则提升到 AGENTS.md 正文。

## 一、结构性与环境硬事实（长期有效，AGENTS.md 正文保留精简版）

- **worker 已补上 caffeine 依赖**；不要回退，否则 `DagExecutionSyncService` 初始化会 `ClassNotFoundException`。
- **Addax writer 配置路径已对齐**：代码读的是 `datanest.addax.writer.*`，不是 `datanest.doris.writer.*`。
- **`writer.database` 兜底已删除**：目标库名由同步任务 `target_database` 决定；为空时直接抛异常。
- **XXL-JOB 任务 ID 可能失效**：`sync_job.xxl_job_id` 若指向已删除/清理的任务，触发时报"任务ID非法"。处理：将 `sync_job.xxl_job_id` 置空，下次执行自动重新注册。
- **Nacos API 可能 401**：直接查 `middleware-mysql` 的 `nacos.config_info` 表更可靠。
- **Doris 是外部主机**：不在 docker-compose 里，部署/清理时不要以为重启容器会影响 Doris。
- **worker 启动 unhealthy 不一定是 caffeine**：若日志报其他 `ClassNotFoundException`，说明还有 `provided` 依赖没在消费方声明。
- **Addax 执行日志**：worker 容器内 `/opt/addax/log/sync_{sync_job_id}.log` 和生成的 job json `/opt/addax/job/job_sync_{sync_job_id}.json` 是排查同步失败的第一现场。
- **Nacos 配置修改后可能不实时生效**：部分服务对 `@Value` 注入无热刷新能力，改完配置后需重启对应服务。
- **MailHog 清空**：`DELETE http://localhost:8025/api/v1/messages`（v2 端点会 404）。
- **Windows Git Bash 下 curl 自测中文接口有坑**（2026-08-06 F1 自测踩）：① curl 命令行内联中文 JSON body 会按本地编码（GBK）发出，后端 Jackson 报 `Invalid UTF-8 middle byte`（表象是 9999 系统内部错误）；② curl `--data-binary @/tmp/xxx.json` 读不到 Git Bash 的 `/tmp` 路径（Windows curl 不识别）。**做法**：用 Python `urllib` 写自测脚本（body `ensure_ascii=False` + `encode('utf-8')`，query 用 `urllib.parse.quote` 百分号编码，终端加 `PYTHONIOENCODING=utf-8` 防控制台乱码）。另外登录接口返回结构是 `data.token`（不是 data 直接为 token 字符串）。
- **一个 AsyncAppender 只能挂一个 appender**（2026-08-06 logback 配置踩）：logback 硬限制，挂第二个时启动日志报 `One and only one appender may be attached to AsyncAppender. Ignoring additional appender named [...]`，被忽略的 appender 文件**会创建但永远 0 字节**（表象极具迷惑性：ERROR 在主文件里有、error 文件却是空的）。做法：每个文件 appender 各包一层 AsyncAppender（见 `data-nest-common/src/main/resources/logback-spring.xml`）。排查 logback 行为异常先看容器启动日志里的 `|-WARN in ch.qos.logback...` 行。
- **common 的 `GlobalExceptionHandler` 是 MVC 专属，WebFlux 网关不能注册**（2026-08-05 部署时发现）：`GlobalExceptionHandler` 是 `@RestControllerAdvice`，其 `@ExceptionHandler(NoResourceFoundException.class)` 引用了 `org.springframework.web.servlet.*` 类型；网关是 WebFlux（无 `spring-webmvc`），WebFlux 的 `RequestMappingHandlerAdapter` 反射 introspect 该 advice 方法签名时 `NoClassDefFoundError: NoResourceFoundException` → 网关启动失败。修复：`CommonExceptionAutoConfiguration`（`@AutoConfiguration`）加 `@ConditionalOnWebApplication(type = SERVLET)`，WebFlux 下不注册。**教训**：`@ConditionalOnWebApplication` 等条件注解必须放在 `@AutoConfiguration`/`@Configuration` 配置类上才生效，放在 `@RestControllerAdvice`/`@Component` 这类被组件扫描或 `@Bean` 注册的普通类上无效。后续给 common 新增 MVC 专属 bean 时注意网关(WebFlux)兼容。

## 二、告警相关（当前有效，AGENTS.md 正文保留精简版）

- **【已过时，微服务化后不再成立】告警邮件需要 worker/job/governance 也配邮件**：阶段 1 起邮件配置已集中到 app-alert（shared-alert.yaml 下沉其本地配置），其余服务不再 import shared-alert、不配 `MAIL_*` 环境变量；告警 fire 全部经 alert-api Feign 调用。`data-nest-alert` 模块也已删除。本地 MailHog 需非空 `MAIL_USERNAME`/`MAIL_PASSWORD`（空值配 `mail.smtp.auth=true` 会报 `Authentication failed`）——此条对 app-alert 仍有效。
- **DAG 告警在哪触发**：SUCCESS/FAILURE 在 **app-worker**（执行终态回调 listener），TIMEOUT 在 **app-job**（`dagNodeTimeoutAlertHandler`）。排查邮件问题查对应容器日志，别只看 engineering。
- **`alert_rule.object_type` 有数据库 CHECK 约束**：原仅允许 `DAG/SYNC_JOB/COLLECT_TASK`，扩 QUALITY 时除改 `AlertRuleService.validate()` 白名单外，**还必须跑 Flyway `V3.6.6__alert_rule_quality_object_type.sql`** drop 旧约束重建为含 QUALITY，否则在告警中心建「质量」规则会报 `check constraint "alert_rule_object_type_check"` 违反。【注：微服务化拆库后 QUALITY 已含在 datanest_alert 基线 V1.0.0 中，V3.6.6 脚本已归档 `scripts/migration-legacy/`；此条仅供追溯历史库。】

## 三、DAG / 条件节点（当前有效，AGENTS.md 正文保留精简版）

- **ReactFlow 11 受控 edges/nodes 必须配 onEdgesChange/onNodesChange**：直接传 `edges`/`nodes` prop 而不传 change handler 时，内部 `setEdges`/`setNodes` 静默不执行，边不渲染（血缘图谱页曾踩此坑，节点正常但边为空且无报错）。改用 `useNodesState`/`useEdgesState` 或补 handler。排查"节点正常、边不渲染"先看此。
- **SimpleEvaluationContext 不含 MapAccessor**：条件分支 SpEL 里 `#upstream.row_count` 属性语法必然抛 "Property cannot be found"。需把 `${a.b}` 转成 SpEL 索引语法 `#a['b']`（见 `DagNodeExecuteService.evaluateBranches`）。
- **条件节点 upstream 是嵌套结构**：`buildConditionContext` 以「前驱节点名」为 key 构造嵌套 map（支持 `${upstream['节点名'].row_count}` 按节点精确取值），顶层同时保留最后遍历前驱的 `row_count/status` 兼容旧写法 `${upstream.row_count}`。排查"多前驱条件分支取错值"时，先确认表达式用的是按节点名写法。
- **条件表达式不再暴露 dag_id**：`buildConditionContext` 已 `vars.remove("dag_id")`；但 `DagParameterResolver` 的 `dag_id` 仍保留（供 SQL 占位符 `${dag_id}` 使用）。改动条件表达式变量时别动参数解析层的 `dag_id`。
- **DagExecutionSyncService 匹配 DS 任务实例**：DS 任务名 = `节点名_节点ID后8位`（nodeId 可能含 `_`），不能简单按 nodeName 或 strip 末尾 `_` 段匹配；应按相同规则构建「DS 任务名→node」反向映射。SUB_DAG 等依赖 sync 更新状态的节点匹配失败会落 WAITING→SKIPPED。

## 四、Flyway / 迁移脚本（当前有效，AGENTS.md 正文保留精简版）

- **格式化工具会破坏已应用 Flyway 脚本的 checksum**：曾出现 IDE/格式化工具把迁移 SQL 按语法树拆行（如 `id\n BIGSERIAL\n PRIMARY\n KEY`、`VARCHAR\n(100)`），导致已应用迁移的本地文件内容与数据库 `flyway_schema_history` 记录的 checksum 不一致，`app-system` 启动时 Flyway validate 报 `Migration checksum mismatch` 而退出，后续新迁移也无法执行。处理：用 flyway 镜像对 postgres 执行 `repair`（`docker run --rm --network datanest-net -v <migration目录>:/flyway/sql:ro flyway/flyway:11.14.1 -url="jdbc:postgresql://middleware-postgres:5432/datanest" -user="datanest" -password="datanest123" repair`）固化 checksum。**预防**：所有 Flyway 脚本统一用紧凑单行风格，**不要用格式化工具拆分**（已 2026-08-04 全量重写 + repair 固化）。
- **【已过时，微服务化后不再成立】Flyway repair 脚本位于 task-core 之外的 system 模块**：旧单库时代 migration 集中在 `data-nest-system`，改脚本须重打 app-system 镜像。拆库后各持库服务独立管理本库 `db/migration`，改谁的脚本重建谁的镜像。
- **新增迁移脚本版本号必须大于本库已有最高版本**：曾新增 `V3.6.10` 时库内已有 `V3.7.0`，Flyway 按版本排序判定新迁移乱序，报 `Detected resolved migration not applied to database` 而启动失败。处理：新脚本版本号取「库内最高版本+1」。**预防**：先查对应库 `flyway_schema_history` 最高版本再定新编号；`mvn clean package`（避免 target/classes 残留已删除的旧脚本被带进 jar）。【微服务化后按各服务本库独立编号：基线 V1.0.0，后续 V1.1.0+ 各自演进；规则本身仍有效。】

## 五、质量执行（当前有效，AGENTS.md 正文保留精简版）

- **质量执行在 app-worker（Sprint 8 执行层）**：`qualityCheckExecuteHandler` 注册在 `data-nest-worker` 组，`QualityCheckService` 在 worker 容器内执行。手动/定时/自动三种触发统一投递到该 handler。改 task-core 的质量执行代码后必须重建 **app-worker**（不只是 governance）。
- **质量任务定时 = 每任务独立注册 XXL-JOB（不再全局扫描）**：`startSchedule` 按需 `registerJob`（worker 组 + 自身 cron）或 `startJob`，`stopSchedule` 仅 `stopJob`（不注销，保留 `xxl_job_id`），`delete` 注销，`update` 里 cron 变更会 `updateJob` 同步。`quality_job` 有 `xxl_job_id` 字段。已废弃 `QualityCheckHandler` 全局扫描（该旧 handler 若残留在 XXL-JOB admin 可手动删）。
- **质量执行 executorParam 带触发类型**：手动/自动经 `QualityCheckTriggerService` 显式传 `jobId:MANUAL` / `jobId:AUTO_TRIGGER`（带冒号）；**定时触发用注册时保存的纯 `jobId`（无冒号）**，`QualityCheckExecuteHandler` 对无冒号 param 默认按 `SCHEDULED` 处理，有冒号则解析 triggerType。排查"定时触发落库成 MANUAL"先看 handler 的 param 解析。
- **质量结果值提取坑（RANGE）**：Doris/MySQL 对空表 `SUM(...) AS out_of_range` 返回 **NULL**（非 0），且 JDBC 列名可能大小写变化。`QualityCheckService.computeRangeRatio` 已对列名大小写不敏感匹配，且 `total=0` 或 `out` 为 NULL 时按 0 处理。改动时保持该语义。
- **质量接口经 gateway 前缀是 `/api/governance/quality/**`**（gateway 只把 `/api/governance/**` 路由到 governance），不是 `/api/quality/**`。直接调 `/api/quality/...` 会得到 gateway 的 `NoResourceFoundException` 404。质量接口实际路径形如 `/api/governance/quality/jobs/page`。
- **质量执行结果表**：`quality_check_batch`（批次）+ `quality_check_detail`（规则明细）。明细含 `result_value`（结果值）与 `result_level`（Sprint 6 分级判定：`PASS`/`WARNING`/`SEVERE`/`UNAVAILABLE`），不评分。批次状态 `RUNNING/SUCCESS/PARTIAL_FAILED/FAILED`（无规则视为 SUCCESS）。
- **质量分级判定（Sprint 6 分级邮件告警）**：阈值判定在 `QualityCheckService.determineLevel`：`value < warning` → PASS；`warning ≤ value < severe` → WARNING；`value ≥ severe`（或无 severe 时 `value ≥ warning`）→ SEVERE；warning/severe 都为空 → PASS（不告警）；SQL 失败 → UNAVAILABLE。批次收尾 `fireBatchAlert` 按任务 `alert_level`（`SEVERE_ONLY` 收 SEVERE；`SEVERE_WARNING` 收 SEVERE+WARNING）过滤达到等级的明细，调 `AlertFiringService.fireBatch` 合并为**一条邮件** + **每条异常写一条 `alert_history`**；`quality_check_batch.alert_sent` 置 1 幂等。**UNAVAILABLE（数据源不可用/SQL 失败）不触发告警**（R2：避免环境抖动误报），只记录 result_level。告警复用 `alert_rule` 体系，扩展对象类型 `QUALITY`（对象=质量任务，`object_id=任务ID`），`triggerConditions` 固定 `["FAILURE"]`，接收用户在「告警中心」创建 QUALITY 规则时选择（质量任务表单仅配置 alert_level，无接收用户控件）。

## 六、已解决坑（仅供复现参考，不阻塞当前开发）

- **质量规则新增（配置层）不强制模板**：前端 `QualityRuleDrawer` 新增时 `templateId` 恒为空、UI 无模板控件，后端 DTO `isTemplateRequiredValid` 原强制非 CUSTOM_SQL 必选模板导致新增完整性/唯一性/值域规则必报 400。已放宽为可选（`template_id` 可空、PRD"可选自模板"）。改动 `QualityRuleService` 后须重建 **app-governance**。无模板的非 CUSTOM_SQL 规则 preview-sql 返回 null（前端降级显示"无预览 SQL"，已知项，执行批再处理）。
- **质量任务自动触发绑定同步任务依赖 engineering 只读权限**：`AutoTriggerSelect` 调 `/engineering/sync-jobs/page` 读取同步任务下拉，该接口原 `@SaCheckRole` 仅限 `SUPER_ADMIN`/`DATA_ENGINEER`，治理员（GOVERNANCE_ADMIN）配置质量任务自动触发绑定同步任务时下拉为空。已在 `SyncJobController.list` 增加 `GOVERNANCE_ADMIN` 只读访问（改动 engineering 后重建 **app-engineering**）。排查"自动触发绑定同步任务下拉空"先看此。
- **CollectTaskService.toDTO NPE（2026-08-05 修复）**：`POST /governance/collect-tasks` 报 `9999 系统内部错误`。根因：`toDTO` 用 `usernameMap.get(task.getUpdatedBy())`，`usernameMap` 是 `Map.of()`（`ImmutableCollections.MapN`，**不允许 null key 的 get**）；按审计约定 V3.6.8 create 只设 `created_by` 不设 `updated_by` → `updatedBy=null` → `Map.of().get(null)` 抛 NPE。修复：`toDTO` 加空安全 `lookupName(map, userId)` helper（`userId==null` 返回 null）。改后须重建 **app-governance**。**教训**：审计约定「create 只设 created_by」后，凡对 `Map.of()`/`Map.ofEntries()` 等不可变 map 做 `get(key)` 的地方，key 可能为 null，需空安全处理。
- **AlertRuleModal 「对象」multiple Select 选中后浮层遮挡下方字段（2026-08-05 修复）**：`/system/alert-center` 新增/编辑告警规则弹窗中，「对象」用 antd `mode="multiple"` Select，antd 默认选中后 dropdown 保持展开。当对象选项 ≥ 4 项时，浮层高度约 200px，会物理遮挡下方「接收用户」select 控件。修复：`AlertRuleModal` 改为受控 dropdown open（`useState objectDropdownOpen`），`onChange` 选中后立即 `setObjectDropdownOpen(false)`。**适用**：所有表单内嵌 antd multiple Select 且下方还有其他字段的场景——要么加 `listHeight` + `virtual={false}` 限高，要么受控 open 选中后关闭。改后须 `npm run build` + 重建 **app-frontend**。

## 七、E2E 测试细节（测试专项，默认不加载）

### 质量执行层 E2E（sprint6/quality-checks.spec.ts）

用 `e2e_s6_exec_ds`（MYSQL）/`e2e_s6_exec_pg_ds`（POSTGRESQL）两个真实执行数据源（middleware-test-mysql:3306 / middleware-test-postgres:5432），目标表 `e2e_s6_orders`。数据源密码需为 AES-256-GCM 可解密密文，helpers/encrypt.ts 复刻后端 EncryptionConfig 逻辑（密钥默认 `DataNestDefaultEncryptionKey2026`）。执行是异步的（经 XXL-JOB 投递 app-worker），断言通过轮询 `quality_check_batch` 至终态；修改 seed/helpers 后不需要重启服务，但若改了 task-core 执行逻辑需重建 **app-worker**。自动触发用 3 种方式覆盖：真实同步任务成功、真实 DAG 节点成功、播种 AUTO_TRIGGER 批次记录。

### 分级邮件告警 E2E（sprint6/quality-alerts.spec.ts，2026-08-05，8 用例全绿）

- **DsModal 弹窗定位用 `getByRole('dialog', {name: title})`**（非 antd `.ant-modal`）。
- **antd multiple Select 选中后 dropdown 保持打开**，点击弹窗标题（而非 Escape，会同时关闭 DsModal）关闭 dropdown 后再点保存。
- **告警规则对象需多选覆盖所有链路**（同一对象告警规则才能在多个任务上命中），SPEC 测试中覆盖主链路 + SEVERE_ONLY 两个任务。
- **MailHog `decodeMimeEncoded` 修复了 RFC 2047 相邻 encoded-word 间空白插入 bug**（sprint5 mailhog.ts 通用修复）：JavaMail 长主题按 ~40 字节拆分，`e2e_s6_alert_main` 被拆成 `e2e_`/`s6_alert_ma`/`in`，未修复时 `find('e2e_s6_alert_main')` 失败。
- **邮件正文是 quoted-printable**（非 base64），spec 内 `decodeBody` 按 `=XX` 字节还原 + 移除软换行 `=\r\n`。
- **结果值前端格式化为整数 `4`**（DB 存 `4.000000`），断言 UI 时用 `getByText('4', {exact:true})`。
- **告警中心历史页首列是「告警时间」**（非对象名称），`rowBy`（按首列匹配）失效，应按对象名称列用 `.filter({hasText: objectName})`；`getByText` 限定在 `historyRow` 内避免命中筛选下拉 `<option>`。
- **严格模式同名批次**：主链路任务执行两次（主链路 + 幂等）会产 2 行，`rowBy(...).first()` 取最新。

### 表级质量评分 E2E（sprint6/quality-scores.spec.ts，2026-08-05，11 用例全绿）

- **DB 多档评分**在 `quality_score` 表精确断言（`score` 存 `100.00`/`70.00`/`20.00` 2 位小数，`health_level`=EXCELLENT/WARNING/BAD）。
- **评分算法**：基础分=100×(PASS 权重/有效启用规则权重)−警告扣(默认10×警告权重)−严重扣(默认30×严重权重)，**SEVERE 强制 BAD**，badThreshold(60) 以下 BAD，UNAVAILABLE 不参与；无有效规则**不落评分行**。
- **多档场景需不同物理表名**（`uk_metadata_table_unique(datasource_id, database_name, COALESCE(schema_name,''), table_name)`），seed 用 4 张 `e2e_s6_score_*` 表行数控制 COUNT。
- **执行**：`POST /governance/quality/scores/table/{tableId}/execute` 逐条 MANUAL 投递 worker（异步），测试轮询 `quality_score` 落行且计数达标/`quality_check_detail` 分级到终态，**不依赖 `waitBatch`**（避免被其他单规则批次污染）。
- **健康度文案**：EXCELLENT=优秀/GOOD=良好/WARNING=一般/BAD=差，「差」筛选 value=`BAD`。
- **扣分配置弹窗**：`getByRole('dialog', {name:'扣分配置（质量评分全局配置）'})`，改配置后**需重新执行才重算**（评分只在批次收尾重算，不实时重算存量）。
- **spec 自带播种**（`ensureTestUsers+seedExecTables+seedExecMetadata+seedQualityScores`），支持 `SKIP_SETUP=1` 独立运行，绕开 Sprint5 collect-task 播种。


## 八、微服务化改造踩坑（阶段 1-5，2026-08-06/07，当前有效）

- **Feign `lb://` 必须依赖 `spring-cloud-starter-loadbalancer`**：governance/alert-service 原本就有，engineering/worker/job 缺失导致启动报 `No Feign Client for loadBalancing defined`，已在三个 pom 补齐。后续给服务加 Feign client 时先确认该依赖。
- **新服务必须显式声明 `spring-boot-starter-validation`**：该依赖在 common 是 provided，而 `GlobalExceptionHandler` 引用 jakarta.validation 类；缺了启动报 `NoClassDefFoundError: jakarta/validation/ConstraintViolationException`（app-alert 踩过）。新服务脚手架时记得带上。
- **启动类 scanBasePackages 只追加 `com.datanest.common.internal`，不要扫整个 common**：扫全包会误装配 `SchedulerClient`（`@Value("${xxl.job.admin.addresses}")` 无默认值，未引 shared-xxljob 的服务会启动失败）。
- **`FeignHttpMessageConverters` 并发缺陷（上游 issue #1307）**：spring-cloud-openfeign 已知 bug——该类未初始化时被并发调用，`getConverters()` 返回空/含 null 的列表，症状是并发首次调用偶发 `'messageConverters' must not be empty`，一次性、不再复现。**Workaround**：common `FeignContextWarmup` 启动期遍历 `FeignClientFactory.getContextNames()`，逐 client 子上下文取 `FeignHttpMessageConverters` 并显式调 `getConverters()` 强制初始化。⚠️ 两个关键细节（5.0.2 源码确认）：① 5.x 中该 bean 在 `FeignClientsConfiguration` 里**按 client 子上下文各一个**，主容器没有（只预热主容器会静默跳过）；② 仅取 Decoder bean 不触发转换器初始化，必须显式调 `getConverters()`。依赖升级到含修复的 spring-cloud 版本后可移除本 workaround。
- **Feign 查询/路径参数禁止用 `LocalDateTime`**：Feign 的 Spring ConversionService 会把 LocalDateTime 查询参数按 locale 格式化（如 `from=8/7/26, 6:20 AM`），服务端按 ISO 解析报 500（job 对账 handler 调 `succeeded-between` 踩过）。**规则**：契约的查询/路径参数一律 ISO String，调用方用 `DateTimeFormatter.ISO_LOCAL_DATE_TIME` 格式化；请求体里的 LocalDateTime 走 Jackson 不受影响。
- **无库服务必须排除 DataSource 系自动配置**：worker/job 无 DataSource 时启动报 `Failed to configure a DataSource`，已在两服务 application.yml 排除 `DataSourceAutoConfiguration/DataSourceTransactionManagerAutoConfiguration/MybatisPlusAutoConfiguration`（Boot 4 类名在 `org.springframework.boot.jdbc.autoconfigure` 包，注意不是旧包名）。
- **Flyway 是代码驱动的，yaml 不生效**：项目 jar 里没有 spring-boot-flyway autoconfigure 模块，`spring.flyway` yaml 配置**不生效**；各持库服务靠本地 `FlywayConfig`（@Bean initMethod=migrate，baselineOnMigrate）。**版本比较忽略尾随零**：baseline marker "1" == "1.0.0"，故存量库跳过 V1.0.0、空库正常执行，勿误判为脚本没跑。**`pg_dump --data-only` 不带 setval**：拆库搬数据后序列不自增，须用 DO 块按 max(id) 同步全部序列（阶段 5 已做过一次）。
- **DS 状态映射终态保护（SKIPPED 不可逆）**：`DagExecutionSyncService` 按 DS 实例状态同步节点状态时，`isTerminalStatus`（SUCCESS/FAILED/SKIPPED/TERMINATED）不可逆，只允许推进 WAITING/RUNNING。背景：远程化后 HTTP 延迟使 sync 可能在 worker 回调（SKIPPED 已落库）后按 DS state=7(SUCCESS) 覆盖回去（条件分支非命中节点曾因此 SKIPPED→SUCCESS）。finalizeIfAllDone 复用同一 helper，改动时保持该保护。
- **XXL-JOB 3.x 管理 API**：`POST /auth/doLogin`（表单 userName/password/ifRemember）→ 拿 cookie → `POST /jobinfo/trigger?id=&executorParam=&addressList=`；context-path 为 `/`，**不是**老版本的 /login + /xxl-job-admin 前缀。手动触发 handler 对账/验证时用这套。
- **MapperScan 同名 bean 冲突曾是过渡期手段**：实体/mapper 逐域迁移期间新旧副本同名共存，`@MapperScan` 加过 `nameGenerator=FullyQualifiedAnnotationBeanNameGenerator`。**已全部回退**（旧副本删除后冲突消失），现在各服务只扫本地包，不要再加。
- **组件扫描不受 MapperScan nameGenerator 管**：同名 @Service 被两处扫描时 nameGenerator 无效，需显式 bean 名（曾把 governance 本地 ScoreCalculator bean 名改为 `governanceScoreCalculator`）。entity 模块删除后此类冲突已消失，但新增同名组件时注意。
