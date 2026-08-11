-- ============================================
-- V1.5.0__sprint8_quality_report.sql
-- Sprint 8 F3（DG-07 完整版）：quality_score_history 表评分快照历史
--   写入口：ScoreCalculator 批次收尾 upsert quality_score 后追加一条快照；
--   存量补算：不做迁移内补算，由 POST /quality/report/backfill-score-history 手工触发（2026-08-11 用户确认）。
-- 注意：紧凑单行风格；主键应用侧雪花（IdType.ASSIGN_ID）不建序列；created_at 由代码写入。
-- ============================================

CREATE TABLE IF NOT EXISTS quality_score_history (
    id bigint NOT NULL,
    table_id bigint NOT NULL,
    table_name character varying(255),
    datasource_id bigint,
    score numeric(5,2),
    health_level character varying(20),
    pass_rules integer DEFAULT 0 NOT NULL,
    warning_rules integer DEFAULT 0 NOT NULL,
    severe_rules integer DEFAULT 0 NOT NULL,
    checked_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT quality_score_history_pkey PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_quality_score_history_table ON quality_score_history (table_id, checked_at);
CREATE INDEX IF NOT EXISTS idx_quality_score_history_checked ON quality_score_history (checked_at);
COMMENT ON TABLE quality_score_history IS '表评分快照历史（Sprint 8 F3 DG-07，批次结束写一条，评分趋势图数据源）';
COMMENT ON COLUMN quality_score_history.table_id IS '目标表 metadata_table.id';
COMMENT ON COLUMN quality_score_history.table_name IS '库名.表名快照';
COMMENT ON COLUMN quality_score_history.datasource_id IS '数据源（-1 = 内置 Doris）';
COMMENT ON COLUMN quality_score_history.score IS '0-100 评分';
COMMENT ON COLUMN quality_score_history.health_level IS '健康度：EXCELLENT/GOOD/WARNING/BAD';
COMMENT ON COLUMN quality_score_history.pass_rules IS '通过规则数';
COMMENT ON COLUMN quality_score_history.warning_rules IS '警告规则数';
COMMENT ON COLUMN quality_score_history.severe_rules IS '严重规则数（UNAVAILABLE 不计入三档）';
COMMENT ON COLUMN quality_score_history.checked_at IS '检查批次结束时间（趋势图 X 轴）';
COMMENT ON COLUMN quality_score_history.created_at IS '记录创建时间';
