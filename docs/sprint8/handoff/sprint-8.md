# Sprint 8 Handoff

> **更新时间**：2026-08-09 | **阶段**：规划/设计完成（PRD v1.2 + 技术文档 v1.1 + UI 原型完成）→ 待实现
> **Sprint 主题**：资产目录深化 + 实时 CDC 管道 + 质量报告（三大模块均为 P0）

## 1. Sprint 目标

在 Sprint 7 资产目录「找得到、看得懂、敢使用」基础上，让资产目录**可协作、可沉淀、可引导**（标签/收藏/关注/评论/热度）；打通平台第一条**实时数据链路**（MySQL Binlog → Iceberg 湖仓 → Doris 外部表，架构级新增 realtime-service + MinIO + Iceberg）；为治理侧补齐**质量报告完整版**（多维趋势 + 问题清单 + 导出），让质量成果「可汇报、可考核」。

## 2. 状态看板

| 事项                                     | 状态      | 说明                                                                                          |
|------------------------------------------|-----------|-----------------------------------------------------------------------------------------------|
| Sprint 8 产品范围确认                    | ✅ 完成   | 用户确认：严格按规格 §15 路线图映射 = **资产目录深化 + 实时 CDC 管道**，另纳入质量报告 DG-07 完整版 |
| Sprint 8 产品决策确认（T1~T5）           | ✅ 完成   | T1 Doris+Iceberg 湖仓层 / T2 独立库 datanest_realtime / T3 quality_score_history / T4 删除语义 / T5 报告入口权限 |
| Sprint 8 技术决策确认（D1~D6 + B1/B2/B4）| ✅ 完成   | 见 §3 技术决策                                                                                 |
| Sprint 8 代码现状调查                    | ✅ 完成   | 复用点与改造点经代码核验（资产协作 6 表从零、collect_change_detail 已有、quality_score 仅存最新、无 realtime 服务、Doris 4.0.7-rc02） |
| Sprint 8 PRD                             | ✅ 完成   | `docs/sprint8/DataNest-Sprint8-PRD.md`（v1.2，2026-08-09）                                     |
| Sprint 8 技术设计                        | ✅ 完成   | `docs/sprint8/DataNest-Sprint8-技术文档.md`（v1.1，含 6 个 ADR D1~D6）                         |
| Sprint 8 UI 原型                         | ✅ 完成   | `DataNest-Sprint8-原型.{html,css,js}`（单 HTML 多视图，7 视图 prototype-switch 切换；渲染已用 Playwright 验证，临时截图/脚本已清理） |
| F1 资产目录深化（DC-06~09）              | ⏳ 未开始 | 后端 + 前端 + E2E                                                                              |
| F2 实时 CDC 管道（DI-04/RC-01）          | ⏳ 未开始 | 架构级新增 realtime-service + MinIO + Iceberg + Flink 内嵌                                     |
| F3 质量报告（DG-07 完整版）              | ⏳ 未开始 | 报告聚合接口 + 评分历史表 + 前端报告页                                                         |
| 联调验证                                 | ⏳ 未开始 | 每块内部：接口先 Postman/curl 自测，再联调前端，再 E2E                                          |
| Sprint 8 Handoff                         | 🔄 进行中 | 本文档（规划/设计阶段记录）                                                                   |

## 3. 关键决策（用户已确认）

### 产品决策（2026-08-09 两轮确认）

| 决策点             | 结论                                                                 |
|--------------------|----------------------------------------------------------------------|
| Sprint 8 主题边界  | 严格按规格文档 §15 路线图映射：实际 S8 = 路线图 S9 = **资产目录深化 + 实时 CDC 管道** |
| 实时 CDC 处置      | **并入本期，架构级新增**：新增 realtime-service + Flink CDC MiniCluster，完整实现 CDC 管道 |
| 质量报告 DG-07     | **完整版**：多维（表/库/数据源/任务）+ 时间趋势（四档分布、评分趋势）+ 问题清单 + CSV 导出 |
| T1 CDC 目标落点    | **Doris + Iceberg 湖仓层**：新增 MinIO + Iceberg（**Hadoop Catalog**，元数据随数据落 MinIO），CDC 先入湖再 Doris 外部表查询 |
| T2 realtime 数据存储 | **独立新库 `datanest_realtime`**（第 5 个业务库，独立 Flyway）        |
| T3 评分趋势数据     | **新增 `quality_score_history`**：批次结束写快照，存量从 check_detail 补算 |
| T4 删除语义         | **表删除级联清理**标签/收藏/关注/评论；**用户删除保留评论**（标记"已注销"）、删收藏/关注 |
| T5 质量报告入口权限 | 独立「质量报告」菜单（治理组）；查看 ALL_ROLES、导出治理员/超管       |

### 技术决策（2026-08-09，含技术调研 + 实测验证，落地于技术文档）

| #  | 决策点               | 结论                                                                                                          |
|----|----------------------|---------------------------------------------------------------------------------------------------------------|
| D1 | Flink 版本选型       | **Flink 2.0.x + Flink CDC 3.4.x**（官方支持 Java 21，匹配项目 JDK 21）；CDC 3.4 新增 Iceberg Sink。容器内验证依赖，阻塞则降级 1.20+3.3（JVM 降 17） |
| D2 | realtime-service 边界 | 独立服务 + 内嵌 Flink MiniCluster + Gateway `/api/realtime/**`；**独立新库 datanest_realtime**；源连接经 engineering 内部端点 Feign 读 |
| D3 | CDC 数据链路         | MySQL Binlog → Iceberg 湖仓（**Hadoop Catalog** + MinIO，元数据随数据落 MinIO 避免第 6 个业务库）→ **Doris Iceberg Catalog** 外部表查询 |
| D4 | 资产协作数据模型     | governance 库新增 6 表（tag/table_tag/favorite/follow/comment/view_log）；标签打通 Sprint 7 search 预留维度；关注通知复用 collect_change_detail，不新建通知表 |
| D5 | 热度埋点             | governance 库按天聚合（asset_view_log upsert 累加），不引入 Redis 计数（最小改动） |
| D6 | 质量报告聚合         | governance 本地 SQL 聚合 + `quality_score_history` 快照（ScoreCalculator 批次结束写）+ CSV 复用合规导出经验（BOM） |
| B1 | Flink 版本定稿       | ✅ 用户确认按 Flink 2.0.x + CDC 3.4.x 实现；降级点已预留                                             |
| B2 | Doris 版本           | ✅ 实测 **doris-4.0.7-rc02**（`SHOW CATALOGS` 正常，Multi-Catalog 可用，≥1.2 满足）                         |
| B4 | 评论「已注销」       | ✅ 前端批量回填用户名（SystemUserApi.usernames），user_id 查无回退「已注销」，零后端改动                      |

## 4. 变更清单（规划/设计阶段）

| 文档/产物                                                        | 变更说明                                                                                         |
|-------------------------------------------------------------------|--------------------------------------------------------------------------------------------------|
| `docs/sprint8/DataNest-Sprint8-PRD.md`（新增）                   | Sprint 8 产品文档 v1.0 → v1.1（T1~T4 交互确认）→ v1.2（B1/B2/B4 + Hadoop Catalog 口径统一）。对齐 Sprint7 PRD 范本，13 章 |
| `docs/sprint8/DataNest-Sprint8-技术文档.md`（新增）               | Sprint 8 技术设计文档 v1.0 → v1.1（B1/B2/B4 定稿 + Iceberg 部署形态说明）。10 章，含 6 个 ADR、迁移脚本规划、接口设计、部署方案 |
| `docs/sprint8/handoff/sprint-8.md`（新增）                        | 本 Handoff                                                                                       |
| `docs/sprint8/DataNest-Sprint8-原型.html/css/js`（新增）| UI 原型：**单 HTML 多视图**（沿用 Sprint 7 范本），prototype-switch 切换 7 视图：数据资产（标签云 + 热门 Top10）、资产详情（标签/收藏/关注/评论/热度）、我的收藏、我的关注（表变更动态）、CDC 管道列表（含日志抽屉）、CDC 新建向导（4 步）、质量报告（**Dashboard 一屏版**：KPI×5 + 四档趋势 + 评分分布环图 + 数据源对比横向条 + 表评分趋势 + 问题清单 TOP5，无滚动）；视觉严格对齐真实 ds-* token + antd 组件结构（accent indigo #4f46e5、圆角 8/12/16px、表头 11px 大写、表格 10px 16px） |

### 代码现状核验要点（2026-08-09，影响落地路径）

- **资产协作**：Sprint 7 `AssetCatalogController`（`/assets`，8 端点，四角色 OR）；`AssetSearchItemDTO` 无 tags/热度字段；`search` 已预留标签维度；无收藏/关注/评论/热度实体——6 表从零建。
- **变更动态**：`collect_change_detail`（`ADDED_TABLE/DELETED_TABLE/MODIFIED_TABLE` + database/schema/table 三元组）已有，关注通知直接复用。
- **评分**：`quality_score`（`uk(table_id)` 仅最新）+ `ScoreCalculator.recalculateForTables`（批次收尾 upsert）；无历史——新增 `quality_score_history`，写入口在 ScoreCalculator。
- **数据源**：`datasource_connection` 表在 engineering，内部端点 `GET /internal/datasources/{id}` 返回全字段含 `encryptedPassword`（realtime 拉源连接复用）。
- **Doris**：实测 **4.0.7-rc02**，`SHOW CATALOGS` 正常，Multi-Catalog 可用；`shared-doris.yaml` 已有 fe-host 192.168.119.135。
- **无 realtime 服务**：Flink/MinIO/Iceberg 均未落地，本期架构级新增。
- **Flyway 版本**：governance 最高 V1.3.0；engineering 最高 V1.5.0；本期 governance 新增 V1.4.0/V1.5.0，realtime 新库 V1.0.0。

## 5. Blocker / 待实现确认点

| #  | 事项                                | 说明                                                                      | 状态   |
|----|-------------------------------------|---------------------------------------------------------------------------|--------|
| B1 | Flink 2.0 + CDC 3.4 + Iceberg 依赖兼容 | ✅ 已定稿（用户确认）：按 Flink 2.0.x + CDC 3.4.x 实现；容器内跑最小示例验证（`flink-cdc-pipeline-connector-mysql` / `-iceberg` 与 Flink 2.0.x、Java 21 兼容）；阻塞则降级 1.20+3.3 | 明确（实现时验证） |
| B2 | Doris 版本 Iceberg Catalog 支持 | ✅ 已确认：**doris-4.0.7-rc02**（2026-08-09 实测 `SHOW CATALOGS` 正常）；`CREATE CATALOG ... TYPE=iceberg` + `s3.*` 属性对接 MinIO | 明确（≥1.2 满足） |
| B3 | 存量评分历史补算 | V1.5.0 不做迁移内补算；一次性 job 从 `quality_check_detail` 补写 `quality_score_history`（复用 ScoreCalculator 算法）；补算范围与触发方式实现时细化 | 待实现 |
| B4 | 删除用户时评论「已注销」 | ✅ 已定稿（用户确认）：前端批量回填用户名，user_id 查无显示「已注销」，零后端改动 | 明确 |
| B5 | 评论阈值字段回填 | `quality_check_detail` 无阈值字段，问题清单按 `rule_id` 回填 `quality_rule` 阈值，历史 rule 已删则缺省 | 明确（按 rule_id 回填） |
| B6 | Flink 依赖 jar 版本矩阵 | 需在容器内锁定 flink-cdc-pipeline-connector-mysql/iceberg 与 Flink 2.0.x 精确版本，shaded jar 冲突排查 | 实现时验证 |

## 6. 开发分阶段计划

> **划分原则**：按功能块切分，**每块 = 后端 → 前端 → 测试 完整闭环**。不做"先全部后端、再全部前端"的横切。
> **顺序**：F1（资产目录深化，纯 governance 增量，无架构风险）→ F3（质量报告，复用现有质量底座）→ F2（实时 CDC，架构级新增，依赖 B1/B2 容器验证，风险最高放最后）。
> **每块验证口径**：① 后端 Postman/curl 自测 → ② 前端联调 → ③ 新建 `e2e/sprint8/e2e/*.spec.ts` 跑通 → ④ 更新本 Handoff 状态看板。

---

### ✅ 已完成（规划/设计）

- [x] Sprint 8 PRD（`DataNest-Sprint8-PRD.md` v1.2）
- [x] Sprint 8 技术设计（`DataNest-Sprint8-技术文档.md` v1.1）
- [x] Sprint 8 UI 原型（`DataNest-Sprint8-原型.{html,css,js}`：7 视图，含 SVG 趋势图 + CDC 向导 + 日志抽屉；Playwright 验证渲染 OK、无 JS 错误）
- [x] 代码现状核验：资产协作 6 表从零、collect_change_detail 可复用、quality_score 仅最新、无 realtime 服务、Doris 4.0.7-rc02 实测
- [x] 技术调研：Flink 2.0 支持 Java 21 / CDC 3.4 Iceberg Sink / Doris Iceberg Catalog 支持
- [ ] UI 原型（制作中）

---

### 6.1 F1 资产目录深化（P0，DC-06~09）

**范围**：数据标签 / 收藏与关注（含变更动态）/ 评论与讨论 / 热度排行。
**块内依赖**：governance Flyway V1.4.0 → governance 服务（协作 6 表 entity/mapper + AssetCollaborationService）→ 前端 4 处（详情页扩展 + 我的收藏/关注 2 页 + 首页热度）→ 联调。

**后端**
- [ ] Flyway `V1.4.0`（governance）：asset_tag / asset_table_tag / asset_favorite / asset_follow / asset_comment / asset_view_log（§3.1，updated_at 无默认值）
- [ ] common：`ErrorCode` 4021~4024
- [ ] governance `AssetCollaborationService`：打标签（复用字典）/收藏/关注（uk 幂等）/评论（软删 deleted）/热度埋点（按天 upsert）；「我的收藏」「我的关注」（关注页含 collect_change_detail 变更动态）分页
- [ ] `AssetCatalogController` 扩展 13 端点（§5.1）；`browse` 补 `tag` 筛选 + `sort=hot`；`search`/`browse` 回填 `tags`
- [ ] 删除校验：删表级联清理协作数据（metadata_table 删除钩子）
- [ ] 重建 governance + 部署 + curl 自测

**前端**
- [ ] `Sidebar.tsx`：「数据资产」组新增「我的收藏」「我的关注」（ALL_ROLES）
- [ ] `router/index.tsx`：`/asset-catalog/favorites`、`/asset-catalog/follows`
- [ ] 资产详情页扩展：标签区（打/删）、收藏/关注按钮、评论页签、热度展示
- [ ] 我的收藏/关注页（复用资产卡片 + 变更动态列表）
- [ ] `types/asset.ts` 扩展（tags/热度）、`api/asset.ts` 新端点

**测试**
- [ ] curl 自测 → 前端联调 → `e2e/sprint8/e2e/asset-collaboration.spec.ts`

---

### 6.2 F3 质量报告（P0，DG-07 完整版）

**范围**：多维报告（数据源/库/质量任务/时间）+ KPI + 四档趋势 + 评分趋势 + 问题清单 + CSV 导出。
**块内依赖**：governance Flyway V1.5.0 → ScoreCalculator 写历史 → QualityReportService/Controller → 前端 1 页 → 联调。

**后端**
- [ ] Flyway `V1.5.0`（governance）：quality_score_history（idx(table_id, checked_at)）
- [ ] `ScoreCalculator` 批次收尾追加写历史快照
- [ ] 存量补算一次性 job（复用 ScoreCalculator 算法，从 quality_check_detail 聚合写首次快照）
- [ ] `QualityReportService` + `QualityReportController`（`/quality/report`：options/summary/level-trend/score-trend/issues/export，6 端点，§5.3）
- [ ] CSV 导出（UTF-8 BOM，复用 Sprint 6 合规导出经验）
- [ ] common：`ErrorCode` 4221/4222
- [ ] 重建 governance + 部署 + curl 自测

**前端**
- [ ] `Sidebar.tsx`：「数据治理」组新增「质量报告」（查看 ALL_ROLES，导出 GOVERNANCE_WRITE_ROLES）
- [ ] `router/index.tsx`：`/governance/quality-report`
- [ ] 质量报告页：筛选区（联动）+ KPI 卡 + 四档趋势图 + 评分趋势图 + 问题清单表 + 导出按钮
- [ ] `types/quality-report.ts` + `api/quality-report.ts`

**测试**
- [ ] curl 自测 → 前端联调 → `e2e/sprint8/e2e/quality-report.spec.ts`

---

### 6.3 F2 实时 CDC 管道（P0，架构级新增）

**范围**：realtime-service + MinIO + Iceberg 湖仓 + CDC 管道配置向导/监控 + Doris 外部表查询。
**块内依赖**：B1/B2 容器验证 → realtime 新库 Flyway V1.0.0 → realtime-service（Flink 内嵌）→ engineering 删除校验 → 前端 CDC 管道页 → 联调。

**前置验证（实现首步）**
- [ ] 容器内最小示例：Flink 2.0 + CDC 3.4 MySQL→Iceberg（MinIO）→ Doris 外部表查询（B1/B2 落地）
- [ ] docker-compose 新增 middleware-minio + app-realtime；PG 建 datanest_realtime 库；Doris `CREATE CATALOG`（§7.2）

**后端**
- [ ] realtime 库 Flyway `V1.0.0`（cdc_pipeline / cdc_pipeline_table / cdc_pipeline_log）
- [ ] `data-nest-realtime` 骨架（pom/application.yml/FlywayConfig，PG_DATABASE=datanest_realtime）
- [ ] `CdcPipelineService` + `CdcPipelineController`（`/cdc/pipelines` 10 端点，§5.2）：预检（连通/binlog）/建管/启停（Flink 作业提交 cancel）/监控（延迟/变更数轮询回写）/日志
- [ ] Flink CDC YAML Pipeline 组装 + 内嵌 MiniCluster 提交；Iceberg Hadoop Catalog Sink（MinIO）
- [ ] realtime-api Feign 契约（internal 端点：按 datasourceId 查管道引用）
- [ ] engineering 数据源删除前置校验（经 realtime-api，fail-closed，8009）
- [ ] common：`ErrorCode` 8001~8009
- [ ] 重建 + 部署 + curl 自测 + 手工 E2E（test-mysql 建表 → 管道 → 湖仓 → Doris 可查 → 变更秒级可见）

**前端**
- [ ] `Sidebar.tsx`：「数据工程」组新增「CDC 管道」（查看 ALL_ROLES，写按钮 ENGINEERING_WRITE_ROLES）
- [ ] `router/index.tsx`：`/engineering/cdc-pipelines`
- [ ] CDC 管道页：向导（4 步：基本信息/源/目标/确认启动）+ 列表（状态/延迟/变更数）+ 日志抽屉 + 启停/编辑/删除
- [ ] `types/cdc.ts` + `api/cdc.ts`

**测试**
- [ ] curl 自测 → 前端联调 → `e2e/sprint8/e2e/cdc-pipeline.spec.ts`

---

### 6.4 收尾（全部块完成后）

- [ ] 全量回归：`docker compose up -d` 全部服务 + 前端 build + 各块 E2E 全跑
- [ ] 代码审查 + 更新 AGENTS.md / docs/agent（如需；新增 realtime 服务/第 5 库/MinIO 需同步架构文档）
- [ ] 更新 §2 看板全部置 ✅ + 本文档归档

## 7. 备注 / 已知坑提醒

- **Flink 版本**：Flink 2.0 是新大版本，Java 21 支持官方确认；容器验证优先，B1 降级点（1.20+3.3，JVM 17）已预留。
- **Doris**：4.0.7-rc02 实测支持 Multi-Catalog；建 Iceberg Catalog 用 Hadoop + `s3.*` 属性指向 MinIO。
- **Iceberg 非独立服务**：写入端内嵌在 realtime-service Flink 作业，存储落 MinIO，读取端 Doris 内置——本期新增独立基础设施容器只有 MinIO。
- **迁移脚本**：governance 新脚本从 V1.4.0 起；datanest_realtime 新库 V1.0.0 baseline；统一紧凑单行风格；审计字段（updated_at 无 DB 默认值，create 只设 created_by/created_at）。
- **消费方重建**：改到 task-core 共享模块必须全量重建消费方；realtime 是共享执行侧新服务，本次不涉及 task-core 改动（CDC 逻辑全在 realtime 内）。
- **删除语义**：删表级联清理标签/收藏/关注/评论；删用户保留评论（前端「已注销」兜底）——F1 实现时注意 metadata_table 删除钩子。
- **E2E 设施**：复用 sprint7 的 `helpers/db.ts`（拆库版 psql，按表路由域库）；新增业务表需补 sprint5 `TABLE_DB` 映射。
