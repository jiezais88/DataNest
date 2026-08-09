# Sprint 8：资产目录深化 + 实时 CDC 管道 + 质量报告——技术设计文档

> **版本**：v1.0 | **日期**：2026-08-09 | **作者**：后端
> **关联**：`DataNest-Sprint8-PRD.md`（v1.1）
> **技术决策**：本 Sprint 范围经 2 轮用户确认（主题边界 + T1~T4 架构决策，见 PRD §13），再经代码现状核验与技术选型调研确定 6 个关键技术决策（D1~D6），见 §1 ADR。涉及架构级新增（realtime-service + MinIO + Iceberg），实现前需先在容器环境验证 Flink/CDC/Iceberg 依赖兼容（§8 B1）。

---

## 0. 技术目标与范围

Sprint 8 三大模块，均为 P0：

1. **资产目录深化**（DC-06 数据标签 / DC-07 收藏与关注 / DC-08 评论与讨论 / DC-09 热度排行）：复用 governance 扩展，新增 6 张协作表，不新建服务。
2. **实时 CDC 管道**（DI-04 / RC-01）：**架构级新增** `data-nest-realtime` 服务 + 内嵌 Flink MiniCluster + MinIO 对象存储 + Iceberg 湖仓表，MySQL Binlog → Iceberg → Doris 外部表查询。
3. **质量报告**（DG-07 完整版）：复用 governance 质量数据底座，新增评分历史表 + 报告聚合接口（KPI / 四档趋势 / 评分趋势 / 问题清单 / CSV 导出）。

> **落点声明（用户 2026-08-09 确认）**：CDC 目标为 **Doris + Iceberg 湖仓层**（先入湖再 Doris 外部表，严格对齐规格 D17/模块七），本期新增 MinIO + Iceberg 基础设施；realtime 独立第 5 个业务库 `datanest_realtime`；质量评分趋势新增 `quality_score_history`；删除语义为表删除级联清理 + 用户删除保留评论历史。

---

## 1. 关键技术决策记录（ADR）

> 本节记录本 Sprint 与用户确认 / 基于代码核验 / 技术调研的技术决策。后续实现必须严格遵循，如需变更需重新确认。

### D-D1：Flink 技术栈 → Flink 2.0.x + Flink CDC 3.4.x（匹配 Java 21）

项目统一 JDK 21。Flink 版本兼容性调研结论：

| 组合 | Java 21 支持 | 说明 |
|------|--------------|------|
| Flink 1.20 | ✗ 未正式认证（官方支持 8/11/17） | 若用需容器内降级 JVM，破坏项目统一基线 |
| **Flink 2.0.x** | ✓ **官方支持**（2025-03-24 发布，默认推荐 Java 17、支持 21） | 与项目 JDK 21 匹配 |
| Flink CDC 3.4.x | 随 Flink 2.0 运行 | 2025-05-16 发布，**新增 Iceberg Sink 连接器**，支持 schema 演进/批流一体 |

- **选型（2026-08-09 用户确认定稿）**：Flink 2.0.x + Flink CDC 3.4.x，`realtime-service` 以 Flink 依赖内嵌运行。
- **运行形态**：内嵌 Flink MiniCluster（JVM 本地模式，`flink-runtime` 本地提交），不部署独立 Flink 集群/TaskManager 容器——对齐 PRD R1「内嵌 MiniCluster 降低部署成本」。
- **风险兜底**：Flink 2.0 是新大版本，与 CDC 3.4 / Iceberg 依赖存在兼容不确定性，实现时在容器内跑最小示例验证（§8 B1）；若阻塞，降级 Flink 1.20 + Flink CDC 3.3（容器 JVM 降 17，其余不变）。

### D-D2：realtime-service 边界 → 独立服务 + 内嵌 MiniCluster + 独立新库 `datanest_realtime`

- **服务**：新增 `data-nest-realtime`（Spring Boot 4，容器 `app-realtime`），内嵌 Flink MiniCluster，注册 Nacos，对外走 Gateway `/api/realtime/**`，容器间 Feign 遵循 `*-api` 契约 + `X-Internal-Token`。
- **数据存储（用户确认 T2）**：**独立第 5 个业务库 `datanest_realtime`**（middleware-postgres 同实例），独立 Flyway，持有 `cdc_pipeline` / `cdc_pipeline_table` / `cdc_pipeline_log`。
- **源连接**：经 engineering 既有内部端点 `GET /engineering/internal/datasources/{id}`（返回全字段含 `encryptedPassword`）读取，Feign 消费方配 fallback，fail-closed（管道创建/启动必须拿到源连接）。
- **无库服务注意**：realtime 是**有库服务**（持 datanest_realtime），需复制 `FlywayConfig`（对齐 governance）。

### D-D3：CDC 数据链路 → MySQL Binlog → Iceberg 湖仓（Hadoop Catalog + MinIO）→ Doris 外部表查询

严格对齐规格模块七「CDC 入湖」（用户确认 T1）：

```
业务 MySQL ──Flink CDC 3.4（全量 Snapshot + 增量 Binlog）──▶ Iceberg 湖仓表
                                                              （Hadoop Catalog, warehouse = s3a://datalake/warehouse, 存储于 MinIO）
                                                                │
                                                    Doris Iceberg Catalog（Multi-Catalog）
                                                                ▼
                                                     平台查询 / 质量 / 报表
```

- **Iceberg 部署形态（2026-08-09 用户确认）**：Iceberg 是**表格式 + 内嵌依赖库，不是独立服务**——写入端以 `flink-cdc-pipeline-connector-iceberg` Jar 内嵌在 realtime-service 的 Flink 作业里；存储端数据文件 + 元数据文件全部落 MinIO；读取端由 Doris 内置 Iceberg 格式支持。**本期新增的独立基础设施容器只有 MinIO**。
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
           提交 Flink 作业（内嵌 MiniCluster 本地提交）
                ↓
         全量 Snapshot（initial）→ 增量 Binlog → Iceberg Sink（checkpoint 周期 commit）
                ↓
         停止（cancel 作业 + 置 STOPPED） / 异常（置 ERROR + 记录 last_error）
                ↓
         删除（停止后删元数据，不自动删湖仓表数据）
```

1. **预检**：源数据源连通性（Feign 读连接信息 + JDBC 探测）、binlog 开启（`SHOW VARIABLES LIKE 'log_bin'`）、目标 MinIO/Iceberg 可写、主键字段存在。
2. **启动**：按 `cdc_pipeline` + `cdc_pipeline_table` 组装 Flink CDC YAML Pipeline（source: mysql → sink: iceberg），内嵌 MiniCluster 提交，回填 `flink_job_id`、置 RUNNING。
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
| `datanest.realtime.flink.parallelism` | 1 | 内嵌 MiniCluster 并行度（单机小规模） |
| `datanest.realtime.flink.checkpoint-interval-ms` | 30000 | Checkpoint 间隔（Iceberg 提交频率） |
| `datanest.realtime.flink.memory-mb` | 1024 | 单作业 JVM 内存上限 |
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
  image: minio/minio:latest
  container_name: datanest-middleware-minio
  command: server /data --console-address ":9001"
  environment:
    MINIO_ROOT_USER: datanest
    MINIO_ROOT_PASSWORD: datanest123
  ports:
    - "9000:9000"
    - "9001:9001"
  volumes:
    - minio-data:/data
  networks: [datanest-net]
  healthcheck:
    test: ["CMD", "curl", "-f", "http://localhost:9000/minio/health/live"]
    interval: 10s
    timeout: 5s
    retries: 10
```

**新增 `app-realtime` 容器**：`docker/realtime.Dockerfile`（基于 JDK 21，引入 Flink/CDC/Iceberg 依赖包），`PG_DATABASE=datanest_realtime`，`NACOS_HOST/...`、`MINIO_*` 环境变量，healthcheck 端口 8089，依赖 middleware-nacos/minio/postgres/engineering。

**PostgreSQL 新库**：middleware-postgres 启动后创建 `datanest_realtime`（init 脚本或手工 `CREATE DATABASE`）。

**Doris 建 Iceberg Catalog（一次性，手工执行）**：

```sql
-- Doris 侧（root 执行）
CREATE CATALOG datalake_catalog PROPERTIES (
  'type' = 'iceberg',
  'iceberg.catalog.type' = 'hadoop',
  'warehouse' = 's3a://datalake/warehouse',
  's3.endpoint' = 'http://middleware-minio:9000',
  's3.access_key' = 'datanest',
  's3.secret_key' = 'datanest123',
  's3.region' = 'us-east-1'
);
-- 平台查询：SELECT * FROM datalake_catalog.dwd.orders LIMIT 10;
```

---

## 8. 已知 Blocker 与待确认点

| # | 事项 | 说明 | 状态 |
|---|------|------|------|
| B1 | Flink 2.0 + CDC 3.4 + Iceberg 依赖兼容 | ✅ 已定稿（2026-08-09 用户确认）：按 Flink 2.0.x + CDC 3.4.x 实现；容器内跑最小示例验证 `flink-cdc-pipeline-connector-mysql` / `flink-cdc-pipeline-connector-iceberg` 与 Flink 2.0.x、Java 21 兼容；若阻塞降级 Flink 1.20 + CDC 3.3（JVM 降 17） | 明确（实现时验证） |
| B2 | Doris 版本 Iceberg Catalog 支持 | ✅ 已确认：**doris-4.0.7-rc02**（2026-08-09 实测 `SHOW CATALOGS` 正常，Multi-Catalog 可用）；建 `CREATE CATALOG ... TYPE=iceberg` + `s3.*` 属性对接 MinIO | 明确（≥1.2 满足） |
| B3 | 存量评分历史补算 | V1.5.0 不做迁移内补算；需一次性 job 从 `quality_check_detail` 补写 `quality_score_history`（复用 ScoreCalculator 算法），补算范围与触发方式待定 | 待实现 |
| B4 | 删除用户时评论保留语义 | ✅ 已定稿（2026-08-09 用户确认）：**前端查用户名批量回填，user_id 查无显示「已注销」**——评论列表经 `SystemUserApi.usernames` 批量回填作者名，查无（用户已物理删）回退「已注销」，零后端改动 | 明确 |
| B5 | 评论阈值字段回填 | `quality_check_detail` 无阈值字段（阈值存 `quality_rule.warning_threshold/severe_threshold`），问题清单需按 `rule_id` 回填，历史 rule 已删则阈值缺省 | 明确（按 rule_id 回填） |

---

## 9. 实现清单（P0）

### 后端

- [ ] **realtime 服务（新模块）**：`data-nest-realtime` 骨架（pom/application.yml/FlywayConfig）+ 独立库 `datanest_realtime`；`CdcPipelineService` + `CdcPipelineController`（§5.2）；Flink CDC YAML 作业组装与内嵌 MiniCluster 提交/停止/监控；`cdc_pipeline` 三表 entity/mapper；`CdcPipelineApi` Feign 契约（internal 端点）
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

- [ ] docker-compose 加 MinIO + app-realtime + datanest_realtime 库；Doris `CREATE CATALOG`（§7.2）
- [ ] 容器内验证 Flink 2.0 + CDC 3.4 + Iceberg 依赖（B1）
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
