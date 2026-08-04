-- ============================================
-- V3.0.9__sync_job_retry_history.sql
-- 批量数据同步任务重试改造（方案 B）：重试状态迁移到历史表
-- ============================================

-- --------------------------------------------
-- 历史表新增重试关联字段
-- --------------------------------------------
ALTER TABLE sync_job_history
    ADD COLUMN IF NOT EXISTS parent_history_id BIGINT DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS retry_count INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS next_retry_at TIMESTAMP DEFAULT NULL;

COMMENT ON COLUMN sync_job_history.parent_history_id IS '父历史记录ID，重试时指向来源执行记录';
COMMENT ON COLUMN sync_job_history.retry_count IS '当前执行链已发生的重试次数';
COMMENT ON COLUMN sync_job_history.next_retry_at IS '计划下次重试时间（仅记录）';

CREATE INDEX IF NOT EXISTS idx_sync_job_history_parent_id ON sync_job_history(parent_history_id);

-- --------------------------------------------
-- 任务主表移除重试状态字段（不再维护）
-- --------------------------------------------
ALTER TABLE sync_job
    DROP COLUMN IF EXISTS retry_count,
    DROP COLUMN IF EXISTS next_retry_at;

-- --------------------------------------------
-- 任务主表新增最近执行时间，用于列表展示
-- --------------------------------------------
ALTER TABLE sync_job
    ADD COLUMN IF NOT EXISTS last_execute_time TIMESTAMP DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS last_history_id BIGINT DEFAULT NULL;

COMMENT ON COLUMN sync_job.last_execute_time IS '最近执行时间';
COMMENT ON COLUMN sync_job.last_history_id IS '最近一次执行历史ID';
