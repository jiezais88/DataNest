-- ============================================
-- V3.7.1__compliance_check_ignore.sql
-- Sprint6：标准合规检查结果支持按具体不合规项忽略/取消忽略
-- ============================================
ALTER TABLE compliance_check_result ADD COLUMN IF NOT EXISTS ignored SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE compliance_check_result ADD COLUMN IF NOT EXISTS ignored_at TIMESTAMP DEFAULT NULL;
ALTER TABLE compliance_check_result ADD COLUMN IF NOT EXISTS ignored_by BIGINT DEFAULT NULL;
CREATE INDEX IF NOT EXISTS idx_compliance_check_result_ignored ON compliance_check_result(ignored);
COMMENT ON COLUMN compliance_check_result.ignored IS '是否已忽略（0-未忽略 1-已忽略）';
COMMENT ON COLUMN compliance_check_result.ignored_at IS '忽略时间';
COMMENT ON COLUMN compliance_check_result.ignored_by IS '忽略操作人ID';
