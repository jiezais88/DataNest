-- 修复 MyBatis Plus JacksonTypeHandler 与 PostgreSQL jsonb 列不兼容的问题
-- 将 applicable_standards 从 jsonb 改为 text，与 field_type_standard.allowed_types 等保持一致
ALTER TABLE compliance_check_result
    ALTER COLUMN applicable_standards TYPE TEXT USING applicable_standards::TEXT;

COMMENT ON COLUMN compliance_check_result.applicable_standards IS '本次检查涉及的相关规范列表（JSON），用于结果展示';
