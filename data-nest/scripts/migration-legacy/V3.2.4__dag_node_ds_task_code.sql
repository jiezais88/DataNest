-- ============================================
-- V3.2.4__dag_node_ds_task_code.sql
-- Sprint 3 P2：持久化 DS task code，避免节点重命名后 DS task code 变化
-- ============================================

ALTER TABLE dag_node
    ADD COLUMN IF NOT EXISTS ds_task_code BIGINT DEFAULT NULL;

COMMENT ON COLUMN dag_node.ds_task_code IS 'DolphinScheduler 任务定义 code（持久化，节点重命名后保持不变）';

CREATE INDEX IF NOT EXISTS idx_dag_node_ds_task_code ON dag_node (ds_task_code);
