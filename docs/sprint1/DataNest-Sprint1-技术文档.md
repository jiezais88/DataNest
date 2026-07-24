# DataNest Sprint 1 技术文档

> **Sprint**：Sprint 1 — 数据源连接 + 元数据采集与管理
> **文档状态**：Working Draft (v1.4) | **作者**：软件架构师 | **日期**：2026-07-24
> **关联文档**：`DataNest-产品规格文档-v2.0.md`、`DataNest-技术架构文档-v2.3.1.md`、`DataNest-Sprint1-数据源连接与元数据采集-PRD.md`

---

## 目录

1. [Sprint 概述](#1-sprint-概述)
2. [交付物清单](#2-交付物清单)
3. [项目结构变更](#3-项目结构变更)
4. [Docker Compose 变更](#4-docker-compose-变更)
5. [engineering-service：数据源管理](#5-engineering-service数据源管理)
6. [governance-service：元数据采集与管理](#6-governance-service元数据采集与管理)
7. [数据库设计](#7-数据库设计)
8. [API 接口设计](#8-api-接口设计)
9. [共享配置变更](#9-共享配置变更)
10. [前端设计](#10-前端设计)
11. [Sprint 1 ADR](#11-sprint-1-adr)
12. [验收标准](#12-验收标准)
13. [风险与对策](#13-风险与对策)

---

## 1. Sprint 概述

### 1.1 Sprint 目标

Sprint 0 建好了门和锁（用户体系），Sprint 1 让平台 **有数据可看、有任务可管**——数据工程师能接入外部数据源，治理管理员能把源库表结构采集成平台元数据。

### 1.2 Sprint 范围

| # | 工作项               | 所属服务            | 说明                                                               |
|---|----------------------|---------------------|--------------------------------------------------------------------|
| 1 | **XXL-JOB 调度中心** | Docker Compose      | 复用 Sprint 0 的 `nacos-mysql`，新增 `datanest_scheduler` 库       |
| 2 | **数据源连接管理**   | engineering-service | 添加、测试、编辑、删除 MySQL/PostgreSQL/Doris 数据源；密码加密存储 |
| 3 | **元数据采集任务**   | governance-service  | 创建、编辑、删除、手动执行、Cron 定时、全量+增量、历史记录+日志    |
| 4 | **元数据管理**       | governance-service  | 树形浏览（源→库→表→字段）、编辑表/字段注释                         |
| 5 | **权限控制生效**     | gateway + 前端      | 工程师管数据源、治理员管采集、分析师只看元数据                     |

### 1.3 新增 Maven 模块

| 模块                    | 端口 | 职责                        |
|-------------------------|------|-----------------------------|
| `data-nest-engineering` | 8082 | 数据源连接 CRUD + 连接测试  |
| `data-nest-governance`  | 8084 | 元数据采集任务 + 元数据管理 |

> Sprint 0 已有的 gateway (8080) 和 system (8087) 不变。

### 1.4 不在本 Sprint

| 暂缓项                   | 后续 Sprint  |
|--------------------------|:------------:|
| Addax 批量数据同步       |   Sprint 2   |
| CDC 实时采集             |   Sprint 9   |
| 数据标准、质量规则、血缘 | Sprint 2/5/6 |
| OpenSearch 全局搜索      |   Sprint 8   |
| 告警通知                 |   Sprint 2   |

---

## 2. 交付物清单

| #  | 交付物                                              | 类型 | 验收方式                              |
|----|-----------------------------------------------------|------|---------------------------------------|
| D1 | `data-nest-engineering/` 模块                       | 代码 | 数据源 CRUD + 测试连接可用            |
| D2 | `data-nest-governance/` 模块                        | 代码 | 采集任务 CRUD + 执行 + 元数据浏览     |
| D3 | Flyway 迁移脚本 V2.0.0 + V2.0.1                     | 代码 | 启动后自动建表                        |
| D4 | `docker-compose.yml` 新增 1 个服务（xxl-job-admin） | 配置 | `docker compose up -d` 7 容器 healthy |
| D5 | shared-configs 新增 `shared-encryption.yaml`        | 配置 | Nacos 可见                            |
| D6 | 前端：数据源/采集任务/元数据管理 3 个页面           | 代码 | 按 PRD 交互可用                       |

---

## 3. 项目结构变更

### 3.1 新增模块

```
data-nest/
├── pom.xml                              # <modules> 新增 2 个
│
├── data-nest-engineering/               # 🆕 数据工程
│   ├── pom.xml
│   └── src/main/java/com/datanest/engineering/
│       ├── EngineeringApplication.java
│       ├── controller/
│       │   └── DataSourceController.java
│       ├── service/
│       │   ├── DataSourceService.java
│       │   └── ConnectionTester.java   # 连接测试
│       ├── mapper/
│       │   └── DataSourceMapper.java
│       ├── entity/
│       │   └── DataSourceConnection.java
│       └── config/
│           └── EncryptionConfig.java   # AES 加解密
│
├── data-nest-governance/                # 🆕 数据治理
│   ├── pom.xml
│   └── src/main/java/com/datanest/governance/
│       ├── GovernanceApplication.java
│       ├── controller/
│       │   ├── CollectTaskController.java
│       │   ├── CollectHistoryController.java
│       │   └── MetadataController.java
│       ├── service/
│       │   ├── CollectTaskService.java
│       │   ├── CollectExecutor.java     # 采集执行引擎
│       │   ├── SchedulerService.java    # Cron 调度
│       │   ├── SchemaService.java       # 🆕 拉取数据源 Schema（Feign 调 engineering）
│       │   └── MetadataService.java
│       ├── mapper/                      # MyBatis-Plus Mapper
│       └── entity/                      # 对应 DB 表
│
└── （gateway / system / common / frontend 不变）
```

### 3.2 Root POM 变更

```xml

<modules>
    <module>data-nest-common</module>
    <module>data-nest-gateway</module>
    <module>data-nest-system</module>
    <module>data-nest-engineering</module>  <!-- 🆕 -->
    <module>data-nest-governance</module>   <!-- 🆕 -->
</modules>
```

---

## 4. Docker Compose 变更

Sprint 0 只有 gateway + system，Sprint 1 加 MySQL、XXL-JOB 调度中心、engineering + governance：

### 4.1 MySQL —— 复用 Sprint 0 的 `nacos-mysql` 🔄

Sprint 0 已为 Nacos 部署了 `nacos-mysql`（MySQL 8.0）。Sprint 1 不再新增 MySQL 容器，XXL-JOB 调度中心直接连同一个实例，但使用独立的数据库
`datanest_scheduler`。

初始化脚本放在 `./scripts/init-xxl-job-db.sql`：

```sql
-- scripts/init-xxl-job-db.sql
CREATE DATABASE IF NOT EXISTS datanest_scheduler DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

**Sprint 0 `docker-compose.yml` 需补充挂载**（在 `nacos-mysql` 的 `volumes` 中增加一行）：

```yaml
  nacos-mysql:
    volumes:
      - ./scripts/mysql-schema.sql:/docker-entrypoint-initdb.d/01-mysql-schema.sql:ro
      - ./scripts/init-xxl-job-db.sql:/docker-entrypoint-initdb.d/02-xxl-job-db.sql:ro
```

> 注：`docker-entrypoint-initdb.d` 只在 MySQL 数据目录首次初始化时执行。如果 Sprint 0 已经启动过，需手动进 `nacos-mysql` 执行
> `CREATE DATABASE`，或删除 `./data/mysql` 卷重新初始化。

### 4.2 XXL-JOB 调度中心 🆕

```yaml
  xxl-job-admin:
    image: xuxueli/xxl-job-admin:3.4.2
    container_name: datanest-xxl-job
    depends_on:
      nacos-mysql: { condition: service_healthy }
    environment:
      PARAMS: "--spring.datasource.url=jdbc:mysql://nacos-mysql:3306/datanest_scheduler?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai
               --spring.datasource.username=root
               --spring.datasource.password=root123
               --xxl.job.accessToken=${XXL_JOB_TOKEN:-datanest_xxl_token}"
    ports:
      - "8088:8080"
    volumes:
      - ./data/xxl-job:/data/applogs
    healthcheck:
      test: [ "CMD-SHELL", "curl -s -f http://localhost:8080/actuator/health >/dev/null 2>&1" ]
      interval: 15s
      timeout: 5s
      retries: 10
      start_period: 30s
```

### 4.4 engineering + governance

```yaml
  # ============ 🆕 engineering-service ============
  engineering:
    build:
      context: ./data-nest-engineering
      dockerfile: Dockerfile
    container_name: datanest-engineering
    depends_on:
      nacos: { condition: service_healthy }
      postgres: { condition: service_healthy }
    environment:
      NACOS_ADDR: nacos:8848
      PG_HOST: postgres
      PG_PORT: 5432
      PG_USER: datanest
      PG_PASSWORD: ${PG_PASSWORD:-datanest123}
    ports:
      - "8082:8082"
    healthcheck:
      test: [ "CMD", "curl", "-f", "http://localhost:8082/actuator/health" ]
      interval:
        15s; timeout:
          5s; retries: 10

  # ============ 🆕 governance-service ============
  governance:
    build:
      context: ./data-nest-governance
      dockerfile: Dockerfile
    container_name: datanest-governance
    depends_on:
      nacos: { condition: service_healthy }
      postgres: { condition: service_healthy }
    environment:
      NACOS_ADDR: nacos:8848
      PG_HOST: postgres
      PG_PORT: 5432
      PG_USER: datanest
      PG_PASSWORD: ${PG_PASSWORD:-datanest123}
    ports:
      - "8084:8084"
    healthcheck:
      test: [ "CMD", "curl", "-f", "http://localhost:8084/actuator/health" ]
      interval:
        15s; timeout:
          5s; retries: 10
```

**启动顺序**：nacos-mysql → nacos → postgres → xxl-job-admin → system → engineering → governance → gateway → frontend

### 4.1 Gateway 路由变更

```yaml
routes:
  - id: system-service
    uri: lb://system-service
    predicates: [ Path=/api/system/** ]
  - id: engineering-service         # 🆕
    uri: lb://engineering-service
    predicates: [ Path=/api/engineering/** ]
  - id: governance-service          # 🆕
    uri: lb://governance-service
    predicates: [ Path=/api/governance/** ]
```

---

## 5. engineering-service：数据源管理

### 5.1 职责

| 功能                         | 接口                                               | 鉴权                                    |
|------------------------------|----------------------------------------------------|-----------------------------------------|
| 数据源列表（搜索/筛选/分页） | `GET /api/engineering/datasources`                 | SUPER_ADMIN / DATA_ENGINEER / GOV_ADMIN |
| 新增数据源                   | `POST /api/engineering/datasources`                | SUPER_ADMIN / DATA_ENGINEER             |
| 编辑数据源                   | `PUT /api/engineering/datasources/{id}`            | SUPER_ADMIN / DATA_ENGINEER             |
| 删除数据源                   | `DELETE /api/engineering/datasources/{id}`         | SUPER_ADMIN / DATA_ENGINEER             |
| 测试连接                     | `POST /api/engineering/datasources/test`           | SUPER_ADMIN / DATA_ENGINEER / GOV_ADMIN |
| 检查引用关系                 | `GET /api/engineering/datasources/{id}/references` | 内部（删除前校验）                      |
| 拉取库/Schema 列表           | `GET /api/engineering/datasources/{id}/schemas`    | GOV_ADMIN（采集任务创建时用）           |

### 5.2 数据源实体

```java

@Data
@TableName("datasource_connection")
public class DataSourceConnection {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String name;             // 唯一，3-30 位字母数字下划线
    private String type;             // MYSQL / POSTGRESQL / DORIS
    private String host;             // IP 或域名
    private Integer port;
    private String databaseName;
    private String schemaName;       // PostgreSQL 必填，默认 public
    private String username;
    private String encryptedPassword; // AES-256-GCM 加密
    private String description;
    private String status;           // NORMAL / ERROR
    private String lastErrorMessage;
    private LocalDateTime lastTestedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

### 5.3 密码加密

```java
// 使用 AES-256-GCM，密钥从 Nacos shared-encryption.yaml 读取
@Component
public class EncryptionUtil {
    @Value("${datanest.encryption.secret-key}")
    private String secretKey;

    public String encrypt(String plainText) {
        // AES/GCM/NoPadding, 随机 IV 12 bytes
    }

    public String decrypt(String cipherText) {
        // 解密
    }

    public String mask(String cipherText) {
        return "••••••••";  // 界面脱敏
    }
}
```

### 5.4 连接测试

```java

@Service
public class ConnectionTester {

    public TestResult test(DataSourceConnection ds) {
        return switch (ds.getType()) {
            case "MYSQL", "DORIS" -> testJdbc(
                    "jdbc:mysql://" + ds.getHost() + ":" + ds.getPort() + "/" + ds.getDatabaseName(),
                    ds.getUsername(), decrypt(ds.getEncryptedPassword()));
            case "POSTGRESQL" -> testJdbc(
                    "jdbc:postgresql://" + ds.getHost() + ":" + ds.getPort() + "/" + ds.getDatabaseName(),
                    ds.getUsername(), decrypt(ds.getEncryptedPassword()));
            default -> TestResult.fail("不支持的数据源类型");
        };
    }

    private TestResult testJdbc(String url, String user, String password) {
        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            conn.isValid(10);  // 10 秒超时
            return TestResult.success();
        } catch (SQLException e) {
            return TestResult.fail(classifyError(e));  // 超时/认证失败/库不存在
        }
    }
}
```

### 5.5 删除前引用校验

```java
// DataSourceService.deleteDataSource()
public void deleteDataSource(Long id) {
    // 1. 查引用：governance 服务的采集任务是否引用此数据源
    List<CollectTask> refs = collectTaskMapper.selectByDataSourceId(id);
    if (!refs.isEmpty()) {
        throw new BusinessException(ErrorCode.HAS_REFERENCES,
                "该数据源已被 " + refs.size() + " 个采集任务引用：" +
                        refs.stream().map(CollectTask::getName).collect(joining(", ")));
    }
    // 2. 删除
    datasourceMapper.deleteById(id);
}
```

---

## 6. governance-service：元数据采集与管理

### 6.1 职责

| 功能         | 接口                                              | 鉴权                                    |
|--------------|---------------------------------------------------|-----------------------------------------|
| 采集任务列表 | `GET /api/governance/collect-tasks`               | SUPER_ADMIN / GOV_ADMIN                 |
| 创建任务     | `POST /api/governance/collect-tasks`              | SUPER_ADMIN / GOV_ADMIN                 |
| 编辑任务     | `PUT /api/governance/collect-tasks/{id}`          | SUPER_ADMIN / GOV_ADMIN                 |
| 删除任务     | `DELETE /api/governance/collect-tasks/{id}`       | SUPER_ADMIN / GOV_ADMIN                 |
| 执行任务     | `POST /api/governance/collect-tasks/{id}/execute` | SUPER_ADMIN / GOV_ADMIN                 |
| 任务历史     | `GET /api/governance/collect-tasks/{id}/history`  | SUPER_ADMIN / GOV_ADMIN / DATA_ENGINEER |
| 执行日志     | `GET /api/governance/collect-history/{id}/log`    | SUPER_ADMIN / GOV_ADMIN / DATA_ENGINEER |
| 元数据浏览   | `GET /api/governance/metadata/**`                 | 所有角色（分析师只读）                  |
| 编辑注释     | `PUT /api/governance/metadata/**`                 | SUPER_ADMIN / GOV_ADMIN                 |

### 6.2 采集执行引擎

```java

@Service
public class CollectExecutor {

    private final CollectHistoryMapper historyMapper;
    private final MetadataTableMapper tableMapper;
    private final MetadataColumnMapper columnMapper;

    /**
     * 异步执行采集任务。
     * 调用方（Controller）提交后立即返回，实际采集在独立线程中执行。
     */
    @Async
    public void execute(CollectTask task) {
        CollectHistory history = createHistory(task, "RUNNING");
        Logger logger = new InMemoryLogger(history.getId());  // 日志写入 DB

        try {
            DataSourceConnection ds = getDataSource(task.getDataSourceId());
            List<String> schemas = task.getTargetSchemas();

            for (String schema : schemas) {
                logger.info("开始采集 " + task.getDataSourceName() + "." + schema);
                SchemaResult result = collectSchema(ds, schema, task.getMode(), logger);
                history.addSchemaResult(result);
                logger.info("完成采集 " + schema + "：" + result.getTableCount() + " 表，" +
                        result.getColumnCount() + " 字段");
            }

            history.setStatus("SUCCESS");
            task.setLastExecutedAt(Instant.now());
            task.setStatus("NORMAL");
        } catch (Exception e) {
            logger.error("采集失败：" + e.getMessage());
            history.setStatus("FAILED");
            task.setStatus("FAILED");
        } finally {
            history.setEndedAt(Instant.now());
            historyMapper.updateById(history);
            taskMapper.updateById(task);
        }
    }

    /**
     * 采集单个 Schema 的元数据。
     * 首次全量采集所有表/字段；增量模式下从 information_schema 获取上次采集后变更的表。
     */
    private SchemaResult collectSchema(DataSourceConnection ds, String schema,
                                       String mode, Logger logger) {
        // 1. 获取表列表
        List<String> tables = listTables(ds, schema);

        int newTables = 0, deletedTables = 0, modifiedTables = 0;
        int newColumns = 0, deletedColumns = 0, modifiedColumns = 0;

        // 2. 对比增量
        if ("INCREMENTAL".equals(mode)) {
            Set<String> existingTables = tableMapper.selectNamesBySchema(ds.getId(), schema);
            newTables = countNew(tables, existingTables);
            deletedTables = countDeleted(tables, existingTables);
            // 只采集新增和变更的表
            tables = filterChangedTables(tables, existingTables);
        } else {
            newTables = tables.size();
        }

        // 3. 逐表采集字段
        for (String table : tables) {
            List<ColumnInfo> columns = listColumns(ds, schema, table);

            // 增量：只更新有变化的字段，保留人工注释
            if ("INCREMENTAL".equals(mode)) {
                upsertColumnsIncremental(ds.getId(), schema, table, columns);
            } else {
                upsertColumns(ds.getId(), schema, table, columns);
            }
        }

        return new SchemaResult(schema, tables.size(),
                newTables, deletedTables, modifiedTables,
                newColumns, deletedColumns, modifiedColumns);
    }
}
```

### 6.3 Cron 调度（XXL-JOB Executor）

governance-service 作为 XXL-JOB Executor 注册到调度中心。 **任务创建时即注册为 XXL-JOB 任务**：CRON 类型带 Cron
表达式自动调度，MANUAL 类型注册为 `ScheduleType.NONE`（不自动调度，仅用于手动触发）。编辑/删除时同步更新/注销。

```java

@Configuration
public class XxlJobConfig {
    @Value("${xxl.job.admin.addresses}")
    private String adminAddresses;
    @Value("${xxl.job.accessToken}")
    private String accessToken;
    @Value("${xxl.job.executor.appname}")
    private String appname;

    @Bean
    public XxlJobSpringExecutor xxlJobExecutor() {
        XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
        executor.setAdminAddresses(adminAddresses);
        executor.setAccessToken(accessToken);
        executor.setAppname(appname);
        executor.setLogPath("/data/applogs/xxl-job");
        return executor;
    }
}

@Component
public class CollectJobHandler {

    @XxlJob("collectTaskHandler")
    public void execute() {
        String taskId = XxlJobHelper.getJobParam();  // 从调度中心传入 taskId
        CollectTask task = taskMapper.selectById(Long.valueOf(taskId));
        collectExecutor.execute(task);
    }
}
```

```java
// SchedulerService — 调 XXL-JOB API，处理 cookie 失效
@Service
public class SchedulerService {
    private final XxlJobApi xxlJobApi;
    private volatile String cookie;

    /** 调 API 前确保已登录；cookie 失效时自动重登录 */
    private void ensureLogin() {
        if (cookie == null) cookie = xxlJobApi.login();
    }

    private <T> T withRetry(Supplier<T> fn) {
        ensureLogin();
        try {
            return fn.get();
        } catch (AuthException e) {
            cookie = xxlJobApi.login();  // 别处登录挤掉了 cookie，重新登录
            return fn.get();
        }
    }

    /** 创建任务时注册到 XXL-JOB（MANUAL 也注册，只是调度类型为 NONE） */
    public void register(CollectTask task) {
        JobInfo jobInfo = buildJobInfo(task);
        int jobId = withRetry(() -> xxlJobApi.addJob(cookie, jobInfo));
        task.setXxlJobId(jobId);
    }

    /** 编辑任务时同步更新 XXL-JOB */
    public void update(CollectTask task) {
        if (task.getXxlJobId() == null) {
            register(task);
            return;
        }
        JobInfo jobInfo = buildJobInfo(task);
        jobInfo.setId(task.getXxlJobId());
        withRetry(() -> {
            xxlJobApi.updateJob(cookie, jobInfo);
            return null;
        });
    }

    public void unregister(CollectTask task) {
        if (task.getXxlJobId() != null)
            withRetry(() -> {
                xxlJobApi.removeJob(cookie, task.getXxlJobId());
                return null;
            });
    }

    public void trigger(CollectTask task) {
        Long jobId = task.getXxlJobId();
        withRetry(() -> {
            xxlJobApi.triggerJob(cookie, jobId);
            return null;
        });
    }

    private JobInfo buildJobInfo(CollectTask task) {
        JobInfo info = new JobInfo();
        info.setJobDesc(task.getName());
        info.setAuthor("datanest");
        info.setGlueType("BEAN");
        info.setExecutorHandler("collectTaskHandler");
        info.setExecutorParam(String.valueOf(task.getId()));
        info.setExecutorTimeout(600);
        info.setExecutorFailRetryCount(2);

        if ("CRON".equals(task.getTriggerType())) {
            info.setScheduleType("CRON");
            info.setScheduleConf(task.getCronExpression());
        } else {
            info.setScheduleType("NONE");  // MANUAL：不自动调度，仅支持手动触发
        }
        return info;
    }
}
```

> ⚠️ **XXL-JOB cookie 坑**：调度中心使用 cookie 鉴权，如果在别处登录了 XXL-JOB Admin UI，当前进程持有的 cookie 会立即失效。
> `withRetry` 方法检测到鉴权失败后自动重新 login 一次再重试，保证 API 调用不会因为管理员操作 UI 而中断。

### 6.4 日志存储

采集日志写入 `collect_execution_log` 表（非文件），按历史记录 ID 关联。日志内容为纯文本。

```java
// InMemoryLogger 在采集过程中缓存日志行，
// 采集结束后批量写入 collect_execution_log 表
class InMemoryLogger {
    private final List<String> lines = new ArrayList<>();

    void info(String msg) {
        lines.add("[INFO] " + msg);
    }

    void error(String msg) {
        lines.add("[ERROR] " + msg);
    }

    void flush() { /* 批量 INSERT INTO collect_execution_log */ }
}
```

---

## 7. 数据库设计

### 7.1 Flyway 迁移

所有迁移脚本统一在 `data-nest-system/src/main/resources/db/migration/`（沿用 Sprint 0 的 system-service Flyway
集中管理）。engineering-service 和 governance-service 各自 POM 只依赖 MyBatis-Plus + PostgreSQL Driver，不包含 Flyway。

| 脚本                                      | 版本          | 内容                                                                                      |
|-------------------------------------------|---------------|-------------------------------------------------------------------------------------------|
| `V1.0.0__init_user_tables.sql`            | Sprint 0 已有 | sys_user / sys_role / sys_permission / sys_user_role / sys_role_permission                |
| `V1.0.1__seed_roles_and_admin.sql`        | Sprint 0 已有 | 预置角色+权限+admin 账号                                                                  |
| `V2.0.0__create_datasource_tables.sql` 🆕 | Sprint 1      | datasource_connection                                                                     |
| `V2.0.1__create_governance_tables.sql` 🆕 | Sprint 1      | collect_task / collect_history / collect_execution_log / metadata_table / metadata_column |

### 7.2 datasource_connection

```sql
CREATE TABLE IF NOT EXISTS datasource_connection
(
    id                 BIGINT PRIMARY KEY,
    name               VARCHAR(30)  NOT NULL,
    type               VARCHAR(20)  NOT NULL,                  -- MYSQL / POSTGRESQL / DORIS
    host               VARCHAR(255) NOT NULL,
    port               INTEGER      NOT NULL,
    database_name      VARCHAR(100) NOT NULL,
    schema_name        VARCHAR(100)          DEFAULT 'public',
    username           VARCHAR(100) NOT NULL,
    encrypted_password VARCHAR(500) NOT NULL,                  -- AES-256-GCM 密文
    description        VARCHAR(200),
    status             VARCHAR(20)           DEFAULT 'NORMAL', -- NORMAL / ERROR
    last_error_message TEXT,
    last_tested_at     TIMESTAMP,
    created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_ds_name UNIQUE (name)
);
COMMENT ON TABLE datasource_connection IS '数据源连接';
```

### 7.3 元数据采集相关表

```sql
-- ===== 采集任务 =====
CREATE TABLE IF NOT EXISTS collect_task
(
    id               BIGINT PRIMARY KEY,
    name             VARCHAR(50) NOT NULL,
    datasource_id    BIGINT      NOT NULL,                   -- 关联 datasource_connection.id
    target_schemas   JSONB       NOT NULL,                   -- ["production", "users_db"]
    collect_mode     VARCHAR(20) NOT NULL,                   -- FULL / INCREMENTAL
    trigger_type     VARCHAR(10) NOT NULL,                   -- MANUAL / CRON
    cron_expression  VARCHAR(100),
    description      VARCHAR(200),
    status           VARCHAR(20)          DEFAULT 'PENDING', -- PENDING / RUNNING / NORMAL / FAILED
    last_executed_at TIMESTAMP,
    created_at       TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_task_name UNIQUE (name)
);

-- ===== 采集历史 =====
CREATE TABLE IF NOT EXISTS collect_history
(
    id               BIGINT PRIMARY KEY,
    task_id          BIGINT      NOT NULL,
    trigger_type     VARCHAR(10) NOT NULL, -- MANUAL / CRON
    status           VARCHAR(20) NOT NULL, -- RUNNING / SUCCESS / FAILED
    started_at       TIMESTAMP   NOT NULL,
    ended_at         TIMESTAMP,
    duration_seconds INTEGER,
    schema_results   JSONB,                -- 按 Schema 统计
    change_stats     JSONB,                -- 变更统计
    created_at       TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_history_task ON collect_history (task_id);

-- ===== 采集日志 =====
CREATE TABLE IF NOT EXISTS collect_execution_log
(
    id         BIGSERIAL PRIMARY KEY,
    history_id BIGINT      NOT NULL,
    seq        INTEGER     NOT NULL, -- 行号
    level      VARCHAR(10) NOT NULL, -- INFO / ERROR
    message    TEXT        NOT NULL,
    logged_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_log_history ON collect_execution_log (history_id);

-- ===== 元数据-表 =====
CREATE TABLE IF NOT EXISTS metadata_table
(
    id             BIGINT PRIMARY KEY,
    datasource_id  BIGINT       NOT NULL,
    schema_name    VARCHAR(100) NOT NULL,
    table_name     VARCHAR(200) NOT NULL,
    comment        VARCHAR(500),                        -- 人工补充注释
    column_count   INTEGER,
    collected_at   TIMESTAMP,                           -- 最近采集时间
    source_task    VARCHAR(50),                         -- 采集任务名
    source_offline BOOLEAN               DEFAULT FALSE, -- 数据源已下线
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_table UNIQUE (datasource_id, schema_name, table_name)
);

-- ===== 元数据-字段 =====
CREATE TABLE IF NOT EXISTS metadata_column
(
    id             BIGINT PRIMARY KEY,
    table_id       BIGINT       NOT NULL,
    column_name    VARCHAR(200) NOT NULL,
    data_type      VARCHAR(100) NOT NULL,
    nullable       BOOLEAN,
    is_primary_key BOOLEAN,
    ordinal        INTEGER,      -- 字段序号
    comment        VARCHAR(500), -- 人工补充注释
    remark         VARCHAR(500), -- 备注（业务口径等）
    source_comment VARCHAR(500), -- 源库原始注释
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_column UNIQUE (table_id, column_name)
);
CREATE INDEX idx_column_table ON metadata_column (table_id);
```

---

## 8. API 接口设计

### 8.1 数据源接口

| 方法   | 路径                                        | 请求参数                                    | 响应                        |
|--------|---------------------------------------------|---------------------------------------------|-----------------------------|
| GET    | `/api/engineering/datasources`              | `keyword`, `type`, `status`, `page`, `size` | `PageResult<DataSourceDTO>` |
| GET    | `/api/engineering/datasources/{id}`         | —                                           | `DataSourceDTO`             |
| POST   | `/api/engineering/datasources`              | `DataSourceCreateRequest`                   | `DataSourceDTO`             |
| PUT    | `/api/engineering/datasources/{id}`         | `DataSourceUpdateRequest`                   | `DataSourceDTO`             |
| DELETE | `/api/engineering/datasources/{id}`         | —                                           | —                           |
| POST   | `/api/engineering/datasources/test`         | `TestConnectionRequest`                     | `TestResult`                |
| GET    | `/api/engineering/datasources/{id}/schemas` | —                                           | `List<String>`              |

```java
// DataSourceCreateRequest
public record DataSourceCreateRequest(
                @NotBlank String name, @NotBlank String type,
                @NotBlank String host, @NotNull Integer port,
                @NotBlank String databaseName, String schemaName,
                @NotBlank String username, @NotBlank String password,
                String description
        ) {
}

// TestConnectionRequest — 测试连接不需要先保存数据源
public record TestConnectionRequest(
        String type, String host, Integer port,
        String databaseName, String schemaName,
        String username, String password
) {
}

// DataSourceDTO — 列表和详情返回，密码字段不返回真实值
public record DataSourceDTO(
        Long id, String name, String type, String host, Integer port,
        String databaseName, String schemaName, String username,
        String passwordMasked,       // "••••••••"
        String status, String lastErrorMessage,
        LocalDateTime lastTestedAt, LocalDateTime createdAt
) {
}
```

### 8.2 采集任务接口

| 方法   | 路径                                                   | 说明                                               |
|--------|--------------------------------------------------------|----------------------------------------------------|
| GET    | `/api/governance/collect-tasks`                        | 列表（搜索 `keyword` + 状态 `status` 筛选 + 分页） |
| GET    | `/api/governance/collect-tasks/{id}`                   | 详情                                               |
| POST   | `/api/governance/collect-tasks`                        | 创建                                               |
| PUT    | `/api/governance/collect-tasks/{id}`                   | 编辑                                               |
| DELETE | `/api/governance/collect-tasks/{id}`                   | 删除                                               |
| POST   | `/api/governance/collect-tasks/{id}/execute`           | 手动执行                                           |
| GET    | `/api/governance/collect-tasks/{id}/history`           | 历史记录列表（分页）                               |
| GET    | `/api/governance/collect-tasks/{id}/history/{hid}`     | 某次执行详情                                       |
| GET    | `/api/governance/collect-tasks/{id}/history/{hid}/log` | 某次执行日志                                       |

```java
// CollectTaskCreateRequest
public record CollectTaskCreateRequest(
                @NotBlank String name,
                @NotNull Long datasourceId,
                @NotEmpty List<String> targetSchemas,    // 库/Schema 列表
                @NotBlank String collectMode,            // FULL / INCREMENTAL
                @NotBlank String triggerType,            // MANUAL / CRON
                String cronExpression,                   // 定时触发时必填
                String description,
                boolean executeImmediately               // 保存后是否立即执行
        ) {
}
```

**手动执行也提交到 XXL-JOB**（不是本地直接执行）：

```java

@RestController
@RequestMapping("/api/governance/collect-tasks")
public class CollectTaskController {

    private final CollectTaskService taskService;
    private final SchedulerService schedulerService;

    @PostMapping("/{id}/execute")
    @SaCheckRole("GOVERNANCE_ADMIN")
    public Result<Void> execute(@PathVariable Long id) {
        CollectTask task = taskService.getById(id);
        if (task == null) throw new BusinessException(ErrorCode.TASK_NOT_FOUND);
        // 统一走 XXL-JOB 调度：手动触发 / Cron 自动触发 都经过调度中心
        schedulerService.trigger(task);
        return Result.ok();
    }
}
```

> 手动执行和 Cron 执行都经过 XXL-JOB 调度中心，由 `CollectJobHandler` 消费，再调 `CollectExecutor.execute(task)`
> 实际采集。这样执行日志、重试、超时、调度审计都在 XXL-JOB 里统一可见。

**任务创建/编辑/删除时同步维护 XXL-JOB 注册**：

```java

@Service
public class CollectTaskService {

    private final CollectTaskMapper taskMapper;
    private final SchedulerService schedulerService;

    @Transactional
    public CollectTask create(CollectTaskCreateRequest request) {
        CollectTask task = convert(request);
        taskMapper.insert(task);
        schedulerService.register(task);   // 创建即注册到 XXL-JOB
        return task;
    }

    @Transactional
    public void update(Long id, CollectTaskUpdateRequest request) {
        CollectTask task = taskMapper.selectById(id);
        if (task == null) throw new BusinessException(ErrorCode.TASK_NOT_FOUND);
        applyUpdate(task, request);
        taskMapper.updateById(task);
        schedulerService.update(task);     // 同步更新 XXL-JOB（Cron 变化等）
    }

    @Transactional
    public void delete(Long id) {
        CollectTask task = taskMapper.selectById(id);
        if (task == null) return;
        schedulerService.unregister(task); // 先从 XXL-JOB 注销
        taskMapper.deleteById(id);
    }
}
```

### 8.3 元数据管理接口

| 方法 | 路径                                               | 说明                             |
|------|----------------------------------------------------|----------------------------------|
| GET  | `/api/governance/metadata/datasources`             | 有元数据的数据源列表（供左侧树） |
| GET  | `/api/governance/metadata/{dsId}/schemas`          | 某数据源下的 Schema 列表         |
| GET  | `/api/governance/metadata/{dsId}/{schema}/tables`  | 某 Schema 下的表列表             |
| GET  | `/api/governance/metadata/{dsId}/{schema}/{table}` | 表详情（含字段列表）             |
| PUT  | `/api/governance/metadata/tables/{id}/comment`     | 编辑表注释                       |
| PUT  | `/api/governance/metadata/columns/{id}`            | 编辑字段注释+备注                |

---

## 9. 共享配置变更

新增 `shared-encryption.yaml` + `shared-xxljob.yaml`：

```yaml
# shared-encryption.yaml
datanest:
  encryption:
    secret-key: ${ENCRYPTION_KEY:DataNestAESKey2026!}
    algorithm: AES/GCM/NoPadding

# shared-xxljob.yaml 🆕
xxl:
  job:
    admin:
      addresses: http://${XXL_JOB_HOST:localhost}:8088        # v3.4.1+ 不需要 /xxl-job-admin context-path
    accessToken: ${XXL_JOB_TOKEN:datanest_xxl_token}
    executor:
      appname: datanest-governance-executor
      port: 9999
      logpath: /data/applogs/xxl-job
```

engineering-service 和 governance-service 都需要引入。

---

## 10. 前端设计

### 10.1 新增页面

```
src/pages/
├── engineering/
│   └── datasources/         # 数据源列表 + 新增/编辑抽屉
├── governance/
│   ├── collect-tasks/       # 采集任务列表 + 创建/编辑抽屉
│   │   └── history/         # 某任务执行历史 + 详情/日志弹窗
│   └── metadata/            # 元数据管理（左右分栏 + 树形浏览 + 内联编辑注释）
```

### 10.2 菜单联动

PRD 要求不同角色看到不同菜单。菜单配置更新如下：

```ts
const menuConfig: Record<string, MenuItem[]> = {
    SUPER_ADMIN: [
        {key: 'home', label: '首页'},
        {key: 'datasources', label: '数据源', path: '/engineering/datasources'},
        {key: 'collect-tasks', label: '元数据采集任务', path: '/governance/collect-tasks'},
        {key: 'metadata', label: '元数据管理', path: '/governance/metadata'},
        {key: 'system', label: '系统管理', children: [...]},
    ],
    DATA_ENGINEER: [
        {key: 'home', label: '首页'},
        {key: 'datasources', label: '数据源', path: '/engineering/datasources'},
        {key: 'metadata', label: '元数据管理', path: '/governance/metadata'},
    ],
    GOV_ADMIN: [
        {key: 'home', label: '首页'},
        {key: 'datasources', label: '数据源', path: '/engineering/datasources', readonly: true},
        {key: 'collect-tasks', label: '元数据采集任务', path: '/governance/collect-tasks'},
        {key: 'metadata', label: '元数据管理', path: '/governance/metadata'},
    ],
    DATA_ANALYST: [
        {key: 'home', label: '首页'},
        {key: 'metadata', label: '元数据管理', path: '/governance/metadata', readonly: true},
    ],
};
```

### 10.3 空状态处理

三个列表页均需处理空状态：

- 数据源列表空状态 → 引导"点击右上角新增数据源"
- 采集任务空状态 → 引导"点击右上角创建任务"
- 元数据管理空状态 → 引导"前往元数据采集任务"，带跳转按钮

---

## 11. Sprint 1 ADR

### ADR-S1-001: 分布式调度——XXL-JOB

| 项目         | 内容                                                                                                                                                            |
|--------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **状态**     | Accepted                                                                                                                                                        |
| **上下文**   | 元数据采集需要 Cron 定时 + 手动触发。Spring TaskScheduler 单机无 HA、重启丢任务。后续 DolphinScheduler 要到 Sprint 3 才上                                       |
| **决策**     | **XXL-JOB 3.4.2**，governance-service 注册为 Executor。调度中心通过 Docker Compose 独立部署                                                                     |
| **替代方案** | DolphinScheduler——功能完整但 4 个容器过重，Sprint 1 只需要 Cron+手动；Spring TaskScheduler——轻但无分布式能力                                                    |
| **后果**     | 📈 分布式 HA、自带重试+超时+日志+Web UI；📈 后续 Sprint 3 引入 DolphinScheduler 后两者可共存（DS 管 DAG 编排、XXL-JOB 管轻量定时采集）；📉 多维护一个中间件容器 |

### ADR-S1-002: 扩展点预留——连接测试器 vs 统一抽象

| 项目       | 内容                                                                                                                                     |
|------------|------------------------------------------------------------------------------------------------------------------------------------------|
| **状态**   | Accepted                                                                                                                                 |
| **上下文** | Sprint 1 支持 MySQL/PG/Doris 三种数据源，后续会扩展到 Oracle/SQL Server/MongoDB 等。是否需要统一的 `DataSourceConnector` 接口？          |
| **决策**   | **先用 switch-case + JDBC 直连**。三种数据源都走 MySQL 协议或 PG 协议，本质差异小。后续扩展到非 JDBC 数据源（MongoDB/Kafka）时再抽象接口 |
| **后果**   | 📈 代码简单直白；📉 后续扩展非关系型数据源时需重构连接测试和元数据采集逻辑                                                               |

### ADR-S1-003: 密码加密——AES-256-GCM

| 项目       | 内容                                                                                                        |
|------------|-------------------------------------------------------------------------------------------------------------|
| **状态**   | Accepted                                                                                                    |
| **上下文** | 数据源密码不能明文存储                                                                                      |
| **决策**   | **AES-256-GCM**，密钥从 Nacos `shared-encryption.yaml` 读取，engineering-service 和 governance-service 共享 |
| **后果**   | 📈 行业标准加密，GCM 提供认证加密防篡改；📉 密钥泄露则所有密码可破，需保护 Nacos 访问权限                   |

### ADR-S1-004: 增量采集策略——表名+字段名对比

| 项目       | 内容                                                                                                                        |
|------------|-----------------------------------------------------------------------------------------------------------------------------|
| **状态**   | Accepted                                                                                                                    |
| **上下文** | 增量采集需要判断哪些表/字段在上次采集后发生了变化                                                                           |
| **决策**   | **首次 Sprint 1 简化：按表名+字段名对比**，忽略字段顺序、类型变化。对比 `metadata_table` 和当前 `information_schema` 的差异 |
| **后果**   | 📈 实现简单，开发量小；📉 字段类型变更（如 varchar(64)→varchar(128)）不会触发重新采集，Sprint 5 可增强                      |

---

## 12. 验收标准

| #  | 验收项                                       | 验证                                 |
|----|----------------------------------------------|--------------------------------------|
| ✅ | 添加 MySQL 数据源并测试连接                  | 连接成功，保存后列表出现             |
| ✅ | 添加 PG/Doris 数据源并测试连接               | 同上                                 |
| ✅ | 填错密码提示具体错误原因                     | 前端红色提示"用户名或密码错误"       |
| ✅ | 数据库中密码为密文                           | SQL 查询 `encrypted_password` 非明文 |
| ✅ | 删除被引用的数据源被阻止                     | 弹窗列出引用任务                     |
| ✅ | 创建采集任务，选择数据源后拉取库/Schema 列表 | 下拉框加载成功                       |
| ✅ | 全量采集执行后元数据管理出现表结构           | 树形浏览可用                         |
| ✅ | 增量采集不覆盖人工注释                       | 编辑注释后重新采集，注释保留         |
| ✅ | Cron 定时任务按时自动执行                    | 配置 Cron，等待触发后历史记录新增    |
| ✅ | 任务失败后日志显示 ERROR 行                  | 历史记录 → 日志弹窗                  |
| ✅ | 治理员编辑表/字段注释后可保存                | 刷新不丢失                           |
| ✅ | 数据分析师看不到数据源和采集任务菜单         | 用分析师账号登录验证                 |
| ✅ | 3 个空状态页面引导文案正确                   | 无数据时显示引导                     |

---

## 13. 风险与对策

| #  | 风险                                           | 概率 | 影响 | 对策                                                                          |
|----|------------------------------------------------|------|------|-------------------------------------------------------------------------------|
| R1 | MySQL/PG/Doris `information_schema` 查询差异大 | 低   | 中   | 都是标准 SQL，差异主要在 Schema 概念（MySQL 无、PG 有），已按类型分支处理     |
| R2 | 采集任务执行耗时导致 HTTP 超时                 | 中   | 中   | 采用 `@Async` 异步执行，接口提交后立即返回，前端轮询状态                      |
| R3 | 增量采集对比逻辑 Bug 导致元数据丢失            | 中   | 高   | Sprint 1 增量策略简单（表名+字段名对比），充分测试；保留全量采集选项兜底      |
| R4 | AES 密钥管理不当                               | 低   | 高   | 密钥放 Nacos，`shared-encryption.yaml` 仅管理员可见；生产环境通过环境变量注入 |

---

## 附录 A：端口速查（Sprint 1 更新）

| 端口 | 服务                                       | Sprint |
|------|--------------------------------------------|:------:|
| 3000 | frontend                                   |   0    |
| 8080 | gateway-service                            |   0    |
| 8082 | **engineering-service** 🆕                 |   1    |
| 8084 | **governance-service** 🆕                  |   1    |
| 8088 | **xxl-job-admin** 🆕                       |   1    |
| 3306 | nacos-mysql（Sprint 0 已有，XXL-JOB 复用） |   0    |
| 8087 | system-service                             |   0    |
| 8848 | Nacos                                      |   0    |
| 5432 | PostgreSQL                                 |   0    |
| 9030 | Doris JDBC                                 |   0    |

## 附录 B：修订记录

| 版本 | 日期                   | 修订内容                                                                                                     | 作者       |
|------|------------------------|--------------------------------------------------------------------------------------------------------------|------------|
| v1.1 | 2026-07-24             | 交互确认：数据源+采集分两服务；Flyway 集中 system-service；API 路径 /api/engineering/** + /api/governance/** | 软件架构师 |
| v1.3 | 2026-07-24             | XXL-JOB 升级 3.4.2；SchedulerService 加 cookie 失效自动重登录                                                | 软件架构师 |
| v1.4 | 2026-07-24             | XXL-JOB 复用 `nacos-mysql`；任务创建时即注册到 XXL-JOB（MANUAL 为 NONE）；手动/Cron 统一走调度中心           | 软件架构师 |
| ✅   | 增量采集不覆盖人工注释 | 编辑注释后重新采集，注释保留                                                                                 |
| ✅   | Cron                   