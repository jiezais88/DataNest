ALTER TABLE compliance_check_result
    ADD COLUMN IF NOT EXISTS applicable_standards JSONB DEFAULT NULL;

COMMENT ON COLUMN compliance_check_result.applicable_standards IS '本次检查涉及的相关规范列表（JSONB），用于结果展示';
