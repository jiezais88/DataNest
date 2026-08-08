-- ============================================
-- V1.2.0__drop_legacy_scheduler_columns.sql
-- P4 切流清理：governance 域删除旧调度列（XXL-JOB xxl_job_id）
-- 说明：XXL-JOB 链路已全部下线，运行中调度统一走 PowerJob（scheduler_job_id 列）；
--       开发环境无需兼容旧数据，直接 DROP COLUMN（两列均无索引）。
-- 注意：本脚本采用紧凑单行 SQL 写法，规避格式化工具拆行导致 checksum 不匹配。
-- ============================================

ALTER TABLE collect_task DROP COLUMN IF EXISTS xxl_job_id;
ALTER TABLE quality_job DROP COLUMN IF EXISTS xxl_job_id;
