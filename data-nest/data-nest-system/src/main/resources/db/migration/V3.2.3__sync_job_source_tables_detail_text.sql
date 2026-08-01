-- V3.2.3：sync_job.source_tables_detail 列类型 jsonb → text
-- 决策 Sprint3-Fix3：Sprint 2 漏了 TypeHandler，MyBatis-Plus 写 jsonb 列报
--   "column is of type jsonb but expression is of type character varying"
--   业务上 sourceTablesDetail 当 string 用（存 JSON 字符串，parse 端 fastjson2），
--   列类型改成 text 让 PG 走字符路径，避免 binding type 问题。
-- ADR-S3-013：VARCHAR/TEXT 不影响业务（业务层手动 JSON 解析），但写入链稳定。
-- 影响范围：仅 sync_job.source_tables_detail 单列；source_tables (jsonb) 不动

ALTER TABLE sync_job
ALTER
COLUMN source_tables_detail TYPE TEXT USING source_tables_detail::TEXT;

-- 列注释保留
COMMENT
ON COLUMN sync_job.source_tables_detail
    IS '多表结构化配置（TEXT，存 JSON 字符串，业务层 fastjson2 解析；Sprint3-Fix3 改自 jsonb）';
