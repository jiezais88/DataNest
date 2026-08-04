-- ============================================
-- V3.3.7__metadata_table_source.sql
-- Sprint 4：元数据表新增 DAG 来源字段
-- 注意：metadata_table.source_type 已用于表示采集/内置数据源类型，不可复用。
-- ============================================

ALTER TABLE metadata_table
    ADD COLUMN IF NOT EXISTS task_source_type VARCHAR(32),
    ADD COLUMN IF NOT EXISTS source_dag_id BIGINT,
    ADD COLUMN IF NOT EXISTS source_dag_name VARCHAR(255),
    ADD COLUMN IF NOT EXISTS source_node_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS source_node_name VARCHAR(255);

COMMENT ON COLUMN metadata_table.task_source_type IS '任务来源类型：SQL / SYNC / PYTHON';
COMMENT ON COLUMN metadata_table.source_dag_id IS '来源 DAG ID';
COMMENT ON COLUMN metadata_table.source_dag_name IS '来源 DAG 名称';
COMMENT ON COLUMN metadata_table.source_node_id IS '来源节点 ID';
COMMENT ON COLUMN metadata_table.source_node_name IS '来源节点名称';
