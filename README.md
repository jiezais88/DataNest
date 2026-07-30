# DataNest

> 开源一站式数据中台 —— 开发治理一体化。
>
> 当前仓库已完成 **Sprint 0（用户与权限）**、 **Sprint 1（数据源连接 + 元数据采集管理）** 与 **Sprint 2（批量数据同步 +
数据标准管理）** 的核心能力，Sprint 3 及以后内容正在迭代中。

---

## 项目愿景

让数据团队在一个平台内完成 **“接数据 → 采元数据 → 管元数据 → 同步数据 → 治理数据”** 的闭环，先解决“数据源散落、元数据靠文档维护”的痛点。

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

---

## 技术栈

| 层级          | 技术                                                                              |
|---------------|-----------------------------------------------------------------------------------|
| 后端框架      | JDK 21、Spring Boot 4.0.7、Spring Cloud 2025.1.2、Spring Cloud Alibaba 2025.1.0.0 |
| 网关与鉴权    | Spring Cloud Gateway、Sa-Token、JWT                                               |
| ORM 与迁移    | MyBatis-Plus、Flyway                                                              |
| 注册/配置中心 | Nacos 3.1.1                                                                       |
| 调度中心      | XXL-JOB 3.4.2                                                                     |
| 数据库        | PostgreSQL 16（业务元数据）、MySQL 8.0（Nacos + XXL-JOB）                         |
| 数仓/目标库   | Apache Doris（内置，同步任务目标端）                                              |
| 缓存/会话     | Redis 7                                                                           |
| 前端          | React 18、TypeScript、Vite 5、Tailwind CSS                                        |
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
│   └── sprint2/
│
└── data-nest/                     # 工程代码
    ├── pom.xml                    # Maven 根 POM
    ├── docker-compose.yml         # 一键部署配置
    │
    ├── data-nest-common/          # 公共模型、异常、工具
    ├── data-nest-gateway/         # API 网关、登录入口、JWT 鉴权
    ├── data-nest-system/          # 用户、角色、权限
    ├── data-nest-engineering/     # 数据源管理 + 批量同步任务
    ├── data-nest-governance/      # 元数据采集任务 + 元数据管理 + 数据标准
    ├── data-nest-worker/          # XXL-JOB 执行器（同步 + 采集）
    ├── data-nest-job/             # 平台定时任务执行器
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

> 注意：`test-oracle` 首次启动需要初始化 Oracle Free 数据库，healthy 可能需要 2–3 分钟；`test-sqlserver` 通过自定义
> `scripts/sqlserver-entrypoint.sh` 完成初始化脚本执行（官方镜像默认不自动执行 `/docker-entrypoint-initdb.d/`）。

启动后所有容器会进入 `healthy` 状态。

### 4. 访问系统

- 前端：`http://localhost:3000`
- 默认管理员账号：`admin / admin123`
- XXL-JOB 控制台：`http://localhost:8088`（默认 admin / 123456）
- Nacos 控制台：`http://localhost:8848`（默认 nacos / nacos）

---

## 主要服务与端口

### 平台依赖服务

| 服务        | 容器名                   | 端口 | 说明                   |
|-------------|--------------------------|------|------------------------|
| Gateway     | `datanest-gateway`       | 8080 | 统一 API 入口          |
| System      | `datanest-system`        | 8087 | 用户/权限服务          |
| Engineering | `datanest-engineering`   | 8082 | 数据源管理 + 批量同步  |
| Governance  | `datanest-governance`    | 8084 | 元数据采集与管理       |
| Worker      | `datanest-worker`        | 8085 | XXL-JOB 执行器         |
| Job         | `datanest-job`           | 8086 | 平台定时任务           |
| Frontend    | `datanest-frontend`      | 3000 | Nginx 托管前端         |
| Nacos       | `datanest-nacos`         | 8848 | 注册/配置中心          |
| PostgreSQL  | `datanest-postgres`      | 5432 | 业务数据库             |
| MySQL       | `datanest-nacos-mysql`   | 3306 | Nacos + XXL-JOB 数据库 |
| Redis       | `datanest-redis`         | 6379 | 会话/缓存              |
| XXL-JOB     | `datanest-xxl-job-admin` | 8088 | 统一调度中心           |

### 测试目标库

| 服务            | 容器名                    | 端口 | 说明                    |
|-----------------|---------------------------|------|-------------------------|
| Test MySQL      | `datanest-test-mysql`     | 3307 | MySQL 测试库            |
| Test PostgreSQL | `datanest-test-postgres`  | 5433 | PostgreSQL 测试库       |
| Test Oracle     | `datanest-test-oracle`    | 1521 | Oracle 23ai Free 测试库 |
| Test SQL Server | `datanest-test-sqlserver` | 1433 | SQL Server 2022 测试库  |

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

#### Nacos + XXL-JOB MySQL

| 项       | 值                             |
|----------|--------------------------------|
| Host     | `localhost`                    |
| Port     | `3306`                         |
| Database | `nacos` / `datanest_scheduler` |
| Username | `nacos`                        |
| Password | `nacos123`                     |

#### 内置 Doris

| 项       | 值                                     |
|----------|----------------------------------------|
| Host     | `localhost`                            |
| Port     | `9030`（查询）/ `8030`（HTTP）         |
| Database | `datanest` / `ods` 等                  |
| Username | `root`                                 |
| Password | （空）                                 |
| JDBC URL | `jdbc:mysql://localhost:9030/datanest` |

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

#### Test Oracle

| 项       | 值                                            |
|----------|-----------------------------------------------|
| Host     | `localhost`                                   |
| Port     | `1521`                                        |
| Service  | `FREEPDB1`                                    |
| Database | `FREEPDB1`（数据源配置中填写）                |
| Schema   | `TESTUSER`                                    |
| Username | `testuser`                                    |
| Password | `testpass123`                                 |
| JDBC URL | `jdbc:oracle:thin:@//localhost:1521/FREEPDB1` |

#### Test SQL Server

| 项       | 值                                                                                              |
|----------|-------------------------------------------------------------------------------------------------|
| Host     | `localhost`                                                                                     |
| Port     | `1433`                                                                                          |
| Database | `testdb`                                                                                        |
| Schema   | `dbo`                                                                                           |
| Username | `sa`                                                                                            |
| Password | `YourStrong@Passw0rd`                                                                           |
| JDBC URL | `jdbc:sqlserver://localhost:1433;databaseName=testdb;encrypt=false;trustServerCertificate=true` |

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

---

## 常用开发命令

```bash
# 查看容器状态
cd data-nest && docker-compose ps

# 查看日志
docker-compose logs -f gateway system engineering governance worker job

# 重启前端（开发模式）
cd data-nest/data-nest-frontend
npm run dev

# 重新构建并部署前后端
cd data-nest
mvn clean install -DskipTests
cd data-nest-frontend && npm run build
cd ..
docker-compose up -d --build gateway system engineering governance worker job frontend
```

---

## 时区

所有容器已统一配置为中国时区（`Asia/Shanghai`）：

- Java 服务通过 `TZ` 环境变量 + `JAVA_TOOL_OPTIONS=-Duser.timezone=Asia/Shanghai` 双重保证
- PostgreSQL 与 MySQL 容器通过 `TZ` 及数据库时区参数配置

---

## 后续路线

- **Sprint 3+**：DAG 可视化编排、SQL 任务编辑器、血缘图谱、数据质量、数据资产目录、数据服务等

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
