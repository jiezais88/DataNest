# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 精神记录变更，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [1.0.0] - 2026-08-17

首个开源发布版本（Sprint 0 ~ Sprint 12）。

### 平台骨架（Sprint 0）

- Sa-Token + JWT 统一登录鉴权；4 个预置角色（超管/数据工程师/治理管理员/数据分析师）
- 用户管理、角色管理；基于角色的菜单与 API 权限
- Spring Cloud Gateway 统一入口、Nacos 注册与配置中心、Docker Compose 基础设施

### 数据工程（Sprint 1 ~ 5）

- 数据源连接管理：MySQL / PostgreSQL / Doris / Oracle / SQL Server，密码 AES 加密存储
- 批量数据同步：Addax 引擎 → Doris Stream Load；全量/增量、多表字段映射、速率限流；执行历史与日志
- DAG 可视化编排：ReactFlow 画布 + Monaco SQL 编辑器；SQL / Python / 条件分支 / 子 DAG 节点
- DAG 参数化（自定义参数 + 系统变量）、版本快照/对比/回滚、失败节点重跑
- 血缘自动上报：SQL 执行后 AST 解析 source → target 写入血缘
- 血缘可视化：表级图谱、字段级下钻、影响分析、溯源分析
- 全局告警中心：DAG/同步/采集统一邮件告警规则、启停、发送历史
- 调度引擎迁移：XXL-JOB / DolphinScheduler → PowerJob 5.1.2（定时任务 + DAG 工作流）

### 数据治理（Sprint 2 / 6 / 7 / 8）

- 元数据自动采集（全量/增量、Cron 定时）与树形浏览，表/字段注释编辑
- 数据标准：命名规范、字段类型标准、合规自动扫描
- 数据质量：完整性/唯一性/值域/自定义 SQL（含 Python）规则，四档判定、趋势、质量报告与导出
- 质量监控告警（通过/警告/严重分级）

### 资产目录（Sprint 7 / 8）

- 数据搜索（关键词/模糊/标签筛选）、按数据域分类浏览
- 数据详情聚合：血缘图 + 质量评分 + 数据预览
- 协作能力：标签、收藏、关注、评论、热度排行

### 实时计算（Sprint 8 / 9）

- CDC 管道：MySQL Binlog / PostgreSQL 逻辑复制 → Flink CDC → Iceberg 湖仓（MinIO）→ Doris
- 管道配置向导、启停管理
- 监控：吞吐/延迟指标分钟级历史与趋势图
- Checkpoint/Savepoint 可视化管理；流处理邮件告警

### 数据服务（Sprint 10）

- SQL 查询终端：Doris JDBC 直连，结果导出 CSV/Excel
- 数据 API：基于表一键生成 RESTful API；API Key 认证、限流、熔断、调用统计
- 实时订阅：CDC 变更事件 WebSocket 推送
- 数据分级分类：机密数据访问管控

### 平台安全与调度治理（Sprint 11）

- 审计日志：10 类操作全量留痕，90 天保留自动清理，只增不改
- 细粒度 RBAC：自定义角色 CRUD、88 个功能权限点、数据源/库/表三级数据权限、机密表锁定、保存即时生效
- 任务资源队列：队列并发上限 + 优先级排队调度；cron 定时触发统一走排队链路
- 首页仪表盘：值班态势总览（症状 KPI、待处理异常队列、系统健康、14 日趋势、快捷操作）
- 个人中心：资料查看/编辑、修改密码
- Nacos 业务参数热更新（@RefreshScope 改造 + cron 热重注册）；realtime 调度全部收敛到 PowerJob

### 开源发布准备（Sprint 12）

- 一键部署脚本 `deploy.sh`：环境预检 → Doris 交互配置 → 构建 → 启动 → 健康等待 → 登录冒烟
- 部署指南 `docs/deploy.md`（环境要求/外部 Doris 配置/端口清单/FAQ/卸载）
- E2E 测试库收敛到 compose `test` profile，默认部署不再拉起
- Apache-2.0 License、README 重写、本 Changelog
