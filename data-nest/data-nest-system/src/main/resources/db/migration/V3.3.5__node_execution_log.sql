-- ============================================
-- V3.3.5__node_execution_log.sql
-- Sprint 4：DAG 节点执行日志行表
-- ============================================

CREATE TABLE IF NOT EXISTS node_execution_log
(
    id
    BIGSERIAL
    PRIMARY
    KEY,
    execution_id
    BIGINT
    NOT
    NULL,
    node_id
    VARCHAR
(
    64
) NOT NULL,
    level VARCHAR
(
    16
) NOT NULL CHECK
(
    level
    IN
(
    'INFO',
    'WARN',
    'ERROR'
)),
    message TEXT NOT NULL,
    line_num INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
    );

CREATE INDEX IF NOT EXISTS idx_node_execution_log_en ON node_execution_log (execution_id, node_id);

COMMENT
ON TABLE node_execution_log IS 'DAG 节点执行日志';
COMMENT
ON COLUMN node_execution_log.level IS '日志级别：INFO / WARN / ERROR';
COMMENT
ON COLUMN node_execution_log.line_num IS '行号，用于排序';
