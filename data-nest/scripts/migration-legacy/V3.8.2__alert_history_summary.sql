-- ============================================
-- V3.8.2__alert_history_summary.sql
-- Sprint 6 UX：质量批次告警「一个批次一条告警记录」——alert_history 增加 summary 列，
-- 存储本次触发命中的多条质量规则明细（每行一条规则：等级 + 规则名 + 结果详情），
-- 供批次详情展示「触发了哪些规则」，同时保持一个批次只落一条告警记录。
-- 历史告警该列为 NULL，不破坏既有告警查询。
-- 注意：本脚本采用紧凑单行 SQL 写法，规避格式化工具拆行导致 checksum 不匹配。
-- ============================================

ALTER TABLE alert_history ADD COLUMN IF NOT EXISTS summary TEXT;

COMMENT ON COLUMN alert_history.summary IS '质量批次告警聚合明细（每行一条命中规则：等级 + 规则名 + 详情；非质量告警为 NULL）';
