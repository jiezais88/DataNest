-- ============================================
-- V3.3.2__dag_version.sql
-- Sprint 4：DAG 版本快照表
-- ============================================

CREATE TABLE IF NOT EXISTS dag_version (
    id BIGSERIAL PRIMARY KEY,
    dag_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    snapshot TEXT NOT NULL,
    change_summary VARCHAR(500),
    created_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_dag_version UNIQUE (dag_id, version_no)
);

COMMENT ON TABLE dag_version IS 'DAG 版本快照';
COMMENT ON COLUMN dag_version.version_no IS '版本号：v1=1, v2=2...';
COMMENT ON COLUMN dag_version.snapshot IS '节点/边/参数 JSON 快照';
COMMENT ON COLUMN dag_version.change_summary IS '变更摘要';
