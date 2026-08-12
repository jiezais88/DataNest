-- SQL 查询历史支持记录失败查询（Sprint 10 F1 产品优化：失败 SQL 也进历史并展示错误信息）
ALTER TABLE public.sql_query_history ADD COLUMN IF NOT EXISTS error_message text;
