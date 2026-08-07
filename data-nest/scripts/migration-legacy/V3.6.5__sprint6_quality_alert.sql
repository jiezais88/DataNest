-- ============================================
-- V3.6.5__sprint6_quality_alert.sql
-- Sprint 6：质量检查分级判定 + 分级邮件告警
-- 说明：
--   1) quality_check_detail 增加 result_level（PASS/WARNING/SEVERE/UNAVAILABLE），分级判定落库
--   2) quality_check_batch 增加 alert_sent（合并告警幂等标记，防止重发）
-- 注意：本脚本采用紧凑单行 SQL 写法，规避被格式化工具拆行导致的问题。
-- ============================================

-- 1) quality_check_detail 增加 result_level 分级列
ALTER TABLE quality_check_detail ADD COLUMN IF NOT EXISTS result_level VARCHAR(20);
COMMENT ON COLUMN quality_check_detail.result_level IS '分级判定：PASS 通过 / WARNING 警告 / SEVERE 严重 / UNAVAILABLE 不可用（执行失败）';

-- 2) quality_check_batch 增加 alert_sent 幂等标记
ALTER TABLE quality_check_batch ADD COLUMN IF NOT EXISTS alert_sent SMALLINT NOT NULL DEFAULT 0;
COMMENT ON COLUMN quality_check_batch.alert_sent IS '合并告警是否已发送：1 已发送，0 未发送（幂等防重发）';
