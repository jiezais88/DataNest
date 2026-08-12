# Sprint 10 Handoff：数据服务（SQL 查询终端 + 数据 API + API 网关 + 实时推送 + 数据分级分类）

> 更新：2026-08-12（F1 SQL 终端后端实现 + 部署 + API 自测会话）
> 对应文档：`../DataNest-Sprint10-PRD.md`（v1.3）· `../DataNest-Sprint10-技术文档.md`（v1.4）· `../DataNest-Sprint10-原型.html/css`

---

## 1. 状态看板

| 交付物 | 状态 | 说明 |
|--------|------|------|
| PRD | ✅ v1.3 | **全部决策定稿（D1~D5）**；§6.3/6.4/6.5 已回落原型修正；§12.2/§13 决策记录含 D4 M0 结论 |
| 技术文档 | ✅ v1.4 | F1 SQL 终端后端实现+部署+API 自测通过（§9 勾选）；新增 `data-service-api` 契约 + job `SqlHistoryCleanupHandler`；§8 Blocker 7 定稿「业务服务本地禁 @Scheduled」 |
| 原型（HTML/CSS） | ✅ 产品逻辑修正完成 | 4 项决策落地 + 「API 运行统计」独立页；原型 = 实现基准 |
| 后端 | 🟡 F1 完成 | **F1 SQL 终端已部署 `datanest-app-data-service`（healthy）**；17 API 自测用例全通过；F2 API 管理/Key/F3 网关/F4 WebSocket/F5 分级对外端点未开始 |
| 前端 | ⬜ 未开始 | 原型已定稿，可作实现基准 |

---

## 2. 本次会话变更清单（原型，4 问 4 答）

### 2.1 新增「API 运行统计」独立页（q-0 定稿）
- **背景**：API 详情页为单 API 深度观测；原全局统计仅有 API 列表页顶部 4 个数字卡片，缺平台整体运行态势。
- **决策**：新增独立页（侧边栏「数据服务」组新入口「API 运行统计」，`/data-service/api-stats`，新图标 `i-trend`）；**全局统计与单 API 统计都归观测域**；API 列表操作列统计按钮（`i-chart`）跳转该页。
- **页面内容**（时间范围 近24h/近7天/近30天 切换）：
  - KPI 条：总调用量 / 平均成功率（目标 ≥ 99.0%）/ 平均+P95 耗时 / 限流命中（占调用比例）
  - 全局调用量趋势：双线大图（调用量 + 失败数），SVG 手写（沿用质量报告 charts 模式）
  - API 健康分布：平台综合健康分 + 健康/警告/严重占比条 + 分级明细（可跳列表/详情）
  - Top 5 API 调用排行：名称 + 路径双行，整行可点击跳单 API 详情
  - 错误码分布：4xx/5xx 占比 + 错误码 Top5（429 限流突出）+ 处置建议
  - 调用方 Key 排行（含僵尸 Key 灰显）、限流命中趋势柱状图（7 天）、API 状态速览（已发布/待发布/已下线）
- **样例口径（实现参考）**：总调用 128,492；成功率 98.7%；P95 412ms；限流 8,214（占 6.4%）；Top5 合计 95,760；错误 1,584（4xx 91%/5xx 9%，429 占错误 50%）；僵尸 Key = 近 7 天 0 调用。

### 2.2 API 详情页（q-1 + q-0 空区闭环）
- 「最近调用」副标题由「实时刷新」改为 **「最新 5 条 · 异常高亮」**（不随机）。
- api-meta-card（健康度卡右侧空区）补「绑定 Key / 近 7 天调用」行 + 底部**「平台运行统计」入口块**（虚线框，跳全局统计页）。

### 2.3 API 创建向导目标卡 → API 预览（q-2）
- 去掉源 SOURCE / 目标 TARGET 英文，标题改为「数据源」/「API 预览」。
- **目标卡重构为 API 预览**：生成路径 `GET /open-api/v1/orders`（带复制）+ 暴露字段清单（勾选，公开字段默认全暴露，`region`/`order_date` 标可参数化；**机密字段自动锁定**灰置不可勾，不进入 API 响应）。
- 页头描述改「选表即生成接口雏形，右侧实时预览」。

### 2.4 API Key 管理运维能力（q-3）
- 表新增「**近 7 天调用**」列（识别僵尸 Key，0 调用灰显）。
- 操作列加快捷**禁用/启用**（`i-stop`/`i-play`，泄露 1 步处置，不必进编辑）。
- 底部提示条补「近 7 天调用为 0 的 Key 建议停用，防止闲置引发泄露风险」。

---

## 3. 待同步项（原型 → PRD / 技术文档）— ✅ 已全部回落（2026-08-12）

> 4 项均已回落：PRD v1.2 + 技术文档 v1.2，见版本记录。

| # | 目标文档 | 回落内容 |
|---|----------|----------|
| 1 | PRD §6.5 | ✅ 已重构为双层次：6.5.1 全局统计页（API 运行统计）+ 6.5.2 单 API 详情统计（最新 N 条 · 异常高亮）+ 6.5.3 统计口径样例表（128,492 / 98.7% / 8,214 / 429 占 50% / 僵尸 Key / 健康 9-2-1） |
| 2 | 技术文档 §5.1 | ✅ 新增 `/stats/*` 全局统计端点组（overview/trend/health-distribution/top-apis/error-codes/top-keys/rate-limit-trend）；§9 实现清单补 `StatsController` 与前端「API 运行统计页」 |
| 3 | PRD §6.3 | ✅ 新增「API 预览（2026-08-12 定稿）」条目（选表即生成接口雏形、暴露字段勾选 + 机密锁定、不重复展示源卡信息） |
| 4 | 技术文档 §5.1（api-keys） | ✅ `/api-keys/page` 补近 7 天调用聚合；新增 `POST /api-keys/{id}/enable`（快捷启用） |

---

## 4. Blocker / 待确认

| # | 事项 | 说明 | 状态 |
|---|------|------|------|
| 1 | **D4 变更事件捕获**（Flink CDC 3.6 多 sink + Kafka） | **M0 已定稿（2026-08-12，q-0~q-3）**：Flink CDC 3.6 不支持多 sink 双写 → **事件管道分离**（每可订阅管道独立 Kafka 单 sink 事件作业，latest-offset 增量）；Kafka `apache/kafka:4.0.x` KRaft 单节点 | ✅ 已定稿 |
| 2 | 全局统计接口聚合方式 | 调用统计异步写 + 聚合查询（对齐技术文档 D8），range 参数 24h/7d/30d；是否独立统计表需后端实现时定 | ⏳ 待后端 |
| 3 | 健康分级口径（健康/警告/严重 + 综合健康分） | 原型以「非 2xx 占比 + P95 + 限流命中」综合打分；正式口径（分级阈值）建议对齐已有告警质量分级语义（PASS/WARNING/SEVERE），待产品确认 | ⏳ 待产品 |

---

## 5. Next Action

1. **F2 API 管理 + Key**：`DataApiController` + `DataApiService`（CRUD/发布/下线 + 敏感度校验 + API 预览）+ `ApiKeyController`（一次性明文 + 近 7 天调用 + 快捷启用 `POST /api-keys/{id}/enable`）+ data_api/api_key/api_key_binding/api_key_pipeline 实体 Mapper（表已建，可直接用）。
2. **F3 API 网关**：`OpenApiKeyFilter`（Key 哈希校验，open-api 路由网关已放行）+ `RateLimitService`（Redis ZSET 滑动窗口）+ `CircuitBreaker`（Resilience4j）+ `ApiCallLogWriter`（异步队列写 api_call_log）；`StatsController` 全局统计（`/stats/*`）。
3. **F5 分级对外端点**：governance `SensitivityController`（改级/批量/开白/审计，机密降级必经 INTERNAL 两步）+ 前端数据分级分类页。
4. **F4 WebSocket 实时订阅**：依赖 Kafka 中间件——compose `middleware-kafka`（`apache/kafka:4.0.x`）+ Flink lib 增 `flink-cdc-pipeline-connector-kafka:3.6.0-2.2` + realtime `CdcEventYamlBuilder` 事件作业联动（server-id 6400+/PG 额外槽）+ `WsEventsHandler` + `KafkaEventConsumer`。
5. **健康分级阈值确认**：全局统计页「健康/警告/严重 + 综合健康分」的分级阈值（当前注记对齐告警 PASS/WARNING/SEVERE 语义），需后端定稿前与产品确认。
6. **前端**：原型已定稿；先建 `/data-service/*` 路由 + Sidebar「数据服务」菜单组 + SQL 终端页（数据源下拉/执行/历史/导出，后端已就绪）。

---

> **版本记录**
> - v1.0 (2026-08-12)：初始 handoff。记录「原型产品逻辑修正」会话（4 项决策 + 新增 API 运行统计页），列出 PRD/技术文档待同步项、Blocker（D4）与 Next Action。
> - v1.1 (2026-08-12)：§3 待同步项 4 项全部回落完成（PRD v1.2 + 技术文档 v1.2：全局统计端点组 / API 预览 / Key 近 7 天调用与快捷启用）；状态看板与技术文档版本同步更新；Next Action 移除回落项、新增健康分级阈值确认。
> - v1.2 (2026-08-12)：**M0 技术调研定稿会话（D4）**——反编译 `flink-cdc-composer-3.6.0-2.2.jar` 证实 Flink CDC 3.6 **不支持多 sink 双写**，原「Iceberg+Kafka 双写」废弃；与用户 4 问 4 答（q-0~q-3）拍板：**事件管道分离**（每可订阅管道独立 Kafka 单 sink 事件作业 latest-offset 增量）、**管道创建即建同生命周期**、**`apache/kafka:4.0.x` KRaft 单节点**、**仅增量推送**。PRD v1.3 + 技术文档 v1.3（D-D6 重写 / §0-F4 / §4.3 / §5.4 / §8 Blocker 1/2/6 定稿 / §9 实现清单）+ 本 handoff 状态看板/Blocker/Next Action 同步更新；Sprint 10 决策全部定稿，可开后端骨架。
> - v1.3 (2026-08-12)：**F1 SQL 终端后端完成会话**——新增 `data-nest-data-service` 服务（骨架/库/网关路由/swagger/SQL 终端）并部署 `datanest-app-data-service`（healthy）；governance V1.6.0 + internal 表清单/敏感度契约；**用户拍板业务服务本地禁 `@Scheduled`**（清理改放 job：data-service-api `DataServiceOpsApi` + job `SqlHistoryCleanupHandler`，已注册 PowerJob jobId=293；规范写入 conventions-backend §7）；API 自测 17 用例全通过；技术文档 v1.4 + 本 handoff 同步；Next Action 更新为 F2~F4。
> - v1.4 (2026-08-12)：**F1 多数据源 E2E**——放开 compose `middleware-test-oracle` + `middleware-test-sqlserver`（+`test-oracle-data` volume），新增 `oracle`/`sqlserver` 两个 NORMAL 数据源；经 SQL 终端实测 **MySQL/PG/Oracle/SQL Server 4 种库查询全通过** + MySQL 超时 9003 + SQL Server 只读拦截 9001；技术文档 v1.5。
