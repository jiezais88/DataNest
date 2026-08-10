# Sprint 8 Handoff

> **更新时间**：2026-08-10 | **阶段**：F1 完成（后端 + 前端 + E2E 15/15）；F2 后端完成（两轮实测 + 评审修复）→ F2 前端 / F3 待开发
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
| F1 资产目录深化（DC-06~09）              | ✅ 完成   | 后端 curl 自测通过 + 前端完成 + E2E `asset-collaboration.spec.ts` 15/15 通过（2026-08-10）；后端补齐 5 项缺口（sort=latest/收藏关注筛选/viewCount 全场景回填/搜索标签维度/导出收藏 CSV），前端滚动体验优化（列宽压缩 + 表名左冻结 + 细滚动条 + 热门面板可折叠） |
| F2 实时 CDC 管道（DI-04/RC-01）          | 🔄 前端完成 | 后端两轮实测通过（2026-08-10）；前端完成 + 联调调通 + Review 修复（2026-08-10）：列表页 + 4 步向导整页 + 日志抽屉 + 统计卡；后端小补 description/stats/source-tables/page 回填 tables；E2E 另一会话负责 |
| F3 质量报告（DG-07 完整版）              | ⏳ 未开始 | 报告聚合接口 + 评分历史表 + 前端报告页                                                         |
| 联调验证                                 | ⏳ 未开始 | 每块内部：接口先 Postman/curl 自测，再联调前端，再 E2E                                          |
| Sprint 8 Handoff                         | 🔄 进行中 | 本文档（规划/设计阶段记录）                                                                   |

## 3. 关键决策（用户已确认）

### 产品决策（2026-08-09 两轮确认）

| 决策点             | 结论                                                                 |
|--------------------|----------------------------------------------------------------------|
| Sprint 8 主题边界  | 严格按规格文档 §15 路线图映射：实际 S8 = 路线图 S9 = **资产目录深化 + 实时 CDC 管道** |
| 实时 CDC 处置      | **并入本期，架构级新增**：新增 realtime-service + **独立 Flink 集群**（Session，JobManager+TaskManager），完整实现 CDC 管道 |
| 质量报告 DG-07     | **完整版**：多维（表/库/数据源/任务）+ 时间趋势（四档分布、评分趋势）+ 问题清单 + CSV 导出 |
| T1 CDC 目标落点    | **Doris + Iceberg 湖仓层**：新增 MinIO + Iceberg（**Hadoop Catalog**，元数据随数据落 MinIO），CDC 先入湖再 Doris 外部表查询 |
| T2 realtime 数据存储 | **独立新库 `datanest_realtime`**（第 5 个业务库，独立 Flyway）        |
| T3 评分趋势数据     | **新增 `quality_score_history`**：批次结束写快照，存量从 check_detail 补算 |
| T4 删除语义         | **表删除级联清理**标签/收藏/关注/评论；**用户删除保留评论**（标记"已注销"）、删收藏/关注 |
| T5 质量报告入口权限 | 独立「质量报告」菜单（治理组）；查看 ALL_ROLES、导出治理员/超管       |

### 技术决策（2026-08-09，含技术调研 + 实测验证，落地于技术文档）

| #  | 决策点               | 结论                                                                                                          |
|----|----------------------|---------------------------------------------------------------------------------------------------------------|
| D1 | Flink 版本选型       | **Flink 2.2.1 + Flink CDC 3.6.0**（2026-08-09 修订：官方配对 + JDK 21 匹配；原案 Flink 2.0+3.4 官方兼容矩阵不覆盖）；connector 坐标 **`3.6.0-2.2`**（shaded 自包含，M0 已实测）；补 `mysql-connector-java:8.0.27`、按需 `flink-shaded-hadoop-2-uber`。容器内验证依赖，阻塞则降级 1.20+3.3（JVM 降 17） |
| D2 | realtime-service 边界 | 独立服务 + **独立 Flink Session 集群**（REST 提交，不内嵌）+ Gateway `/api/realtime/**`；**独立新库 datanest_realtime**；源连接经 engineering 内部端点 Feign 读 |
| D3 | CDC 数据链路         | MySQL Binlog → Iceberg 湖仓（**Hadoop Catalog** + MinIO，元数据随数据落 MinIO 避免第 6 个业务库）→ **Doris Iceberg Catalog** 外部表查询 |
| D4 | 资产协作数据模型     | governance 库新增 6 表（tag/table_tag/favorite/follow/comment/view_log）；标签打通 Sprint 7 search 预留维度；关注通知复用 collect_change_detail，不新建通知表 |
| D5 | 热度埋点             | governance 库按天聚合（asset_view_log upsert 累加），不引入 Redis 计数（最小改动） |
| D6 | 质量报告聚合         | governance 本地 SQL 聚合 + `quality_score_history` 快照（ScoreCalculator 批次结束写）+ CSV 复用合规导出经验（BOM） |
| B1 | Flink 版本定稿       | ✅ 用户确认按 **Flink 2.2.1 + CDC 3.6.0** 实现（2026-08-09 修订，官方配对）；降级点已预留                                             |
| B2 | Doris 版本           | ✅ 实测 **doris-4.0.7-rc02**（`SHOW CATALOGS` 正常，Multi-Catalog 可用，≥1.2 满足）                         |
| B4 | 评论「已注销」       | ✅ 前端批量回填用户名（SystemUserApi.usernames），user_id 查无回退「已注销」，零后端改动                      |

## 4. 变更清单（规划/设计阶段）

| 文档/产物                                                        | 变更说明                                                                                         |
|-------------------------------------------------------------------|--------------------------------------------------------------------------------------------------|
| `docs/sprint8/DataNest-Sprint8-PRD.md`（新增）                   | Sprint 8 产品文档 v1.0 → v1.1（T1~T4 交互确认）→ v1.2（B1/B2/B4 + Hadoop Catalog 口径统一）→ **v1.3（B1 版本组合修订：Flink 2.2.1 + CDC 3.6.0）**。对齐 Sprint7 PRD 范本，13 章 |
| `docs/sprint8/DataNest-Sprint8-技术文档.md`（新增）               | Sprint 8 技术设计文档 v1.0 → v1.1（B1/B2/B4 定稿 + Iceberg 部署形态说明）→ **v1.2（B1 版本组合修订：Flink 2.2.1 + CDC 3.6.0）→ v1.3（部署形态修订：内嵌 MiniCluster → 独立 Flink Session 集群）→ v1.4（依赖坐标锁定）→ v1.5（M0 完成：B1/B2/B6 实测通过 + 已知坑固化）**。10 章，含 6 个 ADR、迁移脚本规划、接口设计、部署方案 |
| `docs/sprint8/handoff/sprint-8.md`（新增）                        | 本 Handoff                                                                                       |
| F1 后端代码（2026-08-10，新增/修改）                              | governance：V1.4.0 迁移脚本 + 协作 6 实体/Mapper + `AssetCollaborationService` + 9 个 DTO；`AssetCatalogController` 扩展 16 端点、`AssetCatalogService`（tags 回填/tag 筛选/sort=hot，`backfill`/`toItemDTO` 转 public 复用）；删除钩子（`MetadataWriteService.remove`/`InternalDatasourceService.cascadeDelete`）；common `ErrorCode` 4021~4024；技术文档 v1.6 回落（collaboration 端点/comment 补字段/tag 传名） |
| F2 后端代码（2026-08-10，新增/修改）                              | 新增 `data-nest-realtime-api`（CdcPipelineApi 契约 + fail-closed fallback）与 `data-nest-realtime` 服务（26 源文件：管道 CRUD/启停/监控/日志 + FlinkJobService/YamlBuilder/SourcePrecheck/DorisCatalog）；realtime 库 V1.0.0 三表；common `ErrorCode` 8000~8009；engineering 删除数据源 CDC 引用校验（8009）；网关路由 `/api/realtime/**` + swagger 聚合；`shared-realtime.yaml`/`shared-minio.yaml`（Nacos 已发布）；`docker/realtime.Dockerfile` + compose app-realtime；`datanest_realtime` 库已建。技术文档 v1.7 + gotchas + AGENTS.md + architecture.md 已回落 |
| F1 后端补齐（2026-08-10 前端联调前用户确认补 5 项缺口）           | ① browse `sort=latest`（updated_at 降序，DB 层排序）；② `my-favorites`/`my-follows` 补 keyword/datasourceId/healthLevel 筛选（`AssetCatalogService.matchTableIds` 反查表 ID 集合，null=不过滤/空=无命中空页）；③ `viewCount` 改为 `backfill` 全场景统一回填（搜索/浏览/收藏/关注/热门，无访问为 0）；④ `search` 新增标签名命中维度（权重 40 与字段同级，`searchAssetTables` 加 `tagHitTableIds`）；⑤ 新增 `GET /my-favorites/export`（CSV BOM，复用合规导出文件名/转义模式）。均已 curl 自测通过并重建 app-governance |
| F1 前端代码（2026-08-10，新增/修改）                              | `types/asset.ts` + `api/asset.ts` 扩展（协作 13 个 API + 7 个类型）；详情页 `CollaborationBar.tsx`（标签打/删 + 收藏/关注）+ `CommentsTab.tsx`（发表/删除/分页）+ 第 4 张热度卡 + 会话级埋点去重；资产首页标签云筛选 + 排序（默认/热度/最新/评分，搜索态禁用）+ 标签/热度两列 + 热门 Top10 面板 + `?tag=` 跳转支持；新页 `favorites/index.tsx`（筛选 + 导出 CSV + 取消收藏）/ `follows/index.tsx`（变更动态摘要 + 取消关注）；router + Sidebar 两菜单（ALL_ROLES） |
| F1 E2E（2026-08-10，新增）                                        | `e2e/sprint8/e2e/asset-collaboration.spec.ts`（复用 sprint7 seed/data + sprint6 Api/gotoAs；协作数据 e2e_s8 前缀自播种自清理）；sprint5/sprint6 `helpers/db.ts` TABLE_DB 补协作 6 表映射 |
| F1 文档回落（2026-08-10）                                          | 技术文档 §5.1（5 项后端补齐口径）；PRD §6.3.1（导出收藏）/§6.5（热度统一 30 天窗口）；原型 html（热度文案 7 天/本周 → 近 30 天）；`conventions-frontend.md` 高度策略补 `ds-table-fill` 约定 |
| 回归修复（2026-08-10）                                             | sprint7 `asset-catalog.spec.ts` 血缘回跳断言修复：`53065d6` 把血缘页「← 返回」改为带 `?tab=lineage`，Playwright glob 匹配含 query 串导致 `waitForURL` 超时（存量问题，非 F1 引入）；断言对齐新行为。最终回归 sprint7 26 + sprint8 15 + 其余共 **47/47 通过** |
| F1 前端 Code Review 修复（2026-08-10，子代理 Review 结论 With fixes，无 Critical） | Important：① Long 计数字段（viewCount/refCount/viewCount30d/commentCount）TS 类型 number→string（后端 Long 全量序列化为 string，已 curl 实证）；② CSV 导出 blob 错误检出（业务异常 Result JSON 会被存成假 CSV）——收敛 `utils/download.ts` 的 `downloadCsvBlob`，收藏页 + 合规页一起换掉。Minor 顺手修：聚合未返回禁点收藏/关注 + 拆分 toggling 锁、`?tag=` 一次性消费 + 首屏双请求消除、评论发表回第 1 页/删空页回退。规范已回落 `conventions-frontend.md`。修复后复跑 sprint8 E2E **15/15 通过**（chip 跳转用例断言对齐 ?tag= 一次性消费新行为） |
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
| B1 | Flink 2.2 + CDC 3.6 + Iceberg 依赖兼容 | ✅ **已通过（M0 实测，2026-08-09）**：Flink 2.2.1 + CDC 3.6.0，`flink-cdc.sh -t remote` 提交到独立 Session 集群跑通 MySQL→Iceberg→Doris 全链路（含实时增量）；依赖矩阵固化进 `datanest-flink:2.2.1` 镜像；阻塞则降级 1.20+3.3（未触发） | ✅ 已通过 |
| B2 | Doris 版本 Iceberg Catalog 支持 | ✅ **已通过（M0 实测）**：Doris `CREATE CATALOG datalake_catalog`（TYPE=iceberg, hadoop, s3a warehouse）→ `SHOW TABLES FROM datalake_catalog.testdb` 可见 users 表，SELECT 返回 3 行；`s3.endpoint` 用 `192.168.119.1:9000` | ✅ 已通过 |
| B3 | 存量评分历史补算 | V1.5.0 不做迁移内补算；一次性 job 从 `quality_check_detail` 补写 `quality_score_history`（复用 ScoreCalculator 算法）；补算范围与触发方式实现时细化 | 待实现 |
| B4 | 删除用户时评论「已注销」 | ✅ 已定稿（用户确认）：前端批量回填用户名，user_id 查无显示「已注销」，零后端改动 | 明确 |
| B5 | 评论阈值字段回填 | `quality_check_detail` 无阈值字段，问题清单按 `rule_id` 回填 `quality_rule` 阈值，历史 rule 已删则缺省 | 明确（按 rule_id 回填） |
| B6 | Flink 依赖 jar 版本矩阵 | ✅ **已锁定并固化**：`flink-cdc-dist/common/flink2-compat/pipeline-connector-mysql/pipeline-connector-iceberg:3.6.0-2.2` + `mysql-connector-j:8.0.33` + `flink-shaded-hadoop-2-uber:2.8.3-10.0` + `flink-s3-fs-hadoop:2.2.1`，全部预置进自定义镜像 `datanest-flink:2.2.1`（`docker/flink/Dockerfile`） | ✅ 已通过 |

## 6. 开发分阶段计划

> **划分原则**：按功能块切分（三个 F），**每块 = 后端 → 前端 → 测试 完整闭环**。不做"先全部后端、再全部前端"的横切。
> **顺序**：F1（资产目录深化，纯 governance 增量，无架构风险）→ F3（质量报告，复用现有质量底座）→ F2（实时 CDC，架构级新增，依赖 M0 容器预研，风险最高放最后）。
> **每块验证口径**：① 后端 Postman/curl 自测 → ② 前端联调 → ③ 新建 `e2e/sprint8/e2e/*.spec.ts` 跑通 → ④ 更新本 Handoff 状态看板。

### 阶段总览（2026-08-09 用户确认：按三个 F 拆分）

| 阶段 | 范围 | 主要产出 | 验证口径 | 依赖 | 预估人日* |
|------|------|----------|----------|------|-----------|
| **M0 环境预研**（F2 前置，可先行） | ~~B1 容器验证 + docker-compose + Doris catalog~~ | ✅ **已完成**：MySQL→Iceberg(MinIO)→Doris 全链路跑通（含实时增量）；MinIO/Flink 集群已部署；依赖矩阵固化进 `datanest-flink:2.2.1` 镜像 | 实测通过 + Doris SELECT 3 行 | 无 | 已完成 |
| **F1 资产目录深化**（DC-06~09） | 标签/收藏/关注/评论/热度 + 详情页扩展 + 我的收藏/关注 2 页 + 首页热门 Top10 | governance V1.4.0 + `AssetCollaborationService` + Controller 13 端点 + 前端 4 处 | curl 自测 → 前端联调 → `asset-collaboration.spec.ts` | 无 | ~4 |
| **F3 质量报告**（DG-07） | KPI/四档趋势/评分趋势/问题清单/CSV + 质量报告 Dashboard 页 | governance V1.5.0 + `ScoreCalculator` 写历史 + `QualityReportController` 6 端点 + 前端 1 页 | curl 自测 → 前端联调 → `quality-report.spec.ts` | 无（与 F1 同服务不同 Controller，可先后或并行） | ~3 |
| **F2 实时 CDC**（DI-04/RC-01） | realtime-service + MinIO + Iceberg 湖仓 + **独立 Flink 集群** + CDC 向导/监控 + engineering 删除校验 | realtime 库 V1.0.0 + `CdcPipelineController` 10 端点 + Flink YAML 作业（REST 提交）+ 前端 1 页 | curl → 手工 E2E（test-mysql→湖仓→Doris 秒级可见）→ `cdc-pipeline.spec.ts` | ✅ M0 已解除（环境就绪） | ~5.5 |

> \* 预估为粗粒度参考（基于实现清单条目数），不含联调兜底与返工。总计约 **13.5 人日**。
> **M0 建议尽早启动**（与 F1 并行）：它是 F2 唯一架构风险，若 B1 阻塞降级 Flink 1.20+3.3，越早暴露越不阻塞后续。

---

### ✅ 已完成（规划/设计）

- [x] Sprint 8 PRD（`DataNest-Sprint8-PRD.md` v1.2）
- [x] Sprint 8 技术设计（`DataNest-Sprint8-技术文档.md` v1.1）
- [x] Sprint 8 UI 原型（`DataNest-Sprint8-原型.{html,css,js}`：7 视图，含 SVG 趋势图 + CDC 向导 + 日志抽屉；Playwright 验证渲染 OK、无 JS 错误）
- [x] 代码现状核验：资产协作 6 表从零、collect_change_detail 可复用、quality_score 仅最新、无 realtime 服务、Doris 4.0.7-rc02 实测
- [x] 技术调研：Flink 2.2 支持 Java 21 / CDC 3.6 Iceberg Sink / Doris Iceberg Catalog 支持 / CDC 3.6 官方兼容矩阵（Flink 1.20/2.2）

---

### 6.1 F1 资产目录深化（P0，DC-06~09）

**范围**：数据标签 / 收藏与关注（含变更动态）/ 评论与讨论 / 热度排行。
**块内依赖**：governance Flyway V1.4.0 → governance 服务（协作 6 表 entity/mapper + AssetCollaborationService）→ 前端 4 处（详情页扩展 + 我的收藏/关注 2 页 + 首页热度）→ 联调。

**后端**——✅ 已完成并 curl 自测通过（2026-08-10）
- [x] Flyway `V1.4.0`（governance）：asset_tag / asset_table_tag / asset_favorite / asset_follow / asset_comment / asset_view_log（§3.1，updated_at 无默认值）——已应用；`asset_comment` 按用户确认补 `deleted_by`/`deleted_at`
- [x] common：`ErrorCode` 4021~4024
- [x] governance `AssetCollaborationService`：打标签（复用字典）/收藏/关注（uk 幂等）/评论（软删 deleted）/热度埋点（按天 upsert）；「我的收藏」「我的关注」（关注页含 collect_change_detail 变更动态）分页
- [x] `AssetCatalogController` 扩展 **16 端点**（§5.1 十五个 + 用户确认新增 `GET /tables/{tableId}/collaboration` 聚合端点）；`browse` 补 `tag` 筛选（**传标签名**）+ `sort=hot`；`search`/`browse` 回填 `tags`
- [x] 删除校验：删表级联清理协作数据（`MetadataWriteService.remove` + `InternalDatasourceService.cascadeDelete` 两处钩子）
- [x] 重建 governance + 部署 + curl 自测（25 项断言全过：标签 CRUD/幂等/标签云/筛选、收藏/关注幂等与分页、评论发布/软删/4022/4024、热度埋点/hot-tables/sort=hot、search 回填 tags、孤儿标签物理删）
- [x] 代码评审（2026-08-10，结论 With fixes，无 Critical）已修复回归：① myFollows 变更动态 N+1 → `CollectChangeDetailMapper.selectLatestByTableTriples` 批量 DISTINCT ON（已验证 orders/products 各返回最新一条）；② addTag 并发回查 NPE 兜底（抛 4024 重试）；③ 新增 `V1.4.1` 补 `asset_favorite(table_id)` 索引（级联删除不再全表扫描）；④ DTO import 清理；⑤ 4021 标注为预留码。接受现状：收藏/关注列表表已删兜底跳过导致 total 轻微失真（级联钩子上线后仅影响历史数据）
- [x] F1 增量的二次评审（2026-08-10，53065d6..0a61254 后端增量，结论 With fixes，无 Critical）已修复回归：① **sort=latest 口径修复**——`CollectWriteService.upsertTable`（注释变更/复活）与 `upsertColumns`（字段级真实变更）现在刷新 `metadata_table.updated_at`（真实采集回归：无变更不刷新 08-07 原值、products 加字段后刷新至当前）；② matchTableIds 复用 cleanKeyword + LIMIT 1000 封顶；③ 收藏导出 5000 行上限 + warn；④ hotTables 去掉与 backfill 重复的 viewCount 回填。记录跟进：**CSV 公式注入防护**是项目级既有缺口（合规导出 esc 同样未防），两处导出需统一加固，不在本 Sprint 顺手单改一处

**前端**——✅ 已完成（2026-08-10）
- [x] `Sidebar.tsx`：「数据资产」组新增「我的收藏」「我的关注」（ALL_ROLES）
- [x] `router/index.tsx`：`/asset-catalog/favorites`、`/asset-catalog/follows`
- [x] 资产详情页扩展：`CollaborationBar`（标签打/删 + 收藏/关注）+ `CommentsTab`（发表/删除/分页/权限）+ 第 4 张热度指标卡 + 会话级埋点去重
- [x] 我的收藏页（关键词/数据源/健康度筛选 + 导出 CSV + 取消收藏）/ 我的关注页（变更动态摘要 + 取消关注）
- [x] `types/asset.ts` 扩展（tags/viewCount/协作 7 类型）、`api/asset.ts` 新端点（13 个）
- [x] 资产首页：标签云筛选 + 排序（默认/热度/最新/评分，搜索态禁用）+ 标签/热度列 + 热门 Top10 面板 + `?tag=` 跳转
- [x] 滚动体验优化（2026-08-10 用户反馈后，两轮）：列宽压缩（1360）+ 表名列左冻结 + 表格细滚动条 + 热门面板可折叠（localStorage 持久化）+ `ds-table-fill` 拉伸 antd 容器链把横向滚动条钉在卡片底边（消除行数不满时的悬空滚动条 + 下方留白）

**测试**——✅ 完成
- [x] curl 自测 → 前端联调 → `e2e/sprint8/e2e/asset-collaboration.spec.ts`（15/15 通过）

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
**块内依赖**：B1/B2 容器验证 → realtime 新库 Flyway V1.0.0 → realtime-service（独立 Flink 集群客户端）→ engineering 删除校验 → 前端 CDC 管道页 → 联调。

**M0 环境预研（对应阶段总览 M0，F2 前置，可先行）——✅ 已完成（2026-08-09）**
- [x] 容器内最小示例：Flink 2.2.1 + CDC 3.6.0 MySQL→Iceberg（MinIO）→ Doris 外部表查询（B1/B2 落地）——`flink-cdc.sh -t remote` 提交到独立 Session 集群，全链路 RUNNING，增量实时同步
- [x] docker-compose 新增 middleware-minio + **middleware-flink-jobmanager/taskmanager**（自定义镜像 `datanest-flink:2.2.1`，`docker/flink/Dockerfile` 预置 7 个 CDC/S3 jar + core-site.xml）；app-realtime + datanest_realtime 库留 F2；Doris `CREATE CATALOG datalake_catalog`（s3.endpoint=`192.168.119.1:9000`）
- [x] 已知坑沉淀：见技术文档 §7.2「M0 已知坑」4 条 + gotchas 实时 CDC 小节

**后端**——✅ 已完成并两轮全链路实测通过（2026-08-10）
- [x] realtime 库 Flyway `V1.0.0`（cdc_pipeline / cdc_pipeline_table / cdc_pipeline_log；含用户确认的 `savepoint_path` 列与 startup_mode Flink 语义三值修正）
- [x] `data-nest-realtime` 骨架（pom/application.yml/FlywayConfig/MyBatisPlusConfig，PG_DATABASE=datanest_realtime，端口 8089）
- [x] `CdcPipelineService` + `CdcPipelineController`（`/cdc/pipelines` 11 端点，§5.2）：预检（连通/binlog/ROW/库存在）/建管/启停（Flink 作业提交 + cancel-with-savepoint）/监控（延迟/变更数轮询回写）/日志
- [x] Flink CDC YAML 组装 + 经 REST 提交到独立 Flink Session 集群（`FlinkPipelineComposer.ofRemoteCluster`）；Iceberg Hadoop Catalog Sink（MinIO）；savepoint 恢复走 `execution.savepoint.path` 配置键
- [x] realtime-api Feign 契约（`GET /realtime/internal/cdc/pipelines/by-datasource?datasourceId=`，fail-closed fallback）
- [x] engineering 数据源删除前置校验（经 realtime-api，fail-closed，8009，已实测拦截）
- [x] common：`ErrorCode` 8000~8009（8000 参数校验码为评审补充）
- [x] 重建 + 部署 + curl 自测 + 手工 E2E（test-mysql users → 管道 → initial 快照 3 行落湖 → Doris 可查 → 增量 insert 经 Iceberg 快照 + Doris REFRESH 可见 → savepoint 停止/恢复续传不丢不重 → 删除级联清理），施工代理与主会话各独立跑通一轮
- [x] 代码评审（结论 With fixes，无 Critical）已修复回归：① **缺 MyBatis-Plus 分页拦截器**（selectPage 退化为全量返回，明确功能缺陷）→ 补 MyBatisPlusConfig；② start 并发重复提交 → CAS 占位（STOPPED/ERROR→RUNNING）；③ Flink REST RestClient 无超时（JM 半挂会卡死监控线程）→ connect 5s/read 10s；④ 指标查询失败误清 total_changes → -1 哨兵跳过回写；⑤ vertex 名称互斥分支丢 lag → Sink/Source 独立 if；另修：8000 参数码、stop 与监控竞态误判「外部停止」、state/metrics REST 合并为一次 /jobs/{id}、无变化跳过 DB 写、删死配置 commit-interval-ms。遗留 TODO：savepoint 文件物理清理（需 S3 客户端）、start 失败极端场景的孤儿作业补偿、UPSERT 主键列存在性预检

**前端**——✅ 已完成并联调调通（2026-08-10；E2E 由另一会话负责）
- [x] `Sidebar.tsx`：「数据工程」组新增「CDC 管道」（菜单 ALL_ROLES，写按钮 ENGINEERING_WRITE_ROLES）
- [x] `router/index.tsx`：`/engineering/cdc-pipelines` + `/new` + `/:id/edit`（向导整页路由，用户确认对齐原型）
- [x] CDC 管道列表页：4 统计卡 + 状态 segmented + 关键词 + 表格（源列「orders 等 N 表」）+ RUNNING 5s 轮询 + 启停/编辑/日志/刷新Catalog/删除
- [x] 日志抽屉（分页 + 刷新 + RUNNING 自动刷新 + 清屏）+ 4 步向导（预检 + 确认页）
- [x] `types/cdc.ts` + `api/cdc.ts`
- [x] 后端小补（用户确认）：`description` 字段（V1.1.0）+ `GET /stats` 统计端点 + `GET /source-tables` 源表列表 + page 批量回填 tables（防 N+1）；curl 自测通过
- [x] 联调调通（截图逐屏验证）：向导新建（预检 4 项通过）→ 仅保存 → 列表 → 日志 → 启动（真实 Flink 作业 RUNNING）→ 停止（savepoint）→ 删除
- [x] Code Review（With fixes）已修复：**Critical 仅增量残留 INITIAL 会真跑全量快照**（syncMode 联动 startupMode=LATEST_OFFSET + 防御校验）；Important：日志抽屉跨管道页码残留（applyQuery 重置）+ 管道状态快照失真（列表轮询联动）；Minor：轮询条件改 stats.running、预检失败禁「保存并启动」等

**测试**
- [ ] curl 自测 → 前端联调（✅ 已完成）→ `e2e/sprint8/e2e/cdc-pipeline.spec.ts`（另一会话负责）

---

### 6.4 收尾（全部块完成后）

- [ ] 全量回归：`docker compose up -d` 全部服务 + 前端 build + 各块 E2E 全跑
- [ ] 代码审查 + 更新 AGENTS.md / docs/agent（如需；新增 realtime 服务/第 5 库/MinIO 需同步架构文档）
- [ ] 更新 §2 看板全部置 ✅ + 本文档归档

## 7. 备注 / 已知坑提醒

- **Flink 版本**：**Flink 2.2.1 + Flink CDC 3.6.0**（2026-08-09 修订，官方配对）；CDC 3.6 支持 Java 11+（含 21）；原案 Flink 2.0+3.4 非官方兼容（CDC 3.4 仅声明 1.19/1.20）。容器验证优先，B1 降级点（1.20+3.3，JVM 17）已预留。
- **Doris**：4.0.7-rc02 实测支持 Multi-Catalog；建 Iceberg Catalog 用 Hadoop + `s3.*` 属性指向 MinIO。
- **Iceberg 非独立服务**：写入端以 connector jar 随作业跑在独立 Flink 集群 TaskManager，存储落 MinIO，读取端 Doris 内置——本期新增独立基础设施容器：**MinIO + Flink 集群（JobManager+TaskManager）**。
- **迁移脚本**：governance 新脚本从 V1.4.0 起；datanest_realtime 新库 V1.0.0 baseline；统一紧凑单行风格；审计字段（updated_at 无 DB 默认值，create 只设 created_by/created_at）。
- **消费方重建**：改到 task-core 共享模块必须全量重建消费方；realtime 是共享执行侧新服务，本次不涉及 task-core 改动（CDC 逻辑全在 realtime 内）。
- **删除语义**：删表级联清理标签/收藏/关注/评论；删用户保留评论（前端「已注销」兜底）——F1 实现时注意 metadata_table 删除钩子。
- **E2E 设施**：复用 sprint7 的 `helpers/db.ts`（拆库版 psql，按表路由域库）；新增业务表需补 sprint5 `TABLE_DB` 映射。
