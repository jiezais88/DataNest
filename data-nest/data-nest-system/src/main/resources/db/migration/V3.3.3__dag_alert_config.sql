-- ============================================
-- V3.3.3__dag_alert_config.sql
-- Sprint 4：全局 DAG 告警配置表
-- ============================================

CREATE TABLE IF NOT EXISTS dag_alert_config
(
    id
    BIGSERIAL
    PRIMARY
    KEY,
    enabled
    SMALLINT
    NOT
    NULL
    DEFAULT
    0,
    recipients
    VARCHAR
(
    1000
),
    trigger_conditions VARCHAR
(
    255
), -- JSON 数组字符串，如 ["FAILURE","TIMEOUT"]
    timeout_minutes INT NOT NULL DEFAULT 30,
    created_by BIGINT,
    updated_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
    );

COMMENT
ON TABLE dag_alert_config IS '全局 DAG 告警配置';
COMMENT
ON COLUMN dag_alert_config.enabled IS '是否启用：1 启用，0 关闭';
COMMENT
ON COLUMN dag_alert_config.recipients IS '收件人邮箱，分号分隔';
COMMENT
ON COLUMN dag_alert_config.trigger_conditions IS '触发条件 JSON 数组：FAILURE / TIMEOUT / SUCCESS';
COMMENT
ON COLUMN dag_alert_config.timeout_minutes IS '节点超时阈值（分钟）';
