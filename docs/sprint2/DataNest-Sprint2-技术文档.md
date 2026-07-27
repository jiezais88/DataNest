# DataNest Sprint 2 技术文档

> **Sprint**：Sprint 2 — 批量数据同步 + 数据标准
> **文档状态**：Working Draft (v1.0) | **作者**：软件架构师 | **日期**：2026-07-27
> **关联文档**：`DataNest-技术架构文档-v2.3.1.md`、`DataNest-Sprint2-批量数据同步与数据标准-PRD.md`、
> `DataNest-Sprint1-技术文档.md`

---

## 目录

1. [Sprint 概述](#1-sprint-概述)
2. [交付物清单](#2-交付物清单)
3. [架构概览](#3-架构概览)
4. [项目结构变更](#4-项目结构变更)
5. [Docker Compose 变更](#5-docker-compose-变更)
6. [engineering-service：批量数据同步](#6-engineering-service批量数据同步)
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

| # | 工作项                    | 所属服务                 | 说明                                                      |
|---|---------------------------|--------------------------|-----------------------------------------------------------|
| 1 | **Addax 批量同步引擎**    | engineering-service      | 基于 `wgzhao/addax:6.0.11` Docker 镜像，命令行调用        |
| 2 | **同步任务管理**          | engineering-service      | 创建/编辑/删除/执行同步任务，全量/增量模式，手动/定时触发 |
| 3 | **同步历史与日志**        | engineering-service      | 每次执行的详情、耗时、行数、Addax 原始日志                |
| 4 | **失败重试与告警**        | engineering-service      | 可配置 0-3 次重试 + 间隔；重试耗尽后发送告警              |
| 5 | **数据预览**              | engineering-service      | 数据源列表和元数据管理页均可预览前 100 行                 |
| 6 | **保存后自动采集**        | engineering → governance | 数据源保存时勾选「立即采集」，自动创建并执行一次采集任务  |
| 7 | **数据标准管理**          | governance-service       | 命名规范（前缀/后缀/正则）+ 字段类型标准 CRUD             |
| 8 | **合规检查**              | governance-service       | 对指定范围内的元数据执行命名规范和字段类型标准检查        |
| 9 | **内置 Doris 元数据标记** | 数据库                   | `metadata_table` 新增 `source_type` 字段区分内置/外部     |

### 1.3 架构服务关系

```
engineering-service (8082)              governance-service (8084)
├── datasource/     # 数据源管理        ├── metadata/      # 元数据管理
├── **sync/**       # 🆕 批量同步       ├── collect/       # 采集任务
│   ├── task/       #   任务 CRUD       ├── **standard/**  # 🆕 数据标准
│   ├── addax/      #   Addax 引擎      └── compliance/    # 🆕 合规检查
│   ├── schedule/   #   XXL-JOB 调度
│   └── metadata/   #   同步后元数据注册
├── **preview/**    # 🆕 数据预览
└── config/         # 连接测试

共享 PostgreSQL（同一 Schema）
├── datasource_connection     ← 两服务都读
├── metadata_table            ← governance 采集写 / engineering 同步后写
├── metadata_column           ← 同上
├── sync_task                 🆕 engineering 读写
├── sync_history              🆕 engineering 读写
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
| 同步速率限流、并发控制           |  Sprint 3   |
| 数据标准自动修复                 |  Sprint 5   |
| 数据质量规则                     |  Sprint 7   |
| 告警通知渠道 UI 配置             |  Sprint 5   |

---

## 2. 交付物清单

| #  | 交付物                                    | 类型 | 验收方式                                    |
|----|-------------------------------------------|------|---------------------------------------------|
| D1 | engineering-service `sync/` 模块          | 代码 | 同步任务 CRUD + Addax 执行 + 历史日志       |
| D2 | engineering-service `preview/` 模块       | 代码 | 数据预览前 100 行                           |
| D3 | governance-service `standard/` 模块       | 代码 | 命名规范 + 字段类型标准 CRUD                |
| D4 | governance-service `compliance/` 模块     | 代码 | 合规检查引擎 + 结果展示                     |
| D5 | Flyway 迁移 V3.0.0 + V3.0.1               | 代码 | 新增表 + metadata_table 加 source_type      |
| D6 | `docker-compose.yml` + `Dockerfile` 更新  | 配置 | engineering 多阶段构建内嵌 Addax            |
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
FROM wgzhao/addax:6.0.11 AS addax

# ===== Stage 2: JDK 21 + Addax 二进制 + Spring Boot =====
FROM eclipse-temurin:21-jre

# 从 Stage 1 复制 Addax 全部文件（/opt/addax，包含 bin/addax.sh + plugin/ + lib/）
COPY --from=addax /opt/addax /opt/addax

# 复制 Spring Boot fat jar
COPY target/data-nest-engineering-*.jar /app/app.jar

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

**调用方式**：

```java

@Service
public class AddaxExecutor {

    private static final String ADDEX_HOME = "/opt/addax";
    private static final String ADDEX_BIN = ADDEX_HOME + "/bin/addax.sh";

    public AddaxResult execute(SyncTask task, Path jobFile) {
        ProcessBuilder pb = new ProcessBuilder(
                ADDEX_BIN, jobFile.toAbsolutePath().toString()
        );
        pb.directory(new File(ADDEX_HOME));
        pb.redirectErrorStream(true);

        Process process = pb.start();
        // 异步读取 stdout/stderr，写入 sync_execution_log
        LogStreamer streamer = new LogStreamer(process.getInputStream(), historyId, logMapper);
        streamer.start();

        int exitCode = process.waitFor(timeoutSeconds, TimeUnit.SECONDS) ?
                process.exitValue() : -1;

        streamer.join(5000);
        return exitCode == 0 ? AddaxResult.success(streamer.getStats())
                : AddaxResult.failure(streamer.getErrorSummary());
    }
}
```

### 3.3 XXL-JOB 调度集成

engineering-service 作为第二个 Executor 注册到 XXL-JOB。 **代码结构与 Sprint 1 governance 的 `SchedulerService`完全一致**
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
    public void register(SyncTask task) { /* 同 Sprint 1 的 register */ }

    public void update(SyncTask task) { /* 同 Sprint 1 的 update */ }

    public void unregister(SyncTask task) { /* 同 Sprint 1 的 unregister */ }

    public void trigger(SyncTask task) { /* 同 Sprint 1 的 trigger */ }

    // 相比 Sprint 1 新增：停用/启用调度
    public void pause(SyncTask task) {
        xxlJobApi.pauseJob(cookie, task.getXxlJobId());
    }

    public void resume(SyncTask task) {
        xxlJobApi.startJob(cookie, task.getXxlJobId());
    }

    private JobInfo buildJobInfo(SyncTask task) {
        JobInfo info = new JobInfo();
        info.setJobDesc(task.getName());
        info.setAuthor("datanest");
        info.setGlueType("BEAN");
        info.setExecutorHandler("syncTaskHandler");          // ← 不同于 collectTaskHandler
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

engineering-service 的 XXL-JOB 配置（在 `application.yml` 中覆盖 shared-xxljob 的 appname 和 port）：

```yaml
xxl:
  job:
    executor:
      appname: datanest-engineering-executor
      port: 9998
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
│   ├── sync/                             # 🆕 批量同步
│   │   ├── controller/
│   │   │   ├── SyncTaskController.java
│   │   │   └── SyncHistoryController.java
│   │   ├── service/
│   │   │   ├── SyncTaskService.java      # 任务 CRUD
│   │   │   ├── AddaxJobBuilder.java      # 生成 Addax JSON
│   │   │   ├── AddaxExecutor.java        # 调用 addax.sh
│   │   │   ├── LogStreamer.java          # 异步读取 Addax stdout/stderr
│   │   │   ├── SyncHistoryService.java   # 历史记录 + 日志查询
│   │   │   ├── MetadataRegistrar.java    # 同步后写 metadata_table/column
│   │   │   ├── RetryService.java         # 失败重试逻辑
│   │   │   └── AlertService.java         # 告警通知
│   │   ├── scheduler/
│   │   │   ├── SchedulerService.java     # XXL-JOB API 封装
│   │   │   └── SyncJobHandler.java       # @XxlJob("syncTaskHandler")
│   │   ├── mapper/                       # MyBatis-Plus Mapper
│   │   │   ├── SyncTaskMapper.java
│   │   │   ├── SyncHistoryMapper.java
│   │   │   ├── MetadataTableMapper.java  # 直接写元数据表
│   │   │   └── MetadataColumnMapper.java
│   │   └── entity/
│   │       ├── SyncTask.java
│   │       ├── SyncHistory.java
│   │       ├── MetadataTable.java
│   │       └── MetadataColumn.java
│   └── preview/                          # 🆕 数据预览
│       ├── controller/PreviewController.java
│       └── service/
│           ├── PreviewService.java       # SELECT * LIMIT 100
│           └── PaginationDialect.java    # 分页语法兼容
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

### 5.1 engineering 容器

Sprint 2 不新增独立容器。engineering-service 通过多阶段构建内嵌 Addax。Doris 是 **单独服务器部署**，不在 docker-compose
中，engineering 通过 Nacos 配置的地址连接。

```yaml
# docker-compose.yml 中的 engineering 声明
engineering:
  build:
    context: ./data-nest-engineering
    dockerfile: Dockerfile
  container_name: datanest-engineering
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
    - "8082:8082"
  healthcheck:
    test: [ "CMD", "curl", "-f", "http://localhost:8082/actuator/health" ]
    interval: 15s
    timeout: 5s
    retries: 10
    start_period: 20s              # Addax 是即时可用的，无需等待
```

### 5.2 启动顺序

```
nacos-mysql → nacos → postgres → xxl-job-admin → system → engineering(含 Addax) → governance → gateway → frontend
```

> 相比 Sprint 1：engineering 不再依赖 Doris 容器（Doris 单独部署）。Doris FE 地址通过 Nacos `shared-doris.yaml`
> 配置，engineering 启动时从配置中心拉取。Addax 是随容器启动即时可用的，不需要额外预热。

### 5.3 Gateway 路由

无变更。Sprint 2 新增的接口都在 `/api/engineering/**` 和 `/api/governance/**` 下，已配置路由。

---

## 6. engineering-service：批量数据同步

### 6.1 职责

| 功能          | 接口                                            | 鉴权                        |
|---------------|-------------------------------------------------|-----------------------------|
| 同步任务列表  | `GET /api/engineering/sync-tasks`               | SUPER_ADMIN / DATA_ENGINEER |
| 创建任务      | `POST /api/engineering/sync-tasks`              | 同上                        |
| 编辑任务      | `PUT /api/engineering/sync-tasks/{id}`          | 同上                        |
| 删除任务      | `DELETE /api/engineering/sync-tasks/{id}`       | 同上                        |
| 执行任务      | `POST /api/engineering/sync-tasks/{id}/execute` | 同上                        |
| 历史记录      | `GET /api/engineering/sync-tasks/{id}/history`  | 同上                        |
| 日志查看      | `GET /api/engineering/sync-history/{id}/log`    | SUPER_ADMIN / DATA_ENGINEER |
| 停用/启用调度 | `PUT /api/engineering/sync-tasks/{id}/schedule` | SUPER_ADMIN / DATA_ENGINEER |

### 6.2 同步任务实体

```java

@Data
@TableName("sync_task")
public class SyncTask {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String name;                // 全局唯一，3-50 位
    private Long datasourceId;          // 源数据源 ID
    private String sourceDatabase;      // 源库/Schema
    private String sourceTable;         // 源表名
    private String targetDatabase;      // 目标 Doris 库
    private String targetTable;         // 目标表名
    private String fieldMapping;        // JSON: [{"source":"id","target":"id"},...]
    private String syncMode;            // FULL / INCREMENTAL
    private String incrementalField;    // 增量字段（增量模式必填）
    private String triggerType;         // MANUAL / CRON
    private String cronExpression;      // Cron 表达式（定时模式必填）
    private Integer retryCount;         // 0-3
    private Integer retryIntervalMinutes; // 1-30
    private String status;              // PENDING / RUNNING / SUCCESS / FAILED / PAUSED
    private String description;
    private Integer xxlJobId;           // XXL-JOB 注册的任务 ID
    private Boolean scheduleEnabled;    // 调度开关
    private LocalDateTime lastExecutedAt;
    private LocalDateTime nextExecutionTime;
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

    public Path buildJobFile(SyncTask task, DataSourceConnection ds) {
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
            "feLoadUrl": [
              "doris-fe:8030"
            ],
            "beLoadUrl": [
              "doris-be:8040"
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

**增量同步的特殊处理**：

```java
private String buildReaderSql(SyncTask task) {
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
public class SyncTaskService {

    private final SchedulerService schedulerService;
    private final AddaxJobBuilder jobBuilder;
    private final AddaxExecutor addaxExecutor;
    private final MetadataRegistrar metadataRegistrar;
    private final RetryService retryService;

    @Transactional
    public SyncTask create(SyncTaskCreateRequest req) {
        SyncTask task = convert(req);
        task.setStatus("PENDING");
        syncTaskMapper.insert(task);
        // 创建即注册到 XXL-JOB（同 Sprint 1 采集任务）
        schedulerService.register(task);
        return task;
    }

    @Transactional
    public void delete(Long id) {
        SyncTask task = getOrThrow(id);
        schedulerService.unregister(task);  // 先从 XXL-JOB 注销
        syncTaskMapper.deleteById(id);
        // 删除历史记录和日志
        syncHistoryMapper.deleteByTaskId(id);
    }
}
```

```java

@Component
public class SyncJobHandler {

    private final SyncTaskMapper taskMapper;
    private final AddaxJobBuilder jobBuilder;
    private final AddaxExecutor addaxExecutor;
    private final DataSourceConnectionMapper dsMapper;
    private final EncryptionConfig encryptionConfig;
    private final MetadataRegistrar metadataRegistrar;
    private final RetryService retryService;
    private final AlertService alertService;

    @XxlJob("syncTaskHandler")
    public void execute() {
        Long taskId = Long.valueOf(XxlJobHelper.getJobParam());
        SyncTask task = taskMapper.selectById(taskId);
        if (task == null) return;

        // 状态更新为 RUNNING
        task.setStatus("RUNNING");
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

    private void onSuccess(SyncTask task, SyncHistory history, AddaxResult result) {
        history.setStatus("SUCCESS");
        history.setEndedAt(Instant.now());
        history.setRowsWritten(result.getRowsWritten());
        history.setThroughput(result.getThroughput());
        syncHistoryMapper.updateById(history);

        task.setStatus("SUCCESS");
        task.setLastExecutedAt(Instant.now());
        taskMapper.updateById(task);

        // 🆕 同步成功后注册元数据
        metadataRegistrar.register(task);

        // 清理临时 job 文件
        Files.deleteIfExists(result.getJobFile());
    }

    private void onFailure(SyncTask task, SyncHistory history, AddaxResult result) {
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

            task.setStatus("FAILED");
            taskMapper.updateById(task);

            // 发送告警
            alertService.sendAlert(task, history, result);
        }
    }
}
```

### 6.5 失败重试

```java

@Service
public class RetryService {

    private final SchedulerService schedulerService;

    /** 检查是否还有重试配额 */
    public boolean shouldRetry(SyncHistory history) {
        SyncTask task = taskMapper.selectById(history.getTaskId());
        return history.getRetryCount() < task.getRetryCount();
    }

    /**
     * 延迟调度重试。
     * 利用 XXL-JOB 的一次性延时任务：当前 Handler 抛异常让 XXL-JOB 的失败重试机制接管，
     * 或者通过 ScheduledExecutorService 在间隔后重新 trigger。
     */
    public void scheduleRetry(SyncTask task, SyncHistory history) {
        long intervalMs = task.getRetryIntervalMinutes() * 60 * 1000L;
        // 使用 ScheduledExecutorService 延迟触发
        scheduler.schedule(() -> {
            schedulerService.trigger(task);
        }, intervalMs, TimeUnit.MILLISECONDS);
    }
}
```

**重试状态反馈给前端**：重试期间 `sync_task.status` 保持 `RUNNING`，`sync_history` 记录每次重试的时间线和结果。前端通过轮询历史记录展开显示重试链。

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
    public void register(SyncTask task) {
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
        // 解析 [{"source":"id","target":"id"},...] → Set<"id">
        JsonArray mappings = JsonParser.parseString(fieldMappingJson).getAsJsonArray();
        Set<String> targets = new HashSet<>();
        mappings.forEach(m -> targets.add(m.getAsJsonObject().get("target").getAsString()));
        return targets;
    }
}
```

### 6.7 Cron 表达式处理

与 Sprint 1 采集任务保持一致。前端提供 12 个预设 + 自定义拼装，后端接收标准的 Quartz Cron 表达式（6 位或 7 位）。

```java
// SyncTaskService.updateNextExecutionTime()
// 每次更新 Cron 时，用 CronExpression 计算下一次执行时间存储到 next_execution_time
// 前端列表直接展示，无需实时计算
private void updateNextExecutionTime(SyncTask task) {
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

| 功能             | 接口                                                | 鉴权                    |
|------------------|-----------------------------------------------------|-------------------------|
| 命名规范列表     | `GET /api/governance/standards/naming`              | SUPER_ADMIN / GOV_ADMIN |
| 新建命名规范     | `POST /api/governance/standards/naming`             | 同上                    |
| 编辑命名规范     | `PUT /api/governance/standards/naming/{id}`         | 同上                    |
| 删除命名规范     | `DELETE /api/governance/standards/naming/{id}`      | 同上                    |
| 字段类型标准列表 | `GET /api/governance/standards/field-types`         | 同上                    |
| 新建字段类型标准 | `POST /api/governance/standards/field-types`        | 同上                    |
| 编辑字段类型标准 | `PUT /api/governance/standards/field-types/{id}`    | 同上                    |
| 删除字段类型标准 | `DELETE /api/governance/standards/field-types/{id}` | 同上                    |
| 合规检查         | `POST /api/governance/compliance/check`             | SUPER_ADMIN / GOV_ADMIN |

### 9.2 命名规范实体

```java

@Data
@TableName("naming_standard")
public class NamingStandard {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String name;             // 规范名称，全局唯一
    private String targetType;       // TABLE / COLUMN（适用对象）
    private String matchType;        // PREFIX / SUFFIX / REGEX
    private String matchValue;       // 规范值（前缀文字 / 后缀文字 / 正则表达式）
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
@TableName("field_type_standard")
public class FieldTypeStandard {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String name;             // 标准类型名称（如"主键ID"）
    private String standardDataType; // 标准数据类型（如"bigint"）
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
     * 对指定数据源列表执行合规检查。
     * @param datasourceIds 数据源 ID 列表，空表示全部
     * @param checkNaming 是否检查命名规范
     * @param checkFieldType 是否检查字段类型标准
     */
    public ComplianceResult check(List<Long> datasourceIds,
                                  boolean checkNaming,
                                  boolean checkFieldType) {
        ComplianceResult result = new ComplianceResult();

        // 加载所有生效的标准
        List<NamingStandard> namingRules = checkNaming ? namingMapper.selectAll() : List.of();
        List<FieldTypeStandard> fieldTypeRules = checkFieldType ? fieldTypeMapper.selectAll() : List.of();

        // 获取检查范围内的所有表（含内置 Doris 和外部数据源）
        List<MetadataTable> tables = (datasourceIds == null || datasourceIds.isEmpty())
                ? metadataTableMapper.selectAll()
                : metadataTableMapper.selectByDatasourceIds(datasourceIds);

        for (MetadataTable table : tables) {
            // 1. 命名规范检查
            if (checkNaming) {
                checkTableNaming(table, namingRules, result);
                List<MetadataColumn> columns = metadataColumnMapper.selectByTableId(table.getId());
                for (MetadataColumn col : columns) {
                    checkColumnNaming(table, col, namingRules, result);
                }
            }

            // 2. 字段类型检查
            if (checkFieldType) {
                List<MetadataColumn> columns = metadataColumnMapper.selectByTableId(table.getId());
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
            if (!"TABLE".equals(rule.getTargetType())) continue;
            if (!matchRule(table.getTableName(), rule)) {
                result.addNamingViolation(
                        buildTablePath(table),
                        rule.getName(),
                        rule.getMatchType() + ":" + rule.getMatchValue()
                );
            }
        }
    }

    /**
     * 匹配逻辑：
     * - PREFIX: tableName.startsWith(matchValue)
     * - SUFFIX: tableName.endsWith(matchValue)
     * - REGEX:  tableName.matches(matchValue)
     */
    private boolean matchRule(String name, NamingStandard rule) {
        return switch (rule.getMatchType()) {
            case "PREFIX" -> name.startsWith(rule.getMatchValue());
            case "SUFFIX" -> name.endsWith(rule.getMatchValue());
            case "REGEX" -> name.matches(rule.getMatchValue());
            default -> true;
        };
    }

    /**
     * 字段类型检查逻辑：
     * 1. 先按命名规范匹配字段名 → 找到对应的数据类型标准
     * 2. 比较字段实际类型和标准类型
     */
    private void checkColumnType(MetadataTable table, MetadataColumn col,
                                 List<NamingStandard> namingRules,
                                 List<FieldTypeStandard> typeRules,
                                 ComplianceResult result) {
        // 找到匹配该字段名的命名规范
        for (NamingStandard namingRule : namingRules) {
            if (!"COLUMN".equals(namingRule.getTargetType())) continue;
            if (!matchRule(col.getColumnName(), namingRule)) continue;

            // 找到对应的字段类型标准（按规范名匹配）
            FieldTypeStandard typeRule = typeRules.stream()
                    .filter(t -> t.getName().equals(namingRule.getName()))
                    .findFirst().orElse(null);
            if (typeRule == null) continue;

            // 比较类型
            if (!typeRule.getStandardDataType().equalsIgnoreCase(col.getDataType())) {
                result.addTypeViolation(
                        buildColumnPath(table, col),
                        col.getDataType(),
                        typeRule.getStandardDataType(),
                        typeRule.getName()
                );
            }
        }
    }
}
```

---

## 10. 数据库设计

### 10.1 Flyway 迁移

| 脚本                                      | 版本     | 内容                                          |
|-------------------------------------------|----------|-----------------------------------------------|
| `V3.0.0__add_metadata_source_type.sql` 🆕 | Sprint 2 | metadata_table 新增 source_type ⭐            |
| `V3.0.1__create_sync_tables.sql` 🆕       | Sprint 2 | sync_task / sync_history / sync_execution_log |
| `V3.0.2__create_standard_tables.sql` 🆕   | Sprint 2 | naming_standard / field_type_standard         |

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

```sql
-- V3.0.1__create_sync_tables.sql

-- ===== 同步任务 =====
CREATE TABLE IF NOT EXISTS sync_task
(
    id
    BIGINT
    PRIMARY
    KEY,
    name
    VARCHAR
(
    50
) NOT NULL,
    datasource_id BIGINT NOT NULL, -- 源数据源 ID
    source_database VARCHAR
(
    100
) NOT NULL, -- 源库/Schema
    source_table VARCHAR
(
    200
) NOT NULL, -- 源表名
    target_database VARCHAR
(
    100
) NOT NULL, -- 目标 Doris 库
    target_table VARCHAR
(
    200
) NOT NULL, -- 目标表名
    field_mapping JSONB NOT NULL, -- [{"source":"id","target":"id"},...]
    sync_mode VARCHAR
(
    20
) NOT NULL, -- FULL / INCREMENTAL
    incremental_field VARCHAR
(
    100
), -- 增量字段
    trigger_type VARCHAR
(
    10
) NOT NULL, -- MANUAL / CRON
    cron_expression VARCHAR
(
    100
), -- Cron 表达式
    retry_count INTEGER NOT NULL DEFAULT 3, -- 0-3
    retry_interval_minutes INTEGER NOT NULL DEFAULT 5, -- 1-30
    status VARCHAR
(
    20
) NOT NULL DEFAULT 'PENDING', -- PENDING/RUNNING/SUCCESS/FAILED/PAUSED
    description TEXT,
    xxl_job_id INTEGER,
    schedule_enabled BOOLEAN NOT NULL DEFAULT TRUE, -- 调度开关
    last_executed_at TIMESTAMP,
    next_execution_time TIMESTAMP,
    created_by BIGINT,
    updated_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
    );

CREATE UNIQUE INDEX IF NOT EXISTS uk_sync_task_name ON sync_task(name);
CREATE INDEX IF NOT EXISTS idx_sync_task_status ON sync_task(status);
CREATE INDEX IF NOT EXISTS idx_sync_task_datasource ON sync_task(datasource_id);

COMMENT
ON TABLE sync_task IS '批量数据同步任务';
COMMENT
ON COLUMN sync_task.field_mapping IS '字段映射关系：JSON 数组 [{"source":"源字段","target":"目标字段"}]';
COMMENT
ON COLUMN sync_task.sync_mode IS '同步模式：FULL 全量，INCREMENTAL 增量';
COMMENT
ON COLUMN sync_task.schedule_enabled IS '调度开关：TRUE 已启用，FALSE 已停用';
COMMENT
ON COLUMN sync_task.status IS 'PENDING/RUNNING/SUCCESS/FAILED/PAUSED';

-- ===== 同步历史 =====
CREATE TABLE IF NOT EXISTS sync_history
(
    id
    BIGINT
    PRIMARY
    KEY,
    task_id
    BIGINT
    NOT
    NULL,
    task_name
    VARCHAR
(
    50
) NOT NULL, -- 冗余，方便查询
    trigger_type VARCHAR
(
    10
) NOT NULL, -- MANUAL / CRON
    status VARCHAR
(
    20
) NOT NULL DEFAULT 'RUNNING', -- RUNNING/SUCCESS/FAILED/RETRYING
    started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at TIMESTAMP,
    duration_ms BIGINT,
    rows_read BIGINT DEFAULT 0,
    rows_written BIGINT DEFAULT 0,
    throughput VARCHAR
(
    50
), -- "11,111 行/秒"
    retry_count INTEGER DEFAULT 0,
    error_message TEXT,
    job_file_path VARCHAR
(
    500
), -- Addax job.json 临时文件路径
    incremental_max BIGINT, -- 本次增量的最大字段值
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
    );

CREATE INDEX IF NOT EXISTS idx_sync_history_task ON sync_history(task_id);
CREATE INDEX IF NOT EXISTS idx_sync_history_status ON sync_history(status);
CREATE INDEX IF NOT EXISTS idx_sync_history_started ON sync_history(started_at);

COMMENT
ON TABLE sync_history IS '批量同步执行历史';
COMMENT
ON COLUMN sync_history.status IS 'RUNNING/SUCCESS/FAILED/RETRYING';
COMMENT
ON COLUMN sync_history.retry_count IS '当前已重试次数';

-- ===== 同步执行日志 =====
CREATE TABLE IF NOT EXISTS sync_execution_log
(
    id
    BIGSERIAL
    PRIMARY
    KEY,
    history_id
    BIGINT
    NOT
    NULL,
    task_id
    BIGINT
    NOT
    NULL,
    level
    VARCHAR
(
    10
) NOT NULL DEFAULT 'INFO',
    message TEXT NOT NULL,
    line_num INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
    );

CREATE INDEX IF NOT EXISTS idx_sync_log_history ON sync_execution_log(history_id);
CREATE INDEX IF NOT EXISTS idx_sync_log_task ON sync_execution_log(task_id);

COMMENT
ON TABLE sync_execution_log IS '批量同步执行日志（Addax 原始输出）';
COMMENT
ON COLUMN sync_execution_log.line_num IS '日志行号，按正序排列';
```

### 10.4 数据标准相关表

```sql
-- V3.0.2__create_standard_tables.sql

-- ===== 命名规范 =====
CREATE TABLE IF NOT EXISTS naming_standard
(
    id
    BIGINT
    PRIMARY
    KEY,
    name
    VARCHAR
(
    50
) NOT NULL, -- 规范名称
    target_type VARCHAR
(
    10
) NOT NULL, -- TABLE / COLUMN
    match_type VARCHAR
(
    10
) NOT NULL, -- PREFIX / SUFFIX / REGEX
    match_value VARCHAR
(
    200
) NOT NULL, -- 规范值
    description TEXT,
    created_by BIGINT,
    updated_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
    );

CREATE UNIQUE INDEX IF NOT EXISTS uk_naming_standard_name ON naming_standard(name);

COMMENT
ON TABLE naming_standard IS '数据标准-命名规范';
COMMENT
ON COLUMN naming_standard.target_type IS '适用对象：TABLE 表 / COLUMN 字段';
COMMENT
ON COLUMN naming_standard.match_type IS '匹配方式：PREFIX 前缀 / SUFFIX 后缀 / REGEX 正则';
COMMENT
ON COLUMN naming_standard.match_value IS '前缀/后缀文字 或 正则表达式';

-- ===== 字段类型标准 =====
CREATE TABLE IF NOT EXISTS field_type_standard
(
    id
    BIGINT
    PRIMARY
    KEY,
    name
    VARCHAR
(
    50
) NOT NULL, -- 标准类型名称（如"主键ID"）
    standard_data_type VARCHAR
(
    100
) NOT NULL, -- 标准数据类型（如"bigint"）
    description TEXT,
    created_by BIGINT,
    updated_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
    );

CREATE UNIQUE INDEX IF NOT EXISTS uk_field_type_standard_name ON field_type_standard(name);

COMMENT
ON TABLE field_type_standard IS '数据标准-字段类型标准';
COMMENT
ON COLUMN field_type_standard.standard_data_type IS '标准数据类型，如 bigint、datetime、decimal(18,2)';
```

---

## 11. API 接口设计

### 11.1 同步任务接口

| 方法   | 路径                                        | 说明                                          |
|--------|---------------------------------------------|-----------------------------------------------|
| GET    | `/api/engineering/sync-tasks`               | 列表（keyword + status + triggerType + 分页） |
| GET    | `/api/engineering/sync-tasks/{id}`          | 详情（含字段映射）                            |
| POST   | `/api/engineering/sync-tasks`               | 创建                                          |
| PUT    | `/api/engineering/sync-tasks/{id}`          | 编辑                                          |
| DELETE | `/api/engineering/sync-tasks/{id}`          | 删除（二次确认）                              |
| POST   | `/api/engineering/sync-tasks/{id}/execute`  | 手动执行                                      |
| PUT    | `/api/engineering/sync-tasks/{id}/schedule` | 停用/启用调度                                 |
| GET    | `/api/engineering/sync-tasks/{id}/history`  | 历史记录列表（分页）                          |
| GET    | `/api/engineering/sync-history/{id}`        | 某次执行详情                                  |
| GET    | `/api/engineering/sync-history/{id}/log`    | 某次执行日志（Addax 原始输出）                |

```java
// SyncTaskCreateRequest
public record SyncTaskCreateRequest(
                @NotBlank String name,
                @NotNull Long datasourceId,
                @NotBlank String sourceDatabase,
                @NotBlank String sourceTable,
                @NotBlank String targetDatabase,
                @NotBlank String targetTable,
                @NotEmpty List<FieldMapping> fieldMapping,
                @NotBlank String syncMode,
                String incrementalField,
                @NotBlank String triggerType,
                String cronExpression,
                @Min(0) @Max(3) Integer retryCount,
                @Min(1) @Max(30) Integer retryIntervalMinutes,
                String description,
                boolean executeImmediately
        ) {
}

public record FieldMapping(
        String source,
        String target
) {
}

// SyncTaskDTO
public record SyncTaskDTO(
        Long id, String name,
        Long datasourceId, String datasourceName,
        String sourceDatabase, String sourceTable,
        String targetDatabase, String targetTable,
        List<FieldMapping> fieldMapping,
        String syncMode, String incrementalField,
        String triggerType, String cronExpression,
        Integer retryCount, Integer retryIntervalMinutes,
        String status, Boolean scheduleEnabled,
        String lastExecutedAt, String nextExecutionTime,
        String description,
        String createdAt
) {
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

| 方法   | 路径                                         | 说明                                        |
|--------|----------------------------------------------|---------------------------------------------|
| GET    | `/api/governance/standards/naming`           | 命名规范列表（keyword + targetType + 分页） |
| POST   | `/api/governance/standards/naming`           | 新建命名规范                                |
| PUT    | `/api/governance/standards/naming/{id}`      | 编辑命名规范                                |
| DELETE | `/api/governance/standards/naming/{id}`      | 删除命名规范                                |
| GET    | `/api/governance/standards/field-types`      | 字段类型标准列表                            |
| POST   | `/api/governance/standards/field-types`      | 新建字段类型标准                            |
| PUT    | `/api/governance/standards/field-types/{id}` | 编辑字段类型标准                            |
| DELETE | `/api/governance/standards/field-types/{id}` | 删除字段类型标准                            |
| POST   | `/api/governance/compliance/check`           | 执行合规检查                                |

```java
// ComplianceCheckRequest
public record ComplianceCheckRequest(
                List<Long> datasourceIds,     // 检查范围，空=全部数据源
                boolean checkNaming,          // 是否检查命名规范
                boolean checkFieldType        // 是否检查字段类型标准
        ) {
}

// ComplianceCheckResponse
public record ComplianceCheckResponse(
        int totalViolations,
        List<NamingViolation> namingViolations,       // 按表/字段分组
        List<TypeViolation> typeViolations            // 按字段分组
) {
}

public record NamingViolation(
        String path,                   // "order_db.production.orders"
        String type,                   // TABLE / COLUMN
        String violatedRule,          // 违反的规范名
        String expected               // 规范要求
) {
}

public record TypeViolation(
        String path,                   // "order_db.production.orders.amount"
        String currentType,           // 当前类型
        String expectedType,          // 标准类型
        String typeStandardName       // 标准类型名称
) {
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
    fe-http-port: 8030
    be-http-port: 8040
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
│   ├── datasources/              # Sprint 1 已有（+ 预览按钮）
│   └── sync-tasks/               # 🆕 批量同步列表 + 创建/编辑抽屉
│       └── history/              # 🆕 某任务执行历史 + 详情/日志弹窗
├── governance/
│   ├── collect-tasks/            # Sprint 1 已有
│   ├── metadata/                 # Sprint 1 已有（+ 预览按钮）
│   └── standards/                # 🆕 数据标准
│       ├── naming/               #   命名规范 Tab + 新建弹窗
│       ├── field-types/          #   字段类型标准 Tab + 新建弹窗
│       └── compliance/           #   合规检查结果页
```

### 13.2 菜单联动

```ts
const menuConfig: Record<string, MenuItem[]> = {
    SUPER_ADMIN: [
        {key: 'home', label: '首页'},
        {key: 'datasources', label: '数据源', path: '/engineering/datasources'},
        {key: 'sync-tasks', label: '批量数据同步', path: '/engineering/sync-tasks'},      // 🆕
        {key: 'collect-tasks', label: '元数据采集任务', path: '/governance/collect-tasks'},
        {key: 'metadata', label: '元数据管理', path: '/governance/metadata'},
        {key: 'standards', label: '数据标准', path: '/governance/standards'},             // 🆕
        {key: 'system', label: '系统管理', children: [...]},
    ],
    DATA_ENGINEER: [
        {key: 'home', label: '首页'},
        {key: 'datasources', label: '数据源', path: '/engineering/datasources'},
        {key: 'sync-tasks', label: '批量数据同步', path: '/engineering/sync-tasks'},      // 🆕
        {key: 'metadata', label: '元数据管理', path: '/governance/metadata'},
    ],
    GOV_ADMIN: [
        {key: 'home', label: '首页'},
        {key: 'datasources', label: '数据源', path: '/engineering/datasources', readonly: true},
        {key: 'collect-tasks', label: '元数据采集任务', path: '/governance/collect-tasks'},
        {key: 'metadata', label: '元数据管理', path: '/governance/metadata'},
        {key: 'standards', label: '数据标准', path: '/governance/standards'},             // 🆕
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

| 项目         | 内容                                                                                                                                                                                                                |
|--------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **状态**     | Accepted                                                                                                                                                                                                            |
| **上下文**   | 需要将 MySQL/PostgreSQL/Doris 数据批量同步到内置 Doris。Addax 6.0.11 已有官方 Docker 镜像                                                                                                                           |
| **决策**     | **Dockerfile 基于 `wgzhao/addax:6.0.11` 构建 engineering 容器**，engineering-service 通过 `ProcessBuilder` 调 `addax.sh job.json`。不内嵌 Addax JAR（避免 classpath 冲突），不单独部署 Addax 容器（减少运维复杂度） |
| **替代方案** | Java 内嵌——classpath 冲突风险高；独立容器——多一个容器编排，日志收集复杂                                                                                                                                             |
| **后果**     | 📈 Addax 原生方式，DolphinScheduler 也是这样调；📈 升级 Addax 只需改 Dockerfile 的 `COPY --from` 源镜像 tag；📉 engineering 镜像较大（多一层 Addax ~200MB）；📉 进程管理需自己处理超时/僵尸进程                     |

### ADR-S2-002: metadata_table 区分内置 vs 外部

| 项目         | 内容                                                                                                                                |
|--------------|-------------------------------------------------------------------------------------------------------------------------------------|
| **状态**     | Accepted                                                                                                                            |
| **上下文**   | 内置 Doris 的表由同步任务写入后自动注册，外部数据源的表由采集任务注册。元数据管理页需要将内置 Doris 置顶展示                        |
| **决策**     | `metadata_table` 新增 `source_type` 字段（`BUILTIN_DORIS` / `EXTERNAL`），`datasource_id` 改为可空。内置 Doris 不创建虚拟数据源记录 |
| **替代方案** | 创建一条特殊的 `datasource_connection` 记录表示内置 Doris——污染数据源表，用户可能看到不该看的记录                                   |
| **后果**     | 📈 前端按 `source_type` 分组渲染干净；📈 查询和统计可区分来源；📉 历史数据需要补填 `source_type = 'EXTERNAL'`（迁移脚本处理）       |

### ADR-S2-003: 同步后元数据注册——直接写表

| 项目       | 内容                                                                                                                                                                    |
|------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **状态**   | Accepted                                                                                                                                                                |
| **上下文** | 同步成功后需要注册元数据到 `metadata_table` / `metadata_column`。engineering 和 governance 共用同一 PostgreSQL                                                          |
| **决策**   | **engineering-service 直接通过 MyBatis-Plus 写入元数据表**，不经过 governance HTTP 接口。与 Sprint 1 的模式一致（governance 直接读 `datasource_connection` 表）         |
| **后果**   | 📈 零 RPC 开销，同步链路短；📈 与 Sprint 1 的「直接查表」模式统一；📉 engineering 需要维护一份 `MetadataTableMapper` / `MetadataColumnMapper`（但遵循现有模式，成本低） |

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
| AC-16 | 权限隔离                        | 治理员看不到「批量数据同步」；工程师看不到「数据标准」        |

---

## 16. 风险与对策

| #  | 风险                                                 | 概率 | 影响 | 对策                                                                                                               |
|----|------------------------------------------------------|------|------|--------------------------------------------------------------------------------------------------------------------|
| R1 | Addax DorisWriter Stream Load 配置复杂               | 中   | 中   | 通过 `shared-addax.yaml` 统一管理 Stream Load 参数；提供默认配置，用户无需手动填                                   |
| R2 | 增量字段选错（如选了 varchar）导致性能差             | 中   | 中   | 前端下拉标记不推荐的字段类型（varchar/text）；后端执行前校验增量字段类型                                           |
| R3 | 同步中源表结构变更导致字段映射失效                   | 低   | 高   | 执行前校验源表当前字段与任务配置的映射是否一致；不一致时给出明确错误提示                                           |
| R4 | 千万级大表同步导致 Doris BE OOM                      | 低   | 高   | 单任务上限 1000 万行；超阈值提示用户分批（Sprint 3 优化 channel 并发控制）                                         |
| R5 | 正则表达式配置门槛高，治理员配错正则                 | 中   | 低   | 正则匹配方式旁提供常用示例（如 `^ods_.*$`）                                                                        |
| R6 | Addax 进程僵尸/超时不退出                            | 低   | 中   | `AddaxExecutor` 设超时（默认 3600s）+ `Process.destroyForcibly()`                                                  |
| R7 | engineering 和 governance 同时写 metadata_table 冲突 | 低   | 中   | 两个服务写的是不同的表（BUILTIN_DORIS vs EXTERNAL），不冲突；同一张表内用 `INSERT ON CONFLICT DO UPDATE`（upsert） |

---

## 附录 A：端口速查（Sprint 2 更新）

| 端口 | 服务                            | Sprint |
|------|---------------------------------|:------:|
| 3000 | frontend                        |   0    |
| 8080 | gateway-service                 |   0    |
| 8082 | engineering-service（含 Addax） |  1,2   |
| 8084 | governance-service              |  1,2   |
| 8088 | xxl-job-admin                   |   1    |
| 8087 | system-service                  |   0    |
| 8848 | Nacos                           |   0    |
| 5432 | PostgreSQL                      |   0    |
| 9030 | Doris JDBC                      |   0    |
| 8030 | Doris FE HTTP（Stream Load）    |   0    |

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
