-- Sprint 7 F4（DG-10）：质量规则新增 PYTHON 类型（模板 CHECK 约束重建 + python_template/python_script 字段）
ALTER TABLE quality_rule_template DROP CONSTRAINT IF EXISTS quality_rule_template_type_check;
ALTER TABLE quality_rule_template ADD CONSTRAINT quality_rule_template_type_check CHECK (type IN ('COMPLETENESS','UNIQUENESS','RANGE','CUSTOM_SQL','PYTHON'));
ALTER TABLE quality_rule_template ADD COLUMN IF NOT EXISTS python_template TEXT;
ALTER TABLE quality_rule ADD COLUMN IF NOT EXISTS python_script TEXT;
COMMENT ON COLUMN public.quality_rule_template.python_template IS 'Python 模板脚本（def check(df) 形式，PYTHON 类型模板用，Sprint 7 DG-10）';
COMMENT ON COLUMN public.quality_rule_template.type IS '模板类型：COMPLETENESS / UNIQUENESS / RANGE / CUSTOM_SQL / PYTHON';
COMMENT ON COLUMN public.quality_rule.python_script IS 'Python 脚本（def check(df) 返回 dict，PYTHON 类型规则落库，Sprint 7 DG-10）';
COMMENT ON COLUMN public.quality_rule.type IS '规则类型：COMPLETENESS / UNIQUENESS / RANGE / CUSTOM_SQL / PYTHON';
