# Sprint 12 Handoff：开源发布准备（一键部署 + GitHub 发布）

> 更新：2026-08-17（规划会话，PRD v1.0 定稿）
> 对应文档：`../DataNest-Sprint12-PRD.md`（v1.0）

---

## 1. 状态看板

| 交付物 | 状态 | 说明 |
|--------|------|------|
| PRD | [OK] v1.0 | 范围用户拍板：只做路线图 ①一键部署脚本+部署文档 与 ⑥Release 物料，追加实际发布（D1~D6 全部定稿） |
| 技术文档 | [OK] v1.0 | 7 个 ADR：七段式 deploy.sh / 预检一次报全 / Doris 交互配置+重推 Nacos / 测试库收敛 test profile / 健康等待+登录冒烟 / 发布物料规格 / 发布流程；含现状勘察结论与验证计划 V1~V7 |
| F1 一键部署脚本 | [OK] | 2026-08-17 交付并全量验证通过：`data-nest/deploy.sh` 七段式（Windows 自动 mvn.cmd；非交互沿用现有 Doris 配置并探测；compose up 失败重试 3 次；健康等待按 `config --services` 枚举 + 竞态容错；登录冒烟）；compose 4 个测试库加 `test` profile（默认 21 服务 / --profile test 25 服务，`docs/agent/build-and-deploy.md` 已同步）；`.gitignore` 补 `data-nest/data/`。**验证**：bash -n/--help ✅；AC-2 预检一次报全（模拟 node/pnpm 缺失，报全 2 项 exit 1）✅；**AC-1 最终全量认证通过**（一条命令 7 段全过：mvn BUILD SUCCESS + pnpm build + compose up 一次成功 + 全容器 healthy + 登录冒烟）✅；AC-4 幂等（多次重复执行无重复资源）✅。**验证中修复 2 个环境级缺陷**（已记 gotchas §一）：① app 服务健康检查窗口过短（冷启动 ~210s 超原 ~160s 窗口被误判 unhealthy → compose up 中止），retries 10→30；② Kafka 健康检查 JVM CLI 探针 53s 超 5s 超时（曾永久 unhealthy 6h），改 /dev/tcp 轻量探测。测试库容器+卷已按用户拍板全部清除（定义保留在 test profile 供 E2E） |
| F2 部署文档 | [OK] | 2026-08-17：`docs/deploy.md` 交付——环境要求（Java 25/Maven 3.9+/Node 18+/pnpm/Docker，内存 ≥16GB，Windows 走 Git Bash）/ 快速开始 + deploy.sh 参数表 / 外部 Doris 指引（最小安装 + 4 项配置 + 脚本与手工两条改配路径）/ 端口清单（与 compose 逐项核对，Nacos 控制台为 :8081/nacos）/ FAQ×6（端口冲突/unhealthy 排查/Nacos 不生效/国内加速/E2E 测试库/Doris 连不上）/ 彻底卸载（`compose --profile test down -v --rmi local` + 数据不可恢复警示） |
| F3 开源物料 | [OK] | 2026-08-17 全部完成：Apache-2.0 LICENSE + README 重写（对标 SeaTunnel/DataHub/DBeaver/OpenMetadata 调研：定位叙事 + badges + 手绘 SVG 架构图 `docs/assets/architecture.svg` 含白底深色模式适配）+ CHANGELOG v1.0.0 + **6 张实拍截图**（`docs/screenshots/`：home/dag-editor/lineage/asset-detail/audit-logs/permission，电商 demo 数据实拍，README 已加「界面展示」区块，用户评审通过） |
| F4 GitHub 发布 | [OK] | 2026-08-17 交付：① 仓库瘦身（方案③）：`scripts/fetch-flink-libs.sh` + `.gitignore` + deploy.sh 自动 fetch + filter-branch 重写 177 提交，**size-pack 158MB → 9.5MB**（插曲：本地 jar 被 reset --hard 误删，已从运行容器恢复，sha256 全对）；② **main + v1.0.0 tag 已推送**（https://github.com/jiezais88/DataNest，本地分支已改 main）；③ **Release v1.0.0 已发布**（公开，Release Notes + 10 jar 附件完整，gh 便携版 + GH_TOKEN 方式）；④ **新用户链路实测通过**：模拟干净环境执行 fetch 脚本，10 个 jar 全部从 Release 下载成功 + sha256 校验通过。遗留：截图待补（README 待加「界面截图」区块，不阻塞发布） |

## 2. 范围确认（用户已拍板）

| 决策 | 结论 |
|------|------|
| D1 范围 | 只做路线图 ①（一键部署+部署文档）与 ⑥（Release 物料）+ 实际发布；②用户手册 ③API 文档站 ④贡献指南 ⑤示例数据集+Demo 视频不做 |
| D2 Doris | 保持外部 Doris 不进 compose；脚本交互式配置 + 连通性校验 + 可跳过 |
| D3 交付形态 | 一键脚本本地构建，不做预构建镜像发布 |
| D4 License | Apache-2.0 |
| D5 发布 | 用户建 GitHub 公开仓库；Sprint 12 含实际推代码/打 tag/发 v1.0.0 |
| D6 脚本形态 | bash 单脚本（Windows 走 Git Bash），不做 PowerShell 版（2026-08-17 用户确认） |

## 3. 已知注意点

- **全新卸载重装演练（2026-08-17，用户发起）抓出并修复 5 颗「老卷遮羞」全新部署阻断雷**（全部已验证 + 记 gotchas §一）：① nacos schema 缺默认用户种子（补 INSERT）；② postgres initdb 缺 6 域库（新增 `scripts/init-postgres-db.sql` + 挂载；旧共享库 `datanest` 废弃，`POSTGRES_DB` 改 `postgres` 防缺省建同名库）；③ Kafka 探针过重（前一日已修）；④ 4 服务 baseline 含 pg_dump `\restrict/\unrestrict` psql 元命令（删行，基线首次真正执行）；⑤ baseline 是 schema-only 无种子数据（新增 system `V1.1.3__seed_preset_roles_and_admin.sql` 角色+admin+权限矩阵、governance `V1.7.1__seed_quality_rule_template.sql` 4 模板；engineering default 队列 V1.7.0 自带种子无需补）。另修血缘写入端空 target 过滤缺陷（`MetadataWriteService`）。
- **demo 数据环境**（本机）：电商主题——Doris `demo_ecommerce`（800 订单/120 用户/60 商品 + dwd/dws/ads 三层）、数据源「demo-电商数仓-Doris」、采集任务、DAG「电商每日经营加工」（4 节点 14 天执行历史，真实 4 条 + SCHEDULED 10 条时间回填）、失败示例 DAG「订单质量核查（异常示例）」（pendingFailed=1）、质量规则「订单明细-金额非空」、角色「电商分析组」+ 库级数据权限 + 用户 demo_analyst/demo123。造数时踩坑记录：Doris CTAS 需 `PROPERTIES("replication_num"="1")`（单 BE）、DAG Python 沙箱禁 socket（pymysql 不可用）、血缘图谱 tableName 需库限定名。
- **测试库现状（2026-08-17 用户拍板）**：4 个测试库容器与数据卷已全部清除（用户：测试数据不需要保留）；compose 中的服务定义保留在 `test` profile 下（用户确认，供 Sprint 6~10 E2E 使用），下次 `docker compose --profile test up -d` 由 init 脚本自动重建数据。

- **README 全面过时**：现 README 停留在 Sprint 5（JDK 21、npm、前端 3000、旧模块结构 `data-nest-common/` 平铺），F3 重写时需逐项核对现状（Java 25、pnpm、三层目录 data-nest-libs/apis/services、网关 8080 统一入口、9 个 app 容器 + alert/realtime/data-service）。
- **发布前卫生检查**（PRD §6.4）：`data-nest/tmp/`、`data-nest/data/` 本地产物不进发布（核对 .gitignore）；`docker/flink/lib/*.jar` 体积大，push 前评估是否改用获取脚本；默认密码保留但标注「本地默认值，生产必改」。
- **Sprint 11 遗留已清零**：F4 全链路验证用户确认完成（2026-08-17 补记 sprint11 handoff）；根 pom `data-service-api` 重复声明 warning 已清理（`mvn validate` 通过）。

## 4. Next Action

1. ~~与用户确认 D6~~（已确认：bash 单脚本，2026-08-17）。
2. ~~写 Sprint 12 技术文档~~（已完成 v1.0，见 `../DataNest-Sprint12-技术文档.md`）。
3. 实施 F1 `deploy.sh`（按 ADR-S12-001/002/003/005）+ compose 测试库加 `test` profile（ADR-S12-004，同步更新 `docs/agent/build-and-deploy.md` 的 E2E 说明）。
4. F2 部署文档 `docs/deploy.md` → F3 物料（LICENSE/README/CHANGELOG/截图）→ F4 等用户建好 GitHub 仓库后执行。
