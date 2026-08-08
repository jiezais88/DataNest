-- ============================================
-- V1.3.0__drop_legacy_scheduler_columns.sql
-- P4 切流清理：engineering 域删除旧调度列（DolphinScheduler ds_* / XXL-JOB xxl_job_id）
-- 说明：DS/XXL-JOB 链路已全部下线，运行中调度统一走 PowerJob（powerjob_* / scheduler_job_id 列）；
--       开发环境无需兼容旧数据，相关索引先 DROP INDEX IF EXISTS 再 DROP COLUMN；
--       release_state 列保留（现用语义 ONLINE = 已同步 PowerJob）。
-- 注意：本脚本采用紧凑单行 SQL 写法，规避格式化工具拆行导致 checksum 不匹配。
-- ============================================

DROP INDEX IF EXISTS idx_dag_ds_process_definition;
DROP INDEX IF EXISTS idx_dag_node_ds_task_code;
DROP INDEX IF EXISTS idx_dag_execution_ds_process_instance_id;
DROP INDEX IF EXISTS idx_dag_project_ds_project_code;
DROP INDEX IF EXISTS idx_node_execution_ds_task_instance_id;

ALTER TABLE dag DROP COLUMN IF EXISTS ds_project_code;
ALTER TABLE dag DROP COLUMN IF EXISTS ds_process_definition_id;
ALTER TABLE dag DROP COLUMN IF EXISTS ds_process_definition_code;
ALTER TABLE dag DROP COLUMN IF EXISTS ds_schedule_id;
ALTER TABLE dag_node DROP COLUMN IF EXISTS ds_task_code;
ALTER TABLE dag_execution DROP COLUMN IF EXISTS ds_process_instance_id;
ALTER TABLE node_execution DROP COLUMN IF EXISTS ds_task_instance_id;
ALTER TABLE dag_project DROP COLUMN IF EXISTS ds_project_code;
ALTER TABLE sync_job DROP COLUMN IF EXISTS xxl_job_id;
