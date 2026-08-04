-- ============================================
-- V3.6.0__sprint6_quality_rule_template.sql
-- Sprint 6：规则模板库（D-D3 决策）
-- 说明：规则模板 = 校验逻辑模板（类型 + SQL 片段 + 字段/阈值占位符），
--       任务内「选择模板 + 多表」批量生成 quality_rule 实例，避免重复配置。
-- ============================================

CREATE TABLE IF NOT EXISTS quality_rule_template
(
    id
    BIGSERIAL
    PRIMARY
    KEY,
    name
    VARCHAR
(
    100
) NOT NULL,
    type VARCHAR
(
    20
) NOT NULL CHECK
(
    type
    IN
(
    'COMPLETENESS',
    'UNIQUENESS',
    'RANGE',
    'CUSTOM_SQL'
)),
    description VARCHAR
(
    500
),
    sql_template TEXT, -- 校验 SQL 模板，占位符 {table}/{column}/{min}/{max} 等
    result_metric VARCHAR
(
    50
), -- 结果指标名，如 null_rate / duplicate_count / out_of_range_rate
    builtin SMALLINT NOT NULL DEFAULT 0, -- 是否内置：1 内置，0 自定义
    enabled SMALLINT NOT NULL DEFAULT 1,
    created_by BIGINT,
    updated_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_quality_rule_template_name UNIQUE
(
    name
)
    );

CREATE INDEX IF NOT EXISTS idx_quality_rule_template_type ON quality_rule_template (type);

COMMENT
ON TABLE quality_rule_template IS '质量规则模板库（D3）';
COMMENT
ON COLUMN quality_rule_template.name IS '模板名称（唯一）';
COMMENT
ON COLUMN quality_rule_template.type IS '模板类型：COMPLETENESS / UNIQUENESS / RANGE / CUSTOM_SQL';
COMMENT
ON COLUMN quality_rule_template.description IS '模板说明';
COMMENT
ON COLUMN quality_rule_template.sql_template IS '校验 SQL 模板，占位符 {table}/{column}/{min}/{max} 等';
COMMENT
ON COLUMN quality_rule_template.result_metric IS '结果指标名，如 null_rate / duplicate_count / out_of_range_rate';
COMMENT
ON COLUMN quality_rule_template.builtin IS '是否内置：1 内置，0 自定义';
COMMENT
ON COLUMN quality_rule_template.enabled IS '是否启用：1 启用，0 停用';

-- ============================================
-- 内置四类模板种子数据
-- 校验 SQL 均采用聚合写法返回单行结果，规避 GenericSqlExecutor 的
-- 200 行截断与 5 秒查询超时（PRD §6.2.2 实现注记 / 技术文档 §4.1）。
-- ============================================

INSERT INTO quality_rule_template (name, type, description, sql_template, result_metric, builtin, enabled, created_by,
                                   updated_by)
SELECT '完整性检查',
       'COMPLETENESS',
       '统计指定字段（或整表）的空值行占比，判定数据完整性。不填字段时按整表口径：存在至少一个空值字段的行数 ÷ 总行数。',
       'SELECT (COUNT(*) - COUNT({column})) * 1.0 / COUNT(*) AS null_rate FROM {table}',
       'null_rate',
       1,
       1,
       NULL,
       NULL WHERE NOT EXISTS (SELECT 1 FROM quality_rule_template WHERE name = '完整性检查');

INSERT INTO quality_rule_template (name, type, description, sql_template, result_metric, builtin, enabled, created_by,
                                   updated_by)
SELECT '唯一性检查',
       'UNIQUENESS',
       '统计指定字段的重复行数（COUNT - COUNT(DISTINCT)），判定主键/唯一键约束。',
       'SELECT COUNT(*) - COUNT(DISTINCT {column}) AS duplicate_count FROM {table}',
       'duplicate_count',
       1,
       1,
       NULL,
       NULL WHERE NOT EXISTS (SELECT 1 FROM quality_rule_template WHERE name = '唯一性检查');

INSERT INTO quality_rule_template (name, type, description, sql_template, result_metric, builtin, enabled, created_by,
                                   updated_by)
SELECT '值域范围检查',
       'RANGE',
       '统计指定字段值超出 [min, max] 区间的行占比，判定字段取值是否在合理范围。',
       'SELECT COUNT(*) AS total, SUM(CASE WHEN {column} < {min} OR {column} > {max} THEN 1 ELSE 0 END) AS out_of_range FROM {table}',
       'out_of_range_rate',
       1,
       1,
       NULL,
       NULL WHERE NOT EXISTS (SELECT 1 FROM quality_rule_template WHERE name = '值域范围检查');

INSERT INTO quality_rule_template (name, type, description, sql_template, result_metric, builtin, enabled, created_by,
                                   updated_by)
SELECT '自定义 SQL',
       'CUSTOM_SQL',
       '用户自定义返回单个统计值的校验 SQL，执行结果作为规则结果值进行分级判定。',
       NULL,
       'custom_value',
       1,
       1,
       NULL,
       NULL WHERE NOT EXISTS (SELECT 1 FROM quality_rule_template WHERE name = '自定义 SQL');
