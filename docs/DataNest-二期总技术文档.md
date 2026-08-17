# DataNest 二期总技术文档

> **文档状态**：定稿（技术总纲级） | **作者**：软件架构师 | **关联文档**：`DataNest-二期总PRD.md`（v1.1）、`DataNest-技术架构文档-v1.0.md`（一期架构总纲）、`docs/agent/architecture.md`（现状架构明细）
>
> 本文档定义 DataNest **二期（Sprint 13~19）** 的技术方案与关键架构决策（ADR），是二期各 Sprint 技术文档的技术底座。
> 各 Sprint 的数据模型、表结构、接口细节、Flyway 脚本由各 Sprint 技术文档承接，不在本文档展开。

---

## 目录

1. [文档定位与二期技术目标](#1-文档定位与二期技术目标)
2. [总体架构演进](#2-总体架构演进)
3. [主线一技术方案：企业级能力](#3-主线一技术方案企业级能力)
4. [主线二技术方案：实时链路深化](#4-主线二技术方案实时链路深化)
5. [主线三技术方案：DataOps 工程效能](#5-主线三技术方案dataops-工程效能)
6. [主线四技术要点：补全一期遗留](#6-主线四技术要点补全一期遗留)
7. [关键架构决策 ADR 汇总](#7-关键架构决策-adr-汇总)
8. [风险与演进](#8-风险与演进)

---

## 1. 文档定位与二期技术目标

### 1.1 定位

一期（Sprint 0~12）已完成从"模块化单体 → 微服务"的架构演进（5 业务域 + gateway + worker/job，9 个 app 容器），技术底座为：Spring Boot 4 + Spring Cloud Alibaba + Nacos + PowerJob 调度 + Doris OLAP + Iceberg/MinIO 湖仓 + Flink CDC 实时 + Sa-Token 鉴权 + MyBatis-Plus + Flyway。

二期在**不推翻现有架构**的前提下，围绕四大主线做技术增强。核心命题是：

> 在多团队、生产化、实时化场景下，让现有架构**具备租户隔离、企业级身份、数据可靠性、实时计算产品化、数据工程效能**能力，且**存量数据与部署形态无损演进**。

### 1.2 二期技术目标

| # | 目标 | 技术衡量标准 |
|---|------|--------------|
| T-G1 | 全链路多租户隔离 | 所有持库业务表具备租户维度，跨租户数据访问在数据访问层被拦截，0 泄露 |
| T-G2 | 企业级身份接入 | OIDC/OAuth2 接入可与本地 Sa-Token 会话共存、可开关 |
| T-G3 | 数据可靠性与安全 | 核心存储副本可配、元数据可备份恢复、敏感数据可脱敏/加密 |
| T-G4 | 实时计算产品化 | 流任务以 SQL 编排，复用 Flink Session 集群，复用现有监控告警底座 |
| T-G5 | 实时治理一体化 | 流 SQL 血缘自动生成，接入现有血缘图谱 |
| T-G6 | DataOps 工程效能 | 任务配置可版本化、可回滚，多环境隔离，变更可审计 |
| T-G7 | 架构演进无损 | 一期存量数据归默认租户，升级后行为不变 |

### 1.3 技术原则

- **最小侵入**：多租户等横切能力优先用框架既有机制（MyBatis-Plus 多租户插件、Sa-Token 上下文、Feign 拦截器）实现，不重造轮子。
- **复用优先**：流处理复用 Flink、血缘复用现有 SQL 解析器、监控复用 Sprint 9 指标底座、脱敏加密复用现有 AES 基建。
- **存量无损**：所有改造默认兼容一期数据与单机 Docker Compose 部署形态。
- **fail-closed 优先于静默降级**（沿用一期语义红线，尤其租户校验、敏感数据校验等写路径）。

---

## 2. 总体架构演进

### 2.1 演进概览

```
一期架构（现状）                      二期架构（目标）
──────────────────────────          ──────────────────────────────
gateway / system / alert /           （服务拓扑不变，9 个 app 容器）
engineering / governance /           + 租户维度横切所有持库表
worker / job / data-service          + 租户上下文跨服务透传
  │                                   + SSO 身份接入（Sprint 14）
  ├─ PG（6 业务库，无租户）           + Doris 多副本 / PG 备份（Sprint 15）
  ├─ Doris（共享，无命名空间）        + Doris 租户命名空间隔离
  ├─ PowerJob（任务无租户）           + PowerJob 任务/工作流租户隔离
  ├─ Flink Session 集群（仅 CDC）     + Flink SQL 流任务托管（Sprint 16-17）
  └─ MinIO（湖仓 + savepoint）        + MinIO 按租户路径隔离
```

### 2.2 二期新增横切能力清单

| 横切能力 | 落点 | Sprint |
|----------|------|--------|
| 租户维度（tenant_id） | 6 个业务库全部持库业务表 + 租户上下文透传 | 13 |
| OIDC/OAuth2 登录桥接 | system-service 认证链路 | 14 |
| 动态脱敏 / 列级加密 | 查询层 + 存储层（复用 AES） | 15 |
| Flink SQL 流任务托管 | realtime-service（复用 Flink Session 集群） | 16-17 |
| 流处理血缘 | governance-service（复用 SQL 解析器） | 16-17 |
| 任务版本/环境/CI-CD | engineering-service | 18 |

---

## 3. 主线一技术方案：企业级能力

### 3.1 多租户架构（Sprint 13）

> 对应 PRD 主线一·子模块 A（MT-01~07）。这是二期最重、风险最高的技术改造，单独成 Sprint。

#### 3.1.1 租户模型

- 新增 `tenant` 表（system 库）：`id`、`name`、`code`、`status`、`quota`（JSONB）、审计字段。
- 预置**默认租户**（`tenant_id` 固定为默认值），一期存量数据全部归默认租户。
- **租户语义 = 组织/公司**（P2-D12）：一个企业一个租户，组织内再按团队/角色划分。
- **单账号多租户可切换**（P2-D7）：新增 `sys_user_tenant` 关联表（user_id ↔ tenant_id，多对多）；登录后进入默认租户、可切换当前租户；租户上下文写入 Sa-Token 会话与 ThreadLocal。
- **租户内数据权限沿用一期策略**（P2-D13）：默认全量可见、按需收缩；租户边界为硬隔离、租户内为软收缩。

#### 3.1.2 数据隔离策略（ADR-012）

**决策：行级隔离（tenant_id 列），采用 MyBatis-Plus 多租户插件 `TenantLineInnerInterceptor` + `TenantLineHandler`。**

| 方案 | 优点 | 缺点 | 结论 |
|------|------|------|------|
| A 行级隔离（tenant_id 列） | 改动可控，共享连接池，与现有 6 库分域模型兼容，查询透明 | 每张表加列 + 索引；自定义 SQL 需手动加租户条件 | ✅ **采纳** |
| B schema 隔离（每租户独立 schema） | 隔离彻底，天然物理隔离 | 与 Flyway"每库独立迁移"模型冲突，schema 数量随租户膨胀，Doris 无法对齐 | ❌ |
| C database 隔离（每租户独立库） | 物理隔离最强 | 租户动态建库、连接池爆炸、运维复杂，与单机部署定位不符 | ❌ |

**实施要点**：

- 6 个业务库全部持库业务表加 `tenant_id`（bigint，非空，加复合索引 `(tenant_id, ...)` 高频查询键）。
- 系统库（`datanest_system`）中 `tenant` 表本身、`sys_user` 等**平台级**表也带 tenant_id（租户内用户管理）；真正的平台全局表（如 tenant 表）由超管维度访问，不加租户过滤。
- `TenantLineHandler` 从租户上下文取当前 tenant_id，自动拼接 `WHERE tenant_id = ?`；**跨租户查询显式使用"忽略租户"上下文**（仅超管跨租户审计等少数场景）。
- **自定义 SQL（@Select/@Update 手写）**：MP 多租户插件对非 MP 生成的 SQL 解析有限，需人工补 tenant_id 条件——这是迁移的最大工作量与风险点（见 §8 R1）。

#### 3.1.3 租户上下文与跨服务透传（ADR-013）

- 登录成功后进入**默认租户**，租户上下文写入 **Sa-Token 会话**（当前 `tenant_id`）与 **ThreadLocal 租户上下文**（`TenantContext`）；切换租户时更新当前 tenant_id。
- 网关/服务间请求：新增 `X-Tenant-Id` 头，由 common 的 Feign 拦截器自动透传（对齐现有 `X-Internal-Token` 机制）。
- **异步执行（worker/job）**：DAG/同步/质量/采集等执行任务的记录里已存创建人，补充存 `tenant_id`；worker 执行与回写时从任务记录读取租户上下文，避免依赖请求线程的 ThreadLocal。
- 租户上下文校验放 fail-closed：租户停用或 tenant_id 不合法时，写路径拒绝操作。

#### 3.1.4 共享引擎租户隔离（ADR-014）

| 引擎 | 隔离方案 |
|------|----------|
| **Doris**（内置 OLAP） | 每租户独立 **database 命名空间**（如 `t{tenantId}` 前缀库，或租户专属 catalog）；建表/查询的连接级限定当前租户库，禁止跨租户 database/catalog 访问。内置 Doris 是共享实例，靠命名空间 + 连接校验隔离 |
| **PowerJob**（调度） | 任务/工作流注册时打租户标签或在命名中携带租户前缀；执行回写时校验租户，避免跨租户触发/查询执行历史 |
| **MinIO**（湖仓 + savepoint） | 路径按租户分桶/前缀（`s3://warehouse/{tenantId}/...`），Iceberg catalog 与 savepoint 目录均带租户前缀 |
| **Redis**（会话/限流/锁） | key 带租户前缀（限流、锁等按租户隔离）；Sa-Token 会话本身按用户隔离 |

#### 3.1.5 存量迁移方案

- Flyway 脚本：各库新增 `tenant_id` 列 → 回填默认租户 → 建索引 → 加非空约束。
- 迁移采用**单脚本原子执行**，紧凑单行风格（对齐 gotchas 约定），版本号大于各库当前最高版本。
- 迁移后一期所有功能在默认租户下行为不变（T-G7）。

### 3.2 SSO 身份接入（Sprint 14）

> 对应 PRD 主线一·子模块 B（SSO-01~05）。

**方案：OIDC/OAuth2 授权码流程认证 + 本地 Sa-Token 会话落地（ADR-015）。**

```
用户 → 点击 SSO 登录 → 跳转 IdP（Keycloak/Auth0/OIDC 标准）
     → IdP 授权回调（Authorization Code）
     → system-service 校验 code / 验签 id_token
     → 按 subject 自动建号或绑定已有 sys_user（首次登录）
     → 角色映射（IdP group/claim → DataNest 角色 + 租户）
     → 建立 Sa-Token 本地会话（复用现有 Token/会话机制）
```

- **与本地登录共存**：`sys_user` 增加认证来源（LOCAL/OIDC/LDAP）；可配置"仅 SSO"或"混合登录"。
- **自动建号**：IdP 首次登录按唯一 subject 自动创建用户并绑定租户（租户来源：claim 或默认租户，配置化）。
- **角色映射**：OIDC 的 group/role claim → DataNest 角色（预置 + 自定义角色），映射规则存 Nacos 或配置表。
- **密码策略强化**：本地账号密码复杂度、过期、登录失败锁定（复用 Sa-Token 会话 + Redis 计数）。

### 3.3 高可用与数据安全（Sprint 15）

> 对应 PRD 主线一·子模块 C/D（HA-01~04、DS-01~04）。

#### 3.3.1 高可用（数据可靠性定位）

- **Doris 多副本**：建表 `replication_num` 可配（默认 1，生产建议 ≥2）；单 BE 节点故障数据不丢（SE-09）。
- **PG 备份恢复**：定时 `pg_dump`（或 `pg_basebackup`）到 MinIO，保留 N 份；提供恢复演练脚本与文档（HA-02）。
- **配置备份**：Nacos 配置导出备份（HA-03）。
- **服务自愈**：compose `restart: unless-stopped` + 健康探活（一期已具备，补齐验收）。

#### 3.3.2 数据安全

- **动态脱敏（DS-01）**：查询层按角色/敏感度脱敏（手机号/身份证/邮箱等），复用一期分级分类（sensitivity）标记，SQL 终端/数据 API 出口统一脱敏。
- **列级加密（DS-02）**：仅敏感列加密（P2-D9），复用一期数据源密码的 **AES-256 加密基建**，密钥走 Nacos 配置；加密列落库为密文，授权用户查询可解密。
- **数据申请审批流（DS-03）**：新增申请/审批实体，审批通过后按"临时/长期"授权，到期自动回收；审批留审计。
- **审计合规加强（DS-04）**：敏感数据访问、导出、授权等敏感操作接入一期审计日志体系。

---

## 4. 主线二技术方案：实时链路深化

> 对应 PRD 主线二（ST-01~06、LB-01~02、RQ-01~03）。

### 4.1 流处理 SQL 架构（Sprint 16）

**方案：复用 Flink 2.2.1 Session 集群，流任务以"Flink SQL 作业"形态托管（ADR-017，对齐 P2-D6）。**

```
流任务定义（向导式）
  ├─ 源：CDC 管道 / 实时表（Iceberg/Doris）
  ├─ 处理：Flink SQL（TUMBLE/HOP/SESSION 窗口、聚合、JOIN、MATCH_RECOGNIZE CEP）
  └─ 目标：湖仓（Iceberg）/ Doris 表
        │
        ▼ 组装 Flink SQL 脚本
realtime-service ──REST 提交──▶ Flink Session 集群
        │
        ▼ 复用 Sprint 9 监控/告警底座
指标历史（延迟/吞吐/重启）· Checkpoint/Savepoint · STREAM_JOB 告警
```

- **流任务实体**：新增 `stream_job` 表（realtime 库），存源/目标/流 SQL/状态，复用 CDC 管道的生命周期语义（创建/提交/启停/删除级联）。
- **SQL 窗口与 CEP**：Flink SQL 原生支持 TUMBLE/HOP/SESSION 窗口与 MATCH_RECOGNIZE（CEP），无需自研。
- **多源 JOIN（ST-06）**：流表 JOIN 维度表（Doris/湖仓外表）。
- **监控复用**：Sprint 9 的 `CdcMonitorService` 指标历史（分钟降采样）+ Flink REST（vertex 指标 double、取消用 PATCH 等已知坑见 gotchas）复用到流任务。
- **告警复用**：app-alert 新增 `STREAM_JOB` 对象类型，触发条件=作业失败/延迟超阈值/外部停止。

### 4.2 流处理血缘（Sprint 16-17）

**方案：流 SQL 复用现有 SQL 血缘解析器（ADR-018）。**

- 流任务 SQL 与离线 SQL 走**同一套 SQL 血缘解析器**（一期 `LineageInterceptor`/JSqlParser 解析），产出表级/字段级血缘写入现有 `lineage_record`。
- 流批血缘融合：实时表 → 下游离线表的混合链路在同一血缘图谱展示（LB-02）。

### 4.3 实时数据质量与观测（Sprint 17）

- **实时数据质量（RQ-01）**：流处理链路嵌入质量校验（空值/唯一/阈值），复用一期质量体系（规则模板/批次/明细），异常告警复用 app-alert。
- **背压观测（RQ-02）**：Flink 背压/消费滞后指标可视化（复用 Flink REST metrics）。
- **外部监控对接（RQ-03）**：可选暴露 Prometheus metrics 端点，对接 Grafana（一期 NG7）。

---

## 5. 主线三技术方案：DataOps 工程效能

> 对应 PRD 主线三（DO-01~06）。

| 能力 | 技术方案 | 复用点 |
|------|----------|--------|
| 任务版本管理完整版（DO-01） | `dag_version` 表已有，补版本差异对比（diff）与回滚（写回指定版本） | 复用 dag_version 表与 DagVersionService |
| 环境管理（DO-02） | DAG/同步任务加 `environment`（dev/test/prod）维度；环境隔离配置（数据源/连接/资源） | 复用数据源连接与调度体系 |
| 数据 CI-CD（DO-03） | 变更流水线（草稿→审批→发布），发布审批留审计 | 复用审计日志体系 |
| 补数据与重跑（DO-04） | 按时间范围补跑，失败任务批量重跑 | 复用 PowerJob 触发与执行历史 |
| 数据可观测性（DO-05） | 平台运行指标聚合看板 + 告警 | 复用首页聚合 + app-alert |
| 资源管理（DO-06） | JAR/Python 依赖/配置文件上传与版本化 | 复用 MinIO 对象存储 |

**环境模型（ADR-019）**：环境为任务级维度，同一任务可绑定不同环境的数据源/连接；发布从 dev → test → prod 逐级审批。

---

## 6. 主线四技术要点：补全一期遗留

> 对应 PRD 主线四（LF-01~13），碎片项，按优先级穿插交付。

| 遗留项 | 技术要点 | 复用点 |
|--------|----------|--------|
| API 数据源接入（LF-01） | 新增 API 数据源类型，RESTful/GraphQL 拉取 → 落湖仓/Doris | 复用数据源管理 + 同步任务引擎 |
| 物化视图（LF-02） | Doris 物化视图创建/管理，预聚合加速 | Doris 原生能力 |
| 联邦查询（LF-03） | Doris 外表（Iceberg/外部 JDBC Catalog）跨源查询 | Doris Multi-Catalog |
| 补数据/重跑完整版（LF-04） | 时间范围补跑 + 批量重跑 | PowerJob 触发 |
| 存储用量统计（LF-05） | 各表/库存储用量与增长趋势 | Doris `information_schema` + 指标历史 |
| GraphQL（LF-06） | 数据 API 支持 GraphQL 形态 | 复用数据 API 定义与网关 |
| 数据沙箱（LF-07） | 隔离探索环境，确认后发布 | 复用环境管理（DO-02） |
| 查询加速缓存（LF-08） | 常用查询结果缓存 | Redis |
| 数据使用分析（LF-09） | 表访问/使用统计 | 复用 asset_view_log |
| 更多数据源（LF-10） | MongoDB/Kafka/ES 数据源 | 复用数据源抽象 |

---

## 7. 关键架构决策 ADR 汇总

> 延续一期 `DataNest-技术架构文档-v1.0.md` 的 ADR-001~011 编号。

### ADR-012：多租户数据隔离 —— 行级隔离（tenant_id 列）

| 项目 | 内容 |
|------|------|
| **状态** | Accepted |
| **上下文** | 二期需完整多租户（P2-D5），在现有 6 业务库分域模型上叠加租户隔离 |
| **决策** | **行级隔离**：全部持库业务表加 `tenant_id`，MyBatis-Plus `TenantLineInnerInterceptor` 自动过滤；共享引擎（Doris/PowerJob/MinIO/Redis）按命名空间/前缀隔离 |
| **替代方案** | schema 隔离（与 Flyway 分域模型冲突、Doris 无法对齐）；database 隔离（动态建库、连接池爆炸） |
| **后果** | 📈 改动可控、连接池共享、存量无损；📉 手写 SQL 需人工补租户条件（迁移工作量大，见 §8 R1） |

### ADR-013：租户上下文透传 —— Sa-Token 会话 + Feign 头

| 项目 | 内容 |
|------|------|
| **状态** | Accepted |
| **上下文** | 跨服务调用与异步执行需要携带租户上下文 |
| **决策** | 登录后进入默认租户，租户上下文写入 Sa-Token 会话 + `TenantContext` ThreadLocal，支持切换当前租户；跨服务经 `X-Tenant-Id` 头由 Feign 拦截器透传；异步执行（worker/job）从任务记录读取 tenant_id |
| **后果** | 📈 对齐现有 X-Internal-Token 机制，实现一致；📉 异步链路需补齐 tenant_id 落库与读取 |

### ADR-014：共享引擎租户隔离

| 项目 | 内容 |
|------|------|
| **状态** | Accepted |
| **上下文** | Doris/PowerJob/MinIO/Redis 是共享实例，需按租户隔离 |
| **决策** | Doris 租户专属 database 命名空间 + 连接级校验；PowerJob 任务/工作流带租户标识 + 回写校验；MinIO 路径按租户前缀；Redis key 按租户前缀 |
| **后果** | 📈 共享实例不拆分、成本低；📉 命名空间/前缀约定需全链路贯彻，连接级校验是关键防线 |

### ADR-015：SSO 桥接 —— OIDC 认证 + 本地 Sa-Token 会话

| 项目 | 内容 |
|------|------|
| **状态** | Accepted |
| **上下文** | 二期接入企业级 SSO（P2-D8），需与现有 Sa-Token 登录共存 |
| **决策** | OIDC/OAuth2 授权码流程完成外部认证，回调后建立本地 Sa-Token 会话；首次登录自动建号绑定租户；角色经 IdP group/claim 映射 |
| **后果** | 📈 复用现有 Token/会话/权限体系，改动集中；📉 需处理 IdP 与本地账号的绑定/冲突/账号禁用同步 |

### ADR-016：列级加密与动态脱敏

| 项目 | 内容 |
|------|------|
| **状态** | Accepted |
| **上下文** | 二期数据安全（P2-D9），需保护敏感数据 |
| **决策** | **仅敏感列加密**（复用一期 AES-256 基建，密钥走 Nacos）；查询出口按角色/敏感度动态脱敏 |
| **后果** | 📈 复用加密基建、改动可控；📉 加密列查询/排序/索引受限，需明确加密列使用边界 |

### ADR-017：流处理 SQL —— 复用 Flink SQL 作业托管

| 项目 | 内容 |
|------|------|
| **状态** | Accepted |
| **上下文** | 二期实时链路深化（P2-D6），需流处理 SQL（窗口/CEP）能力 |
| **决策** | 流任务以"Flink SQL 作业"形态托管到已内嵌 Flink 2.2.1 Session 集群；可视化编排生成 Flink SQL → REST 提交；复用 Sprint 9 监控告警底座 |
| **后果** | 📈 不自研流引擎、复用 Flink SQL 全能力；📉 Flink SQL 作业的产品化（SQL 校验/UDF/状态恢复）需封装，复用 Checkpoint/Savepoint 底座 |

### ADR-018：流处理血缘 —— 复用现有 SQL 血缘解析器

| 项目 | 内容 |
|------|------|
| **状态** | Accepted |
| **上下文** | 流任务 SQL 需自动产出血缘（P2-G5） |
| **决策** | 流 SQL 与离线 SQL 走同一套 SQL 血缘解析器，写入现有 lineage_record，流批血缘融合展示 |
| **后果** | 📈 零新增解析器、血缘闭环；📉 流 SQL 方言（窗口/CEP 语法）需验证解析器覆盖度，缺口补丁化处理 |

### ADR-019：DataOps 环境模型

| 项目 | 内容 |
|------|------|
| **状态** | Accepted |
| **上下文** | 二期 DataOps（主线三），需多环境隔离与发布管控 |
| **决策** | 环境为任务级维度（dev/test/prod），任务绑定环境的数据源/连接；发布走草稿→审批→发布流水线，审批留审计 |
| **后果** | 📈 复用现有 DAG/数据源/审计体系；📉 环境配置一致性需治理，避免环境间配置漂移 |

---

## 8. 风险与演进

| # | 风险 | 影响 | 缓解 |
|---|------|------|------|
| R1 | **多租户手写 SQL 改造面大**：MP 多租户插件对 @Select/@Update 手写 SQL 覆盖有限，需逐条补 tenant_id | 工期长、漏改引发跨租户泄露 | 全量盘点手写 SQL 清单；Sprint 13 单独成 Sprint；E2E 加跨租户越权用例；code review 逐条核对 |
| R2 | **Doris 租户命名空间与连接校验**：共享实例一旦连接级校验有缺口即泄露 | 数据泄露 | 连接级强制限定租户库 + 禁止跨库 catalog；E2E 跨租户查询必测 |
| R3 | **异步链路租户上下文丢失**：worker/job 执行不依赖请求线程，易漏 tenant_id | 越权/错乱 | 任务记录落 tenant_id，执行与回写统一从记录读取；job 对账兜底 |
| R4 | **Flink SQL 产品化复杂度**：SQL 校验、UDF、状态恢复对用户透明度 | 流任务可用性 | 复用 Sprint 9 Checkpoint/Savepoint 底座；模板化 SQL；流 SQL 方言覆盖度预验证 |
| R5 | **单机部署高可用边界**：分布式 HA 超出单机定位 | 期望错位 | 二期 HA = 数据可靠性（副本 + 备份），分布式扩展留未来 |

---

## 附录 A：文档修订记录

| 版本 | 日期 | 修订内容 | 作者 |
|------|------|----------|------|
| v1.0 | 2026-08-17 | 初始版本：二期四大主线技术方案、ADR-012~019、风险与演进 | 软件架构师 |
| v1.1 | 2026-08-17 | 同步 P2-D7/D12/D13 决策：租户=组织、单账号多租户可切换（sys_user_tenant 关联表）、租户内权限默认最小；新增 R6 风险 | 软件架构师 |
| v1.2 | 2026-08-17 | 租户内权限默认策略改回**默认全量可见、按需收缩**（P2-D13 修正），移除 R6 风险 | 软件架构师 |
