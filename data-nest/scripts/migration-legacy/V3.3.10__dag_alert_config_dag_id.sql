-- ============================================
-- V3.3.10__dag_alert_config_dag_id.sql
-- Sprint 4 review：DAG 告警配置支持按 DAG 级别覆盖
-- ============================================

ALTER TABLE dag_alert_config
    ADD COLUMN IF NOT EXISTS dag_id BIGINT;

COMMENT ON COLUMN dag_alert_config.dag_id IS '所属 DAG ID；为空表示全局默认配置';

CREATE INDEX IF NOT EXISTS idx_dag_alert_config_dag_id
    ON dag_alert_config (dag_id);
