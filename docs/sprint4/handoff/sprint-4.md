# Sprint 4 Handoff

> 本文件用于 Sprint 4 内多个 Agent 会话之间的状态同步。每次会话结束时更新状态；新会话进入时先读此文件。
>
> **最后更新**：2026-08-02 — 前端实现完成：全部 Sprint 4 功能已开发并通过 `tsc -b` + `vite build` 构建验证（0 lint
> error），代码 review 后 2 项 major、10 项 minor 修复已落地。未联调、未跑测试。

## Sprint 目标

Sprint 4 在 Sprint 3 DAG 编排基础上，扩展任务类型、提升可复用性、增强可观测性，并为数据治理提供血缘基础：

1. **Python 任务节点**：DAG 画布支持 PYTHON 节点，脚本在隔离进程中执行。
2. **DAG 级参数化**：支持自定义参数与系统变量，SQL/Python 节点通过 `${paramName}` 占位符替换。
3. **监控与邮件告警**：DAG 失败/成功/节点超时时发送邮件通知。
4. **SQL 血缘自动上报**：SQL 节点执行成功后解析 source → target 血缘并上报治理模块。
5. **DAG 版本管理**：保存即生成版本快照，支持对比与回滚。
6. **真正的重跑失败节点**：替换 Sprint 3 MVP 全量重跑，仅重跑 FAILED/SKIPPED 节点。
7. **多表同步 + 速率限流收尾**：后端字段与执行逻辑已就绪，前端 UI 补齐中。

> 详细需求见 [DataNest-Sprint4-PRD.md](../DataNest-Sprint4-PRD.md)
> 与 [DataNest-Sprint4-技术文档.md](../DataNest-Sprint4-技术文档.md)，
> 原型见 [Sprint4-Python参数化监控告警血缘.html](../ui/Sprint4-Python参数化监控告警血缘.html)。

## 范围

| 模块 | 状态   | 说明                                                                                               |
|------|--------|----------------------------------------------------------------------------------------------------|
| 文档 | 进行中 | PRD/技术文档已存在；按 DAG 告警配置已回写到技术文档 8.1/8.2/8.5 节。其他文档由对应负责人继续维护。 |
| 前端 | 已完成 | Sprint 4 全部功能已开发，构建验证通过；review 修复已落地。未联调。                                 |
| 后端 | 已完成 | Phase 1~4 实现 + review 4 项优化均已落地。                                                         |
| 测试 | 未开始 | 用户明确安排后再执行，当前仅保证编译通过。                                                         |

## 关键决策

- **告警配置支持按 DAG 覆盖**（review 新增）：`dag_alert_config` 表新增可空 `dag_id` 列。触发告警时先查该 DAG
  专用配置，无则回退全局默认配置。
- **超时扫描改为按 DAG 配置判断阈值**：`DagNodeTimeoutAlertHandler` 扫描 RUNNING 节点时通过 `execution_id → dag_id`
  关联，再取该 DAG 告警配置（含 `timeout_minutes`）决定是否发送。
- **血缘批量写入**（review 优化）：`SqlLineageExtractor` 收集到批量血缘记录后统一 `insertBatch`，单条时仍走 `insert`。
- **版本 diff 性能优化**（review 优化）：`DagVersionService.generateChangeSummary` 不再 `selectByDagId` 全量加载历史版本，改用已有的
  `selectMaxVersionNo` 直接取上一条快照做 diff。
- **告警邮件内容增强**（review 优化）：节点超时邮件补充 DAG 名、DAG 执行时间、查看链接，与失败/成功告警保持一致。
- **Python 执行位置**：与 SQL/SYNC 一致，通过 DolphinScheduler HTTP 回调到 `data-nest-engineering` 本地执行 `python3`。
- **血缘写入位置**：`engineering-service` 直接写入 `lineage_record`，`governance-service` 只提供查询接口，避免跨服务同步调用阻塞
  DAG 执行。
- **版本快照**：Sprint 4 先全量保存节点/边/参数 JSON，后续 Sprint 再评估 diff 存储。
- **文档/代码/原型一致性**（Sprint 4 收尾）：技术文档 3.1 包结构、`MailService` 位置，PRD 6.3.2/6.3.3 Python 环境说明，
  `DagController.rerunFailed` 注释已按实际实现更新；原型已移除「系统管理 → DAG 告警配置」独立入口，改为在 DAG
  编辑器工具栏显示「告警」按钮，并标注按 DAG 覆盖实现口径。

## 跨会话状态看板

| 会话 | 负责人（Agent/人） | 状态   | 已完成                                    | 待办                 | 备注             |
|------|--------------------|--------|-------------------------------------------|----------------------|------------------|
| 文档 | -                  | 进行中 | PRD/技术文档已创建；按 DAG 告警配置已回写 | 其他模块文档继续维护 | 非阻塞           |
| 前端 | 当前 Agent         | 已完成 | Sprint 4 全部功能 + review 修复           | 前后端联调           | 构建验证通过     |
| 后端 | 后端 Agent         | 已完成 | Phase 1~4 实现 + review 4 项优化          | 等待用户安排测试     | -                |
| 测试 | -                  | 未开始 | -                                         | 接口/集成/回归测试   | 用户安排后再执行 |

## 前端关键决策（2026-08-02，用户确认）

- **告警配置只做按 DAG 覆盖入口**：DAG 画布工具栏「告警」弹窗，不做系统管理全局页（PRD §6.5.2 的全局配置入口本期不实现）。
- **多表同步字段映射按源表逐个配置**：以技术文档 §12.2 为准，不采用 PRD §6.9.3 的"仅首表配置 + 同名自动映射"。
- **验证方式**：仅 `tsc -b` + `vite build` 构建验证，不重建 Docker 前端容器、不联调（用户明确）。

## 当前 Blocker

- **测试待用户安排**：前后端代码均已编译/构建通过，但用户明确要求先不做测试，等待后续安排。不要自行启动测试。
- **前端未联调**：前端按已核实的后端契约开发（见下「前端核实口径」），仅构建验证，需安排前后端联调确认。

## 前端核实口径（与后端契约逐项比对通过）

- 节点实时日志实际路径带 `/dag-executions` 前缀：
  `GET /api/engineering/dag-executions/dev/executions/{executionId}/nodes/{nodeId}/logs`（与技术文档不一致，已按后端实际实现对接）。
- `sourceTablesDetail`：请求体为 JSON 字符串，响应为对象数组；顶层 `targetTable/fieldMapping` 取第一张表。
- 按 DAG 读告警配置：无专属配置时回退全局，响应 `dagId=null` 即全局（弹窗据此显示"继承全局"提示）。
- 版本 diff 项是字符串（nodeId / "a->b" / paramName）；`createdBy` 仅数字 id，前端显示"用户 {id}"。
- Snowflake id 19 位，前端全程保持 string，不转 Number ()。

## 环境/脏数据状态

- TODO：由测试/部署前确认当前环境是否干净、是否有测试数据、是否需要清理。
- 本次修改仅涉及后端代码与 Flyway 迁移脚本，未直接操作生产/测试数据库。

## 变更清单

### 后端新增文件

- `data-nest-system/src/main/resources/db/migration/V3.3.10__dag_alert_config_dag_id.sql` — 按 DAG 告警配置字段
- `data-nest-task-core/.../entity/PythonNodeConfig.java`（如存在）
- `data-nest-task-core/.../service/PythonExecutor.java`（如存在）
- `data-nest-task-core/.../service/SqlLineageExtractor.java`
- `data-nest-task-core/.../entity/DagVersion.java`
- `data-nest-task-core/.../mapper/DagVersionMapper.java`
- `data-nest-task-core/.../entity/DagParameter.java`
- `data-nest-task-core/.../mapper/DagParameterMapper.java`
- `data-nest-task-core/.../entity/DagAlertConfig.java`
- `data-nest-task-core/.../mapper/DagAlertConfigMapper.java`
- `data-nest-task-core/.../entity/DagAlertHistory.java`
- `data-nest-task-core/.../mapper/DagAlertHistoryMapper.java`
- `data-nest-task-core/.../entity/LineageRecord.java`
- `data-nest-task-core/.../mapper/LineageRecordMapper.java`
- `data-nest-engineering/.../controller/PythonCallbackController.java`（如存在）
- `data-nest-engineering/.../controller/DagVersionController.java`
- `data-nest-engineering/.../controller/DagAlertConfigController.java`
- `data-nest-engineering/.../service/DagVersionService.java`
- `data-nest-engineering/.../service/DagParameterService.java`
- `data-nest-engineering/.../service/DagAlertService.java`
- `data-nest-engineering/.../service/DagAlertExecutionListener.java`
- `data-nest-task-core/.../service/MailService.java`
- `data-nest-engineering/.../dto/DagVersionPayload.java`
- `data-nest-engineering/.../dto/DagAlertConfigPayload.java`
- `data-nest-job/.../handler/DagNodeTimeoutAlertHandler.java`
- `data-nest-governance/.../controller/LineageController.java`（如存在）
- `data-nest-governance/.../service/LineageService.java`

### 后端修改文件（重点）

- `data-nest-engineering/.../service/DagVersionService.java` — 版本 diff 性能优化
- `data-nest-task-core/.../service/DagAlertService.java` — 按 DAG 解析告警配置 + 邮件内容增强
- `data-nest-engineering/.../controller/DagAlertConfigController.java` — 新增 `/dev/dags/{dagId}/alert-config` GET/PUT
- `data-nest-engineering/.../dto/DagAlertConfigPayload.java` — 增加 `dagId`
- `data-nest-task-core/.../entity/DagAlertConfig.java` — 增加 `dagId`
- `data-nest-task-core/.../mapper/DagAlertConfigMapper.java` — 新增 `selectByDagId`，调整 `selectGlobal`
- `data-nest-task-core/.../entity/NodeExecution.java` — 增加 transient `dagId`
- `data-nest-task-core/.../mapper/NodeExecutionMapper.java` — 新增 `selectRunningWithDagId`
- `data-nest-job/.../handler/DagNodeTimeoutAlertHandler.java` — 按 DAG 配置判断超时
- `data-nest-task-core/.../service/SqlLineageExtractor.java` — 血缘批量写入
- `data-nest-task-core/.../mapper/LineageRecordMapper.java` — 新增 `insertBatch`
- `data-nest-task-core/.../service/PythonExecutor.java` — `read_doris_table` 返回 pandas DataFrame；`runPython` 通过
  `ulimit -v` 强制虚拟内存限制
- `data-nest-engineering/.../controller/DagController.java` — 重跑失败节点注释改为 Sprint 4 真实实现说明

### 前端新增文件（2026-08-02）

- `data-nest-frontend/src/pages/engineering/dags/components/PythonEditorModal.tsx` — Python 节点编辑器（Monaco python +
  超时/内存配置 + 运行测试 + outputTables 展示）
- `data-nest-frontend/src/pages/engineering/dags/components/DagParameterDrawer.tsx` — DAG 参数抽屉（草稿 diff 提交，删除前
  `${paramName}` 引用校验）
- `data-nest-frontend/src/pages/engineering/dags/components/TriggerParamsModal.tsx` — 手动触发参数覆盖弹窗
- `data-nest-frontend/src/pages/engineering/dags/components/DagVersionModal.tsx` — 版本列表/对比/回滚
- `data-nest-frontend/src/pages/engineering/dags/components/DagAlertConfigModal.tsx` — 按 DAG 告警配置（继承全局提示）
- `data-nest-frontend/src/pages/engineering/dags/components/NodeRuntimeLogPanel.tsx` — 节点实时日志（RUNNING 时 3s 轮询，30
  分钟超时）

### 前端修改文件（2026-08-02）

- `data-nest-frontend/src/pages/engineering/dags/types.ts` — NodeType 加 PYTHON；新增
  DagParameter/PythonExecuteResult/DagVersion/DagVersionDiff/DagAlertConfig/NodeExecutionLog
- `data-nest-frontend/src/pages/engineering/dags/api.ts` — 参数 CRUD、testPythonNode（超时随脚本配置）、版本三接口、按 DAG
  告警配置、节点实时日志（skipErrorMessage）
- `data-nest-frontend/src/pages/engineering/dags/Editor.tsx` — PYTHON
  节点全链路（面板/拖拽/编辑器/序列化/运行视图）；工具栏「参数」「版本」「告警」；触发前查参数弹覆盖弹窗；SQL/PYTHON 节点挂实时日志面板
- `data-nest-frontend/src/pages/engineering/dag-executions/index.tsx` — 重跑确认弹窗列出 FAILED/SKIPPED 节点 + 成功节点复用说明
- `data-nest-frontend/src/pages/engineering/sync-jobs/SyncJobDrawer.tsx` — 重构：多表多选 + 目标表映射 + 按表字段映射 +
  限流区块；列信息按表缓存
- `data-nest-frontend/src/pages/governance/metadata/index.tsx` — 表详情新增「数据来源」卡片（来源类型/DAG/节点 + 跳转按钮）
- `data-nest-frontend/src/types/sync.ts` — SourceTableDetail + 限流三字段
- `data-nest-frontend/src/types/metadata.ts` — MetadataTable 来源六字段
- `data-nest-frontend/src/lib/monacoSetup.ts` — 注册 python 语言支持

### 前端 review 修复（2026-08-02）

- major：节点日志轮询加 `skipErrorMessage`（避免 RUNNING 期间错误弹窗轰炸）；SyncJobDrawer 列信息加载合并为单 effect Set
  去重（避免单表模式重复拉取）
- minor：monacoSetup 注册 python；Python 测试 axios 超时随 timeoutMinutes；运行测试叠加"无未保存变更"校验；删除
  runViewPosCacheRef 死代码；系统变量记法统一 `${biz_date}`；参数抽屉 footer 去 useMemo；重跑空列表兜底；多表任务编辑降级兜底（editIsMulti
  时强制带 sourceTablesDetail）；限流校验改 >0；告警未勾 TIMEOUT 不提交 timeoutMinutes；PythonEditorModal 复用
  selectedNode；结果区补 outputTables

## 验证证据

- 编译命令：
  ```bash
  cd data-nest
  mvn -q -DskipTests compile
  ```
- 结果： **成功**（无编译错误）。
- 前端验证（2026-08-02）：
  ```bash
  cd data-nest/data-nest-frontend
  npx tsc -b && npx vite build
  ```
- 结果： **成功**；eslint 0 error（dag-executions 有 1 个 Sprint 4 之前就存在的 useMemo 依赖 warning，未动）。
- 未执行单元测试、接口测试与集成测试。

## Next Action

1. 等待用户安排测试计划与前后端联调。
2. 测试阶段建议优先覆盖：
    - DAG 版本保存/对比/回滚（验证 diff 性能优化后逻辑正确）。
    - 按 DAG 告警配置：专用配置覆盖全局、未配置时回退全局、超时阈值按 DAG 生效。
    - 告警邮件内容：失败/超时/成功邮件均包含 DAG 名、时间、查看链接。
    - SQL/Python 血缘批量写入：多语句/多节点场景下记录完整。
3. 前后端联调重点：
    - Python 节点运行测试（注意：节点需先保存 DAG 才可测试，前端已禁用未保存场景）。
    - 多表同步创建/编辑回显（sourceTablesDetail 字符串 vs 数组口径）。
    - 节点实时日志轮询（路径带 /dag-executions 前缀）。
    - 触发参数覆盖、版本回滚后画布刷新。

## 参考链接

- [项目级 Agent 约定](../../AGENTS.md)
- [Sprint 4 PRD](../DataNest-Sprint4-PRD.md)
- [Sprint 4 技术文档](../DataNest-Sprint4-技术文档.md)
- [Sprint 4 原型（Python/参数化/监控告警/血缘）](../ui/Sprint4-Python参数化监控告警血缘.html)
