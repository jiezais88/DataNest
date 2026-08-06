-- ============================================
-- V3.6.9__sprint6_quality_score.sql
-- Sprint 6 NG8：表级质量评分落表
-- 说明：每张表一行最新评分，供血缘图谱/元数据详情页批量展示。
--       updated_at 按审计约定不加 DB 默认值（对齐 V3.6.8），仅评分 upsert 时写入。
-- 注意：本脚本采用紧凑单行 SQL 写法，规避格式化工具拆行导致 checksum 不匹配。
-- ============================================

CREATE TABLE IF NOT EXISTS quality_score (
    id BIGSERIAL PRIMARY KEY,
    table_id BIGINT NOT NULL,
    table_name VARCHAR(255),
    datasource_id BIGINT,
    score DECIMAL(5,2),
    health_level VARCHAR(20),
    pass_rules INT NOT NULL DEFAULT 0,
    warning_rules INT NOT NULL DEFAULT 0,
    severe_rules INT NOT NULL DEFAULT 0,
    last_checked_at TIMESTAMP,
    updated_at TIMESTAMP,
    CONSTRAINT uk_quality_score_table UNIQUE (table_id)
);

CREATE INDEX IF NOT EXISTS idx_quality_score_datasource_id ON quality_score (datasource_id);

COMMENT ON TABLE quality_score IS '表级质量评分（Sprint 6 NG8，一张表一行最新评分）';
COMMENT ON COLUMN quality_score.table_id IS '目标表 metadata_table.id';
COMMENT ON COLUMN quality_score.table_name IS '库名.表名';
COMMENT ON COLUMN quality_score.datasource_id IS '数据源';
COMMENT ON COLUMN quality_score.score IS '0-100 分';
COMMENT ON COLUMN quality_score.health_level IS '健康度：EXCELLENT/GOOD/WARNING/BAD';
COMMENT ON COLUMN quality_score.pass_rules IS '最近一次通过规则数';
COMMENT ON COLUMN quality_score.warning_rules IS '最近一次警告规则数';
COMMENT ON COLUMN quality_score.severe_rules IS '最近一次严重规则数';
COMMENT ON COLUMN quality_score.last_checked_at IS '最近检查时间';
COMMENT ON COLUMN quality_score.updated_at IS '评分更新时间';
