-- 合规检查结果按检查时间范围查询频繁，增加索引
CREATE INDEX IF NOT EXISTS idx_compliance_check_result_checked_at
    ON compliance_check_result (checked_at);
