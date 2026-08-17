# DataNest 构建、部署与验证规范

> 本文件是 AGENTS.md §3（构建与部署规则）、§4（验证规范）、§7（代码与提交约定）的详细版。AGENTS.md 正文只保留三条红线，其余按需查阅本文件。

## 一、构建与部署

### 关键原则

- **task-core 是共享执行内核**（原拆分模块 entity/task-core-governance 已删除），是 engineering、governance、worker、job、system 的**共享底座**（原第 4 个模块 alert 已独立为 app-alert 服务）。
- 消费方只显式依赖 `data-nest-task-core`；告警调用另依赖 `data-nest-alert-api`（Feign 契约）。
- **构建顺序**：Maven 按 `<modules>` 声明顺序构建，顺序为 `common → *-api → task-core → 各服务`（已在根 pom 配置）。

### 常用命令

```bash
cd data-nest
# 全量构建
mvn clean package -DskipTests -q
# 只构建 task-core 及主要消费方（engineering/worker）
mvn -pl data-nest-task-core,data-nest-engineering,data-nest-worker -am clean package -DskipTests -q
docker compose build app-engineering app-worker
docker compose up -d --no-deps app-engineering app-worker
```

> `-am`（also make）会自动把 `task-core` 依赖的 common 及各 api 模块一并构建。

### 注意

- **只要改到 `data-nest-task-core`（含任一拆分模块），必须同时重新编译并部署所有消费方**（至少 engineering 和 worker；若涉及治理/质量还需 governance/job/system），否则执行节点还是旧代码。命令见上。
- 构建后检查镜像时间戳，确认用了新 jar（遇到过 buildkit 缓存未更新的情况）。
- **前端部署必须两步**：`app-frontend` 的 Dockerfile 只 `COPY dist/`（不在镜像内构建），改前端代码后必须先本地 `pnpm build` 再 `docker compose build app-frontend && up -d`，否则镜像里是旧产物。
- **前端顶级路由不得与静态目录 `assets/` 同名**（nginx `location /assets/` 是 Vite 产物长缓存目录）：Sprint 7 资产目录路由因此用 `/asset-catalog`。新增顶级路由前先对照 `data-nest-frontend/nginx.conf`。
- 只改动单一服务时，只重建该服务即可，不必全部重启。
- worker 镜像基于 `wgzhao/addax:6.0.11` 多阶段构建，首次构建会下载 Addax 二进制。
- **E2E 测试库已收敛到 `test` profile（2026-08-17，Sprint 12 ADR-S12-004）**：test-mysql / test-postgres / test-oracle / test-sqlserver 默认不随 `docker compose up -d` 启动。跑 E2E（数据源/CDC/数据服务多数据源用例）前必须先 `docker compose --profile test up -d`；日常功能开发/部署不需要它们。`deploy.sh --with-test-deps` 等价于带 profile 启动。
- **测试产物清理约定（2026-08-12 起，全局纪律）**：每次 E2E/API 测试结束必须清理临时产物——前端 `test-results*` 目录、`%TEMP%` 下 `sql_e2e*.ps1`/`verify_fix*.ps1`/`body_*.json`/`login*.json`/`create_*.json`/`export_*.json`。Windows 下 `Remove-Item` 可能被环境安全保护拦截，用 try/catch 或逐目录删，删后确认 `Test-Path` 为 false。`e2e/sprint10/` 下的正式测试套件（如 `sql-console.spec.ts`）属源码，不清理。测试创建的数据库用户（如 `analyst_test`）清理前先问用户。

## 二、验证规范

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

## 三、代码与提交约定

- **代码 Review 目的（2026-08-07 起）**：Review 开发的功能时聚焦三点——① **与当前架构融洽**（不破坏模块边界、依赖方向、服务间调用规则）；② **业务实现正确**（符合 PRD/技术文档语义，边界与异常路径处理到位）；③ **实现高效**（无过度设计、无 N+1/循环远程调用、无不必要的资源开销）。
- 做 **最小改动**，不要顺手重构无关代码。
- 改配置/改接口后，同步检查 yaml、Nacos 配置、注释、测试、前端调用点。
- 新增依赖时检查作用域：`provided` 依赖需要在消费方显式声明。
- 保持代码和周围风格一致，注释用中文。
- **创建审计字段约定（2026-08-05 起生效，V3.6.8）**：所有实体 `create` 入口（含批量 create/DAG 节点）**只设置 `setCreatedBy`/`setCreatedAt`，禁止 `setUpdatedBy`/`setUpdatedAt`**；`updated_at` 列已通过 Flyway `V3.6.8__drop_updated_at_default.sql` 去掉 `DEFAULT CURRENT_TIMESTAMP` 且允许 NULL，仅真正 update/启停/状态变更时才写入。新增带审计字段的表时，其 `updated_at` 不要加 DB 默认值。
- 不要主动运行 `git commit` / `git push`，除非用户明确要求。
