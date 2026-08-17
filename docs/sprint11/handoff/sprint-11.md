# Sprint 11 Handoff：平台安全与调度治理 + 平台体验（审计日志 + RBAC 细粒度权限 + 任务资源队列 + 首页面板 + 个人中心）

> 更新：2026-08-14（PRD v1.9 首页仪表盘 v3 一屏式重设计，原型首页同步重做）
> 对应文档：`../DataNest-Sprint11-PRD.md`（v1.9）

---

## 1. 状态看板

| 交付物 | 状态 | 说明 |
|--------|------|------|
| PRD | [OK] v1.9 | 全部决策定稿（D1~D13）；纯产品视角；**v1.9 首页仪表盘 v3 一屏式重设计**（用户拍板两条硬约束：一屏零滚动 + 不用红绿柱；五区块：5 卡症状 KPI / indigo 面积趋势图 / 待处理异常 / 系统健康 / 快捷操作） |
| 技术文档 | [OK] v1.0 | 6 个 ADR 定稿 + 用户交互确认三决策：①读接口混合策略（区分读迁 view 权限点、无区分读改 @SaCheckLogin，解决自定义角色被 @SaCheckRole 403）；②自定义角色 code 管理员填可读英文；③队列加对账兜底（QueueDispatcherHandler 扫描超时 WAITING + 状态漂移 RUNNING）；权限点清单 18 模块约 80 点；数据模型（system V1.1.0 + engineering V1.1.0）⚠️ **首页聚合接口需按 v3 区块更新**（5 症状 KPI/趋势序列/待处理异常/健康五项/快捷操作按角色） |
| 原型（HTML/CSS/JS） | [OK] | 首页仪表盘 v3（一屏零滚动，两档适配 1440×900 / 1920×1080）：KPI 5 症状卡 + CSS sparkline + 环比 / indigo 单色面积趋势图（异常日琥珀点标记，无红绿柱）/ 右栏系统健康五项 + 快捷操作沉底 / 左下待处理异常（类型徽标 + 行内重跑/去处理）；其余视图沿用 v1.8（角色管理/权限配置/审计日志/执行队列/导航框架）；对齐 tokens.css（indigo #4f46e5）+ 真实 Sidebar/Layout/DsButton/DsStatusBadge 结构 |
| 后端（F1 审计日志） | [OK] | 8 类已有实体写接口埋点（用户/数据源/同步/DAG/SQL/API/APIKey/分级；权限变更+队列留 F2/F3）+ common 审计基础设施（@AuditLog/AuditLogAspect 异步 fail-open/Recorder）+ system audit_log 表与查询/internal 端点 + 跨服务 Feign Recorder；编译通过；**E2E 全功能测试 24/24 通过（2026-08-14），跨服务（engineering/governance/data-service）Feign 埋点链路端到端实测通过** |
| 前端（F1 审计日志页） | [OK] | 审计日志页（列表+组合筛选+失败行浅红高亮+详情抽屉）+ 大标题描述 + 时间范围必填默认近 7 天 + 每页 10 条（用户三轮微调）；build + Playwright 浏览器联调通过 |
| 后端（F2 RBAC） | [OK] | 角色 CRUD（预置只读/自定义增删改、重名校验、删除校验绑定用户、审计）+ 权限点体系（88 点，登录返回 permissions）+ 三级数据权限（FULL/WHITELIST，白名单保存/查询/合并用户权限 internal）+ 五入口接入（SQL 终端 fail-closed 2012/资产目录/元数据树/同步创建/API 选表）；PM-14 保存即时生效（StpInterfaceImpl 每次动态查库）；PM-6 机密表锁定；**E2E 全功能测试 26/26 通过（2026-08-15，e2e/sprint11/e2e/f2-rbac.spec.ts）** |
| 前端（F2 RBAC） | [OK] | 角色管理页（CRUD+详情弹窗）+ 权限配置页（角色清单+三 Tab：功能权限树快捷档位/数据权限三级树+机密锁定/成员）+ useCan 按钮级控制 + 菜单按权限动态渲染；角色管理新增详情（2026-08-15 用户确认，展示与编辑一致 readonly） |
| 后端（F3 执行队列） | [OK] | 队列 CRUD（重名 7402/非法名 7405/default 保护 7403/绑定 DAG 拒绝 7404）+ DAG 绑定 queueName/priority（创建校验队列存在）+ 排队调度（队列满→WAITING，job 调度器 5s 轮询补触发，高优先先执行）+ 对账兜底 + 审计（DELETE 手动埋点补队列名）；**E2E 全功能测试 17/17 通过（2026-08-16，e2e/sprint11/e2e/f3-queue.spec.ts）**；测试发现并修复 2 真实缺陷（见 Next Action 3） |
| 后端（F3 方案A cron 入队） | [OK] | cron 定时触发从 workflow 内嵌改为 job 侧独立 cron job（DagScheduledTriggerHandler + V1.7.2 `dag.scheduler_job_id` + migrateCronJobs 存量迁移），到点经 Feign 调 `/internal/dag/scheduled-trigger` 与手动共用排队链路，执行历史 `trigger_type='SCHEDULED'`；cron job 生命周期（创建注册/停用注销/删除注销）；**E2E 全功能测试 6/6 通过（2026-08-16，e2e/sprint11/e2e/f3-cron.spec.ts）**；修复前端 SCHEDULED 显示缺陷（TRIGGER_LABEL/OPTIONS 补 SCHEDULED） |
| 前端（F3 执行队列） | [OK] | 执行队列页（列表含运行/等待/绑定统计 5s 轮询 + 新建/编辑弹窗 + 删除确认 + 详情抽屉绑定 DAG 列表含优先级/触发方式筛选）；DAG 表单新增执行队列+优先级字段；执行历史两列 |
| F4 全链路验证 | [OK] | 2026-08-17 用户确认完成：端到端用户旅程由各 F 的 E2E 套件（audit 24 + f2-rbac 26 + f3-queue 17 + f3-cron 6 + f5-home 5，全过）+ Sprint 10 及之前既有套件覆盖，无阻断性错误 |
| 后端（F5 首页 KPI） | [OK] | 4 域聚合 KPI：工程（today/失败去重 pendingFailed/failedItems/14 天 trend）+ 告警（近 24h 汇总）+ 治理（采集/质量异常/Doris 探活）+ 实时（CDC/Flink 探活）；**E2E 测试 5/5 通过（2026-08-17，e2e/sprint11/e2e/f5-home.spec.ts）** |
| 前端（F5 首页 v4.1） | [OK] | 「值班态势总览」：态势横幅（判定+状态分布条钻取）+ 待处理异常队列（重跑/日志/等待时长标色）+ 系统健康 5 项 + 快捷操作 4 入口 + 14 日趋势 strip（失败红点+悬停浮层）+ 空平台三步引导 + 60s 自动刷新；**E2E 5/5 通过** |
| 后端（F6 个人中心） | [OK] | `GET /system/auth/profile`（完整资料）+ `PUT /system/auth/profile`（改邮箱/手机号，null 不修改、空串清空，@Pattern 校验）；**API 自测通过（2026-08-17）**；修复 updateById NOT_NULL 不清空坑（改 LambdaUpdateWrapper.set） |
| 前端（F6 个人中心） | [OK] | ProfileDrawer 右侧抽屉（440px）：身份头部 + 基本信息（邮箱/手机号行内编辑+清空）+ 账号安全（改密移入抽屉复用 ChangePasswordModal）；头像下拉调整；build 通过已部署；导航重构取消（保持现状） |
| Nacos 热更新验证（P1 技术验证） | [OK] | 2026-08-17 实测：**logging.level.\* 热生效无需重启**（改 `com.datanest: debug` 后 app-system 实时出 DEBUG SQL，改回实时回落）；**普通 @Value 不热需重启**（验证 B 基线）。**@RefreshScope 改造后复测**：改 `datanest.asset.search.max-results` 200→3 后资产搜索从 5 条立即变 3 条（无需重启），业务参数热更新打通。结论已固化技术文档 §7.1（含 @RefreshScope 适用边界） |
| @RefreshScope 改造（业务参数热更新） | [OK] | 2026-08-17：job 11 个 handler + governance `AssetCatalogService`/`QualityRuleService`/`ScoreCalculator` + data-service `SqlQueryService`/`OpenApiService`/`RateLimitService`/`CircuitBreakerService`/`DataServiceOpsController` 加 `@RefreshScope`；**连接/凭据/appname/@Scheduled 类不加**（重建丢状态或改了不生效）。已部署 job/governance/data-service 并复测通过 |
| 审计日志清理（F1 补全） | [OK] | 2026-08-17：`SystemAuditApi.cleanup` Feign 契约 + fallback；新建 `AuditLogCleanupHandler`（@RefreshScope，`datanest.job.audit-log-cleanup.retain-days:90`）；JobRegistrar 注册 `auditLogCleanupHandler` cron `0 0 5 * * ?`（PowerJob jobId=397 已确认）；已部署 app-job |
| realtime 调度迁移（@Scheduled → PowerJob） | [OK] | 2026-08-17：realtime 3 处 @Scheduled（CdcMonitorService 轮询×2/MetricSnapshotWriter 落库/MetricRetentionCleaner 清理）全部移除，经 CdcOpsApi Feign → CdcInternalController 4 端点触发；job 新增 cdcMonitorPollHandler(cron 由 interval-ms 生成)/cdcMetricFlushHandler(每分)/cdcMetricRetentionHandler(03:40)，PowerJob jobId 398/399/400 已确认；**cron 热更新**：JobRegistrar 监听 RefreshScopeRefreshedEvent 重算全部 cron 幂等重注册，实测 interval-ms 5000→10000 cron 自动 0/5→0/10 无需重启；坑：PowerJob cron 秒位范围 [0,59]，`0/60` 越界报错，落库任务改 `0 * * * * ?` |
| Sprint11 收尾排查（级联删除/校验/定时器） | [OK] | 2026-08-17 三路排查：①定时器 22 个平台任务全注册启用 ✅；②删除校验基本到位；③**P0 修复**：DagService.delete/DagProjectService.delete 补级联删 dag_version+dag_parameter（新增 DagVersionService.deleteByDagId），存量孤儿 70 版本+53 参数已清理归零，API 创建→加版本/参数→删除→查库 0/0/0 验证通过；**P1 修复**：CdcPipelineService.delete 兜底 cancel 残留 Flink 作业（cancelFlinkJobsIfPresent，fail-open）。**轻微项处理**：a) MetadataWriteService.remove 补 quality_rule 引用校验（HAS_REFERENCES 3005，API 自测：插入临时规则→删除 builtin 表被拦截 3005→表保留→清理临时规则，闭环验证通过，app-governance 已部署）；b) 命名规范删除复核：已级联删 compliance_check_result、合规扫描动态读无绑定表→无悬空引用，判定无需改；c) **PowerJob 残留任务清理**：删 44 个 status=99（测试 handler+已删 DAG 调度/节点残留）+ 16 个 status=2 e2e 残留（e2e_s6_exec_scheduled_job/e2e_s7_task_incr_sync，关联业务任务已删），保留 id 17（有效质量任务「定时完整性检查」调度，scheduled_enabled=0 正常），删除前已备份到 job_info_deleted_backup/job_info_deleted_backup2 |

---

## 2. 范围确认（用户已拍板）

| 决策 | 结论 |
|------|------|
| DD-15 形态 | 任务资源队列 + 优先级：管理员定义队列（名称/最大并发），DAG 绑定队列，队列内按高/中/低优先级调度 |
| RBAC 粒度 | 完整数据源 + 库/表级（CM-03 三层权限树）+ 自定义角色（用户后续追加拍板） |
| 审计日志位置 | 系统管理 → 审计日志（独立页，仅超管） |
| Nacos 热更新 | 不做 UI，纯后端验证（细节归技术文档） |
| 前端文案规范 | 用户可见文案禁用「开白/白名单/命中拦截」等内部术语（PRD B7）；Sprint 10 已交付界面文案已替换（「开白」→「特批开放」、「命中拦截」→「查询拒绝」，2026-08-14 落地） |
| Sprint 10 降级链路 | 用户拍板：机密降级两步改为任意级别直接互转 + 前端降级确认框（2026-08-14 落地，后端 4012 已删）；Sprint 11 权限配置页「机密表锁定先降级」交互不受影响 |
| 首页数据面板 | **平台仪表盘 v3 一屏式**（2026-08-14 用户拍板，不参考 v2 从零重做）：两条硬约束——①一屏展示零滚动（1440×900 / 1920×1080 两档严格适配）②不用红绿柱状图（与平台风格不融、突兀）。五区块：①KPI 5 卡症状型（今日任务运行/任务成功率/运行中/失败待处理/告警中，大数字+sparkline+环比，整卡可点）②运行趋势（indigo 单色面积图 + 异常日琥珀点标记）③待处理异常（左下，固定 3~5 行，超出「全部 ›」跳转，不再折叠）④系统健康（右栏上：数据源/集成任务/Flink CDC/Doris/平台服务）⑤快捷操作（右栏沉底，按角色）；删除「最近工作」区块与「数据源类型分布」卡；全新用户三步引导空态；全部真实接口数据（D8） |
| 导航布局 | **取消导航重构（2026-08-17 用户拍板），导航保持现状**（左侧一级菜单 + 顶部原有布局；菜单按权限动态渲染能力沿用 F2 RBAC 已落地部分） |

## 3. PRD 要点

- **F1 审计日志（CM-05/CM-06，P0）**：覆盖 10 类操作；90 天保留；只增不改不删；不记密码/Key 明文/查询结果数据。
- **F2 RBAC 细粒度权限（CM-02/03/04，P0）**：角色管理页支持**自定义角色 CRUD**（功能权限清单勾选，平台管理类能力不开放给自定义角色；预置 4 角色矩阵只读不可删）；权限配置页按角色（预置 + 自定义）勾选数据源/库/表三级树；分析师/治理管理员/自定义角色默认无数据访问（安全默认）；机密表锁定不可授权；权限生效于 SQL 终端/资产目录/数据 API 选表/批量同步四入口；角色增删改与权限保存均记审计。
- **F3 执行队列（DD-15，P1）**：default 队列预置不可删；有绑定 DAG 或运行中任务的队列不可删；DAG 表单新增执行队列 + 优先级字段；执行历史新增两列。
- **F4 全链路验证（P0）**：端到端用户流程回归。
- **F5 首页平台仪表盘 v3 一屏式（P0）**：五区块——①核心指标 KPI 5 症状卡（置顶，大数字+sparkline+环比，整卡点击直达）②运行趋势（indigo 单色面积图 + 异常日琥珀点标记，不用红绿柱）③待处理异常（左下：失败任务+质量告警混排；类型徽标、行内「重跑」、超 4 小时视觉升级、责任边界标注、整行可点、固定 3~5 行超出「全部 ›」跳转）④系统健康（右栏上：数据源/集成任务/Flink CDC/Doris/平台服务五项状态点）⑤快捷操作（右栏沉底：＋同步任务/＋DAG/SQL 查询/数据源，按角色）；各区块独立加载独立失败；禁止写死数据；全新用户三步引导空态（严禁 mock 数据）；**一屏零滚动（1440×900 / 1920×1080 两档适配）**。
- **首页方向变更记录（2026-08-14）**：v1.5 工作台 → v1.6 工作台调研补强 → v1.7 改回「平台仪表盘」五区块 → v1.8 仪表盘 v2 重设计 → **v1.9 仪表盘 v3 一屏式重设计**（用户拍板，不参考 v2 从零重做：一屏零滚动 + 不用红绿柱；KPI 6 卡→5 卡、分组柱→indigo 面积图+琥珀点、需要你关注→待处理异常下沉固定行数、删最近工作区块与数据源分布卡）。v3 设计依据新增：一屏是仪表盘黄金铁律（Microsoft Learn/Nulab/FineBI）、语义色只做高亮不做大面积色块、F 型扫描。
- **F6 个人中心（P0，2026-08-17 由导航重构变更）**：右上角头像下拉进入「个人中心」右侧抽屉（440px）——身份头部（头像/用户名/角色徽章/用户 ID）+ 基本信息（邮箱/手机号可编辑，空输入=清空）+ 账号安全（修改密码移入抽屉）；后端新增 `GET/PUT /system/auth/profile`（`@SaCheckLogin`，null 不修改、空串清空）；所有登录用户可用。导航重构取消，PRD §6.5 已重写、验收 NAV-1~8 → PR-1~7、D9 更新、D10/D11 作废。

## 4. Next Action

1. **F1 已交付并完成 E2E 全功能测试（2026-08-14）**：审计日志 8 类埋点 + 查询页全链路打通。**E2E 套件 `e2e/sprint11/e2e/audit.spec.ts`（24 用例全过）**：8 类埋点（用户/数据源/同步/DAG/SQL 成功+机密拦截/API/APIKey/分级）跨 5 服务（system/engineering/governance/data-service/前端 UI）端到端触发并验证落库，查询页全功能（列表/组合筛选/详情抽屉/失败高亮/分页）+ 权限（分析师 403）+ 只增不改均覆盖；测试数据自播种自清理（helpers/audit-seed.ts，物理清理 e2e_s11_*）。遗留已清零：① 跨服务 Feign 链路已实测通过；② 测试用户 `audit_test_185305` 已删除（含角色关联）；③ 审计页 PRD 原文"每页 20 条"已按用户决策改为默认 10 条（前端 defaultPageSize=10，后端 defaultValue=20 保留，前端显式传参）——此项已实现，非遗留。
2. **技术文档跟进 v3 首页**：首页聚合接口按 v3 区块更新——5 症状 KPI 定义与统计口径（含 sparkline 7 天序列）、运行趋势（近 7 日任务运行量序列）、待处理异常（严重级/超时 4h 标记/重跑动作）、系统健康五项探活（数据源/集成任务/Flink CDC/Doris/平台服务）、快捷操作按角色；其余立项项不变（审计日志表、自定义角色数据模型、权限模型与缓存、队列与 PowerJob 对接、Nacos 热更新验证、菜单权限模型）。
3. **F2 RBAC / F3 队列 已交付并通过 E2E 全功能测试**：
   - **F2（2026-08-15，26/26 通过）**：`e2e/sprint11/e2e/f2-rbac.spec.ts`——角色管理（PM-7~15）、权限点体系（PM-16）、数据权限五入口 fail-closed（PM-1/2/4/5）、机密表锁定（PM-6）、保存即时生效（PM-14）、权限配置页 UI、数据源列表按钮级。关键结论：PM-6 后端 permission-tree 返回 sensitivityLevel + 前端锁定图标已实现；PM-14 无需重新登录即生效。
   - **F3（2026-08-16，17/17 通过）**：`e2e/sprint11/e2e/f3-queue.spec.ts`——队列 CRUD（QU-1/5）、删除约束（QU-3）、DAG 绑定（QU-2/4）、排队调度真实 PowerJob 执行（QU-6）、审计（QU-7）、队列页 UI、权限。**测试发现并修复 2 真实缺陷**：① `QueueDispatchService` 同类内部调用 @Transactional 代理不生效 → 排队补触发后执行永久 RUNNING（改用 TransactionTemplate）；② `DagService.toPayload` 未映射 queueName/priority → DAG 详情/列表丢失队列字段（补映射）。另补 DELETE 审计手动埋点队列名（对齐 RoleService 模式）。
   - 注意：技术文档 §3.0 写的 engineering `V1.1.0__sprint11_queue.sql` **版本号已过时**——engineering 实际最高 V1.6.0，队列脚本实际落地为 `V1.7.0`（已执行，F3 实施时已重新核对）。
4. **F5 首页**：**v4.1「值班态势总览」重设计已落地（2026-08-16，用户评审原型后拍板）**——v3 落地后用户反馈"丑"，经行业调研（DataLeap/DataWorks/Dataphin + dashboard 最佳实践，结论见 `docs/sprint11/DataNest-Sprint11-首页重设计-v4.md` §0）重做信息架构：①态势横幅（状态判定 + 今日状态分布条分段可钻取，行业标配组件）②待处理异常工作队列（失败原因 + 等待时长 4h 黄/24h 红 + 行内重跑/日志）③系统健康人话化（去 TM 等术语）④快捷操作 8→4 ⑤趋势降级为 100px 14 日面积 strip（悬停浮层 + 贴边点钳制）。后端改动：engineering `home/kpis` 趋势 7→14 天 + 新增 todaySuccess/todayFailed/waiting + FailedItem.refId（行内重跑用）+ pendingFailed 按任务去重；前端 `pages/home/index.tsx` 整体重写。**同日指标审计修复（用户追问"口径都正确吗"触发）**：a) **恢复判定 bug（Sprint 11 F5 遗留）**——原 `countSuccessByDagIdsSince` 只查窗口内有无 SUCCESS 不比较先后，"先成功后失败"的任务会被误判已恢复从队列消失；改为 `lastSuccessTimeBy*Since`（MAX(start_time)）+ `isRecovered`（lastSuccess 必须晚于 failedAt）；b) 质量异常 pageSize 3→50（原截断导致横幅计数低估）；c) 趋势图成功率标注明确为「近 7 天」（图是 14 天口径）；d) 「平台服务」行从只看 engineering 接口改为统计全部 6 个首页接口的失败数。**两个落地教训**：①tailwind spacing 原来只有整数档，旧代码 `px-ds-1.5` 等小数档曾是无效类——已于 2026-08-16 修复（config 补齐 ds-0.5/1.5/2.5 = 2/6/10px，全站 169 处生效，回归截图无破版）；②后端 Long 序列化为字符串，前端算术前必须 Number() 归一（否则 3+"2"="32"）。**F6 导航**：未开始。
   - **F5 E2E 测试完成（2026-08-17，5/5 通过）**：`e2e/sprint11/e2e/f5-home.spec.ts`——4 域 KPI 契约 + 态势横幅判定/状态分布条 + 待处理异常队列（质量行/徽章/等待时长/查看报告）+ 系统健康 5 项 + 快捷操作 + 14 日趋势浮层。前置清理：删 `test` 遗留 DAG、恢复 `E2E-条件节点多前驱`（pendingFailed 归零）。空平台三步引导基于全局数据不可 E2E（代码审查确认）。**附带环境修复**：整栈 middleware 重启后网关 Nacos 配置加载失败→全 401，`restart app-gateway` 恢复（已记 gotchas）。
5. **F6 个人中心**：**已交付（2026-08-17）**——后端 `GET/PUT /system/auth/profile`（app-system 已部署，API 自测通过：更新/清空/非法格式 400/未登录 401）+ 前端 ProfileDrawer（440px 抽屉：身份头部/基本信息可编辑/账号安全改密，头像下拉调整，复用 ChangePasswordModal/Drawer/DsButton）。**坑记录**：MyBatis-Plus `updateById` 默认 NOT_NULL 策略不更新 null 字段 → 空串清空 email/phone 静默失败，个人中心与用户管理 `updateUser` 均已改用 `LambdaUpdateWrapper.set` 显式置 null（2026-08-17 修复 updateUser 时发现 sys_user 无 MetaObjectHandler 且 updated_at 无 DB 默认值，`updatedAt` 实际从未被维护，两处 update 已显式 `set(updatedAt, now)`）。**导航重构取消**（用户拍板），F6 需求由「导航重构」变更为「个人中心」。
6. 原型制作：~~首页数据面板~~（v3 已完成）；DAG 表单新增字段待确认是否已在原型覆盖。
7. **Nacos 热更新验证 + @RefreshScope 改造 + realtime 调度迁移（2026-08-17 完成）**：结论「logging.level.* 热、普通 @Value 不热需重启」→ 经 @RefreshScope 改造业务参数已热生效（复测 max-results 200→3 立即生效）；**JobRegistrar 支持 cron 热更新**（监听 RefreshScopeRefreshedEvent，interval-ms 改 10000 后 PowerJob cron 自动 0/5→0/10）；realtime 3 处 @Scheduled 已迁 job（统一 PowerJob 调度，符合「本地禁止 @Scheduled」约定）。**遗留提示（已完成）**：审计日志清理已补（`auditLogCleanupHandler` 每天凌晨 5 点，jobId=397）。~~pom 有 `data-service-api` 重复声明的既有 warning~~（2026-08-17 已清理，`mvn validate` 无 warning）。
