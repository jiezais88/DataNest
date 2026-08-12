# Sprint 9 Handoff

> **更新时间**：2026-08-12 | **阶段**：规划 + 设计 + 后端 F1/F2/F3 全部实施完成 + 后端三块（级联删除/删除校验/内部定时器）复核通过 → 前端联调待启动
> **Sprint 主题**：实时计算深化（CDC 监控深化 + Checkpoint/Savepoint 管理 + 流处理告警）+ S8 F2 遗留 TODO 清零（三大模块均为 P0）

## 1. Sprint 目标

在 Sprint 8 打通第一条实时链路（CDC 管道）基础上，让实时链路**可观测、可运维、可告警**：监控从两个瞬时值升级为指标历史 + 趋势图；Checkpoint/Savepoint 可视可管；异常经 app-alert 规则化邮件告警；清零 S8 F2 遗留可靠性 TODO（404 卡 RUNNING / stop 无降级 / savepoint 文件堆积 / PG 限制提示），达到生产可运维水位。

## 2. 状态看板

| 事项 | 状态 | 说明 |
|------|------|------|
| Sprint 9 产品范围确认 | ✅ 完成 | 用户确认：路线图 S10「实时计算深化」+ S8 F2 遗留 TODO 清零；监控=完整版（指标历史+趋势图）；告警复用 app-alert |
| Sprint 9 产品决策确认（T1~T6） | ✅ 完成 | 2026-08-11 PRD 整体评审通过，T1~T6 全部采纳（见 §3） |
| Sprint 9 PRD | ✅ 完成 | `docs/sprint9/DataNest-Sprint9-PRD.md`（v1.0，2026-08-11，13 章对齐 Sprint8 PRD 范本） |
| Sprint 9 技术设计 | ✅ 完成 | `docs/sprint9/DataNest-Sprint9-技术文档.md`（v1.0，6 个 ADR）；**M0 端点实测已提前完成**（2026-08-11 对 Flink 2.2.1 集群 curl 验证：vertex metrics / job metrics / checkpoints / savepoint 触发全部可用，无 Blocker） |
| Sprint 9 UI 原型 | ✅ 完成 | `DataNest-Sprint9-原型.{html,css,js}`（单 HTML 多视图，4 视图：运行监控/检查点+Savepoint/强制停止/CDC 告警规则；**CSS 基于真实 tokens.css + 真实组件源码新写**（tokens/tailwind.config/Drawer/DsModal/cdc-pipelines/quality-report/AlertRuleModal 逐文件核验），Playwright 4 视图截图验证渲染通过、无 JS 错误，临时截图已清理） |
| **F1 监控深化 + 404 自愈** | ✅ 完成（2026-08-11 后端实施 + curl 自测） | 见 §6：V1.3.0 指标表 + 内存累加器/分钟降采样 + 趋势端点 + 404 连续 N 轮归并；实测 metrics/current、metrics/trend（含 24h 桶修复）、分钟落库、外部停止状态机全通过 |
| **F2 Checkpoint/Savepoint 管理** | ✅ 完成（2026-08-11 后端实施 + curl 自测） | 见 §6：minio 8.5.17 + checkpoints 转发 + 手动 savepoint + force-stop + 文件清理（手动 savepoint 替换/force-stop/删除三场景实测通过；stop 替换清理逻辑同法实现，因 Flink slot 被验收管道占用未单独实测，留待联调） |
| **F3 流处理告警** | ✅ 完成（2026-08-11 后端实施 + curl 自测） | 见 §6：双 CHECK 放宽（**文档修正**：alert_history.alert_type 实际有 CHECK）+ AlertConstants/白名单/反查/三触发点；实测 CDC 规则创建、对象下拉/名称反查、删除管道解绑规则、LAG_EXCEEDED/EXTERNAL_STOP 历史落库全通过 |
| **S8 F2 遗留 TODO 清零** | ✅ 完成 | ①②③ 随 F1/F2 落地；④ PG REPLICA IDENTITY FULL 检查已补（CdcSourceTableDTO.replicaIdentityFull + listPostgresTables relreplident） |
| **后端三块复核（级联删除/删除校验/内部定时器）** | ✅ 通过（2026-08-12） | 后端主 Agent 逐项代码核验：PRD §7 引用总表 4 行级联全落地（含 PG 复制槽清理超范围补充）+ 数据源 8009 fail-closed 校验 + 5 个内部定时器（5s 轮询/60s 降采样/每日 03:40 清理/savepoint 2s×60s 轮询/app-job 30s Doris catalog）全部到位；404 归并保留 savepoint_path 与 forceStop 清位点语义区分清晰、无状态泄漏。无需补做 |
| Sprint 9 Handoff | 🔄 进行中 | 本文档（规划 + F1/F2/F3 后端实施记录 + 后端复核通过）；前端联调 + E2E 待推进 |

## 3. 关键决策（用户已确认）

### 产品决策（2026-08-11）

| 决策点 | 结论 |
|--------|------|
| Sprint 9 主题边界 | 严格按规格 §15 路线图映射：实际 S9 = 路线图 S10 = **实时计算深化** + **S8 F2 遗留 TODO 清零** |
| 监控粒度 | **完整版**：新增指标历史表，支持延迟/吞吐趋势图 |
| 告警通道 | **复用 app-alert**（规则/订阅/邮件），不新建通道 |
| Oracle/SQLServer 源 | 维持 S8 B7/B8：等 Flink CDC 3.7.0（2026-08-11 核实最新正式版 3.6.0 未发布，[Releases](https://github.com/apache/flink-cdc/releases)） |

### T1~T6 倾向方案（2026-08-11 随 PRD 整体评审全部采纳）

| # | 决策点 | 结论 |
|---|--------|------|
| T1 | 指标历史采样口径 | **分钟级降采样**（延迟均值+峰值、吞吐均值、重启增量），保留 **30 天**定时清理 |
| T2 | 「错误数」产品口径 | Flink CDC 无错误行数指标，以**作业重启次数 + 失败事件（日志/告警）**呈现 |
| T3 | savepoint 清理范围 | 删除管道级联清理其已知 savepoint 文件 + 编辑/重建替换时清理失效文件；全局孤儿扫描不做 |
| T4 | 404 判定口径 | **连续 3 轮**（默认，Nacos 可配）404 才归并「外部停止」，防集群抖动误伤 |
| T5 | checkpoint 历史持久化 | **不落库**，实时转发 Flink REST（最小改动） |
| T6 | 延迟告警阈值 | 沿用**全局阈值**（Nacos `lag.warn-threshold`，默认 30s），规则级阈值不做 |

## 4. 变更清单（规划阶段）

| 文档/产物 | 变更说明 |
|-----------|----------|
| `docs/sprint9/DataNest-Sprint9-PRD.md`（新增） | Sprint 9 产品文档 v1.0，13 章对齐 Sprint8 PRD 范本；现状复用点经代码核验（CdcMonitorService / CdcPipelineController 16 端点 / AlertApi 契约 / alert_rule.object_type CHECK 约束 / 前端 cdc-pipelines 结构） |
| `docs/sprint9/DataNest-Sprint9-技术文档.md`（新增） | Sprint 9 技术设计 v1.0：6 个 ADR（指标分钟降采样 / M0 端点实测矩阵 / checkpoint 不落库 / MinIO Client 清理 savepoint / 告警 CDC_PIPELINE 接入 / 404 归并+强制停止）+ 迁移脚本规划（realtime V1.3.0、alert V1.1.0）+ 接口设计（5 新端点 + 1 internal）+ 错误码 8010/8011 + 验收映射 |
| `docs/sprint9/DataNest-Sprint9-原型.{html,css,js}`（新增） | UI 原型：单 HTML 多视图 4 视图——① 详情抽屉「运行监控」页签（KPI×4 + 延迟均值/峰值双系列趋势[30s 阈值虚线] + 吞吐面积趋势 + 1h/6h/24h/7d segmented）；②「检查点」页签（健康度三卡 + 历史表 + Savepoint 区[触发按钮+文件治理说明]）；③ 强制停止确认弹窗（8008 降级入口，DsModal 简洁布局）；④ 告警中心 + 新增规则弹窗（对象类型 CDC 管道 + 三触发条件 + 语义说明盒）。CSS 全部基于真实 tokens.css/tailwind.config.js/真实组件源码新写（未沿用 sprint8 原型 css）；抽屉宽 720px（运行监控图表需要，实现时注意 Drawer width 从 640 调宽）。**2026-08-11 用户反馈后压缩**：运行监控/检查点两页签 1440×900 一屏无上下滚动（Playwright 实测 drawer-body scrollHeight=clientHeight）——图表 SVG 220→170、KPI 卡紧凑（1.25rem 数字/12px padding）、卡片间距 16→12、口径说明压成单行；**同日二次反馈「大屏底部留白」后改弹性填充**：drawer-body 改 flex 列，固定区（页签/筛选/KPI/提示）flex-shrink:0，图表卡与 Checkpoint 历史卡 `flex:1 min-height:0` 随抽屉高度拉伸（SVG `preserveAspectRatio="none"`，对齐 quality-report charts.tsx 既有模式；SVG min-height 140 防过扁），Checkpoint 历史原型行数 6→10（接口实际返回最近 20 条）；900px 不滚动 + 1100px 不留白双尺寸实测通过；实现时对齐这套紧凑+弹性值 |
| `docs/sprint9/DataNest-Sprint9-技术文档.md`（v1.0 → 实施修订） | **2026-08-11 实施核验修正**：① D-D5 修正「alert_history.alert_type 无 CHECK」为**实际有 CHECK**（baseline `alert_history_alert_type_check`），V1.1.0 必须双 CHECK 一并放宽（用户已确认）；② §3.0/§3.2/§9 同步补充 alert_history CHECK 放宽；③ 新增实施记录：minio 8.5.17 版本锁定（Maven Central 核实）、MinIO 8.x `RemoveObjectsArgs.objects()` 需 `List<DeleteObject>` |
| `docs/sprint9/handoff/sprint-9.md`（更新） | 本 Handoff（规划 + F1/F2/F3 后端实施记录 + 自测结论） |
| 后端代码（Sprint 9 F1/F2/F3 + 遗留清零，2026-08-11 实施） | 见 §6「后端实施清单」：common（ErrorCode 8010/8011 + AlertConstants 4 常量）、realtime（V1.3.0 迁移 + CdcMetricMinute/Mapper + FlinkJobService 扩展 + CdcMonitorService 改造 + MetricSnapshotWriter/RetentionCleaner + SavepointFileCleaner + CdcPipelineService 5 方法 + Controller 5 端点 + CdcInternalController names + SourcePrecheckService PG relreplident）、realtime-api（names 契约 + fail-open fallback）、alert（V1.1.0 双 CHECK + AlertRuleService/AlertFiringService 分支）、pom（根 + realtime + alert-service 依赖） |

### 代码现状核验要点（2026-08-11，影响落地路径）

- **监控底座**：`CdcMonitorService` 5s 轮询 RUNNING 管道，一次 `/jobs/{id}` 取状态 + 延迟/累计变更；FAILED→ERROR、CANCELED/FINISHED/SUSPENDED→STOPPED（外部停止）状态机已有；延迟超阈值写 WARN 管道日志（lagWarned 去重、恢复复位）。
- **指标缺口**：吞吐（vertex metrics `numRecordsOutPerSecond`）与重启次数未采集；无指标历史表。
- **Savepoint**：`stopWithSavepoint`（drain=false + 60s 轮询）+ `savepoint_path` 回写 + 启动恢复已有；无手动触发端点；文件物理清理未做（需 S3 客户端）。
- **告警约束**：`alert_rule.object_type` CHECK = DAG/SYNC_JOB/COLLECT_TASK/QUALITY，新增 CDC_PIPELINE 需同步改约束 + `AlertRuleService.validate()` 白名单（AGENTS.md 已记此坑）；AlertApi 已有 `/fired`、`rules/by-object` 等内部端点。
- **双向 Feign**：realtime→alert（触发）+ alert→realtime（对象名反查，需 realtime 新增 internal 批量 id→name 端点）。
- **遗留 TODO 原文**：见 `docs/sprint8/handoff/sprint-8.md` §7（4 项）与 §5 B3/B5。

## 5. Blocker / 待确认点

| # | 事项 | 说明 | 状态 |
|---|------|------|------|
| R1 | Flink 2.2 REST 指标/checkpoint 端点可用性 | ✅ **已通过（M0 实测，2026-08-11）**：vertex metrics（子任务前缀 id，per-second 为 double 需新解析路径）、job metrics（numRestarts 等）、`/jobs/{id}/checkpoints`（counts/summary/latest/history 全结构）、手动 savepoint 触发（**body 必须 kebab-case** `target-directory`/`cancel-job`，与 stop-with-savepoint 的 camelCase 不同）全部可用；详见技术文档 D-D2 | ✅ 已通过 |
| - | 其余产品决策 | T1~T6 已全部确认，无开放产品问题 | ✅ 明确 |

## 6. 开发分阶段计划

> **划分原则**：沿用 Sprint 8——按功能块切分，**每块 = 后端 → 前端 → 测试完整闭环**。
> **顺序**：M0（REST 端点实测，半天内）→ F1（监控深化，realtime 纯增量）→ F2（Checkpoint/Savepoint，引入 S3 客户端）→ F3（告警，依赖 F1 状态机改动就位）。
> **每块验证口径**：① 后端 curl 自测 → ② 前端联调 → ③ 新建 `e2e/sprint9/e2e/*.spec.ts` 跑通 + sprint8 CDC 用例回归 → ④ 更新本 Handoff 看板。

| 阶段 | 范围 | 主要产出 | 验证口径 | 依赖 | 预估人日* |
|------|------|----------|----------|------|-----------|
| **M0 端点实测** | Flink 2.2.1 REST：vertex metrics（吞吐/重启）+ `/jobs/{id}/checkpoints` + savepoint 触发 | 实测结论回落技术文档/PRD R1 | curl 实测 | 无 | ~0.5 |
| **F1 监控深化 + 404 自愈** | 指标历史表（分钟降采样/30 天清理）+ 趋势图端点 + 详情抽屉「运行监控」页签 + 404 连续 3 轮归并外部停止 | realtime 库 V1.1.0 + CdcMonitorService 改造 + 前端页签（手写 SVG 沿用质量报告 charts 模式） | curl → 联调 → `cdc-monitoring.spec.ts` + sprint8 回归 | M0 | ~3.5 |
| **F2 Checkpoint/Savepoint 管理** | checkpoint 历史/健康度端点（实时转发不落库）+ 手动触发 savepoint + 强制停止降级 + savepoint 文件物理清理（S3 客户端） | realtime 库表结构不变（或 V1.2.0 小改）+ 「检查点」页签 | curl → 联调（含 savepoint→停止→启动不丢不重实测）→ E2E | M0 | ~3 |
| **F3 流处理告警** | alert 库 CHECK 加 CDC_PIPELINE + validate 白名单 + realtime 接 alert-api 上报三条件 + realtime internal 对象名反查端点 + 告警规则 UI 对象类型 | alert V1.1.0 + realtime 依赖 alert-api + 前端告警规则页扩展 | curl → 联调（MailHog 断言邮件）→ E2E | F1（触发点依赖监控状态机） | ~2.5 |

> \* 粗粒度参考，总计约 **9.5 人日**。

### ✅ 已完成（规划/设计）

- [x] Sprint 9 范围确认（路线图映射 + 遗留清零 + 监控完整版 + 复用 app-alert）
- [x] T1~T6 产品决策确认
- [x] Sprint 9 PRD v1.0 定稿
- [x] 代码现状核验（监控状态机/告警约束/Feign 契约/前端结构）
- [x] Sprint 9 技术文档 v1.0（6 ADR + 迁移脚本 V1.3.0/V1.1.0 + 接口设计 + 错误码 8010/8011）
- [x] M0 端点实测（Flink 2.2.1 REST 全部通过，三个新坑固化进技术文档 D-D2：double 解析/kebab-case/子任务前缀）
- [x] Sprint 9 UI 原型（4 视图，CSS 基于真实前端 tokens/组件源码新写，Playwright 截图验证通过）
- [x] **F1 后端实施 + curl 自测**（2026-08-11）：V1.3.0 迁移 + FlinkJobService double/job-metrics + CdcMonitorService 累加器/404 归并/三触发点 + MetricSnapshotWriter/RetentionCleaner + metrics/current + metrics/trend（修复 24h 5 分钟桶表达式 `INTERVAL '1 minute'`）+ Controller 2 端点 + shared-realtime.yaml（not-found-threshold/retention-days 已发布 Nacos）
- [x] **F2 后端实施 + curl 自测**（2026-08-11）：minio 8.5.17（根 pom + realtime pom）+ FlinkJobService getCheckpoints/triggerSavepoint/pollSavepointResult 抽取 + SavepointFileCleaner（**MinIO 8.x 需 `List<DeleteObject>` 而非 List\<String\>**）+ checkpoints/savepoints/force-stop 端点 + 删除/编辑/stop/force-stop 四场景文件清理 + 错误码 8010/8011
- [x] **F3 后端实施 + curl 自测**（2026-08-11）：common 4 常量 + alert V1.1.0 双 CHECK + realtime-api names 契约（fail-open）+ CdcInternalController names + AlertRuleService 三分支 + AlertFiringService 三 switch + CdcMonitorService fire 触发点 + 双向 pom 依赖
- [x] **S8 遗留 TODO 清零**（2026-08-11）：①②③ 随 F1/F2 落地并实测；④ SourcePrecheckService 补 relreplident（CdcSourceTableDTO.replicaIdentityFull）

### ⬜ 下一步（Next Action）

- [ ] **前端联调**：CdcPipelineDetailDrawer 改多页签（运行监控/检查点）+ 趋势图手写 SVG + stop 8008 → force-stop 确认 + AlertRuleModal CDC 对象类型（依赖后端已就绪，原型已定稿）
- [ ] **E2E**：新建 `e2e/sprint9/e2e/`（监控趋势/检查点页签/手动 savepoint/强制停止/告警邮件 MailHog 断言）；回归 sprint8 `cdc-pipeline.spec.ts` 23 用例
- [ ] **补验**：① 404 归并外部停止（需重启 Flink 集群制造 404）；② PG 源 REPLICA IDENTITY FULL 表级提示（SQL 已实现，待 PG 源管道实测）

### ✅ 代码 Review（2026-08-11，独立 reviewer 子代理）

| 级别 | 问题 | 修复 |
|------|------|------|
| Critical | **累加器从未重置**：flush 后不清零，分钟数据变成「启动至今累计均值/最大」（趋势失真、lagMax 单调不减） | ✅ `MetricAccumulator` 改 synchronized `accumulate` + `snapshotAndReset()`（原子读后重置，lag/吞吐清零、restarts/totalChanges 保留最新值）；`MetricSnapshotWriter` 改用快照 API。**实测验证**：三分钟吞吐 0.073 → 0.975 → 2.708 行/秒独立聚合 |
| Important | 累加器无 volatile/锁（poll 写 flush 读竞态） | ✅ 随 Critical 一并 synchronized 解决 |
| Important | `notFoundCounts` 以 pipelineId 为 key，跨运行残留可致新作业首个 404 误判 | ✅ key 改 flinkJobId（新作业 id 自动失效旧计数，无需跨服务耦合） |
| Important | S3 清理早于 DB 提交（update/delete/stop/forceStop/triggerSavepoint），回滚后路径指向已删文件 | ✅ 统一挪到 DB 更新成功之后：事务方法用 `TransactionSynchronizationManager.afterCommit`，非事务方法在 update 后直接清理；新增 `afterCommitCleanSavepoint` 辅助方法。**实测验证**：删除带 savepoint 管道后 MinIO 文件已清理 |
| Minor | `(java.sql.Timestamp)` 强转依赖驱动类型 | ✅ instanceof 双分支兜底 |
| Minor | LAG_EXCEEDED/EXTERNAL_STOP 未限制仅 CDC 管道对象 | ✅ validate 加对象类型匹配校验 |
| Minor | 单 sink 抖动丢整轮吞吐样本；PRD「重启取增量」vs 实现「记最新」（跟随 D-D1） | 不改（可接受；PRD 措辞后续对齐） |

修复后全量编译通过、重新部署；实测：分钟独立聚合、stop 正常、删除级联清理（afterCommit）、无孤儿作业残留。

## 7. 备注 / 已知坑提醒

- **alert_rule.object_type CHECK 约束**：新增 CDC_PIPELINE 必须同步改 DB 约束 + `AlertRuleService.validate()` 白名单（AGENTS.md §6 告警小节）。**Sprint 9 追加：alert_history.alert_type 也有 CHECK（FAILURE/TIMEOUT/SUCCESS），新告警类型必须一并放宽**（V1.1.0 已做）。
- **Flink 2.2 REST 差异**：无 `/jobs/{id}/vertices` 子资源；stop-with-savepoint body 无 formatType；无 `SavepointRestoreSettings` 类（见 AGENTS.md §6 实时 CDC 小节）。**Sprint 9 追加（M0 实测）**：vertex per-second 指标是 **double**（Long 解析会丢）；手动 savepoint 触发 body 是 **kebab-case**（`target-directory`/`cancel-job`），与 stop 的 camelCase 不同；cancel 作业用 `PATCH /jobs/{id}` body `{"mode":"cancel"}`（POST /cancel 404）。
- **MinIO 8.x**：`RemoveObjectsArgs.objects()` 需 `Iterable<DeleteObject>`（`List<String>` 编译不过）；savepoint 路径解析 `s3a://bucket/prefix`。
- **force-stop 语义边界**：仅「作业已丢失」场景使用（不 cancel 存活作业，存活作业会成孤儿占 slot）——前端在 stop 8008 时弹确认提示。
- **指标历史表防膨胀**：严禁 5s 轮询直写；内存聚合 + 分钟降采样（T1）。
- **告警 fail-open**：app-alert 不可达只记管道日志，不阻断监控主流程（对齐告警跨域既有约定）。
- **审计字段约定**：新表 `updated_at` 不加 DB 默认值，create 只设 created_by/created_at。
- **E2E 设施**：复用 sprint8 seed/用户与 `helpers/db.ts`（realtime 库映射已存在则复用，新增表需补 TABLE_DB 映射：cdc_metric_minute）。
- **环境提醒**：Flink Session 集群仅 1 slot，全链路验证必须串行；当前存在「验收_用户实时同步」管道占用 slot（RUNNING），后端联调/E2E 前需确认其状态。
