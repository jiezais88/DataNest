# Sprint 10 Handoff：数据服务（SQL 查询终端 + 数据 API + API 网关 + 实时推送 + 数据分级分类）

> 更新：2026-08-13（F5 端到端 E2E 会话）
> 对应文档：`../DataNest-Sprint10-PRD.md`（v1.3）· `../DataNest-Sprint10-技术文档.md`（v1.11）· `../DataNest-Sprint10-原型.html/css`

---

## 1. 状态看板

| 交付物 | 状态 | 说明 |
|--------|------|------|
| PRD | ✅ v1.3 | **全部决策定稿（D1~D5）**；§6.3/6.4/6.5 已回落原型修正；§12.2/§13 决策记录含 D4 M0 结论 |
| 技术文档 | ✅ v1.4 | F1 SQL 终端后端实现+部署+API 自测通过（§9 勾选）；新增 `data-service-api` 契约 + job `SqlHistoryCleanupHandler`；§8 Blocker 7 定稿「业务服务本地禁 @Scheduled」 |
| 原型（HTML/CSS） | ✅ 产品逻辑修正完成 | 4 项决策落地 + 「API 运行统计」独立页；原型 = 实现基准 |
| 后端 | ✅ F1/F2/F3/F4/F5 完成 | **F1 SQL 终端** 17 用例 + F1.1 cancel 全通过；**F2 数据 API 管理端 + Key 管理**（K- 明文一次 + SHA-256 + 绑定/启停/近 7 天调用聚合）45 用例全通过 + `/apis/summary`/`/api-keys/{id}`/敏感度反查；**F3 API 网关**：对外执行入口 + Key 认证/限流 + Resilience4j 熔断 + 异步统计 + `/stats/*` 全局统计（E2E 22 用例）；**F4 WebSocket**：Kafka 事件总线 + realtime 事件管道 + data-service WebSocket（分层自测 + 真实 CDC 端到端 E2E 4 用例全通过，§25）；**F5 分级分类**：governance `SensitivityController`（改级/批量/开白/审计 + 分级列表分页，机密降级两步 + 开白仅 INTERNAL + 审计 action 区分）已实现并部署（healthy），自测 + **E2E 14 用例全通过**（§28） |
| 前端 | ✅ F1/F2/F3/F4/F5 完成 | **SQL 查询终端页**；**F2：API 管理（列表+统计卡/详情/3 步创建向导/编辑）+ API Key 管理（明文一次性展示/快捷启停/僵尸 Key 灰显）**；**F3：API 运行统计全局页 + 单 API 详情统计区块 + Sidebar 入口**（review 决策：操作列不加统计按钮）；**F4：CDC 管道详情「实时订阅」页签（订阅文档 + 连接监控）+ Key 表单绑定管道多选**；**F5：数据分级分类页（筛选/批量打标/改级/开白/审计）+ 资产详情敏感度标签与去查询/生成 API 入口 + SQL 树机密锁标记**，联调 + **E2E 验证通过**（修复无 schema 分支 sensitivityLevel 漏映射，§28） |

---

## 1.1 本次会话变更清单（F3 前端 + 补后端单 API 统计，2026-08-13）

### 1.1.1 后端补丁（data-service，未碰 task-core，已部署）
- **`ApiStatsDTO` 扩展**：加 `hourly`（今日小时调用分布）/ `topKeys`（调用方 Key 排行 Top5）/ `statusBreakdown`（2xx/4xx/5xx 三档）——支撑单 API 详情完整原型图表；新建 `StatusBreakdownDTO`。
- **`ApiCallLogMapper`** 加 3 查询（`hourlyByApiSince` / `topKeysByApiSince` / `statusBreakdownByApiSince`）；`StatsQueryService.apiStats` 填充三字段 + 私有 `topKeysByApi`（key 名反查）。
- 用户拍板「补后端端点，做完整原型」。

### 1.1.2 前端（app-frontend，已部署）
- **API 运行统计全局页** `pages/data-service/api-stats/`：KPI 4 卡 + 全局调用量趋势（双线 LineChart）+ API 健康分布（综合分+三档占比+明细可跳）+ Top5 API 排行（可跳详情）+ 错误码分布（4xx/5xx + 429 突出）+ Key 排行（僵尸灰显）+ 限流趋势（柱状）+ 状态速览（复用 `/apis/summary`）。
- **单 API 详情统计** `api-manage/ApiStatsSection.tsx`（替换「调用统计占位」）：健康评级条（0 调用显「暂无调用」）+ KPI 4 卡 + 调用量/错误率趋势（错误率由 trend 推导）+ 今日小时分布 + Key 排行 + 错误码分布（三档）+ 最近调用（最新 5 条·异常高亮）。
- **共享组件** `api-stats/charts.tsx`：KpiCard/ChartCard/RankItem/SplitBar/Bars/RangeSeg 等（全局页与单 API 详情复用，对齐质量报告 charts 模式）。
- **入口**：Sidebar「数据服务」组补「API 运行统计」；路由 `/data-service/api-stats` + 面包屑。**Review 决策（用户拍板）：操作列不加「运行统计」按钮**（单 API 统计走「查看详情」、全局统计走 Sidebar 入口，避免职责重叠）。

### 1.1.3 验证
- 后端 `mvn compile/package` 通过 + 镜像重建部署（healthy）；前端 `tsc --noEmit` + `pnpm build` 通过 + 部署。
- 实测：`/stats/*` 7 端点 + `/apis/{id}/stats`（含新字段）返回 200 结构正确；浏览器驱动验证全局页 8 区块 + 单 API 详情统计区块渲染无 JS 错误；0 调用健康评级边界已修；测试 API 已清理。
- Review 修复：两处 `useEffect` 加 cancelled 竞态防护（快速切 range 旧请求覆盖新数据）；`Bars` 用 `bucket` 作 key（原 index key 在 range 切换桶数变化时复用错位）。

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
| 2 | 全局统计接口聚合方式 | **已定稿（2026-08-13 F3）**：api_call_log 异步写 + 直接 SQL 聚合（percentile_cont/FILTER），不建独立统计表；range=24h/7d/30d | ✅ 已定稿 |
| 3 | 健康分级口径（健康/警告/严重 + 综合健康分） | **已定稿（2026-08-13 F3，用户拍板对齐告警）**：错误率/P95/限流命中任一命中升级（SEVERE 错误率≥5% 或 P95≥1000ms；WARNING 错误率≥1% 或 P95≥500ms 或限流≥5%），综合健康分 = PASS100/WARNING60/SEVERE20 平均 | ✅ 已定稿 |

---

## 5. Next Action

1. ~~**F2 API 管理 + Key**~~ ✅ 已完成（见 §19 后端 + §20 前端/联调）。
2. ~~**F3 API 网关 + 调用统计**~~ ✅ 已完成（后端见 §21，前端见 §1.1）。
3. ~~**F5 分级对外端点**~~ ✅ 已完成（后端见 §26；前端分级分类页归后续前端会话）。
4. ~~**F4 WebSocket 实时订阅**~~ ✅ 已完成（见 §23）。
5. ~~**健康分级阈值确认**~~ ✅ 已定稿（对齐告警 PASS/WARNING/SEVERE，见 §21）。
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

## 19. F2 数据 API + Key 管理端后端（2026-08-12，本会话）

> 范围：F2 管理端（技术文档 F2 行的「对外调用入口」经用户拍板归 F3）。4 问 4 答决策全部按推荐项拍板。

### 19.1 决策（用户拍板）
- **F2 范围**：只做管理端（API 定义 CRUD/生命周期 + Key 管理/绑定），对外 `/open-api/v1/**` 执行入口 + OpenApiKeyFilter 归 F3。
- **API 对外路径 = 自定义路径**（技术文档 Blocker 5 定稿）：`data_api.path` 存完整 `/open-api/v1/{段}`；输入 `orders`/`/orders`/完整路径三种形态统一归一；段规则 `^[a-z0-9][a-z0-9-_]{0,99}$`。
- **删除 = 软删**（PRD「软删保留调用统计」）：V1.0.2 加 `deleted` 列；path 唯一约束改**部分唯一索引 `WHERE deleted=0`**（删除后路径可复用，实测通过）。
- **返回字段白名单并入 params_json**（不加列）：`{"filters":[{"field","type":"EQ|RANGE"}],"fields":[...]}`，空 fields = 全部字段。

### 19.2 实现（data-service，已部署）
- **实体/Mapper**：`DataApi`/`ApiKey`/`ApiKeyBinding`/`ApiKeyPipeline`（F4 用，本期仅建实体）/`ApiCallLog`（F3 写入，本期仅聚合查询）+ 5 Mapper；绑定数/近 7 天调用走 `GROUP BY` 批量投影（`RefCount`），无 N+1。
- **`DataApiService`/`DataApiController`（`/apis`）**：创建/编辑/发布/下线/软删 + 分页（scope=mine/keyword/status）+ 详情（定义+自动文档+绑定 Key+近 7 天调用）。
  - 敏感度闸门 fail-closed（governance 不可达 9012）：PUBLIC 放行 / INTERNAL 需 `api_exempted=1` / CONFIDENTIAL 9004；**创建/编辑/发布三处都过闸门**（表可能在创建后被改级）。
  - 防注入：filters/fields 列标识符白名单 + orderBy「列名 ASC|DESC」严格正则（会拼进 SQL）→ 非法抛 9013。
  - 自动文档 `DataApiDocDTO`：参数说明（EQ→单参数、RANGE→min_/max_ 双参数）+ 分页参数 + curl 示例。
  - 编辑用 `UpdateWrapper.set` 显式写（`updateById` 忽略 null 会导致 orderBy 无法清空）。
- **`ApiKeyService`/`ApiKeyController`（`/api-keys`）**：创建（`K-`+32hex 明文仅返回一次，SHA-256 落库）/编辑（全量重绑）/快捷启停（幂等）/删除（清理绑定+管道授权）/分页（绑定 API 数 + 近 7 天调用，0=僵尸 Key）。
- **common**：错误码补 `API_DEFINITION_INVALID(9013)`、`API_KEY_NOT_FOUND(9014)`。
- **权限**：类级四角色 OR + 方法级写操作超管/工程师（Sa-Token 类+方法注解同时生效），实测分析师写操作 403。

### 19.3 Review（架构融洽/业务正确/实现高效）
- 跨服务调用全走 Feign 契约（governance 敏感度 / engineering batchGet 数据源名 / system usernames 回填），批量无 N+1；fail-closed 仅用于敏感度与数据源校验（写路径），读路径降级空 Map。
- 审计字段约定：create 仅 created_by/created_at；update 才写 updated_by/at。
- 踩坑记录：Git Bash curl `-d` 直传中文 body 是 GBK 字节 → 后端 9999（Jackson UTF-8 解析失败），自测改 Python urllib；Long 序列化为字符串是全局约定（boundKeyCount/calls7d 前端按 string 处理）。

### 19.4 自测（45 用例全通过，测试脚本与数据已清理）
- 功能 38 项：创建/路径归一/查重 9010/非法路径·orderBy·筛选 9013/列表 keyword·scope/编辑清空排序·type 归一/发布·下线·幂等/Key 全流程（明文、重名 9009、绑定、启停、重绑、哈希落库核对）/权限 403/软删后详情 9008 + 路径复用/Key 删除清理绑定。
- 敏感度闸门 7 项：PUBLIC 放行；CONFIDENTIAL 创建/编辑/重新发布全 9004；INTERNAL 未开白 9004、开白放行；测后恢复 PUBLIC。

---

## 20. F2 前端（API 管理 + Key 管理）+ 后端小补丁 + 联调（2026-08-12，本会话）

> 用户拍板（3 问 3 答）：① 列表页敏感度列/统计卡缺后端数据 → **授权补后端**；② 详情页本期做「概览+定义+文档+绑定 Key」，统计图表待 F3；③ 字段级「机密锁定」不做（NG5 无字段级敏感度，字段全可勾选，仅表级闸门）。

### 20.1 后端补丁（data-service，已部署，用户授权）
- **`GET /apis/summary`**：列表页统计卡（publishedCount/createdCount/disabledCount/totalCalls7d）。
- **`GET /api-keys/{id}`**（`ApiKeyDetailDTO`）：编辑弹窗预填当前绑定 apiIds（明文 Key 不回传）；编辑=全量重绑，无详情端点前端无法预填。
- **`DataApiPageItem`/`DataApiDetailDTO` 加 `sensitivityLevel`**：按 数据源+库+schema 分组批量反查 governance `getSensitivity`（非逐行 N+1）；读路径 fail-open——governance 不可达留空，前端显示「未知」，不阻断列表（写路径闸门仍 fail-closed）。
- **踩坑**：`@Select` 非 `<script>` 模式不解析 `&gt;` 转义 → `countCallsSince` 报 9999（column "gt" does not exist），改 `<script>` 包裹修复。
- **补「修改人/修改时间」列（2026-08-12 用户走查反馈）**：`DataApiPageItem`/`ApiKeyPageItem` 加 `updatedByName`/`updatedAt`（usernames 回填扩展到 updatedBy），API 列表 + Key 列表各补 2 列（对齐原型）；实测编辑后两字段正确返回，测试数据已清理。
- **API Key 管理改独立菜单（2026-08-12 用户拍板）**：Sidebar「数据服务」组新增「API Key 管理」（`/data-service/api-keys`，`HiOutlineFingerPrint`，ALL_ROLES）；API 管理页头「API Key 管理」按钮与 Key 页「返回 API 管理」按钮随之移除（菜单直达，避免冗余入口）。
- **全站用户可见文案产品化（2026-08-12 用户反馈「后端只存哈希这类描述不产品化」）**：梳理所有页面用户可见文案（页头描述/空态/toast/确认框/提示条/tooltip），清除实现视角表述——HTTP 状态码（401/404/429）、「后端只存哈希」「Redis 滑动窗口」「Retry-After」「RESTful」「X-API-Key 头」「落库」「savepoint」「接口雏形/语义名」等改为用户价值语言（如「业务系统将无法再调用」「完整 Key 仅展示一次，请妥善保管」「超限的调用会被拒绝并提示业务方稍后重试」）；CDC 列表/向导描述从技术链路（Binlog→Flink→Iceberg→MinIO）改为用户价值（秒级捕获业务库变更，实时同步湖仓与数仓）。代码注释保持技术表述不变。

### 20.2 前端（app-frontend，已部署）
- **路由/菜单**：`/data-service/api-manage`（列表）+ `/new`（向导）+ `/:id`（详情）+ `/:id/edit` + `/data-service/api-keys`；Sidebar「数据服务」组补「API 管理」（ALL_ROLES，页内写操作按 `DATA_SERVICE_WRITE_ROLES` 隐藏）；breadcrumb map 补 3 条；`constants/roles.ts` 新增 `DATA_SERVICE_WRITE_ROLES`（超管+工程师）。
- **API 列表页**：StatsCards 4 卡（已发布/待发布/近 7 天总调用/已下线，点击下钻状态筛选）+ 搜索/状态筛选/「我的 API·全部」seg + 表格（名称/路径/数据表/敏感度/状态/绑定 Key/近 7 天调用/创建人/时间/操作=查看·编辑·发布|下线·删除）。
- **创建向导 3 步**：① 选表（数据源→库→schema→表 radio 列表带敏感度徽章，机密表禁选+锁图标提示，内部表警告需开白；右侧 API 预览卡实时生成 `GET /open-api/v1/{表名}` + 暴露字段清单）→ ② 配置接口（共享 `ApiConfigForm`：名称/路径段（前缀只读展示）/字段一行三配「暴露 checkbox + EQ/RANGE 筛选」/排序字段+方向/分页+pageSizeMax；前端预校验与后端白名单同规则）→ ③ 绑定 Key（暂不绑定/绑定已有 Key 勾选/新建 Key 三选；新建 Key 成功后明文一次性弹窗）。
- **编辑页**：数据源/库/表只读卡 + ApiConfigForm 预填（orderBy 拆字段+方向、fields 空=全字段展开为全列勾选、filters 回填）；列清单优先 metadataTableId 直取，缺失时按数据源+库+表反查元数据，再退化按当前定义展示。
- **详情页**：状态/敏感度徽章 + 发布/下线/编辑/删除 + 复制 curl + 基本信息（数据源/库表/绑定 Key 数/近 7 天调用/审计）+ 接口定义（筛选/返回字段/排序/分页）+ 调用文档（参数表/返回结构/curl）+ 绑定 Key 列表 + F3 统计占位提示。
- **Key 管理页**：列表（名称/状态/绑定 API 数/QPS/近 7 天调用 0=僵尸灰显+tooltip/创建人/时间/操作=编辑·启停·删除）+ 新建/编辑弹窗（`KeyFormModal`：名称/QPS/绑定 API checkbox 列表；创建成功切换明文展示视图，禁遮罩关闭防明文丢失）+ 底部限流说明+僵尸 Key 建议提示条（对齐原型 hint-box）。
- **与原型偏差（用户确认）**：字段级「机密锁定」不做（NG5）；详情页统计图表区占位待 F3；API 列表「敏感度筛选」下拉不做（列保留，跨服务过滤会破分页）。
- `types/metadata.ts` 补 `sensitivityLevel`/`apiExempted`（治理实体早已返回，前端类型补声明）。

### 20.3 Review（AGENTS §7 三点）
- 架构融洽：跨域数据全走既有 Feign/前端 API 层；敏感度反查走 governance-api 契约批量端点，分组非逐行；列表页 usePagedList、StatsCards/DsToolbar/DsFilterSelect 等全复用全局组件，无自建重复。
- 业务正确：权限对齐 PRD §8（查看四角色、写超管/工程师，后端注解兜底）；审计字段/软删/路径归一语义与后端一致；明文 Key 仅创建返回。
- 实现高效：列表聚合全走后端 GROUP BY 投影；Key 选项/绑定 API 选项一次 pageSize=100 拉取；无循环远程调用（向导绑定已有 Key 的逐个 update 为用户触发的有界操作）。
- 自修复：冒烟首跑发现「新建 API」按钮双匹配（页头+空态 CTA）与 ods 无 users 元数据表（实有 target_users）两处用例问题；完整 E2E 归专门测试会话。

### 20.4 验证
- `mvn clean package`（data-service）✅、`tsc --noEmit` ✅、eslint 0 警告 ✅、`pnpm build` ✅；两容器重建部署，镜像时间戳新、data-service healthy。
- **后端联调 python 21 用例全过**（summary/create/detail 敏感度/编辑清排序/发布/下线/重发布/Key 明文一次/详情 apiIds/重绑/启停/列表聚合/软删 9008），测后数据已清理。
- **前端冒烟**：列表页元素用例通过；向导页经运行快照确认渲染正确（表 radio 列表 + 敏感度徽章 + API 预览路径/字段清单均正常）——完整 E2E 由专门测试会话承担（用户明确），临时 spec 与 test-results 已清理不入库。

---

## 21. F3 API 网关 + 调用统计后端（2026-08-13，本会话）

> 范围：对外执行入口 / 限流 / 熔断 / 调用统计 + 全局统计（技术文档 F3 行 + `/stats/*`）。2 问 2 答决策：**Key 级 QPS 限流**（对齐 PRD 6.4/AC-7，data_api 无 QPS 字段不引入 API 级）、**健康分级对齐告警 PASS/WARNING/SEVERE**。

### 21.1 实现（data-service，已部署 healthy）
- **对外执行入口**：`OpenApiController` `GET /open-api/v1/{path}` + `OpenApiService`——状态校验（未发布 404/9007）→ 熔断检查（数据源维度 503/9015）→ 参数化 SQL 执行（内置 Doris / 外部数据源）→ 分页 COUNT → 记录熔断结果 + 异步写 api_call_log。**对外用 HTTP 状态码语义**（401/404/429/503/200），区别于管理端 Result 信封 200。
- **OpenApiKeyFilter**（OncePerRequestFilter 拦截 `/open-api/**`）：X-API-Key → SHA-256 命中启用 Key → 按 servlet path 查 API → 绑定校验 → Key 级限流；失败 401（无效/禁用/未绑定）/404（API 不存在）/429（限流 + Retry-After），通过后 request attribute 传递 api/key 避免 Controller 重复查库。
- **RateLimitService**：Redis ZSET 滑动窗口（`datanest:ratelimit:{keyId}`，Key 级 QPS，窗口 60s 可配）。
- **CircuitBreakerService**：Resilience4j 按数据源维度（`ds-{datasourceId}`，内置 Doris 同 -1 维度），COUNT_BASED 窗口=failure-threshold、失败率 50%、waitSeconds 半开探测。
- **ApiCallLogWriter**：虚拟线程 + 队列背压异步写 api_call_log（不阻塞主链路，NAC-6）。
- **OpenApiSqlBuilder**：参数化 SELECT 构造（filters EQ/RANGE 绑定 + fields 白名单按类型转义 + orderBy 白名单 + 分页按类型 LIMIT/OFFSET|OFFSET FETCH），参数值启发式推断类型（整数 Long / 小数 BigDecimal / 字符串）避免 PG 数值列 setString 类型不匹配。
- **执行器扩展**：`CancelableSqlExecutor` 加 PreparedStatement 参数化查询路径（queryExternal/queryDoris 带 `List<Object>` params），抽 collect 复用结果提取（未碰 task-core）。
- **统计**：`StatsController` 7 全局端点（overview/trend/health-distribution/top-apis/error-codes/top-keys/rate-limit-trend）+ `DataApiController` 补 `/apis/{id}/stats`；`StatsQueryService` 从 api_call_log 聚合（percentile_cont/FILTER，PG 语法）；健康分级对齐告警 PASS/WARNING/SEVERE（错误率/P95/限流命中任一命中升级，综合分 PASS100/WARNING60/SEVERE20 平均）。
- **错误码**：common 补 `API_CIRCUIT_OPEN(9015)`。

### 21.2 自测（全通过，测试数据已清理）
- Key 认证：无 Key / 错 Key 401（9005）；正确 Key 200 ✅。
- 参数化：`id=1` EQ 筛选返回 1 行；`fields=["id","username"]` 白名单只返两列；分页 total=3 ✅。
- 限流：qpsLimit=2 第 3 次 429 + Retry-After=60（9006）✅。
- 熔断：坏表连续 3 次 500 → 第 4 次起 503（9015）→ 30s 自动半开 + 成功请求闭合 ✅。
- 未发布：CREATED API 调用 404（9007）✅。
- 统计：overview（total/successRate/p95/rateLimited）、error-codes（429 TopN）、top-apis、health-distribution（SEVERE 分级 + overallScore）、单 API stats（total/successRate/avg/p95/today/trend/recentLogs）✅。
- `mvn clean package` ✅ + 镜像重建部署 + 容器 healthy；api/api_key/api_call_log 测试数据已清理（0 残留）。

### 21.3 Review（架构融洽 / 业务正确 / 实现高效）
- 架构：跨服务走既有 Feign（engineering 数据源 / governance 敏感度）；复用 CancelableSqlExecutor 扩展（未碰 task-core）；统计聚合单 SQL 无 N+1；对外路径匹配 servlet path = data_api.path。
- 已知取舍（待后续细化）：① 熔断把「表不存在」也记数据源失败（坏 API 会熔断整个数据源——数据源维度语义，可后续仅对连接失败/超时熔断）；② failureRateThreshold=50 实际 3 次失败开闸（比「连续 5 次」更敏感，可调 100 严格对齐）；③ Redis 限流非原子（removeRange+zCard+add，极端并发少量超发，顺序验收准确）。
- bug 修复：`buildSelectColumns` 未传数据源类型致 PG/Oracle 字段反引号转义错误 → 已修复并重新部署。

---

## 22. F3 完整 E2E 测试会话（2026-08-13，本会话）

> 范围：F3 对外网关（认证/参数化执行/限流/熔断）+ 调用统计（单 API + 全局 + 前端观测页）。新增 3 个测试文件，**22 用例全通过**。

### 22.1 测试文件
- **`e2e/sprint10/e2e/helpers/f3-seed.ts`**：自播种自清理（F3 前缀 `e2e_s10_f3_`）+ `openApiCall`（带 `X-API-Key` 头直调对外入口，不登录，与业务系统视角一致）。
- **`e2e/sprint10/e2e/open-api.spec.ts`（18 用例）**：对外认证 / 参数化执行 / 限流 / 熔断。
- **`e2e/sprint10/e2e/api-stats.spec.ts`（4 用例）**：单 API 统计 / 全局统计 7 端点 / 前端运行统计页 + 详情页统计区块。

### 22.2 覆盖点（对齐 PRD AC-6/7/8/9 + DS-04）
- **认证**：无/错/禁用/未绑定 Key → 401（9005）；路径不存在 → 404（9008）；未发布/下线 → 404（9007）。
- **参数化**：EQ 等值（category=手机 → 6 行）、RANGE 范围（4000~7000 → 4 行）、orderBy（price DESC）、分页 page+pageSize+total、fields 字段裁剪、pageSize 超上限 clamp（pageSizeMax=3）。
- **限流**：QPS=1 第 2 次 429 + `Retry-After`；**窗口 60s 过期后恢复可调**（实测等 63s）。
- **熔断**：坏表连续失败 → 开闸 503（9015）+ 同数据源其它 API 一并 503（数据源维度）；**30s 半开探测通过自动闭合**（实测等 35s）。
- **统计**：api_call_log 异步落库轮询；单 API `/apis/{id}/stats`（total=5/successRate=0.8/today/recentLogs 429 异常高亮/statusBreakdown 三档）；全局 `/stats/*` 7 端点；前端运行统计页（KPI 4 卡 + 7 区块）+ 详情页统计区块（429 明细端到端打通）。

### 22.3 Review / 踩坑
- **熔断器内存态按数据源维度（Doris=-1）**：历史失败残留（含手动冒烟）会污染「连续 5 次失败」计数，导致熔断提前开闸。用例改为「开闸前均为 500 + 必然出现 503」的稳健断言（容忍 ≤4 次残留）；**E2E 跑熔断前需干净状态**（容器重启或前次自愈后）。
- **Long 序列化为字符串**（全局约定）：`todayCalls`/`statusBreakdown.*`/`mine.totalCalls` 需 `Number()` 包裹再断言。
- 测试数据自清理（`e2e_s10_f3_` 前缀 0 残留）+ 测试产物（test-results/pw-out）已清。

---

## 23. F4 WebSocket 实时订阅后端（2026-08-13，本会话）

> 范围：Kafka 事件总线 + Flink CDC 事件管道 + data-service WebSocket（技术文档 F4 行）。2 问 2 答拍板：**分层自测 + 部署**（真实 CDC 端到端归后续专门测试会话）、**机密管道全建事件作业 + 订阅侧拒绝**（realtime 不依赖 governance，服务边界清晰）。

### 23.1 实现（已部署 healthy）
- **中间件**：compose 新增 `middleware-kafka`（`apache/kafka:4.0.0` KRaft 单节点，9092，topic 保留 7d，仅内网）+ `kafka-data` volume；Flink lib 增 `flink-cdc-pipeline-connector-kafka-3.6.0-2.2.jar`；TaskManager `numberOfTaskSlots` 1→2（事件作业额外占 1 slot）。
- **realtime 事件管道**：`CdcPipeline` 加 `cdc_events_flink_job_id`（Flyway V1.4.0）；`CdcYamlBuilder.buildEvent`（Kafka 单 sink 事件 YAML，latest-offset 仅增量，MySQL server-id 6400+ / PG 复制槽 `datanest_cdc_ev_` 错开主管道）；`start/stop/forceStop` 联动事件作业（best effort，失败不阻断主管道）；`CdcMonitorService.pollEventJobs` 覆盖事件作业（FAILED/404/外部停止清字段）；`FlinkJobService.cancelJob`（PATCH /jobs/{id} 不做 savepoint）；realtime-api 加 `getSubscribeInfo`（管道状态+源表清单）+ internal 端点。
- **data-service WebSocket**：`WsEventsHandler`（握手+subscribe/unsubscribe）+ `WsHandshakeInterceptor`（X-API-Key SHA-256 校验，401 拒连）+ `WebSocketSubscriptionRegistry`（双向索引 fan-out）+ `KafkaEventConsumer`（@KafkaListener topicPattern 消费 `cdc-events-*`，Debezium→归一化 fan-out）+ `WsSubscriptionService`（Key 绑定 9005 / 管道 RUNNING 9016 / 机密表 9004 / 治理不可达 9012 fail-closed）。
- **补 F2 缺口**：`ApiKey` 绑定管道管理端点（create/update/detail 加 `pipelineIds`，`api_key_pipeline` 全量重绑）。
- **common 补 9016** `API_PIPELINE_UNAVAILABLE`。

### 23.2 自测（全通过，测试数据已清理）
- 握手：无/错 Key 拒连、正确 Key 连接成功 ✅
- subscribe：绑定管道 → subscribed；未绑定 → 9005；ERROR 管道 → 9016 ✅
- Kafka fan-out：向 `cdc-events-{pipelineId}` 发 Debezium UPDATE → 收到归一化 `{pipelineId/table/opType/data/ts}` ✅
- 部署：Kafka + Flink + realtime + data-service 全部 healthy；Flyway V1.4.0 应用 ✅

### 23.3 Review / 已知取舍
- **Spring Boot 4 spring-kafka 未自动配置 `kafkaListenerContainerFactory`**：显式在 `DataServiceConfig` 定义 ConsumerFactory + ListenerContainerFactory（`@EnableKafka` + 显式 bean）。
- **网关 WebSocket 代理先 101**：data-service 握手拦截器拒绝后连接关闭（1002），而非 HTTP 401（网关代理限制；拒连语义正确，AC-10 满足）。
- **心跳 60s ping/pong** 由 Spring 原生支持（客户端 ping 服务端 pong）；**空闲 120s 断开未实现**（依赖客户端心跳，断线重连由业务端负责，PRD §6.6）。
- **事件作业端到端已验证（§25）**：真实 Flink CDC → Kafka → WebSocket 端到端（AC-10 的 10s 收到）4 用例全通过；期间修复 flink-json 依赖缺失 + topicPattern 消费者不发现新 topic + ts_ms 缺失三处缺陷。


## 24. F4 WebSocket 实时订阅前端 + 连接监控（2026-08-13，本会话）

> 范围：CDC 管道详情「实时订阅」页签（订阅文档 + 连接监控）+ Key 表单「绑定管道」多选 + nginx WebSocket 升级头。用户拍板「订阅文档 + 连接监控」（超出技术文档 §9 原「订阅地址/协议/示例代码」范围）。

### 24.1 后端补丁（data-service，已部署 healthy）
- **连接监控埋点** `ws/SubscriptionMetrics.java`（内存态）：按管道统计今日事件数/推送失败数/端到端延迟 P95（环形采样 1024）+ 按订阅方 Key 统计接收事件数/最近事件时间；跨天自动重置。
- **`KafkaEventConsumer`** 埋点：fan-out 成功送达记 `recordEvent(pipelineId, latencyMs, keyId)`、失败记 `recordFailure`；`normalize` 返回 `NormalizedEvent(json, latencyMs)`（ts_ms → 消费时刻延迟）。
- **查询端点** `GET /subscriptions/{pipelineId}/stats`（`WsSubscriptionController` + `WsSubscriptionQueryService`）：在线连接（registry）+ 埋点快照 + 订阅授权（`api_key_pipeline` join `api_key`，批量无 N+1）+ 用户名回填（system-api）。

### 24.2 前端（app-frontend，已部署）
- **CDC 管道详情「实时订阅」页签** `SubscribeTab.tsx`（第 4 tab）：非 RUNNING 提示 + 连接监控（4 KPI 卡 + 订阅方 Key 表格，RUNNING 5s 轮询）+ 订阅文档（订阅地址/认证/订阅消息/变更事件示例/JS 示例/订阅说明，全部可复制）；订阅地址 host 由 `window.location` 推导。
- **Key 表单「绑定管道」多选** `KeyFormModal.tsx`（`pipelineIds` 全量重绑）+ `types`/`api` 补 `SubscriptionStats`/`SubscriberItem`/`getSubscriptionStats`。
- **`KpiCard` 提取** 到 `shared.tsx`（MonitoringTab/SubscribeTab 共用，DRY）。
- **nginx** 补 WebSocket 升级头（`proxy_http_version 1.1` + `Upgrade`/`Connection` 头）。

### 24.3 验证 / 踩坑
- 后端端点 200 + 字段结构正确（Long 计数 → string）；Key 绑定管道后端（创建 → pipelineIds 保存 → 删除清理）通过；WebSocket 握手链路（nginx 101 转发 + 无 Key 拒连 1002）通过。
- **踩坑（前端精度丢失）**：`Number(detail.id)` 对 19 位 Long 主键超 2^53 精度丢失（`...1073` → `...1000`），订阅消息 pipelineId 错误；改字符串持有 + 直接拼 JSON 数字（订阅消息/事件示例）+ JS 示例用字符串形式（后端 fastjson2 `getLong` 对 String 做 `Long.parseLong`，源码确认）。
- **事件作业端到端已验证（§25）**：真实 Flink CDC → Kafka → WebSocket 端到端（AC-10 的 10s 收到）4 用例全通过。

## 25. F4 真实 CDC 端到端 E2E（2026-08-13，本会话）

> 范围：兑现 §23.3/§24.3 预留的「真实 Flink CDC → Kafka → WebSocket 端到端（AC-10 10s 收到）」专门测试会话。核心是让 RUNNING 管道的事件作业真正跑通，并固化为 Playwright E2E。

### 25.1 三处缺陷定位与修复
- **realtime 缺 `flink-json` 依赖**：事件作业 Kafka sink 序列化需 `flink-json` 的 `JsonFormatOptionsUtil`（KeySerializationFactory 依赖），缺失导致事件作业启动失败。修复：`data-nest-realtime/pom.xml` 加 `flink-json`（版本由根 `pom.xml` `${flink.version}` 统一管理）。
- **data-service topicPattern 消费者不发现新 topic**：`cdc-events-{pipelineId}` topic 在 data-service 启动后才创建，`@KafkaListener(topicPattern)` 消费者默认 metadata 刷新（5min）期间无法感知。修复：`DataServiceConfig` 加 `ConsumerConfig.METADATA_MAX_AGE_CONFIG=10000`（10s 刷新，实测 15s 内动态发现新 topic）。
- **Flink CDC 3.6 debezium-json 不输出 ts_ms**：反编译 `flink-cdc-pipeline-connector-kafka-3.6.0-2.2.jar` 确认序列化枚举固定为 before/after/op/source{db,table}（无 ts_ms，不可配置），data-service 归一化事件 `ts` 恒 null。修复：`KafkaEventConsumer.normalize` 在 ts_ms 缺失时退用 `record.timestamp()`（Kafka 消息时间戳，Flink 写入时刻，接近事件发生时间）。

### 25.2 E2E 测试（4 用例全通过）
- 新增 `e2e/sprint10/e2e/helpers/f4-seed.ts`（自播种自清理：查找 RUNNING `e2e_s10_f4_` 管道 + 创建绑定 Key + MySQL 源表直写 + 删除 Key/清源表数据）。
- 新增 `e2e/sprint10/e2e/ws-subscription.spec.ts`（serial，前置缺管道自动 skip 不误报失败）：
  - WS-1 握手认证：无 Key / 错 Key 拒连 1002，未收到 subscribed
  - WS-2 订阅成功：正确 Key 绑定管道 → subscribed
  - WS-3 端到端 AC-10：订阅后源表 INSERT → 10s 内收到 INSERT 事件（实测 576ms），字段校验（table/opType/data.name/ts）
  - WS-4 订阅校验：未绑定管道 → error 9005
- 依赖 `ws` 库（握手 X-API-Key 头，浏览器原生 WebSocket 无法自定义头），加 `ws` + `@types/ws` 到 devDependencies。

### 25.3 验证
- `npx tsc --noEmit` 通过；`npx playwright test e2e/sprint10/e2e/ws-subscription.spec.ts` 4 用例全通过。
- 测试数据自清理：源表 `e2e-s10-f4-` 前缀 0 残留、api_key 表 `e2e_s10_f4_` 前缀 0 残留；test-results/pw-out 产物已清。

---

## 26. F5 数据分级分类后端（2026-08-13，本会话）

> 范围：governance `SensitivityController`（改级/批量/开白/审计 + 分级列表分页）。3 问 3 答拍板：**补分级列表分页端点**（前端分级页需要，§5.3 未列）、**批量改级全有或全无**、**审计加 action/remark 字段（V1.7.0）**。三端闸门（SQL/API/WebSocket）F1/F2/F4 已完成。

### 26.1 实现（已部署 healthy）
- **governance 分级端点**（`SensitivityController`，context-path /governance）：`PUT /metadata/tables/{id}/sensitivity` 单表改级（治理员/超管）；`POST /metadata/tables/sensitivity/batch` 批量改级；`PUT /metadata/tables/{id}/api-exempt` 内部表开白（仅超管）；`GET /metadata/sensitivity/audit` 审计（回填操作人）；`GET /metadata/sensitivity/tables` 分级列表分页（敏感度筛选 + 数据源筛选 + keyword）。
- **`SensitivityService`**：核心规则——机密降级两步（CONFIDENTIAL→PUBLIC 拒绝 4012，必经 INTERNAL）、开白仅 INTERNAL（9011）、审计区分 CHANGE_LEVEL/API_EXEMPT。
- **`SensitivityChangeLog` 实体/Mapper** + Flyway V1.7.0（sensitivity_change_log 加 action/remark）。
- **common 补 4011/4012**（敏感度级别非法 / 机密降级禁止）。

### 26.2 自测（全通过，测试数据已清理）
- 改级：PUBLIC→INTERNAL 200；INTERNAL→CONFIDENTIAL 200；**CONFIDENTIAL→PUBLIC 4012（两步拦截）**；CONFIDENTIAL→INTERNAL 200；级别非法 4011；表不存在 4006 ✅
- 开白：INTERNAL 表 200 + 审计（API_EXEMPT remark=开白）；PUBLIC 表 9011 ✅
- 批量：正常 200；含机密→PUBLIC 4012 整体拒绝（s4_logs 回滚验证仍 PUBLIC）✅
- 审计：action 区分 CHANGE_LEVEL/API_EXEMPT，operatorName 回填 admin ✅
- 分级列表：分页 + sensitivityLevel 筛选 + datasourceName 回填 ✅

### 26.3 Review / 已知取舍
- 分级列表端点 §5.3 未列，前端分级页需要（用户拍板补）。
- 开白审计加 action/remark（用户拍板 V1.7.0），可区分改级/开白/取消开白四动作。
- 改级不校验 ONLINE（OFFLINE 表也可改级，分级记录独立于在线状态，PRD §7「分级保留记录」）。
- 复用 SystemUserResolver/RemoteCalls 批量回填用户名/数据源名（无 N+1）。

---


## 27. F5 数据分级分类前端 + 联调（2026-08-13，本会话）

> 范围：数据分级分类页 + 资产详情敏感度标签与入口 + SQL 树机密锁标记 + 去查询/生成 API 跳转预填。2 问 2 答拍板：**SQL 树机密表锁标记（显示+禁用，非「默认隐藏」）** / **分级页补后端 DTO 对齐原型（来源/创建人/创建时间）**。

### 27.1 后端补列（governance，已部署 healthy）
- `SensitivityTableItemDTO` 加 `taskSourceType`（来源）/`createdBy`/`createdByName`/`createdAt`；`SensitivityService.toTableItemDTO` 填充 + `fillItemUserNames` 补 createdByName 批量回填（无 N+1）。

### 27.2 前端（app-frontend，已部署）
- **数据分级分类页** `data-service/classification/index.tsx`：敏感度/数据源筛选 + 关键词搜索 + 批量打标（设为机密/内部/公开）+ 单表改级下拉 + 内部表开白（超管）+ 审计记录弹窗 + 分级策略说明；路由 `/data-service/classification` + Sidebar 入口。
- **资产详情页** `assets/detail/index.tsx`：敏感度标签（页头徽章 + 基础信息「敏感度」项）+ 「去查询」/「生成 API」按钮（机密表禁用 + tooltip）。
- **跳转预填**：SQL 终端读 URL 参数 → `selectByPath` + 插入 SELECT + 面包屑；API 向导读 URL 参数 → 数据源/库/schema 优先 + 表加载后自动选中（机密表不预选）。
- **SQL 树机密锁标记** `SqlTree.tsx`：表节点 sensitivityLevel=CONFIDENTIAL 时锁图标 + 点击拦截「机密级，无权查询」（执行时后端 confidentialHits 兜底）。

### 27.3 验证 / 踩坑
- 后端端点 200 + 新字段（taskSourceType/createdAt 有值）；改级/审计/机密降级两步（4012）链路验证通过；前端浏览器联调：分级页渲染、资产详情机密禁用、SQL 树锁标记+拦截、去查询预填 `SELECT * FROM postgres.s4_test.s4_logs`、生成 API 跳 wizard 预选表全通过。
- **踩坑 3 处（build/联调发现并修）**：① `DsButton` 无 size 属性（误用 → className 紧凑化）② wizard `handleSelectTable` 声明前使用（effect 移到声明后）③ 「生成 API」跳转路径 `/api-manage/wizard` 应为 `/api-manage/new`（wizard 被 `:id` 路由拦截显示「API 不存在」）。
- **教训**：`npx tsc --noEmit` 在本项目无效（tsconfig.json 空 files + references），真正的类型检查是 `pnpm build` 的 `tsc -b`。
- **已知局限（不修）**：SQL 树搜索模式不标记机密（执行时后端兜底拦截，无安全漏洞）；分级页数据源筛选不含内置 Doris（-1 伪数据源）；批量设为公开含机密表时后端拒绝（前端无预校验，后端 4012 兜底）。

---

## 28. F5 数据分级分类完整 E2E（2026-08-13，本会话）

> 范围：兑现 F5 的「业务流程 E2E」（AC-11 分级拦截闭环 + AC-12 审计 + AC-13 资产联动）。覆盖全部功能点：分级管理（改级/批量/开白/审计/列表/权限）+ 三端闸门联动（SQL/API/WebSocket）+ 资产详情。用户拍板：**完整闭环**（三端闸门联动）+ **复用现有元数据表**（测后复位）+ **浏览器 E2E 为主、API 辅助诊断**。

### 28.1 E2E 测试（14 用例全通过）
- 新增 `e2e/sprint10/e2e/helpers/f5-seed.ts`（复用现有 ONLINE 元数据表 target_products/e2e_s5_lin_target + 测后复位 PUBLIC + 清开白 + 清审计 + 复用 F2 测试用户）+ `classification.spec.ts`（serial）。
- **分级管理核心**（CL-1~7，浏览器）：页面加载 / 关键词搜索 / 单表改级下拉（PUBLIC→INTERNAL）/ 机密降级两步（INTERNAL→CONFIDENTIAL 成功、CONFIDENTIAL→PUBLIC 4012 徽章仍机密）/ 批量打标 / 超管开白（已开白标记）/ 审计弹窗（改级+开白记录）。
- **三端闸门联动**（CL-8~11，浏览器 + `ensureConfidential` API 前置设机密）：SQL 终端机密拦截（9004「SQL 命中机密数据表」错误面板「机密数据保护」）/ SQL 树机密锁标记+点击拦截「机密级，无权查询」/ 资产详情「去查询/生成 API」机密禁用 / API 向导机密表禁选。
- **权限矩阵 + 后端规则**（CL-12~14，API 辅助）：工程师/分析师改级拦截（envelope code=1005「无权限访问」，非 HTTP 403 直接）/ 治理员可改级/开白仅超管；4012 两步 / 4011 非法 / 批量含机密→公开整体回滚 4012 / 9011 开白；审计完整性（CHANGE_LEVEL/API_EXEMPT 区分 + 操作人回填）。

### 28.2 发现并修复缺陷
- **真实缺陷（SqlTree 无 schema 分支漏映射 sensitivityLevel）**：`SqlTree.tsx` 的「无 schema 数据源」表节点映射（`listMetadataTablesWithoutSchema` 分支，Doris/MySQL 单库）漏了 `sensitivityLevel` 字段，而有 schema 分支（PG 多库）正确映射——导致 datanest 库（Doris 无 schema）的 SQL 树机密表锁标记/点击拦截**不生效**（E2E 发现：API 返回 CONFIDENTIAL 但树节点 tooltip 显示「点击插入 SELECT」）。已修复（补 `sensitivityLevel: t.sensitivityLevel`），`pnpm build` 通过 + 重建部署 app-frontend。

### 28.3 踩坑
- 「查询」按钮 `getByRole('button',{name:'查询'})` 子串匹配到 sidebar「SQL 查询终端」→ 加 `exact: true`；改级下拉选项与工具栏原生 `<option>` 冲突 → 用 `.ant-select-item-option`；审计弹窗/关闭按钮多匹配 → `.first()`/`.last()`；权限拦截 envelope code=1005（非 HTTP 层 403）；Monaco `keyboard.type` 被自动补全干扰丢字符（SELECT→SEL、datanest.target_products→datanesarget_products）→ 用 `window.monaco.editor.getModels()[0].setValue()`（monacoSetup 暴露全局，注释注明供 e2e 探测）+ 「运行」按钮。
- 测试数据自清理：敏感度复位 PUBLIC + 开白清零 + 审计 0 残留 + 测试用户 0 残留；test-results/pw-out 产物已清。

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
> - v1.16 (2026-08-12)：**F2 数据 API + Key 管理端后端完成**——4 项决策拍板（只做管理端/自定义路径/软删加列/白名单并入 params_json）；`DataApiController`+`ApiKeyController` 全端点实现并部署；V1.0.2 软删迁移（path 部分唯一索引）；敏感度闸门 fail-closed 三处（创建/编辑/发布）；标识符与排序白名单防注入；common 补 9013/9014；45 用例自测全通过（handoff §19）；技术文档 v1.7（Blocker 5 定稿 + §3.0/§9/§9.1 同步）。
> - v1.17 (2026-08-12)：**F2 前端 + 联调完成**——用户 3 问 3 答（授权补后端 3 处：`/apis/summary`、`/api-keys/{id}`、PageItem/Detail 敏感度反查；详情页统计图表待 F3；字段级机密锁定不做）；前端 API 管理（列表+统计卡/详情/3 步向导/编辑）+ Key 管理（明文一次性展示/快捷启停/僵尸 Key 灰显）实现并部署；踩坑 `@Select` 非 script 不解析 `&gt;`；后端 python 联调 21 用例全过 + 前端冒烟通过（完整 E2E 归专门测试会话，临时 spec 未入库，handoff §20）；技术文档 v1.8。
> - v1.18 (2026-08-13)：**F2 完整 E2E 测试会话**——api-manage 24 用例 + api-keys 11 用例 **35 用例全通过**（覆盖 API 列表/3 步向导/详情/编辑/生命周期软删/权限矩阵 403/敏感度闸门 9004/Key 明文一次·启停·重绑·僵尸灰显/绑定联动）。修复 2 处：① **后端缺陷（用户授权）**——governance `MetadataTableMapper.selectTablesByDatasourceDatabaseSchema`/`selectTableDetailById` 手写 SQL 漏 SELECT `sensitivity_level`/`api_exempted` 两列，导致 F2 向导页「机密表禁选+机密徽章」「内部表开白警告」失效（后端 9004 闸门本身正常，走 internal 接口 fail-closed）；已补两列 + 重建部署 `app-governance`（实测 PUBLIC/CONFIDENTIAL 均正确返回）；② **测试定位器缺陷**——AM-21 删除按钮 `getByRole('button',{name:'删除'})` 因子串匹配「e2e_s10_待删除」触发 strict mode 歧义，改 `exact:true`。测试数据自清理（e2e_s10_ 前缀 0 残留）+ 敏感度复位 PUBLIC + 测试产物（pw-out/test-results）已清。
> - v1.19 (2026-08-13)：**F3 API 网关 + 调用统计后端完成**——对外执行入口（`OpenApiController`/`OpenApiService`，HTTP 状态码语义 401/404/429/503/200）+ `OpenApiKeyFilter`（Key 认证/绑定/限流）+ `RateLimitService`（Redis ZSET 滑动窗口）+ `CircuitBreakerService`（Resilience4j 数据源维度熔断）+ `ApiCallLogWriter`（异步统计）+ `OpenApiSqlBuilder`（参数化 SQL，参数值类型启发式推断）+ 执行器 PreparedStatement 扩展（未碰 task-core）+ `StatsController` 7 全局端点 + `/apis/{id}/stats`；2 问 2 答拍板（**Key 级 QPS 限流** / **健康分级对齐告警 PASS/WARNING/SEVERE**）；common 补 `API_CIRCUIT_OPEN(9015)`；自测全通过（Key 认证 401/200、参数化 EQ/fields/分页、限流 429+Retry-After、熔断 500→503→闭合、未发布 404、统计 7 端点）+ 测试数据清理（handoff §21）。
> - v1.20 (2026-08-13)：**F3 完整 E2E 测试会话**——新增 `open-api.spec.ts`（18 用例）+ `api-stats.spec.ts`（4 用例）+ `helpers/f3-seed.ts`，**22 用例全通过**（对外认证 401/404、参数化 EQ/RANGE/排序/分页/字段裁剪/clamp、限流 429+Retry-After+60s 恢复、熔断 503+数据源维度+30s 闭合、统计单 API+全局 7 端点+前端页）；技术文档 v1.11（§9 前端清单勾选补齐 + E2E 记录）；踩坑：熔断器内存态需干净状态（用例对历史残留稳健）、Long→string 断言需 `Number()`（handoff §22）。
> - v1.21 (2026-08-13)：**F4 WebSocket 实时订阅后端完成**——中间件 `middleware-kafka`（apache/kafka:4.0.0 KRaft）+ Flink lib 增 kafka connector + TaskManager slot 1→2；realtime 事件管道（`cdc_events_flink_job_id` Flyway V1.4.0 + `CdcYamlBuilder.buildEvent` Kafka 单 sink + `start/stop/forceStop` 联动 + `pollEventJobs` 监控 + `cancelJob` + realtime-api `getSubscribeInfo`）；data-service WebSocket（`WsEventsHandler`/`WsHandshakeInterceptor`/`WebSocketSubscriptionRegistry`/`KafkaEventConsumer`/`WsSubscriptionService`）+ 补 Key 绑定管道端点（`pipelineIds`）+ common 9016；2 问 2 答（分层自测+部署 / 机密管道全建+订阅侧拒绝）；分层自测全通过（握手 401/连接、subscribe 9005/9016、Kafka fan-out 归一化事件）+ 测试数据清理（handoff §23）；踩坑：Spring Boot 4 spring-kafka 未自动配置 ListenerContainerFactory 需显式 bean。
> - v1.22 (2026-08-13)：**F4 WebSocket 实时订阅前端 + 连接监控完成**——用户拍板「订阅文档 + 连接监控」（超出 §9 原范围）；后端补连接监控埋点 `SubscriptionMetrics`（今日事件/延迟 P95/推送失败/按 Key 接收统计，内存态跨天重置）+ `KafkaEventConsumer` 埋点 + `GET /subscriptions/{pipelineId}/stats`（registry + 埋点 + api_key_pipeline join api_key 批量无 N+1 + 用户名回填）；前端 CDC 管道详情「实时订阅」页签 `SubscribeTab.tsx`（订阅文档 + 连接监控 4 KPI + 订阅方 Key 表格，RUNNING 5s 轮询）+ Key 表单「绑定管道」多选 + `KpiCard` 提取 shared + nginx WebSocket 升级头；踩坑：`Number(detail.id)` 对 19 位 Long 精度丢失（订阅消息 pipelineId 错误），改字符串持有 + 直接拼 JSON 数字（handoff §24）；端点 200 / Key 绑定管道 / WebSocket 握手链路均验证通过。
> - v1.23 (2026-08-13)：**F4 真实 CDC 端到端 E2E 会话**——三处缺陷定位修复：① realtime 缺 `flink-json` 依赖（事件作业 Kafka sink 序列化启动失败）② data-service topicPattern 消费者不发现新 topic（`METADATA_MAX_AGE_CONFIG=10000`）③ Flink CDC 3.6 debezium-json 不输出 ts_ms（反编译 jar 确认，normalize 退用 `record.timestamp()`）；新增 `f4-seed.ts` + `ws-subscription.spec.ts`（4 用例：握手拒连 1002 / 订阅成功 / 端到端 AC-10 576ms 收事件 / 未绑定 9005）+ `ws`/`@types/ws` devDeps；`tsc` + `playwright test` 全通过；测试数据/产物已清（handoff §25）。
> - v1.24 (2026-08-13)：**F5 数据分级分类后端完成**——governance `SensitivityController`（改级/批量/开白/审计 + 分级列表分页）+ `SensitivityService`（机密降级两步 4012 / 开白仅 INTERNAL 9011 / 审计 action 区分 CHANGE_LEVEL/API_EXEMPT）+ `SensitivityChangeLog` 实体 + Flyway V1.7.0（action/remark）+ common 4011/4012；3 问 3 答（补分级列表 / 批量全有或全无 / 审计加 action）；自测全通过（改级 6 场景 + 开白 + 批量回滚 + 审计 + 分级列表）+ 测试数据清理（handoff §26）。
> - v1.25 (2026-08-13)：**F5 数据分级分类前端 + 联调完成**——2 问 2 答拍板（SQL 树机密表锁标记而非隐藏 / 分级页补后端 DTO 对齐原型）；后端补 `SensitivityTableItemDTO` 加 taskSourceType/createdBy/createdByName/createdAt；前端数据分级分类页（筛选/批量打标/改级下拉/开白/审计弹窗）+ 资产详情敏感度标签与去查询/生成 API 入口（机密禁用）+ SQL 终端/API 向导 URL 参数跳转预填 + SQL 树机密锁标记（点击拦截）；踩坑 3 处：DsButton 无 size、wizard handleSelectTable 声明前使用、生成 API 跳转路径 /wizard 应为 /new（handoff §27）；`npx tsc --noEmit` 无效需 `pnpm build` 校验；联调全通过。
> - v1.26 (2026-08-13)：**F3 运行统计页一屏紧凑布局**（用户反馈「太丑、需一页显示不滚动、去掉返回按钮」）——根容器 `h-full` + 页头去返回按钮/描述行精简 + 图表网格 `grid-cols-6 grid-rows-3 flex-1 min-h-0`（趋势/Top5 宽列 col-span-4，其余窄列 col-span-2，3 行等高填充剩余空间）+ KpiCard/ChartCard 紧凑 padding（p-ds-4→px-3 py-2.5）+ 内容多的区块（健康分布/错误码列表）区块内 overflow-y-auto；实测整页 scrollHeight==clientHeight 无溢出、底部区块 bottom<视口高未裁剪、返回按钮已去掉。
> - v1.27 (2026-08-13)：**F5 数据分级分类完整 E2E 会话**——3 问 3 答拍板（完整闭环 / 复用现有元数据表测后复位 / 浏览器 E2E 为主+API 辅助）；新增 `f5-seed.ts` + `classification.spec.ts`（**14 用例全通过**：分级管理核心 CL-1~7 改级/降级两步/批量/开白/审计弹窗 + 三端闸门联动 CL-8~11 SQL 拦截/树锁/资产详情禁用/向导禁选 + 权限与后端规则 CL-12~14）；**发现并修复真实缺陷**：`SqlTree.tsx` 无 schema 分支（Doris/MySQL 单库）表节点漏映射 `sensitivityLevel` 导致机密锁不生效（补字段 + `pnpm build` 重建部署）；踩坑：查询按钮子串匹配 sidebar / 改级下拉选项冲突 / 权限 envelope code=1005 非 403 / Monaco keyboard.type 丢字符改 window.monaco setValue（handoff §28）。
