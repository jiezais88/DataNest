-- ============================================
-- V1.1.0__powerjob_scheduler_columns.sql
-- 调度引擎切换（XXL-JOB + DolphinScheduler → PowerJob 5.1.2）：engineering 域加列
-- 说明：只加不改（ADD COLUMN IF NOT EXISTS），保护运行中的旧代码；
--       旧 xxl_job_id / ds_* 列全部保留，待切流完成后由清理脚本统一删除；
--       全部新列可空、无默认值，对存量数据零影响。
-- 注意：本脚本采用紧凑单行 SQL 写法，规避格式化工具拆行导致 checksum 不匹配。
-- ============================================

ALTER TABLE sync_job ADD COLUMN IF NOT EXISTS scheduler_job_id BIGINT;
ALTER TABLE dag ADD COLUMN IF NOT EXISTS powerjob_workflow_id BIGINT;
ALTER TABLE dag_node ADD COLUMN IF NOT EXISTS powerjob_job_id BIGINT;
ALTER TABLE dag_execution ADD COLUMN IF NOT EXISTS powerjob_wf_instance_id BIGINT;
ALTER TABLE node_execution ADD COLUMN IF NOT EXISTS powerjob_instance_id BIGINT;

COMMENT ON COLUMN sync_job.scheduler_job_id IS 'PowerJob jobId（替代 xxl_job_id，切流后生效）';
COMMENT ON COLUMN dag.powerjob_workflow_id IS 'PowerJob 工作流 ID（替代 ds_process_definition_code，切流后生效）';
COMMENT ON COLUMN dag_node.powerjob_job_id IS 'PowerJob 节点任务 jobId（替代 ds_task_code，切流后生效）';
COMMENT ON COLUMN dag_execution.powerjob_wf_instance_id IS 'PowerJob 工作流实例 ID（替代 ds_process_instance_id，切流后生效）';
COMMENT ON COLUMN node_execution.powerjob_instance_id IS 'PowerJob 任务实例 ID（替代 ds_task_instance_id，切流后生效）';
