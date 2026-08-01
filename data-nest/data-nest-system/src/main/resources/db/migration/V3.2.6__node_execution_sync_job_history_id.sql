-- ============================================
-- V3.2.6__node_execution_sync_job_history_id.sql
-- node_execution 加 sync_job_history_id：
-- SYNC 节点收尾（DagExecutionSyncService 查 sync_job_history 命中终态）时记录
-- 命中的 history id，用于按节点执行实例查 sync_job_log 日志
-- ============================================

ALTER TABLE node_execution
    ADD COLUMN IF NOT EXISTS sync_job_history_id BIGINT DEFAULT NULL;

COMMENT
ON COLUMN node_execution.sync_job_history_id IS 'SYNC 节点收尾时命中的 sync_job_history.id（用于查 sync_job_log 日志）';

CREATE INDEX IF NOT EXISTS idx_node_execution_sync_job_history_id
    ON node_execution (sync_job_history_id);
