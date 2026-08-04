-- ============================================
-- V3.3.6__lineage_record.sql
-- Sprint 4：表级血缘记录表
-- ============================================

CREATE TABLE IF NOT EXISTS lineage_record (
    id BIGSERIAL PRIMARY KEY,
    source_table VARCHAR(500),
    target_table VARCHAR(500) NOT NULL,
    dag_id BIGINT,
    dag_name VARCHAR(255),
    node_id VARCHAR(64),
    node_name VARCHAR(255),
    execution_id BIGINT,
    lineage_type VARCHAR(16) NOT NULL DEFAULT 'SQL',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_lineage_target ON lineage_record (target_table);
CREATE INDEX IF NOT EXISTS idx_lineage_dag ON lineage_record (dag_id);

COMMENT ON TABLE lineage_record IS '表级血缘记录';
COMMENT ON COLUMN lineage_record.source_table IS '源表名';
COMMENT ON COLUMN lineage_record.target_table IS '目标表名';
COMMENT ON COLUMN lineage_record.lineage_type IS '血缘类型：SQL / SYNC / PYTHON';
