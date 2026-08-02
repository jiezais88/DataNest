# DataNest Sprint 4 技术文档

> **Sprint**：Sprint 4 — Python 任务、参数化、监控告警与血缘上报
> **文档状态**：Working Draft (v1.0) | **作者**：软件架构师 | **日期**：2026-08-01
> **关联文档**：`DataNest-技术架构文档-v2.3.md`、`DataNest-Sprint4-PRD.md`

---

## 目录

1. [Sprint 概述](#1-sprint-概述)
2. [交付物清单](#2-交付物清单)
3. [项目结构变更](#3-项目结构变更)
4. [Docker Compose 变更](#4-docker-compose-变更)
5. [架构关系图](#5-架构关系图)
6. [Python 任务节点](#6-python-任务节点)
7. [DAG 参数化](#7-dag-参数化)
8. [DAG 运行监控与邮件告警](#8-dag-运行监控与邮件告警)
9. [SQL 血缘自动上报](#9-sql-血缘自动上报)
10. [DAG 版本管理](#10-dag-版本管理)
11. [真正的重跑失败节点](#11-真正的重跑失败节点)
12. [多表同步与速率限流收尾](#12-多表同步与速率限流收尾)
13. [数据库设计](#13-数据库设计)
14. [API 接口设计](#14-api-接口设计)
15. [前端设计](#15-前端设计)
16. [Sprint 4 ADR](#16-sprint-4-adr)
17. [验收标准](#17-验收标准)
18. [风险与对策](#18-风险与对策)

---

## 1. Sprint 概述

### 1.1 Sprint 目标

Sprint 3 完成了 SQL/SYNC 节点的 DAG 编排与执行。Sprint 4 在此基础上扩展任务类型、提升可复用性、增强可观测性，并为数据治理提供血缘基础：

1. **Python 任务节点**：在 DAG 画布中支持拖拽 Python 节点，脚本在隔离进程中执行。
2. **DAG 级参数化**：支持自定义参数与系统变量，SQL/Python 节点通过 `${paramName}` 占位符替换。
3. **监控与邮件告警**：DAG 失败或节点超时时发送邮件通知。
4. **SQL 血缘自动上报**：SQL 节点执行成功后解析 source → target 血缘并上报治理模块。
5. **DAG 版本管理**：保存即生成版本快照，支持对比与回滚。
6. **真正的重跑失败节点**：替换 Sprint 3 MVP 简化实现，仅重跑 FAILED/SKIPPED 节点。
7. **多表同步 + 速率限流收尾**：后端已就绪，前端 UI 补齐。

### 1.2 Sprint 范围

| # | 工作项                 | 所属模块                             | 说明                                       |
|---|------------------------|--------------------------------------|--------------------------------------------|
| 1 | **Python 任务节点**    | task-core + engineering-service      | 新增 PYTHON 节点类型、执行器、回调路径     |
| 2 | **DAG 参数化**         | engineering-service + frontend       | DAG 参数定义、节点占位符替换、手动触发覆盖 |
| 3 | **邮件告警**           | engineering-service / system-service | 告警配置、邮件发送、失败/超时触发          |
| 4 | **SQL 血缘自动上报**   | task-core + governance-service       | SQL 解析血缘、上报接口、元数据详情展示     |
| 5 | **DAG 版本管理**       | engineering-service + frontend       | dag_version 快照表、对比、回滚             |
| 6 | **真正的重跑失败节点** | engineering-service + DS             | 子图重跑、成功节点结果复用                 |
| 7 | **多表同步前端改造**   | frontend                             | 多表选择、目标表映射、字段映射、限流配置   |

### 1.3 不在本 Sprint

| 暂缓项                            | 后续 Sprint | 理由              |
|-----------------------------------|:-----------:|-------------------|
| Python pip 包管理                 |  Sprint 6   | 沙箱 + 私有 PyPI  |
| Python 虚拟环境切换               |  Sprint 6   | 环境管理复杂      |
| 条件分支 / 子 DAG                 |  Sprint 5   | 控制流节点        |
| 告警渠道扩展（钉钉/企微/Webhook） |  Sprint 5   | 全局告警中心      |
| 字段级血缘                        |  Sprint 5   | 表级血缘先落地    |
| 血缘图谱可视化                    |  Sprint 6   | 图数据库 + 可视化 |
| 实时日志流（WebSocket）           |  Sprint 5   | 当前轮询可满足    |

### 1.4 技术栈

| 组件                    | 版本/说明                | 用途                                  |
|-------------------------|--------------------------|---------------------------------------|
| Apache DolphinScheduler | 3.4.2                    | DAG 调度与执行引擎（Sprint 3 已集成） |
| ReactFlow               | 11.x                     | 前端 DAG 画布                         |
| Monaco Editor           | 0.52.x                   | SQL/Python 编辑器                     |
| Python 3                | 3.10+                    | Python 任务执行环境                   |
| pandas                  | 最新稳定版（当前 2.2.x） | Python 节点内置数据处理               |
| JSqlParser              | 4.x                      | SQL 血缘解析（Sprint 3 已引入）       |
| JavaMail / Spring Mail  | -                        | 邮件告警发送                          |
| Addax                   | 6.0.11                   | 批量同步引擎（Sprint 2 已集成）       |

---

## 2. 交付物清单

| #  | 交付物                                                                                          | 类型 | 验收方式          |
|----|-------------------------------------------------------------------------------------------------|------|-------------------|
| D1 | `data-nest-task-core` 新增 Python 执行器、血缘解析服务                                          | 代码 | 编译通过          |
| D2 | `data-nest-engineering` 新增 Python 回调、参数替换、告警、版本、重跑服务                        | 代码 | API 可用          |
| D3 | Flyway 迁移脚本：扩展 node_type、新增 dag_version / lineage / alert_config / alert_history 等表 | 代码 | 启动自动建表      |
| D4 | `data-nest-frontend` 新增 Python 编辑器、参数抽屉、版本弹窗、多表同步表单                       | 代码 | 页面可用          |
| D5 | `docker-compose.yml` 补充 Python 运行环境说明/挂载                                              | 配置 | Python 节点可执行 |
| D6 | `data-nest-job` 新增 `DagNodeTimeoutAlertHandler` XXL-JOB 超时告警扫描任务                      | 代码 | XXL-JOB 调度执行  |
| D7 | Gateway 路由无需新增（复用 `/api/engineering/**`）                                              | 配置 | 现有路由可用      |

---

## 3. 项目结构变更

### 3.1 模块职责划分

Sprint 4 不新增独立微服务，核心逻辑继续下沉到 `task-core`，API 暴露在 `engineering-service`。

```
data-nest/
├── data-nest-task-core/              # Python 执行 + 血缘解析
│   └── src/main/java/com/datanest/task/core/
│       ├── dag/
│       │   ├── entity/               # 新增 PythonNodeConfig、DagVersion、LineageRecord 等
│       │   ├── service/
│       │   │   ├── PythonExecutor.java          # Python 脚本执行器
│       │   │   ├── SqlLineageExtractor.java     # SQL 血缘提取
│       │   │   └── LineageReporter.java         # 血缘上报
│       │   └── dto/
│       └── sync/                     # Sprint 3 已就绪，Sprint 4 前端补齐
│
├── data-nest-engineering/            # 数据开发 API + DS 集成扩展
│   └── src/main/java/com/datanest/engineering/
│       ├── dev/
│       │   ├── controller/
│       │   │   ├── DagController.java
│       │   │   ├── DagVersionController.java    # 🆕 版本管理
│       │   │   ├── DagAlertConfigController.java # 🆕 告警配置
│       │   │   └── PythonCallbackController.java # 🆕 Python 节点回调
│       │   ├── service/
│       │   │   ├── DagService.java
│       │   │   ├── DagDsConverter.java          # 扩展 PYTHON 节点映射
│       │   │   ├── DagParameterService.java     # 🆕 DAG 参数 CRUD + 替换
│       │   │   ├── DagVersionService.java       # 🆕 版本快照/对比/回滚
│       │   │   ├── DagExecutionService.java     # 扩展真正重跑失败节点
│       │   │   ├── DagAlertService.java         # 🆕 告警触发
│       │   │   └── MailService.java             # 🆕 邮件发送
│       │   └── dto/
│       └── sync/                     # Sprint 2 已有
│
├── data-nest-governance/             # 血缘消费 + 元数据详情展示
│   └── src/main/java/com/datanest/governance/
│       ├── controller/
│       │   └── LineageController.java            # 🆕 /lineage/report 等接口
│       └── service/
│           └── LineageService.java
│
├── data-nest-job/                    # 🆕 新增超时告警扫描任务
│   └── src/main/java/com/datanest/job/handler/
│       └── DagNodeTimeoutAlertHandler.java
│
└── data-nest-frontend/               # 前端数据开发模块扩展
    └── src/pages/engineering/dags/
        ├── components/
        │   ├── SqlEditorModal.tsx
        │   ├── PythonEditorModal.tsx  # 🆕
        │   ├── DagParameterDrawer.tsx # 🆕
        │   ├── DagVersionModal.tsx    # 🆕
        │   └── MiniDagGraph.tsx       # 执行历史微缩图（Sprint 3 已有）
        └── ...
```

### 3.2 设计原则

1. **不新增微服务**：Python 执行、血缘解析放在 `task-core`；告警配置放在 `engineering-service`（与 DAG 强相关）。超时告警扫描任务放在现有
   `data-nest-job`。
2. **DS 只负责调度编排**：Python 节点同样映射为 DS HTTP 任务，回调 engineering-service 执行。
3. **版本快照全量存储**：Sprint 4 先全量保存节点/边/参数 JSON，后续 Sprint 再优化为 diff。
4. **血缘先表级后字段级**：Sprint 4 只解析表级 source/target，字段级留 Sprint 5。

---

## 4. Docker Compose 变更

### 4.1 Python 运行环境

Python 任务依赖 DataNest 服务所在容器内的 Python 3 解释器。基础镜像需预装 Python：

```dockerfile
# data-nest-engineering/Dockerfile 调整
FROM eclipse-temurin:17-jdk

# 安装 Python 3 + pip + pandas 最新稳定版
RUN apt-get update && apt-get install -y python3 python3-pip && rm -rf /var/lib/apt/lists/*
RUN pip3 install --no-cache-dir pandas

COPY target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]
```

> 注：Sprint 4 仅支持 Python 标准库 + pandas。Docker 镜像通过 pip 安装 pandas 最新稳定版，`read_doris_table` 返回 pandas
> DataFrame。

### 4.2 沙箱目录

Python 脚本执行时使用临时工作目录：

```yaml
# docker-compose.yml 新增 volumes（engineering-service 服务）
volumes:
  - ./data/python-sandbox:/tmp/datanest-python-sandbox
```

执行器为每个任务创建独立子目录，任务结束后清理。

### 4.3 启动顺序

Sprint 4 启动顺序与 Sprint 3 保持一致，无需新增服务：

```
nacos-mysql → nacos → postgres → xxl-job-admin → dolphinscheduler-*
    ↓
system → engineering → governance → worker → job → gateway → frontend
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
│                                       │ • SYNC 任务节点                       ││
│                                       │ • PYTHON 任务节点 🆕                  ││
│                                       │ • 参数定义 / 版本 / 告警配置 🆕        ││
│                                       └─────────────────────────────────────┘│
└─────────────────────────────────┬───────────────────────────────────────────┘
                                  │ HTTP
                                  ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                     data-nest-engineering (8082)                             │
│  ┌─────────────────┐  ┌─────────────────┐  ┌───────────────────────────────┐ │
│  │ Project/DAG API │  │ Python API 🆕   │  │ Parameter/Version/Alert API 🆕│ │
│  └────────┬────────┘  └────────┬────────┘  └────────┬──────────────────────┘ │
│           │                    │                    │                         │
│           └────────────────────┴────────────────────┘                         │
│                                  │                                            │
│                    ┌─────────────▼─────────────┐                            │
│                    │    DagDsConverter         │                            │
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
          │                │   │ SQL → /dev/internal/sql/callback             │
          │                │   │ SYNC → /dev/internal/sync/callback           │
          │                │   │ PYTHON → /dev/internal/python/callback 🆕    │
          │                │   └─────────────────────────────────────────────┘
          │                │                │
          │                │                ▼
          │                │   ┌─────────────────────────────┐
          │                └──▶│  data-nest-engineering       │
          │                    │  • DorisSqlExecutor          │
          │                    │  • PythonExecutor 🆕          │
          │                    │  • SyncJob trigger & poll    │
          │                    │  • Metadata registration     │
          │                    │  • Lineage report 🆕          │
          │                    │  • Alert mail 🆕              │
          │                    └─────────────────────────────┘
          │
          └────── 查询流程实例状态 ──────▶ 回显到前端画布
```

### 5.2 节点执行模型

| DataNest 节点类型 | DS 任务类型 | 回调接口                        | 执行方              |
|-------------------|-------------|---------------------------------|---------------------|
| SQL 任务          | HTTP 任务   | `/dev/internal/sql/callback`    | engineering-service |
| 同步任务          | HTTP 任务   | `/dev/internal/sync/callback`   | engineering-service |
| Python 任务 🆕    | HTTP 任务   | `/dev/internal/python/callback` | engineering-service |

---

## 6. Python 任务节点

### 6.1 节点类型扩展

Sprint 3 中 `dag_node.node_type` 仅支持 `SQL` / `SYNC`。Sprint 4 需扩展为 `SQL` / `SYNC` / `PYTHON`。

**Flyway 迁移**：

```sql
-- V3.3.0__extend_dag_node_python.sql
COMMENT
ON COLUMN dag_node.node_type IS '节点类型：SQL SQL 任务，SYNC 同步任务，PYTHON Python 任务';
COMMENT
ON COLUMN node_execution.node_type IS '节点类型：SQL / SYNC / PYTHON';
```

> 若原表对 `node_type` 有 CHECK 约束，需先删除再重建。

### 6.2 节点配置模型

```java
// PythonNodeConfig.java
@Data
@EqualsAndHashCode(callSuper = true)
public class PythonNodeConfig extends NodeConfig {
    private String pythonScript;        // Python 脚本内容
    private Integer timeoutMinutes;     // 超时时间，默认 30
    private Integer memoryLimitMb;      // 内存限制，默认 2048
}
```

### 6.3 Python 执行器

**实际运行位置**：Python 节点与 SQL/SYNC 节点一样，由 DolphinScheduler Worker 通过 HTTP 回调到 `data-nest-engineering`，
`engineering-service` 在本地通过 `ProcessBuilder` 调用容器内的 `python3` 执行脚本。即 **Python 进程运行在
`data-nest-engineering` 容器内**。

Sprint 4 采用该方案的理由：

- 与现有 DS HTTP 回调架构完全一致，不新增服务。
- 实现简单，调度、状态回写、元数据注册都在 engineering-service 内完成。

风险与缓解：

- **资源争抢**：用户脚本可能占用大量 CPU/内存；通过超时、内存限制、独立子进程缓解。
- **安全风险**：用户代码与工程服务同容器；通过白名单 API、禁止文件/网络/子进程、临时沙箱目录缓解。

> 如果后续对安全隔离要求更高，可演进为独立的 `data-nest-python-worker` 容器，DS 回调直接打到 worker，worker 通过 gRPC/HTTP
> 回写状态到 engineering-service。Sprint 4 不引入该 worker。

Python 脚本在独立进程中执行，通过临时文件传递脚本，通过 stdout/stderr 捕获输出。

```java

@Service
public class PythonExecutor {

    private static final int DEFAULT_TIMEOUT_MINUTES = 30;
    private static final int DEFAULT_MEMORY_MB = 2048;

    @Value("${datanest.python.sandbox:/tmp/datanest-python-sandbox}")
    private String sandboxBase;

    /**
     * 执行 Python 脚本。
     *
     * @param script      脚本内容
     * @param context     注入的内置 helper 与参数上下文
     * @param timeoutMin  超时分钟
     * @param memoryMb    内存限制（用于容器/ulimit 场景）
     * @return 执行结果
     */
    public PythonExecuteResult execute(String script, PythonContext context,
                                       Integer timeoutMin, Integer memoryMb) {
        Path sandbox = createSandbox();
        Path scriptFile = sandbox.resolve("task.py");
        Path outputFile = sandbox.resolve("output.json");

        // 1. 注入内置 helper
        String wrappedScript = wrapScript(script, context, outputFile);
        Files.writeString(scriptFile, wrappedScript);

        // 2. 执行
        List<String> command = List.of("python3", scriptFile.toString());
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(sandbox.toFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();
        boolean finished = process.waitFor(
                Optional.ofNullable(timeoutMin).orElse(DEFAULT_TIMEOUT_MINUTES),
                TimeUnit.MINUTES);

        if (!finished) {
            process.destroyForcibly();
            return PythonExecuteResult.timeout();
        }

        int exitCode = process.exitValue();
        String stdout = readOutput(process.getInputStream());
        String stderr = readFileIfExists(outputFile);

        // 3. 清理
        deleteSandbox(sandbox);

        return PythonExecuteResult.of(exitCode, stdout, stderr);
    }

    private String wrapScript(String userScript, PythonContext context, Path outputFile) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Built-in helpers\n");
        sb.append("import json\n");
        sb.append("import sys\n");
        sb.append("_OUTPUT_PATH = \"").append(outputFile).append("\"\n");
        sb.append("\n");
        sb.append("def _log(message):\n");
        sb.append("    print(f\"[LOG] {message}\", flush=True)\n");
        sb.append("\n");
        sb.append("def get_param(name):\n");
        sb.append("    return _PARAMS.get(name)\n");
        sb.append("\n");
        sb.append("_PARAMS = ").append(jsonParams(context.getParams())).append("\n");
        sb.append("\n");
        sb.append("# User script begins\n");
        sb.append(userScript);
        return sb.toString();
    }
}
```

### 6.4 内置 helper

Sprint 4 提供以下内置函数，通过脚本包裹注入：

| 函数                           | 说明          | 实现要点                                                                   |
|--------------------------------|---------------|----------------------------------------------------------------------------|
| `get_param(name)`              | 获取 DAG 参数 | 从上下文 `_PARAMS` 字典读取                                                |
| `log(message)`                 | 输出日志      | 打印 `[LOG] message`，engineering-service 捕获后写入 `node_execution_log`  |
| `read_doris_table(table)`      | 读取 Doris 表 | 通过 JDBC 查询后返回 pandas DataFrame                                      |
| `write_doris_table(df, table)` | 写入 Doris 表 | 将 pandas DataFrame 通过 JDBC/Stream Load 写入，并记录目标表用于元数据注册 |

### 6.5 回调接口

```java

@RestController
@RequestMapping("/engineering/dev/internal")
public class PythonCallbackController {

    private final PythonExecutor pythonExecutor;
    private final DagExecutionService dagExecutionService;
    private final MetadataRegistrationService metadataRegistrationService;

    @PostMapping("/python/callback")
    public ResponseEntity<Void> callback(@RequestBody PythonCallbackRequest request) {
        // 1. 替换参数
        Dag dag = dagService.getById(request.getDagId());
        DagNode node = dagNodeMapper.selectByDagIdAndNodeId(request.getDagId(), request.getNodeId());
        PythonNodeConfig config = parseConfig(node.getConfig());
        String script = dagParameterService.replacePlaceholders(
                config.getPythonScript(), dag.getId(), request.getExecutionId());

        // 2. 执行
        PythonContext context = buildContext(dag, request);
        PythonExecuteResult result = pythonExecutor.execute(
                script, context, config.getTimeoutMinutes(), config.getMemoryLimitMb());

        // 3. 更新节点状态
        nodeExecutionService.finish(request.getExecutionId(), request.getNodeId(),
                result.isSuccess() ? "SUCCESS" : "FAILED",
                result.getStdout(), result.getStderr(), result.getOutputTables());

        // 4. 元数据注册
        if (result.isSuccess() && !result.getOutputTables().isEmpty()) {
            for (String table : result.getOutputTables()) {
                metadataRegistrationService.registerFromPython(table, request.getOperatorId(),
                        request.getDagId(), request.getNodeId());
            }
        }

        // 5. DS 通过 HTTP 状态码判断任务成败
        return ResponseEntity.ok().build();
    }
}
```

### 6.6 运行测试

测试执行与正式执行使用同一 `PythonExecutor`，但不注册元数据、不影响 DAG 执行状态。

```java

@Service
public class PythonEditorService {

    public PythonExecuteResult test(String script, Long dagId, Map<String, Object> params) {
        PythonContext context = new PythonContext(params);
        return pythonExecutor.execute(script, context, 30, 2048);
    }
}
```

---

## 7. DAG 参数化

### 7.1 数据模型

```java
// DagParameter.java
@Data
@TableName("dag_parameter")
public class DagParameter {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long dagId;
    private String paramName;      // 参数名，DAG 内唯一
    private String paramType;      // STRING / NUMBER / DATE / BOOLEAN
    private String defaultValue;   // 默认值
    private Integer required;      // 0 / 1
    private String description;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

### 7.2 参数替换服务

替换时机：DAG 执行前（手动触发或 Cron 触发）统一替换节点配置中的占位符。

```java

@Service
public class DagParameterService {

    private final DagParameterMapper dagParameterMapper;

    /**
     * 获取最终参数值：手动传入 > 默认值 > 系统变量。
     */
    public Map<String, Object> resolveParams(Long dagId, Map<String, Object> manualOverrides) {
        List<DagParameter> params = dagParameterMapper.selectByDagId(dagId);
        Map<String, Object> result = new HashMap<>();
        for (DagParameter p : params) {
            Object value = manualOverrides != null && manualOverrides.containsKey(p.getParamName())
                    ? manualOverrides.get(p.getParamName())
                    : p.getDefaultValue();
            result.put(p.getParamName(), value);
        }
        // 系统变量
        result.put("biz_date", resolveBizDate(manualOverrides));
        result.put("current_time", LocalDateTime.now().toString());
        result.put("dag_id", dagId);
        return result;
    }

    /**
     * 替换字符串中的 ${paramName} 占位符。
     */
    public String replacePlaceholders(String raw, Long dagId, Long executionId) {
        DagExecution execution = dagExecutionMapper.selectById(executionId);
        Map<String, Object> params = execution.getResolvedParams(); // JSONB
        if (params == null) return raw;

        String result = raw;
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String placeholder = "${" + entry.getKey() + "}";
            String value = escapeSqlString(String.valueOf(entry.getValue()));
            result = result.replace(placeholder, value);
        }
        return result;
    }

    private String escapeSqlString(String value) {
        if (value == null) return "null";
        return value.replace("'", "''");
    }
}
```

### 7.3 手动触发参数覆盖

```java

@PostMapping("/{id}/trigger")
public Result<DagExecutionDTO> trigger(@PathVariable Long id,
                                       @RequestBody(required = false) Map<String, Object> params) {
    Dag dag = dagService.getById(id);
    Map<String, Object> resolved = dagParameterService.resolveParams(id, params);
    return Result.ok(dagExecutionService.startManual(dag, resolved));
}
```

### 7.4 Cron 触发

Cron 触发时使用默认值，不弹窗覆盖。调度器在创建 execution 前调用 `resolveParams(dagId, null)`。

---

## 8. DAG 运行监控与邮件告警

> **设计决策**：告警配置归属 `engineering-service`（选择 3A），邮件发送采用 Spring Mail + 外部 SMTP（选择 4A）。

### 8.1 数据模型

```java
// DagAlertConfig.java
@Data
@TableName("dag_alert_config")
public class DagAlertConfig {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Integer enabled;              // 0 / 1
    private String recipients;            // 分号分隔的邮箱
    private String triggerConditions;     // JSON: ["FAILURE", "TIMEOUT", "SUCCESS"]
    private Integer timeoutMinutes;       // 节点超时阈值
    private Long dagId;                   // 所属 DAG ID；null 表示全局默认配置
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

**配置策略**：

- `dag_id IS NULL` 的记录为全局默认配置，最多一条。
- `dag_id IS NOT NULL` 的记录为某 DAG 的专用配置，覆盖全局默认。
- 告警触发时先查专用配置，不存在再回退全局配置。

**数据库迁移**：

```sql
-- V3.3.10__dag_alert_config_dag_id.sql
ALTER TABLE dag_alert_config
    ADD COLUMN IF NOT EXISTS dag_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_dag_alert_config_dag_id ON dag_alert_config (dag_id);
```

### 8.2 告警触发

在 `DagExecutionService` 的节点状态更新流程中埋点。告警配置按 DAG 解析：优先取该 DAG 的专用配置，无则回退全局默认配置。

```java

@Service
public class DagAlertService {

    private final DagAlertConfigMapper dagAlertConfigMapper;
    private final DagAlertHistoryMapper dagAlertHistoryMapper;
    private final DagMapper dagMapper;
    private final DagExecutionMapper dagExecutionMapper;
    private final MailService mailService;

    /**
     * 按 DAG 解析告警配置：优先专用配置，无则回退全局默认。
     */
    public DagAlertConfig resolveConfig(Long dagId) {
        if (dagId != null) {
            DagAlertConfig dedicated = dagAlertConfigMapper.selectByDagId(dagId);
            if (dedicated != null) {
                return dedicated;
            }
        }
        return dagAlertConfigMapper.selectGlobal();
    }

    public void onDagFailed(DagExecution execution, List<NodeExecution> failedNodes) {
        DagAlertConfig config = resolveConfig(execution.getDagId());
        if (config == null || config.getEnabled() != 1) return;
        if (!triggerConditions(config).contains("FAILURE")) return;
        if (dagAlertHistoryMapper.exists(execution.getId(), null, "FAILURE")) return;

        Dag dag = dagMapper.selectById(execution.getDagId());
        String dagName = dag == null ? "未知 DAG" : dag.getName();
        String subject = String.format("[DataNest 告警] DAG「%s」执行失败", dagName);
        String body = buildFailureBody(execution, failedNodes, dagName);
        mailService.send(config.getRecipients(), subject, body);
        dagAlertHistoryMapper.insert(execution.getId(), null, "FAILURE", config.getRecipients());
    }

    public void onNodeTimeout(NodeExecution node, Long dagId) {
        DagAlertConfig config = resolveConfig(dagId);
        if (config == null || config.getEnabled() != 1) return;
        if (!triggerConditions(config).contains("TIMEOUT")) return;
        if (dagAlertHistoryMapper.exists(node.getExecutionId(), node.getNodeId(), "TIMEOUT")) return;

        Dag dag = dagMapper.selectById(dagId);
        String dagName = dag == null ? "未知 DAG" : dag.getName();
        DagExecution execution = dagExecutionMapper.selectById(node.getExecutionId());
        String executionTime = format(execution != null ? execution.getStartTime() : null);

        String subject = String.format("[DataNest 告警] DAG「%s」节点执行超时", dagName);
        String body = String.join("\n",
                "DAG：" + dagName,
                "执行时间：" + executionTime,
                "节点：" + node.getNodeName() + "（" + node.getNodeId() + "）",
                "节点类型：" + node.getNodeType(),
                "开始时间：" + format(node.getStartTime()),
                "当前状态：RUNNING",
                "查看详情：" + buildExecutionUrl(node.getExecutionId()));

        mailService.send(config.getRecipients(), subject, body);
        dagAlertHistoryMapper.insert(node.getExecutionId(), node.getNodeId(), "TIMEOUT", config.getRecipients());
    }
}
```

**告警配置接口**：

- 全局配置：
    - `GET /dev/alert-config`
    - `PUT /dev/alert-config`
- 按 DAG 配置（覆盖全局）：
    - `GET /dev/dags/{dagId}/alert-config`
    - `PUT /dev/dags/{dagId}/alert-config`

### 8.3 邮件服务

```java

@Service
public class MailService {

    @Value("${spring.mail.host}")
    private String mailHost;

    @Value("${spring.mail.from}")
    private String from;

    public void send(String recipients, String subject, String body) {
        if (!StringUtils.hasText(recipients)) return;
        String[] tos = recipients.split(";");
        // 使用 JavaMailSender 异步发送
        for (String to : tos) {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(to.trim());
            message.setSubject(subject);
            message.setText(body);
            javaMailSender.send(message);
        }
    }
}
```

### 8.3.1 邮件配置

在 `data-nest-engineering` 的 `application.yml` 或 Nacos 配置中增加：

```yaml
spring:
  mail:
    host: smtp.example.com
    port: 587
    username: datanest@example.com
    password: ${MAIL_PASSWORD}
    from: datanest@example.com
    properties:
      mail.smtp.auth: true
      mail.smtp.starttls.enable: true
```

> 密码通过环境变量注入，不提交到代码仓库。

### 8.3.2 XXL-JOB 任务注册

在 `data-nest-job` 的 `JobRegistrar` 启动注册列表中增加新任务：

```java

@Value("${datanest.job.dag-timeout-alert.cron:0 * * * * ?}")
private String dagTimeoutAlertCron;

platformJobs.

put("dagNodeTimeoutAlertHandler",dagTimeoutAlertCron);
```

`resolveJobDesc` 增加：

```java
case"dagNodeTimeoutAlertHandler"->"DAG 节点超时告警扫描";
```

### 8.4 实时日志

执行详情页选中节点后，每 3 秒轮询日志接口：

```java

@GetMapping("/engineering/dev/executions/{executionId}/nodes/{nodeId}/logs")
public Result<List<NodeExecutionLogDTO>> logs(@PathVariable Long executionId,
                                              @PathVariable String nodeId) {
    return Result.ok(nodeExecutionLogService.query(executionId, nodeId));
}
```

日志来源：

- SQL 节点：`node_execution` 的 `error_message` + `output_info`。
- Python 节点：`node_execution_log` 表（stdout/stderr 逐行写入）。
- SYNC 节点：`sync_job_log` 表，通过 `node_execution.sync_job_history_id` 关联。

### 8.5 超时告警定时任务

**失败告警**在节点状态变为 FAILED 时由 `DagAlertService.onDagFailed()` 实时触发，属于事件驱动，不需要定时任务。

**超时告警**需要定时扫描 RUNNING 节点，因此新增 XXL-JOB 定时任务。扫描时按节点所属 DAG 取告警配置，并应用该 DAG 的
`timeout_minutes`：

```java

@Component
public class DagNodeTimeoutAlertHandler {

    private static final Logger logger = LoggerFactory.getLogger(DagNodeTimeoutAlertHandler.class);
    private static final int BATCH_LIMIT = 100;
    private static final int DEFAULT_TIMEOUT_MINUTES = 30;

    private final NodeExecutionMapper nodeExecutionMapper;
    private final DagAlertService dagAlertService;

    @XxlJob("dagNodeTimeoutAlertHandler")
    public void scan() {
        // 查询 RUNNING 节点，并通过 JOIN dag_execution 附带 dag_id
        List<NodeExecution> runningNodes = nodeExecutionMapper.selectRunningWithDagId(BATCH_LIMIT);
        if (runningNodes.isEmpty()) {
            XxlJobHelper.handleSuccess("无运行中节点");
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        int sent = 0;
        for (NodeExecution node : runningNodes) {
            try {
                // 按 DAG 取告警配置（专用 > 全局）
                DagAlertConfig config = dagAlertService.resolveConfig(node.getDagId());
                if (config == null || config.getEnabled() != 1 ||
                        !triggerConditions(config).contains("TIMEOUT")) {
                    continue;
                }

                int thresholdMinutes = config.getTimeoutMinutes() == null
                        ? DEFAULT_TIMEOUT_MINUTES : config.getTimeoutMinutes();
                LocalDateTime threshold = now.minusMinutes(thresholdMinutes);

                if (node.getStartTime() != null && node.getStartTime().isBefore(threshold)) {
                    dagAlertService.onNodeTimeout(node, node.getDagId());
                    sent++;
                }
            } catch (Exception e) {
                logger.error("发送节点超时告警失败: executionId={}, nodeId={}",
                        node.getExecutionId(), node.getNodeId(), e);
            }
        }

        XxlJobHelper.handleSuccess("扫描完成: runningNodes=" + runningNodes.size() + ", sent=" + sent);
    }
}
```

**关键说明**：

- `NodeExecution` 增加 transient 字段 `dagId`，由 `selectRunningWithDagId` 通过 `node_execution JOIN dag_execution` 映射。
- 每个节点独立按 DAG 配置判断：不同 DAG 可设置不同超时阈值和收件人。
- 防重发由 `DagAlertService.onNodeTimeout` 内部通过 `dag_alert_history` 保证。

**调度周期**：默认每 1 分钟执行一次，在 `data-nest-job` 的 `application.yml` 配置：

```yaml
datanest:
  job:
    dag-timeout-alert:
      cron: ${DAG_TIMEOUT_ALERT_CRON:0 * * * * ?}
```

---

## 9. SQL 血缘自动上报

### 9.1 数据模型

```java
// LineageRecord.java
@Data
@TableName("lineage_record")
public class LineageRecord {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String sourceTable;        // 源表，可能为 null
    private String targetTable;        // 目标表
    private Long dagId;
    private String dagName;
    private String nodeId;
    private String nodeName;
    private Long executionId;
    private String lineageType;        // SQL / SYNC / PYTHON
    private LocalDateTime createdAt;
}
```

### 9.2 血缘提取

复用 JSqlParser，在 `MetadataRegistrationService.registerFromSql` 成功后提取：

```java

@Service
public class SqlLineageExtractor {

    public List<LineageRecord> extract(String sql, Long dagId, String nodeId,
                                       Long executionId, String dagName, String nodeName) {
        List<LineageRecord> records = new ArrayList<>();
        String[] statements = sql.split(";");
        for (String stmt : statements) {
            if (!StringUtils.hasText(stmt)) continue;
            try {
                Statement parsed = CCJSqlParserUtil.parse(stmt);
                if (parsed instanceof CreateTable) {
                    CreateTable ct = (CreateTable) parsed;
                    String target = extractTableName(ct.getTable());
                    List<String> sources = extractSelectTables(ct.getSelect());
                    for (String source : sources) {
                        records.add(buildRecord(source, target, dagId, nodeId, executionId, dagName, nodeName));
                    }
                } else if (parsed instanceof Insert) {
                    Insert insert = (Insert) parsed;
                    String target = extractTableName(insert.getTable());
                    List<String> sources = extractSelectTables(insert.getSelect());
                    for (String source : sources) {
                        records.add(buildRecord(source, target, dagId, nodeId, executionId, dagName, nodeName));
                    }
                }
            } catch (JSQLParserException e) {
                // 解析失败不上报
            }
        }
        return records;
    }
}
```

### 9.3 上报方式

**采用方案 A（本地落表）**：`engineering-service` 在 SQL/Python 节点执行成功后直接写入 `lineage_record`，
`governance-service` 只提供查询/展示接口。

理由：

- 避免 DAG 执行链路跨服务同步调用失败。
- governance-service 后续做可视化、影响分析时直接读取 `lineage_record`。

写入示例：

```java
lineageRecordMapper.insertBatch(records);
```

### 9.4 元数据详情展示

`metadata_table` 新增来源字段：

```sql
ALTER TABLE metadata_table
    ADD COLUMN source_type VARCHAR(32),   -- SYNC / SQL / PYTHON
    ADD COLUMN source_dag_id BIGINT,
    ADD COLUMN source_dag_name VARCHAR(255),
    ADD COLUMN source_node_id VARCHAR(64),
    ADD COLUMN source_node_name VARCHAR(255);
```

`MetadataRegistrationService` 在注册/刷新表时写入来源信息。

---

## 10. DAG 版本管理

### 10.1 数据模型

```java
// DagVersion.java
@Data
@TableName("dag_version")
public class DagVersion {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long dagId;
    private Integer versionNo;           // v1=1, v2=2...
    private String snapshot;             // JSON：{nodes, edges, params}
    private String changeSummary;        // 变更摘要
    private Long createdBy;
    private LocalDateTime createdAt;
}
```

### 10.2 版本服务

```java

@Service
public class DagVersionService {

    private final DagVersionMapper dagVersionMapper;
    private final DagNodeMapper dagNodeMapper;
    private final DagEdgeMapper dagEdgeMapper;
    private final DagParameterMapper dagParameterMapper;

    /**
     * 保存时生成新版本。
     */
    @Transactional
    public DagVersion createVersion(Long dagId) {
        List<DagNode> nodes = dagNodeMapper.selectByDagId(dagId);
        List<DagEdge> edges = dagEdgeMapper.selectByDagId(dagId);
        List<DagParameter> params = dagParameterMapper.selectByDagId(dagId);

        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("nodes", nodes);
        snapshot.put("edges", edges);
        snapshot.put("params", params);

        Integer nextVersion = dagVersionMapper.selectMaxVersionNo(dagId) + 1;

        DagVersion version = new DagVersion();
        version.setDagId(dagId);
        version.setVersionNo(nextVersion);
        version.setSnapshot(JsonUtils.toJson(snapshot));
        version.setChangeSummary(generateChangeSummary(dagId, snapshot));
        version.setCreatedBy(currentUserId());
        version.setCreatedAt(LocalDateTime.now());
        dagVersionMapper.insert(version);
        return version;
    }

    /**
     * 回滚到指定版本：生成一个与目标版本内容一致的新版本。
     */
    @Transactional
    public DagVersion rollback(Long dagId, Integer targetVersionNo) {
        DagVersion target = dagVersionMapper.selectByDagIdAndVersionNo(dagId, targetVersionNo);
        if (target == null) throw new BusinessException(ErrorCode.DAG_VERSION_NOT_FOUND);

        // 清空当前节点/边/参数
        dagNodeMapper.deleteByDagId(dagId);
        dagEdgeMapper.deleteByDagId(dagId);
        dagParameterMapper.deleteByDagId(dagId);

        // 恢复快照
        restoreSnapshot(dagId, target.getSnapshot());

        // 生成新版本
        return createVersion(dagId);
    }
}
```

### 10.3 版本对比

对比两个版本的 snapshot JSON，生成结构化差异：

```java
public class DagVersionDiff {
    private List<String> addedNodes;
    private List<String> removedNodes;
    private List<String> modifiedNodes;
    private List<String> addedEdges;
    private List<String> removedEdges;
    private List<String> addedParams;
    private List<String> removedParams;
    private List<String> modifiedParams;
}
```

---

## 11. 真正的重跑失败节点

### 11.1 现状

Sprint 3 MVP 中 `DagExecutionService.rerunFailed()` 直接 `trigger(dagId)` 全量重跑所有节点。

### 11.2 Sprint 4 目标

仅重新执行 FAILED / SKIPPED 节点，上游成功节点结果复用。

### 11.3 实现方案

**采用方案 A：动态生成临时 ProcessDefinition**。

由于 DS 调度以 ProcessDefinition 为准，Sprint 4 的实现步骤：

1. 查询原 execution 的所有 `node_execution`，标记 FAILED/SKIPPED 节点。
2. 基于原 DAG 的节点/边快照，构建一个临时 ProcessDefinition：
    - 保留所有节点和边，确保依赖关系不变。
    - 对上游已成功节点，通过 DS 的 `startNodeIdList` 或前置任务状态标记为已完成，使其不再执行。
3. 触发新的 DS ProcessInstance，只执行 FAILED/SKIPPED 节点及其必要下游。
4. engineering-service 回调时，按正常流程执行节点；已成功节点不会收到回调。

核心实现：

```java

@Service
public class DagExecutionService {

    /**
     * 真正的重跑失败节点。
     */
    @Transactional
    public DagExecutionDTO rerunFailed(Long dagId, Long executionId) {
        DagExecution oldExecution = dagExecutionMapper.selectById(executionId);
        List<NodeExecution> oldNodes = nodeExecutionMapper.selectByExecutionId(executionId);

        // 1. 校验：executionId 属于 dagId，且不在执行中
        validateRerun(dagId, oldExecution);

        // 2. 复制 execution 记录
        DagExecution newExecution = copyExecution(oldExecution);
        dagExecutionMapper.insert(newExecution);

        // 3. 复制 node_execution：FAILED/SKIPPED 重置为 WAITING，SUCCESS 保持 SUCCESS
        for (NodeExecution ne : oldNodes) {
            NodeExecution newNode = copyNodeExecution(ne, newExecution.getId());
            if ("FAILED".equals(ne.getStatus()) || "SKIPPED".equals(ne.getStatus())) {
                newNode.setStatus("WAITING");
            }
            nodeExecutionMapper.insert(newNode);
        }

        // 4. 触发 DS：传入需要重跑的节点列表
        List<String> rerunNodeIds = oldNodes.stream()
                .filter(n -> "FAILED".equals(n.getStatus()) || "SKIPPED".equals(n.getStatus()))
                .map(NodeExecution::getNodeId)
                .toList();
        dolphinSchedulerClient.restartFailedProcess(oldExecution.getDsProcessInstanceId(), rerunNodeIds);

        return toDTO(newExecution);
    }
}
```

> 注：DS 3.4.2 对“只重跑失败节点”原生支持有限，可能需要通过动态生成临时 ProcessDefinition 实现。上述代码为逻辑示意，具体实现需根据
> DS API 调整。

---

## 12. 多表同步与速率限流收尾

### 12.1 后端现状

- `SyncJobService.copyFromRequest()` 已处理 `sourceTablesDetail`、`readRateLimitMbps`、`writeRateLimitRowsPerSecond`、
  `rateLimitEnabled`。
- `AddaxJobService.generateJobJson()` 已按 `sourceTables` 多表生成 Addax content，并已应用 rate limit 字段。

### 12.2 前端改造点

1. **`src/types/sync.ts`**：扩展 `SyncJobCreateRequest` / `SyncJob` 类型：

```typescript
export interface SyncJobCreateRequest {
    // ... 现有字段
    sourceTablesDetail?: SourceTableDetail[];
    readRateLimitMbps?: number;
    writeRateLimitRowsPerSecond?: number;
    rateLimitEnabled?: boolean;
}

export interface SourceTableDetail {
    sourceTable: string;
    targetTable: string;
    fieldMapping?: SyncFieldMapping[];   // 每个源表独立配置字段映射
}
```

2. **`SyncJobDrawer`**：
    - 源表下拉框改为多选。
    - 选中多表后展示“源表 → 目标表映射”表格，每行可配置目标表名。
    - 字段映射按源表逐个配置：选中某个源表后，展示该源表的字段映射关系。
    - 在“容错配置”下方新增“限流配置”区域。

3. **保存前校验**：
    - 每个 `sourceTablesDetail` 项必须包含 `sourceTable`、`targetTable`、`fieldMapping`。
    - 字段映射不能为空；不同源表之间不做强制同名同型校验，由各表独立配置保证正确性。
    - 多表模式下必须填写 `sourceTablesDetail`。

---

## 13. 数据库设计

### 13.1 新增/变更表总览

| 表名                 | 变更类型       | 说明                                                    |
|----------------------|----------------|---------------------------------------------------------|
| `dag_node`           | 扩展           | `node_type` 扩展为 SQL/SYNC/PYTHON                      |
| `node_execution`     | 扩展           | `node_type` 扩展为 SQL/SYNC/PYTHON                      |
| `dag_execution`      | 扩展           | 新增 `resolved_params` JSONB 存储本次执行解析后的参数值 |
| `dag_parameter`      | 新增           | DAG 自定义参数                                          |
| `dag_version`        | 新增           | DAG 版本快照                                            |
| `dag_alert_config`   | 新增           | 全局 DAG 告警配置                                       |
| `dag_alert_history`  | 新增           | 已发送告警记录（防重发）                                |
| `node_execution_log` | 新增           | Python/SQL 节点日志行                                   |
| `lineage_record`     | 新增           | 表级血缘记录                                            |
| `metadata_table`     | 扩展           | 新增来源字段                                            |
| `sync_job`           | 不变（已就绪） | 前端使用现有 `source_tables_detail`、限流字段           |

### 13.2 Flyway 迁移脚本

**V3.3.0__extend_dag_node_python.sql**

```sql
-- 扩展 dag_node.node_type 注释
COMMENT
ON COLUMN dag_node.node_type IS '节点类型：SQL SQL 任务，SYNC 同步任务，PYTHON Python 任务';
COMMENT
ON COLUMN node_execution.node_type IS '节点类型：SQL / SYNC / PYTHON';

-- 若存在 CHECK 约束需调整
-- ALTER TABLE dag_node DROP CONSTRAINT IF EXISTS chk_dag_node_node_type;
-- ALTER TABLE dag_node ADD CONSTRAINT chk_dag_node_node_type CHECK (node_type IN ('SQL', 'SYNC', 'PYTHON'));
```

**V3.3.1__dag_parameter.sql**

```sql
CREATE TABLE dag_parameter
(
    id            BIGSERIAL PRIMARY KEY,
    dag_id        BIGINT      NOT NULL,
    param_name    VARCHAR(64) NOT NULL,
    param_type    VARCHAR(16) NOT NULL CHECK (param_type IN ('STRING', 'NUMBER', 'DATE', 'BOOLEAN')),
    default_value VARCHAR(255),
    required      SMALLINT    NOT NULL DEFAULT 1,
    description   VARCHAR(500),
    created_by    BIGINT,
    updated_by    BIGINT,
    created_at    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_dag_param_name UNIQUE (dag_id, param_name)
);

COMMENT
ON TABLE dag_parameter IS 'DAG 自定义参数';
```

**V3.3.2__dag_version.sql**

```sql
CREATE TABLE dag_version
(
    id             BIGSERIAL PRIMARY KEY,
    dag_id         BIGINT    NOT NULL,
    version_no     INT       NOT NULL,
    snapshot       TEXT      NOT NULL,
    change_summary VARCHAR(500),
    created_by     BIGINT,
    created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_dag_version UNIQUE (dag_id, version_no)
);

COMMENT
ON TABLE dag_version IS 'DAG 版本快照';
```

**V3.3.3__dag_alert_config.sql**

```sql
CREATE TABLE dag_alert_config
(
    id                 BIGSERIAL PRIMARY KEY,
    enabled            SMALLINT  NOT NULL DEFAULT 0,
    recipients         VARCHAR(1000),
    trigger_conditions VARCHAR(255), -- JSON 数组字符串
    timeout_minutes    INT       NOT NULL DEFAULT 30,
    created_by         BIGINT,
    updated_by         BIGINT,
    created_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT
ON TABLE dag_alert_config IS '全局 DAG 告警配置';
```

**V3.3.4__dag_alert_history.sql**

```sql
CREATE TABLE dag_alert_history
(
    id           BIGSERIAL PRIMARY KEY,
    execution_id BIGINT      NOT NULL,
    node_id      VARCHAR(64), -- 超时告警必填，DAG 级失败告警可为空
    alert_type   VARCHAR(16) NOT NULL CHECK (alert_type IN ('FAILURE', 'TIMEOUT', 'SUCCESS')),
    recipients   VARCHAR(1000),
    sent_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_dag_alert_history_en_type UNIQUE (execution_id, node_id, alert_type)
);

CREATE INDEX idx_dag_alert_history_execution ON dag_alert_history (execution_id);
COMMENT
ON TABLE dag_alert_history IS 'DAG 告警发送记录（防重发）';
```

**V3.3.5__node_execution_log.sql**

```sql
CREATE TABLE node_execution_log
(
    id           BIGSERIAL PRIMARY KEY,
    execution_id BIGINT      NOT NULL,
    node_id      VARCHAR(64) NOT NULL,
    level        VARCHAR(16) NOT NULL CHECK (level IN ('INFO', 'WARN', 'ERROR')),
    message      TEXT        NOT NULL,
    line_num     INT         NOT NULL DEFAULT 0,
    created_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_node_execution_log_en ON node_execution_log (execution_id, node_id);
COMMENT
ON TABLE node_execution_log IS 'DAG 节点执行日志';
```

**V3.3.6__lineage_record.sql**

```sql
CREATE TABLE lineage_record
(
    id           BIGSERIAL PRIMARY KEY,
    source_table VARCHAR(500),
    target_table VARCHAR(500) NOT NULL,
    dag_id       BIGINT,
    dag_name     VARCHAR(255),
    node_id      VARCHAR(64),
    node_name    VARCHAR(255),
    execution_id BIGINT,
    lineage_type VARCHAR(16)  NOT NULL DEFAULT 'SQL',
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_lineage_target ON lineage_record (target_table);
CREATE INDEX idx_lineage_dag ON lineage_record (dag_id);
COMMENT
ON TABLE lineage_record IS '表级血缘记录';
```

**V3.3.7__metadata_table_source.sql**

```sql
ALTER TABLE metadata_table
    ADD COLUMN source_type VARCHAR(32),
    ADD COLUMN source_dag_id BIGINT,
    ADD COLUMN source_dag_name VARCHAR(255),
    ADD COLUMN source_node_id VARCHAR(64),
    ADD COLUMN source_node_name VARCHAR(255);

COMMENT
ON COLUMN metadata_table.source_type IS '来源类型：SYNC / SQL / PYTHON';
```

**V3.3.8__dag_execution_params.sql**

```sql
ALTER TABLE dag_execution
    ADD COLUMN resolved_params JSONB DEFAULT '{}';

COMMENT
ON COLUMN dag_execution.resolved_params IS '本次执行解析后的参数值（手动覆盖 + 默认值 + 系统变量）';
```

---

## 14. API 接口设计

### 14.1 Python 节点测试

```
POST /engineering/dev/dags/{dagId}/nodes/{nodeId}/python/test
Body: { "pythonScript": "...", "params": {} }
Response: { "success": true, "exitCode": 0, "stdout": "...", "stderr": "...", "durationMs": 8000 }
```

### 14.2 DAG 参数

```
GET    /engineering/dev/dags/{dagId}/parameters
POST   /engineering/dev/dags/{dagId}/parameters
PUT    /engineering/dev/dags/{dagId}/parameters/{id}
DELETE /engineering/dev/dags/{dagId}/parameters/{id}
```

### 14.3 DAG 版本

```
GET    /engineering/dev/dags/{dagId}/versions
POST   /engineering/dev/dags/{dagId}/versions/{versionNo}/rollback
GET    /engineering/dev/dags/{dagId}/versions/compare?left=2&right=3
```

### 14.4 DAG 告警配置

```
GET  /engineering/dev/alert-config
PUT  /engineering/dev/alert-config
```

### 14.5 节点实时日志

```
GET /engineering/dev/executions/{executionId}/nodes/{nodeId}/logs
```

### 14.6 血缘查询

```
GET /governance/lineage/target/{tableName}
GET /governance/lineage/dag/{dagId}
```

### 14.7 执行触发（参数覆盖）

> 现状：`DagController` 使用 `POST /dev/dags/{id}/trigger`。Sprint 4 保持该路径，扩展请求体支持参数覆盖。

```
POST /engineering/dev/dags/{id}/trigger
Body: { "biz_date": "2026-07-31", "env": "prod" }  // 可选，覆盖默认参数
```

### 14.8 重跑失败节点

```
POST /engineering/dev/dags/{dagId}/executions/{executionId}/rerun-failed
Response: DagExecutionDTO
```

### 14.9 内部回调

```
POST /engineering/dev/internal/python/callback
Body: { "dagId": 1, "executionId": 10, "nodeId": "n1", "nodeType": "PYTHON" }
```

---

## 15. 前端设计

### 15.1 页面/组件清单

| 组件                      | 路径                                                    | 说明                   |
|---------------------------|---------------------------------------------------------|------------------------|
| `PythonEditorModal.tsx`   | `pages/engineering/dags/components/`                    | Python 编辑器弹窗      |
| `DagParameterDrawer.tsx`  | `pages/engineering/dags/components/`                    | DAG 参数定义抽屉       |
| `DagVersionModal.tsx`     | `pages/engineering/dags/components/`                    | 版本列表/对比/回滚弹窗 |
| `DagAlertConfigModal.tsx` | `pages/engineering/dags/components/` 或 `pages/system/` | 告警配置弹窗           |
| `SyncJobDrawer.tsx`       | `pages/engineering/sync-jobs/components/`               | 扩展多表/限流          |
| `SqlEditorModal.tsx`      | `pages/engineering/dags/components/`                    | 扩展参数占位符提示     |

### 15.2 Python 编辑器弹窗

- 宽度 900px，高度 600px。
- Monaco Editor（language=python，theme=vs-dark）。
- 工具栏：运行测试、全选、撤销、重做（与 SQL 编辑器一致）。
- 结果区：展示 stdout/stderr/返回码/耗时。

### 15.3 DAG 参数抽屉

- 右侧滑出抽屉。
- 表格：参数名、类型、默认值、必填、描述、操作。
- 系统变量提示区：$biz_date、$current_time、$dag_id。

### 15.4 DAG 版本弹窗

- 列表：版本号、保存时间、保存人、操作（对比、回滚）。
- 对比弹窗：展示节点/边/参数差异。
- 回滚确认：生成新版本，内容等同于目标版本。

### 15.5 执行历史实时日志

- 展开行或右侧抽屉展示微缩 DAG 图（Sprint 3 已实现）。
- 选中节点后，右侧面板显示节点信息与日志区。
- 日志区每 3 秒轮询，RUNNING 时自动刷新。

### 15.6 多表同步表单

- 源表多选下拉框。
- 选中后展示源表 → 目标表映射表格，每行可编辑目标表名。
- 字段映射：按源表逐个配置，选中某个源表后展示该源表的字段映射关系。
- 限流配置：启用复选框 + 读取 MB/s + 写入 行/s。

---

## 16. Sprint 4 ADR

### ADR-S4-001：Python 节点执行方式

- **背景**：需要在 DAG 中执行用户编写的 Python 脚本。
- **决策**：在 engineering-service 容器内调用本地 Python 3 解释器，隔离进程运行。
- **理由**：不引入额外 Python 服务，与现有 DS HTTP 回调架构一致。
- **风险**：脚本可能消耗过多资源；通过超时、内存限制、临时沙箱目录缓解。

### ADR-S4-002：DAG 参数替换时机

- **背景**：SQL/Python 节点需要引用 DAG 级参数。
- **决策**：在 DAG 执行前统一替换占位符，execution 记录中保存解析后的参数值。
- **理由**：DS 任务实例只拿到最终内容，避免 DS 侧感知参数系统。

### ADR-S4-003：血缘存储方式

- **背景**：SQL 节点需要上报 source → target 血缘。
- **决策**：先本地落表 `lineage_record`，governance-service 只读/展示。
- **理由**：避免跨服务同步调用失败影响 DAG 执行；后续可视化和影响分析再消费该表。

### ADR-S4-004：DAG 版本快照策略

- **背景**：需要支持 DAG 版本对比与回滚。
- **决策**：每次保存全量快照（节点、边、参数 JSON）。
- **理由**：Sprint 4 实现简单，diff 计算在内存完成；后续 Sprint 可优化为增量 diff。

### ADR-S4-005：告警配置归属

- **背景**：DAG 失败告警配置放在哪里。
- **决策**：放在 `engineering-service`，由 `DagAlertConfigController` 暴露接口。
- **理由**：告警与 DAG 强相关，后续 Sprint 5 全局告警中心可在此基础上扩展。

---

## 17. 验收标准

### 17.1 功能验收

| #     | 验收项           | 通过标准                                                                |
|-------|------------------|-------------------------------------------------------------------------|
| AC-1  | Python 节点编辑  | 画布可拖入 Python 节点，双击编辑脚本并保存                              |
| AC-2  | Python 运行测试  | 点击运行测试，脚本在隔离进程执行，展示 stdout/stderr                    |
| AC-3  | Python 正式执行  | DAG 执行 → Python 节点成功 → 目标表出现在元数据                         |
| AC-4  | Python 超时终止  | 死循环脚本超过 30 分钟被强制终止                                        |
| AC-5  | DAG 参数定义     | 参数抽屉添加参数后，SQL/Python 节点可用 `${param}` 引用                 |
| AC-6  | 手动触发覆盖参数 | 执行弹窗修改参数后 DAG 按新值执行                                       |
| AC-7  | 系统变量替换     | `${biz_date}` 自动替换为昨天日期                                        |
| AC-8  | DAG 失败邮件告警 | 配置收件人后 DAG 失败，收到含失败节点和摘要的邮件                       |
| AC-9  | 节点超时邮件告警 | 节点超过阈值后收到超时告警邮件                                          |
| AC-10 | 实时日志         | 执行详情页选中节点，日志区每 3 秒刷新                                   |
| AC-11 | SQL 血缘上报     | CTAS/INSERT 执行后 `lineage_record` 出现 source → target 记录           |
| AC-12 | 表来源展示       | 元数据详情页显示来源 DAG/节点                                           |
| AC-13 | DAG 版本生成     | 保存 DAG 后版本列表出现新版本                                           |
| AC-14 | DAG 版本对比     | 选择两个版本展示节点/边/参数差异                                        |
| AC-15 | DAG 版本回滚     | 回滚后生成新版本，画布恢复目标版本内容                                  |
| AC-16 | 重跑失败节点     | 失败执行记录点击重跑失败节点，仅 FAILED/SKIPPED 节点重新执行            |
| AC-17 | 多表同步         | 创建任务选择多个源表，映射目标表名，执行后所有表同步                    |
| AC-18 | 速率限流         | 启用 5MB/s 限流后实际读取速率不超过 5MB/s                               |
| AC-19 | 权限隔离         | 治理员/分析师不能编辑 DAG/参数/Python 节点                              |
| AC-20 | 同步任务引用校验 | 删除被 DAG 引用的同步任务时阻断并列出 DAG 名称（Sprint 3 已实现，回归） |

### 17.2 非功能验收

| #     | 验收项          | 通过标准                                   |
|-------|-----------------|--------------------------------------------|
| NAC-1 | Python 执行安全 | `os.system("rm -rf /")` 被拒绝或处于沙箱内 |
| NAC-2 | 参数替换性能    | 100 个参数替换 < 100ms                     |
| NAC-3 | 告警延迟        | DAG 失败后 1 分钟内发送邮件                |
| NAC-4 | 版本列表性能    | 100 个版本加载 < 2 秒                      |
| NAC-5 | 血缘解析覆盖    | CTAS/INSERT/CREATE VIEW 解析准确率 > 95%   |

---

## 18. 风险与对策

| #  | 风险                           | 影响                   | 对策                                             |
|----|--------------------------------|------------------------|--------------------------------------------------|
| R1 | Python 脚本执行时间不可控      | 占用服务资源           | 超时强制终止 + 内存限制 + 独立进程 + 沙箱目录    |
| R2 | Python 脚本安全风险            | 恶意脚本破坏系统       | 禁止文件/网络/子进程（白名单 API）+ 只读挂载     |
| R3 | 参数替换注入风险               | SQL 注入               | 类型校验 + SQL 字符串转义                        |
| R4 | SQL 血缘解析不支持复杂语法     | 血缘缺失               | Sprint 4 覆盖常见 DDL/DML；复杂语法留 Sprint 5   |
| R5 | 邮件发送失败                   | 告警漏发               | 异步发送 + 失败重试 + 后台日志                   |
| R6 | 多表同步字段类型不一致         | 写入失败               | 保存前预校验同名不同型字段                       |
| R7 | DAG 版本数据膨胀               | 存储成本增加           | Sprint 4 全量保留；后续优化为 diff               |
| R8 | 重跑失败节点与 DS 调度语义冲突 | 实现复杂               | 通过临时 ProcessDefinition 或 DS 原生能力实现    |
| R9 | Python 依赖环境不一致          | 脚本在不同环境表现不同 | Docker 镜像固定 Python 版本；Sprint 4 仅用标准库 |

---

> **版本记录**
> - v1.0 (2026-08-01)：初始版本，基于 Sprint 4 PRD 与现有代码现状编写。
