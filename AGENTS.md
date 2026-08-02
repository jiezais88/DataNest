# DataNest Agent 工作约定

> 本文件面向 AI Agent，用于跨会话恢复项目上下文。人类开发者也可查阅。

## 1. 项目概览

DataNest 是一个数据平台，技术栈如下：

- **后端**：Java 21 + Spring Boot 4.x，Maven 多模块
- **前端**：独立容器 `app-frontend`，通过 `app-gateway:8080` 统一入口
- **部署**：Docker Compose，所有服务在同一 `datanest-net` 网络
- **配置中心**：Nacos，配置实际存储在 `middleware-mysql` 的 `nacos.config_info` 表
- **调度**：XXL-JOB（官方镜像），数据库为 `datanest_scheduler`（不是 `xxl_job`）
- **目标数仓**：内置 Doris（当前在 `192.168.119.135:9030`）

### 核心模块

| 模块                    | 说明                                                 |
|-------------------------|------------------------------------------------------|
| `data-nest-task-core`   | 同步/采集任务核心逻辑，被 engineering 和 worker 共用 |
| `data-nest-engineering` | 数据工程服务（同步任务 API、DAG API）                |
| `data-nest-worker`      | Addax 实际执行方                                     |
| `data-nest-governance`  | 元数据采集任务、元数据管理、数据标准                 |
| `data-nest-job`         | XXL-JOB executor，平台定时任务                       |
| `data-nest-system`      | 认证、用户、权限                                     |
| `data-nest-gateway`     | 网关入口                                             |
| `data-nest-common`      | 公共组件（SchedulerClient 等）                       |

### 核心容器

| 容器                  | 说明                                            |
|-----------------------|-------------------------------------------------|
| `app-engineering`     | 数据工程服务                                    |
| `app-worker`          | 同步/采集任务执行                               |
| `app-governance`      | 数据治理服务                                    |
| `app-job`             | XXL-JOB executor                                |
| `app-system`          | 系统服务                                        |
| `app-gateway`         | 网关                                            |
| `middleware-mysql`    | MySQL：Nacos、XXL-JOB、DolphinScheduler、业务库 |
| `middleware-postgres` | PostgreSQL：业务主库                            |
| `middleware-nacos`    | Nacos 服务                                      |
| `middleware-xxljob`   | XXL-JOB Admin                                   |
| `middleware-redis`    | Redis                                           |

## 2. 会话约定

- **一个会话一个目标**。避免把技术选型、闲聊、无关 Bug 修复混进主线。
- 回复和说明使用 **中文**；代码注释/提交信息跟随项目现有风格（中文为主）。
- 跨会话恢复上下文时，先读 `docs/sprint<编号>/handoff/sprint-<编号>.md`；如不存在，请用户简述当前目标。
- 每个 Sprint 建议 2~4 个会话：规划/设计、后端实现、前端联调、验证收尾。
- 不要主动运行 `git commit` / `git push`，除非用户明确要求。

### 编码前约定

- **先读代码再动手**。修改代码前必须通过 `Read`/`Grep` 读透相关文件和调用链，不要凭记忆或猜测；特别是 `data-nest-task-core`
  的改动，要确认 engineering、worker 等所有消费方。
- **改接口必须同步前端/文档**。修改 DTO、返回结构、URL 路径、字段含义时，必须同步检查前端调用点和接口文档，避免前后端不一致。

### 文档同步约定

- **全局 `AGENTS.md`**：当项目架构、环境信息、已知坑、构建规则发生变化时更新。判断标准：这个变更如果下个会话不知道，可能会踩坑或做错决策。
- **Sprint Handoff 文档**：每个子会话结束时更新当前 Sprint 的状态看板、Blocker、变更清单、Next Action。
- **Sprint 配套文档**：一个 Sprint 通常包含技术文档、产品文档、UI
  原型。开发过程中如果对需求、接口、字段、页面交互做了微调，必须同步回落到对应文档，保持"代码实现 = 文档描述"。
- **代码与文档不一致时必须询问**：开发过程中经常出现代码已实现但文档/原型未更新的情况。当 Agent 发现当前实现和已有文档、原型存在偏差时，
  **必须暂停并询问用户**"这是有意的临时调整，还是需要同步更新文档？"，不要擅自替用户决定。
- 不必更新的情况：纯临时调试命令、一次性验证、很快被覆盖的小尝试。

### 问题排查约定

- 遇到报错或不确定的问题时，优先检查日志、配置、数据库状态、容器健康度。
- 如果项目内无法快速定位根因， **先加载 `systematic-debugging` 技能，按四阶段法（根因调查 → 模式分析 → 假设验证 →
  实现修复）排查**，禁止未定位根因就尝试修复。
- 在根因调查阶段， **应主动使用 WebSearch 搜索相关错误信息、框架版本兼容性、最佳实践**，而不是凭经验猜测。
- 搜索后把关键结论（来源 URL + 核心判断）记录到当前会话或 Sprint Handoff 中，避免后续重复搜索。

## 3. 构建与部署规则

### 关键原则

- `data-nest-task-core` 是 `data-nest-engineering` 和 `data-nest-worker` 的 **共享模块**。
- **只要改到 task-core，必须同时重新编译并部署 engineering 和 worker**，否则执行节点还是旧代码。

### 常用命令

```bash
cd data-nest
mvn -pl data-nest-task-core,data-nest-engineering,data-nest-worker -am clean package -DskipTests -q
docker compose build app-engineering app-worker
docker compose up -d --no-deps app-engineering app-worker
```

### 注意

- 构建后检查镜像时间戳，确认用了新 jar（遇到过 buildkit 缓存未更新的情况）。
- 只改动单一服务时，只重建该服务即可，不必全部重启。
- worker 镜像基于 `wgzhao/addax:6.0.11` 多阶段构建，首次构建会下载 Addax 二进制。

## 4. 验证规范

### 不要只在编译成功就报完成

功能改动必须做回归验证。

### 同步任务验证路径

1. 登录拿 token：
   ```bash
   curl -s -X POST http://localhost:8080/api/system/auth/login \
     -H 'Content-Type: application/json' \
     -d '{"username":"admin","password":"admin123"}'
   ```
2. 手动触发：
   ```bash
   curl -s -X POST "http://localhost:8080/api/engineering/sync-jobs/{sync_job_id}/execute" \
     -H "Authorization: $TOKEN"
   ```
3. 查历史：
   ```sql
   SELECT id, status, error_message, source_rows, target_rows, start_time, end_time
   FROM sync_job_history
   WHERE sync_job_id = {sync_job_id}
   ORDER BY start_time DESC;
   ```
4. 必要时查 Doris 目标表确认数据落地。

### 采集任务验证路径

- 查 `collect_history`
- 查 `collect_execution_log`
- 查 `collect_change_detail`（变更明细）

## 5. 环境速查

| 资源              | 用途                    | 地址/命令                                                                   | 账号/密码                   |
|-------------------|-------------------------|-----------------------------------------------------------------------------|-----------------------------|
| 网关入口          | 所有 API 统一入口       | http://localhost:8080                                                       | -                           |
| admin 登录        | 获取全局 token          | `POST /api/system/auth/login`                                               | admin / admin123            |
| PostgreSQL 业务库 | DataNest 业务主库       | `docker exec -it datanest-middleware-postgres psql -U datanest -d datanest` | datanest / datanest123      |
| MySQL root        | 管理 MySQL 所有库       | `docker exec -it datanest-middleware-mysql mysql -u root -proot123`         | root / root123              |
| MySQL nacos       | 查 Nacos 配置、业务库   | `docker exec -it datanest-middleware-mysql mysql -u nacos -pnacos123`       | nacos / nacos123            |
| Nacos 配置库      | 存储所有 shared-configs | `nacos.config_info` 表（在 middleware-mysql）                               | -                           |
| XXL-JOB Admin     | 调度任务管理            | http://localhost:8088                                                       | admin / 123456              |
| XXL-JOB DB        | XXL-JOB 任务信息        | `datanest_scheduler.xxl_job_info`                                           | -                           |
| Doris 内置        | 目标数仓                | `192.168.119.135:9030`                                                      | root / password             |
| DolphinScheduler  | 工作流调度（当前保留）  | http://localhost:12345                                                      | admin / dolphinscheduler123 |

## 6. 已知坑

- **worker 已补上 caffeine 依赖**；不要回退，否则 `DagExecutionSyncService` 初始化会 `ClassNotFoundException`。
- **Addax writer 配置路径已对齐**：代码读的是 `datanest.addax.writer.*`，不是 `datanest.doris.writer.*`。
- **`writer.database` 兜底已删除**：目标库名由同步任务 `target_database` 决定；为空时直接抛异常。
- **XXL-JOB 任务 ID 可能失效**：如果 admin 侧的任务被手动删除或清理，`sync_job.xxl_job_id`
  会指向不存在的任务，触发时报"任务ID非法"。处理办法：将 `sync_job.xxl_job_id` 置空，下次执行会自动重新注册。
- **Nacos API 可能 401**：直接查 `middleware-mysql` 的 `nacos.config_info` 表更可靠。
- **Doris 是外部主机**：不在 docker-compose 里，部署/清理时不要以为重启容器会影响 Doris。
- **worker 启动 unhealthy 不一定是 caffeine**：如果日志报其他 `ClassNotFoundException`，说明还有 `provided` 依赖没在生活方声明。
- **Addax 执行日志**：worker 容器内 `/opt/addax/log/sync_{sync_job_id}.log` 和生成的 job json
  `/opt/addax/job/job_sync_{sync_job_id}.json` 是排查同步失败的第一现场。
- **Nacos 配置修改后可能不实时生效**：部分服务对 `@Value` 注入无热刷新能力，改完配置后需要重启对应服务。

## 7. 代码与提交约定

- 做 **最小改动**，不要顺手重构无关代码。
- 改配置/改接口后，同步检查 yaml、Nacos 配置、注释、测试。
- 新增依赖时检查作用域：`provided` 依赖需要在消费方显式声明。
- 保持代码和周围风格一致，注释用中文。

### 安全与敏感信息

- 密码、token、密钥等敏感信息 **禁止硬编码**到代码或配置文件中；应走 Nacos 配置或环境变量注入。
- 日志中禁止打印密码、完整 token、数据库连接串密码部分；打印 DTO 时先脱敏敏感字段。
