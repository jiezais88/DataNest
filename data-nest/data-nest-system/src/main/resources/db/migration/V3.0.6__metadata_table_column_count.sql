-- ============================================
-- V3.0.6__metadata_table_column_count.sql
-- 元数据表增加字段数统计
-- ============================================

ALTER TABLE metadata_table
    ADD COLUMN IF NOT EXISTS column_count INT NOT NULL DEFAULT 0;

COMMENT
ON COLUMN metadata_table.column_count IS '表字段数量';
