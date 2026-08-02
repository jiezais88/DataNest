# DataNest Sprint 5 技术文档

> **Sprint**：Sprint 5 — 血缘可视化、全局告警中心与 DAG 控制流增强
> **文档状态**：Working Draft (v1.0) | **作者**：软件架构师 | **日期**：2026-08-02
> **关联文档**：`DataNest-Sprint5-PRD.md`、`DataNest-技术架构文档-v2.3.md`

---

## 目录

1. [Sprint 概述](#1-sprint-概述)
2. [交付物清单](#2-交付物清单)
3. [项目结构变更](#3-项目结构变更)
4. [Docker Compose 变更](#4-docker-compose-变更)
5. [架构关系图](#5-架构关系图)
6. [血缘可视化](#6-血缘可视化)
7. [全局告警中心](#7-全局告警中心)
8. [DAG 控制流增强](#8-dag-控制流增强)
9. [数据库设计](#9-数据库设计)
10. [API 接口设计](#10-api-接口设计)
11. [前端设计](#11-前端设计)
12. [Sprint 5 ADR](#12-sprint-5-adr)
13. [验收标准](#13-验收标准)
14. [风险与对策](#14-风险与对策)

---

## 1. Sprint 概述

### 1.1 Sprint 目标

Sprint 4 完成了数据血缘的自动采集与存储、按 DAG 的邮件告警、DAG 节点执行收敛到 worker 等基础能力。Sprint 5 在此基础上：

1. **血缘可视化**：把 `lineage_record` 中的血缘记录以图谱形式展示出来，并支持字段级下钻。
2. **全局告警中心**：把分散的 DAG 告警扩展为平台级告警中心，覆盖 DAG、批量同步任务、元数据采集任务；收件人从手动输入邮箱改为选择平台用户。
3. **DAG 控制流增强**：在 DAG 中支持条件分支节点和子 DAG 节点，复用 DolphinScheduler 原生能力。

### 1.2 Sprint 范围

| # | 工作项           | 所属模块                                                                         | 说明                                    |
|---|------------------|----------------------------------------------------------------------------------|-----------------------------------------|
| 1 | **血缘可视化**   | governance-service + frontend                                                    | 表级/字段级血缘图谱、影响分析、溯源分析 |
| 2 | **全局告警中心** | system-service / task-core / engineering-service / governance-service + frontend | 通用告警规则表、用户选择器、邮件发送    |
| 3 | **条件分支节点** | engineering-service + frontend                                                   | DS SWITCH/CONDITIONS 任务映射           |
| 4 | **子 DAG 节点**  | engineering-service + frontend                                                   | DS SUB_PROCESS 任务映射                 |

### 1.3 不在本 Sprint

| 暂缓项                            | 后续 Sprint | 理由             |
|-----------------------------------|:-----------:|------------------|
| 血缘 3D 效果 / 复杂力导向布局     |  未来优化   | 本期以可用性优先 |
| 告警渠道扩展（钉钉/企微/Webhook） |  Sprint 6   | 渠道对接工作     |
| 子 DAG 参数透传与覆盖             |  Sprint 6   | 参数系统扩展     |
| 条件分支循环（do/while）          |  未来考虑   | 控制流复杂度     |

### 1.4 技术栈

| 组件                               | 版本/说明   | 用途                                  |
|------------------------------------|-------------|---------------------------------------|
| Apache DolphinScheduler            | 3.4.2       | DAG 调度与执行引擎（Sprint 3 已集成） |
| ReactFlow                          | 11.11.x     | 前端 DAG 画布与血缘图谱               |
| PostgreSQL                         | 16          | 血缘记录、告警规则存储                |
| Spring Expression Language（SpEL） | Spring 内置 | 条件分支表达式解析                    |

---

## 2. 交付物清单

| #  | 交付物                                                                                          | 类型 | 验收方式     |
|----|-------------------------------------------------------------------------------------------------|------|--------------|
| D1 | `data-nest-governance` 新增血缘图谱查询/字段级血缘 API                                          | 代码 | API 可用     |
| D2 | `data-nest-task-core` 扩展 `LineageRecord` / `SqlLineageExtractor` 支持字段级血缘               | 代码 | 编译通过     |
| D3 | Flyway 迁移脚本：扩展 `lineage_record`、新建 `alert_rule` / `alert_rule_user` / `alert_history` | 代码 | 启动自动建表 |
| D4 | `data-nest-system` 新增用户选择器 API（过滤有邮箱的用户）                                       | 代码 | API 可用     |
| D5 | `data-nest-task-core` 新增通用告警服务 `AlertRuleService`                                       | 代码 | 编译通过     |
| D6 | `data-nest-engineering` 扩展 `DagDsConverter` 支持 CONDITION / SUB_PROCESS 节点映射             | 代码 | API 可用     |
| D7 | `data-nest-frontend` 新增血缘图谱页面、告警中心页面、条件分支/子 DAG 编辑器                     | 代码 | 页面可用     |
| D8 | Gateway 路由无需新增（复用 `/api/governance/**`、`/api/system/**`、`/api/engineering/**`）      | 配置 | 现有路由可用 |

---

## 3. 项目结构变更

### 3.1 模块职责划分

Sprint 5 不新增独立微服务，核心逻辑继续下沉到 `task-core`；新增接口分布到 `governance-service`、`system-service`、
`engineering-service`。

```
data-nest/
├── data-nest-task-core/              # 字段级血缘提取、通用告警规则、告警触发
│   └── src/main/java/com/datanest/task/core/
│       ├── entity/
│       │   ├── LineageRecord.java            # 扩展 source_column / target_column
│       │   ├── AlertRule.java                # 🆕 通用告警规则
│       │   ├── AlertRuleUser.java            # 🆕 告警规则与接收用户关联
│       │   └── AlertHistory.java             # 🆕 告警发送历史
│       ├── mapper/
│       │   ├── LineageRecordMapper.java      # 扩展字段级查询
│       │   ├── AlertRuleMapper.java          # 🆕
│       │   ├── AlertRuleUserMapper.java      # 🆕
│       │   └── AlertHistoryMapper.java       # 🆕
│       ├── service/
│       │   ├── SqlLineageExtractor.java      # 扩展字段级提取
│       │   ├── AlertRuleService.java         # 🆕 通用告警规则解析/触发
│       │   ├── AlertExecutionListener.java   # 🆕 监听任务终态并触发告警
│       │   └── MailService.java              # 已有，扩展批量用户 ID → 邮箱
│       └── dto/
│           ├── LineageGraphDTO.java          # 🆕 血缘图谱数据
│           └── LineageColumnLinkDTO.java     # 🆕 字段级血缘链路
│
├── data-nest-governance/             # 血缘查询/展示 API
│   └── src/main/java/com/datanest/governance/
│       ├── controller/
│       │   └── LineageController.java        # 扩展图谱查询接口
│       └── service/
│           └── LineageService.java           # 扩展字段级血缘构建
│
├── data-nest-system/                 # 用户选择器 API
│   └── src/main/java/com/datanest/system/
│       └── controller/
│           └── UserSelectorController.java   # 🆕 查询有邮箱的用户列表
│
├── data-nest-engineering/            # DAG 控制流映射、告警规则 CRUD
│   └── src/main/java/com/datanest/engineering/
│       ├── controller/
│       │   ├── DagNodeController.java        # 扩展节点类型
│       │   └── AlertRuleController.java      # 🆕 告警规则 CRUD（统一入口）
│       ├── service/
│       │   ├── DagDsConverter.java           # 扩展 CONDITION / SUB_PROCESS
│       │   └── DagNodeConfigService.java     # 扩展节点配置校验
│       └── dto/
│           ├── ConditionNodeConfig.java      # 🆕
│           └── SubDagNodeConfig.java         # 🆕
│
├── data-nest-frontend/               # 前端页面/组件
│   └── src/pages/
│       ├── governance/lineage/
│       │   ├── LineageGraphPage.tsx          # 🆕 血缘图谱
│       │   └── FieldLineagePanel.tsx         # 🆕 字段级血缘
│       ├── system/alert-center/
│       │   └── AlertCenterPage.tsx           # 🆕 告警中心
│       └── engineering/dags/
│           ├── components/
│           │   ├── ConditionNodeModal.tsx    # 🆕 条件分支配置
│           │   └── SubDagNodeModal.tsx       # 🆕 子 DAG 配置
│           └── Editor.tsx                    # 扩展节点面板与连线规则
```

### 3.2 设计原则

1. **血缘可视化不引入图数据库**：基于现有 `lineage_record` 表做关系查询，前端用 ReactFlow 做图布局；降低部署复杂度。
2. **告警规则通用化**：新建 `alert_rule` 表统一表达 DAG / 同步任务 / 采集任务的告警规则；`dag_alert_config` 兼容读取，新规则优先走
   `alert_rule`。
3. **收件人改为用户 ID**：`alert_rule_user` 只存用户 ID，发送时反查 `sys_user.email`；未填邮箱的用户不可选。
4. **控制流复用 DS 原生能力**：条件分支映射为 DS CONDITIONS/SWITCH，子 DAG 映射为 DS SUB_PROCESS，不自行解释执行。

---

## 4. Docker Compose 变更

Sprint 5 不新增中间件服务，不修改 `docker-compose.yml`。

---

## 5. 架构关系图

### 5.1 整体数据流

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           DataNest Frontend                                  │
│  ┌──────────────┐  ┌──────────────┐  ┌─────────────────────────────────────┐│
│  │ 血缘图谱页    │  │ 告警中心页    │  │ DAG 编辑器                           ││
│  │ • 表级图谱    │  │ • 规则列表    │  │ • 条件分支节点 🆕                    ││
│  │ • 字段级下钻  │  │ • 告警历史    │  │ • 子 DAG 节点 🆕                     ││
│  └──────────────┘  └──────────────┘  └─────────────────────────────────────┘│
└─────────────────────────────────┬───────────────────────────────────────────┘
                                  │ HTTP
                                  ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                              app-gateway                                     │
│       /api/governance/**  /api/system/**  /api/engineering/**                │
└─────────────────────────────────┬───────────────────────────────────────────┘
                                  │
          ┌───────────────────────┼───────────────────────┐
          ▼                       ▼                       ▼
┌───────────────────┐  ┌───────────────────┐  ┌───────────────────────────────┐
│ data-nest-governance│  │ data-nest-system  │  │ data-nest-engineering          │
│ LineageController  │  │ UserSelectorController│  │ AlertRuleController 🆕        │
│ LineageService     │  │                   │  │ DagDsConverter（扩展）         │
└───────────────────┘  └───────────────────┘  └──────────────┬────────────────┘
                                                               │
                                                               ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         Apache DolphinScheduler 3.4.2                        │
│              调度条件分支、子 DAG、SQL/SYNC/Python HTTP 任务                  │
└─────────────────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           data-nest-worker                                   │
│   SQL/Python/SYNC 节点执行、字段级血缘写入、告警事件触发 🆕                   │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 6. 血缘可视化

### 6.1 数据模型扩展

**扩展 `lineage_record` 表**：新增 `source_column`、`target_column` 字段，用于字段级血缘。

```java
// LineageRecord.java
@Data
@TableName("lineage_record")
public class LineageRecord {
    // ... 已有字段
    private String sourceColumn;   // 🆕 源字段，可能为 null
    private String targetColumn;   // 🆕 目标字段，可能为 null
}
```

**说明**：

- 表级血缘：`source_column` 和 `target_column` 均为 null。
- 字段级血缘：`source_column` / `target_column` 有值。
- 当字段级血缘缺失时，前端可回退到表级血缘展示。

### 6.2 字段级血缘提取

**扩展现有 `SqlLineageExtractor`**：在解析 SQL AST 时，不仅提取 source/target 表，还提取 SELECT/INSERT 中的列映射关系。

支持场景：

- `INSERT INTO target_table(a, b) SELECT x, y FROM source_table` → 生成 `x→a`、`y→b` 字段级血缘。
- `CREATE TABLE t AS SELECT ...` → 生成字段级血缘。
- 复杂表达式（如 `SELECT x+y AS z`）先记录表达式字符串作为 sourceColumn。

### 6.3 图谱查询服务

**在 `governance-service` 新增图谱构建逻辑**：

```java

@Service
public class LineageService {

    /**
     * 以指定表为中心，构建表级血缘图谱。
     * 默认返回一层上游 + 一层下游，支持 depth 参数扩展层数。
     */
    public LineageGraphDTO buildTableGraph(String tableName, int depth) { ...}

    /**
     * 以指定字段为中心，构建字段级血缘链路。
     */
    public List<LineageColumnLinkDTO> buildColumnLineage(String tableName, String columnName) { ...}
}
```

**`LineageGraphDTO` 结构**：

```java
public class LineageGraphDTO {
    private List<Node> nodes;      // 表节点：id、name、database、type
    private List<Edge> edges;      // 血缘边：source、target、lineageType
}
```

### 6.4 影响分析与溯源分析

- **影响分析**：从中心表出发，递归查找所有下游表/字段。
- **溯源分析**：从中心表出发，递归查找所有上游表/字段。
- 后端返回完整子图，前端根据类型高亮。

---

## 7. 全局告警中心

### 7.1 数据模型

**新建 `alert_rule` 表**：通用告警规则。

```java
// AlertRule.java
@Data
@TableName("alert_rule")
public class AlertRule {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String objectType;        // DAG / SYNC_JOB / COLLECT_TASK
    private Long objectId;            // DAG ID / sync_job.id / collect_task.id
    private String objectName;        // 冗余名称，便于列表展示
    private String triggerConditions; // JSON: ["FAILURE", "TIMEOUT", "SUCCESS"]
    private Integer timeoutMinutes;   // 超时阈值
    private Integer enabled;          // 1 / 0
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

**新建 `alert_rule_user` 表**：规则与接收用户的多对多关系。

```java
// AlertRuleUser.java
@Data
@TableName("alert_rule_user")
public class AlertRuleUser {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long alertRuleId;
    private Long userId;
}
```

**新建 `alert_history` 表**：告警发送历史。

```java
// AlertHistory.java
@Data
@TableName("alert_history")
public class AlertHistory {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long alertRuleId;
    private String objectType;
    private Long objectId;
    private String alertType;       // FAILURE / TIMEOUT / SUCCESS
    private String recipients;      // 实际发送的邮箱列表
    private LocalDateTime sentAt;
}
```

### 7.2 用户选择器

**在 `data-nest-system` 新增接口**：

```java

@GetMapping("/system/users/with-email")
public Result<List<UserOptionDTO>> listUsersWithEmail(@RequestParam(required = false) String keyword) { ...}
```

- 只返回 `email` 字段非空的用户。
- 支持按用户名/邮箱模糊搜索。

### 7.3 告警触发

**扩展 `data-nest-task-core` 的告警监听**：

- DAG 失败/超时/成功：已有 `DagAlertExecutionListener`，改为调用新的 `AlertRuleService`。
- 同步任务失败：在 `SyncJobExecutorService` 执行结束后触发。
- 采集任务失败：在 `CollectExecutor` 执行结束后触发。

**`AlertRuleService` 核心逻辑**：

```java

@Service
public class AlertRuleService {

    /**
     * 根据对象类型和对象 ID 解析告警规则。
     */
    public AlertRule resolveRule(String objectType, Long objectId) { ...}

    /**
     * 触发告警。
     */
    public void fire(String objectType, Long objectId, String alertType, String detail) {
        AlertRule rule = resolveRule(objectType, objectId);
        if (rule == null || !enabled(rule)) return;
        if (!contains(rule, alertType)) return;

        List<Long> userIds = alertRuleUserMapper.selectUserIdsByRuleId(rule.getId());
        List<String> emails = sysUserMapper.selectEmailsByIds(userIds);
        if (emails.isEmpty()) return;

        String subject = buildSubject(objectType, objectId, alertType);
        String body = buildBody(objectType, objectId, alertType, detail);
        mailService.send(String.join(";", emails), subject, body);
        saveHistory(rule, objectType, objectId, alertType, emails);
    }
}
```

### 7.4 与 Sprint 4 的兼容

- 新规则统一写入 `alert_rule`。
- `dag_alert_config` 数据可在启动迁移脚本中导入 `alert_rule`。
- 读取时优先查 `alert_rule`，不存在时回退 `dag_alert_config`，保证 Sprint 4 已配置规则继续生效。

---

## 8. DAG 控制流增强

### 8.1 节点类型扩展

`dag_node.node_type` 从 `SQL / SYNC / PYTHON` 扩展为 `SQL / SYNC / PYTHON / CONDITION / SUB_DAG`。

### 8.2 条件分支节点

#### 8.2.1 配置模型

```java
// ConditionNodeConfig.java
@Data
public class ConditionNodeConfig {
    private String type = "CONDITION";
    private List<ConditionBranch> branches;

    @Data
    public static class ConditionBranch {
        private String branchName;
        private String expression;    // SpEL 表达式，如 "${upstream.row_count} > 0"
        private String nextNodeId;    // 满足条件时走向的下游节点 ID
    }
}
```

#### 8.2.2 DS 映射

**映射为 DS CONDITIONS 任务**（DS 3.4.2 支持 `CONDITIONS` 任务类型）：

- `taskType = "CONDITIONS"`
- `taskParams` 中定义 `dependence` 关系，指向一个前置任务，并根据结果选择下游任务。
- 由于 DataNest 的条件表达式可能依赖上游输出变量，实际采用替代方案：
    - 在条件分支节点前插入一个轻量的 HTTP 任务（节点类型仍为 `CONDITION`），该任务调用 worker 的
      `/dev/internal/condition/callback`。
    - worker 执行表达式计算，将结果写回 `node_execution.output_info`。
    - DS CONDITIONS 任务读取该结果，决定下游分支。

> **简化方案**：如果 DS 原生 CONDITIONS 对自定义变量支持不够灵活，也可把条件分支节点映射为一个 HTTP 任务，由 worker
> 直接解释表达式并返回成功/失败；DS 根据任务成功/失败走不同的 taskRelation 条件分支。

#### 8.2.3 表达式上下文

表达式可引用：

- 上游节点输出：`${upstream.nodeId.row_count}`
- DAG 参数：`${biz_date}`
- 系统变量：`${dag_id}`、`${current_time}`

### 8.3 子 DAG 节点

#### 8.3.1 配置模型

```java
// SubDagNodeConfig.java
@Data
public class SubDagNodeConfig {
    private String type = "SUB_DAG";
    private Long subDagId;            // 子 DAG ID
    private String subDagName;        // 冗余名称
    private Boolean syncExecution;    // true=同步执行，false=异步执行
}
```

#### 8.3.2 DS 映射

**映射为 DS SUB_PROCESS 任务**：

- `taskType = "SUB_PROCESS"`
- `taskParams` 中指定 `processDefinitionCode` 为子 DAG 的 `ds_process_definition_code`。
- DS 负责调度子 DAG，父 DAG 中该节点状态由子 DAG 流程实例状态决定。

#### 8.3.3 循环引用校验

保存 DAG 时：

1. 收集所有 `SUB_DAG` 节点引用的子 DAG ID。
2. 递归检查子 DAG 是否又引用当前 DAG 或已引用链路上的任何 DAG。
3. 发现循环引用则阻断保存，并提示用户。

---

## 9. 数据库设计

### 9.1 新增/变更表总览

| 表名              | 变更类型 | 说明                                                 |
|-------------------|----------|------------------------------------------------------|
| `lineage_record`  | 扩展     | 新增 `source_column`、`target_column` 字段级血缘字段 |
| `dag_node`        | 扩展     | `node_type` 扩展为 SQL/SYNC/PYTHON/CONDITION/SUB_DAG |
| `node_execution`  | 扩展     | `node_type` 同步扩展                                 |
| `alert_rule`      | 新增     | 通用告警规则表                                       |
| `alert_rule_user` | 新增     | 告警规则与接收用户关联表                             |
| `alert_history`   | 新增     | 告警发送历史                                         |
| `sys_user`        | 不变     | 通过 `email` 字段校验用户是否可被选为接收人          |

### 9.2 Flyway 迁移脚本

**V3.5.0__extend_lineage_record_column.sql**

```sql
ALTER TABLE lineage_record
    ADD COLUMN IF NOT EXISTS source_column VARCHAR (255),
    ADD COLUMN IF NOT EXISTS target_column VARCHAR (255);

COMMENT
ON COLUMN lineage_record.source_column IS '源字段，字段级血缘时使用';
COMMENT
ON COLUMN lineage_record.target_column IS '目标字段，字段级血缘时使用';
```

**V3.5.1__extend_dag_node_control_flow.sql**

```sql
COMMENT
ON COLUMN dag_node.node_type IS '节点类型：SQL / SYNC / PYTHON / CONDITION / SUB_DAG';
COMMENT
ON COLUMN node_execution.node_type IS '节点类型：SQL / SYNC / PYTHON / CONDITION / SUB_DAG';

-- 若存在 CHECK 约束需调整
-- ALTER TABLE dag_node DROP CONSTRAINT IF EXISTS chk_dag_node_node_type;
-- ALTER TABLE dag_node ADD CONSTRAINT chk_dag_node_node_type CHECK (node_type IN ('SQL', 'SYNC', 'PYTHON', 'CONDITION', 'SUB_DAG'));
```

**V3.5.2__alert_rule.sql**

```sql
CREATE TABLE alert_rule
(
    id                 BIGSERIAL PRIMARY KEY,
    object_type        VARCHAR(32) NOT NULL CHECK (object_type IN ('DAG', 'SYNC_JOB', 'COLLECT_TASK')),
    object_id          BIGINT      NOT NULL,
    object_name        VARCHAR(255),
    trigger_conditions VARCHAR(255), -- JSON 数组字符串
    timeout_minutes    INT         NOT NULL DEFAULT 30,
    enabled            SMALLINT    NOT NULL DEFAULT 1,
    created_by         BIGINT,
    updated_by         BIGINT,
    created_at         TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_alert_rule_object UNIQUE (object_type, object_id)
);

COMMENT
ON TABLE alert_rule IS '通用告警规则表';
```

**V3.5.3__alert_rule_user.sql**

```sql
CREATE TABLE alert_rule_user
(
    id            BIGSERIAL PRIMARY KEY,
    alert_rule_id BIGINT NOT NULL,
    user_id       BIGINT NOT NULL,
    CONSTRAINT uk_alert_rule_user UNIQUE (alert_rule_id, user_id)
);

CREATE INDEX idx_alert_rule_user_rule_id ON alert_rule_user (alert_rule_id);
CREATE INDEX idx_alert_rule_user_user_id ON alert_rule_user (user_id);

COMMENT
ON TABLE alert_rule_user IS '告警规则接收用户关联表';
```

**V3.5.4__alert_history.sql**

```sql
CREATE TABLE alert_history
(
    id            BIGSERIAL PRIMARY KEY,
    alert_rule_id BIGINT,
    object_type   VARCHAR(32) NOT NULL,
    object_id     BIGINT      NOT NULL,
    alert_type    VARCHAR(16) NOT NULL CHECK (alert_type IN ('FAILURE', 'TIMEOUT', 'SUCCESS')),
    recipients    VARCHAR(2000),
    sent_at       TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_alert_history_object ON alert_history (object_type, object_id);
CREATE INDEX idx_alert_history_sent_at ON alert_history (sent_at);

COMMENT
ON TABLE alert_history IS '告警发送历史';
```

**V3.5.5__migrate_dag_alert_config.sql**（可选，数据迁移）

```sql
-- 将 Sprint 4 的 dag_alert_config 数据迁移到 alert_rule
-- 仅迁移按 DAG 配置的记录；全局默认配置不迁移，保留兼容读取逻辑
INSERT INTO alert_rule (object_type, object_id, trigger_conditions, timeout_minutes, enabled, created_by, updated_by,
                        created_at, updated_at)
SELECT 'DAG',
       dag_id,
       trigger_conditions,
       timeout_minutes,
       enabled,
       created_by,
       updated_by,
       created_at,
       updated_at
FROM dag_alert_config
WHERE dag_id IS NOT NULL;

-- 迁移接收人（按分号拆分邮箱反查 user_id，若找不到则不关联）
-- 此处为示意，实际迁移脚本需根据项目邮箱唯一性处理
```

---

## 10. API 接口设计

### 10.1 血缘可视化

```
GET /governance/lineage/graph?tableName={tableName}&depth={depth}
Response: LineageGraphDTO { nodes, edges }

GET /governance/lineage/columns?tableName={tableName}&columnName={columnName}
Response: List<LineageColumnLinkDTO>

GET /governance/lineage/impact?tableName={tableName}&depth={depth}
Response: LineageGraphDTO { nodes, edges }  // 下游子图

GET /governance/lineage/source?tableName={tableName}&depth={depth}
Response: LineageGraphDTO { nodes, edges }  // 上游子图
```

### 10.2 告警中心

```
GET    /system/alert-rules?page=1&pageSize=10&objectType=&keyword=
POST   /system/alert-rules
PUT    /system/alert-rules/{id}
DELETE /system/alert-rules/{id}

GET    /system/alert-rules/{id}/users
PUT    /system/alert-rules/{id}/users

GET    /system/alert-history?page=1&pageSize=10&objectType=&objectId=&alertType=

GET    /system/users/with-email?keyword=
```

### 10.3 业务模块快捷告警入口

```
PUT /engineering/sync-jobs/{id}/alert-rule
GET /engineering/sync-jobs/{id}/alert-rule

PUT /governance/collect-tasks/{id}/alert-rule
GET /governance/collect-tasks/{id}/alert-rule

PUT /engineering/dev/dags/{dagId}/alert-rule
GET /engineering/dev/dags/{dagId}/alert-rule
```

> 以上接口复用统一 `AlertRuleController`，仅对 URL 做按对象封装。

### 10.4 用户选择器

```
GET /system/users/with-email?keyword=
Response: List<UserOptionDTO> { id, username, nickname, email }
```

### 10.5 DAG 控制流内部回调

```
POST /worker/dev/internal/condition/callback
Body: { "dagId": 1, "executionId": 10, "nodeId": "n_cond_xxx" }
Response: { "branchIndex": 0 }
```

---

## 11. 前端设计

### 11.1 页面/组件清单

| 组件                     | 路径                                    | 说明                     |
|--------------------------|-----------------------------------------|--------------------------|
| `LineageGraphPage.tsx`   | `pages/governance/lineage/`             | 血缘图谱主页面           |
| `FieldLineagePanel.tsx`  | `pages/governance/lineage/components/`  | 字段级血缘面板           |
| `AlertCenterPage.tsx`    | `pages/system/alert-center/`            | 告警中心（规则+历史）    |
| `AlertRuleModal.tsx`     | `pages/system/alert-center/components/` | 告警规则弹窗             |
| `UserSelect.tsx`         | `components/`                           | 用户选择器（过滤有邮箱） |
| `ConditionNodeModal.tsx` | `pages/engineering/dags/components/`    | 条件分支节点配置弹窗     |
| `SubDagNodeModal.tsx`    | `pages/engineering/dags/components/`    | 子 DAG 节点配置弹窗      |

### 11.2 血缘图谱页面

- 使用 ReactFlow 渲染节点和边。
- 节点类型：表节点（矩形，显示库名.表名）。
- 边类型：血缘边（带方向箭头）。
- 交互：
    - 单击节点：高亮并显示节点信息浮层。
    - 双击节点：跳转表详情页。
    - 影响分析/溯源分析按钮：切换分析模式，点击节点后高亮对应路径。
    - 字段血缘入口：点击当前表节点上的「字段血缘」按钮，展开字段级面板。

### 11.3 告警中心页面

- Tab 切换：告警规则 / 告警历史。
- 规则列表：对象类型、对象名称、触发条件、接收用户、状态、操作。
- 新增/编辑弹窗：对象类型 → 对象选择 → 触发条件 → 接收用户选择器 → 超时阈值 → 启用状态。
- 告警历史列表：发送时间、对象类型、对象名称、告警类型、接收邮箱。

### 11.4 DAG 编辑器扩展

- 左侧节点面板新增「条件分支」「子 DAG」。
- 条件分支节点可配置多个分支，每个分支引出一条出线。
- 子 DAG 节点选择器列出所有已启用、非循环引用的 DAG。
- 保存时校验：
    - 条件分支至少 2 个分支。
    - 子 DAG 无循环引用。
    - 连线规则：条件分支每个分支只能连一个下游节点。

---

## 12. Sprint 5 ADR

### ADR-S5-001：血缘可视化存储方案

- **背景**：需要支持表级和字段级血缘的可视化。
- **决策**：扩展 `lineage_record` 表，新增 `source_column` / `target_column` 字段。
- **理由**：避免引入 Neo4j 等额外存储，降低部署复杂度；表级和字段级数据在同一表中便于联合查询。
- **替代方案**：新建 `lineage_column_record` 独立表。 rejected：会增加 JOIN 复杂度和数据一致性维护成本。

### ADR-S5-002：告警规则表设计

- **背景**：需要把 DAG 告警扩展为覆盖 DAG/同步任务/采集任务的通用告警。
- **决策**：新建 `alert_rule` 通用表 + `alert_rule_user` 关联表。
- **理由**：`dag_alert_config` 字段语义与 DAG 强绑定，扩展会导致表结构混乱；新表结构清晰，便于后续扩展更多对象类型和渠道。
- **兼容性**：保留 `dag_alert_config` 读取回退，并可选迁移数据。

### ADR-S5-003：告警接收人改为平台用户

- **背景**：PRD 要求收件人从手动输入邮箱改为选择平台用户。
- **决策**：`alert_rule_user` 存用户 ID，发送时反查 `sys_user.email`。
- **理由**：保证用户和邮箱的一致性，避免离职/改邮箱后告警规则失效；未填邮箱的用户不可选，确保规则有效。

### ADR-S5-004：条件分支和子 DAG 映射方案

- **背景**：需要在 DAG 中支持条件分支和子 DAG。
- **决策**：复用 DolphinScheduler 原生能力：条件分支映射为 CONDITIONS/SWITCH，子 DAG 映射为 SUB_PROCESS。
- **理由**：减少自研调度逻辑，利用 DS 成熟的状态管理和可视化能力。
- **风险**：DS 的 CONDITIONS 任务对自定义变量的支持需要验证；如不支持，可用 HTTP 任务 + worker 解释表达式作为 fallback。

### ADR-S5-005：字段级血缘解析范围

- **背景**：需要支持字段级血缘。
- **决策**：先覆盖 `INSERT INTO ... SELECT ...` 和 `CREATE TABLE AS SELECT` 中的直接列映射；复杂表达式记录表达式字符串。
- **理由**：直接列映射覆盖 80% 以上场景，实现成本可控；复杂表达式后续可逐步增强。

---

## 13. 验收标准

### 13.1 功能验收

| #     | 验收项         | 通过标准                                                             |
|-------|----------------|----------------------------------------------------------------------|
| AC-1  | 表级血缘图谱   | `GET /governance/lineage/graph` 返回正确的节点和边                   |
| AC-2  | 字段级血缘     | `GET /governance/lineage/columns` 返回正确的字段映射链路             |
| AC-3  | 影响分析       | 选中表后返回完整下游子图                                             |
| AC-4  | 溯源分析       | 选中表后返回完整上游子图                                             |
| AC-5  | 字段级血缘写入 | 执行 `INSERT INTO ... SELECT ...` 后 `lineage_record` 出现字段级记录 |
| AC-6  | 告警中心入口   | 系统管理下存在「告警中心」菜单，可查看规则和历史                     |
| AC-7  | 新增告警规则   | 可新增同步任务失败告警规则，接收用户选择平台用户                     |
| AC-8  | 用户选择器过滤 | 未填邮箱的用户不出现在选择器中                                       |
| AC-9  | 同步任务告警   | 同步任务失败时按规则发送邮件给对应用户                               |
| AC-10 | 采集任务告警   | 采集任务失败时按规则发送邮件给对应用户                               |
| AC-11 | DAG 告警兼容   | Sprint 4 配置的 DAG 告警继续生效                                     |
| AC-12 | 条件分支节点   | DAG 中可添加条件分支节点并配置多个分支                               |
| AC-13 | 条件分支执行   | DAG 执行时按表达式正确选择分支                                       |
| AC-14 | 子 DAG 节点    | DAG 中可添加子 DAG 节点并选择子 DAG                                  |
| AC-15 | 子 DAG 执行    | 父 DAG 执行时正确触发子 DAG                                          |
| AC-16 | 循环引用校验   | 保存存在循环引用的 DAG 时被阻断并提示                                |
| AC-17 | 权限隔离       | 分析师不可编辑条件分支/子 DAG；不可查看告警中心                      |

### 13.2 非功能验收

| #     | 验收项          | 通过标准                                       |
|-------|-----------------|------------------------------------------------|
| NAC-1 | 血缘图谱性能    | 100 个节点以内的图谱加载 < 3 秒                |
| NAC-2 | 字段级血缘性能  | 单表 100 个字段的字段级血缘查询 < 2 秒         |
| NAC-3 | 告警发送延迟    | 任务失败后 1 分钟内发送邮件                    |
| NAC-4 | 条件分支表达式  | 常用比较表达式解析正确率 100%                  |
| NAC-5 | 子 DAG 并发安全 | 同一子 DAG 被多个父 DAG 同时引用时执行互不干扰 |

---

## 14. 风险与对策

| #  | 风险                                    | 影响                   | 对策                                                     |
|----|-----------------------------------------|------------------------|----------------------------------------------------------|
| R1 | DS 原生 CONDITIONS 任务不支持自定义变量 | 条件分支实现复杂       | 先用 HTTP 任务 + worker 解释表达式作为 fallback          |
| R2 | 字段级血缘解析覆盖不足                  | 字段级链路缺失         | 优先覆盖直接列映射；复杂表达式记录字符串，前端展示时提示 |
| R3 | 告警规则迁移时邮箱找不到用户            | 迁移后部分规则无接收人 | 迁移脚本记录缺失用户，手动补录                           |
| R4 | 子 DAG 循环引用                         | 执行死循环             | 保存时检测循环引用并阻断                                 |
| R5 | 血缘图谱节点过多渲染卡顿                | 用户体验下降           | 默认只展示一层，支持逐层展开                             |
| R6 | 条件分支表达式注入风险                  | 表达式被恶意利用       | 限制表达式语法，只支持比较/逻辑运算，禁止方法调用        |

---

> **版本记录**
> - v1.0 (2026-08-02)：初始版本，基于 Sprint 5 PRD 与现有代码现状编写。
