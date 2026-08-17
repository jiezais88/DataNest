# DataNest v1.0.0

首个开源发布版本 —— 一站式数据中台：接数据、管资产、做治理、对外服务，一个平台完成。

## 亮点能力

- **数据工程**：多数据源接入（MySQL/PG/Doris/Oracle/SQL Server）、Addax 批量同步 → Doris、DAG 可视化编排（SQL/Python/条件分支/子 DAG + 参数化 + 版本管理）、任务资源队列与优先级调度（PowerJob）
- **数据治理**：元数据自动采集、表级/字段级血缘图谱、数据质量规则与报告、数据标准合规扫描
- **资产目录**：数据搜索、详情聚合（血缘+质量+预览）、标签/收藏/评论协作
- **实时计算**：CDC 管道（MySQL Binlog / PG 逻辑复制 → Flink CDC → Iceberg 湖仓 → Doris）、指标监控、Checkpoint 管理、流处理告警
- **数据服务**：SQL 查询终端、数据 API 一键生成（Key 认证/限流/熔断）、CDC 事件 WebSocket 订阅、数据分级分类
- **平台安全**：审计日志（10 类操作留痕）、细粒度 RBAC（自定义角色 + 三级数据权限 + 机密表锁定）、首页值班态势仪表盘

完整变更见 [CHANGELOG.md](CHANGELOG.md)。

## 快速开始

```bash
git clone https://github.com/jiezais88/DataNest.git
cd DataNest/data-nest
./deploy.sh
```

一条命令完成环境预检、构建与全栈启动；完成后访问 `http://localhost:3000`（admin / admin123）。

- 环境要求：Docker + Compose v2、JDK 25、Maven 3.9+、Node 18+、pnpm（内存建议 ≥16GB；Windows 请用 Git Bash）
- 完整部署文档：[docs/deploy.md](docs/deploy.md)

## 已知限制

- **外部 Doris 依赖**：同步与数仓功能需要自备 Apache Doris（配置指引见部署文档 §3）
- 仅中文 UI
- Windows 需通过 Git Bash 执行部署脚本
- E2E 测试库需 `docker compose --profile test up -d` 单独拉起

## Release 附件说明

本 Release 的 10 个 jar 附件是 **Flink CDC 运行时依赖**（实时 CDC 功能所需，约 182MB），不入 git 仓库。`deploy.sh` 会在构建前自动从本 Release 下载并做 sha256 校验；手工拉取可执行 `bash scripts/fetch-flink-libs.sh`。

## License

Apache-2.0
