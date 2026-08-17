# Sprint 13：数据服务自定义查询 SQL 技术文档

> **文档状态**：v1.0（技术方案，承接二期总技术文档 ADR-020） | **作者**：软件架构师 | **关联文档**：`DataNest-Sprint13-PRD.md`（v1.0）、`../sprint10/DataNest-Sprint10-技术文档.md`（一期实现基准）
>
> **Sprint 目标**：在 data-service 现有「选表」数据 API 之上，新增「自定义 SQL」查询定义形态，复用一期只读执行/参数化/网关/Key/统计/血缘底座，实现"写一段只读 SQL → 封装成对外 API"。

---

## 1. 技术现状（一期复用底座）

| 能力 | 一期落点 | Sprint 13 复用方式 |
|------|----------|--------------------|
| `data_api` 表 | V1.0.0 基线 + V1.0.2 软删/path 部分唯一索引 | 加列扩展，不重建 |
| `OpenApiSqlBuilder` | F3 参数化 SQL 构造（filters EQ/RANGE + fields 白名单 + orderBy + 分页） | 自定义 SQL 走新的 SQL 直通执行，分页/参数绑定可借鉴其类型推断 |
| `CancelableSqlExecutor` | F1/F3 PreparedStatement 参数化路径（`queryExternal`/`queryDoris` 带 `List<Object> params`，参数值启发式类型推断） | **核心执行器**，自定义 SQL 直接复用 |
| `SqlStatementSplitter.classify` | task-core SQL 四分类（只读/写/DDL/未知） | **只读校验**核心 |
| SQL 血缘解析器 | 一期 `LineageInterceptor`/JSqlParser 表级解析 | 自定义 SQL 表级血缘 |
| 敏感度闸门 | governance `getSensitivity` + `api_exempted`（机密拒/内部特批） | 涉及表逐表校验（fail-closed） |
| 数据权限 | Sprint 11 三级数据权限（FULL/WHITELIST，数据源/库/表） | 涉及表逐表校验（fail-closed） |
| Key/网关/限流/熔断/统计 | F3 `OpenApiKeyFilter`/`RateLimitService`/`CircuitBreakerService`/`ApiCallLogWriter` | **完全复用，零改动** |
| 审计 | Sprint 11 通用审计（8 类埋点 + 跨服务 Feign Recorder） | 创建/编辑/发布/删除补埋点 |

---

## 2. 数据模型扩展

### 2.1 `data_api` 加列（dataservice `V1.1.0`）

```sql
-- 查询定义形态：TABLE_SELECT（选表，一期默认）| CUSTOM_SQL（自定义 SQL）
ALTER TABLE public.data_api ADD COLUMN IF NOT EXISTS query_type character varying(20) DEFAULT 'TABLE_SELECT'::character varying NOT NULL;
COMMENT ON COLUMN public.data_api.query_type IS '查询定义形态（Sprint 13）：TABLE_SELECT 选表 / CUSTOM_SQL 自定义 SQL';

-- 自定义 SQL 文本（仅 CUSTOM_SQL 使用）
ALTER TABLE public.data_api ADD COLUMN IF NOT EXISTS sql_text text;
COMMENT ON COLUMN public.data_api.sql_text IS '自定义 SQL 文本（CUSTOM_SQL 形态，只读 SELECT，:param 命名参数）';

-- 涉及表清单（JSONB 文本，供权限/血缘/展示，冗余存储避免每次重解析）
ALTER TABLE public.data_api ADD COLUMN IF NOT EXISTS involved_tables text;
COMMENT ON COLUMN public.data_api.involved_tables IS 'SQL 涉及表清单 JSON（[{datasourceId,database,schema,table}]，创建/编辑时解析落库）';
```

### 2.2 `params_json` 语义扩展

一期：`{"filters":[...],"fields":[...]}`（选表形态）。

Sprint 13 对 `CUSTOM_SQL` 形态扩展为：

```json
{
  "queryType": "CUSTOM_SQL",
  "sqlParams": [
    {"name": "startDate", "type": "DATE", "required": true, "defaultValue": null},
    {"name": "regionId", "type": "LONG", "required": false, "defaultValue": 100}
  ],
  "paginated": true,
  "pageSizeMax": 100
}
```

> 类型枚举：`LONG / DECIMAL / DATE / DATETIME / STRING / BOOLEAN`，对齐 `CancelableSqlExecutor` 启发式推断结果，可手动修正。

---

## 3. 后端实现方案

### 3.1 创建/编辑流程（写路径）

```
① 前端提交：query_type=CUSTOM_SQL + datasource_id + sql_text + sqlParams + name/path
    │
    ▼
② 只读校验：SqlStatementSplitter.classify(sql) —— 必须为只读 SELECT
    │        （非只读 → 9002 语义；分号检测：; 后非空/注释即拒，防多语句）
    ▼
③ 涉及表解析：JSqlParser 提取 FROM/JOIN/子查询/CTE 的表清单（datasourceId 取入参）
    │
    ▼
④ 参数校验：sql_text 中 :param 占位符与 sqlParams 定义一一对应（多定义/漏定义即拒）
    │
    ▼
⑤ 安全闸门（fail-closed，任一不过整体拒）：
    ├─ 敏感度闸门：governance-api getSensitivity 逐表——机密表拒 / 内部表未 api_exempted 拒
    └─ 数据权限闸门：复用 Sprint 11 权限模型逐表校验（角色数据权限 FULL 或 WHITELIST 含该表）
    │
    ▼
⑥ 试跑预览（可选步骤，前端触发）：CancelableSqlExecutor 参数化执行（示例参数值）
    │
    ▼
⑦ 保存：data_api 落库（query_type/sql_text/involved_tables/params_json）
    │
    ▼
⑧ 血缘：SQL 血缘解析器解析 → lineage_record（表级，来源=API）
```

### 3.2 对外执行流程（读路径）

```
OpenApiController GET /open-api/v1/{path}?{sqlParams}&page&pageSize
    │
    ▼
OpenApiKeyFilter（Key 认证/绑定/限流）→ 熔断检查（数据源维度）—— 复用一期
    │
    ▼
OpenApiService 分支：
    ├─ TABLE_SELECT → 一期 OpenApiSqlBuilder 参数化查询（不变）
    └─ CUSTOM_SQL →
        ① SQL 文本 :param 替换为 ?（词法级：排除字符串/注释字面量内的 :param）
        ② 参数值按 sqlParams.type 类型转换（LONG/DECIMAL/DATE/DATETIME/STRING/BOOLEAN）
        ③ 分页包裹：外层 SELECT * FROM (sql) AS _p LIMIT ? OFFSET ?（Doris/MySQL）
                                   或 OFFSET ? FETCH NEXT ? ROWS ONLY（PG）
            total：SELECT COUNT(*) FROM (sql) AS _c
        ④ CancelableSqlExecutor PreparedStatement 执行（超时 10s 可配，截断 1000 行）
    │
    ▼
结果集 + api_call_log 异步统计（复用）
```

### 3.3 关键实现要点

| 要点 | 方案 |
|------|------|
| **`:param` 替换为 `?`** | 词法级扫描：跳过单引号字符串与 `--`/`/* */` 注释内的内容；`:[a-zA-Z_][a-zA-Z0-9_]*` 匹配参数名并替换为 `?`；**参数名不进入 SQL 文本**（杜绝注入） |
| **参数值类型转换** | 按 `sqlParams[].type` 强转：LONG→`Long.parseLong`、DECIMAL→`BigDecimal`、DATE/DATETIME→`LocalDate/LocalDateTime`（ISO 字符串）、STRING→原值、BOOLEAN→`Boolean.parseBoolean`；转换失败 → 400 参数错误 |
| **分页方言** | 按数据源类型（Doris/MySQL 用 LIMIT/OFFSET，PG 用 OFFSET FETCH）动态生成包裹 SQL；`page` 从 1 起、`pageSize` 默认 20 上限 100（`page_size_max` 可配） |
| **只读校验兜底** | 执行前再次 `SqlStatementSplitter.classify`（防 SQL 落库后被编辑绕过）；执行器层面保持只读连接/只读语句 |
| **超时与截断** | 执行超时默认 10s（Nacos `datanest.data-service.custom-sql.timeout-seconds` 可配，@RefreshScope）；结果上限 1000 行 |
| **涉及表解析落库** | `involved_tables` 冗余存储，列表/详情/权限校验直接读取，避免每次重解析；表被删除时血缘级联清理、involved_tables 保留文本 |

### 3.4 血缘

- **时机**：创建/编辑保存时解析（非调用时，避免重复计算）。
- **解析器**：复用一期 SQL 血缘解析器（JSqlParser），表级血缘 → `lineage_record`（来源类型=API，来源ID=data_api.id，与任务血缘同一图谱）。
- **展示**：表详情血缘图可看到"被自定义 SQL API 引用"的边；API 详情页展示涉及表清单。

---

## 4. API 设计

### 4.1 管理端（复用一期 Controller，字段扩展）

| 端点 | 变化 |
|------|------|
| `POST /apis` | 请求体支持 `queryType/sqlText/sqlParams`；CUSTOM_SQL 走 §3.1 流程 |
| `PUT /apis/{id}` | 同上；改 SQL 后重新校验 + 重新过闸门 + 更新血缘 |
| `GET /apis/{id}` | 详情返回 `queryType/sqlText/sqlParams/involvedTables`（列表页返回 `queryType` 徽章） |
| `GET /apis/page` | 列表项加 `queryType`（筛选可加形态筛选项） |

### 4.2 对外（零改动，复用 `/open-api/v1/**`）

- 参数 = SQL 参数（命名，必填/选填按定义）+ 分页参数（`page/pageSize` 可选）；**不支持外部 orderBy**（排序由 SQL 内 ORDER BY 决定，PRD D9）。
- 错误语义沿用一期 HTTP 状态码：401/404/429/503/200；参数错误 400。

### 4.3 错误码（common 补充）

| 码 | 常量 | 说明 |
|----|------|------|
| 9001 / 9002 | 复用 SQL 终端 | 自定义 SQL 的只读/语法校验**复用 SQL 终端语义**：非只读 9001 `SQL_NOT_READ_ONLY`、语法错误 9002 `SQL_SYNTAX_ERROR`（实测 UPDATE → 9001，v1.2 修正） |
| 9017 | `CUSTOM_SQL_INVALID` | 自定义 SQL 非法（**预留**；当前非只读/语法错误分别由 9001/9002 承接） |
| 9018 | `CUSTOM_SQL_PARAM_MISMATCH` | SQL 参数与定义不一致（多/漏/类型不符/值类型不符） |
| 9019 | `CUSTOM_SQL_TABLE_FORBIDDEN` | 涉及表含机密/未特批内部表或超出数据权限（fail-closed） |

> 注：9016 已被一期 CDC 的 `API_PIPELINE_UNAVAILABLE` 占用，Sprint 13 错误码从 9017 起（v1.1 修正）。9001/9002/9004/9013 等一期错误码语义沿用。

---

## 5. 安全设计（fail-closed 汇总）

| 层 | 防护 |
|----|------|
| SQL 文本 | 只读校验（classify）+ 单语句（分号检测）；执行前兜底再校验 |
| 参数 | 一律 PreparedStatement 绑定；`:param` 词法级替换排除字符串/注释；参数名不拼接 |
| 涉及表 | 敏感度闸门（机密/内部特批）+ 数据权限闸门（三级）逐表校验，**任一不过整体拒**（P2-D15） |
| 资源 | 超时中断 + 结果截断 1000 行，防慢查询/大结果拖垮网关 |
| 审计 | 创建/编辑/发布/下线/删除走一期审计埋点 |

> **已知边界（用户确认 2026-08-17）**：自定义 SQL 返回列不做字段级敏感拦截（一期 NG5 不做字段级敏感度），非机密表的敏感列可能被 API 暴露——靠表级敏感度闸门 + 数据权限 + 审计兜底。

---

## 6. 关键架构决策（ADR）

> 编号延续 data-service 域 Sprint 13 独立编号。

### S13-ADR-001：查询定义形态 —— 双形态并存（选表 + 自定义 SQL）

| 项目 | 内容 |
|------|------|
| 状态 | Accepted（对齐 P2-D14） |
| 决策 | `data_api.query_type` 区分 TABLE_SELECT / CUSTOM_SQL；选表流程与一期零改动；对外执行按形态分支 |
| 后果 | 📈 存量兼容、风险隔离；📉 执行路径双分支，需保证两形态行为一致 |

### S13-ADR-002：参数语法 —— 命名参数 `:param` + PreparedStatement 绑定

| 项目 | 内容 |
|------|------|
| 状态 | Accepted（对齐 PRD D4） |
| 决策 | SQL 内 `:param` 命名占位符；词法级替换为 `?`（排除字符串/注释）；参数值按类型强转后绑定 |
| 后果 | 📈 可读性好、注入不可行；📉 词法替换需覆盖边界（字符串/注释内冒号），实现要谨慎 |

### S13-ADR-003：涉及表权限 —— fail-closed 整体拒绝

| 项目 | 内容 |
|------|------|
| 状态 | Accepted（对齐 P2-D15） |
| 决策 | 涉及表清单（FROM/JOIN/子查询/CTE）逐表过敏感度 + 数据权限闸门；任一不过 → 创建与调用整体拒 |
| 后果 | 📈 安全默认、实现简单；📉 多表 SQL 可能误拒，提示需指明具体被拒表 |

### S13-ADR-004：血缘 —— 创建时表级解析

| 项目 | 内容 |
|------|------|
| 状态 | Accepted（对齐 PRD D6） |
| 决策 | 创建/编辑时解析表级血缘入 lineage_record；字段级留未来 |
| 后果 | 📈 复用现有解析器与图谱；📉 复杂 SQL（CTE/窗口）解析覆盖度需预验证，缺失降级表级 |

---

## 7. 验证计划

| # | 验证项 | 方式 |
|---|--------|------|
| V1 | 只读校验 | 构造 UPDATE/DELETE/DDL/多语句 SQL → 断言拒绝（9001） |
| V2 | 参数绑定与注入 | `:id` 传 `1; DROP TABLE` → 断言被当字面值（无副作用 + 正常查询）；字符串内 `:param` 不误替换 |
| V3 | 涉及表权限 fail-closed | JOIN 含机密/无权限表 → 创建拒绝 + 调用拒绝（9019）；提示具体表 |
| V4 | 分页/超时/截断 | page/pageSize 边界；pg_sleep 超时中断；1000 行截断 |
| V5 | 对外调用闭环 | Key 认证 + 限流 + 熔断 + 调用统计与选表 API 一致 |
| V6 | 血缘 | 创建后 lineage_record 出现涉及表；表详情可见引用 |
| V7 | 存量回归 | 一期选表 API 创建/编辑/调用/统计无回归 |

---

## 8. 版本记录

| 版本 | 日期 | 修订内容 | 作者 |
|------|------|----------|------|
| v1.0 | 2026-08-17 | 初始版本：双形态数据模型、创建/执行流程、参数与权限安全设计、ADR-001~004、验证计划 | 软件架构师 |
