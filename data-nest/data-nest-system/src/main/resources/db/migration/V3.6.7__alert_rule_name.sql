-- ============================================
-- V3.6.7__alert_rule_name.sql
-- Sprint 6：告警规则名称（用户自定义规则名，便于告警中心区分多条规则）
-- alert_rule.name：必填，同一 object_type 下唯一
-- alert_history.rule_name：发送历史冗余规则名（规则删除后仍保留，审计友好）
-- 历史数据回填：name 取原 object_name 兜底；rule_name 置空，前端查询时联查 alert_rule 回显。
-- 注意：本脚本采用紧凑单行 SQL 写法，规避被格式化工具拆行导致的问题。
-- ============================================

ALTER TABLE alert_rule ADD COLUMN name VARCHAR(255);
UPDATE alert_rule SET name = COALESCE(object_name, '未命名规则') WHERE name IS NULL;
UPDATE alert_rule ar SET name = ar.name || '-' || t.rn FROM (SELECT id, ROW_NUMBER() OVER (PARTITION BY object_type, name ORDER BY id) AS rn FROM alert_rule) t WHERE ar.id = t.id AND t.rn > 1;
ALTER TABLE alert_rule ALTER COLUMN name SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_alert_rule_name ON alert_rule (object_type, name);
ALTER TABLE alert_history ADD COLUMN rule_name VARCHAR(255);
