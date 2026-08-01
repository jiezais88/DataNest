-- ============================================
-- V3.3.4__dag_alert_history.sql
-- Sprint 4：DAG 告警发送记录（防重发）
-- ============================================

CREATE TABLE IF NOT EXISTS dag_alert_history
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
), -- 超时告警必填，DAG 级失败告警可为空
    alert_type VARCHAR
(
    16
) NOT NULL CHECK
(
    alert_type
    IN
(
    'FAILURE',
    'TIMEOUT',
    'SUCCESS'
)),
    recipients VARCHAR
(
    1000
),
    sent_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_dag_alert_history_en_type UNIQUE
(
    execution_id,
    node_id,
    alert_type
)
    );

CREATE INDEX IF NOT EXISTS idx_dag_alert_history_execution ON dag_alert_history (execution_id);

COMMENT
ON TABLE dag_alert_history IS 'DAG 告警发送记录（防重发）';
COMMENT
ON COLUMN dag_alert_history.alert_type IS '告警类型：FAILURE / TIMEOUT / SUCCESS';
