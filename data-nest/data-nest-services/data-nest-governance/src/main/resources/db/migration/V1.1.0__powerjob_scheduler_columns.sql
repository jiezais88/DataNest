-- ============================================
-- V1.1.0__powerjob_scheduler_columns.sql
-- 调度引擎切换（XXL-JOB → PowerJob 5.1.2）：governance 域加列
-- 说明：只加不改（ADD COLUMN IF NOT EXISTS），保护运行中的旧代码；
--       旧 xxl_job_id 列保留，待切流完成后由清理脚本统一删除；
--       新列可空、无默认值，对存量数据零影响。
-- 注意：本脚本采用紧凑单行 SQL 写法，规避格式化工具拆行导致 checksum 不匹配。
-- ============================================

ALTER TABLE collect_task ADD COLUMN IF NOT EXISTS scheduler_job_id BIGINT;
ALTER TABLE quality_job ADD COLUMN IF NOT EXISTS scheduler_job_id BIGINT;

COMMENT ON COLUMN collect_task.scheduler_job_id IS 'PowerJob jobId（替代 xxl_job_id，切流后生效）';
COMMENT ON COLUMN quality_job.scheduler_job_id IS 'PowerJob jobId（替代 xxl_job_id，切流后生效）';
