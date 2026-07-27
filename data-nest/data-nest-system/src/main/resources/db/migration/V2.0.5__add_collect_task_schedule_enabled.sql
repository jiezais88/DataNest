-- ============================================
-- V2.0.5__add_collect_task_schedule_enabled.sql
-- 采集任务新增调度启用字段
-- ============================================

ALTER TABLE collect_task
    ADD COLUMN IF NOT EXISTS schedule_enabled SMALLINT NOT NULL DEFAULT 0;

COMMENT
ON COLUMN collect_task.schedule_enabled IS '调度是否启用（仅 CRON 任务有效，0-停止 1-运行）';
