# DataNest 部署指南

> 适用版本：v1.0.0 | 更新：2026-08-17
> 本文档面向首次部署 DataNest 的用户。从克隆仓库到平台可登录，正常情况下约 15~30 分钟（取决于网络与机器性能）。

---

## 1. 环境要求

| 依赖 | 版本要求 | 用途 | 下载 |
|------|----------|------|------|
| Docker + Compose v2 | 最新稳定版（`docker compose version` 可用） | 运行全部中间件与平台服务 | https://docs.docker.com/get-docker/ |
| JDK | **25** | 编译后端 | https://adoptium.net/temurin/releases/?version=25 |
| Maven | 3.9+ | 构建后端 | https://maven.apache.org/download.cgi |
| Node.js | 18+ | 构建前端 | https://nodejs.org/ |
| pnpm | 任意（`npm i -g pnpm`） | 前端包管理 | - |
| curl | 任意 | 部署脚本健康检查 | 系统自带 |

- **硬件建议**：内存 ≥ 16GB（全栈含 Flink/Kafka/MinIO，空闲内存低于 8GB 时容器容易 OOM 或健康检查超时）；磁盘 ≥ 20GB。
- **Windows 用户**：部署脚本是 bash 脚本，请使用 **Git Bash** 执行（安装 Git for Windows 自带），不要在 PowerShell/CMD 中直接运行。
- **外部 Doris**：同步与数仓功能需要一个**平台之外**的 Apache Doris（不在 compose 内）。没有 Doris 也能部署，其余功能正常，同步相关功能待配好后启用。见 §3。

## 2. 快速开始

```bash
git clone <仓库地址>
cd "Data Platform/data-nest"
./deploy.sh
```

脚本自动完成：环境预检 → Doris 配置 → 后端构建（mvn）→ 前端构建（pnpm）→ 容器启动 → 健康等待 → 登录冒烟。看到 `DataNest 部署完成` 即成功。

> 首次执行会自动从 GitHub Release 附件下载 Flink CDC 运行时 jar（约 182MB，实时 CDC 功能所需，已做 sha256 校验）；下载一次后本地缓存，后续离线可重复部署。

- 首次部署时脚本会显示当前 Doris 配置并询问是否修改（见 §3）；没有 Doris 可暂选跳过。
- 部署完成后访问 **http://localhost:3000**，默认账号 `admin / admin123`。

### deploy.sh 参数

| 参数 | 说明 |
|------|------|
| `--skip-build` | 跳过后端/前端构建，仅重新部署（改配置后快速重启用） |
| `--skip-doris` | 跳过 Doris 配置与连通性校验 |
| `--with-test-deps` | 额外拉起 E2E 测试库（test-mysql/test-postgres/test-oracle/test-sqlserver，仅跑 E2E 测试需要） |
| `--doris-host=HOST` 等 | 非交互方式指定 Doris 连接（`--doris-port/--doris-user/--doris-password`） |
| `-h, --help` | 帮助 |

## 3. 外部 Doris 配置

### 3.1 为什么需要

DataNest 的批量同步（Addax → Stream Load）、SQL 终端、质量检查等数仓功能以 Doris 为目标端。Doris 体量较大且通常独立部署，因此**不包含在 compose 内**，由用户自备（物理机/虚拟机/另一台 Docker 主机均可，只要平台容器网络可达）。

### 3.2 最小安装要点（单节点）

1. 下载 Apache Doris（≥ 2.x），解压后启动 FE：`fe/bin/start_fe.sh --daemon`（默认即可）。
2. 注册并启动 BE：`be/bin/start_be.sh --daemon`，然后 `mysql -h127.0.0.1 -P9030 -uroot -e "ALTER SYSTEM ADD BACKEND '127.0.0.1:9050';"`。
3. `SHOW BACKENDS\G` 确认 `Alive: true`。
4. 给 root 设密码并允许远程访问（`SET PASSWORD FOR 'root' = PASSWORD('你的密码')`；确认 `fe.conf` 的 `priority_networks` 与防火墙放行 9030/8030）。

> 详细安装见 Doris 官方文档：https://doris.apache.org/docs/getting-started/

### 3.3 DataNest 侧配置

连接信息只有 4 项，存于 `data-nest/shared-configs/shared-doris.yaml`：

```yaml
datanest:
  doris:
    fe-host: 192.168.1.100    # Doris FE 主机
    fe-query-port: 9030       # 查询端口（MySQL 协议）
    user: root
    password: password
```

- **推荐**：重新执行 `./deploy.sh`（或加 `--doris-host=...` 参数），脚本会写入配置、校验连通性，并自动重推 Nacos + 重启消费方服务（engineering / worker）。
- **手工**：改 `shared-doris.yaml` 后执行
  ```bash
  cd data-nest
  docker compose up -d --force-recreate middleware-nacos-init   # 重推配置到 Nacos
  docker compose restart app-engineering app-worker              # 重启消费方
  ```

## 4. 服务与端口清单

### 平台入口

| 服务 | 地址 | 说明 |
|------|------|------|
| 前端 | http://localhost:3000 | 平台 UI（admin / admin123） |
| 网关 API | http://localhost:8080 | 所有 API 统一入口 |
| 接口文档 | http://localhost:8080/swagger-ui.html | springdoc 聚合页（右上角切服务） |

### 中间件控制台

| 服务 | 地址/端口 | 账号 |
|------|-----------|------|
| Nacos 控制台 | http://localhost:8081/nacos（API 8848 / gRPC 9848） | nacos / nacos |
| PowerJob 控制台 | http://localhost:7700 | App 密码 `powerjob123`（App：`data-nest-job` / `data-nest-worker`） |
| MinIO 控制台 | http://localhost:9001（S3 API :9000） | datanest / datanest123 |
| Flink Web UI | http://localhost:18081 | - |
| MailHog | http://localhost:8025（SMTP :1025） | - |

### 数据库端口（供客户端直连排查）

| 数据库 | 端口 | 账号 |
|--------|------|------|
| PostgreSQL（业务库 ×6） | 5432 | datanest / datanest123 |
| MySQL（Nacos + PowerJob） | 3306 | root / root123 |
| Redis | 6379 | 无密码（仅本机） |

> Kafka 仅容器内网（`middleware-kafka:9092`），不暴露宿主端口。
> 9 个业务微服务不暴露宿主端口，统一走网关 8080。
> **以上账号密码均为本地开发默认值，生产环境必须修改。**

## 5. 常见问题（FAQ）

**Q1：端口冲突（如 8080/3000/3306 被占用）**
改 `docker-compose.yml` 对应服务的 `ports` 左半部分（宿主端口）后 `docker compose up -d`。网关端口改动还需同步前端 nginx 反代配置（`data-nest-frontend/nginx.conf`）。

**Q2：容器一直 `unhealthy` 或启动失败**
按顺序排查：`docker compose ps` 找到异常容器 → `docker compose logs --tail 100 <服务名>`。最常见原因是内存不足（关掉无关程序或调大 Docker Desktop 内存上限）。中间件（nacos/postgres/mysql/redis）必须先全部 healthy，应用服务才能起来。

**Q3：改了 Nacos 里的配置不生效**
业务参数需服务支持热刷新才即时生效；`logging.level.*` 热生效，其余多数改完要 `docker compose restart <服务名>`。直接改库（config_info 表）不会下发，必须用发布 API 或改 `shared-configs/*.yaml` 后按 §3.3 重推。

**Q4：国内拉取镜像/依赖慢**
配置 Docker 镜像加速器（Docker Desktop → Settings → Docker Engine → `registry-mirrors`）；Maven 配阿里云镜像（`~/.m2/settings.xml`）；pnpm 配 `npm config set registry https://registry.npmmirror.com`。

**Q5：E2E 测试怎么跑**
先 `docker compose --profile test up -d` 拉起测试库（首次会自动初始化测试数据），再按各 Sprint 测试文档执行。日常部署不需要测试库。

**Q6：Doris 连不上**
确认：① Doris 主机 `9030` 端口从本机可达（`curl -v telnet://<host>:9030`）；② `fe.conf` 网络配置与防火墙；③ 账号密码正确且允许远程登录。`./deploy.sh` 的探测失败时可选择跳过，不影响其余功能。

## 6. 彻底卸载

> **警告**：以下操作删除全部容器、数据卷与本地镜像，业务数据（PG/MySQL/MinIO/Flink checkpoint 等）**不可恢复**。

```bash
cd data-nest
docker compose --profile test down -v --rmi local
```

- `--profile test`：连同测试库一起删（没启动过也不影响）。
- `-v`：删除全部数据卷（nacos/pg/redis/minio/flink/kafka/powerjob 数据）。
- `--rmi local`：删除本地构建的镜像（data-nest-app-*、datanest-flink）。
- 拉取的基础镜像（mysql/postgres/nacos 等）如需一并清理：`docker image prune -a`。

## 7. 相关文档

- 产品总纲：`DataNest-产品规格文档-v1.0.md`
- 技术架构：`DataNest-技术架构文档-v1.0.md`
- 开发构建规范：`agent/build-and-deploy.md`
