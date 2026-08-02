# DataNest Sprint 3 技术文档

> **Sprint**：Sprint 3 — DAG 编排与 SQL 任务编辑器
> **文档状态**：Working Draft (v1.0) | **作者**：软件架构师 | **日期**：2026-07-29
> **关联文档**：`DataNest-技术架构文档-v2.3.md`、`DataNest-Sprint3-DAG编排与SQL任务编辑器-PRD.md`

---

## 目录

1. [Sprint 概述](#1-sprint-概述)
2. [交付物清单](#2-交付物清单)
3. [项目结构变更](#3-项目结构变更)
4. [Docker Compose 变更](#4-docker-compose-变更)
5. [架构关系图](#5-架构关系图)
6. [task-core：DAG 领域模型与执行映射](#6-task-coredag-领域模型与执行映射)
7. [engineering-service：项目管理模块](#7-engineering-service项目管理模块)
8. [DolphinScheduler 集成](#8-dolphinscheduler-集成)
9. [数据库设计](#9-数据库设计)
10. [API 接口设计](#10-api-接口设计)
11. [共享配置变更](#11-共享配置变更)
12. [前端设计](#12-前端设计)
13. [Sprint 3 ADR](#13-sprint-3-adr)
14. [验收标准](#14-验收标准)
15. [风险与对策](#15-风险与对策)

---

## 1. Sprint 概述

### 1.1 Sprint 目标

Sprint 2 完成了批量数据同步，但同步任务之间、同步与 SQL 加工之间仍靠人工串联。Sprint 3 引入 **DAG 可视化编排**
，让数据工程师在画布上拖拽节点、连线定义依赖，把同步任务和 SQL 任务组成可自动执行的数据流水线。

核心能力：

1. **项目 + DAG 管理**：项目作为 DAG 的命名空间分组。
2. **可视化画布**：ReactFlow 拖拽编排，支持 SQL 任务节点、同步任务节点、依赖连线。
3. **SQL 任务编辑器**：内置 Monaco Editor，Doris SQL 方言，支持语法高亮、格式化、测试执行。
4. **DAG 执行**：手动触发 + Cron 定时触发，按依赖顺序执行，上游失败下游跳过。
5. **元数据自动注册**：SQL 任务创建的新表自动写入元数据管理。
6. **同步任务增强**：多表批量同步、速率限流。

### 1.2 Sprint 范围

| # | 工作项                          | 所属模块                             | 说明                                                                   |
|---|---------------------------------|--------------------------------------|------------------------------------------------------------------------|
| 1 | **DolphinScheduler 3.4.2 集成** | Docker Compose + engineering-service | 引入 DS 作为 DAG 调度与执行引擎                                        |
| 2 | **项目与 DAG 管理**             | engineering-service + task-core      | Project / DAG / Node / Edge CRUD                                       |
| 3 | **DAG → DS 流程映射**           | engineering-service                  | DataNest DAG 保存时同步为 DS ProcessDefinition                         |
| 4 | **SQL 任务执行**                | task-core + engineering-service      | 测试执行、正式执行、元数据注册                                         |
| 5 | **同步任务节点**                | engineering-service                  | DAG 中引用已有 SyncJob，DS 通过 HTTP 回调触发                          |
| 6 | **执行历史与节点状态**          | engineering-service + DS API         | 查询 DS 流程实例状态；新增全局执行历史页面（按时间/状态/触发方式过滤） |
| 7 | **多表批量同步**                | task-core                            | SyncJob 源表从单表扩展为多表                                           |
| 8 | **同步速率限流**                | task-core + Addax                    | 读取 MB/s、写入 行/s 限流                                              |
| 9 | **前端项目管理模块**            | data-nest-frontend                   | ReactFlow 画布 + Monaco SQL 编辑器 + Python 节点                       |

> **XXL-JOB 保留说明**：Sprint 1-2 的同步任务、采集任务独立调度仍由 XXL-JOB 负责；Sprint 3 的 DAG 编排由 DolphinScheduler
> 负责，DAG 中的同步任务节点通过 HTTP 回调触发 XXL-JOB 任务。

### 1.3 不在本 Sprint

| 暂缓项               | 后续 Sprint | 理由                      | 实际状态                               |
|----------------------|:-----------:|---------------------------|----------------------------------------|
| Python 任务节点      |  Sprint 4   | 需要沙箱执行环境          | **已提前实现**：Sprint 3 已落地        |
| 任务参数化           |  Sprint 4   | 参数传递与替换机制        | **已提前实现**：Sprint 3 已落地        |
| 条件分支 / 子 DAG    |  Sprint 5   | 控制流节点                | 仍按 Sprint 5 规划                     |
| DAG 实时日志流       |  Sprint 5   | 需要 WebSocket / 日志采集 | **已提前实现**：运行视图已支持日志轮询 |
| DAG 失败告警         |  Sprint 5   | 告警通道配置              | **已提前实现**：Sprint 3 已落地        |
| SQL 血缘自动解析     |  Sprint 5   | 需要 SQL Parser + Neo4j   | **已提前实现**：Sprint 3 已落地        |
| DAG 版本管理         |  Sprint 5   | 版本与回滚                | **已提前实现**：Sprint 3 已落地        |
| 任务资源队列与优先级 |  Sprint 5   | 调度增强                  | 仍按 Sprint 5 规划                     |

> 说明：根据当前代码实现，Python 节点、参数化、版本、告警、SQL 血缘等能力已在 Sprint 3 提前落地，文档按代码实际更新。

### 1.4 技术栈

| 组件                    | 版本       | 用途                            |
|-------------------------|------------|---------------------------------|
| Apache DolphinScheduler | 3.4.2      | DAG 调度与执行引擎              |
| ReactFlow               | 11.x       | 前端 DAG 画布                   |
| Monaco Editor           | 0.52.x     | SQL 编辑器                      |
| sql-formatter           | 15.x       | SQL 格式化                      |
| Doris JDBC              | MySQL 协议 | SQL 任务执行                    |
| Addax                   | 6.0.11     | 批量同步引擎（Sprint 2 已集成） |
| XXL-JOB                 | 3.4.2      | 同步/采集任务独立调度（保留）   |

---

## 2. 交付物清单

| #  | 交付物                                                                                                                                | 类型 | 验收方式                       |
|----|---------------------------------------------------------------------------------------------------------------------------------------|------|--------------------------------|
| D1 | `data-nest-task-core` 新增 DAG 实体与 SQL 执行服务                                                                                    | 代码 | 编译通过                       |
| D2 | `data-nest-engineering` 新增 `dev` 包（Project / DAG / SQL API）                                                                      | 代码 | API 可用                       |
| D3 | DolphinScheduler 集成客户端（Java/HTTP）                                                                                              | 代码 | 可创建/更新/触发/查询 DS 流程  |
| D4 | Flyway 迁移脚本 `V3.2.0__dag_tables.sql` + `V3.2.1__sync_job_multitable.sql`                                                          | 代码 | 启动自动建表                   |
| D5 | `docker-compose.yml` 新增 DolphinScheduler 服务                                                                                       | 配置 | `docker compose up -d` DS 健康 |
| D6 | `shared-configs` 新增 `shared-dolphinscheduler.yaml`                                                                                  | 配置 | Nacos 可见                     |
| D7 | 前端新增 `dev` 模块（ReactFlow + Monaco）                                                                                             | 代码 | 页面可用                       |
| D8 | Gateway 复用 `/api/engineering/**` 路由；Controller 映射为 `/dev/dag-projects`、`/dev/dags`、`/dev/sql-preview`、`/dag-executions` 等 | 配置 | 路由正确                       |

---

## 3. 项目结构变更

### 3.1 模块职责划分

Sprint 3 不新增独立微服务，核心逻辑下沉到 `task-core`，API 暴露在 `engineering-service`，调度执行交给 DolphinScheduler。

```
data-nest/
├── data-nest-task-core/              # 🆕 DAG 领域模型 + SQL 执行
│   └── src/main/java/com/datanest/task/core/
│       ├── dag/
│       │   ├── entity/               # DagProject, Dag, DagNode, DagEdge, DagExecution, NodeExecution
│       │   ├── mapper/               # MyBatis-Plus Mapper
│       │   ├── service/
│       │   │   ├── DagEngine.java    # DAG 拓扑校验、节点运行器 SPI
│       │   │   └── DorisSqlExecutor.java
│       │   └── dto/
│       └── sync/                     # Sprint 2 已有，扩展多表/限流
│
├── data-nest-engineering/            # 🆕 项目管理 API + DS 集成
│   └── src/main/java/com/datanest/engineering/
│       ├── dev/                      # 🆕 项目管理模块（Controller 映射 /dev/...）
│       │   ├── controller/
│       │   │   ├── ProjectController.java
│       │   │   ├── DagController.java
│       │   │   └── SqlEditorController.java
│       │   ├── service/
│       │   │   ├── ProjectService.java
│       │   │   ├── DagService.java
│       │   │   ├── DagDsSyncService.java       # DAG ↔ DS ProcessDefinition 映射
│       │   │   ├── DagExecutionService.java    # 查询 DS 执行状态
│       │   │   └── SqlEditorService.java
│       │   ├── dto/
│       │   └── client/
│       │       └── DolphinSchedulerClient.java # DS HTTP API 封装
│       └── sync/                     # Sprint 2 已有
│
├── data-nest-worker/                 # 同步/采集任务执行器（不变）
│   └── WorkerJobHandler.java
│
└── data-nest-frontend/               # 🆕 项目管理页面（路由 /engineering/dags/*、/engineering/dag-executions）
    └── src/pages/engineering/dags/
        ├── projects/
        ├── dags/
        ├── canvas/
        └── dag-executions/
```

### 3.2 设计原则

1. **不新增微服务**：利用 `task-core` 的共享能力 + `engineering-service` 的 API 层，避免新增运维负担。
2. **DS 只负责调度编排**：SQL 执行、同步任务触发、元数据注册仍由 DataNest 服务完成，DS 通过 HTTP 任务回调。
3. **DataNest 保留画布模型**：前端 ReactFlow 模型保存到 DataNest 数据库，同时同步为 DS ProcessDefinition；执行时以 DS
   为准，状态回查 DS。

---

## 4. Docker Compose 变更

### 4.1 新增 DolphinScheduler 服务

DolphinScheduler 3.4.2 采用 Master + Worker + API + Alert 分离架构。MVP 阶段在 docker-compose 中部署最小可用组合，数据库复用现有
`nacos-mysql` 实例（新建 `dolphinscheduler` 库）。

```yaml
# docker-compose.yml 新增

# ============================================
# DolphinScheduler 元数据库
# 复用 nacos-mysql，初始化脚本创建 dolphinscheduler 库
# ============================================
# 已在 nacos-mysql volumes 中挂载：
# - ./scripts/init-dolphinscheduler-db.sql:/docker-entrypoint-initdb.d/04-init-ds-db.sql:ro

# ============================================
# DolphinScheduler API Server
# ============================================
dolphinscheduler-api:
  image: apache/dolphinscheduler-api:3.4.2
  container_name: datanest-dolphinscheduler-api
  environment:
    - TZ=Asia/Shanghai
    - DATABASE=mysql
    - SPRING_DATASOURCE_URL=jdbc:mysql://nacos-mysql:3306/dolphinscheduler?useUnicode=true&characterEncoding=UTF-8&autoReconnect=true&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
    - SPRING_DATASOURCE_USERNAME=nacos
    - SPRING_DATASOURCE_PASSWORD=nacos123
    - REGISTRY_TYPE=standalone
    - REGISTRY_ZOOKEEPER_CONNECT_STRING=localhost:2181
  ports:
    - "12345:12345"
  depends_on:
    nacos-mysql:
      condition: service_healthy
  networks:
    - datanest-net

# ============================================
# DolphinScheduler Master Server
# ============================================
dolphinscheduler-master:
  image: apache/dolphinscheduler-master:3.4.2
  container_name: datanest-dolphinscheduler-master
  environment:
    - TZ=Asia/Shanghai
    - DATABASE=mysql
    - SPRING_DATASOURCE_URL=jdbc:mysql://nacos-mysql:3306/dolphinscheduler?useUnicode=true&characterEncoding=UTF-8&autoReconnect=true&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
    - SPRING_DATASOURCE_USERNAME=nacos
    - SPRING_DATASOURCE_PASSWORD=nacos123
    - REGISTRY_TYPE=standalone
  depends_on:
    nacos-mysql:
      condition: service_healthy
    dolphinscheduler-api:
      condition: service_started
  networks:
    - datanest-net

# ============================================
# DolphinScheduler Worker Server
# ============================================
dolphinscheduler-worker:
  image: apache/dolphinscheduler-worker:3.4.2
  container_name: datanest-dolphinscheduler-worker
  environment:
    - TZ=Asia/Shanghai
    - DATABASE=mysql
    - SPRING_DATASOURCE_URL=jdbc:mysql://nacos-mysql:3306/dolphinscheduler?useUnicode=true&characterEncoding=UTF-8&autoReconnect=true&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
    - SPRING_DATASOURCE_USERNAME=nacos
    - SPRING_DATASOURCE_PASSWORD=nacos123
    - REGISTRY_TYPE=standalone
    - ALERT_SERVER_HOST=dolphinscheduler-alert
  depends_on:
    nacos-mysql:
      condition: service_healthy
    dolphinscheduler-api:
      condition: service_started
  networks:
    - datanest-net

# ============================================
# DolphinScheduler Alert Server
# ============================================
dolphinscheduler-alert:
  image: apache/dolphinscheduler-alert-server:3.4.2
  container_name: datanest-dolphinscheduler-alert
  environment:
    - TZ=Asia/Shanghai
    - DATABASE=mysql
    - SPRING_DATASOURCE_URL=jdbc:mysql://nacos-mysql:3306/dolphinscheduler?useUnicode=true&characterEncoding=UTF-8&autoReconnect=true&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
    - SPRING_DATASOURCE_USERNAME=nacos
    - SPRING_DATASOURCE_PASSWORD=nacos123
  depends_on:
    nacos-mysql:
      condition: service_healthy
  networks:
    - datanest-net
```

### 4.2 初始化脚本

新增 `scripts/init-dolphinscheduler-db.sql`：

```sql
CREATE
DATABASE IF NOT EXISTS dolphinscheduler
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;
```

> 注：DolphinScheduler 首次启动时会自动在 `dolphinscheduler` 库中建表。

### 4.3 启动顺序

```
nacos-mysql → nacos → postgres → xxl-job-admin
    ↓
dolphinscheduler-api → dolphinscheduler-master → dolphinscheduler-worker → dolphinscheduler-alert
    ↓
system → engineering → governance → worker → job → gateway → frontend
```

### 4.4 Gateway 路由

Sprint 3 项目管理 API 统一挂在 `engineering-service` 下。Gateway 已有 `/api/engineering/** → data-nest-engineering` 路由，
**无需新增路由**；前端通过以下路径访问：

- `/api/engineering/dev/dag-projects/**` → `engineering-service` `/engineering/dev/dag-projects/**`
- `/api/engineering/dev/dags/**` → `engineering-service` `/engineering/dev/dags/**`
- `/api/engineering/dev/sql-preview` → `engineering-service` `/engineering/dev/sql-preview`
- `/api/engineering/dag-executions` → `engineering-service` `/engineering/dag-executions`

> Controller 映射前缀为 `/dev/...`（项目、DAG、SQL 编辑器）与 `/dag-executions`（全局执行历史）；`engineering-service` 的
> context-path `/engineering` 由 Spring Boot 自动拼接。

---

## 5. 架构关系图

### 5.1 整体数据流

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           DataNest Frontend                                  │
│  ┌──────────────┐  ┌──────────────┐  ┌─────────────────────────────────────┐│
│  │ 项目列表      │  │ DAG 列表      │  │ ReactFlow 画布                       ││
│  └──────────────┘  └──────────────┘  │ • SQL 任务节点                        ││
│                                       │ • Python 任务节点                     ││
│                                       │ • 同步任务节点                        ││
│                                       │ • 依赖连线                            ││
│                                       └─────────────────────────────────────┘│
└─────────────────────────────────┬───────────────────────────────────────────┘
                                  │ HTTP
                                  ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                     data-nest-engineering (8082)                             │
│  ┌─────────────────┐  ┌─────────────────┐  ┌───────────────────────────────┐ │
│  │ Project API     │  │ DAG API         │  │ SqlEditor API                  │ │
│  └────────┬────────┘  └────────┬────────┘  └────────┬──────────────────────┘ │
│           │                    │                    │                        │
│           └────────────────────┴────────────────────┘                        │
│                                  │                                            │
│                    ┌─────────────▼─────────────┐                            │
│                    │    DagDsSyncService       │                            │
│                    │  DataNest DAG ↔ DS Process│                            │
│                    └─────────────┬─────────────┘                            │
│                                  │ HTTP / DS Java Client                     │
└──────────────────────────────────┼──────────────────────────────────────────┘
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                  Apache DolphinScheduler 3.4.2                               │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐ │
│  │ API Server  │  │ Master      │  │ Worker      │  │ Alert Server        │ │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘  └─────────────────────┘ │
│         │                │                │                                  │
│         │  保存 Process   │  调度执行       │  执行 HTTP 任务                   │
└─────────┼────────────────┼────────────────┼──────────────────────────────────┘
          │                │                │
          │                │                ▼
          │                │   ┌─────────────────────────────────────────────┐
          │                │   │  SQL Task → POST /engineering/dev/internal/sql/callback    │
          │                │   │  Sync Task → POST /engineering/dev/internal/sync/callback  │
          │                │   │  Python Task → POST /engineering/dev/internal/python/callback│
          │                │   └─────────────────────────────────────────────┘
          │                │                │
          │                │                ▼
          │                │   ┌─────────────────────────────┐
          │                └──▶│  data-nest-engineering       │
          │                    │  • DorisSqlExecutor          │
          │                    │  • PythonExecutor             │
          │                    │  • SyncJob trigger & poll    │
          │                    │  • Metadata registration     │
          │                    └─────────────────────────────┘
          │
          └────── 查询流程实例状态 ──────▶ 回显到前端画布
```

### 5.2 节点执行模型

| DataNest 节点类型 | DS 任务类型 | DS 任务内容                                      | 回调接口                                                                                |
|-------------------|-------------|--------------------------------------------------|-----------------------------------------------------------------------------------------|
| SQL 任务          | HTTP 任务   | POST `/engineering/dev/internal/sql/callback`    | engineering-service 内部接口，执行 SQL 并注册元数据                                     |
| Python 任务       | HTTP 任务   | POST `/engineering/dev/internal/python/callback` | engineering-service 内部接口，执行 Python 脚本                                          |
| 同步任务          | HTTP 任务   | POST `/engineering/dev/internal/sync/callback`   | engineering-service 内部接口，触发同步任务；状态由 `DagExecutionSyncService` 反查历史表 |

> DS 任务以 HTTP 回调方式调用 engineering-service，engineering-service 负责任务实际执行、超时控制、错误处理和元数据注册。SYNC
> 节点不再提供单独的轮询接口，状态由 `DagExecutionSyncService` 通过查询同步历史表获得。

---

## 6. task-core：DAG 领域模型与执行映射

### 6.1 实体设计

DAG 相关实体统一放在 `data-nest-task-core` 的 `dag` 包下，供 `engineering-service` 和后续消费方共用。

```java
// DagProject.java
@Data
@TableName("dag_project")
public class DagProject {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String name;                 // 全局唯一
    private String description;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

// Dag.java
@Data
@TableName("dag")
public class Dag {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long projectId;
    private String name;                 // 项目内唯一
    private String triggerType;          // MANUAL / CRON
    private String cronExpression;
    private Integer scheduleEnabled;     // 0 / 1
    private Integer maxParallelism;      // 默认 3
    private String status;               // ENABLED / DISABLED
    private Long dsProjectCode;          // DolphinScheduler 项目 Code
    private Long dsProcessDefinitionId;  // DolphinScheduler 流程定义 ID
    private Long dsProcessDefinitionCode;// DolphinScheduler 流程定义 Code
    private Long dsScheduleId;           // DolphinScheduler 调度 ID
    private String releaseState;         // OFFLINE / ONLINE
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

// DagNode.java
@Data
@TableName(value = "dag_node")
public class DagNode {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long dagId;
    private String nodeId;               // DAG 内唯一（前端生成 UUID）
    private String nodeName;             // DAG 内唯一，数据库 VARCHAR(100)
    private String nodeType;             // SQL / PYTHON / SYNC
    private Double positionX;
    private Double positionY;
    private String config;               // JSON 字符串，业务层用 fastjson2 / ObjectMapper 解析
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

// NodeConfig.java（多态 JSON）
@Data
public class NodeConfig {
    private String type;
}

@Data
public class SqlNodeConfig extends NodeConfig {
    private String sqlContent;
}

@Data
public class SyncNodeConfig extends NodeConfig {
    private Long syncJobId;
    private String syncJobName;
}

// DagEdge.java
@Data
@TableName("dag_edge")
public class DagEdge {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long dagId;
    private String edgeId;
    private String sourceNodeId;
    private String targetNodeId;
    private Long createdBy;
    private LocalDateTime createdAt;
}

// DagExecution.java
@Data
@TableName("dag_execution")
public class DagExecution {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long dagId;
    private Long dsProcessInstanceId;    // DS 流程实例 ID
    private String triggerType;          // MANUAL / CRON
    private String status;               // RUNNING / SUCCESS / FAILED / TERMINATED
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Long durationMs;
    private Long createdBy;
    private LocalDateTime createdAt;
}

// NodeExecution.java
@Data
@TableName("node_execution")
public class NodeExecution {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long executionId;
    private String nodeId;
    private String nodeName;
    private String nodeType;
    private String status;               // WAITING / RUNNING / SUCCESS / FAILED / SKIPPED
    private Long dsTaskInstanceId;       // DS 任务实例 ID
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Long durationMs;
    private String errorMessage;
    private String outputInfo;           // 影响行数、创建表名等
}
```

### 6.2 DAG 拓扑校验

```java

@Service
public class DagTopologyService {

    /**
     * 校验 DAG 无环，并返回拓扑排序后的节点列表。
     * 存在环时抛出 BusinessException。
     */
    public List<DagNode> validateAndSort(Dag dag, List<DagNode> nodes, List<DagEdge> edges) {
        Map<String, Set<String>> graph = edges.stream()
                .collect(Collectors.groupingBy(DagEdge::getSourceNodeId,
                        Collectors.mapping(DagEdge::getTargetNodeId, Collectors.toSet())));

        Set<String> visited = new HashSet<>();
        Set<String> recStack = new HashSet<>();
        List<String> sorted = new ArrayList<>();

        for (String nodeId : graph.keySet()) {
            if (!visited.contains(nodeId) && hasCycle(nodeId, graph, visited, recStack, sorted)) {
                throw new BusinessException(ErrorCode.DAG_CYCLE_DETECTED, "DAG 存在循环依赖");
            }
        }
        Collections.reverse(sorted);
        return nodes.stream()
                .filter(n -> sorted.contains(n.getNodeId()))
                .sorted(Comparator.comparingInt(n -> sorted.indexOf(n.getNodeId())))
                .collect(Collectors.toList());
    }

    private boolean hasCycle(String node, Map<String, Set<String>> graph,
                             Set<String> visited, Set<String> recStack, List<String> sorted) {
        visited.add(node);
        recStack.add(node);
        for (String next : graph.getOrDefault(node, Set.of())) {
            if (!visited.contains(next) && hasCycle(next, graph, visited, recStack, sorted)) {
                return true;
            } else if (recStack.contains(next)) {
                return true;
            }
        }
        recStack.remove(node);
        sorted.add(node);
        return false;
    }
}
```

### 6.3 SQL 执行服务

SQL 任务通过 JDBC 直连内置 Doris 执行。测试执行不注册元数据，正式执行由 DS 触发回调。

```java

@Service
public class DorisSqlExecutor {

    private final DataSource dorisDataSource;
    private final MetadataRegistrationService metadataRegistrationService;

    /**
     * 执行 SQL（支持多语句，分号分隔）。
     * @param testMode true 表示测试执行，不触发元数据注册
     */
    public SqlExecuteResult execute(String sqlContent, boolean testMode, Long operatorId) {
        List<String> statements = SqlUtils.splitStatements(sqlContent);
        SqlExecuteResult result = new SqlExecuteResult();

        try (Connection conn = dorisDataSource.getConnection(); Statement stmt = conn.createStatement()) {
            for (String sql : statements) {
                String trimmed = sql.trim();
                if (trimmed.isEmpty()) continue;

                long start = System.currentTimeMillis();
                SqlStatementResult stmtResult = new SqlStatementResult();
                stmtResult.setSql(trimmed);

                try {
                    boolean hasResultSet = stmt.execute(trimmed);
                    stmtResult.setSuccess(true);
                    stmtResult.setDurationMs(System.currentTimeMillis() - start);

                    if (hasResultSet) {
                        // SELECT 语句：收集前 N 行用于预览
                        stmtResult.setResultSetPreview(fetchPreview(stmt.getResultSet(), 100));
                    } else {
                        stmtResult.setAffectedRows(stmt.getUpdateCount());
                    }

                    // 正式执行成功后注册元数据
                    if (!testMode) {
                        metadataRegistrationService.registerFromSql(trimmed, operatorId);
                    }
                } catch (SQLException e) {
                    stmtResult.setSuccess(false);
                    stmtResult.setErrorMessage(e.getMessage());
                    result.setSuccess(false);
                    result.setErrorMessage(e.getMessage());
                    break;
                }
                result.getStatements().add(stmtResult);
            }
        } catch (SQLException e) {
            result.setSuccess(false);
            result.setErrorMessage("获取 Doris 连接失败: " + e.getMessage());
        }
        return result;
    }
}
```

### 6.4 元数据自动注册

复用 Sprint 2 的 `MetadataRegistrationService`，新增 SQL 语句解析入口。

```java

@Service
public class MetadataRegistrationService {

    /**
     * 根据 SQL 语句类型决定注册行为。
     */
    public void registerFromSql(String sql, Long operatorId) {
        String upper = sql.trim().toUpperCase(Locale.ROOT);
        if (upper.startsWith("CREATE TABLE") || upper.contains(" AS SELECT")) {
            String tableName = SqlUtils.extractCreatedTable(sql);
            registerDorisTable(tableName, operatorId);
        } else if (upper.startsWith("DROP TABLE")) {
            String tableName = SqlUtils.extractDroppedTable(sql);
            removeDorisTable(tableName);
        } else if (upper.startsWith("ALTER TABLE")) {
            String tableName = SqlUtils.extractAlteredTable(sql);
            refreshDorisTable(tableName, operatorId);
        }
        // INSERT / SELECT / DELETE / UPDATE 不触发结构注册
    }
}
```

---

## 7. engineering-service：项目管理模块

### 7.1 包结构

```
data-nest-engineering/src/main/java/com/datanest/engineering/
└── dev/
    ├── controller/
    │   ├── ProjectController.java
    │   ├── DagController.java
    │   ├── DagExecutionController.java
    │   ├── SqlEditorController.java
    │   └── internal/
    │       ├── SqlExecutionCallbackController.java
    │       ├── PythonExecutionCallbackController.java
    │       └── SyncExecutionCallbackController.java
    ├── service/
    │   ├── ProjectService.java
    │   ├── DagService.java
    │   ├── DagDsSyncService.java
    │   ├── DagExecutionService.java
    │   ├── SqlEditorService.java
    │   ├── PythonExecutorService.java
    │   ├── DagExecutionSyncService.java
    │   └── DagSyncRefService.java
    ├── client/
    │   └── DolphinSchedulerClient.java
    ├── dto/
    │   ├── ProjectCreateRequest.java
    │   ├── ProjectUpdateRequest.java
    │   ├── ProjectDTO.java
    │   ├── DagCreateRequest.java
    │   ├── DagUpdateRequest.java
    │   ├── DagDTO.java
    │   ├── DagNodeDTO.java
    │   ├── DagEdgeDTO.java
    │   ├── SqlExecuteRequest.java
    │   ├── SqlExecuteResultDTO.java
    │   └── PythonExecuteRequest.java
    └── converter/
        └── DagDsConverter.java
```

### 7.2 权限矩阵

| 接口                                                        | SUPER_ADMIN | DATA_ENGINEER | GOVERNANCE_ADMIN | DATA_ANALYST |
|-------------------------------------------------------------|:-----------:|:-------------:|:----------------:|:------------:|
| `GET /dev/dag-projects/**`                                  |     ✅      |      ✅       |        ✅        |      ✅      |
| `POST/PUT/DELETE /dev/dag-projects/**`                      |     ✅      |      ✅       |        ❌        |      ❌      |
| `GET /dev/dags/**`                                          |     ✅      |      ✅       |        ✅        |      ✅      |
| `POST/PUT/DELETE /dev/dags/**`                              |     ✅      |      ✅       |        ❌        |      ❌      |
| `POST /dev/dags/{id}/trigger`                               |     ✅      |      ✅       |        ❌        |      ❌      |
| `POST /dev/dags/{id}/executions/{executionId}/stop`         |     ✅      |      ✅       |        ❌        |      ❌      |
| `POST /dev/dags/{id}/executions/{executionId}/rerun-failed` |     ✅      |      ✅       |        ❌        |      ❌      |
| `POST /dev/dags/{id}/executions/page`                       |     ✅      |      ✅       |        ✅        |      ✅      |
| `GET /dag-executions`                                       |     ✅      |      ✅       |        ✅        |      ✅      |
| `GET /dag-executions/{id}/nodes`                            |     ✅      |      ✅       |        ✅        |      ✅      |
| `POST /dev/sql-preview`                                     |     ✅      |      ✅       |        ❌        |      ❌      |

> 所有接口通过 `@SaCheckRole` 控制，治理员和分析师仅只读。表中路径为 Controller 映射路径；经 Gateway 后前端访问路径需加
> `/api/engineering` 前缀。

### 7.3 ProjectController

```java

@RestController
@RequestMapping("/dev/dag-projects")
public class ProjectController {

    private final ProjectService projectService;

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER", "GOVERNANCE_ADMIN", "DATA_ANALYST"}, mode = SaMode.OR)
    @GetMapping
    public Result<PageResult<ProjectDTO>> list(
            @RequestParam(required = false) String name,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize) {
        return Result.ok(projectService.page(name, page, pageSize));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER", "GOVERNANCE_ADMIN", "DATA_ANALYST"}, mode = SaMode.OR)
    @GetMapping("/{id}")
    public Result<ProjectDTO> get(@PathVariable Long id) {
        return Result.ok(projectService.getById(id));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping
    public Result<ProjectDTO> create(@Valid @RequestBody ProjectCreateRequest request) {
        return Result.ok(projectService.create(request));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PutMapping("/{id}")
    public Result<ProjectDTO> update(@PathVariable Long id,
                                     @Valid @RequestBody ProjectUpdateRequest request) {
        return Result.ok(projectService.update(id, request));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        projectService.delete(id);
        return Result.ok(null);
    }
}
```

### 7.4 DagController

```java

@RestController
@RequestMapping("/dev/dags")
public class DagController {

    private final DagService dagService;
    private final DagExecutionService dagExecutionService;
    private final PythonExecutor pythonExecutor;
    private final DagParameterService dagParameterService;

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER", "GOVERNANCE_ADMIN", "DATA_ANALYST"}, mode = SaMode.OR)
    @GetMapping
    public Result<List<DagPayload>> list(@RequestParam(required = false) Long projectId) {
        return Result.ok(dagService.list(projectId));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER", "GOVERNANCE_ADMIN", "DATA_ANALYST"}, mode = SaMode.OR)
    @GetMapping("/{id}")
    public Result<DagPayload> get(@PathVariable Long id) {
        return Result.ok(dagService.getDetail(id));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping
    public Result<DagPayload> create(@RequestBody DagPayload payload) {
        return Result.ok(dagService.create(payload));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PutMapping("/{id}")
    public Result<DagPayload> update(@PathVariable Long id, @RequestBody DagPayload payload) {
        return Result.ok(dagService.update(id, payload));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        dagService.delete(id);
        return Result.ok(null);
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/{id}/trigger")
    public Result<DagExecutionDTO> trigger(@PathVariable Long id,
                                           @RequestBody(required = false) Map<String, Object> params) {
        return Result.ok(dagExecutionService.trigger(id, params));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/{id}/schedule/start")
    public Result<Void> startSchedule(@PathVariable Long id) {
        dagService.startSchedule(id);
        return Result.ok((Void) null);
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/{id}/schedule/stop")
    public Result<Void> stopSchedule(@PathVariable Long id) {
        dagService.stopSchedule(id);
        return Result.ok((Void) null);
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/{id}/executions/{executionId}/stop")
    public Result<Void> stop(@PathVariable Long id,
                             @PathVariable Long executionId) {
        dagExecutionService.stop(id, executionId);
        return Result.ok(null);
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/{id}/executions/{executionId}/rerun-failed")
    public Result<DagExecutionDTO> rerunFailed(@PathVariable Long id,
                                               @PathVariable Long executionId) {
        return Result.ok(dagExecutionService.rerunFailed(id, executionId));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER", "GOVERNANCE_ADMIN", "DATA_ANALYST"}, mode = SaMode.OR)
    @GetMapping("/{id}/executions")
    public Result<List<DagExecutionDTO>> executions(@PathVariable Long id) {
        return Result.ok(dagExecutionService.listByDag(id));
    }

    /**
     * PYTHON 节点脚本测试：执行脚本并返回结果，不注册元数据、不写 node_execution。
     */
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/{id}/nodes/{nodeId}/python/test")
    public Result<PythonExecuteResult> testPythonNode(@PathVariable Long id,
                                                      @PathVariable String nodeId,
                                                      @RequestBody PythonTestRequest request) { ... }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER", "GOVERNANCE_ADMIN", "DATA_ANALYST"}, mode = SaMode.OR)
    @GetMapping("/node-executions/{nodeExecutionId}/logs")
    public Result<List<SyncJobLogDTO>> nodeExecutionLogs(@PathVariable Long nodeExecutionId) {
        return Result.ok(dagExecutionService.getNodeExecutionLogs(nodeExecutionId));
    }
}
```

### 7.5 DagService 核心逻辑

> **与代码对齐**：实际实现中统一使用 `DagPayload` 作为创建/更新/详情的请求与响应对象；`syncToDs(...)` 是 `DagService`
> 内的私有方法，由 `DagDsConverter` 生成 DS JSON；触发/停止/重跑等执行语义下沉到 `DagExecutionService`；启用/停用调度由
> `startSchedule` / `stopSchedule` 单独暴露。

```java

@Service
public class DagService {

    private final DagMapper dagMapper;
    private final DagNodeMapper dagNodeMapper;
    private final DagEdgeMapper dagEdgeMapper;
    private final DagExecutionMapper dagExecutionMapper;
    private final NodeExecutionMapper nodeExecutionMapper;
    private final DagTopologyService topologyService;
    private final DolphinSchedulerClient dolphinSchedulerClient;
    private final DagDsConverter dagDsConverter;
    private final DagProjectService dagProjectService;
    private final DagVersionService dagVersionService;
    private final SysUserService sysUserService;

    @Transactional
    public DagPayload create(DagPayload payload) {
        validateRequest(payload);
        // 名称在项目内唯一
        if (dagMapper.countByProjectIdAndName(payload.getProjectId(), payload.getName()) > 0) {
            throw new BusinessException(ErrorCode.DAG_NAME_EXISTS);
        }
        // 拓扑校验
        List<DagNode> nodes = toNodeEntities(payload.getNodes(), null);
        List<DagEdge> edges = toEdgeEntities(payload.getEdges(), null);
        topologyService.validateAndSort(nodes, edges);

        // 入库
        Dag dag = new Dag();
        copyFromPayload(dag, payload);
        dag.setStatus(payload.getStatus() == null ? "ENABLED" : payload.getStatus());
        dag.setMaxParallelism(payload.getMaxParallelism() == null ? 3 : payload.getMaxParallelism());
        dag.setScheduleEnabled(Boolean.TRUE.equals(payload.getScheduleEnabled()) ? 1 : 0);
        dag.setReleaseState("OFFLINE");
        dagMapper.insert(dag);

        // 保存节点 + 边，生成 DS task code
        Map<String, Long> codeMap = saveNodesAndEdges(dag.getId(), payload, Map.of());

        // 事务提交后异步同步到 DS
        Long dagId = dag.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try { syncToDs(dagId, payload, codeMap); }
                catch (Exception e) { logger.error(...); }
            }
        });

        return getDetail(dag.getId());
    }

    @Transactional
    public DagPayload update(Long id, DagPayload payload) {
        validateRequest(payload);
        Dag existing = dagMapper.selectById(id);
        if (existing == null) throw new BusinessException(ErrorCode.DAG_NOT_FOUND);
        // 拓扑校验、全量替换 nodes/edges、生成版本快照...
        // 事务提交后：先下线旧 DS 定义，再全量同步新定义
        return getDetail(id);
    }

    @Transactional
    public void delete(Long id) {
        // 级联删除 execution / node_execution / nodes / edges / dag
        // 事务提交后异步清理 DS schedule 与 workflow
    }

    public DagPayload getDetail(Long id) { ... }

    public List<DagPayload> list(Long projectId) {
        // 一次性返回项目下全部 DAG（前端做假分页）
        // 附带 nodeSummary 与 latestExecution
    }

    public void startSchedule(Long id) { toggleSchedule(id, true); }
    public void stopSchedule(Long id) { toggleSchedule(id, false); }

    private void syncToDs(Long dagId, DagPayload payload, Map<String, Long> codeMap) { ... }
}
```

### 7.6 SQL 编辑器服务

```java

@Service
public class SqlEditorService {

    private final DorisSqlExecutor dorisSqlExecutor;

    /**
     * 测试执行：不注册元数据。
     */
    public SqlExecuteResultDTO testExecute(SqlExecuteRequest request) {
        SqlExecuteResult result = dorisSqlExecutor.execute(request.getSqlContent(), true,
                StpUtil.getLoginIdAsLong());
        return convert(result);
    }

    /**
     * 正式执行：由 DS HTTP 任务回调触发，注册元数据。
     * 该接口为 internal，不暴露给前端直接调用。
     */
    public SqlExecuteResultDTO execute(SqlExecuteRequest request) {
        SqlExecuteResult result = dorisSqlExecutor.execute(request.getSqlContent(), false,
                StpUtil.getLoginIdAsLong());
        return convert(result);
    }
}
```

### 7.7 同步任务触发与状态收尾

> **与代码对齐**：SYNC 节点 **不再提供 `/internal/sync/{historyId}/status` 轮询接口**。DS HTTP 任务回调 engineering 的
> `/dev/internal/sync/callback` 后，节点被标为 `RUNNING` 并记录 `sync_job_id` / `sync_job_history_id`；最终状态由
> `DagExecutionSyncService`（task-core）轮询 `sync_job_history` 表收尾。

```java

@RestController
@RequestMapping("/dev/internal")
public class DagNodeCallbackController {

    private final SyncJobService syncJobService;
    private final SyncNodeMutexService syncNodeMutexService;

    @PostMapping("/sync/callback")
    public Result<Map<String, Integer>> syncCallback(@RequestBody Map<String, Object> body) {
        return handleSyncNode(body);
    }

    private Result<Map<String, Integer>> handleSyncNode(Map<String, Object> body) {
        String nodeId = stringOf(body.get("nodeId"));
        Long dsProcessInstanceId = longOf(body.get("executionId"));
        Long dagId = longOf(body.get("dagId"));
        Map<?, ?> syncJobObj = (Map<?, ?>) body.get("syncJob");
        Long syncJobId = longOf(syncJobObj.get("id"));

        // 任务级互斥：同一 syncJobId 同一时刻只能一个实例运行
        String lockToken = syncNodeMutexService.tryLock(syncJobId);
        try {
            NodeExecution ne = resolveNodeExecution(nodeId, dagId, dsProcessInstanceId);
            ne.setStatus("RUNNING");
            ne.setStartTime(LocalDateTime.now());
            ne.setSyncJobId(syncJobId);

            // 触发 XXL-JOB，来源标记为 DAG
            Long historyId = syncJobService.execute(syncJobId, "DAG", ne.getExecutionId());
            ne.setSyncJobHistoryId(historyId);
            nodeExecutionMapper.updateById(ne);

            // 不标 SUCCESS！由 DagExecutionSyncService 反查 sync_job_history 收尾
            return Result.ok(Map.of("affectedRows", 0));
        } catch (Exception e) {
            syncNodeMutexService.unlock(syncJobId, lockToken);
            throw e;
        }
    }
}

@Service
public class SyncNodeMutexService {

    private final StringRedisTemplate redisTemplate;

    public String tryLock(Long syncJobId) {
        String key = "datanest:sync:job:mutex:" + syncJobId;
        String token = UUID.randomUUID().toString();
        Boolean ok = redisTemplate.opsForValue()
                .setIfAbsent(key, token, 6, TimeUnit.HOURS);
        if (!Boolean.TRUE.equals(ok)) {
            throw new BusinessException(ErrorCode.DAG_ALREADY_RUNNING,
                    "同步任务正在执行中");
        }
        return token;
    }

    public void unlock(Long syncJobId, String token) { ... }
}
```

`DagExecutionSyncService`（task-core）定时轮询 RUNNING 的 `dag_execution`，对 `node_type='SYNC'` 且状态为 `RUNNING` 的节点查询
`sync_job_history`：

- `SUCCESS` → 节点标 `SUCCESS`，写 `end_time` / `duration_ms` / `output_info` / `sync_job_history_id`，释放互斥锁。
- `FAILED` → 节点标 `FAILED`，写 `error_message`，释放锁。
- `TERMINATED` → 节点标 `TERMINATED`，释放锁。

---

## 8. DolphinScheduler 集成

### 8.1 集成方式

engineering-service 通过 **DolphinScheduler REST API** 与 DS 交互，不引入 DS Java Client 依赖（避免版本冲突）。核心交互：

1. 创建/更新 ProcessDefinition
2. 上线（release）ProcessDefinition
3. 手动触发流程实例
4. 查询流程实例状态
5. 查询任务实例状态
6. 终止流程实例

### 8.2 DolphinSchedulerClient

```java

@Component
public class DolphinSchedulerClient {

    @Value("${datanest.dolphinscheduler.api-url}")
    private String apiUrl;

    @Value("${datanest.dolphinscheduler.token}")
    private String token;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * 创建或更新流程定义。
     */
    public DsProcessDefinition syncProcessDefinition(Dag dag, List<DagNode> nodes,
                                                     List<DagEdge> edges,
                                                     Long existingDefinitionId) {
        // 1. 查询项目（DataNest Project 映射为 DS Project）
        Long dsProjectCode = ensureDsProject(dag.getProjectId());

        // 2. 构建任务列表和依赖关系
        List<DsTaskDefinition> taskDefinitions = buildTaskDefinitions(nodes, edges);
        List<DsTaskRelation> taskRelations = buildTaskRelations(edges);

        // 3. 组装 ProcessDefinition
        DsProcessDefinitionCreateRequest request = new DsProcessDefinitionCreateRequest();
        request.setName(dag.getName());
        request.setDescription("DataNest DAG: " + dag.getName());
        request.setTaskDefinitionJson(JsonUtils.toJson(taskDefinitions));
        request.setTaskRelationJson(JsonUtils.toJson(taskRelations));

        if (existingDefinitionId != null) {
            // 更新
            return put("/projects/" + dsProjectCode + "/process-definition/" + existingDefinitionId,
                    request, DsProcessDefinition.class);
        } else {
            // 创建
            return post("/projects/" + dsProjectCode + "/process-definition", request,
                    DsProcessDefinition.class);
        }
    }

    /**
     * 上线流程定义。
     */
    public void releaseProcessDefinition(Long dsProjectCode, Long dsProcessDefinitionId) {
        DsReleaseRequest request = new DsReleaseRequest();
        request.setProcessDefinitionId(dsProcessDefinitionId);
        request.setReleaseState("ONLINE");
        post("/projects/" + dsProjectCode + "/process-definition/" + dsProcessDefinitionId + "/release",
                request, Void.class);
    }

    /**
     * 手动触发流程实例。
     */
    public DsProcessInstance startProcessInstance(Long dsProjectCode, Long dsProcessDefinitionId) {
        DsStartProcessRequest request = new DsStartProcessRequest();
        request.setProcessDefinitionId(dsProcessDefinitionId);
        request.setFailureStrategy("END");          // 失败即结束
        request.setProcessInstancePriority("MEDIUM");
        request.setRunMode("RUN_MODE_SERIAL");      // 串行执行，避免同一 DAG 并发
        return post("/projects/" + dsProjectCode + "/executors/start-process-instance",
                request, DsProcessInstance.class);
    }

    /**
     * 查询流程实例状态。
     */
    public DsProcessInstance getProcessInstance(Long dsProjectCode, Long processInstanceId) {
        return get("/projects/" + dsProjectCode + "/process-instances/" + processInstanceId,
                DsProcessInstance.class);
    }

    /**
     * 查询任务实例列表。
     */
    public List<DsTaskInstance> listTaskInstances(Long dsProjectCode, Long processInstanceId) {
        return getList("/projects/" + dsProjectCode + "/task-instances?processInstanceId=" + processInstanceId,
                DsTaskInstance.class);
    }

    /**
     * 终止流程实例。
     */
    public void stopProcessInstance(Long dsProjectCode, Long processInstanceId) {
        post("/projects/" + dsProjectCode + "/executors/execute?processInstanceId=" + processInstanceId
                + "&executeType=STOP", null, Void.class);
    }

    private <T> T post(String path, Object body, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("token", token);
        HttpEntity<?> entity = new HttpEntity<>(body, headers);
        return restTemplate.postForObject(apiUrl + path, entity, responseType);
    }

    // get / put 省略
}
```

### 8.3 DAG → DS ProcessDefinition 映射

#### SQL 任务节点映射

```json
{
  "name": "SQL_订单数据清洗",
  "taskType": "HTTP",
  "flag": "YES",
  "taskParams": {
    "url": "http://data-nest-engineering:8082/engineering/dev/internal/sql/callback",
    "httpMethod": "POST",
    "httpParams": [
      {
        "prop": "sqlContent",
        "value": "${sqlContent}",
        "httpParamsType": "BODY"
      }
    ],
    "httpCheckCondition": "STATUS_CODE_DEFAULT",
    "condition": "${response_code} == 200",
    "connectTimeout": 60000,
    "socketTimeout": 1800000
  }
}
```

> 实际 SQL 内容在创建 ProcessDefinition 时直接写入 `httpParams.value`，避免 DS 参数解析问题。

#### Python 任务节点映射

```json
{
  "name": "PYTHON_用户画像评分",
  "taskType": "HTTP",
  "taskParams": {
    "url": "http://data-nest-engineering:8082/engineering/dev/internal/python/callback",
    "httpMethod": "POST",
    "httpParams": [
      {
        "prop": "pythonScript",
        "value": "...",
        "httpParamsType": "BODY"
      }
    ],
    "httpCheckCondition": "STATUS_CODE_DEFAULT",
    "condition": "${response_code} == 200",
    "connectTimeout": 60000,
    "socketTimeout": 1800000
  }
}
```

#### 同步任务节点映射

```json
{
  "name": "SYNC_日志数据同步",
  "taskType": "HTTP",
  "taskParams": {
    "url": "http://data-nest-engineering:8082/engineering/dev/internal/sync/callback",
    "httpMethod": "POST",
    "httpParams": [
      {
        "prop": "syncJobId",
        "value": "10001",
        "httpParamsType": "BODY"
      }
    ],
    "httpCheckCondition": "STATUS_CODE_DEFAULT",
    "condition": "${response_code} == 200",
    "connectTimeout": 60000,
    "socketTimeout": 1800000
  }
}
```

> **DS 任务名称与 task code 生成策略**：DataNest 节点映射为 DS 任务时，任务名称与 task code 生成策略以代码实现为准。一般做法是由
> DS 创建任务定义后返回 `code`，DataNest 将 `node_id` 与 DS `task_code` 的映射关系持久化到 `dag_node`
> 或关联表，后续依赖边、状态回查均通过该映射进行。

#### 依赖关系映射

```json
[
  {
    "name": "",
    "preTaskCode": 1001,
    "postTaskCode": 1002,
    "preTaskVersion": 1,
    "postTaskVersion": 1,
    "conditionType": "NONE"
  }
]
```

### 8.4 Cron 调度映射

DataNest DAG 的 Cron 表达式直接映射为 DS ProcessDefinition 的 Schedule。

```java
public void syncSchedule(Dag dag, Long dsProjectCode, Long dsProcessDefinitionId) {
    if (!"CRON".equals(dag.getTriggerType()) || dag.getCronExpression() == null) {
        return;
    }
    DsScheduleRequest request = new DsScheduleRequest();
    request.setProcessDefinitionId(dsProcessDefinitionId);
    request.setCrontab(dag.getCronExpression());  // Quartz 六字段
    request.setReleaseState(dag.getScheduleEnabled() == 1 ? "ONLINE" : "OFFLINE");
    request.setWarningType("NONE");

    if (dag.getDsScheduleId() != null) {
        put("/projects/" + dsProjectCode + "/schedules/" + dag.getDsScheduleId(), request, Void.class);
    } else {
        DsSchedule schedule = post("/projects/" + dsProjectCode + "/schedules", request, DsSchedule.class);
        dag.setDsScheduleId(schedule.getId());
    }
}
```

### 8.5 状态回查与同步

```java

@Service
public class DagExecutionService {

    private final DolphinSchedulerClient dsClient;
    private final DagExecutionMapper dagExecutionMapper;
    private final NodeExecutionMapper nodeExecutionMapper;

    /**
     * 手动触发 DAG 执行。
     */
    public DagExecutionDTO startManual(Dag dag) {
        // 同一 DAG 运行中实例互斥（DS RUN_MODE_SERIAL 已保证，这里再校验一次）
        if (dagExecutionMapper.hasRunningExecution(dag.getId())) {
            throw new BusinessException(ErrorCode.DAG_ALREADY_RUNNING);
        }

        DsProcessInstance instance = dsClient.startProcessInstance(
                dag.getDsProjectCode(), dag.getDsProcessDefinitionId());

        DagExecution execution = new DagExecution();
        execution.setDagId(dag.getId());
        execution.setDsProcessInstanceId(instance.getId());
        execution.setTriggerType("MANUAL");
        execution.setStatus("RUNNING");
        execution.setStartTime(LocalDateTime.now());
        execution.setCreatedBy(StpUtil.getLoginIdAsLong());
        dagExecutionMapper.insert(execution);

        return toDTO(execution);
    }

    /**
     * 定时回查 DS 流程实例状态，更新 DataNest 执行记录。
     * 由 engineering-service 的 Spring Scheduler 每 5 秒执行一次。
     */
    @Scheduled(fixedRate = 5000)
    public void syncExecutionStatus() {
        List<DagExecution> running = dagExecutionMapper.selectByStatus("RUNNING");
        for (DagExecution execution : running) {
            Dag dag = dagMapper.selectById(execution.getDagId());
            DsProcessInstance instance = dsClient.getProcessInstance(
                    dag.getDsProjectCode(), execution.getDsProcessInstanceId());

            execution.setStatus(mapDsStatus(instance.getState()));
            execution.setEndTime(instance.getEndTime());
            if (execution.getEndTime() != null && execution.getStartTime() != null) {
                execution.setDurationMs(Duration.between(execution.getStartTime(),
                        execution.getEndTime()).toMillis());
            }
            dagExecutionMapper.updateById(execution);

            // 同步节点级状态
            syncNodeExecutions(execution, dag, instance);
        }
    }

    private void syncNodeExecutions(DagExecution execution, Dag dag, DsProcessInstance instance) {
        List<DsTaskInstance> dsTasks = dsClient.listTaskInstances(
                dag.getDsProjectCode(), instance.getId());
        for (DsTaskInstance dsTask : dsTasks) {
            NodeExecution node = nodeExecutionMapper.selectByExecutionAndDsTaskId(
                    execution.getId(), dsTask.getId());
            if (node == null) {
                node = new NodeExecution();
                node.setExecutionId(execution.getId());
                node.setDsTaskInstanceId(dsTask.getId());
                DagNode dagNode = findDagNodeByDsName(dag, dsTask.getName());
                node.setNodeId(dagNode.getNodeId());
                node.setNodeName(dagNode.getNodeName());
                node.setNodeType(dagNode.getNodeType());
                nodeExecutionMapper.insert(node);
            }
            node.setStatus(mapDsTaskStatus(dsTask.getState()));
            node.setStartTime(dsTask.getStartTime());
            node.setEndTime(dsTask.getEndTime());
            node.setDurationMs(dsTask.getDuration());
            node.setErrorMessage(dsTask.getLogPath());  // Sprint 3 只存日志路径
            nodeExecutionMapper.updateById(node);
        }
    }

    /**
     * 全局执行历史查询（供独立执行历史页面使用）。
     */
    public PageResult<DagExecutionDTO> pageGlobal(DagExecutionQueryParams request) {
        Page<DagExecution> page = dagExecutionMapper.selectGlobalPage(request);
        List<DagExecutionDTO> list = page.getRecords().stream()
                .map(this::toDTO)
                .peek(dto -> dto.setDagName(dagMapper.selectNameById(dto.getDagId())))
                .collect(Collectors.toList());
        return new PageResult<>(page.getCurrent(), page.getSize(), page.getTotal(), list);
    }
}
```

### 7.6 DagExecutionController

```java
@RestController
@RequestMapping("/dag-executions")
public class DagExecutionController {

    private final DagExecutionService dagExecutionService;

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER", "GOVERNANCE_ADMIN", "DATA_ANALYST"}, mode = SaMode.OR)
    @GetMapping
    public Result<PageResult<DagExecutionDTO>> page(@Valid DagExecutionQueryParams request) {
        return Result.ok(dagExecutionService.pageGlobal(request));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER", "GOVERNANCE_ADMIN", "DATA_ANALYST"}, mode = SaMode.OR)
    @GetMapping("/{executionId}")
    public Result<DagExecutionDTO> detail(@PathVariable Long executionId) {
        return Result.ok(dagExecutionService.getDetail(executionId));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER", "GOVERNANCE_ADMIN", "DATA_ANALYST"}, mode = SaMode.OR)
    @GetMapping("/{executionId}/nodes")
    public Result<List<NodeExecutionDTO>> nodes(@PathVariable Long executionId) {
        return Result.ok(dagExecutionService.listNodeExecutions(executionId));
    }
}
```

### 8.6 停止执行

```java
public void stop(DagExecution execution) {
    Dag dag = dagMapper.selectById(execution.getDagId());
    dsClient.stopProcessInstance(dag.getDsProjectCode(), execution.getDsProcessInstanceId());
    execution.setStatus("TERMINATED");
    execution.setEndTime(LocalDateTime.now());
    dagExecutionMapper.updateById(execution);
}
```

### 8.7 重跑失败节点

```java
public DagExecutionDTO rerunFailedNodes(DagExecution execution) {
    Dag dag = dagMapper.selectById(execution.getDagId());
    // 仅重新调度失败/被跳过的节点，已成功节点不再重复执行
    return dsClient.rerunFailedTasks(dag.getDsProjectCode(), execution.getDsProcessInstanceId());
}
```

---

## 9. 数据库设计

### 9.1 Flyway 迁移策略

沿用 Sprint 0-2 模式：所有迁移脚本集中在 `data-nest-system/src/main/resources/db/migration/`，Sprint 3 新增/扩展：

| 脚本                                                      | 版本     | 内容                                                                            |
|-----------------------------------------------------------|----------|---------------------------------------------------------------------------------|
| `V3.2.0__dag_tables.sql`                                  | Sprint 3 | `dag_project`、`dag`、`dag_node`、`dag_edge`、`dag_execution`、`node_execution` |
| `V3.2.1__sync_job_multitable_and_ratelimit.sql`           | Sprint 3 | `sync_job` 扩展：`source_tables_detail`、速率限制 3 字段                        |
| `V3.2.2__sprint3_p0_p2_fixes.sql`                         | Sprint 3 | DAG 执行并发安全、SYNC 互斥等修复字段                                           |
| `V3.2.3__sync_job_source_tables_detail_text.sql`          | Sprint 3 | `source_tables_detail` 改为 TEXT                                                |
| `V3.2.4__dag_node_ds_task_code.sql`                       | Sprint 3 | `dag_node` 增加 `ds_task_code`                                                  |
| `V3.2.5__drop_dead_columns_and_invalid_index.sql`         | Sprint 3 | 清理废弃列与无效索引                                                            |
| `V3.2.6__node_execution_sync_job_history_id.sql`          | Sprint 3 | `node_execution` 增加 `sync_job_history_id`                                     |
| `V3.2.7__dag_execution_edge_snapshot.sql`                 | Sprint 3 | `dag_execution` 增加 `edge_snapshot`                                            |
| `V3.2.8__dag_execution_error_message.sql`                 | Sprint 3 | `dag_execution` 增加 `error_message`                                            |
| `V3.3.0__extend_dag_node_python.sql`                      | Sprint 3 | `dag_node` 扩展 Python 节点配置字段；`node_execution` 增加 `sync_job_id` 等     |
| `V3.3.1__dag_parameter.sql`                               | Sprint 3 | DAG 参数化配置表 `dag_parameter`                                                |
| `V3.3.2__dag_version.sql`                                 | Sprint 3 | DAG 版本管理表 `dag_version`                                                    |
| `V3.3.3__dag_alert_config.sql`                            | Sprint 3 | DAG 告警配置表 `dag_alert_config`                                               |
| `V3.3.4__dag_alert_history.sql`                           | Sprint 3 | DAG 告警历史表 `dag_alert_history`                                              |
| `V3.3.5__node_execution_log.sql`                          | Sprint 3 | 节点执行日志表 `node_execution_log`                                             |
| `V3.3.6__lineage_record.sql`                              | Sprint 3 | SQL/Python 血缘记录表 `lineage_record`                                          |
| `V3.3.7__metadata_table_source.sql`                       | Sprint 3 | `metadata_table` 增加 `source_dag_id` / `source_node_id` 等来源字段             |
| `V3.3.8__dag_execution_params.sql`                        | Sprint 3 | `dag_execution` 增加 `resolved_params`                                          |
| `V3.3.9__sync_job_history_dag_execution_id.sql`           | Sprint 3 | `sync_job_history` 增加 `dag_execution_id`                                      |
| `V3.3.10__dag_alert_config_dag_id.sql`                    | Sprint 3 | `dag_alert_config` 增加 `dag_id`，支持按 DAG 配置                               |
| `V3.4.0__alter_dag_execution_resolved_params_to_text.sql` | Sprint 4 | `dag_execution.resolved_params` 改为 TEXT                                       |
| `V3.4.1__add_sys_user_audit_columns.sql`                  | Sprint 4 | `sys_user` 审计字段                                                             |

> **与代码对齐**：由于 Python 节点、参数化、版本、告警、血缘、节点日志等能力已在 Sprint 3 提前实现，Flyway 脚本清单远不止 2
> 个。具体文件名与顺序以代码仓库 `data-nest-system/src/main/resources/db/migration/` 目录为准。

### 9.2 V3.2.0__dag_tables.sql

```sql
-- ===== DAG 项目 =====
CREATE TABLE IF NOT EXISTS dag_project
(
    id
    BIGINT
    PRIMARY
    KEY,
    name
    VARCHAR
(
    100
) NOT NULL,
    description VARCHAR
(
    500
),
    created_by BIGINT,
    updated_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_dag_project_name UNIQUE
(
    name
)
    );
COMMENT
ON TABLE dag_project IS 'DAG 项目';

-- ===== DAG =====
CREATE TABLE IF NOT EXISTS dag
(
    id
    BIGINT
    PRIMARY
    KEY,
    project_id
    BIGINT
    NOT
    NULL
    REFERENCES
    dag_project
(
    id
),
    name VARCHAR
(
    100
) NOT NULL,
    trigger_type VARCHAR
(
    10
) NOT NULL, -- MANUAL / CRON
    cron_expression VARCHAR
(
    100
),
    schedule_enabled INTEGER DEFAULT 1, -- 0 / 1
    max_parallelism INTEGER DEFAULT 3,
    status VARCHAR
(
    20
) NOT NULL DEFAULT 'ENABLED', -- ENABLED / DISABLED
    ds_project_code BIGINT,
    ds_process_definition_id BIGINT,
    ds_process_definition_code BIGINT,
    ds_schedule_id BIGINT,
    release_state VARCHAR
(
    20
) NOT NULL DEFAULT 'OFFLINE', -- OFFLINE / ONLINE
    created_by BIGINT,
    updated_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_dag_project_name UNIQUE
(
    project_id,
    name
)
    );
COMMENT
ON TABLE dag IS 'DAG 定义';
CREATE INDEX idx_dag_project_id ON dag (project_id);

-- ===== DAG 节点 =====
CREATE TABLE IF NOT EXISTS dag_node
(
    id
    BIGINT
    PRIMARY
    KEY,
    dag_id
    BIGINT
    NOT
    NULL
    REFERENCES
    dag
(
    id
),
    node_id VARCHAR
(
    64
) NOT NULL,
    node_name VARCHAR
(
    100
) NOT NULL,
    node_type VARCHAR
(
    10
) NOT NULL, -- SQL / PYTHON / SYNC
    position_x DOUBLE PRECISION,
    position_y DOUBLE PRECISION,
    config TEXT, -- JSON 字符串，由 fastjson2/ObjectMapper 解析为 SqlNodeConfig / PythonNodeConfig / SyncNodeConfig
    created_by BIGINT,
    updated_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_dag_node UNIQUE
(
    dag_id,
    node_id
)
    );
COMMENT
ON TABLE dag_node IS 'DAG 节点';
CREATE INDEX idx_dag_node_dag_id ON dag_node (dag_id);

-- ===== DAG 边 =====
CREATE TABLE IF NOT EXISTS dag_edge
(
    id
    BIGINT
    PRIMARY
    KEY,
    dag_id
    BIGINT
    NOT
    NULL
    REFERENCES
    dag
(
    id
),
    edge_id VARCHAR
(
    64
) NOT NULL,
    source_node_id VARCHAR
(
    64
) NOT NULL,
    target_node_id VARCHAR
(
    64
) NOT NULL,
    created_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_dag_edge UNIQUE
(
    dag_id,
    edge_id
)
    );
COMMENT
ON TABLE dag_edge IS 'DAG 依赖边';
CREATE INDEX idx_dag_edge_dag_id ON dag_edge (dag_id);

-- ===== DAG 执行实例 =====
CREATE TABLE IF NOT EXISTS dag_execution
(
    id
    BIGINT
    PRIMARY
    KEY,
    dag_id
    BIGINT
    NOT
    NULL
    REFERENCES
    dag
(
    id
),
    ds_process_instance_id BIGINT,
    trigger_type VARCHAR
(
    10
) NOT NULL, -- MANUAL / CRON
    status VARCHAR
(
    20
) NOT NULL, -- RUNNING / SUCCESS / FAILED / TERMINATED
    start_time TIMESTAMP NOT NULL,
    end_time TIMESTAMP,
    duration_ms BIGINT,
    created_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
    );
COMMENT
ON TABLE dag_execution IS 'DAG 执行实例';
CREATE INDEX idx_dag_execution_dag_id ON dag_execution (dag_id);
CREATE INDEX idx_dag_execution_status ON dag_execution (status);
CREATE INDEX idx_dag_execution_start_time ON dag_execution (start_time);

-- ===== 节点执行实例 =====
CREATE TABLE IF NOT EXISTS node_execution
(
    id
    BIGINT
    PRIMARY
    KEY,
    execution_id
    BIGINT
    NOT
    NULL
    REFERENCES
    dag_execution
(
    id
),
    node_id VARCHAR
(
    64
) NOT NULL,
    node_name VARCHAR
(
    100
) NOT NULL,
    node_type VARCHAR
(
    10
) NOT NULL,
    status VARCHAR
(
    20
) NOT NULL, -- WAITING / RUNNING / SUCCESS / FAILED / SKIPPED
    ds_task_instance_id BIGINT,
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    duration_ms BIGINT,
    error_message TEXT,
    output_info TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
    );
COMMENT
ON TABLE node_execution IS 'DAG 节点执行实例';
CREATE INDEX idx_node_execution_execution_id ON node_execution (execution_id);
```

### 9.3 V3.2.1__sync_job_multitable.sql

```sql
-- 同步任务速率限流字段
ALTER TABLE sync_job
    ADD COLUMN IF NOT EXISTS read_rate_limit_mbps INTEGER,
    ADD COLUMN IF NOT EXISTS write_rate_limit_rows_per_second INTEGER,
    ADD COLUMN IF NOT EXISTS rate_limit_enabled INTEGER DEFAULT 0;

COMMENT
ON COLUMN sync_job.read_rate_limit_mbps IS '读取速率上限（MB/s）';
COMMENT
ON COLUMN sync_job.write_rate_limit_rows_per_second IS '写入速率上限（行/s）';
COMMENT
ON COLUMN sync_job.rate_limit_enabled IS '是否启用速率限制 0/1';
```

> 多表同步已在 Sprint 2 通过 `source_tables` JSON 字段支持，Sprint 3 只需在前端交互和 Addax JSON 生成中扩展。
>
> 多表同步实现要点：
> - 前端源表选择从单选改为多选，目标表名默认等于源表名，允许逐个修改。
> - `fieldMapping` 仍只保存第一张子表的字段映射；其余表按同名自动映射。
> - 保存前 engineering-service 校验所有选中源表的同名字段类型是否一致，不一致时拒绝保存并提示。
> - `AddaxJobService` 根据 `sourceTables` 列表循环生成多张表的 Reader/Writer，或在一个 Addax job 中包含多个
    content（推荐后者，便于统一事务和日志）。
>
> 对应 `SyncJob` 实体需同步增加以下字段：
> ```java
> private Integer readRateLimitMbps;
> private Integer writeRateLimitRowsPerSecond;
> private Integer rateLimitEnabled;
> ```

---

## 10. API 接口设计

### 10.1 Project API

| 方法   | 路径                     | 说明                                               |
|--------|--------------------------|----------------------------------------------------|
| GET    | `/dev/dag-projects`      | 分页查询（query params：`name`/`page`/`pageSize`） |
| GET    | `/dev/dag-projects/{id}` | 项目详情                                           |
| POST   | `/dev/dag-projects`      | 创建项目                                           |
| PUT    | `/dev/dag-projects/{id}` | 编辑项目                                           |
| DELETE | `/dev/dag-projects/{id}` | 删除项目（级联删除 DAG）                           |

> 经 Gateway 后实际路径为 `/api/engineering/dev/dag-projects/**`。当前实现为 GET 一次性返回项目列表（前端做假分页），与
> `SyncJobController` 的 `POST /page` 风格不同。

### 10.2 DAG API

| 方法   | 路径                                                   | 说明                                                            |
|--------|--------------------------------------------------------|-----------------------------------------------------------------|
| GET    | `/dev/dags`                                            | 列表查询（query param：`projectId`）；返回全部 DAG，前端假分页  |
| GET    | `/dev/dags/{id}`                                       | 详情（含节点和边）                                              |
| POST   | `/dev/dags`                                            | 创建 DAG                                                        |
| PUT    | `/dev/dags/{id}`                                       | 编辑 DAG                                                        |
| DELETE | `/dev/dags/{id}`                                       | 删除 DAG                                                        |
| POST   | `/dev/dags/{id}/trigger`                               | 手动触发一次 DAG 执行（支持参数覆盖）                           |
| POST   | `/dev/dags/{id}/schedule/start`                        | 启用 Cron 调度                                                  |
| POST   | `/dev/dags/{id}/schedule/stop`                         | 停用 Cron 调度                                                  |
| POST   | `/dev/dags/{id}/executions/{executionId}/stop`         | 停止指定执行实例                                                |
| POST   | `/dev/dags/{id}/executions/{executionId}/rerun-failed` | 重跑指定执行实例中失败/被跳过的节点                             |
| GET    | `/dev/dags/{id}/executions`                            | 单个 DAG 的执行历史列表                                         |
| POST   | `/dev/dags/{id}/nodes/{nodeId}/python/test`            | PYTHON 节点脚本测试（不注册元数据）                             |
| GET    | `/dev/dags/node-executions/{nodeExecutionId}/logs`     | SYNC 节点执行日志（按 `sync_job_history_id` 查 `sync_job_log`） |

> 经 Gateway 后实际路径为 `/api/engineering/dev/dags/**`。

### 10.3 Execution API（全局执行历史）

| 方法 | 路径                                                | 说明                                                                                                                                 |
|------|-----------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------|
| GET  | `/dag-executions`                                   | 全局执行历史（query params：`dagName`/`projectName`/`dagId`/`status`/`triggerType`/`startTimeFrom`/`startTimeTo`/`page`/`pageSize`） |
| GET  | `/dag-executions/{executionId}/nodes/{nodeId}/logs` | 节点实时日志（`node_execution_log`）                                                                                                 |

> **与代码对齐**：
> - 无独立的 `/dag-executions/{executionId}` 详情接口；全局列表返回的 `DagExecutionGlobalDto` 已包含 `nodeExecutions`。
> - 节点实时日志路径统一为 `/dag-executions/{executionId}/nodes/{nodeId}/logs`。
> - 单 DAG 维度的执行历史在 `DagController` 的 `GET /dev/dags/{id}/executions`。

```java
public class GlobalExecutionFilter {
    private String dagName;            // 按 DAG 名称模糊匹配
    private String projectName;        // 按所属项目名称模糊匹配
    private Long dagId;                // DAG id 精确过滤
    private String status;             // RUNNING / SUCCESS / FAILED / TERMINATED
    private String triggerType;        // MANUAL / CRON
    private String startTimeFrom;      // 执行时间起（ISO 8601，支持 Z 后缀 UTC）
    private String startTimeTo;        // 执行时间止
    private long page;
    private long pageSize;
}
```

> 经 Gateway 后实际路径为 `/api/engineering/dag-executions`。

### 10.4 SQL 编辑器 API

| 方法 | 路径               | 说明                     |
|------|--------------------|--------------------------|
| POST | `/dev/sql-preview` | 测试执行（不注册元数据） |

> **与代码对齐**：当前仅实现 `/dev/sql-preview`；`/dev/sql/format` 与 `/dev/sql/autocomplete` 尚未实现（前端 SQL 编辑器使用
> Monaco 自带语法高亮，无格式化按钮）。

### 10.5 DAG 参数、版本、告警 API（Sprint 3 已提前实现）

| 方法   | 路径                                              | 说明                                        |
|--------|---------------------------------------------------|---------------------------------------------|
| GET    | `/dev/dags/{dagId}/parameters`                    | 查询 DAG 参数列表                           |
| POST   | `/dev/dags/{dagId}/parameters`                    | 创建 DAG 参数                               |
| PUT    | `/dev/dags/{dagId}/parameters/{id}`               | 更新 DAG 参数                               |
| DELETE | `/dev/dags/{dagId}/parameters/{id}`               | 删除 DAG 参数                               |
| GET    | `/dev/dags/{dagId}/versions`                      | 查询 DAG 版本列表                           |
| GET    | `/dev/dags/{dagId}/versions/compare`              | 版本对比（`left`/`right` query params）     |
| POST   | `/dev/dags/{dagId}/versions/{versionNo}/rollback` | 回滚到指定版本                              |
| GET    | `/dev/alert-config`                               | 读取全局告警配置                            |
| PUT    | `/dev/alert-config`                               | 更新全局告警配置                            |
| GET    | `/dev/dags/{dagId}/alert-config`                  | 读取按 DAG 告警配置（无专用配置时回退全局） |
| PUT    | `/dev/dags/{dagId}/alert-config`                  | 更新按 DAG 告警配置                         |

### 10.6 内部回调 API（供 DS 调用）

| 方法 | 路径                            | 说明                |
|------|---------------------------------|---------------------|
| POST | `/dev/internal/sql/callback`    | DS SQL 任务回调     |
| POST | `/dev/internal/python/callback` | DS Python 任务回调  |
| POST | `/dev/internal/sync/callback`   | DS 同步任务触发回调 |

> **与代码对齐**：SYNC 节点不再提供 `/internal/sync/{historyId}/status` 轮询接口；执行状态由 `DagExecutionSyncService` 反查
> `sync_job_history` 表获得。内部 API 通过 IP 白名单或 Internal Token 鉴权，不暴露给前端。

---

## 11. 共享配置变更

新增 `shared-configs/shared-dolphinscheduler.yaml`：

```yaml
datanest:
  dolphinscheduler:
    # DS API Server 地址（Docker 内网）
    api-url: http://dolphinscheduler-api:12345/dolphinscheduler
    # DS 登录 Token（通过 DS Admin 创建，或启动脚本生成）
    token: ${DS_API_TOKEN:default_token}
    # DS 默认租户编码
    tenant-code: ${DS_TENANT_CODE:default}
    # 回调超时（秒）
    callback-timeout-seconds: 1800
```

engineering-service 的 `application.yml` 引入该配置：

```yaml
spring:
  config:
    import:
      - optional:nacos:shared-datasource.yaml?group=shared-configs&refreshEnabled=true
      - optional:nacos:shared-redis.yaml?group=shared-configs&refreshEnabled=true
      - optional:nacos:shared-security.yaml?group=shared-configs&refreshEnabled=true
      - optional:nacos:shared-mybatis.yaml?group=shared-configs&refreshEnabled=true
      - optional:nacos:shared-doris.yaml?group=shared-configs&refreshEnabled=true
      - optional:nacos:shared-xxljob.yaml?group=shared-configs&refreshEnabled=true
      - optional:nacos:shared-addax.yaml?group=shared-configs&refreshEnabled=true
      - optional:nacos:shared-dolphinscheduler.yaml?group=shared-configs&refreshEnabled=true
```

---

## 12. 前端设计

### 12.1 新增依赖

```bash
pnpm add reactflow @monaco-editor/react sql-formatter
```

或更新 `package.json`：

```json
{
  "dependencies": {
    "reactflow": "^11.11.4",
    "@monaco-editor/react": "^4.6.0",
    "sql-formatter": "^15.4.0"
  }
}
```

### 12.2 页面结构与路由

| 页面         | 路由                                            | 说明                                                                        |
|--------------|-------------------------------------------------|-----------------------------------------------------------------------------|
| 项目列表     | `/engineering/dags`                             | 搜索、新建/编辑/删除项目；页头标题为「数据开发」                            |
| DAG 列表     | `/engineering/dags/:projectId`                  | 某项目下的 DAG 列表；支持搜索、新建/编辑/执行/历史/删除 DAG                 |
| 新建 DAG     | `/engineering/dags/new?projectId=xxx`           | 全屏画布，创建新 DAG                                                        |
| DAG 画布     | `/engineering/dags/:id/edit`                    | 全屏画布，编辑 DAG；含节点拖拽、连线、属性面板、参数/版本/告警入口          |
| DAG 运行视图 | `/engineering/dags/:id/executions/:executionId` | 执行实例运行视图，展示节点状态、耗时、实时日志                              |
| DAG 执行历史 | `/engineering/dag-executions`                   | 全局 DAG 执行历史页面，支持状态/触发方式/时间范围筛选；点击详情跳转运行视图 |

> **与代码对齐**：当前路由已实现为 `/engineering/dags`、`/engineering/dags/:projectId`、`/engineering/dags/new`、
> `/engineering/dags/:id/edit`、`/engineering/dags/:id/executions/:executionId`，不再使用早期的
> `/engineering/dags/projects`、`:id/dags`、`:id/canvas`、`:id/running`。
> DAG 执行历史归入左侧导航「执行历史」分组，与「同步执行历史」「采集执行历史」并列。左侧「项目管理」菜单项位于「数据开发」分组下，路径为
> `/engineering/dags`。

```
data-nest-frontend/src/pages/engineering/dags/
├── index.tsx                    # 项目列表页
├── project.tsx                  # 某项目下的 DAG 列表页
├── Editor.tsx                   # DAG 画布/运行视图（复用同一组件）
├── api.ts                       # DAG/项目/执行/参数/版本/告警 API 封装
├── types.ts                     # 类型定义
├── components/
│   ├── SqlEditorModal.tsx       # SQL 任务编辑弹窗
│   ├── PythonEditorModal.tsx    # Python 任务编辑弹窗
│   ├── DagParameterDrawer.tsx   # 参数抽屉
│   ├── DagVersionModal.tsx      # 版本管理弹窗
│   ├── DagAlertConfigModal.tsx  # 告警配置弹窗
│   ├── TriggerParamsModal.tsx   # 触发参数覆盖弹窗
│   └── NodeRuntimeLogPanel.tsx  # 节点实时日志面板
└── dag-executions/
    └── index.tsx                # 全局执行历史页
```

### 12.3 菜单配置更新

参考当前 `Sidebar.tsx` 的扁平分组结构（`group + items`），Sprint 3 新增「项目管理」菜单，DAG 执行历史归入既有「执行历史」分组：

```ts
const allMenus: { group: string; items: MenuItem[] }[] = [
    {
        group: '数据平台',
        items: [{label: '首页', path: '/', icon: <Home size={18}/>}],
    },
    {
        group: '数据工程',
        items: [
            {label: '数据源管理', path: '/engineering/datasources', icon: <Database size={18}/>,
                roles: ['SUPER_ADMIN', 'DATA_ENGINEER', 'GOVERNANCE_ADMIN']},
            {label: '批量数据同步任务', path: '/engineering/sync-jobs', icon: <ArrowLeftRight size={18}/>,
                roles: ['SUPER_ADMIN', 'DATA_ENGINEER']},
        ],
    },
    {
        group: '数据开发',
        items: [
            {label: '项目管理', path: '/engineering/dags', icon: <Folder size={18}/>,  // 🆕
                roles: ['SUPER_ADMIN', 'DATA_ENGINEER', 'GOVERNANCE_ADMIN', 'DATA_ANALYST']},
        ],
    },
    {
        group: '数据治理',
        items: [
            {label: '元数据采集任务', path: '/governance/collect-tasks', icon: <Clock size={18}/>,
                roles: ['SUPER_ADMIN', 'GOVERNANCE_ADMIN']},
            {label: '元数据管理', path: '/governance/metadata', icon: <ClipboardList size={18}/>,
                roles: ['SUPER_ADMIN', 'GOVERNANCE_ADMIN', 'DATA_ENGINEER', 'DATA_ANALYST']},
            {label: '数据标准', path: '/governance/data-standards', icon: <Ruler size={18}/>,
                roles: ['SUPER_ADMIN', 'GOVERNANCE_ADMIN']},
        ],
    },
    {
        group: '执行历史',
        items: [
            {label: '同步执行历史', path: '/engineering/sync-job-history', icon: <History size={18}/>,
                roles: ['SUPER_ADMIN', 'DATA_ENGINEER']},
            {label: '采集执行历史', path: '/governance/collect-task-history', icon: <History size={18}/>,
                roles: ['SUPER_ADMIN', 'GOVERNANCE_ADMIN']},
            {label: 'DAG 执行历史', path: '/engineering/dag-executions', icon: <History size={18}/>,  // 🆕
                roles: ['SUPER_ADMIN', 'DATA_ENGINEER', 'GOVERNANCE_ADMIN', 'DATA_ANALYST']},
        ],
    },
    {
        group: '系统管理',
        items: [{label: '用户管理', path: '/system/users', icon: <UserCog size={18}/>,
            roles: ['SUPER_ADMIN']}],
    },
];
```

### 12.4 ReactFlow 节点类型

```tsx
// canvas/components/FlowCanvas.tsx
import ReactFlow, {Background, Controls, MiniMap, useNodesState, useEdgesState} from 'reactflow';
import 'reactflow/dist/style.css';

const nodeTypes = {
    sqlNode: SqlNode,
    syncNode: SyncNode,
};

export default function FlowCanvas({dagId}: { dagId: string }) {
    const [nodes, setNodes, onNodesChange] = useNodesState([]);
    const [edges, setEdges, onEdgesChange] = useEdgesState([]);

    // 加载 DAG 节点和边
    useEffect(() => {
        fetchDagDetail(dagId).then(data => {
            setNodes(data.nodes.map(toReactFlowNode));
            setEdges(data.edges.map(toReactFlowEdge));
        });
    }, [dagId]);

    return (
        <div style={{width: '100%', height: '100%'}}>
            <ReactFlow
                nodes={nodes}
                edges={edges}
                onNodesChange={onNodesChange}
                onEdgesChange={onEdgesChange}
                nodeTypes={nodeTypes}
                fitView
            >
                <Background/>
                <Controls/>
                <MiniMap/>
            </ReactFlow>
        </div>
    );
}
```

### 12.5 SQL 编辑器弹窗

```tsx
// canvas/components/SqlNodeModal.tsx
import Editor from '@monaco-editor/react';
import {format} from 'sql-formatter';

export default function SqlNodeModal({node, onSave, onTest}: Props) {
    const [sql, setSql] = useState(node.config.sqlContent || '');

    return (
        <Modal open title="编辑 SQL 任务" width={900}>
            <Input defaultValue={node.nodeName}/>
            <Editor
                height="400px"
                language="sql"
                value={sql}
                onChange={setSql}
                options={{minimap: {enabled: false}}}
            />
            <Button onClick={() => onTest(sql)}>运行测试</Button>
            <Button type="primary" onClick={() => onSave({...node, config: {sqlContent: sql}})}>
                保存
            </Button>
        </Modal>
    );
}
```

> **与代码对齐**：当前 SQL 编辑器未提供「格式化」按钮；运行测试调用 `POST /dev/sql-preview`。SELECT 语句执行后结果以表格形式展示在弹窗结果区。

### 12.6 执行状态实时刷新

```tsx
// canvas/hooks/useExecutionStatus.ts
import {useEffect, useState} from 'react';

export function useExecutionStatus(dagId: string, executionId?: string) {
    const [status, setStatus] = useState<string>();

    useEffect(() => {
        if (!executionId) return;
        const timer = setInterval(async () => {
            const res = await fetchExecutionDetail(dagId, executionId);
            setStatus(res.status);
            if (['SUCCESS', 'FAILED', 'TERMINATED'].includes(res.status)) {
                clearInterval(timer);
            }
        }, 3000);
        return () => clearInterval(timer);
    }, [dagId, executionId]);

    return status;
}
```

### 12.7 执行历史页面

全局执行历史页面路由为 `/engineering/dag-executions`，从左侧导航「执行历史 → DAG 执行历史」进入。接口为
`GET /dag-executions`（query params）。

**筛选条件**：

| 条件     | 说明                                  |
|----------|---------------------------------------|
| 所属 DAG | 按 DAG 名称模糊匹配                   |
| 状态     | 全部 / 运行中 / 成功 / 失败 / 已终止  |
| 触发方式 | 全部 / 手动触发 / 定时触发            |
| 执行时间 | 必填时间范围，精确到秒；默认最近 7 天 |

**表格字段**：执行时间、所属 DAG、执行方式、状态、耗时、节点执行情况、操作。

**操作：详情、日志、重跑失败节点**

- **详情**：点击后跳转至「运行视图」`/engineering/dags/:id/running?executionId=xxx`，在运行视图中查看完整节点拓扑、状态、耗时及实时日志。
- **日志**：运行视图内已支持 SQL/Python/SYNC 节点日志轮询（Sprint 3 已提前实现）。
- **重跑失败节点**：对失败记录调用 `POST /dags/{id}/executions/{executionId}/rerun-failed`。

> **与代码对齐**：当前执行历史页不再在行内展开「微缩 DAG 拓扑图」；节点拓扑与日志统一在运行视图展示。

---

## 13. Sprint 3 ADR

### ADR-S3-001：DAG 调度引擎 —— DolphinScheduler 3.4.2

| 项目         | 内容                                                                                                                                                                                 |
|--------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **状态**     | Accepted                                                                                                                                                                             |
| **上下文**   | Sprint 3 需要 DAG 可视化编排、依赖执行、Cron 调度、失败传播。当前已有 XXL-JOB 用于简单定时任务，但 XXL-JOB 不支持任务间 DAG 依赖。                                                   |
| **决策**     | **Apache DolphinScheduler 3.4.2** 作为 DAG 调度引擎。DataNest 前端自建 ReactFlow 画布，engineering-service 通过 DS REST API 同步流程定义；DS 负责 DAG 拓扑执行、定时调度、状态管理。 |
| **替代方案** | XXL-JOB + 自研 DAG 引擎（复用现有 infra，但需自研拓扑/并发/失败处理）；Airflow（生态强但依赖 Python/Scheduler 部署重）。                                                             |
| **后果**     | 📈 与架构文档 ADR-007 一致，获得生产级 DAG 能力；📉 需新增 DS Master/Worker/API/Alert 服务，本地部署变重；📉 前端画布模型与 DS 模型需要双向映射。                                    |

### ADR-S3-002：项目管理模块放置位置

| 项目         | 内容                                                                                                                                          |
|--------------|-----------------------------------------------------------------------------------------------------------------------------------------------|
| **状态**     | Accepted                                                                                                                                      |
| **上下文**   | 项目管理涉及 DAG 管理、SQL/Python 执行、同步任务引用，与数据工程（engineering）业务域高度相关。                                               |
| **决策**     | **不新建独立微服务**，DAG 核心领域模型下沉到 `data-nest-task-core`，API 层放在 `data-nest-engineering` 的 `dev` 包下。                        |
| **替代方案** | 新建 `data-nest-dev` 服务（独立部署但增加运维成本）。                                                                                         |
| **后果**     | 📈 复用 engineering 的 Doris/数据源连接能力，减少 RPC；📉 engineering 服务代码量增加，需通过包结构隔离 `datasource`、`sync`、`dev` 三个子域。 |

### ADR-S3-003：DS 任务执行方式

| 项目         | 内容                                                                                                                                   |
|--------------|----------------------------------------------------------------------------------------------------------------------------------------|
| **状态**     | Accepted                                                                                                                               |
| **上下文**   | DS 原生支持 SQL、Shell、HTTP 等任务类型。DataNest 需要统一处理 SQL 执行、同步任务触发、元数据注册。                                    |
| **决策**     | **DS 中所有 DataNest 节点均映射为 HTTP 任务**，执行时回调 engineering-service 内部接口。engineering-service 负责实际执行和元数据注册。 |
| **替代方案** | DS SQL 任务直连 Doris（失去元数据注册控制）；开发 DS 自定义任务插件（工作量大）。                                                      |
| **后果**     | 📈 DataNest 完全控制执行逻辑、超时、错误、元数据；📉 增加 DS Worker → engineering-service 网络调用，本地环境需保证网络可达。           |

---

## 14. 验收标准

### 14.1 功能验收

| #     | 验收项                 | 通过标准                                                         |
|-------|------------------------|------------------------------------------------------------------|
| AC-1  | 创建项目               | 进入项目管理 → 新建项目 → 列表出现该项目                         |
| AC-2  | 创建 DAG               | 进入项目 → 新建 DAG → 进入画布                                   |
| AC-3  | 添加 SQL 节点          | 拖入 SQL 节点 → 双击编辑 → 填写 SQL → 保存                       |
| AC-4  | 添加同步节点           | 拖入同步节点 → 选择已有 SyncJob → 保存                           |
| AC-5  | 连线依赖               | 节点 A 输出拖到节点 B 输入 → 创建依赖                            |
| AC-6  | DAG 保存同步到 DS      | 保存 DAG 后 DS 出现同名 ProcessDefinition 且已上线               |
| AC-7  | 手动执行 DAG           | 点击执行 → DS 生成流程实例 → 节点依次执行                        |
| AC-8  | Cron 定时执行          | 配置 Cron → DS 自动按时间触发                                    |
| AC-9  | 启用/停用 DAG          | 停用后 DS Schedule 下线，不再自动触发                            |
| AC-10 | 终止执行               | 运行中点击终止 → DS 流程实例 STOP                                |
| AC-11 | SQL 测试执行           | 弹窗点击运行测试 → 结果显示，不注册元数据                        |
| AC-12 | SQL 正式执行注册元数据 | DAG 执行成功后，CTAS 创建的新表出现在元数据管理                  |
| AC-13 | 多表同步               | 创建同步任务选择多个源表 → 执行后所有目标表存在                  |
| AC-14 | 速率限流               | 设置 5MB/s 读取限制 → Addax 实际速率不超过 5MB/s                 |
| AC-15 | 删除引用校验           | 删除被 DAG 引用的 SyncJob 时阻断并列出 DAG                       |
| AC-16 | 权限隔离               | 治理员/分析师只读，工程师可创建/编辑/执行                        |
| AC-17 | DAG 列表 Cron 表达式   | 定时 DAG 在列表中展示 Cron 表达式，手动 DAG 展示「—」            |
| AC-18 | 全局执行历史页面       | 左侧导航进入执行历史，支持按 DAG/状态/触发方式/时间范围筛选      |
| AC-19 | 执行历史运行视图       | 点击执行记录详情跳转运行视图，节点状态色与画布一致，支持实时日志 |

### 14.2 非功能验收

| #     | 验收项         | 通过标准                                   |
|-------|----------------|--------------------------------------------|
| NAC-1 | 画布性能       | 50 个节点拖拽/缩放无卡顿                   |
| NAC-2 | SQL 编辑器响应 | 1000 行 SQL 高亮 + 格式化 < 2s             |
| NAC-3 | DS 同步延迟    | DAG 保存后 3s 内 DS ProcessDefinition 上线 |
| NAC-4 | 状态回查延迟   | 节点状态变化后 5s 内前端刷新               |

---

## 15. 风险与对策

| #  | 风险                                          | 影响                         | 对策                                                                              |
|----|-----------------------------------------------|------------------------------|-----------------------------------------------------------------------------------|
| R1 | DolphinScheduler 首次部署复杂                 | 本地环境搭建困难             | 提供完整 docker-compose 片段；文档记录 DS 初始化 Token 获取方式                   |
| R2 | DS 与 DataNest 模型双向映射不一致             | 画布显示与 DS 执行行为不一致 | 保存时全量替换 DS ProcessDefinition；关键字段（节点名、依赖）强制校验             |
| R3 | DS HTTP 任务回调 engineering-service 网络不通 | DAG 节点执行失败             | Docker 同一网络下使用服务名 `data-nest-engineering:8082`；本地 dev 时配置回调 URL |
| R4 | 大量 DAG 实例同时触发压垮 DS                  | 调度中心不稳定               | Sprint 3 限制最大并行度为 3；后续根据规模扩容 DS Master/Worker                    |
| R5 | SQL 执行时间过长阻塞 DS Worker                | 单节点超时导致整体失败       | SQL HTTP 任务 socketTimeout 设置为 30 分钟；Doris 侧设置 query_timeout            |
| R6 | 同步任务被 DS 和 XXL-JOB 同时触发             | 数据重复或等待               | 任务级互斥：SyncJob 运行中状态全局可见，DS 触发时若冲突则等待或失败               |
| R7 | 前端 Monaco + ReactFlow 包体积增大            | 首屏加载变慢                 | 路由懒加载；webpack/vite splitChunks 分离编辑器 chunk                             |
| R8 | DS API Token 过期或失效                       | 同步失败                     | engineering-service 启动时自动登录 DS 获取 Token；失效时自动刷新                  |

---

## 附录：DS 与 DataNest 字段映射速查

| DataNest                               | DolphinScheduler                   | 说明                                    |
|----------------------------------------|------------------------------------|-----------------------------------------|
| `dag_project.name`                     | `t_ds_project.name`                | 项目一对一映射                          |
| `dag.name`                             | `t_ds_process_definition.name`     | DAG 一对一映射为流程定义                |
| `dag_node.node_id`                     | `t_ds_task_definition.code`        | 节点 code 由 DS 生成，DataNest 保存映射 |
| `dag_edge.source_node_id`              | `t_ds_task_relation.pre_task_code` | 依赖边映射                              |
| `dag.cron_expression`                  | `t_ds_schedules.crontab`           | Cron 映射                               |
| `dag_execution.ds_process_instance_id` | `t_ds_process_instance.id`         | 执行实例映射                            |
| `node_execution.ds_task_instance_id`   | `t_ds_task_instance.id`            | 节点执行映射                            |
