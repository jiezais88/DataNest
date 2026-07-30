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
7. [engineering-service：数据开发模块](#7-engineering-service数据开发模块)
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

| # | 工作项                          | 所属模块                             | 说明                                           |
|---|---------------------------------|--------------------------------------|------------------------------------------------|
| 1 | **DolphinScheduler 3.4.2 集成** | Docker Compose + engineering-service | 引入 DS 作为 DAG 调度与执行引擎                |
| 2 | **项目与 DAG 管理**             | engineering-service + task-core      | Project / DAG / Node / Edge CRUD               |
| 3 | **DAG → DS 流程映射**           | engineering-service                  | DataNest DAG 保存时同步为 DS ProcessDefinition |
| 4 | **SQL 任务执行**                | task-core + engineering-service      | 测试执行、正式执行、元数据注册                 |
| 5 | **同步任务节点**                | engineering-service                  | DAG 中引用已有 SyncJob，DS 通过 HTTP 回调触发  |
| 6 | **执行历史与节点状态**          | engineering-service + DS API         | 查询 DS 流程实例状态，回显到 DataNest 画布     |
| 7 | **多表批量同步**                | task-core                            | SyncJob 源表从单表扩展为多表                   |
| 8 | **同步速率限流**                | task-core + Addax                    | 读取 MB/s、写入 行/s 限流                      |
| 9 | **前端数据开发模块**            | data-nest-frontend                   | ReactFlow 画布 + Monaco SQL 编辑器             |

> **XXL-JOB 保留说明**：Sprint 1-2 的同步任务、采集任务独立调度仍由 XXL-JOB 负责；Sprint 3 的 DAG 编排由 DolphinScheduler
> 负责，DAG 中的同步任务节点通过 HTTP 回调触发 XXL-JOB 任务。

### 1.3 不在本 Sprint

| 暂缓项               | 后续 Sprint | 理由                      |
|----------------------|:-----------:|---------------------------|
| Python 任务节点      |  Sprint 4   | 需要沙箱执行环境          |
| 任务参数化           |  Sprint 4   | 参数传递与替换机制        |
| 条件分支 / 子 DAG    |  Sprint 5   | 控制流节点                |
| DAG 实时日志流       |  Sprint 5   | 需要 WebSocket / 日志采集 |
| DAG 失败告警         |  Sprint 5   | 告警通道配置              |
| SQL 血缘自动解析     |  Sprint 5   | 需要 SQL Parser + Neo4j   |
| 任务资源队列与优先级 |  Sprint 5   | 调度增强                  |

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

| #  | 交付物                                                                       | 类型 | 验收方式                       |
|----|------------------------------------------------------------------------------|------|--------------------------------|
| D1 | `data-nest-task-core` 新增 DAG 实体与 SQL 执行服务                           | 代码 | 编译通过                       |
| D2 | `data-nest-engineering` 新增 `dev` 包（Project / DAG / SQL API）             | 代码 | API 可用                       |
| D3 | DolphinScheduler 集成客户端（Java/HTTP）                                     | 代码 | 可创建/更新/触发/查询 DS 流程  |
| D4 | Flyway 迁移脚本 `V3.2.0__dag_tables.sql` + `V3.2.1__sync_job_multitable.sql` | 代码 | 启动自动建表                   |
| D5 | `docker-compose.yml` 新增 DolphinScheduler 服务                              | 配置 | `docker compose up -d` DS 健康 |
| D6 | `shared-configs` 新增 `shared-dolphinscheduler.yaml`                         | 配置 | Nacos 可见                     |
| D7 | 前端新增 `dev` 模块（ReactFlow + Monaco）                                    | 代码 | 页面可用                       |
| D8 | Gateway 路由新增 `/api/dev/**`（或复用 `/api/engineering/dev/**`）           | 配置 | 路由正确                       |

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
├── data-nest-engineering/            # 🆕 数据开发 API + DS 集成
│   └── src/main/java/com/datanest/engineering/
│       ├── dev/                      # 🆕 数据开发模块
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
└── data-nest-frontend/               # 🆕 数据开发页面
    └── src/pages/dev/
        ├── projects/
        ├── dags/
        └── canvas/
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

Sprint 3 数据开发 API 统一挂在 `engineering-service` 下，前端通过 `/api/engineering/dev/**` 访问。

```yaml
# gateway application.yml 无需新增路由
# /api/engineering/** → data-nest-engineering（已存在）
```

---

## 5. 架构关系图

### 5.1 整体数据流

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           DataNest Frontend                                  │
│  ┌──────────────┐  ┌──────────────┐  ┌─────────────────────────────────────┐│
│  │ 项目列表      │  │ DAG 列表      │  │ ReactFlow 画布                       ││
│  └──────────────┘  └──────────────┘  │ • SQL 任务节点                        ││
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
          │                │   ┌─────────────────────────────┐
          │                │   │  SQL Task → POST /engineering/dev/sql/execute  │
          │                │   │  Sync Task → POST /engineering/dev/sync/trigger│
          │                │   └─────────────────────────────┘
          │                │                │
          │                │                ▼
          │                │   ┌─────────────────────────────┐
          │                └──▶│  data-nest-engineering       │
          │                    │  • DorisSqlExecutor          │
          │                    │  • SyncJob trigger & poll    │
          │                    │  • Metadata registration     │
          │                    └─────────────────────────────┘
          │
          └────── 查询流程实例状态 ──────▶ 回显到前端画布
```

### 5.2 节点执行模型

| DataNest 节点类型 | DS 任务类型 | DS 任务内容                                   | 回调接口                     |
|-------------------|-------------|-----------------------------------------------|------------------------------|
| SQL 任务          | HTTP 任务   | POST `/engineering/dev/internal/sql/execute`  | engineering-service 内部接口 |
| 同步任务          | HTTP 任务   | POST `/engineering/dev/internal/sync/trigger` | 触发同步任务并轮询完成       |

> DS 任务以 HTTP 回调方式调用 engineering-service，engineering-service 负责任务实际执行、超时控制、错误处理和元数据注册。

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
@TableName(value = "dag_node", autoResultMap = true)
public class DagNode {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long dagId;
    private String nodeId;               // DAG 内唯一（前端生成 UUID）
    private String nodeName;
    private String nodeType;             // SQL / SYNC
    private Double positionX;
    private Double positionY;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private NodeConfig config;           // SQL 内容 / 同步任务 ID
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

## 7. engineering-service：数据开发模块

### 7.1 包结构

```
data-nest-engineering/src/main/java/com/datanest/engineering/
└── dev/
    ├── controller/
    │   ├── ProjectController.java
    │   ├── DagController.java
    │   └── SqlEditorController.java
    ├── service/
    │   ├── ProjectService.java
    │   ├── DagService.java
    │   ├── DagDsSyncService.java
    │   ├── DagExecutionService.java
    │   └── SqlEditorService.java
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
    │   └── SqlExecuteResultDTO.java
    └── converter/
        └── DagDsConverter.java
```

### 7.2 权限矩阵

| 接口                                           | SUPER_ADMIN | DATA_ENGINEER | GOVERNANCE_ADMIN | DATA_ANALYST |
|------------------------------------------------|:-----------:|:-------------:|:----------------:|:------------:|
| `GET /engineering/dev/projects/**`             |     ✅      |      ✅       |        ✅        |      ✅      |
| `POST/PUT/DELETE /engineering/dev/projects/**` |     ✅      |      ✅       |        ❌        |      ❌      |
| `GET /engineering/dev/dags/**`                 |     ✅      |      ✅       |        ✅        |      ✅      |
| `POST/PUT/DELETE /engineering/dev/dags/**`     |     ✅      |      ✅       |        ❌        |      ❌      |
| `POST /engineering/dev/dags/{id}/execute`      |     ✅      |      ✅       |        ❌        |      ❌      |
| `POST /engineering/dev/dags/{id}/terminate`    |     ✅      |      ✅       |        ❌        |      ❌      |
| `POST /engineering/dev/sql/execute`            |     ✅      |      ✅       |        ❌        |      ❌      |
| `POST /engineering/dev/sql/test`               |     ✅      |      ✅       |        ❌        |      ❌      |

> 所有接口通过 `@SaCheckRole` 控制，治理员和分析师仅只读。

### 7.3 ProjectController

```java

@RestController
@RequestMapping("/dev/projects")
public class ProjectController {

    private final ProjectService projectService;

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER", "GOVERNANCE_ADMIN", "DATA_ANALYST"}, mode = SaMode.OR)
    @PostMapping("/page")
    public Result<PageResult<ProjectDTO>> page(@RequestBody ProjectQueryRequest request) {
        return Result.ok(projectService.page(request));
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

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER", "GOVERNANCE_ADMIN", "DATA_ANALYST"}, mode = SaMode.OR)
    @PostMapping("/page")
    public Result<PageResult<DagDTO>> page(@RequestBody DagQueryRequest request) {
        return Result.ok(dagService.page(request));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping
    public Result<DagDTO> create(@Valid @RequestBody DagCreateRequest request) {
        return Result.ok(dagService.create(request));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PutMapping("/{id}")
    public Result<DagDTO> update(@PathVariable Long id,
                                 @Valid @RequestBody DagUpdateRequest request) {
        return Result.ok(dagService.update(id, request));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        dagService.delete(id);
        return Result.ok(null);
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/{id}/execute")
    public Result<DagExecutionDTO> execute(@PathVariable Long id) {
        return Result.ok(dagService.execute(id));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/{id}/terminate")
    public Result<Void> terminate(@PathVariable Long id) {
        dagService.terminate(id);
        return Result.ok(null);
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER", "GOVERNANCE_ADMIN", "DATA_ANALYST"}, mode = SaMode.OR)
    @PostMapping("/{id}/executions/page")
    public Result<PageResult<DagExecutionDTO>> executions(@PathVariable Long id,
                                                          @RequestBody ExecutionQueryRequest request) {
        return Result.ok(dagService.executionPage(id, request));
    }
}
```

### 7.5 DagService 核心逻辑

```java

@Service
public class DagService {

    private final DagMapper dagMapper;
    private final DagNodeMapper dagNodeMapper;
    private final DagEdgeMapper dagEdgeMapper;
    private final DagTopologyService dagTopologyService;
    private final DagDsSyncService dagDsSyncService;
    private final DagExecutionService dagExecutionService;

    @Transactional
    public DagDTO create(DagCreateRequest request) {
        // 1. 保存 DAG 基本定义
        Dag dag = convert(request);
        dagMapper.insert(dag);
        saveNodesAndEdges(dag.getId(), request.getNodes(), request.getEdges());

        // 2. 拓扑校验
        List<DagNode> nodes = dagNodeMapper.selectByDagId(dag.getId());
        List<DagEdge> edges = dagEdgeMapper.selectByDagId(dag.getId());
        dagTopologyService.validateAndSort(dag, nodes, edges);

        // 3. 同步到 DolphinScheduler
        dagDsSyncService.syncToDs(dag, nodes, edges);

        return toDTO(dag);
    }

    @Transactional
    public DagDTO update(Long id, DagUpdateRequest request) {
        Dag dag = dagMapper.selectById(id);
        if (dag == null) throw new BusinessException(ErrorCode.DAG_NOT_FOUND);

        applyUpdate(dag, request);
        dagMapper.updateById(dag);

        // 全量替换节点和边
        dagNodeMapper.deleteByDagId(id);
        dagEdgeMapper.deleteByDagId(id);
        saveNodesAndEdges(id, request.getNodes(), request.getEdges());

        List<DagNode> nodes = dagNodeMapper.selectByDagId(id);
        List<DagEdge> edges = dagEdgeMapper.selectByDagId(id);
        dagTopologyService.validateAndSort(dag, nodes, edges);

        // 同步到 DS（更新 ProcessDefinition）
        dagDsSyncService.syncToDs(dag, nodes, edges);

        return toDTO(dag);
    }

    public DagExecutionDTO execute(Long dagId) {
        Dag dag = dagMapper.selectById(dagId);
        if (dag == null) throw new BusinessException(ErrorCode.DAG_NOT_FOUND);
        if (!"ENABLED".equals(dag.getStatus())) {
            throw new BusinessException(ErrorCode.DAG_DISABLED, "DAG 已停用，无法执行");
        }
        return dagExecutionService.startManual(dag);
    }

    public void terminate(Long dagId) {
        DagExecution execution = dagExecutionService.getLatestRunning(dagId);
        if (execution == null) {
            throw new BusinessException(ErrorCode.NO_RUNNING_EXECUTION);
        }
        dagExecutionService.terminate(execution);
    }
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

### 7.7 同步任务触发与轮询

```java

@Service
public class DagSyncTriggerService {

    private final SyncJobMapper syncJobMapper;
    private final SyncJobExecutorService syncJobExecutorService;
    private final SyncJobHistoryMapper syncJobHistoryMapper;

    /**
     * DAG 中同步任务节点触发入口，由 DS HTTP 任务回调。
     */
    public SyncTriggerResult trigger(Long syncJobId, Long dagExecutionId, String nodeId) {
        SyncJob job = syncJobMapper.selectById(syncJobId);
        if (job == null) {
            throw new BusinessException(ErrorCode.SYNC_JOB_NOT_FOUND);
        }

        // 任务级互斥：同一 SyncJob 同时只能有一个实例运行
        if ("RUNNING".equals(job.getExecutionStatus())) {
            return SyncTriggerResult.waiting("同步任务正在独立调度执行中，等待完成");
        }

        Long historyId = syncJobExecutorService.start(syncJobId, "DAG", dagExecutionId);
        return SyncTriggerResult.started(historyId);
    }

    /**
     * DS 任务通过轮询确认同步任务是否完成。
     */
    public SyncPollResult poll(Long historyId) {
        SyncJobHistory history = syncJobHistoryMapper.selectById(historyId);
        if (history == null) {
            throw new BusinessException(ErrorCode.SYNC_HISTORY_NOT_FOUND);
        }
        return new SyncPollResult(history.getStatus(), history.getRowsWritten(),
                history.getErrorMessage());
    }
}
```

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
    "url": "http://data-nest-engineering:8082/engineering/dev/internal/sql/execute",
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

#### 同步任务节点映射

```json
{
  "name": "SYNC_日志数据同步",
  "taskType": "HTTP",
  "taskParams": {
    "url": "http://data-nest-engineering:8082/engineering/dev/internal/sync/trigger",
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
}
```

### 8.6 终止执行

```java
public void terminate(DagExecution execution) {
    Dag dag = dagMapper.selectById(execution.getDagId());
    dsClient.stopProcessInstance(dag.getDsProjectCode(), execution.getDsProcessInstanceId());
    execution.setStatus("TERMINATED");
    execution.setEndTime(LocalDateTime.now());
    dagExecutionMapper.updateById(execution);
}
```

---

## 9. 数据库设计

### 9.1 Flyway 迁移策略

沿用 Sprint 0-2 模式：所有迁移脚本集中在 `data-nest-system/src/main/resources/db/migration/`，Sprint 3 新增：

| 脚本                              | 版本     | 内容                                                                            |
|-----------------------------------|----------|---------------------------------------------------------------------------------|
| `V3.2.0__dag_tables.sql`          | Sprint 3 | `dag_project`、`dag`、`dag_node`、`dag_edge`、`dag_execution`、`node_execution` |
| `V3.2.1__sync_job_multitable.sql` | Sprint 3 | `sync_job` 扩展：读取/写入速率限制字段                                          |

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
    30
) NOT NULL,
    description VARCHAR
(
    200
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
    50
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
    50
) NOT NULL,
    node_type VARCHAR
(
    10
) NOT NULL, -- SQL / SYNC
    position_x DOUBLE PRECISION,
    position_y DOUBLE PRECISION,
    config TEXT, -- JSON: SqlNodeConfig / SyncNodeConfig
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
    50
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

| 方法   | 路径                             | 说明                     |
|--------|----------------------------------|--------------------------|
| POST   | `/engineering/dev/projects`      | 创建项目                 |
| PUT    | `/engineering/dev/projects/{id}` | 编辑项目                 |
| DELETE | `/engineering/dev/projects/{id}` | 删除项目（级联删除 DAG） |
| POST   | `/engineering/dev/projects/page` | 分页查询                 |

### 10.2 DAG API

| 方法   | 路径                                                        | 说明               |
|--------|-------------------------------------------------------------|--------------------|
| POST   | `/engineering/dev/dags`                                     | 创建 DAG           |
| PUT    | `/engineering/dev/dags/{id}`                                | 编辑 DAG           |
| DELETE | `/engineering/dev/dags/{id}`                                | 删除 DAG           |
| GET    | `/engineering/dev/dags/{id}`                                | 详情（含节点和边） |
| POST   | `/engineering/dev/dags/page`                                | 分页查询           |
| POST   | `/engineering/dev/dags/{id}/execute`                        | 手动执行           |
| POST   | `/engineering/dev/dags/{id}/terminate`                      | 终止执行           |
| POST   | `/engineering/dev/dags/{id}/executions/page`                | 执行历史           |
| GET    | `/engineering/dev/dags/{id}/executions/{executionId}/nodes` | 节点执行详情       |

### 10.3 SQL 编辑器 API

| 方法 | 路径                                | 说明                        |
|------|-------------------------------------|-----------------------------|
| POST | `/engineering/dev/sql/test`         | 测试执行（不注册元数据）    |
| POST | `/engineering/dev/sql/format`       | SQL 格式化                  |
| GET  | `/engineering/dev/sql/autocomplete` | 获取 Doris 库表字段补全候选 |

### 10.4 内部回调 API（供 DS 调用）

| 方法 | 路径                                                | 说明                |
|------|-----------------------------------------------------|---------------------|
| POST | `/engineering/dev/internal/sql/execute`             | DS SQL 任务回调     |
| POST | `/engineering/dev/internal/sync/trigger`            | DS 同步任务触发     |
| GET  | `/engineering/dev/internal/sync/{historyId}/status` | DS 同步任务状态轮询 |

> 内部 API 通过 IP 白名单或 Internal Token 鉴权，不暴露给前端。

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

### 12.2 页面结构

```
data-nest-frontend/src/pages/dev/
├── projects/
│   └── index.tsx          # 项目列表页
├── dags/
│   └── index.tsx          # 某项目下的 DAG 列表页
├── canvas/
│   ├── index.tsx          # DAG 画布页
│   ├── components/
│   │   ├── FlowCanvas.tsx # ReactFlow 画布
│   │   ├── NodePanel.tsx  # 左侧节点面板
│   │   ├── PropertyPanel.tsx # 右侧属性面板
│   │   ├── SqlNodeModal.tsx  # SQL 任务编辑弹窗
│   │   └── SyncNodeModal.tsx # 同步任务编辑弹窗
│   └── hooks/
│       ├── useDagNodes.ts
│       └── useDagEdges.ts
```

### 12.3 菜单配置更新

```ts
const menuConfig: Record<string, MenuItem[]> = {
    SUPER_ADMIN: [
        // ... 其他菜单
        {key: 'dev', label: '数据开发', path: '/dev/projects'},
    ],
    DATA_ENGINEER: [
        // ... 其他菜单
        {key: 'dev', label: '数据开发', path: '/dev/projects'},
    ],
    GOVERNANCE_ADMIN: [
        // ... 其他菜单
        {key: 'dev', label: '数据开发', path: '/dev/projects', readonly: true},
    ],
    DATA_ANALYST: [
        // ... 其他菜单
        {key: 'dev', label: '数据开发', path: '/dev/projects', readonly: true},
    ],
};
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

    const handleFormat = () => {
        setSql(format(sql, {language: 'mysql'}));
    };

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
            <Button onClick={handleFormat}>格式化</Button>
            <Button type="primary" onClick={() => onSave({...node, config: {sqlContent: sql}})}>
                保存
            </Button>
        </Modal>
    );
}
```

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

### ADR-S3-002：数据开发模块放置位置

| 项目         | 内容                                                                                                                                          |
|--------------|-----------------------------------------------------------------------------------------------------------------------------------------------|
| **状态**     | Accepted                                                                                                                                      |
| **上下文**   | 数据开发涉及 DAG 管理、SQL 执行、同步任务引用，与数据工程（engineering）业务域高度相关。                                                      |
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

| #     | 验收项                 | 通过标准                                           |
|-------|------------------------|----------------------------------------------------|
| AC-1  | 创建项目               | 进入数据开发 → 新建项目 → 列表出现该项目           |
| AC-2  | 创建 DAG               | 进入项目 → 新建 DAG → 进入画布                     |
| AC-3  | 添加 SQL 节点          | 拖入 SQL 节点 → 双击编辑 → 填写 SQL → 保存         |
| AC-4  | 添加同步节点           | 拖入同步节点 → 选择已有 SyncJob → 保存             |
| AC-5  | 连线依赖               | 节点 A 输出拖到节点 B 输入 → 创建依赖              |
| AC-6  | DAG 保存同步到 DS      | 保存 DAG 后 DS 出现同名 ProcessDefinition 且已上线 |
| AC-7  | 手动执行 DAG           | 点击执行 → DS 生成流程实例 → 节点依次执行          |
| AC-8  | Cron 定时执行          | 配置 Cron → DS 自动按时间触发                      |
| AC-9  | 启用/停用 DAG          | 停用后 DS Schedule 下线，不再自动触发              |
| AC-10 | 终止执行               | 运行中点击终止 → DS 流程实例 STOP                  |
| AC-11 | SQL 测试执行           | 弹窗点击运行测试 → 结果显示，不注册元数据          |
| AC-12 | SQL 正式执行注册元数据 | DAG 执行成功后，CTAS 创建的新表出现在元数据管理    |
| AC-13 | 多表同步               | 创建同步任务选择多个源表 → 执行后所有目标表存在    |
| AC-14 | 速率限流               | 设置 5MB/s 读取限制 → Addax 实际速率不超过 5MB/s   |
| AC-15 | 删除引用校验           | 删除被 DAG 引用的 SyncJob 时阻断并列出 DAG         |
| AC-16 | 权限隔离               | 治理员/分析师只读，工程师可创建/编辑/执行          |

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
