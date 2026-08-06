-- ============================================
-- V3.0.3__sync_job_enhancements.sql
-- 批量数据同步任务增强：执行状态与目标端拆分、日志行号
-- ============================================

-- --------------------------------------------
-- 同步任务主表增强
-- --------------------------------------------
ALTER TABLE sync_job
    ADD COLUMN IF NOT EXISTS execution_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN IF NOT EXISTS target_database VARCHAR(100),
    ADD COLUMN IF NOT EXISTS target_table VARCHAR(100),
    ADD COLUMN IF NOT EXISTS next_execution_time TIMESTAMP,
    ADD COLUMN IF NOT EXISTS retry_count INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS next_retry_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS incremental_field VARCHAR(100);

-- 目标数据源已改为内置 Doris，原字段改为可选并不再使用
ALTER TABLE sync_job
    ALTER COLUMN target_datasource_id DROP NOT NULL;

-- 同步任务日志表增加行号，便于按 Addax 原始行序展示
ALTER TABLE sync_job_log
    ADD COLUMN IF NOT EXISTS line_num INT DEFAULT 0;

-- --------------------------------------------
-- 更新列注释
-- --------------------------------------------
COMMENT ON COLUMN sync_job.execution_status IS '执行状态：PENDING 未执行，RUNNING 运行中，SUCCESS 成功，FAILED 失败';
COMMENT ON COLUMN sync_job.target_database IS '目标 Doris 库名';
COMMENT ON COLUMN sync_job.target_table IS '目标 Doris 表名';
COMMENT ON COLUMN sync_job.next_execution_time IS 'Cron 任务下一次执行时间';
COMMENT ON COLUMN sync_job.retry_count IS '当前已连续重试次数';
COMMENT ON COLUMN sync_job.next_retry_at IS '下次重试时间';
COMMENT ON COLUMN sync_job.incremental_field IS '增量同步字段';
COMMENT ON COLUMN sync_job.status IS '调度状态：NORMAL 正常，PAUSED 暂停';
COMMENT ON COLUMN sync_job.target_datasource_id IS '目标数据源ID（已废弃，目标端固定为内置Doris）';
COMMENT ON COLUMN sync_job_log.line_num IS 'Addax 日志原始行号';
