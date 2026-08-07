-- ============================================
-- V3.6.2__sprint6_quality_rule_range_bounds.sql
-- Sprint 6：quality_rule 增加 RANGE 值域边界字段
-- 说明：RANGE（值域范围检查）的 {min}/{max} 占位符来源为「值域边界」，
--       与 warning_threshold / severe_threshold（结果分级阈值）语义不同，需独立存储。
--       现有 warning/severe 继续作为结果分级（out_of_range_rate ≥ warning → 警告，≥ severe → 严重）。
-- ============================================

ALTER TABLE quality_rule
    ADD COLUMN IF NOT EXISTS range_min DECIMAL(20, 6),
    ADD COLUMN IF NOT EXISTS range_max DECIMAL(20, 6);

COMMENT ON COLUMN quality_rule.range_min IS '值域下界（RANGE 类型专用，SQL 模板 {min} 占位符来源；其余类型为 NULL）';
COMMENT ON COLUMN quality_rule.range_max IS '值域上界（RANGE 类型专用，SQL 模板 {max} 占位符来源；其余类型为 NULL）';
