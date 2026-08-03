-- ============================================
-- V3.5.0__extend_lineage_record_column.sql
-- Sprint 5：lineage_record 扩展字段级血缘字段
-- ============================================

ALTER TABLE lineage_record
    ADD COLUMN IF NOT EXISTS source_column VARCHAR (255),
    ADD COLUMN IF NOT EXISTS target_column VARCHAR (255);

COMMENT
ON COLUMN lineage_record.source_column IS '源字段，字段级血缘时使用；表级血缘为 NULL';
COMMENT
ON COLUMN lineage_record.target_column IS '目标字段，字段级血缘时使用；表级血缘为 NULL';
