# DataNest Sprint 2 技术文档

> **Sprint**：Sprint 2 — 批量数据同步任务 + 数据标准
> **文档状态**：Working Draft (v1.0) | **作者**：软件架构师 | **日期**：2026-07-27
> **关联文档**：`DataNest-技术架构文档-v2.3.1.md`、`DataNest-Sprint2-批量数据同步任务与数据标准-PRD.md`、
> `DataNest-Sprint1-技术文档.md`

---

## 目录

1. [Sprint 概述](#1-sprint-概述)
2. [交付物清单](#2-交付物清单)
3. [架构概览](#3-架构概览)
4. [项目结构变更](#4-项目结构变更)
5. [Docker Compose 变更](#5-docker-compose-变更)
6. [engineering-service：批量数据同步任务](#6-engineering-service批量数据同步任务)
7. [engineering-service：数据预览](#7-engineering-service数据预览)
8. [engineering-service：保存后自动采集](#8-engineering-service保存后自动采集)
9. [governance-service：数据标准](#9-governance-service数据标准)
10. [数据库设计](#10-数据库设计)
11. [API 接口设计](#11-api-接口设计)
12. [共享配置变更](#12-共享配置变更)
13. [前端设计](#13-前端设计)
14. [Sprint 2 ADR](#14-sprint-2-adr)
15. [验收标准](#15-验收标准)
16. [风险与对策](#16-风险与对策)

---

## 1. Sprint 概述

### 1.1 Sprint 目标

Sprint 1 接入了数据源、采集了元数据——但数据本身还在源库里。Sprint 2 做两件事：

1. **把数据真正"搬进来"**：支持 MySQL/PostgreSQL/Doris → 内置 Doris 的批量同步，同步完成后自动注册元数据
2. **把治理的第一块砖砌上**：定义命名规范和字段类型标准，对现有元数据执行合规检查

### 1.2 Sprint 范围

| # | 工作项                    | 所属服务                 | 说明                                                       |
|---|---------------------------|--------------------------|------------------------------------------------------------|
| 1 | **Addax 批量同步引擎**    | data-nest-worker         | 基于 `quay.io/wgzhao/addax:6.0.11` Docker 镜像，命令行调用 |
| 2 | **同步任务管理**          | engineering-service      | 创建/编辑/删除/执行同步任务，全量/增量模式，手动/定时触发  |
| 3 | **同步历史与日志**        | engineering-service      | 每次执行的详情、耗时、行数、Addax 原始日志                 |
| 4 | **失败重试与告警**        | data-nest-worker         | 可配置 0-3 次重试 + 间隔；重试耗尽后由 worker 发送告警     |
| 5 | **数据预览**              | engineering-service      | 数据源列表和元数据管理页均可预览前 100 行                  |
| 6 | **保存后自动采集**        | engineering → governance | 数据源保存时勾选「立即采集」，自动创建并执行一次采集任务   |
| 7 | **数据标准管理**          | governance-service       | 命名规范（前缀/后缀/正则）+ 字段类型标准 CRUD              |
| 8 | **合规检查**              | governance-service       | 对指定范围内的元数据执行命名规范和字段类型标准检查         |
| 9 | **内置 Doris 元数据标记** | 数据库                   | `metadata_table` 新增 `source_type` 字段区分内置/外部      |

### 1.3 架构服务关系

```
engineering-service (8082)              governance-service (8084)
├── datasource/     # 数据源管理        ├── metadata/      # 元数据管理
├── **sync/**       # 🆕 批量同步任务 CRUD / 调度注册       ├── collect/       # 采集任务
│   ├── task/       #   任务 CRUD       ├── **standard/**  # 🆕 数据标准
│   └── schedule/   #   XXL-JOB 调度注册 └── compliance/    # 🆕 合规检查
├── **preview/**    # 🆕 数据预览
└── config/         # 连接测试

data-nest-worker (8083)
└── **sync/**       # 🆕 实际执行 Addax 的 XXL-JOB Executor
    ├── addax/      #   Addax 引擎
    ├── executor/   #   XXL-JOB Handler / 日志回写
    └── metadata/   #   同步后元数据注册

共享 PostgreSQL（同一 Schema）
├── datasource_connection     ← engineering / worker / governance 都读
├── metadata_table            ← governance 采集写 / worker 同步后写
├── metadata_column           ← 同上
├── sync_job                 🆕 engineering 读写 / worker 读、更新执行状态
├── sync_history              🆕 engineering 读写 / worker 读写
├── naming_standard           🆕 governance 读写
├── field_type_standard       🆕 governance 读写
└── compliance_check_result   🆕 governance 读写
```

> 两服务共用同一 PostgreSQL 数据库同一 Schema，通过 MyBatis-Plus Mapper 直接读写表。公共能力（密码加解密、JDBC 连接）在
> `data-nest-common` 模块。

### 1.4 不在本 Sprint

| 暂缓项                           | 后续 Sprint |
|----------------------------------|:-----------:|
| 数据转换（行过滤、列计算、Join） |  Sprint 3   |
| 增量同步自动 Schema 变更适配     |  Sprint 3   |
| CDC 实时同步                     |  Sprint 9   |
| 跨数据源多表批量同步             |  Sprint 3   |
| 并发控制                         |  Sprint 3   |
| 数据标准自动修复                 |  Sprint 5   |
| 数据质量规则                     |  Sprint 7   |
| 告警通知渠道 UI 配置             |  Sprint 5   |

---

## 2. 交付物清单

| #  | 交付物                                    | 类型 | 验收方式                                    |
|----|-------------------------------------------|------|---------------------------------------------|
| D1 | engineering-service `sync/` 模块          | 代码 | 同步任务 CRUD + 调度注册 + 历史日志         |
| D2 | engineering-service `preview/` 模块       | 代码 | 数据预览前 100 行                           |
| D3 | governance-service `standard/` 模块       | 代码 | 命名规范 + 字段类型标准 CRUD                |
| D4 | governance-service `compliance/` 模块     | 代码 | 合规检查引擎 + 结果展示                     |
| D5 | Flyway 迁移 V3.0.0 + V3.0.1               | 代码 | 新增表 + metadata_table 加 source_type      |
| D6 | `docker-compose.yml` + `Dockerfile` 更新  | 配置 | 新增 data-nest-worker 容器内嵌 Addax        |
| D7 | shared-configs 新增 `shared-addax.yaml`   | 配置 | Addax 安装路径 + Doris Stream Load 公共参数 |
| D8 | 前端：批量同步 / 数据标准 / 数据预览 页面 | 代码 | 按 PRD 交互可用                             |

---

## 3. 架构概览

### 3.1 同步数据流

```
┌──────────────────────────────────────────────────────────────────┐
│                    Batch Sync Data Flow                          │
│                                                                   │
│  用户触发(手动/定时)                                              │
│       │                                                          │
│       ▼                                                          │
│  ┌─────────────────┐    XXL-JOB     ┌───────────────────────┐   │
│  │ sync-task        │─────────────▶│ SyncJobHandler         │   │
│  │ (任务记录)        │   trigger     │ (XXL-JOB Executor)     │   │
│  └─────────────────┘               └───────────┬───────────┘   │
│                                                │                │
│                                       ┌────────▼──────────┐     │
│                                       │ AddaxJobBuilder    │     │
│                                       │ 1. 生成 job.json   │     │
│                                       │ 2. 字段映射处理     │     │
│                                       │ 3. 增量条件拼接     │     │
│                                       └────────┬──────────┘     │
│                                                │                │
│                                       ┌────────▼──────────┐     │
│                                       │ AddaxExecutor      │     │
│                                       │ ProcessBuilder     │     │
│                                       │ addax.sh job.json  │     │
│                                       └────────┬──────────┘     │
│                                                │                │
│                         ┌──────────────────────┼──────────┐     │
│                         │                Addax Engine      │     │
│                         │  ┌──────────┐     ┌──────────┐  │     │
│                         │  │ Reader   │────▶│  Writer  │  │     │
│                         │  │ mysql/   │     │ doris    │  │     │
│                         │  │ postgre/ │     │ Stream   │  │     │
│                         │  │ doris    │     │ Load     │  │     │
│                         │  └──────────┘     └──────────┘  │     │
│                         └──────────────────────┬──────────┘     │
│                                                │                │
│                                    ┌───────────▼───────────┐    │
│                                    │    同步结果处理         │    │
│                                    │  SUCCESS → 注册元数据   │    │
│                                    │  FAILED  → 重试/告警    │    │
│                                    └───────────────────────┘    │
└──────────────────────────────────────────────────────────────────┘
```

### 3.2 Addax 集成方式

**容器策略**：Addax 是纯 Java 程序，`addax.sh` 是 bash 脚本， **不需要 Python**。但 Addax 镜像自带的 JDK 版本较老，与 Spring
Boot 4.0 的 JDK 21 要求不兼容。采用 **多阶段构建**：从 Addax 镜像提取二进制，JDK 21 镜像跑 Spring Boot。

```dockerfile
# ===== Stage 1: 从 Addax 镜像提取二进制 =====
FROM quay.io/wgzhao/addax:6.0.11 AS addax

# ===== Stage 2: JDK 21 + Addax 二进制 + Spring Boot =====
FROM eclipse-temurin:21-jre

# 从 Stage 1 复制 Addax 全部文件（/opt/addax，包含 bin/addax.sh + plugin/ + lib/）
COPY --from=addax /opt/addax /opt/addax

# 复制 Spring Boot fat jar
COPY target/data-nest-worker-*.jar /app/app.jar

# 启动脚本
COPY docker-entrypoint.sh /docker-entrypoint.sh
RUN chmod +x /docker-entrypoint.sh

ENTRYPOINT ["/docker-entrypoint.sh"]
```

```bash
#!/bin/bash
# docker-entrypoint.sh
export ADDEX_HOME=/opt/addax
export PATH=$ADDEX_HOME/bin:$PATH

# 验证 Addax 可用（纯 sh 脚本，无需 Python）
if [ ! -f "$ADDEX_HOME/bin/addax.sh" ]; then
    echo "ERROR: Addax not found at $ADDEX_HOME"
    exit 1
fi

exec java -jar /app/app.jar
```

**调用方式与实际代码位置**：

- Addax `job.json` 的构建、进程调用、日志收集、结果解析实际位于 **`data-nest-task-core` 的 `AddaxJobService`**。
- XXL-JOB 的 Handler 入口定义在 **`data-nest-worker` 的 `WorkerJobHandler`**（`@XxlJob("syncJobHandler")`），它接收任务参数后调用
  `data-nest-task-core` 的 `SyncJobExecutor` → `SyncJobExecutorService.runSyncJob(...)`，最终进入
  `AddaxJobService.execute(syncJobId, historyId)`。
- engineering-service 仅负责任务 CRUD 与 XXL-JOB 调度注册，不直接执行 Addax。

```java
// data-nest-task-core / AddaxJobService 核心片段
@Service
public class AddaxJobService {

    private static final String ADDEX_HOME = "/opt/addax";
    private static final String ADDEX_BIN = ADDEX_HOME + "/bin/addax.sh";

    public AddaxExecutionResult execute(Long syncJobId, Long historyId) {
        Path jobFile = buildJobFile(syncJobId);       // 生成临时 job.json
        ProcessBuilder pb = new ProcessBuilder(ADDEX_BIN, jobFile.toAbsolutePath().toString());
        pb.directory(new File(ADDEX_HOME));
        pb.redirectErrorStream(true);

        Process process = pb.start();
        LogStreamer streamer = new LogStreamer(process.getInputStream(), historyId, logMapper);
        streamer.start();

        int exitCode = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                ? process.exitValue() : -1;
        streamer.join(5000);
        return exitCode == 0
                ? AddaxExecutionResult.success(streamer.getLogLines(), streamer.getStats())
                : AddaxExecutionResult.failure(streamer.getLogLines(), streamer.getErrorSummary());
    }
}
```

### 3.3 XXL-JOB 调度集成

data-nest-worker 作为 XXL-JOB Executor 注册到 XXL-JOB，engineering-service 负责任务 CRUD 与调度注册。 **代码结构与 Sprint
1 governance 的 `SchedulerService`完全一致**
——复用套 cookie 自动重登录、`register`/`update`/`trigger`/`unregister` 四项核心方法。

差异点仅在 `buildJobInfo()` 的任务参数：

```java
// SchedulerService（engineering 版，整体拷贝自 Sprint 1 governance 的同名类）
// 具体代码参见 Sprint 1 技术文档 6.3 节，下面仅标注不同于采集任务的部分：

@Service
public class SchedulerService {
    private final XxlJobApi xxlJobApi;
    private volatile String cookie;

    private void ensureLogin() { /* 同 Sprint 1 */ }

    private <T> T withRetry(Supplier<T> fn) { /* 同 Sprint 1，cookie 失效自动重登 */ }

    // ===== 四项核心方法，逻辑同 Sprint 1 =====
    public void register(SyncJob task) { /* 同 Sprint 1 的 register */ }

    public void update(SyncJob task) { /* 同 Sprint 1 的 update */ }

    public void unregister(SyncJob task) { /* 同 Sprint 1 的 unregister */ }

    public void trigger(SyncJob task) { /* 同 Sprint 1 的 trigger */ }

    // 相比 Sprint 1 新增：停用/启用调度
    public void pause(SyncJob task) {
        xxlJobApi.pauseJob(cookie, task.getXxlJobId());
    }

    public void resume(SyncJob task) {
        xxlJobApi.startJob(cookie, task.getXxlJobId());
    }

    private JobInfo buildJobInfo(SyncJob task) {
        JobInfo info = new JobInfo();
        info.setJobDesc(task.getName());
        info.setAuthor("datanest");
        info.setGlueType("BEAN");
        info.setExecutorHandler("syncJobHandler");          // ← 不同于 collectTaskHandler
        info.setExecutorParam(String.valueOf(task.getId()));
        info.setExecutorTimeout(3600);                        // ← 同步比采集更长（1 小时）
        info.setExecutorFailRetryCount(0);                   // ← 重试由 DataNest 自己管
        info.setExecutorBlockStrategy("SERIAL_EXECUTION");   // 同一任务不并发

        if ("CRON".equals(task.getTriggerType())) {
            info.setScheduleType("CRON");
            info.setScheduleConf(task.getCronExpression());
        } else {
            info.setScheduleType("NONE");  // MANUAL 只支持手动触发
        }
        return info;
    }
}
```

data-nest-worker 的 XXL-JOB 配置（在 `application.yml` 中覆盖 shared-xxljob 的 appname 和 port）：

```yaml
xxl:
  job:
    executor:
      appname: datanest-worker-executor
      port: 9999
```

---

## 4. 项目结构变更

### 4.1 engineering-service 新增

```
data-nest-engineering/
├── src/main/java/com/datanest/engineering/
│   ├── EngineeringApplication.java
│   ├── datasource/                       # Sprint 1 已有
│   │   ├── controller/DataSourceController.java
│   │   └── ...
│   ├── sync/                             # 🆕 批量同步任务 CRUD / 调度注册
│   │   ├── controller/
│   │   │   ├── SyncJobController.java
│   │   │   └── SyncHistoryController.java
│   │   ├── service/
│   │   │   ├── SyncJobService.java      # 任务 CRUD
│   │   │   └── SyncHistoryService.java   # 历史记录 + 日志查询
│   │   ├── scheduler/
│   │   │   └── SchedulerService.java     # XXL-JOB API 封装
│   │   ├── mapper/                       # MyBatis-Plus Mapper
│   │   │   ├── SyncJobMapper.java
│   │   │   └── SyncHistoryMapper.java
│   │   └── entity/
│   │       ├── SyncJob.java
│   │       └── SyncHistory.java
│   └── preview/                          # 🆕 数据预览
│       ├── controller/PreviewController.java
│       └── service/
│           ├── PreviewService.java       # SELECT * LIMIT 100
│           └── PaginationDialect.java    # 分页语法兼容
```

### 4.1.1 data-nest-worker 新增

```
data-nest-worker/
├── src/main/java/com/datanest/worker/
│   ├── WorkerApplication.java
│   └── sync/                             # 🆕 实际执行 Addax
│       ├── executor/
│       │   ├── AddaxExecutor.java        # 调用 addax.sh
│       │   └── LogStreamer.java          # 异步读取 Addax stdout/stderr
│       ├── handler/
│       │   └── SyncJobHandler.java       # @XxlJob("syncJobHandler")
│       ├── service/
│       │   ├── AddaxJobBuilder.java      # 生成 Addax JSON
│       │   ├── RetryService.java         # 失败重试逻辑
│       │   ├── AlertService.java         # 告警通知
│       │   └── MetadataRegistrar.java    # 同步后写 metadata_table/column
│       ├── mapper/
│       │   ├── SyncJobMapper.java
│       │   ├── SyncHistoryMapper.java
│       │   ├── DataSourceConnectionMapper.java
│       │   ├── MetadataTableMapper.java
│       │   └── MetadataColumnMapper.java
│       └── entity/
│           ├── SyncJob.java
│           ├── SyncHistory.java
│           ├── DataSourceConnection.java
│           ├── MetadataTable.java
│           └── MetadataColumn.java
```

### 4.2 governance-service 新增

```
data-nest-governance/
├── src/main/java/com/datanest/governance/
│   ├── GovernanceApplication.java
│   ├── metadata/                         # Sprint 1 已有
│   ├── collect/                          # Sprint 1 已有
│   ├── standard/                         # 🆕 数据标准
│   │   ├── controller/
│   │   │   └── StandardController.java
│   │   ├── service/
│   │   │   ├── NamingStandardService.java
│   │   │   └── FieldTypeStandardService.java
│   │   ├── mapper/
│   │   │   ├── NamingStandardMapper.java
│   │   │   └── FieldTypeStandardMapper.java
│   │   └── entity/
│   │       ├── NamingStandard.java
│   │       └── FieldTypeStandard.java
│   └── compliance/                       # 🆕 合规检查
│       ├── controller/
│       │   └── ComplianceController.java
│       ├── service/
│       │   ├── ComplianceCheckService.java
│       │   ├── NamingChecker.java        # 命名规范检查
│       │   └── TypeChecker.java          # 字段类型检查
│       └── entity/ComplianceResult.java
```

### 4.3 common 模块新增

```
data-nest-common/
└── src/main/java/com/datanest/common/
    ├── config/EncryptionConfig.java      # 已有
    ├── util/JdbcSchemaExtractor.java     # 已有
    └── util/                             # 🆕
        ├── JdbcQueryUtil.java            # 通用 JDBC 查询（预览、Schema 拉取）
        └── PaginationDialect.java        # 分页语法适配
```

### 4.4 Root POM 变更

无新增模块。engineering-service 和 governance-service 新增依赖：

```xml
<!-- data-nest-engineering/pom.xml 🆕 -->
<dependency>
    <groupId>com.datanest</groupId>
    <artifactId>data-nest-common</artifactId>
</dependency>
        <!-- MyBatis-Plus 已有，metadata_table 的 Mapper 需要 -->

        <!-- data-nest-governance/pom.xml 无变更（已有 common 依赖） -->
```

---

## 5. Docker Compose 变更

### 5.1 worker 容器

Sprint 2 新增独立 `data-nest-worker` 容器，内置 Addax 并作为 XXL-JOB Executor；engineering-service 负责任务管理、调度注册和历史记录。Doris
是 **单独服务器部署**，不在 docker-compose
中，engineering 通过 Nacos 配置的地址连接。

```yaml
# docker-compose.yml 中的 worker 声明
worker:
  build:
    context: ./data-nest-worker
    dockerfile: Dockerfile
  container_name: datanest-worker
  depends_on:
    nacos: { condition: service_healthy }
    postgres: { condition: service_healthy }
    # 注意：Doris 单独部署，不在此等待
  environment:
    NACOS_ADDR: nacos:8848
    PG_HOST: postgres
    PG_PORT: 5432
    PG_USER: datanest
    PG_PASSWORD: ${PG_PASSWORD:-datanest123}
    ADDEX_HOME: /opt/addax
    # Doris 地址通过 shared-doris.yaml（Nacos 配置中心）注入，不在环境变量硬编码
  ports:
    - "8083:8083"
  healthcheck:
    test: [ "CMD", "curl", "-f", "http://localhost:8083/actuator/health" ]
    interval: 15s
    timeout: 5s
    retries: 10
    start_period: 20s              # Addax 是即时可用的，无需等待
```

### 5.2 启动顺序

```
nacos-mysql → nacos → postgres → xxl-job-admin → system → engineering → worker(含 Addax) → governance → gateway → frontend
```

> 相比 Sprint 1：engineering 不再依赖 Doris 容器（Doris 单独部署）。Doris FE 地址通过 Nacos `shared-doris.yaml`
> 配置，engineering 启动时从配置中心拉取。Addax 内置在 worker 容器中，随 worker 启动即时可用，不需要额外预热。

### 5.3 Gateway 路由

无变更。Sprint 2 新增的接口都在 `/api/engineering/**` 和 `/api/governance/**` 下，已配置路由。

---

## 6. 批量数据同步任务

> **职责拆分**：`engineering-service` 负责任务 CRUD、XXL-JOB 调度注册与历史记录查询；`data-nest-worker` 作为 XXL-JOB
> Executor 实际执行 Addax、失败重试、告警及同步后元数据注册。下文 6.3–6.6 的代码示例均位于 `data-nest-worker`。

### 6.1 职责

| 功能               | 接口                                                           | 鉴权                        |
|--------------------|----------------------------------------------------------------|-----------------------------|
| 同步任务列表       | `POST /api/engineering/sync-jobs/page`                         | SUPER_ADMIN / DATA_ENGINEER |
| 任务详情           | `GET /api/engineering/sync-jobs/{id}`                          | 同上                        |
| 创建任务           | `POST /api/engineering/sync-jobs`                              | 同上                        |
| 编辑任务           | `PUT /api/engineering/sync-jobs/{id}`                          | 同上                        |
| 删除任务           | `DELETE /api/engineering/sync-jobs/{id}`                       | 同上                        |
| 执行任务           | `POST /api/engineering/sync-jobs/{id}/execute`                 | 同上                        |
| 启动调度           | `POST /api/engineering/sync-jobs/{id}/schedule/start`          | 同上                        |
| 停止调度           | `POST /api/engineering/sync-jobs/{id}/schedule/stop`           | 同上                        |
| 历史记录（按任务） | `POST /api/engineering/sync-jobs/{id}/history/page`            | 同上                        |
| 历史记录（全局）   | `POST /api/engineering/sync-jobs/history/page`                 | 同上                        |
| 停止运行中历史     | `POST /api/engineering/sync-jobs/history/{historyId}/stop`     | 同上                        |
| 执行日志           | `GET /api/engineering/sync-jobs/{id}/history/{historyId}/logs` | 同上                        |

### 6.2 同步任务实体

```java

@Data
@TableName(value = "sync_job", autoResultMap = true)
public class SyncJob {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String name;                    // 全局唯一，3-50 位（DB 长度 100）
    private Long sourceDatasourceId;        // 源数据源 ID
    private Long targetDatasourceId;        // 目标数据源 ID（已废弃，目标端固定为内置 Doris）
    private String sourceDatabase;          // 源库名
    private String sourceSchema;            // 源 Schema 名（PG/Oracle/SQL Server 使用）
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> sourceTables;      // 源表名数组（多表同步）
    @TableField(jdbcType = JdbcType.OTHER)
    private String sourceTablesDetail;      // TEXT/JSON 字符串：多表同步详情（含每表目标表名、字段映射）
    private String targetDatabase;          // 目标 Doris 库
    private String targetTable;             // 目标表名（单表时必填；多表时取首表默认值）
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<FieldMappingItem> fieldMapping; // [{"sourceColumn":"id","targetColumn":"id","targetType":"bigint"},...]
    private String syncMode;                // FULL / INCREMENTAL
    private String incrementalField;        // 增量字段（INCREMENTAL 模式必填）
    private String triggerType;             // MANUAL / CRON / DAG
    private String cronExpression;          // Cron 表达式（CRON 模式必填）
    private Integer retryTimes;             // 0-3，默认 3
    private Integer retryInterval;          // 重试间隔分钟数，1-30，默认 5
    private String status;                  // NORMAL / PAUSED / ERROR（调度状态）
    private String executionStatus;         // PENDING / RUNNING / SUCCESS / FAILED / TERMINATED（执行状态）
    private Integer scheduleEnabled;        // 0-停止，1-运行（调度开关）
    private Integer rateLimitEnabled;       // 0-关闭，1-开启（速率限流开关）
    private Integer readRateLimitMbps;      // 读取速率限制（MB/s，0=不限）
    private Integer writeRateLimitRowsPerSecond; // 写入速率限制（行/秒，0=不限）
    private String description;             // 任务描述，最多 1000 字
    private Integer xxlJobId;               // XXL-JOB 注册的任务 ID
    private LocalDateTime nextExecutionTime;// 下次执行时间（CRON 任务）
    private LocalDateTime lastExecuteTime;  // 最近执行时间
    private Long lastHistoryId;             // 最近一次执行历史 ID
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

### 6.3 Addax Job JSON 构建

`AddaxJobBuilder` 根据任务配置动态生成 Addax 任务 JSON，写入临时文件后交给 `AddaxExecutor` 执行。

**Reader 插件选择**（根据数据源类型）：

```java

@Component
public class AddaxJobBuilder {

    private static final Map<String, String> READER_PLUGIN = Map.of(
            "MYSQL", "mysqlreader",
            "POSTGRESQL", "postgresqlreader",
            "DORIS", "dorisreader"
    );

    public Path buildJobFile(SyncJob task, DataSourceConnection ds) {
        JsonObject job = buildJobJson(task, ds);
        // 写入 /tmp/addax-jobs/{taskId}_{timestamp}.json
        Path jobFile = Paths.get("/tmp/addax-jobs",
                task.getId() + "_" + System.currentTimeMillis() + ".json");
        Files.createDirectories(jobFile.getParent());
        Files.writeString(jobFile, job.toString());
        return jobFile;
    }
}
```

**生成的 JSON 示例**（MySQL 全量同步 → Doris）：

```json
{
  "job": {
    "setting": {
      "speed": {
        "channel": 3
      }
    },
    "content": [
      {
        "reader": {
          "name": "mysqlreader",
          "parameter": {
            "username": "root",
            "password": "***",
            "column": [
              "id",
              "username",
              "created_at"
            ],
            "connection": [
              {
                "table": [
                  "orders"
                ],
                "jdbcUrl": [
                  "jdbc:mysql://source-host:3306/production"
                ]
              }
            ]
          }
        },
        "writer": {
          "name": "doriswriter",
          "parameter": {
            "username": "root",
            "password": "",
            "database": "ods",
            "table": "orders",
            "column": [
              "id",
              "username",
              "created_at"
            ],
            "loadUrl": [
              "doris-fe:8030"
            ],
            "lineDelimiter": "\n",
            "maxRetries": 3,
            "loadProps": {
              "format": "json",
              "strip_outer_array": "true"
            }
          }
        }
      }
    ]
  }
}
```

**速率限流映射**：当 `rateLimitEnabled=true` 时，`AddaxJobBuilder` 将 `readRateLimitMbps` 映射到 `setting.speed.byte`
（Mbps → bytes/s），将 `writeRateLimitRowsPerSecond` 映射到 `setting.speed.record`。

**增量同步的特殊处理**：

```java
private String buildReaderSql(SyncJob task) {
    if (!"INCREMENTAL".equals(task.getSyncMode())) {
        return null;  // 全量不需要 where
    }
    // 从上次执行记录获取最大增量值
    Long maxValue = syncHistoryMapper.getMaxIncrementalValue(task.getId());
    if (maxValue == null) {
        return null;  // 首次执行全量
    }
    return "SELECT * FROM " + task.getSourceTable() +
            " WHERE " + task.getIncrementalField() + " > " + maxValue;
}
```

Addax 的 mysqlreader/postgresqlreader 支持 `querySql` 参数，当需要增量条件时用 `querySql` 替代 `table + column` 组合。

### 6.4 同步执行流程

```java

@Service
public class SyncJobService {

    private final SchedulerService schedulerService;
    private final SyncJobMapper syncJobMapper;
    private final SyncHistoryMapper syncHistoryMapper;

    @Transactional
    public SyncJob create(SyncJobCreateRequest req) {
        SyncJob task = convert(req);
        task.setStatus("NORMAL");            // 调度状态：正常
        task.setExecutionStatus("PENDING");  // 执行状态：待执行
        syncJobMapper.insert(task);
        // 创建即注册到 XXL-JOB（同 Sprint 1 采集任务）
        schedulerService.register(task);
        return task;
    }

    @Transactional
    public void delete(Long id) {
        SyncJob task = getOrThrow(id);
        schedulerService.unregister(task);  // 先从 XXL-JOB 注销
        syncJobMapper.deleteById(id);
        // 删除历史记录和日志
        syncHistoryMapper.deleteByTaskId(id);
    }
}
```

```java

@Component
public class SyncJobHandler {

    private final SyncJobMapper taskMapper;
    private final AddaxJobBuilder jobBuilder;
    private final AddaxExecutor addaxExecutor;
    private final DataSourceConnectionMapper dsMapper;
    private final EncryptionConfig encryptionConfig;
    private final MetadataRegistrar metadataRegistrar;
    private final RetryService retryService;
    private final AlertService alertService;

    @XxlJob("syncJobHandler")
    public void execute() {
        Long taskId = Long.valueOf(XxlJobHelper.getJobParam());
        SyncJob task = taskMapper.selectById(taskId);
        if (task == null) return;

        // 执行状态更新为 RUNNING
        task.setExecutionStatus("RUNNING");
        taskMapper.updateById(task);

        // 创建历史记录
        SyncHistory history = createHistory(task);

        try {
            DataSourceConnection ds = dsMapper.selectById(task.getDatasourceId());
            String password = encryptionConfig.decrypt(ds.getEncryptedPassword());

            // 生成 Addax job.json
            Path jobFile = jobBuilder.buildJobFile(task, ds, password);

            // 执行 Addax
            AddaxResult result = addaxExecutor.execute(task, jobFile, history);

            if (result.isSuccess()) {
                onSuccess(task, history, result);
            } else {
                onFailure(task, history, result);
            }
        } catch (Exception e) {
            log.error("Sync task failed", e);
            onFailure(task, history, new AddaxResult(false, e.getMessage()));
        }
    }

    private void onSuccess(SyncJob task, SyncHistory history, AddaxResult result) {
        history.setStatus("SUCCESS");
        history.setEndedAt(Instant.now());
        history.setRowsWritten(result.getRowsWritten());
        history.setThroughput(result.getThroughput());
        syncHistoryMapper.updateById(history);

        task.setExecutionStatus("SUCCESS");
        task.setLastExecutedAt(Instant.now());
        taskMapper.updateById(task);

        // 🆕 同步成功后注册元数据
        metadataRegistrar.register(task);

        // 清理临时 job 文件
        Files.deleteIfExists(result.getJobFile());
    }

    private void onFailure(SyncJob task, SyncHistory history, AddaxResult result) {
        // 先判断是否还有重试次数
        if (retryService.shouldRetry(history)) {
            history.setStatus("RETRYING");
            history.setRetryCount(history.getRetryCount() + 1);
            syncHistoryMapper.updateById(history);
            retryService.scheduleRetry(task, history);
        } else {
            // 重试耗尽
            history.setStatus("FAILED");
            history.setEndedAt(Instant.now());
            history.setErrorMessage(result.getErrorMessage());
            syncHistoryMapper.updateById(history);

            task.setExecutionStatus("FAILED");
            taskMapper.updateById(task);

            // 发送告警
            alertService.sendAlert(task, history, result);
        }
    }
}
```

**与代码实现的对应关系**：

- 上图中的 `SyncJobHandler` 为概念示意；XXL-JOB 的 Handler 实际定义在 `data-nest-worker:WorkerJobHandler`（
  `syncJobHandler`），它解析参数后调用 `data-nest-task-core:SyncJobExecutor.execute(param)`。
- `SyncJobExecutor` 再进入 `data-nest-task-core:SyncJobExecutorService.runSyncJob(syncJobId, triggerType, historyId)`
  ，由它负责状态翻转、Addax 执行、元数据注册以及失败后的 `SyncJobRetryService.registerRetryIfNeeded(...)` 登记。
- engineering-service 的 `SyncJobService` 负责任务 CRUD、分页查询、启停调度、停止历史记录等 HTTP 接口逻辑。

### 6.5 失败重试

Sprint 2 采用 **持久化重试模型**，重试状态保存在 `sync_job_history` 中，避免进程重启或事务问题导致重试丢失。

```java

@Service
public class SyncJobRetryService {

    private final SyncJobHistoryMapper syncJobHistoryMapper;

    /**
     * 失败收尾时登记下一次重试。
     * 仅当剩余重试次数 > 0 且任务已注册 XXL-JOB 时，
     * 在失败历史记录上写入 next_retry_at（retry_interval 分钟后）。
     */
    public boolean registerRetryIfNeeded(SyncJob job, SyncJobHistory failedHistory) {
        if (job.getRetryTimes() == null || job.getRetryTimes() <= 0) return false;
        int retried = failedHistory.getRetryCount() == null ? 0 : failedHistory.getRetryCount();
        if (retried >= job.getRetryTimes()) return false;

        int interval = job.getRetryInterval() == null || job.getRetryInterval() <= 0
                ? 5 : job.getRetryInterval();
        LocalDateTime nextRetryAt = LocalDateTime.now().plusMinutes(interval);
        syncJobHistoryMapper.update(null, new UpdateWrapper<SyncJobHistory>()
                .set("next_retry_at", nextRetryAt)
                .eq("id", failedHistory.getId()));
        return true;
    }

    /**
     * 供 data-nest-job 的 syncJobRetryHandler 周期扫描调用：
     * 认领到期记录并新建一条 RUNNING 重试历史，parent_history_id 指向来源记录。
     */
    public SyncJobHistory claimAndCreateRetryHistory(SyncJobHistory failedHistory) {
        int claimed = syncJobHistoryMapper.update(null, new UpdateWrapper<SyncJobHistory>()
                .set("next_retry_at", null)
                .eq("id", failedHistory.getId())
                .isNotNull("next_retry_at"));
        if (claimed == 0) return null;

        SyncJobHistory retryHistory = new SyncJobHistory();
        retryHistory.setSyncJobId(failedHistory.getSyncJobId());
        retryHistory.setTriggerType(failedHistory.getTriggerType());
        retryHistory.setParentHistoryId(failedHistory.getId());
        retryHistory.setRetryCount((failedHistory.getRetryCount() == null ? 0 : failedHistory.getRetryCount()) + 1);
        retryHistory.setStatus("RUNNING");
        retryHistory.setStartTime(LocalDateTime.now());
        syncJobHistoryMapper.insert(retryHistory);
        return retryHistory;
    }
}
```

**重试状态反馈给前端**：

- 任务主表 `sync_job.execution_status` 在重试期间保持 `RUNNING`，任务列表显示当前执行状态。
- `sync_job_history` 通过 `parent_history_id` 形成重试链；重试历史行的 `retry_count` 表示该链已发生的重试次数，
  `next_retry_at` 记录计划下次重试时间。
- 前端通过轮询全局历史页 `/engineering/sync-job-history` 查看重试链与日志；手动停止操作在历史行上进行。

### 6.6 元数据自动注册

```java

@Service
public class MetadataRegistrar {

    private final MetadataTableMapper metadataTableMapper;
    private final MetadataColumnMapper metadataColumnMapper;
    private final JdbcSchemaExtractor schemaExtractor;
    private final DataSourceConnectionMapper dsMapper;
    private final EncryptionConfig encryptionConfig;

    /**
     * 同步成功后注册目标 Doris 表的元数据到 metadata_table 和 metadata_column。
     * 直接写表，不经过 governance-service。
     */
    @Transactional
    public void register(SyncJob task) {
        // 1. 查询/创建 metadata_table 记录
        // datasource_id 用特殊值 -1 表示内置 Doris
        MetadataTable table = metadataTableMapper.selectByUnique(
                -1L, task.getTargetDatabase(), null, task.getTargetTable());

        if (table == null) {
            table = new MetadataTable();
            table.setDatasourceId(-1L);           // 内置 Doris
            table.setDatabaseName(task.getTargetDatabase());
            table.setTableName(task.getTargetTable());
            table.setSourceType("BUILTIN_DORIS"); // 🆕
            table.setSourceStatus("ONLINE");
            metadataTableMapper.insert(table);
        }

        // 2. 查询 Doris 目标表的实际字段结构
        //    通过 JDBC 直连 Doris FE，查询 information_schema
        DataSourceConnection ds = dsMapper.selectById(task.getDatasourceId());
        String password = encryptionConfig.decrypt(ds.getEncryptedPassword());
        List<ColumnInfo> columns = schemaExtractor.extractColumns(
                "DORIS", ds.getHost(), ds.getPort(),
                task.getTargetDatabase(), null,   // Doris 无 Schema 层级
                task.getTargetTable(),
                ds.getUsername(), password);

        // 3. 按字段映射写入（只写入被映射的字段）
        Set<String> mappedColumns = parseMappedColumns(task.getFieldMapping());
        for (ColumnInfo col : columns) {
            if (!mappedColumns.contains(col.getName())) continue;

            metadataColumnMapper.upsert(new MetadataColumn(
                    table.getId(), col.getName(), col.getType(),
                    col.isNullable(), col.getDefaultValue(), col.getOrdinal()
            ));
        }

        // 4. 更新表的字段计数和采集时间
        table.setColumnCount(mappedColumns.size());
        table.setUpdatedAt(LocalDateTime.now());
        metadataTableMapper.updateById(table);
    }

    private Set<String> parseMappedColumns(String fieldMappingJson) {
        // 解析 [{"sourceColumn":"id","targetColumn":"id","targetType":"bigint"},...] → Set<"id">
        JsonArray mappings = JsonParser.parseString(fieldMappingJson).getAsJsonArray();
        Set<String> targets = new HashSet<>();
        mappings.forEach(m -> targets.add(m.getAsJsonObject().get("targetColumn").getAsString()));
        return targets;
    }
}
```

### 6.7 Cron 表达式处理

与 Sprint 1 采集任务保持一致。前端提供 12 个预设 + 自定义拼装，后端接收标准的 Quartz Cron 表达式（6 位或 7 位）。

```java
// SyncJobService.updateNextExecutionTime()
// 每次更新 Cron 时，用 CronExpression 计算下一次执行时间存储到 next_execution_time
// 前端列表直接展示，无需实时计算
private void updateNextExecutionTime(SyncJob task) {
    if ("CRON".equals(task.getTriggerType()) && task.getCronExpression() != null) {
        CronExpression cron = new CronExpression(task.getCronExpression());
        task.setNextExecutionTime(cron.next(LocalDateTime.now()));
    }
}
```

---

## 7. engineering-service：数据预览

### 7.1 职责

| 功能                     | 接口                                                                  | 鉴权                                           |
|--------------------------|-----------------------------------------------------------------------|------------------------------------------------|
| 拉取数据源的库/Schema 树 | `GET /api/engineering/datasources/{id}/schema-tree`                   | SUPER_ADMIN / DATA_ENGINEER / GOV_ADMIN        |
| 预览表数据（前 100 行）  | `GET /api/engineering/preview?dsId={}&database={}&schema={}&table={}` | 同上（数据源列表）/ 加 ANALYST（元数据管理页） |

### 7.2 实现

```java

@Service
public class PreviewService {

    private final DataSourceConnectionMapper dsMapper;
    private final EncryptionConfig encryptionConfig;

    /**
     * 预览表前 100 行。
     * 各数据源分页语法差异通过 PaginationDialect 处理。
     * Sprint 2 支持的数据源（MySQL/PG/Doris）均支持 LIMIT，无需差异化。
     * 后续扩展 Oracle 时需在 dialect 层处理 ROWNUM。
     */
    public PreviewResult preview(Long dsId, String database, String schema, String table) {
        DataSourceConnection ds = dsMapper.selectById(dsId);
        String password = encryptionConfig.decrypt(ds.getEncryptedPassword());

        // 先查总行数（非精确，information_schema 近似值）
        long totalRows = getApproxRowCount(ds, database, schema, table);

        // 构建 JDBC URL 和查询
        String jdbcUrl = buildJdbcUrl(ds, database);
        String sql = PaginationDialect.limit(
                "SELECT * FROM " + quote(ds.getType(), schema, table), 100);

        List<Map<String, Object>> rows = executeQuery(jdbcUrl, ds.getUsername(), password, sql);
        List<String> columns = extractColumnNames(rows);

        return new PreviewResult(totalRows, columns, rows);
    }

    /**
     * 拉取数据源的库/Schema 树。
     * 从 Sprint 1 的 SchemaService 逻辑迁移，不再走 HTTP 接口。
     * 直接通过 JdbcSchemaExtractor 连接数据源查询。
     */
    public SchemaTree getSchemaTree(Long dsId) {
        DataSourceConnection ds = dsMapper.selectById(dsId);
        String password = encryptionConfig.decrypt(ds.getEncryptedPassword());

        List<String> databases = JdbcSchemaExtractor.extractDatabases(
                ds.getType(), ds.getHost(), ds.getPort(), ds.getUsername(), password);

        // 逐库拉表
        List<SchemaTree.DatabaseNode> nodes = new ArrayList<>();
        for (String db : databases) {
            List<String> tables = JdbcSchemaExtractor.extractTables(
                    ds.getType(), ds.getHost(), ds.getPort(), db, null,
                    ds.getUsername(), password);
            nodes.add(new SchemaTree.DatabaseNode(db, tables));
        }
        return new SchemaTree(nodes);
    }
}
```

### 7.3 分页语法兼容

```java
public class PaginationDialect {

    private static final Set<String> LIMIT_SUPPORTED = Set.of("MYSQL", "POSTGRESQL", "DORIS");

    public static String limit(String sql, int limit, String dbType) {
        if (LIMIT_SUPPORTED.contains(dbType.toUpperCase())) {
            return sql + " LIMIT " + limit;
        }
        // 后续扩展点：
        // ORACLE  → SELECT * FROM (sql) WHERE ROWNUM <= limit
        // SQLSVR  → SELECT TOP limit ... FROM (sql)
        throw new UnsupportedOperationException("Pagination not supported for: " + dbType);
    }

    /** 默认 LIMIT（MySQL/PG/Doris 通用） */
    public static String limit(String sql, int limit) {
        return sql + " LIMIT " + limit;
    }
}
```

---

## 8. engineering-service：保存后自动采集

### 8.1 实现

数据源保存时如果用户勾选了「保存后立即采集元数据」，engineering-service 直接写 `collect_task` 表并触一次执行。

> governance-service 的 XXL-JOB handler `collectTaskHandler` 是通用的——只要 `collect_task` 表里有记录且注册到了
> XXL-JOB，governance 的 Executor 就能执行。所以 engineering 只需要：
> 1. INSERT 一条 `collect_task`（trigger_type=MANUAL）
> 2. 注册到 XXL-JOB
> 3. 调 XXL-JOB trigger API

```java
// DataSourceService.save()
@Transactional
public DataSourceDTO save(DataSourceCreateRequest req, boolean autoCollect, Long userId) {
    // 1. 保存数据源（Sprint 1 逻辑不变）
    DataSourceConnection ds = convert(req);
    ds.setCreatedBy(userId);
    datasourceMapper.insert(ds);

    // 2. 如果勾选了自动采集
    if (autoCollect) {
        String taskName = "自动采集-" + ds.getName() + "-" +
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        // 直接 INSERT collect_task
        CollectTask task = new CollectTask();
        task.setName(taskName);
        task.setDatasourceId(ds.getId());
        task.setDatasourceName(ds.getName());
        task.setScope(toJsonArray(ds.getDatabaseName())); // 采集当前库
        task.setCollectMode("FULL");
        task.setTriggerType("MANUAL");
        task.setStatus("NORMAL");
        collectTaskMapper.insert(task);

        // 注册到 XXL-JOB 并立即触发
        schedulerService.register(task);      // appname: datanest-governance-executor
        schedulerService.trigger(task);
    }

    return toDTO(ds);
}
```

> 这里 `schedulerService` 注册的 Executor handler 是 `collectTaskHandler`，和 governance 的采集任务共用同一个
> handler。engineering 只负责创建任务和触发，实际采集由 governance 的 Executor 执行。

### 8.2 依赖关系

engineering → 写 `collect_task` 表 → governance XXL-JOB Executor 消费 → `CollectJobHandler` 执行。

**不需要 governance HTTP 接口**。governance 的 `CollectJobHandler` 按 `taskId` 从 `collect_task` 表加载任务，不区分是谁创建的。

---

## 9. governance-service：数据标准

### 9.1 职责

| 功能             | 接口                                                              | 鉴权                    |
|------------------|-------------------------------------------------------------------|-------------------------|
| 命名规范列表     | `POST /api/governance/data-standards/naming-standards/page`       | SUPER_ADMIN / GOV_ADMIN |
| 新建命名规范     | `POST /api/governance/data-standards/naming-standards`            | 同上                    |
| 编辑命名规范     | `PUT /api/governance/data-standards/naming-standards/{id}`        | 同上                    |
| 删除命名规范     | `DELETE /api/governance/data-standards/naming-standards/{id}`     | 同上                    |
| 命名规范详情     | `GET /api/governance/data-standards/naming-standards/{id}`        | 同上                    |
| 字段类型标准列表 | `POST /api/governance/data-standards/field-type-standards/page`   | 同上                    |
| 新建字段类型标准 | `POST /api/governance/data-standards/field-type-standards`        | 同上                    |
| 编辑字段类型标准 | `PUT /api/governance/data-standards/field-type-standards/{id}`    | 同上                    |
| 删除字段类型标准 | `DELETE /api/governance/data-standards/field-type-standards/{id}` | 同上                    |
| 字段类型标准详情 | `GET /api/governance/data-standards/field-type-standards/{id}`    | 同上                    |
| 执行合规检查     | `POST /api/governance/data-standards/compliance-check`            | SUPER_ADMIN / GOV_ADMIN |
| 查询检查结果     | `POST /api/governance/data-standards/compliance-check/results`    | 同上                    |

### 9.2 命名规范实体

```java

@Data
@TableName("naming_standard")
public class NamingStandard {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String name;             // 规范名称，全局唯一
    private String appliesTo;        // TABLE / COLUMN（适用对象）
    private String ruleType;         // PREFIX / SUFFIX / REGEX（匹配方式）
    private String ruleValue;        // 规范值（前缀文字 / 后缀文字 / 正则表达式）
    private Long targetStandardId;   // 关联的字段类型标准 ID
    private Integer priority;        // 优先级，数字越大越优先
    private Integer enabled;         // 0-禁用，1-启用
    private String description;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

### 9.3 字段类型标准实体

```java

@Data
@TableName(value = "field_type_standard", autoResultMap = true)
public class FieldTypeStandard {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String name;             // 标准类型名称（如"主键ID"）
    private String category;         // 分类：数值 / 字符串 / 时间 等
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> allowedTypes; // 允许类型数组（如 ["BIGINT", "DECIMAL(18,2)"]）
    private String description;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

### 9.4 合规检查引擎

```java

@Service
public class ComplianceCheckService {

    private final NamingStandardMapper namingMapper;
    private final FieldTypeStandardMapper fieldTypeMapper;
    private final MetadataTableMapper metadataTableMapper;
    private final MetadataColumnMapper metadataColumnMapper;

    /**
     * 对指定数据源/库/Schema/表执行合规检查。
     * @param request 检查范围（datasourceId/datasourceIds/databaseName/schemaName/tableId/startTime/endTime）
     */
    public List<ComplianceCheckResultDTO> check(ComplianceCheckRequest request) {
        ComplianceResult result = new ComplianceResult();

        boolean checkNaming = request.getCheckNaming() == null || request.getCheckNaming();
        boolean checkFieldType = request.getCheckFieldType() == null || request.getCheckFieldType();
        if (!checkNaming && !checkFieldType) {
            throw new BusinessException(ErrorCode.INVALID_COMPLIANCE_SCOPE, "检查项目不能全部关闭");
        }

        Long tableId = request.getTableId();
        // 未指定数据源/表时，默认取全部在线数据源
        List<Long> datasourceIds = resolveDatasourceIds(request, tableId);

        // 按开关加载标准
        List<NamingStandard> namingRules = checkNaming ? namingMapper.selectEnabled() : List.of();
        List<FieldTypeStandard> fieldTypeRules = checkFieldType ? fieldTypeMapper.selectAll() : List.of();

        // 获取检查范围内的所有表
        List<MetadataTable> tables = tableId != null
                ? List.of(metadataTableMapper.selectById(tableId))
                : listTables(datasourceIds, request.getDatabaseName(), request.getSchemaName());

        for (MetadataTable table : tables) {
            List<MetadataColumn> columns = metadataColumnMapper.selectByTableId(table.getId());

            if (checkNaming) {
                // 1. 命名规范检查
                checkTableNaming(table, namingRules, result);
                for (MetadataColumn col : columns) {
                    checkColumnNaming(table, col, namingRules, result);
                }
            }

            if (checkFieldType) {
                // 2. 字段类型检查
                for (MetadataColumn col : columns) {
                    checkColumnType(table, col, namingRules, fieldTypeRules, result);
                }
            }
        }

        return result;
    }

    private void checkTableNaming(MetadataTable table, List<NamingStandard> rules,
                                  ComplianceResult result) {
        for (NamingStandard rule : rules) {
            if (!"TABLE".equals(rule.getAppliesTo())) continue;
            if (!matchRule(table.getTableName(), rule)) {
                result.addNamingViolation(
                        buildTablePath(table),
                        "表名不符合命名规范",
                        rule.getName(),
                        "建议重命名为符合 " + rule.getRuleType() + ":" + rule.getRuleValue() + " 的名称"
                );
            }
        }
    }

    /**
     * 匹配逻辑：
     * - PREFIX: tableName.startsWith(ruleValue)
     * - SUFFIX: tableName.endsWith(ruleValue)
     * - REGEX:  tableName.matches(ruleValue)
     */
    private boolean matchRule(String name, NamingStandard rule) {
        return switch (rule.getRuleType()) {
            case "PREFIX" -> name.startsWith(rule.getRuleValue());
            case "SUFFIX" -> name.endsWith(rule.getRuleValue());
            case "REGEX" -> name.matches(rule.getRuleValue());
            default -> true;
        };
    }

    /**
     * 字段类型检查逻辑：
     * 1. 先按命名规范匹配字段名 → 通过 targetStandardId 找到关联的字段类型标准
     * 2. 判断字段实际类型是否在 allowedTypes 列表中
     */
    private void checkColumnType(MetadataTable table, MetadataColumn col,
                                 List<NamingStandard> namingRules,
                                 List<FieldTypeStandard> typeRules,
                                 ComplianceResult result) {
        // 找到匹配该字段名的命名规范
        for (NamingStandard namingRule : namingRules) {
            if (!"COLUMN".equals(namingRule.getAppliesTo())) continue;
            if (!matchRule(col.getColumnName(), namingRule)) continue;
            if (namingRule.getTargetStandardId() == null) continue;

            // 通过 targetStandardId 找到关联的字段类型标准
            FieldTypeStandard typeRule = typeRules.stream()
                    .filter(t -> t.getId().equals(namingRule.getTargetStandardId()))
                    .findFirst().orElse(null);
            if (typeRule == null) continue;

            // 判断类型是否在允许列表中（allowedTypes 为 List<String>）
            Set<String> allowed = typeRule.getAllowedTypes().stream()
                    .map(String::trim)
                    .map(String::toLowerCase)
                    .collect(Collectors.toSet());
            if (!allowed.contains(col.getDataType().toLowerCase())) {
                result.addTypeViolation(
                        buildColumnPath(table, col),
                        "字段类型不符合标准",
                        col.getDataType(),
                        typeRule.getName(),
                        "建议改为 " + typeRule.getAllowedTypes() + " 之一"
                );
            }
        }
    }
}
```

> **与代码实现对齐的补充说明**：
> - 命名规范实体字段为 `appliesTo`（TABLE/COLUMN）、`ruleType`（PREFIX/SUFFIX/REGEX）、`ruleValue`，`enabled` 为 `Integer`
    （0/1），`priority` 数字越大越优先。
> - 字段类型标准的 `allowedTypes` 为 `List<String>`（DB JSONB 数组），非逗号分隔字符串。
> - 合规检查请求 `ComplianceCheckRequest` 支持 `datasourceId`、`datasourceIds`、`databaseName`、`schemaName`、`tableId`、
    `checkNaming`、`checkFieldType`、`startTime`、`endTime`；调用方可按数据源、库/Schema 或单表维度指定范围，未指定数据源/表时默认检查全部数据源。
> - `checkNaming`/`checkFieldType` 默认 `true`；至少开启一项，否则抛错。
> - 检查结果 DTO 包含 `actualValue`、`expectedValue`、`applicableStandards`、`isCompliant`、`checkedAt`
    等字段，前端按命名违规与字段类型违规分组展示。

---

## 10. 数据库设计

### 10.1 Flyway 迁移

| 脚本                                                                 | 版本     | 内容                                                                                                                     |
|----------------------------------------------------------------------|----------|--------------------------------------------------------------------------------------------------------------------------|
| `V3.0.0__add_batch_sync_tables.sql` 🆕                               | Sprint 2 | 新建 sync_job / sync_job_history / sync_job_log                                                                          |
| `V3.0.1__add_data_standard_tables.sql` 🆕                            | Sprint 2 | 新建 naming_standard / field_type_standard / compliance_check_result                                                     |
| `V3.0.2__metadata_source_type_and_preview.sql` 🆕                    | Sprint 2 | metadata_table 新增 source_type，支持数据预览                                                                            |
| `V3.0.3__sync_job_enhancements.sql` 🆕                               | Sprint 2 | sync_job 拆分 execution_status、target_database/target_table、重试相关字段                                               |
| `V3.0.5__compliance_violation_type.sql` 🆕                           | Sprint 2 | compliance_check_result 新增 violation_type                                                                              |
| `V3.0.6__metadata_table_column_count.sql` 🆕                         | Sprint 2 | metadata_table 新增 column_count                                                                                         |
| `V3.0.7__alter_jsonb_columns_to_text.sql` 🆕                         | Sprint 2 | 部分 JSONB 列改为 TEXT（MyBatis 兼容）                                                                                   |
| `V3.0.8__metadata_column_source_status.sql` 🆕                       | Sprint 2 | metadata_column 新增 source_status                                                                                       |
| `V3.0.9__sync_job_retry_history.sql` 🆕                              | Sprint 2 | sync_job_history 新增 parent_history_id / retry_count / next_retry_at；sync_job 新增 last_execute_time / last_history_id |
| `V3.1.0__history_time_range_index.sql` 🆕                            | Sprint 2 | 同步/采集历史表增加时间范围索引                                                                                          |
| `V3.1.1__compliance_check_applicable_standards.sql` 🆕               | Sprint 2 | compliance_check_result 新增 applicable_standards                                                                        |
| `V3.1.2__alter_compliance_check_applicable_standards_to_text.sql` 🆕 | Sprint 2 | applicable_standards 改为 TEXT 存储                                                                                      |
| `V3.1.3__add_compliance_check_result_checked_at_index.sql` 🆕        | Sprint 2 | compliance_check_result 增加 checked_at 索引                                                                             |

> 注：`V3.2.x`、`V3.3.x`、`V3.4.x` 系列为 Sprint 3 及后续迭代的迁移脚本，不在 Sprint 2 范围内。

### 10.2 metadata_table 变更

```sql
-- V3.0.0__add_metadata_source_type.sql
-- metadata_table 新增 source_type 字段，区分内置 Doris / 外部数据源

ALTER TABLE metadata_table
    ADD COLUMN IF NOT EXISTS source_type VARCHAR (20) NOT NULL DEFAULT 'EXTERNAL';

COMMENT
ON COLUMN metadata_table.source_type IS 
    '元数据来源：BUILTIN_DORIS 内置Doris（同步任务自动注册）/ EXTERNAL 外部数据源（采集任务注册）';

-- 将 datasource_id 改为可空（内置 Doris 没有对应数据源记录）
ALTER TABLE metadata_table
    ALTER COLUMN datasource_id DROP NOT NULL;
```

### 10.3 同步任务相关表

> 以下 DDL 综合了 `V3.0.0__add_batch_sync_tables.sql`、`V3.0.3__sync_job_enhancements.sql`、
> `V3.0.7__alter_jsonb_columns_to_text.sql`、`V3.0.9__sync_job_retry_history.sql` 的变更。
> `source_tables_detail`、`read_rate_limit_mbps`、`write_rate_limit_rows_per_second`、
> `rate_limit_enabled` 为 Sprint 3 通过 `V3.2.1__sync_job_multitable_and_ratelimit.sql`
> / `V3.2.3__sync_job_source_tables_detail_text.sql` 引入的字段，若仅回顾 Sprint 2 交付范围可忽略。
>
> `sync_mode` 当前代码与迁移脚本均使用 `FULL / INCREMENTAL`，文档按代码实现书写。

```sql
-- ===== 同步任务主表 =====
CREATE TABLE IF NOT EXISTS sync_job
(
    id                    BIGINT       NOT NULL PRIMARY KEY,
    name                  VARCHAR(100) NOT NULL,
    source_datasource_id  BIGINT       NOT NULL,
    target_datasource_id  BIGINT       DEFAULT NULL, -- 已废弃，目标端固定为内置 Doris
    source_database       VARCHAR(100) DEFAULT NULL,
    source_schema         VARCHAR(100) DEFAULT NULL,
    source_tables         TEXT         NOT NULL DEFAULT '[]', -- 源表名数组 JSON 字符串
    sync_mode             VARCHAR(20)  NOT NULL DEFAULT 'FULL', -- FULL / INCREMENTAL
    trigger_type          VARCHAR(20)  NOT NULL DEFAULT 'MANUAL', -- MANUAL / CRON / DAG
    cron_expression       VARCHAR(100) DEFAULT NULL,
    retry_times           INT          NOT NULL DEFAULT 0, -- 0-3
    retry_interval        INT          NOT NULL DEFAULT 0, -- 重试间隔分钟数 1-30
    field_mapping         TEXT         NOT NULL DEFAULT '[]', -- 字段映射 JSON 字符串
    status                VARCHAR(20)  NOT NULL DEFAULT 'NORMAL', -- NORMAL / PAUSED（调度状态）
    execution_status      VARCHAR(20)  NOT NULL DEFAULT 'PENDING', -- PENDING / RUNNING / SUCCESS / FAILED / TERMINATED（执行状态）
    schedule_enabled      SMALLINT     NOT NULL DEFAULT 0, -- 0-停止，1-运行
    xxl_job_id            INT          DEFAULT NULL,
    description           TEXT         DEFAULT NULL,
    target_database       VARCHAR(100) DEFAULT NULL, -- 目标 Doris 库名
    target_table          VARCHAR(100) DEFAULT NULL, -- 目标表名
    next_execution_time   TIMESTAMP    DEFAULT NULL, -- Cron 下次执行时间
    incremental_field     VARCHAR(100) DEFAULT NULL, -- 增量同步字段
    source_tables_detail  TEXT         NOT NULL DEFAULT '[]', -- 多表结构化配置 JSON 字符串（Sprint 3）
    read_rate_limit_mbps              INT     NOT NULL DEFAULT 0, -- 读取限速 MB/s（Sprint 3）
    write_rate_limit_rows_per_second  INT     NOT NULL DEFAULT 0, -- 写入限速 行/s（Sprint 3）
    rate_limit_enabled    SMALLINT     NOT NULL DEFAULT 0, -- 0-关闭，1-开启（Sprint 3）
    last_execute_time     TIMESTAMP    DEFAULT NULL, -- 最近执行时间
    last_history_id       BIGINT       DEFAULT NULL, -- 最近一次执行历史 ID
    created_by            BIGINT       DEFAULT NULL,
    updated_by            BIGINT       DEFAULT NULL,
    created_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_sync_job_name                 ON sync_job(name);
CREATE INDEX IF NOT EXISTS idx_sync_job_source_datasource_id       ON sync_job(source_datasource_id);
CREATE INDEX IF NOT EXISTS idx_sync_job_target_datasource_id       ON sync_job(target_datasource_id);
CREATE INDEX IF NOT EXISTS idx_sync_job_status                     ON sync_job(status);
CREATE INDEX IF NOT EXISTS idx_sync_job_rate_limit_enabled         ON sync_job(rate_limit_enabled) WHERE rate_limit_enabled = 1;

COMMENT ON TABLE  sync_job IS '批量数据同步任务';
COMMENT ON COLUMN sync_job.name                  IS '任务名称，全局唯一';
COMMENT ON COLUMN sync_job.source_datasource_id  IS '源数据源 ID';
COMMENT ON COLUMN sync_job.target_datasource_id  IS '目标数据源 ID（已废弃，目标端固定为内置 Doris）';
COMMENT ON COLUMN sync_job.source_database       IS '源数据库名';
COMMENT ON COLUMN sync_job.source_schema         IS '源 Schema 名';
COMMENT ON COLUMN sync_job.source_tables         IS '源表名数组 JSON 字符串';
COMMENT ON COLUMN sync_job.sync_mode             IS '同步模式：FULL 全量，INCREMENTAL 增量';
COMMENT ON COLUMN sync_job.trigger_type          IS '触发方式：MANUAL 手动，CRON 定时，DAG 编排';
COMMENT ON COLUMN sync_job.cron_expression       IS 'Cron 表达式';
COMMENT ON COLUMN sync_job.retry_times           IS '失败重试次数（0-3）';
COMMENT ON COLUMN sync_job.retry_interval        IS '重试间隔分钟数（1-30）';
COMMENT ON COLUMN sync_job.field_mapping         IS '字段映射配置 JSON 字符串';
COMMENT ON COLUMN sync_job.status                IS '调度状态：NORMAL 正常，PAUSED 暂停';
COMMENT ON COLUMN sync_job.execution_status      IS '执行状态：PENDING / RUNNING / SUCCESS / FAILED / TERMINATED';
COMMENT ON COLUMN sync_job.schedule_enabled      IS '调度是否启用（0-停止，1-运行）';
COMMENT ON COLUMN sync_job.xxl_job_id            IS 'XXL-JOB 注册任务 ID';
COMMENT ON COLUMN sync_job.description           IS '任务描述';
COMMENT ON COLUMN sync_job.target_database       IS '目标 Doris 库名';
COMMENT ON COLUMN sync_job.target_table          IS '目标表名';
COMMENT ON COLUMN sync_job.next_execution_time   IS 'Cron 任务下一次执行时间';
COMMENT ON COLUMN sync_job.incremental_field     IS '增量同步字段';
COMMENT ON COLUMN sync_job.source_tables_detail  IS '多表结构化配置 JSON 字符串';
COMMENT ON COLUMN sync_job.read_rate_limit_mbps  IS '读取速率限制（MB/s，0=不限制）';
COMMENT ON COLUMN sync_job.write_rate_limit_rows_per_second IS '写入速率限制（行/秒，0=不限制）';
COMMENT ON COLUMN sync_job.rate_limit_enabled    IS '限流总开关（0-关闭，1-开启）';
COMMENT ON COLUMN sync_job.last_execute_time     IS '最近执行时间';
COMMENT ON COLUMN sync_job.last_history_id       IS '最近一次执行历史 ID';
COMMENT ON COLUMN sync_job.created_by            IS '创建人 ID';
COMMENT ON COLUMN sync_job.updated_by            IS '修改人 ID';
COMMENT ON COLUMN sync_job.created_at            IS '创建时间';
COMMENT ON COLUMN sync_job.updated_at            IS '修改时间';

-- ===== 同步任务执行历史 =====
CREATE TABLE IF NOT EXISTS sync_job_history
(
    id                 BIGINT       NOT NULL PRIMARY KEY,
    sync_job_id        BIGINT       NOT NULL,
    trigger_type       VARCHAR(20)  NOT NULL, -- MANUAL / CRON / DAG
    status             VARCHAR(20)  NOT NULL DEFAULT 'RUNNING', -- RUNNING / SUCCESS / FAILED / TERMINATED
    start_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    end_time           TIMESTAMP    DEFAULT NULL,
    duration_ms        BIGINT       DEFAULT NULL,
    source_rows        BIGINT       DEFAULT 0,
    target_rows        BIGINT       DEFAULT 0,
    error_message      TEXT         DEFAULT NULL,
    parent_history_id  BIGINT       DEFAULT NULL, -- 重试时指向来源执行记录
    retry_count        INT          NOT NULL DEFAULT 0, -- 当前执行链已发生的重试次数
    next_retry_at      TIMESTAMP    DEFAULT NULL, -- 计划下次重试时间（仅记录）
    created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_sync_job_history_sync_job_id   ON sync_job_history(sync_job_id);
CREATE INDEX IF NOT EXISTS idx_sync_job_history_status        ON sync_job_history(status);
CREATE INDEX IF NOT EXISTS idx_sync_job_history_start_time    ON sync_job_history(start_time);
CREATE INDEX IF NOT EXISTS idx_sync_job_history_parent_id     ON sync_job_history(parent_history_id);

COMMENT ON TABLE  sync_job_history IS '批量数据同步执行历史';
COMMENT ON COLUMN sync_job_history.sync_job_id       IS '关联同步任务 ID';
COMMENT ON COLUMN sync_job_history.trigger_type      IS '触发方式：MANUAL / CRON / DAG';
COMMENT ON COLUMN sync_job_history.status            IS '执行状态：RUNNING / SUCCESS / FAILED / TERMINATED';
COMMENT ON COLUMN sync_job_history.start_time        IS '开始时间';
COMMENT ON COLUMN sync_job_history.end_time          IS '结束时间';
COMMENT ON COLUMN sync_job_history.duration_ms       IS '执行耗时（毫秒）';
COMMENT ON COLUMN sync_job_history.source_rows       IS '源表读取行数';
COMMENT ON COLUMN sync_job_history.target_rows       IS '目标表写入行数';
COMMENT ON COLUMN sync_job_history.error_message     IS '错误信息';
COMMENT ON COLUMN sync_job_history.parent_history_id IS '父历史记录 ID，重试时指向来源执行记录';
COMMENT ON COLUMN sync_job_history.retry_count       IS '当前执行链已发生的重试次数';
COMMENT ON COLUMN sync_job_history.next_retry_at     IS '计划下次重试时间（仅记录）';
COMMENT ON COLUMN sync_job_history.created_at        IS '创建时间';

-- ===== 同步任务执行日志（Addax 日志片段） =====
CREATE TABLE IF NOT EXISTS sync_job_log
(
    id           BIGINT       NOT NULL PRIMARY KEY,
    history_id   BIGINT       NOT NULL,
    sync_job_id  BIGINT       NOT NULL,
    level        VARCHAR(20)  NOT NULL DEFAULT 'INFO', -- INFO / WARN / ERROR
    message      TEXT         NOT NULL,
    line_num     INT          DEFAULT 0, -- Addax 日志原始行号
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_sync_job_log_history_id   ON sync_job_log(history_id);
CREATE INDEX IF NOT EXISTS idx_sync_job_log_sync_job_id  ON sync_job_log(sync_job_id);
CREATE INDEX IF NOT EXISTS idx_sync_job_log_created_at   ON sync_job_log(created_at);

COMMENT ON TABLE  sync_job_log IS '批量数据同步执行日志（Addax 输出）';
COMMENT ON COLUMN sync_job_log.history_id   IS '关联执行历史 ID';
COMMENT ON COLUMN sync_job_log.sync_job_id  IS '关联同步任务 ID';
COMMENT ON COLUMN sync_job_log.level        IS '日志级别：INFO / WARN / ERROR';
COMMENT ON COLUMN sync_job_log.message      IS '日志内容';
COMMENT ON COLUMN sync_job_log.line_num     IS 'Addax 日志原始行号';
COMMENT ON COLUMN sync_job_log.created_at   IS '创建时间';
```

### 10.4 数据标准相关表

> 以下 DDL 综合了 `V3.0.1__add_data_standard_tables.sql`、`V3.0.5__compliance_violation_type.sql`、
> `V3.0.7__alter_jsonb_columns_to_text.sql`、`V3.1.1__compliance_check_applicable_standards.sql`、
> `V3.1.2__alter_compliance_check_applicable_standards_to_text.sql` 的变更。

```sql
-- ===== 命名规范 =====
CREATE TABLE IF NOT EXISTS naming_standard
(
    id                 BIGINT       NOT NULL PRIMARY KEY,
    name               VARCHAR(100) NOT NULL, -- 规范名称，全局唯一
    applies_to         VARCHAR(20)  NOT NULL, -- TABLE / COLUMN（适用对象）
    rule_type          VARCHAR(20)  NOT NULL, -- PREFIX / SUFFIX / REGEX（规则类型）
    rule_value         VARCHAR(255) NOT NULL, -- 规则值（前缀/后缀文字或正则表达式）
    target_standard_id BIGINT       DEFAULT NULL, -- 关联的字段类型标准 ID
    priority           INT          NOT NULL DEFAULT 0, -- 优先级，数字越大越优先
    enabled            SMALLINT     NOT NULL DEFAULT 1, -- 0-禁用，1-启用
    description        TEXT         DEFAULT NULL,
    created_by         BIGINT       DEFAULT NULL,
    updated_by         BIGINT       DEFAULT NULL,
    created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_naming_standard_name          ON naming_standard(name);
CREATE INDEX IF NOT EXISTS idx_naming_standard_applies_to          ON naming_standard(applies_to);
CREATE INDEX IF NOT EXISTS idx_naming_standard_enabled             ON naming_standard(enabled);
CREATE INDEX IF NOT EXISTS idx_naming_standard_target_standard_id  ON naming_standard(target_standard_id);

COMMENT ON TABLE  naming_standard IS '数据标准-命名规范';
COMMENT ON COLUMN naming_standard.name              IS '规范名称，全局唯一';
COMMENT ON COLUMN naming_standard.applies_to        IS '适用对象：TABLE 表 / COLUMN 字段';
COMMENT ON COLUMN naming_standard.rule_type         IS '规则类型：PREFIX 前缀 / SUFFIX 后缀 / REGEX 正则';
COMMENT ON COLUMN naming_standard.rule_value        IS '规则值（前缀/后缀文字或正则表达式）';
COMMENT ON COLUMN naming_standard.target_standard_id IS '关联的字段类型标准 ID';
COMMENT ON COLUMN naming_standard.priority          IS '优先级，数字越大越优先';
COMMENT ON COLUMN naming_standard.enabled           IS '是否启用（0-禁用，1-启用）';
COMMENT ON COLUMN naming_standard.description       IS '描述';
COMMENT ON COLUMN naming_standard.created_by        IS '创建人 ID';
COMMENT ON COLUMN naming_standard.updated_by        IS '修改人 ID';
COMMENT ON COLUMN naming_standard.created_at        IS '创建时间';
COMMENT ON COLUMN naming_standard.updated_at        IS '修改时间';

-- ===== 字段类型标准 =====
CREATE TABLE IF NOT EXISTS field_type_standard
(
    id            BIGINT       NOT NULL PRIMARY KEY,
    name          VARCHAR(100) NOT NULL, -- 标准类型名称（如"主键ID"）
    category      VARCHAR(50)  DEFAULT NULL, -- 分类：数值 / 字符串 / 时间 等
    allowed_types TEXT         NOT NULL DEFAULT '[]', -- 允许的字段类型数组 JSON 字符串
    description   TEXT         DEFAULT NULL,
    created_by    BIGINT       DEFAULT NULL,
    updated_by    BIGINT       DEFAULT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_field_type_standard_name ON field_type_standard(name);

COMMENT ON TABLE  field_type_standard IS '数据标准-字段类型标准';
COMMENT ON COLUMN field_type_standard.name          IS '标准类型名称';
COMMENT ON COLUMN field_type_standard.category      IS '分类：数值 / 字符串 / 时间 等';
COMMENT ON COLUMN field_type_standard.allowed_types IS '允许的字段类型数组 JSON 字符串';
COMMENT ON COLUMN field_type_standard.description   IS '描述';
COMMENT ON COLUMN field_type_standard.created_by    IS '创建人 ID';
COMMENT ON COLUMN field_type_standard.updated_by    IS '修改人 ID';
COMMENT ON COLUMN field_type_standard.created_at    IS '创建时间';
COMMENT ON COLUMN field_type_standard.updated_at    IS '修改时间';

-- ===== 合规检查结果 =====
CREATE TABLE IF NOT EXISTS compliance_check_result
(
    id                  BIGINT       NOT NULL PRIMARY KEY,
    standard_id         BIGINT       DEFAULT NULL, -- 命中的命名规范 ID，未命中时可为空
    standard_name       VARCHAR(100) DEFAULT NULL, -- 命中的命名规范名称
    object_type         VARCHAR(20)  NOT NULL,     -- TABLE / COLUMN（对象类型）
    datasource_id       BIGINT       DEFAULT NULL, -- 数据源 ID
    database_name       VARCHAR(255) DEFAULT NULL, -- 数据库名
    schema_name         VARCHAR(255) DEFAULT NULL, -- Schema 名
    table_id            BIGINT       DEFAULT NULL, -- 关联元数据表 ID
    column_id           BIGINT       DEFAULT NULL, -- 关联元数据字段 ID
    object_name         VARCHAR(255) NOT NULL,     -- 对象名称（表名或字段名）
    object_path         VARCHAR(500) DEFAULT NULL, -- 检查对象路径，如 db.schema.table.column
    violation_type      VARCHAR(20)  DEFAULT NULL, -- NAMING 命名不合规 / TYPE 字段类型不合规
    actual_value        VARCHAR(255) DEFAULT NULL, -- 实际值（字段类型等）
    expected_value      VARCHAR(255) DEFAULT NULL, -- 期望值（允许的字段类型等）
    applicable_standards TEXT        DEFAULT NULL, -- 本次检查涉及的相关规范列表 JSON 字符串
    is_compliant        SMALLINT     NOT NULL DEFAULT 0, -- 0-不合规，1-合规
    checked_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_compliance_check_result_standard_id    ON compliance_check_result(standard_id);
CREATE INDEX IF NOT EXISTS idx_compliance_check_result_table_id       ON compliance_check_result(table_id);
CREATE INDEX IF NOT EXISTS idx_compliance_check_result_column_id      ON compliance_check_result(column_id);
CREATE INDEX IF NOT EXISTS idx_compliance_check_result_object_type    ON compliance_check_result(object_type);
CREATE INDEX IF NOT EXISTS idx_compliance_check_result_datasource_id  ON compliance_check_result(datasource_id);
CREATE INDEX IF NOT EXISTS idx_compliance_check_result_database_name  ON compliance_check_result(database_name);
CREATE INDEX IF NOT EXISTS idx_compliance_check_result_schema_name    ON compliance_check_result(schema_name);
CREATE INDEX IF NOT EXISTS idx_compliance_check_result_violation_type ON compliance_check_result(violation_type);
CREATE INDEX IF NOT EXISTS idx_compliance_check_result_checked_at     ON compliance_check_result(checked_at);

COMMENT ON TABLE  compliance_check_result IS '合规检查结果';
COMMENT ON COLUMN compliance_check_result.standard_id         IS '命中的命名规范 ID，未命中时可为空';
COMMENT ON COLUMN compliance_check_result.standard_name       IS '命中的命名规范名称，未命中时可为空';
COMMENT ON COLUMN compliance_check_result.object_type         IS '对象类型：TABLE 表 / COLUMN 字段';
COMMENT ON COLUMN compliance_check_result.datasource_id       IS '数据源 ID';
COMMENT ON COLUMN compliance_check_result.database_name       IS '数据库名';
COMMENT ON COLUMN compliance_check_result.schema_name         IS 'Schema 名';
COMMENT ON COLUMN compliance_check_result.table_id            IS '关联元数据表 ID';
COMMENT ON COLUMN compliance_check_result.column_id           IS '关联元数据字段 ID';
COMMENT ON COLUMN compliance_check_result.object_name         IS '对象名称（表名或字段名）';
COMMENT ON COLUMN compliance_check_result.object_path         IS '检查对象路径，如 db.schema.table.column';
COMMENT ON COLUMN compliance_check_result.violation_type      IS '违规类型：NAMING 命名不合规，TYPE 字段类型不合规';
COMMENT ON COLUMN compliance_check_result.actual_value        IS '实际值（字段类型等）';
COMMENT ON COLUMN compliance_check_result.expected_value      IS '期望值（允许的字段类型等）';
COMMENT ON COLUMN compliance_check_result.applicable_standards IS '本次检查涉及的相关规范列表 JSON 字符串';
COMMENT ON COLUMN compliance_check_result.is_compliant        IS '是否合规（0-不合规，1-合规）';
COMMENT ON COLUMN compliance_check_result.checked_at          IS '检查时间';
```

---

## 11. API 接口设计

### 11.1 同步任务接口

| 方法   | 路径                                                       | 说明                                              |
|--------|------------------------------------------------------------|---------------------------------------------------|
| POST   | `/api/engineering/sync-jobs/page`                          | 任务列表（keyword + status + triggerType + 分页） |
| GET    | `/api/engineering/sync-jobs/{id}`                          | 详情（含字段映射、sourceTablesDetail）            |
| POST   | `/api/engineering/sync-jobs`                               | 创建                                              |
| PUT    | `/api/engineering/sync-jobs/{id}`                          | 编辑                                              |
| DELETE | `/api/engineering/sync-jobs/{id}`                          | 删除（二次确认）                                  |
| POST   | `/api/engineering/sync-jobs/{id}/execute`                  | 手动执行                                          |
| POST   | `/api/engineering/sync-jobs/{id}/schedule/start`           | 启用调度                                          |
| POST   | `/api/engineering/sync-jobs/{id}/schedule/stop`            | 停用调度                                          |
| POST   | `/api/engineering/sync-jobs/{id}/history/page`             | 某任务执行历史（分页）                            |
| POST   | `/api/engineering/sync-jobs/history/page`                  | 全局执行历史（分页）                              |
| POST   | `/api/engineering/sync-jobs/history/{historyId}/stop`      | 停止运行中的历史记录                              |
| GET    | `/api/engineering/sync-jobs/{id}/history/{historyId}/logs` | 某次执行日志（Addax 原始输出）                    |

```java
// SyncJobCreateRequest（SyncJobUpdateRequest 字段与其一致，仅用于更新）
public class SyncJobCreateRequest {
    @NotBlank @Size(min = 3, max = 50) private String name;
    @NotNull  private Long sourceDatasourceId;          // 源数据源 ID
              private String sourceDatabase;            // 源库名
              private String sourceSchema;              // 源 Schema 名
    @NotEmpty private List<String> sourceTables;        // 源表名数组（多表同步）
    @NotBlank @Pattern(regexp = "^(FULL|INCREMENTAL)$")
              private String syncMode;                  // FULL / INCREMENTAL
              private String incrementalField;          // 增量同步字段
    @NotBlank @Pattern(regexp = "^(MANUAL|CRON)$")
              private String triggerType;               // MANUAL / CRON（DAG 编排由后端写入）
              private String cronExpression;            // Cron 表达式
    @Min(0) @Max(3)
              private Integer retryTimes = 3;           // 重试次数 0-3
    @Min(0) @Max(30)
              private Integer retryInterval = 5;        // 重试间隔分钟数 0-30
              private List<FieldMappingItem> fieldMapping;
    @NotBlank private String targetDatabase;            // 目标 Doris 库名
    @NotBlank private String targetTable;               // 目标表名
              private String sourceTablesDetail;        // JSON 字符串：多表结构化配置
              private Integer readRateLimitMbps = 0;    // 读取限速 MB/s
              private Integer writeRateLimitRowsPerSecond = 0; // 写入限速 行/s
              private Boolean rateLimitEnabled = false; // 限流总开关
              private String description;
}

public class FieldMappingItem {
    private String sourceColumn;
    private String targetColumn;
    private String targetType;
}

public class SourceTableDetail {
    private String sourceTable;
    private String targetTable;
    private List<FieldMappingItem> fieldMapping;
}

// SyncJobDTO
public class SyncJobDTO {
    private Long id;
    private String name;
    private Long sourceDatasourceId;
    private String sourceDatabase;
    private String sourceSchema;
    private List<String> sourceTables;
    private String syncMode;
    private String incrementalField;
    private String triggerType;
    private String cronExpression;
    private Integer retryTimes;
    private Integer retryInterval;
    private List<FieldMappingItem> fieldMapping;
    private String status;                 // NORMAL / PAUSED
    private String executionStatus;        // PENDING / RUNNING / SUCCESS / FAILED / TERMINATED
    private Integer scheduleEnabled;       // 0-停止，1-运行
    private Boolean rateLimitEnabled;
    private Integer readRateLimitMbps;
    private Integer writeRateLimitRowsPerSecond;
    private String targetDatabase;
    private String targetTable;
    private List<SourceTableDetail> sourceTablesDetail;
    private LocalDateTime nextExecutionTime;
    private LocalDateTime lastExecuteTime;
    private Long lastHistoryId;
    private Integer xxlJobId;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long createdBy;
    private Long updatedBy;
    private String createdByName;
    private String updatedByName;
}

// SyncJobHistoryDTO
public class SyncJobHistoryDTO {
    private Long id;
    private Long syncJobId;
    private String taskName;
    private Long dagExecutionId;       // DAG 触发时才有
    private Long dagId;
    private String dagName;
    private String triggerType;
    private String status;             // RUNNING / SUCCESS / FAILED / TERMINATED
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Long durationMs;
    private Long durationSeconds;
    private Double throughputRowsPerSecond;
    private Long sourceRows;
    private Long targetRows;
    private String errorMessage;
    private String sourceDatabase;
    private String sourceSchema;
    private String sourceTable;
    private String targetDatabase;
    private String targetTable;
    private String syncMode;
    private String incrementalField;
    private Long parentHistoryId;      // 重试链父记录
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private LocalDateTime createdAt;
}

// SyncJobLogDTO
public class SyncJobLogDTO {
    private Long id;
    private Long historyId;
    private Long syncJobId;
    private String level;          // INFO / WARN / ERROR
    private String message;
    private Integer lineNum;       // Addax 原始日志行号
    private LocalDateTime createdAt;
}
```

### 11.2 数据预览接口

| 方法 | 路径                                            | 说明                          |
|------|-------------------------------------------------|-------------------------------|
| GET  | `/api/engineering/datasources/{id}/schema-tree` | 数据源的库/Schema/表 树形结构 |
| GET  | `/api/engineering/preview`                      | 预览表前 100 行               |

```
GET /api/engineering/preview?dsId=1&database=production&table=users
→ { totalRows: 500000, columns: ["id","username","created_at"], rows: [...] }
```

### 11.3 数据标准接口

| 方法   | 路径                                                       | 说明                                       |
|--------|------------------------------------------------------------|--------------------------------------------|
| POST   | `/api/governance/data-standards/naming-standards/page`     | 命名规范列表（keyword + appliesTo + 分页） |
| GET    | `/api/governance/data-standards/naming-standards/{id}`     | 命名规范详情                               |
| POST   | `/api/governance/data-standards/naming-standards`          | 新建命名规范                               |
| PUT    | `/api/governance/data-standards/naming-standards/{id}`     | 编辑命名规范                               |
| DELETE | `/api/governance/data-standards/naming-standards/{id}`     | 删除命名规范                               |
| POST   | `/api/governance/data-standards/field-type-standards/page` | 字段类型标准列表                           |
| GET    | `/api/governance/data-standards/field-type-standards/{id}` | 字段类型标准详情                           |
| POST   | `/api/governance/data-standards/field-type-standards`      | 新建字段类型标准                           |
| PUT    | `/api/governance/data-standards/field-type-standards/{id}` | 编辑字段类型标准                           |
| DELETE | `/api/governance/data-standards/field-type-standards/{id}` | 删除字段类型标准                           |
| POST   | `/api/governance/data-standards/compliance-check`          | 执行合规检查                               |
| POST   | `/api/governance/data-standards/compliance-check/results`  | 查询合规检查结果                           |

```java
// NamingStandardCreateRequest（UpdateRequest 与其一致）
public class NamingStandardCreateRequest {
    @NotBlank private String name;            // 3-50 位，全局唯一
    @NotBlank private String appliesTo;       // TABLE / COLUMN
    @NotBlank private String ruleType;        // PREFIX / SUFFIX / REGEX
    @NotBlank private String ruleValue;       // 规则值
              private Long targetStandardId;  // 关联字段类型标准 ID
              private Integer priority = 0;   // 数字越大越优先
              private Integer enabled = 1;    // 0-禁用，1-启用
              private String description;
}

// NamingStandardDTO
public class NamingStandardDTO {
    private Long id;
    private String name;
    private String appliesTo;
    private String ruleType;
    private String ruleValue;
    private Long targetStandardId;
    private String targetStandardName;
    private Integer priority;
    private Integer enabled;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long createdBy;
    private Long updatedBy;
    private String createdByName;
    private String updatedByName;
}

// FieldTypeStandardCreateRequest（UpdateRequest 与其一致）
public class FieldTypeStandardCreateRequest {
    @NotBlank private String name;
              private String category;
    @NotEmpty private List<String> allowedTypes; // 允许类型数组
              private String description;
}

// FieldTypeStandardDTO
public class FieldTypeStandardDTO {
    private Long id;
    private String name;
    private String category;
    private List<String> allowedTypes;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long createdBy;
    private Long updatedBy;
    private String createdByName;
    private String updatedByName;
}

// ComplianceCheckRequest
public class ComplianceCheckRequest {
    private Long datasourceId;          // 单个数据源（与 datasourceIds 二选一）
    private List<Long> datasourceIds;   // 多数据源；为空或 null 时检查全部
    private String databaseName;
    private String schemaName;
    private Long tableId;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
}

// ComplianceCheckResultDTO
public class ComplianceCheckResultDTO {
    private Long id;
    private Long standardId;
    private String standardName;
    private String objectType;          // TABLE / COLUMN
    private String objectPath;          // 如 order_db.production.orders.id
    private String violationType;       // NAMING / TYPE
    private Long tableId;
    private String tableName;
    private Long columnId;
    private String columnName;
    private String objectName;
    private String actualValue;
    private String expectedValue;
    private List<ApplicableStandardDTO> applicableStandards;
    private Integer isCompliant;        // 0-不合规，1-合规
    private LocalDateTime checkedAt;

    public static class ApplicableStandardDTO {
        private String standardName;
        private String ruleType;
        private String ruleValue;
        private List<String> allowedTypes;
    }
}
```

---

## 12. 共享配置变更

### 12.1 新增 shared-addax.yaml

```yaml
# shared-addax.yaml 🆕
addax:
  home: /opt/addax
  job-dir: /tmp/addax-jobs        # 临时 job.json 存放路径
  timeout-seconds: 3600           # 单任务超时（1 小时）

doris:
  stream-load:
    load-url: doris-fe:8030
    max-retries: 3
    properties:
      format: json
      strip_outer_array: "true"
```

engineering-service 引入：

```yaml
spring:
  config:
    import:
      - nacos:shared-addax.yaml?refreshEnabled=true&group=shared-configs
```

### 12.2 shared-xxljob.yaml 调整

原 shared-xxljob.yaml 中 executor 配置改为可由各服务覆盖：

```yaml
# shared-xxljob.yaml（调整 executor 为可选覆盖）
xxl:
  job:
    admin:
      addresses: http://${XXL_JOB_HOST:localhost}:8088
    accessToken: ${XXL_JOB_TOKEN:datanest_xxl_token}
    executor:
      # appname 和 port 由各服务在自己的 application.yml 中覆盖
      logpath: /data/applogs/xxl-job
```

engineering-service 覆盖 `appname` 和 `port`，governance 不变。

### 12.3 shared-security.yaml 变更

无变更。`datanest.security.encryption.key` 已有，engineering 和 governance 共用。

---

## 13. 前端设计

### 13.1 新增页面

```
src/pages/
├── engineering/
│   ├── datasources/              # Sprint 1 已有（+ 预览按钮），菜单显示为「数据源管理」
│   ├── sync-jobs/               # 🆕 批量同步列表 + 创建/编辑抽屉
│   │   └── [id]/                #   任务详情（可选独立页，也可抽屉）
│   └── sync-job-history/        # 🆕 全局同步执行历史页（含按任务筛选、停止、日志）
├── governance/
│   ├── collect-tasks/            # Sprint 1 已有
│   ├── metadata/                 # Sprint 1 已有（+ 预览按钮）
│   └── data-standards/          # 🆕 数据标准（路由 /governance/data-standards）
│       ├── naming/               #   命名规范 Tab + 新建弹窗
│       ├── field-types/          #   字段类型标准 Tab + 新建弹窗
│       └── compliance/           #   合规检查结果页
```

> 与旧版文档的区别：
> - 历史记录不再放在 `sync-jobs/:id/history` 的子路由下，而是作为全局页面 `/engineering/sync-job-history`。
> - 数据标准页面路由统一为 `/governance/data-standards`，与后端 `DataStandardController` 的
    `@RequestMapping("/data-standards")` 保持一致。
> - 「数据源」菜单显示文案为「数据源管理」，与 PRD 对齐。

### 13.2 菜单联动

```ts
const menuConfig: Record<string, MenuItem[]> = {
    SUPER_ADMIN: [
        {key: 'home', label: '首页'},
        {key: 'datasources', label: '数据源管理', path: '/engineering/datasources'},
        {key: 'sync-jobs', label: '批量数据同步任务', path: '/engineering/sync-jobs'},      // 🆕
        {key: 'collect-tasks', label: '元数据采集任务', path: '/governance/collect-tasks'},
        {key: 'metadata', label: '元数据管理', path: '/governance/metadata'},
        {key: 'data-standards', label: '数据标准', path: '/governance/data-standards'},   // 🆕
        {key: 'system', label: '系统管理', children: [...]},
    ],
    DATA_ENGINEER: [
        {key: 'home', label: '首页'},
        {key: 'datasources', label: '数据源管理', path: '/engineering/datasources'},
        {key: 'sync-jobs', label: '批量数据同步任务', path: '/engineering/sync-jobs'},      // 🆕
        {key: 'metadata', label: '元数据管理', path: '/governance/metadata'},
    ],
    GOV_ADMIN: [
        {key: 'home', label: '首页'},
        {key: 'datasources', label: '数据源管理', path: '/engineering/datasources', readonly: true},
        {key: 'collect-tasks', label: '元数据采集任务', path: '/governance/collect-tasks'},
        {key: 'metadata', label: '元数据管理', path: '/governance/metadata'},
        {key: 'data-standards', label: '数据标准', path: '/governance/data-standards'},   // 🆕
    ],
    DATA_ANALYST: [
        {key: 'home', label: '首页'},
        {key: 'metadata', label: '元数据管理', path: '/governance/metadata', readonly: true},
    ],
};
```

### 13.3 元数据管理树 —— 内置 Doris 置顶

前端 `MetadataTree` 组件按 `source_type` 分组：

```tsx
const treeData = useMemo(() => {
    const builtinDoris = tables.filter(t => t.sourceType === 'BUILTIN_DORIS');
    const external = tables.filter(t => t.sourceType === 'EXTERNAL');
    return [
        {
            key: 'builtin-doris',
            title: '🏠 Doris（内置）',
            children: buildTree(builtinDoris),
        },
        ...buildExternalGroup(external), // 按数据源分组
    ];
}, [tables]);
```

---

## 14. Sprint 2 ADR

### ADR-S2-001: 批量同步引擎——Addax 命令行调用

| 项目         | 内容                                                                                                                                                                                                                                |
|--------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **状态**     | Accepted                                                                                                                                                                                                                            |
| **上下文**   | 需要将 MySQL/PostgreSQL/Doris 数据批量同步到内置 Doris。Addax 6.0.11 已有官方 Docker 镜像                                                                                                                                           |
| **决策**     | **新增独立 `data-nest-worker` 容器，Dockerfile 基于 `quay.io/wgzhao/addax:6.0.11` 构建**，worker 作为 XXL-JOB Executor 通过 `ProcessBuilder` 调 `addax.sh job.json`。engineering-service 负责任务 CRUD 与调度注册，不直接执行 Addax |
| **替代方案** | Java 内嵌——classpath 冲突风险高；独立容器——多一个容器编排，日志收集复杂                                                                                                                                                             |
| **后果**     | 📈 Addax 原生方式，DolphinScheduler 也是这样调；📈 升级 Addax 只需改 worker Dockerfile 的 `COPY --from` 源镜像 tag；📉 新增 worker 容器，运维面略增；📉 进程管理需自己处理超时/僵尸进程                                             |

### ADR-S2-002: metadata_table 区分内置 vs 外部

| 项目         | 内容                                                                                                                                |
|--------------|-------------------------------------------------------------------------------------------------------------------------------------|
| **状态**     | Accepted                                                                                                                            |
| **上下文**   | 内置 Doris 的表由同步任务写入后自动注册，外部数据源的表由采集任务注册。元数据管理页需要将内置 Doris 置顶展示                        |
| **决策**     | `metadata_table` 新增 `source_type` 字段（`BUILTIN_DORIS` / `EXTERNAL`），`datasource_id` 改为可空。内置 Doris 不创建虚拟数据源记录 |
| **替代方案** | 创建一条特殊的 `datasource_connection` 记录表示内置 Doris——污染数据源表，用户可能看到不该看的记录                                   |
| **后果**     | 📈 前端按 `source_type` 分组渲染干净；📈 查询和统计可区分来源；📉 历史数据需要补填 `source_type = 'EXTERNAL'`（迁移脚本处理）       |

### ADR-S2-003: 同步后元数据注册——直接写表

| 项目       | 内容                                                                                                                                                                          |
|------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **状态**   | Accepted                                                                                                                                                                      |
| **上下文** | 同步成功后需要注册元数据到 `metadata_table` / `metadata_column`。worker、engineering 和 governance 共用同一 PostgreSQL                                                        |
| **决策**   | **data-nest-worker 在执行 Handler 内直接通过 MyBatis-Plus 写入元数据表**，不经过 governance HTTP 接口。与 Sprint 1 的模式一致（governance 直接读 `datasource_connection` 表） |
| **后果**   | 📈 零 RPC 开销，同步链路短；📈 与 Sprint 1 的「直接查表」模式统一；📉 worker 需要维护 `MetadataTableMapper` / `MetadataColumnMapper`（但遵循现有模式，成本低）                |

### ADR-S2-004: 数据预览分页语法适配

| 项目       | 内容                                                                                                                                                 |
|------------|------------------------------------------------------------------------------------------------------------------------------------------------------|
| **状态**   | Accepted                                                                                                                                             |
| **上下文** | 数据预览需要 `SELECT * LIMIT 100`，不同数据源的分页语法不同（MySQL/PG/Doris 用 LIMIT，Oracle 用 ROWNUM，SQL Server 用 TOP）                          |
| **决策**   | **Sprint 2 三种数据源均支持 LIMIT**，直接使用。但预留 `PaginationDialect` 接口，通过 `dbType` 选择实现。后续扩展非 LIMIT 方言时只需新增 dialect 实现 |
| **后果**   | 📈 当前代码简洁；📈 扩展点清晰不侵入业务；📉 前期设计略「过度」（但成本极低，只是一个 switch）                                                       |

### ADR-S2-005: 失败重试——Sprint 2 简化版

| 项目       | 内容                                                                                                                                                                                                    |
|------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **状态**   | Accepted                                                                                                                                                                                                |
| **上下文** | 同步任务失败后需要按配置重试。生产级重试通常依赖消息队列的死信队列或调度中心的失败回调                                                                                                                  |
| **决策**   | **利用 XXL-JOB 失败重试 + `ScheduledExecutorService` 延时触发**。XXL-JOB 的 `executorFailRetryCount` 设为 0（不由 XXL-JOB 管重试），重试逻辑由 `RetryService` 自己调度 `schedulerService.trigger(task)` |
| **后果**   | 📈 实现简单，和 Sprint 1 调度逻辑一致；📉 非生产级（进程重启丢失延迟任务），但 MVP 阶段可接受                                                                                                           |

---

## 15. 验收标准

| #     | 验收项                          | 验证方式                                                      |
|-------|---------------------------------|---------------------------------------------------------------|
| AC-1  | 创建 MySQL → Doris 全量同步任务 | 选择数据源 → 库 → 表，字段自动映射，保存后列表出现任务        |
| AC-2  | 手动执行全量同步                | 点击执行 → RUNNING → SUCCESS，Doris 目标表可查到数据          |
| AC-3  | 增量同步（updated_at）          | 首次全量后，修改源表数据，第二次执行只同步增量行              |
| AC-4  | 定时同步（Cron）                | 设置 Cron 表达式，到达时间自动执行                            |
| AC-5  | 同步后元数据自动注册            | 同步成功 → 元数据管理页「Doris（内置）」节点下出现新表        |
| AC-6  | 失败自动重试                    | 配置重试 3 次/间隔 1 分钟；人为制造失败 → 3 次重试 → FAILED   |
| AC-7  | 重试成功后停止                  | 第 2 次重试成功 → SUCCESS，不执行第 3 次                      |
| AC-8  | 停用/启用调度                   | 定时任务操作区点击 → 调度开关切换，next_execution_time 更新   |
| AC-9  | 数据预览（数据源列表）          | 点击「预览」→ 左侧库树 → 点击表 → 展示前 100 行               |
| AC-10 | 数据预览（元数据管理页）        | 表详情 → 点击「预览数据」→ 弹窗展示前 100 行                  |
| AC-11 | 保存后自动采集                  | 新增数据源时勾选复选框 → 保存 → collect_task 列表出现自动任务 |
| AC-12 | 新建命名规范                    | 填写规范 → 列表出现 → 可编辑/删除                             |
| AC-13 | 新建字段类型标准                | 填写标准 → 列表出现 → 可编辑/删除                             |
| AC-14 | 合规检查                        | 点击合规检查 → 选择范围 → 展示不合规项列表                    |
| AC-15 | 检查结果跳转                    | 点击不合规项「查看」→ 跳转到元数据管理表/字段详情             |
| AC-16 | 权限隔离                        | 治理员看不到「批量数据同步任务」；工程师看不到「数据标准」    |

---

## 16. 风险与对策

| #  | 风险                                            | 概率 | 影响 | 对策                                                                                                               |
|----|-------------------------------------------------|------|------|--------------------------------------------------------------------------------------------------------------------|
| R1 | Addax DorisWriter Stream Load 配置复杂          | 中   | 中   | 通过 `shared-addax.yaml` 统一管理 Stream Load 参数；提供默认配置，用户无需手动填                                   |
| R2 | 增量字段选错（如选了 varchar）导致性能差        | 中   | 中   | 前端下拉标记不推荐的字段类型（varchar/text）；后端执行前校验增量字段类型                                           |
| R3 | 同步中源表结构变更导致字段映射失效              | 低   | 高   | 执行前校验源表当前字段与任务配置的映射是否一致；不一致时给出明确错误提示                                           |
| R4 | 千万级大表同步导致 Doris BE OOM                 | 低   | 高   | 单任务上限 1000 万行；超阈值提示用户分批（Sprint 3 优化 channel 并发控制）                                         |
| R5 | 正则表达式配置门槛高，治理员配错正则            | 中   | 低   | 正则匹配方式旁提供常用示例（如 `^ods_.*$`）                                                                        |
| R6 | Addax 进程僵尸/超时不退出                       | 低   | 中   | `AddaxExecutor` 设超时（默认 3600s）+ `Process.destroyForcibly()`                                                  |
| R7 | worker 和 governance 同时写 metadata_table 冲突 | 低   | 中   | 两个服务写的是不同的表（BUILTIN_DORIS vs EXTERNAL），不冲突；同一张表内用 `INSERT ON CONFLICT DO UPDATE`（upsert） |

---

## 附录 A：端口速查（Sprint 2 更新）

| 端口 | 服务                         | Sprint |
|------|------------------------------|:------:|
| 3000 | frontend                     |   0    |
| 8080 | gateway-service              |   0    |
| 8082 | engineering-service          |  1,2   |
| 8083 | data-nest-worker（含 Addax） |   2    |
| 8084 | governance-service           |  1,2   |
| 8088 | xxl-job-admin                |   1    |
| 8087 | system-service               |   0    |
| 8848 | Nacos                        |   0    |
| 5432 | PostgreSQL                   |   0    |
| 9030 | Doris JDBC                   |   0    |
| 8030 | Doris FE HTTP（Stream Load） |   0    |

## 附录 B：源端与目标端支持矩阵

| 源端       | Reader 插件      | 目标端 | Writer 插件 |
|------------|------------------|--------|-------------|
| MySQL      | mysqlreader      | Doris  | doriswriter |
| PostgreSQL | postgresqlreader | Doris  | doriswriter |
| Doris      | dorisreader      | Doris  | doriswriter |

## 附录 C：修订记录

| 版本 | 日期       | 修订内容                                                                                                                                                 | 作者       |
|------|------------|----------------------------------------------------------------------------------------------------------------------------------------------------------|------------|
| v1.0 | 2026-07-27 | 初始版本：Addax 集成、批量同步（全量+增量+定时+重试+告警）、数据预览、保存后自动采集、数据标准（命名规范+字段类型+合规检查）、metadata_table source_type | 软件架构师 |

> **附注**：Sprint 2 不需要对已有代码做重构。Sprint 1 已经采用「直接查表 + common 公共代码」的模式（governance 的
> `SchemaService` 直接读 `datasource_connection` 表 + 使用 common 的 `EncryptionConfig` / `JdbcSchemaExtractor`），Sprint 2
> 延续这个模式即可。
