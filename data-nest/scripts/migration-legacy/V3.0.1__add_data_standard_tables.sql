-- ============================================
-- V3.0.1__add_data_standard_tables.sql
-- 数据标准：命名规范、字段类型标准、合规检查结果
-- ============================================

-- --------------------------------------------
-- 命名规范
-- --------------------------------------------
CREATE TABLE IF NOT EXISTS naming_standard (
    id BIGINT NOT NULL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    applies_to VARCHAR(20) NOT NULL,
    rule_type VARCHAR(20) NOT NULL,
    rule_value VARCHAR(255) NOT NULL,
    target_standard_id BIGINT DEFAULT NULL,
    priority INT NOT NULL DEFAULT 0,
    enabled SMALLINT NOT NULL DEFAULT 1,
    description TEXT DEFAULT NULL,
    created_by BIGINT DEFAULT NULL,
    updated_by BIGINT DEFAULT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_naming_standard_name ON naming_standard(name);
CREATE INDEX IF NOT EXISTS idx_naming_standard_applies_to ON naming_standard(applies_to);
CREATE INDEX IF NOT EXISTS idx_naming_standard_enabled ON naming_standard(enabled);
CREATE INDEX IF NOT EXISTS idx_naming_standard_target_standard_id ON naming_standard(target_standard_id);

COMMENT ON TABLE naming_standard IS '命名规范';
COMMENT ON COLUMN naming_standard.id IS '主键ID';
COMMENT ON COLUMN naming_standard.name IS '规范名称';
COMMENT ON COLUMN naming_standard.applies_to IS '适用对象：TABLE 表名，COLUMN 字段名';
COMMENT ON COLUMN naming_standard.rule_type IS '规则类型：PREFIX 前缀，SUFFIX 后缀，REGEX 正则';
COMMENT ON COLUMN naming_standard.rule_value IS '规则值';
COMMENT ON COLUMN naming_standard.target_standard_id IS '关联的字段类型标准ID';
COMMENT ON COLUMN naming_standard.priority IS '优先级，数字越大越优先';
COMMENT ON COLUMN naming_standard.enabled IS '是否启用（0-禁用 1-启用）';
COMMENT ON COLUMN naming_standard.description IS '描述';
COMMENT ON COLUMN naming_standard.created_by IS '创建人ID';
COMMENT ON COLUMN naming_standard.updated_by IS '更新人ID';
COMMENT ON COLUMN naming_standard.created_at IS '创建时间';
COMMENT ON COLUMN naming_standard.updated_at IS '更新时间';

-- --------------------------------------------
-- 字段类型标准
-- --------------------------------------------
CREATE TABLE IF NOT EXISTS field_type_standard (
    id BIGINT NOT NULL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    category VARCHAR(50) DEFAULT NULL,
    allowed_types JSONB NOT NULL DEFAULT '[]'::jsonb,
    description TEXT DEFAULT NULL,
    created_by BIGINT DEFAULT NULL,
    updated_by BIGINT DEFAULT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_field_type_standard_name ON field_type_standard(name);

COMMENT ON TABLE field_type_standard IS '字段类型标准';
COMMENT ON COLUMN field_type_standard.id IS '主键ID';
COMMENT ON COLUMN field_type_standard.name IS '标准名称';
COMMENT ON COLUMN field_type_standard.category IS '分类（如：数值、字符串、时间）';
COMMENT ON COLUMN field_type_standard.allowed_types IS '允许的字段类型数组';
COMMENT ON COLUMN field_type_standard.description IS '描述';
COMMENT ON COLUMN field_type_standard.created_by IS '创建人ID';
COMMENT ON COLUMN field_type_standard.updated_by IS '更新人ID';
COMMENT ON COLUMN field_type_standard.created_at IS '创建时间';
COMMENT ON COLUMN field_type_standard.updated_at IS '更新时间';

-- --------------------------------------------
-- 合规检查结果
-- --------------------------------------------
CREATE TABLE IF NOT EXISTS compliance_check_result (
    id BIGINT NOT NULL PRIMARY KEY,
    standard_id BIGINT NOT NULL,
    object_type VARCHAR(20) NOT NULL,
    table_id BIGINT DEFAULT NULL,
    column_id BIGINT DEFAULT NULL,
    object_name VARCHAR(255) NOT NULL,
    actual_value VARCHAR(255) DEFAULT NULL,
    expected_value VARCHAR(255) DEFAULT NULL,
    is_compliant SMALLINT NOT NULL DEFAULT 0,
    checked_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_compliance_check_result_standard_id ON compliance_check_result(standard_id);
CREATE INDEX IF NOT EXISTS idx_compliance_check_result_table_id ON compliance_check_result(table_id);
CREATE INDEX IF NOT EXISTS idx_compliance_check_result_column_id ON compliance_check_result(column_id);
CREATE INDEX IF NOT EXISTS idx_compliance_check_result_object_type ON compliance_check_result(object_type);

COMMENT ON TABLE compliance_check_result IS '合规检查结果';
COMMENT ON COLUMN compliance_check_result.id IS '主键ID';
COMMENT ON COLUMN compliance_check_result.standard_id IS '命中的命名规范ID';
COMMENT ON COLUMN compliance_check_result.object_type IS '对象类型：TABLE 表，COLUMN 字段';
COMMENT ON COLUMN compliance_check_result.table_id IS '关联元数据表ID';
COMMENT ON COLUMN compliance_check_result.column_id IS '关联元数据字段ID';
COMMENT ON COLUMN compliance_check_result.object_name IS '对象名称（表名或字段名）';
COMMENT ON COLUMN compliance_check_result.actual_value IS '实际值（字段类型等）';
COMMENT ON COLUMN compliance_check_result.expected_value IS '期望值（允许的字段类型等）';
COMMENT ON COLUMN compliance_check_result.is_compliant IS '是否合规（0-不合规 1-合规）';
COMMENT ON COLUMN compliance_check_result.checked_at IS '检查时间';
