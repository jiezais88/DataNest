# Sprint 10：数据服务（SQL 查询终端 + 数据 API + API 网关 + 实时推送 + 数据分级分类）——技术设计文档

> **版本**：v1.3 | **日期**：2026-08-12 | **对应 PRD**：`docs/sprint10/DataNest-Sprint10-PRD.md` v1.2（范围 T1~T8 决策已确认；D4 变更事件捕获 M0 已定 = **事件管道分离 + Kafka 事件总线**）
>
> **技术目标**：新建独立数据服务（`data-nest-data-service`，持 `datanest_dataservice` 库），实现 SQL 查询终端（多数据源只读执行 + JSqlParser 语法级只读校验）、数据 API 生成与对外 Key 认证、API 网关（限流/熔断）、调用统计、WebSocket 实时订阅（Kafka 事件总线 + 事件管道分离）、数据分级分类闸门（governance 侧打标 + 数据服务侧拦截）。
>
> **已确认技术决策（2026-08-12 用户拍板）**：① 变更事件捕获 = **引入 Kafka 事件总线**（每可订阅管道独立 Kafka 单 sink 事件作业，方案 B）；② SQL 只读校验 = **JSqlParser 语法级**；③ WebSocket = **数据服务原生 WebSocket**（网关放行握手）；④ 新库名 = **`datanest_dataservice`**。

---

## 0. 技术目标与范围

| 功能块 | 技术范围 | 涉及服务/库 |
|--------|----------|-------------|
| F1 SQL 查询终端 | 多数据源只读 SQL 执行（内置 Doris + 平台数据源）+ JSqlParser 只读校验 + 超时中断 + 结果导出 + 查询历史 | 新建 data-service（库 V1.0.0）、governance（敏感度拦截）、前端 |
| F2 数据 API 生成 + Key 认证 | 表级参数化 API 定义 + 生命周期 + API Key（哈希存储）+ 对外调用入口 | data-service、前端 |
| F3 API 网关（限流/熔断/统计） | Redis 滑动窗口限流 + 熔断 + 调用统计（异步聚合） | data-service（Redis）、前端 |
| F4 WebSocket 实时推送 | Kafka 事件总线（新中间件）+ 事件管道分离（Kafka 单 sink 事件作业）+ 数据服务 WebSocket 订阅分发 | **新增 middleware-kafka**、Flink 集群（lib 加 connector）、realtime（事件 YAML 生成器 + 作业联动）、data-service、前端 |
| F5 数据分级分类 | metadata_table 加敏感度字段 + 分级管理/审计 + 数据服务三端闸门 | governance（库 V1.6.0）、data-service、前端 |

**非范围**：不对外提供 GraphQL/沙箱/联邦查询（PRD NG1~NG9）；数据服务不持有数据源连接配置（经 engineering-api 读）；不改 Flink 集群镜像基础结构（仅在 lib 增补 connector jar）；Kafka 仅作 CDC 事件总线，不承载业务消息。

---

## 1. 关键技术决策记录（ADR）

### D-D1：新建独立服务 `data-nest-data-service` + 新库 `datanest_dataservice`（T8）

- **背景**：PRD T8 已定「新建独立服务」，与规格路线图一致；T3 已定「查询数据源 = 全部可创建数据源 + 内置 Doris」。
- **服务边界**：SQL 执行 / API 定义与 Key / 限流熔断统计 / WebSocket 端点 / 订阅文档。**不持有数据源密码**（经 `EngineeringDatasourceApi.getById` 读 `encryptedPassword` + `EncryptionConfig.decrypt` 解密，复用 `DataPreviewService` 既有模式）；**不持有元数据**（表清单/敏感度经 governance-api 读）。
- **库**：`datanest_dataservice`（第 6 个业务库，独立 Flyway，基线 V1.0.0）。`PG_DATABASE` 注入对齐既有 fail-fast 约定。
- **新服务三件套**（AGENTS.md 约定）：① 引 `spring-cloud-starter-loadbalancer`；② 显式声明 `spring-boot-starter-validation`；③ 启动类 `scanBasePackages` 只追加 `com.datanest.common.internal` + 消费的 api 包。
- **与 existing 服务依赖方向**：data-service → engineering-api（数据源连接）/ governance-api（元数据+敏感度）/ system-api（用户名回填）/ realtime-api（管道信息与订阅文档）。无反向依赖。

### D-D2：SQL 执行引擎 = 内置 Doris 直连（复用 task-core）+ 外部数据源 JDBC 直连 + JSqlParser 只读校验

- **内置 Doris**：复用 `DorisDataSourceConfig`（task-core，静态 getter + HikariCP，懒加载降级 DriverManager）与 `DorisSqlExecutor` 查询模式（上限 1000 行）。数据服务直接依赖 task-core 即可获得（无需自建 Doris 连接配置）。
- **外部数据源**：复用 common `JdbcPreviewHelper`/`JdbcSchemaExtractor` 的 JDBC URL/标识符/分页类型分支（MySQL/PG/Doris 先放开，Oracle/SQLServer 驱动与测试就绪再放，PRD NG10）；连接信息经 `EngineeringDatasourceApi.getById` + `EncryptionConfig.decrypt` 解密（对齐 `DataPreviewService`）；每次查询新连接 + `Statement.setQueryTimeout` 超时中断（动态多数据源连接池管理复杂，本期不做池化，QPS 由限流控制）。
- **只读校验（用户确认 JSqlParser 语法级）**：
  - 引入 `com.github.jsqlparser:jsqlparser`（版本对齐 mybatis-plus-jsqlparser 传递线，见 §7.2）；
  - 用 `CCJSqlParserUtil.parseStatements` 解析，遍历 Statement 类型：`Select`/`SetOperationList` 放行；`Insert`/`Update`/`Delete`/`Create*`/`Alter`/`Drop`/`Truncate`/`Merge` 全部拦截（返回只读错误）；
  - 同时校验 `Select` 内嵌的 `ExplainStatement`、`WithItem` 中的 DML（递归 visitor）——杜绝注释/子查询绕过（PRD 安全口径）；
  - 解析失败（语法错误）→ 返回 SQL 语法错误，不落执行。
- **超时**：`Statement.setQueryTimeout`（默认 60s，Nacos 可配）；前端「停止」走异步取消（执行线程 interrupt + 关闭连接）。

### D-D3：数据 API 生成 + API Key 认证（对外入口与鉴权分离）

- **对外入口**：网关新增路由 `/api/data-service/**` → `lb://data-nest-data-service`（StripPrefix=1）；`open-api` 与 `ws` 路径在网关 `SaTokenConfig.addExclude` **放行登录态**（业务系统无平台账号），改为数据服务侧 `X-API-Key` 独立校验（与 `/internal/**` 令牌模式并列但独立，PRD R9）。
- **Key 存储**：`SHA-256(key)` 存哈希（`api_key.key_hash`），创建时一次性返回明文（`K-` 前缀，仅展示一次）；禁用/删除即失效。
- **API 定义**：`data_api` 表存路径/方法/参数（参数化筛选字段、范围字段、分页开关、返回字段白名单、关联表元数据 id）；发布后对外路径 `/api/data-service/open-api/v1/{自定义path}`（Blocker 5 已定：自定义路径，path 列存完整 `/open-api/v1/{段}` 唯一），认证 `X-API-Key` 头（见 §4.3）。
- **鉴权过滤器**：数据服务 `OpenApiKeyFilter`（OncePerRequestFilter）拦截 `/open-api/**` 与 `/ws/**`：校验 Key 哈希命中 + 启用 + 绑定关系；失败 401；限流超限 429（带 `Retry-After`）。
- **分级闸门**：生成 API 前经 governance-api 校验表敏感度——机密表禁止（任何角色）；内部表默认禁止、超管可开白（governance 侧 `api_exempted` 字段，T6）。

### D-D4：限流 = Redis 滑动窗口（Key × API 取小）+ 熔断 = Resilience4j 计数

- **限流**：粒度 = `Key × API`（Key 级总 QPS + 每 API 级 QPS 取小）；Redis `ZSET` 滑动窗口（`ratelimit:{keyId}:{apiId}` 存时间戳，`ZCARD` 计数 + `ZREMRANGEBYSCORE` 清过期）；超限 429 + `Retry-After` 头。已有 middleware-redis 与 `StringRedisTemplate` 用法可复用。
- **熔断**：数据服务对目标数据源查询的 Resilience4j `CircuitBreaker`（按数据源维度）；连续失败（如查询超时）开闸返回 503「数据源暂不可用」，半开探测通过自动闭合（复用既有 Resilience4j 语义与依赖）。

### D-D5：WebSocket 订阅端点 = 数据服务原生 WebSocket（网关放行握手）

- **端点**：`/api/data-service/ws/events`（Spring MVC `TextWebSocketHandler` + `spring-boot-starter-websocket`）；网关 WebFlux 对 `ws://` 握手请求同样走 `/api/data-service/**` 路由放行（Spring Cloud Gateway 原生支持 WebSocket 路由转发）。
- **连接管理**：握手时校验 `X-API-Key` 头（Key 启用 + 绑定该管道 = 订阅权，T7）；连接后 `subscribe` 消息绑定 `pipelineId`；`WebSocketSubscriptionRegistry`（`Map<pipelineId, Set<WebSocketSession>>`）fan-out 分发；心跳 60s ping/pong；空闲 120s 断开。
- **与 Kafka 的关系**：数据服务 `KafkaEventConsumer` 消费 `cdc-events` topic → 按事件 `pipelineId` 路由到订阅连接；无订阅的管道事件直接丢弃（无落库，PRD NG9）。

### D-D6：变更事件捕获 = Kafka 事件总线（事件管道分离 + Kafka sink → 数据服务消费）

> **用户确认（2026-08-12 定稿，M0 拍板 q-0/q-1/q-2/q-3）**：引入 Kafka 事件总线，规格 DS-05 完整语义（行级变更）。
>
> **⚠️ M0 关键结论（2026-08-12 反编译 `flink-cdc-composer-3.6.0-2.2.jar` 铁证）**：Flink CDC 3.6.0-2.2 的 `PipelineDef.sink` 是**单个 `SinkDef`**（非 List），`YamlPipelineDefinitionParser` 仅一个 `toSinkDef` 单值解析——**YAML 不支持多 sink 双写**（多 sink 是 3.6 之后版本特性，master 文档已展示 `sink:` 列表 + route 带 `sink:` 字段）。原「Iceberg + Kafka 双写」方案**废弃**，改 **方案 B 事件管道分离**（用户 q-0 拍板）。

- **架构（方案 B：事件管道分离）**：每个可订阅管道在 Iceberg 主管道**之外**再维护一个独立的 **Kafka 事件作业**（单 sink: kafka，`scan.startup.mode: latest-offset` 仅增量）→ 每管道专属 topic `cdc-events-{pipelineId}` → 数据服务 `KafkaEventConsumer` → 按 pipelineId WebSocket fan-out。两作业并行、互不影响，官方完全支持单 sink kafka。
- **作业生命周期（q-1 拍板）**：**管道创建即建、与 Iceberg 主管道同生命周期**。管道启动/停止/删除时同步启动/停止/删除事件作业；Kafka 无订阅者时事件直接丢弃（NG9 不落库，不空转资源）。
- **资源与冲突错开**：事件作业额外占 1 个 TaskManager slot（JM/TM 内存需评估扩容）；MySQL binlog **server-id 用独立区间**（主管道 5400+，事件作业 6400+，错开避免并发干扰）；PG 需额外复制槽（slot 名 `datanest_cdc_ev_{pipelineId}`）。
- **Kafka 部署（q-2 拍板）**：新增 `middleware-kafka` 容器，官方 **`apache/kafka:4.0.x`**（纯 KRaft 单节点，compose 挂 datanest-net，不暴露宿主端口或仅内部）。Topic `cdc-events-*`（分区 1，保留 7d，`log.retention.bytes` 限容；每管道专属 topic，无跨管道串扰）。
- **Flink 侧**：集群 lib 增补 **`flink-cdc-pipeline-connector-kafka:3.6.0-2.2`**（Maven Central 已确认存在，jar 内 shade 重定位 `flink-connector-kafka`，**无需额外单独引 `flink-connector-kafka`**）；`CdcYamlBuilder.build` 新增**独立事件 YAML 组装**（`CdcEventYamlBuilder` 或 build 重载）：source 复用 + `sink: {type: kafka, properties.bootstrap.servers, topic: cdc-events-{pipelineId}, value.format: debezium-json}`。
- **事件格式**：Kafka 消息为 Debezium JSON（`{before, after, op, source:{db, table}}`，ts_ms 在 source 外）；数据服务消费端归一化为订阅事件格式（PRD §6.6：`pipelineId/table/opType/data/ts`，pipelineId 从 topic 名解析）。
- **订阅语义（q-3 拍板）**：**仅增量推送**——事件作业 `latest-offset` 从订阅者订阅时刻起推送后续 INSERT/UPDATE/DELETE，历史变更不推送。
- **端到端延迟**：CDC 捕获 → Kafka → WebSocket 分发，目标 P95 < 10s（规格 RC-05）。

### D-D7：数据分级分类 = governance 打标 + 数据服务三端闸门

- **分级字段**：governance `metadata_table` 加 `sensitivity_level`（`PUBLIC/INTERNAL/CONFIDENTIAL`，默认 PUBLIC）+ `api_exempted boolean`（内部表超管开白，T6）。`asset_classification`（数据域/主题业务分类）不动。
- **打标入口**：governance 新增分级端点（改级/批量/审计查询），权限治理员/超管；前端分级页在数据服务菜单下但调 governance API。
- **审计**：新建 `sensitivity_change_log`（table_id/old_level/new_level/operator_id/created_at）；项目无通用审计体系（规格 Sprint 12 才做），本期独立轻量表。
- **数据服务闸门**：
  - **SQL 终端**：执行前 JSqlParser 提取 SQL 引用的表集合 → 经 governance-api 批量查敏感度（按 datasource/database/schema/table 匹配）→ 命中机密表即拦截（T5：默认隐藏 + 命中拦截）；表选择器/元数据树经 governance-api 读表清单时**过滤机密表**。
  - **API 生成**：创建/编辑前校验表敏感度（机密禁止；内部需开白）。
  - **WebSocket 订阅**：Key 绑定管道时校验管道目标表敏感度（机密管道不可订阅，T5）。

### D-D8：调用统计 = 异步写入 + 聚合查询

- **采集**：OpenApiKeyFilter 记录调用事件（apiId/keyId/statusCode/durationMs/ts）→ 内存队列 → 异步批量写入 `api_call_log`（不阻塞 API 主链路，PRD NAC-6）。
- **聚合**：查询时按范围（24h/7d/30d）SQL 聚合（count/avg/P95/errorRate）；明细分页查 `api_call_log`；保留 30 天定时清理。

---

## 2. 领域模型

```
dataservice 域（datanest_dataservice 库，V1.0.0）
├── sql_query_history     （查询历史：user_id/sql_text/datasource_id/duration_ms/row_count/created_at）
├── data_api              （API 定义：name/path/method/table_id(metadata)/params_json/status/created_by…）
├── api_key               （Key：name/key_hash/qps_limit/status/created_by…）
├── api_key_binding       （Key-API 绑定：key_id/api_id）
├── api_key_pipeline      （Key-管道订阅授权：key_id/pipeline_id，T7）
├── api_call_log          （调用统计明细：api_id/key_id/status_code/duration_ms/created_at）
└── (无 updated_at 默认值，审计字段约定对齐)

governance 域（datanest_governance 库，V1.6.0）
├── metadata_table        （已有，加 sensitivity_level + api_exempted）
├── sensitivity_change_log（新增：分级变更审计）
└── asset_classification  （不动：数据域/主题业务分类）
```

> 新库表统一：id `bigint` + `@TableId(IdType.ASSIGN_ID)`；`updated_at` 不加 DB 默认值（审计约定）；紧凑单行迁移脚本。

---

## 3. 数据模型设计

### 3.0 迁移脚本与版本规划

| 库 | 脚本 | 内容 |
|----|------|------|
| `datanest_dataservice`（新库，基线） | `V1.0.0__baseline.sql` | 6 表（sql_query_history / data_api / api_key / api_key_binding / api_key_pipeline / api_call_log） |
| `datanest_dataservice` | `V1.0.1__sql_query_history_error_message.sql` | sql_query_history 加 error_message（失败 SQL 进历史） |
| `datanest_dataservice` | `V1.0.2__data_api_soft_delete.sql` | data_api 加 deleted 软删列；path 唯一约束改部分唯一索引（deleted=0）；params_json 注释升级为完整定义对象 |
| `datanest_governance`（现最高 V1.5.0） | `V1.6.0__sprint10_sensitivity.sql` | metadata_table 加 sensitivity_level + api_exempted；新建 sensitivity_change_log |

### 3.1 dataservice `V1.0.0__baseline.sql`（核心表）

```sql
CREATE TABLE IF NOT EXISTS public.data_api (
    id bigint NOT NULL, name character varying(100) NOT NULL, path character varying(200) NOT NULL,
    method character varying(10) DEFAULT 'GET'::character varying NOT NULL,
    datasource_id bigint NOT NULL, database_name character varying(100) NOT NULL,
    schema_name character varying(100), table_name character varying(100) NOT NULL,
    metadata_table_id bigint, params_json text, order_by character varying(100),
    paginated smallint DEFAULT 1 NOT NULL, page_size_max integer DEFAULT 100 NOT NULL,
    status character varying(20) DEFAULT 'CREATED'::character varying NOT NULL,
    created_by bigint, updated_by bigint, created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone, CONSTRAINT data_api_pkey PRIMARY KEY (id),
    CONSTRAINT uk_data_api_path UNIQUE (path));
COMMENT ON TABLE public.data_api IS '数据 API 定义（Sprint 10 F2）：表级参数化查询 API';
COMMENT ON COLUMN public.data_api.params_json IS '参数化筛选/范围字段 JSON（[{field,type,operator}]）';
COMMENT ON COLUMN public.data_api.status IS 'CREATED 未发布 / PUBLISHED 可调用 / DISABLED 下线';

CREATE TABLE IF NOT EXISTS public.api_key (
    id bigint NOT NULL, name character varying(100) NOT NULL,
    key_hash character varying(64) NOT NULL, qps_limit integer DEFAULT 10 NOT NULL,
    status character varying(20) DEFAULT 'ENABLED'::character varying NOT NULL,
    created_by bigint, updated_by bigint, created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone, CONSTRAINT api_key_pkey PRIMARY KEY (id),
    CONSTRAINT uk_api_key_hash UNIQUE (key_hash));
COMMENT ON TABLE public.api_key IS 'API Key（SHA-256 哈希存储，明文仅创建时展示一次）';

CREATE TABLE IF NOT EXISTS public.api_key_binding (
    id bigint NOT NULL, key_id bigint NOT NULL, api_id bigint NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT api_key_binding_pkey PRIMARY KEY (id),
    CONSTRAINT uk_api_key_binding UNIQUE (key_id, api_id));
COMMENT ON TABLE public.api_key_binding IS 'Key-API 绑定';

CREATE TABLE IF NOT EXISTS public.api_key_pipeline (
    id bigint NOT NULL, key_id bigint NOT NULL, pipeline_id bigint NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT api_key_pipeline_pkey PRIMARY KEY (id),
    CONSTRAINT uk_api_key_pipeline UNIQUE (key_id, pipeline_id));
COMMENT ON TABLE public.api_key_pipeline IS 'Key-管道订阅授权（WebSocket 实时订阅，T7）';

CREATE TABLE IF NOT EXISTS public.api_call_log (
    id bigint NOT NULL, api_id bigint, key_id bigint, status_code integer NOT NULL,
    duration_ms integer, created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT api_call_log_pkey PRIMARY KEY (id));
CREATE INDEX IF NOT EXISTS idx_api_call_log_created_at ON public.api_call_log USING btree (created_at);
CREATE INDEX IF NOT EXISTS idx_api_call_log_api_time ON public.api_call_log USING btree (api_id, created_at);

CREATE TABLE IF NOT EXISTS public.sql_query_history (
    id bigint NOT NULL, user_id bigint NOT NULL, datasource_id bigint, sql_text text NOT NULL,
    duration_ms integer, row_count integer, created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT sql_query_history_pkey PRIMARY KEY (id));
CREATE INDEX IF NOT EXISTS idx_sql_query_history_user_time ON public.sql_query_history USING btree (user_id, created_at);
```

> 审计字段约定：新表 create 只设 `created_by/created_at`，`updated_at` 无 DB 默认值，仅真正 update 写入。

### 3.2 governance `V1.6.0__sprint10_sensitivity.sql`

```sql
ALTER TABLE public.metadata_table ADD COLUMN IF NOT EXISTS sensitivity_level character varying(20) DEFAULT 'PUBLIC'::character varying NOT NULL;
COMMENT ON COLUMN public.metadata_table.sensitivity_level IS '数据敏感度（Sprint 10 F5）：PUBLIC 公开 / INTERNAL 内部 / CONFIDENTIAL 机密，默认公开';
ALTER TABLE public.metadata_table ADD COLUMN IF NOT EXISTS api_exempted smallint DEFAULT 0 NOT NULL;
COMMENT ON COLUMN public.metadata_table.api_exempted IS '内部表生成对外 API 的超管强制开白标记（Sprint 10 F5，T6）；机密表恒为 0 不可开白';

CREATE TABLE IF NOT EXISTS public.sensitivity_change_log (
    id bigint NOT NULL, table_id bigint NOT NULL, table_name character varying(200) NOT NULL,
    old_level character varying(20), new_level character varying(20) NOT NULL,
    operator_id bigint NOT NULL, created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT sensitivity_change_log_pkey PRIMARY KEY (id));
COMMENT ON TABLE public.sensitivity_change_log IS '数据分级变更审计（Sprint 10 F5）：谁/何时/从哪级到哪级';
```

---

## 4. 核心流程

### 4.1 SQL 查询终端（F1）

```
前端 Monaco 编辑器 → POST /data-service/sql-console/execute（请求带前端生成 queryId，axios 超时 70s）
  ├─ JSqlParser 语法解析（只读校验：Select/SetOperation 放行，DML/DDL 拦截）
  ├─ 提取引用的表集合（visitor）→ governance-api 批量查敏感度
  │     └─ 命中 CONFIDENTIAL → 6xxx 拦截（T5：默认隐藏 + 命中拦截）；未命中返回 confidentialHits=0
  ├─ 数据源路由：内置 Doris → CancelableSqlExecutor.queryDoris（连接注册可取消）
  │               外部数据源 → EngineeringDatasourceApi.getById + 解密 + CancelableSqlExecutor.queryExternal（socketTimeout=请求超时，setQueryTimeout）
  ├─ 结果 ≤1000 行 → 返回 columns/rows/truncated + durationMs(int) + tableCount + confidentialHits
  ├─ 异步写 sql_query_history（不阻塞返回）
  └─ 停止（F1.1）：前端 AbortController.abort + POST /sql-console/cancel {queryId}
        → SqlQueryService 虚拟线程执行 + queryId→RunningQuery(Future+Connection) 注册表
        → future.cancel(true) 中断线程 + 关闭连接立即打断 JDBC 阻塞读取（比 setQueryTimeout 提前终止）
导出：**后端生成文件流**（2026-08-12 用户拍板：所有导出走后端）——`POST /sql-console/export`（format XLSX/CSV，复用 execute 全链路校验+执行+写历史）；XLSX 走 common `XlsxExportHelper`（自动列宽 + 表头加粗），CSV 走 common `CsvExportHelper`（UTF-8 BOM + RFC4180）；文件名 {数据源}_{首表}_{yyyyMMdd_HHmmss}，RFC5987 中文名
```

### 4.2 API 生成与对外调用（F2/F3）

```
创建 API（POST /data-service/apis）
  ├─ 校验表存在 + 敏感度（机密禁止 / 内部需 api_exempted 开白）
  └─ 生成 path（/open-api/v1/{id}）+ params_json + 文档

对外调用（GET /api/data-service/open-api/v1/{自定义path}，X-API-Key 头）
  └─ OpenApiKeyFilter：Key 哈希校验 → Key×API 限流（Redis ZSET）→ 熔断检查
       ├─ 构造 SQL（params 白名单绑定 + 分页 + orderBy 白名单）→ 执行
       ├─ 异步记 api_call_log
       └─ 超限 429 / 熔断 503 / 未发布 404
```

### 4.3 WebSocket 实时订阅（F4）

```
realtime：每可订阅管道的事件作业（CdcEventYamlBuilder，latest-offset 增量）— 方案 B
  └─ Kafka topic cdc-events-{pipelineId}（每管道专属，debezium-json）
       └─ data-service KafkaEventConsumer（spring-kafka @KafkaListener，offset=latest）
            └─ 按 topic 解析 pipelineId → 查 WebSocketSubscriptionRegistry → fan-out 事件 JSON
业务端：ws://…/api/data-service/ws/events（X-API-Key）→ 握手校验 → subscribe{pipelineId}
  ├─ 心跳 60s ping/pong；空闲 120s 断开
  └─ 管道删除 → 事件作业停止 + registry 移除 + 推送「管道已关闭」
```

### 4.4 数据分级闸门（F5）

```
改级（governance PUT /metadata/tables/{id}/sensitivity，治理员/超管）
  ├─ 校验：CONFIDENTIAL 不可降级到 PUBLIC 直达，必经 INTERNAL 两步（用户已确认）
  ├─ 更新 metadata_table.sensitivity_level
  └─ 写 sensitivity_change_log

数据服务消费（经 governance-api internal 端点批量读敏感度）
  ├─ SQL 执行前表集合校验 → 机密拦截
  ├─ API 创建校验 → 机密禁止 / 内部需开白
  └─ Key 绑定管道校验 → 机密管道不可订阅
  └─ ⚠️ governance 不可达时 fail-closed：无法确认敏感度则 SQL 执行与 API 创建默认拒绝（用户已确认）
```

---

## 5. 接口设计（Controller）

### 5.1 data-service 管理端（`/data-service/**`，Sa-Token 保护）

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| POST | `/sql-console/execute` | 执行只读 SQL（§4.1），返回 columns/rows/truncated + durationMs + tableCount + confidentialHits；请求带 queryId 支持停止 | 四角色 OR |
| POST | `/sql-console/cancel` | 停止查询（body `{queryId}`，幂等；中断线程 + 关闭连接） | 四角色 OR |
| GET | `/sql-console/history?page=&pageSize=` | 我的查询历史（分页） | 四角色 OR |
| DELETE | `/sql-console/history` | 清空我的查询历史 | 四角色 OR |
| POST | `/apis` | 创建 API（校验表敏感度） | 超管/工程师 |
| GET | `/apis/page` | API 列表（分页，我的/全量） | 四角色 OR |
| GET | `/apis/{id}` | API 详情（含参数/文档） | 四角色 OR |
| PUT | `/apis/{id}` | 编辑 API | 超管/工程师 |
| POST | `/apis/{id}/publish` | 发布 | 超管/工程师 |
| POST | `/apis/{id}/disable` | 下线 | 超管/工程师 |
| DELETE | `/apis/{id}` | 删除（软删） | 超管/工程师 |
| GET | `/apis/{id}/stats?range=` | 单 API 调用统计（24h/7d/30d：KPI + 调用量趋势 + 今日小时分布 + Key 排行 + 错误码三档 + 最近明细；2026-08-13 F3 前端会话补 `hourly`/`topKeys`/`statusBreakdown`） | 四角色 OR |
| GET | `/apis/summary` | API 汇总（列表页统计卡：已发布/待发布/已下线计数 + 近 7 天总调用；2026-08-12 F2 前端会话补） | 四角色 OR |
| GET | `/stats/overview?range=` | 全局 KPI 聚合（总调用/成功率/P95/限流命中，API 运行统计页） | 四角色 OR |
| GET | `/stats/trend?range=` | 全局调用量趋势（双线：调用量 + 失败数，标注峰值） | 四角色 OR |
| GET | `/stats/health-distribution?range=` | API 健康分布（健康/警告/严重占比 + 平台综合健康分） | 四角色 OR |
| GET | `/stats/top-apis?range=&limit=` | Top N API 调用排行（名称 + 路径） | 四角色 OR |
| GET | `/stats/error-codes?range=&limit=` | 错误码分布（4xx/5xx 占比 + 错误码 TopN，429 突出） | 四角色 OR |
| GET | `/stats/top-keys?range=&limit=` | 调用方 Key 排行（含近 7 天 0 调用僵尸 Key） | 四角色 OR |
| GET | `/stats/rate-limit-trend?range=` | 限流命中趋势（时间范围柱状） | 四角色 OR |
| POST | `/api-keys` | 创建 Key（返回明文一次） | 超管/工程师 |
| GET | `/api-keys/page` | Key 列表（含近 7 天调用聚合，识别僵尸 Key） | 四角色 OR |
| GET | `/api-keys/{id}` | Key 详情（编辑弹窗预填当前绑定 apiIds；明文不回传；2026-08-12 F2 前端会话补） | 四角色 OR |
| PUT | `/api-keys/{id}` | 编辑（改名/qps/绑定） | 超管/工程师 |
| POST | `/api-keys/{id}/disable` | 禁用 | 超管/工程师 |
| POST | `/api-keys/{id}/enable` | 启用（快捷启用，操作列一步恢复） | 超管/工程师 |
| DELETE | `/api-keys/{id}` | 删除 | 超管/工程师 |
| GET | `/ws-docs` | 订阅文档数据（地址/协议/示例） | 四角色 OR |

### 5.2 data-service 对外（`/open-api/**`、`/ws/**`，网关放行登录态，OpenApiKeyFilter 校验）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/open-api/v1/{自定义path}` | 对外数据 API（X-API-Key 认证 + 限流 + 熔断 + 统计） |
| WS | `/ws/events` | WebSocket 实时订阅（握手校验 X-API-Key） |

### 5.3 governance 新增（internal + 分级端点）

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | `/governance/internal/metadata/tables/sensitivity?datasourceId=&database=&schema=&tables=` | 批量查表敏感度（供数据服务 SQL/API 闸门） | internal（X-Internal-Token） |
| GET | `/governance/internal/metadata/tables?datasourceId=&database=&schema=` | 表清单（含敏感度，SQL 终端表选择器；数据服务侧过滤机密） | internal |
| PUT | `/governance/metadata/tables/{id}/sensitivity` | 改级（分级管理页） | 治理员/超管 |
| POST | `/governance/metadata/tables/sensitivity/batch` | 批量改级 | 治理员/超管 |
| PUT | `/governance/metadata/tables/{id}/api-exempt` | 内部表 API 开白（超管） | 超管 |
| GET | `/governance/metadata/sensitivity/audit?page=&pageSize=` | 分级变更审计查询 | 治理员/超管 |

> governance-api 新增 `GovernanceMetadataApi` 契约（internal 表清单+敏感度批量，fallback 降级——数据服务读路径 fail-open 但**敏感度校验 fail-closed**：governance 不可达时 SQL 执行默认放行还是拦截需定，见 §8）。

### 5.4 realtime 改动（无新端点，事件 YAML 生成器 + 作业联动）

> **M0 定稿（2026-08-12）**：多 sink 双写不支持，改**事件管道分离**（方案 B）。realtime 为每个可订阅管道生成独立 Kafka 事件 YAML，并联动管理第二个 Flink 作业。

- 新增 `CdcEventYamlBuilder`（或 `CdcYamlBuilder.buildEvent`）：source 段复用，sink 段为单 Kafka（`type: kafka` + `properties.bootstrap.servers` + `topic: cdc-events-{pipelineId}` + `value.format: debezium-json`），`scan.startup.mode: latest-offset`（仅增量，q-3）。
- `CdcPipelineService.start/stop/delete` 联动：除 Iceberg 主管道外，同步启动/停止/删除事件作业（`cdc_events_flink_job_id` 字段，与主管道同生命周期，q-1）；失败不阻断主管道、记录事件作业 last_error。
- MySQL binlog **server-id 独立区间**（事件作业 6400+，主管道 5400+ 错开）；PG 额外复制槽 `datanest_cdc_ev_{pipelineId}`。
- `CdcMonitorService` 监控轮询同时覆盖事件作业（状态/吞吐/错误，失败降级不影响主管道指标）。

---

## 6. 权限矩阵映射

| 端点/操作 | 超管 | 工程师 | 分析师 | 治理员 |
|-----------|:----:|:------:|:------:|:------:|
| SQL 终端（执行/导出/历史） | ✅ | ✅ | ✅ | ✅ |
| 机密表 SQL 拦截 | ✅ | ✅ | ❌ | ✅（分级页可见） |
| API 查看（列表/详情/统计/文档） | ✅ | ✅ | ✅ | ✅ |
| API 创建/发布/下线/删除 | ✅ | ✅ | ❌ | ❌ |
| API Key 管理 / 限流配置 | ✅ | ✅ | ❌ | ❌ |
| 内部表 API 开白（超管） | ✅ | ❌ | ❌ | ❌ |
| 分级打标/改级/审计查询 | ✅ | ❌ | ❌ | ✅ |
| 订阅文档查看 | ✅ | ✅ | ✅ | ✅ |
| WebSocket 实际订阅（持 Key） | ✅ | ✅ | 依 Key 授权 | 依 Key 授权 |

> 对齐 PRD §8：SQL 终端四角色；API 写=超管/工程师、查看=+分析师；分级写=治理员/超管、查看全角色；开白仅超管（T6）。

---

## 7. 配置项与部署

### 7.1 配置项（Nacos shared-configs + 新服务配置）

**新增 `shared-dataservice.yaml`**（数据服务引入；改后需重启 app-data-service）：

```yaml
datanest:
  dataservice:
    sql:
      query-timeout-seconds: 60        # SQL 查询超时（F1）
      max-rows: 1000                   # 结果上限（对齐 DorisSqlExecutor）
    ratelimit:
      window-seconds: 60               # Redis 滑动窗口
    circuitbreaker:
      failure-threshold: 5             # 熔断连续失败阈值
      wait-seconds: 30                 # 半开探测等待
    history:
      retention-days: 30               # 查询历史/调用统计保留
kafka:
  bootstrap-servers: ${KAFKA_HOST:middleware-kafka}:${KAFKA_PORT:9092}
```

**`shared-datasource.yaml`**：数据服务作为持库服务引入（PG_DATABASE=datanest_dataservice，fail-fast）。

**网关 `SaTokenConfig.addExclude` 追加**：`/api/data-service/open-api/**`、`/api/data-service/ws/**`（对外放行登录态）。

**Flink 集群**：`docker/flink/Dockerfile` lib 增补 `flink-cdc-pipeline-connector-kafka` + `flink-connector-kafka`（版本 M0 锁定）；compose `FLINK_PROPERTIES` 不需要新键（Kafka 走 YAML sink 配置）。

### 7.2 依赖与构建变更

| 模块 | 变更 |
|------|------|
| 根 `pom.xml` | `dependencyManagement` 新增：`com.github.jsqlparser:jsqlparser`（对齐 mybatis-plus-jsqlparser 传递线，实现时以 Maven Central 为准）、`org.springframework.kafka:spring-kafka`（Boot BOM 管版本）、data-service 模块；services pom 增模块 `data-nest-data-service` |
| `data-nest-data-service`（新） | common + task-core（DorisSqlExecutor/DorisDataSourceConfig）+ engineering-api + governance-api + system-api + realtime-api + jsqlparser + spring-boot-starter-websocket + spring-kafka + springdoc + 三件套（loadbalancer/validation） |
| `data-nest-governance-api` | 新增 `GovernanceMetadataApi` 契约（表清单+敏感度批量，fallback） |
| `data-nest-governance` | V1.6.0 迁移 + 分级端点 + internal 表清单/敏感度端点 |
| `data-nest-realtime` | `CdcYamlBuilder` Kafka sink 段（多 sink 双写，M0 验证） |
| 根 `pom.xml` + compose | 新增 `middleware-kafka` 容器；Flink Dockerfile lib 增 jar |

> 服务新增 = 新容器 `app-data-service`（compose + Dockerfile 复制 realtime 模板）；网关路由 + swagger urls + `SaTokenConfig` 放行；`datanest_dataservice` 库由 compose 初始化。

### 7.3 部署步骤

1. `mvn clean package -DskipTests -q`（common → api → task-core → 各服务全量，含新模块）
2. `docker compose build app-data-service app-governance app-realtime middleware-kafka middleware-flink-jobmanager` + `up -d`
3. Nacos 发布 `shared-dataservice.yaml`；网关 `SaTokenConfig` 放行（重建 app-gateway）
4. Flyway 自动应用：dataservice V1.0.0、governance V1.6.0
5. Flink 集群 lib 增补 kafka connector jar → 重启 Flink 集群
6. 验证：见 §10

---

## 8. 已知 Blocker 与待确认点

| # | 事项 | 说明 | 状态 |
|---|------|------|------|
| 1 | **Flink CDC 3.6 YAML 多 sink 双写**（iceberg + kafka） | **M0 已定（2026-08-12）**：3.6.0-2.2 `PipelineDef.sink` 单 `SinkDef`，**不支持多 sink**；改**方案 B 事件管道分离**（每可订阅管道独立 Kafka 单 sink 事件作业） | ✅ M0 定稿（q-0） |
| 2 | **Kafka 版本与部署形态** | **M0 已定（2026-08-12）**：官方 `apache/kafka:4.0.x` 纯 KRaft 单节点；Kafka 客户端向后兼容 ≥0.10 broker，无兼容风险；仅需 lib 增补 `flink-cdc-pipeline-connector-kafka:3.6.0-2.2`（shade 含 flink-connector-kafka，无需单独引） | ✅ M0 定稿（q-2） |
| 3 | **governance 不可达时敏感度校验策略** | **fail-closed 已确认（2026-08-12）**：数据服务无法确认表敏感度时，SQL 执行与 API 创建默认拒绝并提示「分级服务暂不可用」——保护敏感数据不因治理服务故障而泄漏 | ✅ 已确认 |
| 4 | **机密表改级降级是否需两步** | **两步已确认（2026-08-12）**：CONFIDENTIAL→PUBLIC 必经 INTERNAL，防一步误操作机密裸奔 | ✅ 已确认 |
| 5 | **API 对外路径形态** | **已定（2026-08-12，F2 实现时用户拍板）**：**自定义路径**（原型=实现基准），`data_api.path` 存完整路径 `/open-api/v1/{自定义段}`，未删除行部分唯一索引；段规则 `^[a-z0-9][a-z0-9-_]{0,99}$`，输入三种形态（`orders`/`/orders`/完整路径）统一归一 | ✅ 已定 |
| 6 | **Kafka 消费端 offset 语义** | **已定（2026-08-12，q-3）**：`latest`（仅增量推送，订阅时刻后事件；无历史重放，PRD NG9） | ✅ 已定 |
| 7 | **查询历史/调用统计清理** | **已定（2026-08-12，用户拍板）**：**业务服务本地禁止 `@Scheduled`**，定时清理全部放 **app-job**（PowerJob cron）——清理逻辑下沉 data-service `/internal/**` 端点（经 data-service-api Feign 触发），job 新增 `sqlHistoryCleanupHandler`；规范已写入 `docs/agent/conventions-backend.md` §7「定时任务规范」 | ✅ 已定并实现 |
| 8 | **X-API-Key 头名与文档** | `X-API-Key`（对齐规格示例），CORS allowed-headers 需补（shared-security.yaml 当前无此项） | 实现定 |

---

## 9. 实现清单（P0）

### 后端

- [x] **新服务骨架**：`data-nest-services/data-nest-data-service`（启动类 scanBasePackages 约定 + FlywayConfig + 三件套依赖）+ `app-data-service` Dockerfile + compose + 网关路由 + swagger urls + `SaTokenConfig` 放行 open-api/ws（已部署 `datanest-app-data-service` healthy）
- [x] **dataservice 库** `V1.0.0__baseline.sql`（6 表，已 Flyway v1.0.0 建表）
- [x] SQL 终端：`SqlQueryController`（execute/history/clear/datasources/**export**）+ `ReadOnlySqlValidator`（JSqlParser 语法级）+ `SqlQueryService`（Doris/外部数据源双路径 + 超时 + 表集合敏感度校验 + fail-closed）——**API 自测 17 用例全通过**；export 复用执行链路（只读校验+敏感度闸门+写历史），XLSX/CSV 后端生成实测通过
- [x] **导出统一走后端（2026-08-12 用户拍板）**：`XlsxExportHelper` 下沉 common（`com.datanest.common.util`，自动列宽+表头加粗）+ 新增 `CsvExportHelper`（UTF-8 BOM + RFC4180）；common 以 compile scope 提供 poi-ooxml（版本根 pom 管理），governance/data-service 各自冗余声明已移除；governance 3 个导出 + data-service SQL 终端导出全部经 common 工具
- [x] **F1.1 停止查询（2026-08-12）**：`POST /sql-console/cancel`（body `{queryId}` 幂等，四角色）+ `SqlExecuteRequest.queryId` + 虚拟线程执行 + `queryId→RunningQuery(Future+Connection)` 注册表（cancel=interrupt+关连接立即打断）；`SqlExecuteResult` 加 `tableCount`/`confidentialHits`（前端 KPI）、`durationMs` long→int（避免 Long 字符串化）；`ExternalSqlExecutor.buildJdbcUrl` socketTimeout 参数化（默认 10s 同步任务不变，SQL 终端用请求级超时）；新增 `CancelableSqlExecutor`（data-service 内，含 Doris+外部，复用 buildJdbcUrl/formatValue，**未改 task-core**）——实测 30s pg_sleep 3s 被 cancel 中断返回 9003
- [x] common：新增 ErrorCode 段（数据服务 9xxx：SQL 只读拦截/Key 无效/限流/API 未发布/表敏感度等）
- [x] governance 依赖项：V1.6.0 迁移（metadata_table 加 sensitivity_level/api_exempted + sensitivity_change_log）+ internal `GovernanceMetadataController`（表清单+敏感度批量）+ governance-api `GovernanceMetadataApi` 契约（fallback fail-closed 已生效）——**SensitivityController 改级/批量/开白/审计仍归 F5**
- [x] **定时清理规范落定**：业务服务本地禁 `@Scheduled`（写入 conventions-backend §7）；新增 data-service-api `DataServiceOpsApi` 契约 + data-service `/internal/sql-history/cleanup` + job `SqlHistoryCleanupHandler`（已注册 PowerJob jobId=293，cron `0 50 3 * * ?`）
- [x] API 定义：`DataApiController` + `DataApiService`（CRUD/发布/下线 + 敏感度校验）+ `data_api`/`api_key`/`api_key_binding`/`api_key_pipeline` 实体 Mapper——**F2 已完成并部署（2026-08-12）**：自定义路径归一/查重（V1.0.2 软删 + path 部分唯一索引）、params_json 定义对象（filters EQ/RANGE + fields 白名单，标识符/排序严格白名单防注入）、自动文档（参数说明 + curl 示例）、`ApiKeyController`（K- 明文一次 + SHA-256 哈希 + 绑定/快捷启停/近 7 天调用聚合识别僵尸 Key）；API 自测 45 用例全通过（含敏感度 9004 三门路、权限 403、软删路径复用）
- [x] API 网关：`OpenApiController`/`OpenApiService`（对外执行 `GET /open-api/v1/{path}`，状态校验/熔断/参数化 SQL 执行/分页 COUNT，**HTTP 状态码语义** 401/404/429/503/200）+ `OpenApiKeyFilter`（Key 哈希校验 + 绑定 + 限流）+ `RateLimitService`（Redis ZSET 滑动窗口，Key 级 QPS）+ `CircuitBreakerService`（Resilience4j 数据源维度）+ `ApiCallLogWriter`（异步队列写 api_call_log）+ `OpenApiSqlBuilder`（参数化 SQL，参数值类型启发式推断）+ `CancelableSqlExecutor` 扩展 PreparedStatement 路径——**F3 已完成并部署（2026-08-13），自测全通过**
- [x] 全局统计：`StatsController`（`/stats/*` 7 端点）+ `StatsQueryService`（api_call_log 聚合 percentile_cont/FILTER）+ `DataApiController` 补 `/apis/{id}/stats`；健康分级对齐告警 PASS/WARNING/SEVERE（错误率≥5% 或 P95≥1000ms=SEVERE；错误率≥1% 或 P95≥500ms 或限流≥5%=WARNING），综合分 PASS100/WARNING60/SEVERE20 平均——**F3 已完成并部署（2026-08-13）**
- [x] WebSocket：`WsEventsHandler`（TextWebSocketHandler + 握手 Key 校验 + subscribe/unsubscribe）+ `WsHandshakeInterceptor`（X-API-Key SHA-256，401 拒连）+ `WebSocketSubscriptionRegistry`（双向索引 fan-out）+ `KafkaEventConsumer`（spring-kafka @KafkaListener topicPattern 消费 `cdc-events-*`，Debezium→归一化）+ `WsSubscriptionService`（Key 绑定 9005 / 管道 RUNNING 9016 / 机密表 9004 / 治理不可达 9012 fail-closed）+ `WebSocketConfig`（`/ws/events` + @EnableKafka）——**F4 已完成并部署（2026-08-13），分层自测全通过**
- [ ] common：新增 ErrorCode 段（数据服务 9xxx：SQL 只读拦截/Key 无效/限流/API 未发布/表敏感度等）
- [x] governance：V1.6.0 迁移（metadata_table 加 sensitivity_level/api_exempted + sensitivity_change_log）+ internal `GovernanceMetadataController`（表清单+敏感度批量）+ governance-api `GovernanceMetadataApi` 契约 + `SensitivityController`（改级/批量/开白/审计 + 分级列表分页）+ `SensitivityService`（机密降级两步 4012 / 开白仅 INTERNAL / 审计 action 区分）+ Flyway V1.7.0（sensitivity_change_log 加 action/remark）+ common 4011/4012——**F5 已完成并部署（2026-08-13），自测全通过**
- [x] realtime：`CdcYamlBuilder.buildEvent`（Kafka 单 sink 事件 YAML，latest-offset 增量，MySQL server-id 6400+ / PG 复制槽 `datanest_cdc_ev_` 错开主管道）+ `CdcPipelineService.start/stop/forceStop` 事件作业联动（best effort 失败不阻断主管道）+ `CdcMonitorService.pollEventJobs` 覆盖事件作业（FAILED/404/外部停止清字段）+ `CdcPipeline.cdc_events_flink_job_id`（Flyway V1.4.0）+ `FlinkJobService.cancelJob`（PATCH /jobs/{id} 不做 savepoint）+ realtime-api `getSubscribeInfo`（管道状态+源表清单）——**F4 已完成并部署（2026-08-13）**
- [x] **Kafka 中间件**：compose `middleware-kafka`（`apache/kafka:4.0.0` KRaft 单节点，9092，topic 保留 7d，仅内网）+ Flink lib 增 `flink-cdc-pipeline-connector-kafka:3.6.0-2.2` + TaskManager `numberOfTaskSlots` 1→2（事件作业额外占 1 slot）——**F4 已完成并部署（2026-08-13）**

### 前端

- [x] 路由 `/data-service/*` + Sidebar「数据服务」菜单组（全角色，**F1 仅 SQL 查询终端一项，F2/F3/F5 完成后再补**）
- [x] SQL 终端页（产品化改版，紧凑 IDE 风格）：左侧 `SqlTree` 数据目录（sql-console 全部 NORMAL 数据源 + 元数据域库/表懒加载，内置 Doris 显示「Doris 数仓」+多库，未采集数据源「去采集」提示）+ 面包屑路径显示到表级（不显示 id）+ 点表插入 `SELECT * FROM 库.表 LIMIT 100` + Monaco（Ctrl+Enter）+ 运行/停止（AbortController+cancel 双管齐下）+ 结果表/KPI 紧凑化 + 导出 CSV/Excel + 查询历史 Drawer（按钮+Badge+回填/清空）——已部署 app-frontend
- [x] API 管理页：列表/详情（文档+统计）/新建向导（3 步，含 API 预览）/Key 管理（一次性明文展示 + 近 7 天调用列 + 快捷禁用/启用）——F2 已完成并部署（handoff §20）
- [x] API 运行统计页：全局 KPI / 双线趋势 / 健康分布 / Top5 排行 / 错误码分布 / Key 排行 / 限流趋势 / 状态速览（`/stats/*`）——F3 已完成并部署（handoff §1.1）
- [ ] 数据分级分类页：敏感度筛选 + 批量打标 + 审计查询（调 governance API）
- [ ] 资产详情页：敏感度标签 + 「去查询」/「生成 API」入口
- [x] CDC 管道详情：「实时订阅」页签（订阅文档 + 连接监控；连接监控后端补 `GET /subscriptions/{pipelineId}/stats` + 内存埋点）——F4 已完成并部署（handoff §24）
- [x] `types/data-service.ts` + `api/data-service.ts`（含 `/stats/*` + `/apis/{id}/stats`）；`types/metadata.ts` 补 sensitivityLevel

### 部署与验证

- [ ] Nacos 发布 `shared-dataservice.yaml` + 网关放行 + Kafka 起容器 + Flink lib 增 jar
- [x] E2E：`e2e/sprint10/e2e/` F1 SQL 终端（sql-console）+ F2 API 管理/Key（api-manage 24 + api-keys 11）+ F3 对外网关/限流/熔断/统计（open-api 18 + api-stats 4，本会话）全通过；F5 分级拦截 / F4 WebSocket 订阅 E2E 待后续；回归 sprint8/9 关键规格待补
- [ ] M0 验证记录回落本文档 §8

### 9.1 新增错误码（common `ErrorCode`，建议 9xxx 段）

| 码 | 常量 | 语义 |
|----|------|------|
| 9001 | SQL_NOT_READ_ONLY | SQL 非只读语句，禁止执行 |
| 9002 | SQL_SYNTAX_ERROR | SQL 语法错误 |
| 9003 | SQL_TIMEOUT | 查询超时中断 |
| 9004 | TABLE_SENSITIVE | 表为机密/内部敏感级，禁止操作 |
| 9005 | API_KEY_INVALID | API Key 无效/禁用/未绑定 |
| 9006 | API_RATE_LIMITED | 请求超限（429） |
| 9007 | API_NOT_PUBLISHED | API 未发布或已下线 |
| 9008 | API_NOT_FOUND | API 不存在 |
| 9009 | API_KEY_NAME_EXISTS | Key 名称已存在 |
| 9010 | API_PATH_EXISTS | API 路径已存在 |
| 9011 | API_EXEMPT_NOT_ALLOWED | 机密表不可开白 / 非超管不可开白 |
| 9012 | SENSITIVITY_SERVICE_UNAVAILABLE | 分级服务暂不可用（fail-closed） |
| 9013 | API_DEFINITION_INVALID | API 定义参数非法（路径/筛选/字段/排序白名单校验失败） |
| 9014 | API_KEY_NOT_FOUND | API Key 不存在 |
| 9015 | API_CIRCUIT_OPEN | 数据源暂不可用（熔断开闸，503） |
| 9016 | API_PIPELINE_UNAVAILABLE | CDC 管道不可订阅（不存在或未运行） |
| 4011 | SENSITIVITY_LEVEL_INVALID | 敏感度级别非法（仅 PUBLIC/INTERNAL/CONFIDENTIAL） |
| 4012 | CONFIDENTIAL_DOWNGRADE_FORBIDDEN | 机密表不可直接降级为公开，需先降为内部 |

> 具体段号以实现时 common `ErrorCode` 实际布局为准（当前已用 2xxx~8xxx）。

---

## 10. 验收口径映射（PRD AC）

| PRD AC | 验证方式 |
|--------|----------|
| AC-1 SQL 只读 | 内置 Doris 执行 SELECT/JOIN 成功；INSERT/UPDATE/DDL 被 JSqlParser 拦截（含注释/子查询绕过用例） |
| AC-2 超时截断 | `SELECT SLEEP(120)` 60s 中断；>1000 行提示截断 |
| AC-3 导出 | 结果导出 CSV/Excel 中文正常、文件名规范 |
| AC-4 查询历史 | 按用户留存、回填、30 天清理 |
| AC-5 API 创建 | 公开表生成成功、参数化/分页生效、文档可预览；机密表被 9004 拦截 |
| AC-6 Key 认证 | 无/错/禁用 Key 401；正确 Key 200 |
| AC-7 限流 | Key QPS=5 第 6 次 429 + Retry-After；恢复窗口后可调 |
| AC-8 熔断 | 数据源不可用连续失败 503，恢复后闭合 |
| AC-9 调用统计 | 统计聚合与调用行为一致（api_call_log 抽样比对） |
| AC-10 WebSocket | 订阅运行管道 10s 内收到变更；无 Key/未绑定拒连 |
| AC-11 分级拦截 | 机密表 SQL 拦截/API 置灰/管道不可订阅；内部表可查、API 需开白 |
| AC-12 分级审计 | 改级（含开白）写 sensitivity_change_log |
| AC-13 资产联动 | 详情页敏感度标签 + 去查询/生成 API 带表跳转 |
| NAC-3 回归 | sprint8/9 关键 E2E 保持通过（CDC 23 用例 + 资产 + 质量报告） |

---

> **版本记录**
> - v1.0 (2026-08-12)：初始版本。技术决策全部经用户确认（Kafka 事件总线 / JSqlParser 语法级只读 / 数据服务原生 WebSocket / datanest_dataservice 库）；8 个 ADR 对齐 PRD T1~T8；代码改动点经源码核验（SaTokenConfig 全路径鉴权、EngineeringDatasourceApi/DataSourceInfo、EncryptionConfig、JdbcPreviewHelper/JdbcSchemaExtractor、DorisDataSourceConfig/DorisSqlExecutor、CdcYamlBuilder、realtime pom 依赖集、根 pom、shared-datasource.yaml、前端 router/Sidebar/metadata.ts/Monaco）。**Blocker 1/2 为 M0 必测项**（Flink CDC 多 sink + Kafka 兼容坐标）；Blocker 3/4 待用户确认（governance 故障时敏感度 fail-closed / 机密降级两步）。
> - v1.1 (2026-08-12)：用户确认 Blocker 3（governance 不可达时敏感度校验 **fail-closed**）与 Blocker 4（机密表降级**必经 INTERNAL 两步**），已回落 §4.4 与 §8。
> - v1.2 (2026-08-12)：原型产品逻辑修正回落——§5.1 管理端新增全局统计端点组（`/stats/overview|trend|health-distribution|top-apis|error-codes|top-keys|rate-limit-trend`，服务「API 运行统计」页）；`/api-keys/page` 补近 7 天调用聚合字段；新增 `POST /api-keys/{id}/enable`（快捷启用）。实现清单 §9.1 错误码段（9001~9011）可复用，无需新增码。
> - v1.3 (2026-08-12)：**M0 技术调研定稿（D4）**——反编译 `flink-cdc-composer-3.6.0-2.2.jar` 证实 `PipelineDef.sink` 为单个 `SinkDef`，**Flink CDC 3.6 YAML 不支持多 sink 双写**；原「Iceberg+Kafka 双写」方案废弃，改 **方案 B 事件管道分离**（用户 q-0~q-3 拍板：每可订阅管道独立 Kafka 单 sink 事件作业 latest-offset 增量、管道创建即建同生命周期、`apache/kafka:4.0.x` KRaft、仅增量推送）。更新 D-D6/§0-F4/§4.3/§5.4/§8/§9 实现清单与版本记录；依赖仅需 `flink-cdc-pipeline-connector-kafka:3.6.0-2.2`（shade 含 flink-connector-kafka）。
> - v1.4 (2026-08-12)：**F1 SQL 终端后端实现 + 部署 + API 自测通过**——① q-0~q-3 细化落地：JSqlParser 语法级只读校验（放行 SELECT/WITH/SHOW/DESC/EXPLAIN）、task-core `DorisSqlExecutor.query(sql, timeoutSeconds)` 超时重载（超时→9003）、数据服务聚合 `/sql-console/datasources`、governance V1.6.0 + internal 表清单/敏感度批量 + GovernanceMetadataApi（fail-closed）；② 新服务 `data-nest-data-service` 部署（容器 `datanest-app-data-service`，Flyway v1.0.0 建 6 表，网关 `/api/data-service/**` + swagger urls）；③ **用户拍板：业务服务本地禁止 `@Scheduled`**，SQL 查询历史清理改放 job——新增 data-service-api `DataServiceOpsApi` + data-service `/internal/sql-history/cleanup` + job `SqlHistoryCleanupHandler`（PowerJob jobId=293）；规范写入 `docs/agent/conventions-backend.md` §7；④ API 自测 **17 用例全通过**（只读拦截/语法错/多语句绕过/敏感度 9004/fail-closed 9012/超时 9003/外部数据源/Doris/SHOW/历史/权限/文档/internal 安全）。
> - v1.5 (2026-08-12)：**F1 多数据源 E2E**——放开 compose `middleware-test-oracle`（gvenzl/oracle-free:23，1521，testuser/FREEPDB1）+ `middleware-test-sqlserver`（mssql 2022，1433，sa/datanest_test）+ `test-oracle-data` volume；工程侧新增 `oracle`（id 2087429814056460290）与 `sqlserver`（id 2087429854464385026）两个 NORMAL 数据源。经 SQL 终端逐一实测 **MySQL / PostgreSQL / Oracle / SQL Server 4 种库查询全部通过**（MySQL `users`、PG `s4_orders`、Oracle `TESTUSER.test_orders`、SQL Server `dbo.test_orders`），并复验 MySQL `SELECT SLEEP(3)` timeout=1s→9003 超时中断、SQL Server `DELETE`→9001 只读拦截；数据源下拉确认内置 Doris + 4 类型平台数据源齐全。
> - v1.6 (2026-08-12)：**导出统一走后端**——用户拍板「系统所有导出走后端」：① `XlsxExportHelper` 下沉 common（自动列宽 A/B + 表头加粗 C，治理域 3 个导出已改）并新增 `CsvExportHelper`（UTF-8 BOM + RFC4180）；② common 以 compile scope 统一提供 poi-ooxml（根 pom 管版本 5.4.1），governance/data-service 冗余声明移除；③ data-service SQL 终端新增 `POST /sql-console/export`（XLSX/CSV，复用 execute 全链路校验），前端 `exportSqlResult` 后端调用替代 SheetJS 前端生成；实测 xlsx（PK 头、数字列正确、列宽适配）+ CSV（EF BB BF BOM、内容正确）+ 导出只读拦截 9001 全通过。
> - v1.6 (2026-08-12)：**F1 前端 + 联调 + 补 F1.1 后端**——① 前端 SQL 终端页（路由/菜单/Monaco Ctrl+Enter/运行·停止/KPI 4 卡/结果表/CSV·Excel 导出/历史回填+清空）实现并部署 app-frontend；② F1.1 后端补丁（用户授权「你来补后端」）：`POST /sql-console/cancel` + `queryId` 注册表取消（interrupt+关连接）、`SqlExecuteResult.tableCount/confidentialHits`、socketTimeout 参数化、durationMs int；③ §4.1/§5.1/§9 同步（本版本记录下段落地）。「扫描行」KPI 因 JDBC 无可靠 API 改「涉及表」（见 handoff §6.2 已知取舍）。
> - v1.7 (2026-08-12)：**F2 数据 API + Key 管理端后端完成并部署**——① 4 项决策用户拍板：F2 只做管理端（对外调用入口归 F3）、自定义路径（Blocker 5 定稿）、软删加 deleted 列（V1.0.2，path 改部分唯一索引）、返回字段白名单并入 params_json 定义对象；② `DataApiController`/`DataApiService`（CRUD/发布/下线/软删 + 敏感度闸门 fail-closed + 路径归一查重 + 标识符/排序白名单防注入 + 自动文档）+ `ApiKeyController`/`ApiKeyService`（K- 明文一次性返回 + SHA-256 哈希 + 绑定/快捷启停/近 7 天调用聚合）；③ common 错误码补 9013/9014；④ API 自测 45 用例全通过（功能 38 + 敏感度闸门 7：机密/内部未开白 9004、开白放行、fail-closed 语义同 F1）；§3.0 补 V1.0.1/V1.0.2 迁移行、§8 Blocker 5 定稿、§9.1 补错误码。
> - v1.8 (2026-08-12)：**F2 前端 + 联调**——① 前端：API 管理（列表+统计卡下钻/详情=概览+定义+文档+绑定 Key/3 步创建向导含 API 预览/编辑页）+ API Key 管理（列表+新建·编辑弹窗+明文一次性展示+快捷启停+僵尸 Key 灰显）+ Sidebar「数据服务」组补「API 管理」；roles 补 `DATA_SERVICE_WRITE_ROLES`；`types/metadata.ts` 补 sensitivityLevel/apiExempted；② 用户授权补后端 3 处：`GET /apis/summary`（列表统计卡）、`GET /api-keys/{id}`（编辑预填 apiIds）、`DataApiPageItem`/`DataApiDetailDTO` 加 sensitivityLevel（按 数据源+库+schema 分组批量反查 governance，读路径 fail-open 降级「未知」）；③ 与原型偏差（用户确认）：字段级「机密锁定」不做（NG5 无字段级敏感度，字段全可勾选）、详情页调用统计图表区占位待 F3 `/stats/*`、API 列表敏感度筛选下拉不做（列保留）；④ 踩坑：`@Select` 非 `<script>` 模式不解析 `&gt;` 转义（countCallsSince 9999，已修）；⑤ 验证：后端 python 联调 21 用例全过 + 前端冒烟（列表页用例通过、向导页快照确认渲染）；完整 E2E 由专门测试会话承担（用户明确），临时 spec 未入库。
> - v1.9 (2026-08-13)：**F3 API 网关 + 调用统计后端完成并部署**——对外执行入口（`OpenApiController`/`OpenApiService`，HTTP 状态码语义）+ `OpenApiKeyFilter`（Key 认证/绑定/限流）+ `RateLimitService`（Redis 滑动窗口，Key 级 QPS）+ `CircuitBreakerService`（数据源维度熔断）+ `ApiCallLogWriter`（异步统计）+ `OpenApiSqlBuilder`（参数化 SQL）+ 执行器 PreparedStatement 扩展 + `StatsController` 7 全局端点 + `/apis/{id}/stats`；2 问 2 答拍板（Key 级 QPS 限流 / 健康分级对齐告警）；common 补 9015；§9 勾选、§9.1 补错误码；自测全通过 + 测试数据清理（见 handoff §21）。
> - v1.10 (2026-08-13)：**F3 前端 + 补单 API 统计端点**——① 用户拍板「补后端端点，做完整原型」：`ApiStatsDTO` 加 `hourly`/`topKeys`/`statusBreakdown`（新建 `StatusBreakdownDTO`），`ApiCallLogMapper` 加 3 查询，`StatsQueryService.apiStats` 填充；② 前端 API 运行统计全局页（`/data-service/api-stats`，8 区块）+ 单 API 详情统计区块（`ApiStatsSection.tsx`，健康评级 0 调用显「暂无调用」）+ 共享组件 `api-stats/charts.tsx` + 入口（Sidebar/路由/面包屑，操作列不加统计按钮）；③ 验证：`tsc --noEmit` + `pnpm build` 通过 + 浏览器驱动联调（全局页 8 区块 + 单 API 统计区块渲染无 JS 错误，`/stats/*` + `/apis/{id}/stats` 返回 200 结构正确）；测试 API 已清理。
> - v1.11 (2026-08-13)：**F3 完整 E2E 测试会话**——新增 `e2e/sprint10/e2e/open-api.spec.ts`（18 用例）+ `api-stats.spec.ts`（4 用例）+ `helpers/f3-seed.ts`（自播种自清理 + `openApiCall` 带 X-API-Key 直调对外入口）**22 用例全通过**；覆盖：对外认证（无/错/禁用/未绑定 Key 401、路径不存在 404、未发布/下线 404）、参数化执行（EQ/RANGE/orderBy/分页+total/字段裁剪/pageSize clamp）、限流（QPS=1 第 2 次 429 + Retry-After + 窗口 60s 恢复）、熔断（坏表连续失败 → 503 + 数据源维度 + 30s 半开探测闭合）、调用统计（异步落库轮询 + 单 API `/apis/{id}/stats` + 全局 `/stats/*` 7 端点 + 前端统计页/详情区块渲染）；§9 前端清单勾选补齐（API 管理页/运行统计页/types）；E2E 环境注意：熔断器内存态按数据源维度，需干净状态（容器重启后或前次自愈后），用例对历史残留失败稳健（见 handoff §22）。
> - v1.12 (2026-08-13)：**F4 WebSocket 实时订阅后端完成并部署**——中间件 `middleware-kafka`（apache/kafka:4.0.0 KRaft）+ Flink lib 增 kafka connector + TaskManager slot 1→2；realtime 事件管道（`cdc_events_flink_job_id` Flyway V1.4.0 + `CdcYamlBuilder.buildEvent` Kafka 单 sink latest-offset + `start/stop/forceStop` 联动 + `pollEventJobs` 监控 + `cancelJob` + realtime-api `getSubscribeInfo`）；data-service WebSocket（`WsEventsHandler`/`WsHandshakeInterceptor`/`WebSocketSubscriptionRegistry`/`KafkaEventConsumer`/`WsSubscriptionService`）+ 补 Key 绑定管道端点（`pipelineIds`）+ common 9016；2 问 2 答（分层自测+部署 / 机密管道全建+订阅侧拒绝）；分层自测全通过（握手 401/连接、subscribe 9005/9016、Kafka fan-out 归一化事件）+ 测试数据清理（handoff §23）；踩坑：Spring Boot 4 spring-kafka 需显式 ListenerContainerFactory bean。
> - v1.13 (2026-08-13)：**F4 WebSocket 实时订阅前端 + 连接监控**——① 后端补连接监控：`SubscriptionMetrics`（内存埋点：今日事件/延迟 P95/推送失败/按 Key 接收统计，跨天重置）+ `KafkaEventConsumer` 埋点 + `GET /subscriptions/{pipelineId}/stats`（`WsSubscriptionController`/`WsSubscriptionQueryService`：registry + 埋点 + api_key_pipeline join api_key 批量 + 用户名回填）；② 前端：CDC 管道详情「实时订阅」页签（`SubscribeTab.tsx`：订阅文档 + 连接监控 4 KPI + 订阅方 Key 表格）+ Key 表单「绑定管道」多选（`pipelineIds`）+ `KpiCard` 提取 shared + nginx WebSocket 升级头；③ 验证：端点 200 + Key 绑定管道 + WebSocket 握手链路（nginx 101 + 无 Key 拒连 1002）；踩坑：`Number(detail.id)` 19 位 Long 精度丢失（订阅消息 pipelineId 错误）→ 字符串持有 + 直接拼 JSON 数字。
> - v1.14 (2026-08-13)：**F5 数据分级分类后端完成并部署**——governance `SensitivityController`（改级/批量/开白/审计 + 分级列表分页）+ `SensitivityService`（机密降级两步 4012 / 开白仅 INTERNAL 9011 / 审计 action 区分 CHANGE_LEVEL/API_EXEMPT）+ `SensitivityChangeLog` 实体 + Flyway V1.7.0（action/remark）+ common 4011/4012；3 问 3 答（补分级列表 / 批量全有或全无 / 审计加 action）；自测全通过（改级 6 场景 + 开白 + 批量回滚 + 审计 + 分级列表）+ 测试数据清理（handoff §26）；§9 勾选 governance、§9.1 补错误码。
