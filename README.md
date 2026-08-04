# DataNest

> 开源一站式数据中台 —— 开发治理一体化。
>
> 当前仓库已完成 **Sprint 0（用户与权限）**、 **Sprint 1（数据源连接 + 元数据采集管理）**、 **Sprint 2（批量数据同步 +
> 数据标准管理）**、 **Sprint 3（DAG 编排 + SQL 任务编辑器）**、 **Sprint 4（Python 节点 / 参数化 / 监控告警 / 血缘上报 /
> 版本管理）** 与 **Sprint 5（血缘可视化 + 全局告警中心 + DAG 控制流增强）** 的核心能力，后续 Sprint 正在迭代中。

---

## 项目愿景

让数据团队在一个平台内完成 **“接数据 → 采元数据 → 管元数据 → 同步数据 → 治理数据 → 编排调度 → 可视追踪”**
的闭环，先解决“数据源散落、元数据靠文档维护、任务编排靠人肉、血缘链路看不清”的痛点。

---

## 当前已交付能力

### Sprint 0：用户体系与平台骨架

- 基于 Sa-Token + JWT 的统一登录鉴权
- 4 个预置角色：超级管理员 / 数据工程师 / 数据分析师 / 治理管理员
- 用户管理：创建、编辑、禁用、重置密码、修改密码
- 基于角色的菜单与 API 权限控制
- 后端 Maven 多模块骨架、Nacos 注册与配置中心、前端 React + Vite 骨架

### Sprint 1：数据源连接与元数据采集

- 数据源管理（engineering-service）：新增 / 编辑 / 删除 / 测试连接 MySQL、PostgreSQL、Doris、Oracle、SQL Server 数据源
- 数据源密码 AES 加密落库，前端脱敏展示
- 元数据采集任务（governance-service）：创建 / 编辑 / 删除 / 手动执行 / Cron 定时
- 采集模式：全量采集、全量+增量
- 执行历史与执行日志
- 元数据管理：数据源 → 库/Schema → 表 → 字段 的树形浏览，表/字段注释可编辑
- 集成 XXL-JOB 作为统一调度中心

### Sprint 2：批量数据同步与数据标准

- 批量数据同步任务（engineering-service + worker）：基于 Addax 从任意支持的源库同步到内置 Doris
- 支持全量同步与增量同步（按增量字段）
- 同步执行历史、日志与 Doris 目标表元数据自动注册
- 数据标准管理：命名规范、字段类型标准、合规检查

### Sprint 3：DAG 编排与 SQL 任务编辑器

- 引入 **DolphinScheduler 3.4.2** 作为 DAG 调度与执行引擎（Master / Worker / API / Alert 分离架构）
- DAG 项目管理：项目维度隔离 DAG 流水线
- DAG 编辑器：ReactFlow 画布拖拽编排 + Monaco SQL 编辑器，支持 SQL 节点定义与执行
- SQL 预览、执行历史与节点执行日志
- 新增 `data-nest-task-core` 共享模块，承载 DAG 领域模型与执行映射

### Sprint 4：Python 节点、参数化、监控告警与血缘上报

- **Python 任务节点**：DAG 画布支持 PYTHON 节点，脚本在 worker 隔离进程中执行
- **DAG 级参数化**：自定义参数 + 系统变量，SQL/Python 节点通过 `${paramName}` 占位符替换
- **监控与邮件告警**：DAG 失败 / 成功 / 节点超时邮件通知，告警配置支持按 DAG 覆盖、无专属配置时回退全局默认
- **SQL 血缘自动上报**：SQL 节点执行成功后解析 source → target 血缘并写入 `lineage_record`
- **DAG 版本管理**：保存即生成版本快照，支持对比与回滚
- **重跑失败节点**：仅重跑 FAILED/SKIPPED 节点（替代全量重跑）
- **多表同步 + 速率限流**：同步任务支持多表字段映射与限流配置
- 节点执行收敛到 `data-nest-worker`，公共能力（参数解析、同步触发、互斥锁、节点日志）下沉到 task-core

### Sprint 5：血缘可视化、全局告警中心与 DAG 控制流增强

- **血缘可视化**：元数据详情页一键打开血缘图谱（ReactFlow），支持表级图谱、字段级血缘下钻、影响分析、溯源分析
- **全局告警中心**：系统管理下统一管理 DAG / 同步任务 / 采集任务的邮件告警规则（通用 `alert_rule` 表），支持规则
  CRUD、启停、收件人选择、发送历史（含发送状态）；DAG 编辑器、同步任务、采集任务保留模块快捷入口
- **DAG 控制流增强**：
  - 条件分支节点（CONDITION）：按表达式选择下游路径，未命中分支自动 SKIPPED
  - 子 DAG 节点（SUB_DAG）：将子流程封装为可复用流水线，支持同步 / 异步执行，限制同项目引用并检测循环依赖
- Sprint 5 API + E2E 测试 **85/85 通过**（Playwright，基建保留在 `data-nest-frontend/e2e/sprint5/`）

---

## 技术栈

| 层级          | 技术                                                                              |
|---------------|-----------------------------------------------------------------------------------|
| 后端框架      | JDK 21、Spring Boot 4.0.7、Spring Cloud 2025.1.2、Spring Cloud Alibaba 2025.1.0.0 |
| 网关与鉴权    | Spring Cloud Gateway、Sa-Token、JWT                                               |
| ORM 与迁移    | MyBatis-Plus、Flyway                                                              |
| 注册/配置中心 | Nacos 3.1.1                                                                       |
| 任务调度      | XXL-JOB 3.4.2（平台定时任务）、DolphinScheduler 3.4.2（DAG 编排执行）             |
| 数据库        | PostgreSQL 16（业务元数据）、MySQL 8.0（Nacos + XXL-JOB + DolphinScheduler）      |
| 数仓/目标库   | Apache Doris（外部部署，同步任务目标端）                                          |
| 缓存/会话     | Redis 7                                                                           |
| 前端          | React 18、TypeScript、Vite 5、Tailwind CSS、ReactFlow 11、Monaco Editor           |
| 邮件测试      | MailHog（本地 SMTP 测试）                                                         |
| 部署          | Docker、Docker Compose                                                            |

---

## 仓库结构

```
Data Platform/
├── docs/                          # 产品/架构/Sprint 文档
│   ├── DataNest-产品规格文档-v1.0.md
│   ├── DataNest-技术架构文档-v1.0.md
│   ├── sprint0/
│   ├── sprint1/
│   ├── sprint2/
│   ├── sprint3/
│   ├── sprint4/
│   └── sprint5/
│
└── data-nest/                     # 工程代码
    ├── pom.xml                    # Maven 根 POM
    ├── docker-compose.yml         # 一键部署配置
    ├── shared-configs/            # Nacos 共享配置（shared-*.yaml）
    ├── scripts/                   # 数据库初始化等脚本
    │
    ├── data-nest-common/          # 公共模型、异常、工具
    ├── data-nest-gateway/         # API 网关、登录入口、JWT 鉴权
    ├── data-nest-system/          # 用户、角色、权限、告警中心管理
    ├── data-nest-engineering/     # 数据源管理 + 同步任务 + DAG 编排管理
    ├── data-nest-task-core/       # 共享核心：DAG 模型、节点执行、血缘、告警服务
    ├── data-nest-governance/      # 元数据采集 + 元数据管理 + 数据标准 + 血缘查询
    ├── data-nest-worker/          # 任务执行器（SQL/Python/同步节点、DAG 回调）
    ├── data-nest-job/             # 平台定时任务执行器（XXL-JOB）
    │
    └── data-nest-frontend/        # React 前端
```

---

## 快速启动

### 环境要求

- Docker + Docker Compose
- JDK 21
- Maven 3.9+
- Node.js 18+ + npm

### 1. 编译后端

```bash
cd data-nest
mvn clean install -DskipTests
```

### 2. 编译前端

```bash
cd data-nest/data-nest-frontend
npm install
npm run build
```

### 3. 一键启动

```bash
cd data-nest
docker-compose up -d
```

> 说明：DolphinScheduler（API / Master / Worker / Alert + Zookeeper）、Nacos、XXL-JOB、MailHog 等中间件均随
> `docker-compose.yml` 一键拉起；首次启动会执行数据库初始化脚本，等待所有容器进入 `healthy` 状态即可。

### 4. 访问系统

- 前端：`http://localhost:3000`
- 默认管理员账号：`admin / admin123`
- XXL-JOB 控制台：`http://localhost:8088`（默认 admin / 123456）
- Nacos 控制台：`http://localhost:8848`（默认 nacos / nacos）
- DolphinScheduler 控制台：`http://localhost:12345`（默认 admin / dolphinscheduler123）
- MailHog 邮件测试：`http://localhost:8025`（SMTP 端口 1025）

---

## 主要服务与端口

### 平台服务

| 服务        | 容器名                     | 对外端口 | 说明                       |
|-------------|----------------------------|----------|----------------------------|
| Gateway     | `datanest-app-gateway`     | 8080     | 统一 API 入口              |
| System      | `datanest-app-system`      | -        | 用户/权限/告警中心（内网） |
| Engineering | `datanest-app-engineering` | -        | 数据源/同步/DAG（内网）    |
| Governance  | `datanest-app-governance`  | -        | 元数据/血缘（内网）        |
| Worker      | `datanest-app-worker`      | -        | 任务执行器（内网）         |
| Job         | `datanest-app-job`         | -        | 平台定时任务（内网）       |
| Frontend    | `datanest-app-frontend`    | 3000     | Nginx 托管前端             |

> 内部微服务不直接暴露端口，统一通过 Gateway `8080` 访问。

### 中间件

| 服务             | 容器名                          | 端口        | 说明                         |
|------------------|---------------------------------|-------------|------------------------------|
| Nacos            | `datanest-middleware-nacos`     | 8848 / 9848 | 注册/配置中心                |
| XXL-JOB Admin    | `datanest-middleware-xxljob`    | 8088        | 平台定时任务调度中心         |
| PostgreSQL       | `datanest-middleware-postgres`  | 5432        | 业务数据库                   |
| MySQL            | `datanest-middleware-mysql`     | 3306        | Nacos + XXL-JOB + DS 数据库  |
| Redis            | `datanest-middleware-redis`     | 6379        | 会话/缓存                    |
| DolphinScheduler | `datanest-middleware-ds-api` 等 | 12345       | DAG 编排执行引擎（API 入口） |
| Zookeeper        | `datanest-middleware-zookeeper` | 2181        | DolphinScheduler 依赖        |
| MailHog          | `datanest-mailhog`              | 1025 / 8025 | 本地 SMTP（发送）/ Web UI    |

### 测试目标库

| 服务            | 容器名                              | 端口 | 说明              |
|-----------------|-------------------------------------|------|-------------------|
| Test MySQL      | `datanest-middleware-test-mysql`    | 3307 | MySQL 测试库      |
| Test PostgreSQL | `datanest-middleware-test-postgres` | 5433 | PostgreSQL 测试库 |

> Test Oracle / Test SQL Server 测试库当前已在 docker-compose 中注释停用，需要时按需启用。

---

## 数据库连接信息

### 平台数据库

#### PostgreSQL 业务数据库

| 项       | 值                                          |
|----------|---------------------------------------------|
| Host     | `localhost`                                 |
| Port     | `5432`                                      |
| Database | `datanest`                                  |
| Username | `datanest`                                  |
| Password | `datanest123`                               |
| JDBC URL | `jdbc:postgresql://localhost:5432/datanest` |

#### Nacos + XXL-JOB + DolphinScheduler MySQL

| 项       | 值                                                  |
|----------|-----------------------------------------------------|
| Host     | `localhost`                                         |
| Port     | `3306`                                              |
| Database | `nacos` / `datanest_scheduler` / `dolphinscheduler` |
| Username | `nacos`                                             |
| Password | `nacos123`                                          |

#### 内置 Doris（外部部署）

| 项       | 值                                           |
|----------|----------------------------------------------|
| Host     | `192.168.119.135`                            |
| Port     | `9030`（查询）/ `8030`（HTTP）               |
| Database | `datanest` / `ods` 等                        |
| Username | `root`                                       |
| Password | `password`                                   |
| JDBC URL | `jdbc:mysql://192.168.119.135:9030/datanest` |

> Doris 为外部主机，不在 docker-compose 中；部署/清理环境时重启容器不影响 Doris。

### 测试目标库

#### Test MySQL

| 项       | 值                                   |
|----------|--------------------------------------|
| Host     | `localhost`                          |
| Port     | `3307`                               |
| Database | `testdb`                             |
| Username | `testuser`                           |
| Password | `testpass123`                        |
| Schema   | `testdb`                             |
| JDBC URL | `jdbc:mysql://localhost:3307/testdb` |

#### Test PostgreSQL

| 项       | 值                                          |
|----------|---------------------------------------------|
| Host     | `localhost`                                 |
| Port     | `5433`                                      |
| Database | `postgres`                                  |
| Username | `postgres`                                  |
| Password | `postgres123`                               |
| Schema   | `public`                                    |
| JDBC URL | `jdbc:postgresql://localhost:5433/postgres` |

---

## 角色与权限

| 能力             | 超级管理员 | 数据工程师 | 治理管理员 | 数据分析师 |
|------------------|:----------:|:----------:|:----------:|:----------:|
| 用户管理         |     ✅     |     ❌     |     ❌     |     ❌     |
| 数据源管理       |     ✅     |     ✅     |    只读    |     ❌     |
| 元数据采集任务   |     ✅     |     ❌     |     ✅     |     ❌     |
| 查看元数据       |     ✅     |     ✅     |     ✅     |     ✅     |
| 编辑表/字段注释  |     ✅     |     ❌     |     ✅     |     ❌     |
| 执行采集任务     |     ✅     |     ❌     |     ✅     |     ❌     |
| 批量数据同步任务 |     ✅     |     ✅     |     ❌     |     ❌     |
| 数据标准管理     |     ✅     |     ❌     |     ✅     |     ❌     |
| DAG 编排管理     |     ✅     |     ✅     |     ❌     |     ❌     |
| 血缘图谱查看     |     ✅     |     ✅     |     ✅     |     ✅     |
| 告警中心查看     |     ✅     |     ✅     |     ✅     |     ❌     |
| 告警规则编辑     |     ✅     |     ✅     |     ❌     |     ❌     |

---

## 常用开发命令

```bash
# 查看容器状态
cd data-nest && docker-compose ps

# 查看日志
docker-compose logs -f app-gateway app-system app-engineering app-governance app-worker app-job

# 重启前端（开发模式）
cd data-nest/data-nest-frontend
npm run dev

# 重新构建并部署前后端
cd data-nest
mvn clean install -DskipTests
cd data-nest-frontend && npm run build
cd ..
docker-compose up -d --build app-gateway app-system app-engineering app-governance app-worker app-job app-frontend

# 回归测试（Playwright E2E，基建在 e2e/ 目录）
cd data-nest/data-nest-frontend
npx playwright test --project=chromium --timeout=300000
```

> 注意：改动 `data-nest-task-core` 后，需重新编译并部署 engineering / worker / governance / system / job 全部服务。

---

## 时区

所有容器已统一配置为中国时区（`Asia/Shanghai`）：

- Java 服务通过 `TZ` 环境变量 + `JAVA_TOOL_OPTIONS=-Duser.timezone=Asia/Shanghai` 双重保证
- PostgreSQL 与 MySQL 容器通过 `TZ` 及数据库时区参数配置

---

## 后续路线

- 数据资产目录、数据服务等能力持续迭代中。

详细路线图与 PRD 见 `docs/` 目录。

---

## 文档索引

- 产品总纲：`docs/DataNest-产品规格文档-v1.0.md`
- 架构总纲：`docs/DataNest-技术架构文档-v1.0.md`
- Sprint 0 PRD：`docs/sprint0/DataNest-Sprint0-用户与权限管理-PRD.md`
- Sprint 0 技术文档：`docs/sprint0/DataNest-Sprint0-技术文档.md`
- Sprint 1 PRD：`docs/sprint1/DataNest-Sprint1-数据源连接与元数据采集-PRD.md`
- Sprint 1 技术文档：`docs/sprint1/DataNest-Sprint1-技术文档.md`
- Sprint 2 PRD：`docs/sprint2/DataNest-Sprint2-批量数据同步与数据标准-PRD.md`
- Sprint 2 技术文档：`docs/sprint2/DataNest-Sprint2-技术文档.md`
- Sprint 3 PRD：`docs/sprint3/DataNest-Sprint3-DAG编排与SQL任务编辑器-PRD.md`
- Sprint 3 技术文档：`docs/sprint3/DataNest-Sprint3-技术文档.md`
- Sprint 4 PRD：`docs/sprint4/DataNest-Sprint4-PRD.md`
- Sprint 4 技术文档：`docs/sprint4/DataNest-Sprint4-技术文档.md`
- Sprint 5 PRD：`docs/sprint5/DataNest-Sprint5-PRD.md`
- Sprint 5 技术文档：`docs/sprint5/DataNest-Sprint5-技术文档.md`
