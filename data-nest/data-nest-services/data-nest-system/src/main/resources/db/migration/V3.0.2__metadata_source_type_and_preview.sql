-- ============================================
-- V3.0.2__metadata_source_type_and_preview.sql
-- 元数据表/字段增加来源类型，数据源增加保存后自动采集标记
-- ============================================

ALTER TABLE metadata_table
    ADD COLUMN IF NOT EXISTS source_type VARCHAR(20) NOT NULL DEFAULT 'EXTERNAL';

ALTER TABLE metadata_column
    ADD COLUMN IF NOT EXISTS source_type VARCHAR(20) NOT NULL DEFAULT 'EXTERNAL';

ALTER TABLE datasource_connection
    ADD COLUMN IF NOT EXISTS auto_collect_on_save SMALLINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN metadata_table.source_type IS '元数据来源：BUILTIN_DORIS 内置Doris，EXTERNAL 外部数据源';
COMMENT ON COLUMN metadata_column.source_type IS '元数据来源：BUILTIN_DORIS 内置Doris，EXTERNAL 外部数据源';
COMMENT ON COLUMN datasource_connection.auto_collect_on_save IS '保存后是否自动采集元数据（0-否 1-是）';
