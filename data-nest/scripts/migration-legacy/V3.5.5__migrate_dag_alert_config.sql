-- ============================================
-- V3.5.5__migrate_dag_alert_config.sql
-- Sprint 5：将 Sprint 4 的按 DAG 告警配置迁移到 alert_rule
-- 说明：
--   - 仅迁移 dag_alert_config.dag_id 非空的按 DAG 记录；全局默认配置保留兼容读取，不迁移。
--   - 收件人邮箱通过反查 sys_user.email 关联为 alert_rule_user；
--     若邮箱找不到平台用户，则忽略该收件人（历史规则收件人无法保证全部命中）。
--   - 幂等：alert_rule 有 (object_type, object_id) 唯一约束，重复执行不会产生重复规则；
--     但 alert_rule_user 可能因规则已存在而重复插入，用 NOT EXISTS 去重。
-- ============================================

-- 1. 迁移规则主体（仅按 DAG 记录；object_name 冗余取 dag.name）
INSERT INTO alert_rule (object_type, object_id, object_name, trigger_conditions, timeout_minutes, enabled,
                        created_by, updated_by, created_at, updated_at)
SELECT 'DAG',
       dac.dag_id,
       d.name,
       dac.trigger_conditions,
       dac.timeout_minutes,
       dac.enabled,
       dac.created_by,
       dac.updated_by,
       dac.created_at,
       dac.updated_at
FROM dag_alert_config dac
         LEFT JOIN dag d ON d.id = dac.dag_id
WHERE dac.dag_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM alert_rule r WHERE r.object_type = 'DAG' AND r.object_id = dac.dag_id);

-- 2. 迁移接收人（邮箱按分号/逗号等分隔符拆分反查 user_id，找不到的用户跳过）
INSERT INTO alert_rule_user (alert_rule_id, user_id)
SELECT r.id, u.id
FROM alert_rule r
         JOIN dag_alert_config dac
              ON dac.dag_id = r.object_id AND r.object_type = 'DAG' AND dac.dag_id IS NOT NULL
         JOIN LATERAL unnest(string_to_array(regexp_replace(dac.recipients, '[;；,，]', ';', 'g'), ';')) AS rcpt
         ON true
         JOIN sys_user u ON lower (trim (u.email)) = lower (trim (rcpt))
WHERE dac.recipients IS NOT NULL
  AND dac.recipients <> ''
  AND NOT EXISTS (SELECT 1
                  FROM alert_rule_user aru
                  WHERE aru.alert_rule_id = r.id
                    AND aru.user_id = u.id);
