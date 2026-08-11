# Sprint 9：实时计算深化（CDC 监控 + Checkpoint 管理 + 流处理告警）——技术设计文档

> **版本**：v1.0 | **日期**：2026-08-11 | **对应 PRD**：`docs/sprint9/DataNest-Sprint9-PRD.md` v1.0（范围与 T1~T6 决策已确认）
>
> **技术目标**：realtime 服务内完成监控深化（指标历史 + 趋势）、Checkpoint/Savepoint 管理（含文件清理与强制停止降级）、流处理告警（realtime→app-alert 触发 + app-alert→realtime 对象名反查）；清零 Sprint 8 F2 遗留可靠性 TODO。**M0 端点实测已全部通过**（2026-08-11，对运行中的 Flink 2.2.1 集群逐个 curl 验证，见 D-D2）。

---

## 0. 技术目标与范围

| 功能块 | 技术范围 | 涉及服务/库 |
|--------|----------|-------------|
| F1 监控深化 + 404 自愈 | 指标历史表（分钟降采样）+ 趋势/实时 KPI 端点 + 详情抽屉「运行监控」页签 + 404 连续 N 轮归并外部停止 | realtime（库 V1.3.0）、前端 CDC 页 |
| F2 Checkpoint/Savepoint 管理 | checkpoint 历史/健康度实时转发 + 手动触发 savepoint + 强制停止 + savepoint 文件物理清理（MinIO 客户端） | realtime、前端 CDC 页 |
| F3 流处理告警 | alert 库 CHECK 加 CDC_PIPELINE + 告警触发/反查/UI 扩展 | app-alert（库 V1.1.0）、realtime、realtime-api、前端告警中心 |

**非范围**：不改 Flink 集群镜像与 compose；不引入 Prometheus 等外部监控；checkpoint 历史不落库（T5）；规则级告警阈值不做（T6）。

---

## 1. 关键技术决策记录（ADR）

### D-D1：指标历史 → 5s 轮询内存累加 + 分钟降采样 upsert `cdc_metric_minute`

- **背景**：PRD T1（分钟降采样、保留 30 天）。现有 `CdcMonitorService` 已 5s 轮询 RUNNING 管道并经一次 `/jobs/{id}` + vertex metrics 提取 lag/totalChanges。
- **方案**：轮询内追加提取吞吐量（vertex `numRecordsOutPerSecond`，后缀匹配求和）与重启次数（job-level `numRestarts`），写入**内存累加器** `Map<pipelineId, MetricAccumulator>`（lag 累加/取 max、吞吐累加、restarts/totalChanges 记最新）；独立 `@Scheduled(fixedDelay = 60s)` 把累加器**按当前整分钟 upsert** 进 `cdc_metric_minute`（`ON CONFLICT (pipeline_id, minute_at) DO UPDATE`，幂等可重入）；管道停 RUNNING 后其累加器随最后一次 flush 移除。每日定时清理 `minute_at < now() - 30d`。
- **取舍**：不直写 5s 明细（单管道每天 17280 行不可接受）；不引入 Redis/TSDB（最小改动，对齐 Sprint 8 D5 热度埋点思路）。
- **容量估算**：单管道 1440 行/天 × 30 天 ≈ 4.3 万行/管道，按 20 管道不到百万行，PG 无压力。

### D-D2：Flink 2.2.1 REST 端点实测矩阵（M0，2026-08-11 全部通过）

> 对本环境 Flink 2.2.1 集群（localhost:18081）逐个 curl 验证，结论如下——**Sprint 8 遗留的 REST 不确定性全部消除**：

| 端点 | 结论 | 关键坑 |
|------|------|--------|
| `GET /jobs/{id}` | ✅ vertices 内嵌，含累计 IOMetrics（read/write-records 等） | 无 `/jobs/{id}/vertices` 列表子资源（S8 已知） |
| `GET /jobs/{id}/vertices/{vid}/metrics?get=` | ✅ 可用 | **指标 id 带子任务序号前缀**（`0.numRecordsOutPerSecond`），需先列指标再按后缀匹配聚合（现有 `sumVertexMetrics`/`maxVertexMetric` 机制直接复用）；**per-second 速率是 double**（`0.0833...`），现有 `queryVertexMetricValues` 用 `Long.parseLong` 会解析失败被跳过——**需新增 double 解析路径**；source 算子指标带作用域名（`0.Source__MySQL_Source.currentEmitEventTimeLag`，ms，-1=未知） |
| `GET /jobs/{id}/metrics?get=` | ✅ 可用 | job-level 指标丰富：`numRestarts`、`numberOfCompletedCheckpoints`、`numberOfFailedCheckpoints`、`lastCheckpointDuration`、`lastCheckpointSize`、`uptime/downtime` 等 |
| `GET /jobs/{id}/checkpoints` | ✅ 可用 | 返回 `counts`（completed/failed/in_progress/restored/total）、`summary`（p50/p95/avg）、`latest`（completed/savepoint/failed/restored，含 `external_path`）、`history`（`trigger_timestamp`/`end_to_end_duration`/`state_size`/`status`/`is_savepoint`/`checkpoint_type`） |
| `POST /jobs/{id}/savepoints` | ✅ 可用（触发契约已验证） | **body 必须是 kebab-case** `{"target-directory": "...", "cancel-job": false}`——camelCase 会被静默忽略报「Property [target-directory] must be provided」；返回 `{"request-id": "..."}`，轮询 `GET /jobs/{id}/savepoints/{requestId}` 取结果（与现有 stopWithSavepoint 轮询逻辑同款，抽公共方法复用）。注意：stop-with-savepoint 的 body 是 camelCase（`{"drain":false,"targetDirectory":...}`），两个端点命名风格不同，勿混 |

### D-D3：Checkpoint 数据 → 实时转发不落库（T5）

- **方案**：「检查点」页签数据由 realtime 实时转发 `GET /jobs/{id}/checkpoints`（裁剪出三卡 + 最近 20 条历史）+ `GET /jobs/{id}/metrics`（numberOfFailedCheckpoints 等补充）。非 RUNNING 或查询失败时返回空结构 + 前端降级提示。
- **取舍**：不落库（checkpoint 高频低价值，Flink 已保留窗口）；「24h 失败次数」口径受 Flink 保留窗口限制，卡片文案标注「近期失败次数」。

### D-D4：savepoint 文件清理 → 引入 MinIO Java Client

- **背景**：PRD T3（删除管道级联清理其已知 savepoint 文件 + 替换时清理失效文件）。全项目无 S3/MinIO 客户端依赖（已核验 pom）。
- **方案**：realtime 引入 `io.minio:minio`（8.x 最新稳定版，版本入根 pom `dependencyManagement` 统一管理）；配置**复用现有 `shared-minio.yaml`**（`datanest.minio.endpoint/access-key/secret-key/bucket`，消费方已是 app-realtime，零新增配置）。路径解析：`s3a://datalake/savepoints/savepoint-xxx` → bucket=`datalake`、object=`savepoints/savepoint-xxx`（含目录对象，`removeObject` 按前缀列出后批量删）。
- **选型理由**：仅需「列前缀 + 删对象」，MinIO Client 单 artifact 轻量；AWS SDK v2 模块化但传递依赖更重。两候选都不与 Flink 集群侧 jar 冲突（服务端独立进程）。
- **失败语义**：删文件失败**不阻断**删除主流程，warn 日志 + 管道日志留痕（PRD R5）。

### D-D5：流处理告警 → realtime 依赖 alert-api 上报 + app-alert 扩展 CDC_PIPELINE 对象

- **对象类型**：common `AlertConstants` 新增 `OBJECT_TYPE_CDC_PIPELINE = "CDC_PIPELINE"` + `DISPLAY_CDC_PIPELINE = "CDC 管道"`；alert 库 Flyway 放宽 `alert_rule.object_type` CHECK（V1.1.0）；`AlertRuleService.validate()` 白名单（现 451~455 行四类型 if）同步加分支。
- **告警类型**：新增两个常量即可：`ALERT_LAG_EXCEEDED = "LAG_EXCEEDED"`（延迟超阈值）、`ALERT_EXTERNAL_STOP = "EXTERNAL_STOP"`（外部停止）；作业失败复用现有 `ALERT_FAILURE`。
  **⚠️ 文档修正（2026-08-11 实施核验）**：`alert_history.alert_type` 实际有 DB CHECK（baseline `alert_history_alert_type_check` 仅 FAILURE/TIMEOUT/SUCCESS），并非「无 CHECK」——V1.1.0 脚本必须**同时放宽 alert_history.alert_type CHECK**（加 LAG_EXCEEDED/EXTERNAL_STOP），否则新告警类型写历史会被数据库拒绝（用户已确认一并放宽并修正本文档）。`trigger_conditions` 为 varchar 无 CHECK。
  `AlertFiringService` 的 `displayObjectType`/`displayAlertType`/`buildObjectUrl` 三个 switch 同步加分支（URL → `http://localhost:3000/engineering/cdc-pipelines`，管道详情是抽屉无独立路由，链到列表页）。
- **触发点**（`CdcMonitorService`，经 alert-api `AlertApi.fire` + common `RemoteCalls.execute` 降级，fail-open 不阻断监控主流程）：
  - 作业 FAILED（`onJobFailed`）→ `fire("CDC_PIPELINE", id, "FAILURE", lastError)`；
  - 延迟超阈值（沿用 `lagWarnedPipelineIds` 去重语义：首次越阈触发一次，恢复复位）→ `fire(..., "LAG_EXCEEDED", "当前延迟 Xs，阈值 Ys")`；
  - 外部停止（`onJobStoppedExternally` + 本期 404 归并分支）→ `fire(..., "EXTERNAL_STOP", "Flink 作业状态=...")`。
- **对象名反查**：app-alert 的 `fetchObjectNames`/`listObjectOptions` 现有分组模式（DAG/SYNC_JOB→engineering-api，COLLECT_TASK/QUALITY→governance-api），本期加 **CDC_PIPELINE→realtime-api** 分支：realtime-api `CdcPipelineApi` 新增 `GET /realtime/internal/cdc/pipelines/names?ids=`（批量 id→name Map，fallback 降级空 Map，读路径 fail-open）；`CdcInternalController` 实现。**app-alert pom 新增 data-nest-realtime-api 依赖；realtime pom 新增 data-nest-alert-api 依赖**（双向均为独立契约模块，无循环依赖）。
- **既有防重**：`AlertFiringService.fire` 自带「同对象同类型 60s 窗口防重」（`countRecent`），叠加 lagWarned 语义即满足「连续超阈值只告警一次」（PRD R3）。

### D-D6：404 归并外部停止 + 强制停止降级（T4）

- **404 判定**：`CdcMonitorService.pollOne` 捕获 REST 异常时区分 404（`RestClientResponseException` 状态码）：404 → 该管道 `notFoundCount++`，**连续 ≥ `not-found-threshold`（默认 3，Nacos 可配）轮** 才按「外部停止」处理（置 STOPPED + 清 flink_job_id + WARN 日志 + EXTERNAL_STOP 告警）；成功或非 404 异常（连接拒绝/超时=集群不可达）→ 计数清零、保持现状（沿用「集群重启期间不误伤」语义）。
- **强制停止**：现有 `stop` 在作业已丢失时失败 8008 保持不变（fail-closed 默认）；新增 `POST /{id}/force-stop`：跳过 savepoint，置 STOPPED + 清 `flink_job_id` 与 `savepoint_path` + INFO 日志。前端在 stop 收到 8008 时弹确认框（「作业已丢失，未保存位点，下次启动将按启动位点重新同步」）后调 force-stop。

---

## 2. 领域模型

```
realtime 域（datanest_realtime 库）
├── cdc_pipeline          （已有，不变）
├── cdc_pipeline_table    （已有，不变）
├── cdc_pipeline_log      （已有，不变；新增事件文案直接复用 level 体系）
└── cdc_metric_minute     （新增：分钟级指标快照，append/upsert 型，无 updated_at）

alert 域（datanest_alert 库）
├── alert_rule            （object_type CHECK 放宽 + CDC_PIPELINE）
├── alert_rule_object     （不变，object_type 无 CHECK）
└── alert_history         （不变，alert_type varchar(16) 容纳新枚举）
```

---

## 3. 数据模型设计

### 3.0 迁移脚本与版本规划

| 库 | 脚本 | 内容 |
|----|------|------|
| `datanest_realtime`（现最高 V1.2.0） | `V1.3.0__cdc_metric_minute.sql` | 新增指标历史表 |
| `datanest_alert`（现最高 V1.0.0） | `V1.1.0__alert_cdc_pipeline_object.sql` | 放宽 alert_rule.object_type CHECK（+CDC_PIPELINE）与 alert_history.alert_type CHECK（+LAG_EXCEEDED/EXTERNAL_STOP，文档修正见 D-D5） |

> 沿用紧凑单行风格；新表无 `updated_at`（upsert 覆盖写，非业务更新语义）；id 用 `bigint` + 实体 `@TableId(IdType.ASSIGN_ID)`（对齐 cdc_pipeline_log 现有惯例）。

### 3.1 realtime `V1.3.0__cdc_metric_minute.sql`

```sql
CREATE TABLE IF NOT EXISTS public.cdc_metric_minute (id bigint NOT NULL, pipeline_id bigint NOT NULL, minute_at timestamp without time zone NOT NULL, lag_avg_seconds integer, lag_max_seconds integer, records_per_second_avg double precision, num_restarts integer, total_changes bigint, created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL, CONSTRAINT cdc_metric_minute_pkey PRIMARY KEY (id), CONSTRAINT uk_cdc_metric_minute_pipeline_minute UNIQUE (pipeline_id, minute_at));
COMMENT ON TABLE public.cdc_metric_minute IS 'CDC 管道分钟级指标历史（5s 轮询内存聚合后每分钟 upsert 一行，保留 30 天）';
COMMENT ON COLUMN public.cdc_metric_minute.minute_at IS '采样分钟（截断到分）';
COMMENT ON COLUMN public.cdc_metric_minute.lag_avg_seconds IS '本分钟延迟均值（秒）；该分钟无有效样本为 NULL';
COMMENT ON COLUMN public.cdc_metric_minute.lag_max_seconds IS '本分钟延迟峰值（秒），趋势图标红判定用';
COMMENT ON COLUMN public.cdc_metric_minute.records_per_second_avg IS '本分钟吞吐均值（行/秒，sink vertex numRecordsOutPerSecond 求和后按分钟平均）';
COMMENT ON COLUMN public.cdc_metric_minute.num_restarts IS '作业累计重启次数（该分钟最后一次采样值，job-level numRestarts）';
COMMENT ON COLUMN public.cdc_metric_minute.total_changes IS '累计变更数（该分钟最后一次采样值）';
CREATE INDEX IF NOT EXISTS idx_cdc_metric_minute_minute_at ON public.cdc_metric_minute USING btree (minute_at);
```

- `uk(pipeline_id, minute_at)` 支撑幂等 upsert；`idx(minute_at)` 供 30 天清理。
- lag 无样本（-1 未知）不计入均值/峰值，全无样本写 NULL（前端断点展示，对齐 PRD「不插值造假」）。

### 3.2 alert `V1.1.0__alert_cdc_pipeline_object.sql`

```sql
-- ① 放宽 alert_rule.object_type CHECK，新增 CDC_PIPELINE
ALTER TABLE public.alert_rule DROP CONSTRAINT alert_rule_object_type_check;
ALTER TABLE public.alert_rule ADD CONSTRAINT alert_rule_object_type_check CHECK (((object_type)::text = ANY ((ARRAY['DAG'::character varying, 'SYNC_JOB'::character varying, 'COLLECT_TASK'::character varying, 'QUALITY'::character varying, 'CDC_PIPELINE'::character varying])::text[])));
COMMENT ON COLUMN public.alert_rule.object_type IS '对象类型：DAG / SYNC_JOB / COLLECT_TASK / QUALITY / CDC_PIPELINE';

-- ② 放宽 alert_history.alert_type CHECK（文档修正：baseline 实际有 CHECK，需一并放宽，否则 LAG_EXCEEDED/EXTERNAL_STOP 写历史被拒）
ALTER TABLE public.alert_history DROP CONSTRAINT alert_history_alert_type_check;
ALTER TABLE public.alert_history ADD CONSTRAINT alert_history_alert_type_check CHECK (((alert_type)::text = ANY ((ARRAY['FAILURE'::character varying, 'TIMEOUT'::character varying, 'SUCCESS'::character varying, 'LAG_EXCEEDED'::character varying, 'EXTERNAL_STOP'::character varying])::text[])));
COMMENT ON COLUMN public.alert_history.alert_type IS '告警类型：FAILURE / TIMEOUT / SUCCESS / LAG_EXCEEDED / EXTERNAL_STOP';
```

> `alert_rule_object.object_type` 无 CHECK（baseline 已核验），无需迁移。

---

## 4. 核心流程

### 4.1 指标采集与降采样（F1）

```
CdcMonitorService.pollOne（5s 轮询，RUNNING 管道）
  ├─ getJobOverview(/jobs/{id})            ← 已有：状态 + lag + totalChanges
  ├─ extractMetrics 扩展：
  │    ├─ throughput = Σ sink vertex "numRecordsOutPerSecond"（后缀匹配，**double 解析**）
  │    └─ （lag/totalChanges 逻辑不变）
  ├─ jobMetrics = GET /jobs/{id}/metrics?get=numRestarts（每轮一次，轻量）
  └─ MetricAccumulator.accumulate(lag, throughput, restarts, totalChanges)
        └─ 内存 Map；lag<0 不计入样本

MetricSnapshotWriter（@Scheduled fixedDelay=60s）
  └─ 遍历累加器 → upsert cdc_metric_minute（ON CONFLICT (pipeline_id, minute_at)）
       └─ 管道已非 RUNNING 的累加器 flush 后移除

MetricRetentionCleaner（@Scheduled 每日 03:40）
  └─ DELETE WHERE minute_at < now() - interval '30 days'（retention-days 可配）
```

### 4.2 404 归并外部停止 + 强制停止（F1/F2）

```
pollOne 捕获 REST 异常：
  ├─ 404 Not Found → notFoundCount[pipelineId]++
  │     └─ count ≥ threshold(3) → onJobStoppedExternally(state="NOT_FOUND")
  │           （置 STOPPED + 清 flink_job_id + WARN 日志 + fire EXTERNAL_STOP）
  ├─ 其他异常（连接拒绝/超时） → 计数清零，只 warn（现状保留）
  └─ 成功 → 计数清零

stop 流程：
  ├─ 作业存在 → cancel-with-savepoint（现状）
  └─ 作业 404 → 抛 8008（现状）→ 前端弹确认 → POST /{id}/force-stop
        （跳过 savepoint，置 STOPPED + 清 flink_job_id/savepoint_path + INFO 日志）
```

### 4.3 手动触发 savepoint（F2）

```
POST /cdc/pipelines/{id}/savepoints（仅 RUNNING，否则 8011）
  ├─ POST /jobs/{jobId}/savepoints  body={"target-directory": SAVEPOINT_DIR, "cancel-job": false}  ← kebab-case
  ├─ 轮询 GET /jobs/{jobId}/savepoints/{requestId}（复用 stopWithSavepoint 的轮询，抽公共方法 pollSavepointResult）
  ├─ 成功 → 旧 savepoint_path 若存在且不同 → MinIO 删旧文件（T3 替换清理）→ 回写新 savepoint_path + INFO 日志
  └─ 失败/超时 → 8010
```

### 4.4 告警触发链路（F3）

```
CdcMonitorService（realtime）                app-alert
  onJobFailed            ──fire(FAILURE, lastError)──┐
  lag 首次越阈           ──fire(LAG_EXCEEDED, …)─────┼─→ AlertFiringService.fire
  onJobStoppedExternally ──fire(EXTERNAL_STOP, …)────┘   ├─ resolveRule（CDC_PIPELINE 匹配规则+触发条件）
  （RemoteCalls 降级：alert 不可达只记管道日志）          ├─ 收件人邮箱（SystemUserApi，现状）
                                                        ├─ resolveObjectName ──→ realtime-api names 端点（新）
                                                        └─ 邮件 + alert_history
```

### 4.5 savepoint 文件清理（F2）

```
触发点：① 删除管道（级联）② 手动/停止产生新 savepoint 替换旧路径时 ③ force-stop 清 savepoint_path 时
  └─ SavepointFileCleaner.delete(s3a://datalake/savepoints/xxx)
       ├─ 解析 bucket/object 前缀（bucket 固定取 shared-minio 配置校验一致，不一致 warn 跳过）
       ├─ minioClient.listObjects(prefix) + removeObjects 批量删
       └─ 异常 → warn 日志 + 管道日志，不阻断主流程
```

---

## 5. 接口设计（Controller）

### 5.1 realtime `CdcPipelineController` 扩展（`/cdc/pipelines`）

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | `/{id}/metrics/current` | 实时 KPI：throughputRowsPerSecond（live vertex 指标）/ numRestarts（live job 指标）/ currentLagSeconds / totalChanges（DB 字段）；非 RUNNING 返回最后已知值 + `live=false` | 四角色 OR |
| GET | `/{id}/metrics/trend?range=` | 趋势：range ∈ `1h/6h/24h/7d`（默认 24h）；1h/6h 返回原始分钟点，24h 按 5 分钟桶、7d 按小时桶聚合（SQL `date_trunc` + avg/max）；返回 `{points: [{minuteAt, lagAvgSeconds, lagMaxSeconds, recordsPerSecondAvg}]}` | 四角色 OR |
| GET | `/{id}/checkpoints` | 实时转发 Flink checkpoints：summary 三卡（最近完成时间/平均耗时/近期失败次数）+ history 最近 20 条（触发时间/耗时/大小/状态/类型）+ `latest.savepoint.external_path`；作业不可达返回 `reachable=false` 空结构 | 四角色 OR |
| POST | `/{id}/savepoints` | 手动触发 savepoint（§4.3），成功返回新路径并回写管道 | 超管/工程师 |
| POST | `/{id}/force-stop` | 强制停止（§4.2），幂等：非 RUNNING 直接返回当前状态 | 超管/工程师 |

### 5.2 realtime `CdcInternalController` 扩展（`/realtime/internal`）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/cdc/pipelines/names?ids=` | 批量 id→name（`Map<Long,String>`），供 app-alert 对象名反查/可选对象下拉；realtime-api `CdcPipelineApi` 加契约方法，fallback 降级空 Map（fail-open） |

### 5.3 app-alert 改动点（无新端点）

- common `AlertConstants`：`OBJECT_TYPE_CDC_PIPELINE` / `ALERT_LAG_EXCEEDED` / `ALERT_EXTERNAL_STOP` / `DISPLAY_CDC_PIPELINE`。
- `AlertRuleService`：validate 白名单加 CDC_PIPELINE；`fetchObjectNames`/`listObjectOptions` 加 realtime 分支（注入 `CdcPipelineApi`）。
- `AlertFiringService`：三个 switch 加分支（displayObjectType/displayAlertType/buildObjectUrl）。
- `AlertRuleController` 的「可选告警对象下拉」（`/alert-rules/object-options`）自动获得 CDC_PIPELINE 支持（走 `listObjectOptions` 新分支）。

---

## 6. 权限矩阵映射

| 端点/操作 | 超管 | 工程师 | 治理员 | 分析师 |
|-----------|:----:|:------:|:------:|:------:|
| metrics/current、metrics/trend、checkpoints | ✅ | ✅ | ✅ | ✅ |
| savepoints（触发）、force-stop | ✅ | ✅ | ❌ | ❌ |
| 告警规则 CDC_PIPELINE（增删改/订阅） | ✅ | ✅ | ❌ | ❌ |
| 告警规则/历史查看（CDC_PIPELINE） | ✅ | ✅ | ✅ | ❌ |

> 对齐现有：`CdcPipelineController` 类级四角色 OR + 写方法超管/工程师；`AlertRuleController`/`AlertHistoryController` 权限不动（写=超管/工程师、查看=+治理员）。

---

## 7. 配置项与部署

### 7.1 配置项（Nacos shared-configs）

**`shared-realtime.yaml` 追加**（改后需重启 app-realtime）：

```yaml
datanest:
  realtime:
    monitor:
      interval-ms: 5000            # 已有
      not-found-threshold: 3       # 新增：连续 N 轮 404 才归并外部停止（T4）
    metric:
      retention-days: 30           # 新增：cdc_metric_minute 保留天数（T1）
```

**`shared-minio.yaml`**：不变（S3 客户端直接复用 `datanest.minio.*`）。

### 7.2 依赖与构建变更

| 模块 | 变更 |
|------|------|
| 根 `pom.xml` | `dependencyManagement` 新增 `io.minio:minio`（8.x 最新稳定版，实现时以 Maven Central 为准并记录实际版本） |
| `data-nest-realtime` | + `data-nest-alert-api`（告警上报）、+ `io.minio:minio`（savepoint 清理） |
| `data-nest-alert-service` | + `data-nest-realtime-api`（对象名反查） |
| `data-nest-realtime-api` | + `names` 契约方法 + fallback（fail-open 空 Map） |

> common 改了 `AlertConstants`/`ErrorCode` → 需重建全部消费方中至少 **app-realtime、app-alert**；common 是基础底座，按 AGENTS.md 规则建议全量 `mvn clean package -DskipTests` 后重建这两个镜像即可（其他服务不引用新常量，但同版本号统一部署避免混版）。

### 7.3 部署步骤

1. `mvn clean package -DskipTests -q`（common → api → 服务全量）
2. `docker compose build app-realtime app-alert && docker compose up -d --no-deps app-realtime app-alert`
3. Nacos 发布 `shared-realtime.yaml` 新配置 → 重启 app-realtime（已在第 2 步重建，顺序上先发布配置）
4. Flyway 自动应用：realtime V1.3.0、alert V1.1.0
5. 验证：见 §10

---

## 8. 已知 Blocker 与待确认点

**M0 已全部实测通过，无阻塞项。** 实现注意点：

| # | 注意点 | 出处 |
|---|--------|------|
| 1 | vertex per-second 指标是 **double**，现有 `queryVertexMetricValues` 的 `Long.parseLong` 会丢弃——新增 double 解析方法 | D-D2 实测 |
| 2 | 手动 savepoint 触发 body 是 **kebab-case**（`target-directory`/`cancel-job`），与 stop-with-savepoint 的 camelCase **不同**，勿混 | D-D2 实测 |
| 3 | 指标 id 带**子任务前缀**（`0.`），并行度 >1 时需跨子任务求和——复用现有后缀匹配机制天然支持 | D-D2 实测 |
| 4 | 「近期失败次数」受 Flink checkpoint 历史保留窗口限制，卡片文案不承诺精确 24h | D-D3 |
| 5 | 对 FINISHED 作业触发 savepoint 会返回 request-id 但异步失败——端点层先校验管道 RUNNING（8011）拦截 | §4.3 |
| 6 | force-stop 与 stop 竞态：force-stop 内用 CAS 更新（`WHERE status='RUNNING'`），失败返回当前状态 | §4.2 |

---

## 9. 实现清单（P0）

### 后端

- [ ] **realtime 库** `V1.3.0__cdc_metric_minute.sql`；**alert 库** `V1.1.0__alert_cdc_pipeline_object.sql`（**双 CHECK**：alert_rule.object_type + alert_history.alert_type，见 D-D5 修正）
- [ ] `CdcMonitorService`：throughput/numRestarts 提取（double 解析）+ 内存累加器 + 404 计数归并 + 三个告警触发点（AlertApi.fire + RemoteCalls）
- [ ] `MetricSnapshotWriter`（分钟 upsert）+ `MetricRetentionCleaner`（每日清理）
- [ ] `FlinkJobService`：`getJobMetrics`（job-level）/ `getCheckpoints` / `triggerSavepoint`（kebab-case）+ 抽 `pollSavepointResult` 公共轮询
- [ ] `SavepointFileCleaner`（MinIO client，三触发点接入：删除管道/替换/force-stop）
- [ ] `CdcPipelineService`：metrics/current、metrics/trend（range 分桶聚合）、checkpoints、savepoints、force-stop 五个方法 + 删除管道级联清理 savepoint 文件
- [ ] `CdcPipelineController` 5 端点 + `CdcInternalController` names 端点
- [ ] realtime-api：`CdcPipelineApi.names` + fallback
- [ ] common：`AlertConstants`（对象类型/告警类型/显示名）+ `ErrorCode` 8010/8011
- [ ] app-alert：`AlertRuleService`（validate + fetchObjectNames + listObjectOptions 分支）+ `AlertFiringService`（三 switch）+ pom 加 realtime-api

### 前端

- [ ] `CdcPipelineDetailDrawer` 改 Tabs：基本信息（现有）/ **运行监控**（KPI×4 + 延迟/吞吐趋势，range 切换）/ **检查点**（三卡 + 历史表 + 触发 Savepoint 按钮）
- [ ] 趋势图：复用质量报告手写 SVG 模式（`quality-report/charts.tsx`），抽 `LineChart` 通用组件（多系列/断点/阈值标红）
- [ ] 列表页操作：stop 遇 8008 弹确认 → force-stop
- [ ] `AlertRuleModal`：`OBJECT_TYPE_OPTIONS` 加「CDC 管道」；触发条件按对象类型分组（CDC_PIPELINE → 作业失败/延迟超阈值/外部停止；TIMEOUT 的 timeoutMinutes 输入仅 DAG 系显示）
- [ ] `types/cdc.ts` + `api/cdc.ts` 扩展；`types/alert.ts` 枚举扩展

### 部署与验证

- [ ] Nacos 发布 `shared-realtime.yaml`（not-found-threshold / retention-days）
- [ ] 重建 app-realtime、app-alert；Flyway 两脚本应用确认
- [ ] E2E：新建 `e2e/sprint9/e2e/`（监控趋势/检查点页签/手动 savepoint/强制停止/告警邮件 MailHog 断言）；回归 sprint8 `cdc-pipeline.spec.ts` 23 用例

### 9.1 新增错误码（common `ErrorCode`）

| 码 | 常量 | 语义 |
|----|------|------|
| 8010 | CDC_SAVEPOINT_TRIGGER_FAILED | savepoint 触发失败/超时（含 Flink 异步失败原因） |
| 8011 | CDC_PIPELINE_NOT_RUNNING | 仅运行中管道可触发 savepoint |

> 8008（作业丢失停止失败）沿用现有语义，前端据此弹 force-stop 确认。

---

## 10. 验收口径映射（PRD AC）

| PRD AC | 验证方式 |
|--------|----------|
| AC-1 指标历史 | 运行管道 ≥2 分钟后查 `cdc_metric_minute` 有分钟行；改 retention 或手工插旧数据验证清理 |
| AC-2 监控趋势图 | 四档 range 切换与表数据一致；lag 超阈值区段标红；停止时段断点 |
| AC-3 检查点可视 | 页签三卡/历史与 `curl /jobs/{id}/checkpoints` 原始数据一致 |
| AC-4 手动 savepoint | 触发成功 → savepoint_path 回写 → 停止/启动后数据不丢不重（对齐 S8 savepoint E2E 口径） |
| AC-5 savepoint 清理 | 删除管道后 MinIO `mc ls` 对应路径不存在；MinIO 停掉时删除主流程仍成功且有 warn 留痕 |
| AC-6 404 自愈 | `docker restart middleware-flink-jobmanager`（作业丢失）→ ≤3 轮轮询后管道 STOPPED + 日志 |
| AC-7 强制停止 | 作业丢失的 RUNNING 管道 force-stop 成功；savepoint_path 清空；日志语义符合提示 |
| AC-8 流处理告警 | 三条件分别构造触发（杀作业/压测延迟/集群重启）→ MailHog 收到对应邮件；连续超阈只一封 |
| AC-9 告警规则 UI | 规则对象类型可选 CDC 管道、下拉列出管道；删管道后规则对象解绑（rules/by-object 现有链路） |
| AC-10 权限隔离 | 分析师调 savepoints/force-stop/告警规则写 403；治理员告警只读 |
| NAC-3 回归 | sprint8 `cdc-pipeline.spec.ts` 23/23 通过 |

---

> **版本记录**
> - v1.0 (2026-08-11)：初始版本。M0 端点实测完成（Flink 2.2.1 REST metrics/checkpoints/savepoints 全部 curl 验证，含 double 解析、kebab-case body、子任务前缀三个新坑）；6 个 ADR 对齐 PRD T1~T6；代码改动点均经源码核验（CdcMonitorService/FlinkJobService/AlertRuleService/AlertFiringService/AlertConstants/AlertRuleModal/pom/migration 现状）。
