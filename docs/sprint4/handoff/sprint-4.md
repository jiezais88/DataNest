# Sprint 4 Handoff

> 本文件用于 Sprint 4 内多个 Agent 会话之间的状态同步。每次会话结束时更新状态；新会话进入时先读此文件。
>
> **最后更新**：2026-08-01 — 后端实现完成，review 后 4 项优化已落地，仅做编译验证，未跑测试。

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
> 与 [DataNest-Sprint4-技术文档.md](../DataNest-Sprint4-技术文档.md)。

## 范围

| 模块 | 状态   | 说明                                                                                               |
|------|--------|----------------------------------------------------------------------------------------------------|
| 文档 | 进行中 | PRD/技术文档已存在；按 DAG 告警配置已回写到技术文档 8.1/8.2/8.5 节。其他文档由对应负责人继续维护。 |
| 前端 | 进行中 | 由其他 Agent 负责改造，不在本会话处理。                                                            |
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

## 跨会话状态看板

| 会话 | 负责人（Agent/人） | 状态   | 已完成                                    | 待办                                            | 备注             |
|------|--------------------|--------|-------------------------------------------|-------------------------------------------------|------------------|
| 文档 | -                  | 进行中 | PRD/技术文档已创建；按 DAG 告警配置已回写 | 其他模块文档继续维护                            | 非阻塞           |
| 前端 | 其他 Agent         | 进行中 | -                                         | Python 编辑器、参数抽屉、版本弹窗、多表同步表单 | 不在本会话处理   |
| 后端 | 当前 Agent         | 已完成 | Phase 1~4 实现 + review 4 项优化          | 等待用户安排测试                                | -                |
| 测试 | -                  | 未开始 | -                                         | 接口/集成/回归测试                              | 用户安排后再执行 |

## 当前 Blocker

- **测试待用户安排**：后端代码已编译通过，但用户明确要求先不做测试，等待后续安排。不要自行启动测试。
- **前端进度未知**：前端由其他 Agent 负责，后端接口已就绪，需前端确认联调时间。

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
- `data-nest-engineering/.../service/MailService.java`（如存在）
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

## 验证证据

- 编译命令：
  ```bash
  cd data-nest
  mvn -q -DskipTests compile
  ```
- 结果： **成功**（无编译错误）。
- 未执行单元测试、接口测试与集成测试。

## Next Action

1. 等待用户安排测试计划。
2. 测试阶段建议优先覆盖：
    - DAG 版本保存/对比/回滚（验证 diff 性能优化后逻辑正确）。
    - 按 DAG 告警配置：专用配置覆盖全局、未配置时回退全局、超时阈值按 DAG 生效。
    - 告警邮件内容：失败/超时/成功邮件均包含 DAG 名、时间、查看链接。
    - SQL/Python 血缘批量写入：多语句/多节点场景下记录完整。
3. 前端联调：确认前端已对接 `/dev/dags/{dagId}/alert-config`、`/dev/dags/{dagId}/versions`、参数覆盖弹窗等接口。

## 参考链接

- [项目级 Agent 约定](../../AGENTS.md)
- [Sprint 4 PRD](../DataNest-Sprint4-PRD.md)
- [Sprint 4 技术文档](../DataNest-Sprint4-技术文档.md)
