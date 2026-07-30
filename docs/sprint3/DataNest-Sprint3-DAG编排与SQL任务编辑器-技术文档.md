# DataNest Sprint 3 技术文档

> **Sprint**：Sprint 3 — DAG 编排与 SQL 任务编辑器
> **文档状态**：Working Draft (v1.0) | **作者**：软件架构师 | **日期**：2026-07-29
> **关联文档**：`DataNest-技术架构文档-v1.0.md`、`DataNest-Sprint3-DAG编排与SQL任务编辑器-PRD.md`、
> `DataNest-Sprint2-技术文档.md`

---

## 目录

1. [Sprint 概述](#1-sprint-概述)
2. [交付物清单](#2-交付物清单)
3. [架构概览](#3-架构概览)
4. [项目结构变更](#4-项目结构变更)
5. [Docker Compose 变更](#5-docker-compose-变更)
6. [engineering-service：DAG 编排](#6-engineering-servicedag-编排)
7. [engineering-service：SQL 任务编辑器](#7-engineering-servicesql-任务编辑器)
8. [engineering-service：DAG 执行引擎](#8-engineering-servicedag-执行引擎)
9. [engineering-service：多表批量同步增强](#9-engineering-service多表批量同步增强)
10. [engineering-service：同步速率限流](#10-engineering-service同步速率限流)
11. [governance-service：SQL 产出元数据自动注册](#11-governance-servicesql-产出元数据自动注册)
12. [数据库设计](#12-数据库设计)
13. [API 接口设计](#13-api-接口设计)
14. [共享配置变更](#14-共享配置变更)
15. [前端设计](#15-前端设计)
16. [Sprint 3 ADR](#16-sprint-3-adr)
17. [验收标准](#17-验收标准)
18. [风险与对策](#18-风险与对策)

---

## 1. Sprint 概述

### 1.1 Sprint 目标

Sprint 2 完成了数据的"搬运"——把外部数据源的表同步到内置 Doris。Sprint 3 在此基础上做两件事：

1. **把同步和加工串成流水线**：在可视化 DAG 画布上拖拽 SQL 任务节点和同步任务节点，连线定义依赖，实现按依赖顺序自动执行。
2. **让工程师在平台内写 SQL 加工数据**：内置 SQL 编辑器，执行目标为内置 Doris，SQL 任务产出新表自动注册到元数据管理。

同时 Sprint 3 还包含 Sprint 2 的两项增强：多表批量同步、同步速率限流。

### 1.2 Sprint 范围

| #  | 工作项                     | 所属服务            | 说明                                                      |
|----|----------------------------|---------------------|-----------------------------------------------------------|
| 1  | **Project 管理**           | engineering-service | DAG 顶层分组容器：项目列表、新建/编辑/删除                |
| 2  | **DAG 编排**               | engineering-service | ReactFlow 画布，节点拖拽、连线、自动布局、未保存拦截      |
| 3  | **SQL 任务编辑器**         | engineering-service | Monaco 编辑器，Doris SQL 方言，语法高亮、格式化、测试执行 |
| 4  | **DAG 执行引擎**           | engineering-service | 拓扑排序、依赖触发、并行执行、终止执行、执行历史          |
| 5  | **定时调度集成**           | engineering-service | XXL-JOB Cron 调度，启用/停用开关                          |
| 6  | **多表批量同步**           | engineering-service | 一个同步任务可选多个源表，批量目标表映射                  |
| 7  | **同步速率限流**           | engineering-service | 读取 MB/s、写入 行/s 上限配置                             |
| 8  | **同步任务被 DAG 引用**    | engineering-service | 删除校验、变更感知、任务级互斥                            |
| 9  | **SQL 产出自动注册元数据** | governance-service  | CTAS/CREATE TABLE 后自动写入 metadata_table/column        |
| 10 | **引用关系表**             | 数据库              | dag_sync_task_ref 记录同步任务被 DAG 引用关系             |

### 1.3 架构服务关系

```
engineering-service (8082)              governance-service (8084)
├── datasource/     # 数据源管理        ├── metadata/      # 元数据管理
├── sync/           # 批量同步          ├── collect/       # 采集任务
│   ├── task/       #   任务 CRUD       └── standard/      # 数据标准
│   ├── addax/      #   Addax 引擎
│   └── schedule/   #   XXL-JOB 调度
core ── data-nest-task-core
├── dev/            # 🆕 DAG 编排核心
│   ├── project/    #   项目管理
│   ├── dag/        #   DAG 定义与执行
│   └── sql/        #   SQL 任务执行
└── entity/         #   Project/DAG/Node/Edge/Execution

共享 PostgreSQL（同一 Schema）
├── dev_project                 🆕 engineering 读写
├── dev_dag                     🆕 engineering 读写
├── dev_dag_node                🆕 engineering 读写
├── dev_dag_edge                🆕 engineering 读写
├── dev_dag_execution           🆕 engineering 读写
├── dev_node_execution          🆕 engineering 读写
├── dag_sync_task_ref           🆕 engineering 读写
├── sync_task                   ← Sprint 2 已有， engineering 读写
├── sync_history                ← Sprint 2 已有
├── metadata_table              ← governance 采集写 / engineering 同步后写 / SQL 执行后写
└── metadata_column             ← 同上
```

> 两服务共用同一 PostgreSQL 数据库同一 Schema，通过 MyBatis-Plus Mapper 直接读写表。公共能力（密码加解密、JDBC 连接、Addax
> 执行）在 `data-nest-common` 和 `data-nest-task-core` 模块。

### 1.4 不在本 Sprint

| 暂缓项                   | 后续 Sprint |
|--------------------------|:-----------:|
| Python 任务节点          |  Sprint 4   |
| 任务参数化配置           |  Sprint 4   |
| 条件分支节点（if/else）  |  Sprint 5   |
| 子 DAG                   |  Sprint 5   |
| DAG 运行实时日志流式展示 |  Sprint 5   |
| DAG 失败告警通知         |  Sprint 5   |
| SQL 血缘自动解析和上报   |  Sprint 5   |
| DAG 版本管理             |  Sprint 5   |
| 任务资源队列与优先级     |  Sprint 5   |

---

## 2. 交付物清单

| #  | 交付物                                     | 类型 | 验收方式                                   |
|----|--------------------------------------------|------|--------------------------------------------|
| D1 | engineering-service `dev/` 模块            | 代码 | Project/DAG/Node/Edge CRUD + 画布数据接口  |
| D2 | engineering-service `DagExecutionEngine`   | 代码 | 拓扑排序、依赖触发、并行执行、终止         |
| D3 | engineering-service `SqlExecutor`          | 代码 | 内置 Doris SQL 执行、结果解析、错误处理    |
| D4 | engineering-service `sync/` 增强           | 代码 | 多表选择 + 批量目标映射 + 速率限流         |
| D5 | Flyway 迁移 V4.0.0 ~ V4.0.3                | 代码 | dev 相关表 + 引用关系表 + sync_task 加字段 |
| D6 | `docker-compose.yml` 路由/依赖更新         | 配置 | gateway 路由、服务启动顺序                 |
| D7 | shared-configs 新增 `shared-dag.yaml`      | 配置 | DAG 默认并行度、单节点超时                 |
| D8 | 前端：数据开发 / DAG 画布 / SQL 编辑器页面 | 代码 | 按 PRD 交互可用                            |
| D9 | governance-service 元数据自动注册增强      | 代码 | SQL 任务 CTAS/CREATE TABLE 后自动注册      |

---

## 3. 架构概览

### 3.1 DAG 编排数据流

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         DAG Orchestration Data Flow                         │
│                                                                             │
│  用户进入「数据开发」                                                         │
│       │                                                                      │
│       ▼                                                                      │
│  ┌─────────────────┐    新建/编辑    ┌─────────────────────────────────┐    │
│  │ Project 列表     │──────────────▶│ DAG 画布（ReactFlow）             │    │
│  └─────────────────┘                 │  • 拖拽 SQL/Sync 节点             │    │
│       │                              │  • 连线定义依赖                   │    │
│       ▼                              │  • 自动布局 / 快捷键              │    │
│  ┌─────────────────┐    保存         │  • 未保存拦截                     │    │
│  │ DAG 列表         │◀──────────────│  • 启用/停用 / Cron               │    │
│  └─────────────────┘                 └─────────────────────────────────┘    │
│       │                              │                                       │
│       ▼ 执行                         ▼ 保存                                  │
│  ┌───────────────────────────────────────┐    ┌─────────────────────────┐   │
│  │ DAGExecutionEngine                     │    │ PostgreSQL dev_* tables │   │
│  │ 1. 拓扑排序（检测循环依赖）              │    │                         │   │
│  │ 2. 无依赖节点率先执行                    │    │ dev_project             │   │
│  │ 3. 上游成功触发下游                      │    │ dev_dag                 │   │
│  │ 4. 多无依赖节点并行（默认 max=3）         │    │ dev_dag_node            │   │
│  │ 5. 上游失败下游标记 SKIP                 │    │ dev_dag_edge            │   │
│  │ 6. 支持终止执行                          │    │ dev_dag_execution       │   │
│  └───────────────────────────────────────┘    │ dev_node_execution      │   │
│       │                                        └─────────────────────────┘   │
│       ▼                                                                      │
│  ┌──────────────────────┐    SQL 节点         ┌─────────────────────────┐   │
│  │ Node Executor        │──────────────────▶│ SqlExecutor             │   │
│  │ SQL / SYNC           │                     │  → 内置 Doris           │   │
│  │                      │──────────────────▶│ SyncTaskHandler         │   │
│  │                      │    SYNC 节点        │  → Addax / XXL-JOB      │   │
│  └──────────────────────┘                     └─────────────────────────┘   │
│       │                                                                      │
│       ▼ 执行成功                                                             │
│  ┌───────────────────────────────────┐                                       │
│  │ 元数据自动注册                       │                                       │
│  │ SQL CTAS/CREATE TABLE → metadata  │                                       │
│  └───────────────────────────────────┘                                       │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 3.2 DAG 定义与运行时状态分离

核心原则： **DAG 定义与运行时状态分离**。

- `dev_dag` / `dev_dag_node` / `dev_dag_edge` 只保存静态定义。
- 每次执行生成一条 `dev_dag_execution` 记录，节点状态写入 `dev_node_execution`。
- 多实例并发执行时互不覆盖状态。

### 3.3 SQL 任务执行路径

```
用户点击「执行」
    │
    ▼
DagExecutionEngine 调度 SQL 节点
    │
    ▼
SqlExecutor.execute(sqlContent)
    ├── 拆分多语句（按分号）
    ├── 逐条提交到内置 Doris JDBC
    ├── 收集每条语句结果（影响行数、错误、是否创建表）
    └── 返回 NodeExecutionResult
    │
    ▼
若执行成功且包含 CREATE TABLE / CTAS
    └── MetadataRegistrar.register(sqlNode, createdTables)
```

### 3.4 同步任务节点执行路径

```
用户点击「执行」
    │
    ▼
DagExecutionEngine 调度 SYNC 节点
    │
    ▼
SyncNodeExecutor.execute(syncTaskId)
    ├── 校验同步任务存在
    ├── 任务级互斥锁：同一同步任务同一时刻只能一个实例运行
    ├── 调用 SyncTaskService.trigger(syncTaskId)
    │       └── XXL-JOB trigger / 或直接 Addax 执行
    └── 等待执行结果，回填 NodeExecutionResult
```

---

## 4. 项目结构变更

### 4.1 engineering-service 新增

```
data-nest-engineering/
├── src/main/java/com/datanest/engineering/
│   ├── EngineeringApplication.java
│   ├── datasource/                       # Sprint 1 已有
│   ├── sync/                             # Sprint 2 已有（本 Sprint 增强）
│   │   ├── controller/SyncTaskController.java
│   │   ├── service/SyncTaskService.java
│   │   ├── service/AddaxJobBuilder.java
│   │   ├── service/AddaxExecutor.java
│   │   └── ...
│   └── dev/                              # 🆕 数据开发 / DAG 编排
│       ├── controller/
│       │   ├── ProjectController.java
│       │   ├── DagController.java
│       │   ├── DagExecutionController.java
│       │   └── SqlTaskController.java
│       ├── service/
│       │   ├── ProjectService.java
│       │   ├── DagService.java           # DAG CRUD + 画布数据
│       │   ├── DagValidator.java         # 循环依赖 / 孤立节点校验
│       │   ├── DagLayoutService.java     # 自动布局算法
│       │   ├── DagExecutionEngine.java   # DAG 执行调度核心
│       │   ├── SqlExecutor.java          # Doris SQL 执行
│       │   ├── SyncNodeExecutor.java     # DAG 中同步节点执行
│       │   └── DagHistoryService.java    # 执行历史查询
│       ├── scheduler/
│       │   ├── DagSchedulerService.java  # XXL-JOB 注册/触发
│       │   └── DagJobHandler.java        # @XxlJob("dagJobHandler")
│       ├── mapper/
│       │   ├── ProjectMapper.java
│       │   ├── DagMapper.java
│       │   ├── DagNodeMapper.java
│       │   ├── DagEdgeMapper.java
│       │   ├── DagExecutionMapper.java
│       │   ├── NodeExecutionMapper.java
│       │   └── DagSyncTaskRefMapper.java
│       └── entity/
│           ├── Project.java
│           ├── Dag.java
│           ├── DagNode.java
│           ├── DagEdge.java
│           ├── DagExecution.java
│           ├── NodeExecution.java
│           └── DagSyncTaskRef.java
```

### 4.2 data-nest-task-core 新增

```
data-nest-task-core/
└── src/main/java/com/datanest/task/core/
    ├── dev/                               # 🆕 DAG 共享实体/DTO
    │   ├── dto/
    │   │   ├── DagCreateRequest.java
    │   │   ├── DagUpdateRequest.java
    │   │   ├── DagNodeDTO.java
    │   │   ├── DagEdgeDTO.java
    │   │   ├── SqlExecuteRequest.java
    │   │   └── SqlExecuteResult.java
    │   └── enums/
    │       ├── DagStatus.java
    │       ├── NodeStatus.java
    │       ├── NodeType.java
    │       └── TriggerType.java
    └── sql/                               # 🆕 SQL 执行公共能力
        ├── SqlParser.java                 # 解析 CREATE TABLE / CTAS / INSERT
        └── SqlMetadataExtractor.java      # 从 SQL 中提取目标库表名
```

### 4.3 governance-service 变更

```
data-nest-governance/
└── src/main/java/com/datanest/governance/
    └── metadata/
        └── service/
            └── MetadataRegistrar.java     # 已有（Sprint 2）增强 SQL 产出注册
```

### 4.4 common 模块新增

```
data-nest-common/
└── src/main/java/com/datanest/common/
    └── util/
        └── DagUtil.java                   # 拓扑排序、循环检测工具
```

### 4.5 Root POM 变更

无新增顶层模块。engineering-service 新增依赖：

```xml
<!-- data-nest-engineering/pom.xml 🆕 -->
<dependency>
    <groupId>com.datanest</groupId>
    <artifactId>data-nest-task-core</artifactId>
</dependency>
```

---

## 5. Docker Compose 变更

### 5.1 服务启动顺序

Sprint 3 不新增独立容器。engineering-service 继续内嵌 Addax，新增 DAG 调度能力。

```
nacos-mysql → nacos → postgres → xxl-job-admin → system → worker → engineering(含 Addax) → governance → gateway → frontend
```

### 5.2 Gateway 路由

新增 `/api/dev/**` 路由到 engineering-service：

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: engineering-dev
          uri: lb://engineering-service
          predicates:
            - Path=/api/dev/**
          filters:
            - StripPrefix=0
```

> 前端「数据开发」模块所有接口统一走 `/api/dev/**`。

---

## 6. engineering-service：DAG 编排

### 6.1 职责

| 功能         | 接口                                      | 鉴权                                              |
|--------------|-------------------------------------------|---------------------------------------------------|
| 项目列表     | `GET /api/dev/projects`                   | SUPER_ADMIN / DATA_ENGINEER / GOV_ADMIN / ANALYST |
| 创建项目     | `POST /api/dev/projects`                  | SUPER_ADMIN / DATA_ENGINEER                       |
| 编辑项目     | `PUT /api/dev/projects/{id}`              | SUPER_ADMIN / DATA_ENGINEER                       |
| 删除项目     | `DELETE /api/dev/projects/{id}`           | SUPER_ADMIN / DATA_ENGINEER                       |
| DAG 列表     | `GET /api/dev/projects/{id}/dags`         | 所有数据开发可查看角色                            |
| 创建 DAG     | `POST /api/dev/projects/{id}/dags`        | SUPER_ADMIN / DATA_ENGINEER                       |
| 编辑 DAG     | `PUT /api/dev/dags/{id}`                  | SUPER_ADMIN / DATA_ENGINEER                       |
| 删除 DAG     | `DELETE /api/dev/dags/{id}`               | SUPER_ADMIN / DATA_ENGINEER                       |
| 获取画布数据 | `GET /api/dev/dags/{id}/canvas`           | 所有数据开发可查看角色                            |
| 保存画布数据 | `PUT /api/dev/dags/{id}/canvas`           | SUPER_ADMIN / DATA_ENGINEER                       |
| 执行 DAG     | `POST /api/dev/dags/{id}/execute`         | SUPER_ADMIN / DATA_ENGINEER                       |
| 终止执行     | `POST /api/dev/executions/{id}/terminate` | SUPER_ADMIN / DATA_ENGINEER                       |
| 执行历史     | `GET /api/dev/dags/{id}/history`          | 所有数据开发可查看角色                            |
| 全局执行历史 | `GET /api/dev/executions/history`         | 所有数据开发可查看角色                            |

> 执行历史支持查询参数：DAG 名称、状态、触发方式、执行时间起止范围。

### 6.2 核心实体

```java
@Data
@TableName("dev_project")
public class Project {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String name;                // 全局唯一，3-30 位
    private String description;         // 最多 200 字
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

@Data
@TableName("dev_dag")
public class Dag {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long projectId;
    private String name;                // 项目内唯一，3-50 位
    private String triggerType;         // MANUAL / CRON
    private String cronExpression;      // Cron 表达式
    private Boolean scheduleEnabled;    // 调度启用/停用
    private Integer maxParallelism;     // 默认 3
    private String status;              // PENDING / RUNNING / SUCCESS / FAILED
    private LocalDateTime lastExecutedAt;
    private LocalDateTime nextExecutionTime;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

@Data
@TableName("dev_dag_node")
public class DagNode {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long dagId;
    private String nodeId;              // 前端生成的唯一 ID
    private String nodeName;            // DAG 内唯一
    private String nodeType;            // SQL / SYNC
    private Double positionX;
    private Double positionY;
    private String config;              // JSON：SQL 内容 / syncTaskId
}

@Data
@TableName("dev_dag_edge")
public class DagEdge {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long dagId;
    private String edgeId;              // 前端生成的唯一 ID
    private String sourceNodeId;
    private String targetNodeId;
}

@Data
@TableName("dev_dag_execution")
public class DagExecution {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long dagId;
    private String triggerType;         // MANUAL / CRON
    private String status;              // RUNNING / SUCCESS / FAILED / TERMINATED
    private LocalDateTime startTime;
    private LocalDateTime endTime;
}

@Data
@TableName("dev_node_execution")
public class NodeExecution {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long executionId;
    private String nodeId;
    private String status;              // WAITING / RUNNING / SUCCESS / FAILED / SKIPPED
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String errorMessage;
    private String outputSummary;       // 影响行数、创建表名等
}

@Data
@TableName("dag_sync_task_ref")
public class DagSyncTaskRef {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long dagId;
    private Long syncTaskId;
    private String nodeId;
}
```

### 6.3 DAG 校验

```java
@Component
public class DagValidator {

    /**
     * 保存前校验：
     * 1. 至少包含 1 个节点
     * 2. 不存在与执行起点完全孤立的节点
     * 3. 无循环依赖
     */
    public void validate(Dag dag, List<DagNode> nodes, List<DagEdge> edges) {
        if (nodes == null || nodes.isEmpty()) {
            throw new BizException("DAG 至少包含 1 个节点");
        }

        // 孤立节点检测：从没有入边且没有出边的节点开始 BFS/DFS
        Set<String> reachable = computeReachable(nodes, edges);
        for (DagNode node : nodes) {
            if (!reachable.contains(node.getNodeId())) {
                throw new BizException("存在孤立节点：" + node.getNodeName());
            }
        }

        // 循环依赖检测
        if (hasCycle(nodes, edges)) {
            throw new BizException("DAG 存在循环依赖，请检查连线");
        }
    }

    private boolean hasCycle(List<DagNode> nodes, List<DagEdge> edges) {
        // Kahn 算法或 DFS 三色标记
        Map<String, Integer> inDegree = new HashMap<>();
        nodes.forEach(n -> inDegree.put(n.getNodeId(), 0));
        edges.forEach(e -> inDegree.merge(e.getTargetNodeId(), 1, Integer::sum));

        Queue<String> queue = new LinkedList<>();
        inDegree.entrySet().stream()
                .filter(e -> e.getValue() == 0)
                .forEach(e -> queue.offer(e.getKey()));

        int processed = 0;
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            processed++;
            for (DagEdge edge : edges) {
                if (edge.getSourceNodeId().equals(cur)) {
                    int deg = inDegree.merge(edge.getTargetNodeId(), -1, Integer::sum);
                    if (deg == 0) queue.offer(edge.getTargetNodeId());
                }
            }
        }
        return processed != nodes.size();
    }
}
```

### 6.4 自动布局

```java
@Service
public class DagLayoutService {

    /**
     * 基于拓扑层级自动排列节点。
     * 同层级节点水平排列，层级之间垂直排列。
     */
    public List<DagNode> autoLayout(List<DagNode> nodes, List<DagEdge> edges) {
        Map<String, Integer> levels = computeLevels(nodes, edges);
        Map<Integer, List<DagNode>> groups = new TreeMap<>();
        nodes.forEach(n -> groups.computeIfAbsent(levels.get(n.getNodeId()), k -> new ArrayList<>()).add(n));

        double y = 80;
        for (Map.Entry<Integer, List<DagNode>> entry : groups.entrySet()) {
            double x = 80;
            for (DagNode node : entry.getValue()) {
                node.setPositionX(x);
                node.setPositionY(y);
                x += 240;
            }
            y += 160;
        }
        return nodes;
    }

    private Map<String, Integer> computeLevels(List<DagNode> nodes, List<DagEdge> edges) {
        Map<String, Integer> level = new HashMap<>();
        nodes.forEach(n -> level.put(n.getNodeId(), 0));
        boolean changed;
        do {
            changed = false;
            for (DagEdge edge : edges) {
                int newLevel = level.get(edge.getSourceNodeId()) + 1;
                if (newLevel > level.get(edge.getTargetNodeId())) {
                    level.put(edge.getTargetNodeId(), newLevel);
                    changed = true;
                }
            }
        } while (changed);
        return level;
    }
}
```

---

## 7. engineering-service：SQL 任务编辑器

### 7.1 职责

| 功能             | 接口                                                 | 鉴权                        |
|------------------|------------------------------------------------------|-----------------------------|
| SQL 测试执行     | `POST /api/dev/sql/execute-test`                     | SUPER_ADMIN / DATA_ENGINEER |
| SQL 语法提示候选 | `GET /api/dev/sql/completion?dagId={id}&nodeId={id}` | SUPER_ADMIN / DATA_ENGINEER |

### 7.2 SQL 执行

```java
@Service
public class SqlExecutor {

    @Value("${datanest.dag.sql-timeout-seconds:1800}")
    private int timeoutSeconds;

    private final JdbcTemplate jdbcTemplate;  // 内置 Doris DataSource
    private final SqlParser sqlParser;

    /**
     * 执行 SQL 内容（多条语句按分号拆分）。
     * 返回每条语句的执行结果，不触发元数据自动注册。
     */
    public SqlExecuteResult executeTest(String sqlContent) {
        List<String> statements = sqlParser.split(sqlContent);
        SqlExecuteResult result = new SqlExecuteResult();
        for (String stmt : statements) {
            if (StringUtils.isBlank(stmt)) continue;
            try {
                SqlStatementInfo info = sqlParser.analyze(stmt);
                int affected = jdbcTemplate.update(stmt);  // 简化示意，Doris 可能返回 0
                result.addSuccess(info.getType(), affected, info.getTargetTable());
            } catch (Exception e) {
                result.addFailure(sqlParser.detectType(stmt), e.getMessage());
                break;  // 多语句遇到错误停止
            }
        }
        return result;
    }
}
```

### 7.3 SQL 解析与元数据注册

```java
@Component
public class SqlParser {

    /**
     * 拆分多语句。注意：分号可能出现在字符串/注释中，生产环境需用 JSqlParser 等工具。
     */
    public List<String> split(String sqlContent) {
        // 简化实现；生产环境推荐 JSqlParser Statements 解析
        return Arrays.stream(sqlContent.split(";"))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toList());
    }

    /**
     * 识别语句类型并提取目标表。
     */
    public SqlStatementInfo analyze(String sql) {
        String upper = sql.toUpperCase(Locale.ROOT);
        SqlStatementInfo info = new SqlStatementInfo();
        if (upper.startsWith("SELECT")) {
            info.setType("SELECT");
        } else if (upper.startsWith("CREATE TABLE")) {
            info.setType("CREATE");
            info.setTargetTable(extractCreateTable(sql));
        } else if (upper.startsWith("INSERT")) {
            info.setType("INSERT");
            info.setTargetTable(extractInsertTable(sql));
        } else if (upper.startsWith("DROP TABLE")) {
            info.setType("DROP");
            info.setTargetTable(extractDropTable(sql));
        } else if (upper.startsWith("ALTER TABLE")) {
            info.setType("ALTER");
            info.setTargetTable(extractAlterTable(sql));
        } else {
            info.setType("OTHER");
        }
        return info;
    }

    /**
     * 标准 CREATE TABLE [db.]table ... / CREATE TABLE [db.]table AS SELECT ...
     */
    private String extractCreateTable(String sql) {
        Pattern pattern = Pattern.compile(
            "CREATE\\s+TABLE\\s+`?([^`\\s]+(?:\\.[^`\\s]+)*)`?",
            Pattern.CASE_INSENSITIVE);
        Matcher m = pattern.matcher(sql);
        return m.find() ? m.group(1) : null;
    }
}
```

### 7.4 上游产出表补全

```java
@Service
public class SqlCompletionService {

    private final MetadataTableMapper metadataTableMapper;
    private final SqlParser sqlParser;

    /**
     * 返回当前 SQL 节点的自动补全候选。
     * 候选来源：
     * 1. 内置 Doris 已有库/表/字段（从 metadata 表读取）
     * 2. 上游 SQL 节点通过 CREATE TABLE / CTAS 创建的新表（解析 SQL 静态提取）
     */
    public List<CompletionItem> getCandidates(Long dagId, String currentNodeId) {
        List<CompletionItem> items = new ArrayList<>();

        // 1. 内置 Doris 已有元数据
        items.addAll(loadBuiltinDorisMetadata());

        // 2. 上游 SQL 节点产出表
        List<DagNode> upstreamSqlNodes = findUpstreamSqlNodes(dagId, currentNodeId);
        for (DagNode node : upstreamSqlNodes) {
            SqlNodeConfig config = JsonUtils.fromJson(node.getConfig(), SqlNodeConfig.class);
            List<String> createdTables = sqlParser.extractCreatedTables(config.getSqlContent());
            for (String table : createdTables) {
                items.add(new CompletionItem(table, "⬆ 上游产出 - " + node.getNodeName()));
            }
        }
        return items;
    }
}
```

---

## 8. engineering-service：DAG 执行引擎

### 8.1 执行流程

```java
@Service
public class DagExecutionEngine {

    @Value("${datanest.dag.default-max-parallelism:3}")
    private int defaultMaxParallelism;

    private final ExecutorService executor = Executors.newFixedThreadPool(8);
    private final DagNodeMapper nodeMapper;
    private final DagEdgeMapper edgeMapper;
    private final DagExecutionMapper executionMapper;
    private final NodeExecutionMapper nodeExecutionMapper;
    private final SqlExecutor sqlExecutor;
    private final SyncNodeExecutor syncNodeExecutor;

    /**
     * 触发一次 DAG 执行。
     * 1. 校验 DAG 状态（非 RUNNING）
     * 2. 创建 DagExecution + NodeExecution(WAITING)
     * 3. 启动调度循环
     */
    public Long startExecution(Long dagId, String triggerType) {
        Dag dag = dagMapper.selectById(dagId);
        if ("RUNNING".equals(dag.getStatus())) {
            throw new BizException("DAG 当前正在执行中");
        }

        List<DagNode> nodes = nodeMapper.selectByDagId(dagId);
        List<DagEdge> edges = edgeMapper.selectByDagId(dagId);

        DagExecution execution = new DagExecution();
        execution.setDagId(dagId);
        execution.setTriggerType(triggerType);
        execution.setStatus("RUNNING");
        execution.setStartTime(LocalDateTime.now());
        executionMapper.insert(execution);

        // 初始化所有节点为 WAITING
        Map<String, NodeExecution> nodeExecMap = new HashMap<>();
        for (DagNode node : nodes) {
            NodeExecution ne = new NodeExecution();
            ne.setExecutionId(execution.getId());
            ne.setNodeId(node.getNodeId());
            ne.setStatus("WAITING");
            nodeExecutionMapper.insert(ne);
            nodeExecMap.put(node.getNodeId(), ne);
        }

        dag.setStatus("RUNNING");
        dagMapper.updateById(dag);

        // 提交调度任务
        executor.submit(() -> runExecution(execution.getId(), nodes, edges));
        return execution.getId();
    }

    private void runExecution(Long executionId, List<DagNode> nodes, List<DagEdge> edges) {
        Map<String, DagNode> nodeMap = nodes.stream().collect(Collectors.toMap(DagNode::getNodeId, n -> n));
        Map<String, List<String>> downstream = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();
        nodes.forEach(n -> {
            inDegree.put(n.getNodeId(), 0);
            downstream.put(n.getNodeId(), new ArrayList<>());
        });
        edges.forEach(e -> {
            downstream.computeIfAbsent(e.getSourceNodeId(), k -> new ArrayList<>()).add(e.getTargetNodeId());
            inDegree.merge(e.getTargetNodeId(), 1, Integer::sum);
        });

        // 就绪队列
        Queue<DagNode> ready = new LinkedList<>();
        nodes.stream().filter(n -> inDegree.get(n.getNodeId()) == 0).forEach(ready::offer);

        Map<String, Future<?>> running = new ConcurrentHashMap<>();
        Set<String> completed = ConcurrentHashMap.newKeySet();
        Set<String> failed = ConcurrentHashMap.newKeySet();

        while (!ready.isEmpty() || !running.isEmpty()) {
            // 启动就绪节点，控制并行度
            while (!ready.isEmpty() && running.size() < defaultMaxParallelism) {
                DagNode node = ready.poll();
                running.put(node.getNodeId(), executor.submit(() -> executeNode(executionId, node)));
            }

            // 等待任意一个运行中节点完成
            Iterator<Map.Entry<String, Future<?>>> it = running.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Future<?>> entry = it.next();
                if (entry.getValue().isDone()) {
                    it.remove();
                    completed.add(entry.getKey());
                    try {
                        entry.getValue().get();
                    } catch (Exception e) {
                        failed.add(entry.getKey());
                    }

                    // 下游节点入度减一，若上游全成功则就绪
                    for (String nextId : downstream.getOrDefault(entry.getKey(), Collections.emptyList())) {
                        if (failed.contains(entry.getKey())) {
                            markSkipped(executionId, nextId);
                            failed.add(nextId);
                            completed.add(nextId);
                            continue;
                        }
                        inDegree.merge(nextId, -1, Integer::sum);
                        if (inDegree.get(nextId) == 0) {
                            ready.offer(nodeMap.get(nextId));
                        }
                    }
                }
            }

            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // 结束状态
        DagExecution execution = executionMapper.selectById(executionId);
        execution.setEndTime(LocalDateTime.now());
        execution.setStatus(failed.isEmpty() ? "SUCCESS" : "FAILED");
        executionMapper.updateById(execution);

        Dag dag = dagMapper.selectById(execution.getDagId());
        dag.setStatus(execution.getStatus());
        dag.setLastExecutedAt(LocalDateTime.now());
        dagMapper.updateById(dag);
    }

    private void executeNode(Long executionId, DagNode node) {
        updateNodeStatus(executionId, node.getNodeId(), "RUNNING", LocalDateTime.now(), null);
        NodeExecutionResult result;
        try {
            if ("SQL".equals(node.getNodeType())) {
                result = sqlExecutor.executeInDag(executionId, node);
            } else {
                result = syncNodeExecutor.executeInDag(executionId, node);
            }
        } catch (Exception e) {
            result = NodeExecutionResult.failure(e.getMessage());
        }

        updateNodeStatus(executionId, node.getNodeId(),
                result.isSuccess() ? "SUCCESS" : "FAILED",
                null, LocalDateTime.now(), result.getSummary(), result.getErrorMessage());
    }

    private void markSkipped(Long executionId, String nodeId) {
        updateNodeStatus(executionId, nodeId, "SKIPPED", null, LocalDateTime.now(), null, null);
    }
}
```

### 8.2 终止执行

```java
public void terminateExecution(Long executionId) {
    DagExecution execution = executionMapper.selectById(executionId);
    if (!"RUNNING".equals(execution.getStatus())) {
        throw new BizException("当前执行不在运行中");
    }

    // 向运行中的节点发送取消信号
    List<NodeExecution> runningNodes = nodeExecutionMapper.selectRunningByExecutionId(executionId);
    for (NodeExecution ne : runningNodes) {
        sqlExecutor.cancel(ne.getId());       // 中断当前 SQL 语句
        syncNodeExecutor.cancel(ne.getId());  // 取消当前同步实例
        ne.setStatus("FAILED");
        ne.setEndTime(LocalDateTime.now());
        ne.setErrorMessage("执行被手动终止");
        nodeExecutionMapper.updateById(ne);
    }

    // 未开始节点标记为 SKIPPED
    List<NodeExecution> waitingNodes = nodeExecutionMapper.selectWaitingByExecutionId(executionId);
    for (NodeExecution ne : waitingNodes) {
        ne.setStatus("SKIPPED");
        ne.setEndTime(LocalDateTime.now());
        nodeExecutionMapper.updateById(ne);
    }

    execution.setStatus("TERMINATED");
    execution.setEndTime(LocalDateTime.now());
    executionMapper.updateById(execution);
}
```

### 8.3 XXL-JOB 定时调度

与 Sprint 2 同步任务类似，engineering-service 新增 `dagJobHandler`：

```java
@Component
public class DagSchedulerService {

    private final XxlJobApi xxlJobApi;
    private final SchedulerService syncSchedulerService; // 复用 Sprint 2 的 cookie/login 工具

    public void register(Dag dag) {
        // 同 Sprint 2，注册到 XXL-JOB
        JobInfo info = buildJobInfo(dag);
        xxlJobApi.addJob(cookie, info);
    }

    private JobInfo buildJobInfo(Dag dag) {
        JobInfo info = new JobInfo();
        info.setJobDesc("DAG-" + dag.getName());
        info.setAuthor("datanest");
        info.setGlueType("BEAN");
        info.setExecutorHandler("dagJobHandler");
        info.setExecutorParam(String.valueOf(dag.getId()));
        info.setExecutorTimeout(0);  // DAG 自己管理超时
        info.setExecutorFailRetryCount(0);
        info.setExecutorBlockStrategy("SERIAL_EXECUTION");
        if ("CRON".equals(dag.getTriggerType())) {
            info.setScheduleType("CRON");
            info.setScheduleConf(dag.getCronExpression());
        } else {
            info.setScheduleType("NONE");
        }
        return info;
    }
}

@Component
public class DagJobHandler {

    private final DagExecutionEngine executionEngine;

    @XxlJob("dagJobHandler")
    public void execute() {
        Long dagId = Long.valueOf(XxlJobHelper.getJobParam());
        executionEngine.startExecution(dagId, "CRON");
    }
}
```

---

## 9. engineering-service：多表批量同步增强

### 9.1 数据模型变更

`sync_task` 表从单表字段扩展为支持多源表：

```java
@Data
@TableName("sync_task")
public class SyncTask {
    // ... Sprint 2 已有字段 ...

    /**
     * 多表模式：源表列表 JSON。
     * ["orders","order_logs","payment_records"]
     */
    private String sourceTables;

    /**
     * 多表模式：源表 → 目标表映射 JSON。
     * {"orders":"orders","order_logs":"order_logs","payment_records":"payment_records"}
     */
    private String tableMapping;

    /**
     * 是否多表任务。
     * 已有单表任务不可切换为多表模式，需新建。
     */
    private Boolean multiTable;
}
```

### 9.2 Addax Job 多表模式

```java
@Component
public class AddaxJobBuilder {

    public List<Path> buildJobFiles(SyncTask task, DataSourceConnection ds) {
        if (Boolean.TRUE.equals(task.getMultiTable())) {
            return buildMultiTableJobs(task, ds);
        }
        return List.of(buildSingleTableJob(task, ds));
    }

    private List<Path> buildMultiTableJobs(SyncTask task, DataSourceConnection ds) {
        List<String> sourceTables = JsonUtils.parseList(task.getSourceTables(), String.class);
        Map<String, String> tableMapping = JsonUtils.parseMap(task.getTableMapping(), String.class, String.class);
        List<Path> files = new ArrayList<>();
        for (String sourceTable : sourceTables) {
            String targetTable = tableMapping.getOrDefault(sourceTable, sourceTable);
            Path file = buildJobFile(task, ds, sourceTable, targetTable);
            files.add(file);
        }
        return files;
    }
}
```

### 9.3 字段映射策略

多表模式下， **第一个源表**展示完整字段映射 UI，其余源表默认同名自动映射，保存前做字段名/类型一致性预校验。

```java
@Service
public class MultiTableMappingValidator {

    public void validate(SyncTask task) {
        if (!Boolean.TRUE.equals(task.getMultiTable())) return;

        List<String> sourceTables = JsonUtils.parseList(task.getSourceTables(), String.class);
        if (sourceTables.size() <= 1) return;

        // 拉取所有源表字段
        Map<String, List<ColumnInfo>> tableColumns = new LinkedHashMap<>();
        for (String table : sourceTables) {
            tableColumns.put(table, schemaExtractor.extractColumns(table));
        }

        // 以第一个表为基准
        List<ColumnInfo> first = tableColumns.get(sourceTables.get(0));
        Map<String, String> firstTypes = first.stream().collect(Collectors.toMap(ColumnInfo::getName, ColumnInfo::getType));

        for (int i = 1; i < sourceTables.size(); i++) {
            String table = sourceTables.get(i);
            for (ColumnInfo col : tableColumns.get(table)) {
                if (firstTypes.containsKey(col.getName()) && !firstTypes.get(col.getName()).equals(col.getType())) {
                    throw new BizException(
                        "多表字段类型不一致：" + sourceTables.get(0) + "." + col.getName() +
                        " 与 " + table + "." + col.getName() + " 类型不同，请逐表确认映射关系");
                }
            }
        }
    }
}
```

---

## 10. engineering-service：同步速率限流

### 10.1 数据模型变更

`sync_task` 表新增限流字段：

```java
@Data
@TableName("sync_task")
public class SyncTask {
    // ... 已有字段 ...

    private Boolean rateLimitEnabled;   // 是否启用速率限制
    private Integer readRateLimitMb;    // 读取速率上限 MB/s
    private Integer writeRateLimitRows; // 写入速率上限 行/s
}
```

### 10.2 Addax Job 限流配置

Addax `core.transport.channel.speed` 支持字节流控，`channel` 数量控制并发：

```java
private JsonObject buildSetting(SyncTask task) {
    JsonObject setting = new JsonObject();
    JsonObject speed = new JsonObject();

    if (Boolean.TRUE.equals(task.getRateLimitEnabled())) {
        if (task.getReadRateLimitMb() != null) {
            // byte = MB * 1024 * 1024
            speed.addProperty("byte", task.getReadRateLimitMb() * 1024 * 1024);
        }
        if (task.getWriteRateLimitRows() != null) {
            speed.addProperty("record", task.getWriteRateLimitRows());
        }
    }

    JsonObject channel = new JsonObject();
    channel.addProperty("channel", 3);  // 默认 3 并发
    setting.add("speed", speed);
    setting.add("errorLimit", channel); // 占位
    return setting;
}
```

> Addax 实际字节限速参数为 `job.setting.speed.byte`，记录限速为 `job.setting.speed.record`。根据 Addax 版本调整字段名。

---

## 11. governance-service：SQL 产出元数据自动注册

### 11.1 触发条件

当 DAG 中 SQL 节点 **正式执行成功**时：

| SQL 类型                       | 处理逻辑                                   |
|--------------------------------|--------------------------------------------|
| CREATE TABLE / CTAS            | 新表注册到 metadata_table/column           |
| INSERT INTO / INSERT OVERWRITE | 更新目标表最近写入时间；未注册则不主动注册 |
| ALTER TABLE                    | 更新对应表字段结构                         |
| DROP TABLE                     | 从元数据管理中移除该表                     |
| SELECT / DELETE / UPDATE       | 不触发注册                                 |

### 11.2 注册实现

```java
@Service
public class MetadataRegistrar {

    private final MetadataTableMapper metadataTableMapper;
    private final MetadataColumnMapper metadataColumnMapper;
    private final JdbcSchemaExtractor schemaExtractor;

    /**
     * 注册 SQL 节点执行后创建/变更的表。
     */
    public void registerSqlOutput(Long dagId, String nodeId, List<SqlStatementInfo> statements) {
        for (SqlStatementInfo stmt : statements) {
            switch (stmt.getType()) {
                case "CREATE":
                    registerTable(stmt.getTargetTable());
                    break;
                case "DROP":
                    unregisterTable(stmt.getTargetTable());
                    break;
                case "ALTER":
                    refreshTableSchema(stmt.getTargetTable());
                    break;
                case "INSERT":
                    touchTable(stmt.getTargetTable());
                    break;
                default:
                    // no-op
            }
        }
    }

    private void registerTable(String fullTableName) {
        String[] parts = fullTableName.split("\\.");
        String db = parts.length > 1 ? parts[0] : "default";
        String table = parts.length > 1 ? parts[1] : parts[0];

        MetadataTable mt = metadataTableMapper.selectByUnique(-1L, db, null, table);
        if (mt == null) {
            mt = new MetadataTable();
            mt.setDatasourceId(-1L);
            mt.setDatabaseName(db);
            mt.setTableName(table);
            mt.setSourceType("BUILTIN_DORIS");
            mt.setSourceStatus("ONLINE");
            metadataTableMapper.insert(mt);
        }

        // 拉取 Doris 字段结构
        List<ColumnInfo> columns = schemaExtractor.extractColumns("DORIS", dorisHost, dorisPort,
                db, null, table, dorisUser, dorisPassword);
        for (ColumnInfo col : columns) {
            metadataColumnMapper.upsert(new MetadataColumn(
                    mt.getId(), col.getName(), col.getType(),
                    col.isNullable(), col.getDefaultValue(), col.getOrdinal()));
        }
        mt.setColumnCount(columns.size());
        mt.setUpdatedAt(LocalDateTime.now());
        metadataTableMapper.updateById(mt);
    }
}
```

---

## 12. 数据库设计

### 12.1 新增表

```sql
-- V4.0.0__create_dev_project.sql
CREATE TABLE dev_project (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(30) NOT NULL UNIQUE,
    description     VARCHAR(200),
    created_by      BIGINT,
    updated_by      BIGINT,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- V4.0.1__create_dev_dag.sql
CREATE TABLE dev_dag (
    id                  BIGSERIAL PRIMARY KEY,
    project_id          BIGINT NOT NULL REFERENCES dev_project(id) ON DELETE CASCADE,
    name                VARCHAR(50) NOT NULL,
    trigger_type        VARCHAR(20) NOT NULL DEFAULT 'MANUAL',
    cron_expression     VARCHAR(120),
    schedule_enabled    BOOLEAN NOT NULL DEFAULT TRUE,
    max_parallelism     INT NOT NULL DEFAULT 3,
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    last_executed_at    TIMESTAMP,
    next_execution_time TIMESTAMP,
    created_by          BIGINT,
    updated_by          BIGINT,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (project_id, name)
);

CREATE TABLE dev_dag_node (
    id              BIGSERIAL PRIMARY KEY,
    dag_id          BIGINT NOT NULL REFERENCES dev_dag(id) ON DELETE CASCADE,
    node_id         VARCHAR(64) NOT NULL,
    node_name       VARCHAR(50) NOT NULL,
    node_type       VARCHAR(20) NOT NULL,
    position_x      DOUBLE PRECISION,
    position_y      DOUBLE PRECISION,
    config          TEXT,
    UNIQUE (dag_id, node_id),
    UNIQUE (dag_id, node_name)
);

CREATE TABLE dev_dag_edge (
    id              BIGSERIAL PRIMARY KEY,
    dag_id          BIGINT NOT NULL REFERENCES dev_dag(id) ON DELETE CASCADE,
    edge_id         VARCHAR(64) NOT NULL,
    source_node_id  VARCHAR(64) NOT NULL,
    target_node_id  VARCHAR(64) NOT NULL,
    UNIQUE (dag_id, edge_id)
);

CREATE TABLE dev_dag_execution (
    id              BIGSERIAL PRIMARY KEY,
    dag_id          BIGINT NOT NULL REFERENCES dev_dag(id) ON DELETE CASCADE,
    trigger_type    VARCHAR(20) NOT NULL,
    status          VARCHAR(20) NOT NULL,
    start_time      TIMESTAMP NOT NULL,
    end_time        TIMESTAMP
);

CREATE TABLE dev_node_execution (
    id              BIGSERIAL PRIMARY KEY,
    execution_id    BIGINT NOT NULL REFERENCES dev_dag_execution(id) ON DELETE CASCADE,
    node_id         VARCHAR(64) NOT NULL,
    status          VARCHAR(20) NOT NULL,
    start_time      TIMESTAMP,
    end_time        TIMESTAMP,
    error_message   TEXT,
    output_summary  TEXT
);

-- V4.0.2__create_dag_sync_task_ref.sql
CREATE TABLE dag_sync_task_ref (
    id              BIGSERIAL PRIMARY KEY,
    dag_id          BIGINT NOT NULL REFERENCES dev_dag(id) ON DELETE CASCADE,
    sync_task_id    BIGINT NOT NULL REFERENCES sync_task(id),
    node_id         VARCHAR(64) NOT NULL
);

-- V4.0.3__sync_task_multi_table_rate_limit.sql
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS multi_table BOOLEAN DEFAULT FALSE;
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS source_tables TEXT;
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS table_mapping TEXT;
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS rate_limit_enabled BOOLEAN DEFAULT FALSE;
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS read_rate_limit_mb INT;
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS write_rate_limit_rows INT;
```

### 12.2 索引

```sql
CREATE INDEX idx_dev_dag_project ON dev_dag(project_id);
CREATE INDEX idx_dev_dag_node_dag ON dev_dag_node(dag_id);
CREATE INDEX idx_dev_dag_edge_dag ON dev_dag_edge(dag_id);
CREATE INDEX idx_dev_dag_execution_dag ON dev_dag_execution(dag_id);
CREATE INDEX idx_dev_node_execution_execution ON dev_node_execution(execution_id);
CREATE INDEX idx_dag_sync_task_ref_task ON dag_sync_task_ref(sync_task_id);
```

---

## 13. API 接口设计

### 13.1 Project API

```
GET    /api/dev/projects                  # 项目列表（分页 + 搜索）
POST   /api/dev/projects                  # 创建项目
PUT    /api/dev/projects/{id}             # 编辑项目
DELETE /api/dev/projects/{id}             # 删除项目（级联删除 DAG）
```

### 13.2 DAG API

```
GET    /api/dev/projects/{projectId}/dags  # DAG 列表（分页 + 搜索 + 状态筛选）
POST   /api/dev/projects/{projectId}/dags  # 创建 DAG
GET    /api/dev/dags/{id}                  # DAG 详情
PUT    /api/dev/dags/{id}                  # 更新 DAG 基础信息
DELETE /api/dev/dags/{id}                  # 删除 DAG
GET    /api/dev/dags/{id}/canvas           # 获取画布数据（nodes + edges）
PUT    /api/dev/dags/{id}/canvas           # 保存画布数据
POST   /api/dev/dags/{id}/execute          # 手动执行
POST   /api/dev/dags/{id}/schedule/enable  # 启用调度
POST   /api/dev/dags/{id}/schedule/disable # 停用调度
GET    /api/dev/dags/{id}/history          # 执行历史（支持 status/triggerType/startTimeFrom/startTimeTo 查询）
```

### 13.3 Execution API

```
GET    /api/dev/executions/history         # 全局执行历史（支持 dagName/status/triggerType/startTimeFrom/startTimeTo 查询）
GET    /api/dev/executions/{id}            # 执行实例详情
POST   /api/dev/executions/{id}/terminate  # 终止执行
GET    /api/dev/executions/{id}/nodes      # 节点执行详情
```

```java
public interface DagExecutionHistoryQueryParams {
    private String dagName;            // 按 DAG 名称模糊匹配
    private String status;             // RUNNING / SUCCESS / FAILED / TERMINATED
    private String triggerType;        // MANUAL / CRON
    private String startTimeFrom;      // 执行时间起（ISO 8601）
    private String startTimeTo;        // 执行时间止（ISO 8601）
    private Integer page;
    private Integer pageSize;
}
```

### 13.4 SQL API

GET /api/dev/sql/completion # 自动补全候选 ?dagId={dagId}&nodeId={nodeId}

```

### 13.5 Sync Task API 增强

```

GET /api/engineering/sync-tasks/{id}/dag-refs # 查询被哪些 DAG 引用

```

> 删除同步任务前调用此接口，若有引用则阻断删除。

---

## 14. 共享配置变更

### 14.1 新增 shared-dag.yaml

```yaml
# shared-configs/shared-dag.yaml
datanest:
  dag:
    default-max-parallelism: 3
    sql-timeout-seconds: 1800
    sync-timeout-seconds: 3600
    node-retry-count: 0        # DAG 节点内部不重试，失败即失败
```

### 14.2 shared-doris.yaml 扩展

```yaml
# 已有配置，用于 SQL 执行和同步写入
datanest:
  doris:
    fe-host: ${DORIS_FE_HOST:}
    fe-port: ${DORIS_FE_PORT:9030}
    be-http-port: ${DORIS_BE_HTTP_PORT:8040}
    username: ${DORIS_USER:root}
    password: ${DORIS_PASSWORD:}
    default-db: ${DORIS_DEFAULT_DB:default}
```

---

## 15. 前端设计

### 15.1 页面清单

| 页面     | 路由                          | 说明                                                                        |
|----------|-------------------------------|-----------------------------------------------------------------------------|
| 项目列表 | `/data-dev/projects`          | 搜索、新建/编辑/删除项目                                                    |
| DAG 列表 | `/data-dev/projects/:id/dags` | 搜索、新建/编辑/执行/历史/删除 DAG；表格展示 Cron 表达式                    |
| DAG 画布 | `/data-dev/dags/:id/canvas`   | 全屏画布，节点拖拽、连线、属性面板                                          |
| 执行历史 | `/data-dev/history`           | 全局执行历史页面，支持状态/触发方式/时间范围筛选，展开后展示微缩 DAG 拓扑图 |

> 左侧导航「数据开发」分组下包含两个子菜单：「数据开发」（项目/DAG 管理）和「执行历史」（全局执行记录）。

### 15.2 关键组件

- `ProjectListPage` / `ProjectModal` / `ProjectDeleteModal`
- `DagListPage`
- `DagCanvasPage`
    - `CanvasToolbar`：返回、DAG 名称、触发方式、Cron、调度状态、保存、执行
    - `NodePalette`：SQL 任务、同步任务拖拽面板
    - `DagCanvas`：ReactFlow 画布
    - `PropertyPanel`：节点只读属性
- `SqlEditorModal`： Monaco Editor + 执行结果
- `SyncNodeModal`：选择同步任务 + 摘要
- `ExecutionHistoryPage`：全局执行历史页面，含状态/触发方式/时间范围筛选
    - `MiniDagGraph`：展开行内的微缩 DAG 拓扑图组件，按节点运行时状态渲染节点与连线
- `UnsavedConfirmModal`：未保存拦截

### 15.3 ReactFlow 集成要点

```tsx
import ReactFlow, {
  Background, Controls, MiniMap,
  useNodesState, useEdgesState, addEdge
} from 'reactflow';

const nodeTypes = {
  sql: SqlNode,
  sync: SyncNode,
};

function DagCanvas() {
  const [nodes, setNodes, onNodesChange] = useNodesState(initialNodes);
  const [edges, setEdges, onEdgesChange] = useEdgesState(initialEdges);

  const onConnect = useCallback(
    (params) => setEdges((eds) => addEdge(params, eds)),
    [setEdges]
  );

  return (
    <ReactFlow
      nodes={nodes}
      edges={edges}
      onNodesChange={onNodesChange}
      onEdgesChange={onEdgesChange}
      onConnect={onConnect}
      nodeTypes={nodeTypes}
      fitView
    >
      <Background gap={20} size={1} color="#e2e6ed" />
      <Controls />
      <MiniMap />
    </ReactFlow>
  );
}
```

### 15.4 权限控制

| 操作                   | 超管 | 数据工程师 | 治理员 | 数据分析师 |
|------------------------|:----:|:----------:|:------:|:----------:|
| 查看 DAG 列表          |  ✅  |     ✅     |   ✅   |     ✅     |
| 查看 DAG 画布（只读）  |  ✅  |     ✅     |   ✅   |     ✅     |
| 创建/编辑/删除 DAG     |  ✅  |     ✅     |   ❌   |     ❌     |
| 执行 DAG               |  ✅  |     ✅     |   ❌   |     ❌     |
| 创建/编辑 SQL 任务节点 |  ✅  |     ✅     |   ❌   |     ❌     |
| 添加/编辑同步任务节点  |  ✅  |     ✅     |   ❌   |     ❌     |
| 查看执行历史           |  ✅  |     ✅     |   ✅   |     ✅     |

### 15.5 UI 原型交付物

UI 原型位于 `docs/sprint3/ui/`：

- `DESIGN.md`：Sprint 3 视觉规范与组件说明
- `tokens.css`：CSS 变量
- `tailwind.config.ts`：Tailwind 配置
- `Sprint3-DAG编排与SQL任务编辑器.html`：单文件可交互原型

---

## 16. Sprint 3 ADR

### ADR-1：DAG 定义与运行时状态分离

**背景**：DAG 可能同时有多个执行实例在跑，若把状态存在 DAG 定义表中会互相覆盖。

**决策**：

- `dev_dag` / `dev_dag_node` / `dev_dag_edge` 只保存静态定义。
- 每次执行生成 `dev_dag_execution` + `dev_node_execution`。

**影响**：

- 需要同时读取定义表和运行时表来展示画布状态。
- 历史记录天然支持多实例回溯。

### ADR-2：同步任务采用引用模式而非内嵌模式

**背景**：DAG 中的同步任务节点可以直接内嵌同步配置，也可以引用已有同步任务。

**决策**：采用引用模式——DAG 节点引用「批量数据同步」中的已有任务。

**影响**：

- 同步任务可被多个 DAG 复用。
- 删除同步任务前需要校验 DAG 引用关系。
- 同步任务变更后，DAG 画布进入时重新加载摘要。

### ADR-3：SQL 测试执行不注册元数据

**背景**：工程师需要在编辑 SQL 时验证语法和逻辑，但不想污染元数据。

**决策**：SQL 节点弹窗中的「运行测试」仅执行并返回结果，不触发元数据自动注册；只有 DAG 正式执行成功后才注册。

**影响**：

- 测试执行可以重复进行，不会产生脏表。
- 元数据管理中的表只来自正式执行。

### ADR-4：任务级互斥锁避免同步任务双入口冲突

**背景**：同一同步任务可能被 DAG 触发，也可能被自身独立调度触发。

**决策**：同一同步任务同一时刻只能有一个运行实例；后触发方进入「等待中」状态。

**影响**：

- 避免数据重复或冲突。
- 需要在 `sync_task` 或 Redis 中维护分布式锁（单机可用内存锁，集群需 Redis）。

### ADR-5：DAG 节点默认并行度 3

**背景**：无依赖节点可以并行执行以缩短整体耗时，但并行度过大会压垮 Doris。

**决策**：默认最大并行度 3，可通过 Nacos 配置调整。

---

## 17. 验收标准

### 17.1 功能验收

| #     | 验收项                 | 通过标准                                                                    |
|-------|------------------------|-----------------------------------------------------------------------------|
| AC-1  | 创建 DAG               | 点击新建 → 进入画布 → 输入名称 → 保存 → DAG 列表出现该条目                  |
| AC-2  | 添加 SQL 任务节点      | 从面板拖动 → 画布出现节点 → 双击编辑 → 填写名称和 SQL → 保存                |
| AC-3  | 添加同步任务节点       | 拖动 → 选择已有同步任务 → 保存 → 节点显示任务摘要                           |
| AC-4  | 连线依赖               | 从节点 A 输出端拖到节点 B 输入端 → 连线创建成功 → 依赖关系确立              |
| AC-5  | 删除连线/节点          | 选中连线/节点 → Delete → 确认 → 移除                                        |
| AC-6  | 手动执行 DAG           | 点击执行 → 无上游节点率先运行 → 上游成功后下游自动触发 → 所有节点完成       |
| AC-7  | 上游失败下游跳过       | 人为让上游失败 → 下游标记为「被跳过」→ DAG 状态为「失败」                   |
| AC-8  | 定时执行 DAG           | 配置 Cron → 到达指定时间 → 自动执行 → 执行日志正确记录                      |
| AC-9  | 暂停/恢复调度          | 停用调度 → DAG 不再按 Cron 触发 → 启用调度 → 恢复触发                       |
| AC-10 | SQL 测试执行           | 在编辑弹窗中点击「运行测试」→ 结果显示在弹窗内 → 不更新元数据               |
| AC-11 | SQL 正式执行注册元数据 | DAG 正式执行 → SQL 节点中的 CTAS/CREATE TABLE → 新表出现在元数据管理中      |
| AC-12 | 多表同步               | 创建同步任务时选择多个源表 → 逐个映射目标表名 → 执行后所有表被同步          |
| AC-13 | 速率限流               | 启用限流并设置 5MB/s → 执行同步 → 实际读取速率不超过 5MB/s                  |
| AC-14 | 删除引用校验           | 删除被 DAG 引用的同步任务时，弹窗阻断列出 DAG 名称                          |
| AC-15 | 权限隔离               | 治理员可查看 DAG 但不能创建/编辑/执行；分析师仅可查看                       |
| AC-16 | 循环依赖校验           | 保存存在 A→B→A 循环依赖的 DAG 时，系统拒绝保存并提示                        |
| AC-17 | 多分支并行执行         | 构建两个无依赖的并行分支 → 同时执行 → 总体耗时低于串行执行                  |
| AC-18 | 终止执行               | 运行中的 DAG 点击「终止执行」→ 当前节点停止 → 下游标记为跳过 → DAG 状态失败 |
| AC-19 | 同步任务变更感知       | 修改被引用的同步任务目标表 → 重新打开 DAG 画布 → 节点摘要显示新目标表       |
| AC-20 | SQL 测试不污染元数据   | 在 SQL 节点弹窗点击「运行测试」执行 CTAS → 元数据管理中不出现新表           |

### 17.2 非功能验收

| #     | 验收项         | 通过标准                                        |
|-------|----------------|-------------------------------------------------|
| NAC-1 | 画布性能       | 50 个节点的 DAG 画布操作流畅（平移/缩放无卡顿） |
| NAC-2 | SQL 编辑器响应 | 1000 行 SQL 的语法高亮和格式化 < 2 秒           |
| NAC-3 | DAG 执行延迟   | 单个节点完成到下游节点开始 < 3 秒               |
| NAC-4 | 自动补全速度   | 100 张表的元数据加载下，自动补全响应 < 500ms    |

---

## 18. 风险与对策

| #  | 风险                                     | 影响                               | 对策                                                                               |
|----|------------------------------------------|------------------------------------|------------------------------------------------------------------------------------|
| R1 | SQL 编辑器自动补全需要读取元数据管理     | 首次打开编辑器时网络延迟           | 进入画布时预加载内置 Doris 的表和字段列表                                          |
| R2 | SQL 任务执行时间不可控                   | DAG 整体执行时间过长               | Sprint 3 设置单节点超时时间为 30 分钟，超时自动标记失败；同时支持执行中手动终止    |
| R3 | 同步任务被 DAG 和自己独立调度同时触发    | 同一任务并发执行导致数据重复或冲突 | 任务级互斥锁：同一同步任务同一时刻只能有一个实例在执行；后触发方进入「等待中」状态 |
| R4 | 多表同步时字段映射容易出错               | 数据写错字段                       | 多表模式下，除第一个表外其余表默认同名映射；保存前进行字段名/类型一致性预校验      |
| R5 | DAG 中循环依赖（A→B→A）                  | DAG 无法执行，死循环               | 保存 DAG 时校验无环（拓扑排序），存在环时拒绝保存并提示                            |
| R6 | DAG 定义与运行时状态混存                 | 多实例同时执行时状态互相覆盖       | 后端将 DAG 定义与执行实例/节点状态分表存储，每次执行生成独立实例记录               |
| R7 | 画布未保存拦截在浏览器关闭时无法完全保证 | 用户误关标签丢失工作               | 使用 `beforeunload` 事件 + 弹窗内二次确认；最终仍依赖用户操作                      |
| R8 | ReactFlow 节点多导致性能下降             | 50+ 节点画布卡顿                   | 使用 `React.memo` 缓存节点组件；大 DAG 开启仅显示节点缩略图                        |
