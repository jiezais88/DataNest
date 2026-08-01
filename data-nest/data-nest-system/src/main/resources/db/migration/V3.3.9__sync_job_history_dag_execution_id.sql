-- ============================================
-- V3.3.9__sync_job_history_dag_execution_id.sql
-- sync_job_history 加 dag_execution_id：
-- 标记本次同步执行是否由 DAG 编排触发，前端历史页可点击跳到对应 DAG 执行实例
-- ============================================

ALTER TABLE sync_job_history
    ADD COLUMN IF NOT EXISTS dag_execution_id BIGINT DEFAULT NULL;

COMMENT
ON COLUMN sync_job_history.dag_execution_id IS '由 DAG 编排触发时的 dag_execution.id；手动/定时触发为 NULL';

CREATE INDEX IF NOT EXISTS idx_sync_job_history_dag_execution_id
    ON sync_job_history (dag_execution_id);
