# DataNest Sprint 3 实施计划（v2.0 · 基于工程现状 + 10 个已确认决策）

> **Sprint**：Sprint 3 — DAG 编排与 SQL 任务编辑器
> **版本**：v2.0 | **日期**：2026-07-30 | **作者**：基于实际代码现状 + 杰仔 5 轮交互式确认
>
> 实施依据：`docs/sprint3/DataNest-Sprint3-技术文档.md`（DS 路线 86KB，2026-07-30 13:17 更新）
>
> 上一版（v1.0）作废。

---

## 0. 10 个最终决策（已确认）

| #  | 决策点              | 选项                                       | 说明                                                                                                                                                                                                                                                                |
|----|---------------------|--------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1  | task-core 包结构    | **B. 不分 dag/ 子包**                      | 文件全放 `com.datanest.task.core.{entity, mapper, service, dto}` root 包；与 Sprint 2 现有 collect/sync 同级                                                                                                                                                        |
| 2  | DagNode.config 存储 | **B. String 存 JSON 字符串**               | `DagNode.config` 字段是 String；业务层用 ObjectMapper 解析为 Map 或 POJO                                                                                                                                                                                            |
| 3  | 同步任务互斥        | **B. Redis SETNX**                         | 新增 `SyncNodeMutexService`；`redisTemplate.opsForValue().setIfAbsent("datanest:sync:job:mutex:" + syncJobId, "1", 30, TimeUnit.MINUTES)`                                                                                                                           |
| 4  | 前端路由前缀        | **A. /engineering/dags/...**（按代码实际） | 路由 `/engineering/dags`（项目列表）、`/engineering/dags/:projectId`（DAG 列表）、`/engineering/dags/new`（新建）、`/engineering/dags/:id/edit`（编辑画布）、`/engineering/dags/:id/executions/:executionId`（运行视图）、`/engineering/dag-executions`（全局历史） |
| 5  | 执行历史菜单        | **A. 「执行历史」分组下**                  | 新增"DAG 执行历史"菜单项，跟"同步执行历史""采集执行历史"并列                                                                                                                                                                                                        |
| 6  | DS 客户端实现       | **A. 纯 HTTP 自己封装**                    | Spring RestTemplate，跟现有 SchedulerClient 风格一致；0 额外依赖                                                                                                                                                                                                    |
| 7  | DS 元数据库         | **A. 复用 nacos-mysql**                    | 新建 `dolphinscheduler` 库；DS 文档默认方案                                                                                                                                                                                                                         |
| 8  | DS 回调内部接口认证 | **C. 啥也不做，纯内网隔离**                | 开发阶段可接受；代码里留 `@ConditionalOnProperty` hook                                                                                                                                                                                                              |
| 9  | Flyway 迁移位置     | **A. data-nest-system/db/migration/**      | 跟现状一致；所有迁移集中管理                                                                                                                                                                                                                                        |
| 10 | 同步任务引用校验    | **A. 查 dag_node.config LIKE**             | `SELECT dag_id FROM dag_node WHERE config LIKE '%syncJobId\\":xxx%'`；无需中间表                                                                                                                                                                                    |

---

## 1. 路线决策

**采用 DolphinScheduler 3.4.2 集成路线**（DS 文档 §3.2 设计原则）：

- **DS 只负责调度编排**：拓扑、依赖触发、并发控制、终止、状态机
- **DataNest 负责执行**：SQL 通过 `DorisSqlExecutor` 直连 Doris 执行；Python 通过 `PythonExecutorService` 执行；同步任务通过
  DS HTTP 回调触发 engineering 的
  `/engineering/dev/internal/sync/callback` 接口，由 engineering 转调 XXL-JOB；状态由 `DagExecutionSyncService` 反查历史表
- **元数据注册仍在 engineering**：CTAS/CREATE TABLE 后由 engineering 调
  `MetadataRegistrationService.registerFromSql(...)` 写 `metadata_table/column`
- **双调度中心共存**：XXL-JOB 继续管 Sprint 1-2 的同步/采集任务；DS 管 Sprint 3 的 DAG 编排

---

## 2. 工程现状摘要（已读代码事实）

### 2.1 后端工程结构

```
data-nest/
├── data-nest-common/        # Result/PageResult/ErrorCode/BusinessException/
│                            # SchedulerClient（XXL-JOB admin HTTP 客户端）
│                            # JdbcSchemaExtractor / JdbcPreviewHelper
│                            # EncryptionConfig（AES 密钥）
├── data-nest-task-core/     # 共享能力：collect/ + entity/ + mapper/ + service/（root 包，不分子包）
│                            # Sprint 2 已有：MetadataRegistrationService（关键！可复用）
├── data-nest-gateway/       # 端口 8080，已有 3 条路由：/api/system/**、/api/engineering/**、/api/governance/**
│                            # StripPrefix=1，Sprint 3 不用改
├── data-nest-system/        # 端口 8087，用户/角色/权限
│                            # 嵌入 Flyway 迁移，db/migration/ 下所有 V 脚本（V1.0.0 ~ V3.1.3）
├── data-nest-engineering/   # 端口 8082，context-path=/engineering
│                            # 已有：datasource/ + sync/ + scheduler/（SchedulerServiceForEngineering）
│                            # Sprint 3 全部新代码进 dev/ 包
├── data-nest-governance/    # 端口 8084，元数据采集 + 数据标准
├── data-nest-worker/        # 端口 8085，XXL-JOB 执行器
│                            # WorkerJobHandler 已有 @XxlJob("syncJobHandler") 和 ("collectTaskHandler")
│                            # Sprint 3 不再加 handler（DS 通过 HTTP 回调，不走 XXL-JOB）
├── data-nest-job/           # 端口 8086，平台定时任务
├── data-nest-frontend/      # React 18 + Vite 5 + AntD 6.5 + Tailwind + Zustand
│                            # 缺：reactflow、@monaco-editor/react、sql-formatter
```

### 2.2 关键现状

| 项                                    | 现状                                                                                                | Sprint 3 影响                                                                                                                          |
|---------------------------------------|-----------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------|
| Flyway 最新版本                       | **V3.4.1**（仓库已存在 V3.2.0 ~ V3.4.1）                                                            | 新增 V3.2.0 起，无冲突；Python/参数/版本/告警/血缘/限流等扩展已分散在 V3.2.x ~ V3.4.x                                                  |
| 工程 context-path                     | `/engineering`                                                                                      | Controller 映射：`/dev/dag-projects`、`/dev/dags`、`/dev/sql-preview`；全局执行历史 `/dag-executions`；前端访问需加 `/api/engineering` |
| engineering 已有 service              | DataSourceService, DataPreviewService, SyncJobService, SchedulerServiceForEngineering, RetryService | 新增 dev/ 包，**不复用** sync/ 现有 service                                                                                            |
| engineering 已有 controller           | DataSourceController, DataPreviewController, SyncJobController                                      | 新增 dev/ 包                                                                                                                           |
| task-core MetadataRegistrationService | **已有**                                                                                            | 新增 `registerFromSql(sql, operatorId)` 方法复用现有能力                                                                               |
| task-core 现有 MyBatis-Plus mapper    | `BaseMapper<XXX>` 风格                                                                              | 新实体继承 `BaseMapper` 即可                                                                                                           |
| shared-configs                        | shared-addax/common/datasource/doris/nacos/security/xxljob                                          | 缺 shared-dolphinscheduler.yaml，**新建**                                                                                              |
| ErrorCode 编号                        | 1xxx 认证 / 2xxx 用户 / 3xxx 数据源 / 4xxx 治理 / 5xxx 标准 / 6xxx 同步 / 9xxx 系统                 | 新增 7xxx 段：DAG/Project/SqlEditor                                                                                                    |
| frontend 依赖                         | antd 6.5、react-router-dom 6、zustand 5、cron-parser、cronstrue                                     | 缺 reactflow 11、@monaco-editor/react 0.52、sql-formatter 15                                                                           |
| frontend 包管理                       | **npm**（不是 pnpm）                                                                                | 用 `npm install`                                                                                                                       |
| EngineeringApplication.java           | `com.datanest.engineering` 包                                                                       | dev/ 子包同根；mapper 扫包范围已有 task-core，dev/ 包 mapper 需要 `@MapperScan` 包含                                                   |
| EngineeringApplication 注解           | `@MapperScan("com.datanest.task.core.mapper")`                                                      | dev/ 包下的 mapper 也要能被扫到                                                                                                        |
| DS 元数据库                           | **不存在**                                                                                          | 在 nacos-mysql 上新建 `dolphinscheduler` 库                                                                                            |
| gateway routes                        | 3 条（system/engineering/governance）                                                               | 不变                                                                                                                                   |
| Redis                                 | engineering 已有 spring-boot-starter-data-redis                                                     | Redis SETNX 互斥可直接用                                                                                                               |

---

## 3. Sprint 3 范围（严格按 DS 文档 §1.2 的 9 个工作项）

| # | 工作项                            | 所属模块                     | 主要交付                                                                                                      |
|---|-----------------------------------|------------------------------|---------------------------------------------------------------------------------------------------------------|
| 1 | **DolphinScheduler 3.4.2 集成**   | docker-compose + engineering | 4 个 DS 容器 + shared-dolphinscheduler.yaml + `DolphinSchedulerClient`                                        |
| 2 | **项目与 DAG 管理**               | engineering + task-core      | Project/Dag/DagNode/DagEdge/DagExecution/NodeExecution 实体 + CRUD                                            |
| 3 | **DAG → DS 流程映射**             | engineering                  | `DagDsSyncService`（保存 DAG 时同步为 DS ProcessDefinition）                                                  |
| 4 | **SQL 任务执行**                  | task-core + engineering      | `DorisSqlExecutor` + 测试执行 + 元数据注册                                                                    |
| 5 | **同步任务节点**                  | engineering                  | DS HTTP 回调 `/engineering/dev/internal/sync/callback` → XXL-JOB；状态由 `DagExecutionSyncService` 反查历史表 |
| 6 | **执行历史与节点状态**            | engineering + DS API         | 5s 轮询 DS / 3s 前端轮询后端；运行视图支持 SQL/Python/SYNC 节点日志轮询                                       |
| 7 | **多表批量同步**（Sprint 2 增强） | task-core                    | SyncJob `source_tables` JSON 字段扩展 + 多 reader Addax                                                       |
| 8 | **同步速率限流**（Sprint 2 增强） | task-core + Addax            | `read_rate_limit_mbps` / `write_rate_limit_rows_per_second`                                                   |
| 9 | **前端项目管理模块**              | frontend                     | ReactFlow + Monaco + Python + 5 个页面                                                                        |

### 3.2 非目标（仍按 Sprint 4-5 规划）

条件分支 / 子 DAG / 资源队列

> **与代码对齐**：Python 任务、任务参数化、实时日志、失败告警、SQL 血缘、DAG 版本管理等能力已在 Sprint 3 提前实现，不再列入非目标。

---

## 4. 模块架构

### 4.1 服务依赖图

```
┌────────────────────────────────────┐
│       data-nest-frontend           │
│  /pages/engineering/dags/          │
│   ├── index.tsx       (项目列表)   │
│   ├── project.tsx     (DAG 列表)   │
│   ├── Editor.tsx      (ReactFlow 画布 / 运行视图) │
│   └── components/                  │
│       ├── SqlEditorModal.tsx       │
│       ├── PythonEditorModal.tsx    │
│       ├── DagParameterDrawer.tsx   │
│       ├── DagVersionModal.tsx      │
│       ├── DagAlertConfigModal.tsx  │
│       └── NodeRuntimeLogPanel.tsx  │
│  /pages/engineering/dag-executions/│
│   └── index.tsx       (全局执行历史) │
└─────────────┬──────────────────────┘
              │ /api/engineering/dev/**、/api/engineering/dag-executions
              ▼
┌────────────────────────────────────┐
│     data-nest-engineering (8082)   │
│ context-path: /engineering         │
│ /dev/                              │
│  ├── controller/                   │
│  │   ├── DagProjectController      │  ← /dev/dag-projects
│  │   ├── DagController             │  ← /dev/dags
│  │   ├── SqlPreviewController      │  ← /dev/sql-preview
│  │   ├── DagParameterController    │  ← /dev/dags/{id}/parameters
│  │   ├── DagVersionController      │  ← /dev/dags/{id}/versions
│  │   ├── DagAlertConfigController  │  ← /dev/dags/{id}/alert-config
│  │   └── DagExecutionController    │  ← /dag-executions
│  │   └── internal/                 │
│  │       ├── DagNodeCallbackController  ← /dev/internal/{sql,sync,unknown}/callback
│  │       └── PythonCallbackController  ← /dev/internal/python/callback
│  ├── service/                      │
│  │   ├── DagProjectService         │
│  │   ├── DagService                │
│  │   ├── DagDsSyncService          │  ← 核心：DataNest DAG ↔ DS ProcessDefinition
│  │   ├── DagExecutionService       │  ← @Scheduled 轮询 DS / 手动触发 / 停止 / 重跑
│  │   ├── SqlPreviewService         │
│  │   ├── DagParameterService       │
│  │   ├── DagVersionService         │
│  │   ├── DagAlertConfigService     │
│  │   ├── PythonExecutor            │  ← Python 节点执行（task-core）
│  │   ├── DagExecutionSyncService   │  ← SYNC 状态反查历史表（task-core）
│  │   ├── SyncNodeMutexService      │  ← Redis SETNX 互斥
│  │   └── SyncJobService            │  ← 同步任务引用校验、触发
│  ├── client/                       │
│  │   └── DolphinSchedulerClient    │  ← RestTemplate
│  └── converter/                    │
│      └── DagDsConverter            │  ← DAG ↔ DS JSON
└──────┬─────────────────┬───────────┘
       │                 │ HTTP
       │                 ▼
       │      ┌─────────────────────┐
       │      │   DolphinScheduler  │
       │      │   3.4.2 (4 容器)     │
       │      └─────────────────────┘
       ▼
┌──────────────────────┐
│  data-nest-task-core │
│  entity/ (root)      │  ← 不分 dag/ 包
│  mapper/ (root)      │
│  service/ (root)     │
│  ├── MetadataRegistrationService  (已有，新增 registerFromSql)
│  ├── DagTopologyService            (新增)
│  ├── DorisSqlExecutor              (新增)
│  └── ...
│  dto/ (root)         │
│  ├── SqlExecuteRequest/Result
│  ├── DagNodeDTO/DagEdgeDTO
│  └── ...
└──────────────────────┘
       │
       ▼
┌──────────────────────────┐
│  PostgreSQL (datanest)    │
│  • dag_project             │
│  • dag                     │
│  • dag_node                │  config TEXT 存 JSON
│  • dag_edge                │
│  • dag_execution           │  ds_process_instance_id
│  • node_execution          │  ds_task_instance_id
│  • sync_job                │  扩展 rate_limit 字段
│  • metadata_table/column   │  SQL 产出写入
└──────────────────────────┘
```

### 4.2 启动顺序（DS 文档 §4.3）

```
nacos-mysql → nacos → postgres → xxl-job-admin
    ↓
dolphinscheduler-api → dolphinscheduler-master → dolphinscheduler-worker → dolphinscheduler-alert
    ↓
system → engineering → governance → worker → job → gateway → frontend
```

DS 4 容器依赖 nacos-mysql 准备就绪（建库脚本 `04-init-ds-db.sql`），DS api 启动后再起 master/worker/alert。

### 4.3 DS 调 engineering 网络拓扑

DS Worker 在执行 HTTP 任务时，回调 URL 用 **Docker 服务名**：

```
http://data-nest-engineering:8082/engineering/dev/internal/sql/callback
http://data-nest-engineering:8082/engineering/dev/internal/sync/callback
http://data-nest-engineering:8082/engineering/dev/internal/unknown/callback
http://data-nest-engineering:8082/engineering/dev/internal/python/callback
```

> **与代码对齐**：SYNC 节点不再使用 `/internal/sync/{historyId}/status` 轮询接口；同步任务状态由 `DagExecutionSyncService`
> 反查 `sync_job_history` 表获得。

端口 8082 是 engineering 容器内端口， **不暴露宿主机**。

---

## 5. 文件级交付清单

### 5.1 后端 — task-core 新增（不分 dag/ 子包，root 包结构）

| 文件                                       | 内容                                                                            | 工作量估  |
|--------------------------------------------|---------------------------------------------------------------------------------|-----------|
| `entity/DagProject.java`                   | `@TableName("dag_project")` + 7 字段                                            | 0.2d      |
| `entity/Dag.java`                          | `@TableName("dag")` + 12 字段（含 dsProcessDefinitionCode/Id/ScheduleId）       | 0.3d      |
| `entity/DagNode.java`                      | `@TableName("dag_node")` + 9 字段，**config 是 String**（存 JSON）              | 0.2d      |
| `entity/DagEdge.java`                      | `@TableName("dag_edge")`                                                        | 0.2d      |
| `entity/DagExecution.java`                 | `@TableName("dag_execution")` + 11 字段（含 dsProcessInstanceId）               | 0.3d      |
| `entity/NodeExecution.java`                | `@TableName("node_execution")` + 11 字段（含 dsTaskInstanceId）                 | 0.3d      |
| `mapper/DagProjectMapper.java`             | `BaseMapper<DagProject>`                                                        | 0.1d      |
| `mapper/DagMapper.java`                    | `BaseMapper<Dag>` + selectByProjectId、hasRunningExecution、selectGlobalPage    | 0.2d      |
| `mapper/DagNodeMapper.java`                | `BaseMapper<DagNode>` + selectByDagId、deleteByDagId、**findReferencingDags**   | 0.2d      |
| `mapper/DagEdgeMapper.java`                | `BaseMapper<DagEdge>` + selectByDagId、deleteByDagId                            | 0.1d      |
| `mapper/DagExecutionMapper.java`           | `BaseMapper<DagExecution>` + selectByStatus、selectRunning                      | 0.1d      |
| `mapper/NodeExecutionMapper.java`          | `BaseMapper<NodeExecution>` + selectByExecutionAndDsTaskId、selectByExecutionId | 0.2d      |
| `service/DagTopologyService.java`          | 拓扑校验（DFS 三色标记）、孤立节点检测                                          | 0.5d      |
| `service/DorisSqlExecutor.java`            | 多语句拆分、Doris JDBC 执行、结果预览、CTAS 检测                                | 1d        |
| `service/MetadataRegistrationService.java` | **增强**：新增 `registerFromSql(String sql, Long operatorId)`                   | 0.3d      |
| `dto/SqlExecuteRequest.java`               | SQL 执行入参                                                                    | 0.1d      |
| `dto/SqlExecuteResult.java`                | SQL 执行结果                                                                    | 0.2d      |
| `dto/SqlStatementResult.java`              | 单条 SQL 执行结果                                                               | 0.1d      |
| `dto/DagNodeDTO.java`                      | 节点 DTO                                                                        | 0.1d      |
| `dto/DagEdgeDTO.java`                      | 边 DTO                                                                          | 0.1d      |
| `dto/DagCanvasDTO.java`                    | 画布完整数据                                                                    | 0.1d      |
| **小计**                                   |                                                                                 | **~4.5d** |

### 5.2 后端 — engineering 新增（dev/ 包）

| 文件                                                             | 内容                                                                                                                                                                                                                        | 工作量估   |
|------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------|
| `dev/controller/ProjectController.java`                          | 仿 SyncJobController，CRUD + 分页 + 搜索；映射 `/dev/dag-projects`                                                                                                                                                          | 0.5d       |
| `dev/controller/DagController.java`                              | CRUD + 画布数据 + `/trigger` + `/executions/{id}/stop` + `/executions/{id}/rerun-failed`；映射 `/dev/dags`                                                                                                                  | 1d         |
| `dev/controller/SqlEditorController.java`                        | 测试执行 `/dev/sql-preview` + 自动补全（SQL 格式化按钮当前未实现）                                                                                                                                                          | 0.5d       |
| `dev/controller/DagExecutionController.java`                     | 全局执行历史 `GET /dag-executions` + 单实例详情 + 节点执行                                                                                                                                                                  | 0.5d       |
| `dev/controller/internal/SqlExecutionCallbackController.java`    | 接收 DS HTTP 回调 `/dev/internal/sql/callback`                                                                                                                                                                              | 0.5d       |
| `dev/controller/internal/PythonExecutionCallbackController.java` | 接收 DS HTTP 回调 `/dev/internal/python/callback`                                                                                                                                                                           | 0.5d       |
| `dev/controller/internal/SyncExecutionCallbackController.java`   | 接收 DS HTTP 回调 `/dev/internal/sync/callback`                                                                                                                                                                             | 0.5d       |
| `dev/service/ProjectService.java`                                | CRUD、name 唯一校验、级联删除 DAG                                                                                                                                                                                           | 0.5d       |
| `dev/service/DagService.java`                                    | CRUD、画布数据保存/加载、名称唯一校验                                                                                                                                                                                       | 1d         |
| `dev/service/DagDsSyncService.java`                              | **核心**：DAG 保存时同步到 DS ProcessDefinition；DAG 更新时 update；DAG 删除时 deleteProcess；task code 映射策略以代码为准                                                                                                  | 2d         |
| `dev/service/DagExecutionService.java`                           | 手动触发、停止、重跑失败节点、**@Scheduled(5s) 轮询 DS**、状态回写、全局历史                                                                                                                                                | 1.5d       |
| `dev/service/SqlEditorService.java`                              | 测试执行、自动补全候选                                                                                                                                                                                                      | 0.5d       |
| `dev/service/PythonExecutorService.java`                         | Python 节点脚本执行                                                                                                                                                                                                         | 0.8d       |
| `dev/service/DagExecutionSyncService.java`                       | SYNC 节点状态反查 `sync_job_history` 表                                                                                                                                                                                     | 0.5d       |
| `dev/service/SyncNodeCallbackService.java`                       | 接收 DS 回调 → 校验互斥 → 触发 XXL-JOB                                                                                                                                                                                      | 1d         |
| `dev/service/SyncNodeMutexService.java`                          | **Redis SETNX** 互斥                                                                                                                                                                                                        | 0.3d       |
| `dev/service/DagSyncRefService.java`                             | **config LIKE 查询引用 DAG**                                                                                                                                                                                                | 0.3d       |
| `dev/client/DolphinSchedulerClient.java`                         | DS HTTP API 封装：login（保留 token 复用）、createProcess、updateProcess、deleteProcess、releaseProcess、startProcessInstance、stopProcessInstance、queryProcessInstance、listTaskInstances、createSchedule、updateSchedule | 2d         |
| `dev/converter/DagDsConverter.java`                              | DataNest DAG 模型 ↔ DS ProcessDefinition JSON 转换                                                                                                                                                                          | 1.5d       |
| `dev/dto/...`                                                    | ProjectCreateRequest/UpdateRequest/QueryRequest/DTO、DagCreateRequest/UpdateRequest/QueryRequest/DTO、SqlExecuteRequest/Result DTO、DagExecutionQueryParams、DagExecutionDTO、NodeExecutionDTO                              | 1d         |
| **小计**                                                         |                                                                                                                                                                                                                             | **~14.4d** |

### 5.3 后端 — Flyway 迁移（仓库实际脚本 V3.2.0 ~ V3.4.1）

| 文件                                                      | 内容                                                                                                                               | 工作量估  |
|-----------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------|-----------|
| `V3.2.0__dag_tables.sql`                                  | 6 张新表：dag_project、dag、dag_node、dag_edge、dag_execution、node_execution                                                      | 0.5d      |
| `V3.2.1__sync_job_multitable_and_ratelimit.sql`           | sync_job 多表源 + 速率限流字段（source_tables_detail、read_rate_limit_mbps、write_rate_limit_rows_per_second、rate_limit_enabled） | 0.2d      |
| `V3.2.2__sprint3_p0_p2_fixes.sql`                         | 加 ds_project_code、node_execution.sync_job_id、唯一索引、status 乐观锁                                                            | 0.2d      |
| `V3.2.3__sync_job_source_tables_detail_text.sql`          | source_tables_detail 类型 jsonb → text（兼容 MyBatis-Plus 写入）                                                                   | 0.1d      |
| `V3.2.4__dag_node_ds_task_code.sql`                       | dag_node 加 ds_task_code（持久化 DS task code）                                                                                    | 0.1d      |
| `V3.2.5__drop_dead_columns_and_invalid_index.sql`         | 清理 metadata_table 死列与无效索引                                                                                                 | 0.1d      |
| `V3.2.6__node_execution_sync_job_history_id.sql`          | node_execution 加 sync_job_history_id（按节点查同步日志）                                                                          | 0.1d      |
| `V3.2.7__dag_execution_edge_snapshot.sql`                 | dag_execution 加 edge_snapshot（历史视图边快照）                                                                                   | 0.1d      |
| `V3.2.8__dag_execution_error_message.sql`                 | dag_execution 加 error_message                                                                                                     | 0.1d      |
| `V3.3.0__extend_dag_node_python.sql`                      | DAG 节点类型扩展为 SQL / SYNC / PYTHON                                                                                             | 0.1d      |
| `V3.3.1__dag_parameter.sql`                               | DAG 自定义参数表 dag_parameter                                                                                                     | 0.2d      |
| `V3.3.2__dag_version.sql`                                 | DAG 版本快照表 dag_version                                                                                                         | 0.2d      |
| `V3.3.3__dag_alert_config.sql`                            | 全局 DAG 告警配置表 dag_alert_config                                                                                               | 0.2d      |
| `V3.3.4__dag_alert_history.sql`                           | DAG 告警发送记录表 dag_alert_history                                                                                               | 0.2d      |
| `V3.3.5__node_execution_log.sql`                          | DAG 节点执行日志行表 node_execution_log                                                                                            | 0.2d      |
| `V3.3.6__lineage_record.sql`                              | 表级血缘记录表 lineage_record                                                                                                      | 0.2d      |
| `V3.3.7__metadata_table_source.sql`                       | metadata_table 加 task_source_type / task_source_id                                                                                | 0.1d      |
| `V3.3.8__dag_execution_params.sql`                        | dag_execution 加 resolved_params（执行级参数）                                                                                     | 0.1d      |
| `V3.3.9__sync_job_history_dag_execution_id.sql`           | sync_job_history 加 dag_execution_id（DAG 触发溯源）                                                                               | 0.1d      |
| `V3.3.10__dag_alert_config_dag_id.sql`                    | dag_alert_config 加 dag_id（支持按 DAG 覆盖）                                                                                      | 0.1d      |
| `V3.4.0__alter_dag_execution_resolved_params_to_text.sql` | dag_execution.resolved_params jsonb → text                                                                                         | 0.1d      |
| `V3.4.1__add_sys_user_audit_columns.sql`                  | sys_user 加 created_by / updated_by                                                                                                | 0.1d      |
| **小计**                                                  |                                                                                                                                    | **~3.2d** |

### 5.4 后端 — 共享 / 错误码

| 文件                                        | 内容                                                                                                                                                                                                                                                                                                                           | 工作量估  |
|---------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------|
| `ErrorCode.java` 新增 7xxx 段               | DAG_NOT_FOUND(7001), DAG_NAME_EXISTS(7002), DAG_CYCLE_DETECTED(7003), DAG_ISOLATED_NODE(7004), DAG_ALREADY_RUNNING(7005), DAG_DISABLED(7006), NO_RUNNING_EXECUTION(7007), DAG_NODE_EXECUTE_FAILED(7008), DAG_REFERENCED(7009), PROJECT_NAME_EXISTS(7010), SQL_EXECUTE_FAILED(7011), SQL_PARSE_FAILED(7012), DS_API_ERROR(7013) | 0.2d      |
| `shared-dolphinscheduler.yaml`              | api-url、token、tenant-code、callback-timeout-seconds                                                                                                                                                                                                                                                                          | 0.2d      |
| `application.yml` (engineering) 新增 import | `optional:nacos:shared-dolphinscheduler.yaml?group=shared-configs&refreshEnabled=true`                                                                                                                                                                                                                                         | 0.1d      |
| **小计**                                    |                                                                                                                                                                                                                                                                                                                                | **~0.5d** |

### 5.5 前端 — 页面 + 通用组件（路由 /engineering/dags/...、/engineering/dag-executions）

| 文件                                                        | 内容                                                                                                                            | 工作量估   |
|-------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------|------------|
| `package.json` 新增依赖                                     | `reactflow@^11.x`、`@monaco-editor/react@^4.x` 等                                                                               | 0.1d       |
| `pages/engineering/dags/index.tsx`                          | 项目列表（搜索、新建/编辑/删除、二次确认）                                                                                      | 0.8d       |
| `pages/engineering/dags/project.tsx`                        | 某项目下 DAG 列表（搜索、状态筛选、调度启停、新建/执行/历史/删除；前端假分页）                                                  | 0.8d       |
| `pages/engineering/dags/Editor.tsx`                         | DAG 画布 + 运行视图复用（ReactFlow、顶部工具栏含参数/版本/告警/自动布局、左侧节点面板、右侧属性面板、SQL/Python/SYNC 节点日志） | 3.5d       |
| `pages/engineering/dags/components/SqlEditorModal.tsx`      | SQL 任务编辑弹窗（Monaco；运行测试调用 `/dev/sql-preview`）                                                                     | 1d         |
| `pages/engineering/dags/components/PythonEditorModal.tsx`   | Python 任务编辑弹窗                                                                                                             | 0.5d       |
| `pages/engineering/dags/components/DagParameterDrawer.tsx`  | DAG 参数抽屉                                                                                                                    | 0.3d       |
| `pages/engineering/dags/components/DagVersionModal.tsx`     | DAG 版本管理弹窗                                                                                                                | 0.3d       |
| `pages/engineering/dags/components/DagAlertConfigModal.tsx` | DAG 告警配置弹窗                                                                                                                | 0.3d       |
| `pages/engineering/dags/components/NodeRuntimeLogPanel.tsx` | 节点实时日志面板                                                                                                                | 0.5d       |
| `pages/engineering/dags/components/TriggerParamsModal.tsx`  | 触发时参数覆盖弹窗                                                                                                              | 0.2d       |
| `pages/engineering/dag-executions/index.tsx`                | 全局执行历史（多条件筛选；点击详情跳转运行视图）                                                                                | 1.2d       |
| `pages/engineering/dags/api.ts`                             | DAG/dev/dag-executions 接口封装                                                                                                 | 0.5d       |
| `pages/engineering/dags/types.ts`                           | DAG 领域类型定义                                                                                                                | 0.2d       |
| `router/index.tsx` 新增路由                                 | `/engineering/dags`、`:projectId`、`new`、`/:id/edit`、`/:id/executions/:executionId`、`/dag-executions`                        | 0.3d       |
| `components/Sidebar.tsx` 新增菜单                           | 「数据开发」分组下新增「项目管理」+「执行历史」分组下新增「DAG 执行历史」                                                       | 0.3d       |
| **小计**                                                    |                                                                                                                                 | **~10.8d** |

### 5.6 部署 / 基础设施

| 文件                                                | 内容                                                                           | 工作量估  |
|-----------------------------------------------------|--------------------------------------------------------------------------------|-----------|
| `docker-compose.yml` 新增 4 个 DS 服务              | dolphinscheduler-api / master / worker / alert                                 | 0.3d      |
| `docker-compose.yml` nacos-mysql volumes 加挂       | `scripts/init-dolphinscheduler-db.sql` 初始化 dolphinscheduler 库              | 0.1d      |
| `scripts/init-dolphinscheduler-db.sql`              | `CREATE DATABASE IF NOT EXISTS dolphinscheduler DEFAULT CHARACTER SET utf8mb4` | 0.1d      |
| `data-nest-engineering/Dockerfile`                  | 不变                                                                           | 0d        |
| `data-nest-engineering/application.yml` 新增 import | shared-dolphinscheduler.yaml                                                   | 0.1d      |
| `gateway/application.yml`                           | **不变**（`/api/engineering/**` 已存在）                                       | 0d        |
| **小计**                                            |                                                                                | **~0.6d** |

### 5.7 全部工作量汇总

| 模块                   | 工作量                    |
|------------------------|---------------------------|
| 后端 task-core         | 4.5d                      |
| 后端 engineering       | 15.9d                     |
| Flyway + 错误码 + 配置 | 2.2d                      |
| 前端                   | 11.9d                     |
| 部署 / Docker          | 0.6d                      |
| 联调 / 测试            | 3d                        |
| **合计**               | **~38.1 人天（约 7 周）** |

---

## 6. 关键技术设计

### 6.1 DataNest DAG → DS ProcessDefinition 映射

DS ProcessDefinition JSON 关键字段（参考 DS 文档 §8.3）：

```json
{
  "projectCode": 1000,
  "name": "order-data-pipeline",
  "description": "DataNest DAG: order-data-pipeline",
  "taskDefinitionJson": "[{\"code\":1,\"name\":\"SQL_订单数据清洗\",\"taskType\":\"HTTP\",\"taskParams\":{...}}]",
  "taskRelationJson": "[{\"preTaskCode\":1,\"postTaskCode\":2,\"preTaskVersion\":1,\"postTaskVersion\":1,\"conditionType\":\"NONE\"}]",
  "locations": "[{\"taskCode\":1,\"x\":80,\"y\":80},{\"taskCode\":2,\"x\":320,\"y\":80}]"
}
```

**DagDsConverter 转换规则**：

- DataNest DagNode.nodeId ↔ DS task.code（DS 自动生成，DataNest 用 `node_name` 关联）
- DataNest DagNode.positionX/Y ↔ DS locations[].x/y
- DataNest DagNode.config（String JSON）↔ DS taskParams.httpParams.value
- DataNest DagEdge.source/targetNodeId ↔ taskRelationJson[].pre/postTaskCode（按 node_name 查 code）

### 6.2 节点 config 字段约定（String JSON）

```json
// SQL 节点
{"type":"sql","sqlContent":"INSERT INTO doris.dwd.orders SELECT ..."}

// 同步节点
{"type":"sync","syncJobId":50,"syncJobName":"日志数据同步"}
```

业务层用 ObjectMapper 反序列化为 Map<String, Object>，按 `type` 字段判断。

### 6.3 DS 状态回查策略

- **后端**：`DagExecutionService.syncExecutionStatus()` 加 `@Scheduled(fixedRate = 5000)`，每 5 秒查一次所有 `RUNNING` 的
  dag_execution
- **前端**：`useExecutionStatus` hook 每 3 秒轮询后端 API
- **节点状态**：在 `syncExecutionStatus` 里同时调用 `dsClient.listTaskInstances(...)` 更新 node_execution
- **执行结束自动停轮询**：前端 `useExecutionStatus` 在状态为 SUCCESS/FAILED/TERMINATED 时 clearInterval

### 6.4 同步任务互斥（Redis SETNX）

```java
String key = "datanest:sync:job:mutex:" + syncJobId;
Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, "1", 30, TimeUnit.MINUTES);
if (!acquired) {
    // 已有实例在跑，返回 WAITING
    return SyncTriggerResult.waiting("同步任务正在执行中");
}
try {
    // 触发 XXL-JOB
    syncJobExecutorService.start(syncJobId, "DAG", dagExecutionId);
} finally {
    redisTemplate.delete(key);
}
```

**注意**：这里的 `redisTemplate` 复用 engineering 已有 `spring-boot-starter-data-redis` 依赖；key 前缀 `datanest:`
避免跟其他业务冲突。

### 6.5 同步任务引用校验（dag_node.config LIKE）

```java
public List<Long> findReferencingDags(Long syncJobId) {
    // 转义 JSON 字符串中的特殊字符
    String pattern = String.format("%%\"syncJobId\":%d%%", syncJobId);
    return dagNodeMapper.findReferencingDags(pattern);
}
```

SQL：

```sql
SELECT DISTINCT dag_id FROM dag_node WHERE config LIKE ?;
```

**性能**：DAG 节点数 < 10000 时全表扫 < 50ms，可接受；后续可加 GIN 索引（PostgreSQL JSONB），但 config 是 TEXT 不是 JSONB。

### 6.6 DS 回调内部接口（无鉴权 + hook）

按 Q8 决策：纯内网隔离。内部回调路径：

- `/engineering/dev/internal/sql/callback`
- `/engineering/dev/internal/sync/callback`
- `/engineering/dev/internal/unknown/callback`
- `/engineering/dev/internal/python/callback`

代码留 hook：

```java
// 路径白名单（仅 DS 服务名访问）
@Configuration
public class InternalEndpointWhitelist implements WebMvcConfigurer {
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new InternalAuthInterceptor())
            .addPathPatterns("/engineering/dev/internal/**");
    }
}

// 拦截器默认放行，可通过配置开启
@ConditionalOnProperty(name = "datanest.dev.internal.auth-enabled", havingValue = "true")
public class InternalAuthInterceptor implements HandlerInterceptor { ... }
```

> **与代码对齐**：SYNC 节点不再使用 `/internal/sync/{historyId}/status` 轮询接口；同步任务状态由 `DagExecutionSyncService`
> 反查 `sync_job_history` 表获得。

`application.yml` 默认 `datanest.dev.internal.auth-enabled: false`；生产环境改 `true` + 实现 HMAC 逻辑。

### 6.7 状态机映射

DataNest 状态 ↔ DS 状态：

| DataNest   | DS 状态码   | 说明                            |
|------------|-------------|---------------------------------|
| WAITING    | 0           | 等待执行                        |
| RUNNING    | 1           | 等待运行                        |
| SUCCESS    | 7           | 成功                            |
| FAILED     | 6           | 失败                            |
| TERMINATED | 5           | 终止                            |
| SKIPPED    | -1 / 自定义 | 跳过（DS 没这状态，前端展示用） |

DS 状态码定义在 `t_ds_process_instance.state` 和 `t_ds_task_instance.state`。

---

## 7. 落地步骤（10 个 Phase）

### Phase 0：基础准备（0.5d）

- [ ] ErrorCode 新增 7xxx 段（13 个错误码）
- [ ] shared-configs 新增 `shared-dolphinscheduler.yaml`
- [ ] scripts 新增 `init-dolphinscheduler-db.sql`
- [ ] docker-compose.yml 加 4 个 DS 容器 + nacos-mysql volumes 加挂
- [ ] engineering application.yml 加 import

### Phase 1：DS 部署 + 验证（1d）

- [x] `docker compose up -d` 启动所有容器（含 4 个 DS 服务 + ZK + 中间件/应用分层重构）
- [x] 健康检查：`curl http://localhost:12345/dolphinscheduler/ui` 可访问
- [x] 默认 admin/dolphinscheduler123 登录验证
- [x] 登录后手动创建 token，存到 shared-dolphinscheduler.yaml 的 `token` 字段
- [x] 在 DS UI/API 建租户 `datanest` + 项目 `data-dev`
- [x] 端到端调度验证：DS Workflow `test-echo-hello` 跑通（state=SUCCESS, 2s, worker 172.21.0.14:1234）
- [x] docker-compose 命名重构：分 `middleware-*`（基础设施）/ `app-*`（业务应用）两层

> 📌 **Phase 1 踩坑记录**（2026-07-30 实际操作沉淀，详细分析见同目录 `DataNest-Sprint3-实施计划-Phase1踩坑.md`）：

| # | 坑                                                                                                                          | 解决方案                                                                    |
|---|-----------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------|
| 1 | DS 官方默认 PostgreSQL，MySQL 驱动因 GPLv2 vs Apache 2.0 协议冲突**不内置镜像**                                             | 手动挂载 `mysql-connector-j-8.0.33.jar` 到容器                              |
| 2 | DS 3.4.2 镜像**不是共享顶层 `libs/`**，是每个 server 独立 `libs/` 子目录                                                    | jar 必须挂到 `/opt/dolphinscheduler/<server>-server/libs/`                  |
| 3 | `DATABASE=mysql` 老版本变量在 3.4.2 失效                                                                                    | 改用 `SPRING_PROFILES_ACTIVE=mysql` + `SPRING_DATASOURCE_URL`               |
| 4 | DS tools 容器内 `bash`/`sh` 都「cannot execute binary file」（alpine 镜像问题）                                             | `command: [ "tools/bin/upgrade-schema.sh" ]` 直接传脚本，不用 shell wrapper |
| 5 | `docker-compose.yml` 第 322-325 行 YAML 缩进错位（`volumes:` 写在 `ports:` 里面 8 空格）                                    | 改成 4 空格，与 `ports:` 同级                                               |
| 6 | 端口冲突：旧容器（`datanest-app-ds-api`）占 12345，新容器起不来                                                             | `docker compose down --remove-orphans` 先清旧                               |
| 7 | nacos-mysql 已运行 3h 不会执行 `init-dolphinscheduler-db.sql` 一次性脚本                                                    | 手动 `docker exec` 跑 `CREATE DATABASE dolphinscheduler` + GRANT            |
| 8 | DS 容器间类路径差异：`api-server/libs/*` / `master-server/libs/*` / `worker-server/libs/*` / `alert-server/libs/*` 各自独立 | 4 个服务每个挂一份 jar；tools 容器挂到 `tools/libs/`                        |

> ⚠️ **`Dockerfile 自定义镜像法`（推荐但本次未做）**：可用
>
`FROM apache/dolphinscheduler-{api,master,worker,alert,tools}:3.4.2 + COPY mysql-connector-j-8.0.33.jar /opt/dolphinscheduler/<server>-server/libs/`
> 构建 5 个自定义镜像，避免每次 docker-compose 改挂载路径。本项目为了不引入 image build 复杂度，暂用 volume
> 挂载；如需长期运行建议改成自定义镜像。

### Phase 2：数据层（1d）

- [x] Flyway V3.2.0 建 6 张新表（dag_project, dag, dag_node, dag_edge, dag_execution, node_execution）
- [x] Flyway V3.2.1 扩展 sync_job：source_tables_detail + 限流 3 字段
- [ ] task-core 实体 + Mapper 全建好（Phase 3 一起做）
- [x] mvn install + docker compose build 触发 Flyway，验证 6 张表创建成功（2026-07-30 16:21:47）

> **Phase 2 完成情况**：
> - V3.2.0: 13KB，6 张表 + 26 个索引（5+5+5+5+2+4）+ 全字段中文 COMMENT
> - V3.2.1: 2.5KB，sync_job 加 4 字段（source_tables_detail + 限流 3 件套）
> - 配置索引：`idx_dag_node_config_pattern` (text_pattern_ops) 支持 ADR-S3-009 `LIKE '%syncJobId%'` 高效查询
> - 部分索引：`idx_sync_job_rate_limit_enabled WHERE rate_limit_enabled=1` 节省空间

### Phase 3：DS 集成客户端（2d）

- [ ] `DolphinSchedulerClient`
  ：login、createProcess、updateProcess、deleteProcess、releaseProcess、startProcessInstance、stopProcessInstance、queryProcessInstance、listTaskInstances、createSchedule、updateSchedule
- [ ] token 缓存 + 过期重登
- [ ] 单元测试 mock DS 响应

### Phase 4：MetadataRegistrationService 增强（0.3d）

- [ ] 新增 `registerFromSql(String sql, Long operatorId)` 方法
- [ ] 解析 CREATE TABLE / CTAS / DROP / ALTER
- [ ] 调现有 `findOrCreateTable` + `extractColumns` + `refreshColumns`

### Phase 5：DagTopologyService + DorisSqlExecutor（2d）

- [ ] `DagTopologyService`：DFS 三色标记 + 孤立节点检测
- [ ] `DorisSqlExecutor`：多语句拆分 + JDBC 执行 + 结果预览 + CTAS 检测
- [ ] 单元测试

### Phase 6：Project + DAG API（3d）

- [ ] ProjectController / ProjectService
- [ ] DagController / DagService
- [ ] DagDsSyncService（核心，2d）
- [ ] DagDsConverter
- [ ] 单元测试覆盖映射算法

### Phase 7：DAG 执行 + SQL 任务 + 同步回调（3d）

- [ ] DagExecutionService（手动触发、终止、5s 轮询）
- [ ] SqlEditorService + SqlEditorController
- [ ] SyncNodeCallbackService + SyncNodeMutexService（Redis SETNX）
- [ ] DagSyncRefService（config LIKE）
- [ ] internal/* Controller（SqlExecution、SyncTrigger、SyncPoll）

### Phase 8：多表 + 限流（1d）

- [ ] SyncJobCreateRequest/UpdateRequest 加多表/限流字段
- [ ] AddaxJobBuilder 扩展多表模式
- [ ] Addax setting.speed.byte / record 配置
- [ ] 已有单表任务兼容（ **杰仔 Q4 决策：不用做**）
- [ ] 前端同步任务表单 UI 改造

### Phase 9：前端（11.9d）

- [ ] `npm install reactflow @monaco-editor/react`
- [ ] 路由 + 菜单（数据工程分组下「项目管理」+ 执行历史分组下「DAG 执行历史」）
- [ ] projects / dags 列表页
- [ ] canvas 画布（3d）+ SqlNodeModal + PythonNodeModal + SyncNodeModal；工具栏含参数/版本/告警/自动布局
- [ ] dag-executions 全局历史页（详情跳转 running 视图）
- [ ] running 运行视图（节点拓扑 + SQL/Python/SYNC 实时日志轮询）
- [ ] 节点组件、工具栏、属性面板

### Phase 10：端到端联调（3d）

- [ ] 创建项目 → 创建 DAG → 拖 SQL 节点 → 写 CTAS → 执行 → 元数据管理看到新表
- [ ] 拖同步任务节点 → DS HTTP 回调 → engineering 触发 XXL-JOB → 完成 → 节点状态变绿
- [ ] Cron 触发 + 启停 + 终止执行
- [ ] 循环依赖校验、孤立节点校验
- [ ] Redis 互斥验证（同一 syncJob 同时 2 个 DAG 触发）

### 总工作量： **~38.1 人天（约 7 周）**

---

## 8. 风险与对策

| #   | 风险                                                                                | 影响                       | 对策                                                                                                 |
|-----|-------------------------------------------------------------------------------------|----------------------------|------------------------------------------------------------------------------------------------------|
| R1  | DS 容器内存占用大（默认 4G+）                                                       | dev 环境跑不动             | 调小 JVM heap 到 1G；standalone 注册模式                                                             |
| R2  | DS ProcessDefinition JSON 复杂，映射易错                                            | DAG 保存/加载失真          | 强契约单测覆盖每种节点类型；映射算法分步单测                                                         |
| R3  | DS 状态轮询 5s 延迟                                                                 | 前端状态更新不及时         | 前端 3s 轮询后端 API 兜底；Sprint 5 升级 WebSocket                                                   |
| R4  | SQL 解析不支持 CTE / 复杂 DDL                                                       | 元数据注册漏表             | Sprint 3 先支持基本 CREATE TABLE / CTAS；复杂语法 Sprint 4 接 JSqlParser                             |
| R5  | DS HTTP 回调内部接口被外部攻击                                                      | SQL 注入、敏感数据泄露     | 路径白名单 + `@ConditionalOnProperty` hook；生产前补 HMAC                                            |
| R6  | Redis SETNX 互斥 key 过期（30 分钟）但任务还在跑                                    | 互斥失效                   | XXL-JOB timeout 设为 1 小时兜底；key 过期前完成任务                                                  |
| R7  | `config LIKE '%syncJobId":50%'` 对 JSON 格式敏感                                    | syncJobId 50 跟 500 误匹配 | 改用 `'%"syncJobId":50,%'` 或 `'%"syncJobId":50}'` 加边界                                            |
| R8  | DS 调 engineering 用服务名 `data-nest-engineering:8082`，但本地 dev 环境没用 Docker | DAG 节点执行失败           | shared-dolphinscheduler.yaml 加 `api-url` 和 `callback-base-url` 两个配置，dev 模式用 localhost:8082 |
| R9  | Docker Compose 启动顺序复杂                                                         | 启动慢                     | depends_on: condition: service_healthy 串行化；开发期 `docker compose up -d` 一把起                  |
| R10 | Spring Boot 4.0.7 跟 DS 3.4.2 的 Java Client 兼容                                   | 引入依赖冲突               | Sprint 3 用 HTTP 自己封装，不用 Java Client                                                          |

---

## 9. 关键决策记录（ADR）

### ADR-S3-001：DAG 调度引擎 —— DolphinScheduler 3.4.2

- **决策**：DS 集成路线，XXL-JOB 仅承担 Sprint 1-2 的同步/采集任务
- **依据**：DS 文档 §1.2 范围 + 杰仔交互式确认

### ADR-S3-002：项目管理模块放置位置

- **决策**：DAG 领域模型下沉到 `data-nest-task-core`（ **不分子包**，root 包结构），API 层放在 `data-nest-engineering` 的
  `dev` 包下（Controller 映射 `/dev/...` 与 `/dag-executions`）
- **依据**：杰仔 Q1 决策 + DS 文档 ADR-S3-002

### ADR-S3-003：DS 任务执行方式

- **决策**：DS 中所有 DataNest 节点均映射为 HTTP 任务，engineering 内部接口处理
- **依据**：DS 文档 ADR-S3-003

### ADR-S3-004：DS 客户端用纯 HTTP 自己封装

- **决策**：用 Spring RestTemplate，不引入 DS Java Client
- **依据**：杰仔 Q6 决策 + 避免依赖冲突

### ADR-S3-005：DagNode.config 字段 String 存 JSON

- **决策**：DagNode.config 是 String 字段，业务层用 ObjectMapper 解析
- **依据**：杰仔 Q2 决策（与 DS 文档 §6.1 的 JacksonTypeHandler 不同，按杰仔决策走）

### ADR-S3-006：同步任务互斥用 Redis SETNX

- **决策**：新增 SyncNodeMutexService，用 redisTemplate.setIfAbsent 实现
- **依据**：杰仔 Q3 决策

### ADR-S3-007：DS 元数据库复用 nacos-mysql

- **决策**：DS 元数据库（库名 `dolphinscheduler`）跟 nacos/XXL-JOB 共用 nacos-mysql
- **依据**：杰仔 Q7 决策 + DS 文档 §4.1

### ADR-S3-008：DS 回调内部接口鉴权 — 开发阶段不鉴权

- **决策**：纯内网隔离 + `@ConditionalOnProperty` hook；生产环境按需开启 HMAC
- **依据**：杰仔 Q8 决策

### ADR-S3-009：同步任务引用关系用 dag_node.config LIKE 查询

- **决策**：不建中间表，通过 LIKE 查询引用关系
- **依据**：杰仔 Q10 决策

### ADR-S3-010：前端路由 /engineering/dags/... 与 /engineering/dag-executions

- **决策**：路由前缀用 `/engineering/dags`（项目列表）、`/engineering/dags/:projectId`（DAG 列表）、`/engineering/dags/new`
  （新建）、`/engineering/dags/:id/edit`（编辑画布）、`/engineering/dags/:id/executions/:executionId`（运行视图）与
  `/engineering/dag-executions`（全局执行历史），与代码实际保持一致
- **依据**：代码实际实现（`router/index.tsx`）+ DS 文档 §12.2

### ADR-S3-011：菜单分组

- **决策**：「项目管理」放在独立的「数据开发」分组下；DAG 执行历史放在「执行历史」分组下，跟"同步执行历史""采集执行历史"并列
- **依据**：代码实际实现（`components/Sidebar.tsx`）+ DS 文档 §12.3

---

## 10. 下一步

**所有 10 个决策已落地**，按计划进入 Phase 0 实施。

1. **Phase 0（0.5d）**：基础准备
2. **Phase 1（1d）**：DS 部署 + 验证
3. **Phase 2（1d）**：数据层
4. **Phase 3（2d）**：DS 集成客户端
5. ... 按计划走

> ⚠️ **本计划已按当前代码实际完成同步更新**：菜单「项目管理」、路由 `/engineering/dags/*` 与 `/engineering/dag-executions`
> 、API 前缀 `/dev/...` 与 `/dag-executions`、回调路径 `/dev/internal/*/callback`、全局执行历史 `GET /dag-executions`、SQL 预览
> `/dev/sql-preview`、字段长度 100、`dag_node.config` String JSON、Flyway 扩展脚本等。
