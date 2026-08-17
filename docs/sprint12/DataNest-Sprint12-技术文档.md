# DataNest Sprint 12 技术文档：开源发布准备（一键部署 + GitHub 发布）

> 版本：v1.0 | 日期：2026-08-17
> 对应 PRD：`DataNest-Sprint12-PRD.md`（v1.0，决策 D1~D6 已定稿）

---

## 1. 现状勘察结论（实施依据，2026-08-17 实地核对）

| 事实 | 出处 |
|------|------|
| compose 共 24 服务：中间件 11（含 nacos-init 一次性 Job）+ app 9 + mailhog + 测试库 4（test-mysql/test-postgres/test-oracle/test-sqlserver 均在默认编排中启用） | `data-nest/docker-compose.yml` |
| Doris 配置单一来源 `shared-configs/shared-doris.yaml`（fe-host/fe-query-port/user/password 4 项），由 `middleware-nacos-init` 推入 Nacos（group=shared-configs）；`shared-addax.yaml` 经 `${datanest.doris.*}` 占位引用，无需联动改 | `shared-configs/shared-doris.yaml`、`scripts/init-nacos.sh` |
| 后端构建：Java 25 + Maven（根 pom `java.version=25`）；产物为各模块 `target/*.jar`（fat jar），后端镜像 build context 为 `data-nest/` 根，`.dockerignore` 只放行 fat jar | `pom.xml`、`.dockerignore` |
| 前端构建：pnpm（`pnpm-lock.yaml`），`pnpm build` = `tsc -b && vite build`，产物 `dist/`；`app-frontend` Dockerfile 只 `COPY dist/`，**必须先本地 build 再 compose build** | `data-nest-frontend/` |
| app 服务健康检查齐全（`nc -z` 各端口）；app-gateway 依赖 `middleware-nacos-init` `service_completed_successfully`，首启自动推配置 | `docker-compose.yml` |
| 仓库卫生：`data-nest/tmp/` 已被 gitignore；`data-nest/data/`（python-sandbox 挂载点）**未被 ignore 也未被跟踪**——需补 `.gitignore`；test-results/playwright-report 无跟踪记录 | `git ls-files` / `git check-ignore` |
| `docker/flink/lib/` 10 个 jar 共 182MB，最大单文件 43MB（GitHub 单文件上限 100MB），可直接入库，v1 不做下载脚本 | `du` / `ls` |
| Windows Git Bash 下裸 `mvn` 有 classworlds 路径解析坑（AGENTS.md 已记），deploy.sh 在 Windows 需调 `mvn.cmd` | AGENTS.md §6 |

## 2. ADR

### ADR-S12-001：deploy.sh 单脚本七段式流程

位置 `data-nest/deploy.sh`（与 docker-compose.yml 同目录），`#!/usr/bin/env bash` + `set -euo pipefail`。任何阶段失败即停，打印阶段名 + 排查指引，退出码非 0。

```
[1/7] 环境预检 → [2/7] Doris 配置 → [3/7] 后端构建(mvn) → [4/7] 前端构建(pnpm)
→ [5/7] compose up -d --build → [6/7] 健康等待 + 登录冒烟 → [7/7] 打印访问信息
```

支持参数：`--skip-build`（跳过 3/4，仅重启部署）、`--doris-host/--doris-port/--doris-user/--doris-password`（非交互模式）、`--skip-doris`（跳过 Doris 配置与校验）、`--with-test-deps`（追加 `--profile test` 拉起测试库）。

### ADR-S12-002：环境预检一次报全

逐项探测并收集结果，最后统一输出缺失清单（含安装指引 URL），任一缺失 exit 1：

| 检查项 | 断言 | 缺失提示 |
|--------|------|----------|
| docker daemon | `docker info` 成功 | 安装 Docker Desktop / docker engine 并启动 |
| compose v2 | `docker compose version` | 升级到含 compose v2 插件的 Docker |
| JDK | `java -version` 主版本 = 25 | Adoptium Temurin 25 下载链接 |
| Maven | `mvn -version` ≥ 3.9（Windows MINGW 下改调 `mvn.cmd -version`，构建同理） | maven.apache.org 下载链接 |
| Node | `node --version` ≥ 18 | nodejs.org 下载链接 |
| pnpm | `pnpm --version` 存在 | `npm i -g pnpm` 或 `corepack enable` |

### ADR-S12-003：Doris 交互式配置 + 连通性探测

- 读取现有 `shared-configs/shared-doris.yaml` 展示当前值；交互询问是否修改（非交互模式用参数/环境变量覆盖）。
- 探测方式：bash `/dev/tcp/<host>/<port>` 探测 FE 9030（Git Bash / Linux 均可用，不依赖 nc/mysql client）。
- 不可达 → 明确提示「同步与数仓功能不可用」，选择：重新填写 / 跳过继续 / 退出。
- 确认后**整体重写** shared-doris.yaml（4 项 + 注释头，避免 sed 原地改的转义坑）。
- 已存在运行中的 Nacos 时（重复执行场景），写配置后执行 `docker compose up -d --force-recreate middleware-nacos-init` 触发重推；首启场景随 compose up 自然完成（app 服务 `depends_on` 已串好）。

### ADR-S12-004：测试库收敛到 `test` profile

4 个测试库（test-mysql/test-postgres/test-oracle/test-sqlserver）加 `profiles: ["test"]`，默认一键部署不再拉起（Oracle/SQLServer 镜像合计约 4GB，对评估用户是纯负担）。E2E 场景改用 `docker compose --profile test up -d`。

- 影响面核查：无任何 app 服务 `depends_on` 测试库 ✅；需同步更新 `docs/agent/build-and-deploy.md` 与 README 的 E2E 前置说明。

### ADR-S12-005：健康等待 = compose 状态轮询 + 登录冒烟

- 轮询 `docker compose ps`（每 5s）直到全部服务 `running`/`healthy`（one-shot 的 nacos-init 为 `exited (0)`），总超时 10 分钟；超时打印非健康容器清单 + `docker compose logs <服务>` 排查指引，exit 1。
- 功能冒烟：`curl -s -X POST http://localhost:8080/api/system/auth/login`（admin/admin123）返回 token 视为全链路通；失败提示看 app-gateway / app-system 日志。

### ADR-S12-006：发布物料规格

- **LICENSE**：Apache-2.0 全文，根目录 `LICENSE`，版权行 `Copyright 2026 DataNest Contributors`。
- **README 重写**（根 `README.md`，中文）大纲：简介与能力全景（Sprint 0~11 全能力域）→ 截图（4~6 张，存 `docs/screenshots/`，相对路径引用）→ 技术栈（Java 25/Spring Boot 4/PowerJob 5.1.2/Nacos 3.1.1/Flink CDC 3.6/React 18+Vite+pnpm）→ 仓库结构（三层目录现状）→ 快速开始（`cd data-nest && ./deploy.sh`，链接部署文档）→ 文档索引 → License。消除全部过时表述（JDK 21/npm/前端 3000 为主入口/Sprint 5 截止清单/平铺模块结构）。
- **CHANGELOG.md**：Keep a Changelog 精简格式，`## [1.0.0] - 2026-08-XX` 单条目，按能力域分小节（平台骨架/数据工程/治理/实时/数据服务/安全与调度治理）从各 Sprint PRD 提炼。
- **截图清单**：首页仪表盘、DAG 编辑器、血缘图谱、资产目录、审计日志、权限配置（6 张，PNG，部署环境实拍）。
- **.gitignore 补充**：`data-nest/data/`（python-sandbox 本地产物）。

### ADR-S12-007：发布流程（外向操作，逐步与用户确认）

1. 用户创建 GitHub 公开仓库（仓库名用户定）。
2. 卫生检查：`git grep` 敏感串复核（现有密码均为本地默认值，README/部署文档统一加「本地开发默认值，生产必改」提示）；确认 tmp/data/test-results 不入库。
3. `git remote add origin <url>` → `git push -u origin main` → `git tag v1.0.0` → `git push origin v1.0.0`。
4. `gh release create v1.0.0 --notes-file docs/sprint12/release-notes-v1.0.0.md`（gh CLI 不可用时网页手工）。
5. Release Notes 内容：亮点能力 + 截图 + 快速开始 + 已知限制（外部 Doris 依赖 / 仅中文 UI / Windows 需 Git Bash / 测试库需 `--profile test`）。

## 3. 部署文档结构（`docs/deploy.md`）

1. 环境要求（Docker + Compose v2 / JDK 25 / Maven 3.9+ / Node 18+ / pnpm；内存 ≥16GB；Windows 用 Git Bash）
2. 快速开始（一条命令 + 预期时长 + 成功标志）
3. 外部 Doris 配置指引（为什么需要 / 单节点 FE+BE 最小安装要点 / DataNest 侧 4 个配置项 / 改配后重推 Nacos 命令）
4. 服务与端口清单（以 compose 现状为准：网关 8080、前端 3000、Nacos 8848/控制台 8081、PowerJob 7700、PG 5432、MySQL 3306、Redis 6379、MinIO 9000/9001、Flink 18081、MailHog 1025/8025；Kafka 仅内网）
5. deploy.sh 参数说明（--skip-build / --skip-doris / --with-test-deps / --doris-*）
6. 常见问题（端口冲突、内存不足、容器 unhealthy 排查路径、Nacos 配置未生效时重推方法、国内镜像加速）
7. 彻底卸载（`docker compose down -v --rmi local` + 卷清单说明 + 数据不可恢复警示）

## 4. 验证计划

| # | 场景 | 预期 |
|---|------|------|
| V1 | 干净环境（删 target/dist 后）`./deploy.sh` 全量跑通 | 全部容器 healthy，登录冒烟通过，PRD AC-1 |
| V2 | 模拟 docker daemon 未启动 / pnpm 缺失 | 一次报全缺失项 + 指引，exit 1，AC-2 |
| V3 | Doris 填不可达地址 → 选跳过 | 明确提示影响范围，部署继续，AC-3 |
| V4 | 默认 `up` 后 `docker compose ps` 无 test-* 容器；`--profile test up -d` 拉起 4 测试库 | ADR-S12-004 |
| V5 | 重复执行 `./deploy.sh --skip-build` | 幂等，无重复资源，AC-4 |
| V6 | 按 docs/deploy.md 从零走一遍（含卸载） | 无卡点，AC-5/AC-6 |
| V7 | 推送前 `git status` + 敏感串 grep | 无本地产物/敏感信息，AC-11 |

## 5. 修订记录

| 版本 | 日期 | 说明 |
|------|------|------|
| v1.0 | 2026-08-17 | 初稿，基于现状勘察（§1）与 PRD D1~D6 |
