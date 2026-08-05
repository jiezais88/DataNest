# DataNest 架构明细

> 本文件是 AGENTS.md 的详细版，供按需查阅。核心摘要见 AGENTS.md §1。

## 1. 模块依赖

> **task-core 拆分（2026-08-05 三步重构）**：原 `data-nest-task-core` 按依赖分层拆为 **4 个模块**，**包名 `com.datanest.task.core.*` 全部保持不变**（零 import 改动）。依赖链：`common ← task-core-entity ← alert ← task-core-governance ← task-core`。所有消费方服务（engineering/governance/worker/job/system）**只显式声明依赖 `data-nest-task-core`**，新模块经 task-core 传递获得，各消费方 pom 无需改动。

| 模块 | 说明 |
|------|------|
| `data-nest-common` | 公共组件（SchedulerClient 等），最底层底座 |
| `data-nest-task-core-entity` | 共享实体/mapper/constant/dto 底座：entity(35)/mapper(36)/dto(35)/constant(2) + `SysUserService`（横切通用服务）。被 alert/governance/task-core 及所有消费方共用 |
| `data-nest-alert` | 告警域：`AlertFiringService`/`AlertRuleService`/`DagAlertService`/`MailService`/`DagAlertExecutionListener`/`DagExecutionFinishedListener` + 接口 `QualityAutoTriggerPort`（解耦 alert↔governance） |
| `data-nest-task-core-governance` | 治理编排服务：`QualityRuleService`/`QualityJobService`/`QualityScoreService`/`QualityRuleTemplateService`/`QualityCheckTriggerService`/`QualityAutoTriggerService`/`DataPreviewService`/`DagTopologyService`/`DataSourceRefreshService`/`ConnectionTester`/`RuleSqlGenerator`（后者实现 `QualityAutoTriggerPort`） |
| `data-nest-task-core` | 纯执行内核（21 个 service + collect/config/job 包）：`SyncJobExecutorService`/`CollectExecutor`/`GenericSqlExecutor`/`QualityCheckService`/`ScoreCalculator` 等。保留 `MybatisPlusInterceptorAutoConfiguration` |
| `data-nest-engineering` | 数据工程服务（同步任务 API、DAG API） |
| `data-nest-worker` | Addax 实际执行方（质量检查执行 handler 也在 worker） |
| `data-nest-governance` | 数据治理服务（元数据采集、元数据管理、数据标准、质量/评分 Controller） |
| `data-nest-job` | XXL-JOB executor，平台定时任务 |
| `data-nest-system` | 认证、用户、权限 |
| `data-nest-gateway` | 网关入口 |

> **依赖方向规则**：`alert` 不能依赖 `task-core-governance`（会成环）。告警侧需要调用治理域自动触发时，通过 `alert` 内定义的接口 `QualityAutoTriggerPort`（含常量 `OBJECT_TYPE_DAG_NODE/SYNC_JOB/COLLECT_TASK`），由 `task-core-governance` 的 `QualityAutoTriggerService implements` 实现。

## 2. 核心容器

| 容器 | 说明 |
|------|------|
| `app-engineering` | 数据工程服务 |
| `app-worker` | 同步/采集任务执行 |
| `app-governance` | 数据治理服务 |
| `app-job` | XXL-JOB executor |
| `app-system` | 系统服务 |
| `app-gateway` | 网关 |
| `middleware-mysql` | MySQL：Nacos、XXL-JOB、DolphinScheduler、业务库 |
| `middleware-postgres` | PostgreSQL：业务主库 |
| `middleware-nacos` | Nacos 服务 |
| `middleware-xxljob` | XXL-JOB Admin |
| `middleware-redis` | Redis |

## 3. 后端包结构

每个业务模块（`engineering`/`governance`/`system`/`job`）统一按以下结构组织：

```
com.datanest.<模块>
├── <模块>Application.java        # @SpringBootApplication + @MapperScan
├── config/                      # MybatisPlusConfig 等模块级配置
├── controller/                  # REST API 入口
├── dto/                         # Request / Response / Query DTO
├── service/                     # 业务逻辑
├── entity/                      # MyBatis-Plus 实体（共享实体放在 task-core-entity）
└── mapper/                      # Mapper 接口（共享 Mapper 放在 task-core-entity）
```

实际代码中包结构保持 **扁平按层划分**：`controller`/`service`/`dto`/`config` 直接挂在 `com.datanest.<模块>` 下， 不要引入 `dag/`、`dev/`、`sync/` 等子包，否则会影响 MyBatis Mapper 扫描和依赖方引用。共享的 `entity`、`mapper`、`dto`、`constant` 集中在 `data-nest-task-core-entity` 的同名包中（`com.datanest.task.core.*`，包名未随模块拆分改变）；共享 `service` 按域分散在 `data-nest-alert`（告警域）、`data-nest-task-core-governance`（治理编排域）、`data-nest-task-core`（执行内核域）的 `com.datanest.task.core.service` 中。

`data-nest-common` 只放跨服务共享内容：

```
com.datanest.common
├── config/GlobalExceptionHandler.java   # 统一异常处理
├── dto/                                 # 少量公共 DTO
├── exception/                           # BusinessException、ErrorCode
├── jackson/JacksonConfig.java           # Long 转 String 序列化
├── model/                               # Result、PageResult、LoginRequest
├── satoken/                             # Sa-Token 公共自动配置
└── util/                                # 公共工具类
```

## 4. task-core 共享模块依赖关系

原 `data-nest-task-core` 拆为 4 个模块，**包名 `com.datanest.task.core.*` 全部不变**（import 零改动）。依赖链（自底向上）：

```
data-nest-common
  └─ data-nest-task-core-entity     # entity/mapper/constant/dto 底座 + SysUserService
       └─ data-nest-alert           # 告警服务 + 接口 QualityAutoTriggerPort
            └─ data-nest-task-core-governance   # 治理编排服务（实现 QualityAutoTriggerPort）
                 └─ data-nest-task-core         # 纯执行内核 + MybatisPlusInterceptorAutoConfiguration
```

- **消费方**（engineering/governance/worker/job/system）只显式依赖 `data-nest-task-core`，新模块经依赖传递获得。
- **只要改到任一拆分模块，必须同时重新编译并部署相关消费方**（quality 执行改在 worker、告警发信在 worker/job/governance、接口在 governance），至少 engineering + worker。
- 各模块 `service` 在 `com.datanest.task.core.service` 下按域分布：alert=告警域、task-core-governance=治理编排域、task-core=执行内核域。`entity/mapper` 都在 `task-core-entity`。
- `entity`、`mapper` 会被多个服务共同扫描，注意 Bean 冲突和事务边界（同前）。
- **防环**：`alert` 与 `task-core-governance` 之间用接口 `QualityAutoTriggerPort` 解耦，禁止 alert 反向依赖 governance 服务类。
