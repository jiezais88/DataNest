# Sprint 8：资产目录深化 + 实时 CDC 管道 + 质量报告——技术设计文档

> **版本**：v1.0 | **日期**：2026-08-09 | **作者**：后端
> **关联**：`DataNest-Sprint8-PRD.md`（v1.1）
> **技术决策**：本 Sprint 范围经 2 轮用户确认（主题边界 + T1~T4 架构决策，见 PRD §13），再经代码现状核验与技术选型调研确定 6 个关键技术决策（D1~D6），见 §1 ADR。涉及架构级新增（realtime-service + MinIO + Iceberg），实现前需先在容器环境验证 Flink/CDC/Iceberg 依赖兼容（§8 B1）。

---

## 0. 技术目标与范围

Sprint 8 三大模块，均为 P0：

1. **资产目录深化**（DC-06 数据标签 / DC-07 收藏与关注 / DC-08 评论与讨论 / DC-09 热度排行）：复用 governance 扩展，新增 6 张协作表，不新建服务。
2. **实时 CDC 管道**（DI-04 / RC-01）：**架构级新增** `data-nest-realtime` 服务 + **独立 Flink Session 集群**（JobManager+TaskManager）+ MinIO 对象存储 + Iceberg 湖仓表，MySQL Binlog → Iceberg → Doris 外部表查询。
3. **质量报告**（DG-07 完整版）：复用 governance 质量数据底座，新增评分历史表 + 报告聚合接口（KPI / 四档趋势 / 评分趋势 / 问题清单 / CSV 导出）。

> **落点声明（用户 2026-08-09 确认）**：CDC 目标为 **Doris + Iceberg 湖仓层**（先入湖再 Doris 外部表，严格对齐规格 D17/模块七），本期新增 MinIO + Iceberg 基础设施；realtime 独立第 5 个业务库 `datanest_realtime`；质量评分趋势新增 `quality_score_history`；删除语义为表删除级联清理 + 用户删除保留评论历史。

---

## 1. 关键技术决策记录（ADR）

> 本节记录本 Sprint 与用户确认 / 基于代码核验 / 技术调研的技术决策。后续实现必须严格遵循，如需变更需重新确认。

### D-D1：Flink 技术栈 → Flink 2.2.x + Flink CDC 3.6.x（官方配对 + 匹配 Java 21）

项目统一 JDK 21。Flink 版本兼容性调研结论（**2026-08-09 按官方发布公告/Releases 核实后修订**）：

| 组合 | 官方兼容声明 | Java 21 支持 | 说明 |
|------|--------------|--------------|------|
| Flink 1.20 + CDC 3.4 | ✅（CDC 3.4 官方支持 Flink 1.19/1.20） | ✗（Flink 1.20 官方支持 8/11/17） | 需容器内降级 JVM，破坏项目统一基线 |
| Flink 2.0 + CDC 3.4（原案） | ✗（CDC 3.4 发布公告仅声明 1.19/1.20，**未覆盖 Flink 2.0**） | ✓ | 官方兼容矩阵不覆盖，兼容性需赌实测 |
| **Flink 2.2.1 + CDC 3.6.0** | ✅（CDC 3.6 官方支持 Flink 1.20/2.2，2026-03-31 发布） | ✓（JDK 11+，含 21） | **本方案**：官方配对 + 项目 JDK 21 匹配 |

- **选型（2026-08-09 重新确认定稿，替换原 Flink 2.0 + CDC 3.4 案）**：**Flink 2.2.1**（2.2.x 最新 patch）+ **Flink CDC 3.6.0**（connector 坐标为 **`3.6.0-2.2`**，Flink 2.2 专属后缀）。配套依赖（**M0 已实测 Maven Central 锁定**）：`flink-cdc-pipeline-connector-mysql:3.6.0-2.2`（shaded 自包含，含 flink-connector-mysql-cdc + debezium；**不含 MySQL JDBC 驱动**，需手动带 `mysql-connector-j:8.0.33`——CDC 文档推荐的 8.0.27 已从 central 下架）、`flink-cdc-pipeline-connector-iceberg:3.6.0-2.2`（shaded 自包含，含 iceberg core + FlinkCatalog + S3FileIO/AWS SDK；**不含 Hadoop FileSystem**）。**不需要单独 `iceberg-flink-runtime-2.0`**（connector 已自带 Flink runtime 集成）；**若 warehouse 用 `s3a://` 协议**需补 `flink-shaded-hadoop-2-uber:2.8.3-10.0`（Hadoop FileSystem + S3A），若用 S3FileIO（`s3://`）则免——M0 验证时确定协议选型。
- **运行形态（2026-08-09 修订，替换原内嵌 MiniCluster 案）**：**独立 Flink 集群**——新增 `middleware-flink-jobmanager` / `middleware-flink-taskmanager` 容器（Flink 2.2.1 发行版，Session 模式），`app-realtime` 作为**客户端**经 Flink REST API（JobManager 8081）提交/停止作业（`FlinkPipelineComposer.ofRemoteCluster`，对齐 CDC CLI `-t remote` Session 形态）。理由：资源隔离（Flink 任务不占业务 JVM）、JobManager 独立保证 checkpoint 故障恢复（NAC-2 不丢不重）、Flink Web UI 可观测、TaskManager 可独立扩缩容。本期新增基础设施容器：**MinIO + Flink 集群（JobManager+TaskManager）**。
- **风险兜底**：Flink 2.2 / CDC 3.6 均较新，实现时在容器内跑最小示例验证（§8 B1）；若阻塞，降级 Flink 1.20 + Flink CDC 3.3（容器 JVM 降 17，其余不变）。

### D-D2：realtime-service 边界 → 独立服务 + 独立 Flink 集群 + 独立新库 `datanest_realtime`

- **服务**：新增 `data-nest-realtime`（Spring Boot 4，容器 `app-realtime`，**不内嵌 Flink**，仅作为集群客户端），注册 Nacos，对外走 Gateway `/api/realtime/**`，容器间 Feign 遵循 `*-api` 契约 + `X-Internal-Token`。
- **数据存储（用户确认 T2）**：**独立第 5 个业务库 `datanest_realtime`**（middleware-postgres 同实例），独立 Flyway，持有 `cdc_pipeline` / `cdc_pipeline_table` / `cdc_pipeline_log`。
- **源连接**：经 engineering 既有内部端点 `GET /engineering/internal/datasources/{id}`（返回全字段含 `encryptedPassword`）读取，Feign 消费方配 fallback，fail-closed（管道创建/启动必须拿到源连接）。
- **无库服务注意**：realtime 是**有库服务**（持 datanest_realtime），需复制 `FlywayConfig`（对齐 governance）。

### D-D3：CDC 数据链路 → MySQL Binlog → Iceberg 湖仓（Hadoop Catalog + MinIO）→ Doris 外部表查询

严格对齐规格模块七「CDC 入湖」（用户确认 T1）：

```
业务 MySQL ──Flink CDC 3.6（全量 Snapshot + 增量 Binlog）──▶ Iceberg 湖仓表
                                                              （Hadoop Catalog, warehouse = s3a://datalake/warehouse, 存储于 MinIO）
                                                                │
                                                    Doris Iceberg Catalog（Multi-Catalog）
                                                                ▼
                                                     平台查询 / 质量 / 报表
```

- **Iceberg 部署形态（2026-08-09 用户确认）**：Iceberg 是**表格式 + 依赖库，不是独立服务**——写入端以 `flink-cdc-pipeline-connector-iceberg` Jar 随作业提交到独立 Flink 集群（TaskManager 执行）；存储端数据文件 + 元数据文件全部落 MinIO；读取端由 Doris 内置 Iceberg 格式支持。**本期新增的独立基础设施容器：MinIO + Flink 集群（JobManager + TaskManager）**。
- **Iceberg Catalog 选型**：**Hadoop Catalog**（warehouse 指向 MinIO S3），元数据文件（snapshot/metadata/manifest）随数据文件一起存 MinIO，**无需额外元数据库**——避免第 6 个业务库，且 Doris Iceberg Catalog 原生支持 `warehouse` + `s3.*` 属性对接。
- **全量+增量**：Flink CDC MySQL source 默认 `scan.startup.mode = initial`（先快照后 binlog）；`仅增量` 时 `scan.startup.mode = latest-offset`（需目标湖仓表已存在）。
- **Doris 侧**：一次性建 Iceberg Catalog（DDL 见 §7.2），平台内湖仓表经外部表对平台透明可见。
- **延迟口径**：端到端延迟 = binlog 事件时间 → Doris 外部表最后可见快照时间；Iceberg commit + Doris metadata refresh 纳入，默认告警阈值 30s（放宽于规格 5s，PRD R4）。
- **表结构变更**：Flink CDC Iceberg Sink 默认**自动 schema 演进**（新增列自动补），Doris 外部表需 `REFRESH` 后可见（人工触发，见 §6.3）。

### D-D4：资产协作数据模型 → governance 库新增 6 表，标签复用已有搜索维度

- **落库**：全部新增协作表落 **governance 库**（对齐资产目录归属，无需跨服务）：
  `asset_tag`（标签字典）、`asset_table_tag`（表-标签关联）、`asset_favorite`（收藏）、`asset_follow`（关注）、`asset_comment`（评论）、`asset_view_log`（热度按天聚合）。
- **用户归属**：收藏/关注/评论均为个人维度（`user_id`），前端从 Sa-Token 取当前用户；评论展示用户名经 `SystemUserApi.usernames` 批量回填（失败降级「—」；**查无用户回退「已注销」**，2026-08-09 用户确认）。
- **标签与搜索打通**：Sprint 7 `AssetCatalogService.search` 已预留标签维度（PRD §6.2「Sprint 7 已预留标签展示位」），本期 `search`/`browse` 回填 `tags`，`browse` 支持 `tag` 筛选、`sort=hot`（对齐 DC-09）。
- **关注通知复用**：`collect_change_detail`（`ADDED_TABLE / DELETED_TABLE / MODIFIED_TABLE`）已有变更明细，关注表变更动态经 `asset_follow` ⋈ `collect_change_detail`（按 database/schema/table 三元组匹配）产出，**不新建通知表**（站内动态，NG8）。

### D-D5：热度埋点 → governance 库按天聚合，不做 Redis 计数

- **埋点**：`POST /assets/tables/{tableId}/view`，前端详情页打开防抖上报（同一会话同表去重），异步不阻塞页面（PRD NAC-4）。
- **聚合**：`asset_view_log`（table_id + view_date + view_count），埋点落当日行 upsert 累加；`sort=hot` 按最近 30 天 view_count 求和降序（可扩展 7 天）。
- **不引入 Redis 计数**（最小改动）：热度是展示型指标，允许有秒级延迟，直接写 PG 足够。

### D-D6：质量报告聚合 → governance 本地聚合 + 新增 `quality_score_history` + CSV 导出复用合规经验

- **评分趋势（用户确认 T3）**：新增 `quality_score_history`，**每次检查批次结束**由 `ScoreCalculator` 写一条该表评分快照（score / health_level / 四档规则数 / last_checked_at）；存量数据从 `quality_check_detail` 补算一次（写首次快照）。趋势图读历史表，避免每次报告现算。
- **报告聚合接口**：governance 本地 SQL 聚合（quality_check_batch / quality_check_detail / quality_score / quality_score_history / metadata_table / metadata_column），不跨服务；数据源/库维度经 `metadata_table.datasource_id` 与 `database_name` 本地过滤，数据源名经 engineering-api 批量回填。
- **CSV 导出**：复用 Sprint 6 合规导出经验（UTF-8 BOM + 流式写），`POST /quality/report/export`，治理员/超管权限。

---

## 2. 领域模型

```
资产协作（governance 域，个人维度 user_id）
  ├── asset_tag            标签字典（平台级，可复用）
  ├── asset_table_tag      metadata_table 与 asset_tag 多对多关联
  ├── asset_favorite       收藏（user_id + table_id，uk 去重）
  ├── asset_follow         关注（user_id + table_id，uk 去重）
  ├── asset_comment        评论（table_id + user_id + content）
  └── asset_view_log       热度（table_id + view_date + view_count，uk）

实时 CDC（realtime 域，独立库 datanest_realtime）
  ├── cdc_pipeline         管道主表（源/目标/状态/位点/延迟）
  ├── cdc_pipeline_table   管道表级映射（源表 → 湖仓表 + 主键）
  └── cdc_pipeline_log     管道运行日志（checkpoint/错误堆栈）

质量报告（governance 域）
  └── quality_score_history 表评分快照历史（批次结束写一条）
```

---

## 3. 数据模型设计

### 3.0 迁移脚本与版本规划

| 库 | 当前最高版本 | 本期新脚本 | 内容 |
|----|-------------|-----------|------|
| governance | V1.3.0（sprint7_quality_python） | **V1.4.0**（资产协作 6 表 + 热度） | §3.1 |
| governance | 同上 | **V1.5.0**（quality_score_history + 存量补算函数） | §3.2 |
| datanest_realtime（新库） | 无（新库） | **V1.0.0**（baseline + cdc 三表） | §3.3 |
| engineering / system / alert | 不动 | - | 无库表变更 |

> **新库初始化**：`datanest_realtime` 库需在 middleware-postgres 容器内创建（`CREATE DATABASE datanest_realtime OWNER datanest;`），realtime 服务 `PG_DATABASE=datanest_realtime` 启动后 Flyway 自动执行 V1.0.0。

### 3.1 governance `V1.4.0__sprint8_asset_collaboration.sql`

**`asset_tag`（标签字典）**：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | Snowflake |
| name | VARCHAR(100) | 标签名（唯一，复用已有同名） |
| created_by / created_at | - | 审计（无 updated_by/updated_at，字典不修改） |

> 索引：`uk(name)`。

**`asset_table_tag`（表-标签关联）**：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | Snowflake |
| table_id | BIGINT | metadata_table.id |
| tag_id | BIGINT | asset_tag.id |
| created_by / created_at | - | 审计 |

> 索引：`uk(table_id, tag_id)`、`idx(tag_id)`；`table_id` 删除级联（表删除清绑定）、`tag_id` 级联（标签删除自动解绑）。

**`asset_favorite`（收藏）**：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | Snowflake |
| user_id | BIGINT | 收藏人 |
| table_id | BIGINT | metadata_table.id |
| created_at | TIMESTAMP | 收藏时间 |

> 索引：`uk(user_id, table_id)`；`table_id` 删除级联。

**`asset_follow`（关注）**：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | Snowflake |
| user_id | BIGINT | 关注人 |
| table_id | BIGINT | metadata_table.id |
| created_at | TIMESTAMP | 关注时间 |

> 索引：`uk(user_id, table_id)`、`idx(table_id)`；`table_id` 删除级联。

**`asset_comment`（评论）**：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | Snowflake |
| table_id | BIGINT | metadata_table.id |
| user_id | BIGINT | 评论人 |
| content | VARCHAR(2000) | 评论内容 |
| deleted | SMALLINT DEFAULT 0 | 软删标记（用户删除/表删除置 1，保留历史） |
| created_by / created_at | - | 审计 |

> 索引：`idx(table_id, id DESC)`（倒序分页）、`idx(user_id)`；`table_id` 删除级联物理删；用户删除保留记录（deleted=1）。

**`asset_view_log`（热度按天聚合）**：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | Snowflake |
| table_id | BIGINT | metadata_table.id |
| view_date | DATE | 访问日期 |
| view_count | INT | 当日访问数 |
| updated_at | TIMESTAMP | 最近累加时间 |

> 索引：`uk(table_id, view_date)`；表删除级联清理。

### 3.2 governance `V1.5.0__sprint8_quality_report.sql`

**`quality_score_history`（表评分快照历史）**：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | Snowflake |
| table_id | BIGINT | metadata_table.id |
| table_name | VARCHAR(255) | 库名.表名快照 |
| datasource_id | BIGINT | 数据源（-1 = 内置 Doris） |
| score | DECIMAL(5,2) | 0-100 评分 |
| health_level | VARCHAR(20) | EXCELLENT/GOOD/WARNING/BAD |
| pass_rules / warning_rules / severe_rules | INT | 四档规则数（UNAVAILABLE 不计） |
| checked_at | TIMESTAMP | 检查批次结束时间（趋势图 X 轴） |
| created_at | TIMESTAMP | 记录创建时间 |

> 索引：`idx(table_id, checked_at)`（评分趋势查询）、`idx(checked_at)`（时间范围过滤）。
>
> **写入口**：`ScoreCalculator.recalculateForTables` 内，upsert `quality_score` 后追加一条 `quality_score_history`（复用同一次计算结果，避免重复计算）。
>
> **存量补算**：V1.5.0 脚本**不做数据补算**（Flyway 迁移内补算复杂且不可逆）。由上线后一次性 job/脚本从 `quality_check_detail` 按表取最近批次聚合（复用 ScoreCalculator 算法）写首次快照——见 §4.3。

### 3.3 realtime 库 `V1.0.0__baseline.sql`

**`cdc_pipeline`（管道主表）**：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | Snowflake |
| name | VARCHAR(100) | 管道名（唯一） |
| source_datasource_id | BIGINT | 源数据源（engineering datasource_connection.id） |
| source_database | VARCHAR(100) | 源库名 |
| target_database | VARCHAR(100) | 湖仓库名（Iceberg namespace） |
| sync_mode | VARCHAR(20) | `FULL_AND_INCREMENT` 全量+增量（默认）/ `INCREMENTAL_ONLY` 仅增量 |
| startup_mode | VARCHAR(20) | `INITIAL` 从最新 / `LATEST_OFFSET` 从最早（Flink CDC 语义） |
| write_mode | VARCHAR(20) | `UPSERT`（按主键）/ `APPEND` |
| status | VARCHAR(20) | `STOPPED` 未启动 / `RUNNING` 运行中 / `ERROR` 异常 |
| flink_job_id | VARCHAR(64) | Flink 作业 ID（提交后回填，停止时清理） |
| current_lag_seconds | INT | 当前端到端延迟（秒） |
| total_changes | BIGINT | 累计变更数（含全量+增量） |
| last_error | VARCHAR(2000) | 最近一次错误信息 |
| config_json | TEXT | 扩展配置（checkpoint 间隔、Iceberg commit 间隔等） |
| created_by / updated_by / created_at / updated_at | - | 审计（updated_at 无 DB 默认值） |

> 索引：`uk(name)`、`idx(status)`、`idx(source_datasource_id)`（删除数据源前置校验）。

**`cdc_pipeline_table`（管道表级映射）**：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | Snowflake |
| pipeline_id | BIGINT | cdc_pipeline.id |
| source_table | VARCHAR(200) | 源表名 |
| target_table | VARCHAR(200) | 湖仓表名（默认同名） |
| primary_key | VARCHAR(500) | 主键字段（逗号分隔，UPSERT 用） |
| created_at | TIMESTAMP | 创建时间 |

> 索引：`idx(pipeline_id)`；pipeline 删除级联。

**`cdc_pipeline_log`（管道运行日志）**：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | Snowflake |
| pipeline_id | BIGINT | cdc_pipeline.id |
| level | VARCHAR(10) | INFO/WARN/ERROR |
| message | TEXT | 日志内容 |
| created_at | TIMESTAMP | 日志时间 |

> 索引：`idx(pipeline_id, id DESC)`；pipeline 删除级联。

---

## 4. 核心流程

### 4.1 资产协作（标签/收藏/关注/评论/热度）

**打标签**：详情页输入标签名 → 查 `asset_tag` 存在则复用、否则新建 → 写 `asset_table_tag`（幂等）→ 返回表当前标签列表。删除标签：删关联行；标签字典无表引用时物理删除。

**收藏/关注**：详情页按钮切换，写/删 `asset_favorite`/`asset_follow`（uk 幂等）。「我的收藏」列表 = `asset_favorite` ⋈ `metadata_table` 分页（复用资产卡片字段 + 收藏时间）；「我的关注」= `asset_follow` ⋈ `metadata_table` + 每表最近变更动态（`collect_change_detail` 按 database/schema/table 三元组匹配取最近一条，时间倒序）。

**评论**：详情页「评论」页签，发布写 `asset_comment`；列表倒序分页，`deleted=0` 过滤；作者/治理员/超管可删（作者删 = 软删 deleted=1；治理员/超管删 = 软删 + 记录删除人）。

**热度**：详情页打开 → 防抖上报 `POST /assets/tables/{id}/view`（幂等，后端按 (table_id, 当天) upsert view_count++）→ 资产浏览 `sort=hot` 聚合最近 30 天。

### 4.2 CDC 管道生命周期

```
创建（向导）→ 预检 → 保存（仅配置）
                ↓ 启动
      提交 Flink 作业（app-realtime 经 REST 提交到独立 Flink Session 集群）
                ↓
         全量 Snapshot（initial）→ 增量 Binlog → Iceberg Sink（checkpoint 周期 commit）
                ↓
         停止（cancel 作业 + 置 STOPPED） / 异常（置 ERROR + 记录 last_error）
                ↓
         删除（停止后删元数据，不自动删湖仓表数据）
```

1. **预检**：源数据源连通性（Feign 读连接信息 + JDBC 探测）、binlog 开启（`SHOW VARIABLES LIKE 'log_bin'`）、目标 MinIO/Iceberg 可写、主键字段存在。
2. **启动**：按 `cdc_pipeline` + `cdc_pipeline_table` 组装 Flink CDC YAML Pipeline（source: mysql → sink: iceberg），经 `FlinkPipelineComposer.ofRemoteCluster` 提交到独立 Flink Session 集群（REST 8081），回填 `flink_job_id`、置 RUNNING。
3. **监控**：作业内定期上报延迟（binlog 位点 - 当前时间）/累计变更 → 回写 `current_lag_seconds`/`total_changes`（realtime 服务内轮询，不依赖外部指标系统）。
4. **停止**：cancel Flink 作业 → 置 STOPPED、清 `flink_job_id`。
5. **删除校验（PRD §7）**：运行中删除需先停止确认；删除数据源时 engineering 前置校验仍有管道引用（经 realtime-api 查询）。

### 4.3 质量报告聚合

**KPI**：范围（数据源/库/质量任务/时间）内 `quality_check_batch` 批次数、`quality_check_detail` 明细数；平均评分 = 范围内 `quality_score`（当前最新）均值；通过率 = PASS 明细数 / 有效明细数（排除 UNAVAILABLE）。

**四档分布趋势**：按天 GROUP BY `quality_check_detail.created_at` 聚合 PASS/WARNING/SEVERE/UNAVAILABLE 数量（`result_level`），折线图多系列。

**评分趋势**：按 `quality_score_history` 按表（table_id）+ 时间范围取 `score`/`checked_at` 序列。

**问题清单**：`quality_check_detail`（result_level IN SEVERE/WARNING）+ 表名/规则名/类型/结果值/阈值/检查时间，分页；阈值字段从 `quality_rule`（warning_threshold/severe_threshold）回填。

**导出**：当前筛选的问题清单 + 汇总 KPI，UTF-8 BOM CSV 流式写出。

---

## 5. 接口设计（Controller）

### 5.1 资产协作（governance `AssetCatalogController` 扩展，`/assets`）

> 读接口四角色可见；写接口标注权限。所有写操作鉴权经 Sa-Token `StpUtil.getLoginIdAsLong()` 取当前用户。

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | `/tags` | 全部标签字典（标签云） | 全角色 |
| GET | `/tables/{tableId}/tags` | 某表标签列表 | 全角色 |
| POST | `/tables/{tableId}/tags` | 打标签（body: {tagName}） | 全角色 |
| DELETE | `/tables/{tableId}/tags/{tagId}` | 删表标签绑定 | 全角色 |
| POST | `/tables/{tableId}/favorite` | 收藏 | 全角色 |
| DELETE | `/tables/{tableId}/favorite` | 取消收藏 | 全角色 |
| GET | `/my-favorites` | 我的收藏（分页） | 全角色 |
| POST | `/tables/{tableId}/follow` | 关注 | 全角色 |
| DELETE | `/tables/{tableId}/follow` | 取消关注 | 全角色 |
| GET | `/my-follows` | 我的关注（含最近变更动态，分页） | 全角色 |
| GET | `/tables/{tableId}/comments` | 评论列表（分页，deleted=0） | 全角色 |
| POST | `/tables/{tableId}/comments` | 发表评论 | 全角色 |
| DELETE | `/comments/{commentId}` | 删除评论（作者/治理员/超管；治理员/超管删记录删除人） | 作者 / 治理员 / 超管 |
| POST | `/tables/{tableId}/view` | 热度埋点（幂等，防抖） | 全角色 |
| GET | `/hot-tables` | 热门数据表 Top N（30 天热度） | 全角色 |

> **`browse` 扩展**：新增可选 `tag`（按标签筛选）、`sort=hot`（热度降序）；`search`/`browse` 返回 `tags`（表标签名数组）。

### 5.2 CDC 管道（realtime `CdcPipelineController`，`/cdc/pipelines`）

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| POST | `/validate-source` | 预检源数据源（连通/binlog/权限） | 超管/工程师 |
| GET | `/source-databases/{datasourceId}` | 源库列表（向导下拉） | 超管/工程师 |
| POST | `/` | 创建管道（保存配置，不启动） | 超管/工程师 |
| GET | `/page` | 管道分页（状态/延迟/累计变更） | 超管/工程师/治理员/分析师 |
| GET | `/{id}` | 管道详情 | 超管/工程师/治理员/分析师 |
| PUT | `/{id}` | 编辑（仅 STOPPED 可编辑） | 超管/工程师 |
| DELETE | `/{id}` | 删除（运行中先停再删） | 超管/工程师 |
| POST | `/{id}/start` | 启动（预检 + 提交 Flink 作业） | 超管/工程师 |
| POST | `/{id}/stop` | 停止（cancel 作业） | 超管/工程师 |
| GET | `/{id}/logs` | 运行日志（分页） | 超管/工程师/治理员/分析师 |
| GET | `/{id}/refresh-catalog` | 触发 Doris Iceberg Catalog REFRESH（表结构变更后） | 超管/工程师 |

> 配套 internal 端点（realtime-api 契约）：`GET /realtime/internal/cdc/pipelines?datasourceId=`（engineering 删除数据源前置校验引用用，返回管道 id/name 列表）。

### 5.3 质量报告（governance `QualityReportController`，`/quality/report`）

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| POST | `/options` | 筛选联动选项（数据源/库/质量任务） | 全角色 |
| POST | `/summary` | KPI 汇总 | 全角色 |
| POST | `/level-trend` | 四档分布趋势（按天） | 全角色 |
| POST | `/score-trend` | 表评分趋势（按表 + 时间范围） | 全角色 |
| POST | `/issues` | 问题清单分页 | 全角色 |
| POST | `/export` | 导出 CSV（BOM） | 治理员/超管 |

> 请求体统一 `QualityReportRequest`：`datasourceId / databaseName / jobId / startTime / endTime / tableId(评分趋势) / page/pageSize(问题清单)`；时间用 ISO String（Feign 约束对齐）。

---

## 6. 权限矩阵映射

基于既有 Sa-Token 角色，Controller 方法按 PRD §8 加权限注解：

| 操作 | 角色 |
|------|------|
| 打标签 / 收藏 / 关注 / 发表评论 / 删自己评论 | 超管、工程师、治理员、分析师（`DATA_ANALYST`） |
| 删他人评论 | 超管、治理员 |
| 我的收藏 / 我的关注 / 热门排行 / 热度埋点 | 超管、工程师、治理员、分析师 |
| CDC 管道配置 / 启停 / 编辑 / 删除 | 超管、工程师（`DATA_ENGINEER`） |
| CDC 管道查看（列表/详情/日志） | 超管、工程师、治理员、分析师 |
| 质量报告查看（KPI/趋势/问题清单） | 超管、工程师、治理员、分析师 |
| 质量报告导出 CSV | 超管、治理员 |

> `AssetCatalogController` 类级已是四角色 OR；新增写接口（标签/收藏/关注/评论）**不额外收窄**（全角色），删他人评论方法级收窄到 `SUPER_ADMIN`/`GOVERNANCE_ADMIN`。
> 前端 `Sidebar.tsx` 新增：「数据资产」组下「我的收藏」「我的关注」（`ALL_ROLES`）；「数据工程」组下「CDC 管道」（查看 `ALL_ROLES`，写按钮按 `ENGINEERING_WRITE_ROLES` 显隐）；「数据治理」组下「质量报告」（查看 `ALL_ROLES`，导出按 `GOVERNANCE_WRITE_ROLES` 显隐）。

---

## 7. 配置项与部署

### 7.1 配置项（Nacos shared-configs）

**`shared-realtime.yaml`（新增，realtime 消费）**：

| key | 默认值 | 说明 |
|-----|--------|------|
| `datanest.realtime.flink.jobmanager-url` | `http://middleware-flink-jobmanager:8081` | 独立 Flink Session 集群 REST 地址 |
| `datanest.realtime.flink.parallelism` | 1 | 作业并行度（提交参数） |
| `datanest.realtime.flink.checkpoint-interval-ms` | 30000 | Checkpoint 间隔（Iceberg 提交频率） |
| `datanest.realtime.flink.additional-jars` | `flink-cdc-pipeline-connector-mysql/iceberg:3.6.0-2.2` 等 | 提交作业时附加的 CDC/驱动 jar（M0 已锁定精确版本） |
| `datanest.realtime.iceberg.warehouse` | `s3a://datalake/warehouse` | Iceberg warehouse（MinIO S3） |
| `datanest.realtime.iceberg.catalog-name` | `datalake_catalog` | Hadoop Catalog 名 |
| `datanest.realtime.iceberg.commit-interval-ms` | 60000 | Iceberg commit 间隔（快照频率） |
| `datanest.realtime.monitor.interval-ms` | 5000 | 延迟/变更数轮询回写间隔 |
| `datanest.realtime.lag.warn-threshold` | 30 | 延迟告警阈值（秒） |

**`shared-minio.yaml`（新增，realtime + governance 消费）**：

| key | 默认值 | 说明 |
|-----|--------|------|
| `datanest.minio.endpoint` | `http://middleware-minio:9000` | MinIO 服务地址 |
| `datanest.minio.access-key` | `datanest` | S3 AccessKey |
| `datanest.minio.secret-key` | `datanest123` | S3 SecretKey |
| `datanest.minio.bucket` | `datalake` | 湖仓 bucket |

> `shared-doris.yaml` 追加 Iceberg Catalog 建库信息（供治理侧工具/文档参考，不落代码）。

### 7.2 Docker Compose 变更

**新增 `middleware-minio` 容器**：

```yaml
middleware-minio:
  # 版本锁定：RELEASE.2025-09-07T16-13-09Z（2026-08-09 实测的 minio/minio:latest 实际版本，固化避免漂移）
  image: minio/minio:RELEASE.2025-09-07T16-13-09Z
  container_name: datanest-middleware-minio
  command: server /data --console-address ":9001"
  environment:
    MINIO_ROOT_USER: datanest
    MINIO_ROOT_PASSWORD: datanest123
    TZ: Asia/Shanghai
  ports:
    - "9000:9000"
    - "9001:9001"
  volumes:
    - minio-data:/data
  networks: [datanest-net]
  healthcheck:
    # minio/minio 官方镜像不含 curl/mc，用 bash /dev/tcp 探测 S3 端口
    test: ["CMD-SHELL", "bash -c 'echo > /dev/tcp/localhost/9000' 2>/dev/null || exit 1"]
    interval: 10s
    timeout: 5s
    retries: 10
```

**新增 `middleware-flink-jobmanager` / `middleware-flink-taskmanager` 容器（Flink 2.2.1 Session 集群）**：基于自定义镜像 `datanest-flink:2.2.1`（`docker/flink/Dockerfile`，**M0 实测验证通过**），JobManager 暴露宿主 18081→容器 8081（REST + Web UI，宿主 8081 被 nacos 占用），Session 模式（TaskManager 数量 1，1 slot）。

**自定义镜像预置内容（M0 实测锁定的完整依赖矩阵，缺一不可）**：
- `flink-cdc-dist` / `flink-cdc-common` / `flink-cdc-flink2-compat` / `flink-cdc-pipeline-connector-mysql` / `flink-cdc-pipeline-connector-iceberg`：均 **`3.6.0-2.2`** → `/opt/flink/lib/`
- `mysql-connector-j:8.0.33` → `/opt/flink/lib/`（CDC 文档推荐 8.0.27 已从 central 下架）
- `flink-shaded-hadoop-2-uber:2.8.3-10.0` → `/opt/flink/lib/`（Hadoop FileSystem，iceberg HadoopCatalog 必需）
- `flink-s3-fs-hadoop:2.2.1` → `/opt/flink/lib/`（S3AFileSystem 实现；**必须放 lib 而非 plugins/**，否则作业看不到 S3A 类且 delegration token provider 重复注册）
- `core-site.xml`（fs.s3a.endpoint/path-style）→ `/opt/flink/conf/` + `HADOOP_CONF_DIR=/opt/flink/conf`（S3A endpoint 唯一生效途径；iceberg `hadoop.*` catalog 前缀不透传）
- `config.yaml` 追加：`s3.*`（access-key/secret-key/endpoint/path-style）+ **`classloader.resolve-order: parent-first`**（必配，否则 connector 在 lib 与 pipeline.jars 双加载 → `HadoopCatalog cannot be cast to Catalog`）
- 容器环境变量：`AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`（S3A 凭据；`s3.*` 配置不映射时凭据从这里读）

**M0 已知坑（均已在镜像中固化解决）**：
1. **`-Dclassloader.resolve-order` 作业参数不生效**，必须写集群 config.yaml
2. **`execution.checkpointing.unaligned.enabled` 与 CDC 自定义 partitioner 冲突**（JobInitializationException），不要开
3. **首次提交 `pekko.ask.timeout` 需加大到 120s**（SchemaOperator RPC 到协调器超时）
4. 端口映射后 Doris 访问 MinIO 需用宿主侧地址 `http://192.168.119.1:9000`（VMnet8），不能用容器名

**新增 `app-realtime` 容器**：`docker/realtime.Dockerfile`（基于 JDK 21，**不内嵌 Flink**，仅含 CDC YAML 组装 + 集群客户端依赖 + 业务依赖），`PG_DATABASE=datanest_realtime`，`NACOS_HOST/...`、`MINIO_*`、`FLINK_JM_URL` 环境变量，healthcheck 端口 8089，依赖 middleware-nacos/minio/flink-jobmanager/postgres/engineering。

**PostgreSQL 新库**：middleware-postgres 启动后创建 `datanest_realtime`（init 脚本或手工 `CREATE DATABASE`）。

**Doris 建 Iceberg Catalog（一次性，手工执行）**：

```sql
-- Doris 侧（root 执行；M0 已实测通过）
-- 注意：s3.endpoint 必须用 Doris 主机可路由到的宿主侧地址（192.168.119.1=VMnet8 宿主接口），
--       不能写容器名 middleware-minio（Doris 不在 datanest-net）
CREATE CATALOG datalake_catalog PROPERTIES (
  'type' = 'iceberg',
  'iceberg.catalog.type' = 'hadoop',
  'warehouse' = 's3a://datalake/warehouse',
  's3.endpoint' = 'http://192.168.119.1:9000',
  's3.access_key' = 'datanest',
  's3.secret_key' = 'datanest123',
  's3.region' = 'us-east-1',
  's3.path_style_access' = 'true'
);
-- 平台查询：SELECT * FROM datalake_catalog.testdb.users LIMIT 10;（M0 返回 3 行含实时增量）
```

---

## 8. 已知 Blocker 与待确认点

| # | 事项 | 说明 | 状态 |
|---|------|------|------|
| B1 | Flink 2.2 + CDC 3.6 + Iceberg 依赖兼容 | ✅ **已通过（M0 实测）**：Flink 2.2.1 + CDC 3.6.0（connector `3.6.0-2.2`）；`flink-cdc.sh -t remote` 提交到独立 Session 集群，MySQL→Iceberg 链路 RUNNING，含全量+增量；依赖矩阵完整锁定（见 §7.2）；若阻塞降级 Flink 1.20 + CDC 3.3（JVM 降 17，未触发） | ✅ 已通过 |
| B2 | Doris 版本 Iceberg Catalog 支持 | ✅ **已通过（M0 实测）**：Doris 建 `datalake_catalog`（TYPE=iceberg, hadoop catalog, s3a warehouse）后 `SHOW TABLES FROM datalake_catalog.testdb` 可见 `users` 表，`SELECT` 返回 3 行（含实时增量）；`s3.endpoint` 用 `192.168.119.1:9000`（宿主 VMnet8） | ✅ 已通过 |
| B3 | 存量评分历史补算 | V1.5.0 不做迁移内补算；需一次性 job 从 `quality_check_detail` 补写 `quality_score_history`（复用 ScoreCalculator 算法），补算范围与触发方式待定 | 待实现 |
| B4 | 删除用户时评论保留语义 | ✅ 已定稿（2026-08-09 用户确认）：**前端查用户名批量回填，user_id 查无显示「已注销」**——评论列表经 `SystemUserApi.usernames` 批量回填作者名，查无（用户已物理删）回退「已注销」，零后端改动 | 明确 |
| B5 | 评论阈值字段回填 | `quality_check_detail` 无阈值字段（阈值存 `quality_rule.warning_threshold/severe_threshold`），问题清单需按 `rule_id` 回填，历史 rule 已删则阈值缺省 | 明确（按 rule_id 回填） |

---

## 9. 实现清单（P0）

### 后端

- [ ] **realtime 服务（新模块）**：`data-nest-realtime` 骨架（pom/application.yml/FlywayConfig）+ 独立库 `datanest_realtime`；`CdcPipelineService` + `CdcPipelineController`（§5.2）；Flink CDC YAML 作业组装与**经 REST 提交到独立 Flink Session 集群**（`FlinkPipelineComposer.ofRemoteCluster`）的启停/监控；`cdc_pipeline` 三表 entity/mapper；`CdcPipelineApi` Feign 契约（internal 端点）
- [ ] **governance 资产协作**：Flyway `V1.4.0`（§3.1）；`AssetTag/AssetTableTag/AssetFavorite/AssetFollow/AssetComment/AssetViewLog` entity/mapper；`AssetCollaborationService`（打标签/收藏/关注/评论/热度/我的收藏/我的关注）+ Controller 扩展（§5.1）；`browse`/`search` 补 `tags`/`sort=hot`
- [ ] **governance 质量报告**：Flyway `V1.5.0`（§3.2）；`QualityScoreHistory` entity/mapper；`ScoreCalculator` 批次结束写历史快照；`QualityReportService` + `QualityReportController`（§5.3）；CSV 导出（BOM）；存量补算脚本
- [ ] **task-core / common**：`ErrorCode` 新增（§9.1）；realtime-api 契约 DTO
- [ ] **engineering 数据源删除校验**：删除数据源前经 realtime-api 校验管道引用（fail-closed，PRD §7）

### 前端

- [ ] `Sidebar.tsx`：新增「我的收藏」「我的关注」（数据资产组，ALL_ROLES）、「CDC 管道」（数据工程组）、「质量报告」（数据治理组）
- [ ] `router/index.tsx`：新增 `/asset-catalog/favorites`、`/asset-catalog/follows`、`/engineering/cdc-pipelines`、`/governance/quality-report`
- [ ] 资产详情页扩展：标签区（打/删标签）、收藏/关注按钮、评论页签、热度展示（`pages/assets/detail`）
- [ ] 「我的收藏」「我的关注」页（复用资产卡片 + 变更动态）
- [ ] CDC 管道页：向导（4 步）+ 列表（状态/延迟/变更数）+ 日志抽屉 + 启停/编辑/删除（`pages/engineering/cdc-pipelines`）
- [ ] 质量报告页：筛选区 + KPI 卡 + 趋势图（复用图表库）+ 问题清单 + 导出（`pages/governance/quality-report`）
- [ ] `types/`：`asset.ts` 扩展（tags/热度）、新增 `cdc.ts`、`quality-report.ts`；`api/` 对应模块

### 部署与验证

- [x] docker-compose 加 MinIO + Flink 集群（JobManager+TaskManager）+ app-realtime + datanest_realtime 库；Doris `CREATE CATALOG`（§7.2）——✅ MinIO/Flink 集群/Doris catalog 已在 M0 完成并验证；app-realtime + datanest_realtime 库待 F2
- [x] 容器内验证 Flink 2.2 + CDC 3.6 + Iceberg 依赖（B1）——✅ M0 已通过（MySQL→Iceberg→Doris 全链路）
- [ ] 手工 CDC 管道 E2E：test-mysql 建表 → 管道创建启动 → 湖仓表出现 → Doris 外部表可查 → 变更秒级可见
- [ ] 质量报告 E2E：跑一批质量检查 → 报告 KPI/趋势/问题清单/导出

### 9.1 新增错误码（common `ErrorCode`）

| 区间 | 错误码 | 说明 |
|------|--------|------|
| 资产协作（4xxx 扩展） | 4021 | 标签不存在 |
| | 4022 | 评论不存在 |
| | 4023 | 无权限删除他人评论 |
| | 4024 | 收藏/关注/标签 数据校验失败（幂等等） |
| CDC（8xxx 新增区间） | 8001 | 管道不存在 |
| | 8002 | 管道名已存在 |
| | 8003 | 管道状态非法（编辑/删除时） |
| | 8004 | 源数据源连接失败 |
| | 8005 | 源库 binlog 未开启 |
| | 8006 | 目标湖仓写入失败 |
| | 8007 | 管道启动失败 |
| | 8008 | 管道停止失败 |
| | 8009 | 数据源已被 CDC 管道引用 |
| 质量报告（4xxx 扩展） | 4221 | 报告参数非法（时间范围/表不存在） |
| | 4222 | 报告导出失败 |

---

## 10. 验收口径映射（PRD AC）

| PRD AC | 技术验证方式 |
|--------|-------------|
| AC-1 数据标签 | 打/删标签后 `GET /tables/{id}/tags` 变化；`browse?tag=` 命中；`search` 返回 tags |
| AC-2 收藏 | `POST/DELETE /favorite`；`GET /my-favorites` 分页正确 |
| AC-3 关注与变更通知 | 关注后触发元数据采集产生 `collect_change_detail`，`GET /my-follows` 出现变更动态；取消后消失 |
| AC-4 评论 | 发布/删除/权限（作者/治理员）逐一验证 |
| AC-5 热度排行 | 详情页打开后 `GET /hot-tables` 计数增长；`browse sort=hot` 排序正确 |
| AC-6/7 CDC 创建与实时性 | test-mysql 建表→管道→Doris 外部表；`INSERT/UPDATE/DELETE` 后 < 10s Doris 可见（NAC-1） |
| AC-8 CDC 运维 | 启停/编辑/删除；列表延迟/变更数；异常态日志 |
| AC-9/10 质量报告 | 筛选/趋势/问题清单渲染；CSV 带 BOM 可打开 |
| AC-11 权限隔离 | 分析师不可配置 CDC/导出报告；工程师不可删他人评论 |
| NAC-2 故障恢复 | 停 MinIO/网络抖动后管道重启从 checkpoint 恢复 |
| NAC-3 报告响应 | 千级表 + 30 天数据下 < 5s |

---

> **版本记录**
> - v1.0 (2026-08-09)：初始版本。基于 PRD v1.1 与代码核验编写；6 个 ADR（Flink 选型 / realtime 边界 / 入湖链路 / 资产协作模型 / 热度埋点 / 质量报告聚合）；迁移脚本 V1.4.0/V1.5.0/datanest_realtime V1.0.0；部署新增 MinIO + app-realtime；B1/B2 依赖兼容待容器验证。
> - v1.1 (2026-08-09)：用户交互确认后更新——B1 Flink 2.0.x + CDC 3.4.x 定稿；B2 实测 Doris **4.0.7-rc02**（Multi-Catalog 可用）确认支持 Iceberg Catalog；B4 评论「已注销」走前端批量回填查无兜底（零后端改动）；补充 Iceberg 部署形态说明（非独立服务，内嵌库 + MinIO + Doris 内置）。
> - v1.2 (2026-08-09)：**B1 版本组合修订**——经官方发布公告/Releases 核实，Flink CDC 3.4 仅官方支持 Flink 1.19/1.20（未覆盖 2.0），原案 Flink 2.0 + CDC 3.4 非官方配对；经用户确认改用 **Flink 2.2.1 + Flink CDC 3.6.0**（官方配对 + JDK 21 匹配），配套依赖矩阵（`flink-cdc-pipeline-connector-mysql/iceberg:3.6.0` + `iceberg-flink-runtime-2.0:1.10.x` + `mysql-connector-java:8.0.27` + `flink-shaded-hadoop-2-uber`）同步更新（§1 D-D1 / §8 B1 / §9）。
> - v1.3 (2026-08-09)：**Flink 部署形态修订**——经用户确认，**内嵌 MiniCluster 改为独立 Flink Session 集群**（新增 `middleware-flink-jobmanager` / `middleware-flink-taskmanager` 容器，`app-realtime` 不内嵌 Flink、仅经 REST 提交作业，`FlinkPipelineComposer.ofRemoteCluster`）。理由：资源隔离 / JobManager 独立保证 checkpoint 故障恢复（NAC-2 不丢不重）/ Flink Web UI 可观测 / TaskManager 独立扩缩容。同步更新 D-D1 运行形态、D-D2 服务边界、§4.2 生命周期、§7 配置与部署、§8 B1、§9 实现清单。本期新增基础设施容器变为 **MinIO + Flink 集群**。
> - v1.4 (2026-08-09)：**依赖坐标实测锁定**——Flink CDC 3.6 的 connector Maven 坐标为 **`3.6.0-2.2`**（Flink 2.2 专属后缀，非 `3.6.0`）；mysql/iceberg connector 均为 **shaded 自包含 jar**（iceberg 含 core + FlinkCatalog + S3FileIO，无需单独 `iceberg-flink-runtime-2.0`），mysql 缺 JDBC 驱动需补 `mysql-connector-java:8.0.27`，`s3a://` 协议需补 `flink-shaded-hadoop-2-uber`。同步更新 D-D1 配套依赖、§7.1 additional-jars、§7.2 集群 lib、§8 B1（§1 D-D1 / §8 B1）。
> - v1.5 (2026-08-09)：**M0 环境预研完成（B1/B2/B6 全部实测通过）**——`flink-cdc.sh -t remote` 提交到独立 Flink Session 集群跑通 **MySQL(testdb.users) → Iceberg(Hadoop Catalog/MinIO S3A) → Doris Iceberg Catalog 查询**全链路，含实时增量。实战结论固化：① 集群 lib 需预置 **dist/common/flink2-compat/两 connector/mysql 驱动/flink-shaded-hadoop/flink-s3-fs-hadoop** 共 7 个 jar（自定义镜像 `datanest-flink:2.2.1`，`docker/flink/Dockerfile`）；② **`classloader.resolve-order=parent-first`** 必配（否则 iceberg 双 classloader 冲突）；③ `flink-s3-fs-hadoop` 放 **lib 非 plugins**（plugins 会 delegration provider 重复注册 + 作业看不到 S3A）；④ S3A endpoint 用 **core-site.xml + HADOOP_CONF_DIR**，凭据用容器环境变量（`s3.*`/`hadoop.*` 前缀均不透传）；⑤ 禁开 `execution.checkpointing.unaligned`（与 CDC partitioner 冲突）；⑥ `pekko.ask.timeout=120s`；⑦ Doris 建 catalog 的 `s3.endpoint` 用宿主侧 `192.168.119.1:9000`（VMnet8）。更新 §7.2、§8 B1/B2/B6、§9（M0 完成项）。
