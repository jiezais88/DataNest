-- ============================================
-- V3.7.0__quality_score_config.sql
-- Sprint 6 NG8：质量评分全局配置落表
-- 说明：扣分值/低分区阈值原为 Nacos 配置（ScoreCalculator @Value），
--       现改为可配置（「扣分配置」弹窗读写），落库单行配置，ScoreCalculator 优先读表。
-- 注意：本脚本采用紧凑单行 SQL 写法，规避格式化工具拆行导致 checksum 不匹配。
-- ============================================

CREATE TABLE IF NOT EXISTS quality_score_config (
    id BIGSERIAL PRIMARY KEY,
    warning_deduct INT NOT NULL DEFAULT 10,
    severe_deduct INT NOT NULL DEFAULT 30,
    bad_threshold INT NOT NULL DEFAULT 60,
    updated_by BIGINT,
    updated_at TIMESTAMP
);

INSERT INTO quality_score_config (warning_deduct, severe_deduct, bad_threshold)
SELECT 10, 30, 60
WHERE NOT EXISTS (SELECT 1 FROM quality_score_config);

COMMENT ON TABLE quality_score_config IS '质量评分全局配置（Sprint 6 NG8，单行配置）';
COMMENT ON COLUMN quality_score_config.warning_deduct IS '警告规则每权重扣分分值';
COMMENT ON COLUMN quality_score_config.severe_deduct IS '严重规则每权重扣分分值';
COMMENT ON COLUMN quality_score_config.bad_threshold IS '低分区阈值：评分 < 此值 → 健康度「差」；存在严重规则强制压至低分区';
COMMENT ON COLUMN quality_score_config.updated_by IS '最近修改人';
COMMENT ON COLUMN quality_score_config.updated_at IS '最近修改时间';
