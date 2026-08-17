# Sprint 13 Handoff：数据服务自定义查询 SQL（开发完成，E2E 待测试会话）

> 更新：2026-08-17（开发会话收尾）
> 对应文档：`../DataNest-Sprint13-PRD.md`（v1.1）· `../DataNest-Sprint13-技术文档.md`（v1.2）· `../测试用例清单.md`（错误码已对齐）

---

## 1. 状态看板

| 交付物 | 状态 | 说明 |
|--------|------|------|
| PRD | [OK] v1.1 | 双形态（选表/自定义 SQL）、决策 D1~D9、已知边界 R6 |
| 技术文档 | [OK] v1.2 | ADR-001~004；错误码语义已修正（只读/语法复用 9001/9002，9017 预留） |
| 测试用例清单 | [OK] 30 用例 | API 20 + UI 10，映射 AC-1~8/N-1~3/V1~7；错误码断言已对齐 9001 |
| 后端开发 | [OK] | ErrorCode 9017~9019 + Flyway V1.1.0（query_type/sql_text/involved_tables）+ CustomSqlService + DataApiService/OpenApiService 双形态 + page() queryType 筛选；编译通过 |
| 前端开发 | [OK] | 双形态创建向导（方式选择→SQL 定义→配置接口→绑定 Key）+ customSql.ts + CustomSqlForm + 列表形态列/筛选 + 详情 SQL 区块 + 编辑页；tsc/pnpm build 通过 |
| 部署 | [OK] | app-data-service healthy（Flyway V1.1.0 生效）、app-frontend 已更新；测试残留已清理 |
| 后端自测 | [OK] | 冒烟（创建/只读 9001/参数 9018/发布/Key/注入拦截 400/正常调用）+ 血缘 + 9019 机密闸门 + 分页边界，全部通过 |
| 前端联调 | [OK] | 双形态向导渲染、Monaco、校验按钮、列表形态列、接口全 200、0 console error |
| 代码 Review | [OK] | 架构融洽（复用 ReadOnlySqlValidator/CancelableSqlExecutor/Feign）/业务正确（fail-closed 闸门/注入防护）/实现高效（无 N+1）；注释笔误已修 |
| **E2E 全功能测试** | **[待测试会话]** | 测试脚本已写好：`data-nest-frontend/e2e/sprint13/`（api/custom-sql-api.spec.ts + e2e/custom-sql.spec.ts + helpers/seed.ts） |

## 2. 变更清单

### 后端（data-nest-data-service + common）
- `ErrorCode.java`：新增 CUSTOM_SQL_INVALID(9017)/CUSTOM_SQL_PARAM_MISMATCH(9018)/CUSTOM_SQL_TABLE_FORBIDDEN(9019)
- `V1.1.0__data_api_custom_sql.sql`（新）：data_api 加 query_type（默认 TABLE_SELECT）/sql_text/involved_tables
- `CustomSqlService.java`（新）：只读校验（复用 ReadOnlySqlValidator）+ 涉及表解析 + :param 词法级替换（排除字符串/注释/PG ::）+ 参数一一对应（9018）+ 6 类型强转 + 分页/COUNT 包裹
- `DataApiService.java`：create/update 双形态分支、applyTableSelectDefinition（服务端强制校验库/表）、applyCustomSqlDefinition（逐表 9019 闸门 + 血缘）、recheckCustomSqlGates（发布/下线前重过闸门）、writeLineage（表级自环降级）
- `OpenApiService.java`：execute CUSTOM_SQL 分支（参数绑定执行 + COUNT 降级，无外部 orderBy）
- `DataApiController`/`OpenApiController`：page 加 queryType 筛选、@Operation 双形态、参数错误 400 映射
- DTO：DataApiDefinition/CreateRequest/UpdateRequest/DetailDTO/PageItem 加 queryType/sqlText/sqlParams/involvedTables；CustomSqlParamDef（新）

### 前端（app-frontend）
- `customSql.ts`（新）：词法 :param 扫描/涉及表提取/只读预检/类型推断/预览 SQL 拼装/SQL 高亮
- `CustomSqlForm.tsx`（新）：数据源 + Monaco + 校验 + 参数表 + 涉及表 + 试跑预览
- `wizard.tsx`：4 步双形态向导；`ApiConfigForm.tsx`：CUSTOM_SQL 隐藏字段裁剪/外部排序；`index.tsx`：形态列/筛选；`detail.tsx`：SQL 定义区块；`edit.tsx`：改 SQL 重新校验
- `types/data-service.ts`/`api/data-service.ts`：契约同步

### 文档
- `docs/sprint13/DataNest-Sprint13-PRD.md`（v1.1）、`技术文档.md`（v1.2）、`测试用例清单.md`（错误码对齐）

## 3. 已知注意点（测试会话必读）

- **错误码语义（已定稿）**：只读/语法校验复用 SQL 终端错误码 **9001/9002**（实测 UPDATE → 9001）；9017 CUSTOM_SQL_INVALID 为预留；9018 参数不匹配；9019 涉及表闸门。测试脚本断言已对齐。
- **环境缺表**：内置 Doris 元数据无 `target_products`，只剩 demo_ecommerce 3 张表；E2E 种子自建 `e2e_s13_orders`/`e2e_s13_region`（Doris 建表 + 直插 metadata_table/column）。
- **E2E 环境前置**：完整 Playwright 套件会触发 globalSetup 播种 sprint5/6/7 数据（依赖测试数据源），需 `docker compose --profile test up -d` 起测试库；只跑 sprint13 用例建议用独立 playwright 入口或先满足 globalSetup 依赖。
- **机密表测试**：标记 CONFIDENTIAL 的表必须**存在于 metadata_table**（实测：不在元数据里的表 UPDATE 不生效、机密闸门不触发，非 bug）。
- **部署状态**：app-data-service healthy（Flyway V1.1.0）、app-frontend 已更新；`data_api` 中 CUSTOM_SQL 残留已清理为 0。
- **接口**：创建 CUSTOM_SQL 请求可省略 databaseName/tableName（后端取默认库）；列表筛选 `?queryType=CUSTOM_SQL|TABLE_SELECT`。

## 4. Next Action

1. **E2E 全功能测试（测试会话）**：按 `测试用例清单.md` 30 用例执行（API 20 + UI 10），环境先 `docker compose --profile test up -d`；发现 bug 修复后回归。
2. **代码提交**：本会话代码已就绪待提交（见 sprint13 分支 `feature/phase2-s13-custom-sql`），提交信息建议 `feat(sprint13): 数据服务自定义查询 SQL 双形态`。
3. **Review 收尾**：已做过三点 Review，测试会话后如发现新问题再补。
