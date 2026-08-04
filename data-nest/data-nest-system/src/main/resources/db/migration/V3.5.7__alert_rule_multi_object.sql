-- Sprint 5 补充：告警规则对象支持多选
-- 1. 新增 alert_rule_object 关联表
-- 2. 将 alert_rule.object_id 迁移到 alert_rule_object
-- 3. 删除 alert_rule.object_id 列及唯一约束

CREATE TABLE IF NOT EXISTS alert_rule_object (
    id BIGINT PRIMARY KEY,
    alert_rule_id BIGINT NOT NULL,
    object_type VARCHAR(32) NOT NULL,
    object_id BIGINT NOT NULL,
    object_name VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_alert_rule_object_rule FOREIGN KEY (alert_rule_id) REFERENCES alert_rule (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_alert_rule_object_rule_id ON alert_rule_object (alert_rule_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_alert_rule_object ON alert_rule_object (alert_rule_id, object_type, object_id);
CREATE INDEX IF NOT EXISTS idx_alert_rule_object_type_id ON alert_rule_object (object_type, object_id);

-- 迁移已有数据：将 alert_rule.object_id 写入 alert_rule_object
INSERT INTO alert_rule_object (id, alert_rule_id, object_type, object_id, object_name, created_at)
SELECT NEXTVAL('alert_rule_id_seq'),
       ar.id,
       ar.object_type,
       ar.object_id,
       ar.object_name,
       ar.created_at
FROM alert_rule ar
WHERE ar.object_id IS NOT NULL
  AND NOT EXISTS (SELECT 1
                  FROM alert_rule_object aro
                  WHERE aro.alert_rule_id = ar.id
                    AND aro.object_type = ar.object_type
                    AND aro.object_id = ar.object_id);

-- 删除 alert_rule.object_id 列及唯一约束
ALTER TABLE alert_rule
DROP COLUMN IF EXISTS object_id;

ALTER TABLE alert_rule
DROP CONSTRAINT IF EXISTS uk_alert_rule_object;
