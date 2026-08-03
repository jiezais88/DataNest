-- ============================================
-- V3.5.4__alert_history.sql
-- Sprint 5：告警发送历史
-- ============================================

CREATE TABLE IF NOT EXISTS alert_history
(
    id
    BIGSERIAL
    PRIMARY
    KEY,
    alert_rule_id
    BIGINT,
    object_type
    VARCHAR
(
    32
) NOT NULL,
    object_id BIGINT NOT NULL,
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
    2000
),
    sent_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
    );

CREATE INDEX IF NOT EXISTS idx_alert_history_object ON alert_history (object_type, object_id);
CREATE INDEX IF NOT EXISTS idx_alert_history_sent_at ON alert_history (sent_at);

COMMENT
ON TABLE alert_history IS '告警发送历史';
COMMENT
ON COLUMN alert_history.alert_rule_id IS '告警规则 ID（可空，规则删除后保留历史）';
COMMENT
ON COLUMN alert_history.object_type IS '对象类型：DAG / SYNC_JOB / COLLECT_TASK';
COMMENT
ON COLUMN alert_history.object_id IS '对象 ID';
COMMENT
ON COLUMN alert_history.alert_type IS '告警类型：FAILURE / TIMEOUT / SUCCESS';
COMMENT
ON COLUMN alert_history.recipients IS '实际发送的邮箱列表，分号分隔';
