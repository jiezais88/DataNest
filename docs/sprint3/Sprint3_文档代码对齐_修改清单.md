# Sprint 3 文档/原型向代码对齐 — 修改清单与待确认问题

> 任务范围：仅修改 `docs/sprint3/` 下的 PRD、技术文档、实施计划与原型 HTML；未改动任何代码文件，未创建/删除 `docs` 以外的文件。

---

## 一、修改文件总览

| 文件                                                          | 说明                                                                           |
|---------------------------------------------------------------|--------------------------------------------------------------------------------|
| `docs/sprint3/DataNest-Sprint3-DAG编排与SQL任务编辑器-PRD.md` | 对齐菜单分组、路由、DAG 列表、快捷键、数据模型                                 |
| `docs/sprint3/DataNest-Sprint3-技术文档.md`                   | 对齐 Controller/Service 路由、API 表、Flyway 清单、前端路由/菜单、同步回调机制 |
| `docs/sprint3/DataNest-Sprint3-实施计划.md`                   | 对齐决策表、模块架构图、Flyway 脚本清单、前端文件清单、ADR                     |
| `docs/sprint3/ui/Sprint3-DAG编排与SQL任务编辑器.html`         | 修正菜单分组、路由批注、DAG 列表批注、页面标题                                 |
| `docs/sprint3/Sprint3_文档代码对齐_修改清单.md`               | 本报告                                                                         |

---

## 二、PRD 修改点

### 1. §6.2 项目管理 — 项目列表页

- **新增待确认批注**（约 L173）：当前项目列表页顶部标题代码实现为「数据开发」，菜单项为「项目管理」，二者语义不一致，需 UX 侧确认统一。
- **新增长度限制批注**（约 L234）：前端校验项目名 3-30 位，数据库 `dag_project.name` 为 `VARCHAR(100)`，需产品侧确认是否放宽。

### 2. §6.3 项目下的 DAG 列表

- 已在前一版调整：列改为与代码一致（DAG 名称、触发方式、Cron、调度状态、创建人/时间、修改人/时间、最近执行状态、最近执行、操作），并说明后端一次性返回、前端假分页。

### 3. §6.4.3 中间画布 — 键盘快捷键

- **Ctrl+Z / Ctrl+Y 说明更新**（约 L414-L415）：由「当前未实现」改为「画布全局未实现；SQL 编辑器弹窗内 Monaco 已实现撤销/重做按钮」。

### 4. §13 附录：DAG 数据模型概念

- **SYNC 节点 `syncJobId` 类型修正**（约 L1007）：由 `string` 改为 `number`，并注明实际代码序列化为 `Long/number`。

---

## 三、技术文档修改点（摘要）

### 1. §7 Project/DAG Controller

- Project 列表接口由 `POST /page` 改为 `GET /dev/dag-projects`（query params）。
- DAG 列表接口改为 `GET /dev/dags`；新增 `/schedule/start`、`/schedule/stop`、Python 测试、节点日志接口。

### 2. §7.5 DagService

- 增加与代码对齐的注释，说明使用 `DagPayload`、`syncToDs` 私有方法、调度开关独立。

### 3. §7.7 同步任务触发

- 删除旧的 `/internal/sync/{historyId}/status` 轮询描述。
- 改为 `/dev/internal/sync/callback` + `DagExecutionSyncService` 反查历史表 + `SyncNodeMutexService`。

### 4. §9 Flyway 脚本清单

- 更新为仓库实际存在的 V3.2.0 ~ V3.4.1 系列脚本。

### 5. §10 API 表

- Project、DAG、Execution、SQL Preview、参数/版本/告警、内部回调均按实际 Controller 更新。

### 6. §12.2 前端路由

- 更新为 `/engineering/dags`、`:projectId`、`new`、`/:id/edit`、`/:id/executions/:executionId`。

### 7. §12.3 菜单

- 「项目管理」从「数据工程」分组拆出，改为独立的「数据开发」分组；DAG 执行历史仍归「执行历史」分组。

---

## 四、实施计划修改点

### 1. §0 最终决策表

- **决策 4 前端路由前缀**（L19）：更新为实际路由 `/engineering/dags`、`/engineering/dags/:projectId`、
  `/engineering/dags/new`、`/engineering/dags/:id/edit`、`/engineering/dags/:id/executions/:executionId`、
  `/engineering/dag-executions`。

### 2. §2.2 关键现状

- **Flyway 最新版本**（L74）：由 `V3.1.3` 改为 `V3.4.1`，并注明仓库已存在 V3.2.0 ~ V3.4.1。

### 3. §4.1 服务依赖图

- 前端页面改为 `index.tsx` / `project.tsx` / `Editor.tsx` / `dag-executions/index.tsx`。
- Controller 名称改为 `DagProjectController`、`DagController`、`SqlPreviewController`、`DagParameterController`、
  `DagVersionController`、`DagAlertConfigController`、`DagExecutionController`。
- 内部回调改为 `DagNodeCallbackController`（`/dev/internal/{sql,sync,unknown}/callback`）与 `PythonCallbackController`。
- Service 层同步为实际类名（`DagProjectService`、`SqlPreviewService`、`DagParameterService`、`DagVersionService`、
  `DagAlertConfigService`、`SyncJobService` 等）。

### 4. §4.3 DS 回调网络拓扑

- 回调 URL 增加 `/dev/internal/unknown/callback`。

### 5. §5.3 Flyway 迁移

- 完整替换为仓库实际脚本 V3.2.0 ~ V3.4.1，并补充每份脚本的内容摘要。

### 6. §5.5 前端文件清单

- 由旧的 `projects/` / `dags/` / `canvas/` / `running/` / `dag-executions/` 结构改为实际文件：
    - `pages/engineering/dags/index.tsx`
    - `pages/engineering/dags/project.tsx`
    - `pages/engineering/dags/Editor.tsx`
    - `pages/engineering/dags/components/SqlEditorModal.tsx`、`PythonEditorModal.tsx`、`DagParameterDrawer.tsx`、
      `DagVersionModal.tsx`、`DagAlertConfigModal.tsx`、`NodeRuntimeLogPanel.tsx`、`TriggerParamsModal.tsx`
    - `pages/engineering/dag-executions/index.tsx`
    - `pages/engineering/dags/api.ts`、`types.ts`
    - `router/index.tsx`、`components/Sidebar.tsx`
- Sidebar 分组改为「数据开发」下「项目管理」。

### 7. §6.6 DS 回调内部接口

- 回调路径增加 `/dev/internal/unknown/callback`。

### 8. §9 ADR

- **ADR-S3-010**（L687）：更新为完整实际路由。
- **ADR-S3-011**（L692）：扩展为菜单分组决策，明确「项目管理」归「数据开发」分组、「DAG 执行历史」归「执行历史」分组。

---

## 五、原型 HTML 修改点

### 1. 顶部批注（约 L1396-L1404）

- 修正菜单分组说明：「项目管理」从「数据工程」分组拆出，改为独立的「数据开发」分组。
- 修正前端路由说明为实际路由。
- 增加项目列表页标题「数据开发」与菜单「项目管理」语义不一致的待确认批注。
- 增加 DAG 列表实际列与原型不同的批注。

### 2. 侧边栏菜单分组

- **项目列表视图**（约 L1412-L1423）、 **DAG 列表视图**（约 L1514-L1525）：新增独立的「数据开发」分组，将「项目管理」从「数据工程」下移出。
- **执行历史视图**（约 L1796-L1807）：同样将「项目管理」移到「数据开发」分组。

### 3. 页面标题与面包屑

- 项目列表页标题由「项目管理」改为「数据开发」（约 L1440）。
- DAG 列表页面包屑由「项目管理 / 项目名」改为「数据开发 / 项目名」（约 L1541）。
- 执行历史页面包屑由「项目管理 / 执行历史」改为「数据开发 / 执行历史」（约 L1824）。
- `backToProjects()` 后 document.title 由「项目管理」改为「数据开发」（约 L2299）。

### 4. DAG 列表批注

- 路由批注改为 `/engineering/dags/:projectId`（约 L1549）。
- 增加说明：实际列含 DAG 名称、触发方式、Cron、调度状态、创建人/时间、修改人/时间、最近执行状态、最近执行、操作。

### 5. 画布/执行历史路由批注

- 画布批注改为编辑路由 `/engineering/dags/:id/edit`、运行视图路由 `/engineering/dags/:id/executions/:executionId`（约
  L1680）。
- 执行历史「详情」按钮提示与批注改为 `/engineering/dags/:id/executions/:executionId`（约 L1860、L1884、L1936、L1992）。

---

## 六、待确认问题

| #  | 问题                              | 位置                                                                                                                                                                     | 建议                                                                                                                                                     |
|----|-----------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------|
| Q1 | **菜单分组 / 页面标题语义不一致** | Sidebar 将「项目管理」放在「数据开发」分组；但项目列表页顶部标题为「数据开发」                                                                                           | 产品/UX 确认统一：菜单项改为「数据开发」或页面标题改为「项目管理」                                                                                       |
| Q2 | **项目名长度限制不一致**          | 前端校验 3-30 位；数据库 `dag_project.name` 为 `VARCHAR(100)`                                                                                                            | 确认是否放宽前端限制或收紧数据库字段                                                                                                                     |
| Q3 | **`syncJobId` 类型**              | 代码序列化为 `number/Long`；PRD 已按代码改为 `number`                                                                                                                    | 若产品侧原意是 string，需后端/前端统一调整                                                                                                               |
| Q4 | **节点日志路径结构怪异**          | `DagExecutionController` 类级映射 `/dag-executions`，方法却映射 `/dev/executions/{executionId}/nodes/{nodeId}/logs`，导致实际路径为 `/dag-executions/dev/executions/...` | 后端统一节点日志入口：建议统一到 `/dev/dags/node-executions/{nodeExecutionId}/logs` 或 `/dag-executions/{executionId}/nodes/{nodeId}/logs`，避免混合前缀 |
| Q5 | **DAG 列表列与原型差异**          | 已按代码更新文档/原型批注                                                                                                                                                | 产品确认是否接受当前列布局                                                                                                                               |

---

## 七、验证说明

- 本次改动均为文档/原型批注，未修改代码，未运行编译/测试。
- 对齐依据来自实际代码读取：
    - 后端：`DagProjectController`、`DagController`、`DagExecutionController`、`DagNodeCallbackController`、
      `PythonCallbackController`、`SqlPreviewController`、`DagParameterController`、`DagVersionController`、
      `DagAlertConfigController` 及相关 Service。
    - 前端：`data-nest-frontend/src/pages/engineering/dags/index.tsx`、`project.tsx`、`Editor.tsx`、`api.ts`、
      `components/Sidebar.tsx`、`router/index.tsx`。
    - 数据库：`data-nest-system/src/main/resources/db/migration/V3.2.0__dag_tables.sql` 及 V3.2.0 ~ V3.4.1 系列脚本。
