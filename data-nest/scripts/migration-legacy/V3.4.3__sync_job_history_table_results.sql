-- 多表同步 per-table 明细：TEXT 存储 JSON 数组字符串（[{sourceTable,targetTable,status,readRows,writeRows,durationMs,errorMessage}]）
-- 用于历史详情/日志按表分组展示
ALTER TABLE sync_job_history
    ADD COLUMN table_results TEXT;
