# 微服务化改造 Handoff

> 独立改造（不属于任何 Sprint）。总目标：共享 jar 进程内调用 → OpenFeign 远程调用 + 按域拆库。
> 已确认决策：OpenFeign + Nacos；按域拆库（阶段 5 才拆）；最终一致性（Feign + 重试 + 对账，无分布式事务无 MQ）；新建 app-alert 独立告警服务；worker/job 最终不持库（纯执行节点，经 Feign 回写 owner）。
> **当前进度：阶段 1-5 已完成 ✅（2026-08-07，已按域拆 4 库）。下一阶段：阶段 6（清理收尾），等用户安排。**

## 阶段 5 范围（拆库 + 配置整理，2026-08-07 完成 ✅）

**形态**：多数据库（用户决策）——datanest_system（5 表）/ datanest_alert（6 表）/ datanest_engineering（13 表）/ datanest_governance（19 表），全部在 middleware-postgres 同一实例。worker/job 无库（纯执行/调度节点）。

**实施记录**：
- **5.0 拆库前哨**：消除最后两处跨域 SQL JOIN——app-alert `selectHistoryPage`（删 4 个外域 JOIN，对象名改按类型分组 Feign 批量回填）、governance `MetadataTableMapper` 4 个查询（删 datasource_connection JOIN，Feign batchGet 回填）。
- **5.1**：4 份基线脚本（从在线库 pg_dump --schema-only 生成，清理 `\restrict` 行）落位各服务 `db/migration/V1.0.0__baseline.sql`；72 个旧脚本归档 `scripts/migration-legacy/`；compose 按服务注入 PG_DATABASE；worker/job 摘 shared-datasource import；shared-alert 下沉 app-alert；shared-internal+shared-feign 合并为 **shared-rpc.yaml**（已推送 Nacos，旧 3 个 dataId 已删除）。
- **5.2**：CREATE DATABASE ×4 → 应用基线 → 停写拷贝数据（行数逐表核对一致）→ **序列 setval 修正**（pg_dump --data-only 不带 setval，已用 DO 块按 max(id) 同步全部序列）→ 切换启动。
- **踩坑修复**：
  - worker/job 无 DataSource 时启动报 `Failed to configure a DataSource` → 两服务 application.yml 排除 `DataSourceAutoConfiguration/DataSourceTransactionManagerAutoConfiguration/MybatisPlusAutoConfiguration`（Boot 4 类名在 `org.springframework.boot.jdbc.autoconfigure` 包）。
  - **Flyway 是代码驱动的**：项目 jar 里没有 spring-boot-flyway autoconfigure 模块，`spring.flyway` yaml **不生效**；各服务靠 `FlywayConfig`（@Bean initMethod=migrate，baselineOnMigrate）。已为 alert/engineering/governance 补 FlywayConfig，4 个服务的 inert spring.flyway yaml 已删除。Flyway 版本比较忽略尾随零：baseline marker "1" == "1.0.0"，故存量库跳过 V1.0.0、空库正常执行。
- **验证（全部通过）**：4 库 flyway_schema_history 基线标记就位；同步/DAG（n5_e SKIPPED）/采集/质量（3 明细+聚合告警+alert 库单条记录）E2E 全绿；告警历史跨域名称回填正常；旧 datanest 库写入已冻结（10:17 后无新数据）；全服务 RemoteCalls 零失败。
- **旧库处置**：datanest 库保留只读观察一个迭代，之后下线。

## 阶段 4 范围（governance 域远程化，2026-08-07 完成 ✅）

**阶段 4 最终回归（SysUser 迁移修复后，全部通过）**：6 容器 healthy；登录 + 用户名回填（SysUser 已在 system 本地 + Feign 链）✅；同步 SUCCESS ✅；质量批次 SUCCESS + 3 明细 + alert_sent=1 ✅；全 6 服务 RemoteCalls 零失败 ✅。

19 表归 governance：metadata_*/collect_*/quality_* 全族、naming_standard、field_type_standard、compliance_check_result、asset_classification、lineage_record。

- **4.1（完成 ✅）**：19 表实体/mapper 复制到 `com.datanest.governance.entity/mapper`（旧副本暂留，4.4 删；GovernanceApplication MapperScan 加 nameGenerator，4.4 回退）；governance 自有 12 文件 import 翻转本地；governance-api 扩为 6 个 client（新增 QualityExecutionApi/CollectWriteApi/MetadataWriteApi/GovernanceOpsApi，26 端点）。**关键设计**：质量 `execution/plan` 端点服务端一次生成 SQL（RuleSqlGenerator 服务端化）；`batches/{id}/finish` 服务端串联批次收尾+评分重算+fireBatchAlert（ScoreCalculator 搬 governance）；采集 upsert/detect 服务端 diff + 事务；清理×3/合规扫描逻辑下沉 job 只触发。本地 ScoreCalculator bean 名改 `governanceScoreCalculator`（与 task-core 旧版组件扫描冲突，nameGenerator 只管 @MapperScan 不管组件扫描）。
- **4.2（完成 ✅）**：worker 执行链路全切 Feign——QualityCheckService 拆分（执行侧留 task-core 全 Feign：plan fail-fast → details 降级 → finish 降级；查询侧移 governance `QualityCheckQueryService` 本地批量消 N+1）；CollectExecutor 全链路改 CollectWriteApi（日志缓冲 50 条 flush，计数 DTO 累加统计列）；MetadataRegistrationService/SqlLineageExtractor 改 MetadataWriteApi；sync/collect 成功的质量自动触发改 `qualityAutoTriggerBatch` 单元素调用。契约补漏：upsert 三端点返回计数 DTO、RulePlanItem 补 resultMetric、refresh-if-exists 补 columns。GovernanceApplication 自启用 governance.api（同 engineering 3.2 先例）。
- **4.2 E2E（全部通过 ✅）**：质量任务（有规则）批次 SUCCESS + 3 明细（PASS/WARNING/SEVERE）+ alert_sent=1 + alert_history 单条聚合（summary 逐规则）+ MailHog 收信；无规则任务 SUCCESS 空批次（与旧行为一致）；采集 SUCCESS + 日志；同步 SUCCESS；DAG SUCCESS + SKIPPED 正确。
- **4.3（完成 ✅）**：**task-core-governance 模块已删除**——质量编排 6 类 + ComplianceCheckService 迁 governance（含 job 合规扫描端点化）；ConnectionTester/DataPreviewService 迁 engineering（`buildJdbcUrl` 抽到 common `JdbcUrlBuilder`，task-core 三执行器静态调用）；DataSourceRefreshService 下沉为 engineering 端点 `refresh-statuses`；task-core 旧 ScoreCalculator/RuleSqlGenerator 删除。job 6 个 handler 全部切端点（collect/quality/lineage cleanup、compliance run-checks、datasource refresh、reconcile 剩余两表改 auto-trigger-bindings/auto-triggered-since）。依赖链修复：system-api 原经 task-core-governance 传递，已在 4 个消费方 pom 显式声明。
- **4.3 验证（通过 ✅）**：6 个 handler XXL 手动触发全部 handle_code=200（合规扫描实产 89 条结果；reconcile 全 Feign 链路通）；质量任务页/评分页（迁移后本地 Service + Feign 回填）正常；governance/job 日志零 RemoteCalls 失败。
- **4.4（完成 ✅）**：entity 模块删除 19 治理实体 + 20 mapper（模块只剩 dto 包 + AlertConstants/QualityScoreConstants——SysUser 体系见下条）；全库零残留（含 XML/yml）；MapperScan 最终态：governance/engineering 只扫本地包，**worker/job 已无 @MapperScan（无任何本地 DB 访问）**，system 只扫本地包。GovernanceApplication nameGenerator 已回退。
- **4.4 部署事故与修复（已解决 ✅）**：MapperScan 收窄后 4 服务启动失败——`SysUserService`（@Service 在 task-core-entity jar 内，被 com.datanest.task.core 组件扫描）依赖 `SysUserMapper`，而 mapper 扫描已收窄不再提供该 bean。**修复（顺势完成阶段 6 的一部分）**：SysUser 实体/SysUserMapper/SysUserService 从 entity 模块迁入 system 模块（com.datanest.system.entity/mapper/service），system 的 MyBatisPlusConfig 去掉 @Import 和 task.core.mapper 扫描；全库外部零引用、编译通过。**entity 模块现只剩 dto + constant 两个包**。

## 阶段 3 范围（engineering 域远程化，2026-08-07 完成 ✅）

13 表归 engineering：sync_job 三表、dag 全族（project/dag/node/edge/parameter/version）、dag_execution/node_execution/node_execution_log、datasource_connection。

**阶段 3 全量回归记录（2026-08-07，全部通过）**：
- 同步任务 E2E：SUCCESS + 6 行 + 日志服务端续号 + last_history_id 正确 ✅
- 条件 DAG E2E（两轮冷启动 ×2 轮修复后复跑）：全节点 SUCCESS + n5_e=SKIPPED ✅
- 采集任务 E2E：SUCCESS（5 表 29 列，CollectExecutor 经 Feign 读数据源）✅
- 质量任务 E2E：批次 SUCCESS ✅
- 数据源列表/连接测试/元数据与质量规则数据源名回填/有引用删除拒绝（3005）✅
- 对账 handler（job 589）XXL API 手动触发 handle_code=200，无 Feign 错误 ✅
- 告警中心分页/对象下拉（跨服务 Feign）✅
- 回归期间出现的 sync/DAG FAILED 为**外部 Doris 主机（192.168.119.135）当时宕机**，恢复后重跑全绿，非代码回归
- 全容器 RemoteCalls 失败扫描：0（修复后窗口）

- **3.1（完成 ✅）**：13 表实体/mapper 复制到 `com.datanest.engineering.entity/mapper`（旧副本暂留，3.5 删）；engineering-api 扩为 4 个 Feign client（EngineeringDatasourceApi/EngineeringSyncJobApi/EngineeringDagApi/EngineeringDagExecutionApi，~40 端点）；复杂语义整体下沉 engineering（reap-stuck、cleanup、finalize+告警副作用、乐观锁 batch-update、logs:append 服务端续号）。⚠️ `EngineeringApplication` 的 @MapperScan 加了 `nameGenerator=FullyQualifiedAnnotationBeanNameGenerator`（新旧同名 mapper 共存必需，3.5 删旧实体后可考虑回退）。
- **3.2（完成 ✅）**：同步执行链路全切 Feign——SyncJobExecutorService/SyncJobExecutor/AddaxJobService/SyncJobRetryService/MetadataRegistrationService（task-core，worker 运行）+ job 的 SyncJobRetryHandler/SyncHistoryCleanupHandler/DagExecutionSyncHandler.fetchLatestSyncHistory；**SyncJobTriggerService 从 task-core 迁入 engineering**（新增 `POST /internal/sync-jobs/{id}/trigger` 端点，worker 的 handleSyncNode 远程触发）。语义红线：执行开始处 fail-fast（mark-running/init history 失败则 XXL 任务失败，不跑无登记执行）；结束处降级靠对账兜底。engineering/governance 启动类也追加了 com.datanest.engineering.api 扫描（它们同样加载 task-core bean）。
- **3.3（完成 ✅）**：DAG 执行链路全切 Feign——worker `DagNodeExecuteService`（5 个 mapper 全移除，状态机走 `/node-executions/{id}/mark`，日志走服务端续号 logs:append）、task-core `DagExecutionSyncService`（终态收尾走 `/dag-executions/{id}/finalize`，engineering 端内置 dag-finished 告警）；**删除 4 个类**：RemoteDagFinishedListener、DagExecutionFinishedListener、NodeExecutionLogService、StuckExecutionReaperService（逻辑下沉 engineering 端点）；job 的 DagExecutionHistoryCleanup/DagNodeTimeoutAlert/QualityAutoTriggerReconcile/StuckExecutionReaper handler 全部改端点；DagParameterResolver/DagEdgeSnapshot 改 DTO 版；QualityJobService 对象名回填改 EngineeringObjectApi。
- **对账 handler 验证**：XXL-JOB 3.x 管理 API 已打通（`POST /auth/doLogin`（表单 userName/password/ifRemember）→ cookie → `POST /jobinfo/trigger?id=&executorParam=&addressList=`，context-path 为 `/`，**不是**老版本的 /login + /xxl-job-admin 前缀）；手动触发 jobId=589（qualityAutoTriggerReconcileHandler）执行成功 ✅
- **3.3 DAG E2E（条件多前驱 DAG）发现并已修 1 个竞态 bug**：
  - **现象**：条件分支非命中节点 n5_e 应 SKIPPED 实际 SUCCESS（历史执行均为 SKIPPED）。
  - **根因**：`DagExecutionSyncService` 的 DS 状态映射无条件覆盖节点状态——worker 回调已把 n5_e 标 SKIPPED（version+1），job 同步器按 DS 实例 state=7(SUCCESS) 又覆盖回 SUCCESS（endTime 因"只补空"保留 worker 的毫秒值，成为实锤）。远程化前 callback→finalize 全是进程内毫秒级，sync 每 5s 一轮总是输给 finalize；远程化后 HTTP 延迟让 sync 在 finalize 落库前扫到 RUNNING 执行实例，潜在竞态显形。
  - **修复**：DS 状态映射加终态保护（`isTerminalStatus`：SUCCESS/FAILED/SKIPPED/TERMINATED 不可逆，只允许推进 WAITING/RUNNING），finalizeIfAllDone 复用同一 helper。
  - **观察项**：worker 出现 1 次 `by-ds-instance` 调用报 `'messageConverters' must not be empty`（job 0 次，原因未定位，疑似一次性/初始化竞态），下次 DAG 运行时重点观察，若复现需深挖 Feign 解码配置。
  - **messageConverters 根因（已定位，systematic-debugging 全流程）**：
    - ~~初判假设：NamedContextFactory 懒构建竞态~~ → **证伪**（预热子上下文后第 2 次冷启动仍复现 2 次）。
    - **真根因**：spring-cloud-openfeign 上游已知并发缺陷 [issue #1307](https://github.com/spring-cloud/spring-cloud-openfeign/issues/1307)——`FeignHttpMessageConverters#getConverters` 类未初始化时被并发调用返回未初始化列表（空/含 null），初始化后不再出现。症状 100% 吻合（并发首调、一次性、Boot4 新错误文案）。
    - **修复（官方 workaround 的 5.x 版）**：common `FeignContextWarmup` 启动期遍历 `FeignClientFactory.getContextNames()`，逐 client 子上下文取 `FeignHttpMessageConverters` 并显式调 `getConverters()` 强制初始化。⚠️ 两个关键细节（5.0.2 源码确认）：① 5.x 中 `FeignHttpMessageConverters` 在 `FeignClientsConfiguration` 里按 **client 子上下文各一个**，主容器没有该 bean（只预热主容器会静默跳过——第一版修复就踩了这个）；② 仅取 Decoder bean 不会触发转换器初始化，必须显式调 `getConverters()`。后续依赖升级到含修复的 spring-cloud 版本时可移除本 workaround。
    - **验证（已闭环 ✅）**：两轮 worker 冷启动 + 条件 DAG 运行，`messageConverters` 均 0 次、n5_e 均为 SKIPPED、预热日志 7 个 client 各 6 个转换器初始化成功。
    - 教训记录：第一版修复未验证机制就动手（预热了错误的层面），被实验证伪后严格回 Phase 1，靠 WebSearch 定位上游 issue + 读 5.0.2 源码确认 per-context 结构才修对——符合 AGENTS.md 排查约定。
  - **Feign 查询参数 LocalDateTime 被 locale 格式化（已修复 ✅）**：3.5 全量回归时 job 对账 handler 调 `succeeded-between` 报 500——`from=8/7/26, 6:20 AM`（Feign 的 Spring ConversionService 把 LocalDateTime 查询参数按 locale 格式化，服务端按 ISO 解析失败）。**修复**：engineering-api 两处查询参数（`succeededBetween(from,to)`、`latestHistory(notBefore)`）契约改 String，调用方用 `DateTimeFormatter.ISO_LOCAL_DATE_TIME` 格式化。**规则：Feign 契约的查询/路径参数禁止用 LocalDateTime，一律 ISO String**（请求体里的 LocalDateTime 走 Jackson 不受影响）。
- **3.4（完成 ✅）**：datasource_connection 跨服务读取全切 EngineeringDatasourceApi（GenericSqlExecutor/CollectExecutor/QualityCheckService/DataSourceRefreshService + governance 六处回填）；**跨域写入收进 governance 端点**：新建 GovernanceDatasourceApi（references 引用检查 / cascade-delete 级联删除（fail-closed）/ collect-tasks/auto-create（逻辑下沉治理侧）/ lineage/by-dag）；engineering 的 DataSourceService 移除 8 个治理/同步 mapper 注入。⚠️ 两个已知小差异留 3.5/阶段 4：references DTO 不带 status/enabled 字段；采集抽取器仍吃 DataSourceConnection 实体旧签名（3.5 删旧实体时同步改映射）。DataSourceService.delete 新事务边界：远程 cascade 先行（失败中止无残留），本地删除在后（失败可重试）。
- **3.5（完成 ✅）**：entity 模块删除 13 实体 + 13 mapper 旧副本（剩治理表/SysUser/dto）；抽取器（MetadataExtractor×4）、ConnectionTester、AddaxJobService、IncrementalFieldTypeResolver 签名改吃 engineering-api 的 DataSourceInfo/SyncJobInfo DTO；**DagTopologyService 从 task-core-governance 迁入 engineering**（lib 不能反向依赖 service 实体）；engineering 10 个文件通配 import 翻转到本地 entity/mapper；EngineeringApplication 的 MapperScan nameGenerator 已回退。全量编译 + grep 零残留。

## 阶段 2 范围（system 域远程化，2026-08-06 完成）

- `SysUserService`（entity 模块）的进程内消费全部改 Feign：engineering 5 个 Service、governance 5 个 Service、task-core-governance 3 个 Service（QualityRule/QualityJob/QualityRuleTemplate）。
- system-api 扩充：`GET /system/internal/users/ids-by-name-keyword?keyword=`（资产搜索负责人维度）；原有的 `usernames`/`emails` 批量端点复用。
- 降级语义：usernames Feign 失败 → warn + 空 Map（列表页名称列为空/「-」，接口不 500）；负责人名搜索失败 → 空列表；assignOwner 存在性校验为 fail-closed（system 不可用时拒绝写）。
- **N+1 修复（阶段 1 遗留）**：dag-finished 质量自动触发原逐节点调 `findDagNodeId`（N 次）+ 逐节点 `qualityAutoTrigger`（M 次）→ 改为批量端点：engineering `POST /engineering/internal/dags/{dagId}/nodes/resolve`（一次拿 nodeId→dagNodeId 映射）+ governance `POST /governance/internal/quality/auto-trigger/batch`；单条 auto-trigger 端点与 findDagNodeId 已删除。
- 消费方 13 个 Service 各自持有私有 `usernames()` helper（项目偏好简单重复，不在 common 抽象）。
- SysUserService/SysUserMapper/SysUser 实体保留在 entity 模块（仅 system 使用），entity 清退属阶段 6。

## 阶段 2 验证记录

- 全量 `mvn clean package` exit 0；grep 无 SysUserService/SysUserMapper 残留（system 模块除外）
- 6 个重建服务（system/engineering/governance/worker/job/alert）全部 healthy，日志无 Feign 异常
- engineering `/api/engineering/sync-jobs/page`：`createdByName=admin` ✅（经 Feign 调 system usernames）
- 质量任务 `/api/governance/quality/jobs/page`：`createdByName=admin` ✅（task-core-governance 的 QualityJobService 远程回填）
- 资产搜索 `/api/governance/assets/search?keyword=admin`：命中负责人维度（ownerName=admin）✅（ids-by-name-keyword + ownerName 回填）
- 未 e2e 项：dag-finished 批量质量触发链路（resolveDagNodeIds/auto-trigger/batch）只做了代码级验证，待下次 DAG 真实执行时观察 app-alert 日志（应只有 2 次远程调用，无逐节点循环）

## 结构整改：模块三层目录 + 容错体系（2026-08-06 完成，用户决策）

### Maven 模块三层目录（data-nest-libs / data-nest-apis / data-nest-services）

- `data-nest-libs/`：data-nest-common、data-nest-task-core-entity、data-nest-task-core-governance、data-nest-task-core（阶段 6 后只剩 common）
- `data-nest-apis/`：4 个 Feign 契约模块
- `data-nest-services/`：7 个可部署服务（gateway/system/alert-service/engineering/governance/worker/job）
- 三个目录各有聚合 pom（**目录名 = 聚合 artifactId**：data-nest-libs/apis/services，packaging=pom）；根 pom modules = data-nest-libs → data-nest-apis → data-nest-services
- **artifactId 不变，依赖零改动**；仅 pom relativePath（../../pom.xml）与 7 个 Dockerfile 的 jar COPY 路径（加 services/ 前缀）调整
- 全部 `git mv` 完成（484 项 rename，历史保留）；全量构建 + compose config + app-system 镜像构建验证通过
- ⚠️ 后续引用模块物理路径时注意新位置（AGENTS.md §3 命令、docs 已同步）

### 远程调用容错体系（L1 统一设施 + L2 熔断）

**L1**：
- `shared-feign.yaml`（已推送 Nacos，消费方 alert/engineering/governance/worker/job 已 import）：Feign 全局 connect 2s/read 5s、loggerLevel basic、`feign.circuitbreaker.enabled=true`、resilience4j default 配置（10 次滑动窗口/5 次最小调用/50% 失败率熔断/30s 半开）
- common `InternalFeignErrorDecoder`：远端 Result 信封 message 提取；503→RetryableException 触发重试；其它→BusinessException("远程调用失败[svc path]: msg")
- common `InternalFeignRetryer`：Retryer.Default(100ms, 1s, 3)，全 client 生效
- common `RemoteCalls.execute(description, supplier, fallback)`：统一降级入口 + warn 日志 + Micrometer `remote_call_failed_total{target}` 计数；已替换 25 处手写 try-catch 样板

**L2**：4 个 @FeignClient 全部配 `fallbackFactory`（各 api 模块 fallback 包，@Component；消费方启动类 scanBasePackages 追加对应 api 包）。降级语义：读路径空集合/空 Map；fire→false；**`listRuleNamesByObject` 抛 BusinessException（fail-closed，QualityJobService 删除前置校验）**；`AssetCatalogService.assignOwner` 不包装（空 Map → 抛用户不存在，fail-closed）。resilience4j starter 由 spring-cloud BOM 管理（5.0.2），不要在根 pom 显式声明无版本条目（会报 version missing）。

**fail-closed 清单（改动时注意保持）**：QualityJobService 删除前告警引用校验、AssetCatalogService.assignOwner 用户存在性校验。

**验证记录（全部实测通过）**：
- 7 后端服务重建后全部 healthy；正常路径（sync-jobs 用户名回填、alert-rules 分页）✅
- 故障注入 1：`docker stop app-alert` → 同步任务执行 SUCCESS 不受影响；worker 日志 `远程调用失败，按降级处理: target=alert.fire`（Retryer 重试 3 次后快速降级，无线程挂起）✅
- 故障注入 2：`docker stop app-system` → sync-jobs page 仍 200，名称列降级为「-」✅
- 恢复：两服务重启后用户名回填/告警 fire 自动恢复（alert_history 正常落库）✅；测试规则与测试告警历史已清理

### 降级副作用两项修复（2026-08-06，用户评审发现）

**(c) 规则保存持久化污染 → fail-closed（app-alert）**：
- `AlertRuleService` 新增 `resolveObjectNamesForSave`：`createRule`/`updateRule`/`saveRuleObjects` 保存路径上，objectIds 非空而名称解析为空（远端宕机或对象不存在，不区分）→ 抛 BusinessException「对象服务不可用或对象不存在，请稍后重试」，事务回滚，空 object_name 不再落库。
- 双道守卫（updateRule + saveRuleObjects 各自独立 Feign 调用处）；`updateRule` 用 `effectiveType`（dto 缺省回退原类型）避免空类型误抛/误写。
- fire/展示路径保持降级语义不变。
- **实测**：停 app-engineering 保存规则 → 被拒且 0 落库；恢复后保存成功；测试数据已清理 ✅

**(b) 质量自动触发丢失 → 对账补发（app-job）**：
- 新增 `QualityAutoTriggerReconcileHandler`（cron `0 0/10 * * * ?`，JobRegistrar 已注册，可配 `datanest.job.quality-auto-trigger-reconcile.cron`）。
- 逻辑：扫描近 2h 内 SUCCESS 的 dag_execution（排除最近 5 分钟在途）→ 成功节点批量解析 dag_node.id → 查绑定的启用质量任务（DAG_NODE）→ 缺 `trigger_type='AUTO_TRIGGER'` 批次的判定漏触发 → `qualityAutoTriggerBatch` 补发（RemoteCalls 容错，失败下轮再补，幂等）。
- 全部批量查询无逐条循环；每轮补发上限 50。
- ⚠️ 该 handler 直接读 dag_execution/node_execution/quality_job/quality_check_batch 表——阶段 3/4 拆表归属后需改为经 engineering-api/governance-api 读取（记入阶段 3 改造清单）。

## 总体阶段规划

1. **阶段 1（已完成 ✅）**：新建 app-alert + 告警域远程化
2. **阶段 2（已完成 ✅）**：system 域远程化（SysUserService → Feign）+ dag-finished N+1 批量化修复
3. **阶段 3（已完成 ✅）**：engineering 域远程化（worker/job 执行回写链路）
4. **阶段 4（已完成 ✅）**：governance 域远程化（质量/采集/元数据/血缘回写）
5. **阶段 5（已完成 ✅）**：拆库（多数据库 4 库）+ Flyway 基线 + 数据迁移 + 配置整理
6. 阶段 6：删除 task-core 残余共享模块（entity 只剩 dto+constant）、文档收尾、全量回归

## 阶段 1 范围

- 新建 **app-alert** 服务（端口 8088，context `/alert`），收拢告警域全部代码与数据：
  - data-nest-alert 模块 7 个类（AlertRuleService/AlertFiringService/DagAlertService/MailService/DagAlertExecutionListener/QualityAutoTriggerPort/DagExecutionFinishedListener）
  - alert 四表 + **dag_alert_config / dag_alert_history**（方案调整：这两表随 DagAlertService 归 app-alert，不再归 engineering）
  - 收拢 Controller：system 的 AlertRuleController/AlertHistoryController、engineering 的 DagAlertRuleController/SyncJobAlertRuleController/DagAlertConfigController、governance 的 CollectTaskAlertRuleController
- 新建 4 个 api 模块骨架：data-nest-alert-api（完整）、system-api / engineering-api / governance-api（仅本阶段需要的端点，后续阶段扩充）
- 消费方改造：task-core 执行器 fire/fireBatch、DagExecutionFinishedListener 远程化、job 超时告警、engineering/governance 的 deleteByObject 级联 → 全部改 Feign
- app-alert 反向依赖：system（用户邮箱/用户名）、engineering（DAG/同步任务名称与下拉、dag_node 解析）、governance（采集/质量任务名称与下拉、质量自动触发）→ 各服务新增 `/internal/**` 端点
- 内部鉴权：`X-Internal-Token` 头（common 模块统一过滤器 + Feign 拦截器），仅拦截以 `/internal/` 开头的路径（不影响 DS 回调 `/dev/internal/**`）
- gateway 加 `/api/alert/**` 路由；前端告警 API 路径改 `/api/alert/**`
- 邮件配置集中到 app-alert，其余服务撤掉 shared-alert.yaml 引用与 MAIL_* 环境变量
- 本阶段**不拆库**（app-alert 暂连同一 datanest 库，阶段 5 统一拆）

## 状态看板

| 事项 | 状态 |
|---|---|
| 调研（模块结构/DB 矩阵/调用点） | ✅ 完成 |
| 方案批准 | ✅ 已批准 |
| Chunk A：api 模块 + 内部 token 基础设施 | ✅ 完成（4 api 模块 + InternalTokenFilter/Feign 拦截器 + shared-internal.yaml） |
| Chunk B：app-alert 服务 + 提供方内部端点 | ✅ 完成（data-nest-alert-service 模块，全量编译通过） |
| Chunk C：消费方 Feign 化 + 删 data-nest-alert | ✅ 完成（旧模块/旧实体已删，全量编译通过） |
| Chunk D：gateway/compose/Nacos/前端 | ✅ 完成（compose 加 app-alert、MAIL_* 收拢、shared-internal 已推送、前端 tsc 通过） |
| 构建部署 + 回归验证 | ✅ 完成（8 容器 healthy，验证记录见下） |

## 阶段 1 验证记录（2026-08-06，全部通过）

- 8 容器（含新 app-alert、重建的 gateway）全部 Up/healthy
- `GET /api/alert/alert-rules` 分页 ✅；旧路径 `/api/system/alert-rules` 已 404 ✅
- 对象下拉 4 类型（DAG 树/SYNC_JOB/COLLECT_TASK/QUALITY）✅ → app-alert→engineering/governance Feign 链路通
- `/alert/internal/fired` 无 token / 错 token → 401 ✅；Feign 互调自动带头 ✅
- `PUT /api/alert/rules/by-object` 建规则（objectName 经 engineering 远程解析）✅
- **fire 全链路 E2E**：触发同步任务 → worker SyncJobExecutorService → Feign fire → app-alert → system 邮箱反查 + engineering 名称解析 → MailHog 收到「[DataNest 通知] 同步任务…执行成功」→ alert_history 落库（rule_name/alert_type/send_status/recipients 正确）✅
- `/api/alert/alert-history` 分页、`/api/alert/dag-alert-config` ✅；验证规则已删除（未留脏数据）
- 前端 `npm run typecheck` 0 错误（浏览器端 UI 未逐页人工点验，建议下个会话快速过一遍告警中心/同步任务告警弹窗/DAG 告警配置弹窗）

## 关键调用点清单（改造时对照）

- fire/fireBatch：task-core `SyncJobExecutorService:100,122`、`CollectExecutor:204,214`、`QualityCheckService:204,623,631`（均在 worker 容器运行）
- DAG 终态：`DagAlertExecutionListener`（被 `DagExecutionSyncService` 经 `List<DagExecutionFinishedListener>` 回调，worker+job）→ 改为 task-core 内新 listener 调 AlertApi + GovernanceApi
- 节点超时：job `DagNodeTimeoutAlertHandler:48,56`（resolveConfig + onNodeTimeout）
- deleteByObject 级联：engineering `DagService:251`/`DagProjectService:175`/`SyncJobService:216`、governance `CollectTaskService:230`
- alert_history 反查：`QualityCheckService.getBatchDetail` 按 quality_batch_id 查 AlertHistoryMapper → app-alert 内部端点
- alert_history 清理：job AlertCleanupHandler → app-alert 内部清理端点
- system `MyBatisPlusConfig` `@Import({SysUserService.class, AlertRuleService.class})` → 移除 AlertRuleService

## Blocker

- 无

## 实施记录（Chunk A-D 关键变更与遗留）

**契约/结构**
- `data-nest-alert-api` 在 Chunk C 又扩了 4 个端点：`GET /alert/internal/dag-alert-config/resolve?dagId=`（job 超时阈值判断，返回 enabled+timeoutMinutes）、`DELETE /alert/internal/dag-alert-config/by-dag?dagId=` 与 `DELETE /alert/internal/dag-alert-histories/by-executions?executionIds=`（engineering 删 DAG/项目级联）、`GET /alert/internal/rules/by-object/names`（QualityJobService 删除前引用校验）。
- 最终 Feign 依赖：worker/job/engineering/governance → alert-api；app-alert → system-api/engineering-api/governance-api。
- 各服务启动类 `@EnableFeignClients(basePackages = "com.datanest.alert.api")`；app-alert 扫 system/engineering/governance 三个 api 包。

**重要实现细节**
- 启动类 scanBasePackages 只追加 `com.datanest.common.internal`（不扫整个 common）：扫全包会误装配 `SchedulerClient`（`@Value("${xxl.job.admin.addresses}")` 无默认值，未引 shared-xxljob 的服务会启动失败）。system 此前未扫 common，InternalTokenFilter 因此才补上。
- InternalTokenFilter 只拦截 servlet path 以 `/internal/` 开头的请求，DS 回调 `/dev/internal/**` 不受影响；token 为空放行（本地兜底），配置后经 Nacos `shared-internal.yaml` 下发。
- `QualityJobService` 删除前"被告警规则绑定"校验采用**失败关闭**（alert 服务不可用则禁止删除，抛 BusinessException），避免引用校验被静默跳过。
- 其余 Feign 调用（fire/fireBatch/dag-finished/deleteByObject 级联/超时告警）全部 try-catch 容错只记日志，符合最终一致性语义。
- `dag-finished` 端点内部完成 DAG 告警 + 质量自动触发（经 engineering 解析 dag_node.id → governance auto-trigger），调用方（task-core RemoteDagFinishedListener）只发一个请求。
- app-alert **不暴露宿主机端口**（对外统一走 gateway `/api/alert/**`，容器间 Feign 走 datanest-net:8088；调试 internal 端点需 `docker exec` 进容器或经其它服务发起）。

**遗留（后续阶段处理）**
- ⚠️ app-alert 的 `AlertHistoryMapper.selectHistoryPage` 仍有**跨表 LEFT JOIN**（dag/sync_job/collect_task/quality_job）——同库期间正常，**阶段 5 拆库前必须改为 Feign 反查对象名**。
- `constant/AlertConstants` 在 entity 模块和 app-alert 各有一份副本（entity 版保留因 ScoreCalculator/QualityCheckService/DagService 仍在用），阶段 6 清理。
- 其它服务 compose 里残留的 `depends_on: mailhog` 未清理（无害）。
- 质量批次详情 `alertHistories` 字段类型变为 alert-api 的 AlertHistoryDTO（字段同名，前端 JSON 不变）。

**部署期踩坑（已修复）**
- Feign `lb://` 调用必需 `spring-cloud-starter-loadbalancer`：governance/alert-service 原本就有，engineering/worker/job 缺失导致启动报 `No Feign Client for loadBalancing defined`，已在三个 pom 补齐。后续阶段给服务加 Feign client 时先确认该依赖。
- app-alert 缺 `spring-boot-starter-validation`（common 中是 provided，GlobalExceptionHandler 引用 jakarta.validation 类），启动报 `NoClassDefFoundError: jakarta/validation/ConstraintViolationException`，已补。新服务脚手架时记得带上。

## Next Action

**阶段 3 已完成**。下一阶段（阶段 4：governance 域远程化，约 2-3 会话）：
1. governance-api 扩充：质量执行回写（batch/detail）、采集回写（history/execution_log/change_detail）、元数据写入（metadata_table/column）、血缘写入（lineage_record）、合规结果写入。
2. entity 模块治理表（metadata_*/collect_*/quality_*/compliance_check_result/lineage_record/naming_standard/field_type_standard/asset_classification）复制到 governance 本地包。
3. worker 的 `QualityCheckService`（批次/明细回写）、`CollectExecutor`（采集三表回写）、`MetadataRegistrationService`/`SqlLineageExtractor`（元数据/血缘写入）改 Feign；task-core-governance 的质量编排类（QualityJobService/QualityRuleService 等）迁入 governance 或保留 libs 但表访问本地化。
4. `QualityAutoTriggerReconcileHandler` 剩余两张治理表（quality_job/quality_check_batch）的直读改 governance-api。
5. 阶段 4 完成后治理表旧实体删除（同 3.5 模式）。
6. 重点回归：质量三种触发（手动/定时/自动）、采集全流程、元数据/血缘、合规检查、批次详情告警反查（quality_batch_id 链路）。

阶段 5（拆库，**先处理 app-alert `selectHistoryPage` 跨表 LEFT JOIN**）→ 阶段 6（删 task-core 剩余模块、AlertConstants 双份合并、docs/agent/* 同步、全量回归）。

**阶段 5 配置整理清单（2026-08-07 评审决定，与拆库一起做）**：

**阶段 5 拆库形态决策（2026-08-07）**：**多数据库**（datanest_system / datanest_alert / datanest_engineering / datanest_governance），不用单库多 schema——硬边界使跨域直读物理不可能，独立权限/备份/Flyway。实施要点：PG volume 已有数据，init 脚本不会重跑，4 个库手动 CREATE DATABASE；旧 datanest 库保留只读观察一个迭代再下线。
1. shared-datasource.yaml 拆 4 个 per-service 数据源配置（datanest_system/datanest_alert/datanest_engineering/datanest_governance）——worker/job 无库不需要
2. shared-alert.yaml 下沉 app-alert 本地配置（单消费方，共享无意义）
3. 摘掉 worker/job 的 shared-datasource.yaml import（4.4 后两服务无任何本地 DB 访问，PG 连接池是死配置；摘掉时验证无 DataSource 时 MyBatis-Plus 自动配置正常退让）
4. shared-internal.yaml + shared-feign.yaml 合并为 shared-rpc.yaml（同属服务间调用域）
5. 评估 shared-doris + shared-addax 合并（同属执行引擎域）、shared-quality 归属
6. Nacos config_info 里被合并/下线的旧 dataId 要清理
