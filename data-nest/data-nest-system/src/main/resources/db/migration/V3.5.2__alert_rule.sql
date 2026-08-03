-- ============================================
-- V3.5.2__alert_rule.sql
-- Sprint 5：通用告警规则表（DAG / SYNC_JOB / COLLECT_TASK）
-- ============================================

CREATE TABLE IF NOT EXISTS alert_rule
(
    id
    BIGSERIAL
    PRIMARY
    KEY,
    object_type
    VARCHAR
(
    32
) NOT NULL CHECK
(
    object_type
    IN
(
    'DAG',
    'SYNC_JOB',
    'COLLECT_TASK'
)),
    object_id BIGINT NOT NULL,
    object_name VARCHAR
(
    255
),
    trigger_conditions VARCHAR
(
    255
), -- JSON 数组字符串，如 ["FAILURE","TIMEOUT"]
    timeout_minutes INT NOT NULL DEFAULT 30,
    enabled SMALLINT NOT NULL DEFAULT 1,
    created_by BIGINT,
    updated_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_alert_rule_object UNIQUE
(
    object_type,
    object_id
)
    );

CREATE INDEX IF NOT EXISTS idx_alert_rule_object_type ON alert_rule (object_type);

COMMENT
ON TABLE alert_rule IS '通用告警规则表';
COMMENT
ON COLUMN alert_rule.object_type IS '对象类型：DAG / SYNC_JOB / COLLECT_TASK';
COMMENT
ON COLUMN alert_rule.object_id IS '对象 ID（dag.id / sync_job.id / collect_task.id）';
COMMENT
ON COLUMN alert_rule.object_name IS '对象名称冗余，便于列表展示';
COMMENT
ON COLUMN alert_rule.trigger_conditions IS '触发条件 JSON 数组：FAILURE / TIMEOUT / SUCCESS';
COMMENT
ON COLUMN alert_rule.timeout_minutes IS '超时阈值（分钟），默认 30';
COMMENT
ON COLUMN alert_rule.enabled IS '是否启用：1 启用，0 关闭';
