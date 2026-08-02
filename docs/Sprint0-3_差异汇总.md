# Sprint 0-3 文档/原型与代码差异汇总

> 生成时间：2026-08-02
> 对齐原则：以当前代码实现为准，文档/原型向代码靠拢；静态 HTML 原型若改动较大则加批注说明。
> 来源：4 个只读子代理分别分析 Sprint 0/1/2/3 的 PRD、技术文档、原型与前后端代码后汇总。

## 影响级别说明

- **P0**：会导致测试/联调歧义，或验收标准与实现冲突。
- **P1**：描述不准确、字段/交互有差异，需要修正。
- **P2**：文案、Logo、列表列、按钮样式等次要差异。

---

## Sprint 0：用户与权限管理

### P0

1. **用户列表未限制管理员权限**
    - 文档：`技术文档.md` §6.1 称 `GET /users` 仅管理员可查询。
    - 代码：`UserController.list` 只加 `@SaCheckLogin`，任何登录用户均可查看全部用户。
    - 处理：文档按代码实际改为「当前任何登录用户均可查看」；如要改代码需单独排期。

2. **禁用用户不会立即踢出会话**
    - 文档：PRD §5.2.4 称禁用后当前会话立即失效。
    - 代码：`toggleStatus` 只改 `enabled`，未调用 Sa-Token 踢出；Token 有效期内仍可访问。
    - 处理：文档改为「Token 自然过期前仍有效」。

3. **创建/编辑用户缺少「确认密码」**
    - 文档：PRD §5.2.2 要求两次输入一致。
    - 代码：`UserModal` 只有单个密码框。
    - 处理：文档删除确认密码要求。

4. **用户名字段缺少正则校验**
    - 文档：PRD 要求 3-30 位字母数字下划线。
    - 代码：仅 `@Size(min=3, max=30)`，无正则。
    - 处理：文档改为「长度 3-30 位，当前仅后端做长度校验」。

5. **最近登录时间未实现**
    - 文档：PRD §5.2.5 要求展示最近登录时间。
    - 代码：`sys_user` 无 `last_login`。
    - 处理：文档删除该字段描述。

### P1

- 登录入口实际在 `data-nest-system` 的 `AuthController`，技术文档误写在 Gateway。
- 切换状态 API 实际为 `PUT /users/{userId}/toggle`，文档写为 `/users/{id}/status`。
- 预置角色权限矩阵与当前 Sidebar 菜单不匹配；当前菜单为「数据工程、数据开发、数据治理、执行历史、系统管理」。
- 数据库字段：实际 `password`（非 `password_hash`），关联表有自增 id 主键（非复合主键），`sys_permission` 无 `resource/action`。
- Token `active-timeout` 实际 30 分钟（文档写 2 小时）。
- 技术文档称 Sprint 0 暂缓 Redis，实际已用 Redis 做 Sa-Token 会话存储。
- Gateway `JwtAuthFilter` 职责描述错误：实际由 `SaReactorFilter` 统一鉴权。

### P2

- 登录页副标题、Logo、用户列表列（含审计列）、操作按钮样式、搜索占位文案等原型/PRD 与代码不一致。

### Sprint 0 对齐结果（2026-08-02）

已按当前代码完成 Sprint 0 的 PRD、技术文档、原型 HTML 对齐，未修改任何代码：

- P0 安全项：代码已修复 `UserController.list` 限制 `SUPER_ADMIN`、`toggleStatus` 禁用后调用 `StpUtil.logout`、
  `UserCreateRequest` 已加 `@Pattern`；PRD/技术文档/原型已同步为「立即踢会话」「3-30 位字母数字下划线」等描述。
- P1 项：技术文档已修正 Gateway 路由（`StripPrefix=1`、`system` context-path）、`AuthController` 与 `UserController` 路径、
  `SaReactorFilter`/`JwtAuthFilter` 职责、数据库 schema/种子数据、动态菜单结构。
- P2 项：PRD 与原型已对齐登录页副标题/Logo、列表列（增加创建人/修改人/修改时间）、行操作含重置密码、搜索占位文案、左侧菜单分组。

> 具体修改见各文件末尾「修订记录」，以及原型 HTML 顶部批注。

---

## Sprint 1：数据源连接与元数据采集

### P0

1. **数据源列表接口方式错误**
    - 技术文档：`GET /api/engineering/datasources`
    - 代码：`POST /api/engineering/datasources/page`

2. **采集任务列表接口方式错误**
    - 技术文档：`GET /api/governance/collect-tasks`
    - 代码：`POST /api/governance/collect-tasks/page`

3. **元数据管理 API 路径完全错误**
    - 技术文档写为 `/api/governance/metadata/{dsId}/schemas` 等三级路径。
    - 代码实际为 `/api/governance/metadata/datasources/{id}/databases/{db}/schemas/{schema}/tables` 等四级路径，并含
      `/search-tree`、`/builtin-doris/**`。

4. **连接测试强制阻塞保存**
    - PRD：必须测试成功后才可保存。
    - 代码：测试与保存独立，测试失败仍可保存。

5. **元数据树层级描述错误**
    - PRD：「数据源 → 库/Schema → 表」三级。
    - 代码：MySQL/Doris 三级，PG/Oracle/SQL Server 四级（Database → Schema → Table）。

6. **删除数据源行为冲突**
    - PRD AC-16：删除后已采集元数据保留并标记 offline。
    - 代码：级联删除 `metadata_table`/`metadata_column`。

### P1

- 数据源类型实际支持 MySQL/PostgreSQL/Doris/Oracle/SQL Server（PRD 只列前三者）。
- 采集任务字段名：代码用 `scope`（JSON），文档写 `targetSchemas`。
- 采集模式代码枚举为 `FULL / FULL_INCREMENT`，文档写 `FULL / INCREMENTAL`。
- Cron 任务创建后默认不自动调度，需额外调用 `/schedule/start`；文档未说明调度启停。
- 任务名称实际可编辑，文档写不可编辑。
- 任务状态实际含 `NEVER_EXECUTED / RUNNING / SUCCESS / FAILED / TERMINATED`，文档为「未执行/运行中/正常/失败」。
- 元数据「人工注释」与「源库注释」分离，文档未区分。
- 权限矩阵：历史记录接口允许 `DATA_ANALYST`，与 PRD 冲突。
- 数据库字段长度：数据源名 `VARCHAR(100)`、描述 `VARCHAR(500)`，与 PRD 长度不一致。
- `collect_task.status` DB 默认 `NORMAL`，代码枚举为 `NEVER_EXECUTED/RUNNING/SUCCESS/FAILED/TERMINATED`。
- 文档未包含 `schedule_enabled`、`collect_change_detail`、`metadata_column.remark` 等实际字段。
- PRD 写 Sprint 1 不做「保存后自动采集」，代码已实现 `autoCollectOnSave`。

### P2

- 菜单/页面标题为「数据源管理」而非「数据源」。
- 数据源列表列、操作、脱敏占位符与原型不一致。
- 采集任务抽屉内无「立即执行」按钮，在列表页执行。
- 元数据管理左侧树实际为右侧分步浏览。

---

## Sprint 2：批量数据同步与数据标准

### P0

1. **命名冲突：`sync-tasks` vs `sync-jobs`**
    - PRD/技术文档/原型：菜单「批量数据同步」、API `/sync-tasks`、表 `sync_task/history/log`。
    - 代码：菜单「批量数据同步任务」、API `/sync-jobs`、表 `sync_job/history/log`、路由 `/engineering/sync-jobs`。

2. **数据标准 API 路径错误**
    - 文档：`/api/governance/standards/...`
    - 代码：`/api/governance/data-standards/naming-standards`、`/field-type-standards`、`/compliance-check`

3. **数据标准字段模型不符**
    - 命名规范代码含 `targetStandardId`、`priority`、`enabled`；字段类型标准代码含 `category`、`allowedTypes`。
    - 文档描述为不同字段。

4. **多表同步已实现**
    - PRD 非目标 NG4 写 Sprint 2 不做多表；代码已实现 `sourceTables` 数组、`sourceTablesDetail` JSONB、多表字段映射。

5. **Addax 部署架构错误**
    - 文档：engineering-service 内嵌 Addax。
    - 代码：Addax 在 `data-nest-worker` 中执行。

6. **XXL-JOB Executor 归属错误**
    - 文档：engineering-service 作为 Executor，handler `syncTaskHandler`。
    - 代码：`data-nest-worker` 作为 Executor，handler `syncJobHandler`。

7. **Addax Doris writer 配置错误**
    - 文档：同时配置 `feLoadUrl` 和 `beLoadUrl`。
    - 代码：只生成 `loadUrl`。

8. **字段映射字段名不符**
    - 文档：`source`/`target`
    - 代码：`sourceColumn`/`targetColumn`/`targetType`

### P1

- 同步任务列表/历史字段与原型差异大；状态含 `TERMINATED`；重试模型基于历史表 `parent_history_id`。
- 手动停止运行中任务功能存在但文档缺失。
- 速率限流字段已存在（`rate_limit_enabled`、`read_rate_limit_mbps`、`write_rate_limit_rows_per_second`），文档仍写 Sprint 3
  做。
- 合规检查不支持「全部数据源」一键选项；结果展示字段为「问题描述/涉及规范/整改建议」。
- Flyway 脚本清单与文档对不上（含 V3.0.x 系列多个脚本）。
- `sync_job` 拆分为 `status`（NORMAL/PAUSED）和 `execution_status`（PENDING/RUNNING/SUCCESS/FAILED/TERMINATED）。
- 描述字段限制 1000 字符，PRD 写 200。

### P2

- 菜单文案、列表列、按钮位置、Addax 镜像 registry（`quay.io`）、worker 端口未在文档中体现。

---

## Sprint 3：DAG 编排与 SQL 任务编辑器

### P0

1. **菜单/路由名称错误**
    - 文档/原型：「数据开发」菜单，路由 `/data-dev/*`。
    - 代码：「项目管理」菜单，路由 `/engineering/dags/*`、`/engineering/dag-executions`。

2. **API 路径前缀错误**
    - 文档写 `/engineering/dev/...`。
    - 代码 Controller 前缀为 `/dev/dag-projects`、`/dev/dags`、`/dag-executions`（gateway 已做 StripPrefix）。

3. **执行/停止/重跑接口路径错误**
    - 文档：`/dags/{id}/execute`、`/dags/{id}/terminate`、MVP 全量重跑。
    - 代码：`/dags/{id}/trigger`、`/dags/{id}/executions/{executionId}/stop`、
      `/dags/{id}/executions/{executionId}/rerun-failed`（真正重跑失败节点）。

4. **内部回调路径错误**
    - 文档：`/engineering/dev/internal/sql/execute`、`/sync/trigger`、`/sync/{historyId}/status`。
    - 代码：`/dev/internal/sql/callback`、`/sync/callback`、`/python/callback`；SYNC 状态由 `DagExecutionSyncService`
      反查历史表，无轮询接口。

5. **全局执行历史接口错误**
    - 文档：`POST /engineering/dev/executions/page`。
    - 代码：`GET /dag-executions`（query params）。

### P1

- SQL 编辑器无「格式」按钮；运行测试 API 为 `/dev/sql-preview`。
- 参数化占位符 `${param}`、参数抽屉、触发覆盖弹窗已提前实现（PRD 写 Sprint 4）。
- 实时日志：运行视图已支持 SYNC 日志与 SQL/Python 节点日志轮询（PRD 写 Sprint 5）。
- 执行历史无展开行微缩 DAG，点击详情跳转运行视图。
- 数据库字段长度：`dag_project.name`、`dag.name`、`dag_node.node_name` 实际 `VARCHAR(100)`，文档写 30/50。
- `dag_node.config` 实际为 `TEXT` 存 JSON 字符串，由 fastjson2 解析；文档示例用 `JacksonTypeHandler`。
- 迁移脚本清单落后（含 Python 节点、参数、版本、告警、血缘等 Sprint 3/4 脚本）。
- DS 任务名称与 task code 生成策略与文档不符。
- 工具栏已含「参数」「版本」「告警」「自动布局」；快捷键未实现 `Ctrl+Z/Y`。
- PRD「非目标」中多项（Python、参数化、版本、告警、血缘）已实际落地。

### P2

- 原型项目/DAG 列表列、SQL 编辑器结果区形态与代码不一致。

---

## 待用户决策事项

以下问题在「文档向代码对齐」时存在两种合理处理方式，请确认：

1. **Sprint 0 用户列表未限制 SUPER_ADMIN**：这是安全 bug。本次是只改文档描述（任何登录用户可见），还是顺手给
   `UserController.list` 加 `@SaCheckRole("SUPER_ADMIN")`？
2. **Sprint 0 禁用用户不踢会话**：只改文档，还是补代码调用 `StpUtil.logout()`？
3. **Sprint 0 用户名字段无正则**：只改文档，还是后端补 `@Pattern`？
4. **Sprint 1 删除数据源级联清理元数据**：PRD 原意是保留并标记 offline。本次只改文档为「级联清理」，还是改代码保留元数据？
5. **Sprint 1 保存数据源后自动采集**：PRD 写不做，代码已实现。只改文档纳入该功能，还是代码移除？
6. **Sprint 2 `sync-tasks` vs `sync-jobs`**：文档全面改为 `sync-jobs`（以代码为准），还是改代码回 `sync-tasks`？
7. **Sprint 3 PRD「非目标」中已落地的 Sprint 4 能力**：是把这些项从 Sprint 3 非目标中删除并补充说明，还是保持 Sprint 3
   文档不变、仅加脚注？

默认做法（以代码为准）：对上述问题均按代码实际修改文档，不修改代码；但 P0 安全项会特别标注。
