-- ============================================
-- V3.6.6__alert_rule_quality_object_type.sql
-- Sprint 6：告警规则对象类型扩展 QUALITY（质量任务分级告警）
-- alert_rule.object_type 原 CHECK 仅允许 DAG/SYNC_JOB/COLLECT_TASK，需放开 QUALITY。
-- 先 DROP 旧约束，再重建为含 QUALITY 的约束（保持枚举约束而非彻底移除）。
-- 注意：本脚本采用紧凑单行 SQL 写法，规避被格式化工具拆行导致的问题。
-- ============================================

ALTER TABLE alert_rule DROP CONSTRAINT IF EXISTS alert_rule_object_type_check;
ALTER TABLE alert_rule ADD CONSTRAINT alert_rule_object_type_check CHECK (object_type IN ('DAG', 'SYNC_JOB', 'COLLECT_TASK', 'QUALITY'));
