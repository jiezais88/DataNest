# Sprint 7 Handoff

> **更新时间**：2026-08-08 | **阶段**：F1~F4 全部闭环 ✅（E2E 全量 57 用例绿）→ 待收尾（§6.5）
> **Sprint 主题**：数据资产目录（+ 三项轻量增强）

## 1. Sprint 目标

让数据分析师拥有一站式数据资产发现入口——找得到、看得懂、敢使用。在现有治理能力（元数据采集、血缘可视化、数据质量管理）之上，扩展**数据搜索、数据详情聚合、血缘与质量联动、按数据域分类浏览**五项 P0 能力，并顺带交付三项轻量增强（子 DAG 参数下发、任务模板库、自定义质量规则 Python）。

## 2. 状态看板

| 事项                                     | 状态      | 说明                                                                                          |
|------------------------------------------|-----------|-----------------------------------------------------------------------------------------------|
| Sprint 7 产品范围确认                    | ✅ 完成   | 用户确认：资产目录为主 + 三项增强（NG5/DD-09/DG-10）                                          |
| Sprint 7 产品决策澄清                    | ✅ 完成   | 见「关键决策」                                                                                |
| Sprint 7 代码现状调查                    | ✅ 完成   | 资产目录从零基于 governance 扩展；血缘/质量/搜索/子DAG/PythonExecutor 复用点已核验             |
| Sprint 7 PRD                             | ✅ 完成   | `docs/sprint7/DataNest-Sprint7-PRD.md`（v1.0）                                                |
| Sprint 7 技术设计                        | ✅ 完成   | `docs/sprint7/DataNest-Sprint7-技术文档.md`（v1.0，含 4 个技术决策 D1~D4）                     |
| Sprint 7 UI 原型                         | ✅ 对齐   | 已对照真实前端 token/组件逐项修正（见 §4 变更清单），可参考 Sprint6 原型约定                      |
| **F1 资产目录**（P0：搜索/详情/血缘/质量/分类） | ✅ 完成 | 后端 ✅ + 前端 ✅ + **E2E ✅（2026-08-07，`e2e/sprint7/e2e/asset-catalog.spec.ts` 32 用例全绿）**；E2E 发现并修复 1 个前端 bug（搜索态配置负责人不刷新），见 §6.1「测试」 |
| **F2 任务模板库**（DD-09）               | ✅ 完成     | 类型范围 SYNC + COLLECT；后端+前端+E2E 全部闭环（2026-08-08，12 用例全绿，见 §6.2） |
| **F3 子 DAG 参数下发**（NG5）            | ✅ 完成    | 后端+前端+E2E 全部闭环（2026-08-08，UI 4 用例 + 执行链路 4 用例共 8 用例全绿 ×2 轮，见 §6.3） |
| **F4 Python 质量规则**（DG-10）          | ✅ 完成    | 后端+前端+E2E 全部闭环（2026-08-08，5 用例全绿含真实沙箱试跑，见 §6.4） |
| 联调验证                                 | ⏳ 未开始 | 每块内部：接口先 Postman/curl 自测，再联调前端，再 E2E                                              |
| Sprint 7 Handoff                         | 🔄 进行中 | 本文档（规划/设计阶段记录）                                                                   |

## 3. 关键决策（用户已确认）

### 产品决策（2026-08-05 确认）

| 决策点             | 结论                                                                 |
|--------------------|----------------------------------------------------------------------|
| 文档主题边界       | 资产目录为主 + 三项增强（NG5 子DAG参数透传 / DD-09 任务模板库 / DG-10 自定义质量规则） |
| S6 遗留 UI 改造    | 已实现，不纳入 S7 PRD（质量规则先选数据源再选表、行内级联选表、触发方式单选、去?tab=jobs） |
| 资产目录交付深度   | P0 五项全做（DC-01 搜索 / DC-02 详情 / DC-03 血缘嵌入 / DC-04 质量展示 / DC-05 分类浏览） |
| DG-10 边界         | A+B：新增 Python 规则类型 + 强化自定义 SQL（模板化/参数化/多指标）    |
| 资产目录落点       | 复用 governance 扩展，不新建独立 catalog-service                      |
| NG5 交付目标       | 仅主→子参数下发，不做子 DAG 结果回传                                 |

### 技术决策（2026-08-05，由我基于代码现状定，落地于技术文档）

| #  | 决策点               | 结论                                                                                                          |
|----|----------------------|---------------------------------------------------------------------------------------------------------------|
| D1 | 资产目录落点         | 复用 governance 扩展（`AssetCatalogController`/`AssetCatalogService`），不新建服务                            |
| D2 | 血缘嵌入             | 资产详情页血缘页签复用 `getLineageGraph` 数据 API + 精简 ReactFlow 自绘，**不改造现有 `LineageGraphPage`**（零影响） |
| D3 | 多维搜索接口         | **新增独立 `/assets/search`** 扁平结果接口，保留 `search-tree` 不动（血缘双击跳转等现有调用点零影响）         |
| D4 | Python 质量规则执行  | 复用 `PythonExecutor` 沙箱内核（临时目录/ulimit/超时），质量脚本用 `def check(df)` 独立约定；**数据拉取方案 B（通用连接注入）**——连接注入层从 Doris 写死抽象为通用 `conn.json` + 沙箱 `read_table` helper，用户确认 |

## 4. 变更清单（规划/设计阶段）

| 文档/产物                                                             | 变更说明                                                                                              |
|------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------|
| **F1 后端实现（2026-08-06，本段为开发阶段新增）**                      | 见下方「F1 后端变更明细」                                                                             |
| **F1 前端实现（2026-08-07，含当日修订轮）**                              | 见 §6.1「前端」+「F1 修订轮」：合并单页（方案 A）；后端补 4 接口（树计数/healthLevel/批量分配/用户选项）+ Doris 回显 + 2 处 `MetadataService` 补丁 |
| **F2 后端实现（2026-08-08，本段为开发阶段新增）**                        | 见下方「F2 后端变更明细」                                                                             |
| **F2 后端 Review（2026-08-08）** | 按 AGENTS.md §7 三点审查通过；修复 3 项（usernames 判空对齐项目惯例/PUT 编辑缺省保留原配置/停用模板错误码 7301→7307），已重建 engineering + 回归冒烟 |
| **F3 后端实现（2026-08-08，本段为开发阶段新增）** | 见下方「F3 后端变更明细」 |
| **F3 后端 Review（2026-08-08）** | 按 AGENTS.md §7 三点审查通过；修复 1 项（worker 2 参 ensureExecutionByWfInstance 重载无调用方死代码，删除），已重建 worker + 冒烟 |
| **F4 后端实现（2026-08-08，本段为开发阶段新增）** | 见下方「F4 后端变更明细」 |
| **F4 后端 Review（2026-08-08）** | 按 AGENTS.md §7 三点审查通过；修复 3 项（`@Value` 全限定名改 import/preview-execute 占位符检查对齐执行层成对语义/DTO 类型注释补 PYTHON）；开发期实证修复 2 个真 bug（沙箱禁 socket 与 pymysql 冲突、Doris 凭据取值源错误） |
| **PowerJob worker 配置优化（2026-08-08，用户确认三项全做）** | 对照 5.1.2 starter 源码 `PowerJobProperties` 全量键审查 `shared-powerjob.yaml`：① `protocol: AKKA→HTTP`（官方推荐方向）；② `store-strategy: disk→memory`（项目全部 STANDALONE+BUILT_IN 无 MapReduce，本地 H2 属死重）；③ 新增 `max-result-length: 32768`。yaml+Nacos 已同步，restart job/worker 后验证：server 日志两 App 心跳均为 HTTP、app-job 秒级任务执行+上报成功、worker 侧 `jdbc:h2:mem:` 生效。AGENTS.md §1 已同步 |
| **全量配置审计与优化（2026-08-08，用户确认全做）** | 审计 8 个 shared-configs + 7 个服务 application.yml + Nacos config_info 实况。应用 3 项：① `shared-datasource.yaml` 的 `PG_DATABASE` 去默认值 fail-fast（原默认指向已冻结旧库 `datanest`，本地 IDE 无 env 会静默误连；docker 由 compose 注入不受影响）；② `shared-common.yaml` 日志 `com.datanest: debug→${DATANEST_LOG_LEVEL:info}`（消除 mapper DEBUG 刷屏，Nacos 推送后 LoggingRebinder 热生效无需重启，已实证 job/worker 40s 内 0 行 DEBUG）；③ 4 处消费方注释勘误（datasource/common/security/doris）。核查无需动：config_info 无陈旧 dataId、InternalFeignRetryer 存在、openfeign 命名空间正确、worker/job DataSource 排除正确、gateway 路由一致。AGENTS.md §6 已同步 |
| `docs/sprint7/DataNest-Sprint7-PRD.md`（新增）                        | Sprint 7 数据资产目录产品文档 v1.0（12 章，对齐 Sprint6 PRD 范本；复用 vs 新增边界经代码核验）        |
| `docs/sprint7/DataNest-Sprint7-技术文档.md`（新增）                   | Sprint 7 技术设计文档 v1.0（11 章，含 4 个技术决策、数据模型、接口、实现清单）；v1.1 补充 Python 数据拉取方案 B（通用连接注入） |
| `docs/sprint7/handoff/sprint-7.md`（新增）                            | 本 Handoff                                                                                            |
| `docs/sprint7/DataNest-Sprint7-资产目录原型.{css,html,js}`（修正）    | UI 原型高保真对齐真实前端：token/组件/侧边栏/表格/徽章等逐项修正（见下「原型对齐要点」）                |

**F1 后端变更明细（2026-08-06，curl 自测通过）**
- `data-nest-system/.../db/migration/V3.8.0__sprint7_asset_catalog.sql`（新增）：`asset_classification` 表（uk(level,name) + 2 索引，`updated_at` 无默认值）+ `metadata_table` 加 `data_domain`/`data_topic`/`owner_user_id`。已应用（flyway_schema_history 3.8.0 success）。
- `data-nest-task-core-entity`：`MetadataTable` 加 3 持久化字段 + `ownerName`（exist=false）；新增 `AssetClassification` entity（含 LEVEL_DOMAIN/LEVEL_TOPIC 常量、children exist=false）+ `AssetClassificationMapper`；`MetadataTableMapper` 两处手写列清单加新列 + 新增 `searchAssetTables`（多维搜索，script foreach）；`MetadataColumnMapper.selectTableIdsByColumnKeyword`；`SysUserMapper.selectIdsByUsernameKeyword` + `SysUserService.findUserIdsByNameKeyword`。
- `data-nest-task-core-governance`：`QualityScoreService.mapByTableIds(Collection<Long>)` 批量方法。
- `data-nest-common`：`ErrorCode` 治理段新增 4007/4008/4009/4010。
- `data-nest-governance`：`AssetCatalogController`（`/assets`，8 端点）+ `AssetCatalogService` + DTO×5（`AssetSearchItemDTO`/`AssetClassificationDTO`/`ClassificationSaveRequest`/`AssignClassificationRequest`/`AssignOwnerRequest`）。
- `data-nest/shared-configs/shared-common.yaml`：`datanest.asset.search.max-results: 200`（`@Value` 默认值兜底，未刷 Nacos 库）。
- 与技术文档的偏差：① 分类重命名策略经用户确认为**级联更新** metadata_table 冗余名（技术文档 §3.1 只写了删除校验）；② `browse` 增加 `uncategorized=true` 参数实现「未分类」浏览（PRD §6.4）；③ 搜索得分实现为表名 100（前缀 +20）/注释 60/字段 40/负责人 20，与技术文档 §4.1 示例权重一致。
- 部署：全量 `mvn clean package` + 重建 app-system/governance/engineering/worker/job 并 up，全部 healthy（镜像时间戳已核验）。
- 自测环境残留数据：`asset_classification` 有「交易域（含主题：订单）/用户域」；`metadata_table` 的 orders/order_items 已分配交易域·订单，orders 负责人=admin。**E2E 种子数据可直接复用或清理后重建**。

**F2 后端变更明细（2026-08-08，curl 自测全过、残留已清理）**
- **范围偏差（用户确认）**：模板类型收敛为 **SYNC + COLLECT**——SQL 无独立任务实体（仅 DAG 节点类型）、EXPORT 平台不存在，本期不做；PRD/技术文档/原型已同步回落。
- `data-nest-engineering/.../db/migration/V1.5.0__sprint7_task_template.sql`（新增，**拆库后按域落 engineering 库**，非技术文档原写的 V3.8.1/system）：`task_template` 表（uk(name) + idx(type)，type 服务层白名单无 DB CHECK，`updated_at` 无默认值）+ **播种 3 条内置模板**（整表同步/增量同步（每日）/元数据全量采集，id 910000000000000001~3 固定值，created_by=NULL 前端展示「系统」）。已应用（flyway_schema_history 1.5.0 success）。
- `data-nest-common`：`ErrorCode` 新增 7301~7307（NOT_FOUND/NAME_EXISTS/TYPE_INVALID/BUILTIN_READONLY/PLACEHOLDER_MISSING/CONFIG_INVALID/CREATE_FAILED）。
- `data-nest-engineering`：`TaskTemplate` entity/mapper + DTO×4（`TaskTemplateDTO`/`TaskTemplateSaveRequest`/`TemplateCreateTaskRequest`/`CreateTaskResultDTO`）+ `TaskTemplateService` + `TaskTemplateController`（`/task-templates`，5 端点，全部超管/工程师 OR）：GET 列表（type/category 过滤，createdByName 经 system-api 批量回填降级空 Map）、POST 新增（`sourceTaskId` 非空时从任务另存为——SYNC 本地读 sync_job、COLLECT 经 `CollectWriteApi.getTask` 远程读；配置原样保留，单表 SYNC 源表与 CRON 自动占位化为 `{source_table}`/`{schedule_cron}`）、PUT 编辑（内置 7304、type 不可变）、DELETE（内置 7304、快照式无引用校验）、POST `/{id}/create-task` 一键创建。
- **B4 config_template 结构定稿**：`{"placeholders":[{key,label,required,valueType(TEXT/DATASOURCE),defaultValue}],"config":{对应类型创建请求，字符串值含 {key} 占位符}}`；一键创建 = 校验 config 中出现的占位符全部有值（用户填写优先，其次 defaultValue，否则 7305）→ 文本替换（引号/反斜杠转义）→ fastjson2 反序列化 → 手动 Bean Validation → SYNC 复用 `SyncJobService.create` 本地落库；COLLECT 走下述内部端点远程落库（fail-closed，Result.code≠200 时透传 governance 错误消息）。
- `data-nest-governance-api`：`CollectWriteApi` 新增 `createTask`（`POST /governance/internal/collect/tasks`）+ `CollectTaskCreateInternalRequest` DTO（对齐 CollectTaskCreateRequest + createdBy 显式传入；**api 模块无 jakarta.validation 依赖，字段校验在 governance 服务端手动做**）+ fallback 补方法（降级返回 null，调用方按 null 判失败）。
- `data-nest-governance`：`CollectTaskService.create` 抽 `doCreate` 私有方法 + 新增 `createInternal`（手动非空校验 + createdBy 用传入值）；`CollectWriteController` 注入 CollectTaskService 加端点。
- 部署：重建 app-engineering + app-governance 并 up，healthy，Flyway 1.5.0 ✅。
- curl 自测全过：列表/过滤/无 token 1004、内置 3 条播种、SYNC 一键创建落 sync_job（DB 验证）、COLLECT 一键创建跨服务落 collect_task（governance 库验证，createdBy=1）、7301/7302/7303/7304/7305/7306 各校验、从任务另存为（多表 SYNC 不抽 source_table 占位）、编辑/删除自定义、createdByName 回填 admin。自测残留（2 模板+1 sync_job+1 collect_task）已经 API 删除清理。
- **坑**：Windows Git Bash curl `-d` 内联中文按 GBK 编码会触发服务端 `Invalid UTF-8 start byte`（9999 假象）——自测含中文的 JSON 体一律写临时 UTF-8 文件用 `--data-binary @file`。

**F2 后端 Review（2026-08-08，按 AGENTS.md §7 三点审查 + 修复回归）**
- 结论：架构融洽 ✅（Feign 契约/内部端点/拆库落点/容错三件套均合约定）、业务正确 ✅（修复后）、实现高效 ✅（无 N+1，usernames 批量回填）。
- 修复 3 项（已重建 engineering + 回归冒烟通过）：① `list()` 的 `systemUserApi.usernames(...).data()` 未按项目惯例判空（Result 非 200 返回 null data 时 usernameMap=null → NPE 9999），已对齐 AssetCatalogService 的判空写法；② PUT 编辑原先必须重传完整 configTemplate（只改名称/说明会 7306），改为两者缺省保留原配置；③ 模板停用一键创建的错误码从 7301（语义不符）改 7307 +「模板已停用，无法创建任务」。
- 遗留观察项（不改，前端开发时注意）：① 占位符约定必须位于 JSON 带引号字符串值内（播种模板均如此，列注释已说明）；② create/update 为 @Transactional 且另存为 COLLECT 时在事务内做只读 Feign getTask（短调用低风险）；③ 无启用/停用端点（原型亦无开关 UI，enabled 当前只读）。

**F3 后端变更明细（2026-08-08，curl 自测全过、残留已清理）**
- **范围扩展（用户确认 2026-08-08）**：技术文档 §4.4/§5.4 只写了 SubDagTriggerController（异步链路），但同步执行（`syncExecution=true`，默认方式，走 PowerJob NESTED_WORKFLOW 嵌套节点）也必须覆盖，否则默认配置下功能是坏的——**同步+异步双链路都实现**，技术文档已同步回落。
- `data-nest-task-core`：`SubDagNodeConfig` 加 `paramMappings: List<ParamMapping>`（`{mainParam, subParam}`，契约 DTO；config TEXT JSON 持久化，无迁移，旧数据 null 视为不传参）。
- `data-nest-common`：`ErrorCode` 新增 7106 `SUB_DAG_PARAM_INVALID`。
- `data-nest-engineering`：新增 `SubDagParamMappingResolver`（双链路共用：按父节点 config 的 paramMappings，从父执行上下文 resolvedParams〔为空时按默认值+系统变量现算〕映射出子 DAG 覆盖集；`${name}`/裸名归一化；主参数无值 warn 跳过不阻断，对齐 DagParameterResolver 容错语义）；`DagService.validateSubDagParamMappings`（保存校验 PRD R5：mainParam/subParam 必填、subParam 归一化后映射内唯一、mainParam 必须在主 DAG 已声明参数或系统变量 biz_date/current_time/dag_id 中，新建 DAG 无 id 跳过存在性校验）；`DagExecutionService.triggerSubDag`（**异步链路**：查父 DAG 当前 RUNNING 执行 → 解析覆盖集 → `trigger(subDagId, overrides)`，无映射等价原 `trigger(subDagId)`；注意必须 `@Transactional`，self-invocation 下 trigger 的 registerSynchronization 依赖外层事务）；`SubDagTriggerController` 改调 triggerSubDag（body 契约 `{dagId,nodeId,subDagId}` 不变，worker 侧零改动）。
- **同步链路**：engineering-api `EnsureDagExecutionRequest` 加 `parentDagExecutionId`（可选，向后兼容）→ worker `AbstractDagNodeHandler.resolveDagExecutionId` 归属不匹配（即嵌套场景）时透传父执行 ID → `DagNodeExecuteService.ensureExecutionByWfInstance` 3 参 → engineering `InternalDagExecutionService.ensureExecutionByWfInstance` 创建子执行记录时按父节点 paramMappings 解析覆盖集，非空则落 `resolved_params`（`resolveParams(子dagId, overrides)` 全量解析值，节点执行时优先级最高；空保持 null 原语义）。
- 部署：全量 `mvn clean package` + 重建 app-engineering/app-worker 并 up，healthy（镜像时间戳已核验）。
- curl 自测全过（复用 P4 夹具：父异步 DAG 2085695316252241921 / 父同步 DAG 2085695302939521026 / 子夹具 2085694953528832001）：保存校验 dup subParam 7106、未声明 mainParam 7106；**异步链路**触发父（biz_date=2026-08-01 手动覆盖 + env=prod 默认值）→ 子执行 resolved_params 含 `sub_date=2026-08-01, sub_env=prod`，SUCCESS；**同步链路**（NESTED_WORKFLOW，trigger_type=SCHEDULED 补齐记录）→ 子执行含 `sub_date=2026-08-02, sub_env=prod`，SUCCESS；**边界**：主参数声明但无值（empty_p 无默认值未覆盖）→ warn 跳过、其余正常下发、不阻断（日志实证）；**回归**：还原映射后触发 → 子执行无 sub_*（旧语义不变）。自测残留（3 参数 + 8 执行 + 12 节点执行 + 8 日志）已清理，节点 config 已还原。

**F3 后端 Review（2026-08-08，按 AGENTS.md §7 三点审查 + 修复回归）**
- 结论：架构融洽 ✅（契约向后兼容/映射计算全落持库方 engineering/worker 无库原则未破/无 N+1）、业务正确 ✅（双链路自测实证 + 边界 + 回归）、实现高效 ✅（修复后）。
- 修复 1 项：`DagNodeExecuteService.ensureExecutionByWfInstance(dagId, wfInstanceId)` 2 参重载在唯一调用点改 3 参后成死代码，已删除 + 重建 worker + 冒烟。
- 遗留观察项（不改）：① 同步路径 `resolveParentParams` 在 ensure 锁内可能 Feign 自调用 listParameters——仅 cron 父 + 有映射 + resolvedParams 为空时触发，短调用低频可接受；② triggerType 语义保持既有（异步子=MANUAL、同步子=SCHEDULED）未动；③ `paramMappings` 配成非数组类型会被外层 catch 归为「config JSON 解析失败」（SQL_PARSE_FAILED），语义可接受；④ 前端 `types.ts` 的 `SubDagNodeConfig` 加 `paramMappings` 类型留待 F3 前端阶段。

**坑（本次实证）**：① docker exec 不加 `-i` 时 heredoc SQL 不会执行（stdin 关闭，psql 立即 EOF）——管道/heredoc 传 SQL 必须 `docker exec -i`；② `DagExecutionService.triggerSubDag` 调同类 `this.trigger()`（self-invocation 代理失效），必须自身加 `@Transactional` 否则 trigger 内 `registerSynchronization` 抛 `Transaction synchronization is not active`。

**F4 后端变更明细（2026-08-08，curl 自测全过、残留已清理）**
- **口径确认（用户 2026-08-08）**：① CUSTOM_SQL 强化新增**执行预览端点**（非零新增）；② 新增 `test-script` 端点（原型「测试脚本」按钮）；③ Python 驱动装 `psycopg2-binary` + `oracledb`（thin 模式；Oracle 测试库在 compose 注释中，本期不可验证）。
- Flyway `data-nest-governance/.../db/migration/V1.3.0__sprint7_quality_python.sql`（**governance 库**，非技术文档原写的 V3.8.2/system）：`quality_rule_template_type_check` drop 重建加 `PYTHON` + `python_template`/`python_script` 列。已应用（flyway_schema_history 1.3.0 success）。
- `data-nest-task-core`：`PythonExecutor` 新增 `executeQualityCheck`（`doExecute` 收敛原 `execute`，DAG 节点路径零变化）——质量模式写 `conn.json` + 注入 `read_table(table, where=None, limit=None)` helper（按 type 选 pymysql/psycopg2/oracledb；库/schema/表名标识符 `^[A-Za-z0-9_]+$` 白名单 + 引号包裹防注入；oracle 用 FETCH FIRST）+ 收尾 `check(read_table(目标表))` → dict 写 `output.json.check_result`；新增 `PythonConnectionResolver`（共享：datasourceId 空/-1 → Doris 静态配置，否则 Feign 读数据源 + 解密；目标表 databaseName/schemaName 覆盖连接默认库）；`QualityCheckService` PYTHON 分支（`executePythonRule` + `extractPythonMetric`：dict 按 resultMetric 取键，缺失/脚本失败 → UNAVAILABLE）；`DorisDataSourceConfig` 加静态凭据 getter；DTO/契约：`QualityRuleDTO.pythonScript`、Create/Update 请求加 `pythonScript` + `@Pattern` 加 PYTHON + `@AssertTrue isPythonValid`（脚本+resultMetric 必填）、模板三 DTO 加 `pythonTemplate`。
- `data-nest-governance-api`：`QualityExecutionPlanDTO.RulePlanItem` 加 `pythonScript/databaseName/schemaName`。
- `data-nest-governance`：实体 `QualityRule.pythonScript`/`QualityRuleTemplate.pythonTemplate`；`QualityRuleService`/`QualityRuleTemplateService` 白名单 + PYTHON（create/update 模板必填排除 PYTHON、批量应用脚本从模板带出且模板无脚本拒绝、`preview-sql` 对 PYTHON 返回脚本、toDTO 透出）；`QualityExecutionService.toPlanItem` 透新字段 + PYTHON 跳过 SQL 生成 + 告警中文标签加「Python」；新端点 `POST /quality/rules/test-script`（governance 本地沙箱试跑，返回 `success/result(dict)/error/durationMs`）+ `POST /quality/rules/preview-execute`（CUSTOM_SQL 占位符展开后真实执行，仅 SELECT/WITH + 占位符残留拦截，Doris 走 DorisSqlExecutor 截 50 行、注册数据源走 GenericSqlExecutor；返回 `columns+rows+truncated` 供多指标选 resultMetric）。新 DTO×4（governance dto 包）。
- `data-nest/shared-configs/shared-quality.yaml`：`datanest.quality.python.timeout-seconds: 300`（已发布 Nacos）。
- 镜像：worker + governance 均 `pip install pandas pymysql psycopg2-binary oracledb`（governance 新增 Python 环境供 test-script）。
- **开发期实证修复 2 个真 bug**：① 沙箱 `_FORBIDDEN_MODULES` 禁 `socket`，纯 Python 的 pymysql 建连必 `ImportError`（psycopg2/oracledb 是 C 扩展不受影响）——质量模式放开 socket（脚本为治理员/超管配置，连库是本职），DAG 节点保持禁令；② Doris 凭据原从 system property 读（`System.getProperty` 拿不到 `@Value` 注入值），密码恒为空串认证必败——`DorisDataSourceConfig` 加静态凭据 getter，`PythonExecutor.resolveDorisConfig` 与 `PythonConnectionResolver` 统一改从静态配置取。**注意：①② 意味着 DAG Python 节点的 `read_doris_table` 存量同样不可用（socket 禁令 + 密码空），本次只修质量链路，DAG 节点侧是否放开留待用户决策。**
- 部署：全量 `mvn clean package` + 重建 5 个 task-core 消费方镜像（engineering/worker/governance/job/system），healthy，镜像时间戳已核验。
- curl 自测全过：模板 PYTHON 创建/非法类型 4203、规则缺脚本/缺指标 400、test-script 三驱动（Doris null_rate=0.0 / MySQL 0.667 / PG 0.25）+ `read_table(limit=2)` 采样 + 失败脚本 traceback 透传、单规则执行三档（PASS/WARNING/UNAVAILABLE 落 detail）、批量应用 PYTHON 模板（脚本落库 + 绑定任务）、update 编辑脚本保留、preview-sql 对 PYTHON 返回脚本、preview-execute 多指标列（`total/nulls`）+ DML/占位符拦截（7015）、无 token 1004。自测残留（4 规则 + 1 模板 + 3 批次/明细 + 2 临时 metadata 行 + PG 表）已清理。
- **存量观察（非 F4 引入，不改）**：共享单规则质量 job（id=45）`maxInstanceNum=1`，快速连续触发多个单规则执行会被 server 丢弃（"too much instance is running"）且实例直接 FAILED——前端批量连续点「执行」会静默丢失，建议后续把该 job 的 maxInstanceNum 调大或排队。

**F4 后端 Review（2026-08-08，按 AGENTS.md §7 三点审查 + 修复回归）**
- 结论：架构融洽 ✅（方案 B 通用连接注入落 task-core 共享、契约加可选字段向后兼容、worker 无库原则未破、conn.json 临时目录执行后即清）、业务正确 ✅（三驱动三档自测实证）、实现高效 ✅（无 N+1，连接解析共享复用）。
- 修复 3 项：① `QualityCheckService` 的 `@Value` 全限定名改 import（风格一致）；② `preview-execute` 占位符检查从 `contains("{")` 对齐执行层 `assertNoUnresolvedPlaceholder` 成对语义（避免 SQL 内合法单 `{` 误伤）；③ DTO 类型注释补 PYTHON（3 处）。
- 遗留观察项（不改，前端/后续注意）：① `read_table` 默认全表拉取（大表风险由沙箱 2GB 内存 + 300s 超时兜底，脚本内可 `where/limit` 采样）；② `oracledb` thin 模式代码路径未实测（无 Oracle 环境）；③ `test-script` 在 governance 容器跑沙箱，超大结果集可能占 governance 内存（与执行侧同限 2GB）；④ e2e 种子数据源 `e2e_s7_mysql_ds` 密码为空会触发解密异常（种子数据问题，自测改用了真实数据源）。

**原型对齐要点（2026-08-05-06，对照真实前端源码 + 自动化截图逐项修正）**
- **自动化截图对比**：曾用临时 Playwright Python 脚本（已删）截真实前端 3 页（`/governance/metadata` 展开树、`/governance/data-quality`、`/governance/quality-templates`）+ 截原型 5 视图（assets/classification/task-template/subdag/python-rule）逐屏对比。脚本核心要点已固化到 `docs/agent/prototype-guide.md §7`（UI 登录、press Enter 提交、flex 滚动诊断等）。
- **截图对比结论**：核心对齐已达成 — 侧边栏 `#334155` + indigo active、卡片圆角 12px、表头 uppercase + bg-root、工具栏查询按钮顺序、行高紧凑、质量徽章中文两段式、**数据库品牌图标（Doris 圆柱 / MySQL 多边形）**、**LogoMark 三层渐变六边形**、**极简分页**（上一页/1/下一页）、**操作列 icon-btn 风格**、**info notice 浅紫底**，与真实前端几乎一致。

**2026-08-06 残余差距补齐（自动化截图二次迭代）**
- **数据库品牌图标**：替换 `db-badge` 字母方块为真实 `i-doris` 圆柱 SVG（`#1E90FF` 蓝）和 `i-mysql` 多边形 SVG（`#4479A1`），对齐 `DatabaseTypeIcon` 组件
- **LogoMark 升级**：3 层六边形 + indigo 渐变（`#4f46e5 → #818cf8`），对齐 `LogoMark` 组件
- **极简分页**：View 1 分页从"5 个页码 + 上下页"简化为"上一页/1/下一页"，对齐真实 antd Pagination 紧凑模式
- **视图切换修复**：截图脚本 `data-view` 名修正为单数 `task-template`（匹配 HTML），5 个视图全部正确截图
- **Python view 滚动修复**：`.info-card { overflow:hidden }` 默认 `flex-shrink:1` 被 layout-main-inner flex 压缩，内部溢出 494px 被静默裁切（看似没滚动条）。修复：加 `flex-shrink:0` 让 info-card 自然撑高，inner 出现滚动条（sh=1338, ch=844，溢出 494px 可滚）。**坑已固化到 `docs/agent/prototype-guide.md §7 flex 容器滚动陷阱`**。

**原型对齐要点（2026-08-05，对照真实前端源码逐项修正）**
- **颜色 token**：accent 由 blue `#1d4ed8` 改 indigo `#4f46e5`（品牌主色，最关键差距）；bg-root `#eef0f7`、border `#d8dee8`、text-secondary `#475569`、text-muted `#64748b` 全部对齐 `src/styles/tokens.css`。
- **侧边栏**：`#111827` 近黑 → `#334155` slate-700 中等深 + slate-600 边框；item `py-9px rounded-lg`、active `bg-accent/25 text-white font-semibold`、group-label 11px uppercase。
- **圆角/阴影**：radius-sm 6→8px、md 8→12px、lg 10→16px；shadow-xs 对齐 `0 1px 2px rgba(0,0,0,.06)`。
- **表格**（antd）：表头 `bg-root` + `2px` 底边框 + `11px uppercase letter-spacing .5px`；行 `10px 16px` 紧凑 + `1px` 底边框 + hover bg-hover。
- **质量徽章**：英文 `85 GOOD` → 真实中文两段式「数字(13px粗体) + 胶囊(11px)」（良好/优秀/一般/差），`QUALITY_HEALTH_LABEL` 对齐 `types/quality.ts`。
- **DsButton/DsToolbar**：按钮 `px-16px py-8px 13px 600`；工具栏 `p-12px gap-12px`，查询在前/重置在后。
- **SearchInput/DsFilterSelect**：搜索框 `bg-root 浅灰底 py-9px min-w-240 max-w-360`；筛选 `min-w-140 py-8px`。
- **数据源徽章**：字母方块改品牌色（Doris `#1e90ff`、MySQL `#4479a1`）。
- **Pagination**：无边框数字钮，active `bg-accent`，左「共N条」右页码 justify-between。
- **DsModal**：content `padding 36px radius 16px`、标题 18px 700（对齐 `prototype-modal`）。

## 5. Blocker / 待实现确认点

| #  | 事项                                | 说明                                                                      | 状态   |
|----|-------------------------------------|---------------------------------------------------------------------------|--------|
| B1 | Python 质量规则数据拉取            | ✅ 已消解（方案 B，用户确认）：`PythonExecutor` 连接注入层抽象为通用连接注入（`conn.json`）+ 沙箱 `read_table(table, where, limit)` 按数据源 type 选驱动；不再用 `GenericSqlExecutor`（5s 超时+200 行截断不适合） | 明确 |
| B2 | 资产详情页路由与元数据详情页关系    | `/assets/:tableId` 独立路由，与现有 `/governance/metadata?tableId=` 双入口并存 | 明确   |
| B3 | 分类体系删除校验 SQL                | 删除分类时按 `data_domain`/`data_topic` 匹配 `metadata_table` 引用        | 明确   |
| B4 | 任务模板 config_template JSON 结构  | ✅ 已消解（2026-08-08 定稿）：`{"placeholders":[{key,label,required,valueType,defaultValue}],"config":{对应类型创建请求}}`；类型范围同步收敛 SYNC/COLLECT | 明确 |

## 6. 开发分阶段计划

> **划分原则**：按功能块切分，**每块 = 后端 → 前端 → 测试 完整闭环**，全部验证通过后再进入下一块。不做"先全部后端、再全部前端"的横切。
> **顺序**：F1（P0 主功能）优先；F2/F3/F4 为顺带增强，按**从易到难**（纯新增独立 → 改共享实体但简单 → 需 task-core + worker 镜像改造）。
> **每块验证口径**：① 后端 Postman/curl 自测 → ② 前端联调 → ③ 新建 `e2e/sprint7/e2e/*.spec.ts` 跑通 → ④ 更新本 Handoff 状态看板。

---

### ✅ 已完成（规划/设计）

- [x] Sprint 7 PRD（`DataNest-Sprint7-PRD.md` v1.0）
- [x] Sprint 7 技术设计（`DataNest-Sprint7-技术文档.md` v1.0）
- [x] 代码现状核验：搜索仅表名、子DAG不支持透传、质量类型无PYTHON、元数据无分类/负责人字段、PythonExecutor 沙箱已存在、Flyway 最高 V3.7.1
- [x] UI 原型高保真对齐（见 §4）

---

### 6.1 F1 资产目录（P0，核心块）🔄 后端完成（2026-08-06）

**范围**：DC-01 搜索 / DC-02 详情聚合 / DC-03 血缘嵌入 / DC-04 质量展示 / DC-05 分类浏览。
**块内依赖**：Flyway → task-core-entity → governance 服务 → 前端 3 页 → 联调。

**后端**（✅ 全部完成，curl 自测通过）
- [x] Flyway `V3.8.0`：`asset_classification` 表 + `metadata_table` 加 `data_domain`/`data_topic`/`owner_user_id`（已应用，库中验证成功）
- [x] task-core-entity：`MetadataTable` 扩展 3 字段 + `ownerName`（exist=false）；新增 `AssetClassification` entity/mapper；`MetadataTableMapper` 两个手写 `@Select` 列清单已同步加新列 + 新增 `searchAssetTables`（多维搜索 SQL）；`MetadataColumnMapper.selectTableIdsByColumnKeyword`；`SysUserService.findUserIdsByNameKeyword`
- [x] task-core-governance：`QualityScoreService.mapByTableIds`（批量回填，避免 N+1）
- [x] common：`ErrorCode` 新增 4007 CLASSIFICATION_NOT_FOUND / 4008 NAME_EXISTS / 4009 IN_USE / 4010 PARENT_INVALID
- [x] governance `AssetCatalogService`/`AssetCatalogController`（`/assets`）：`GET /search`（五维命中 + 相关度：表名 100+前缀 20/注释 60/字段 40/负责人 20）、分类树/CRUD（**改名级联更新 metadata_table 冗余名，用户确认**；删除校验引用 4009）、`GET /browse`（domain/topic/datasourceId/uncategorized/sort=score，分页）、`PUT /tables/{id}/classification`、`PUT /tables/{id}/owner`（类级四角色 OR，写接口治理员/超管）
- [x] `shared-configs/shared-common.yaml` 加 `datanest.asset.search.max-results: 200`（`@Value` 默认值兜底，Nacos 库未刷）
- [x] **全量重建并部署** app-system（Flyway V3.8.0 ✅）+ governance/engineering/worker/job，全部 healthy，镜像时间戳已确认
- [x] curl 自测全过：分类 CRUD/树、4007/4008/4009/4010 校验、四维搜索命中与 score 排序（前缀 120>表名 100>注释 60>字段 40>负责人 20）、质量分/健康度/负责人名/数据源回填、browse 分页/sort=score/未分类、改名级联（交易域→交易→改回均一致）、删除引用拦截 + 无引用可删、无 token 1004

**前端**（✅ 全部完成，2026-08-07 联调冒烟全绿；含当日修订轮）
- [x] `Sidebar.tsx` 新增「数据资产」组（单入口 ALL_ROLES）+ `router/index.tsx` 新增 `/asset-catalog`、`/asset-catalog/:tableId`
- [x] 数据资产首页（左树右表：分类树带计数 + 搜索/浏览双态表格，复用 `QualityScoreBadge`/`DsStatusBadge`/`Pagination`/`usePagedList`）
- [x] 资产详情页：三指标卡（质量评分/字段数/直接上下游表数，砍「更新频率」——无数据源）+ 四页签懒加载（基础信息/字段/血缘/质量；血缘页签复用 `getLineageGraph` + 精简 ReactFlow 自绘 `AssetLineageTab`，不改造现有 `LineageGraphPage`）
- [x] 分类维护**合并进首页**（方案 A，用户确认 2026-08-07：原独立「分类体系」页与首页产品定位混淆，已删页/删路由/删侧边栏项）：治理员在首页直接看到树编辑/删除、新增数据域/主题、分配表到分类、操作列（负责人/移出），按 `GOVERNANCE_WRITE_ROLES` 显隐
- [x] 新增 `src/types/asset.ts` + `src/api/asset.ts`（端点封装）；`types/metadata.ts` 补 `dataDomain/dataTopic/ownerUserId/ownerName`

**F1 修订轮（2026-08-07，用户反馈驱动：两页定位混淆 + 下拉不即时 + Doris 不回显 + 原型差距）**

后端补充（governance/system，curl 自测全过，已重建部署）：
- `GET /assets/classifications` 响应改为 `{list, totalCount, uncategorizedCount}`，节点带 `tableCount`（一次 GROUP BY 聚合，ONLINE 口径与 browse 一致）。**坑**：PostgreSQL 未加引号的驼峰别名会被折叠成小写，`AS dataDomain` 的 map key 取不到 → 别名用下划线小写。
- `GET /assets/browse` 加 `healthLevel` 参数（`QualityScoreService.findTableIdsByHealthLevel` 反查表 ID 拼 IN，无命中直接空页）；`GET /assets/search` 加 `datasourceId`/`healthLevel` 可选过滤。
- 新增 `PUT /assets/tables/classification/batch`（`tableIds[]` + domain/topic，一次校验一条 UPDATE，返回更新数）→ 前端不再循环单表 PUT。
- 新增 `GET /system/users/options`（`UserSelectorController`，超管/治理员）：全部启用用户轻量选项，**不要求邮箱**（替代复用 with-email 的限制）；负责人选择器已切换。
- 内置 Doris 回显：`toItemDTO` 按 `source_type=BUILTIN_DORIS` 兜底「Doris 数仓 / DORIS」（engineering 无连接记录）；资产详情页基础信息同口径前端兜底。
- 前端即时筛选：数据源/健康度下拉变更即查（不再要求点查询按钮），搜索态同样传后端。
- 前端布局：资产目录首页改**固定撑满 + 栏内独立滚动**（对齐元数据管理页 `h-[calc(100vh-9rem)]` 模式；分页器/工具栏常驻，树与表格各自滚动）。「双栏页固定撑满、单栏列表页整页滚动」规则已写入 `docs/agent/conventions-frontend.md` §8。
- 体验修复 3 项（2026-08-07 用户反馈）：① 资产详情返回改为平台惯例「← 返回」secondary 按钮；② 血缘图谱页支持 `from=asset-catalog` 参数，「查看完整血缘」后返回资产详情而非元数据管理（节点单击切换图谱时保留 from）；③ **字段血缘 drawer 关闭后表血缘消失 bug**——`FieldLineagePanel` 的 ReactFlow 未包独立 `ReactFlowProvider`，复用页面级 Provider 的 store，卸载时清掉主图节点，已修复；drawer 同步加宽 860px + 画布撑满 + Controls + EmptyState 空态。坑已固化到 gotchas §三。

其余偏差记录：
- **路由 `/assets` → `/asset-catalog`**（用户确认）：nginx `location /assets/` 是 Vite 静态产物目录；PRD/技术文档已同步。
- 树计数/健康度筛选/批量接口/负责人口径 → 本轮已全部补齐，不再是偏差。
- 后端补丁（`MetadataService.applyUsernameNames`）：`getTable` 补 `ownerName` 回填；修存量 NPE（immutable map `get(null)`，自动采集表 created_by 为 null 时详情 500），三处判空。

**测试**（后端自测 ✅ / 联调 ✅ / E2E ✅ 2026-08-07）
- [x] 后端 Postman/curl 自测：`/assets/search`、分类 CRUD、分配分类/负责人、改名级联、删除校验 + 修订轮（树计数/healthLevel/search datasourceId/批量/用户选项/Doris 回显）
- [x] 前端联调冒烟（临时 Playwright 脚本，用完即删，共 5 轮）：合并页计数徽章、下拉即时过滤（BAD→1 行 orders）、管理条、Doris 回显、三指标卡、分类增删、批量分配+移出还原、负责人清除/重设（新选项接口）、删除被引用分类 4009 拦截——全绿无控制台错误，种子数据已还原
- [x] `e2e/sprint7/e2e/asset-catalog.spec.ts`（32 用例全绿，1.2 分钟；共 9 轮迭代修选择器/断言）：DC-05 树计数+浏览过滤+即时筛选、DC-01 四维搜索+空态+重置+搜索态筛选、DC-02/03/04 详情页（三指标卡/字段/血缘 from 回跳/空态/质量页签/Doris 兜底/返回）、DC-05 维护（增删域/主题、改名级联 UI+DB 双断言、4009/子分类拦截、批量分配+移出、详情页分配/清除分类、负责人配置/清除）、权限隔离（engineer/analyst UI 隐藏 + API 拒绝）、API 辅助（sort=score、相关度分值 120/60/20、healthLevel/datasourceId、树计数、批量接口返回数、execute 冒烟）
- [x] **E2E 发现并修复 1 个前端 bug**：搜索态下「配置负责人」保存成功但列表不刷新——`AssignOwnerModal onSaved={reload}` 只刷浏览态 usePagedList；修复为 `reloadCurrent`（搜索态 bump `searchRefreshKey` 重搜，浏览态 reload），`src/pages/assets/index.tsx`。已 `pnpm build` + 重建 app-frontend 验证
- [x] F1 全块闭环，§2 看板置 ✅

**E2E 设施说明（sprint7 新增，后续 F2/F3/F4 复用）**
- `e2e/sprint7/helpers/db.ts`：拆库版 psql（`psqlGov/psqlEng/psqlSys`）。注：sprint5/6 的 `helpers/db.ts` 已于 2026-08-08 完成拆库适配（按表自动路由域库，跨库语句抛错），各 sprint db 模块均可使用；新增业务表需补 sprint5 `TABLE_DB` 映射。
- `e2e/sprint7/helpers/seed.ts`：幂等播种（e2e_s7 前缀 + 固定 ID 900007*）——3 测试用户（s7_govadmin/engineer/analyst）、e2e_s7_mysql_ds 数据源、5 张元数据表（含 1 张 BUILTIN_DORIS）、分类体系（交易域[订单/退款]+用户域）、4 档质量评分、T1 的 3 规则+1 批次 3 明细、2 条血缘。spec beforeAll 自带播种，支持 `SKIP_SETUP=1` 独立运行；global-setup/teardown 已注册 sprint7。
- F1 开发自测残留（交易域/用户域分类 + orders/order_items 分类/负责人）已经用户确认在 seed 中清理（`cleanupResidue`）。
- 跑法：`cd data-nest/data-nest-frontend && SKIP_SETUP=1 npx playwright test e2e/sprint7/e2e/asset-catalog.spec.ts`

---

### 6.2 F2 任务模板库（DD-09）✅ 全部完成（2026-08-08）

**范围**：任务模板 CRUD + 一键创建。**类型范围经用户确认收敛为 SYNC + COLLECT**（SQL/EXPORT 不做，见 §4 F2 明细）。
**块内依赖**：Flyway → engineering 服务（entity 随服务本地，task-core-entity 已不存在）→ governance-api/governance 内部端点（COLLECT）→ 前端 1 页 → 联调。

**后端**（✅ 全部完成，curl 自测通过，明细见 §4「F2 后端变更明细」）
- [x] Flyway `V1.5.0`（engineering 库）：`task_template` 表 + 3 条内置模板播种（`updated_at` 无默认值）
- [x] common：`ErrorCode` 7301~7307
- [x] engineering `TaskTemplateService`/`TaskTemplateController`：模板 CRUD + 一键创建（SYNC 本地落 sync_job）+ 从任务另存为
- [x] governance-api `CollectWriteApi.createTask` + governance `CollectTaskService.createInternal`/`CollectWriteController` 端点（COLLECT 跨服务一键创建，fail-closed）
- [x] 重建 engineering + governance 并部署（healthy，Flyway 1.5.0 ✅）；worker/job 无需重建（不消费新端点/新错误码）

**前端**（✅ 全部完成，2026-08-08 联调冒烟全绿）
- [x] `Sidebar.tsx` 新增「任务模板库」（数据开发组，ENGINEERING_WRITE_ROLES）+ `router/index.tsx` 新增 `/engineering/task-templates` + 面包屑
- [x] 任务模板库页（`pages/engineering/task-templates/`）：segmented 类型分组（全部/同步/采集，前端过滤）+ 内置/自定义徽章 + 占位参数列 + 状态列 + 操作列（fixed right）：一键创建（停用禁用）/内置仅「复制为自定义」/自定义编辑+删除；底部 info notice（快照式说明）
- [x] 新增 `src/types/taskTemplate.ts` + `src/api/taskTemplate.ts`；`CreateTaskModal` 按 `placeholders` 动态渲染（`valueType=DATASOURCE` → 数据源下拉，defaultValue 预填，required 前端校验 + 后端 7305 兜底）；`TemplateFormModal` 新增/编辑/复制三态（编辑仅传名称/说明保留原配置；另存为与手动 JSON 二选一，类型切换重置候选任务）

**前端 Review（2026-08-08，按 AGENTS.md §7 三点）**
- 架构融洽 ✅：API 走统一封装新风格（`.then(r=>r.data)`）；权限前后端一致（ENGINEERING_WRITE_ROLES）；复用 Ds* 组件/notify/COL/formatDateTime，无新依赖、无硬编码颜色；单栏列表页按 §8 约定整页滚动（未滥用固定撑满）
- 业务正确 ✅：占位符表单对齐 B4 结构；编辑语义对齐后端「缺省保留原配置」；内置只读 + 复制路径走手动配置；停用模板按钮禁用（7307 兜底）
- 实现高效 ✅：列表全量一次拉取前端过滤；数据源下拉仅在含 DATASOURCE 占位符时拉取；另存候选按类型懒拉一次；无 N+1/轮询
- 说明：一键创建成功仅 toast 不跳页（对齐原型「生成任务」语义）；`parseTemplatePlaceholders` 从组件文件导出有一条 react-refresh warning（项目已有同类，可接受）
- 追加修订（2026-08-08 用户反馈）：模板新增/编辑/复制表单由居中 DsModal 改**右侧 Drawer**（`TemplateFormDrawer`，640px）——平台惯例：实体主表单全部走右侧 Drawer（数据源/同步/采集/质量任务等 8 处同类）；存量例外**用户管理 `UserModal` 一并改为 `UserDrawer`**。「弹窗 vs 抽屉分工」规则已写入 `docs/agent/conventions-frontend.md` §7。
- 追加修订 2（2026-08-08 用户确认）：列表由全量 GET 改 **`POST /task-templates/page` 分页**（对齐 AGENTS §9 列表页约定；service `listPage` + `attachCreatedByName` 抽公共回填，GET list 保留），前端切 `usePagedList` + `Pagination`，segmented 改服务端过滤，「内置 X · 自定义 Y」计数文案移除（由分页器「共 N 条」承担）。E2E 断言同步适配，全量 44 用例回归全绿。

**测试**
- [x] 后端 Postman/curl 自测：模板 CRUD、7301~7306 校验、一键创建后 sync_job/collect_task 落库（残留已清理）
- [x] 前端联调冒烟（临时 Playwright 脚本，用完即删）：列表/segmented 过滤/内置计数、一键创建 SYNC（填占位符 → sync_job 落库已验证并清理）、新增手动 JSON、另存为（选任务隐藏 JSON 框）、编辑（类型锁定）、删除、内置只读按钮集——全绿无控制台错误
- [x] 新建 `e2e/sprint7/e2e/task-templates.spec.ts`（12 用例，serial）：列表/segmented/计数文案、权限隔离（分析师无入口+API 1005）、一键创建 SYNC×2（占位符表单/前端必填校验/cron 默认值/DB 落库验证）、COLLECT 跨服务落库、CRUD（手动 JSON/重名 7302/另存为占位化/编辑类型锁定/复制内置/删除/内置只读按钮集）。seed 新增 F2 fixture（另存为候选 sync_job 固定 ID + e2e_s7% 模板/任务清理）
- [x] 更新 §2 看板：F2 置 ✅（2026-08-08，全量套件 44 用例全绿）

**E2E 轮附带修复（2026-08-08）**
- **组件真 bug**：`AssignOwnerModal` 原为 `filterOption={false}` + onSearch 合并选项——下拉不收窄，用户数多（全量 setup 下 s5/s6/s7 用户）时目标选项被 antd 虚拟滚动挤出 DOM。改为一次拉全量（≤100）+ 客户端 `filterOption` 过滤。
- F1 spec 两处存量脆弱断言适配全量数据环境：① 未分类/默认浏览首屏超 10 条/页时目标行落第 2 页（未分类用例先切 50 条/页；权限隔离用例改搜索确定命中）；② 负责人下拉先输入关键字过滤再点（antd v6 搜索框类名 `.ant-select-input`，与 v5 不同）。

> **已消解**：B4 `config_template` JSON 结构（见 §5）；内置模板经 Flyway 播种（迁移内 INSERT 为本项目首例，固定 id + `ON CONFLICT DO NOTHING`）。

---

### 6.3 F3 子 DAG 参数下发（NG5）✅ 全部完成（2026-08-08）

**范围**：主 DAG → 子 DAG 参数单向透传（`paramMappings`）。**同步+异步双链路**（用户确认 2026-08-08，技术文档原只写异步链路，已回落）。
**无需迁移**（`dag_node.config` 为 TEXT JSON，向后兼容；旧数据 paramMappings=null 视为不传参）。

**后端**（✅ 全部完成，curl 自测通过，明细见 §4「F3 后端变更明细」）
- [x] task-core：`SubDagNodeConfig` 加 `paramMappings: List<ParamMapping>`（契约 DTO，无新 Controller）
- [x] common：`ErrorCode` 7106 `SUB_DAG_PARAM_INVALID`
- [x] engineering：`SubDagParamMappingResolver`（双链路共用）+ `DagService` 保存校验（R5）+ `DagExecutionService.triggerSubDag`（异步链路）+ `InternalDagExecutionService` ensure-execution 参数注入（同步链路）
- [x] engineering-api/worker：`EnsureDagExecutionRequest.parentDagExecutionId` + `AbstractDagNodeHandler`/`DagNodeExecuteService` 透传
- [x] 重建 engineering + worker（healthy）；curl 自测：7106 校验 ×2、异步/同步双链路下发、主参数无值跳过边界、无映射回归——全绿，残留已清

**前端**（✅ 全部完成，2026-08-08 联调冒烟全绿）
- [x] `types.ts`：`ParamMapping` + `SubDagNodeConfig.paramMappings`；Editor `RFNodeData`/`parseConfig`/config 序列化三处接线（空映射不写入，旧数据兼容）
- [x] `SubDagNodeModal` 参数下发编辑器（对齐原型 subdag 视图，640px）：映射行 = 主参数 Select（主 DAG 声明参数 + 系统变量 biz_date/current_time/dag_id，与后端校验白名单一致）→ 子参数 AutoComplete（选中子 DAG 后懒拉其声明参数，可手输）+ 删除；添加映射按钮；前端校验（必填/子参数唯一）+ 后端 7106 兜底；「本期不支持」提示已移除
- [x] 联调冒烟（临时脚本，用后删除）：P4 夹具 DAG 双击子节点 → 添加映射（biz_date→sub_date）→ 保存 → API 验证 `dag_node.config` 含 paramMappings → 重开回显 → 删除映射保存 → config 还原——全过无控制台错误

**前端 Review（2026-08-08，按 AGENTS.md §7 三点）**
- 架构融洽 ✅：复用现有 SubDagNodeModal（节点轻量配置 = 居中弹窗，符合 §7 弹窗/抽屉分工）；config TEXT JSON 扩展无迁移、向后兼容；无新 API（复用 `listDagParameters`）无新依赖
- 业务正确 ✅：主参数候选与后端 R5 校验白名单一致；必填/唯一性前端先拦、7106 兜底；空数组不落库（旧语义不变）；回显/删除/持久化全链路实证
- 实现高效 ✅：子 DAG 参数列表仅选中后懒拉一次；主参数复用 Editor 既有 `dagParams/draftParams` 状态，无额外请求

**测试**
- [x] 后端 Postman/curl 自测：主 DAG 配 paramMappings → 触发 → 子 DAG 执行上下文收到透传参数（同步/异步双链路）
- [x] 新建 `e2e/sprint7/e2e/subdag-param.spec.ts`（初版 4 用例 UI 级）：参数下发区/主参数候选（声明+系统变量）、前端校验（必填/唯一）、保存持久化（config 断言）/回显/删除还原、7106 后端校验（API 辅助）。夹具经 API 创建（项目+父子 DAG+参数），afterAll 删除
- [x] **执行链路 E2E 扩充（2026-08-08，用户确认纳入，8 用例全绿 ×2 轮）**：同 spec 新增「执行链路」describe 4 用例——① 异步链路：真实触发父 DAG → 子执行 `resolved_params` 断言 `sub_env=prod_async`、`sub_date=父执行 biz_date`（与父 resolved_params 对比杜绝时区歧义）、子 `trigger_type=MANUAL`；② 同步链路：同断言 + `trigger_type=SCHEDULED`；③ 边界：主参数无值（声明无默认、触发未传）warn 跳过不阻断，`sub_env` 落子 DAG 自身默认值；④ 回归：无 paramMappings 时子执行无任何透传值。执行夹具（exec_sub + 异步父/同步父/无值父）beforeAll API 创建 + `waitDagDsSynced` 等调度注册，afterAll 删除
- [x] **E2E 轮附带修复 1 个存量 bug**：`Editor.loadDag` 此前不加载 DAG 声明参数（仅触发时才 `listDagParameters`），导致子 DAG/条件节点配置的主参数下拉只剩系统变量——已改为 loadDag 时顺带加载
- [x] 更新 §2 看板：F3 置 ✅（2026-08-08，全量套件 48 用例全绿；执行链路扩充后 F3 spec 8 用例全绿）
- E2E 调试经验（复用价值）：① 弹窗内别用 Escape 关下拉（useModalA11y 会连带关弹窗）；② antd 关闭的 dropdown 残留 DOM 隐藏态，选项点击必须限定 `:not(.ant-select-dropdown-hidden)` 可见实例

---

### 6.4 F4 Python 质量规则（DG-10）✅ 全部完成（2026-08-08）

**范围**：新增 PYTHON 规则类型 + 强化自定义 SQL。
**口径确认（2026-08-08 用户确认）**：① CUSTOM_SQL 强化后端新增**执行预览端点**（多指标列选择）；② 新增**测试脚本端点**（原型「测试脚本」按钮，技术文档 §5.3 未列）；③ worker 装 `psycopg2-binary` + `oracledb`（thin 模式无需 Oracle client；Oracle 测试库在 compose 注释中，本期不可验证）。

**后端**（✅ 全部完成，curl 自测通过，明细见 §4「F4 后端变更明细」）
- [x] Flyway `V1.3.0`（**governance 库**，按域拆库落点，非技术文档原写的 V3.8.2/system）：`quality_rule_template.type` CHECK drop 重建加 `PYTHON` + `python_template`/`python_script` 字段（已应用 success）
- [x] task-core `PythonExecutor` 改造（方案 B）：`executeQualityCheck` 新模式——通用 `conn.json` 注入 + 沙箱 `read_table(table, where, limit)`（按 type 选驱动 pymysql/psycopg2/oracledb，标识符白名单校验防注入）+ 收尾自动 `check(read_table(目标表))` 返回 dict 写 `check_result`；`read_doris_table`/`write_doris_table` 保留向后兼容；新增共享 `PythonConnectionResolver`（Doris 静态配置/注册数据源解密 → conn map，worker 与 governance 共用）
- [x] worker + governance 镜像：`psycopg2-binary` + `oracledb`（governance 加 Python 环境供 test-script 本地沙箱）
- [x] governance 配置层：模板/规则白名单加 PYTHON（服务层 + DTO `@Pattern` + `@AssertTrue` 脚本/指标必填）、`pythonTemplate/pythonScript` 全链路落库透出、批量应用 PYTHON 模板（脚本从模板带出）、`preview-sql` 对 PYTHON 返回脚本、执行计划透 `pythonScript/databaseName/schemaName`
- [x] worker `QualityCheckService` PYTHON 执行链路：脚本沙箱执行 → dict 按 `resultMetric` 取值（缺失报错）→ 复用 `determineLevel` 分级；脚本失败/无 dict → `UNAVAILABLE`（不告警）；超时 `datanest.quality.python.timeout-seconds:300`（shared-quality.yaml 已发布 Nacos）
- [x] 新端点：`POST /quality/rules/test-script`（保存前试跑，返回 dict/错误/耗时）+ `POST /quality/rules/preview-execute`（CUSTOM_SQL 真实执行预览，仅 SELECT/WITH，返回列清单+截断样例行供选 resultMetric）；均治理员/超管
- [x] 重建 engineering + worker + governance + job + system（task-core 消费方全量），全部 healthy，Flyway 1.3.0 ✅

**前端**（✅ 全部完成，2026-08-08 联调冒烟全绿）
- [x] `types/quality.ts`：`QualityTemplateType`/LABEL/OPTIONS 加 PYTHON + `pythonTemplate/pythonScript` 字段 + 两个结果类型；`api/quality.ts` 加 `testQualityPythonScript`（timeout 320s）/`previewExecuteQualitySql`
- [x] `QualityRuleDrawer`：PYTHON 类型（模板选择隐藏、脚本编辑区 + 约定 hint、检查字段可选、结果指标必填）+「测试脚本」按钮（结果/耗时/失败 traceback 行内展示）；CUSTOM_SQL 加「执行预览」（列 chips 点击回填结果指标 + 样例行表格 + 截断提示）
- [x] `QualityTemplateDrawer`：PYTHON 模板（`pythonTemplate` 脚本编辑区，校验分支互斥；列表页类型标签补 Python）

**前端 Review（2026-08-08，按 AGENTS.md §7 三点）**
- 架构融洽 ✅：类型走既有单一出处（LABEL/OPTIONS 各处自动获得 Python 标签）；新端点封装沿用既有风格；无新依赖；脚本编辑用 textarea 而非 Monaco（短脚本 + Drawer 内嵌语义，Monaco 属 DAG 全屏编辑器场景）；两个表单均为右侧 Drawer 符合 §7 分工
- 业务正确 ✅：PYTHON 脚本+结果指标必填对齐后端 `@AssertTrue`；模板校验分支（PYTHON 要 pythonTemplate、其余要 sqlTemplate）对齐后端「模板必填排除 PYTHON」；payload 按类型互斥（sqlExpression/pythonScript 不混传）；预览透传 columnName/阈值作 {column}/{min}/{max} 占位符来源对齐请求 DTO
- 实现高效 ✅：试跑/预览仅点击触发；模板下拉沿用按类型懒加载；无 N+1/轮询

**测试**
- [x] 后端 Postman/curl 自测：模板/规则 CRUD 校验（4203/400）、test-script 三驱动（Doris/MySQL/PG）+ 采样（read_table limit）+ 失败 traceback、执行链路三档（PASS 0.0 / WARNING 0.25 / UNAVAILABLE 坏脚本）、批量应用 PYTHON 模板、update 保留脚本、preview-execute 多指标列 + DML/占位符拦截、无 token 1004；残留已清（见 §4「F4 后端变更明细」）
- [x] 新建 `e2e/sprint7/e2e/quality-python.spec.ts`（5 用例 serial）：PYTHON 模板 CRUD（DB 断言 python_template）、PYTHON 规则（模板选择隐藏/可选检查字段/**测试脚本真实沙箱试跑断言 dict**/必填校验/DB 断言 python_script/编辑回显/删除）、CUSTOM_SQL 执行预览（多指标列 chips + 点列回填 + 样例行）。test-script 用环境真实 mysql 数据源（种子数据源假密码连不上，与后端自测同口径）；e2e_s7_f4% 前缀 afterAll DB 兜底清理
- [x] 更新 §2 看板：F4 置 ✅（2026-08-08，全量套件 57 用例全绿）

---

### 6.5 收尾补强（2026-08-09，三态梳理后修复）

**背景**：Sprint 7 交付后按「级联删除 / 删除校验 / 平台定时任务」三态复盘，结合业务与代码实际评估发现 2 处补齐项（governance 侧，均已实现并重建 app-governance）：

- **[x] 质量任务删除加 RUNNING 保护**：`QualityJobService.delete` 原无运行中拦截（采集/同步任务均有），worker 执行中仍会经质量执行接口回写批次/明细，删除会让 worker 把结果写到已不存在的任务上产生孤儿批次。修复：注入 `QualityCheckBatchMapper`，删除前校验任务下是否存在 `status="RUNNING"` 批次（`quality_check_batch.job_id=id`），存在则抛 `ErrorCode.QUALITY_JOB_ALREADY_RUNNING(4218)`（common ErrorCode 新增）阻止删除，对齐采集/同步任务语义。判定依据：`QualityJob` 实体无运行状态字段，worker 经 `initBatch` 创建 RUNNING 批次、收尾回写终态。
- **[x] 统一「创建 cron 任务不自动开启调度」**（对齐同步/采集任务模型）：`QualityJobService.create` 原行为是请求 `scheduledEnabled=1` 且配 cron 时 `registerSchedule` 直接启动（自动开启）；现改为 `scheduled_enabled` 恒存 0、cron 有值仅注册 PowerJob 任务（`registerSchedule(entity, false)`，start=false 不启动）回填 `scheduler_job_id`，由用户手动 `startSchedule` 开启（注册并 start=true）。`registerSchedule` 加 `start` 参数。
- **[x] 顺带修复 `update` 调度同步不一致**：原逻辑仅 `oldSchedulerJobId != null && cronChanged` 时同步调度中心；统一创建语义后编辑任务「手动→定时（无调度任务→有 cron）」或「仅开启调度」会漏同步（DB 显示已开启但调度中心未注册/未启动）。现改为：未注册但本次配 cron → 事务内 `registerSchedule(entity, scheduleEnabled)`（失败回滚）；已注册且 cron/调度开关变化 → 事务提交后 `updateJob` 同步。

**部署**：改动涉及 common（ErrorCode 4218）+ governance，已 `mvn package` + `docker compose build app-governance` + up，healthy，启动日志无 ERROR。

**前端影响**：质量任务表单里「开启定时调度」开关语义不变——勾选后在保存时创建任务（`scheduled_enabled=0`），仍需用户进入任务详情点「启动调度」才会真正生效；已创建的定时任务（旧数据 `scheduled_enabled=1` 且已注册）不受影响。

---

### 6.5 收尾（全部块完成后）

- [ ] 全量回归：`docker compose up -d` 全部服务 + 前端 build + 各块 E2E 全跑
- [ ] 代码审查 + 更新 AGENTS.md / docs/agent（如需）
- [ ] 更新 §2 看板全部置 ✅ + 本文档归档

## 7. 备注 / 已知坑提醒

- **构建规则**：只要改到 task-core 共享模块（entity 字段扩展等），必须同时重建所有消费方（至少 engineering + worker；涉及治理/质量还需 governance/job/system）。F1 改 `MetadataTable`、F2 加 `TaskTemplate`、F3 改 `SubDagNodeConfig`、F4 改 `PythonExecutor`——**每块都需全量重建对应容器**。
- **Flyway**：最新脚本编号 `V3.7.1`，新脚本必须从 `V3.8.0` 起；`quality_rule_template.type` CHECK 约束扩展需 drop 重建（对齐 V3.6.6 做法）；统一紧凑单行风格，禁格式化工具拆行。
- **审计字段**（AGENTS.md §7）：新增表 `asset_classification`/`task_template` 的 `updated_at` 不要加 DB 默认值，create 只设 `created_by`/`created_at`。
- **搜索性能**：资产多维搜索需处理千级表规模（R1），先 LIKE + 首屏防抖，量大再上全文索引。
- **Python 规则**：执行失败落 `result_level=UNAVAILABLE`（不告警、不参与评分，对齐 Sprint 6 R2）；复用 PythonExecutor 超时/内存限制。
- **F3 无迁移**：`dag_node.config` 为 TEXT JSON，`paramMappings` 新增字段向后兼容，勿新增 DB 迁移脚本。
- **每块独立验证**：F2 完成后即可部署上线（不与 F1 耦合），无需等全部块完成。
