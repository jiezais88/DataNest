# Sprint 7：数据资产目录——技术设计文档

> **版本**：v1.0 | **日期**：2026-08-05 | **作者**：后端
> **关联**：`DataNest-Sprint7-PRD.md`（v1.0）
> **技术决策**：本 Sprint 通过 2 轮沟通确认了需求边界，再经代码现状核验确定 4 个关键技术决策（D1~D4），见 §1 决策记录。

---

## 0. 技术目标与范围

Sprint 7 让数据分析师拥有一站式数据资产发现入口：在现有治理能力（元数据采集、血缘可视化、数据质量管理）之上，扩展**数据搜索、数据详情聚合、血缘与质量联动、按数据域分类浏览**五项 P0 能力，并顺带交付三项轻量增强（子 DAG 参数下发、任务模板库、自定义质量规则 Python）。

本技术文档覆盖 **P0 四大模块 + 三项增强**：

1. **资产目录 P0 五项**（DC-01 数据搜索 / DC-02 数据详情页 / DC-03 血缘嵌入 / DC-04 质量评分展示 / DC-05 数据分类浏览）
2. **NG5 子 DAG 参数下发**（主→子单向下发）
3. **DD-09 任务模板库**（同步/SQL/导出/采集，区别于质量规则模板库）
4. **DG-10 自定义质量规则增强**（新增 Python 规则类型 + 强化自定义 SQL）

> **资产目录复用 governance 扩展实现，不新建独立 catalog-service**（用户确认）。直接消费现有 `metadata_table`/`metadata_column`、血缘 `getLineageGraph`、`quality_score` 表与质量检查结果能力。

---

## 1. 关键技术决策记录（ADR）

> 本节记录本 Sprint 与用户确认/基于代码核验的技术决策。后续实现必须严格遵循，如需变更需重新确认。

### D-D1：资产目录落点 → 复用 governance 扩展，不新建 catalog-service

用户确认资产目录 P0 五项复用现有 governance 模块扩展实现，不新建独立 `catalog-service`。

- **后端**：新增 `AssetCatalogController`/`AssetCatalogService` 于 `data-nest-governance`（复用 `metadata_table`、血缘、质量评分能力）。
- **前端**：新增「数据资产」顶级菜单，独立路由 `/asset-catalog`（首页搜索）+ `/asset-catalog/:tableId`（详情页）。> 注：原设计为 `/assets`，因与 nginx 静态资源目录 `/assets/`（Vite 构建产物）冲突改为 `/asset-catalog`（2026-08-07 联调确认）。
- **依赖方向**：仅新增 governance 侧 Controller/Service 与 task-core-entity 共享实体字段扩展，不引入新模块。

### D-D2：血缘嵌入 → 复用数据 API + 精简渲染，保留独立血缘页

`LineageGraphPage` 是独立路由页（含工具栏/返回/空态/影响分析/字段血缘）。资产详情页要「血缘页签内嵌图谱」。**基于最小改动 + 不破坏现有血缘页**决策：

- **复用数据层**：资产详情页血缘页签直接用 `getLineageGraph(tableName)` + `layoutWithDagre`（`utils/dagLayout`）拉取并布局血缘图。
- **精简渲染**：详情页血缘页签内自绘精简 ReactFlow（节点渲染复用 `QualityScoreBadge` 徽章逻辑），**不带**影响分析/溯源分析工具栏、返回按钮、空态引导等页级交互。
- **保留独立页**：现有 `LineageGraphPage` 完全不动，双入口并存（治理侧血缘图谱、分析侧资产详情页血缘页签）。
- **共享抽取（可选优化）**：将 `TableNode`（ReactFlow 节点渲染，含质量徽章）抽为 `components/LineageNode.tsx` 供两处复用，减少重复渲染逻辑；不改变 `LineageGraphPage` 的页面结构与交互。

### D-D3：多维资产搜索 → 新增独立 `/assets/search` 接口

`searchTree(keyword)` 仅按 `database_name/schema_name/table_name` 三列模糊匹配并返回「数据源→库→schema→表」**树结构**，且被 `LineageGraphPage` 双击跳转复用。资产搜索需要**扁平结果卡片**（表名/注释/质量徽章/分类/相关度得分），两者形态差异大。

**新增独立资产搜索接口** `/assets/search`：

- 保留 `search-tree` 不动（血缘双击跳转等现有调用点零影响）。
- 新接口返回扁平 `List<AssetSearchItemDTO>`，支持表名/字段名/注释/标签/负责人五维模糊匹配 + 相关度得分 + 质量分回填。
- 命中表规模裁剪 + 首屏防抖（对齐 `searchTree` 已有的 `MAX_SEARCH_RESULTS` 保护）。

### D-D4：Python 质量规则执行 → 复用 PythonExecutor 沙箱，独立脚本约定

`PythonExecutor`（task-core，DAG Python 节点用）已提供**沙箱执行内核**（临时目录、`ulimit -v` 内存限制、超时中断、日志收集、安全沙箱禁网络/子进程）。质量 Python 规则**复用该沙箱机制**，但脚本约定独立，且**连接注入层从 Doris 专用抽象为通用数据源注入（方案 B，用户确认）**：

- **脚本约定**：`def check(df) -> dict`，接收目标表 DataFrame，返回「指标名 → 数值」dict（如 `{'null_rate': 0.01}`）。
- **数据拉取（通用连接注入，方案 B）**：worker 内质量执行 handler 取规则表 `datasource_id` → 用 `DataSourceConnection` 构造 JDBC 连接信息 → **注入沙箱通用 `conn.json`**（含 type/host/port/user/password/database）→ 沙箱 helper `read_table(table, where=None, limit=None)` 按数据源 type 选择驱动（Doris/MySQL→pymysql、PostgreSQL→psycopg2、Oracle→cx_Oracle）拉取 DataFrame → 用户脚本 `check(df)` 消费。
  - **不再使用 `GenericSqlExecutor` 拉数据**（其 5s 超时 + 200 行截断不适合 Python 校验）。
  - Python 内可写 `WHERE`/`LIMIT` 自由采样，不受预览型执行器限制（PRD NAC-3：超时可中断）。
- **执行链路**：加载规则 `python_script` → 组装通用连接注入 → 沙箱执行 → 解析返回 dict 取 `result_metric` 指标 → 沿用既有分级判定（`determineLevel`）落 `quality_check_detail`。
- **超时/失败**：复用 PythonExecutor 的超时中断与失败返回；执行失败 → `result_level=UNAVAILABLE`（不告警、不参与评分，对齐 Sprint 6 R2）。
- **不侵入 DAG Python 节点**：质量脚本独立约定；`PythonExecutor` 连接注入层从「Doris 写死」抽象为「通用连接注入」（新增 `read_table` helper，保留 `read_doris_table`/`write_doris_table` 供 DAG 节点场景向后兼容）。

---

## 2. 领域模型

### 2.1 资产目录

```
数据资产（分析师视角，复用治理能力）
  ├── DC-01 数据搜索：/assets/search 多维命中 + 相关度排序 + 质量回填
  ├── DC-02 数据详情页：/asset-catalog/:tableId 页签聚合（基础信息/字段/血缘/质量）
  ├── DC-03 血缘嵌入：复用 getLineageGraph + 精简渲染
  ├── DC-04 质量展示：复用 quality_score + quality_check_detail
  └── DC-05 分类浏览：asset_classification（数据域→主题两级） + metadata_table 分类字段
```

### 2.2 子 DAG 参数下发

```
主 DAG 子 DAG 节点（dag_node.config JSON）
  └── 新增 paramMappings: [{ mainParam: "${biz_date}", subParam: "${sub_date}" }]
        执行子 DAG 前：主 DAG resolveParams 解析主参数 → 按映射注入子 DAG manualOverrides → 子 DAG 沿用 DagParameterResolver 解析
```

### 2.3 任务模板库

```
任务模板 task_template（数据开发侧，区别于质量规则模板 quality_rule_template）
  ├── 类型：SYNC 数据同步 / SQL SQL转换 / EXPORT 数据导出 / COLLECT 采集
  ├── 来源：builtin 内置（平台预置，禁删）/ custom 自定义（用户将任务存为模板）
  └── config_template：JSON 模板含占位符 {source_table}/{target_db}/{schedule_cron} 等
        一键创建 → 填充占位符 → 生成对应类型任务草稿
```

### 2.4 质量规则增强

```
质量规则 quality_rule（Sprint 7 已独立化：quality_job_rule 多对多）
  └── 类型从 4 类扩展为 5 类：COMPLETENESS / UNIQUENESS / RANGE / CUSTOM_SQL / PYTHON
        ├── PYTHON：新增 python_script 字段（def check(df)），复用 PythonExecutor 沙箱
        └── CUSTOM_SQL 强化：模板化/参数化/多指标返回（复用 RuleSqlGenerator 占位符）
```

---

## 3. 数据模型设计

### 3.0 迁移脚本

- Flyway 最新脚本编号 **`V3.7.1`**（compliance_check_ignore），本 Sprint 新脚本从 **`V3.8.0`** 起，放在 `data-nest-system/src/main/resources/db/migration/`。
- 迁移脚本统一**紧凑单行风格**，改动 `quality_rule_template.type` 的 CHECK 约束需 drop 重建（对齐 Sprint 6 V3.6.6 做法）。

### 3.1 `V3.8.0__sprint7_asset_catalog.sql`：资产目录分类 + 表扩展

**新增分类体系表 `asset_classification`**（数据域→主题两级，供治理员维护）：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | Snowflake |
| level | VARCHAR(20) | `DOMAIN` 数据域（一级）/ `TOPIC` 主题（二级） |
| name | VARCHAR(100) | 分类名称 |
| parent_id | BIGINT | 父分类 ID（TOPIC 指向 DOMAIN；DOMAIN 为 NULL） |
| sort | INT | 排序 |
| created_by / updated_by / created_at / updated_at | - | 审计（updated_at 无 DB 默认值，仅写入时置值） |

> 索引：`idx(level)`、`idx(parent_id)`、`uk(level, name)`（同级分类名唯一）。

**`metadata_table` 新增字段**（DC-02/DC-05，负责人 + 分类）：

| 字段 | 类型 | 说明 |
|------|------|------|
| data_domain | VARCHAR(100) | 数据域（一级分类名，冗余存名称便于展示） |
| data_topic | VARCHAR(100) | 主题（二级分类名，冗余存名称） |
| owner_user_id | BIGINT | 负责人用户 ID（关联 sys_user.id，DC-02 展示） |

> **冗余存名称**：分类浏览按名称匹配、资产卡片直接展示，避免每次 join 分类表；分类体系维护（增删改）时需同步校验 `metadata_table` 引用（PRD §7）。

### 3.2 `V3.8.1__sprint7_task_template.sql`：任务模板库（DD-09）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | Snowflake |
| name | VARCHAR(100) | 模板名称（唯一） |
| type | VARCHAR(20) | `SYNC` 数据同步 / `SQL` SQL转换 / `EXPORT` 数据导出 / `COLLECT` 采集 |
| category | VARCHAR(20) | `BUILTIN` 内置（禁删可复制）/ `CUSTOM` 自定义 |
| description | VARCHAR(500) | 模板说明 |
| config_template | TEXT | JSON 模板，含占位符 `{source_table}`/`{target_db}`/`{schedule_cron}` 等 |
| enabled | SMALLINT | 是否启用 |
| created_by / updated_by / created_at / updated_at | - | 审计 |

> 索引：`idx(type)`、`uk(name)`。与 `quality_rule_template`（质量规则模板）**完全独立**，两者对象、字段、落地表不同。

### 3.3 子 DAG 参数下发（无新表，扩展 `SubDagNodeConfig`）

`SubDagNodeConfig`（task-core-entity）新增参数映射：

```java
// SubDagNodeConfig 新增
/** 参数映射列表：主 DAG 参数 → 子 DAG 参数 */
private List<ParamMapping> paramMappings;

@Data
public static class ParamMapping {
    private String mainParam;   // 主 DAG 参数引用，如 "${biz_date}"
    private String subParam;    // 子 DAG 参数名，如 "${sub_date}"
}
```

- 存储：`dag_node.config` 仍为 TEXT JSON，扩展后形如 `{"type":"SUB_DAG","subDagId":123,"subDagName":"xxx","syncExecution":true,"paramMappings":[{"mainParam":"${biz_date}","subParam":"${sub_date}"}]}`。
- **无需迁移脚本**（config 为 TEXT JSON，后端解析兼容新增字段；旧数据 `paramMappings` 为 null 视为不传参）。

### 3.4 `V3.8.2__sprint7_quality_python.sql`：质量 Python 规则（DG-10）

**`quality_rule_template.type` CHECK 约束追加 `PYTHON`**（drop 重建）：

```sql
ALTER TABLE quality_rule_template DROP CONSTRAINT IF EXISTS quality_rule_template_type_check;
ALTER TABLE quality_rule_template ADD CONSTRAINT quality_rule_template_type_check
  CHECK (type IN ('COMPLETENESS','UNIQUENESS','RANGE','CUSTOM_SQL','PYTHON'));
```

**`quality_rule_template` 新增字段**：

| 字段 | 类型 | 说明 |
|------|------|------|
| python_template | TEXT | Python 模板脚本（`def check(df)` 形式），PYTHON 类型模板用 |

**`quality_rule` 新增字段**：

| 字段 | 类型 | 说明 |
|------|------|------|
| python_script | TEXT | Python 脚本（`def check(df)` 返回 dict），PYTHON 类型规则落库 |

> 复用现有 `result_metric`（结果指标名，如 `null_rate`）、`warning_threshold`/`severe_threshold` 进行分级判定；多指标返回时用户选择 `result_metric` 指定取脚本返回 dict 的哪个键。

---

## 4. 核心流程

### 4.1 资产搜索（DC-01）

`AssetCatalogService.search(keyword)`：

1. 关键词清理（空白/纯通配符/超长截断，对齐 `searchTree`）。
2. **五维匹配**：`metadata_table`（表名/注释/负责人名）`OR` `metadata_column`（字段名/字段注释）模糊匹配；标签维度本期预留（无标签表则跳过，PRD §6.2）。
3. **相关度得分**：命中表名 > 命中注释 > 命中字段名 > 命中标签/负责人；表名前缀命中优先。返回每张表的 `score`（可简单加权，如表名命中 100、注释 60、字段 40、负责人 20）。
4. **质量回填**：命中表 ID 集合批量查 `quality_score`（`uk(table_id)`），回填 `qualityScore`/`healthLevel`；未配置规则为 null。
5. **分类回填**：回填 `data_domain`/`data_topic`。
6. **裁剪**：`MAX_SEARCH_RESULTS`（对齐现有保护）截断结果。

### 4.2 资产详情页（DC-02/03/04）

资产详情页 `/assets/:tableId` 采用页签式聚合，**前端复用现有 API 组装**（不新建聚合大接口，减少后端改动）：

| 页签 | 数据来源（复用） |
|------|------------------|
| 基础信息 | `getMetadataTable(tableId)` + 新增 `owner_user_id`/`data_domain`/`data_topic` 展示 |
| 字段列表 | `listMetadataColumns(tableId)` |
| 血缘图谱 | `getLineageGraph(tableName)` + `layoutWithDagre` 精简渲染（D2） |
| 质量 | `getQualityScoreByTable(tableId)` + `getQualityCheckDetail`/`queryQualityChecks` 最近结果 |

> 页面加载 < 3 秒（PRD NAC-2）：页签懒加载，血缘/质量页签进入时才拉取（PRD R3 对策）。

### 4.3 数据分类浏览（DC-05）

- **分类体系维护**（治理员/超管）：`asset_classification` 增删改、排序（DOMAIN 一级 → TOPIC 二级）。
- **表分配分类**：`metadata_table` 写 `data_domain`/`data_topic`（资产详情页或元数据管理详情页编辑，可批量）。
- **分类浏览**：左侧分类树（DOMAIN → TOPIC），右侧列出该分类下资产卡片（按 `data_domain`/`data_topic` 匹配），支持按质量评分排序、按数据源筛选。
- **未分类**：`data_domain` 为空的表归入「未分类」节点。
- **删除校验**：删除分类前查询 `metadata_table` 是否仍有引用（`data_domain=data_domain OR data_topic=...`），有则提示先解除分配（PRD §7）。
- **改名级联**（2026-08-06 用户确认）：重命名分类时同步 UPDATE `metadata_table` 冗余的 `data_domain`/`data_topic`，保证冗余名称一致。

### 4.4 子 DAG 参数下发（NG5）

`SubDagTriggerController` 触发子 DAG 时，在主 DAG 执行上下文扩展参数下发链路：

1. 主 DAG 执行节点到 SUB_DAG 节点，读取 `SubDagNodeConfig.paramMappings`。
2. 用主 DAG 已 `resolveParams` 出的参数 Map，按映射把 `mainParam` 的实际值注入子 DAG 触发入参 `manualOverrides[subParam]`。
3. 子 DAG 内部沿用 `DagParameterResolver.resolveParams(subDagId, manualOverrides)` 解析（系统变量/默认值/手动覆盖三级）。
4. **校验**：配置时校验 `mainParam` 在主 DAG 已声明参数中存在（或为系统变量 `biz_date` 等）、`subParam` 名在映射内唯一（PRD R5）。

> **仅主→子单向下发**（用户确认）：不做子 DAG 结果回传主 DAG（NG1 延后）。

### 4.5 任务模板库（DD-09）

- **内置模板**：平台预置（同步/SQL/导出/采集），`category=BUILTIN`，禁删可复制。
- **自定义模板**：用户把已配置好的任务保存为模板（`category=CUSTOM`）。
- **一键创建**：选模板 → 填占位符（`{source_table}`/`{target_db}`/`{schedule_cron}` 等）→ 校验必填占位符已填充（PRD R6）→ 生成对应类型任务草稿（同步任务 `sync_job`/SQL 任务等）→ 进入任务编辑/调度。
- **删除**：模板被删除不影响已创建任务（快照式），删除模板仅校验无独占引用。

### 4.6 Python 质量规则（DG-10）

`QualityCheckService` 执行 PYTHON 类型规则（数据拉取采用**方案 B 通用连接注入**，用户确认）：

1. 加载规则 `python_script`（`def check(df)`）与目标表 `datasource_id`。
2. **组装通用连接注入**：用 `DataSourceConnection` 构造目标表数据源连接信息（type/host/port/user/password/database，密码经 `EncryptionConfig` 解密）→ 注入沙箱 `conn.json`（非 Doris 专用 `doris.json`）。
3. **复用 `PythonExecutor` 沙箱**（临时目录 + `ulimit -v` + 超时中断 + 安全沙箱）执行脚本；沙箱内 `read_table(table, where=None, limit=None)` 按数据源 type 选驱动拉取 DataFrame 给 `check(df)`。
4. 解析返回 dict，按规则 `result_metric` 取指标值。
5. 沿用 `determineLevel` 分级判定（`value < warning`→PASS 等），落 `quality_check_detail`；失败 → `UNAVAILABLE`（不告警、不参与评分）。

> **数据拉取约定**：Python 脚本内通过 `read_table` 自由控制采样（`where`/`limit`），不受 `GenericSqlExecutor` 的 5s 超时/200 行截断限制；脚本返回 dict 仅作统计值，不产出新表（区别于 DAG Python 节点的 `write_doris_table`）。

**强化自定义 SQL**（CUSTOM_SQL）：复用 `RuleSqlGenerator` 占位符（`{table}/{column}/{min}/{max}`）模板化、参数化；多指标返回由脚本返回多列/多值，用户选 `result_metric` 指定；保留 `preview-sql` 预览增强多指标预览。

---

## 5. 接口设计（Controller）

### 5.1 资产目录 `AssetCatalogController`（governance，`/assets`）

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | `/search` | 资产多维搜索（keyword，返回 `List<AssetSearchItemDTO>`：tableId/表名/注释/库/数据源/质量分/健康度/数据域/主题/相关度得分） | 超管/工程师/治理员/分析师 |
| GET | `/classifications` | 分类体系树（DOMAIN→TOPIC） | 超管/工程师/治理员/分析师 |
| POST | `/classifications` | 新增分类 | 超管/治理员 |
| PUT | `/classifications/{id}` | 编辑分类 | 超管/治理员 |
| DELETE | `/classifications/{id}` | 删除分类（校验表引用） | 超管/治理员 |
| GET | `/browse?domain=&topic=&datasourceId=&sort=score` | 分类浏览资产列表（分页） | 超管/工程师/治理员/分析师 |
| PUT | `/tables/{tableId}/classification` | 分配分类（data_domain/data_topic） | 超管/治理员 |
| PUT | `/tables/{tableId}/owner` | 配置负责人（owner_user_id） | 超管/治理员 |

### 5.2 任务模板 `TaskTemplateController`（engineering，`/task-templates`）

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | `/` | 模板列表（含内置，按 type/category 过滤） | 超管/工程师 |
| POST | `/` | 新增自定义模板（含从任务另存为） | 超管/工程师 |
| PUT | `/{id}` | 编辑自定义模板 | 超管/工程师 |
| DELETE | `/{id}` | 删除自定义模板（内置禁删） | 超管/工程师 |
| POST | `/{id}/create-task` | 从模板一键创建任务（填占位符） | 超管/工程师 |

### 5.3 质量规则增强（沿用 `QualityTemplateController`/`QualityRuleController`）

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | `/quality/templates` | 模板列表（类型扩展 PYTHON） | 治理员/超管/工程师（查看） |
| POST | `/quality/templates` | 新增模板（含 PYTHON 模板） | 治理员/超管 |
| PUT | `/quality/templates/{id}` | 编辑模板 | 治理员/超管 |
| POST | `/quality/rules` | 新增规则（含 PYTHON 类型，python_script） | 治理员/超管 |
| PUT | `/quality/rules/{id}` | 编辑规则 | 治理员/超管 |
| POST | `/quality/rules/{id}/execute` | 单条执行（PYTHON 复用沙箱） | 治理员/超管 |

### 5.4 子 DAG 参数下发（沿用 `SubDagTriggerController`/`DagService`）

- `SubDagNodeConfig` 扩展 `paramMappings`（DTO 字段，config JSON 持久化，无新 Controller）。
- 校验：主参数存在性、子参数名唯一（`DagService` 或 `SubDagNodeModal` 保存时）。
- 触发：`SubDagTriggerController` 触发时注入子 DAG manualOverrides（§4.4）。

---

## 6. 权限矩阵映射

基于既有 Sa-Token 角色，Controller 方法按 PRD §8 加权限注解：

| 操作 | 角色 |
|------|------|
| 资产搜索/浏览/详情（含血缘/质量） | 超管、工程师、治理员、分析师（`DATA_ANALYST`） |
| 按分类浏览 | 超管、工程师、治理员、分析师 |
| 维护分类体系/分配分类/配置负责人 | 超管、治理员（`GOVERNANCE_ADMIN`） |
| 任务模板库维护 + 一键创建 | 超管、工程师（`DATA_ENGINEER`） |
| 配置 Python/强化 SQL 规则 | 超管、治理员 |
| 查看质量检查历史/评分 | 超管、工程师、治理员、分析师 |

> 前端 `Sidebar.tsx`：新增「数据资产」顶级入口（`ALL_ROLES`）；「数据开发→任务模板」（`ENGINEERING_WRITE_ROLES`）；`AssetCatalogController` 类级 `@SaCheckRole(value={"SUPER_ADMIN","DATA_ENGINEER","GOVERNANCE_ADMIN","DATA_ANALYST"}, mode=SaMode.OR)`，写接口方法级再收窄到治理员/超管。

---

## 7. 配置项（Nacos `shared-common.yaml`，governance 与 worker 共同导入）

| key | 默认值 | 说明 |
|-----|--------|------|
| `datanest.asset.search.max-results` | 200 | 资产搜索结果裁剪上限（对齐 searchTree） |
| `datanest.asset.search.preview-tables` | 1000 | 千级表规模搜索优化阈值（超过提示走索引） |
| `datanest.quality.python.timeout-seconds` | 600 | Python 质量规则执行超时（复用 PythonExecutor） |
| `datanest.quality.python.memory-limit-mb` | 1024 | Python 质量规则内存限制（复用 ulimit 机制） |
| `datanest.quality.python.sample-max-rows` | 10000 | `read_table` 默认采样行数上限（无 LIMIT 时防全表拉取过大） |
| `datanest.quality.python.driver-map` | - | 数据源 type → Python 驱动映射（DORIS/MySQL→pymysql、POSTGRESQL→psycopg2、ORACLE→cx_Oracle） |
| `datanest.asset.preview.max-rows` | 100 | 资产质量页签/字段预览采样行数 |

---

## 8. 已知 Blocker 与待确认点

| # | 事项 | 说明 | 状态 |
|---|------|------|------|
| B1 | Python 质量规则数据拉取 | ✅ 已消解（方案 B）：`PythonExecutor` 连接注入层从 Doris 专用抽象为通用连接注入（`conn.json`），沙箱 helper 增 `read_table(table, where, limit)` 按数据源 type 选驱动（pymysql/psycopg2/cx_Oracle）；不再用 `GenericSqlExecutor`（5s 超时+200 行截断不适合） | 明确 |
| B2 | 资产详情页路由与元数据详情页关系 | `/asset-catalog/:tableId` 独立路由（原 `/assets` 与 nginx 静态目录冲突已改名），需确认与现有 `/governance/metadata?tableId=` 的双入口展示差异 | 明确（并存） |
| B3 | 分类体系删除校验 SQL | 删除分类时按 `data_domain`/`data_topic` 匹配 `metadata_table` 引用 | 明确 |
| B4 | 任务模板 config_template JSON 结构 | 同步/SQL/导出/采集四类任务的 config_template 具体字段占位 | 待实现细化 |

---

## 9. 实现清单（P0）

### 后端

- [ ] Flyway `V3.8.0`（asset_classification + metadata_table 分类/负责人字段）、`V3.8.1`（task_template）、`V3.8.2`（quality 类型扩展 PYTHON + python_script/python_template）
- [ ] task-core-entity：`MetadataTable` 加 `dataDomain`/`dataTopic`/`ownerUserId`；新增 `AssetClassification` entity/mapper；`SubDagNodeConfig` 加 `paramMappings`
- [ ] governance `AssetCatalogService` + `AssetCatalogController`（§5.1）+ `AssetSearchItemDTO`/`AssetBrowseDTO`
- [ ] engineering `TaskTemplateService` + `TaskTemplateController`（§5.2）+ `TaskTemplateCreateRequest`
- [ ] governance 质量增强：`QualityTemplateController`/`QualityRuleController` 支持 PYTHON；`QualityCheckService` PYTHON 执行链路（§4.6）
- [ ] task-core `PythonExecutor` 改造（方案 B）：连接注入层从 Doris 写死抽象为通用连接注入（`conn.json`）+ 沙箱 helper 增 `read_table`（保留 `read_doris_table`/`write_doris_table` 供 DAG 节点向后兼容）
- [ ] worker 镜像：预装 Python 数据源驱动（pymysql/psycopg2/cx_Oracle，按需）
- [ ] engineering `DagService`/`SubDagTriggerController` 子 DAG 参数下发链路（§4.4）

### 前端

- [ ] `Sidebar.tsx` 新增「数据资产」顶级入口（ALL_ROLES）+「任务模板」（ENGINEERING_WRITE_ROLES）
- [ ] `router/index.tsx` 新增 `/asset-catalog`（数据资产首页）、`/asset-catalog/:tableId`（详情页）、`/engineering/task-templates`
- [ ] 数据资产首页（大搜索框 + 分类树 + 资产卡片流，复用 `QualityScoreBadge`）
- [ ] 资产详情页（基础信息/字段/血缘/质量四页签；血缘页签复用 `getLineageGraph` + 精简 ReactFlow）
- [ ] 分类体系维护（治理员）、表分配分类/负责人
- [ ] 任务模板库页（列表/新增/一键创建）
- [ ] 质量规则表单扩展 PYTHON 类型 + `QUALITY_TYPE_LABEL/OPTIONS` 扩展（`types/quality.ts`）

---

## 10. 验收口径映射（PRD AC）

| 验收项 | 落地位置 |
|--------|----------|
| AC-1 资产搜索 | `/assets/search` 多维命中 + 相关度排序 + < 2s |
| AC-2 资产详情页 | `/asset-catalog/:tableId` 页签聚合 + < 3s |
| AC-3 血缘嵌入 | 详情页血缘页签复用 `getLineageGraph` + 空态 |
| AC-4 质量评分联动 | 详情页质量页签复用 `quality_score` + `quality_check_detail` |
| AC-5 分类浏览 | `asset_classification` + `metadata_table` 分类字段 + 分类树浏览 |
| AC-6 子 DAG 参数下发 | `SubDagNodeConfig.paramMappings` + 触发注入 |
| AC-7 任务模板库 | `task_template` + 一键创建 |
| AC-8 Python 规则 | `quality_rule` PYTHON + 复用 PythonExecutor 沙箱执行 |
| AC-9 强化自定义 SQL | `RuleSqlGenerator` 模板化 + 多指标返回 |
| AC-10 权限隔离 | §6 注解映射 |

---

## 11. 变更说明

### 相对 PRD 的技术决策

1. **血缘嵌入**：不抽取/改造 `LineageGraphPage`，资产详情页血缘页签复用 `getLineageGraph` 数据 API + 精简 ReactFlow 自绘（D2），现有血缘页零改动。
2. **搜索接口**：新增独立 `/assets/search` 扁平结果接口，保留 `search-tree` 不动（D3）。
3. **Python 执行**：复用 `PythonExecutor` 沙箱内核（临时目录/ulimit/超时），质量脚本用 `def check(df)` 独立约定（D4）。
   - **数据拉取（方案 B，用户确认）**：`PythonExecutor` 连接注入层从 Doris 专用抽象为**通用连接注入**（`conn.json`），沙箱 helper 增 `read_table` 按数据源 type 选驱动；不再用 `GenericSqlExecutor`（5s 超时+200 行截断不适合 Python 校验）。
4. **分类字段**：`metadata_table` 冗余存分类名（`data_domain`/`data_topic`）+ 负责人 `owner_user_id`，另建 `asset_classification` 字典表维护体系。

### 需同步注意的既有状态

- `quality_rule` 已独立化（V3.6.3，规则独立菜单 + `quality_job_rule` 多对多），DG-10 基于该状态扩展 PYTHON。
- `quality_job.datasource_id` 已废弃（方案 A 移除，列保留不删），质量规则数据源下放到规则层，资产质量展示按规则表维度聚合。

---

> **版本记录**
> - v1.0 (2026-08-05)：基于 PRD v1.0、代码现状核验与用户确认的技术决策（资产目录复用 governance、血缘/搜索/Python 三方案由我定）编写。
> - v1.1 (2026-08-05)：用户确认 Python 质量规则数据拉取采用**方案 B（通用连接注入）**——`PythonExecutor` 连接注入层从 Doris 专用抽象为通用 `conn.json` + 沙箱 `read_table` helper；更新 §1 D4、§4.6、§7 配置、§8 B1（消解）、§9 实现清单。
> - v1.2 (2026-08-06)：F1 后端落地。补充 §4.3 分类**改名级联更新** metadata_table 冗余名（用户确认）；实现侧新增 `browse` 的 `uncategorized` 参数、`ErrorCode` 4007–4010、`QualityScoreService.mapByTableIds` 批量方法。
