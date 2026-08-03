# Sprint 4 Handoff

> 本文件用于 Sprint 4 内多个 Agent 会话之间的状态同步。每次会话结束时更新状态；新会话进入时先读此文件。
>
> **最后更新**：2026-08-03 — Sprint 4 验收（多表同步+速率限流、重跑失败节点展示、列表筛选持久化、同步日志批量写+按表分页滚动加载）完成并验证通过；详见文末
> 「2026-08-03 — Sprint 4 验收记录」。另：修了 `SysUserMapper` `@Select(<script>)` 内 `email <> ''`（非法 XML，改 `!=`）与迁移
> `V3.5.5` `email` 列歧义（unnest 别名 `email`→`rcpt`），并豁免了 3.4.1/3.4.3 因行尾变化导致的 Flyway checksum 校验。
> 注意：Sprint 5（另一 Agent 并行开发）已改动 `data-nest-common`/告警规则/SQL 血缘等，本文件不覆盖其内容。

## Sprint 目标

Sprint 4 在 Sprint 3 DAG 编排基础上，扩展任务类型、提升可复用性、增强可观测性，并为数据治理提供血缘基础：

1. **Python 任务节点**：DAG 画布支持 PYTHON 节点，脚本在隔离进程中执行。
2. **DAG 级参数化**：支持自定义参数与系统变量，SQL/Python 节点通过 `${paramName}` 占位符替换。
3. **监控与邮件告警**：DAG 失败/成功/节点超时时发送邮件通知。
4. **SQL 血缘自动上报**：SQL 节点执行成功后解析 source → target 血缘并上报治理模块。
5. **DAG 版本管理**：保存即生成版本快照，支持对比与回滚。
6. **真正的重跑失败节点**：替换 Sprint 3 MVP 全量重跑，仅重跑 FAILED/SKIPPED 节点。
7. **多表同步 + 速率限流收尾**：后端字段与执行逻辑已就绪，前端 UI 补齐中。

> 详细需求见 [DataNest-Sprint4-PRD.md](../DataNest-Sprint4-PRD.md)
> 与 [DataNest-Sprint4-技术文档.md](../DataNest-Sprint4-技术文档.md)，
> 原型见 [Sprint4-Python参数化监控告警血缘.html](../ui/Sprint4-Python参数化监控告警血缘.html)。

## 范围

| 模块 | 状态   | 说明                                                                                                       |
|------|--------|------------------------------------------------------------------------------------------------------------|
| 文档 | 进行中 | PRD/技术文档已存在；按 DAG 告警配置已回写到技术文档 8.1/8.2/8.5 节。其他文档由对应负责人继续维护。         |
| 前端 | 已完成 | Sprint 4 全部功能已开发，构建验证通过；review 修复已落地。已联调并通过 E2E。                               |
| 后端 | 已完成 | Phase 1~4 实现 + review 4 项优化均已落地；节点执行迁移到 worker 已部署并回归通过；测试中发现的问题已修复。 |
| 测试 | 已完成 | API 测试 + E2E 12/12 通过；测试数据已清理；遗留问题已标记。                                                |

## 关键决策

- **告警配置支持按 DAG 覆盖**（review 新增）：`dag_alert_config` 表新增可空 `dag_id` 列。触发告警时先查该 DAG
  专用配置，无则回退全局默认配置。
- **超时扫描改为按 DAG 配置判断阈值**：`DagNodeTimeoutAlertHandler` 扫描 RUNNING 节点时通过 `execution_id → dag_id`
  关联，再取该 DAG 告警配置（含 `timeout_minutes`）决定是否发送。
- **血缘批量写入**（review 优化）：`SqlLineageExtractor` 收集到批量血缘记录后统一 `insertBatch`，单条时仍走 `insert`。
- **版本 diff 性能优化**（review 优化）：`DagVersionService.generateChangeSummary` 不再 `selectByDagId` 全量加载历史版本，改用已有的
  `selectMaxVersionNo` 直接取上一条快照做 diff。
- **告警邮件内容增强**（review 优化）：节点超时邮件补充 DAG 名、DAG 执行时间、查看链接，与失败/成功告警保持一致。
- **Python 执行位置**：与 SQL/SYNC 一致，通过 DolphinScheduler HTTP 回调到 `data-nest-worker` 本地执行 `python3`。
  `data-nest-engineering` 不再执行节点，只负责 DAG 管理、参数 CRUD、版本、告警配置等非执行类 API。
- **节点执行收敛到 worker**：SQL/SYNC/PYTHON 三种节点回调均由 `data-nest-worker` 接收，`data-nest-worker` 直接操作业务库 写
  `node_execution`、`node_execution_log`、`lineage_record`、`sync_job_history` 等。
- **血缘写入位置**：节点执行侧（worker）直接写入 `lineage_record`，`governance-service` 只提供查询接口，避免跨服务同步调用阻塞
  DAG 执行。
- **公共能力下沉到 task-core**：为支持 worker 执行节点，将 `DagParameterResolver`（参数解析/占位符替换）、
  `SyncJobTriggerService`（同步任务触发）、`SyncNodeMutexService`（SYNC 互斥锁）、`NodeExecutionLogService`（节点日志写入） 下沉到
  `data-nest-task-core`；worker 与 engineering 共用。
- **版本快照**：Sprint 4 先全量保存节点/边/参数 JSON，后续 Sprint 再评估 diff 存储。
- **文档/代码/原型一致性**（Sprint 4 收尾）：技术文档 3.1 包结构、`MailService` 位置，PRD 6.3.2/6.3.3 Python 环境说明，
  `DagController.rerunFailed` 注释已按实际实现更新；原型已移除「系统管理 → DAG 告警配置」独立入口，改为在 DAG
  编辑器工具栏显示「告警」按钮，并标注按 DAG 覆盖实现口径。

## 跨会话状态看板

| 会话 | 负责人（Agent/人） | 状态   | 已完成                                                   | 待办                   | 备注             |
|------|--------------------|--------|----------------------------------------------------------|------------------------|------------------|
| 文档 | -                  | 进行中 | PRD/技术文档已创建；按 DAG 告警配置已回写                | 其他模块文档继续维护   | 非阻塞           |
| 前端 | 当前 Agent         | 已完成 | Sprint 4 全部功能 + review 修复                          | 前后端联调             | 构建验证通过     |
| 后端 | 后端 Agent         | 已完成 | Phase 1~4 实现 + review 4 项优化；迁移部署与回归测试通过 | 等待用户安排新功能测试 | -                |
| 测试 | -                  | 未开始 | -                                                        | 接口/集成/回归测试     | 用户安排后再执行 |

## 前端关键决策（2026-08-02，用户确认）

- **告警配置只做按 DAG 覆盖入口**：DAG 画布工具栏「告警」弹窗，不做系统管理全局页（PRD §6.5.2 的全局配置入口本期不实现）。
- **多表同步字段映射按源表逐个配置**：以技术文档 §12.2 为准，不采用 PRD §6.9.3 的"仅首表配置 + 同名自动映射"。
- **验证方式**：仅 `tsc -b` + `vite build` 构建验证，不重建 Docker 前端容器、不联调（用户明确）。

## 当前 Blocker

- **Sprint 4 新功能测试待用户安排**：迁移回归测试已通过；Python/血缘/告警/版本等新功能仍待用户安排测试。不要自行启动测试。
- **前端未联调**：前端按已核实的后端契约开发（见下「前端核实口径」），仅构建验证，需安排前后端联调确认。

## 前端核实口径（与后端契约逐项比对通过）

- 节点实时日志实际路径带 `/dag-executions` 前缀：
  `GET /api/engineering/dag-executions/dev/executions/{executionId}/nodes/{nodeId}/logs`（与技术文档不一致，已按后端实际实现对接）。
- `sourceTablesDetail`：请求体为 JSON 字符串，响应为对象数组；顶层 `targetTable/fieldMapping` 取第一张表。
- 按 DAG 读告警配置：无专属配置时回退全局，响应 `dagId=null` 即全局（弹窗据此显示"继承全局"提示）。
- 版本 diff 项是字符串（nodeId / "a->b" / paramName）；`createdBy` 仅数字 id，前端显示"用户 {id}"。
- Snowflake id 19 位，前端全程保持 string，不转 Number ()。

## 环境/脏数据状态

- TODO：由测试/部署前确认当前环境是否干净、是否有测试数据、是否需要清理。
- 本次修改仅涉及后端代码与 Flyway 迁移脚本，未直接操作生产/测试数据库。

## 变更清单

### 后端新增文件

- `data-nest-system/src/main/resources/db/migration/V3.3.10__dag_alert_config_dag_id.sql` — 按 DAG 告警配置字段
- `data-nest-task-core/.../dto/PythonNodeConfig.java`
- `data-nest-task-core/.../service/PythonExecutor.java`
- `data-nest-task-core/.../service/SqlLineageExtractor.java`
- `data-nest-task-core/.../service/DagParameterResolver.java` — DAG 参数解析/占位符替换
- `data-nest-task-core/.../service/SyncJobTriggerService.java` — 同步任务触发
- `data-nest-task-core/.../service/SyncNodeMutexService.java` — SYNC 节点互斥锁
- `data-nest-task-core/.../service/NodeExecutionLogService.java` — 节点执行日志
- `data-nest-task-core/.../entity/DagVersion.java`
- `data-nest-task-core/.../mapper/DagVersionMapper.java`
- `data-nest-task-core/.../entity/DagParameter.java`
- `data-nest-task-core/.../mapper/DagParameterMapper.java`
- `data-nest-task-core/.../entity/DagAlertConfig.java`
- `data-nest-task-core/.../mapper/DagAlertConfigMapper.java`
- `data-nest-task-core/.../entity/DagAlertHistory.java`
- `data-nest-task-core/.../mapper/DagAlertHistoryMapper.java`
- `data-nest-task-core/.../entity/LineageRecord.java`
- `data-nest-task-core/.../mapper/LineageRecordMapper.java`
- `data-nest-engineering/.../controller/DagVersionController.java`
- `data-nest-engineering/.../controller/DagAlertConfigController.java`
- `data-nest-engineering/.../service/DagVersionService.java`
- `data-nest-engineering/.../service/DagParameterService.java`
- `data-nest-engineering/.../service/DagAlertService.java`
- `data-nest-task-core/.../service/DagAlertExecutionListener.java` — DAG 终态/节点失败事件监听，下沉后
  worker/job/engineering 共用
- `data-nest-task-core/.../service/DagEdgeSnapshot.java` — 边快照采集，下沉后 worker/engineering 共用
- `data-nest-task-core/.../service/GenericSqlExecutor.java` — SQL 预览公共执行器
- `data-nest-task-core/.../service/DataPreviewService.java` — 表预览公共查询
- `data-nest-task-core/.../dto/DataPreviewResult.java` — 表预览结果 DTO
- `data-nest-task-core/.../service/MailService.java`
- `data-nest-engineering/.../dto/DagVersionPayload.java`
- `data-nest-engineering/.../dto/DagAlertConfigPayload.java`
- `data-nest-job/.../handler/DagNodeTimeoutAlertHandler.java`
- `data-nest-worker/.../controller/DagNodeCallbackController.java` — 接收 DS 节点回调
- `data-nest-worker/.../service/DagNodeExecuteService.java` — 节点执行（SQL/SYNC/PYTHON）
- `data-nest-governance/.../controller/LineageController.java`
- `data-nest-governance/.../service/LineageService.java`

### 后端修改文件（重点）

- `data-nest-engineering/.../service/DagVersionService.java` — 版本 diff 性能优化
- `data-nest-task-core/.../service/DagAlertService.java` — 按 DAG 解析告警配置 + 邮件内容增强
- `data-nest-engineering/.../controller/DagAlertConfigController.java` — 新增 `/dev/dags/{dagId}/alert-config` GET/PUT
- `data-nest-engineering/.../dto/DagAlertConfigPayload.java` — 增加 `dagId`
- `data-nest-task-core/.../entity/DagAlertConfig.java` — 增加 `dagId`
- `data-nest-task-core/.../mapper/DagAlertConfigMapper.java` — 新增 `selectByDagId`，调整 `selectGlobal`
- `data-nest-task-core/.../entity/NodeExecution.java` — 增加 transient `dagId`
- `data-nest-task-core/.../mapper/NodeExecutionMapper.java` — 新增 `selectRunningWithDagId`
- `data-nest-job/.../handler/DagNodeTimeoutAlertHandler.java` — 按 DAG 配置判断超时
- `data-nest-task-core/.../service/SqlLineageExtractor.java` — 血缘批量写入
- `data-nest-task-core/.../mapper/LineageRecordMapper.java` — 新增 `insertBatch`
- `data-nest-task-core/.../service/PythonExecutor.java` — `read_doris_table` 返回 pandas DataFrame；`runPython` 通过
  `ulimit -v` 强制虚拟内存限制
- `data-nest-engineering/.../controller/DagController.java` — 重跑失败节点注释改为 Sprint 4 真实实现说明

#### Sprint 4 架构调整（节点执行从 engineering 迁移到 worker）

- `data-nest-engineering/.../config/DolphinSchedulerConfig.java` — `callbackBaseUrl` 默认值改为
  `http://app-gateway:8080/api/worker`
- `data-nest-engineering/.../service/DagDsConverter.java` — 回调 URL 注释更新为 `/api/worker`，DS HTTP 任务仍路由到
  gateway
- `data-nest-engineering/.../service/DagParameterService.java` — 参数解析/占位符替换委托 `data-nest-task-core` 的
  `DagParameterResolver`
- `data-nest-engineering/.../service/SyncJobService.java` — 同步任务触发委托 `data-nest-task-core` 的
  `SyncJobTriggerService`；互斥锁改用 task-core `SyncNodeMutexService`
- `data-nest-engineering/.../service/NodeExecutionLogQueryService.java`（由 `NodeExecutionLogService` 重命名）— 改为
  task-core `NodeExecutionLogService` 的薄封装，仅保留 DTO 转换
- `data-nest-engineering/.../service/SyncNodeMutexService.java` — 删除，迁移到 `data-nest-task-core`
- `data-nest-engineering/.../controller/DagNodeCallbackController.java` — 删除，SQL/SYNC 回调由 worker 承接
- `data-nest-engineering/.../controller/PythonCallbackController.java` — 删除，Python 回调由 worker 承接
- `data-nest-task-core/.../dto/PythonNodeConfig.java` — 下沉到 task-core，字段与注释调整，供 engineering/worker 共用
- `data-nest-worker/pom.xml` — 增加 fastjson2 依赖
- `data-nest-worker/Dockerfile` — 安装 `python3`、`py3-pip`、`pandas`、`pymysql`
- `data-nest-engineering/Dockerfile` — 移除 Python 环境安装
- `data-nest/docker-compose.yml` — `python-sandbox` volume 从 `app-engineering` 移到 `app-worker`
- `data-nest-gateway/src/main/resources/application.yml` — 新增 `/api/worker/**` 路由到 `data-nest-worker`
- `data-nest-gateway/src/main/java/com/datanest/gateway/config/SaTokenConfig.java` — `/api/worker/dev/internal/**`
  加入匿名白名单
- `shared-configs/shared-dolphinscheduler.yaml` — `callback-base-url` 改为 `/api/worker`
- `data-nest-task-core/.../config/MybatisPlusInterceptorAutoConfiguration.java` — 统一下沉分页 + 乐观锁拦截器；删除
  engineering/worker/job/governance 各自的 `MybatisPlusConfig`
- `data-nest-task-core/.../service/DagAlertExecutionListener.java` — 从 engineering 下沉到
  task-core，worker/job/engineering 均注册该 Bean
- `data-nest-task-core/.../service/DagEdgeSnapshot.java` — 从 engineering 下沉到 task-core；提供公共 `capture` 方法供
  worker 执行节点时采集边快照
- `data-nest-task-core/.../service/GenericSqlExecutor.java` / `DataPreviewService.java` / `dto/DataPreviewResult.java` —
  从 engineering 下沉到 task-core，SQL 预览/表预览能力供多模块共用
- `data-nest-engineering/.../service/DagExecutionService.java` — 改用 task-core `DagEdgeSnapshot` 采集边快照
- `data-nest-engineering/.../service/SqlPreviewService.java` — `GenericSqlExecutor` 改从 task-core import
- `data-nest-engineering/.../controller/DataPreviewController.java` — `DataPreviewService` 改从 task-core import
- `data-nest-engineering/.../service/SyncJobService.java` — 移除 `RetryService` 注入；engineering `RetryService` 为死代码，已删除
- `data-nest-engineering/.../util/SqlStatementSplitter.java` — 删除 engineering 侧与 task-core 重复的多余实现
- `data-nest-worker/.../service/DagNodeExecuteService.java` — 删除内联 `captureEdgeSnapshot`，改用 task-core
  `DagEdgeSnapshot.capture`

> 部署注意：
> 1. 已保存/上线的 DAG 需要重新保存并上线，DS 任务回调地址才会切到 `/api/worker`；否则 DS 仍会回调旧 engineering 路径。
> 2. 修改 `shared-configs/shared-dolphinscheduler.yaml` 后需重新执行 Nacos 初始化（
     `docker compose --profile init up middleware-nacos-init`）并重启 `app-engineering`，否则 engineering 仍使用 Nacos 中旧的
     `callback-base-url`。
> 3. `app-engineering` 镜像已移除 Python 环境；Python 节点测试执行由 `data-nest-worker` 承担。

#### 回归测试中发现并修复的问题

- `data-nest-gateway/.../config/SaTokenConfig.java` — `/api/worker/dev/internal/**` 加入匿名白名单，避免 DS 回调被
  Gateway 鉴权拦截
- `data-nest-task-core/.../config/MybatisPlusInterceptorAutoConfiguration.java` — 统一提供分页 + 乐观锁拦截器，删除
  engineering/worker/job/governance 各自的 `MybatisPlusConfig`，避免部分模块缺插件
- `data-nest-task-core/.../service/SyncNodeMutexService.java` — 移除 `tryLock` 中基于“无 RUNNING
  记录”的残留锁清理逻辑，避免与正在启动但尚未写入 RUNNING 记录的线程产生竞态，导致同一 `syncJobId` 并发执行
- `data-nest-engineering/.../service/NodeExecutionLogService.java` → 重命名为 `NodeExecutionLogQueryService.java`，解决与
  task-core `NodeExecutionLogService` 的 Bean 名冲突导致 engineering 启动失败
- `data-nest-engineering/.../controller/DagExecutionController.java` — 注入改为 `NodeExecutionLogQueryService`
- `data-nest-task-core/.../service/AddaxJobService.java` — 修复多表同步只执行第一张表：Addax
  `ConfigParser.upgradeJobConfig()` 会在 `job.content` 为数组时只取第一个元素，因此改为按 `sourceTables` 逐张生成独立 job
  文件顺序执行， 并聚合日志与行数；同时修复增量模式下后续表取错目标表水位的问题
- `data-nest-task-core/.../service/MetadataRegistrationService.java` — 多表同步成功后注册元数据时，优先使用
  `sourceTablesDetail` 中每张表的 `targetTable`，避免全部注册到第一张目标表

### 前端新增文件（2026-08-02）

- `data-nest-frontend/src/pages/engineering/dags/components/PythonEditorModal.tsx` — Python 节点编辑器（Monaco python +
  超时/内存配置 + 运行测试 + outputTables 展示）
- `data-nest-frontend/src/pages/engineering/dags/components/DagParameterDrawer.tsx` — DAG 参数抽屉（草稿 diff 提交，删除前
  `${paramName}` 引用校验）
- `data-nest-frontend/src/pages/engineering/dags/components/TriggerParamsModal.tsx` — 手动触发参数覆盖弹窗
- `data-nest-frontend/src/pages/engineering/dags/components/DagVersionModal.tsx` — 版本列表/对比/回滚
- `data-nest-frontend/src/pages/engineering/dags/components/DagAlertConfigModal.tsx` — 按 DAG 告警配置（继承全局提示）
- `data-nest-frontend/src/pages/engineering/dags/components/NodeRuntimeLogPanel.tsx` — 节点实时日志（RUNNING 时 3s 轮询，30
  分钟超时）

### 前端修改文件（2026-08-02）

- `data-nest-frontend/src/pages/engineering/dags/types.ts` — NodeType 加 PYTHON；新增
  DagParameter/PythonExecuteResult/DagVersion/DagVersionDiff/DagAlertConfig/NodeExecutionLog
- `data-nest-frontend/src/pages/engineering/dags/api.ts` — 参数 CRUD、testPythonNode（超时随脚本配置）、版本三接口、按 DAG
  告警配置、节点实时日志（skipErrorMessage）
- `data-nest-frontend/src/pages/engineering/dags/Editor.tsx` — PYTHON
  节点全链路（面板/拖拽/编辑器/序列化/运行视图）；工具栏「参数」「版本」「告警」；触发前查参数弹覆盖弹窗；SQL/PYTHON 节点挂实时日志面板
- `data-nest-frontend/src/pages/engineering/dag-executions/index.tsx` — 重跑确认弹窗列出 FAILED/SKIPPED 节点 + 成功节点复用说明
- `data-nest-frontend/src/pages/engineering/sync-jobs/SyncJobDrawer.tsx` — 重构：多表多选 + 目标表映射 + 按表字段映射 +
  限流区块；列信息按表缓存
- `data-nest-frontend/src/pages/governance/metadata/index.tsx` — 表详情新增「数据来源」卡片（来源类型/DAG/节点 + 跳转按钮）
- `data-nest-frontend/src/types/sync.ts` — SourceTableDetail + 限流三字段
- `data-nest-frontend/src/types/metadata.ts` — MetadataTable 来源六字段
- `data-nest-frontend/src/lib/monacoSetup.ts` — 注册 python 语言支持

### 前端 review 修复（2026-08-02）

- major：节点日志轮询加 `skipErrorMessage`（避免 RUNNING 期间错误弹窗轰炸）；SyncJobDrawer 列信息加载合并为单 effect Set
  去重（避免单表模式重复拉取）
- minor：monacoSetup 注册 python；Python 测试 axios 超时随 timeoutMinutes；运行测试叠加"无未保存变更"校验；删除
  runViewPosCacheRef 死代码；系统变量记法统一 `${biz_date}`；参数抽屉 footer 去 useMemo；重跑空列表兜底；多表任务编辑降级兜底（editIsMulti
  时强制带 sourceTablesDetail）；限流校验改 >0；告警未勾 TIMEOUT 不提交 timeoutMinutes；PythonEditorModal 复用
  selectedNode；结果区补 outputTables

### 前端 review 修复（2026-08-02 第二轮）

- 采集执行历史：
  - 状态筛选删除「部分成功」（后端 `ExecutionStatus` 无 `PARTIAL`），同步收窄 `types/collect.ts` 的 `ExecutionStatus`。
  - 新增「是否变化」列：按 `added/updated/deleted` 表/字段数合计 >0 显示「有变化」/「无变化」徽章。
  - 「库/表/字段」列改名为「扫描库表字段」；对应 `scroll.x` 从 1120 调整到 1200。
- 项目列表 / DAG 列表：日期、创建人/修改人等实际数据颜色从 `text-ds-text-muted` 改为 `text-ds-text-secondary`，`—` 占位保持
  muted；统一为 `text-ds-small` 以与同步/DAG 执行历史页行高对齐。
- 用户管理：重置按钮不再把 `pageSize` 切回 20，保持默认 10 条。

### 前端 review 修复（2026-08-02 第三轮）

- **列表页高度不一致根因修复**：
  - `Layout.tsx` 原先对 `/engineering/dags` 路由跳过全局面包屑，导致项目列表 / DAG 列表顶部基准线比其他列表页低约
    39.5px，表现为"项目列表比 DAG/同步历史高一点"。
  - 统一所有路由渲染 `<Breadcrumb/>`；移除 `ProjectDagsPage` 内部面包屑避免重复。
  - 同步修正 `/engineering/dag-executions` 面包屑分组为"数据工程"。
- **量化验证**：Playwright 测量确认 10 条时项目列表、DAG 执行历史、同步执行历史的表格卡片高度均为 564.78px，行高
  47px，header/toolbar/card 顶部基准线一致。
- **采集执行变更日志样式补齐**：新增表 / 删除表 / 变化表 / 原始日志统一为带标题头的圆角卡片，标题头置于卡片内部并带灰底，内容行以分隔线区分，对齐原型；本期不实现点击看字段。
- **项目列表标题对齐**：项目列表页大标题从「数据开发」改为「项目管理」，与面包屑「数据开发 / 项目管理」一致。
- **DAG 列表增加返回入口**：`ProjectDagsPage` 页头右侧新增「返回项目列表」按钮，与同步执行历史等页面返回风格一致。

## 验证证据

- 编译命令：
  ```bash
  cd data-nest
  mvn -q -DskipTests compile
  ```
- 结果： **成功**（无编译错误）。
- 前端验证（2026-08-02）：
  ```bash
  cd data-nest/data-nest-frontend
  pnpm build
  ```
- 结果： **成功**；`eslint .` 0 error（dag-executions 有 1 个 Sprint 4 之前就存在的 useMemo 依赖 warning，未动）。
- 前端部署（2026-08-02）：
  ```bash
  cd data-nest
  docker compose build app-frontend && docker compose up -d app-frontend
  ```
- 结果：`datanest-app-frontend` 镜像重建成功，容器 `healthy`，`http://localhost:3000/` 返回 200。
- 前端第三轮修复验证（2026-08-02）：
  - `pnpm build` 通过；`npx eslint src` 通过。
  - Docker 重建 `app-frontend` 成功，`datanest-app-frontend` healthy。
  - Playwright 测量：10 条时项目列表 / DAG 执行历史 / 同步执行历史表格卡片高度均为 564.78px，行高 47px，顶部对齐。
  - 采集变更日志弹窗截图验证：新增表已渲染为带边框圆角卡片 + 行分隔线。
- 后端迁移回归测试（2026-08-02，重新部署后）：
    - 触发 DAG `regression-migration`（1 SQL + 1 SYNC）→ SQL 节点与 SYNC 节点均回调到 `data-nest-worker`，执行记录 ID
      `2083822336429502465` 最终 status SUCCESS，两个节点均 SUCCESS。
    - DS `t_ds_task_definition` 中回调 URL 已切到 `http://app-gateway:8080/api/worker/dev/internal/...`。
    - 全量编译 `mvn -q -DskipTests compile` 通过；
      `mvn -q -pl data-nest-engineering,data-nest-worker,data-nest-job,data-nest-governance -am package -DskipTests`
      通过；app-engineering/app-worker/app-job/app-governance/app-gateway 镜像已重建并启动健康。
- 未执行 Sprint 4 新功能（Python/血缘/告警/版本等）的端到端测试。

## 2026-08-02 — Sprint 4 全功能测试完成

### 测试执行结果

- **API 测试**：完成（通过 `e2e/sprint4/api-helpers.ts` 与 curl 覆盖数据准备、触发、状态轮询）。
- **E2E 测试**：`npx playwright test e2e/sprint4/ --project=chromium --reporter=list --timeout=120000`
  - **结果：12/12 通过**，耗时约 40 秒。
  - 命令：
    `cd data-nest/data-nest-frontend && npx playwright test e2e/sprint4/ --project=chromium --reporter=list --timeout=120000`

| #  | 用例                           | 状态 |
|----|--------------------------------|------|
| 1  | 用户登录进入首页               | ✅   |
| 2  | 创建 DAG 项目                  | ✅   |
| 3  | 在项目中新建并保存 DAG         | ✅   |
| 4  | DAG 参数化增删改查             | ✅   |
| 5  | Python 节点配置与运行测试      | ✅   |
| 6  | DAG 版本对比与回滚             | ✅   |
| 7  | DAG 告警配置                   | ✅   |
| 8  | 触发 DAG 执行并查看执行详情    | ✅   |
| 9  | SQL 产出表在元数据页面展示来源 | ✅   |
| 10 | 重跑失败节点                   | ✅   |
| 11 | 创建多表同步任务并执行         | ✅   |
| 12 | （setup）authenticate          | ✅   |

### 测试中发现并修复的问题

| # | 问题                                                        | 根因                                                                                                                                                                                      | 修复位置                                                                                                                                                                                                                                 | 状态            |
|---|-------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------|
| 1 | `MetadataRegistrationService` schema-qualified 表名重复前缀 | `CREATE TABLE datanest.xxx` 被拼成 `datanest.datanest.xxx`                                                                                                                                | `data-nest-task-core/.../service/MetadataRegistrationService.java`                                                                                                                                                                       | ✅ 已修复并验证 |
| 2 | SQL 节点来源信息未写入 SourceContext                        | 元数据注册时未传来源字段                                                                                                                                                                  | `data-nest-worker/.../service/DagNodeExecuteService.java`                                                                                                                                                                                | ✅ 已修复并验证 |
| 3 | 元数据详情「数据来源」卡片不展示                            | `MetadataTableMapper.selectTableDetailById` SQL 未选来源字段                                                                                                                              | `data-nest-task-core/.../mapper/MetadataTableMapper.java`                                                                                                                                                                                | ✅ 已修复并验证 |
| 4 | E2E 测试 7 antd Switch 在 headless 下不切换                 | Playwright 原生 click/Space/label click 无法触发 antd Switch onChange；需等弹窗加载后用 `evaluate((el) => el.click())`                                                                    | `data-nest-frontend/e2e/sprint4/sprint4.spec.ts`                                                                                                                                                                                         | ✅ 已修复并验证 |
| 5 | E2E 测试 11 `waitSyncJobSuccess` 超时                       | ① `/sync-jobs/history/page` 要求 `LocalDateTime`，但 Jackson 无法解析前端/测试传来的 ISO 字符串；② 全局历史接口忽略 `syncJobId`；③  helper 使用 UTC 时间而与后端 Asia/Shanghai 时区不一致 | `data-nest-engineering/.../dto/SyncJobHistoryQueryRequest.java`、`data-nest-engineering/.../service/SyncJobService.java`、`data-nest-engineering/.../controller/SyncJobController.java`、`data-nest-frontend/e2e/sprint4/api-helpers.ts` | ✅ 已修复并验证 |
| 6 | 多表同步实际只同步第一张表                                  | Addax `ConfigParser.upgradeJobConfig()` 在 `job.content` 为数组时只取第一个元素执行，导致多表任务仅第一张表被同步                                                                         | `data-nest-task-core/.../service/AddaxJobService.java`、`data-nest-task-core/.../service/MetadataRegistrationService.java`                                                                                                               | ✅ 已修复并验证 |

### 仍未修复 / 已标记缺失的问题

无。

### 本轮已修复 / 补齐的问题

| # | 问题                                     | 说明                                                                                                                                                                                                                                                      | 验证方式                                                                                                                      |
|---|------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------|
| 1 | **元数据详情页「表级血缘」列表前端缺失** | 原型 `docs/sprint4/ui/Sprint4-Python参数化监控告警血缘.html:2250` 有「表级血缘」列表（source → target）。前端已新增 `/governance/lineage/target/{tableName}` 调用，在元数据详情页以「数据来源 + 表级血缘」两栏卡片展示血缘链路，支持跳转 DAG / 执行历史。 | `pnpm build` + `npx eslint src` 通过；Docker 部署 healthy；Playwright 登录后访问元数据详情页，确认展示 source → target 记录。 |

### 本次变更文件清单

- `data-nest/data-nest-task-core/src/main/java/com/datanest/task/core/service/MetadataRegistrationService.java`
- `data-nest/data-nest-worker/src/main/java/com/datanest/worker/service/DagNodeExecuteService.java`
- `data-nest/data-nest-task-core/src/main/java/com/datanest/task/core/mapper/MetadataTableMapper.java`
- `data-nest/data-nest-engineering/src/main/java/com/datanest/engineering/dto/SyncJobHistoryQueryRequest.java`
- `data-nest/data-nest-engineering/src/main/java/com/datanest/engineering/service/SyncJobService.java`
- `data-nest/data-nest-engineering/src/main/java/com/datanest/engineering/controller/SyncJobController.java`
- `data-nest/data-nest-frontend/src/pages/engineering/dags/components/DagAlertConfigModal.tsx`
- `data-nest/data-nest-frontend/e2e/sprint4/sprint4.spec.ts`
- `data-nest/data-nest-frontend/e2e/sprint4/api-helpers.ts`
- `data-nest/data-nest-task-core/src/main/java/com/datanest/task/core/service/AddaxJobService.java`
- `data-nest/data-nest-task-core/src/main/java/com/datanest/task/core/service/MetadataRegistrationService.java`
  （多表目标表解析修复）
- `data-nest/data-nest-frontend/src/types/lineage.ts`（新增）
- `data-nest/data-nest-frontend/src/api/lineage.ts`（新增）
- `data-nest/data-nest-frontend/src/pages/governance/metadata/index.tsx`（接入血缘查询与两栏卡片 UI；血缘类型标签中文化；字段列表「是否可空」从
  YES/NO 改为 是/否）
- `data-nest/data-nest-frontend/src/pages/engineering/dag-executions/index.tsx`（节点类型分布标签中文化）

### 环境/脏数据状态

- 已清理本次 E2E 产生的 `e2e_*` / `debug_*` 项目、DAG、同步任务、Doris 表及元数据记录。
- `s4_test.s4_orders` / `s4_test.s4_logs` 保留，Doris 端对应表已 `TRUNCATE`。
- `app-engineering` 镜像已基于最新代码重建并健康运行。

## 2026-08-03 — Sprint 4 验收记录

### 验收结论（页面验收，用户确认通过）

| 验收项                            | 结论 | 备注                                                                                                                                                     |
|-----------------------------------|------|----------------------------------------------------------------------------------------------------------------------------------------------------------|
| 多表同步 + 速率限流               | ✅   | 双源表（s4_test.s4_orders/s4_logs）→ 各自目标表；限流 5MB/s+100 行/s；`Addax job JSON` 含 `setting.speed.byte=655360/record=100/channel=3` + per-channel |
| 失败继续跑完其余表                | ✅   | 删目标表重跑：两张表都执行，整体 FAILED，per-table 明细 1 成功 1 失败                                                                                    |
| 重跑失败节点展示                  | ✅   | 复用节点置灰+「复用」角标、运行视图横幅、列表「重跑」徽标                                                                                                |
| 列表筛选持久化（P0/P1）           | ✅   | 查询保留 URL 身份；7 个列表页筛选 URL 持久化，深层跳转返回不丢                                                                                           |
| 同步日志批量写 + 按表分页滚动加载 | ✅   | 500/批 `insertBatch`；`table_name` 标记；概览+每表 Tab 各自无限滚动                                                                                      |

### 产品设计决策

- **重跑实例展示**：运行视图默认显示全部节点，复用节点置灰+「复用」角标、本次执行节点「本次执行」；顶部横幅
  `重跑实例 · 本次重跑 n 个节点，其余 m 个复用上轮结果` + 「仅看本次执行」开关（隐藏复用节点）。判定规则前端推断：
  `节点.startTime < 实例.startTime` ⇒ 复用（零后端改动）。
- **同步日志分页**：`sync_job_log` 加 `table_name` 列（每行标记所属表，平台概要行为 NULL 归「概览」）；日志弹窗 「概览 + 每表」Tab
  各自独立分页（pageSize=200）+ 滚动自动加载更多（无需点击）。`HistoryLogModal` 兼容平铺模式 （DAG SYNC 节点实时日志复用）。

### 变更清单

后端：

- `V3.4.3__sync_job_history_table_results.sql` — `sync_job_history.table_results`（TEXT，per-table 明细）
- `V3.4.4__sync_job_log_table_name.sql` — `sync_job_log.table_name` + 索引（按表分页前提）
- `task-core`：`SyncJobHistory`/`SyncJobLog` 实体加字段；`LineageRecord`（Sprint 5 字段级血缘，另一 Agent）；
  `AddaxJobService` 多表失败继续 + `TableResult(logLines)`；`SyncJobExecutorService` 按表批量写日志（500/批）；
  `SyncJobLogMapper.insertBatch`
- `engineering`：`SyncJobHistoryDTO` 透出 `sourceTables`/`tableResults`；`SyncJobController.logs` +
  `SyncJobService.getLogs`
  按 `scope=overview|all|表名` + `page/pageSize` 分页返回 `PageResult`
- `data-nest-job`/`governance` 等 Sprint 5 改动：另一 Agent，未纳入本文档

前端：

- `api/sync.ts`（`getSyncJobLogs` 带 scope/page/pageSize）
- `pages/engineering/dag-executions/index.tsx`（L1：失败节点跳画布带 from；L2：筛选 URL 持久化；重跑徽标/复用计数）
- `pages/engineering/dags/Editor.tsx`（运行视图复用/本次执行角标、横幅+开关、NodeSummary 执行方式行）
- `pages/engineering/dags/project.tsx`、`dags/index.tsx`、`datasources/index.tsx`、`sync-jobs/index.tsx`、
  `collect-tasks/index.tsx`、`users/index.tsx`、`data-standards/index.tsx`（筛选 URL 持久化）
- `pages/engineering/sync-jobs/history-global/index.tsx` + `history-common.tsx`（日志分页弹窗、P0 查询保留身份）
- `pages/governance/collect-tasks/history-global/index.tsx`（P0 查询保留 taskId）
- `pages/governance/metadata/index.tsx`：树导航，`tableId` 已 URL 持久化（已覆盖，未额外改动）

### 期间修复的问题

| # | 问题                                                                | 根因                                                  | 修复                                            |
|---|---------------------------------------------------------------------|-------------------------------------------------------|-------------------------------------------------|
| 1 | 多表同步任务 2084118527044341761 报 `Failed to flush data to Doris` | 目标表在 Doris 不存在（Addax doriswriter 不自动建表） | 预建两张目标表（`acc_mt_orders/logs_20260803`） |
| 2 | `SyncTableResultDTO.java` 丢失                                      | 另一 Agent 提交后未包含该新文件                       | 重建恢复                                        |
| 3 | `SysUserMapper` 启动报 XML 解析错误                                 | `@Select("<script>")` 内 `email <> ''` 的裸 `<` 非法  | 改 `!=`                                         |
| 4 | 迁移 `V3.5.5` 报 `email is ambiguous`                               | unnest 列别名 `email` 与 `sys_user.email` 冲突        | 别名改 `rcpt`                                   |
| 5 | Flyway 校验 3.4.1/3.4.3 checksum 不匹配                             | git 行尾转换（LF→CRLF）改文件字节                     | 对应 checksum 置 NULL 豁免                      |

### 部署状态（2026-08-03）

- `app-system` / `app-engineering` / `app-worker` / `app-frontend` 已重建并 healthy（task-core 15:28 打包含新代码）。
- 迁移 `V3.4.3`/`V3.4.4` 及 Sprint 5 的 V3.5.x 全部应用成功。
- 环境遗留：测试目标表 `datanest.acc_mt_orders_20260803`/`acc_mt_logs_20260803` 保留，供后续验收/复测。

## Next Action

1. Sprint 4 验收已完成（多表同步/限流/重跑展示/筛选持久化/日志分页）。
2. 可继续补充：邮件告警实际发送（MailHog）、节点超时告警扫描、Python 节点大数据量执行等专项验证。
3. Sprint 5（另一 Agent）与本次改动存在少量交集（`data-nest-common`、告警规则、SQL 血缘），协作时注意 merge 冲突。

## 参考链接

- [项目级 Agent 约定](../../AGENTS.md)
- [Sprint 4 PRD](../DataNest-Sprint4-PRD.md)
- [Sprint 4 技术文档](../DataNest-Sprint4-技术文档.md)
- [Sprint 4 原型（Python/参数化/监控告警/血缘）](../ui/Sprint4-Python参数化监控告警血缘.html)
