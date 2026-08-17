-- ============================================
-- V1.7.1__seed_quality_rule_template.sql
-- Sprint 12 补：质量规则内置模板种子（全新部署种子）
-- 背景：V1.0.0 baseline 为 pg_dump --schema-only（无数据），4 个内置模板（源自旧共享库
--       migration-legacy V3.6.0）在全新库上缺失，质量规则无法基于模板创建。幂等补齐。
-- 说明：校验 SQL 采用聚合写法返回单行结果，规避 GenericSqlExecutor 的 200 行截断与 5 秒超时。
-- ============================================
INSERT INTO quality_rule_template (name, type, description, sql_template, result_metric, builtin, enabled, created_by, updated_by) SELECT '完整性检查', 'COMPLETENESS', '统计指定字段（或整表）的空值行占比，判定数据完整性。不填字段时按整表口径：存在至少一个空值字段的行数 ÷ 总行数。', 'SELECT (COUNT(*) - COUNT({column})) * 1.0 / COUNT(*) AS null_rate FROM {table}', 'null_rate', 1, 1, NULL, NULL WHERE NOT EXISTS (SELECT 1 FROM quality_rule_template WHERE name = '完整性检查');
INSERT INTO quality_rule_template (name, type, description, sql_template, result_metric, builtin, enabled, created_by, updated_by) SELECT '唯一性检查', 'UNIQUENESS', '统计指定字段的重复行数（COUNT - COUNT(DISTINCT)），判定主键/唯一键约束。', 'SELECT COUNT(*) - COUNT(DISTINCT {column}) AS duplicate_count FROM {table}', 'duplicate_count', 1, 1, NULL, NULL WHERE NOT EXISTS (SELECT 1 FROM quality_rule_template WHERE name = '唯一性检查');
INSERT INTO quality_rule_template (name, type, description, sql_template, result_metric, builtin, enabled, created_by, updated_by) SELECT '值域范围检查', 'RANGE', '统计指定字段值超出 [min, max] 区间的行占比，判定字段取值是否在合理范围。', 'SELECT COUNT(*) AS total, SUM(CASE WHEN {column} < {min} OR {column} > {max} THEN 1 ELSE 0 END) AS out_of_range FROM {table}', 'out_of_range_rate', 1, 1, NULL, NULL WHERE NOT EXISTS (SELECT 1 FROM quality_rule_template WHERE name = '值域范围检查');
INSERT INTO quality_rule_template (name, type, description, sql_template, result_metric, builtin, enabled, created_by, updated_by) SELECT '自定义 SQL', 'CUSTOM_SQL', '用户自定义返回单个统计值的校验 SQL，执行结果作为规则结果值进行分级判定。', NULL, 'custom_value', 1, 1, NULL, NULL WHERE NOT EXISTS (SELECT 1 FROM quality_rule_template WHERE name = '自定义 SQL');
