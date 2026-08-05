# Sprint 6：数据质量管理——技术设计文档

> **版本**：v1.0 | **日期**：2026-08-04 | **作者**：后端
> **关联**：`DataNest-Sprint6-PRD.md`（v1.1，本技术文档落地后已同步修订任务统一调度）
> **技术决策**：本 Sprint 通过 4 轮沟通确认了 6 个关键技术决策（T1~T4、D1、D3），见 §1 决策记录。

---

## 0. 技术目标与范围

Sprint 6 让治理管理员为元数据表配置质量规则、在真实数据源上执行校验、按通过/警告/严重分级并邮件告警，产出表级质量评分并联动血缘展示，同时自动扫描不符合数据标准的表和字段。

本技术文档覆盖 **P0** 三大模块 + 两项联动：

1. 质量任务与规则模板配置
2. 质量检查执行与结果（手动/定时/任务完成三时机）
3. 分级邮件告警（复用 alert_rule 体系，扩展 object_type=QUALITY）
4. 表级质量评分 + 血缘联动
5. 标准合规检查（忽略/取消忽略/自动扫描）

质量报告（P1，DG-07）本期只做接口预留，不实现。

---

## 1. 关键技术决策记录（ADR）

> 本节记录本 Sprint 与用户确认的技术决策。后续实现必须严格遵循，如需变更需重新确认。

### D-T1：质量检查核心逻辑模块归属 → 下沉 task-core

质量检查（规则执行、判定、评分、告警触发）需要被三处消费：

- **定时调度**：在 `data-nest-job`（新增 handler）
- **任务完成自动触发**：在 `data-nest-worker`（DAG/同步任务终态回调）
- **Controller 入口**：在 `data-nest-governance`

而校验引擎 `GenericSqlExecutor` 已在 task-core。因此 **所有质量核心 Service、Entity、Mapper 下沉 `data-nest-task-core`**（同
`AlertRuleService` 模式），governance 只提供 Controller，job/worker 直接复用。

- task-core 新增包：`com.datanest.task.core.quality`（entity / mapper / service 平铺）
- governance 新增 Controller：`QualityJobController`、`QualityRuleController`、`QualityResultController`、
  `QualityScoreController`、`QualityTemplateController`

### D-T2：告警「合并成一条邮件」→ 引入检查批次 batchId

PRD 要求「同一次检查多条异常合并成一条邮件」。现有 `AlertFiringService.fire()` 是单对象单条发送，无法表达「一次检查的多条异常」。

**引入 `quality_check_batch`（检查批次）概念**：

- 一次「质量任务执行」= 一个 `batchId`（UUID）。
- 该批次下所有达到告警等级的规则异常，统一收集。
- 批次执行完成后，调用新增的 **批量合并告警入口**
  `AlertFiringService.fireBatch(objectType, objectId, batchId, List<AlertItem>)`，生成 **一条邮件**（正文逐条列出）+ **多条
  `alert_history` 记录**（每异常一条，便于审计）。

> 单条严重告警仍复用既有 60 秒防重（PRD NAC-5）；批次的告警合并与防重机制见 §5.4。

### D-T3：表级质量评分 → 落 `quality_score` 表

血缘图谱/元数据详情页要批量展示评分，实时 join 检查历史在 N 个节点时慢。 **每次检查后按「表」维度更新 `quality_score` 表**
，血缘节点批量查该表。

- 一张表只保留 **一行**最新评分（含最近检查时间、通过/警告/严重规则数）。
- 血缘图谱构建 `LineageGraphDTO` 后，用表名集合批量查 `quality_score` 回填节点 `qualityScore`，避免 N+1。

### D-T4：定时调度驱动 → 单 handler 每分钟扫描任务 cron 匹配

PRD 原「每条规则独立 Cron」已在三层模型下 **废除**（见 D1）。现在为「 **每个质量任务统一 cron**，任务下规则跟随」。

`JobRegistrar` 是启动时静态注册固定 handler+cron 的平台任务，无法为动态的任务 cron 逐个注册。采用 **单 handler 扫描**：

- 新增 `QualityCheckHandler`（`@XxlJob("qualityCheckHandler")`，cron `0 * * * * ?`，每分钟）。
- handler 扫描所有「启用 + 开了定时调度」的质量任务，用任务自己的 cron 表达式匹配 **当前分钟**是否命中，命中则执行该任务（跑其下所有规则）。
- cron 匹配用 Spring 自带 `org.springframework.scheduling.support.CronExpression`（Spring Framework 7 自带，无需新依赖）。

> 精度受限于 1 分钟，对质量检查足够（PRD NAC-2：到期 1 分钟内触发）。

### D-D1：任务调度方式 → 手动为默认能力 + 可选配定时/自动

三个时机组织方式确认： **手动是默认能力（随时可执行），可选配定时 cron 或任务完成自动触发，两者可叠加**。

- 任务始终可手动执行（不要求配调度）。
- `scheduled_enabled`：是否开定时；开则必填 `cron`。
- `auto_trigger_enabled`：是否绑定任务完成后自动触发；开则记录绑定对象（DAG 节点 / 同步任务 / 采集任务，枚举见
  `auto_trigger_object_type`）。
- 手动、定时、自动三种可同时存在；R6「同规则同小时重复触发合并」由执行层 `last_trigger_at` 去重（见 §5.2）。

**列表「调度状态」口径**（对齐现有同步/采集任务 `scheduleStatusBadge`）：仅 `scheduled_enabled=1` 且配置了 cron
时显示「已启用 / 已停用」，纯手动/自动触发任务显示「—」；「任务状态」列对应 `enabled`（任务整体启用/停用），二者独立。

### D-D3：引入独立「规则模板库」

确认引入独立模板库，多一张 `quality_rule_template` 表。任务内「选择模板 + 多表」批量生成规则明细，避免重复配置。

- 模板 = 校验逻辑模板（类型 + SQL 片段 + 字段/阈值占位），任务引用模板时填入具体表、字段、阈值，生成 `quality_rule` 实例。
- 本期模板库提供内置四类模板（完整性/唯一性/值域范围/自定义 SQL），治理员可维护自定义模板。

### D-D4 / D-D5（直接定，不再确认）

- **评分维度**：表级评分跨任务聚合（一张表可出现在多个任务），综合该表所有启用规则最近一次检查结果。
- **血缘联动**：表级评分徽章（绿/黄/红），未配置规则的表显示灰色「—」。

---

## 2. 领域模型：三层结构

本 Sprint 采用 **质量任务 → 规则 → 表**三层模型：

```
质量任务 quality_job
  ├── 名称、描述、启用状态
  ├── 调度：手动(默认) + 可选定时(cron) + 可选任务完成自动触发
  ├── 告警配置：触发等级(仅严重/严重+警告)、接收用户
  └── 关联多条质量规则 quality_rule   (job_id)
        └── 每条规则：来源模板 template_id + 类型 + 表 + 字段 + 阈值 + 权重
```

执行维度：

```
一次任务执行 quality_check_batch (batchId)
  └── 多条规则检查结果 quality_check_history  (batch_id)
  └── 更新表级质量评分 quality_score
```

模型变更说明：

- **废除** PRD v1.0 的「规则直接绑表 + 每条规则独立 Cron」（决策 C1）。
- 规则不再有独立 cron；调度挂在 **任务**上。
- 规则实例仍可单独「手动执行」「停用」「编辑」，但调度跟随所属任务。

---

## 3. 数据模型设计

### 3.0 迁移脚本

- Flyway 最新脚本编号 `V3.5.7`，本 Sprint 新增脚本从 `V3.6.0` 开始，放在
  `data-nest-system/src/main/resources/db/migration/`。
- 本 Sprint 需要 1 个总脚本 `V3.6.0__sprint6_data_quality.sql`（含任务/规则/模板/批次/历史/评分/合规忽略字段），以及若干增量脚本视实现拆分。
- 分级告警相关（本会话交付）：
  - `V3.6.5__sprint6_quality_alert.sql`：`quality_check_detail` 加 `result_level`，`quality_check_batch` 加 `alert_sent`。
  - `V3.6.6__alert_rule_quality_object_type.sql`：放开 `alert_rule.object_type` 的 CHECK 约束，追加 `QUALITY`。
  - `V3.6.7__alert_rule_name.sql`：`alert_rule` 加 `name`（必填、同一 object_type 下唯一），`alert_history` 加 `rule_name`
    （冗余落库，规则删除后历史仍保留名称）。历史数据回填 `COALESCE(object_name,'未命名规则')`，同类型重名追加 `-N` 序号。

### 3.1 `quality_rule_template`（规则模板库，D3）

| 字段                                              | 类型         | 说明                                                                |
|---------------------------------------------------|--------------|---------------------------------------------------------------------|
| id                                                | BIGINT PK    | Snowflake                                                           |
| name                                              | VARCHAR(100) | 模板名称（唯一）                                                    |
| type                                              | VARCHAR(20)  | `COMPLETENESS`/`UNIQUENESS`/`RANGE`/`CUSTOM_SQL`                    |
| description                                       | VARCHAR(500) | 模板说明                                                            |
| sql_template                                      | TEXT         | 校验 SQL 模板，占位符 `{table}`/`{column}`/`{min}`/`{max}` 等       |
| result_metric                                     | VARCHAR(50)  | 结果指标名（如 `null_rate`/`duplicate_count`/`out_of_range_count`） |
| builtin                                           | SMALLINT     | 是否内置（1 内置，0 自定义）                                        |
| enabled                                           | SMALLINT     | 是否启用                                                            |
| created_by / updated_by / created_at / updated_at | -            | 审计                                                                |

### 3.2 `quality_job`（质量任务）

| 字段                                              | 类型         | 说明                                                         |
|---------------------------------------------------|--------------|--------------------------------------------------------------|
| id                                                | BIGINT PK    | Snowflake                                                    |
| name                                              | VARCHAR(100) | 任务名称                                                     |
| description                                       | VARCHAR(500) | 描述                                                         |
| datasource_id                                     | BIGINT       | ~~数据源范围~~ 已废弃（Sprint 7 方案A移除，数据源下放到规则层；列保留不删，后端不再读写） |
| enabled                                           | SMALLINT     | 启用状态                                                     |
| scheduled_enabled                                 | SMALLINT     | 是否开定时调度（D1）                                         |
| cron                                              | VARCHAR(64)  | 定时 cron（scheduled_enabled=1 时必填）                      |
| auto_trigger_enabled                              | SMALLINT     | 是否任务完成自动触发（D1）                                   |
| auto_trigger_object_type                          | VARCHAR(30)  | 自动触发绑定对象类型（`DAG_NODE`/`SYNC_JOB`/`COLLECT_TASK`） |
| auto_trigger_object_id                            | BIGINT       | 自动触发绑定对象 ID                                          |
| alert_level                                       | VARCHAR(20)  | 告警触发等级：`SEVERE_ONLY` / `SEVERE_WARNING`               |
| last_trigger_at                                   | TIMESTAMP    | 最近一次触发时间（防重 R6）                                  |
| created_by / updated_by / created_at / updated_at | -            | 审计                                                         |

### 3.3 `quality_rule`（质量规则实例，挂任务下）

| 字段                                              | 类型          | 说明                                                   |
|---------------------------------------------------|---------------|--------------------------------------------------------|
| id                                                | BIGINT PK     | Snowflake                                              |
| job_id                                            | BIGINT        | 所属质量任务                                           |
| template_id                                       | BIGINT        | 来源模板（可空，自定义 SQL 也记）                      |
| name                                              | VARCHAR(100)  | 规则名称                                               |
| type                                              | VARCHAR(20)   | 四类（继承模板或自定义）                               |
| table_id                                          | BIGINT        | 目标表 metadata_table.id                               |
| column_name                                       | VARCHAR(128)  | 检查字段（唯一性/值域必填；完整性可空）                |
| check_field                                       | SMALLINT      | 是否按字段检查（完整性填字段时=1，整表=0）             |
| sql_expression                                    | TEXT          | 实际校验 SQL（含占位符替换后的最终 SQL，或自定义 SQL） |
| warning_threshold                                 | DECIMAL(20,6) | 警告阈值（执行结果 ≥ 此值 → 警告）                     |
| severe_threshold                                  | DECIMAL(20,6) | 严重阈值（执行结果 ≥ 此值 → 严重）                     |
| range_min                                         | DECIMAL(20,6) | 值域下界（RANGE 专用，SQL 模板 `{min}` 来源；其余类型 NULL） |
| range_max                                         | DECIMAL(20,6) | 值域上界（RANGE 专用，SQL 模板 `{max}` 来源；其余类型 NULL） |
| result_metric                                     | VARCHAR(50)   | 结果指标名                                             |
| weight                                            | INT           | 权重（评分加权，默认 1）                               |
| enabled                                           | SMALLINT      | 规则启用状态                                           |
| created_by / updated_by / created_at / updated_at | -             | 审计                                                   |

> 索引：`uk(job_id, table_id, name)`、`idx(table_id)`。

### 3.4 `quality_check_batch`（检查批次，T2）

| 字段                  | 类型        | 说明                                   |
|-----------------------|-------------|----------------------------------------|
| id                    | BIGINT PK   | Snowflake                              |
| batch_id              | VARCHAR(64) | 批次号（UUID，同一次任务执行共享）     |
| job_id                | BIGINT      | 所属任务                               |
| trigger_type          | VARCHAR(20) | `MANUAL`/`SCHEDULED`/`AUTO_TRIGGER`    |
| status                | VARCHAR(20) | `RUNNING`/`SUCCESS`/`PARTIAL`/`FAILED` |
| total_rules           | INT         | 应执行规则数                           |
| success_rules         | INT         | 成功规则数                             |
| fail_rules            | INT         | 失败/跳过规则数                        |
| alert_sent            | SMALLINT    | 本次是否已发合并告警                   |
| start_time / end_time | TIMESTAMP   | 执行时间窗                             |

### 3.5 `quality_check_history`（检查结果明细）

| 字段          | 类型          | 说明                                    |
|---------------|---------------|-----------------------------------------|
| id            | BIGINT PK     | Snowflake                               |
| batch_id      | VARCHAR(64)   | 关联批次                                |
| job_id        | BIGINT        | 所属任务                                |
| rule_id       | BIGINT        | 规则实例                                |
| rule_name     | VARCHAR(100)  | 规则名快照                              |
| table_id      | BIGINT        | 目标表                                  |
| table_name    | VARCHAR(255)  | 库名.表名 快照                          |
| column_name   | VARCHAR(128)  | 字段（整表为空）                        |
| datasource_id | BIGINT        | 执行数据源                              |
| result_value  | DECIMAL(20,6) | 执行结果值（如空值率 1.2）              |
| result_metric | VARCHAR(50)   | 结果指标                                |
| result_level  | VARCHAR(20)   | `PASS`/`WARNING`/`SEVERE`/`UNAVAILABLE` |
| executed_sql  | TEXT          | 执行的 SQL                              |
| error_message | VARCHAR(1000) | 失败/不可用原因                         |
| checked_at    | TIMESTAMP     | 检查时间                                |

> 索引：`idx(table_id, checked_at)`、`idx(rule_id, checked_at)`、`idx(batch_id)`。按保留天数（默认 90 天）由
> `QualityCheckHistoryCleanupHandler` 定时清理（PRD §7）。

### 3.6 `quality_score`（表级评分，T3）

| 字段                                      | 类型         | 说明                               |
|-------------------------------------------|--------------|------------------------------------|
| id                                        | BIGINT PK    | Snowflake                          |
| table_id                                  | BIGINT       | 目标表                             |
| table_name                                | VARCHAR(255) | 库名.表名                          |
| datasource_id                             | BIGINT       | 数据源                             |
| score                                     | DECIMAL(5,2) | 0-100 分                           |
| health_level                              | VARCHAR(20)  | `EXCELLENT`/`GOOD`/`WARNING`/`BAD` |
| pass_rules / warning_rules / severe_rules | INT          | 最近一次各类规则数                 |
| last_checked_at                           | TIMESTAMP    | 最近检查时间                       |
| updated_at                                | TIMESTAMP    | 更新时间                           |

> 索引：`uk(table_id)`。一张表一行。

### 3.7 标准合规扩展：`compliance_check_result` 加忽略字段

PRD 要求按 **具体不合规项**忽略 + 取消忽略（AC-10b）。在现有 `compliance_check_result` 表新增：

| 字段       | 类型      | 说明               |
|------------|-----------|--------------------|
| ignored    | SMALLINT  | 0 未忽略，1 已忽略 |
| ignored_at | TIMESTAMP | 忽略时间           |
| ignored_by | BIGINT    | 忽略操作人         |

> `ComplianceCheckResult` 实体、`ComplianceCheckService.listResults`、DTO、前端列表需同步扩展。取消忽略即把 `ignored` 置 0。

### 3.8 血缘节点扩展：`LineageNodeDTO` 加 `qualityScore`

```java
// LineageNodeDTO 新增
private Integer qualityScore;     // 0-100，未配置规则为 null
private String healthLevel;       // EXCELLENT/GOOD/WARNING/BAD，null=暂无质量
private String tableName;         // 库名.表名，用于回填评分
```

前端 `LineageNodeData` 同步扩展并渲染徽章（绿/黄/红，null 显示灰色「—」）。`LineageService.buildTableGraph` 在构造完 nodes
后，用表名集合批量查 `quality_score` 回填。

---

## 4. 校验 SQL 生成（规则模板展开）

所有校验 SQL 采用 **聚合写法返回单行结果**，规避 `GenericSqlExecutor` 的 200 行截断与 5 秒超时（PRD §6.2.2 实现注记）。执行后在
`PreviewResult.rows.get(0).get(0)` 取第一列数值。

### 4.1 四类模板

| 类型             | SQL 逻辑                                                                                                                                              | 结果指标            | 说明             |
|------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------|------------------|
| 完整性（按字段） | `SELECT (COUNT(*) - COUNT({column})) * 1.0 / COUNT(*) AS null_rate FROM {table}`                                                                      | `null_rate`         | 字段空值率（%）  |
| 完整性（整表）   | `SELECT COUNT(*) AS total, COUNT(*) - COUNT(*) AS ... `（需空值行数）→ 用 `SUM(CASE WHEN a IS NULL OR b IS NULL ... THEN 1 ELSE 0 END)/COUNT(*)`      | `null_rate`         | 整表不完整行占比 |
| 唯一性           | `SELECT COUNT(*) - COUNT(DISTINCT {column}) AS duplicate_count FROM {table}`                                                                          | `duplicate_count`   | 重复行数         |
| 值域范围         | `SELECT COUNT(*) AS total, SUM(CASE WHEN {column} < {min} OR {column} > {max} THEN 1 ELSE 0 END) AS out_of_range FROM {table}` → `out_of_range/total` | `out_of_range_rate` | 越界行占比（%）  |
| 自定义 SQL       | 用户自定义返回单个统计值                                                                                                                              | 自定义              | 直接执行         |

### 4.2 阈值判定

```
result_value < warning_threshold        → PASS
warning_threshold ≤ result_value < severe_threshold → WARNING
result_value ≥ severe_threshold          → SEVERE
数据源不可用 / SQL 失败                  → UNAVAILABLE（不触发告警）
```

### 4.3 执行路径

`QualityCheckService.executeJob(jobId, triggerType)`：

1. 生成 `batchId`，插入 `quality_check_batch(RUNNING)`。
2. 加载任务下所有启用规则，逐条：
    - 取规则表的 `datasourceId` → `dataSourceMapper.selectById` → `GenericSqlExecutor.execute(ds, sqlExpression)`。
    - 从 `rows.get(0).get(0)` 取数值 → 判定分级 → 落 `quality_check_history`。
    - 单条失败不影响其他（NAC-4）；`UNAVAILABLE` 不告警（R2）。
3. 批次结束更新 `quality_check_batch` 状态。
4. 按表聚合更新 `quality_score`（见 §5.1）。
5. 达到告警等级 → 收集异常项 → 批量合并告警（见 §5.4）。

---

## 5. 核心流程

### 5.1 表级评分计算（T3 + D-D4）

`ScoreCalculator.recalculateForTables(tableIds)`，在每次任务执行后调用：

1. 对每张表，取所有任务下 **启用规则**的最近一次检查结果（`quality_check_history` 按 rule 取最近）。
2. 按 PRD §6.5.1 加权算法：
    - 基础分 = `100 × (通过规则权重之和 / 全部启用规则权重之和)`
    - 扣分：警告规则按权重 × 警告扣分值；严重规则按权重 × 严重扣分值。
    - `警告扣分值` / `严重扣分值` / `低分区阈值` 为 **全局配置项**（Nacos 配置，见 §8）。
    - 存在严重规则 → 强制压至低分区并标记健康度 `BAD`。
3. 落 `quality_score`（upsert，`uk(table_id)`）。

> 血缘图谱批量回填：`LineageService` 构造完节点后用表名集合 `IN` 查 `quality_score`，一次回填。

### 5.2 三种执行时机

| 时机 | 触发者                                                    | 入口                                                                       |
|------|-----------------------------------------------------------|----------------------------------------------------------------------------|
| 手动 | 治理员点「执行」/「批量执行」                             | `QualityJobController.execute(id)`                                         |
| 定时 | `QualityCheckHandler`（每分钟）扫描任务 cron 匹配当前分钟 | task-core `QualityCheckService`                                            |
| 自动 | worker 在 DAG 节点/同步任务终态回调                       | task-core `QualityCheckService.executeByAutoTrigger(objectType, objectId)` |

**防重（R6）**：同一任务 `last_trigger_at` 在同一小时内（或 cron 精度内）已有成功执行则跳过本次定时触发；手动执行不受此限，但记录
`last_trigger_at`。

### 5.3 worker 挂载点（任务完成自动触发）

在 worker 的 **执行终态回调**接入（SUCCESS 时）：

- DAG 节点：`DagExecutionSyncService` 节点终态回调处，按「节点」匹配 `quality_job.auto_trigger_object_type=DAG_NODE` 且
  `auto_trigger_object_id=nodeId` 的任务 → `executeJob`。
- 同步任务：`SyncJobExecutorService` 执行完成处，按 `auto_trigger_object_type=SYNC_JOB` 匹配。
- 采集任务：采集任务执行完成处，按 `auto_trigger_object_type=COLLECT_TASK` 匹配（对齐告警对象类型体系，命名与
  `AlertConstants.OBJECT_TYPE_COLLECT_TASK` 一致）。

> 需确认 worker 中同步/采集任务终态回调的准确类名/方法，实现时定位（见 §9 Blocker B1）。

### 5.4 告警（复用 alert_rule 体系，扩展 QUALITY）

**对象模型**：质量告警仍走 `alert_rule`，但对象类型扩展 `QUALITY`（对象粒度 = **质量任务**，`objectId=任务ID`）。

- `AlertConstants` 新增 `OBJECT_TYPE_QUALITY = "QUALITY"`、`DISPLAY_QUALITY = "质量任务"`；触发条件复用
  `ALERT_FAILURE = "FAILURE"`（语义=质量异常，不新增 ALERT_QUALITY_FAILURE）。
- `AlertRuleService.validate()` 的对象类型校验扩展支持 QUALITY（白名单 DAG/SYNC_JOB/COLLECT_TASK/QUALITY）；
  `SUPPORTED_TRIGGERS` 保持 FAILURE/TIMEOUT/SUCCESS，QUALITY 规则 `triggerConditions` 固定 `["FAILURE"]`。
- `AlertRuleService.resolveObjectName` 对 QUALITY 返回 **质量任务名**（查 `quality_job`）。
- 告警规则的对象下拉新增「质量」类型：按质量任务返回（`listObjectOptions` 扩展，`objectType=QUALITY`）。
- `AlertHistoryMapper.selectHistoryPage` 增加 `LEFT JOIN quality_job` 联查 QUALITY 对象名。
- **规则名称（2026-08-05）**：`alert_rule` 新增 `name`（必填、同一 `object_type` 下唯一，索引 `uk_alert_rule_name(object_type,name)`）；
  `AlertRuleService.validate` 校验必填 + `assertNameUnique`（update 排除自身 id）。`alert_history` 新增 `rule_name`（冗余落库），
  `selectHistoryPage` 以 `COALESCE(ar.name, ah.rule_name) AS ruleName` 联查（兼容历史旧数据）。
  前端告警中心规则表/历史表各新增名称列，`AlertRuleModal` 新增规则名称输入框。

**批量合并告警**：

- 新增 `AlertFiringService.fireBatch(objectType, objectId, alertType, List<AlertItem>)`，`AlertItem(level, ruleName, detail)`
  （level 为 PASS/WARNING/SEVERE/UNAVAILABLE 之一，仅告警等级项进入 items）。
- 逻辑：查该对象 QUALITY 告警规则 → 校验 `alert_level`（SEVERE_ONLY 仅收 SEVERE；SEVERE_WARNING 收 SEVERE+WARNING；
  UNAVAILABLE 不告警 R2）→ 过滤达到等级的异常项 → 生成 **一条邮件**（正文逐条列出）+ **多条 `alert_history` 记录**
  （每异常一条，便于审计）。
- 防重：`quality_check_batch.alert_sent` 标记已发（批次级幂等，防止 handler 重跑重复发）；fireBatch 内部另按既有
  60 秒防重（同一对象同类告警 60s 内不重复发）。

---

## 6. 接口设计（Controller）

### 6.1 质量任务 `QualityJobController` `/quality/jobs`

| 方法   | 路径            | 说明                                 |
|--------|-----------------|--------------------------------------|
| POST   | `/`             | 创建任务                             |
| PUT    | `/{id}`         | 更新任务                             |
| DELETE | `/{id}`         | 删除任务（级联删规则；检查历史保留） |
| GET    | `/{id}`         | 任务详情（含规则列表）               |
| POST   | `/page`         | 分页列表                             |
| POST   | `/{id}/execute` | 手动执行                             |
| POST   | `/{id}/toggle`  | 启停                                 |

### 6.2 质量规则 `QualityRuleController` `/quality/rules`

| 方法   | 路径            | 说明                                      |
|--------|-----------------|-------------------------------------------|
| POST   | `/`             | 在任务下新增规则                          |
| POST   | `/batch`        | 批量应用（选模板 + 多表生成多条规则，D3） |
| PUT    | `/{id}`         | 编辑规则                                  |
| DELETE | `/{id}`         | 删除规则                                  |
| POST   | `/{id}/execute` | 单条规则手动执行                          |
| POST   | `/{id}/toggle`  | 规则启停                                  |

### 6.3 质量模板 `QualityTemplateController` `/quality/templates`

| 方法   | 路径    | 说明               |
|--------|---------|--------------------|
| GET    | `/`     | 模板列表（含内置） |
| POST   | `/`     | 新增自定义模板     |
| PUT    | `/{id}` | 编辑模板           |
| DELETE | `/{id}` | 删除自定义模板     |

### 6.4 检查结果 `QualityResultController` `/quality/results`

| 方法 | 路径               | 说明                                  |
|------|--------------------|---------------------------------------|
| POST | `/page`            | 检查历史分页（可按任务/规则/表/时间） |
| GET  | `/trend`           | 单规则/单表趋势（时间序列）           |
| GET  | `/batch/{batchId}` | 批次详情（一次任务执行的规则结果）    |

### 6.5 质量评分 `QualityScoreController` `/quality/scores`

| 方法 | 路径               | 说明                               |
|------|--------------------|------------------------------------|
| GET  | `/table/{tableId}` | 单表评分与最近结果                 |
| POST | `/by-tables`       | 批量查多表评分（血缘回填用）       |
| POST | `/page`            | 评分列表（按库/数据源/健康度筛选） |

### 6.6 标准合规扩展 `ComplianceCheckController`

| 方法 | 路径                   | 说明                                      |
|------|------------------------|-------------------------------------------|
| POST | `/ignore/{resultId}`   | 忽略某项（ignored=1）                     |
| POST | `/unignore/{resultId}` | 取消忽略（ignored=0）                     |
| GET  | `/page`                | 列表（默认排除已忽略，可带 ignored 筛选） |
| GET  | `/export`              | 导出 CSV（不合规清单）                    |

---

## 7. 权限矩阵映射

基于既有 Sa-Token 角色，Controller 方法按 PRD §8 加权限注解：

| 操作                             | 角色                         |
|----------------------------------|------------------------------|
| 新增/编辑/停用质量任务/规则/模板 | 治理员、超管                 |
| 手动/批量执行                    | 治理员、超管                 |
| 配置质量告警                     | 治理员、超管                 |
| 查看检查历史/趋势/评分           | 治理员、超管、工程师、分析师 |
| 血缘节点评分徽章                 | 治理员、超管、工程师、分析师 |
| 运行合规扫描                     | 治理员、超管                 |
| 忽略/取消忽略/导出               | 治理员、超管、工程师         |

> 具体注解权限 key 需对照系统现有角色权限表（`sys_role`/`sys_user_role`），实现时确认（见 §9 Blocker B2）。

---

## 8. 配置项（Nacos `shared-governance.yaml` 或 `shared-job.yaml`）

| key                                            | 默认值        | 说明                            |
|------------------------------------------------|---------------|---------------------------------|
| `datanest.quality.score.warning-deduct`        | 10            | 警告规则扣分分值（R3）          |
| `datanest.quality.score.severe-deduct`         | 30            | 严重规则扣分分值                |
| `datanest.quality.score.bad-threshold`         | 60            | 低分区阈值（< 此值健康度=BAD）  |
| `datanest.quality.history.retention-days`      | 90            | 检查历史保留天数                |
| `datanest.quality.check-handler.cron`          | `0 * * * * ?` | 定时扫描 handler 频率（每分钟） |
| `datanest.quality.auto-trigger.debounce-hours` | 1             | 同任务重复触发去重窗口（R6）    |

---

## 9. 已知 Blocker 与待确认点

| #  | 事项                          | 说明                                                                                                                           | 状态   |
|----|-------------------------------|--------------------------------------------------------------------------------------------------------------------------------|--------|
| B1 | worker 同步任务终态回调挂载点 | 需定位 `SyncJobExecutorService` 执行完成回调的确切类/方法，确认自动触发接入点                                                  | 待确认 |
| B2 | 权限注解 key                  | 需对照现有角色权限表确定质量相关操作的权限 key                                                                                 | 待确认 |
| B3 | `alert_rule` QUALITY 对象扩展 | `AlertRuleService.validate()` 硬编码对象类型需扩展；`listObjectOptions`/`resolveObjectName`/`displayObjectType` 需支持 QUALITY | 明确   |

---

## 10. 实现清单（P0）

### 后端

- [x] 规则模板库（CRUD，已交付）：Flyway `V3.6.0__sprint6_quality_rule_template.sql` + task-core
  `QualityRuleTemplate`(entity/Mapper/Service/DTO) + governance `QualityTemplateController`（
  `/api/governance/quality/templates`）
- [ ] Flyway：`V3.6.x__sprint6_data_quality.sql`（任务/规则/批次/历史/评分/合规忽略字段）
- [ ] task-core 实体 + Mapper：`QualityJob`/`QualityRule`/`QualityCheckBatch`/
  `QualityCheckHistory`/`QualityScore`
- [ ] task-core Service：`QualityCheckService`（执行/判定/评分）、`ScoreCalculator`、`QualityRuleTemplateService`、
  `QualityJobService`
- [x] task-core 告警扩展（分级邮件告警，本次交付）：`AlertConstants`、`AlertFiringService.fireBatch`、
  `AlertRuleService` 支持 QUALITY；`QualityCheckService` 分级判定落库（result_level）+ 批次收尾触发合并告警；
  Flyway `V3.6.5`（result_level/alert_sent）+ `V3.6.6`（alert_rule.object_type 放开 QUALITY）
- [ ] governance Controller：6 个（§6.1~6.6）
- [ ] job Handler：`QualityCheckHandler`（定时）、`StandardComplianceCheckHandler`（合规扫描）、
  `QualityCheckHistoryCleanupHandler`（历史清理）
- [ ] worker 挂载：DAG/同步任务终态回调接入自动触发（B1）
- [ ] `LineageService`/`LineageNodeDTO` 扩展 `qualityScore` 并回填
- [ ] `ComplianceCheckService` 扩展忽略/取消忽略/忽略过滤

### 前端

- [x] 规则模板库页（独立菜单 `数据治理/规则模板库`，`/governance/quality-templates`）：统计卡片 + 搜索/类型/来源/状态筛选 +
  列表 + 新增/编辑/详情 Drawer + 启停/删除（内置禁删）+ 批量应用占位提示。已部署并联调通过。
- [x] 数据质量页（`数据治理/数据质量`，`/governance/data-quality`）：**质量任务/质量规则 双页签已完成并部署**；检查历史/质量评分页签留待执行批接口就绪后接入。
- [x] 质量任务配置表单（`QualityJobDrawer`）：名称/数据源范围/描述/启用/定时调度+cron(D1)/自动触发对象选择(项目-DAG-节点树/同步/采集)/告警等级。
- [x] 规则批量应用（`BatchApplyModal`，选模板+多表 D3，逐表可微调字段/阈值/权重）— 已接后端 `/batch` 真逻辑。
- [ ] 检查历史列表 + 详情 + 趋势图（待执行批）
- [ ] 元数据详情页「质量」页签（待执行批）
- [ ] 血缘图谱节点质量徽章（`LineageNodeData` 扩展，待执行批）
- [ ] 标准合规页（忽略/取消忽略/导出，待执行批）

---

## 11. 验收口径映射（PRD AC）

| 验收项                  | 落地位置                              |
|-------------------------|---------------------------------------|
| AC-1 规则配置           | quality_rule + template               |
| AC-2 批量应用           | quality_rule 批量生成                 |
| AC-3 真实数据校验       | GenericSqlExecutor                    |
| AC-4 分级判定           | §4.2                                  |
| AC-5 定时调度           | QualityCheckHandler（任务级 cron）    |
| AC-6 任务完成自动触发   | worker 终态回调                       |
| AC-7/7b 告警分级/合并   | fireBatch + alert_rule QUALITY        |
| AC-8 加权评分           | ScoreCalculator + quality_score       |
| AC-9 血缘联动           | LineageNodeDTO.qualityScore           |
| AC-10/10b 合规扫描/忽略 | ComplianceCheckService + ignored 字段 |
| AC-11 导出 CSV          | ComplianceCheckController.export      |
| AC-12 告警中心对象扩展  | AlertConstants QUALITY                |
| AC-13 权限隔离          | §7                                    |

---

## 12. 变更说明

### 相对 PRD v1.0 的变更（需同步修订 PRD）

1. **模型从「规则直接绑表」改为「质量任务 → 规则 → 表」三层模型**。
2. **调度从「每条规则独立 Cron」改为「任务统一 cron，规则跟随」**（废除决策 C1）。
3. **新增规则模板库**（`quality_rule_template`，D3）。
4. **调度组织**：手动为默认能力 + 可选配定时/自动可叠加（D1）。
5. **新增表**：`quality_job`/`quality_rule`/`quality_rule_template`/`quality_check_batch`/`quality_check_history`/
   `quality_score`。
6. **告警对象粒度**从「质量规则实例」调整为「质量任务」为对象（`objectId=任务ID`），合并告警按批次。

### 待修订的 PRD 小节

- §6.2（规则列表/新增表单）：改为任务 + 规则两层表单，调度移到任务级。
- §6.2.2 字段说明中「调度方式」删除；新增任务级调度配置。
- §6.3.1 执行时机表：「每条规则独立 Cron」→「每个任务统一 Cron」。
- §6.4 告警：对象从规则改任务；`objectType=QUALITY` + `objectId=任务ID`。
- §2 范围总览、§6.6 标准合规：补充忽略/取消忽略交互与导出。
- AC-5：改为任务级 cron。
- §7 引用关系：质量规则→告警规则的删除联动改为「质量任务→告警规则」。

---

> **版本记录**
> - v1.0 (2026-08-04)：基于 PRD v1.0、代码现状与用户确认的 6 个技术决策编写。标注了 PRD 需同步修订的点（§12）。
