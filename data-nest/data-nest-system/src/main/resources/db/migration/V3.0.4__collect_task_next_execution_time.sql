-- ============================================
-- V3.0.4__collect_task_next_execution_time.sql
-- 采集任务增加下次执行时间字段
-- ============================================

ALTER TABLE collect_task
    ADD COLUMN IF NOT EXISTS next_execution_time TIMESTAMP DEFAULT NULL;

COMMENT ON COLUMN collect_task.next_execution_time IS 'Cron 任务下一次执行时间';
