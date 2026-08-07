-- V3.0.8 — 为 metadata_column 增加源状态字段，用于标记已删除的字段

ALTER TABLE metadata_column
    ADD COLUMN IF NOT EXISTS source_status VARCHAR(20) NOT NULL DEFAULT 'ONLINE';

COMMENT ON COLUMN metadata_column.source_status IS '源状态：ONLINE 在线，OFFLINE 源已删除';

UPDATE metadata_column
SET source_status = 'ONLINE'
WHERE source_status IS NULL;
