-- ============================================
-- V3.8.1__alert_history_quality_batch_id.sql
-- Sprint 6 UX：质量批次↔告警对应——alert_history 增加 quality_batch_id 列，
-- 质量对象（QUALITY）告警落库时关联批次，供质量检查批次详情反查告警记录。
-- 历史告警该列为 NULL（表示未关联批次），不破坏既有告警查询。
-- 注意：本脚本采用紧凑单行 SQL 写法，规避格式化工具拆行导致 checksum 不匹配。
-- ============================================

ALTER TABLE alert_history ADD COLUMN IF NOT EXISTS quality_batch_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_alert_history_quality_batch ON alert_history (quality_batch_id);

COMMENT ON COLUMN alert_history.quality_batch_id IS '关联的质量检查批次 ID（质量对象告警落库时写入，非质量告警为 NULL）';
