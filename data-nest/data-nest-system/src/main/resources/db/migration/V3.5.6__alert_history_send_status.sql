-- ============================================
-- V3.5.6__alert_history_send_status.sql
-- Sprint 5：告警历史增加「发送状态」字段
-- 说明：发送成功 = SUCCESS；发送失败（未配置 JavaMailSender / 无收件人 / 异常）= FAILED。
-- 历史数据默认按 SUCCESS 处理（迁移前只有发送成功才会正常落库的存量较少，且无法回判，保守取 SUCCESS）。
-- ============================================

ALTER TABLE alert_history
    ADD COLUMN IF NOT EXISTS send_status VARCHAR(16) NOT NULL DEFAULT 'SUCCESS';

COMMENT ON COLUMN alert_history.send_status IS '邮件发送状态：SUCCESS 发送成功 / FAILED 发送失败';
