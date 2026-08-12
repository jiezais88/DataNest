# Sprint 10 Handoff：数据服务（SQL 查询终端 + 数据 API + API 网关 + 实时推送 + 数据分级分类）

> 更新：2026-08-12（F1 SQL 终端前端 + 联调 + 补 cancel 后端会话）
> 对应文档：`../DataNest-Sprint10-PRD.md`（v1.3）· `../DataNest-Sprint10-技术文档.md`（v1.5）· `../DataNest-Sprint10-原型.html/css`

---

## 1. 状态看板

| 交付物 | 状态 | 说明 |
|--------|------|------|
| PRD | ✅ v1.3 | **全部决策定稿（D1~D5）**；§6.3/6.4/6.5 已回落原型修正；§12.2/§13 决策记录含 D4 M0 结论 |
| 技术文档 | ✅ v1.4 | F1 SQL 终端后端实现+部署+API 自测通过（§9 勾选）；新增 `data-service-api` 契约 + job `SqlHistoryCleanupHandler`；§8 Blocker 7 定稿「业务服务本地禁 @Scheduled」 |
| 原型（HTML/CSS） | ✅ 产品逻辑修正完成 | 4 项决策落地 + 「API 运行统计」独立页；原型 = 实现基准 |
| 后端 | ✅ F1 完成 | **F1 SQL 终端已部署 `datanest-app-data-service`（healthy）**；17 API 自测用例 + F1.1 cancel 补丁全通过；**F1.1（本会话）：`SqlExecuteRequest.queryId` + `POST /sql-console/cancel` 停止查询、`SqlExecuteResult.tableCount/confidentialHits` KPI 字段、socketTimeout 参数化、durationMs 改 int**；F2 API 管理/Key/F3 网关/F4 WebSocket/F5 分级对外端点未开始 |
| 前端 | ✅ F1 完成 | **SQL 查询终端页已实现并部署 `app-frontend`**（`/data-service/sql-console` + Sidebar「数据服务」组，仅此一项渐进式）；数据源下拉/Monaco(Ctrl+Enter)/运行/停止/KPI 4 卡/结果表/CSV·Excel 导出/查询历史回填+清空；与后端联调通过 |

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
6. ~~前端 SQL 终端页~~ ✅ 已完成（本会话，见 §6 变更清单）。

---

## 6. 本次会话变更清单（F1 前端 + 联调 + 补后端 cancel，3 问 3 答）

> 3 问 3 答：q-0 菜单只放「SQL 查询终端」一项（渐进式）；q-1/q-2 用户授权「你来补后端」→ 本会话补 F1.1 后端能力支撑前端完整呈现。

### 6.1 前端（app-frontend，已部署）
- **路由/菜单**：`/data-service/sql-console` + Sidebar「数据服务」组（仅 SQL 查询终端一项，`HiOutlineCommandLine`，ALL_ROLES）；breadcrumb「数据服务 / SQL 查询终端」。
- **页面** `src/pages/data-service/sql-console/index.tsx`（工作台布局：工具栏 → Monaco 编辑器 → 结果信息条 → KPI 4 卡 → 左结果/右历史 → 只读安全提示条）：
  - 数据源下拉（DsFilterSelect，默认内置 Doris）+ 刷新
  - Monaco（`@monaco-editor/react` + monacoSetup，vs-dark，`Ctrl+Enter` 运行）
  - 运行（DsButton loading）/ 停止（AbortController.abort + `POST /cancel` 双管齐下）
  - **KPI 4 卡**：本次用时 / 返回行·上限(1000) / **涉及表**（后端 tableCount）/ **机密拦截**（后端 confidentialHits，0 绿色「未触碰机密数据」）
  - 结果表（antd Table 动态列、横向滚动、NULL 灰显、截断提示）、错误行内横幅（execute 走 `skipErrorMessage: true`，9001/9002/9003/9004/9012 不弹全局 toast）
  - 导出 CSV（带 BOM，Excel 中文正常）/ Excel（`xlsx` 库，aoa_to_sheet 保列序）；文件名 `{数据源}_{首表}_{yyyyMMdd_HHmmss}`
  - 查询历史（usePagedList 分页 10/页、点击回填 SQL+数据源、清空带 ConfirmDialog）
- **API/类型**：`src/api/data-service.ts`（execute 请求 timeout 70s + AbortSignal + skipErrorMessage）+ `src/types/data-service.ts`（datasourceId 为 string——Snowflake Long 精度）。
- `xlsx` 从 devDependencies 移入 dependencies（页面运行时 import）。

### 6.2 后端 F1.1 补丁（data-service 模块内，未碰 task-core，已部署）
- **`POST /sql-console/cancel`**（body `{queryId}`，幂等，四角色）：`SqlExecuteRequest` 加 `queryId`；`SqlQueryService` 虚拟线程执行 + `queryId → RunningQuery(Future+Connection)` 注册表；cancel = `future.cancel(true)` + 关闭连接（立即中断 JDBC 阻塞读取，比 setQueryTimeout 提前终止）。execute 请求需 ≥60s 超时，前端已配 70s。
- **`SqlExecuteResult` 加 `tableCount`（涉及表数，JSqlParser 表集合）+ `confidentialHits`（机密命中数，成功恒 0）** 支撑前端 KPI；`durationMs` 由 long 改 int（避免 Long 序列化为字符串）。
- **socketTimeout 参数化**：`ExternalSqlExecutor.buildJdbcUrl` 加重载（默认 10s 保持同步任务行为不变），SQL 终端外部查询用请求级超时（默认 60s），避免 pg/mysql 10s socket 超时提前截断。
- **取消时序结论**：HTTP 层 cancel 一定在 execute 之后（前端 running 态才有停止按钮）；自测 30s `pg_sleep` 在 3s 时被 cancel 中断，execute 返回 9003「查询已被停止」。
- **已知取舍**：原「扫描行」KPI JDBC 无可靠 API、Doris `SHOW QUERY PROFILE` 成本过高 → 改为「涉及表」（tableCount 精确可给）。

### 6.3 回归验证记录
- 数据源下拉（Doris + 5 个 NORMAL 外部数据源）✅；Doris `SELECT` 执行（durationMs 数字、tableCount/confidentialHits 齐全）✅；UPDATE 被拒 9001 ✅；历史分页 ✅；cancel 幂等（无 id → false）✅；**cancel 真实中断（pg_sleep(30) 3s 中断 → 9003）✅**；`tsc --noEmit` + `pnpm build` 通过；后端 `mvn package` 通过。

### 6.4 前端 Review（按 AGENTS.md §7：架构融洽/业务正确/实现高效）
- **BUG-1 严重**：Monaco `onMount` 只触发一次，`handleEditorMount` 依赖 `[runQuery]` 但挂载时闭包捕获旧 `sql` → **Ctrl+Enter 永远执行挂载时的旧 SQL**。修复：`runQueryRef.current = runQuery` 每次渲染刷新，command 调 `runQueryRef.current()`，`handleEditorMount` 依赖清空。
- **BUG-2 中**：结果卡表格区外层 `overflow-auto` 与 `ds-table-fill`（滚动交给 `.ant-table-content`）冲突双滚动条 → 改 `overflow-hidden`。
- **BUG-3 中**：`rowKey="__rowKey"` 与结果列同名冲突 → 改 rowKey 函数 `(_, idx) => idx ?? 0`，去掉 map 包装。
- **BUG-4 低**：`extractTableName` 正则误匹配注释里的 `FROM` → 先剥离 `/* */` 与 `--` 注释。
- **BUG-5 低**：结果值对象（如 PG void）`String(v)` 显示 `[object Object]` → 取 `.value` 兜底 `JSON.stringify`。
- 以上均已修复 + `tsc --noEmit` + `pnpm build` 通过 + 重新部署 app-frontend。
- **确认符合规范项**：面包屑 leaf 页保留自身 h1、ID 全程 string 不转 Number、本地 Blob 导出用 `downloadBlob`、execute 70s>60s、错误行内+`skipErrorMessage`、DsButton loading、usePagedList、后端改动未碰 task-core。
- **全局约定未改**：`PageResult.total` 为 Long→string（全项目统一，分页隐式转换正常，非本次引入）。

## 7. 产品化改版（F1 前端，2026-08-12，用户 5 点反馈 + 3 问 3 答）

> 用户反馈 5 点：①太丑 ②内置 Doris 只能连 datanest 库 + 应叫「Doris 数仓」（看元数据管理）③结果对比原型要套表格 ④刷新没反应 ⑤产品角度加左侧「数据源→库/模式→表→字段」树。
> 用户授权「你来补后端」已在上轮（§6.2 F1.1）完成；本轮 3 问 3 答：q-0 复用元数据树（governance 域）+ q-1 点表插入带库名 SQL + 机密表 F5 待补。

### 7.1 布局重构（工具型 → 数据探索一体化工作台）
- **左侧数据目录树（280px）**：**直接复用 `MetadataTree`**（`/governance/metadata/MetadataTree.tsx`，零重写）——数据源→库→Schema→表懒加载；内置 Doris 显示「**Doris 数仓**」且展开其真实多库（实测 `datanest` + `ods`）；外部数据源仅展示「已采集元数据」的（实测 mysql 有 `testdb`）。
- **点表插入**：`handleTreeSelect` 判断 `node.type==='table'` → 插入 `SELECT * FROM 库.表 LIMIT 100;`（带库名，兼容多库）+ 光标聚焦末尾 + toast。
- **执行数据源路由**：`runQuery` 用 `deriveDatasourceId(treeSelected) ?? '-1'`。**实测确认 governance 元数据树 datasourceId 与 sql-console 是同一套 id**（内置 Doris 均 `-1`，mysql 均 `2083088527209295874`），SQL 执行可正确路由。
- **中央列**：数据源上下文条 → Monaco（240px）→ 结果信息条+导出 → KPI 4 卡 → 结果表卡片化。
- **结果表卡片化**：外框 `bg-ds-bg-surface + border + rounded` + 表头栏（标题 + 「N 列 · 命中 M 行」+「只读 SQL · 结果上限 1000 行」）；错误/机密拦截横幅内嵌。
- **查询历史下沉底部时间线**：横排卡片（220px/张，点回填）+ 底部 Pagination + 清空确认。
- **刷新反馈**：数据源刷新按钮 `listSqlDatasources().then`（governance 树数据刷新由 `MetadataTree` 自带 loadRoots 管理，无 loading 问题）。

### 7.2 机密拦截展示（F5 前置占位）
- 结果区 `confidentialHits > 0` 时顶部横幅「该查询涉及 N 张机密表…已拦截」。
- 左侧树节点机密标记（锁图标 + tooltip）**依赖 F5 分级分类**，已记 memory（ID 见 memory），F5 完成后补 `sensitivity` 判断。

### 7.3 回归验证
- `tsc --noEmit` ✅、`pnpm build` ✅、app-frontend 已重建部署。
- 实测：Doris 多库（`SELECT * FROM ods.users` → 3 行）✅；MySQL 外部数据源（`SELECT * FROM users` → 3 行）✅；Doris 默认库 ✅；governance 元数据树 Doris=2 库 / mysql=testdb ✅。
- **已确认为同一套 id**：sql-console 与 governance 元数据树的 datasourceId 一致。

### 7.4 待 F5 补齐（已记 memory）
- 树节点机密标记（`MetadataTable`/`MetadataTreeNode` 无 `sensitivity` 字段，待 F5 `SensitivityController` + `metadata_table.sensitivity_level`）。

## 8. UI 产品化改版第 2 轮（2026-08-12，紧凑 IDE 风格）

> 用户反馈：①不应显示 id + 当前数据源显示到表级 ②界面太丑，查询历史应收起为弹窗 ③只展示已采集元数据从产品角度是否合理 ④从产品和 UI 角度美化。
> 3 问 3 答：q-0 历史 → 采纳我方建议「顶部按钮 + Drawer」；q-1 未采集 → 采纳「提示采集（去采集按钮跳元数据管理页）」；q-2 UI → **紧凑 IDE 风格**（DBeaver/Beekeeper）。

### 8.1 布局与交互
- **左侧树改用自定义 `SqlTree.tsx`**（不再复用 `MetadataTree`）：根 = **sql-console 全部 NORMAL 数据源**（含内置 Doris + 5 个 NORMAL），库/表走元数据域接口懒加载；内置 Doris 显示「Doris 数仓」默认展开。
- **未采集数据源**：展开库为空时显示 `Empty` +「去采集元数据」按钮（`navigate('/governance/metadata')`）——产品判断：SQL 终端不该被「必须先采集元数据」卡住，但需告知用户为何无库表。
- **面包屑路径**：顶部工具栏显示 `Doris 数仓 › ods › target_products`（不显示 id，显示到表级）。
- **当前数据源上下文**：`onContextChange` 由 `SqlTree` 选中节点回传（datasourceId + dsName + databaseName + tableName），`datasourceIdRef` 存当前执行数据源。
- **查询历史 → Drawer**：顶部「查询历史」按钮 + Badge 显示总数，点开右侧 Drawer（列表分页 + 清空 + 回填），不再常驻底部。
- **结果表/KPI 紧凑化**：KPI 4 卡改为小尺寸 `KpiItem` 横排一行（用时/返回行/涉及表/机密拦截）+ 右侧 CSV/Excel 导出；结果表紧凑行高 + 表头栏（N 列 · M 行 · 上限 1000 行）。

### 8.2 关键实现
- `SqlTree.tsx`：antd Tree + `loadData` 懒加载；`onInsert(qualified)` 点表插入 `SELECT * FROM 库.表 LIMIT 100`；`onContextChange` 回传选中上下文；`dsNameMapRef` 反查数据源名（内置 Doris 显示「Doris 数仓」）。
- `index.tsx`：面包屑路径、Drawer 历史、`datasourceIdRef` 执行路由、紧凑 KpiItem/结果表。
- **类型修正**：`DatabaseTypeIcon` 为默认导出（非命名）；`HiOutlineDatabase`→`HiOutlineCircleStack`（hi2 无 Database）；`DsButton` 无 `size` prop（去掉 size="small"）。

### 8.3 验证
- `tsc --noEmit` ✅、`pnpm build` ✅、app-frontend 已重建部署。
- 依赖：sql-console 数据源根（含 Doris 多库 + 5 NORMAL）✅；governance 元数据域库/表懒加载 ✅（前序已实测 Doris=2 库 / mysql=testdb）。
- 未采集数据源「去采集」跳转依赖 `/governance/metadata` 路由存在 ✅。

## 9. UI 改版第 3 轮（2026-08-12，用户 4 点反馈 + 2 问 2 答）

> 用户反馈：①顶部被挡（运行按钮文字过长挤占停止）②上面太小下面太多（编辑器矮/结果表空白多）③去采集跳元数据页没解决用户问题 ④历史不知数据源名、回填不联动左侧树高亮。
> 2 问 2 答：q-0 去采集 → **补 governance inline 端点**（用户选）；q-1 历史回填联动 → **全自动展开到表**（用户选）。

### 9.1 后端（governance，已部署）
- **`POST /governance/metadata/datasources/{id}/collect-now`**（四角色，MetadataController）：
  - `InternalDatasourceService.collectNow(datasourceId, operatorId)`——注入 `EngineeringDatasourceApi`，回读数据源连接信息（type/databaseName/schemaName/username）→ 组装 `AutoCreateCollectTaskRequest` → 复用 `autoCreateCollectTask` 建任务+注册+立即触发。
  - engineering 不可用或数据源不存在 → 抛 `DATASOURCE_NOT_FOUND`（fail-closed，避免静默失败）。
  - **实测**：pg 数据源（原未采集）触发后 status=SUCCESS，元数据库列表返回 `postgres` ✅。

### 9.2 前端（app-frontend，已部署）
- **布局**：编辑器 220→280px；运行按钮精简为「运行」+ tooltip「Ctrl+Enter」；顶部工具栏紧凑不挤压。
- **SqlTree inline 采集**：未采集数据源 Empty 显示「立即采集」按钮（不再跳页）→ `collectMetadataNow(datasourceId)` + loading → 成功 toast + 清空 loadedRef 重载根节点 + 重新展开该数据源。
- **历史回填联动**：`SqlTree` 改 `forwardRef` + `useImperativeHandle` 暴露 `selectByPath(datasourceId, databaseName?, tableName?)`——自动展开数据源→库→(schema)→表并高亮选中；`extractTableRef(sql)` 从 SQL 解析 `db.table`/`db.schema.table`；面包屑同步更新。
- **历史 Drawer 显示数据源名**：`dsNameOf(datasourceId)` 反查（内置 Doris 显示「Doris 数仓」）+ 时长/行数/时间。

### 9.3 验证
- `tsc --noEmit` ✅、`pnpm build` ✅、governance `mvn package` ✅、两容器已部署。
- 实测 collect-now 端点 + 采集任务 SUCCESS + 元数据落地 ✅。

## 10. KPI 卡图标视觉优化（2026-08-12）

> 用户反馈原型 4 个 KPI 卡图标（⚡ 闪电/✓ 对勾/📊 服务器/⚠️ 警告）+ 圆形彩色背景 + 粗体大字数字比当前的纯灰细图标好看。

- 每个 KPI 配**语义图标** + **圆形彩色背景**（对齐原型 sql-kpi）：
  - **本次用时** ⚡ `HiOutlineBolt`（accent 蓝）· **返回行/上限** ✓ `HiOutlineCheckCircle`（success 绿）· **涉及表** 📊 `HiOutlineCircleStack`（neutral 灰）· **机密拦截** 🛡️ `HiOutlineShieldExclamation`（0=绿/命中=黄）
- 卡片样式：8px 圆形图标背景（`bg-ds-*/10` 透明色）、`text-ds-h5` 粗体大字、保留 sub 备注。
- 移除原 `HiOutlineBolt size={13}` 灰小杠（视觉权重太弱）。

### 10.1 验证
- `tsc --noEmit` ✅、`pnpm build` ✅、app-frontend 已重建部署。

## 11. 左侧树对齐元数据管理页视觉（2026-08-12）

> 用户反馈：①查询历史 Badge 数字被遮挡 ②大标题下缺描述 ③元数据管理页无左右滚动（树截断），SQL 终端要对齐元数据管理页树的视觉。

### 11.1 查询历史 Badge 遮挡
- 原 `Badge count={historyTotal}` 包在 `DsButton`（inline-grid + place-items）上，角标绝对定位被挤压/裁剪。
- **改内联计数**：按钮内 `查询历史` 后跟一个 `rounded-full bg-ds-accent/10 text-ds-accent` 小计数徽章（`Number(historyTotal) > 0` 才显示），彻底避免角标遮挡。

### 11.2 页头补描述
- 改为「标题 + 描述」结构（对齐资产目录页）：h1 + `text-ds-small text-ds-text-muted` 描述（「在左侧目录选择库表插入查询模板，或手动编写只读 SQL 查询与导出结果」），面包屑路径跟在描述后。

### 11.3 左侧树对齐元数据管理页
- **弃用 antd Tree**，改为**与 `MetadataTree` 相同的手写递归树**：
  - 库/模式 `HiOutlineFolder` **黄色**（`text-ds-warning`）、表 `HiOutlineTableCells` 灰、数据源 `DatabaseTypeIcon size=16`
  - 名称 `truncate min-w-0 flex-1` + `Tooltip`（**无左右滚动**，对齐元数据管理）
  - 选中态 `bg-ds-accent-light text-ds-accent font-semibold`、箭头 `HiChevronRight rotate-90`、库/模式计数「N表」、加载中 `DsSpinner`
- 保留 SqlTree 特有能力：sql-console 数据源根、inline 采集（`collect-now`）、`selectByPath` 历史回填联动、`onInsert` 点表插入。

### 11.4 验证
- `tsc --noEmit` ✅、`pnpm build` ✅、app-frontend 已重建部署。

## 12. SQL 查询树差异化增强（2026-08-12）

> 产品定位：SQL 查询树「结构 + 视觉对齐元数据管理树，但保留查询终端特色」。3 问 3 答确认实施 3 个增强。

### 12.1 增强内容
- **搜索框**（复用 `searchMetadataTree`，对齐元数据管理树）：搜索库/模式/表，搜索模式默认展开所有非叶子节点；空态提示「未找到匹配的库或表」；清空按钮回正常树；`selectByPath` 历史回填时自动退出搜索模式。
- **表节点 hover 提示**：Tooltip 显示「点击插入 SELECT：`库.表`」，让「点表=插入」核心交互可发现。
- **库/模式层上下文联动**：点击数据源→库→模式逐层更新面包屑路径（`dsName › db › schema`），不再只更新到数据源/表级。

### 12.2 验证
- `tsc --noEmit` ✅、`pnpm build` ✅、app-frontend 已重建部署。

## 13. 面包屑适配 schema + 独立导航条（2026-08-12）

> 用户反馈：①面包屑没适配「模式」层（schema 被误塞进 tableName）②面包屑不应放在描述后面（应从产品角度设计独立导航）。

### 13.1 改动
- **`SqlTreeContext` 加 `schemaName` 字段**；`handleToggle` 修复 schema 层传值（不再把 schema 塞进 `tableName`）。
- **面包屑从描述行抽离**，独立为「当前上下文」导航条（页头下方、主体上方）：
  - 左侧 `当前上下文` 标签 + `数据源 › 库 › 模式 › 表` 路径（truncate 防溢出，max-w 160px/段）
  - 无选中时默认显示「内置 Doris 数仓」；右侧辅助说明「执行数据源由当前上下文决定」
- **`selectByPath` 支持 schemaName 参数**：历史回填时按 `db.schema.table` 定位（schema 层优先用传入名，找不到用第一个）；表定位改为在 schema（或库）子节点中按表名 find，不依赖 key 拼接（兼容多 schema）。
- **`fillFromHistory`**：传 `ref.schemaName` 给 `selectByPath` 和 `setContext`。

### 13.2 验证
- `tsc --noEmit` ✅、`pnpm build` ✅、app-frontend 已重建部署。

## 14. 首次进入默认上下文一致性（2026-08-12）

> 用户质疑：首次进入页面未点击任何节点，上下文条却显示「内置 Doris 数仓」，语义上「当前上下文」不该是用户没选过的东西。
> 产品讨论后用户选定方案 C：**首次进入自动选中内置 Doris 数仓节点**，树选中态与上下文条一致。

- `SqlTree.loadRoots`：加载根后找到内置 Doris → `setExpanded`（默认展开）+ `setSelectedKey`（自动高亮）+ `onContextChange`（上下文条同步「Doris 数仓」），`loadRoots` 依赖补 `onContextChange`。
- 主页面上下文条默认文案统一为「Doris 数仓」（与树一致，去掉「内置」前缀）。

### 14.1 验证
- `tsc --noEmit` ✅、`pnpm build` ✅、app-frontend 已重建部署。

## 15. 查询历史按钮精简 + 用户隔离确认（2026-08-12）

> 用户：①查询历史按钮不需要显示个数 ②历史是否用户隔离。

### 15.1 去掉计数徽章
- 「查询历史」按钮移除内联计数徽章（`historyTotal` 仍用于 Drawer 分页，非未使用）。产品判断：历史是「回顾/复用」功能，用户关心内容而非条数，数字徽章是噪音。

### 15.2 历史用户隔离确认
- `GET /sql-console/history`：`StpUtil.getLoginIdAsLong()` + `QueryWrapper.eq("user_id", userId)` → 只查当前用户。
- `DELETE /sql-console/history`：按当前 `user_id` 删除。
- **结论：查询历史严格按用户隔离**，安全。

### 15.3 验证
- `tsc --noEmit` ✅、`pnpm build` ✅、app-frontend 已重建部署。

## 16. 失败 SQL 进历史 + 错误展示优化（2026-08-12，产品优化）

> 用户需求：①失败 SQL 也进历史且能看到错误信息 ②执行错误展示优化。

### 16.1 后端（data-service，已部署）
- **Flyway V1.0.1**：`sql_query_history` 加 `error_message text`。
- **实体** `SqlQueryHistory` 加 `errorMessage` 字段。
- **`SqlQueryService`**：
  - 成功/失败统一写历史；失败 catch（BusinessException / 其它 Exception）也调 `asyncSaveHistory` 记录 errorMessage。
  - **关键坑**：`asyncSaveHistory` 原在虚拟线程（QUERY_EXECUTOR）内调 `StpUtil.getLoginIdAsLong()` → 虚拟线程无 Sa-Token ThreadLocal 上下文（「SaTokenContext 上下文尚未初始化」），失败路径静默不写历史。
  - **修复**：`execute()` 在请求线程先 `resolveCurrentUserId()` 取 userId 传入 lambda，`asyncSaveHistory(Long userId, ...)` 不再内部取登录态。
- **实测**：语法错误 9002 / 表不存在 7011 均写入历史（error=YES + errorMessage）；成功查询正常（rowCount=3, error=NO）无回归。

### 16.2 前端（app-frontend，已部署）
- **`types/data-service.ts`**：`SqlQueryHistory` 加 `errorMessage?`。
- **历史 Drawer**：失败项显示红色「失败」badge + 错误信息（truncate + title），成功项保持时长/行数。
- **错误展示优化**：`classifyError(message)` 按错误码语义分类（非只读/语法/超时/机密/失败）；结果区 `errorMsg` 时显示**专门错误面板**（圆形图标 + 分类标题 + 错误详情 + 关闭按钮），不再显示「运行 SQL 后结果展示于此」占位；`renderSecurityBanner` 对 errorMsg 返回 null（避免与主体重复）。

### 16.3 验证
- `tsc --noEmit` ✅、`pnpm build` ✅、后端 `mvn clean package` ✅（曾踩 `e.code()`→`e.getErrorCode()` 编译坑）、两容器已部署。
- 实测失败/成功历史写入均正常。

## 17. 「立即采集」提示恢复 + 采集后自动轮询刷新（2026-08-12）

> 用户反馈：①未采集→显示「立即采集」→点已采集后消失→再点回未采集，「立即采集」框出不来了 ②采集后无刷新反馈（点了采集提示「稍后刷新」，但没有刷新按钮）。

### 17.1 Bug：二次展开未采集数据源「立即采集」不恢复
- **根因**：`loadChildren` 用 `loadedRef` 缓存「已尝试加载的数据源」，二次展开直接 return（不再 setEmptySource），`emptySource` 卡在 null。
- **修复**：把「根据数据源子节点是否为空设置立即采集提示」从 `loadChildren` 移到 `handleToggle`，展开数据源时**无条件执行**（无论是否命中 loadedRef 缓存）。

### 17.2 采集后自动轮询刷新（产品方案）
- **核心洞察**：采集是异步任务，前端要解决「提交后如何反馈 + 完成后自动刷新」。标准做法是**轮询**。
- **实现**（复用 `usePollingWhile` hook）：
  - `handleCollectNow` 提交后进入 `collectingDatasourceId` 采集中状态，按钮变「采集中…」（loading+disabled），提示「正在采集元数据…」。
  - `usePollingWhile(!!collectingDatasourceId, pollCollect, {interval:3000, timeout:90000})`：每 3s 拉该数据源库列表，**非空即视为采集完成** → 更新树子节点（库列表）+ 展开该数据源 + 隐藏「立即采集」+ toast「元数据采集完成」。
  - 超时 90s 自动停止（`usePollingWhile` 内置 timeout），避免无限轮询。
- **产品价值**：用户无需手动刷新按钮，采集完成树自动更新并展开，反馈闭环。

### 17.3 验证
- `tsc --noEmit` ✅、`pnpm build` ✅、app-frontend 已重建部署。

## 18. 采集完成判定修复：库为空但任务结束不再转圈（2026-08-12）

> 用户反馈：sqlserver 数据源点「立即采集」后一直转圈，实际采集任务已结束。**根因**：数据源真的没有可采集的库/表（库列表恒空），但前端轮询只以「库列表非空」判定完成 → 库恒空 → 无限转圈。

### 18.1 后端（governance，已部署）
- **`CollectTaskService.getById` NPE 修复**：`List.of(task.getCreatedBy(), task.getUpdatedBy())` 当 `updatedBy` 为 null（自动采集任务只设 created_by）时抛 NPE → 9999。改为 `Stream.of(...).filter(nonNull).distinct().toList()`。实测 `getCollectTask` 正常返回 status=SUCCESS。

### 18.2 前端（app-frontend，已部署）
- **采集完成判定改为「任务状态」兜底**（不再仅凭库列表非空）：
  - `handleCollectNow` 保存 `collectMetadataNow` 返回的 taskId（`collectingTaskIdRef`）。
  - `pollCollect` 每 3s：先查库列表非空 → `markCollected` 成功；库空则查 `getCollectTask(taskId)` 状态 → **SUCCESS 但库空 = 停止转圈 + 提示「采集完成但未发现元数据，请检查数据源配置」**；FAILED/TERMINATED = 提示「采集任务执行失败」；RUNNING 继续等。
  - 修复「数据源真没表」时无限转圈的产品缺陷。

### 18.3 验证
- `tsc --noEmit` ✅、`pnpm build` ✅、governance `mvn clean package` ✅、两容器已部署。
- 实测：sqlserver 采集任务 SUCCESS + 库数量 0 → 前端正确提示而非转圈。

---

> **版本记录**
> - v1.0 (2026-08-12)：初始 handoff。记录「原型产品逻辑修正」会话（4 项决策 + 新增 API 运行统计页），列出 PRD/技术文档待同步项、Blocker（D4）与 Next Action。
> - v1.1 (2026-08-12)：§3 待同步项 4 项全部回落完成（PRD v1.2 + 技术文档 v1.2：全局统计端点组 / API 预览 / Key 近 7 天调用与快捷启用）；状态看板与技术文档版本同步更新；Next Action 移除回落项、新增健康分级阈值确认。
> - v1.2 (2026-08-12)：**M0 技术调研定稿会话（D4）**——反编译 `flink-cdc-composer-3.6.0-2.2.jar` 证实 Flink CDC 3.6 **不支持多 sink 双写**，原「Iceberg+Kafka 双写」废弃；与用户 4 问 4 答（q-0~q-3）拍板：**事件管道分离**（每可订阅管道独立 Kafka 单 sink 事件作业 latest-offset 增量）、**管道创建即建同生命周期**、**`apache/kafka:4.0.x` KRaft 单节点**、**仅增量推送**。PRD v1.3 + 技术文档 v1.3（D-D6 重写 / §0-F4 / §4.3 / §5.4 / §8 Blocker 1/2/6 定稿 / §9 实现清单）+ 本 handoff 状态看板/Blocker/Next Action 同步更新；Sprint 10 决策全部定稿，可开后端骨架。
> - v1.3 (2026-08-12)：**F1 SQL 终端后端完成会话**——新增 `data-nest-data-service` 服务（骨架/库/网关路由/swagger/SQL 终端）并部署 `datanest-app-data-service`（healthy）；governance V1.6.0 + internal 表清单/敏感度契约；**用户拍板业务服务本地禁 `@Scheduled`**（清理改放 job：data-service-api `DataServiceOpsApi` + job `SqlHistoryCleanupHandler`，已注册 PowerJob jobId=293；规范写入 conventions-backend §7）；API 自测 17 用例全通过；技术文档 v1.4 + 本 handoff 同步；Next Action 更新为 F2~F4。
> - v1.4 (2026-08-12)：**F1 多数据源 E2E**——放开 compose `middleware-test-oracle` + `middleware-test-sqlserver`（+`test-oracle-data` volume），新增 `oracle`/`sqlserver` 两个 NORMAL 数据源；经 SQL 终端实测 **MySQL/PG/Oracle/SQL Server 4 种库查询全通过** + MySQL 超时 9003 + SQL Server 只读拦截 9001；技术文档 v1.5。
> - v1.5 (2026-08-12)：**导出统一走后端**——用户拍板「所有导出走后端」：`XlsxExportHelper` 下沉 common（自动列宽+表头加粗）+ 新增 `CsvExportHelper`；common compile 提供 poi-ooxml，governance/data-service 冗余声明移除；SQL 终端新增 `POST /sql-console/export`（XLSX/CSV 后端生成），前端改调后端导出；xlsx/csv/只读拦截实测通过；技术文档 v1.6。
> - v1.5 (2026-08-12)：**F1 前端 + 联调 + 补后端 cancel 会话**——SQL 终端页（路由/菜单/编辑器/运行·停止/KPI/导出/历史）实现并部署 app-frontend；补 F1.1 后端（`/sql-console/cancel` 停止查询 + `SqlExecuteResult.tableCount/confidentialHits` + socketTimeout 参数化 + durationMs int）；与用户 3 问 3 答（q-0 菜单渐进式 / q-1、q-2 授权补后端）；回归全通过。技术文档 v1.5（§4 补 cancel 端点 + §9 勾选前端）。
> - v1.6 (2026-08-12)：**F1 SQL 终端产品化改版**（用户 5 点反馈 + 3 问 3 答）——左侧复用 `MetadataTree` 数据目录树（Doris 数仓多库 + 外部已采集数据源）、点表插入带库名 SELECT、结果表卡片化、查询历史下沉底部时间线、刷新反馈；实测确认 sql-console 与 governance 元数据树 datasourceId 同套、Doris 多库查询通过；机密表前端标记依赖 F5（已记 memory，handoff §7.4）。
> - v1.7 (2026-08-12)：**F1 SQL 终端 UI 改版第 2 轮（紧凑 IDE 风格）**——左侧改自定义 `SqlTree`（sql-console 全部 NORMAL 数据源根 + 元数据域库/表懒加载 + 未采集「去采集」提示）、面包屑路径显示到表级（不显示 id）、查询历史收起为 Drawer（按钮+Badge）、结果/KPI 紧凑化；3 问 3 答（历史=顶部按钮+Drawer、未采集=提示采集、UI=紧凑 IDE）；`tsc`+`build` 通过并部署（handoff §8）。
> - v1.8 (2026-08-12)：**F1 UI 改版第 3 轮**（用户 4 点反馈 + 2 问 2 答）——后端补 `POST /metadata/datasources/{id}/collect-now`（governance inline 采集，实测 SUCCESS + 元数据落地）；前端布局优化（编辑器 280px + 运行按钮精简）、SqlTree「立即采集」inline 按钮、历史 Drawer 显示数据源名 + 回填联动 `selectByPath` 全自动展开到表 + 面包屑同步；`tsc`+`build`+`mvn package` 通过并部署（handoff §9）。
> - v1.9 (2026-08-12)：**KPI 卡图标视觉优化**（对齐原型 sql-kpi 视觉）——4 个 KPI 配语义图标（闪电/对勾/服务器/警告）+ 圆形彩色背景（accent/success/neutral/warning）+ 粗体大字数字；移除原灰色细闪电；`tsc`+`build` 通过并部署（handoff §10）。
> - v1.10 (2026-08-12)：**细节修正**（用户 3 点反馈）——查询历史 Badge 角标改内联计数徽章（消除 DsButton 上角标遮挡）；页头补灰色描述行；左侧树弃 antd Tree 改手写递归树，完全对齐元数据管理页视觉（黄色文件夹/truncate+Tooltip 无左右滚动/选中高亮/计数/箭头旋转）；`tsc`+`build` 通过并部署（handoff §11）。
> - v1.11 (2026-08-12)：**SQL 查询树差异化增强**（产品定位：结构对齐元数据树 + 保留查询终端特色）——新增搜索框（searchMetadataTree，搜索模式全展开 + 空态 + 清空回正常树）、表节点 hover 提示「点击插入 SELECT」、库/模式层上下文联动（面包屑逐层收窄）；`tsc`+`build` 通过并部署（handoff §12）。
> - v1.12 (2026-08-12)：**面包屑独立导航条 + schema 适配**——`SqlTreeContext` 加 `schemaName` 字段，schema 不再误塞 tableName；面包屑从描述行抽离为独立「当前上下文」导航条（页头下方）；`selectByPath`/`fillFromHistory` 支持 db.schema.table 四层定位；`tsc`+`build` 通过并部署（handoff §13）。
> - v1.13 (2026-08-12)：**首次进入默认上下文一致性**（方案 C：自动选中 Doris 数仓）——`SqlTree.loadRoots` 加载根后自动展开+高亮内置 Doris 并同步上下文条；上下文条默认文案统一「Doris 数仓」；`tsc`+`build` 通过并部署（handoff §14）。
> - v1.14 (2026-08-12)：**失败 SQL 进历史 + 错误展示优化**——后端 Flyway V1.0.1 加 `error_message`、成功/失败统一写历史（修复虚拟线程 Sa-Token 上下文丢失导致失败历史不写）；前端历史 Drawer 失败项红标+错误信息、结果区专门错误面板（分类图标+标题+详情）；`tsc`+`build`+`mvn package` 通过并部署（handoff §16）。
> - v1.15 (2026-08-12)：**采集完成判定修复**（数据源真没表时不再无限转圈）——后端 `CollectTaskService.getById` NPE 修复（`List.of` 遇 null updatedBy 抛 9999）；前端采集轮询改「库列表非空 + 任务状态兜底」双判定，SUCCESS 但库空时提示「采集完成但未发现元数据」；`tsc`+`build`+`mvn package` 通过并部署（handoff §18）。
