# Sprint 11：平台安全与调度治理 + 平台体验——技术设计文档

> **版本**：v1.0 | **日期**：2026-08-14 | **作者**：产品通
>
> 对应 PRD：`DataNest-Sprint11-PRD.md`（v1.4）。本文档记录技术决策、领域/数据模型、接口与实现清单，实现与验收以本文档为基准。

---

## 0. 技术目标与范围

Sprint 11 交付 6 大功能块 + 1 项技术验证，横跨 system / engineering / governance / data-service / job 五个服务：

| 功能 | 主要服务 | 核心改造 |
|------|---------|---------|
| F1 审计日志 | system（存储）+ engineering/governance/data-service（埋点） | 通用审计表 + common AOP 注解 + 跨服务写入 |
| F2 RBAC 按钮级权限 | system（权限点/角色/数据权限）+ 全服务（写接口校验） | 激活权限点体系 + 自定义角色 CRUD + 三级数据权限 |
| F3 任务资源队列 | engineering + job | 队列表 + 排队状态机 + 定时调度 |
| F5 首页数据面板 | system/governance/engineering/data-service（聚合端点）+ 前端 | 5 区块聚合端点 |
| F6 个人中心 | system + 前端 | `GET/PUT /auth/profile` + 前端右侧抽屉（资料查看/编辑/改密） |
| Nacos 热更新验证 | 全服务 | 技术验证，不出 UI |

**关键现状**（已代码核验）：
- `sys_permission` / `sys_role_permission` 表 Sprint 0 已建但**是空表**，4 预置角色靠代码硬编码 `@SaCheckRole("SUPER_ADMIN")` 等 + 前端 `roles.ts` 角色数组，权限点体系未激活。
- 现有 `SchedulerClient`（PowerJob OpenAPI 客户端）只传 name/cron/timeout/retry，**无队列/优先级/并发上限字段**，F3 需扩展 + 应用层实现排队。
- 现有权限注解模式统一：读 = 角色并集（`{SUPER_ADMIN, DATA_ENGINEER, GOVERNANCE_ADMIN, DATA_ANALYST}` 全角色 或 去分析师），写 = 超管+域管理员（工程写 `{SUPER_ADMIN, DATA_ENGINEER}`、治理写 `{SUPER_ADMIN, GOVERNANCE_ADMIN}`、告警写 `{SUPER_ADMIN, DATA_ENGINEER}`）。

---

## 1. 关键技术决策记录（ADR）

### D-1：按钮级权限 = 激活权限点体系（写接口全校验 + 读接口混合策略）

- **权限点 code 规范**：`模块:动作`（如 `datasource:view` / `datasource:create` / `sync:execute`）。全部权限点清单见 §6。
- **存储**：复用 `sys_permission`（code/name/description），经 Flyway 种子脚本写入全部按钮级权限点；`sys_role_permission` 写预置 4 角色的权限点关联。
- **后端校验——写接口**：写接口（增删改/启停/改级/发布/触发）加 `@SaCheckPermission("模块:动作")`。
- **后端校验——读接口（混合策略，用户拍板）**：
  - **有角色区分语义的读接口**（数据源/告警/数据预览/同步任务等「分析师不可见」的）→ 从 `@SaCheckRole` 迁 `@SaCheckPermission("模块:view")`；
  - **无区分语义的读接口**（元数据/资产/SQL 终端/执行历史/质量结果等「全角色可见」的）→ 从 `@SaCheckRole` 改 `@SaCheckLogin`（登录即可，数据范围交数据权限层）。
  - **目的**：自定义角色用户不在预设角色码内，若读接口保留 `@SaCheckRole` 会被 403 挡住；混合策略在改造面与安全间取平衡。
- **前端显隐**：登录/用户信息接口返回当前用户的权限点集合（`permissions: string[]`），前端据此动态渲染菜单 + 页面内按钮（无权限按钮不渲染）。
- **预置角色迁移**：4 预置角色从「角色码硬编码」迁移到「角色-权限点关联」；写接口 `@SaCheckRole`→`@SaCheckPermission`，读接口按上一条混合策略处理。
- **自定义角色 code**：管理员创建时填写可读英文 code（如 `VENDOR_READONLY`，唯一约束已有 `uk_sys_role_code`），与预置角色码风格一致，前端判断/日志可读。

### D-2：数据权限 = 三级白名单 + 默认全量 + Redis 缓存

- **新表 `sys_data_permission`**（datanest_system）：`role_id` + `datasource_id` + `database_name`（可空=库级通配）+ `table_name`（可空=表级通配）。
- **语义**：角色无任何记录 = 全量可见（默认，向后兼容）；有记录 = 白名单过滤，最细粒度优先匹配。
- **校验下沉**：system 提供 internal 端点返回「当前用户角色合并后的数据权限范围」，engineering（同步数据源选择）/ governance（资产目录、元数据树）/ data-service（SQL 终端、API 选表）经 system-api Feign 消费后本地过滤。
- **缓存**：Redis 缓存数据权限（key=`data:perm:role:{roleId}`），保存时主动失效；无权限角色直接判「全量」短路。
- **机密表**：数据权限白名单**不覆盖机密锁**——机密表永远走 Sprint 10 机密锁（默认隐藏+查询拒绝），与数据权限正交。

### D-3：审计日志 = common AOP 注解 + system 集中存储 + fail-open 异步写入

- **审计表 `audit_log`**（datanest_system），字段见 §3。
- **埋点**：common 提供 `@AuditLog(resourceType, opType)` 注解 + `AuditLogAspect`（切面收集操作人/资源/结果/IP），组装 `AuditLogEvent` 后经 `AuditLogRecorder` 接口异步落库。
- **跨服务写入**：system-api 新增 `SystemAuditApi` Feign 契约（internal 写入端点）；engineering/governance/data-service 各自提供 `AuditLogRecorder` Bean（内部调 SystemAuditApi）；system 服务自己直接写库。
- **fail-open**：审计写入失败只记 warn 日志，**不阻断业务**（审计是旁路能力）。
- **与分级审计关系（D12）**：分级变更（改级）同时写通用 `audit_log`（超管视角）+ 既有 `sensitivity_change_log`（治理域专项视图，不动）。表结构不合并。
- **清理**：90 天保留，job 新增 `AuditLogCleanupHandler`（对齐 data-service sql-history 清理模式，业务逻辑下沉 system `/internal/audit/cleanup`）。

### D-4：执行队列 = 应用层完整队列（排队状态机 + 定时调度）

PowerJob 无原生队列/优先级，采用应用层实现：

- **`execution_queue` 表**（datanest_engineering）：`queue_name`/`max_concurrency`/`description`/`is_system`。
- **`dag` 表扩展**：`queue_name`（默认 `default`）+ `priority`（1=低/2=中/3=高）。
- **排队状态机**：DAG 触发时，检查队列当前运行数 ≥ `max_concurrency` → 执行实例标记 `WAITING`（入等待池）；否则直接提交 PowerJob 执行。
- **定时调度器**：job 新增 `QueueDispatcherHandler`（每 5s 轮询），对 WAITING 实例按 `priority DESC, created_at ASC` 排序，队列有空位时逐个触发（提交 PowerJob 并置 RUNNING）。
- **对账兜底（用户拍板加）**：QueueDispatcherHandler 每轮除常规调度外，扫描超时未推进的 WAITING 实例 + 状态漂移的 RUNNING 实例（对齐现有 job 对账模式），job 重启后自动恢复调度，避免 WAITING 停滞。
- **并发计数**：队列「当前运行数」= 该队列下 RUNNING 执行实例数（`dag_execution` 按 queue_name + status 统计），允许秒级延迟（PRD B6）。
- **SchedulerClient 扩展**：DAG 触发走现有 `runWorkflow`，队列逻辑在 engineering 触发入口 + job 调度器，不改 PowerJob server 配置。

### D-5：首页聚合 = 前端并发调各服务 internal 端点 + 区块独立失败

- **不新建聚合服务**，前端首页 5 区块各发独立请求，任一区块失败只影响自身（PRD「独立加载独立失败」）。
- 各服务提供 internal 聚合端点（见 §5.5）：system（用户近期工作聚合）、governance（表总量/质量告警/资产浏览历史）、engineering（同步任务数/DAG 成功率/同步动态）、data-service（SQL 历史/API 统计）、system（服务健康，actuator health 聚合）。
- **权限**：聚合端点按当前登录用户过滤（近期工作只返回本人），经网关 + Sa-Token。

### D-6：菜单动态渲染 = 登录接口返回权限点集合

- 登录/用户信息接口扩展返回 `permissions: string[]`（当前用户全部角色的权限点并集）。
- 前端菜单（左侧子功能）与页面按钮由权限点集合推导：某模块至少一个权限点 → 菜单显示；无权限按钮不渲染。（顶部域改版已随导航重构取消，见 PRD v2.0）
- 前端权限点映射表集中一份（类似现有 `roles.ts` 收敛思路），避免散落各页。

---

## 2. 领域模型

```
system 域（权限点体系）
  sys_role（复用，加自定义角色 CRUD）
  sys_permission（复用，扩充按钮级权限点）
  sys_role_permission（复用，预置角色关联 + 自定义角色关联）
  sys_user_role（复用）
  sys_data_permission（新增，数据权限白名单）
  audit_log（新增，通用审计）

engineering 域（队列）
  execution_queue（新增）
  dag（扩展 queue_name/priority）

data-service / governance / engineering（埋点）
  复用 @AuditLog 注解，无新表
```

---

## 3. 数据模型设计

### 3.0 迁移脚本规划

| 服务 | 脚本 | 内容 |
|------|------|------|
| system | `V1.1.0__sprint11_audit.sql`（**F1 已交付**，2026-08-14） | 仅 audit_log 表（F1 拆分）；权限点种子 + sys_data_permission 拆到 F2，建议脚本 `V1.1.1__sprint11_rbac.sql` |
| engineering | `V1.7.0__sprint11_queue.sql`（**F3 实施前核对版本号**） | execution_queue + dag 加 queue_name/priority。⚠️ 原规划 V1.1.0 已过时：engineering 当前 Flyway 最高版本是 V1.6.0（V1.5.0 任务模板 / V1.6.0 模板值类型），必须用 V1.7.0 |

> 版本号必须大于各库 `flyway_schema_history` 当前最高版本；紧凑单行风格，禁格式化工具拆行。

### 3.1 system `V1.1.0` 核心表

```sql
-- sys_permission 扩充按钮级权限点（code 见 §6，name 中文）
-- 表已存在，本脚本 INSERT 权限点 + 预置角色关联

-- 数据权限白名单（默认全量可见 = 无记录）
CREATE TABLE public.sys_data_permission (
    id bigint NOT NULL,
    role_id bigint NOT NULL,
    datasource_id bigint NOT NULL,
    database_name character varying(128),
    table_name character varying(128),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone,
    CONSTRAINT sys_data_permission_pkey PRIMARY KEY (id)
);
CREATE UNIQUE INDEX uk_sys_data_permission ON public.sys_data_permission USING btree (role_id, datasource_id, database_name, table_name);
CREATE INDEX idx_sys_data_permission_role ON public.sys_data_permission USING btree (role_id);

-- 通用审计日志
CREATE TABLE public.audit_log (
    id bigint NOT NULL,
    operator_id bigint,
    operator_name character varying(64),
    op_type character varying(32) NOT NULL,
    resource_type character varying(32) NOT NULL,
    resource_id character varying(64),
    resource_name character varying(256),
    content text,
    result character varying(16) NOT NULL,
    error_message character varying(512),
    client_ip character varying(64),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT audit_log_pkey PRIMARY KEY (id)
);
CREATE INDEX idx_audit_log_operator ON public.audit_log USING btree (operator_name);
CREATE INDEX idx_audit_log_created ON public.audit_log USING btree (created_at DESC);
CREATE INDEX idx_audit_log_resource ON public.audit_log USING btree (resource_type, resource_id);
CREATE INDEX idx_audit_log_type ON public.audit_log USING btree (op_type);
```

### 3.2 engineering `V1.1.0`

```sql
CREATE TABLE public.execution_queue (
    id bigint NOT NULL,
    queue_name character varying(64) NOT NULL,
    max_concurrency integer DEFAULT 10 NOT NULL,
    description character varying(256),
    is_system boolean DEFAULT false NOT NULL,
    created_by bigint,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by bigint,
    updated_at timestamp without time zone,
    CONSTRAINT execution_queue_pkey PRIMARY KEY (id)
);
CREATE UNIQUE INDEX uk_execution_queue_name ON public.execution_queue USING btree (queue_name);

ALTER TABLE public.dag ADD COLUMN queue_name character varying(64) DEFAULT 'default' NOT NULL;
ALTER TABLE public.dag ADD COLUMN priority smallint DEFAULT 2 NOT NULL;
```

---

## 4. 核心流程

### 4.1 按钮级权限校验链路

```
用户登录 → system AuthController 返回 { token, userInfo, roles, permissions[] }
   │  permissions = 该用户全部角色 sys_role_permission 关联的 code 并集
前端菜单/按钮渲染：
   │  菜单：模块存在任一权限点 → 显示；按钮：有对应权限点 → 渲染
后端写接口（增删改/启停/改级/发布/触发）：
   │  @SaCheckPermission("sync:create") → Sa-Token 校验当前用户 permissions 含该 code
   │  无权限 → 403 NotPermissionException（GlobalExceptionHandler 已覆盖）
```

### 4.2 数据权限校验链路

```
SQL 终端/资产目录/API 选表/同步数据源选择：
   │ 前端传 datasourceId（+ database/table 可选）
   │  后端经 system-api Feign 查「当前用户数据权限范围」
   │    - 无记录 → 全量放行
   │    - 有记录 → 白名单过滤（datasource/database/table 逐级匹配，最细粒度优先）
   │  机密表 → 走 Sprint 10 机密锁（正交，不受白名单影响）
```

### 4.3 审计埋点链路

```
业务方法（写接口/关键操作）加 @AuditLog(resourceType, opType)
   │  AuditLogAspect 环绕切面：收集操作人(Sa-Token)/IP/入参 → 组装 event
   │  方法正常返回 → result=SUCCESS；抛异常 → result=FAILURE + error_message
   │  异步经 AuditLogRecorder → system internal 审计写入端点 → audit_log 落库
   │  写入失败 → fail-open（仅 warn 日志，不阻断业务）
```

### 4.4 执行队列调度链路

```
DAG 手动触发/定时触发（engineering DagService）
   │  读 dag.queue_name → 查 execution_queue.max_concurrency
   │  当前队列 RUNNING 数 < max_concurrency → 直接 runWorkflow 执行
   │  否则 → dag_execution 标记 WAITING（入等待池）

job QueueDispatcherHandler（每 5s）
   │  查所有队列当前 RUNNING 数
   │  对有空位的队列：取该队列 WAITING 实例，按 priority DESC + created_at ASC 排序
   │  逐个 runWorkflow 触发，置 RUNNING，直到队列满
```

### 4.5 首页数据聚合

```
首页加载 → 前端 5 区块并发请求（各自 try/catch，独立 loading/error）
   │  平台 KPI 4 卡 → 分别调 governance/engineering 统计端点
   │  我的近期工作 → system 聚合端点（当前用户 SQL/DAG/资产浏览）
   │  平台最新动态 → engineering(同步)+governance(质量/分级) 合并
   │  快捷入口 → 前端按 permissions 本地渲染
   │  系统健康 → system actuator 聚合端点
```

---

## 5. 接口设计

### 5.1 system 新增（RBAC + 数据权限 + 审计）

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | `/system/roles` | 角色列表（预置+自定义） | 超管 |
| POST | `/system/roles` | 创建自定义角色 | 超管 |
| PUT | `/system/roles/{id}` | 编辑自定义角色（名称/描述/权限点） | 超管 |
| DELETE | `/system/roles/{id}` | 删除自定义角色（有绑定用户则拒绝） | 超管 |
| GET | `/system/permissions` | 权限点清单（按钮级，供角色勾选） | 超管 |
| POST | `/system/roles/{id}/permissions` | 保存角色权限点集合 | 超管 |
| POST | `/system/data-permissions` | 保存角色数据权限（白名单） | 超管 |
| GET | `/system/data-permissions/{roleId}` | 查询角色数据权限 | 超管 |
| GET | `/system/audit-logs` | 审计日志分页（用户/类型/时间/关键词） | 超管 |
| GET | `/system/audit-logs/{id}` | 审计详情 | 超管 |
| GET | `/system/internal/permissions/{userId}` | 内部：查用户权限点集合 | internal |
| GET | `/system/internal/data-permission/{userId}` | 内部：查用户数据权限范围 | internal |
| POST | `/system/internal/audit` | 内部：审计写入 | internal |
| POST | `/system/internal/audit/cleanup` | 内部：清理 90 天前审计 | internal |

> 登录接口（现有 AuthController）扩展返回 `permissions` 字段。

### 5.2 engineering 新增（队列）

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | `/engineering/execution-queues` | 队列列表（含运行/等待数） | 超管 |
| POST | `/engineering/execution-queues` | 创建队列 | 超管 |
| PUT | `/engineering/execution-queues/{id}` | 编辑队列（名称/并发/描述） | 超管 |
| DELETE | `/engineering/execution-queues/{id}` | 删除队列（有绑定 DAG 拒绝） | 超管 |
| GET | `/engineering/internal/home/stats` | 首页聚合：同步任务数/DAG 周成功率/同步动态 | internal |
| POST | `/engineering/internal/queue/dispatch` | 队列调度触发（job 调） | internal |

> DAG 创建/编辑请求体扩展 `queueName`/`priority` 字段；DAG 执行历史 DTO 扩展回填队列/优先级。

### 5.3 governance 新增（审计埋点 + 首页聚合）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/governance/internal/home/stats` | 首页聚合：表总量/质量告警/分级动态 |

> 数据源管理/元数据/质量/分级等写接口加 `@AuditLog` + `@SaCheckPermission`。

### 5.4 data-service 新增（审计埋点 + 首页聚合）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/data-service/internal/home/stats` | 首页聚合：SQL 历史/API 统计 |

> SQL 执行、数据 API 写接口、API Key 写接口加 `@AuditLog` + `@SaCheckPermission`。

### 5.5 前端新增/改造

- 个人中心：头像下拉入口 + 右侧抽屉（身份头部 / 基本信息可编辑 / 改密移入抽屉），后端 `GET/PUT /system/auth/profile`；菜单/按钮按 `permissions` 渲染沿用 F2。
- 首页面板：5 区块组件 + 各区块独立请求。
- 系统管理新增页：角色管理（含创建/编辑弹窗 + 按钮级权限勾选树）、权限配置（数据源/库/表三级树）、审计日志页、执行队列页。
- DAG 编辑表单新增队列/优先级字段；DAG 执行历史新增两列。

---

## 6. 权限点清单（按钮级，§6.2.1 PRD 矩阵的技术落地）

权限点 code = `模块:动作`，共 **18 模块 / 约 80 权限点**：

| 模块 | 权限点（`模块:动作`） |
|------|----------------------|
| 数据源管理 | `datasource:view` / `datasource:create` / `datasource:update` / `datasource:delete` / `datasource:test` |
| 批量同步任务 | `sync:view` / `sync:create` / `sync:update` / `sync:delete` / `sync:execute` / `sync:history` |
| CDC 管道 | `cdc:view` / `cdc:create` / `cdc:update` / `cdc:delete` / `cdc:execute` / `cdc:monitor` |
| DAG 编排 | `dag:view` / `dag:create` / `dag:update` / `dag:delete` / `dag:execute` / `dag:history` |
| 任务模板库 | `template:view` / `template:create` / `template:update` / `template:delete` |
| 元数据 | `metadata:view` / `metadata:comment` / `metadata:lineage` |
| 元数据采集任务 | `collect:view` / `collect:create` / `collect:update` / `collect:delete` / `collect:execute` / `collect:history` |
| 数据标准 | `standard:view` / `standard:create` / `standard:update` / `standard:delete` |
| 标准合规 | `compliance:view` / `compliance:handle` |
| 质量规则 | `quality_rule:view` / `quality_rule:create` / `quality_rule:update` / `quality_rule:delete` |
| 质量任务 | `quality_job:view` / `quality_job:create` / `quality_job:update` / `quality_job:delete` / `quality_job:execute` / `quality_job:history` |
| 质量结果 | `quality_result:score` / `quality_result:report` |
| 资产目录 | `asset:view` / `asset:collab` / `asset:comment` |
| SQL 查询终端 | `sql:execute` / `sql:export` / `sql:history` |
| 数据 API | `api:view` / `api:create` / `api:update` / `api:publish` / `api:delete` / `api:stats` |
| API Key 管理 | `api_key:view` / `api_key:create` / `api_key:toggle` / `api_key:delete` |
| 数据分级分类 | `sensitivity:view` / `sensitivity:change` / `sensitivity:batch_change` |
| 告警中心 | `alert:view` / `alert:rule_manage` |

**系统管理类（仅超管，不开放自定义角色）**：`user:view` / `user:create` / `user:update` / `user:toggle` / `user:reset_pwd` / `role:view` / `role:create` / `role:update` / `role:delete` / `data_permission:manage` / `audit:view` / `queue:manage`。

**预置角色权限点分配**（由 PRD §6.2.1 按钮级矩阵 + 现有 @SaCheckRole 现状映射）：

| 角色 | 权限点（概要） |
|------|--------------|
| 超级管理员 | 全部权限点 + 系统管理类 |
| 数据工程师 | datasource/sync/cdc/dag/template 全部；metadata 全部；compliance:view；quality_result 全部；asset 全部；sql 全部；api 全部；api_key 全部；sensitivity:view；alert 全部 |
| 数据分析师 | metadata:view；quality_result 全部；asset 全部；sql 全部；api:view+stats；api_key:view；sensitivity:view |
| 治理管理员 | datasource:view；metadata 全部；collect/standard/compliance/quality_rule/quality_job/quality_result 全部；asset 全部；api:view+stats；api_key:view；sensitivity 全部；alert:view |

---

## 7. 配置项与部署

### 7.1 配置项（Nacos shared-configs）

共享配置经 `spring.config.import: optional:nacos:shared-*.yaml?group=shared-configs&refreshEnabled=true` 加载（各服务 import 各自需要的 shared 文件）。

**Nacos 热更新验证结论（2026-08-17 实测，PRD N2/D4）**：

| 配置类别 | 修改 Nacos 后是否生效 | 说明 |
|----------|----------------------|------|
| `logging.level.*`（shared-common.yaml） | ✅ **热生效，无需重启** | Spring Boot `LoggingRebinder` 监听环境变更实时刷新 logger；实测 app-system 改 `com.datanest: debug` 后登录请求立即输出 MyBatis SQL DEBUG，改回后立即回落（验证 A） |
| 业务参数 `@Value`（已配 `@RefreshScope`，如 `datanest.asset.search.max-results`） | ✅ **热生效，无需重启** | Nacos 推送 → `Refresh keys changed: [xxx]` → `@RefreshScope` Bean 被失效并在下次访问时重建（重新注入 `@Value`）；实测改 max-results 200→3 后资产搜索立即从 5 条变 3 条（2026-08-17 改造后复测） |
| 普通 `@Value`（未配 `@RefreshScope`） | ❌ **不生效，需重启服务** | 仅 Environment 刷新，Bean 字段不变（验证 B 原始结论）；如新增业务参数需要热更新，须加 `@RefreshScope` 或 `@ConfigurationProperties` |

> 注意：`logging.level.com.datanest` 在 shared-common.yaml 中为 `${DATANEST_LOG_LEVEL:info}` 占位符（docker compose 未注入该 env，占位符解析为默认 info）。**改 Nacos 里占位符的默认值无效**（env 优先），要调整日志级别应改为字面量 `debug`/`info`（见验证 A 步骤）。
>
> **`@RefreshScope` 适用边界（2026-08-17 定稿）**：仅给**纯业务参数 Bean**（cleanup retain-days、query-timeout、max-results、熔断/限流阈值、质量扣分阈值等）配置；**连接/凭据类**（Doris/MinIO/Flink/Kafka）与 **PowerJob appname** 不加（重建 Bean 会中断连接或改了不生效，此类配置经重启更新）。已加范围：job 11 个 handler、governance `AssetCatalogService`/`QualityRuleService`/`ScoreCalculator`、data-service `SqlQueryService`/`OpenApiService`/`RateLimitService`/`CircuitBreakerService`/`DataServiceOpsController`。
>
> **realtime 调度迁移（2026-08-17）**：realtime 原 3 处本地 `@Scheduled`（`CdcMonitorService` 监控轮询×2、`MetricSnapshotWriter` 分钟落库、`MetricRetentionCleaner` 保留期清理）已全部移除，**统一迁至 app-job 按 PowerJob cron 调度**（符合「本地禁止 @Scheduled，统一 PowerJob cron」约定）——job 新增 `CdcOpsApi` Feign 契约，经 realtime `CdcInternalController` 的 4 个内部端点触发执行，内存状态（累加器/告警去重/404 计数）仍留在 realtime。**cron 热更新**：`JobRegistrar` 监听 `RefreshScopeRefreshedEvent`，Nacos 配置推送（任何 @RefreshScope Bean 重建）后从 Environment 重算全部 cron 并 `saveOrUpdate` 幂等重注册；实测改 `datanest.job.cdc-monitor-poll.interval-ms` 5000→10000 后 PowerJob cron 自动 `0/5`→`0/10` 无需重启。

**实际业务配置键**（来自代码，非全量）：

| 键 | 默认 | 读取方 | 说明 |
|----|------|--------|------|
| `datanest.asset.search.max-results` | 200 | governance `AssetCatalogService` | 资产搜索结果裁剪上限（@RefreshScope） |
| `datanest.queue.dispatch-interval-seconds` | 5 | job `JobRegistrar` | 执行队列调度 cron 间隔（生成 `0/N * * * * ?`，热更新） |
| `datanest.job.cdc-monitor-poll.interval-ms` | 5000 | job `JobRegistrar` | CDC 监控轮询间隔（毫秒→cron `0/N * * * * ?`，热更新；原 realtime `datanest.realtime.monitor.interval-ms` 迁移） |
| `datanest.dataservice.sql.query-timeout-seconds` | 60 | data-service `SqlQueryService`/`OpenApiService` | SQL 终端/API 查询超时（@RefreshScope） |
| `datanest.job.*-cleanup.retain-days` | 30~90 | job 各 CleanupHandler | 各类历史数据清理保留天数（dag-history/lineage/alert-history/quality-check/sql/api-call/asset-view-log/audit-log，均 @RefreshScope） |
| `datanest.realtime.monitor.not-found-threshold` / `datanest.realtime.lag.warn-threshold` | 3 / 30 | realtime `CdcMonitorService` | 监控判定阈值（逻辑在 realtime，非热更新） |

> 审计日志清理：system 暴露 `InternalAuditController.cleanup`（Feign，`retainDays` 参数默认 90）；**job 侧已注册 `auditLogCleanupHandler`**（2026-08-17，PowerJob jobId=397，cron `0 0 5 * * ?` 每天凌晨 5 点，`SystemAuditApi.cleanup` Feign 契约 + 降级返回 0）。

### 7.2 依赖与构建变更

- common：新增 `@AuditLog` 注解 + `AuditLogAspect` + `AuditLogEvent` DTO + `AuditLogRecorder` 接口（改动 common = **全量重建所有后端服务镜像**，见红线）。
- system-api：新增 `SystemAuditApi` Feign 契约（internal 写入 + 权限查询）。
- engineering：加 `queue` 相关 Service/Controller/Mapper；依赖 system-api（已有）。
- job：新增 `QueueDispatcherHandler`（执行队列调度，依赖 engineering-api）。
- 前端：Sidebar/路由/首页/系统管理页重写，权限点映射表 + `usePermission` hook。

### 7.3 部署步骤

1. 后端：system → common 依赖方（engineering/governance/data-service/job/alert/realtime）全量重建 → 重建镜像。
2. Flyway：system V1.1.0（权限点种子 + 数据权限 + 审计表）、engineering V1.1.0（队列）。
3. 前端：`pnpm build` → 重建 app-frontend。
4. 重启依赖服务，验证权限点返回 + 菜单渲染。

---

## 8. 已知 Blocker 与待确认点

| # | 问题 | 状态 |
|---|------|------|
| 1 | **读接口权限处理**：写接口全校验 + 读接口混合策略（区分读迁 view 权限点、无区分读改 @SaCheckLogin），解决自定义角色被 @SaCheckRole 403 挡住 | ✅ 已定（D-1） |
| 2 | **权限点 code 命名**：`模块:动作` 已定，前端 `roles.ts` 保留角色组合常量（用于预置角色），权限点用于按钮级，两者并存不冲突 | ✅ 已定 |
| 3 | **队列对账兜底**：QueueDispatcherHandler 每轮扫描超时 WAITING + 状态漂移 RUNNING，job 重启自动恢复调度 | ✅ 已定（D-4） |
| 4 | **首页 KPI 统计口径**：数据表总量（ONLINE 表）/ 活跃同步任务（启用或运行中）/ 7天质量告警 / DAG 周成功率，实现时与各服务现有统计端点对齐 | 待实现对齐 |
| 5 | **审计异步写入可靠性**：fail-open 会导致 system 挂掉时审计丢数据，可接受（审计是旁路） | ✅ 已定 |
| 6 | **自定义角色 code 生成**：管理员填写可读英文 code（唯一约束已有 uk_sys_role_code） | ✅ 已定（D-1） |

---

## 9. 实现清单（P0）

### 后端

> 实现进度（2026-08-14）：**F1 审计日志已完成**（除标注 F2/F3 外）；其余立项项未开始。

- [x] system：`V1.1.0__sprint11_audit.sql`（仅 audit_log 表；权限点种子 + sys_data_permission 属 F2，拆 `V1.1.1__sprint11_rbac.sql`）
- [ ] system：`PermissionService`/`RoleService`（角色 CRUD + 权限点关联 + 数据权限 CRUD）【F2】
- [x] system：`AuditLogService`（写入 + 分页查询 + 清理 internal 端点）
- [ ] system：登录接口扩展返回 `permissions`【F2，注：`UserLoginDTO` 已有 permissions 字段，verify() 暂返空 List】
- [x] system：internal 端点（审计写入 `/internal/audit` + 清理 `/internal/audit/cleanup`）；权限点/数据权限查询端点属 F2
- [x] common：`@AuditLog` + `AuditLogAspect`（异步 fail-open + SpEL 提取）+ `AuditLogEvent` + `AuditLogRecorder` + `AuditAutoConfiguration`
- [x] system-api：`SystemAuditApi` Feign 契约 + fallback
- [ ] engineering：队列 CRUD + dag 扩展字段 + 排队状态机 + 触发入口改造【F3】
- [x] engineering：写接口加 `@AuditLog`（数据源/同步/DAG；`@SaCheckPermission` 属 F2）
- [x] governance：写接口加 `@AuditLog`（分级改级手动埋点，旧→新等级；其余写接口 `@SaCheckPermission` 属 F2）
- [x] data-service：写接口加 `@AuditLog`（SQL 手动埋点 + API/Key；`@SaCheckPermission` 属 F2）
- [ ] job：`AuditLogCleanupHandler` + `QueueDispatcherHandler` + JobRegistrar 注册【F2/F3，注：audit 90 天清理端点已在 system 就绪】
- [ ] 首页聚合 internal 端点（system/governance/engineering/data-service）【F5】

### 前端

> 实现进度：F1 审计日志页 / F2 RBAC / F3 队列 / F5 首页均已交付；**F6 个人中心已完成（2026-08-17）**。

- [x] 个人中心：头像下拉入口 + ProfileDrawer（身份头部/基本信息可编辑/改密）【F6，2026-08-17】
- [ ] 权限点映射表 + `usePermission` hook（菜单/按钮显隐）【F2/F6】
- [ ] 角色管理页（列表 + 创建/编辑弹窗 + 按钮级权限勾选树）【F2】
- [ ] 权限配置页（数据源/库/表三级树）【F2】
- [x] 审计日志页 + 详情抽屉（列表/组合筛选/失败行浅红高亮/大标题描述/时间必填默认近7天/每页10条）
- [ ] 执行队列页【F3】
- [ ] DAG 表单队列/优先级字段 + 执行历史两列【F3】
- [ ] 首页五区块真实数据【F5】

### 部署与验证

- [x] 后端 5 容器（engineering/worker/governance/data-service/system）重建镜像并部署；前端已 build 部署 app-frontend
- [x] **F1 审计日志 E2E 全功能测试通过（2026-08-14，24/24）**：`e2e/sprint11/e2e/audit.spec.ts` + `helpers/audit-seed.ts`（自播种自清理，物理删除 e2e_s11_* 资源 + 敏感度快照恢复）
  - 覆盖 AL-1~AL-10：8 类埋点（USER/DATASOURCE/SYNC_JOB/DAG/SQL_QUERY 成功+机密拦截/DATA_API/API_KEY/SENSITIVITY 改级）跨 5 服务触发并轮询验证落库；查询页列表/组合筛选（操作人/类型/资源/关键词/时间范围）/详情抽屉/失败行浅红高亮/分页；分析师 403 + 无修改删除接口
  - 测试要点：同步任务 execute 为真实 PowerJob 异步执行，删除前须轮询执行历史至终态（否则 6005）；机密拦截用 `datanest.target_users` 临时改 CONFIDENTIAL（不动被 PUBLISHED API 绑定的 target_products），测完恢复 PUBLIC
  - 测试产物与临时数据已清理（含既有残留 `e2e_s11_*`、测试用户 `audit_test_185305`）
- [ ] 按 PRD 验收标准（AL/PM/QU/HP/NAV）逐项验证（其余功能项待 F2/F3/F5/F6）

---

## 10. 验收口径映射（PRD AC）

| PRD 验收 | 对应实现点 |
|---------|-----------|
| F1 AL-1~10 | 审计埋点 + 审计查询页 + 权限（仅超管） |
| F2 PM-1~18 | 数据权限 + 角色 CRUD + 按钮级权限 + 后端写接口校验 |
| F3 QU-1~7 | 队列 CRUD + dag 扩展 + 排队状态机 + 审计 |
| F5 HP-1~8 | 首页五区块聚合端点 + 前端渲染 |
| F6 PR-1~7 | 个人中心抽屉 + 资料编辑/清空 + 改密 + 权限（登录可用/未登录 401） |

> **版本记录**
> - v1.1（2026-08-14）：F1 审计日志实现进度同步——§3.0 迁移脚本版本号校对（system 拆 `V1.1.0__sprint11_audit.sql` 仅审计表、权限点/数据权限拆 F2；engineering 队列脚本实际版本应为 **V1.7.0** 非 V1.1.0）、§9 实现清单勾选 F1 已交付项并标注 F2/F3 边界。
> - v1.0（2026-08-14）：初始版本，6 个 ADR + 权限点清单 + 数据模型 + 实现清单。用户交互确认三决策：读接口混合策略（区分读迁 view 权限点/无区分读改 @SaCheckLogin）、自定义角色 code 管理员填可读英文、队列加对账兜底。
