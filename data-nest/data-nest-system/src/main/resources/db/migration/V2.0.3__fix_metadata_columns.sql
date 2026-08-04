-- V2.0.3 — 修正元数据表与实体字段差异

-- metadata_table 增加最近一次采集历史记录 ID
ALTER TABLE metadata_table
    ADD COLUMN IF NOT EXISTS last_collect_history_id BIGINT DEFAULT NULL;
COMMENT ON COLUMN metadata_table.last_collect_history_id IS '最近一次采集历史记录ID';

-- metadata_column 重命名 is_nullable -> nullable，并增加最近一次采集历史记录 ID
ALTER TABLE metadata_column RENAME COLUMN is_nullable TO nullable;
ALTER TABLE metadata_column
    ADD COLUMN IF NOT EXISTS last_collect_history_id BIGINT DEFAULT NULL;
COMMENT ON COLUMN metadata_column.nullable IS '是否允许为空';
COMMENT ON COLUMN metadata_column.last_collect_history_id IS '最近一次采集历史记录ID';
