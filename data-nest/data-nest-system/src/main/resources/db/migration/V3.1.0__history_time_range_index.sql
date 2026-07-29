-- ============================================
-- V3.1.0__history_time_range_index.sql
-- 为历史记录查询增加任务ID+开始时间的联合索引，优化时间范围过滤性能
-- ============================================

CREATE INDEX IF NOT EXISTS idx_collect_history_task_id_started_at
    ON collect_history (task_id, started_at);

CREATE INDEX IF NOT EXISTS idx_sync_job_history_sync_job_id_start_time
    ON sync_job_history (sync_job_id, start_time);
