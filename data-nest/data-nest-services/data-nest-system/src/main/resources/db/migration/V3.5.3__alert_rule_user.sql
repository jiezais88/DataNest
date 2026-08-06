-- ============================================
-- V3.5.3__alert_rule_user.sql
-- Sprint 5：告警规则接收用户关联表（多对多）
-- ============================================

CREATE TABLE IF NOT EXISTS alert_rule_user (
    id BIGSERIAL PRIMARY KEY,
    alert_rule_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    CONSTRAINT uk_alert_rule_user UNIQUE (alert_rule_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_alert_rule_user_rule_id ON alert_rule_user (alert_rule_id);
CREATE INDEX IF NOT EXISTS idx_alert_rule_user_user_id ON alert_rule_user (user_id);

COMMENT ON TABLE alert_rule_user IS '告警规则接收用户关联表';
COMMENT ON COLUMN alert_rule_user.alert_rule_id IS '告警规则 ID';
COMMENT ON COLUMN alert_rule_user.user_id IS '平台用户 ID（发送时反查 sys_user.email）';
